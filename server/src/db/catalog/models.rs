use std::collections::HashSet;

use chrono::{DateTime, Utc};
pub use roche_core::domain::{DynamicCollectionRule, EntryIdentifier};
use roche_core::external_links::EXTERNAL_ID_DISPLAY_ORDER;
use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::db::types::{CollectionRole, ConsumptionStatus, EntryKind, HexColor};

#[must_use]
pub fn is_dynamic_rule(rule_json: Option<&str>) -> bool {
    rule_json.is_some_and(|s| !s.trim().is_empty())
}

#[must_use]
pub fn collection_immutable_reason(
    role: CollectionRole,
    rule_json: Option<&str>,
) -> Option<&'static str> {
    if is_dynamic_rule(rule_json) {
        Some("dynamic")
    } else if role == CollectionRole::Feed {
        Some("feed")
    } else {
        None
    }
}

#[derive(Debug, Error)]
pub enum CatalogError {
    #[error("Database error: {0}")]
    Db(#[from] sqlx::Error),

    #[error("{0} not found")]
    NotFound(String),

    #[error("Ambiguous lookup for '{query}': matches {}", .candidates.join(", "))]
    AmbiguousLookup { query: String, candidates: Vec<String> },

    #[error("Cannot manually modify entries of {reason} collection '{title}'")]
    ImmutableCollection { title: String, reason: &'static str },

    #[error("Invalid catalog input: {0}")]
    InvalidInput(String),

    #[error("Conflict: {0}")]
    Conflict(String),

    #[error("Internal catalog error: {0}")]
    Internal(String),
}

pub type CatalogResult<T> = std::result::Result<T, CatalogError>;

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

macro_rules! raw_entry_columns {
    () => {
        "id, kind, title, creators_json, url, published_at, release_year, summary, \
         thumbnail_url, metadata_json, merged_into_id, created_at, updated_at"
    };
    (e) => {
        "e.id, e.kind, e.title, e.creators_json, e.url, e.published_at, e.release_year, \
         e.summary, e.thumbnail_url, e.metadata_json, e.merged_into_id, e.created_at, \
         e.updated_at"
    };
}

pub(super) use raw_entry_columns;

macro_rules! raw_collection_columns {
    () => {
        "e.id, cs.slug, e.title, e.summary, e.kind, cs.collection_role, \
         cs.rule_json, cs.color, \
         EXISTS (SELECT 1 FROM collection_generators cg WHERE cg.collection_id = e.id) AS has_generator, \
         e.created_at, e.updated_at"
    };
}

pub(super) use raw_collection_columns;

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

/// Partial update payload for explicitly editing an existing non-collection catalog entry.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct UpdateEntryInput {
    #[serde(default)]
    pub kind: Option<EntryKind>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub creators: Option<Vec<String>>,
    #[allow(clippy::option_option)]
    #[serde(default)]
    pub url: Option<Option<String>>,
    #[allow(clippy::option_option)]
    #[serde(default)]
    pub published_at: Option<Option<DateTime<Utc>>>,
    #[allow(clippy::option_option)]
    #[serde(default)]
    pub release_year: Option<Option<i32>>,
    #[allow(clippy::option_option)]
    #[serde(default)]
    pub summary: Option<Option<String>>,
    #[allow(clippy::option_option)]
    #[serde(default)]
    pub thumbnail_url: Option<Option<String>>,
    /// Per-scheme identifier replacements (`(scheme, Some(value))` to set/replace,
    /// `(scheme, None)` to clear identifiers for that scheme on this entry).
    #[serde(default)]
    pub identifier_updates: Vec<(String, Option<String>)>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CollectionSummary {
    pub id: String,
    pub slug: Option<String>,
    pub title: String,
    pub summary: Option<String>,
    pub kind: EntryKind,
    pub role: CollectionRole,
    pub rule_json: Option<String>,
    pub color: Option<HexColor>,
    pub member_count: usize,
    pub comparison_count: usize,
    pub has_generator: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl CollectionSummary {
    #[must_use]
    pub fn is_dynamic(&self) -> bool {
        is_dynamic_rule(self.rule_json.as_deref())
    }

    #[must_use]
    pub fn is_manual(&self) -> bool {
        !self.is_dynamic() && self.role != CollectionRole::Feed
    }
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
pub struct CollectionGeneratorState {
    pub collection_id: String,
    pub collection_slug: Option<String>,
    pub collection_title: String,
    pub enabled: bool,
    pub current_entry: Option<CatalogEntry>,
    pub drawn_at: Option<DateTime<Utc>>,
    pub updated_at: DateTime<Utc>,
    pub total_pool: usize,
    pub consumed_count: usize,
    pub abandoned_count: usize,
    pub remaining_count: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum DiaryKindFilter {
    /// Default diary view: excludes container kinds and individual `song` plays so large music
    /// streaming histories do not drown out books, albums, films, and articles.
    #[default]
    DefaultNonSong,
    /// Include all diary-visible kinds, including individual `song` plays.
    All,
    /// Restrict strictly to a single [`EntryKind`].
    Only(EntryKind),
}

impl DiaryKindFilter {
    /// Parses a `?kind=` / `--kind` filter value (`""`/`None` -> `DefaultNonSong`,
    /// `"all"` -> `All`, otherwise [`EntryKind::parse_flexible`]).
    pub fn parse_optional(raw: Option<&str>) -> CatalogResult<Self> {
        let Some(trimmed) = raw.map(str::trim).filter(|s| !s.is_empty()) else {
            return Ok(Self::DefaultNonSong);
        };
        if trimmed.eq_ignore_ascii_case("all") {
            return Ok(Self::All);
        }
        let kind = EntryKind::parse_flexible(trimmed)
            .map_err(|e| CatalogError::InvalidInput(e.to_string()))?;
        if matches!(
            kind,
            EntryKind::Artist | EntryKind::Podcast | EntryKind::Publication | EntryKind::Channel
        ) {
            return Err(CatalogError::InvalidInput(format!(
                "Kind '{}' is a container and does not appear in Diary",
                kind.as_str()
            )));
        }
        Ok(Self::Only(kind))
    }

    #[must_use]
    pub const fn query_param_value(self) -> Option<&'static str> {
        match self {
            Self::DefaultNonSong => None,
            Self::All => Some("all"),
            Self::Only(kind) => Some(kind.as_str()),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EntryDetailView {
    pub entry: CatalogEntry,
    pub children: Vec<CatalogEntry>,
    pub parents: Vec<CatalogEntry>,
    pub log_history: Vec<ConsumptionLogRow>,
    #[serde(default)]
    pub total_log_count: usize,
    #[serde(default)]
    pub has_consumed_log: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WantSearchMatch {
    pub queue_id: String,
    pub queue_slug: String,
    pub queue_title: String,
    pub entry: CatalogEntry,
}

#[derive(Debug, Clone, sqlx::FromRow)]
pub(super) struct RawCollectionRow {
    pub id: String,
    pub slug: Option<String>,
    pub title: String,
    pub summary: Option<String>,
    pub kind: EntryKind,
    pub collection_role: CollectionRole,
    pub rule_json: Option<String>,
    pub color: Option<String>,
    pub has_generator: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl RawCollectionRow {
    #[must_use]
    pub fn is_dynamic(&self) -> bool {
        is_dynamic_rule(self.rule_json.as_deref())
    }

    #[must_use]
    pub fn immutable_reason(&self) -> Option<&'static str> {
        collection_immutable_reason(self.collection_role, self.rule_json.as_deref())
    }

    #[must_use]
    pub fn into_summary(self, member_count: usize, comparison_count: usize) -> CollectionSummary {
        CollectionSummary {
            id: self.id,
            slug: self.slug,
            title: self.title,
            summary: self.summary,
            kind: self.kind,
            role: self.collection_role,
            rule_json: self.rule_json,
            color: HexColor::from_stored_tolerant(self.color.as_deref()),
            member_count,
            comparison_count,
            has_generator: self.has_generator,
            created_at: self.created_at,
            updated_at: self.updated_at,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct BatchEntryInput {
    #[serde(default)]
    pub ref_key: Option<String>,
    #[serde(flatten)]
    pub entry: UpsertEntryInput,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchLogInput {
    pub entry_ref: String,
    pub status: ConsumptionStatus,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub ts: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchComparisonInput {
    #[serde(default)]
    pub scope_collection_id: Option<String>,
    pub ordering: Vec<Vec<String>>,
    #[serde(default)]
    pub ts: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct BatchCollectionMemberInput {
    pub entry_ref: String,
    #[serde(default)]
    pub position: Option<f64>,
    #[serde(default)]
    pub added_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchCollectionInput {
    #[serde(default)]
    pub ref_key: Option<String>,
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub slug: Option<String>,
    #[serde(default = "default_batch_collection_role")]
    pub role: CollectionRole,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub color: Option<String>,
    #[serde(default)]
    pub members: Vec<BatchCollectionMemberInput>,
    #[serde(default)]
    pub replace_members: bool,
}

impl Default for BatchCollectionInput {
    fn default() -> Self {
        Self {
            ref_key: None,
            id: None,
            title: String::new(),
            slug: None,
            role: default_batch_collection_role(),
            summary: None,
            color: None,
            members: Vec::new(),
            replace_members: false,
        }
    }
}

const fn default_batch_collection_role() -> CollectionRole {
    CollectionRole::List
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct BatchGeneratorInput {
    pub collection_ref: String,
    #[serde(default)]
    pub enabled: Option<bool>,
    #[serde(default)]
    pub current_entry_ref: Option<String>,
    #[serde(default)]
    pub drawn_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct CatalogBatchInput {
    #[serde(default)]
    pub collections: Vec<BatchCollectionInput>,
    #[serde(default)]
    pub entries: Vec<BatchEntryInput>,
    #[serde(default)]
    pub logs: Vec<BatchLogInput>,
    #[serde(default)]
    pub comparisons: Vec<BatchComparisonInput>,
    #[serde(default)]
    pub generators: Vec<BatchGeneratorInput>,
    #[serde(default)]
    pub dry_run: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogBatchResult {
    pub collections_processed: usize,
    pub entries_processed: usize,
    pub logs_recorded: usize,
    pub comparisons_recorded: usize,
    pub generators_updated: usize,
    pub dry_run: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogBatchStreamResult {
    pub batches_processed: usize,
    pub collections_processed: usize,
    pub entries_processed: usize,
    pub logs_recorded: usize,
    pub comparisons_recorded: usize,
    pub generators_updated: usize,
    pub dry_run: bool,
}

impl CatalogBatchStreamResult {
    pub const fn accumulate(&mut self, batch: &CatalogBatchResult) {
        self.batches_processed += 1;
        self.collections_processed += batch.collections_processed;
        self.entries_processed += batch.entries_processed;
        self.logs_recorded += batch.logs_recorded;
        self.comparisons_recorded += batch.comparisons_recorded;
        self.generators_updated += batch.generators_updated;
    }
}

/// Pure database snapshot of a collection and its rankable members, consumed by
/// `CatalogService` to run EP inference and cluster selection outside the DB layer.
#[derive(Debug, Clone)]
pub struct CollectionRankingInputs {
    pub collection: CollectionSummary,
    pub is_priority_scope: bool,
    pub members: Vec<CatalogEntry>,
}

/// Lightweight paginated metadata snapshot for a collection: full counts, per-kind counts, and
/// hydrated records ONLY for compared members (never hydrating thousands of uncompared rows).
#[derive(Debug, Clone)]
pub struct CollectionPageMeta {
    pub collection: CollectionSummary,
    pub is_priority_scope: bool,
    pub total_count: usize,
    pub kind_counts: Vec<(EntryKind, usize)>,
    pub compared_members: Vec<CatalogEntry>,
    pub present_member_ids: HashSet<String>,
}

/// Exported catalog entry record (alias for [`CatalogEntry`]).
pub type ExportCatalogEntry = CatalogEntry;

/// Exported consumption log record (alias for [`ConsumptionLogRow`]).
pub type ExportConsumptionLog = ConsumptionLogRow;

/// Exported comparison ordering record (alias for [`ComparisonRecord`]).
pub type ExportComparison = ComparisonRecord;

/// Exported `collection_entries` membership row.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, sqlx::FromRow)]
pub struct ExportCollectionEntry {
    pub collection_id: String,
    pub entry_id: String,
    pub position: Option<f64>,
    pub added_at: DateTime<Utc>,
}

/// Exported `collection_generators` row.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, sqlx::FromRow)]
pub struct ExportCollectionGenerator {
    pub collection_id: String,
    pub enabled: bool,
    pub current_entry_id: Option<String>,
    pub drawn_at: Option<DateTime<Utc>>,
    pub updated_at: DateTime<Utc>,
}

/// Complete, point-in-time snapshot of unified catalog tables for full data export.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct CatalogExportSnapshot {
    pub entries: Vec<CatalogEntry>,
    pub collections: Vec<CollectionSummary>,
    pub collection_entries: Vec<ExportCollectionEntry>,
    pub consumption_log: Vec<ConsumptionLogRow>,
    pub comparisons: Vec<ComparisonRecord>,
    pub collection_generators: Vec<ExportCollectionGenerator>,
}
