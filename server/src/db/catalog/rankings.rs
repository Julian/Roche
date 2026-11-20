use std::collections::HashSet;

use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use super::{
    CatalogRepo,
    models::{CatalogError, CatalogResult, CollectionRankingInputs, ComparisonRecord},
};
use crate::{
    db::{
        sync::{SyncRepo, canonical_ordering_json, deterministic_comparison_id},
        types::{ActivityAction, ActivityEntityType, CollectionMode, CollectionRole},
    },
    ranking::validate_ordering,
    util::clock::now_utc,
};

impl CatalogRepo<'_> {
    pub async fn append_comparison(
        &self,
        scope_collection_id: Option<&str>,
        ordering: &[Vec<String>],
        ts: Option<DateTime<Utc>>,
    ) -> CatalogResult<ComparisonRecord> {
        let now = now_utc();
        let event_ts = ts.unwrap_or(now);
        let mut tx = crate::db::begin_write(self.pool).await?;

        let mut resolved_ordering: Vec<Vec<String>> = Vec::with_capacity(ordering.len());
        for group in ordering {
            let mut resolved_group = Vec::with_capacity(group.len());
            for raw_id in group {
                let canon_id = Self::follow_merged_id_in_tx(&mut tx, raw_id)
                    .await?
                    .ok_or_else(|| CatalogError::NotFound(format!("Entry '{raw_id}'")))?;
                resolved_group.push(canon_id);
            }
            resolved_ordering.push(resolved_group);
        }
        validate_ordering(&resolved_ordering).map_err(CatalogError::InvalidInput)?;

        let clean_scope = match scope_collection_id.map(str::trim).filter(|s| !s.is_empty()) {
            Some(sc) => {
                let col = Self::lookup_collection_row_in_tx(&mut tx, sc, false).await?;
                Some(col.id)
            }
            None => None,
        };

        let (record, _) =
            Self::append_comparison_in_tx(&mut tx, clean_scope, resolved_ordering, event_ts, now)
                .await?;
        tx.commit().await?;
        Ok(record)
    }

    pub(super) async fn append_comparison_in_tx(
        tx: &mut SqliteConnection,
        clean_scope: Option<String>,
        resolved_ordering: Vec<Vec<String>>,
        event_ts: DateTime<Utc>,
        now: DateTime<Utc>,
    ) -> CatalogResult<(ComparisonRecord, bool)> {
        let comp_id = deterministic_comparison_id(
            clean_scope.as_deref(),
            event_ts.timestamp_millis(),
            &resolved_ordering,
        );
        let ord_json = canonical_ordering_json(&resolved_ordering);

        let clean_scope_ref = clean_scope.as_deref();
        let res = sqlx::query!(
            "INSERT OR IGNORE INTO comparisons (id, ts, scope_collection_id, ordering_json, created_at)
             VALUES (?, ?, ?, ?, ?)",
            &comp_id,
            event_ts as _,
            clean_scope_ref,
            &ord_json,
            now as _,
        )
        .execute(&mut *tx)
        .await?;
        let inserted = res.rows_affected() > 0;
        if inserted {
            SyncRepo::record_activity(
                tx,
                ActivityEntityType::Comparison,
                &comp_id,
                ActivityAction::Create,
                now,
            )
            .await?;
        }

        let canonical_groups: Vec<Vec<String>> =
            serde_json::from_str(&ord_json).unwrap_or(resolved_ordering);
        Ok((
            ComparisonRecord {
                id: comp_id,
                ts: event_ts,
                scope_collection_id: clean_scope,
                ordering: canonical_groups,
                created_at: now,
            },
            inserted,
        ))
    }

    /// Loads a collection and its rankable members without running EP inference.
    ///
    /// Strictly read-only: when `want == true` and the queue does not yet exist, returns
    /// [`CatalogError::NotFound`] rather than creating rows on `GET`.
    pub async fn load_collection_ranking_inputs(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> CatalogResult<CollectionRankingInputs> {
        let raw_col = if want {
            self.lookup_want_collection_row(slug_or_id).await?.ok_or_else(|| {
                CatalogError::NotFound(format!("Want collection '{slug_or_id}' not found"))
            })?
        } else {
            self.lookup_collection_row(slug_or_id, true).await?
        };
        let is_priority_scope = raw_col.collection_role == CollectionRole::Want;
        let members = self.resolve_rankable_members(&raw_col).await?;
        let summary = raw_col.into_summary(members.len(), 0);
        Ok(CollectionRankingInputs { collection: summary, is_priority_scope, members })
    }

    /// Lightweight count of rankable members in `slug_or_id` without hydrating entries,
    /// loading comparisons, or running EP inference. Strictly read-only.
    pub async fn count_rankable_members(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> CatalogResult<usize> {
        let raw_col = if want {
            let Some(col) = self.lookup_want_collection_row(slug_or_id).await? else {
                return Ok(0);
            };
            col
        } else {
            self.lookup_collection_row(slug_or_id, true).await?
        };
        match raw_col.mode {
            CollectionMode::Manual | CollectionMode::Feed => {
                let count: i64 = if raw_col.has_generator {
                    sqlx::query_scalar(
                        "SELECT COUNT(*)
                         FROM collection_entries ce
                         JOIN entries e ON e.id = ce.entry_id
                         WHERE ce.collection_id = ?
                           AND e.merged_into_id IS NULL
                           AND EXISTS (
                               SELECT 1 FROM consumption_log cl
                               WHERE cl.entry_id = e.id AND cl.status = 'consumed'
                           )",
                    )
                    .bind(&raw_col.id)
                    .fetch_one(self.pool)
                    .await?
                } else {
                    sqlx::query_scalar(
                        "SELECT COUNT(*)
                         FROM collection_entries ce
                         JOIN entries e ON e.id = ce.entry_id
                         WHERE ce.collection_id = ? AND e.merged_into_id IS NULL",
                    )
                    .bind(&raw_col.id)
                    .fetch_one(self.pool)
                    .await?
                };
                Ok(usize::try_from(count).unwrap_or(0))
            }
            CollectionMode::Dynamic => Ok(self.resolve_rankable_member_ids(&raw_col).await?.len()),
        }
    }

    /// Resolves `raw_parent_id` across merges and returns its member entry IDs.
    pub async fn load_child_entry_ids_for_parent(
        &self,
        raw_parent_id: &str,
    ) -> CatalogResult<HashSet<String>> {
        let resolved_parent =
            self.follow_merged_id(raw_parent_id).await?.unwrap_or_else(|| raw_parent_id.to_owned());
        let child_ids = sqlx::query_scalar!(
            r#"SELECT entry_id as "entry_id!" FROM collection_entries WHERE collection_id = ?"#,
            &resolved_parent,
        )
        .fetch_all(self.pool)
        .await?
        .into_iter()
        .collect();
        Ok(child_ids)
    }

    /// Resolves a list of entry IDs through any `merged_into_id` chains, dropping missing entries.
    pub async fn resolve_canonical_ids(&self, ids: &[String]) -> CatalogResult<Vec<String>> {
        let mut resolved = Vec::with_capacity(ids.len());
        for id in ids {
            if let Some(canon) = self.follow_merged_id(id).await? {
                resolved.push(canon);
            }
        }
        Ok(resolved)
    }

    /// Returns up to `limit` most recently consumed entry IDs ordered newest-first.
    pub async fn load_recent_consumed_entry_ids(&self, limit: usize) -> CatalogResult<Vec<String>> {
        let recent_cap = i64::try_from(limit).unwrap_or(50);
        let recent_ids = sqlx::query_scalar!(
            r#"SELECT entry_id as "entry_id!"
                   FROM consumption_log
                   WHERE status = 'consumed'
                   GROUP BY entry_id
                   ORDER BY MAX(strftime('%Y-%m-%dT%H:%M:%fZ', ts)) DESC
                   LIMIT ?"#,
            recent_cap,
        )
        .fetch_all(self.pool)
        .await?;
        Ok(recent_ids)
    }

    /// Loads comparison orderings for a priority collection scope (`Some(id)`) or global scope (`None`).
    pub async fn load_orderings_for_scope(
        &self,
        scope_collection_id: Option<&str>,
    ) -> CatalogResult<Vec<Vec<Vec<String>>>> {
        let json_rows = if let Some(scope_id) = scope_collection_id {
            sqlx::query_scalar!(
                r#"SELECT ordering_json as "ordering_json!" FROM comparisons
                   WHERE scope_collection_id = ?
                   ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', ts) ASC, id ASC"#,
                scope_id,
            )
            .fetch_all(self.pool)
            .await?
        } else {
            sqlx::query_scalar!(
                r#"SELECT ordering_json as "ordering_json!" FROM comparisons
                   WHERE scope_collection_id IS NULL
                   ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', ts) ASC, id ASC"#,
            )
            .fetch_all(self.pool)
            .await?
        };
        Ok(json_rows
            .into_iter()
            .filter_map(|j| serde_json::from_str::<Vec<Vec<String>>>(&j).ok())
            .collect())
    }
}
