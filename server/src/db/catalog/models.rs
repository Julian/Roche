use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use thiserror::Error;

use super::identity_norm::{
    EXTERNAL_ID_DISPLAY_ORDER, external_identifier_label, external_identifier_url,
};
use crate::{
    db::types::{CollectionMode, CollectionRole, ConsumptionStatus, EntryKind},
    ranking::Score,
};

#[derive(Debug, Error)]
pub enum CatalogError {
    #[error("Database error: {0}")]
    Db(#[from] sqlx::Error),

    #[error("{0} not found")]
    NotFound(String),

    #[error("Ambiguous lookup for '{query}': matches {matches}")]
    AmbiguousLookup { query: String, matches: String },

    #[error("Cannot manually modify entries of {mode:?} collection '{title}'")]
    ImmutableCollection { title: String, mode: CollectionMode },

    #[error("Invalid catalog input: {0}")]
    InvalidInput(String),

    #[error("Conflict: {0}")]
    Conflict(String),
}

pub type CatalogResult<T> = std::result::Result<T, CatalogError>;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EntryIdentifier {
    pub scheme: String,
    pub value: String,
}

impl EntryIdentifier {
    #[must_use]
    pub fn label(&self) -> &str {
        external_identifier_label(&self.scheme)
    }

    #[must_use]
    pub fn external_url(&self) -> Option<String> {
        external_identifier_url(&self.scheme, &self.value)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogEntry {
    pub id: String,
    pub kind: EntryKind,
    pub title: String,
    pub creators: Vec<String>,
    pub url: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub release_year: Option<i32>,
    pub summary: Option<String>,
    pub thumbnail_url: Option<String>,
    pub metadata: serde_json::Value,
    pub identifiers: Vec<EntryIdentifier>,
    pub merged_into_id: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl CatalogEntry {
    #[must_use]
    pub fn identifier(&self, scheme: &str) -> Option<&str> {
        self.identifiers
            .iter()
            .find(|id| id.scheme.eq_ignore_ascii_case(scheme))
            .map(|id| id.value.as_str())
    }

    /// Resolves the best web/open URL for this entry (`entries.url` first, then ordered `identifiers`).
    #[must_use]
    pub fn primary_open_url(&self) -> Option<String> {
        if let Some(u) = self.url.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            return Some(u.to_owned());
        }
        for preferred in EXTERNAL_ID_DISPLAY_ORDER {
            if let Some(ident) = self.identifiers.iter().find(|i| i.scheme == preferred)
                && let Some(ext_url) = ident.external_url()
            {
                return Some(ext_url);
            }
        }
        self.identifiers.iter().find_map(EntryIdentifier::external_url)
    }

    #[must_use]
    pub fn sorted_external_identifiers(&self) -> Vec<&EntryIdentifier> {
        let mut list: Vec<&EntryIdentifier> = self
            .identifiers
            .iter()
            .filter(|i| !matches!(i.scheme.as_str(), "slug" | "tag"))
            .collect();
        list.sort_by(|a, b| {
            let ra = EXTERNAL_ID_DISPLAY_ORDER
                .iter()
                .position(|&s| s == a.scheme)
                .unwrap_or(EXTERNAL_ID_DISPLAY_ORDER.len());
            let rb = EXTERNAL_ID_DISPLAY_ORDER
                .iter()
                .position(|&s| s == b.scheme)
                .unwrap_or(EXTERNAL_ID_DISPLAY_ORDER.len());
            ra.cmp(&rb).then_with(|| a.scheme.cmp(&b.scheme)).then_with(|| a.value.cmp(&b.value))
        });
        list
    }
}

#[derive(Debug, Clone, sqlx::FromRow)]
pub(super) struct RawEntryRow {
    pub id: String,
    pub kind: EntryKind,
    pub title: String,
    pub creators_json: String,
    pub url: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub release_year: Option<i32>,
    pub summary: Option<String>,
    pub thumbnail_url: Option<String>,
    pub metadata_json: String,
    pub merged_into_id: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl RawEntryRow {
    pub fn into_entry(self, identifiers: Vec<EntryIdentifier>) -> CatalogEntry {
        let creators: Vec<String> = serde_json::from_str(&self.creators_json).unwrap_or_default();
        let metadata: serde_json::Value = serde_json::from_str(&self.metadata_json)
            .unwrap_or_else(|_| serde_json::Value::Object(serde_json::Map::new()));
        CatalogEntry {
            id: self.id,
            kind: self.kind,
            title: self.title,
            creators,
            url: self.url,
            published_at: self.published_at,
            release_year: self.release_year,
            summary: self.summary,
            thumbnail_url: self.thumbnail_url,
            metadata,
            identifiers,
            merged_into_id: self.merged_into_id,
            created_at: self.created_at,
            updated_at: self.updated_at,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct UpsertEntryInput {
    #[serde(default)]
    pub id: Option<String>,
    pub kind: Option<EntryKind>,
    pub title: String,
    #[serde(default)]
    pub creators: Vec<String>,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub published_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub release_year: Option<i32>,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub metadata: Option<serde_json::Value>,
    #[serde(default)]
    pub identifiers: Vec<EntryIdentifier>,
    #[serde(default)]
    pub parent_collection_id: Option<String>,
    #[serde(default)]
    pub parent_position: Option<f64>,
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DynamicCollectionRule {
    #[serde(default)]
    pub kind: Option<EntryKind>,
    #[serde(default)]
    pub kinds: Vec<EntryKind>,
    #[serde(default)]
    pub consumed: Option<bool>,
    #[serde(default)]
    pub status: Option<ConsumptionStatus>,
    #[serde(default, alias = "creator_contains")]
    pub creator: Option<String>,
    #[serde(default)]
    pub release_year: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CollectionSummary {
    pub id: String,
    pub slug: Option<String>,
    pub title: String,
    pub summary: Option<String>,
    pub kind: EntryKind,
    pub mode: CollectionMode,
    pub role: CollectionRole,
    pub rule_json: Option<String>,
    pub show_in_lists: bool,
    pub color: Option<String>,
    pub member_count: usize,
    pub comparison_count: usize,
    pub has_generator: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RankedEntryRow {
    pub rank: usize,
    pub entry: CatalogEntry,
    pub score: Score,
    pub tier: Option<char>,
    pub bar_pct: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RankedCollectionView {
    pub collection: CollectionSummary,
    pub is_priority_scope: bool,
    pub comparison_count: usize,
    pub rows: Vec<RankedEntryRow>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ConsumptionLogView {
    pub id: String,
    pub entry: CatalogEntry,
    pub ts: DateTime<Utc>,
    pub status: ConsumptionStatus,
    pub notes: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, sqlx::FromRow)]
pub struct ConsumptionLogRow {
    pub id: String,
    pub entry_id: String,
    pub ts: DateTime<Utc>,
    pub status: ConsumptionStatus,
    pub notes: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ComparisonRecord {
    pub id: String,
    pub ts: DateTime<Utc>,
    pub scope_collection_id: Option<String>,
    pub ordering: Vec<Vec<String>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Generator1001State {
    pub collection_id: String,
    pub enabled: bool,
    pub current_entry: Option<CatalogEntry>,
    pub drawn_at: Option<DateTime<Utc>>,
    pub updated_at: DateTime<Utc>,
    pub total_pool: usize,
    pub consumed_count: usize,
    pub abandoned_count: usize,
    pub remaining_count: usize,
}

#[derive(Debug, Clone, Default)]
pub struct ClusterSelectFilter {
    pub max_k: usize,
    pub iteration: usize,
    pub focus_ids: Vec<String>,
    pub recent_limit: Option<usize>,
    pub by_creator: Option<String>,
    pub from_parent_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EntryDetailView {
    pub entry: CatalogEntry,
    pub children: Vec<CatalogEntry>,
    pub parents: Vec<CatalogEntry>,
    pub log_history: Vec<ConsumptionLogRow>,
}

#[derive(Debug, Clone, sqlx::FromRow)]
pub(super) struct RawCollectionRow {
    pub id: String,
    pub slug: Option<String>,
    pub title: String,
    pub summary: Option<String>,
    pub kind: EntryKind,
    pub mode: CollectionMode,
    pub collection_role: CollectionRole,
    pub rule_json: Option<String>,
    pub show_in_lists: bool,
    pub color: Option<String>,
    pub has_generator: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}
