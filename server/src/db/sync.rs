use std::collections::{HashMap, HashSet};

use anyhow::Result;
use chrono::{DateTime, SubsecRound, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{SqliteConnection, SqlitePool};
use url::Url;
use uuid::Uuid;

use super::{
    ActivityAction, ActivityEntityType, ContentType, Feed, FeedRepo, FeedType, HexColor, Item,
    ItemFilter, ItemRepo, ItemStatus, PollItemCandidate, Tag, TagRepo,
};
use crate::content::fetcher::{HtmlSanitizer, MAX_SUMMARY_CHARS, SafeHttpClient};

pub const SERVER_PEER_ID: &str = "server";
pub const PEER_LEASE_TTL_MINUTES: i64 = 120;
pub const DEFAULT_ONLINE_PEER_WEIGHT: f64 = 1.0;

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum NetworkTier {
    Wired,
    #[default]
    Wifi,
    Cellular,
    Offline,
}

impl NetworkTier {
    #[must_use]
    pub const fn default_weight(self) -> f64 {
        match self {
            Self::Wired | Self::Wifi | Self::Cellular => DEFAULT_ONLINE_PEER_WEIGHT,
            Self::Offline => 0.0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, sqlx::FromRow)]
pub struct PeerNode {
    pub peer_id: String,
    pub device_name: String,
    pub network_tier: NetworkTier,
    pub weight: f64,
    pub last_seen_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, sqlx::FromRow)]
pub struct SyncTombstone {
    pub entity_type: ActivityEntityType,
    pub natural_key: String,
    pub deleted_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerFeedRecord {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub feed_type: FeedType,
    pub url: Url,
    pub title: Option<String>,
    pub description: Option<String>,
    pub language: Option<String>,
    #[serde(default = "default_poll_interval")]
    pub poll_interval: i64,
    pub last_polled: Option<DateTime<Utc>>,
    pub last_polled_by: Option<String>,
    pub last_error: Option<String>,
    pub last_error_by: Option<String>,
    #[serde(default = "default_true")]
    pub is_active: bool,
    #[serde(default)]
    pub created_at: Option<DateTime<Utc>>,
}

const fn default_poll_interval() -> i64 {
    30
}

const fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerItemRecord {
    #[serde(default)]
    pub id: String,
    pub feed_id: Option<String>,
    pub feed_url: Option<Url>,
    pub title: String,
    pub url: Url,
    pub author: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub created_at: Option<DateTime<Utc>>,
    pub summary: Option<String>,
    pub content_html: Option<String>,
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub audio_url: Option<String>,
    #[serde(default)]
    pub duration: Option<i64>,
    #[serde(default)]
    pub content_type: ContentType,
    #[serde(default)]
    pub status: ItemStatus,
    #[serde(default)]
    pub is_favorite: bool,
    pub read_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub read_progress: f64,
    pub interaction_updated_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub tag_names: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerTagRecord {
    #[serde(default)]
    pub id: String,
    pub name: String,
    pub color: Option<HexColor>,
    #[serde(default)]
    pub created_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct PeerSyncRequest {
    pub peer_id: String,
    pub device_name: Option<String>,
    #[serde(default)]
    pub network_tier: NetworkTier,
    pub weight: Option<f64>,
    pub updated_after: Option<DateTime<Utc>>,
    #[serde(default)]
    pub feeds: Vec<PeerFeedRecord>,
    #[serde(default)]
    pub items: Vec<PeerItemRecord>,
    #[serde(default)]
    pub tags: Vec<PeerTagRecord>,
    #[serde(default)]
    pub tombstones: Vec<SyncTombstone>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerSyncResponse {
    pub server_peer_id: String,
    pub sync_timestamp: DateTime<Utc>,
    pub active_peers: Vec<PeerNode>,
    pub feeds: Vec<Feed>,
    pub items: Vec<Item>,
    pub tags: Vec<Tag>,
    pub tombstones: Vec<SyncTombstone>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct ActivityEntry {
    pub id: i64,
    pub entity_type: ActivityEntityType,
    pub entity_id: String,
    pub action: ActivityAction,
    pub timestamp: DateTime<Utc>,
}

/// Generates a deterministic RFC 4122 v5-shaped UUID from `(kind, natural_key)` using SHA-256
/// so every peer (Rust, Android, CLI, Web) derives the exact same UUID for the same entity.
#[must_use]
pub fn deterministic_entity_uuid(kind: &str, natural_key: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(b"roche:entity:v1:");
    hasher.update(kind.as_bytes());
    hasher.update(b":");
    hasher.update(natural_key.as_bytes());
    let digest = hasher.finalize();
    let mut bytes = [0u8; 16];
    bytes.copy_from_slice(&digest[..16]);
    bytes[6] = (bytes[6] & 0x0f) | 0x50;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    Uuid::from_bytes(bytes).to_string()
}

#[must_use]
pub fn canonicalize_sync_url(raw: &str) -> String {
    let trimmed = raw.trim();
    Url::parse(trimmed).map_or_else(|_| trimmed.to_ascii_lowercase(), String::from)
}

#[must_use]
pub fn feed_natural_key(url: &str) -> String {
    canonicalize_sync_url(url)
}

#[must_use]
pub fn item_natural_key(feed_url: Option<&str>, item_url: &str) -> String {
    let canon_item = canonicalize_sync_url(item_url);
    feed_url.map(str::trim).filter(|s| !s.is_empty()).map_or_else(
        || format!("manual\0{canon_item}"),
        |f_url| format!("{}\0{canon_item}", canonicalize_sync_url(f_url)),
    )
}

#[must_use]
pub fn tag_natural_key(name: &str) -> String {
    name.trim().to_lowercase()
}

#[must_use]
pub fn deterministic_feed_id(url: &str) -> String {
    deterministic_entity_uuid("feed", &feed_natural_key(url))
}

#[must_use]
pub fn deterministic_item_id(feed_url: Option<&str>, item_url: &str) -> String {
    deterministic_entity_uuid("item", &item_natural_key(feed_url, item_url))
}

#[must_use]
pub fn deterministic_tag_id(name: &str) -> String {
    deterministic_entity_uuid("tag", &tag_natural_key(name))
}

/// Maps `(peer_id, feed_url, epoch)` deterministically to a uniform value in `(0.0, 1.0)`
/// using the top 53 bits of SHA-256. Identical across Rust and Kotlin/JVM.
#[must_use]
#[allow(clippy::cast_precision_loss)]
pub fn rendezvous_uniform_unit(peer_id: &str, feed_url: &str, epoch: i64) -> f64 {
    let mut hasher = Sha256::new();
    hasher.update(b"roche:hrw:v1:");
    hasher.update(peer_id.as_bytes());
    hasher.update(b"\0");
    hasher.update(feed_url.as_bytes());
    hasher.update(b"\0");
    hasher.update(epoch.to_string().as_bytes());
    let digest = hasher.finalize();
    let mut head = [0u8; 8];
    head.copy_from_slice(&digest[..8]);
    let raw = u64::from_be_bytes(head);
    let top53 = raw >> 11;
    ((top53 as f64) + 0.5) / ((1u64 << 53) as f64)
}

/// Computes a peer's dynamic polling weight from the work it processed (`feeds_completed`
/// and `items_processed`) and the wall-clock duration `elapsed_secs`.
/// Before any work is measured (`feeds_completed == 0`), returns `DEFAULT_ONLINE_PEER_WEIGHT` (1.0)
/// so all online peers start with an equal preliminary allocation.
#[must_use]
#[allow(clippy::cast_precision_loss)]
pub fn compute_throughput_weight(
    feeds_completed: usize,
    items_processed: usize,
    elapsed_secs: f64,
) -> f64 {
    if feeds_completed == 0 {
        return DEFAULT_ONLINE_PEER_WEIGHT;
    }
    let safe_secs = elapsed_secs.max(0.05);
    let work_units = (feeds_completed as f64) + (items_processed as f64) / 25.0;
    let units_per_sec = work_units / safe_secs;
    units_per_sec.clamp(0.2, 10.0)
}

#[must_use]
#[allow(clippy::cast_precision_loss)]
fn peer_liveness_factor(last_seen_at: DateTime<Utc>, now: DateTime<Utc>) -> f64 {
    let stale_secs = (now - last_seen_at).num_seconds();
    if stale_secs <= 120 { 1.0 } else { 1.0 / (1.0 + ((stale_secs - 120) as f64) / 180.0) }
}

/// Computes the effective polling weight of `peer` for a specific feed, incorporating
/// measured throughput (`peer.weight`) and reachability health (`last_error_by` / `last_polled_by`).
#[must_use]
pub fn effective_peer_weight(
    peer: &PeerNode,
    last_polled_by: Option<&str>,
    last_error: Option<&str>,
    last_error_by: Option<&str>,
) -> f64 {
    if peer.network_tier == NetworkTier::Offline {
        return 0.0;
    }
    let base = peer.weight.clamp(0.0, 10.0);
    if base <= 0.0 {
        return 0.0;
    }
    let clean_err_by = last_error_by.map(str::trim).filter(|s| !s.is_empty());
    let clean_polled_by = last_polled_by.map(str::trim).filter(|s| !s.is_empty());
    if last_error.is_some_and(|e| !e.trim().is_empty()) {
        if clean_err_by == Some(peer.peer_id.as_str()) { base * 0.05 } else { base * 2.0 }
    } else if clean_polled_by == Some(peer.peer_id.as_str()) {
        base * 1.1
    } else {
        base
    }
}

/// Dynamic throughput-weighted Rendezvous Hash (HRW) peer selector.
/// Preliminarily splits feeds equally (`weight = 1.0`) and dynamically shifts larger portions
/// to whichever peer has higher measured processing throughput (`peer.weight`) and liveness.
#[must_use]
#[allow(clippy::too_many_arguments)]
pub fn select_feed_poller<'a>(
    peers: &'a [PeerNode],
    feed_url: &str,
    poll_interval_mins: i64,
    now: DateTime<Utc>,
    last_polled_by: Option<&str>,
    last_error: Option<&str>,
    last_error_by: Option<&str>,
    local_boost: Option<(&str, f64)>,
) -> Option<&'a str> {
    let interval_secs = poll_interval_mins.max(1).saturating_mul(60);
    let epoch = now.timestamp().div_euclid(interval_secs);

    let mut best_peer: Option<(&'a str, f64)> = None;
    for peer in peers {
        let mut weight = effective_peer_weight(peer, last_polled_by, last_error, last_error_by)
            * peer_liveness_factor(peer.last_seen_at, now);
        if let Some((boost_peer_id, factor)) = local_boost
            && peer.peer_id == boost_peer_id
        {
            weight *= factor;
        }
        if weight <= 0.0 {
            continue;
        }
        let u = rendezvous_uniform_unit(&peer.peer_id, feed_url, epoch);
        let score = -weight / u.ln();
        match best_peer {
            None => best_peer = Some((peer.peer_id.as_str(), score)),
            Some((best_id, best_score)) => {
                if score > best_score
                    || ((score - best_score).abs() <= f64::EPSILON
                        && peer.peer_id.as_str() < best_id)
                {
                    best_peer = Some((peer.peer_id.as_str(), score));
                }
            }
        }
    }
    best_peer.map(|(id, _)| id)
}

/// Determines whether `local_peer_id` should poll `feed` at `now` given `active_peers`.
/// Starts with a preliminary allocation proportional to each peer's throughput weight, and
/// progressively expands `local_peer_id`'s portion on feeds that remain unpolled past their due time.
#[must_use]
#[allow(clippy::cast_precision_loss)]
pub fn should_peer_poll_feed(
    local_peer_id: &str,
    active_peers: &[PeerNode],
    feed: &Feed,
    now: DateTime<Utc>,
) -> bool {
    if !feed.is_active {
        return false;
    }
    let has_error = feed.last_error.as_deref().is_some_and(|e| !e.trim().is_empty());
    let is_cross_peer_rescue = has_error
        && feed
            .last_error_by
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .is_some_and(|err_peer| err_peer != local_peer_id);

    let interval_secs = feed.poll_interval.max(1).saturating_mul(60);
    let elapsed_secs = feed.last_polled.map(|lp| (now - lp).num_seconds());
    let is_normally_due = elapsed_secs.is_none_or(|s| s >= interval_secs);

    if !is_cross_peer_rescue && !is_normally_due {
        return false;
    }

    // If a feed is overdue by >= 2x its poll_interval, any active peer can rescue it even if
    // another peer won the initial allocation for the current epoch.
    if elapsed_secs.is_some_and(|s| s >= interval_secs.saturating_mul(2)) {
        return true;
    }

    if active_peers.len() <= 1 {
        return true;
    }

    let overdue_secs = elapsed_secs.map_or(0, |s| (s - interval_secs).max(0));
    let local_steal_boost = if overdue_secs > 0 {
        let overdue_ratio = (overdue_secs as f64) / (interval_secs as f64);
        (4.0 * overdue_ratio).mul_add(overdue_ratio, 1.0)
    } else {
        1.0
    };

    select_feed_poller(
        active_peers,
        feed.url.as_str(),
        feed.poll_interval,
        now,
        feed.last_polled_by.as_deref(),
        feed.last_error.as_deref(),
        feed.last_error_by.as_deref(),
        Some((local_peer_id, local_steal_boost)),
    ) == Some(local_peer_id)
}

pub struct SyncRepo<'a> {
    pub(crate) pool: &'a SqlitePool,
}

impl<'a> SyncRepo<'a> {
    #[must_use]
    pub const fn new(pool: &'a SqlitePool) -> Self {
        Self { pool }
    }

    /// Returns the current UTC timestamp truncated to milliseconds (`YYYY-MM-DDTHH:MM:SS.sss+00:00`).
    #[must_use]
    pub fn now_utc() -> DateTime<Utc> {
        Utc::now().trunc_subsecs(3)
    }

    pub async fn record_activity(
        conn: &mut SqliteConnection,
        entity_type: ActivityEntityType,
        entity_id: &str,
        action: ActivityAction,
        timestamp: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        sqlx::query(
            "INSERT INTO activity_log (entity_type, entity_id, action, timestamp)
             VALUES (?, ?, ?, ?)",
        )
        .bind(entity_type)
        .bind(entity_id)
        .bind(action)
        .bind(timestamp)
        .execute(conn)
        .await?;
        Ok(())
    }

    pub async fn record_tombstone(
        conn: &mut SqliteConnection,
        entity_type: ActivityEntityType,
        natural_key: &str,
        deleted_at: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        sqlx::query(
            "INSERT INTO sync_tombstones (entity_type, natural_key, deleted_at)
             VALUES (?, ?, ?)
             ON CONFLICT(entity_type, natural_key) DO UPDATE SET
                 deleted_at = CASE
                     WHEN strftime('%Y-%m-%dT%H:%M:%fZ', excluded.deleted_at) > strftime('%Y-%m-%dT%H:%M:%fZ', sync_tombstones.deleted_at)
                     THEN excluded.deleted_at
                     ELSE sync_tombstones.deleted_at
                 END",
        )
        .bind(entity_type)
        .bind(natural_key)
        .bind(deleted_at)
        .execute(conn)
        .await?;
        Ok(())
    }

    pub async fn clear_tombstone(
        conn: &mut SqliteConnection,
        entity_type: ActivityEntityType,
        natural_key: &str,
    ) -> std::result::Result<(), sqlx::Error> {
        sqlx::query("DELETE FROM sync_tombstones WHERE entity_type = ? AND natural_key = ?")
            .bind(entity_type)
            .bind(natural_key)
            .execute(conn)
            .await?;
        Ok(())
    }

    pub async fn list_tombstones(
        &self,
        since: Option<DateTime<Utc>>,
    ) -> Result<Vec<SyncTombstone>> {
        if let Some(ts) = since {
            Ok(sqlx::query_as::<_, SyncTombstone>(
                "SELECT entity_type, natural_key, deleted_at
                 FROM sync_tombstones
                 WHERE strftime('%Y-%m-%dT%H:%M:%fZ', deleted_at) >= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                 ORDER BY deleted_at DESC
                 LIMIT 5000",
            )
            .bind(ts)
            .fetch_all(self.pool)
            .await?)
        } else {
            Ok(sqlx::query_as::<_, SyncTombstone>(
                "SELECT entity_type, natural_key, deleted_at
                 FROM sync_tombstones
                 ORDER BY deleted_at DESC
                 LIMIT 5000",
            )
            .fetch_all(self.pool)
            .await?)
        }
    }

    pub async fn upsert_peer_node(
        &self,
        peer_id: &str,
        device_name: &str,
        network_tier: NetworkTier,
        weight: f64,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let clamped_weight =
            if network_tier == NetworkTier::Offline { 0.0 } else { weight.clamp(0.0, 10.0) };
        sqlx::query(
            "INSERT INTO peer_nodes (peer_id, device_name, network_tier, weight, last_seen_at)
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(peer_id) DO UPDATE SET
                 device_name = excluded.device_name,
                 network_tier = excluded.network_tier,
                 weight = excluded.weight,
                 last_seen_at = excluded.last_seen_at",
        )
        .bind(peer_id)
        .bind(device_name)
        .bind(network_tier)
        .bind(clamped_weight)
        .bind(now)
        .execute(self.pool)
        .await?;
        Ok(())
    }

    pub async fn list_active_peers(&self, now: DateTime<Utc>) -> Result<Vec<PeerNode>> {
        let cutoff = now - chrono::Duration::minutes(PEER_LEASE_TTL_MINUTES);
        Ok(sqlx::query_as::<_, PeerNode>(
            "SELECT peer_id, device_name, network_tier, weight, last_seen_at
             FROM peer_nodes
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', last_seen_at) >= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
             ORDER BY peer_id ASC",
        )
        .bind(cutoff)
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn list_activity(&self) -> Result<Vec<ActivityEntry>> {
        Ok(sqlx::query_as::<_, ActivityEntry>(
            "SELECT id, entity_type, entity_id, action, timestamp
             FROM activity_log
             ORDER BY id DESC
             LIMIT 5000",
        )
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn latest_activity_id(&self) -> Result<i64> {
        Ok(sqlx::query_scalar::<_, i64>("SELECT COALESCE(MAX(id), 0) FROM activity_log")
            .fetch_one(self.pool)
            .await?)
    }

    pub async fn list_changes_since(
        &self,
        since_id: i64,
        limit: i64,
    ) -> Result<Vec<ActivityEntry>> {
        let clamped_since = since_id.max(0);
        let clamped_limit = limit.clamp(1, 1001);
        Ok(sqlx::query_as::<_, ActivityEntry>(
            "SELECT id, entity_type, entity_id, action, timestamp
             FROM activity_log
             WHERE id > ?
             ORDER BY id ASC
             LIMIT ?",
        )
        .bind(clamped_since)
        .bind(clamped_limit)
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn prune_activity_older_than_days(&self, days: i64) -> Result<u64> {
        let cutoff = Self::now_utc() - chrono::Duration::days(days.max(0));
        let result = sqlx::query(
            "DELETE FROM activity_log
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', timestamp) < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
        )
        .bind(cutoff)
        .execute(self.pool)
        .await?;
        let _ = sqlx::query(
            "DELETE FROM sync_tombstones
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', deleted_at) < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
        )
        .bind(cutoff)
        .execute(self.pool)
        .await;
        Ok(result.rows_affected())
    }

    pub async fn merge_peer_state(
        &self,
        req: &PeerSyncRequest,
    ) -> Result<(PeerSyncResponse, Vec<(String, PollItemCandidate)>)> {
        let now = Self::now_utc();
        let peer_id = req.peer_id.trim();
        let device_name =
            req.device_name.as_deref().map(str::trim).filter(|s| !s.is_empty()).unwrap_or(peer_id);
        let measured_weight = if req.network_tier == NetworkTier::Offline {
            Some(0.0)
        } else {
            req.weight.map(|w| w.clamp(0.2, 10.0))
        };

        let mut tx = self.pool.begin().await?;

        // Begin `tx` with an immediate write (`INSERT INTO peer_nodes`) so SQLite acquires a
        // write lock right away instead of upgrading a deferred read snapshot later.
        Self::ensure_server_peer_in_tx(&mut tx, now).await?;
        if !peer_id.is_empty() && peer_id != SERVER_PEER_ID {
            Self::upsert_peer_in_tx(
                &mut tx,
                peer_id,
                device_name,
                req.network_tier,
                measured_weight,
                now,
            )
            .await?;
        }

        Self::apply_incoming_tombstones(&mut tx, &req.tombstones, now).await?;
        Self::merge_incoming_tags(&mut tx, &req.tags, now).await?;
        let feed_map =
            Self::merge_incoming_feeds(&mut tx, &req.feeds, req.updated_after, now).await?;
        let inserted_candidates =
            Self::merge_incoming_items(&mut tx, &req.items, &feed_map, now).await?;

        tx.commit().await?;

        let inclusive_since = req.updated_after.map(|ts| ts - chrono::Duration::seconds(1));
        let active_peers = self.list_active_peers(now).await?;
        let feeds = FeedRepo::new(self.pool).list().await?;
        let mut items = Vec::new();
        let mut page = 1;
        loop {
            let res = ItemRepo::new(self.pool)
                .list_filtered_page(&ItemFilter {
                    updated_after: inclusive_since,
                    include_content: Some(true),
                    page: Some(page),
                    per_page: Some(200),
                    ..ItemFilter::default()
                })
                .await?;
            items.extend(res.items);
            if !res.has_more || items.len() >= 5_000 {
                break;
            }
            page += 1;
        }
        let tags = TagRepo::new(self.pool).list().await?;
        let tombstones = self.list_tombstones(inclusive_since).await?;

        Ok((
            PeerSyncResponse {
                server_peer_id: SERVER_PEER_ID.to_owned(),
                sync_timestamp: now,
                active_peers,
                feeds,
                items,
                tags,
                tombstones,
            },
            inserted_candidates,
        ))
    }

    async fn ensure_server_peer_in_tx(
        conn: &mut SqliteConnection,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        sqlx::query(
            "INSERT INTO peer_nodes (peer_id, device_name, network_tier, weight, last_seen_at)
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(peer_id) DO UPDATE SET
                 device_name = excluded.device_name,
                 last_seen_at = excluded.last_seen_at",
        )
        .bind(SERVER_PEER_ID)
        .bind("Roche Headless Peer")
        .bind(NetworkTier::Wifi)
        .bind(DEFAULT_ONLINE_PEER_WEIGHT)
        .bind(now)
        .execute(conn)
        .await?;
        Ok(())
    }

    async fn upsert_peer_in_tx(
        conn: &mut SqliteConnection,
        peer_id: &str,
        device_name: &str,
        network_tier: NetworkTier,
        measured_weight: Option<f64>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let initial_weight = measured_weight.unwrap_or_else(|| network_tier.default_weight());
        sqlx::query(
            "INSERT INTO peer_nodes (peer_id, device_name, network_tier, weight, last_seen_at)
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(peer_id) DO UPDATE SET
                 device_name = excluded.device_name,
                 network_tier = excluded.network_tier,
                 weight = CASE
                     WHEN excluded.network_tier = 'offline' THEN 0.0
                     WHEN ? IS NOT NULL THEN ?
                     WHEN peer_nodes.weight > 0.0 THEN peer_nodes.weight
                     ELSE excluded.weight
                 END,
                 last_seen_at = excluded.last_seen_at",
        )
        .bind(peer_id)
        .bind(device_name)
        .bind(network_tier)
        .bind(initial_weight)
        .bind(now)
        .bind(measured_weight)
        .bind(measured_weight)
        .execute(conn)
        .await?;
        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    async fn apply_incoming_tombstones(
        conn: &mut SqliteConnection,
        tombstones: &[SyncTombstone],
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        for tomb in tombstones {
            let key = tomb.natural_key.trim();
            if key.is_empty() {
                continue;
            }
            match tomb.entity_type {
                ActivityEntityType::Feed => {
                    let canon_key = feed_natural_key(key);
                    let det_id = deterministic_feed_id(&canon_key);
                    let newer_live: Option<i64> = sqlx::query_scalar(
                        "SELECT 1 FROM feeds
                         WHERE (url = ? OR id = ?)
                           AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         LIMIT 1",
                    )
                    .bind(&canon_key)
                    .bind(&det_id)
                    .bind(tomb.deleted_at)
                    .fetch_optional(&mut *conn)
                    .await?;
                    if newer_live.is_some() {
                        continue;
                    }
                    Self::record_tombstone(
                        &mut *conn,
                        tomb.entity_type,
                        &canon_key,
                        tomb.deleted_at,
                    )
                    .await?;
                    let deleted_id: Option<String> = sqlx::query_scalar(
                        "DELETE FROM feeds
                         WHERE (url = ? OR id = ?)
                           AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         RETURNING id",
                    )
                    .bind(&canon_key)
                    .bind(&det_id)
                    .bind(tomb.deleted_at)
                    .fetch_optional(&mut *conn)
                    .await?;
                    if let Some(fid) = deleted_id {
                        Self::record_activity(
                            &mut *conn,
                            ActivityEntityType::Feed,
                            &fid,
                            ActivityAction::Delete,
                            now,
                        )
                        .await?;
                    }
                }
                ActivityEntityType::Item => {
                    if let Some(item_url) = key.strip_prefix("manual\0") {
                        let canon_item = canonicalize_sync_url(item_url);
                        let canon_key = item_natural_key(None, &canon_item);
                        let det_id = deterministic_item_id(None, &canon_item);
                        let newer_live: Option<i64> = sqlx::query_scalar(
                            "SELECT 1 FROM items
                             WHERE (id = ? OR (feed_id IS NULL AND url = ?))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                             LIMIT 1",
                        )
                        .bind(&det_id)
                        .bind(&canon_item)
                        .bind(tomb.deleted_at)
                        .fetch_optional(&mut *conn)
                        .await?;
                        if newer_live.is_some() {
                            continue;
                        }
                        Self::record_tombstone(
                            &mut *conn,
                            tomb.entity_type,
                            &canon_key,
                            tomb.deleted_at,
                        )
                        .await?;
                        let deleted_id: Option<String> = sqlx::query_scalar(
                            "DELETE FROM items
                             WHERE (id = ? OR (feed_id IS NULL AND url = ?))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                             RETURNING id",
                        )
                        .bind(&det_id)
                        .bind(&canon_item)
                        .bind(tomb.deleted_at)
                        .fetch_optional(&mut *conn)
                        .await?;
                        if let Some(iid) = deleted_id {
                            Self::record_activity(
                                &mut *conn,
                                ActivityEntityType::Item,
                                &iid,
                                ActivityAction::Delete,
                                now,
                            )
                            .await?;
                        }
                    } else if let Some((feed_url, item_url)) = key.split_once('\0') {
                        let canon_feed = canonicalize_sync_url(feed_url);
                        let canon_item = canonicalize_sync_url(item_url);
                        let canon_key = item_natural_key(Some(&canon_feed), &canon_item);
                        let det_feed_id = deterministic_feed_id(&canon_feed);
                        let det_id = deterministic_item_id(Some(&canon_feed), &canon_item);
                        let newer_live: Option<i64> = sqlx::query_scalar(
                            "SELECT 1 FROM items
                             WHERE (id = ? OR (url = ? AND feed_id IN (SELECT id FROM feeds WHERE url = ? OR id = ?)))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                             LIMIT 1",
                        )
                        .bind(&det_id)
                        .bind(&canon_item)
                        .bind(&canon_feed)
                        .bind(&det_feed_id)
                        .bind(tomb.deleted_at)
                        .fetch_optional(&mut *conn)
                        .await?;
                        if newer_live.is_some() {
                            continue;
                        }
                        Self::record_tombstone(
                            &mut *conn,
                            tomb.entity_type,
                            &canon_key,
                            tomb.deleted_at,
                        )
                        .await?;
                        let deleted_id: Option<String> = sqlx::query_scalar(
                            "DELETE FROM items
                             WHERE (id = ? OR (url = ? AND feed_id IN (SELECT id FROM feeds WHERE url = ? OR id = ?)))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                             RETURNING id",
                        )
                        .bind(&det_id)
                        .bind(&canon_item)
                        .bind(&canon_feed)
                        .bind(&det_feed_id)
                        .bind(tomb.deleted_at)
                        .fetch_optional(&mut *conn)
                        .await?;
                        if let Some(iid) = deleted_id {
                            Self::record_activity(
                                &mut *conn,
                                ActivityEntityType::Item,
                                &iid,
                                ActivityAction::Delete,
                                now,
                            )
                            .await?;
                        }
                    } else {
                        Self::record_tombstone(&mut *conn, tomb.entity_type, key, tomb.deleted_at)
                            .await?;
                    }
                }
                ActivityEntityType::Tag => {
                    let canon_key = tag_natural_key(key);
                    let newer_live: Option<i64> = sqlx::query_scalar(
                        "SELECT 1 FROM tags
                         WHERE LOWER(name) = LOWER(?)
                           AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         LIMIT 1",
                    )
                    .bind(&canon_key)
                    .bind(tomb.deleted_at)
                    .fetch_optional(&mut *conn)
                    .await?;
                    if newer_live.is_some() {
                        continue;
                    }
                    Self::record_tombstone(
                        &mut *conn,
                        tomb.entity_type,
                        &canon_key,
                        tomb.deleted_at,
                    )
                    .await?;
                    sqlx::query(
                        "UPDATE items SET updated_at = ?
                         WHERE id IN (
                             SELECT it.item_id FROM item_tags it
                             JOIN tags t ON t.id = it.tag_id
                             WHERE LOWER(t.name) = LOWER(?)
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', t.created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         )",
                    )
                    .bind(now)
                    .bind(&canon_key)
                    .bind(tomb.deleted_at)
                    .execute(&mut *conn)
                    .await?;
                    let deleted_id: Option<String> = sqlx::query_scalar(
                        "DELETE FROM tags
                         WHERE LOWER(name) = LOWER(?)
                           AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         RETURNING id",
                    )
                    .bind(&canon_key)
                    .bind(tomb.deleted_at)
                    .fetch_optional(&mut *conn)
                    .await?;
                    if let Some(tid) = deleted_id {
                        Self::record_activity(
                            &mut *conn,
                            ActivityEntityType::Tag,
                            &tid,
                            ActivityAction::Delete,
                            now,
                        )
                        .await?;
                    }
                }
                ActivityEntityType::Media => {}
            }
        }
        Ok(())
    }

    async fn merge_incoming_tags(
        conn: &mut SqliteConnection,
        tags: &[PeerTagRecord],
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        for t in tags {
            let clean_name = t.name.trim();
            if clean_name.is_empty() {
                continue;
            }
            let nat_key = tag_natural_key(clean_name);
            let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'tag' AND natural_key = ?",
            )
            .bind(&nat_key)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some(del_at) = tomb_ts {
                if t.created_at.is_none_or(|c| c <= del_at) {
                    continue;
                }
                Self::clear_tombstone(&mut *conn, ActivityEntityType::Tag, &nat_key).await?;
            }

            let det_id = deterministic_tag_id(clean_name);
            let existing: Option<(String, Option<HexColor>)> = sqlx::query_as(
                "SELECT id, color FROM tags WHERE id = ? OR LOWER(name) = LOWER(?) LIMIT 1",
            )
            .bind(&det_id)
            .bind(clean_name)
            .fetch_optional(&mut *conn)
            .await?;

            if let Some((tag_id, existing_color)) = existing {
                if t.color.is_some() && existing_color != t.color {
                    sqlx::query("UPDATE tags SET color = ? WHERE id = ?")
                        .bind(&t.color)
                        .bind(&tag_id)
                        .execute(&mut *conn)
                        .await?;
                    Self::record_activity(
                        &mut *conn,
                        ActivityEntityType::Tag,
                        &tag_id,
                        ActivityAction::Update,
                        now,
                    )
                    .await?;
                }
            } else {
                let created_at = t.created_at.unwrap_or(now);
                let res = sqlx::query(
                    "INSERT OR IGNORE INTO tags (id, name, color, created_at) VALUES (?, ?, ?, ?)",
                )
                .bind(&det_id)
                .bind(clean_name)
                .bind(&t.color)
                .bind(created_at)
                .execute(&mut *conn)
                .await?;
                if res.rows_affected() > 0 {
                    Self::record_activity(
                        &mut *conn,
                        ActivityEntityType::Tag,
                        &det_id,
                        ActivityAction::Create,
                        now,
                    )
                    .await?;
                }
            }
        }
        Ok(())
    }

    async fn merge_incoming_feeds(
        conn: &mut SqliteConnection,
        feeds: &[PeerFeedRecord],
        req_updated_after: Option<DateTime<Utc>>,
        now: DateTime<Utc>,
    ) -> std::result::Result<HashMap<String, (String, String)>, sqlx::Error> {
        let mut feed_map = HashMap::new();
        for f in feeds {
            Self::merge_single_feed_in_tx(&mut *conn, f, req_updated_after, &mut feed_map, now)
                .await?;
        }
        Ok(feed_map)
    }

    async fn merge_single_feed_in_tx(
        conn: &mut SqliteConnection,
        f: &PeerFeedRecord,
        req_updated_after: Option<DateTime<Utc>>,
        feed_map: &mut HashMap<String, (String, String)>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        type ExistingFeedRow = (String, String, Option<DateTime<Utc>>, bool);
        let feed_url = f.url.as_str();
        let nat_key = feed_natural_key(feed_url);
        let det_id = deterministic_feed_id(feed_url);
        let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
            "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'feed' AND natural_key = ?",
        )
        .bind(&nat_key)
        .fetch_optional(&mut *conn)
        .await?;
        if let Some(del_at) = tomb_ts {
            if f.created_at.is_none_or(|c| c <= del_at) {
                return Ok(());
            }
            Self::clear_tombstone(&mut *conn, ActivityEntityType::Feed, &nat_key).await?;
        }

        let existing: Option<ExistingFeedRow> = sqlx::query_as(
            "SELECT id, url, last_polled, is_active
             FROM feeds
             WHERE url = ? OR id = ? OR id = ?
             LIMIT 1",
        )
        .bind(feed_url)
        .bind(&f.id)
        .bind(&det_id)
        .fetch_optional(&mut *conn)
        .await?;

        let clean_title = f.title.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_desc = f.description.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_lang = f.language.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_err = f.last_error.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_polled_by = f.last_polled_by.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_err_by = if clean_err.is_some() {
            f.last_error_by.as_deref().map(str::trim).filter(|s| !s.is_empty())
        } else {
            None
        };

        if let Some((local_id, local_url, local_last_polled, local_is_active)) = existing {
            Self::update_existing_feed_in_tx(
                &mut *conn,
                &local_id,
                f,
                local_last_polled,
                local_is_active,
                req_updated_after,
                clean_title,
                clean_desc,
                clean_lang,
                clean_err,
                clean_polled_by,
                clean_err_by,
                now,
            )
            .await?;
            if !f.id.is_empty() {
                feed_map.insert(f.id.clone(), (local_id.clone(), local_url.clone()));
            }
            feed_map.insert(local_id.clone(), (local_id, local_url));
        } else {
            let local_id = det_id;
            let interval = f.poll_interval.clamp(5, 1440);
            let created_at = f.created_at.unwrap_or(now);
            sqlx::query(
                "INSERT OR IGNORE INTO feeds
                 (id, feed_type, url, title, description, language, poll_interval, last_polled, last_polled_by, last_error, last_error_by, is_active, created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(&local_id)
            .bind(f.feed_type)
            .bind(feed_url)
            .bind(clean_title)
            .bind(clean_desc)
            .bind(clean_lang)
            .bind(interval)
            .bind(f.last_polled)
            .bind(clean_polled_by)
            .bind(clean_err)
            .bind(clean_err_by)
            .bind(f.is_active)
            .bind(created_at)
            .bind(now)
            .execute(&mut *conn)
            .await?;

            Self::record_activity(
                &mut *conn,
                ActivityEntityType::Feed,
                &local_id,
                ActivityAction::Create,
                now,
            )
            .await?;
            if !f.id.is_empty() {
                feed_map.insert(f.id.clone(), (local_id.clone(), feed_url.to_owned()));
            }
            feed_map.insert(local_id.clone(), (local_id, feed_url.to_owned()));
        }
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    async fn update_existing_feed_in_tx(
        conn: &mut SqliteConnection,
        local_id: &str,
        f: &PeerFeedRecord,
        local_last_polled: Option<DateTime<Utc>>,
        local_is_active: bool,
        req_updated_after: Option<DateTime<Utc>>,
        clean_title: Option<&str>,
        clean_desc: Option<&str>,
        clean_lang: Option<&str>,
        clean_err: Option<&str>,
        clean_polled_by: Option<&str>,
        clean_err_by: Option<&str>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let incoming_poll_newer = match (f.last_polled, local_last_polled) {
            (Some(inc), Some(loc)) => inc > loc,
            (Some(_), None) => true,
            _ => false,
        };
        if incoming_poll_newer {
            sqlx::query(
                "UPDATE feeds
                 SET title = COALESCE(title, ?),
                     description = COALESCE(?, description),
                     language = COALESCE(?, language),
                     last_polled = ?,
                     last_polled_by = ?,
                     last_error = ?,
                     last_error_by = ?,
                     updated_at = ?
                 WHERE id = ?",
            )
            .bind(clean_title)
            .bind(clean_desc)
            .bind(clean_lang)
            .bind(f.last_polled)
            .bind(clean_polled_by)
            .bind(clean_err)
            .bind(clean_err_by)
            .bind(now)
            .bind(local_id)
            .execute(&mut *conn)
            .await?;
        } else {
            sqlx::query(
                "UPDATE feeds
                 SET title = COALESCE(title, ?),
                     description = COALESCE(description, ?),
                     language = COALESCE(language, ?)
                 WHERE id = ?",
            )
            .bind(clean_title)
            .bind(clean_desc)
            .bind(clean_lang)
            .bind(local_id)
            .execute(&mut *conn)
            .await?;
        }

        if f.is_active != local_is_active {
            let last_archive_change_at: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT MAX(timestamp) FROM activity_log
                 WHERE entity_type = 'feed' AND entity_id = ? AND action IN ('archive', 'unarchive')",
            )
            .bind(local_id)
            .fetch_optional(&mut *conn)
            .await?
            .flatten();

            let should_update_active = match (req_updated_after, last_archive_change_at) {
                (Some(ua), Some(server_ts)) => server_ts <= ua,
                (Some(_), None) => true,
                (None, None) => !f.is_active,
                (None, Some(_)) => false,
            };

            if should_update_active {
                sqlx::query("UPDATE feeds SET is_active = ?, updated_at = ? WHERE id = ?")
                    .bind(f.is_active)
                    .bind(now)
                    .bind(local_id)
                    .execute(&mut *conn)
                    .await?;
                let action =
                    if f.is_active { ActivityAction::Unarchive } else { ActivityAction::Archive };
                Self::record_activity(&mut *conn, ActivityEntityType::Feed, local_id, action, now)
                    .await?;
            }
        }
        Ok(())
    }

    async fn resolve_peer_item_feed(
        conn: &mut SqliteConnection,
        inc: &PeerItemRecord,
        feed_map: &HashMap<String, (String, String)>,
    ) -> std::result::Result<Option<(Option<String>, Option<String>)>, sqlx::Error> {
        if inc.feed_url.is_none() && inc.feed_id.is_none() {
            return Ok(Some((None, None)));
        }
        if let Some(ref f_url) = inc.feed_url {
            let det_fid = deterministic_feed_id(f_url.as_str());
            let found: Option<(String, String)> =
                sqlx::query_as("SELECT id, url FROM feeds WHERE url = ? OR id = ? LIMIT 1")
                    .bind(f_url.as_str())
                    .bind(&det_fid)
                    .fetch_optional(&mut *conn)
                    .await?;
            if let Some((fid, url)) = found {
                return Ok(Some((Some(fid), Some(url))));
            }
        }
        if let Some(ref fid) = inc.feed_id {
            if let Some((mapped_id, mapped_url)) = feed_map.get(fid) {
                return Ok(Some((Some(mapped_id.clone()), Some(mapped_url.clone()))));
            }
            let found: Option<(String, String)> =
                sqlx::query_as("SELECT id, url FROM feeds WHERE id = ? LIMIT 1")
                    .bind(fid)
                    .fetch_optional(&mut *conn)
                    .await?;
            if let Some((local_fid, url)) = found {
                return Ok(Some((Some(local_fid), Some(url))));
            }
        }
        Ok(None)
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_incoming_items(
        conn: &mut SqliteConnection,
        items: &[PeerItemRecord],
        feed_map: &HashMap<String, (String, String)>,
        now: DateTime<Utc>,
    ) -> std::result::Result<Vec<(String, PollItemCandidate)>, sqlx::Error> {
        let mut inserted_candidates = Vec::new();

        for inc in items {
            let Some((resolved_feed_id, resolved_feed_url)) =
                Self::resolve_peer_item_feed(&mut *conn, inc, feed_map).await?
            else {
                // Item belongs to a feed that was deleted/unsubscribed; skip it.
                continue;
            };

            let nat_key = item_natural_key(resolved_feed_url.as_deref(), inc.url.as_str());
            let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'item' AND natural_key = ?",
            )
            .bind(&nat_key)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some(del_at) = tomb_ts {
                let inc_created = inc.created_at.unwrap_or(del_at);
                let inc_interacted = inc.interaction_updated_at.unwrap_or(del_at);
                if inc_created <= del_at && inc_interacted <= del_at {
                    continue;
                }
                Self::clear_tombstone(&mut *conn, ActivityEntityType::Item, &nat_key).await?;
            }

            let clean_title = inc.title.trim();
            let final_title = if clean_title.is_empty() { inc.url.as_str() } else { clean_title };
            let clean_author = inc.author.as_deref().map(str::trim).filter(|s| !s.is_empty());
            let clean_html = inc
                .content_html
                .as_deref()
                .map(|h| HtmlSanitizer::clean_with_base(h, Some(&inc.url)))
                .filter(|h| !h.trim().is_empty());
            let clean_summary = inc
                .summary
                .as_deref()
                .map(|s| HtmlSanitizer::plain_text_excerpt(s, MAX_SUMMARY_CHARS))
                .filter(|s| !s.is_empty())
                .or_else(|| {
                    clean_html
                        .as_deref()
                        .map(|h| HtmlSanitizer::plain_text_excerpt_from_clean(h, MAX_SUMMARY_CHARS))
                        .filter(|s| !s.is_empty())
                });
            let clean_thumb = inc
                .thumbnail_url
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .and_then(|u| HtmlSanitizer::resolve_and_validate_thumbnail_url(u, Some(&inc.url)))
                .or_else(|| {
                    clean_html.as_deref().and_then(|h| {
                        HtmlSanitizer::extract_first_image_url_with_base(h, Some(&inc.url))
                    })
                });
            let clean_audio_url = inc
                .audio_url
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .and_then(|u| SafeHttpClient::parse_url(u).ok())
                .map(String::from)
                .or_else(|| {
                    (inc.content_type == ContentType::Audio
                        && crate::content::fetcher::is_direct_audio_url(&inc.url))
                    .then(|| inc.url.as_str().to_owned())
                });
            let clean_duration = inc.duration.filter(|&d| d > 0);
            let resolved_content_type =
                if clean_audio_url.is_some() { ContentType::Audio } else { inc.content_type };
            let clamped_progress =
                if inc.read_progress.is_finite() { inc.read_progress.clamp(0.0, 1.0) } else { 0.0 };

            let det_item_id = deterministic_item_id(resolved_feed_url.as_deref(), inc.url.as_str());
            let existing: Option<ExistingItemRow> = if let Some(ref fid) = resolved_feed_id {
                sqlx::query_as(
                    "SELECT id, title, author, published_at, summary, content_html, thumbnail_url,
                            audio_url, duration, content_type, status, is_favorite, read_at, read_progress, interaction_updated_at
                     FROM items
                     WHERE (feed_id = ? AND url = ?) OR id = ? OR id = ?
                     LIMIT 1",
                )
                .bind(fid)
                .bind(inc.url.as_str())
                .bind(&inc.id)
                .bind(&det_item_id)
                .fetch_optional(&mut *conn)
                .await?
            } else {
                sqlx::query_as(
                    "SELECT id, title, author, published_at, summary, content_html, thumbnail_url,
                            audio_url, duration, content_type, status, is_favorite, read_at, read_progress, interaction_updated_at
                     FROM items
                     WHERE (feed_id IS NULL AND url = ?) OR id = ? OR id = ?
                     LIMIT 1",
                )
                .bind(inc.url.as_str())
                .bind(&inc.id)
                .bind(&det_item_id)
                .fetch_optional(&mut *conn)
                .await?
            };

            if let Some(ex) = existing {
                Self::merge_existing_item_in_tx(
                    &mut *conn,
                    &ex,
                    inc,
                    final_title,
                    clean_author,
                    clean_summary.as_deref(),
                    clean_html.as_deref(),
                    clean_thumb.as_deref(),
                    clean_audio_url.as_deref(),
                    clean_duration,
                    resolved_content_type,
                    clamped_progress,
                    now,
                )
                .await?;
            } else {
                let item_id = det_item_id;
                let created_at = inc.created_at.unwrap_or(now);
                let insert_inter_ts = inc.interaction_updated_at.map(|ts| ts.trunc_subsecs(3));
                let insert_read_at = if inc.status == ItemStatus::Archived {
                    inc.read_at.or(insert_inter_ts).or(Some(now))
                } else {
                    inc.read_at
                };
                let res = sqlx::query(
                    "INSERT OR IGNORE INTO items
                     (id, feed_id, title, url, author, published_at, summary, content_html, thumbnail_url, audio_url, duration, content_type, status, is_favorite, read_at, read_progress, interaction_updated_at, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                )
                .bind(&item_id)
                .bind(resolved_feed_id.as_deref())
                .bind(final_title)
                .bind(inc.url.as_str())
                .bind(clean_author)
                .bind(inc.published_at)
                .bind(clean_summary.as_deref())
                .bind(clean_html.as_deref())
                .bind(clean_thumb.as_deref())
                .bind(clean_audio_url.as_deref())
                .bind(clean_duration)
                .bind(resolved_content_type)
                .bind(inc.status)
                .bind(inc.is_favorite)
                .bind(insert_read_at)
                .bind(clamped_progress)
                .bind(insert_inter_ts)
                .bind(created_at)
                .bind(now)
                .execute(&mut *conn)
                .await?;

                if res.rows_affected() > 0 {
                    Self::record_activity(
                        &mut *conn,
                        ActivityEntityType::Item,
                        &item_id,
                        ActivityAction::Create,
                        now,
                    )
                    .await?;
                    Self::replace_item_tags_in_tx(
                        &mut *conn,
                        &item_id,
                        &inc.tag_names,
                        now,
                        false,
                        insert_inter_ts,
                    )
                    .await?;
                    inserted_candidates.push((
                        item_id,
                        PollItemCandidate {
                            title: final_title.to_owned(),
                            url: inc.url.clone(),
                            author: clean_author.map(str::to_owned),
                            published_at: inc.published_at,
                            summary: clean_summary.unwrap_or_default(),
                            content_html: clean_html,
                            thumbnail_url: clean_thumb,
                            audio_url: clean_audio_url,
                            duration: clean_duration,
                            content_type: resolved_content_type,
                        },
                    ));
                }
            }
        }

        Ok(inserted_candidates)
    }

    #[allow(clippy::too_many_arguments)]
    async fn merge_existing_item_in_tx(
        conn: &mut SqliteConnection,
        ex: &ExistingItemRow,
        inc: &PeerItemRecord,
        final_title: &str,
        clean_author: Option<&str>,
        clean_summary: Option<&str>,
        clean_html: Option<&str>,
        clean_thumb: Option<&str>,
        clean_audio_url: Option<&str>,
        clean_duration: Option<i64>,
        resolved_content_type: ContentType,
        clamped_progress: f64,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let merged_title = if (ex.title.trim().is_empty() || ex.title == inc.url.as_str())
            && !final_title.is_empty()
        {
            final_title
        } else {
            ex.title.as_str()
        };
        let merged_author = ex.author.as_deref().or(clean_author);
        let merged_pub = ex.published_at.or(inc.published_at);
        let merged_summary = pick_richer_text(ex.summary.as_deref(), clean_summary);
        let merged_html = pick_richer_text(ex.content_html.as_deref(), clean_html);
        let merged_thumb = ex.thumbnail_url.as_deref().or(clean_thumb);
        let merged_audio_url = ex.audio_url.as_deref().or(clean_audio_url);
        let merged_duration = ex.duration.or(clean_duration);
        let merged_content_type = if merged_audio_url.is_some()
            || ex.content_type == ContentType::Audio
            || resolved_content_type == ContentType::Audio
        {
            ContentType::Audio
        } else {
            ex.content_type
        };

        let content_changed = merged_title != ex.title
            || merged_author != ex.author.as_deref()
            || merged_pub != ex.published_at
            || merged_summary != ex.summary.as_deref()
            || merged_html != ex.content_html.as_deref()
            || merged_thumb != ex.thumbnail_url.as_deref()
            || merged_audio_url != ex.audio_url.as_deref()
            || merged_duration != ex.duration
            || merged_content_type != ex.content_type;

        let (target_status, target_fav, target_read_at, target_prog, target_inter_ts, tag_mode) =
            resolve_merged_interaction_state(ex, inc, clamped_progress, now);

        let interaction_changed = target_status != ex.status
            || target_fav != ex.is_favorite
            || target_read_at != ex.read_at
            || (target_prog - ex.read_progress).abs() > f64::EPSILON
            || target_inter_ts != ex.interaction_updated_at;

        if content_changed || interaction_changed {
            sqlx::query(
                "UPDATE items
                 SET title = ?, author = ?, published_at = ?, summary = ?, content_html = ?,
                     thumbnail_url = ?, audio_url = ?, duration = ?, content_type = ?, status = ?,
                     is_favorite = ?, read_at = ?, read_progress = ?, interaction_updated_at = ?,
                     updated_at = ?
                 WHERE id = ?",
            )
            .bind(merged_title)
            .bind(merged_author)
            .bind(merged_pub)
            .bind(merged_summary)
            .bind(merged_html)
            .bind(merged_thumb)
            .bind(merged_audio_url)
            .bind(merged_duration)
            .bind(merged_content_type)
            .bind(target_status)
            .bind(target_fav)
            .bind(target_read_at)
            .bind(target_prog)
            .bind(target_inter_ts)
            .bind(now)
            .bind(&ex.id)
            .execute(&mut *conn)
            .await?;

            Self::record_activity(
                &mut *conn,
                ActivityEntityType::Item,
                &ex.id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }

        if let TagMergeMode::Replace | TagMergeMode::Union = tag_mode {
            let clear_unlisted = matches!(tag_mode, TagMergeMode::Replace);
            Self::replace_item_tags_in_tx(
                &mut *conn,
                &ex.id,
                &inc.tag_names,
                now,
                clear_unlisted,
                target_inter_ts,
            )
            .await?;
        }

        Ok(())
    }

    async fn replace_item_tags_in_tx(
        conn: &mut SqliteConnection,
        item_id: &str,
        tag_names: &[String],
        now: DateTime<Utc>,
        clear_unlisted: bool,
        preserved_interaction_ts: Option<DateTime<Utc>>,
    ) -> std::result::Result<(), sqlx::Error> {
        let mut normalized_set = HashSet::new();
        let mut clean_names = Vec::new();
        for raw in tag_names {
            let trimmed = raw.trim();
            if !trimmed.is_empty() && normalized_set.insert(trimmed.to_lowercase()) {
                clean_names.push(trimmed.to_owned());
            }
        }

        if clear_unlisted {
            sqlx::query("DELETE FROM item_tags WHERE item_id = ?")
                .bind(item_id)
                .execute(&mut *conn)
                .await?;
        }

        for name in clean_names {
            let nat_key = tag_natural_key(&name);
            let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'tag' AND natural_key = ?",
            )
            .bind(&nat_key)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some(del_at) = tomb_ts
                && preserved_interaction_ts.is_none_or(|ts| ts <= del_at)
            {
                continue;
            }
            let _ =
                TagRepo::get_or_create_and_attach_in_tx(&mut *conn, item_id, &name, now).await?;
        }

        sqlx::query("UPDATE items SET interaction_updated_at = ?, updated_at = ? WHERE id = ?")
            .bind(preserved_interaction_ts)
            .bind(now)
            .bind(item_id)
            .execute(&mut *conn)
            .await?;
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TagMergeMode {
    Replace,
    Union,
    KeepExisting,
}

#[derive(Debug, sqlx::FromRow)]
struct ExistingItemRow {
    id: String,
    title: String,
    author: Option<String>,
    published_at: Option<DateTime<Utc>>,
    summary: Option<String>,
    content_html: Option<String>,
    thumbnail_url: Option<String>,
    audio_url: Option<String>,
    duration: Option<i64>,
    content_type: ContentType,
    status: ItemStatus,
    is_favorite: bool,
    read_at: Option<DateTime<Utc>>,
    read_progress: f64,
    interaction_updated_at: Option<DateTime<Utc>>,
}

fn pick_richer_text<'a>(existing: Option<&'a str>, incoming: Option<&'a str>) -> Option<&'a str> {
    match (
        existing.map(str::trim).filter(|s| !s.is_empty()),
        incoming.map(str::trim).filter(|s| !s.is_empty()),
    ) {
        (Some(ex), Some(inc)) => {
            if inc.len() > ex.len() {
                incoming
            } else {
                existing
            }
        }
        (Some(_), None) => existing,
        (None, Some(_)) => incoming,
        (None, None) => None,
    }
}

type MergedInteractionState =
    (ItemStatus, bool, Option<DateTime<Utc>>, f64, Option<DateTime<Utc>>, TagMergeMode);

fn resolve_merged_interaction_state(
    ex: &ExistingItemRow,
    inc: &PeerItemRecord,
    clamped_progress: f64,
    now: DateTime<Utc>,
) -> MergedInteractionState {
    let inc_norm = inc.interaction_updated_at.map(|ts| ts.trunc_subsecs(3));
    let loc_norm = ex.interaction_updated_at.map(|ts| ts.trunc_subsecs(3));
    let (status, fav, raw_read_at, prog, inter_ts, tag_mode) = match (inc_norm, loc_norm) {
        (Some(inc_ts), None) => (
            inc.status,
            inc.is_favorite,
            inc.read_at,
            clamped_progress,
            Some(inc_ts),
            TagMergeMode::Replace,
        ),
        (Some(inc_ts), Some(loc_ts)) if inc_ts > loc_ts => (
            inc.status,
            inc.is_favorite,
            inc.read_at,
            clamped_progress,
            Some(inc_ts),
            TagMergeMode::Replace,
        ),
        (Some(inc_ts), Some(loc_ts)) if inc_ts == loc_ts => (
            if ex.status == ItemStatus::Archived || inc.status == ItemStatus::Archived {
                ItemStatus::Archived
            } else {
                ItemStatus::Unread
            },
            ex.is_favorite || inc.is_favorite,
            ex.read_at.max(inc.read_at),
            ex.read_progress.max(clamped_progress),
            Some(loc_ts),
            TagMergeMode::Union,
        ),
        _ => (
            ex.status,
            ex.is_favorite,
            ex.read_at,
            ex.read_progress,
            ex.interaction_updated_at,
            if ex.interaction_updated_at.is_none() && !inc.tag_names.is_empty() {
                TagMergeMode::Union
            } else {
                TagMergeMode::KeepExisting
            },
        ),
    };
    let read_at = if status == ItemStatus::Archived {
        raw_read_at.or(ex.read_at).or(inc.read_at).or(inter_ts).or(Some(now))
    } else {
        raw_read_at
    };
    (status, fav, read_at, prog, inter_ts, tag_mode)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::too_many_lines)]
mod tests {
    use super::*;
    use crate::db::{Db, PollBatch};

    #[test]
    fn test_deterministic_uuid_and_hrw_work_splitting() {
        let f_id1 = deterministic_feed_id("https://example.com/feed.xml");
        let f_id2 = deterministic_feed_id("https://example.com/feed.xml");
        assert_eq!(f_id1, f_id2);
        assert_eq!(f_id1.len(), 36);

        let i_id1 =
            deterministic_item_id(Some("https://example.com/feed.xml"), "https://example.com/1");
        let i_id2 =
            deterministic_item_id(Some("https://example.com/feed.xml"), "https://example.com/1");
        assert_eq!(i_id1, i_id2);
        assert_ne!(f_id1, i_id1);

        let now = DateTime::parse_from_rfc3339("2026-06-01T12:00:00Z").unwrap().with_timezone(&Utc);
        let mut peers = vec![
            PeerNode {
                peer_id: "server".to_string(),
                device_name: "Headless".to_string(),
                network_tier: NetworkTier::Wifi,
                weight: compute_throughput_weight(0, 0, 0.0),
                last_seen_at: now,
            },
            PeerNode {
                peer_id: "android-1".to_string(),
                device_name: "Pixel".to_string(),
                network_tier: NetworkTier::Wifi,
                weight: compute_throughput_weight(0, 0, 0.0),
                last_seen_at: now,
            },
        ];

        // 1. Preliminary allocation: both online peers start at weight 1.0 and split feeds roughly
        // equally, with exactly one peer claiming each due feed.
        let mut server_wins = 0;
        let mut android_wins = 0;
        for idx in 0..100 {
            let feed_url = Url::parse(&format!("https://example.com/feed-{idx}.xml")).unwrap();
            let feed = Feed {
                id: deterministic_feed_id(feed_url.as_str()),
                feed_type: FeedType::Rss,
                url: feed_url,
                title: Some(format!("Feed {idx}")),
                description: None,
                language: None,
                poll_interval: 30,
                last_polled: None,
                last_polled_by: None,
                last_error: None,
                last_error_by: None,
                is_active: true,
                created_at: now,
                unread_count: 0,
            };
            let s_polls = should_peer_poll_feed("server", &peers, &feed, now);
            let a_polls = should_peer_poll_feed("android-1", &peers, &feed, now);
            assert_ne!(s_polls, a_polls, "Exactly one peer must claim each healthy due feed");
            if s_polls {
                server_wins += 1;
            } else {
                android_wins += 1;
            }
        }
        assert!(server_wins > 35 && server_wins < 65, "server_wins={server_wins}");
        assert_eq!(server_wins + android_wins, 100);

        // 2. Dynamic throughput reallocation: when the faster peer processes more feeds and items
        // per second, it is dynamically allocated a much larger portion of the remaining feeds.
        peers[0].weight = compute_throughput_weight(8, 200, 2.0);
        peers[1].weight = compute_throughput_weight(2, 25, 3.0);
        assert!(peers[0].weight > peers[1].weight * 4.0);
        let mut fast_wins = 0;
        for idx in 0..100 {
            let feed_url = Url::parse(&format!("https://example.com/feed-{idx}.xml")).unwrap();
            let feed = Feed {
                id: deterministic_feed_id(feed_url.as_str()),
                feed_type: FeedType::Rss,
                url: feed_url,
                title: Some(format!("Feed {idx}")),
                description: None,
                language: None,
                poll_interval: 30,
                last_polled: None,
                last_polled_by: None,
                last_error: None,
                last_error_by: None,
                is_active: true,
                created_at: now,
                unread_count: 0,
            };
            if should_peer_poll_feed("server", &peers, &feed, now) {
                fast_wins += 1;
            }
        }
        assert!(
            fast_wins > server_wins && fast_wins >= 75,
            "Faster peer must dynamically receive a larger portion: fast_wins={fast_wins}, initial={server_wins}"
        );

        // When the server fails on a feed (e.g. HTTP 403), Android immediately rescues it even
        // though `last_polled` was 1 minute ago (within the 30m backoff window for the server).
        let blocked_url = Url::parse("https://blocked.example.com/rss.xml").unwrap();
        let blocked_feed = Feed {
            id: deterministic_feed_id(blocked_url.as_str()),
            feed_type: FeedType::Rss,
            url: blocked_url,
            title: Some("Blocked".to_string()),
            description: None,
            language: None,
            poll_interval: 30,
            last_polled: Some(now - chrono::Duration::minutes(1)),
            last_polled_by: Some("server".to_string()),
            last_error: Some("Upstream server returned HTTP 403 Forbidden".to_string()),
            last_error_by: Some("server".to_string()),
            is_active: true,
            created_at: now,
            unread_count: 0,
        };
        assert!(!should_peer_poll_feed("server", &peers, &blocked_feed, now));
        assert!(should_peer_poll_feed("android-1", &peers, &blocked_feed, now));
    }

    #[tokio::test]
    async fn test_peer_sync_merges_without_duplication_and_respects_tombstones() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/rss.xml").unwrap();
        let feed =
            db.feeds().create(FeedType::Rss, &feed_url, Some("Example"), None).await.unwrap();

        let item1_url = Url::parse("https://example.com/post-1").unwrap();
        let item2_url = Url::parse("https://example.com/post-2").unwrap();
        db.feeds()
            .record_poll_batch(
                &feed.id,
                &PollBatch {
                    title: Some("Example".to_string()),
                    description: None,
                    language: None,
                    items: vec![
                        PollItemCandidate {
                            title: "Post 1".to_string(),
                            url: item1_url.clone(),
                            author: None,
                            published_at: None,
                            summary: "Short".to_string(),
                            content_html: Some("<p>Short</p>".to_string()),
                            thumbnail_url: None,
                            audio_url: None,
                            duration: None,
                            content_type: ContentType::Article,
                        },
                        PollItemCandidate {
                            title: "Post 2".to_string(),
                            url: item2_url.clone(),
                            author: None,
                            published_at: None,
                            summary: "To be deleted".to_string(),
                            content_html: None,
                            thumbnail_url: None,
                            audio_url: None,
                            duration: None,
                            content_type: ContentType::Article,
                        },
                    ],
                },
            )
            .await
            .unwrap();

        // Delete Post 2 on server; verify re-polling the feed does NOT resurrect Post 2!
        let post2_id = deterministic_item_id(Some(feed_url.as_str()), item2_url.as_str());
        assert!(db.items().delete(&post2_id).await.unwrap());
        db.feeds()
            .record_poll_batch(
                &feed.id,
                &PollBatch {
                    title: None,
                    description: None,
                    language: None,
                    items: vec![PollItemCandidate {
                        title: "Post 2".to_string(),
                        url: item2_url.clone(),
                        author: None,
                        published_at: None,
                        summary: "To be deleted".to_string(),
                        content_html: None,
                        thumbnail_url: None,
                        audio_url: None,
                        duration: None,
                        content_type: ContentType::Article,
                    }],
                },
            )
            .await
            .unwrap();
        assert!(db.items().get(&post2_id).await.unwrap().is_none());

        // Android peer syncs:
        // 1. Sends richer content + read/star state for Post 1 (using a different local feed_id!)
        // 2. Sends Post 2 (which was deleted on server -> must remain suppressed by tombstone)
        // 3. Sends Post 3 (fetched by Android directly from feed -> must be added to server)
        let now = SyncRepo::now_utc();
        let item3_url = Url::parse("https://example.com/post-3").unwrap();
        let req = PeerSyncRequest {
            peer_id: "android-peer-1".to_string(),
            device_name: Some("Pixel 10 Pro".to_string()),
            network_tier: NetworkTier::Wifi,
            weight: Some(4.2),
            updated_after: None,
            feeds: vec![PeerFeedRecord {
                id: "android-local-feed-id".to_string(),
                feed_type: FeedType::Rss,
                url: feed_url.clone(),
                title: Some("Example".to_string()),
                description: Some("Enriched description".to_string()),
                language: Some("en".to_string()),
                poll_interval: 30,
                last_polled: Some(now),
                last_polled_by: Some("android-peer-1".to_string()),
                last_error: None,
                last_error_by: None,
                is_active: true,
                created_at: None,
            }],
            items: vec![
                PeerItemRecord {
                    id: "android-local-item-1".to_string(),
                    feed_id: Some("android-local-feed-id".to_string()),
                    feed_url: Some(feed_url.clone()),
                    title: "Post 1".to_string(),
                    url: item1_url.clone(),
                    author: Some("Alice".to_string()),
                    published_at: Some(now),
                    created_at: Some(now),
                    summary: Some("Much longer and richer summary from peer".to_string()),
                    content_html: Some(
                        "<p>Much longer full article body from peer</p>".to_string(),
                    ),
                    thumbnail_url: Some("https://example.com/hero.jpg".to_string()),
                    audio_url: None,
                    duration: None,
                    content_type: ContentType::Article,
                    status: ItemStatus::Archived,
                    is_favorite: true,
                    read_at: Some(now),
                    read_progress: 0.9,
                    interaction_updated_at: Some(now),
                    tag_names: vec!["distributed".to_string(), "systems".to_string()],
                },
                PeerItemRecord {
                    id: post2_id.clone(),
                    feed_id: Some(feed.id.clone()),
                    feed_url: Some(feed_url.clone()),
                    title: "Post 2".to_string(),
                    url: item2_url.clone(),
                    author: None,
                    published_at: None,
                    created_at: Some(now - chrono::Duration::minutes(5)),
                    summary: Some("Old".to_string()),
                    content_html: None,
                    thumbnail_url: None,
                    audio_url: None,
                    duration: None,
                    content_type: ContentType::Article,
                    status: ItemStatus::Unread,
                    is_favorite: false,
                    read_at: None,
                    read_progress: 0.0,
                    interaction_updated_at: None,
                    tag_names: vec![],
                },
                PeerItemRecord {
                    id: String::new(),
                    feed_id: Some("android-local-feed-id".to_string()),
                    feed_url: Some(feed_url.clone()),
                    title: "Post 3 Fetched On Android".to_string(),
                    url: item3_url.clone(),
                    author: Some("Bob".to_string()),
                    published_at: Some(now),
                    created_at: Some(now),
                    summary: Some("Fetched by phone".to_string()),
                    content_html: Some("<p>Fetched by phone</p>".to_string()),
                    thumbnail_url: None,
                    audio_url: None,
                    duration: None,
                    content_type: ContentType::Article,
                    status: ItemStatus::Unread,
                    is_favorite: false,
                    read_at: None,
                    read_progress: 0.0,
                    interaction_updated_at: None,
                    tag_names: vec!["mobile".to_string()],
                },
            ],
            tags: vec![],
            tombstones: vec![],
        };

        let (resp, inserted) = db.sync().merge_peer_state(&req).await.unwrap();
        assert_eq!(inserted.len(), 1);
        assert_eq!(resp.feeds.len(), 1);
        assert_eq!(
            resp.items.len(),
            2,
            "Only Post 1 and Post 3 should exist (no duplicates, Post 2 tombstoned)"
        );
        assert_eq!(resp.active_peers.len(), 2);

        let post1_id = deterministic_item_id(Some(feed_url.as_str()), item1_url.as_str());
        let merged_post1 = db.items().get(&post1_id).await.unwrap().unwrap();
        assert_eq!(merged_post1.author.as_deref(), Some("Alice"));
        assert_eq!(
            merged_post1.content_html.as_deref(),
            Some("<p>Much longer full article body from peer</p>")
        );
        assert_eq!(merged_post1.thumbnail_url.as_deref(), Some("https://example.com/hero.jpg"));
        assert_eq!(merged_post1.status, ItemStatus::Archived);
        assert!(merged_post1.is_favorite);
        assert!(merged_post1.read_at.is_some());
        assert!((merged_post1.read_progress - 0.9).abs() < 1e-6);
        assert_eq!(
            merged_post1.tag_names.0,
            vec!["distributed".to_string(), "systems".to_string()]
        );

        // Delta sync: backdate Post 3 so only items modified at or after `watermark` are returned
        let watermark = now + chrono::Duration::seconds(10);
        let post3_id = deterministic_item_id(Some(feed_url.as_str()), item3_url.as_str());
        sqlx::query("UPDATE items SET updated_at = ? WHERE id = ?")
            .bind(now - chrono::Duration::seconds(60))
            .bind(&post3_id)
            .execute(db.pool())
            .await
            .unwrap();
        sqlx::query("UPDATE items SET updated_at = ? WHERE id = ?")
            .bind(watermark)
            .bind(&post1_id)
            .execute(db.pool())
            .await
            .unwrap();

        let delta_req = PeerSyncRequest {
            peer_id: "android-peer-1".to_string(),
            device_name: Some("Pixel 10 Pro".to_string()),
            network_tier: NetworkTier::Wifi,
            weight: None,
            updated_after: Some(watermark),
            feeds: vec![],
            items: vec![],
            tags: vec![],
            tombstones: vec![],
        };
        let (delta_resp, _) = db.sync().merge_peer_state(&delta_req).await.unwrap();
        let android_peer =
            delta_resp.active_peers.iter().find(|p| p.peer_id == "android-peer-1").unwrap();
        assert!(
            (android_peer.weight - 4.2).abs() < 1e-6,
            "Delta sync without a new poll sample must preserve previously measured throughput weight"
        );
        assert_eq!(
            delta_resp.items.len(),
            1,
            "Delta sync must return only Post 1 (modified at watermark) and omit unchanged Post 3"
        );
        assert_eq!(delta_resp.items[0].id, post1_id);

        // Routine server poll bumps feeds.updated_at, but peer archive with an older cursor must still succeed
        db.feeds().mark_error(&feed.id, "transient error").await.unwrap();
        let archive_req = PeerSyncRequest {
            peer_id: "android-peer-1".to_string(),
            device_name: Some("Pixel 10 Pro".to_string()),
            network_tier: NetworkTier::Wifi,
            weight: None,
            updated_after: Some(now - chrono::Duration::seconds(30)),
            feeds: vec![PeerFeedRecord {
                id: feed.id.clone(),
                url: feed_url.clone(),
                title: Some("Example Blog".to_string()),
                description: None,
                language: None,
                feed_type: FeedType::Rss,
                poll_interval: 30,
                last_polled: Some(now),
                last_polled_by: Some("android-peer-1".to_string()),
                last_error: None,
                last_error_by: None,
                is_active: false,
                created_at: None,
            }],
            items: vec![],
            tags: vec![],
            tombstones: vec![],
        };
        let (archive_resp, _) = db.sync().merge_peer_state(&archive_req).await.unwrap();
        assert!(!archive_resp.feeds[0].is_active);

        // A second peer doing an initial cursorless sync (updated_after: None) with default is_active: true
        // must NOT overwrite the server's explicit archive state
        let peer2_req = PeerSyncRequest {
            peer_id: "android-peer-2".to_string(),
            device_name: Some("Tablet".to_string()),
            network_tier: NetworkTier::Wifi,
            weight: None,
            updated_after: None,
            feeds: vec![PeerFeedRecord {
                id: feed.id.clone(),
                url: feed_url,
                title: Some("Example Blog".to_string()),
                description: None,
                language: None,
                feed_type: FeedType::Rss,
                poll_interval: 30,
                last_polled: None,
                last_polled_by: None,
                last_error: None,
                last_error_by: None,
                is_active: true,
                created_at: None,
            }],
            items: vec![],
            tags: vec![],
            tombstones: vec![],
        };
        let (peer2_resp, _) = db.sync().merge_peer_state(&peer2_req).await.unwrap();
        assert!(
            !peer2_resp.feeds[0].is_active,
            "Cursorless initial sync from a second peer must not unarchive a server-archived feed"
        );
    }

    #[test]
    fn test_canonical_sync_urls_and_deterministic_ids() {
        assert_eq!(canonicalize_sync_url("  HTTPS://Example.COM:443  "), "https://example.com/");
        assert_eq!(
            canonicalize_sync_url("http://Example.COM:80/Feed.xml"),
            "http://example.com/Feed.xml"
        );
        assert_eq!(
            canonicalize_sync_url("https://Example.COM:8443/demo"),
            "https://example.com:8443/demo"
        );
        assert_eq!(canonicalize_sync_url("  NOT A VALID URL  "), "not a valid url");

        assert_eq!(
            feed_natural_key("HTTPS://Example.COM:443"),
            feed_natural_key("https://example.com/")
        );
        assert_eq!(
            deterministic_feed_id("HTTPS://Example.COM:443"),
            deterministic_feed_id("https://example.com/")
        );
        assert_eq!(
            deterministic_item_id(
                Some("HTTP://Example.COM:80/rss.xml"),
                "HTTPS://Example.COM:443/post-1"
            ),
            deterministic_item_id(Some("http://example.com/rss.xml"), "https://example.com/post-1")
        );
    }

    #[tokio::test]
    async fn test_subsecond_delta_sync_and_multipage_content_html() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/subsec-rss.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Subsec"), None).await.unwrap();

        let batch_items: Vec<PollItemCandidate> = (0..205)
            .map(|idx| PollItemCandidate {
                title: format!("Article {idx}"),
                url: Url::parse(&format!("https://example.com/article-{idx}")).unwrap(),
                author: None,
                published_at: None,
                summary: format!("Summary {idx}"),
                content_html: Some(format!("<p>Body content {idx}</p>")),
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
            })
            .collect();

        db.feeds()
            .record_poll_batch(
                &feed.id,
                &PollBatch {
                    title: Some("Subsec".to_string()),
                    description: None,
                    language: None,
                    items: batch_items,
                },
            )
            .await
            .unwrap();

        // Full sync with >200 items must include content_html on ALL pages (not just page 1)
        let full_req = PeerSyncRequest {
            peer_id: "android-peer-1".to_string(),
            device_name: Some("Pixel".to_string()),
            network_tier: NetworkTier::Wifi,
            ..PeerSyncRequest::default()
        };
        let (full_resp, _) = db.sync().merge_peer_state(&full_req).await.unwrap();
        assert_eq!(full_resp.items.len(), 205);
        assert!(
            full_resp.items.iter().all(|item| item.content_html.is_some()),
            "Every synced item across page 1 and page 2 must retain inline content_html"
        );

        // Subsecond updated_after: item updated 500ms after `since` within the same second MUST be returned
        let old_ts =
            DateTime::parse_from_rfc3339("2026-11-11T20:00:00Z").unwrap().with_timezone(&Utc)
                - chrono::Duration::seconds(30);
        sqlx::query("UPDATE items SET updated_at = ?")
            .bind(old_ts)
            .execute(db.pool())
            .await
            .unwrap();

        let since =
            DateTime::parse_from_rfc3339("2026-11-11T20:00:00.100Z").unwrap().with_timezone(&Utc);
        let before_in_same_sec =
            DateTime::parse_from_rfc3339("2026-11-11T20:00:00.050Z").unwrap().with_timezone(&Utc);
        let after_500ms = since + chrono::Duration::milliseconds(500);

        let id0 = deterministic_item_id(Some(feed_url.as_str()), "https://example.com/article-0");
        let id1 = deterministic_item_id(Some(feed_url.as_str()), "https://example.com/article-1");

        sqlx::query("UPDATE items SET updated_at = ? WHERE id = ?")
            .bind(before_in_same_sec)
            .bind(&id0)
            .execute(db.pool())
            .await
            .unwrap();
        sqlx::query("UPDATE items SET updated_at = ? WHERE id = ?")
            .bind(after_500ms)
            .bind(&id1)
            .execute(db.pool())
            .await
            .unwrap();

        let filtered = db
            .items()
            .list_filtered_page(&ItemFilter { updated_after: Some(since), ..ItemFilter::default() })
            .await
            .unwrap();
        assert_eq!(filtered.items.len(), 1);
        assert_eq!(filtered.items[0].id, id1);

        let delta_req = PeerSyncRequest {
            peer_id: "android-peer-1".to_string(),
            device_name: Some("Pixel".to_string()),
            network_tier: NetworkTier::Wifi,
            updated_after: Some(since),
            ..PeerSyncRequest::default()
        };
        let (delta_resp, _) = db.sync().merge_peer_state(&delta_req).await.unwrap();
        assert!(
            delta_resp.items.iter().any(|i| i.id == id1 && i.content_html.is_some()),
            "Delta merge_peer_state must return item updated 500ms after since within same second"
        );
    }

    #[tokio::test]
    async fn test_tombstone_resurrection_requires_newer_created_at_and_preserves_custom_feed_title()
    {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/tombstone-feed.xml").unwrap();
        let feed =
            db.feeds().create(FeedType::Rss, &feed_url, Some("Original"), None).await.unwrap();
        let tag = db.tags().create("ephemeral", None).await.unwrap();

        assert!(db.feeds().delete(&feed.id).await.unwrap());
        assert!(db.tags().delete(&tag.id).await.unwrap());
        let del_after = SyncRepo::now_utc();

        // Stale offline poll (last_polled > del_at, created_at: None / older) must NOT resurrect feed or tag
        let stale_req = PeerSyncRequest {
            peer_id: "android-peer-1".to_string(),
            device_name: Some("Pixel".to_string()),
            network_tier: NetworkTier::Wifi,
            feeds: vec![PeerFeedRecord {
                id: feed.id.clone(),
                feed_type: FeedType::Rss,
                url: feed_url.clone(),
                title: Some("Stale Offline Copy".to_string()),
                description: None,
                language: None,
                poll_interval: 30,
                last_polled: Some(del_after + chrono::Duration::minutes(5)),
                last_polled_by: Some("android-peer-1".to_string()),
                last_error: None,
                last_error_by: None,
                is_active: true,
                created_at: None,
            }],
            tags: vec![PeerTagRecord {
                id: tag.id.clone(),
                name: "ephemeral".to_string(),
                color: None,
                created_at: Some(del_after - chrono::Duration::minutes(5)),
            }],
            ..PeerSyncRequest::default()
        };
        let (stale_resp, _) = db.sync().merge_peer_state(&stale_req).await.unwrap();
        assert!(stale_resp.feeds.is_empty(), "Stale last_polled must not resurrect deleted feed");
        assert!(stale_resp.tags.is_empty(), "Older created_at must not resurrect deleted tag");

        // Explicit re-creation with created_at > del_at clears tombstones and recreates feed + tag
        let recreate_ts = del_after + chrono::Duration::seconds(10);
        let recreate_req = PeerSyncRequest {
            peer_id: "android-peer-1".to_string(),
            device_name: Some("Pixel".to_string()),
            network_tier: NetworkTier::Wifi,
            feeds: vec![PeerFeedRecord {
                id: feed.id.clone(),
                feed_type: FeedType::Rss,
                url: feed_url.clone(),
                title: Some("Recreated Feed".to_string()),
                description: None,
                language: None,
                poll_interval: 30,
                last_polled: Some(recreate_ts),
                last_polled_by: Some("android-peer-1".to_string()),
                last_error: None,
                last_error_by: None,
                is_active: true,
                created_at: Some(recreate_ts),
            }],
            tags: vec![PeerTagRecord {
                id: tag.id.clone(),
                name: "ephemeral".to_string(),
                color: None,
                created_at: Some(recreate_ts),
            }],
            ..PeerSyncRequest::default()
        };
        let (recreate_resp, _) = db.sync().merge_peer_state(&recreate_req).await.unwrap();
        assert_eq!(recreate_resp.feeds.len(), 1);
        assert_eq!(recreate_resp.tags.len(), 1);

        // User renames feed on server; subsequent peer poll with raw RSS title must NOT clobber custom title
        assert!(
            db.feeds().update(&feed.id, Some("My Custom Feed Name"), None, None).await.unwrap()
        );
        let poll_bump_req = PeerSyncRequest {
            peer_id: "android-peer-1".to_string(),
            device_name: Some("Pixel".to_string()),
            network_tier: NetworkTier::Wifi,
            feeds: vec![PeerFeedRecord {
                id: feed.id.clone(),
                feed_type: FeedType::Rss,
                url: feed_url,
                title: Some("Raw Upstream RSS Title".to_string()),
                description: Some("Updated desc".to_string()),
                language: None,
                poll_interval: 30,
                last_polled: Some(recreate_ts + chrono::Duration::minutes(30)),
                last_polled_by: Some("android-peer-1".to_string()),
                last_error: None,
                last_error_by: None,
                is_active: true,
                created_at: Some(recreate_ts),
            }],
            ..PeerSyncRequest::default()
        };
        let (bump_resp, _) = db.sync().merge_peer_state(&poll_bump_req).await.unwrap();
        assert_eq!(bump_resp.feeds[0].title.as_deref(), Some("My Custom Feed Name"));
        assert_eq!(bump_resp.feeds[0].description.as_deref(), Some("Updated desc"));
    }

    #[test]
    fn test_cross_stack_golden_vectors() {
        #[derive(Deserialize)]
        struct CanonicalUrlVector {
            input: String,
            expected: String,
        }

        #[derive(Deserialize)]
        struct EntityIdVector {
            feed_url: String,
            item_url: String,
            tag_name: String,
            expected_feed_id: String,
            expected_item_id: String,
            expected_tag_id: String,
        }

        #[derive(Deserialize)]
        struct HrwPeerInput {
            peer_id: String,
            weight: f64,
        }

        #[derive(Deserialize)]
        struct HrwElectionVector {
            feed_url: String,
            peers: Vec<HrwPeerInput>,
            expected_winner: String,
        }

        #[derive(Deserialize)]
        struct GoldenVectors {
            canonical_urls: Vec<CanonicalUrlVector>,
            entity_ids: Vec<EntityIdVector>,
            hrw_elections: Vec<HrwElectionVector>,
        }

        let vectors_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../testdata/cross_stack_vectors.json");
        let raw = std::fs::read_to_string(&vectors_path).unwrap();
        let vectors: GoldenVectors = serde_json::from_str(&raw).unwrap();

        assert!(vectors.canonical_urls.len() >= 6);
        for case in &vectors.canonical_urls {
            assert_eq!(
                canonicalize_sync_url(&case.input),
                case.expected,
                "canonical_urls mismatch for input '{}'",
                case.input
            );
        }

        assert!(vectors.entity_ids.len() >= 3);
        for case in &vectors.entity_ids {
            assert_eq!(
                deterministic_feed_id(&case.feed_url),
                case.expected_feed_id,
                "feed_id mismatch for '{}'",
                case.feed_url
            );
            assert_eq!(
                deterministic_item_id(Some(&case.feed_url), &case.item_url),
                case.expected_item_id,
                "item_id mismatch for ('{}', '{}')",
                case.feed_url,
                case.item_url
            );
            assert_eq!(
                deterministic_tag_id(&case.tag_name),
                case.expected_tag_id,
                "tag_id mismatch for '{}'",
                case.tag_name
            );
        }

        assert!(vectors.hrw_elections.len() >= 3);
        let epoch_zero = DateTime::UNIX_EPOCH;
        for case in &vectors.hrw_elections {
            let peer_nodes: Vec<PeerNode> = case
                .peers
                .iter()
                .map(|p| PeerNode {
                    peer_id: p.peer_id.clone(),
                    device_name: p.peer_id.clone(),
                    network_tier: NetworkTier::Wifi,
                    weight: p.weight,
                    last_seen_at: epoch_zero,
                })
                .collect();
            let winner = select_feed_poller(
                &peer_nodes,
                &case.feed_url,
                30,
                epoch_zero,
                None,
                None,
                None,
                None,
            );
            assert_eq!(
                winner,
                Some(case.expected_winner.as_str()),
                "hrw_elections mismatch for '{}'",
                case.feed_url
            );
        }
    }
}
