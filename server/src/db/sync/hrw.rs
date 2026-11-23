use anyhow::Result;
use chrono::{DateTime, Utc};
#[allow(unused_imports)]
pub use roche_core::hrw::{
    ABSOLUTE_MAX_BACKOFF_SECS, DEFAULT_MAX_BACKOFF_SECS, DEFAULT_ONLINE_PEER_WEIGHT,
    MAX_CELLULAR_PEER_WEIGHT, MAX_CONSECUTIVE_FAILURES, MAX_PEER_WEIGHT, MIN_PEER_WEIGHT,
    NetworkTier, PEER_LEASE_TTL_MINUTES, PeerNode, PollableFeed, SERVER_PEER_ID,
    THROUGHPUT_EWMA_ALPHA, bound_peer_weight, compute_throughput_weight, effective_peer_weight,
    feed_phase_offset_secs, next_phase_slot_at_or_after, next_scheduled_poll_epoch_secs,
    normalize_consecutive_failures, rendezvous_uniform_unit, select_feed_poller,
    should_peer_poll_feed, target_poll_interval_secs, update_throughput_ewma,
};
use sqlx::SqliteConnection;

use super::SyncRepo;
use crate::db::Feed;

impl PollableFeed for Feed {
    fn url_str(&self) -> &str {
        self.url.as_str()
    }

    fn poll_interval_mins(&self) -> i64 {
        self.poll_interval
    }

    fn is_active(&self) -> bool {
        self.is_active
    }

    fn last_polled(&self) -> Option<DateTime<Utc>> {
        self.last_polled
    }

    fn last_polled_by(&self) -> Option<&str> {
        self.last_polled_by.as_deref()
    }

    fn last_error(&self) -> Option<&str> {
        self.last_error.as_deref()
    }

    fn last_error_by(&self) -> Option<&str> {
        self.last_error_by.as_deref()
    }

    fn consecutive_failures(&self) -> i64 {
        self.consecutive_failures
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
        let _write_guard = self.write_lock.acquire().await?;
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
        let _write_guard = self.write_lock.acquire().await?;
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
