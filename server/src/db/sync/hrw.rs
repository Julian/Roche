use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::SqliteConnection;

use super::{SyncRepo, identity::canonicalize_sync_url};
use crate::db::Feed;

pub const SERVER_PEER_ID: &str = "server";
pub const PEER_LEASE_TTL_MINUTES: i64 = 120;
pub const DEFAULT_ONLINE_PEER_WEIGHT: f64 = 1.0;
pub const MAX_CONSECUTIVE_FAILURES: i64 = 20;
pub const DEFAULT_MAX_BACKOFF_SECS: i64 = 24 * 3600;
pub const ABSOLUTE_MAX_BACKOFF_SECS: i64 = 7 * 24 * 3600;
pub const THROUGHPUT_EWMA_ALPHA: f64 = 0.2;
pub const MIN_PEER_WEIGHT: f64 = 0.2;
pub const MAX_CELLULAR_PEER_WEIGHT: f64 = 2.0;
pub const MAX_PEER_WEIGHT: f64 = 10.0;

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

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
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

/// Computes a deterministic per-feed phase offset in `[0, interval_secs)` from the canonicalized
/// feed URL. Bit-for-bit identical across Rust and Kotlin/JVM.
#[must_use]
pub fn feed_phase_offset_secs(feed_url: &str, poll_interval_mins: i64) -> i64 {
    let interval_secs = poll_interval_mins.max(1).saturating_mul(60);
    let canon_url = canonicalize_sync_url(feed_url);
    let mut hasher = Sha256::new();
    hasher.update(b"roche:phase:v1:");
    hasher.update(canon_url.as_bytes());
    let digest = hasher.finalize();
    let mut head = [0u8; 8];
    head.copy_from_slice(&digest[..8]);
    let raw = u64::from_be_bytes(head);
    let interval_u64 = u64::try_from(interval_secs).unwrap_or(60);
    i64::try_from(raw % interval_u64).unwrap_or(0)
}

/// Returns the earliest phase-aligned epoch second `>= earliest_epoch_secs` matching `phase_secs`
/// modulo `interval_secs`.
#[must_use]
pub const fn next_phase_slot_at_or_after(
    earliest_epoch_secs: i64,
    phase_secs: i64,
    interval_secs: i64,
) -> i64 {
    let safe_interval = if interval_secs < 1 { 1 } else { interval_secs };
    let safe_phase = phase_secs.rem_euclid(safe_interval);
    let slot = earliest_epoch_secs
        .saturating_sub(safe_phase)
        .saturating_add(safe_interval - 1)
        .div_euclid(safe_interval);
    slot.saturating_mul(safe_interval).saturating_add(safe_phase)
}

/// Computes the target delay in seconds before the next poll, applying exponential backoff when
/// `consecutive_failures > 0`.
#[must_use]
pub fn target_poll_interval_secs(poll_interval_mins: i64, consecutive_failures: i64) -> i64 {
    let interval_secs = poll_interval_mins.max(1).saturating_mul(60);
    if consecutive_failures <= 0 {
        return interval_secs;
    }
    let cap_secs = DEFAULT_MAX_BACKOFF_SECS
        .max(interval_secs.saturating_mul(4))
        .min(ABSOLUTE_MAX_BACKOFF_SECS);
    let shift = u32::try_from(consecutive_failures.clamp(1, 16)).unwrap_or(1);
    let backed_off = interval_secs.saturating_mul(1_i64 << shift);
    backed_off.min(cap_secs).max(interval_secs)
}

/// Computes the next scheduled phase-aligned poll timestamp in Unix epoch seconds.
#[must_use]
pub fn next_scheduled_poll_epoch_secs(
    feed_url: &str,
    poll_interval_mins: i64,
    last_polled_epoch_secs: i64,
    consecutive_failures: i64,
) -> i64 {
    let interval = poll_interval_mins.max(1).saturating_mul(60);
    let phase = feed_phase_offset_secs(feed_url, poll_interval_mins);
    let earliest = if consecutive_failures <= 0 {
        let min_healthy_gap = (interval / 10).min(120);
        last_polled_epoch_secs.saturating_add(min_healthy_gap)
    } else {
        let target_delay = target_poll_interval_secs(poll_interval_mins, consecutive_failures);
        let snap_margin = interval / 2;
        last_polled_epoch_secs.saturating_add(target_delay).saturating_sub(snap_margin)
    };
    next_phase_slot_at_or_after(earliest, phase, interval)
}

/// Normalizes `consecutive_failures` against `last_error`:
/// - Blank/absent `last_error` yields `0`.
/// - Non-blank `last_error` with `<= 0` (e.g. from a pre-upgrade peer) yields `1`.
/// - Otherwise clamps to `[1, MAX_CONSECUTIVE_FAILURES]`.
#[must_use]
pub fn normalize_consecutive_failures(last_error: Option<&str>, raw: i64) -> i64 {
    let has_error = last_error.is_some_and(|e| !e.trim().is_empty());
    if !has_error {
        0
    } else if raw <= 0 {
        1
    } else {
        raw.min(MAX_CONSECUTIVE_FAILURES)
    }
}

/// Computes a peer's dynamic polling weight from processed work and elapsed wall-clock time.
///
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
    units_per_sec.clamp(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT)
}

/// Folds a per-feed throughput weight sample into the peer's EWMA throughput weight.
#[must_use]
#[allow(clippy::suboptimal_flops)]
pub fn update_throughput_ewma(current_weight: f64, sample_weight: f64) -> f64 {
    let base = if current_weight.is_finite() && current_weight > 0.0 {
        current_weight.clamp(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT)
    } else {
        DEFAULT_ONLINE_PEER_WEIGHT
    };
    let sample = if sample_weight.is_finite() {
        sample_weight.clamp(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT)
    } else {
        base
    };
    (THROUGHPUT_EWMA_ALPHA * sample + (1.0 - THROUGHPUT_EWMA_ALPHA) * base)
        .clamp(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT)
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
    if peer.network_tier == NetworkTier::Offline || !peer.weight.is_finite() {
        return 0.0;
    }
    let base = peer.weight.clamp(0.0, MAX_PEER_WEIGHT);
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

/// Dynamic throughput-weighted Rendezvous Hash (HRW) peer selector with phase-aligned epochs.
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
    let phase_secs = feed_phase_offset_secs(feed_url, poll_interval_mins);
    let epoch = now.timestamp().saturating_sub(phase_secs).div_euclid(interval_secs);

    let mut best_peer: Option<(&'a str, f64)> = None;
    for peer in peers {
        let mut weight = effective_peer_weight(peer, last_polled_by, last_error, last_error_by)
            * peer_liveness_factor(peer.last_seen_at, now);
        if let Some((boost_peer_id, factor)) = local_boost
            && peer.peer_id == boost_peer_id
        {
            weight *= factor;
        }
        if !weight.is_finite() || weight <= 0.0 {
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
///
/// Uses deterministic per-feed phase slots for healthy feeds, exponential backoff for failing
/// feeds, single-hop cross-peer rescue on failure #1 (without ping-pong), and progressive overdue
/// rescue relative to the scheduled due time.
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
    let effective_failures = if has_error { feed.consecutive_failures.max(1) } else { 0 };

    // Allow ONE immediate cross-peer rescue when a different peer hit failure #1.
    // Once a second peer also fails (`effective_failures >= 2`), immediate rescue stops
    // and exponential backoff applies to all peers (no ping-pong!).
    let is_cross_peer_rescue = has_error
        && effective_failures == 1
        && feed
            .last_error_by
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .is_some_and(|err_peer| err_peer != local_peer_id);

    let interval_secs = feed.poll_interval.max(1).saturating_mul(60);
    let now_secs = now.timestamp();
    let due_at_secs = feed.last_polled.map_or(now_secs, |lp| {
        next_scheduled_poll_epoch_secs(
            feed.url.as_str(),
            feed.poll_interval,
            lp.timestamp(),
            effective_failures,
        )
    });

    let is_normally_due = now_secs >= due_at_secs;
    if !is_cross_peer_rescue && !is_normally_due {
        return false;
    }

    let overdue_secs = now_secs.saturating_sub(due_at_secs).max(0);

    // If a feed is overdue by >= 1 full interval past `due_at_secs`, any active peer can rescue
    // it even if another peer won the initial allocation for the current epoch.
    if feed.last_polled.is_some() && overdue_secs >= interval_secs {
        return true;
    }

    if active_peers.len() <= 1 {
        return true;
    }

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

#[must_use]
pub fn bound_peer_weight(network_tier: NetworkTier, raw_weight: Option<f64>) -> f64 {
    let finite = raw_weight.filter(|w| w.is_finite());
    match network_tier {
        NetworkTier::Offline => 0.0,
        NetworkTier::Cellular => {
            finite.unwrap_or(0.5).clamp(MIN_PEER_WEIGHT, MAX_CELLULAR_PEER_WEIGHT)
        }
        NetworkTier::Wifi | NetworkTier::Wired => {
            finite.unwrap_or(DEFAULT_ONLINE_PEER_WEIGHT).clamp(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT)
        }
    }
}

impl SyncRepo<'_> {
    pub async fn get_peer_weight(&self, peer_id: &str) -> Result<Option<f64>> {
        Ok(sqlx::query_scalar("SELECT weight FROM peer_nodes WHERE peer_id = ?")
            .bind(peer_id)
            .fetch_optional(self.pool)
            .await?)
    }

    pub async fn prune_stale_peer_nodes(&self, older_than_days: i64) -> Result<u64> {
        let cutoff = Self::now_utc() - chrono::Duration::days(older_than_days.max(1));
        let res = sqlx::query(
            "DELETE FROM peer_nodes
             WHERE peer_id != ?
               AND strftime('%Y-%m-%dT%H:%M:%fZ', last_seen_at)
                 < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
        )
        .bind(SERVER_PEER_ID)
        .bind(cutoff)
        .execute(self.pool)
        .await?;
        Ok(res.rows_affected())
    }

    pub async fn upsert_peer_node(
        &self,
        peer_id: &str,
        device_name: &str,
        network_tier: NetworkTier,
        weight: f64,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let clamped_weight = bound_peer_weight(network_tier, Some(weight));
        sqlx::query!(
            "INSERT INTO peer_nodes (peer_id, device_name, network_tier, weight, last_seen_at)
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(peer_id) DO UPDATE SET
                 device_name = excluded.device_name,
                 network_tier = excluded.network_tier,
                 weight = excluded.weight,
                 last_seen_at = excluded.last_seen_at",
            peer_id,
            device_name,
            network_tier as _,
            clamped_weight,
            now as _,
        )
        .execute(self.pool)
        .await?;
        Ok(())
    }

    pub async fn list_active_peers(&self, now: DateTime<Utc>) -> Result<Vec<PeerNode>> {
        let mut conn = self.pool.acquire().await?;
        Self::list_active_peers_in_tx(&mut conn, now).await
    }

    pub(super) async fn list_active_peers_in_tx(
        conn: &mut SqliteConnection,
        now: DateTime<Utc>,
    ) -> Result<Vec<PeerNode>> {
        let cutoff = now - chrono::Duration::minutes(PEER_LEASE_TTL_MINUTES);
        Ok(sqlx::query_as!(
            PeerNode,
            "SELECT peer_id,
                    device_name,
                    network_tier AS \"network_tier: NetworkTier\",
                    weight,
                    last_seen_at AS \"last_seen_at: DateTime<Utc>\"
             FROM peer_nodes
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', last_seen_at) >= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
             ORDER BY peer_id ASC",
            cutoff as _,
        )
        .fetch_all(conn)
        .await?)
    }

    pub(super) async fn ensure_server_peer_in_tx(
        conn: &mut SqliteConnection,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let wifi_tier = NetworkTier::Wifi;
        sqlx::query!(
            "INSERT INTO peer_nodes (peer_id, device_name, network_tier, weight, last_seen_at)
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(peer_id) DO UPDATE SET
                 device_name = excluded.device_name,
                 last_seen_at = excluded.last_seen_at",
            SERVER_PEER_ID,
            "Roche Headless Peer",
            wifi_tier as _,
            DEFAULT_ONLINE_PEER_WEIGHT,
            now as _,
        )
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
        let initial_weight = bound_peer_weight(network_tier, measured_weight);
        let bounded_measured = measured_weight.map(|w| bound_peer_weight(network_tier, Some(w)));
        let max_tier_weight = match network_tier {
            NetworkTier::Offline => 0.0,
            NetworkTier::Cellular => MAX_CELLULAR_PEER_WEIGHT,
            NetworkTier::Wifi | NetworkTier::Wired => MAX_PEER_WEIGHT,
        };
        sqlx::query(
            "INSERT INTO peer_nodes (peer_id, device_name, network_tier, weight, last_seen_at)
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(peer_id) DO UPDATE SET
                 device_name = excluded.device_name,
                 network_tier = excluded.network_tier,
                 weight = CASE
                     WHEN excluded.network_tier = 'offline' THEN 0.0
                     WHEN ? IS NOT NULL THEN ?
                     WHEN peer_nodes.weight > 0.0 THEN MIN(peer_nodes.weight, ?)
                     ELSE excluded.weight
                 END,
                 last_seen_at = excluded.last_seen_at",
        )
        .bind(peer_id)
        .bind(device_name)
        .bind(network_tier)
        .bind(initial_weight)
        .bind(now)
        .bind(bounded_measured)
        .bind(bounded_measured)
        .bind(max_tier_weight)
        .execute(conn)
        .await?;
        Ok(())
    }
}
