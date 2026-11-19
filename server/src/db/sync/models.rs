use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use super::{
    activity::SyncTombstone,
    hrw::{NetworkTier, PeerNode},
};
use crate::db::{
    CollectionMode, CollectionRole, ConsumptionStatus, ContentType, EntryIdentifier, EntryKind,
    FeedType, InboxState, PollItemCandidate,
};

pub const MAX_PEER_SYNC_PAGE_CHANGES: usize = 2_000;
pub const MAX_PEER_SYNC_PAGE_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_SYNC_INLINE_HTML_BYTES: usize = 512_000;

pub type InsertedItemCandidate = (String, PollItemCandidate);

const fn default_manual_mode() -> CollectionMode {
    CollectionMode::Manual
}

const fn default_poll_interval() -> i64 {
    30
}

const fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncEntryContent {
    #[serde(default)]
    pub content_type: ContentType,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content_html: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub audio_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncEntryState {
    #[serde(default)]
    pub inbox_state: InboxState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub read_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub progress: f64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub interaction_updated_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub hlc: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncCollectionSpec {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub slug: Option<String>,
    #[serde(default = "default_manual_mode")]
    pub mode: CollectionMode,
    #[serde(default)]
    pub role: CollectionRole,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rule_json: Option<String>,
    #[serde(default)]
    pub show_in_lists: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub color: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncFeedSource {
    pub feed_url: String,
    #[serde(default)]
    pub source_type: FeedType,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub feed_title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub language: Option<String>,
    #[serde(default = "default_poll_interval")]
    pub poll_interval: i64,
    #[serde(default = "default_true")]
    pub is_active: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_polled_at: Option<DateTime<Utc>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_polled_by: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_error: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_error_by: Option<String>,
    #[serde(default)]
    pub consecutive_failures: i64,
    #[serde(default)]
    pub hlc: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncEntryRecord {
    #[serde(default)]
    pub id: String,
    pub kind: EntryKind,
    pub title: String,
    #[serde(default)]
    pub creators: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub published_at: Option<DateTime<Utc>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub release_year: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub summary: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub thumbnail_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub metadata: Option<serde_json::Value>,
    #[serde(default)]
    pub identifiers: Vec<EntryIdentifier>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub merged_into_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub created_at: Option<DateTime<Utc>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub updated_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub hlc: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content: Option<SyncEntryContent>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub state: Option<SyncEntryState>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub collection_spec: Option<SyncCollectionSpec>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub feed_source: Option<SyncFeedSource>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct SyncCollectionEntryRecord {
    pub collection_id: String,
    pub entry_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub position: Option<f64>,
    pub added_at: DateTime<Utc>,
    #[serde(default)]
    pub hlc: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct SyncCollectionGeneratorRecord {
    pub collection_id: String,
    pub enabled: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub current_entry_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub drawn_at: Option<DateTime<Utc>>,
    pub updated_at: DateTime<Utc>,
    #[serde(default)]
    pub hlc: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct SyncConsumptionLogRecord {
    #[serde(default)]
    pub id: String,
    pub entry_id: String,
    pub ts: DateTime<Utc>,
    pub status: ConsumptionStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub notes: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub created_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub hlc: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncComparisonRecord {
    #[serde(default)]
    pub id: String,
    pub ts: DateTime<Utc>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scope_collection_id: Option<String>,
    pub ordering: Vec<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub created_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub hlc: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct PeerSyncRequest {
    pub peer_id: String,
    #[serde(default)]
    pub device_name: Option<String>,
    #[serde(default)]
    pub network_tier: NetworkTier,
    #[serde(default)]
    pub weight: Option<f64>,
    #[serde(default)]
    pub since_seq: i64,
    #[serde(default)]
    pub client_hlc: Option<String>,
    #[serde(default)]
    pub entries: Vec<SyncEntryRecord>,
    #[serde(default)]
    pub collection_entries: Vec<SyncCollectionEntryRecord>,
    #[serde(default)]
    pub collection_generators: Vec<SyncCollectionGeneratorRecord>,
    #[serde(default)]
    pub consumption_log: Vec<SyncConsumptionLogRecord>,
    #[serde(default)]
    pub comparisons: Vec<SyncComparisonRecord>,
    #[serde(default)]
    pub tombstones: Vec<SyncTombstone>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerSyncResponse {
    pub server_peer_id: String,
    pub sync_seq: i64,
    pub server_hlc: String,
    pub sync_timestamp: DateTime<Utc>,
    #[serde(default)]
    pub has_more: bool,
    #[serde(default)]
    pub reset_required: bool,
    pub active_peers: Vec<PeerNode>,
    #[serde(default)]
    pub entries: Vec<SyncEntryRecord>,
    #[serde(default)]
    pub collection_entries: Vec<SyncCollectionEntryRecord>,
    #[serde(default)]
    pub collection_generators: Vec<SyncCollectionGeneratorRecord>,
    #[serde(default)]
    pub consumption_log: Vec<SyncConsumptionLogRecord>,
    #[serde(default)]
    pub comparisons: Vec<SyncComparisonRecord>,
    #[serde(default)]
    pub tombstones: Vec<SyncTombstone>,
}
