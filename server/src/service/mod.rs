mod auth;
mod catalog;
pub mod enrich;
mod error;
mod feeds;
mod items;
mod media;
mod sync;
mod tags;

use std::{
    collections::HashMap,
    future::Future,
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::sync::Semaphore;

pub use self::{
    auth::WEB_SESSION_MAX_AGE_SECS,
    catalog::{
        CatalogService, ClusterSelectFilter, EntryListAppearance, EpScoreCache, PrioritizedCluster,
        RankedCollectionFilter, RankedCollectionView, RankedEntryRow,
    },
    error::AppError,
    feeds::FeedService,
    items::ItemDetail,
    media::unlink_and_delete_media_rows,
    sync::{SyncChanges, SyncStatus},
};
use self::{
    auth::{AuthService, PairingRateLimiter},
    enrich::{ArtistEnricher, EnrichService},
    items::ItemService,
    media::MediaService,
    sync::SyncService,
    tags::TagService,
};
use crate::{
    config::Config,
    content::{HtmlSanitizer, MAX_SUMMARY_CHARS},
    db::{
        ActivityEntry, CatalogEntry, CollectionSummary, ComparisonRecord, ConsumptionLogRow,
        ConsumptionLogView, Db, ExportCollectionEntry, ExportCollectionGenerator, Feed, Item,
        ItemFilter, ItemStatusFilter, ItemTag, Tag, WantSearchMatch,
    },
    domain::EntryKind,
    ingest::{
        DynFeedFetcher, FeedFetcher, FeedPollLocks, MAX_CONCURRENT_POLLS, MediaIngestContext,
    },
    media::{MediaStore, ytdlp::YtDlpWorker},
    util::{clock::now_utc, media_urls::youtube_thumbnail_url, task_tracker::TaskTracker},
};

/// Grouped workspace-wide search results across Catalog, Lists, Want Queues, Inbox, and Diary.
#[derive(Debug, Clone, Default, Serialize)]
pub struct WorkspaceSearchResults {
    pub query: String,
    pub catalog_entries: Vec<CatalogEntry>,
    pub collections: Vec<CollectionSummary>,
    pub want_entries: Vec<WantSearchMatch>,
    pub inbox_items: Vec<Item>,
    pub diary_logs: Vec<ConsumptionLogView>,
}

/// Runs one-time content-layer database maintenance tasks (`normalize_legacy_html_summaries` and
/// `backfill_missing_thumbnails`) using `HtmlSanitizer` outside the `db` module.
pub async fn run_content_startup_maintenance(db: &Db) {
    const NORMALIZE_SUMMARIES_TASK: &str = "normalize_legacy_html_summaries";
    const BACKFILL_THUMBNAILS_TASK: &str = "backfill_missing_thumbnails";

    db.run_maintenance_task_once(NORMALIZE_SUMMARIES_TASK, async {
        db.items().normalize_legacy_html_summaries(normalize_legacy_summary).await.map(|_| ())
    })
    .await;
    db.run_maintenance_task_once(BACKFILL_THUMBNAILS_TASK, async {
        db.items().backfill_missing_thumbnails(extract_backfill_thumbnail).await.map(|_| ())
    })
    .await;
}

#[must_use]
pub fn normalize_legacy_summary(
    raw_summary: &str,
    base_url: Option<&url::Url>,
) -> (String, Option<String>) {
    let excerpt = HtmlSanitizer::plain_text_excerpt(raw_summary, MAX_SUMMARY_CHARS);
    let thumb = if excerpt == raw_summary {
        None
    } else {
        HtmlSanitizer::extract_first_image_url_with_base(raw_summary, base_url)
    };
    (excerpt, thumb)
}

#[must_use]
pub fn extract_backfill_thumbnail(
    content_html: Option<&str>,
    base_url: Option<&url::Url>,
) -> Option<String> {
    content_html
        .and_then(|html| HtmlSanitizer::extract_first_image_url_with_base(html, base_url))
        .or_else(|| base_url.and_then(youtube_thumbnail_url))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExportBundle {
    pub version: String,
    pub exported_at: DateTime<Utc>,
    pub feeds: Vec<Feed>,
    pub items: Vec<Item>,
    pub tags: Vec<Tag>,
    pub item_tags: Vec<ItemTag>,
    pub activity: Vec<ActivityEntry>,
    #[serde(default)]
    pub entries: Vec<CatalogEntry>,
    #[serde(default)]
    pub collections: Vec<CollectionSummary>,
    #[serde(default)]
    pub collection_entries: Vec<ExportCollectionEntry>,
    #[serde(default)]
    pub consumption_log: Vec<ConsumptionLogRow>,
    #[serde(default)]
    pub comparisons: Vec<ComparisonRecord>,
    #[serde(default)]
    pub collection_generators: Vec<ExportCollectionGenerator>,
}

struct ActiveRefreshGuard {
    counter: Arc<AtomicUsize>,
}

impl ActiveRefreshGuard {
    fn acquire(counter: &Arc<AtomicUsize>) -> Self {
        counter.fetch_add(1, Ordering::AcqRel);
        Self { counter: Arc::clone(counter) }
    }
}

impl Drop for ActiveRefreshGuard {
    fn drop(&mut self) {
        self.counter.fetch_sub(1, Ordering::AcqRel);
    }
}

/// Application service layer coordinating repositories, domain validation, and external fetchers.
#[derive(Debug, Clone)]
pub struct RocheService {
    db: Db,
    fetcher: DynFeedFetcher,
    config: Config,
    media_store: MediaStore,
    ytdlp_worker: YtDlpWorker,
    download_semaphore: Arc<Semaphore>,
    poll_semaphore: Arc<Semaphore>,
    active_refreshes: Arc<AtomicUsize>,
    feed_poll_locks: FeedPollLocks,
    task_tracker: TaskTracker,
    ep_cache: EpScoreCache,
    pairing_limiter: Arc<PairingRateLimiter>,
    tailscale_web_sessions: Arc<Mutex<HashMap<String, String>>>,
    web_session_last_touch: Arc<Mutex<HashMap<String, Instant>>>,
    artist_enricher: Arc<ArtistEnricher>,
    node_identity: Option<crate::tailscale::NodeIdentity>,
}

impl RocheService {
    #[must_use]
    pub fn new(db: Db, fetcher: impl FeedFetcher, config: Config) -> Self {
        let media_store = MediaStore::new(&config.storage.media_dir);
        let ytdlp_worker = YtDlpWorker::new(config.media.clone(), media_store.clone());
        let download_semaphore =
            Arc::new(Semaphore::new(config.media.max_concurrent_downloads.max(1)));
        Self {
            db,
            fetcher: DynFeedFetcher::new(fetcher),
            config,
            media_store,
            ytdlp_worker,
            download_semaphore,
            poll_semaphore: Arc::new(Semaphore::new(MAX_CONCURRENT_POLLS)),
            active_refreshes: Arc::new(AtomicUsize::new(0)),
            feed_poll_locks: FeedPollLocks::new(),
            task_tracker: TaskTracker::new(),
            ep_cache: EpScoreCache::new(),
            pairing_limiter: Arc::new(PairingRateLimiter::default()),
            tailscale_web_sessions: Arc::new(Mutex::new(HashMap::new())),
            web_session_last_touch: Arc::new(Mutex::new(HashMap::new())),
            artist_enricher: Arc::new(ArtistEnricher::default_for_runtime()),
            node_identity: None,
        }
    }

    /// Spawns a tracked background task tied to this service's shutdown lifecycle.
    pub fn spawn_background<F>(&self, fut: F) -> bool
    where
        F: Future<Output = ()> + Send + 'static,
    {
        self.task_tracker.spawn(fut)
    }

    #[must_use]
    pub fn background_task_count(&self) -> usize {
        self.task_tracker.active_count()
    }

    /// Stops accepting new background tasks and waits up to `timeout` for in-flight tasks to finish.
    pub async fn shutdown_background_tasks(&self, timeout: Duration) -> bool {
        self.task_tracker.close_and_wait(timeout).await
    }

    #[must_use]
    pub fn active_refresh_count(&self) -> usize {
        self.active_refreshes.load(Ordering::Acquire)
    }

    #[must_use]
    pub fn is_refreshing(&self) -> bool {
        self.active_refresh_count() > 0
    }

    #[must_use]
    pub const fn config(&self) -> &Config {
        &self.config
    }

    #[must_use]
    pub fn with_node_identity(
        mut self,
        node_identity: Option<crate::tailscale::NodeIdentity>,
    ) -> Self {
        self.node_identity = node_identity;
        self
    }

    #[must_use]
    pub fn health_payload(&self) -> serde_json::Value {
        let canonical_url =
            self.node_identity.as_ref().and_then(crate::tailscale::NodeIdentity::canonical_url);
        let urls = self
            .node_identity
            .as_ref()
            .map_or_else(Vec::new, crate::tailscale::NodeIdentity::candidate_urls);

        serde_json::json!({
            "status": "ok",
            "service": "roche",
            "version": env!("CARGO_PKG_VERSION"),
            "canonical_url": canonical_url,
            "urls": urls,
        })
    }

    #[must_use]
    pub const fn auth(&self) -> AuthService<'_> {
        AuthService { service: self }
    }

    #[must_use]
    pub const fn feeds(&self) -> FeedService<'_> {
        FeedService { service: self }
    }

    #[must_use]
    pub const fn items(&self) -> ItemService<'_> {
        ItemService { service: self }
    }

    #[must_use]
    pub const fn tags(&self) -> TagService<'_> {
        TagService { service: self }
    }

    #[must_use]
    pub const fn media(&self) -> MediaService<'_> {
        MediaService { service: self }
    }

    #[must_use]
    pub const fn sync(&self) -> SyncService<'_> {
        SyncService { service: self }
    }

    #[must_use]
    pub const fn catalog(&self) -> CatalogService<'_> {
        CatalogService::with_cache(&self.db, &self.ep_cache)
    }

    /// Unified workspace-wide search across Catalog entries (`entries_fts`), Lists, Want queues,
    /// Inbox/Reader items (`entries_fts`), and Diary logs (`entries_fts` + notes).
    pub async fn search_workspace(
        &self,
        raw_query: &str,
        limit_per_group: usize,
    ) -> Result<WorkspaceSearchResults, AppError> {
        let trimmed_raw = raw_query.trim();
        let cap = limit_per_group.clamp(1, 100);
        if trimmed_raw.is_empty() {
            return Ok(WorkspaceSearchResults::default());
        }
        let trimmed: String = trimmed_raw.chars().take(256).collect();
        let trimmed = trimmed.trim();
        if trimmed.is_empty() {
            return Ok(WorkspaceSearchResults::default());
        }
        let lower_q = trimmed.to_lowercase();
        let item_filter = ItemFilter {
            status: Some(ItemStatusFilter::All),
            search: Some(trimmed.to_owned()),
            include_content: Some(false),
            per_page: Some(cap),
            ..ItemFilter::default()
        };
        let catalog = self.catalog();
        let (raw_entries, all_collections, want_entries, item_page, diary_logs) = tokio::try_join!(
            catalog.search_entries(Some(trimmed), None, cap.saturating_mul(2).min(200)),
            catalog.list_collections(false, false),
            catalog.search_want_entries(trimmed, cap),
            self.items().query().with_filter(item_filter).list_page(),
            catalog.search_diary_logs(trimmed, cap),
        )?;

        let catalog_entries: Vec<CatalogEntry> =
            raw_entries.into_iter().filter(|e| e.kind != EntryKind::Collection).take(cap).collect();
        let collections: Vec<CollectionSummary> = all_collections
            .into_iter()
            .filter(|c| {
                c.title.to_lowercase().contains(&lower_q)
                    || c.slug.as_deref().is_some_and(|s| s.to_lowercase().contains(&lower_q))
                    || c.summary.as_deref().is_some_and(|s| s.to_lowercase().contains(&lower_q))
            })
            .take(cap)
            .collect();

        Ok(WorkspaceSearchResults {
            query: trimmed.to_owned(),
            catalog_entries,
            collections,
            want_entries,
            inbox_items: item_page.items,
            diary_logs,
        })
    }

    #[must_use]
    pub const fn enrich(&self) -> EnrichService<'_> {
        EnrichService { service: self }
    }

    #[must_use]
    pub fn fetcher(&self) -> DynFeedFetcher {
        self.fetcher.clone()
    }

    #[must_use]
    pub fn feed_poll_locks(&self) -> FeedPollLocks {
        self.feed_poll_locks.clone()
    }

    #[must_use]
    pub fn poll_semaphore(&self) -> Arc<Semaphore> {
        Arc::clone(&self.poll_semaphore)
    }

    #[must_use]
    pub fn media_store(&self) -> MediaStore {
        self.media_store.clone()
    }

    #[must_use]
    pub fn ytdlp_worker(&self) -> YtDlpWorker {
        self.ytdlp_worker.clone()
    }

    #[must_use]
    pub fn download_semaphore(&self) -> Arc<Semaphore> {
        Arc::clone(&self.download_semaphore)
    }

    const fn media_ingest_context(&self) -> MediaIngestContext<'_, DynFeedFetcher> {
        MediaIngestContext {
            db: &self.db,
            media_store: &self.media_store,
            ytdlp_worker: Some(&self.ytdlp_worker),
            download_semaphore: Some(&self.download_semaphore),
            fetcher: &self.fetcher,
        }
    }

    pub async fn export_data(&self) -> Result<ExportBundle, AppError> {
        const MAX_EXPORT_ROWS: usize = 5_000;
        let mut tx = self.db.begin_read().await?;
        let feeds = tx.list_feeds().await?;
        let items = tx.list_items(None, None, Some(MAX_EXPORT_ROWS)).await?;
        let tags = tx.list_tags().await?;
        let item_ids: Vec<String> = items.iter().map(|i| i.id.clone()).collect();
        let item_tags = tx.list_item_tags_for_items(&item_ids).await?;
        let activity = tx.list_activity().await?;
        let catalog = tx.export_catalog_snapshot().await?;
        tx.commit().await?;

        Ok(ExportBundle {
            version: "1.0".to_owned(),
            exported_at: now_utc(),
            feeds,
            items,
            tags,
            item_tags,
            activity,
            entries: catalog.entries,
            collections: catalog.collections,
            collection_entries: catalog.collection_entries,
            consumption_log: catalog.consumption_log,
            comparisons: catalog.comparisons,
            collection_generators: catalog.collection_generators,
        })
    }
}
