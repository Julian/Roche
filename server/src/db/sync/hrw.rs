use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::SqliteConnection;

use super::SyncRepo;
use crate::db::Feed;

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

impl SyncRepo<'_> {
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

    pub(super) async fn ensure_server_peer_in_tx(
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

    pub(super) async fn upsert_peer_in_tx(
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
}
