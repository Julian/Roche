pub mod catalog;
pub use catalog::CatalogService;

use std::{
    collections::{HashMap, HashSet},
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

use axum::http::HeaderMap;
use chrono::{DateTime, Utc};
use hex::FromHex;
use hmac::{Hmac, KeyInit, Mac};
use serde::Serialize;
use sha2::Sha256;
use subtle::ConstantTimeEq;
use tokio::sync::Semaphore;
use url::Url;

use crate::{
    auth::{pairing::PairingManager, tokens::TokenManager},
    config::Config,
    content::fetcher::{HtmlSanitizer, SafeHttpClient, classify_url},
    db::{
        ActivityEntry, ArchiveBatchScope, ContentType, Db, DeviceToken, Feed, FeedType, HexColor,
        Item, ItemFilter, ItemPage, ItemStatus, ItemTag, ManualItemInput, Media, PeerSyncRequest,
        PeerSyncResponse, ReadAtPatch, SyncRepo, Tag,
    },
    error::ApiError,
    feeds::{FeedSource, OpmlDocument, YouTubeParser},
    media::{MediaStore, ytdlp::YtDlpWorker},
    scheduler::{
        FeedFetcher, FeedIngester, FeedPollLocks, HttpFeedFetcher, MediaIngestContext, PollError,
    },
};

const MAX_DEVICE_NAME_LEN: usize = 128;
const MAX_SYNC_URL_LEN: usize = 2048;
const PAIR_RATE_LIMIT_WINDOW: Duration = Duration::from_secs(60);
const PAIR_RATE_LIMIT_MAX_FAILURES: usize = 4;
const MAX_TRACKED_PAIRING_IPS: usize = 4_096;
const MAX_TAILSCALE_WEB_TOKENS_PER_USER: i64 = 3;

const MAX_SYNC_ENTRIES: usize = 15_000;
const MAX_SYNC_TOMBSTONES: usize = 10_000;
const MAX_SYNC_COLLECTION_ENTRIES: usize = 25_000;
const MAX_SYNC_GENERATORS: usize = 64;
const MAX_SYNC_CONSUMPTION_LOG: usize = 25_000;
const MAX_SYNC_COMPARISONS: usize = 25_000;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum PairingClientKey {
    DirectIp(IpAddr),
    TailscaleLogin(String),
}

#[derive(Debug, Default)]
struct PairingRateLimiterState {
    active_hash: Option<String>,
    by_client: HashMap<PairingClientKey, Vec<Instant>>,
}

#[derive(Debug, Default)]
struct PairingRateLimiter {
    state: Mutex<PairingRateLimiterState>,
}

impl PairingRateLimiter {
    fn check_and_record(&self, active_hash: &str, client: PairingClientKey) -> bool {
        let now = Instant::now();
        let mut guard = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if guard.active_hash.as_deref() != Some(active_hash) {
            guard.active_hash = Some(active_hash.to_owned());
            guard.by_client.clear();
        }
        guard.by_client.retain(|_, timestamps| {
            timestamps.retain(|&ts| now.duration_since(ts) < PAIR_RATE_LIMIT_WINDOW);
            !timestamps.is_empty()
        });
        if let Some(timestamps) = guard.by_client.get_mut(&client) {
            if timestamps.len() >= PAIR_RATE_LIMIT_MAX_FAILURES {
                return false;
            }
            timestamps.push(now);
            return true;
        }
        if guard.by_client.len() >= MAX_TRACKED_PAIRING_IPS
            && let Some(oldest_key) = guard
                .by_client
                .iter()
                .min_by_key(|(_, ts)| ts.last().copied().unwrap_or(now))
                .map(|(k, _)| k.clone())
        {
            guard.by_client.remove(&oldest_key);
        }
        guard.by_client.insert(client, vec![now]);
        true
    }

    fn reset(&self) {
        let mut guard = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        guard.active_hash = None;
        guard.by_client.clear();
    }
}

fn is_valid_sync_item_url(url: &Url) -> bool {
    matches!(url.scheme(), "http" | "https")
        && url.host_str().is_some()
        && url.username().is_empty()
        && url.password().is_none()
        && url.as_str().len() <= MAX_SYNC_URL_LEN
}

#[derive(Debug, Clone, Serialize)]
pub struct ItemDetail {
    #[serde(flatten)]
    pub item: Item,
    pub tags: Vec<Tag>,
    pub media: Vec<Media>,
}

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

#[derive(Debug, Clone, Serialize)]
pub struct SyncStatus {
    pub status: &'static str,
    pub feeds_count: usize,
    pub unread_items_count: i64,
    pub archived_items_count: i64,
    pub latest_activity_id: i64,
    pub is_refreshing: bool,
    pub active_refresh_count: usize,
    pub timestamp: DateTime<Utc>,
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

#[derive(Debug, Clone, Serialize)]
pub struct SyncChanges {
    pub changes: Vec<ActivityEntry>,
    pub next_since_id: i64,
    pub latest_activity_id: i64,
    pub has_more: bool,
}

/// Application service layer coordinating repositories, domain validation, and external fetchers.
#[derive(Debug)]
pub struct RocheService<F = HttpFeedFetcher> {
    db: Db,
    fetcher: Arc<F>,
    config: Config,
    media_store: MediaStore,
    ytdlp_worker: YtDlpWorker,
    download_semaphore: Arc<Semaphore>,
    active_refreshes: Arc<AtomicUsize>,
    feed_poll_locks: FeedPollLocks,
    pairing_limiter: Arc<PairingRateLimiter>,
    tailscale_web_sessions: Arc<Mutex<HashMap<String, String>>>,
    node_identity: Option<crate::tailscale::NodeIdentity>,
}

impl<F> Clone for RocheService<F> {
    fn clone(&self) -> Self {
        Self {
            db: self.db.clone(),
            fetcher: Arc::clone(&self.fetcher),
            config: self.config.clone(),
            media_store: self.media_store.clone(),
            ytdlp_worker: self.ytdlp_worker.clone(),
            download_semaphore: Arc::clone(&self.download_semaphore),
            active_refreshes: Arc::clone(&self.active_refreshes),
            feed_poll_locks: self.feed_poll_locks.clone(),
            pairing_limiter: Arc::clone(&self.pairing_limiter),
            tailscale_web_sessions: Arc::clone(&self.tailscale_web_sessions),
            node_identity: self.node_identity.clone(),
        }
    }
}

impl<F: FeedFetcher> RocheService<F> {
    #[must_use]
    pub fn new(db: Db, fetcher: F, config: Config) -> Self {
        let media_store = MediaStore::new(&config.storage.media_dir);
        let ytdlp_worker = YtDlpWorker::new(config.media.clone(), media_store.clone());
        let download_semaphore =
            Arc::new(Semaphore::new(config.media.max_concurrent_downloads.max(1)));
        Self {
            db,
            fetcher: Arc::new(fetcher),
            config,
            media_store,
            ytdlp_worker,
            download_semaphore,
            active_refreshes: Arc::new(AtomicUsize::new(0)),
            feed_poll_locks: FeedPollLocks::new(),
            pairing_limiter: Arc::new(PairingRateLimiter::default()),
            tailscale_web_sessions: Arc::new(Mutex::new(HashMap::new())),
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
    pub const fn auth(&self) -> AuthService<'_, F> {
        AuthService { service: self }
    }

    #[must_use]
    pub fn resolve_pairing_client_key(
        &self,
        peer_addr: Option<SocketAddr>,
        headers: &HeaderMap,
    ) -> PairingClientKey {
        if self.config.tailscale.enabled && peer_addr.is_some_and(|addr| addr.ip().is_loopback()) {
            if let Some(ts_login) = headers
                .get("tailscale-user-login")
                .and_then(|v| v.to_str().ok())
                .map(|s| s.trim().to_ascii_lowercase())
                .filter(|s| !s.is_empty())
            {
                return PairingClientKey::TailscaleLogin(ts_login);
            }
            if let Some(fwd_ip) = headers
                .get("x-forwarded-for")
                .and_then(|v| v.to_str().ok())
                .and_then(|s| s.rsplit(',').next())
                .and_then(|raw| raw.trim().parse::<IpAddr>().ok())
            {
                return PairingClientKey::DirectIp(fwd_ip);
            }
        }
        PairingClientKey::DirectIp(
            peer_addr.map_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED), |addr| addr.ip()),
        )
    }

    pub async fn pair_device_for_request(
        &self,
        device_name: &str,
        pairing_code: &str,
        peer_addr: Option<SocketAddr>,
        headers: &HeaderMap,
    ) -> Result<String, ApiError> {
        let key = self.resolve_pairing_client_key(peer_addr, headers);
        self.auth().pair_device_with_key(device_name, pairing_code, key).await
    }

    #[must_use]
    pub const fn feeds(&self) -> FeedService<'_, F> {
        FeedService { service: self }
    }

    #[must_use]
    pub const fn items(&self) -> ItemService<'_, F> {
        ItemService { service: self }
    }

    #[must_use]
    pub const fn tags(&self) -> TagService<'_, F> {
        TagService { service: self }
    }

    #[must_use]
    pub const fn media(&self) -> MediaService<'_, F> {
        MediaService { service: self }
    }

    #[must_use]
    pub const fn catalog(&self) -> CatalogService<'_> {
        CatalogService::new(&self.db)
    }

    #[must_use]
    pub fn poll_scheduler(&self) -> crate::scheduler::PollScheduler<F> {
        crate::scheduler::PollScheduler::with_arc_fetcher(
            self.db.clone(),
            Arc::clone(&self.fetcher),
        )
        .with_feed_poll_locks(self.feed_poll_locks.clone())
        .with_media(
            self.media_store.clone(),
            self.ytdlp_worker.clone(),
            Arc::clone(&self.download_semaphore),
        )
    }

    fn media_ingest_context(&self) -> MediaIngestContext<'_, F> {
        MediaIngestContext {
            db: &self.db,
            media_store: &self.media_store,
            ytdlp_worker: Some(&self.ytdlp_worker),
            download_semaphore: Some(&self.download_semaphore),
            fetcher: self.fetcher.as_ref(),
        }
    }

    pub async fn export_data(&self) -> Result<ExportBundle, ApiError> {
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
            exported_at: SyncRepo::now_utc(),
            feeds,
            items,
            tags,
            item_tags,
            activity,
        })
    }

    pub async fn sync_status(&self) -> Result<SyncStatus, ApiError> {
        let feeds = self.db.feeds().list().await?;
        let unread_items_count = self.db.items().count_unread().await?;
        let archived_items_count = self.db.items().count_by_status(ItemStatus::Archived).await?;
        let latest_activity_id = self.db.sync().latest_activity_id().await?;

        let active_refresh_count = self.active_refresh_count();
        Ok(SyncStatus {
            status: "ok",
            feeds_count: feeds.len(),
            unread_items_count,
            archived_items_count,
            latest_activity_id,
            is_refreshing: active_refresh_count > 0,
            active_refresh_count,
            timestamp: SyncRepo::now_utc(),
        })
    }

    pub async fn sync_changes(
        &self,
        since_id: Option<i64>,
        limit: Option<i64>,
    ) -> Result<SyncChanges, ApiError> {
        let since = since_id.unwrap_or(0).max(0);
        let lim = limit.unwrap_or(500).clamp(1, 1000);
        let mut changes = self.db.sync().list_changes_since(since, lim.saturating_add(1)).await?;
        let limit_usize = usize::try_from(lim).unwrap_or(500);
        let has_more = changes.len() > limit_usize;
        changes.truncate(limit_usize);
        let next_since_id = changes.last().map_or(since, |c| c.id);
        let latest_activity_id = self.db.sync().latest_activity_id().await?;
        Ok(SyncChanges { changes, next_since_id, latest_activity_id, has_more })
    }

    #[allow(clippy::too_many_lines)]
    pub async fn peer_sync(&self, mut req: PeerSyncRequest) -> Result<PeerSyncResponse, ApiError> {
        let trimmed_peer = req.peer_id.trim();
        if trimmed_peer.is_empty() || trimmed_peer.len() > MAX_DEVICE_NAME_LEN {
            return Err(ApiError::bad_request(
                "peer_id must be non-empty and at most 128 characters",
            ));
        }

        for (field, len, limit) in [
            ("entries", req.entries.len(), MAX_SYNC_ENTRIES),
            ("tombstones", req.tombstones.len(), MAX_SYNC_TOMBSTONES),
            ("collection_entries", req.collection_entries.len(), MAX_SYNC_COLLECTION_ENTRIES),
            ("collection_generators", req.collection_generators.len(), MAX_SYNC_GENERATORS),
            ("consumption_log", req.consumption_log.len(), MAX_SYNC_CONSUMPTION_LOG),
            ("comparisons", req.comparisons.len(), MAX_SYNC_COMPARISONS),
        ] {
            if len > limit {
                return Err(ApiError::payload_too_large(format!(
                    "peer_sync '{field}' array ({len}) exceeds per-request limit ({limit}); paginate into smaller batches"
                )));
            }
        }

        let mut skipped_feed_ids = std::collections::HashSet::new();
        req.entries.retain_mut(|entry| {
            if let Some(ref mut fs) = entry.feed_source {
                let Ok(parsed_feed_url) = Url::parse(fs.feed_url.trim()) else {
                    if !entry.id.trim().is_empty() {
                        skipped_feed_ids.insert(entry.id.trim().to_owned());
                    }
                    return false;
                };
                if let Err(err) = SafeHttpClient::validate_url(&parsed_feed_url) {
                    tracing::warn!(
                        url = %parsed_feed_url,
                        error = %err,
                        "Skipping non-public or disallowed feed URL in peer_sync"
                    );
                    if !entry.id.trim().is_empty() {
                        skipped_feed_ids.insert(entry.id.trim().to_owned());
                    }
                    skipped_feed_ids
                        .insert(crate::db::deterministic_feed_id(parsed_feed_url.as_str()));
                    return false;
                }
                match FeedSource::Url(parsed_feed_url).into_feed_url(fs.source_type) {
                    Ok((resolved_type, validated_url)) => {
                        fs.source_type = resolved_type;
                        fs.feed_url = validated_url.to_string();
                        return true;
                    }
                    Err(err) => {
                        tracing::warn!(
                            url = %fs.feed_url,
                            error = %err,
                            "Skipping incompatible feed URL in peer_sync"
                        );
                        if !entry.id.trim().is_empty() {
                            skipped_feed_ids.insert(entry.id.trim().to_owned());
                        }
                        return false;
                    }
                }
            }

            if let Some(raw_url) = entry.url.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                let valid = Url::parse(raw_url).as_ref().is_ok_and(is_valid_sync_item_url);
                if !valid {
                    tracing::warn!(
                        url = %raw_url,
                        "Skipping or clearing invalid entry URL in peer_sync"
                    );
                    if entry.state.is_some() || entry.content.is_some() {
                        return false;
                    }
                    entry.url = None;
                }
            }

            if let Some(ref mut ct) = entry.content
                && ct.audio_url.as_deref().map(str::trim).filter(|s| !s.is_empty()).is_some_and(
                    |u| {
                        Url::parse(u).map_or(true, |parsed| {
                            !is_valid_sync_item_url(&parsed)
                                || SafeHttpClient::validate_url(&parsed).is_err()
                        })
                    },
                )
            {
                tracing::warn!(
                    id = %entry.id,
                    "Clearing invalid or disallowed audio_url on synced entry in peer_sync"
                );
                ct.audio_url = None;
            }

            if entry.thumbnail_url.as_deref().is_some_and(|u| u.trim().len() > MAX_SYNC_URL_LEN) {
                tracing::warn!(
                    id = %entry.id,
                    "Clearing oversized thumbnail_url on synced entry in peer_sync"
                );
                entry.thumbnail_url = None;
            }
            true
        });

        if !skipped_feed_ids.is_empty() {
            let mut orphaned_feed_items = std::collections::HashSet::new();
            for ce in &req.collection_entries {
                if skipped_feed_ids.contains(ce.collection_id.trim()) {
                    orphaned_feed_items.insert(ce.entry_id.trim().to_owned());
                }
            }
            req.collection_entries.retain(|ce| !skipped_feed_ids.contains(ce.collection_id.trim()));
            if !orphaned_feed_items.is_empty() {
                let still_linked: std::collections::HashSet<&str> =
                    req.collection_entries.iter().map(|ce| ce.entry_id.trim()).collect();
                req.entries.retain(|e| {
                    let id = e.id.trim();
                    !orphaned_feed_items.contains(id) || still_linked.contains(id)
                });
            }
        }

        let (response, mut inserted_candidates) = self.db.sync().merge_peer_state(&req).await?;
        inserted_candidates.retain(|(_, c)| SafeHttpClient::validate_url(&c.url).is_ok());

        if !inserted_candidates.is_empty() {
            let service_clone = self.clone();
            tokio::spawn(async move {
                let ctx = service_clone.media_ingest_context();
                FeedIngester::ingest_items_concurrently(&ctx, &inserted_candidates).await;
            });
        }

        Ok(response)
    }
}

pub struct AuthService<'a, F> {
    service: &'a RocheService<F>,
}

impl<F: FeedFetcher> AuthService<'_, F> {
    pub async fn issue_pairing_code(&self) -> Result<String, ApiError> {
        let pairing = PairingManager::new(self.service.db.auth());
        let code = pairing.create_code(self.service.config.auth.pairing_code_ttl).await?;
        self.service.pairing_limiter.reset();
        Ok(code)
    }

    pub async fn pair_device_with_key(
        &self,
        device_name: &str,
        pairing_code: &str,
        client_key: PairingClientKey,
    ) -> Result<String, ApiError> {
        let trimmed_name = device_name.trim();
        if trimmed_name.is_empty() {
            return Err(ApiError::bad_request("device_name cannot be empty"));
        }
        if trimmed_name.len() > MAX_DEVICE_NAME_LEN {
            return Err(ApiError::bad_request(format!(
                "device_name cannot exceed {MAX_DEVICE_NAME_LEN} characters"
            )));
        }

        let Some(active_hash) = self.service.db.auth().active_pairing_code_hash().await? else {
            return Err(ApiError::unauthorized("Invalid or expired pairing code"));
        };

        if !self.service.pairing_limiter.check_and_record(&active_hash, client_key) {
            return Err(ApiError::too_many_requests(
                "Too many failed pairing attempts; please wait before retrying",
            ));
        }

        let pairing = PairingManager::new(self.service.db.auth());
        if !pairing.consume_valid_code(pairing_code.trim()).await? {
            return Err(ApiError::unauthorized("Invalid or expired pairing code"));
        }

        self.service.pairing_limiter.reset();

        let tokens = TokenManager::new(self.service.db.auth());
        let token = tokens.create(trimmed_name, self.service.config.auth.token_length).await?;
        Ok(token)
    }

    pub async fn authenticate(&self, bearer_token: &str) -> Result<(), ApiError> {
        let tokens = TokenManager::new(self.service.db.auth());
        if !tokens.validate(bearer_token).await? {
            return Err(ApiError::unauthorized("Invalid or revoked bearer token"));
        }
        Ok(())
    }

    pub async fn count_devices(&self) -> Result<i64, ApiError> {
        Ok(self.service.db.auth().count_devices().await?)
    }

    pub async fn list_devices(&self) -> Result<Vec<DeviceToken>, ApiError> {
        Ok(self.service.db.auth().list_devices().await?)
    }

    pub async fn revoke_device(&self, id: &str) -> Result<(), ApiError> {
        if !self.service.db.auth().revoke_device(id).await? {
            return Err(ApiError::not_found("Device not found"));
        }
        Ok(())
    }

    fn csrf_mac_bytes(raw_session_token: &str) -> [u8; 32] {
        type HmacSha256 = Hmac<Sha256>;
        let mut mac = HmacSha256::new_from_slice(b"roche-web-csrf-hmac-v1")
            .expect("HMAC-SHA256 accepts keys of any size");
        mac.update(raw_session_token.as_bytes());
        mac.finalize().into_bytes().into()
    }

    #[must_use]
    pub fn csrf_token_for_session(&self, raw_session_token: &str) -> String {
        let _ = self;
        hex::encode(Self::csrf_mac_bytes(raw_session_token))
    }

    #[must_use]
    pub fn verify_csrf_token(&self, raw_session_token: &str, candidate: &str) -> bool {
        let _ = self;
        let Ok(cand_bytes) = <[u8; 32]>::from_hex(candidate.trim()) else {
            return false;
        };
        let exp_bytes = Self::csrf_mac_bytes(raw_session_token);
        bool::from(exp_bytes.ct_eq(&cand_bytes))
    }

    pub async fn mint_tailscale_web_session(&self, login: &str) -> Result<String, ApiError> {
        let clean_login = login.trim().to_ascii_lowercase();
        let cached = self
            .service
            .tailscale_web_sessions
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .get(&clean_login)
            .cloned();
        if let Some(existing) = cached
            && self.authenticate(&existing).await.is_ok()
        {
            return Ok(existing);
        }

        let raw_name = format!("Roche Web ({clean_login})");
        let safe_name: String = raw_name.chars().take(MAX_DEVICE_NAME_LEN).collect();
        let auth_repo = self.service.db.auth();
        let tokens = TokenManager::new(auth_repo);
        let minted = tokens.create(&safe_name, self.service.config.auth.token_length).await?;
        let _ = self
            .service
            .db
            .auth()
            .prune_excess_named_tokens(&safe_name, MAX_TAILSCALE_WEB_TOKENS_PER_USER)
            .await;
        self.service
            .tailscale_web_sessions
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .insert(clean_login, minted.clone());
        Ok(minted)
    }

    pub async fn revoke_session_token(&self, raw_session_token: &str) -> Result<bool, ApiError> {
        {
            let mut guard = self
                .service
                .tailscale_web_sessions
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            guard.retain(|_, tok| tok != raw_session_token);
        }
        let token_hash = TokenManager::hash_token(raw_session_token);
        Ok(self.service.db.auth().revoke_device_by_token_hash(&token_hash).await?)
    }
}

pub struct FeedService<'a, F> {
    service: &'a RocheService<F>,
}

impl<F: FeedFetcher> FeedService<'_, F> {
    pub async fn list(&self) -> Result<Vec<Feed>, ApiError> {
        Ok(self.service.db.feeds().list().await?)
    }

    pub async fn get(&self, id: &str) -> Result<Feed, ApiError> {
        self.service.db.feeds().get(id).await?.ok_or_else(|| ApiError::not_found("Feed not found"))
    }

    pub async fn create(
        &self,
        feed_type: FeedType,
        source: FeedSource,
        title: Option<&str>,
        description: Option<&str>,
    ) -> Result<Feed, ApiError> {
        let (initial_type, validated_url) = source
            .into_feed_url(feed_type)
            .map_err(|e| ApiError::bad_request(format!("Invalid feed URL: {e}")))?;

        let (resolved_type, final_url) =
            self.discover_feed_url_if_needed(initial_type, validated_url).await?;

        let feed = self
            .service
            .db
            .feeds()
            .create_with_interval(
                resolved_type,
                &final_url,
                title.map(str::trim).filter(|t| !t.is_empty()),
                description.map(str::trim).filter(|d| !d.is_empty()),
                self.service.config.feeds.default_poll_interval,
            )
            .await?;

        Ok(feed)
    }

    async fn discover_feed_url_if_needed(
        &self,
        initial_type: FeedType,
        validated_url: Url,
    ) -> Result<(FeedType, Url), ApiError> {
        if YouTubeParser::is_youtube_feed_url(&validated_url)
            || crate::feeds::ArxivParser::is_arxiv_host(validated_url.host_str())
        {
            return Ok((initial_type, validated_url));
        }

        let probe_type = match initial_type {
            FeedType::Youtube => FeedType::Rss,
            other => other,
        };

        let mut html_candidate = if let Ok(Ok(raw_body)) = tokio::time::timeout(
            Duration::from_secs(8),
            self.service.fetcher.fetch_feed(&validated_url),
        )
        .await
        {
            let body_clone = raw_body.clone();
            let parsed_ok = tokio::task::spawn_blocking(move || {
                FeedIngester::parse_feed_content(probe_type, &body_clone).is_ok()
            })
            .await
            .unwrap_or(false);
            if parsed_ok {
                return Ok((initial_type, validated_url));
            }
            Some(raw_body)
        } else {
            None
        };

        if html_candidate.is_none()
            && let Ok(Ok(Some(fetched_html))) = tokio::time::timeout(
                Duration::from_secs(8),
                self.service.fetcher.fetch_html(&validated_url),
            )
            .await
        {
            html_candidate = Some(fetched_html);
        }

        let Some(raw_html) = html_candidate else {
            return Ok((initial_type, validated_url));
        };

        let base_clone = validated_url.clone();
        let html_for_disc = raw_html.clone();
        let candidates = tokio::task::spawn_blocking(move || {
            HtmlSanitizer::discover_feed_candidates(&html_for_disc, &base_clone)
        })
        .await
        .unwrap_or_default();

        for cand in candidates {
            if YouTubeParser::is_youtube_feed_url(&cand) {
                return Ok((FeedType::Youtube, cand));
            }
            if let Ok(Ok(cand_body)) =
                tokio::time::timeout(Duration::from_secs(6), self.service.fetcher.fetch_feed(&cand))
                    .await
            {
                let cand_parse_type = match initial_type {
                    FeedType::Podcast => FeedType::Podcast,
                    _ => FeedType::Rss,
                };
                let ok = tokio::task::spawn_blocking(move || {
                    FeedIngester::parse_feed_content(cand_parse_type, &cand_body).is_ok()
                })
                .await
                .unwrap_or(false);
                if ok {
                    let final_type = match initial_type {
                        FeedType::Podcast => FeedType::Podcast,
                        _ => FeedType::Rss,
                    };
                    return Ok((final_type, cand));
                }
            }
        }

        let lower_prefix =
            raw_html.trim_start().get(..256).unwrap_or(&raw_html).to_ascii_lowercase();
        if lower_prefix.contains("<!doctype html") || lower_prefix.contains("<html") {
            return Err(ApiError::bad_request(format!(
                "No RSS or Atom feed found at {validated_url}"
            )));
        }

        Ok((initial_type, validated_url))
    }

    pub async fn update(
        &self,
        id: &str,
        title: Option<&str>,
        poll_interval: Option<i64>,
        is_active: Option<bool>,
    ) -> Result<Feed, ApiError> {
        let clean_title = match title {
            Some(t) => {
                let trimmed = t.trim();
                if trimmed.is_empty() {
                    return Err(ApiError::bad_request("Feed title cannot be empty"));
                }
                Some(trimmed)
            }
            None => None,
        };

        if let Some(interval) = poll_interval {
            let min = self.service.config.feeds.min_poll_interval;
            let max = self.service.config.feeds.max_poll_interval;
            if !(min..=max).contains(&interval) {
                return Err(ApiError::bad_request(format!(
                    "poll_interval must be between {min} and {max} minutes"
                )));
            }
        }

        let repo = self.service.db.feeds();
        if !repo.update(id, clean_title, poll_interval, is_active).await? {
            return Err(ApiError::not_found("Feed not found"));
        }

        self.get(id).await
    }

    pub async fn archive(&self, id: &str) -> Result<Feed, ApiError> {
        if !self.service.db.feeds().archive(id).await? {
            return Err(ApiError::not_found("Feed not found"));
        }
        self.get(id).await
    }

    pub async fn unarchive(&self, id: &str) -> Result<Feed, ApiError> {
        if !self.service.db.feeds().unarchive(id).await? {
            return Err(ApiError::not_found("Feed not found"));
        }
        self.get(id).await
    }

    pub async fn delete(&self, id: &str) -> Result<(), ApiError> {
        let repo = self.service.db.feeds();
        let media_repo = self.service.db.media();
        let media_list = media_repo.list_exclusively_owned_by_feed(id).await?;

        if !repo.delete(id).await? {
            return Err(ApiError::not_found("Feed not found"));
        }

        media_repo.delete_with_files(&self.service.media_store, media_list).await?;
        Ok(())
    }

    pub async fn refresh(&self, id: &str) -> Result<Feed, ApiError> {
        let _guard = ActiveRefreshGuard::acquire(&self.service.active_refreshes);
        let poll_guard = self.service.feed_poll_locks.acquire(id).await;
        let feed = self.get(id).await?;
        let ctx = self.service.media_ingest_context();

        let poll_res = FeedIngester::poll_xml_and_record_outcome(
            ctx.db,
            ctx.fetcher,
            &feed,
            "manual_refresh",
            true,
        )
        .await;
        drop(poll_guard);

        match poll_res {
            Ok(outcome) => {
                FeedIngester::ingest_items_concurrently(&ctx, &outcome.inserted).await;
            }
            Err(err) => {
                return match err {
                    PollError::Upstream(upstream) => {
                        Err(ApiError::bad_gateway(format!("Failed to refresh feed: {upstream}")))
                    }
                    PollError::Database(db_err) => Err(ApiError::Database(db_err)),
                };
            }
        }

        self.get(id).await
    }

    pub async fn refresh_all_in_background(&self) -> Result<usize, ApiError> {
        let active_feeds: Vec<Feed> =
            self.list().await?.into_iter().filter(|f| f.is_active).collect();
        let count = active_feeds.len();
        if !active_feeds.is_empty() {
            let service_bg = self.service.clone();
            tokio::spawn(async move {
                service_bg.feeds().poll_feeds_concurrently(active_feeds).await;
            });
        }
        Ok(count)
    }

    pub async fn import_opml(&self, opml_str: String) -> Result<Vec<Feed>, ApiError> {
        let outlines = tokio::task::spawn_blocking(move || OpmlDocument::parse(&opml_str))
            .await
            .map_err(|e| anyhow::anyhow!("OPML parse task failed: {e}"))?;
        let default_interval = self.service.config.feeds.default_poll_interval;
        Ok(self.service.db.feeds().import_outlines(outlines, default_interval).await?)
    }

    pub async fn poll_feeds_concurrently(&self, feeds: Vec<Feed>) {
        if feeds.is_empty() {
            return;
        }
        let poll_sem = Arc::new(Semaphore::new(8));
        let mut join_set = tokio::task::JoinSet::new();

        for feed in feeds {
            let Some(poll_guard) = self.service.feed_poll_locks.try_acquire(&feed.id) else {
                continue;
            };
            let service_clone = self.service.clone();
            let p_sem = Arc::clone(&poll_sem);
            let refresh_guard = ActiveRefreshGuard::acquire(&self.service.active_refreshes);
            join_set.spawn(async move {
                let _task_guard = refresh_guard;
                let ctx = service_clone.media_ingest_context();
                let poll_res = {
                    let Ok(_permit) = p_sem.acquire_owned().await else {
                        drop(poll_guard);
                        return;
                    };
                    FeedIngester::poll_xml_and_record_outcome(
                        ctx.db,
                        ctx.fetcher,
                        &feed,
                        "background_refresh",
                        true,
                    )
                    .await
                };
                drop(poll_guard);
                if let Ok(outcome) = poll_res {
                    FeedIngester::ingest_items_concurrently(&ctx, &outcome.inserted).await;
                }
            });
        }
        while join_set.join_next().await.is_some() {}
    }

    pub async fn export_opml(&self) -> Result<String, ApiError> {
        let feeds = self.list().await?;
        Ok(OpmlDocument::to_xml(&feeds))
    }
}

pub struct ItemService<'a, F> {
    service: &'a RocheService<F>,
}

impl<'a, F: FeedFetcher> ItemService<'a, F> {
    /// Starts a chainable query builder for filtering, searching, sorting, and paginating items.
    #[must_use]
    pub fn query(&self) -> ItemQuery<'a> {
        ItemQuery { db: &self.service.db, filter: ItemFilter::default() }
    }

    pub async fn get(&self, id: &str) -> Result<ItemDetail, ApiError> {
        let item = self
            .service
            .db
            .items()
            .get(id)
            .await?
            .ok_or_else(|| ApiError::not_found("Item not found"))?;
        let tags = self.service.db.tags().list_for_item(id).await?;
        let media = self.service.db.media().list_for_item(id).await?;
        Ok(ItemDetail { item, tags, media })
    }

    pub async fn existing_ids(&self, ids: &[String]) -> Result<HashSet<String>, ApiError> {
        Ok(self.service.db.items().existing_ids(ids).await?)
    }

    pub async fn save(
        &self,
        url: &Url,
        title: Option<&str>,
        tags: &[String],
    ) -> Result<ItemDetail, ApiError> {
        SafeHttpClient::validate_url(url)
            .map_err(|e| ApiError::bad_request(format!("Invalid item URL: {e}")))?;

        let arxiv_id = crate::feeds::ArxivParser::extract_arxiv_id(url.as_str());
        let content_type = if arxiv_id.is_some() { ContentType::Paper } else { classify_url(url) };
        let explicit_title = title.map(str::trim).filter(|t| !t.is_empty());
        let has_explicit_title = explicit_title.is_some();
        let initial_title = explicit_title.unwrap_or_else(|| url.as_str());
        let yt_thumb_fallback = crate::feeds::youtube::YouTubeParser::extract_video_id(url)
            .map(|vid| format!("https://i.ytimg.com/vi/{vid}/hqdefault.jpg"));
        let audio_url = (content_type == ContentType::Audio).then(|| url.as_str());

        let (item, _) = self
            .service
            .db
            .items()
            .save_manual_input_with_tags(&ManualItemInput {
                url,
                title: initial_title,
                summary: None,
                content_html: None,
                thumbnail_url: yt_thumb_fallback.as_deref(),
                audio_url,
                duration: None,
                content_type,
                tags,
            })
            .await?;

        let service_clone = self.service.clone();
        let item_id = item.id.clone();
        let item_url = url.clone();
        let refresh_guard = ActiveRefreshGuard::acquire(&self.service.active_refreshes);

        tokio::spawn(async move {
            let _guard = refresh_guard;
            Self::enrich_and_ingest_saved_item(
                &service_clone,
                &item_id,
                &item_url,
                content_type,
                arxiv_id.as_deref(),
                has_explicit_title,
            )
            .await;
        });

        self.get(&item.id).await
    }

    async fn enrich_and_ingest_saved_item(
        service: &RocheService<F>,
        item_id: &str,
        item_url: &Url,
        content_type: ContentType,
        arxiv_id: Option<&str>,
        has_explicit_title: bool,
    ) {
        if let Some(aid) = arxiv_id
            && let Ok(api_url) = SafeHttpClient::parse_url(&format!(
                "https://export.arxiv.org/api/query?id_list={aid}"
            ))
            && let Ok(Ok(xml)) =
                tokio::time::timeout(Duration::from_secs(10), service.fetcher.fetch_feed(&api_url))
                    .await
            && let Ok(Ok(parsed)) =
                tokio::task::spawn_blocking(move || crate::feeds::ArxivParser::parse(&xml)).await
            && let Some(paper) = parsed.items.into_iter().next()
        {
            let clean_title = paper.title.split_whitespace().collect::<Vec<_>>().join(" ");
            let clean_summary = HtmlSanitizer::plain_text_truncate(
                &paper.summary,
                crate::content::fetcher::MAX_SUMMARY_CHARS,
            );
            let _ = service
                .db
                .items()
                .apply_background_enrichment(
                    item_id,
                    (!clean_title.is_empty()).then_some(clean_title.as_str()),
                    &paper.authors,
                    paper.published_at,
                    (!clean_summary.is_empty()).then_some(clean_summary.as_str()),
                    None,
                    None,
                )
                .await;
        }

        let should_fetch_html = arxiv_id.is_none()
            && (content_type == ContentType::Article
                || (!has_explicit_title
                    && !matches!(content_type, ContentType::Pdf | ContentType::Audio)));
        if should_fetch_html {
            match tokio::time::timeout(
                Duration::from_secs(10),
                service.fetcher.fetch_html(item_url),
            )
            .await
            {
                Ok(Ok(Some(raw_html))) => {
                    let base_url = item_url.clone();
                    if let Ok(snapshot) = tokio::task::spawn_blocking(move || {
                        HtmlSanitizer::extract_snapshot_with_base(&raw_html, Some(&base_url))
                    })
                    .await
                    {
                        let body_html = (content_type == ContentType::Article)
                            .then_some(snapshot.content_html.as_str())
                            .filter(|h| !h.trim().is_empty());
                        let _ = service
                            .db
                            .items()
                            .apply_background_enrichment(
                                item_id,
                                snapshot.title.as_deref(),
                                snapshot.author.as_slice(),
                                None,
                                snapshot.summary.as_deref(),
                                snapshot.thumbnail_url.as_deref(),
                                body_html,
                            )
                            .await;
                    }
                }
                Ok(Ok(None)) => {}
                Ok(Err(e)) => {
                    tracing::warn!(url = %item_url, error = %e, "Failed to fetch HTML snapshot in background");
                }
                Err(_) => {
                    tracing::warn!(url = %item_url, "Timed out fetching HTML snapshot in background");
                }
            }
        }

        let ctx = service.media_ingest_context();
        FeedIngester::ingest_item_media(&ctx, item_id, content_type, item_url).await;
    }

    pub async fn update(
        &self,
        id: &str,
        status: Option<ItemStatus>,
        is_favorite: Option<bool>,
        read_progress: Option<f64>,
        read_at: ReadAtPatch,
    ) -> Result<ItemDetail, ApiError> {
        if let Some(p) = read_progress
            && (!p.is_finite() || !(0.0..=1.0).contains(&p))
        {
            return Err(ApiError::bad_request("read_progress must be between 0.0 and 1.0"));
        }
        if !self.service.db.items().update(id, status, is_favorite, read_progress, read_at).await? {
            return Err(ApiError::not_found("Item not found"));
        }
        self.get(id).await
    }

    pub async fn archive(&self, id: &str) -> Result<ItemDetail, ApiError> {
        if !self.service.db.items().archive(id).await? {
            return Err(ApiError::not_found("Item not found"));
        }
        self.get(id).await
    }

    pub async fn archive_batch(
        &self,
        item_ids: &[String],
        feed_id: Option<&str>,
        all_unread: bool,
    ) -> Result<u64, ApiError> {
        let Some(scope) = ArchiveBatchScope::from_request(item_ids, feed_id, all_unread) else {
            return Err(ApiError::bad_request(
                "Specify non-empty item_ids, feed_id, or all_unread: true",
            ));
        };
        if let Some(fid) = scope.feed_id() {
            let _ = self.service.feeds().get(fid).await?;
        }
        Ok(self.service.db.items().archive_batch(&scope).await?)
    }

    pub async fn unarchive(&self, id: &str) -> Result<ItemDetail, ApiError> {
        if !self.service.db.items().unarchive(id).await? {
            return Err(ApiError::not_found("Item not found"));
        }
        self.get(id).await
    }

    pub async fn delete(&self, id: &str) -> Result<(), ApiError> {
        let item_repo = self.service.db.items();
        let media_repo = self.service.db.media();
        let media_list = media_repo.list_for_item(id).await?;
        let outcome = item_repo.delete_returning_outcome(id).await?;
        if !outcome.deleted {
            return Err(ApiError::not_found("Item not found"));
        }

        if outcome.readable_content_removed {
            media_repo.delete_with_files(&self.service.media_store, media_list).await?;
        }
        Ok(())
    }

    pub async fn add_tag(
        &self,
        item_id: &str,
        tag_id: Option<&str>,
        name: Option<&str>,
    ) -> Result<ItemDetail, ApiError> {
        if self.service.db.items().get(item_id).await?.is_none() {
            return Err(ApiError::not_found("Item not found"));
        }

        if let Some(tid) = tag_id {
            let tag = self.service.tags().get(tid).await?;
            self.service.db.tags().add_to_item(item_id, &tag.id).await?;
        } else if let Some(name) = name {
            let trimmed = name.trim();
            if trimmed.is_empty() {
                return Err(ApiError::bad_request("Tag name cannot be empty"));
            }
            self.service.db.tags().add_named_to_item(item_id, trimmed).await?;
        } else {
            return Err(ApiError::bad_request("Either tag_id or name must be provided"));
        }

        self.get(item_id).await
    }

    pub async fn remove_tag(&self, item_id: &str, tag_id: &str) -> Result<(), ApiError> {
        if self.service.db.items().get(item_id).await?.is_none() {
            return Err(ApiError::not_found("Item not found"));
        }
        let _ = self.service.db.tags().remove_from_item(item_id, tag_id).await?;
        Ok(())
    }

    pub async fn list_media(&self, item_id: &str) -> Result<Vec<Media>, ApiError> {
        if self.service.db.items().get(item_id).await?.is_none() {
            return Err(ApiError::not_found("Item not found"));
        }
        Ok(self.service.db.media().list_for_item(item_id).await?)
    }
}

/// Fluent, chainable query builder for items (`service.items().query().with_filter(...).feed_id(...).list()`).
pub struct ItemQuery<'a> {
    db: &'a Db,
    filter: ItemFilter,
}

impl ItemQuery<'_> {
    #[must_use]
    pub fn with_filter(mut self, filter: ItemFilter) -> Self {
        self.filter = filter;
        self
    }

    #[must_use]
    pub fn feed_id(mut self, feed_id: impl Into<String>) -> Self {
        self.filter.feed_id = Some(feed_id.into());
        self
    }

    pub async fn list_page(self) -> Result<ItemPage, ApiError> {
        Ok(self.db.items().list_filtered_page(&self.filter).await?)
    }
}

pub struct TagService<'a, F> {
    service: &'a RocheService<F>,
}

impl<F: FeedFetcher> TagService<'_, F> {
    pub async fn list(&self) -> Result<Vec<Tag>, ApiError> {
        Ok(self.service.db.tags().list().await?)
    }

    pub async fn get(&self, id: &str) -> Result<Tag, ApiError> {
        self.service.db.tags().get(id).await?.ok_or_else(|| ApiError::not_found("Tag not found"))
    }

    pub async fn create(&self, name: &str, color: Option<&HexColor>) -> Result<Tag, ApiError> {
        let trimmed = name.trim();
        if trimmed.is_empty() {
            return Err(ApiError::bad_request("Tag name cannot be empty"));
        }
        Ok(self.service.db.tags().create(trimmed, color).await?)
    }

    pub async fn update(
        &self,
        id: &str,
        name: Option<&str>,
        color: Option<&HexColor>,
    ) -> Result<Tag, ApiError> {
        let clean_name = match name {
            Some(n) => {
                let trimmed = n.trim();
                if trimmed.is_empty() {
                    return Err(ApiError::bad_request("Tag name cannot be empty"));
                }
                Some(trimmed)
            }
            None => None,
        };

        if !self.service.db.tags().update(id, clean_name, color).await? {
            return Err(ApiError::not_found("Tag not found"));
        }
        self.get(id).await
    }

    pub async fn delete(&self, id: &str) -> Result<(), ApiError> {
        let repo = self.service.db.tags();
        if !repo.delete(id).await? {
            return Err(ApiError::not_found("Tag not found"));
        }
        Ok(())
    }
}

pub struct MediaStream {
    pub content_type: &'static str,
    pub filename: String,
    pub total_len: u64,
    pub file: tokio::fs::File,
}

pub struct MediaService<'a, F> {
    service: &'a RocheService<F>,
}

impl<F: FeedFetcher> MediaService<'_, F> {
    pub async fn open_stream(&self, id: &str) -> Result<MediaStream, ApiError> {
        let repo = self.service.db.media();
        let media = repo.get(id).await?.ok_or_else(|| ApiError::not_found("Media not found"))?;
        let content_type = MediaStore::content_type_for(media.media_type, &media.path);
        let file = self.service.media_store.open_file(&media.path).await.map_err(|e| {
            if e.downcast_ref::<std::io::Error>()
                .is_some_and(|io_err| io_err.kind() == std::io::ErrorKind::NotFound)
            {
                ApiError::not_found("Media file not found on disk")
            } else {
                ApiError::Internal(e)
            }
        })?;
        let total_len = match file.metadata().await {
            Ok(meta) => meta.len(),
            Err(_) => media.file_size.and_then(|s| u64::try_from(s).ok()).unwrap_or(0),
        };
        let filename = std::path::Path::new(&media.path)
            .file_name()
            .and_then(|n| n.to_str())
            .map(|name| {
                name.chars()
                    .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
                    .collect::<String>()
            })
            .filter(|s| !s.trim_matches('.').is_empty() && !s.starts_with('.'))
            .unwrap_or_else(|| "media".to_string());
        Ok(MediaStream { content_type, filename, total_len, file })
    }

    pub async fn delete(&self, id: &str) -> Result<(), ApiError> {
        let repo = self.service.db.media();
        let media = repo.get(id).await?.ok_or_else(|| ApiError::not_found("Media not found"))?;

        self.service.media_store.remove(&media.path).await?;
        repo.delete(id).await?;
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr};

    use super::*;

    #[test]
    fn test_pairing_rate_limiter_bounds_tracked_ips_and_evicts_oldest() {
        let limiter = PairingRateLimiter::default();
        let active_hash = "hash-1";

        for idx in 0..(MAX_TRACKED_PAIRING_IPS + 8) {
            let hi = u8::try_from((idx >> 8) & 0xff).unwrap();
            let lo = u8::try_from(idx & 0xff).unwrap();
            let ip = IpAddr::V4(Ipv4Addr::new(198, 51, hi, lo));
            assert!(limiter.check_and_record(active_hash, PairingClientKey::DirectIp(ip)));
        }

        let guard = limiter.state.lock().unwrap();
        assert_eq!(guard.by_client.len(), MAX_TRACKED_PAIRING_IPS);
    }

    #[test]
    fn test_pairing_rate_limiter_isolates_tailscale_logins_behind_loopback() {
        let limiter = PairingRateLimiter::default();
        let active_hash = "hash-ts";

        for _ in 0..PAIR_RATE_LIMIT_MAX_FAILURES {
            assert!(limiter.check_and_record(
                active_hash,
                PairingClientKey::TailscaleLogin("alice@tailnet.ts.net".to_owned()),
            ));
        }
        assert!(!limiter.check_and_record(
            active_hash,
            PairingClientKey::TailscaleLogin("alice@tailnet.ts.net".to_owned()),
        ));

        // Another Tailscale user behind the same 127.0.0.1 `tailscale serve` proxy is unaffected.
        assert!(limiter.check_and_record(
            active_hash,
            PairingClientKey::TailscaleLogin("bob@tailnet.ts.net".to_owned()),
        ));
    }

    struct DummyServiceFetcher;

    impl FeedFetcher for DummyServiceFetcher {
        fn fetch_feed(
            &self,
            _url: &Url,
        ) -> impl std::future::Future<Output = anyhow::Result<String>> + Send {
            std::future::ready(Ok(String::new()))
        }
    }

    #[tokio::test]
    async fn test_item_service_existing_ids() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let service = RocheService::new(db.clone(), DummyServiceFetcher, Config::default());
        let url = Url::parse("https://example.com/service-existing-id").unwrap();
        let (saved, _) = db
            .items()
            .save_manual_input_with_tags(&ManualItemInput {
                url: &url,
                title: "Service Existing Item",
                summary: None,
                content_html: Some("<p>body</p>"),
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
                tags: &[],
            })
            .await
            .unwrap();

        let existing = service
            .items()
            .existing_ids(&[saved.id.clone(), "missing-item-id".to_owned()])
            .await
            .unwrap();
        assert_eq!(existing.len(), 1);
        assert!(existing.contains(&saved.id));
    }

    struct MapServiceFetcher(HashMap<String, String>);

    impl FeedFetcher for MapServiceFetcher {
        fn fetch_feed(
            &self,
            url: &Url,
        ) -> impl std::future::Future<Output = anyhow::Result<String>> + Send {
            let res = self
                .0
                .get(url.as_str())
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("unexpected URL: {url}"));
            std::future::ready(res)
        }
    }

    #[tokio::test]
    async fn test_discover_feed_url_does_not_bypass_or_misclassify_spoofed_youtube_url() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let spoofed_page = "https://evil.example/page?q=youtube.com/feeds/videos.xml";
        let spoofed_cand = "https://evil.example/feed?q=youtube.com/feeds/videos.xml";
        let plain_html =
            "<!DOCTYPE html><html><head><title>Not a feed</title></head><body>Hi</body></html>";
        let html_with_spoofed_rss = format!(
            "<!DOCTYPE html><html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"{spoofed_cand}\"></head><body>Hi</body></html>"
        );
        let valid_rss = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Evil Blog</title><link>https://evil.example/</link>
            <item><title>Post</title><link>https://evil.example/post</link></item></channel></rss>"#;

        // Case 1: Initial URL containing "youtube.com/feeds/videos.xml" in query must NOT bypass discovery
        let fetcher_plain =
            MapServiceFetcher(HashMap::from([(spoofed_page.to_owned(), plain_html.to_owned())]));
        let svc_plain = RocheService::new(db.clone(), fetcher_plain, Config::default());
        let err = svc_plain
            .feeds()
            .discover_feed_url_if_needed(FeedType::Rss, Url::parse(spoofed_page).unwrap())
            .await
            .unwrap_err();
        assert!(matches!(err, ApiError::BadRequest(_)));

        // Case 2: Discovered candidate containing "youtube.com/feeds/videos.xml" in query must NOT
        // be misclassified as FeedType::Youtube; it is parsed as normal RSS instead.
        let fetcher_rss = MapServiceFetcher(HashMap::from([
            (spoofed_page.to_owned(), html_with_spoofed_rss),
            (spoofed_cand.to_owned(), valid_rss.to_owned()),
        ]));
        let svc_rss = RocheService::new(db, fetcher_rss, Config::default());
        let (feed_type, discovered_url) = svc_rss
            .feeds()
            .discover_feed_url_if_needed(FeedType::Rss, Url::parse(spoofed_page).unwrap())
            .await
            .unwrap();
        assert_eq!(feed_type, FeedType::Rss);
        assert_eq!(discovered_url.as_str(), spoofed_cand);
    }

    #[tokio::test]
    async fn test_arxiv_save_enrichment_preserves_separate_authors_in_creators_json() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let paper_url = Url::parse("https://arxiv.org/abs/2402.54321").unwrap();
        let api_url = "https://export.arxiv.org/api/query?id_list=2402.54321";
        let arxiv_xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>ArXiv Query</title>
          <entry>
            <id>http://arxiv.org/abs/2402.54321v1</id>
            <title>Multi Author Paper</title>
            <summary>Abstract text.</summary>
            <author><name>Alice Smith</name></author>
            <author><name>Bob Jones</name></author>
            <link rel="alternate" type="text/html" href="https://arxiv.org/abs/2402.54321"/>
          </entry>
        </feed>"#;

        let fetcher =
            MapServiceFetcher(HashMap::from([(api_url.to_owned(), arxiv_xml.to_owned())]));
        let service = RocheService::new(db.clone(), fetcher, Config::default());
        let (saved, _) = db
            .items()
            .save_manual_input_with_tags(&ManualItemInput {
                url: &paper_url,
                title: paper_url.as_str(),
                summary: None,
                content_html: None,
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Paper,
                tags: &[],
            })
            .await
            .unwrap();

        ItemService::enrich_and_ingest_saved_item(
            &service,
            &saved.id,
            &paper_url,
            ContentType::Paper,
            Some("2402.54321"),
            false,
        )
        .await;

        let creators_json: String =
            sqlx::query_scalar("SELECT creators_json FROM entries WHERE id = ?")
                .bind(&saved.id)
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert_eq!(creators_json, r#"["Alice Smith","Bob Jones"]"#);

        let item = db.items().get(&saved.id).await.unwrap().unwrap();
        assert_eq!(item.title, "Multi Author Paper");
        assert_eq!(item.author.as_deref(), Some("Alice Smith, Bob Jones"));
    }
}
