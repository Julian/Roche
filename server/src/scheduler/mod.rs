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
    content::fetcher::{
        HtmlSanitizer, SafeHttpClient, classify_url, is_valid_pdf_bytes, resolve_pdf_url,
    },
    db::{ContentType, Db, Feed, FeedRepo, FeedType, MediaType, PollBatch, PollItemCandidate},
    feeds::{ArxivParser, RssParser, YouTubeParser},
    media::{MediaStore, ytdlp::YtDlpWorker},
};

const MAX_FEED_BYTES: usize = 10 * 1024 * 1024; // 10 MiB
const MAX_HTML_BYTES: usize = 5 * 1024 * 1024; // 5 MiB
const MAX_PDF_BYTES: usize = 50 * 1024 * 1024; // 50 MiB
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

    fn fetch_html(
        &self,
        _url: &Url,
    ) -> impl std::future::Future<Output = Result<Option<String>>> + Send {
        std::future::ready(Ok(None))
    }

    fn fetch_pdf(
        &self,
        _url: &Url,
    ) -> impl std::future::Future<Output = Result<Option<Vec<u8>>>> + Send {
        std::future::ready(Ok(None))
    }
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

    async fn fetch_html(&self, url: &Url) -> Result<Option<String>> {
        self.client.fetch_html_text(url, MAX_HTML_BYTES).await.map(Some)
    }

    async fn fetch_pdf(&self, url: &Url) -> Result<Option<Vec<u8>>> {
        self.client.fetch_pdf_bytes(url, MAX_PDF_BYTES).await.map(Some)
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
    media_store: Option<MediaStore>,
    ytdlp_worker: Option<YtDlpWorker>,
    download_semaphore: Arc<Semaphore>,
    semaphore: Arc<Semaphore>,
    in_flight: Arc<Mutex<HashSet<String>>>,
}

impl<F: FeedFetcher> PollScheduler<F> {
    #[must_use]
    pub fn with_fetcher(db: Db, fetcher: F) -> Self {
        Self {
            db,
            fetcher: Arc::new(fetcher),
            media_store: None,
            ytdlp_worker: None,
            download_semaphore: Arc::new(Semaphore::new(2)),
            semaphore: Arc::new(Semaphore::new(MAX_CONCURRENT_POLLS)),
            in_flight: Arc::new(Mutex::new(HashSet::new())),
        }
    }

    #[must_use]
    pub fn with_media(
        mut self,
        media_store: MediaStore,
        ytdlp_worker: YtDlpWorker,
        download_semaphore: Arc<Semaphore>,
    ) -> Self {
        self.media_store = Some(media_store);
        self.ytdlp_worker = Some(ytdlp_worker);
        self.download_semaphore = download_semaphore;
        self
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
            let media_store = self.media_store.clone();
            let ytdlp_worker = self.ytdlp_worker.clone();
            let download_semaphore = Arc::clone(&self.download_semaphore);
            let semaphore = Arc::clone(&self.semaphore);
            let in_flight = Arc::clone(&self.in_flight);

            tokio::spawn(async move {
                let _guard = InFlightGuard { in_flight, feed_id: feed.id.clone() };
                let poll_res = {
                    let Ok(_permit) = semaphore.acquire_owned().await else {
                        return;
                    };
                    let feed_repo = db.feeds();
                    FeedIngester::poll_returning_inserted(&feed_repo, &feed, fetcher.as_ref()).await
                };
                match poll_res {
                    Ok(inserted) => {
                        tracing::info!(feed_id = %feed.id, "Feed polled successfully");
                        if let Some(ref store) = media_store {
                            let ctx = MediaIngestContext {
                                db: &db,
                                media_store: store,
                                ytdlp_worker: ytdlp_worker.as_ref(),
                                download_semaphore: Some(&download_semaphore),
                                fetcher: fetcher.as_ref(),
                            };
                            FeedIngester::ingest_items_concurrently(&ctx, &inserted).await;
                        }
                    }
                    Err(e) => {
                        tracing::error!(feed_id = %feed.id, error = %e, "Feed poll failed");
                        let _ = db.feeds().mark_error(&feed.id, &e.to_string()).await;
                    }
                }
            });
        }

        Ok(())
    }
}

pub struct MediaIngestContext<'a, F> {
    pub db: &'a Db,
    pub media_store: &'a MediaStore,
    pub ytdlp_worker: Option<&'a YtDlpWorker>,
    pub download_semaphore: Option<&'a Arc<Semaphore>>,
    pub fetcher: &'a F,
}

pub struct FeedIngester;

impl FeedIngester {
    #[cfg(test)]
    pub async fn poll<F: FeedFetcher>(
        repo: &FeedRepo<'_>,
        feed: &Feed,
        fetcher: &F,
    ) -> std::result::Result<(), PollError> {
        let _ = Self::poll_returning_inserted(repo, feed, fetcher).await?;
        Ok(())
    }

    pub async fn poll_with_media<F: FeedFetcher>(
        ctx: &MediaIngestContext<'_, F>,
        feed: &Feed,
    ) -> std::result::Result<(), PollError> {
        let feed_repo = ctx.db.feeds();
        let inserted = Self::poll_returning_inserted(&feed_repo, feed, ctx.fetcher).await?;
        Self::ingest_items_concurrently(ctx, &inserted).await;
        Ok(())
    }

    pub async fn ingest_items_concurrently<F: FeedFetcher>(
        ctx: &MediaIngestContext<'_, F>,
        inserted: &[(String, PollItemCandidate)],
    ) {
        let mut futures: Vec<Option<_>> = inserted
            .iter()
            .map(|(item_id, candidate)| {
                Some(Box::pin(Self::ingest_item_media(
                    ctx,
                    item_id,
                    candidate.content_type,
                    &candidate.url,
                )))
            })
            .collect();

        if futures.is_empty() {
            return;
        }

        std::future::poll_fn(move |cx| {
            let mut all_done = true;
            for slot in &mut futures {
                if let Some(fut) = slot {
                    if fut.as_mut().poll(cx).is_ready() {
                        *slot = None;
                    } else {
                        all_done = false;
                    }
                }
            }
            if all_done { std::task::Poll::Ready(()) } else { std::task::Poll::Pending }
        })
        .await;
    }

    pub async fn ingest_item_media<F: FeedFetcher>(
        ctx: &MediaIngestContext<'_, F>,
        item_id: &str,
        content_type: ContentType,
        url: &Url,
    ) {
        match content_type {
            ContentType::Paper | ContentType::Pdf => {
                let Some(pdf_url) = resolve_pdf_url(url) else {
                    return;
                };
                let _permit = match ctx.download_semaphore {
                    Some(sem) => sem.acquire().await.ok(),
                    None => None,
                };
                match ctx.fetcher.fetch_pdf(&pdf_url).await {
                    Ok(Some(bytes)) if is_valid_pdf_bytes(&bytes) => {
                        let rel_path = format!("pdfs/{item_id}.pdf");
                        match ctx.media_store.write_bytes(&rel_path, &bytes).await {
                            Ok(file_size) => {
                                if let Err(e) = ctx
                                    .db
                                    .media()
                                    .create(
                                        item_id,
                                        MediaType::Pdf,
                                        &rel_path,
                                        Some(file_size),
                                        None,
                                    )
                                    .await
                                {
                                    let _ = ctx.media_store.delete_file(&rel_path).await;
                                    tracing::warn!(
                                        item_id = %item_id,
                                        error = %e,
                                        "Failed to record PDF media row"
                                    );
                                }
                            }
                            Err(e) => {
                                tracing::warn!(
                                    item_id = %item_id,
                                    error = %e,
                                    "Failed to write PDF file to media_dir"
                                );
                            }
                        }
                    }
                    Ok(_) => {}
                    Err(e) => {
                        tracing::warn!(
                            item_id = %item_id,
                            pdf_url = %pdf_url,
                            error = %e,
                            "Failed to download PDF for item"
                        );
                    }
                }
            }
            ContentType::Video => {
                let Some(worker) = ctx.ytdlp_worker.cloned() else {
                    return;
                };
                let db = ctx.db.clone();
                let sem = ctx.download_semaphore.cloned();
                let item_id_owned = item_id.to_owned();
                let url_owned = url.clone();
                tokio::spawn(async move {
                    let _permit = match sem {
                        Some(ref s) => s.acquire().await.ok(),
                        None => None,
                    };
                    let media_repo = db.media();
                    match worker.download_for_item(&media_repo, &item_id_owned, &url_owned).await {
                        Ok((_, meta)) => {
                            let clean_desc = meta.description.as_deref().map(HtmlSanitizer::clean);
                            let _ = db
                                .items()
                                .backfill_metadata(
                                    &item_id_owned,
                                    meta.title.as_deref(),
                                    meta.uploader.as_deref(),
                                    clean_desc.as_deref(),
                                )
                                .await;
                        }
                        Err(e) => {
                            tracing::debug!(
                                item_id = %item_id_owned,
                                url = %url_owned,
                                error = %e,
                                "Skipped or failed yt-dlp download for video item"
                            );
                        }
                    }
                });
            }
            ContentType::Article | ContentType::Audio => {}
        }
    }

    async fn poll_returning_inserted<F: FeedFetcher>(
        repo: &FeedRepo<'_>,
        feed: &Feed,
        fetcher: &F,
    ) -> std::result::Result<Vec<(String, PollItemCandidate)>, PollError> {
        let content = fetcher.fetch_feed(&feed.url).await.map_err(PollError::Upstream)?;
        let feed_type = feed.feed_type;
        let batch =
            tokio::task::spawn_blocking(move || Self::parse_feed_content(feed_type, &content))
                .await
                .map_err(|e| PollError::Upstream(anyhow::anyhow!("Feed parse task failed: {e}")))?
                .map_err(PollError::Upstream)?;

        let inserted = repo.record_poll_batch(&feed.id, &batch).await?;
        Ok(inserted)
    }

    fn parse_feed_content(feed_type: FeedType, content: &str) -> Result<PollBatch> {
        match feed_type {
            FeedType::Rss => {
                let (rss_feed, items) = RssParser::parse(content)?;
                let parsed_items = items
                    .into_iter()
                    .filter_map(|item| {
                        let url = item.link?;
                        let content_type = classify_url(&url);
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
                            content_type,
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

    #[tokio::test]
    async fn test_rss_url_classification_and_orphan_file_cleanup_on_db_error() {
        struct PdfCapableFetcher;
        impl FeedFetcher for PdfCapableFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                std::future::ready(Ok(r#"<?xml version="1.0" encoding="UTF-8" ?>
                <rss version="2.0">
                <channel>
                    <title>Mixed Feed</title>
                    <link>https://example.com</link>
                    <description>Mixed</description>
                    <item>
                        <title>Video Link</title>
                        <link>https://www.youtube.com/watch?v=dQw4w9WgXcQ</link>
                        <description>Vid</description>
                    </item>
                    <item>
                        <title>Arxiv Link</title>
                        <link>https://arxiv.org/abs/2301.07041</link>
                        <description>Paper</description>
                    </item>
                    <item>
                        <title>Direct PDF Link</title>
                        <link>https://example.com/doc.pdf</link>
                        <description>PDF</description>
                    </item>
                </channel>
                </rss>"#
                    .to_string()))
            }

            fn fetch_pdf(
                &self,
                _url: &Url,
            ) -> impl std::future::Future<Output = Result<Option<Vec<u8>>>> {
                std::future::ready(Ok(Some(b"%PDF-1.5\n%test-pdf\n%%EOF".to_vec())))
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/mixed.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let temp_dir =
            std::env::temp_dir().join(format!("roche-sched-test-{}", uuid::Uuid::new_v4()));
        let store = MediaStore::new(&temp_dir);
        let sem = Arc::new(Semaphore::new(4));
        let ctx = MediaIngestContext {
            db: &db,
            media_store: &store,
            ytdlp_worker: None,
            download_semaphore: Some(&sem),
            fetcher: &PdfCapableFetcher,
        };

        FeedIngester::poll_with_media(&ctx, &feed).await.unwrap();

        let items = db.items().list(None, Some(&feed.id), None).await.unwrap();
        assert_eq!(items.len(), 3);
        let by_title: std::collections::HashMap<_, _> =
            items.iter().map(|i| (i.title.as_str(), i)).collect();
        assert_eq!(by_title["Video Link"].content_type, ContentType::Video);
        assert_eq!(by_title["Arxiv Link"].content_type, ContentType::Paper);
        assert_eq!(by_title["Direct PDF Link"].content_type, ContentType::Pdf);

        // Both paper and direct PDF items have ingested PDF media
        assert_eq!(db.media().list_for_item(&by_title["Arxiv Link"].id).await.unwrap().len(), 1);
        assert_eq!(
            db.media().list_for_item(&by_title["Direct PDF Link"].id).await.unwrap().len(),
            1
        );

        // If `db.media().create` fails after writing the PDF file to disk, the file is immediately
        // deleted so no orphaned file remains in `media_dir`.
        sqlx::query("DROP TABLE media").execute(db.pool()).await.unwrap();
        let pdf_url = Url::parse("https://example.com/orphan.pdf").unwrap();
        FeedIngester::ingest_item_media(&ctx, "non-existent-item", ContentType::Pdf, &pdf_url)
            .await;
        assert!(!temp_dir.join("pdfs/non-existent-item.pdf").exists());

        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }
}
