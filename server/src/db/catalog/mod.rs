use sqlx::SqlitePool;

mod batch;
mod collections;
mod consumption;
mod entries;
mod generators;
mod identity_norm;
mod merge;
mod models;
mod rankings;
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::float_cmp, clippy::too_many_lines)]
mod tests;

pub use collections::{matches_dynamic_collection_rule, parse_dynamic_collection_rule};
pub use identity_norm::{
    PREFERRED_IDENTIFIER_SCHEMES, choose_deterministic_entry_id, external_identifier_label,
    external_identifier_url, normalize_identifier_scheme, normalize_identifier_value,
    strip_album_edition_suffix,
};
pub use models::{
    BatchCollectionInput, BatchCollectionMemberInput, BatchComparisonInput, BatchEntryInput,
    BatchGeneratorInput, BatchLogInput, CatalogBatchInput, CatalogBatchResult, CatalogEntry,
    CatalogError, CatalogResult, ClusterSelectFilter, CollectionGeneratorState, CollectionSummary,
    ComparisonRecord, ConsumptionLogRow, ConsumptionLogView, DynamicCollectionRule,
    EntryDetailView, EntryIdentifier, RankedCollectionView, RankedEntryRow, UpsertEntryInput,
};

#[derive(Clone, Copy)]
pub struct CatalogRepo<'a> {
    pool: &'a SqlitePool,
}

impl<'a> CatalogRepo<'a> {
    #[must_use]
    pub const fn new(pool: &'a SqlitePool) -> Self {
        Self { pool }
    }
}
