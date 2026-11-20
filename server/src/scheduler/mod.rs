use std::{
    collections::HashMap,
    future::Future,
    pin::Pin,
    sync::{Arc, Mutex, Weak},
    time::{Duration, Instant},
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
    db::{
        DEFAULT_ONLINE_PEER_WEIGHT, Db, Feed, FeedRepo, MAX_PEER_WEIGHT, MIN_PEER_WEIGHT,
        NetworkTier, PollBatch, PollItemCandidate, SERVER_PEER_ID, compute_throughput_weight,
        should_peer_poll_feed, update_throughput_ewma,
    },
    domain::{ContentType, FeedType, MediaType},
    feeds::{ArxivParser, RssParser, YouTubeParser},
    media::{MediaStore, ytdlp::YtDlpWorker},
    util::{clock::now_utc, media_urls::youtube_thumbnail_url, url_guard::validate_public_url},
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

/// Type-erased, cheaply cloneable [`FeedFetcher`] handle used by `RocheService`.
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
    fn tracked_len(&self) -> usize {
        self.locks.lock().unwrap_or_else(std::sync::PoisonError::into_inner).len()
    }
}

#[derive(Debug)]
struct ThroughputEwmaState {
    weight: f64,
    seeded: bool,
}

#[derive(Debug, Clone)]
struct ThroughputEwma {
    state: Arc<Mutex<ThroughputEwmaState>>,
}

impl ThroughputEwma {
    fn new() -> Self {
        Self {
            state: Arc::new(Mutex::new(ThroughputEwmaState {
                weight: DEFAULT_ONLINE_PEER_WEIGHT,
                seeded: false,
            })),
        }
    }

    fn is_seeded(&self) -> bool {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).seeded
    }

    fn seed(&self, persisted_weight: Option<f64>) {
        let mut state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.seeded {
            return;
        }
        if let Some(w) = persisted_weight
            && w.is_finite()
            && w > 0.0
        {
            state.weight = w.clamp(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT);
        }
        state.seeded = true;
    }

    fn record_sample(&self, sample_weight: f64) -> f64 {
        let mut state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        state.weight = update_throughput_ewma(state.weight, sample_weight);
        state.seeded = true;
        state.weight
    }

    fn current(&self) -> f64 {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).weight
    }
}

pub struct PollScheduler<F = HttpFeedFetcher> {
    db: Db,
    fetcher: Arc<F>,
    media_store: Option<MediaStore>,
    ytdlp_worker: Option<YtDlpWorker>,
    download_semaphore: Arc<Semaphore>,
    semaphore: Arc<Semaphore>,
    feed_poll_locks: FeedPollLocks,
    throughput_ewma: ThroughputEwma,
    tasks: tokio::sync::Mutex<tokio::task::JoinSet<()>>,
}

impl<F: FeedFetcher> PollScheduler<F> {
    #[must_use]
    pub fn with_arc_fetcher(db: Db, fetcher: Arc<F>) -> Self {
        Self {
            db,
            fetcher,
            media_store: None,
            ytdlp_worker: None,
            download_semaphore: Arc::new(Semaphore::new(2)),
            semaphore: Arc::new(Semaphore::new(MAX_CONCURRENT_POLLS)),
            feed_poll_locks: FeedPollLocks::new(),
            throughput_ewma: ThroughputEwma::new(),
            tasks: tokio::sync::Mutex::new(tokio::task::JoinSet::new()),
        }
    }

    #[must_use]
    pub fn with_feed_poll_locks(mut self, locks: FeedPollLocks) -> Self {
        self.feed_poll_locks = locks;
        self
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

    #[cfg(test)]
    #[must_use]
    pub fn current_weight(&self) -> f64 {
        self.throughput_ewma.current()
    }

    pub async fn run_until_shutdown(
        &self,
        mut shutdown: tokio::sync::watch::Receiver<bool>,
    ) -> Result<()> {
        let mut interval = tokio::time::interval(Duration::from_secs(60));
        loop {
            if *shutdown.borrow_and_update() {
                break;
            }
            tokio::select! {
                _ = interval.tick() => {
                    if let Err(e) = self.check_and_poll().await {
                        tracing::error!(error = %e, "Scheduler check_and_poll failed");
                    }
                }
                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow_and_update() {
                        break;
                    }
                }
            }
        }
        self.shutdown_tasks(Duration::from_secs(10)).await;
        Ok(())
    }

    async fn reap_finished_tasks(&self) {
        let mut tasks = self.tasks.lock().await;
        while tasks.try_join_next().is_some() {}
    }

    pub async fn shutdown_tasks(&self, grace_period: Duration) {
        let mut tasks = self.tasks.lock().await;
        let drained = tokio::time::timeout(grace_period, async {
            while tasks.join_next().await.is_some() {}
        })
        .await;
        if drained.is_err() {
            tasks.abort_all();
            while tasks.join_next().await.is_some() {}
        }
    }

    pub async fn check_and_poll(&self) -> Result<()> {
        self.reap_finished_tasks().await;
        let now = now_utc();
        if !self.throughput_ewma.is_seeded()
            && let Ok(persisted) = self.db.sync().get_peer_weight(SERVER_PEER_ID).await
        {
            self.throughput_ewma.seed(persisted);
        }
        if self.throughput_ewma.is_seeded() {
            let current_weight = self.throughput_ewma.current();
            let _ = self
                .db
                .sync()
                .upsert_peer_node(
                    SERVER_PEER_ID,
                    "Roche Headless Peer",
                    NetworkTier::Wifi,
                    current_weight,
                    now,
                )
                .await;
        }
        let active_peers = self.db.sync().list_active_peers(now).await.unwrap_or_default();
        let feeds = self.db.feeds().get_active_for_cooperative_polling().await?;
        let mut due_feeds = Vec::new();
        for feed in feeds {
            if !should_peer_poll_feed(SERVER_PEER_ID, &active_peers, &feed, now) {
                continue;
            }
            let Some(poll_guard) = self.feed_poll_locks.try_acquire(&feed.id) else {
                continue;
            };
            due_feeds.push((feed, poll_guard));
        }

        if due_feeds.is_empty() {
            return Ok(());
        }

        let mut tasks = self.tasks.lock().await;
        for (feed, poll_guard) in due_feeds {
            self.spawn_feed_poll_task(&mut tasks, feed, poll_guard);
        }

        Ok(())
    }

    fn spawn_feed_poll_task(
        &self,
        tasks: &mut tokio::task::JoinSet<()>,
        feed: Feed,
        poll_guard: FeedPollGuard,
    ) {
        let db = self.db.clone();
        let fetcher = Arc::clone(&self.fetcher);
        let media_store = self.media_store.clone();
        let ytdlp_worker = self.ytdlp_worker.clone();
        let download_semaphore = Arc::clone(&self.download_semaphore);
        let semaphore = Arc::clone(&self.semaphore);
        let throughput_ewma = self.throughput_ewma.clone();

        tasks.spawn(async move {
            let (poll_res, elapsed_secs) = {
                let Ok(_permit) = semaphore.acquire_owned().await else {
                    drop(poll_guard);
                    return;
                };
                let started_at = Instant::now();
                let res = FeedIngester::poll_xml_and_record_outcome(
                    &db,
                    fetcher.as_ref(),
                    &feed,
                    "scheduled",
                    false,
                )
                .await;
                (res, started_at.elapsed().as_secs_f64())
            };

            if let Ok(ref outcome) = poll_res {
                let sample_weight =
                    compute_throughput_weight(1, outcome.parsed_entries, elapsed_secs);
                let _ = throughput_ewma.record_sample(sample_weight);
            }

            // Release the per-feed poll lock before potentially long-running media downloads
            // (e.g. yt-dlp) so subsequent polls or manual refreshes are not blocked.
            drop(poll_guard);

            if let Ok(outcome) = poll_res
                && let Some(ref store) = media_store
            {
                let ctx = MediaIngestContext {
                    db: &db,
                    media_store: store,
                    ytdlp_worker: ytdlp_worker.as_ref(),
                    download_semaphore: Some(&download_semaphore),
                    fetcher: fetcher.as_ref(),
                };
                FeedIngester::ingest_items_concurrently(&ctx, &outcome.inserted).await;
            }
        });
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
                    let _ = feed_repo.mark_error(&feed.id, &err.to_string()).await;
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
                let (rss_feed, items) = RssParser::parse(content)?;
                let parsed_items = items
                    .into_iter()
                    .filter_map(|item| {
                        let raw_url = item.link?;
                        let url = crate::util::media_urls::canonical_arxiv_abstract_url(&raw_url)
                            .unwrap_or(raw_url);
                        let content_type = if item.audio_url.is_some() {
                            ContentType::Audio
                        } else {
                            classify_url(&url)
                        };
                        let audio_url = item.audio_url.map(String::from).or_else(|| {
                            (content_type == ContentType::Audio).then(|| url.as_str().to_owned())
                        });
                        let duration = item.duration;
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
        assert_eq!(by_title["Podcast Episode"].content_type, ContentType::Audio);
        assert_eq!(
            by_title["Podcast Episode"].audio_url.as_deref(),
            Some("https://cdn.example.com/ep42.mp3")
        );
        assert_eq!(by_title["Podcast Episode"].url.as_str(), "https://example.com/episodes/42");

        // Both paper and direct PDF items have ingested PDF media
        assert_eq!(db.media().list_for_item(&by_title["Arxiv Link"].id).await.unwrap().len(), 1);
        assert_eq!(
            db.media().list_for_item(&by_title["Direct PDF Link"].id).await.unwrap().len(),
            1
        );

        // If `db.media().create` fails after writing the PDF file to disk, the file is immediately
        // deleted so no orphaned file remains in `media_dir`.
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
    async fn test_throughput_ewma_convergence_failure_exclusion_and_seeding_persistence() {
        struct SelectiveFetcher {
            ok_url: String,
            xml: String,
        }
        impl FeedFetcher for SelectiveFetcher {
            fn fetch_feed(&self, url: &Url) -> impl std::future::Future<Output = Result<String>> {
                let res = if url.as_str() == self.ok_url {
                    Ok(self.xml.clone())
                } else {
                    Err(anyhow::anyhow!("simulated upstream failure"))
                };
                std::future::ready(res)
            }
        }

        let ewma = ThroughputEwma::new();
        assert!((ewma.current() - DEFAULT_ONLINE_PEER_WEIGHT).abs() < 1e-9);
        let w1 = ewma.record_sample(10.0);
        assert!((w1 - 2.8).abs() < 1e-9);
        let w2 = ewma.record_sample(10.0);
        assert!((w2 - 4.24).abs() < 1e-9);
        // Non-finite sample does not corrupt EWMA
        let w3 = ewma.record_sample(f64::NAN);
        assert!((w3 - 4.24).abs() < 1e-9);

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let fail_url = Url::parse("https://example.com/fail-only.xml").unwrap();
        let fail_feed = db.feeds().create(FeedType::Rss, &fail_url, None, None).await.unwrap();

        // Failing feed must NOT alter EWMA weight, and must increment consecutive_failures.
        let fetcher = Arc::new(SelectiveFetcher {
            ok_url: "https://example.com/ok-only.xml".to_owned(),
            xml: r#"<?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0"><channel><title>OK</title>
                <item><title>Post</title><link>https://example.com/ok-1</link><description>Body</description></item>
            </channel></rss>"#
                .to_owned(),
        });
        let scheduler = PollScheduler::with_arc_fetcher(db.clone(), Arc::clone(&fetcher));
        scheduler.check_and_poll().await.unwrap();
        scheduler.shutdown_tasks(Duration::from_secs(2)).await;

        assert!(
            (scheduler.current_weight() - DEFAULT_ONLINE_PEER_WEIGHT).abs() < 1e-9,
            "Failed feed must not modify throughput EWMA"
        );
        let updated_fail = db.feeds().get(&fail_feed.id).await.unwrap().unwrap();
        assert_eq!(updated_fail.consecutive_failures, 1);

        // Now add a healthy fast feed: EWMA moves upward in memory and persists on next tick.
        let ok_url = Url::parse("https://example.com/ok-only.xml").unwrap();
        let _ok_feed = db.feeds().create(FeedType::Rss, &ok_url, None, None).await.unwrap();
        scheduler.check_and_poll().await.unwrap();
        scheduler.shutdown_tasks(Duration::from_secs(2)).await;
        let in_mem_weight = scheduler.current_weight();
        assert!(
            in_mem_weight > DEFAULT_ONLINE_PEER_WEIGHT,
            "Fast succeeded feed should advance EWMA above 1.0, got {in_mem_weight}"
        );
        // Not yet persisted until next tick
        let persisted_before_tick =
            db.sync().get_peer_weight(SERVER_PEER_ID).await.unwrap().unwrap();
        assert!((persisted_before_tick - DEFAULT_ONLINE_PEER_WEIGHT).abs() < 1e-9);

        // Next tick persists the EWMA once
        scheduler.check_and_poll().await.unwrap();
        let persisted_after_tick =
            db.sync().get_peer_weight(SERVER_PEER_ID).await.unwrap().unwrap();
        assert!((persisted_after_tick - in_mem_weight).abs() < 1e-9);

        // A freshly constructed PollScheduler seeds from persisted `peer_nodes.weight` on first tick
        let restarted = PollScheduler::with_arc_fetcher(db.clone(), fetcher);
        assert!((restarted.current_weight() - DEFAULT_ONLINE_PEER_WEIGHT).abs() < 1e-9);
        restarted.check_and_poll().await.unwrap();
        assert!(
            (restarted.current_weight() - persisted_after_tick).abs() < 1e-9,
            "Restarted scheduler must seed EWMA from persisted peer_nodes.weight"
        );

        let (tx, rx) = tokio::sync::watch::channel(false);
        let handle = tokio::spawn(async move { restarted.run_until_shutdown(rx).await });
        tx.send(true).unwrap();
        let res = tokio::time::timeout(Duration::from_secs(2), handle).await.unwrap().unwrap();
        assert!(res.is_ok());
    }

    #[tokio::test]
    async fn test_feed_poll_locks_deduplication_and_release_before_media_download() {
        struct SlowPdfFetcher {
            pdf_started: Arc<tokio::sync::Notify>,
            pdf_release: Arc<tokio::sync::Notify>,
        }
        impl FeedFetcher for SlowPdfFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                std::future::ready(Ok(r#"<?xml version="1.0" encoding="UTF-8" ?>
                <rss version="2.0"><channel><title>Papers</title>
                    <item><title>Paper</title><link>https://example.com/slow.pdf</link><description>PDF</description></item>
                </channel></rss>"#
                    .to_owned()))
            }

            fn fetch_pdf(
                &self,
                _url: &Url,
            ) -> impl std::future::Future<Output = Result<Option<Vec<u8>>>> {
                let started = Arc::clone(&self.pdf_started);
                let release = Arc::clone(&self.pdf_release);
                async move {
                    started.notify_one();
                    release.notified().await;
                    Ok(Some(b"%PDF-1.5\n%slow\n%%EOF".to_vec()))
                }
            }
        }

        let locks = FeedPollLocks::new();
        let g1 = locks.try_acquire("feed-1").expect("initial try_acquire must succeed");
        assert!(locks.try_acquire("feed-1").is_none(), "concurrent try_acquire must return None");
        assert!(locks.try_acquire("feed-2").is_some(), "distinct feed must not be blocked");

        let locks_clone = locks.clone();
        let waiter = tokio::spawn(async move {
            let _g2 = locks_clone.acquire("feed-1").await;
        });
        tokio::task::yield_now().await;
        assert!(!waiter.is_finished(), "acquire().await must wait while guard is held");
        drop(g1);
        tokio::time::timeout(Duration::from_secs(2), waiter).await.unwrap().unwrap();
        assert_eq!(locks.tracked_len(), 0, "Weak lock map must prune dropped entries cleanly");

        // Verify PollScheduler releases the per-feed lock BEFORE slow PDF media download completes
        let pdf_started = Arc::new(tokio::sync::Notify::new());
        let pdf_release = Arc::new(tokio::sync::Notify::new());

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/slow-pdf-feed.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let temp_dir =
            std::env::temp_dir().join(format!("roche-lock-release-{}", uuid::Uuid::new_v4()));
        let store = MediaStore::new(&temp_dir);
        let ytdlp = YtDlpWorker::new(crate::config::MediaConfig::default(), store.clone());
        let fetcher = Arc::new(SlowPdfFetcher {
            pdf_started: Arc::clone(&pdf_started),
            pdf_release: Arc::clone(&pdf_release),
        });
        let shared_locks = FeedPollLocks::new();
        let scheduler = PollScheduler::with_arc_fetcher(db.clone(), fetcher)
            .with_feed_poll_locks(shared_locks.clone())
            .with_media(store, ytdlp, Arc::new(Semaphore::new(2)));

        scheduler.check_and_poll().await.unwrap();
        tokio::time::timeout(Duration::from_secs(2), pdf_started.notified()).await.unwrap();

        // While slow PDF download is still blocked, the feed poll lock MUST already be released!
        let lock_during_media = shared_locks.try_acquire(&feed.id);
        assert!(
            lock_during_media.is_some(),
            "PollScheduler must release per-feed lock before slow media downloads"
        );
        drop(lock_during_media);

        pdf_release.notify_one();
        scheduler.shutdown_tasks(Duration::from_secs(2)).await;
        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }

    #[tokio::test]
    async fn test_scheduler_does_not_increment_active_refresh_count_while_manual_and_refresh_all_do()
     {
        use std::sync::atomic::{AtomicUsize, Ordering};

        struct BarrierFetcher {
            calls: Arc<AtomicUsize>,
            entered: Arc<tokio::sync::Notify>,
            release: Arc<tokio::sync::Notify>,
        }
        impl FeedFetcher for BarrierFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                let calls = Arc::clone(&self.calls);
                let entered = Arc::clone(&self.entered);
                let release = Arc::clone(&self.release);
                async move {
                    calls.fetch_add(1, Ordering::SeqCst);
                    entered.notify_one();
                    release.notified().await;
                    Ok(r#"<?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0"><channel><title>Barrier Feed</title>
                        <item><title>Item</title><link>https://example.com/b1</link><description>Hi</description></item>
                    </channel></rss>"#
                        .to_owned())
                }
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/banner-test.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let calls = Arc::new(AtomicUsize::new(0));
        let entered = Arc::new(tokio::sync::Notify::new());
        let release = Arc::new(tokio::sync::Notify::new());
        let fetcher = BarrierFetcher {
            calls: Arc::clone(&calls),
            entered: Arc::clone(&entered),
            release: Arc::clone(&release),
        };
        let service = crate::service::RocheService::new(
            db.clone(),
            fetcher,
            crate::config::Config::default(),
        );
        let scheduler = service.poll_scheduler();

        // 1. Scheduled poll runs WITHOUT incrementing `active_refresh_count`
        scheduler.check_and_poll().await.unwrap();
        tokio::time::timeout(Duration::from_secs(2), entered.notified()).await.unwrap();
        assert_eq!(service.active_refresh_count(), 0);
        assert!(!service.is_refreshing());
        assert_eq!(calls.load(Ordering::SeqCst), 1);

        // 2. While scheduled poll is mid-fetch, `poll_feeds_concurrently` skips the in-flight feed
        service.feeds().poll_feeds_concurrently(vec![feed.clone()]).await;
        assert_eq!(calls.load(Ordering::SeqCst), 1, "Refresh All must skip feed already mid-poll");

        // 3. Explicit single-feed `refresh(id)` increments `active_refresh_count` AND waits for the
        //    in-flight scheduled poll to finish before running its fresh poll.
        let service_for_manual = service.clone();
        let feed_id = feed.id.clone();
        let manual_task =
            tokio::spawn(async move { service_for_manual.feeds().refresh(&feed_id).await });
        for _ in 0..50 {
            if service.active_refresh_count() > 0 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        assert_eq!(service.active_refresh_count(), 1);
        assert_eq!(calls.load(Ordering::SeqCst), 1, "Manual refresh must wait on in-flight lock");

        // Release scheduled poll -> manual refresh acquires lock and executes call #2
        release.notify_one();
        tokio::time::timeout(Duration::from_secs(2), entered.notified()).await.unwrap();
        assert_eq!(calls.load(Ordering::SeqCst), 2);
        release.notify_one();

        manual_task.await.unwrap().unwrap();
        scheduler.shutdown_tasks(Duration::from_secs(2)).await;
        assert_eq!(service.active_refresh_count(), 0);

        // 4. `refresh_all_in_background` still increments `active_refresh_count` while running
        let count = service.feeds().refresh_all_in_background().await.unwrap();
        assert_eq!(count, 1);
        tokio::time::timeout(Duration::from_secs(2), entered.notified()).await.unwrap();
        assert!(
            service.is_refreshing() && service.active_refresh_count() == 1,
            "refresh_all_in_background must keep ActiveRefreshGuard active"
        );
        release.notify_one();
        for _ in 0..50 {
            if !service.is_refreshing() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        assert_eq!(service.active_refresh_count(), 0);
    }

    #[tokio::test]
    async fn test_manual_refresh_bypasses_backoff_increments_on_failure_and_resets_on_success() {
        use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

        struct ToggleFetcher {
            should_fail: Arc<AtomicBool>,
            calls: Arc<AtomicUsize>,
        }
        impl FeedFetcher for ToggleFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                self.calls.fetch_add(1, Ordering::SeqCst);
                let res = if self.should_fail.load(Ordering::SeqCst) {
                    Err(anyhow::anyhow!("upstream HTTP 503"))
                } else {
                    Ok(r#"<?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0"><channel><title>Recovered Feed</title>
                        <item><title>Fresh Post</title><link>https://example.com/fresh-1</link><description>Back online</description></item>
                    </channel></rss>"#
                        .to_owned())
                };
                std::future::ready(res)
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/backoff-manual.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        // Put the active feed deep into exponential backoff (k = 2, recent last_polled).
        db.feeds().mark_error(&feed.id, "HTTP 500").await.unwrap();
        db.feeds().mark_error(&feed.id, "HTTP 500").await.unwrap();
        let backed_off = db.feeds().get(&feed.id).await.unwrap().unwrap();
        assert_eq!(backed_off.consecutive_failures, 2);
        assert!(
            !should_peer_poll_feed(SERVER_PEER_ID, &[], &backed_off, now_utc()),
            "Scheduled poller must keep feed in backoff before due_at"
        );

        let should_fail = Arc::new(AtomicBool::new(true));
        let calls = Arc::new(AtomicUsize::new(0));
        let service = crate::service::RocheService::new(
            db.clone(),
            ToggleFetcher { should_fail: Arc::clone(&should_fail), calls: Arc::clone(&calls) },
            crate::config::Config::default(),
        );

        // 1. Manual refresh bypasses backoff and increments consecutive_failures (2 -> 3) on failure
        let err = service.feeds().refresh(&feed.id).await.unwrap_err();
        assert!(matches!(err, crate::service::AppError::BadGateway(_)));
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        let after_manual_fail = db.feeds().get(&feed.id).await.unwrap().unwrap();
        assert_eq!(after_manual_fail.consecutive_failures, 3);
        assert!(after_manual_fail.last_error.is_some());
        assert_eq!(after_manual_fail.last_error_by.as_deref(), Some(SERVER_PEER_ID));

        // 2. Manual refresh again bypasses backoff and resets consecutive_failures + error on success
        should_fail.store(false, Ordering::SeqCst);
        let recovered = service.feeds().refresh(&feed.id).await.unwrap();
        assert_eq!(calls.load(Ordering::SeqCst), 2);
        assert_eq!(recovered.consecutive_failures, 0);
        assert!(recovered.last_error.is_none());
        assert!(recovered.last_error_by.is_none());
        let items = db.items().list(None, Some(&feed.id), None).await.unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "Fresh Post");
    }

    #[tokio::test]
    #[allow(clippy::significant_drop_tightening)]
    async fn test_spawn_feed_poll_task_drops_guards_when_aborted_before_first_poll() {
        use std::sync::atomic::{AtomicUsize, Ordering};

        struct CountingFetcher {
            calls: Arc<AtomicUsize>,
        }

        impl FeedFetcher for CountingFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                self.calls.fetch_add(1, Ordering::SeqCst);
                std::future::ready(Ok(String::new()))
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/abort-before-poll.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let fetch_calls = Arc::new(AtomicUsize::new(0));
        let shared_locks = FeedPollLocks::new();
        let scheduler = PollScheduler::with_arc_fetcher(
            db,
            Arc::new(CountingFetcher { calls: Arc::clone(&fetch_calls) }),
        )
        .with_feed_poll_locks(shared_locks.clone());

        let feed_id = feed.id.clone();
        let poll_guard =
            shared_locks.try_acquire(&feed_id).expect("initial feed lock acquire must succeed");
        assert!(shared_locks.try_acquire(&feed_id).is_none());

        let mut tasks = tokio::task::JoinSet::new();
        scheduler.spawn_feed_poll_task(&mut tasks, feed, poll_guard);
        // Abort synchronously before yielding so the spawned future is dropped unpolled.
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}

        assert_eq!(fetch_calls.load(Ordering::SeqCst), 0);
        let reacquired = shared_locks.try_acquire(&feed_id);
        assert!(reacquired.is_some(), "poll_guard must be dropped when unpolled task is aborted");
        drop(reacquired);
        assert_eq!(shared_locks.tracked_len(), 0);
    }

    #[tokio::test]
    async fn test_run_until_shutdown_exits_immediately_when_already_true() {
        use std::sync::atomic::{AtomicUsize, Ordering};

        struct CountingFetcher {
            calls: Arc<AtomicUsize>,
        }

        impl FeedFetcher for CountingFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                self.calls.fetch_add(1, Ordering::SeqCst);
                std::future::ready(Ok(String::new()))
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/pre-shutdown.xml").unwrap();
        db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let fetch_calls = Arc::new(AtomicUsize::new(0));
        let scheduler = PollScheduler::with_arc_fetcher(
            db,
            Arc::new(CountingFetcher { calls: Arc::clone(&fetch_calls) }),
        );
        let (tx, rx) = tokio::sync::watch::channel(true);
        let res =
            tokio::time::timeout(Duration::from_secs(1), scheduler.run_until_shutdown(rx)).await;
        assert!(res.is_ok(), "shutdown loop must exit immediately when shutdown starts as true");
        assert!(res.unwrap().is_ok());
        drop(tx);
        assert_eq!(fetch_calls.load(Ordering::SeqCst), 0);
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
}
