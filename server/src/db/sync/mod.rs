mod activity;
mod catalog_sync;
mod hrw;
mod identity;
mod peer_merge;
#[cfg(test)]
mod tests;

#[allow(unused_imports)]
pub use activity::{ActivityEntry, SyncTombstone};
#[allow(unused_imports)]
pub use catalog_sync::{
    PeerCatalogEntryRecord, PeerCollectionEntryRecord, PeerCollectionGeneratorRecord,
    PeerCollectionSpecRecord, PeerComparisonRecord, PeerConsumptionLogRecord,
};
use chrono::{DateTime, SubsecRound, Utc};
#[allow(unused_imports)]
pub use hrw::{
    DEFAULT_ONLINE_PEER_WEIGHT, NetworkTier, PEER_LEASE_TTL_MINUTES, PeerNode, SERVER_PEER_ID,
    compute_throughput_weight, effective_peer_weight, rendezvous_uniform_unit, select_feed_poller,
    should_peer_poll_feed,
};
#[allow(unused_imports)]
pub use identity::{
    ALBUMS_1001_COLLECTION_ID, STARRED_COLLECTION_ID, UP_NEXT_COLLECTION_ID,
    canonical_ordering_json, canonicalize_sync_url, collection_entry_natural_key,
    deterministic_auto_consumption_log_id, deterministic_catalog_entry_id,
    deterministic_comparison_id, deterministic_consumption_log_id,
    deterministic_enclosure_media_id, deterministic_entity_uuid, deterministic_feed_id,
    deterministic_item_id, deterministic_system_collection_id, deterministic_tag_id,
    deterministic_title_entry_id, feed_natural_key, item_natural_key, tag_natural_key,
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
