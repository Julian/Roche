use chrono::{DateTime, Utc};
use roche_core::boards::{BoardOrderMode, BoardType};
use serde::{Deserialize, Serialize};
use url::Url;

use super::{
    activity::SyncTombstone,
    hrw::{NetworkTier, PeerNode},
    identity::canonicalize_sync_url,
};
use crate::{
    db::{
        CollectionRole, ConsumptionStatus, ContentType, EntryIdentifier, EntryKind, FeedType,
        InboxState, PollItemCandidate,
    },
    util::media_urls::{extract_arxiv_id, is_direct_audio_url},
};

pub const MAX_PEER_SYNC_PAGE_CHANGES: usize = 2_000;
pub const MAX_PEER_SYNC_PAGE_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_SYNC_INLINE_HTML_BYTES: usize = 512_000;

pub type InsertedItemCandidate = (String, PollItemCandidate);

const fn default_poll_interval() -> i64 {
    30
}

const fn default_true() -> bool {
    true
}

fn default_empty_json_object() -> String {
    "{}".to_owned()
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
    #[serde(default)]
    pub role: CollectionRole,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rule_json: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub color: Option<String>,
}

impl SyncCollectionSpec {
    #[must_use]
    pub fn is_dynamic(&self) -> bool {
        self.rule_json.as_deref().is_some_and(|s| !s.trim().is_empty())
    }
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

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct SyncBoardItemRecord {
    pub external_id: String,
    pub rank: i64,
    pub title: String,
    pub url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub discussion_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub author: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub domain: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub published_at: Option<DateTime<Utc>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub score: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub comment_count: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub summary: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub self_text_html: Option<String>,
    #[serde(default = "default_empty_json_object")]
    pub metadata_json: String,
    pub fetched_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncBoardRecord {
    #[serde(default)]
    pub id: String,
    pub adapter_type: BoardType,
    pub source_key: String,
    pub source_url: String,
    #[serde(default = "default_empty_json_object")]
    pub source_config_json: String,
    pub title: String,
    #[serde(default)]
    pub order_mode: BoardOrderMode,
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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub snapshot_fetched_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub snapshot_hlc: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    #[serde(default)]
    pub hlc: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub snapshot_items: Option<Vec<SyncBoardItemRecord>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct SyncBoardItemStateRecord {
    pub board_id: String,
    pub external_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seen_at: Option<DateTime<Utc>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dismissed_at: Option<DateTime<Utc>>,
    pub last_seen_in_snapshot_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
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
    #[serde(default)]
    pub boards: Vec<SyncBoardRecord>,
    #[serde(default)]
    pub board_item_states: Vec<SyncBoardItemStateRecord>,
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
    #[serde(default)]
    pub boards: Vec<SyncBoardRecord>,
    #[serde(default)]
    pub board_item_states: Vec<SyncBoardItemStateRecord>,
}

/// Incoming peer sync entry whose HTML, summary excerpt, and thumbnail URL have already been
/// sanitized outside the database layer before entering the SQLite write transaction.
#[derive(Debug)]
pub struct PreparedSyncEntry {
    pub record: SyncEntryRecord,
    pub parsed_url: Option<Url>,
    pub canon_url: Option<String>,
    pub arxiv_id: Option<String>,
    pub clean_summary: Option<String>,
    pub clean_html: Option<String>,
    pub clean_thumb: Option<String>,
    pub clean_audio_url: Option<String>,
    pub clean_duration: Option<i64>,
    pub resolved_content_type: ContentType,
}

impl PreparedSyncEntry {
    #[must_use]
    pub fn from_sanitized_fields(
        rec: SyncEntryRecord,
        parsed_url: Option<Url>,
        clean_html: Option<String>,
        clean_summary: Option<String>,
        clean_thumb: Option<String>,
    ) -> Self {
        let canon_url = rec
            .url
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(canonicalize_sync_url)
            .filter(|s| !s.is_empty());
        let arxiv_id = rec.url.as_deref().and_then(extract_arxiv_id);

        let base_content_type = rec.content.as_ref().map_or_else(
            || match rec.kind {
                EntryKind::Video => ContentType::Video,
                EntryKind::Paper => ContentType::Paper,
                EntryKind::PodcastEpisode => ContentType::Audio,
                _ => ContentType::Article,
            },
            |c| c.content_type,
        );

        let clean_audio_url = rec
            .content
            .as_ref()
            .and_then(|c| c.audio_url.as_deref())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|u| Url::parse(u).ok())
            .map(String::from)
            .or_else(|| {
                parsed_url
                    .as_ref()
                    .filter(|u| base_content_type == ContentType::Audio && is_direct_audio_url(u))
                    .map(|u| u.as_str().to_owned())
            });

        let clean_duration = rec.content.as_ref().and_then(|c| c.duration).filter(|&d| d > 0);
        let resolved_content_type = base_content_type;

        Self {
            record: rec,
            parsed_url,
            canon_url,
            arxiv_id,
            clean_summary,
            clean_html,
            clean_thumb,
            clean_audio_url,
            clean_duration,
            resolved_content_type,
        }
    }

    #[cfg(test)]
    #[must_use]
    pub fn from_pre_cleaned_record(rec: SyncEntryRecord) -> Self {
        let parsed_url = rec
            .url
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|u| Url::parse(u).ok());
        let clean_html = rec
            .content
            .as_ref()
            .and_then(|c| c.content_html.as_deref())
            .filter(|h| !h.trim().is_empty())
            .map(str::to_owned);
        let clean_summary =
            rec.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
        let clean_thumb = rec
            .thumbnail_url
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|u| crate::util::url_guard::parse_public_url(u).ok().map(String::from));
        Self::from_sanitized_fields(rec, parsed_url, clean_html, clean_summary, clean_thumb)
    }
}
