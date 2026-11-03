use std::{
    collections::HashSet,
    sync::{Arc, Mutex},
    time::Duration,
};

use anyhow::Result;
use thiserror::Error;
use tokio::sync::Semaphore;
use url::Url;

use crate::{
    content::fetcher::{HtmlSanitizer, SafeHttpClient},
    db::{ContentType, Db, Feed, FeedRepo, FeedType, PollBatch, PollItemCandidate},
    feeds::{ArxivParser, RssParser, YouTubeParser},
};

const MAX_FEED_BYTES: usize = 10 * 1024 * 1024; // 10 MiB
const MAX_CONCURRENT_POLLS: usize = 8;
const MAX_ITEMS_PER_POLL: usize = 500;

#[derive(Debug, Error)]
pub enum PollError {
    #[error("Upstream feed error: {0}")]
    Upstream(anyhow::Error),

    #[error("Database error: {0}")]
    Database(#[from] sqlx::Error),
}

pub trait FeedFetcher: Send + Sync + 'static {
    fn fetch_feed(&self, url: &Url) -> impl std::future::Future<Output = Result<String>> + Send;
}

#[derive(Debug, Clone)]
pub struct HttpFeedFetcher {
    client: SafeHttpClient,
}

impl HttpFeedFetcher {
    pub fn new() -> Result<Self> {
        Ok(Self { client: SafeHttpClient::new(Duration::from_secs(30))? })
    }
}

impl FeedFetcher for HttpFeedFetcher {
    async fn fetch_feed(&self, url: &Url) -> Result<String> {
        self.client.fetch_text(url, MAX_FEED_BYTES).await
    }
}

struct InFlightGuard {
    in_flight: Arc<Mutex<HashSet<String>>>,
    feed_id: String,
}

impl Drop for InFlightGuard {
    fn drop(&mut self) {
        if let Ok(mut set) = self.in_flight.lock() {
            set.remove(&self.feed_id);
        }
    }
}

pub struct PollScheduler<F = HttpFeedFetcher> {
    db: Db,
    fetcher: Arc<F>,
    semaphore: Arc<Semaphore>,
    in_flight: Arc<Mutex<HashSet<String>>>,
}

impl<F: FeedFetcher> PollScheduler<F> {
    #[must_use]
    pub fn with_fetcher(db: Db, fetcher: F) -> Self {
        Self {
            db,
            fetcher: Arc::new(fetcher),
            semaphore: Arc::new(Semaphore::new(MAX_CONCURRENT_POLLS)),
            in_flight: Arc::new(Mutex::new(HashSet::new())),
        }
    }

    pub async fn run(&self) -> Result<()> {
        let mut interval = tokio::time::interval(Duration::from_secs(60));
        loop {
            interval.tick().await;
            if let Err(e) = self.check_and_poll().await {
                tracing::error!(error = %e, "Scheduler check_and_poll failed");
            }
        }
    }

    pub async fn check_and_poll(&self) -> Result<()> {
        let feeds = self.db.feeds().get_active_for_polling().await?;

        for feed in feeds {
            {
                let Ok(mut set) = self.in_flight.lock() else {
                    continue;
                };
                if !set.insert(feed.id.clone()) {
                    continue;
                }
            }

            let db = self.db.clone();
            let fetcher = Arc::clone(&self.fetcher);
            let semaphore = Arc::clone(&self.semaphore);
            let in_flight = Arc::clone(&self.in_flight);

            tokio::spawn(async move {
                let _guard = InFlightGuard { in_flight, feed_id: feed.id.clone() };
                let Ok(_permit) = semaphore.acquire_owned().await else {
                    return;
                };
                let feed_repo = db.feeds();
                match FeedIngester::poll(&feed_repo, &feed, fetcher.as_ref()).await {
                    Ok(()) => {
                        tracing::info!(feed_id = %feed.id, "Feed polled successfully");
                    }
                    Err(e) => {
                        tracing::error!(feed_id = %feed.id, error = %e, "Feed poll failed");
                        let _ = feed_repo.mark_error(&feed.id, &e.to_string()).await;
                    }
                }
            });
        }

        Ok(())
    }
}

pub struct FeedIngester;

impl FeedIngester {
    pub async fn poll<F: FeedFetcher>(
        repo: &FeedRepo<'_>,
        feed: &Feed,
        fetcher: &F,
    ) -> std::result::Result<(), PollError> {
        let content = fetcher.fetch_feed(&feed.url).await.map_err(PollError::Upstream)?;
        let feed_type = feed.feed_type;
        let batch =
            tokio::task::spawn_blocking(move || Self::parse_feed_content(feed_type, &content))
                .await
                .map_err(|e| PollError::Upstream(anyhow::anyhow!("Feed parse task failed: {e}")))?
                .map_err(PollError::Upstream)?;

        repo.record_poll_batch(&feed.id, &batch).await?;
        Ok(())
    }

    fn parse_feed_content(feed_type: FeedType, content: &str) -> Result<PollBatch> {
        match feed_type {
            FeedType::Rss => {
                let (rss_feed, items) = RssParser::parse(content)?;
                let parsed_items = items
                    .into_iter()
                    .filter_map(|item| {
                        let url = item.link?;
                        let summary = HtmlSanitizer::clean(&item.description);
                        let content_html = item
                            .content_html
                            .as_deref()
                            .map(HtmlSanitizer::clean)
                            .or_else(|| Some(summary.clone()));
                        Some(PollItemCandidate {
                            title: item.title,
                            url,
                            author: item.author,
                            published_at: item.pub_date,
                            summary,
                            content_html,
                            content_type: ContentType::Article,
                        })
                    })
                    .take(MAX_ITEMS_PER_POLL)
                    .collect();
                Ok(PollBatch {
                    title: Some(rss_feed.title),
                    description: Some(rss_feed.description),
                    language: Some(rss_feed.language),
                    items: parsed_items,
                })
            }
            FeedType::Youtube => {
                let yt_feed = YouTubeParser::parse(content)?;
                let author =
                    (!yt_feed.channel_title.is_empty()).then_some(yt_feed.channel_title.clone());
                let parsed_items = yt_feed
                    .items
                    .into_iter()
                    .take(MAX_ITEMS_PER_POLL)
                    .map(|video| {
                        let summary = HtmlSanitizer::clean(&video.description);
                        PollItemCandidate {
                            title: video.title,
                            url: video.watch_url,
                            author: author.clone(),
                            published_at: video.published_at,
                            content_html: Some(summary.clone()),
                            summary,
                            content_type: ContentType::Video,
                        }
                    })
                    .collect();
                Ok(PollBatch {
                    title: Some(yt_feed.channel_title),
                    description: None,
                    language: None,
                    items: parsed_items,
                })
            }
            FeedType::Arxiv => {
                let arxiv_feed = ArxivParser::parse(content)?;
                let parsed_items = arxiv_feed
                    .items
                    .into_iter()
                    .filter_map(|paper| {
                        let url = paper.abstract_url?;
                        let authors = paper.authors.join(", ");
                        let author = (!authors.is_empty()).then_some(authors);
                        let summary = HtmlSanitizer::clean(&paper.summary);
                        Some(PollItemCandidate {
                            title: paper.title,
                            url,
                            author,
                            published_at: paper.published_at,
                            content_html: Some(summary.clone()),
                            summary,
                            content_type: ContentType::Paper,
                        })
                    })
                    .take(MAX_ITEMS_PER_POLL)
                    .collect();
                Ok(PollBatch {
                    title: Some(arxiv_feed.title),
                    description: None,
                    language: None,
                    items: parsed_items,
                })
            }
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::db::Db;

    struct StaticFetcher {
        xml: String,
    }

    impl FeedFetcher for StaticFetcher {
        fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
            std::future::ready(Ok(self.xml.clone()))
        }
    }

    #[tokio::test]
    async fn test_poll_feed_with_trait_fetcher_populates_metadata_and_items() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/feed.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let fetcher = StaticFetcher {
            xml: r#"<?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0">
            <channel>
                <title>Example Channel</title>
                <link>https://example.com</link>
                <description>Desc</description>
                <language>en</language>
                <item>
                    <title>Trait Tested Post</title>
                    <link>https://example.com/post-1</link>
                    <description><![CDATA[<script>evil()</script><p>Safe Body</p>]]></description>
                </item>
                <item>
                    <title>SSRF Item Dropped</title>
                    <link>http://127.0.0.1:8080/internal</link>
                    <description>Should be skipped</description>
                </item>
            </channel>
            </rss>"#
                .to_string(),
        };

        FeedIngester::poll(&db.feeds(), &feed, &fetcher).await.unwrap();

        let items = db.items().list(None, Some(&feed.id), None).await.unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "Trait Tested Post");
        assert_eq!(items[0].url.as_str(), "https://example.com/post-1");
        assert_eq!(items[0].summary.as_deref(), Some("<p>Safe Body</p>"));

        let updated_feed = db.feeds().get(&feed.id).await.unwrap().unwrap();
        assert!(updated_feed.last_polled.is_some());
        assert_eq!(updated_feed.title.as_deref(), Some("Example Channel"));
        assert_eq!(updated_feed.description.as_deref(), Some("Desc"));
        assert_eq!(updated_feed.language.as_deref(), Some("en"));
    }
}
