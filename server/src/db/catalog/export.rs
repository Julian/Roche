use std::collections::{HashMap, HashSet};

use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use super::{
    CatalogRepo,
    collections::{matches_dynamic_collection_rule, parse_dynamic_collection_rule},
    models::{
        CatalogEntry, CatalogExportSnapshot, CatalogResult, CollectionSummary, ComparisonRecord,
        ConsumptionLogRow, ExportCollectionEntry, ExportCollectionGenerator, RawCollectionRow,
        RawEntryRow, raw_collection_columns, raw_entry_columns,
    },
};
use crate::db::types::{CollectionMode, CollectionRole, ConsumptionStatus};

#[derive(sqlx::FromRow)]
struct RawComparisonRow {
    id: String,
    ts: DateTime<Utc>,
    scope_collection_id: Option<String>,
    ordering_json: String,
    created_at: DateTime<Utc>,
}

impl CatalogRepo<'_> {
    /// Exports a consistent read-snapshot of the entire unified catalog graph.
    pub async fn export_catalog_snapshot(&self) -> CatalogResult<CatalogExportSnapshot> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let snapshot = Self::export_catalog_snapshot_in_tx(&mut tx).await?;
        tx.commit().await?;
        Ok(snapshot)
    }

    /// Exports the entire unified catalog graph inside an existing read transaction so callers
    /// (such as `RocheService::export_data`) observe a single consistent WAL snapshot.
    pub(crate) async fn export_catalog_snapshot_in_tx(
        conn: &mut SqliteConnection,
    ) -> CatalogResult<CatalogExportSnapshot> {
        let raw_entries: Vec<RawEntryRow> = sqlx::query_as(concat!(
            "SELECT ",
            raw_entry_columns!(),
            " FROM entries
             WHERE merged_into_id IS NULL
             ORDER BY created_at ASC, id ASC"
        ))
        .fetch_all(&mut *conn)
        .await?;
        let entries = Self::hydrate_entries_in_tx(&mut *conn, raw_entries).await?;

        let raw_collections: Vec<RawCollectionRow> = sqlx::query_as(concat!(
            "SELECT ",
            raw_collection_columns!(),
            " FROM collection_specs cs
             JOIN entries e ON e.id = cs.collection_id
             WHERE e.merged_into_id IS NULL
               AND cs.collection_role != 'tag'
               AND cs.collection_role != 'feed'
             ORDER BY cs.collection_role ASC, LOWER(e.title) ASC, e.id ASC"
        ))
        .fetch_all(&mut *conn)
        .await?;

        let collection_entries: Vec<ExportCollectionEntry> = sqlx::query_as(
            "SELECT ce.collection_id, ce.entry_id, ce.position, ce.added_at
             FROM collection_entries ce
             JOIN entries c ON c.id = ce.collection_id
             JOIN entries e ON e.id = ce.entry_id
             WHERE c.merged_into_id IS NULL AND e.merged_into_id IS NULL
             ORDER BY ce.collection_id ASC, ce.position ASC NULLS LAST, ce.added_at ASC, ce.entry_id ASC",
        )
        .fetch_all(&mut *conn)
        .await?;

        let consumption_log: Vec<ConsumptionLogRow> = sqlx::query_as(
            "SELECT cl.id, cl.entry_id, cl.ts, cl.status, cl.notes, cl.created_at
             FROM consumption_log cl
             JOIN entries e ON e.id = cl.entry_id
             WHERE e.merged_into_id IS NULL
             ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', cl.ts) ASC, cl.id ASC",
        )
        .fetch_all(&mut *conn)
        .await?;

        let raw_comparisons: Vec<RawComparisonRow> = sqlx::query_as(
            "SELECT id, ts, scope_collection_id, ordering_json, created_at
             FROM comparisons
             ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', ts) ASC, id ASC",
        )
        .fetch_all(&mut *conn)
        .await?;
        let comparisons: Vec<ComparisonRecord> = raw_comparisons
            .into_iter()
            .filter_map(|row| {
                let ordering: Vec<Vec<String>> = serde_json::from_str(&row.ordering_json).ok()?;
                Some(ComparisonRecord {
                    id: row.id,
                    ts: row.ts,
                    scope_collection_id: row.scope_collection_id,
                    ordering,
                    created_at: row.created_at,
                })
            })
            .collect();

        let collection_generators: Vec<ExportCollectionGenerator> = sqlx::query_as(
            "SELECT cg.collection_id, cg.enabled, cg.current_entry_id, cg.drawn_at, cg.updated_at
             FROM collection_generators cg
             JOIN entries e ON e.id = cg.collection_id
             WHERE e.merged_into_id IS NULL
             ORDER BY cg.collection_id ASC",
        )
        .fetch_all(&mut *conn)
        .await?;

        let collections = summarize_exported_collections(
            raw_collections,
            &entries,
            &collection_entries,
            &consumption_log,
            &comparisons,
        );

        Ok(CatalogExportSnapshot {
            entries,
            collections,
            collection_entries,
            consumption_log,
            comparisons,
            collection_generators,
        })
    }
}

fn summarize_exported_collections(
    raw_collections: Vec<RawCollectionRow>,
    entries: &[CatalogEntry],
    collection_entries: &[ExportCollectionEntry],
    consumption_log: &[ConsumptionLogRow],
    comparisons: &[ComparisonRecord],
) -> Vec<CollectionSummary> {
    let mut statuses_by_entry: HashMap<&str, Vec<ConsumptionStatus>> = HashMap::new();
    for log in consumption_log {
        statuses_by_entry.entry(log.entry_id.as_str()).or_default().push(log.status);
    }

    let mut members_by_col: HashMap<&str, Vec<&str>> = HashMap::new();
    for ce in collection_entries {
        members_by_col.entry(ce.collection_id.as_str()).or_default().push(ce.entry_id.as_str());
    }

    let mut global_comp_indices_by_entry: HashMap<&str, Vec<usize>> = HashMap::new();
    let mut scoped_comps_by_col: HashMap<&str, Vec<&ComparisonRecord>> = HashMap::new();
    for (idx, comp) in comparisons.iter().enumerate() {
        if let Some(scope_id) = comp.scope_collection_id.as_deref() {
            scoped_comps_by_col.entry(scope_id).or_default().push(comp);
        } else {
            let mut seen = HashSet::new();
            for entry_id in comp.ordering.iter().flatten() {
                if seen.insert(entry_id.as_str()) {
                    global_comp_indices_by_entry.entry(entry_id.as_str()).or_default().push(idx);
                }
            }
        }
    }

    let mut out = Vec::with_capacity(raw_collections.len());
    for raw in raw_collections {
        let member_ids = collect_rankable_member_ids_for_export(
            &raw,
            entries,
            &members_by_col,
            &statuses_by_entry,
        );
        let comp_count = count_collection_comparisons_for_export(
            &raw.id,
            raw.collection_role,
            &member_ids,
            &global_comp_indices_by_entry,
            &scoped_comps_by_col,
        );
        out.push(raw.into_summary(member_ids.len(), comp_count));
    }
    out
}

fn collect_rankable_member_ids_for_export<'a>(
    raw: &RawCollectionRow,
    entries: &'a [CatalogEntry],
    members_by_col: &HashMap<&str, Vec<&'a str>>,
    statuses_by_entry: &HashMap<&str, Vec<ConsumptionStatus>>,
) -> Vec<&'a str> {
    match raw.mode {
        CollectionMode::Manual | CollectionMode::Feed => {
            members_by_col.get(raw.id.as_str()).map_or_else(Vec::new, |ids| {
                ids.iter()
                    .copied()
                    .filter(|&eid| {
                        !raw.has_generator
                            || statuses_by_entry
                                .get(eid)
                                .is_some_and(|st| st.contains(&ConsumptionStatus::Consumed))
                    })
                    .collect()
            })
        }
        CollectionMode::Dynamic => {
            let Some(rule) = parse_dynamic_collection_rule(raw.rule_json.as_deref()) else {
                return Vec::new();
            };
            entries
                .iter()
                .filter(|entry| {
                    let statuses =
                        statuses_by_entry.get(entry.id.as_str()).map_or(&[][..], Vec::as_slice);
                    matches_dynamic_collection_rule(
                        &rule,
                        entry.kind,
                        &entry.creators,
                        entry.release_year,
                        statuses,
                    )
                })
                .map(|entry| entry.id.as_str())
                .collect()
        }
    }
}

fn count_collection_comparisons_for_export(
    collection_id: &str,
    role: CollectionRole,
    member_ids: &[&str],
    global_comp_indices_by_entry: &HashMap<&str, Vec<usize>>,
    scoped_comps_by_col: &HashMap<&str, Vec<&ComparisonRecord>>,
) -> usize {
    if member_ids.is_empty() {
        return 0;
    }
    if role == CollectionRole::Want {
        let member_set: HashSet<&str> = member_ids.iter().copied().collect();
        scoped_comps_by_col.get(collection_id).map_or(0, |scoped| {
            scoped
                .iter()
                .filter(|comp| {
                    comp.ordering.iter().flatten().any(|id| member_set.contains(id.as_str()))
                })
                .count()
        })
    } else {
        let mut matched = HashSet::new();
        for &mid in member_ids {
            if let Some(indices) = global_comp_indices_by_entry.get(mid) {
                matched.extend(indices.iter().copied());
            }
        }
        matched.len()
    }
}
