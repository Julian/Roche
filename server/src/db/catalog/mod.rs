use sqlx::SqlitePool;

use super::WriteLock;

mod batch;
mod collections;
mod consumption;
mod entries;
mod export;
mod generators;
mod merge;
mod models;
mod rankings;
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::float_cmp, clippy::too_many_lines)]
mod tests;

pub use collections::derive_manual_collection_slug_and_id;
pub use models::{
    ActiveStartedEntry, BatchCollectionInput, BatchCollectionMemberInput, BatchComparisonInput,
    BatchEntryInput, BatchGeneratorInput, BatchLogInput, CatalogBatchInput, CatalogBatchResult,
    CatalogBatchStreamResult, CatalogEntry, CatalogError, CatalogExportSnapshot, CatalogResult,
    CollectionGeneratorState, CollectionPageMeta, CollectionRankableIds, CollectionRankingInputs,
    CollectionSummary, ComparisonRecord, ConsumptionLogRow, ConsumptionLogView, DiaryKindFilter,
    DynamicCollectionRule, EntryDetailView, EntryIdentifier, ExportCatalogEntry,
    ExportCollectionEntry, ExportCollectionGenerator, ExportComparison, ExportConsumptionLog,
    UpdateEntryInput, UpsertEntryInput, WantSearchMatch,
};
pub use roche_core::{
    domain::{matches_dynamic_collection_rule, parse_dynamic_collection_rule},
    external_links::{
        external_identifier_label, external_identifier_url, external_identifier_url_for_kind,
    },
    identity_norm::{
        PREFERRED_IDENTIFIER_SCHEMES, choose_deterministic_entry_id, normalize_identifier_scheme,
        normalize_identifier_value, strip_album_edition_suffix,
    },
};

#[derive(Clone, Copy)]
pub struct CatalogRepo<'a> {
    pool: &'a SqlitePool,
    write_lock: &'a WriteLock,
}

impl<'a> CatalogRepo<'a> {
    #[must_use]
    pub(crate) const fn new(pool: &'a SqlitePool, write_lock: &'a WriteLock) -> Self {
        Self { pool, write_lock }
    }
}
