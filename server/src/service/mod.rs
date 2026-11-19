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
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
};

use chrono::{DateTime, Utc};
use serde::Serialize;
use tokio::sync::Semaphore;

use self::{
    auth::{AuthService, PairingRateLimiter},
    enrich::{ArtistEnricher, EnrichService},
    items::ItemService,
    media::MediaService,
    sync::SyncService,
    tags::TagService,
};
pub use self::{
    catalog::{CatalogService, EntryListAppearance},
    error::AppError,
    feeds::FeedService,
    items::ItemDetail,
    sync::{SyncChanges, SyncStatus},
};
use crate::{
    config::Config,
    db::{ActivityEntry, Db, Feed, Item, ItemTag, SyncRepo, Tag},
    media::{MediaStore, ytdlp::YtDlpWorker},
    scheduler::{DynFeedFetcher, FeedFetcher, FeedPollLocks, MediaIngestContext},
    util::clock::now_utc,
};

#[derive(Debug, Clone, Serialize)]
pub struct ExportBundle {
    pub version: &'static str,
    pub exported_at: DateTime<Utc>,
    pub feeds: Vec<Feed>,
    pub items: Vec<Item>,
    pub tags: Vec<Tag>,
    pub item_tags: Vec<ItemTag>,
    pub activity: Vec<ActivityEntry>,
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
    active_refreshes: Arc<AtomicUsize>,
    feed_poll_locks: FeedPollLocks,
    pairing_limiter: Arc<PairingRateLimiter>,
    tailscale_web_sessions: Arc<Mutex<HashMap<String, String>>>,
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
            active_refreshes: Arc::new(AtomicUsize::new(0)),
            feed_poll_locks: FeedPollLocks::new(),
            pairing_limiter: Arc::new(PairingRateLimiter::default()),
            tailscale_web_sessions: Arc::new(Mutex::new(HashMap::new())),
            artist_enricher: Arc::new(ArtistEnricher::default_for_runtime()),
            node_identity: None,
        }
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
        CatalogService::new(&self.db)
    }

    #[must_use]
    pub const fn enrich(&self) -> EnrichService<'_> {
        EnrichService { service: self }
    }

    #[must_use]
    pub fn poll_scheduler(&self) -> crate::scheduler::PollScheduler<DynFeedFetcher> {
        crate::scheduler::PollScheduler::with_arc_fetcher(
            self.db.clone(),
            Arc::new(self.fetcher.clone()),
        )
        .with_feed_poll_locks(self.feed_poll_locks.clone())
        .with_media(
            self.media_store.clone(),
            self.ytdlp_worker.clone(),
            Arc::clone(&self.download_semaphore),
        )
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
        let feeds = crate::db::FeedRepo::list_in_tx(&mut tx).await?;
        let items =
            crate::db::ItemRepo::list_in_tx(&mut tx, None, None, Some(MAX_EXPORT_ROWS)).await?;
        let tags = crate::db::TagRepo::list_in_tx(&mut tx).await?;
        let item_ids: Vec<String> = items.iter().map(|i| i.id.clone()).collect();
        let item_tags =
            crate::db::TagRepo::list_item_tags_for_items_in_tx(&mut tx, &item_ids).await?;
        let activity = SyncRepo::list_activity_in_tx(&mut tx).await?;
        tx.commit().await?;

        Ok(ExportBundle {
            version: "1.0",
            exported_at: now_utc(),
            feeds,
            items,
            tags,
            item_tags,
            activity,
        })
    }
}
