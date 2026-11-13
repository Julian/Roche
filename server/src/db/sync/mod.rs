mod activity;
mod hrw;
mod identity;
mod peer_merge;
#[cfg(test)]
mod tests;

#[allow(unused_imports)]
pub use activity::{ActivityEntry, SyncTombstone};
use chrono::{DateTime, SubsecRound, Utc};
#[allow(unused_imports)]
pub use hrw::{
    DEFAULT_ONLINE_PEER_WEIGHT, NetworkTier, PEER_LEASE_TTL_MINUTES, PeerNode, SERVER_PEER_ID,
    compute_throughput_weight, effective_peer_weight, rendezvous_uniform_unit, select_feed_poller,
    should_peer_poll_feed,
};
#[allow(unused_imports)]
pub use identity::{
    canonicalize_sync_url, deterministic_entity_uuid, deterministic_feed_id, deterministic_item_id,
    deterministic_tag_id, feed_natural_key, item_natural_key, tag_natural_key,
};
#[allow(unused_imports)]
pub use peer_merge::{
    InsertedItemCandidate, MAX_PEER_SYNC_OUTGOING_ITEMS, PeerFeedRecord, PeerItemRecord,
    PeerSyncRequest, PeerSyncResponse, PeerTagRecord,
};
use sqlx::SqlitePool;

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
}
