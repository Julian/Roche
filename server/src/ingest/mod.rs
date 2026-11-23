use std::{
    collections::HashMap,
    future::Future,
    pin::Pin,
    sync::{Arc, Mutex, Weak},
    time::Duration,
};

use anyhow::Result;
use thiserror::Error;
use tokio::sync::Semaphore;
use url::Url;

use crate::{
    content::{
        HtmlSanitizer, MAX_SUMMARY_CHARS, SafeHttpClient, classify_url, is_valid_pdf_bytes,
        resolve_pdf_url,
    },
    db::{Db, Feed, FeedRepo, PollBatch, PollItemCandidate},
    domain::{ContentType, FeedType, MediaType},
    feeds::{ArxivParser, RssParser, YouTubeParser},
    media::{MediaStore, ytdlp::YtDlpWorker},
    util::{media_urls::youtube_thumbnail_url, url_guard::validate_public_url},
};

const MAX_FEED_BYTES: usize = 10 * 1024 * 1024; // 10 MiB
const MAX_HTML_BYTES: usize = 5 * 1024 * 1024; // 5 MiB
const MAX_PDF_BYTES: usize = 50 * 1024 * 1024; // 50 MiB
pub const MAX_CONCURRENT_POLLS: usize = 8;
const MAX_ITEMS_PER_POLL: usize = 500;

#[derive(Debug, Error)]
pub enum PollError {
    #[error("Upstream feed error: {0}")]
    Upstream(anyhow::Error),

    #[error("Database error: {0}")]
    Database(#[from] sqlx::Error),
}

pub trait FeedFetcher: Send + Sync + 'static {
    fn fetch_feed(&self, url: &Url) -> impl Future<Output = Result<String>> + Send;

    fn fetch_html(&self, _url: &Url) -> impl Future<Output = Result<Option<String>>> + Send {
        std::future::ready(Ok(None))
    }

    fn fetch_pdf(&self, _url: &Url) -> impl Future<Output = Result<Option<Vec<u8>>>> + Send {
        std::future::ready(Ok(None))
    }
}

type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

trait BoxedFeedFetcher: Send + Sync + 'static {
    fn fetch_feed<'a>(&'a self, url: &'a Url) -> BoxFuture<'a, Result<String>>;
    fn fetch_html<'a>(&'a self, url: &'a Url) -> BoxFuture<'a, Result<Option<String>>>;
    fn fetch_pdf<'a>(&'a self, url: &'a Url) -> BoxFuture<'a, Result<Option<Vec<u8>>>>;
}

impl<T: FeedFetcher> BoxedFeedFetcher for T {
    fn fetch_feed<'a>(&'a self, url: &'a Url) -> BoxFuture<'a, Result<String>> {
        Box::pin(FeedFetcher::fetch_feed(self, url))
    }

    fn fetch_html<'a>(&'a self, url: &'a Url) -> BoxFuture<'a, Result<Option<String>>> {
        Box::pin(FeedFetcher::fetch_html(self, url))
    }

    fn fetch_pdf<'a>(&'a self, url: &'a Url) -> BoxFuture<'a, Result<Option<Vec<u8>>>> {
        Box::pin(FeedFetcher::fetch_pdf(self, url))
    }
}

/// Type-erased, cheaply cloneable [`FeedFetcher`] handle used by `RocheService` and `PollScheduler`.
#[derive(Clone)]
pub struct DynFeedFetcher(Arc<dyn BoxedFeedFetcher>);

impl std::fmt::Debug for DynFeedFetcher {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_tuple("DynFeedFetcher").field(&"<dyn FeedFetcher>").finish()
    }
}

impl DynFeedFetcher {
    #[must_use]
    pub fn new(fetcher: impl FeedFetcher) -> Self {
        Self(Arc::new(fetcher))
    }
}

impl FeedFetcher for DynFeedFetcher {
    async fn fetch_feed(&self, url: &Url) -> Result<String> {
        self.0.fetch_feed(url).await
    }

    async fn fetch_html(&self, url: &Url) -> Result<Option<String>> {
        self.0.fetch_html(url).await
    }

    async fn fetch_pdf(&self, url: &Url) -> Result<Option<Vec<u8>>> {
        self.0.fetch_pdf(url).await
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

#[derive(Debug)]
pub struct FeedPollGuard {
    guard: Option<tokio::sync::OwnedMutexGuard<()>>,
    locks: Arc<Mutex<HashMap<String, Weak<tokio::sync::Mutex<()>>>>>,
    feed_id: String,
}

impl Drop for FeedPollGuard {
    fn drop(&mut self) {
        // Drop the OwnedMutexGuard first so the Arc strong count decrements before cleanup.
        drop(self.guard.take());
        if let Ok(mut map) = self.locks.lock()
            && map.get(&self.feed_id).is_some_and(|w| w.strong_count() == 0)
        {
            map.remove(&self.feed_id);
        }
    }
}

/// Weak-backed per-feed poll lock registry shared across `PollScheduler` and `RocheService`
/// so scheduled background polls, Refresh All batches, and single-feed manual refreshes
/// never fetch the same feed concurrently.
#[derive(Debug, Clone, Default)]
pub struct FeedPollLocks {
    locks: Arc<Mutex<HashMap<String, Weak<tokio::sync::Mutex<()>>>>>,
}

impl FeedPollLocks {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    fn lock_for(&self, feed_id: &str) -> Arc<tokio::sync::Mutex<()>> {
        let mut map = self.locks.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(existing) = map.get(feed_id).and_then(Weak::upgrade) {
            return existing;
        }
        if map.len() >= 64 {
            map.retain(|_, weak| weak.strong_count() > 0);
        }
        let mutex = Arc::new(tokio::sync::Mutex::new(()));
        map.insert(feed_id.to_owned(), Arc::downgrade(&mutex));
        mutex
    }

    /// Non-blocking attempt to acquire exclusive poll ownership for `feed_id`.
    /// Returns `None` if another poll for `feed_id` is already in flight.
    #[must_use]
    pub fn try_acquire(&self, feed_id: &str) -> Option<FeedPollGuard> {
        let mutex = self.lock_for(feed_id);
        mutex.try_lock_owned().ok().map(|guard| FeedPollGuard {
            guard: Some(guard),
            locks: Arc::clone(&self.locks),
            feed_id: feed_id.to_owned(),
        })
    }

    /// Waits for any in-flight poll of `feed_id` to complete, then acquires exclusive ownership.
    pub async fn acquire(&self, feed_id: &str) -> FeedPollGuard {
        let mutex = self.lock_for(feed_id);
        let guard = mutex.lock_owned().await;
        FeedPollGuard {
            guard: Some(guard),
            locks: Arc::clone(&self.locks),
            feed_id: feed_id.to_owned(),
        }
    }

    #[cfg(test)]
    pub(crate) fn tracked_len(&self) -> usize {
        self.locks.lock().unwrap_or_else(std::sync::PoisonError::into_inner).len()
    }
}

pub struct MediaIngestContext<'a, F> {
    pub db: &'a Db,
    pub media_store: &'a MediaStore,
    pub ytdlp_worker: Option<&'a YtDlpWorker>,
    pub download_semaphore: Option<&'a Arc<Semaphore>>,
    pub fetcher: &'a F,
}

pub struct PollOutcome {
    pub inserted: Vec<(String, PollItemCandidate)>,
    pub feed_title: Option<String>,
    pub parsed_entries: usize,
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

    pub async fn poll_xml_and_record_outcome<F: FeedFetcher>(
        db: &Db,
        fetcher: &F,
        feed: &Feed,
        context_label: &str,
        record_db_errors: bool,
    ) -> std::result::Result<PollOutcome, PollError> {
        let feed_repo = db.feeds();
        match Self::poll_returning_inserted(&feed_repo, feed, fetcher).await {
            Ok(outcome) => {
                let feed_title =
                    outcome.feed_title.as_deref().or(feed.title.as_deref()).unwrap_or("<untitled>");
                tracing::info!(
                    feed_title = %feed_title,
                    feed_url = %feed.url,
                    feed_id = %feed.id,
                    context = context_label,
                    new_items = outcome.inserted.len(),
                    feed_entries = outcome.parsed_entries,
                    "Feed polled successfully"
                );
                Ok(outcome)
            }
            Err(err) => {
                let feed_title = feed.title.as_deref().unwrap_or("<untitled>");
                tracing::error!(
                    feed_title = %feed_title,
                    feed_url = %feed.url,
                    feed_id = %feed.id,
                    context = context_label,
                    error = %err,
                    "Feed poll failed"
                );
                if record_db_errors || matches!(err, PollError::Upstream(_)) {
                    let stored_err = match &err {
                        PollError::Upstream(upstream) => {
                            crate::util::url_guard::sanitize_upstream_error(upstream)
                        }
                        PollError::Database(db_err) => db_err.to_string(),
                    };
                    let _ = feed_repo.mark_error(&feed.id, &stored_err).await;
                }
                Err(err)
            }
        }
    }

    #[cfg(test)]
    pub async fn poll_and_record<F: FeedFetcher>(
        ctx: &MediaIngestContext<'_, F>,
        feed: &Feed,
        context_label: &str,
    ) -> std::result::Result<PollOutcome, PollError> {
        let outcome =
            Self::poll_xml_and_record_outcome(ctx.db, ctx.fetcher, feed, context_label, true)
                .await?;
        Self::ingest_items_concurrently(ctx, &outcome.inserted).await;
        Ok(outcome)
    }

    pub async fn ingest_items_concurrently<F: FeedFetcher>(
        ctx: &MediaIngestContext<'_, F>,
        inserted: &[(String, PollItemCandidate)],
    ) {
        if inserted.is_empty() {
            return;
        }
        let next_idx = std::sync::atomic::AtomicUsize::new(0);
        let worker = async || {
            loop {
                let idx = next_idx.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                let Some((item_id, candidate)) = inserted.get(idx) else {
                    break;
                };
                Self::ingest_item_media(ctx, item_id, candidate.content_type, &candidate.url).await;
            }
        };
        tokio::join!(worker(), worker(), worker(), worker());
    }

    pub async fn ingest_item_media<F: FeedFetcher>(
        ctx: &MediaIngestContext<'_, F>,
        item_id: &str,
        content_type: ContentType,
        url: &Url,
    ) {
        match content_type {
            ContentType::Paper | ContentType::Pdf => {
                Self::ingest_pdf_media(ctx, item_id, url).await;
            }
            ContentType::Video => {
                Self::ingest_video_media(ctx, item_id, url).await;
            }
            ContentType::Article | ContentType::Audio => {}
        }
    }

    async fn ingest_pdf_media<F: FeedFetcher>(
        ctx: &MediaIngestContext<'_, F>,
        item_id: &str,
        url: &Url,
    ) {
        let Some(pdf_url) = resolve_pdf_url(url) else {
            return;
        };
        if validate_public_url(&pdf_url).is_err() {
            return;
        }
        let fetched = {
            let _permit = match ctx.download_semaphore {
                Some(sem) => sem.acquire().await.ok(),
                None => None,
            };
            ctx.fetcher.fetch_pdf(&pdf_url).await
        };
        match fetched {
            Ok(Some(bytes)) if is_valid_pdf_bytes(&bytes) => {
                let rel_path = format!("pdfs/{item_id}.pdf");
                match ctx.media_store.write_bytes(&rel_path, &bytes).await {
                    Ok(file_size) => {
                        if let Err(e) = ctx
                            .db
                            .media()
                            .create(item_id, MediaType::Pdf, &rel_path, Some(file_size), None)
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

    async fn ingest_video_media<F: FeedFetcher>(
        ctx: &MediaIngestContext<'_, F>,
        item_id: &str,
        url: &Url,
    ) {
        let Some(worker) = ctx.ytdlp_worker else {
            return;
        };
        if validate_public_url(url).is_err() {
            return;
        }
        let download_res = {
            let _permit = match ctx.download_semaphore {
                Some(sem) => sem.acquire().await.ok(),
                None => None,
            };
            let media_repo = ctx.db.media();
            worker.download_for_item(&media_repo, item_id, url).await
        };
        match download_res {
            Ok((_, meta)) => {
                let clean_desc = meta
                    .description
                    .as_deref()
                    .map(|d| HtmlSanitizer::plain_text_truncate(d, MAX_SUMMARY_CHARS));
                let _ = ctx
                    .db
                    .items()
                    .backfill_metadata(
                        item_id,
                        meta.title.as_deref(),
                        meta.uploader.as_deref(),
                        clean_desc.as_deref(),
                    )
                    .await;
            }
            Err(e) => {
                tracing::debug!(
                    item_id = %item_id,
                    url = %url,
                    error = %e,
                    "Skipped or failed yt-dlp download for video item"
                );
            }
        }
    }

    async fn poll_returning_inserted<F: FeedFetcher>(
        repo: &FeedRepo<'_>,
        feed: &Feed,
        fetcher: &F,
    ) -> std::result::Result<PollOutcome, PollError> {
        let content = fetcher.fetch_feed(&feed.url).await.map_err(PollError::Upstream)?;
        let feed_type = feed.feed_type;
        let batch =
            tokio::task::spawn_blocking(move || Self::parse_feed_content(feed_type, &content))
                .await
                .map_err(|e| PollError::Upstream(anyhow::anyhow!("Feed parse task failed: {e}")))?
                .map_err(PollError::Upstream)?;

        let parsed_entries = batch.items.len();
        let feed_title = batch.title.clone().filter(|t| !t.trim().is_empty());
        let inserted = repo.record_poll_batch(&feed.id, &batch).await?;
        Ok(PollOutcome { inserted, feed_title, parsed_entries })
    }

    #[allow(clippy::too_many_lines)]
    pub(crate) fn parse_feed_content(feed_type: FeedType, content: &str) -> Result<PollBatch> {
        match feed_type {
            FeedType::Rss | FeedType::Podcast => {
                let is_podcast_feed = feed_type == FeedType::Podcast;
                let (rss_feed, items) = RssParser::parse(content)?;
                let parsed_items = items
                    .into_iter()
                    .filter_map(|item| {
                        let raw_url = item.link?;
                        let url = crate::util::media_urls::canonical_arxiv_abstract_url(&raw_url)
                            .unwrap_or(raw_url);
                        let url_ct = classify_url(&url);
                        let content_type = if is_podcast_feed && item.audio_url.is_some() {
                            ContentType::Audio
                        } else {
                            url_ct
                        };
                        let audio_url = item.audio_url.map(String::from).or_else(|| {
                            (content_type == ContentType::Audio).then(|| url.as_str().to_owned())
                        });
                        let duration = audio_url.is_some().then_some(item.duration).flatten();
                        let clean_desc =
                            HtmlSanitizer::clean_with_base(&item.description, Some(&url));
                        let content_html = item
                            .content_html
                            .as_deref()
                            .map(|raw| HtmlSanitizer::clean_with_base(raw, Some(&url)))
                            .filter(|h| !h.trim().is_empty())
                            .or_else(|| Some(clean_desc.clone()));
                        let thumbnail_url = item
                            .thumbnail_url
                            .or_else(|| {
                                item.content_html
                                    .as_deref()
                                    .or(Some(item.description.as_str()))
                                    .and_then(|raw| {
                                        HtmlSanitizer::extract_first_image_url_with_base(
                                            raw,
                                            Some(&url),
                                        )
                                    })
                            })
                            .or_else(|| youtube_thumbnail_url(&url));
                        let summary = HtmlSanitizer::plain_text_excerpt_from_clean(
                            &clean_desc,
                            MAX_SUMMARY_CHARS,
                        );
                        Some(PollItemCandidate {
                            title: item.title,
                            url,
                            creators: item.authors,
                            published_at: item.pub_date,
                            summary,
                            content_html,
                            thumbnail_url,
                            audio_url,
                            duration,
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
                        let clean_desc = HtmlSanitizer::clean(&video.description);
                        let summary = HtmlSanitizer::plain_text_excerpt_from_clean(
                            &clean_desc,
                            MAX_SUMMARY_CHARS,
                        );
                        let thumbnail_url = youtube_thumbnail_url(&video.watch_url);
                        PollItemCandidate {
                            title: video.title,
                            url: video.watch_url,
                            creators: author.clone().into_iter().collect(),
                            published_at: video.published_at,
                            content_html: Some(clean_desc),
                            thumbnail_url,
                            audio_url: None,
                            duration: None,
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
                        let clean_summary = HtmlSanitizer::clean(&paper.summary);
                        let summary = HtmlSanitizer::plain_text_excerpt_from_clean(
                            &clean_summary,
                            MAX_SUMMARY_CHARS,
                        );
                        Some(PollItemCandidate {
                            title: paper.title,
                            url,
                            creators: paper.authors,
                            published_at: paper.published_at,
                            content_html: Some(clean_summary),
                            thumbnail_url: None,
                            audio_url: None,
                            duration: None,
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
        assert_eq!(items[0].summary.as_deref(), Some("Safe Body"));
        assert_eq!(items[0].content_html.as_deref(), Some("<p>Safe Body</p>"));

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
                    <item>
                        <title>Podcast Episode</title>
                        <link>https://example.com/episodes/42</link>
                        <description>Audio show notes</description>
                        <enclosure url="https://cdn.example.com/ep42.mp3" length="50000000" type="audio/mpeg"/>
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

        FeedIngester::poll_and_record(&ctx, &feed, "test").await.unwrap();

        let items = db.items().list(None, Some(&feed.id), None).await.unwrap();
        assert_eq!(items.len(), 4);
        let by_title: std::collections::HashMap<_, _> =
            items.iter().map(|i| (i.title.as_str(), i)).collect();
        assert_eq!(by_title["Video Link"].content_type, ContentType::Video);
        assert_eq!(by_title["Arxiv Link"].content_type, ContentType::Paper);
        assert_eq!(by_title["Direct PDF Link"].content_type, ContentType::Pdf);
        // Audio enclosures on an RSS feed remain narrated Articles (explicit opt-in for Podcasts)
        assert_eq!(by_title["Podcast Episode"].content_type, ContentType::Article);
        assert_eq!(by_title["Podcast Episode"].entry_kind, crate::domain::EntryKind::Article);
        assert_eq!(
            by_title["Podcast Episode"].audio_url.as_deref(),
            Some("https://cdn.example.com/ep42.mp3")
        );
        assert_eq!(by_title["Podcast Episode"].url.as_str(), "https://example.com/episodes/42");

        assert_eq!(db.media().list_for_item(&by_title["Arxiv Link"].id).await.unwrap().len(), 1);
        assert_eq!(
            db.media().list_for_item(&by_title["Direct PDF Link"].id).await.unwrap().len(),
            1
        );

        sqlx::query("DROP TABLE entry_media").execute(db.pool()).await.unwrap();
        let pdf_url = Url::parse("https://example.com/orphan.pdf").unwrap();
        FeedIngester::ingest_item_media(&ctx, "non-existent-item", ContentType::Pdf, &pdf_url)
            .await;
        assert!(!temp_dir.join("pdfs/non-existent-item.pdf").exists());

        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }

    #[tokio::test]
    async fn test_ingest_items_concurrently_bounds_active_workers_to_four() {
        use std::sync::atomic::{AtomicUsize, Ordering};

        struct ConcurrencyProbeFetcher {
            active: Arc<AtomicUsize>,
            peak: Arc<AtomicUsize>,
        }

        impl FeedFetcher for ConcurrencyProbeFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                std::future::ready(Ok(String::new()))
            }

            fn fetch_pdf(
                &self,
                _url: &Url,
            ) -> impl std::future::Future<Output = Result<Option<Vec<u8>>>> {
                let active = Arc::clone(&self.active);
                let peak = Arc::clone(&self.peak);
                async move {
                    let cur = active.fetch_add(1, Ordering::SeqCst) + 1;
                    peak.fetch_max(cur, Ordering::SeqCst);
                    tokio::time::sleep(Duration::from_millis(15)).await;
                    active.fetch_sub(1, Ordering::SeqCst);
                    Ok(Some(b"%PDF-1.5\n%bounded-test\n%%EOF".to_vec()))
                }
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/papers.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let batch_items: Vec<PollItemCandidate> = (0..12)
            .map(|idx| PollItemCandidate {
                title: format!("Paper {idx}"),
                url: Url::parse(&format!("https://example.com/paper-{idx}.pdf")).unwrap(),
                creators: Vec::new(),
                published_at: None,
                summary: format!("Summary {idx}"),
                content_html: None,
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Pdf,
            })
            .collect();
        let inserted = db
            .feeds()
            .record_poll_batch(
                &feed.id,
                &PollBatch {
                    title: Some("Papers".to_string()),
                    description: None,
                    language: None,
                    items: batch_items,
                },
            )
            .await
            .unwrap();
        assert_eq!(inserted.len(), 12);

        let temp_dir =
            std::env::temp_dir().join(format!("roche-bounded-ingest-{}", uuid::Uuid::new_v4()));
        let store = MediaStore::new(&temp_dir);
        let active = Arc::new(AtomicUsize::new(0));
        let peak = Arc::new(AtomicUsize::new(0));
        let fetcher = ConcurrencyProbeFetcher { active, peak: Arc::clone(&peak) };
        let ctx = MediaIngestContext {
            db: &db,
            media_store: &store,
            ytdlp_worker: None,
            download_semaphore: None,
            fetcher: &fetcher,
        };

        FeedIngester::ingest_items_concurrently(&ctx, &inserted).await;

        let observed_peak = peak.load(Ordering::SeqCst);
        assert!(
            (2..=4).contains(&observed_peak),
            "Expected bounded concurrency between 2 and 4, got {observed_peak}"
        );
        for (item_id, _) in &inserted {
            assert_eq!(db.media().list_for_item(item_id).await.unwrap().len(), 1);
        }

        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }

    #[tokio::test]
    async fn test_arxiv_poll_preserves_separate_authors_in_creators_json() {
        let xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>arXiv cs.LG</title>
          <entry>
            <id>http://arxiv.org/abs/2401.12345v1</id>
            <title>Distributed Consensus Revisited</title>
            <summary>We study multi-author consensus.</summary>
            <author><name>Alice Smith</name></author>
            <author><name>Bob Jones</name></author>
            <link rel="alternate" type="text/html" href="https://arxiv.org/abs/2401.12345"/>
          </entry>
        </feed>"#;

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url =
            Url::parse("https://export.arxiv.org/api/query?search_query=cat:cs.LG").unwrap();
        let feed = db.feeds().create(FeedType::Arxiv, &feed_url, None, None).await.unwrap();
        let batch = FeedIngester::parse_feed_content(FeedType::Arxiv, xml).unwrap();
        let inserted = db.feeds().record_poll_batch(&feed.id, &batch).await.unwrap();
        assert_eq!(inserted.len(), 1);
        let item_id = &inserted[0].0;

        let creators_json: String =
            sqlx::query_scalar("SELECT creators_json FROM entries WHERE id = ?")
                .bind(item_id)
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert_eq!(creators_json, r#"["Alice Smith","Bob Jones"]"#);

        let item = db.items().get(item_id).await.unwrap().unwrap();
        assert_eq!(item.author.as_deref(), Some("Alice Smith, Bob Jones"));
    }

    #[tokio::test]
    async fn test_podcast_vs_rss_feed_audio_enclosure_classification() {
        let xml = r#"<?xml version="1.0" encoding="UTF-8" ?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
        <channel>
            <title>Mixed Audio Show</title>
            <link>https://show.example.com</link>
            <description>Episodes and notes</description>
            <item>
                <title>Narrated Post</title>
                <link>https://show.example.com/p/narrated</link>
                <description>Post with audio</description>
                <itunes:duration>15:00</itunes:duration>
                <enclosure url="https://cdn.show.example.com/narrated.mp3" length="12345" type="audio/mpeg"/>
            </item>
            <item>
                <title>Text Only Announcement</title>
                <link>https://show.example.com/p/text-only</link>
                <description>No enclosure</description>
                <itunes:duration>05:00</itunes:duration>
            </item>
        </channel>
        </rss>"#;

        let db = Db::connect("sqlite::memory:").await.unwrap();

        // 1. RSS feed keeps narrated post as Article with preserved audio_url + duration
        let rss_url = Url::parse("https://show.example.com/rss.xml").unwrap();
        let rss_feed = db.feeds().create(FeedType::Rss, &rss_url, None, None).await.unwrap();
        let rss_batch = FeedIngester::parse_feed_content(FeedType::Rss, xml).unwrap();
        let rss_ids = db.feeds().record_poll_batch(&rss_feed.id, &rss_batch).await.unwrap();
        assert_eq!(rss_ids.len(), 2);
        let rss_narrated = db.items().get(&rss_ids[0].0).await.unwrap().unwrap();
        assert_eq!(rss_narrated.content_type, ContentType::Article);
        assert_eq!(rss_narrated.entry_kind, crate::domain::EntryKind::Article);
        assert_eq!(
            rss_narrated.audio_url.as_deref(),
            Some("https://cdn.show.example.com/narrated.mp3")
        );
        assert_eq!(rss_narrated.duration, Some(900));
        let rss_text = db.items().get(&rss_ids[1].0).await.unwrap().unwrap();
        assert_eq!(rss_text.content_type, ContentType::Article);
        assert!(rss_text.audio_url.is_none());
        assert!(rss_text.duration.is_none());

        // 2. Explicit Podcast feed classifies audio item as PodcastEpisode/Audio, text post stays Article
        let pod_db = Db::connect("sqlite::memory:").await.unwrap();
        let pod_url = Url::parse("https://show.example.com/podcast.xml").unwrap();
        let pod_feed =
            pod_db.feeds().create(FeedType::Podcast, &pod_url, None, None).await.unwrap();
        let pod_batch = FeedIngester::parse_feed_content(FeedType::Podcast, xml).unwrap();
        let pod_ids = pod_db.feeds().record_poll_batch(&pod_feed.id, &pod_batch).await.unwrap();
        assert_eq!(pod_ids.len(), 2);
        let pod_ep = pod_db.items().get(&pod_ids[0].0).await.unwrap().unwrap();
        assert_eq!(pod_ep.content_type, ContentType::Audio);
        assert_eq!(pod_ep.entry_kind, crate::domain::EntryKind::PodcastEpisode);
        assert_eq!(pod_ep.audio_url.as_deref(), Some("https://cdn.show.example.com/narrated.mp3"));
        assert_eq!(pod_ep.duration, Some(900));
        let pod_text = pod_db.items().get(&pod_ids[1].0).await.unwrap().unwrap();
        assert_eq!(pod_text.content_type, ContentType::Article);
        assert_eq!(pod_text.entry_kind, crate::domain::EntryKind::Article);
        assert!(pod_text.audio_url.is_none());
        assert!(pod_text.duration.is_none());
    }

    #[tokio::test]
    async fn test_repoll_upgrades_existing_item_html_when_richer_without_clobbering_full_text() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://proofsandprompts.com/feed/").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let legacy_xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
        <channel>
            <title>Proofs and Prompts</title>
            <link>https://proofsandprompts.com</link>
            <item>
                <title>A mathematical community?</title>
                <link>https://proofsandprompts.com/2026/11/22/a-mathematical-community/</link>
                <content:encoded><![CDATA[<p><a href="https://www.jm-kantor.fr/">Jean-Michel Kantor</a>, Emeritus</p><p>Body text.</p>]]></content:encoded>
            </item>
        </channel>
        </rss>"#;
        let first_batch = FeedIngester::parse_feed_content(FeedType::Rss, legacy_xml).unwrap();
        let inserted = db.feeds().record_poll_batch(&feed.id, &first_batch).await.unwrap();
        assert_eq!(inserted.len(), 1);
        let item_id = &inserted[0].0;
        let initial = db.items().get(item_id).await.unwrap().unwrap();
        assert!(!initial.content_html.as_deref().unwrap().contains("<em>"));

        // Re-polling with semantic `author-line` class upgrades stored HTML via pick_richer_text.
        let semantic_xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
        <channel>
            <title>Proofs and Prompts</title>
            <link>https://proofsandprompts.com</link>
            <item>
                <title>A mathematical community?</title>
                <link>https://proofsandprompts.com/2026/11/22/a-mathematical-community/</link>
                <content:encoded><![CDATA[<p class="author-line wp-block-paragraph"><a href="https://www.jm-kantor.fr/">Jean-Michel Kantor</a>, Emeritus</p><p>Body text.</p>]]></content:encoded>
            </item>
        </channel>
        </rss>"#;
        let second_batch = FeedIngester::parse_feed_content(FeedType::Rss, semantic_xml).unwrap();
        let reinserted = db.feeds().record_poll_batch(&feed.id, &second_batch).await.unwrap();
        assert!(reinserted.is_empty());
        let upgraded = db.items().get(item_id).await.unwrap().unwrap();
        assert!(
            upgraded
                .content_html
                .as_deref()
                .unwrap()
                .contains("<p><em><a href=\"https://www.jm-kantor.fr/\"")
        );

        // A shorter teaser on a later poll must NOT clobber the richer stored HTML.
        let teaser_xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
        <channel>
            <title>Proofs and Prompts</title>
            <link>https://proofsandprompts.com</link>
            <item>
                <title>A mathematical community?</title>
                <link>https://proofsandprompts.com/2026/11/22/a-mathematical-community/</link>
                <description>Short teaser.</description>
            </item>
        </channel>
        </rss>"#;
        let third_batch = FeedIngester::parse_feed_content(FeedType::Rss, teaser_xml).unwrap();
        let _ = db.feeds().record_poll_batch(&feed.id, &third_batch).await.unwrap();
        let preserved = db.items().get(item_id).await.unwrap().unwrap();
        assert_eq!(preserved.content_html, upgraded.content_html);
    }
}
