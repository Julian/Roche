use std::collections::HashMap;

use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use super::{
    CatalogRepo,
    identity_norm::{normalize_identifier_scheme, normalize_identifier_value},
    models::{
        BatchComparisonInput, CatalogBatchInput, CatalogBatchResult, CatalogError, CatalogResult,
    },
};
use crate::{
    db::{sync::SyncRepo, types::EntryKind},
    ranking::validate_ordering,
};

impl CatalogRepo<'_> {
    /// Applies a declarative batch of entries, consumption logs, comparisons, and optional
    /// `1001 Albums` generator updates inside a single atomic SQLite transaction.
    ///
    /// When `batch.dry_run` is `true`, all validation and database writes run inside the
    /// transaction and are rolled back cleanly before returning.
    pub async fn apply_batch(
        &self,
        batch: &CatalogBatchInput,
    ) -> CatalogResult<CatalogBatchResult> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        let needs_1001_pool = batch.generator_1001.is_some()
            || batch.entries.iter().any(|e| e.entry.kind == Some(EntryKind::Album));
        if needs_1001_pool {
            Self::ensure_1001_albums_seeded_in_tx(&mut tx, now).await?;
        }

        let mut ref_map: HashMap<String, String> = HashMap::with_capacity(batch.entries.len());
        let mut entries_processed = 0usize;

        for item in &batch.entries {
            let kind = item.entry.kind.ok_or_else(|| {
                CatalogError::InvalidInput("Batch entry `kind` is required".to_owned())
            })?;
            let title = item.entry.title.trim();
            if title.is_empty() {
                return Err(CatalogError::InvalidInput(
                    "Batch entry `title` cannot be empty".to_owned(),
                ));
            }
            let entry_id = Self::upsert_entry_in_tx(&mut tx, &item.entry, kind, title, now).await?;
            if let Some(key) = item.ref_key.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                ref_map.insert(key.to_owned(), entry_id);
            }
            entries_processed += 1;
        }

        let mut logs_recorded = 0usize;
        for log in &batch.logs {
            let target_id =
                Self::resolve_batch_ref_in_tx(&mut tx, &ref_map, &log.entry_ref).await?;
            let event_ts = log.ts.unwrap_or(now);
            Self::log_consumption_in_tx(
                &mut tx,
                &target_id,
                log.status,
                log.notes.as_deref(),
                event_ts,
                now,
            )
            .await?;
            logs_recorded += 1;
        }

        let comparisons_recorded =
            Self::apply_batch_comparisons_in_tx(&mut tx, &ref_map, &batch.comparisons, now).await?;

        let active_1001_entry_id = if let Some(gen_update) = &batch.generator_1001 {
            let explicit_id = match gen_update
                .current_entry_ref
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                Some(r) => Some(Self::resolve_batch_ref_in_tx(&mut tx, &ref_map, r).await?),
                None => None,
            };
            Self::update_1001_generator_in_tx(
                &mut tx,
                gen_update.enabled,
                explicit_id.as_deref(),
                gen_update.drawn_at,
                now,
            )
            .await?
        } else {
            None
        };

        if batch.dry_run {
            tx.rollback().await?;
        } else {
            tx.commit().await?;
        }

        Ok(CatalogBatchResult {
            entries_processed,
            logs_recorded,
            comparisons_recorded,
            active_1001_entry_id,
            dry_run: batch.dry_run,
        })
    }

    async fn apply_batch_comparisons_in_tx(
        tx: &mut SqliteConnection,
        ref_map: &HashMap<String, String>,
        comparisons: &[BatchComparisonInput],
        now: DateTime<Utc>,
    ) -> CatalogResult<usize> {
        let mut comparisons_recorded = 0usize;
        for comp in comparisons {
            let mut resolved_ordering = Vec::with_capacity(comp.ordering.len());
            for group in &comp.ordering {
                let mut resolved_group = Vec::with_capacity(group.len());
                for raw_ref in group {
                    let eid = Self::resolve_batch_ref_in_tx(tx, ref_map, raw_ref).await?;
                    resolved_group.push(eid);
                }
                resolved_ordering.push(resolved_group);
            }
            validate_ordering(&resolved_ordering).map_err(CatalogError::InvalidInput)?;

            let clean_scope = match comp
                .scope_collection_id
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                Some(sc) => Some(Self::resolve_collection_id_in_tx(tx, sc).await?),
                None => None,
            };
            let event_ts = comp.ts.unwrap_or(now);
            let (_, inserted) =
                Self::append_comparison_in_tx(tx, clean_scope, resolved_ordering, event_ts, now)
                    .await?;
            if inserted {
                comparisons_recorded += 1;
            }
        }
        Ok(comparisons_recorded)
    }

    async fn resolve_batch_ref_in_tx(
        tx: &mut SqliteConnection,
        ref_map: &HashMap<String, String>,
        raw_ref: &str,
    ) -> CatalogResult<String> {
        let trimmed = raw_ref.trim();
        if trimmed.is_empty() {
            return Err(CatalogError::InvalidInput("Entry reference cannot be empty".to_owned()));
        }
        if let Some(mapped) = ref_map.get(trimmed) {
            return Ok(mapped.clone());
        }
        if let Some(canon_id) = Self::follow_merged_id_in_tx(tx, trimmed).await? {
            return Ok(canon_id);
        }
        if let Some((raw_scheme, raw_val)) =
            trimmed.split_once('=').or_else(|| trimmed.split_once(':'))
        {
            let scheme = if trimmed.starts_with("spotify:") {
                "spotify".to_owned()
            } else {
                normalize_identifier_scheme(raw_scheme)
            };
            let val = if scheme == "spotify" {
                normalize_identifier_value(EntryKind::Album, "spotify", trimmed)
            } else {
                normalize_identifier_value(EntryKind::Album, &scheme, raw_val)
            };
            let found: Option<String> = sqlx::query_scalar(
                "SELECT entry_id FROM entry_identifiers WHERE scheme = ? AND value = ? LIMIT 1",
            )
            .bind(&scheme)
            .bind(&val)
            .fetch_optional(&mut *tx)
            .await?;
            if let Some(owner_id) = found
                && let Some(canon) = Self::follow_merged_id_in_tx(tx, &owner_id).await?
            {
                return Ok(canon);
            }
        }
        Err(CatalogError::NotFound(format!("Entry reference '{trimmed}'")))
    }

    async fn resolve_collection_id_in_tx(
        tx: &mut SqliteConnection,
        slug_or_id: &str,
    ) -> CatalogResult<String> {
        let trimmed = slug_or_id.trim();
        let found: Option<String> = sqlx::query_scalar(
            "SELECT collection_id FROM collection_specs
             WHERE collection_id = ? OR LOWER(slug) = LOWER(?)
             LIMIT 1",
        )
        .bind(trimmed)
        .bind(trimmed)
        .fetch_optional(&mut *tx)
        .await?;
        found.ok_or_else(|| CatalogError::NotFound(format!("Collection '{trimmed}'")))
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use chrono::TimeZone;

    use crate::db::{
        BatchComparisonInput, BatchEntryInput, BatchLogInput, CatalogBatchInput, ConsumptionStatus,
        Db, EntryIdentifier, EntryKind, Generator1001UpdateInput, UpsertEntryInput,
    };

    #[tokio::test]
    async fn test_apply_batch_dry_run_and_commit_enriches_pool_and_ranks() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let batch = CatalogBatchInput {
            entries: vec![
                BatchEntryInput {
                    ref_key: Some("a1".to_owned()),
                    entry: UpsertEntryInput {
                        kind: Some(EntryKind::Album),
                        title: "In The Wee Small Hours".to_owned(),
                        creators: vec!["Frank Sinatra".to_owned()],
                        release_year: Some(1955),
                        metadata: Some(serde_json::json!({ "global_rating": 3.85 })),
                        identifiers: vec![
                            EntryIdentifier {
                                scheme: "spotify".to_owned(),
                                value: "3GmwKB1tgPZgXeRJZSm9WX".to_owned(),
                            },
                            EntryIdentifier {
                                scheme: "youtube_music".to_owned(),
                                value: "OLAK5uy_wee_small".to_owned(),
                            },
                        ],
                        ..Default::default()
                    },
                },
                BatchEntryInput {
                    ref_key: Some("a2".to_owned()),
                    entry: UpsertEntryInput {
                        kind: Some(EntryKind::Album),
                        title: "Ellington at Newport".to_owned(),
                        creators: vec!["Duke Ellington".to_owned()],
                        release_year: Some(1956),
                        identifiers: vec![EntryIdentifier {
                            scheme: "spotify".to_owned(),
                            value: "0t41BkcZayaAsa0FdRelfz".to_owned(),
                        }],
                        ..Default::default()
                    },
                },
                BatchEntryInput {
                    ref_key: Some("cur".to_owned()),
                    entry: UpsertEntryInput {
                        kind: Some(EntryKind::Album),
                        title: "Elvis Presley".to_owned(),
                        creators: vec!["Elvis Presley".to_owned()],
                        release_year: Some(1956),
                        identifiers: vec![EntryIdentifier {
                            scheme: "spotify".to_owned(),
                            value: "7GXP5OhYyPVLmcVfO9Iqin".to_owned(),
                        }],
                        ..Default::default()
                    },
                },
            ],
            logs: vec![
                BatchLogInput {
                    entry_ref: "a1".to_owned(),
                    status: ConsumptionStatus::Consumed,
                    notes: Some("★★★★★ — Timeless".to_owned()),
                    ts: Some(chrono::Utc.with_ymd_and_hms(2025, 4, 1, 8, 0, 0).unwrap()),
                },
                BatchLogInput {
                    entry_ref: "a2".to_owned(),
                    status: ConsumptionStatus::Consumed,
                    notes: Some("★★★☆☆".to_owned()),
                    ts: Some(chrono::Utc.with_ymd_and_hms(2025, 4, 2, 8, 0, 0).unwrap()),
                },
            ],
            comparisons: vec![BatchComparisonInput {
                scope_collection_id: None,
                ordering: vec![vec!["a1".to_owned()], vec!["a2".to_owned()]],
                ts: Some(chrono::Utc.with_ymd_and_hms(2025, 4, 2, 8, 0, 1).unwrap()),
            }],
            generator_1001: Some(Generator1001UpdateInput {
                enabled: Some(true),
                current_entry_ref: Some("cur".to_owned()),
                drawn_at: Some(chrono::Utc.with_ymd_and_hms(2025, 4, 3, 8, 0, 0).unwrap()),
            }),
            dry_run: true,
        };

        let dry = db.catalog().apply_batch(&batch).await.unwrap();
        assert!(dry.dry_run);
        assert_eq!(dry.entries_processed, 3);
        assert_eq!(dry.logs_recorded, 2);
        assert_eq!(dry.comparisons_recorded, 1);
        assert_eq!(db.catalog().list_diary(None, None, 10).await.unwrap().len(), 0);

        let mut live = batch;
        live.dry_run = false;
        let res = db.catalog().apply_batch(&live).await.unwrap();
        assert!(!res.dry_run);
        assert_eq!(res.comparisons_recorded, 1);

        let gen_state = db.catalog().get_1001_generator_state().await.unwrap();
        assert!(gen_state.enabled);
        assert_eq!(gen_state.consumed_count, 2);
        assert_eq!(gen_state.current_entry.as_ref().unwrap().title, "Elvis Presley");

        let ranked = db.catalog().get_ranked_collection("1001_albums", false).await.unwrap();
        assert_eq!(ranked.rows.len(), 2);
        assert_eq!(ranked.rows[0].entry.title, "In The Wee Small Hours");
        assert_eq!(ranked.rows[0].entry.identifier("youtube_music"), Some("OLAK5uy_wee_small"));
        assert_eq!(ranked.rows[0].entry.metadata["global_rating"], serde_json::json!(3.85));
        assert_eq!(ranked.rows[0].entry.metadata["genres"], serde_json::json!(["jazz"]));
    }
}
