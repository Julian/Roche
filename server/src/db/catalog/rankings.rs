use std::collections::{HashMap, HashSet};

use chrono::{DateTime, Utc};
use roche_core::domain::parse_dynamic_collection_rule;
use sqlx::{QueryBuilder, Sqlite, SqliteConnection};

use super::{
    CatalogRepo,
    collections::{
        append_dynamic_rule_sql_filters, append_dynamic_rule_sql_filters_opts,
        creators_json_matches_needle,
    },
    models::{
        CatalogEntry, CatalogError, CatalogResult, CollectionPageMeta, CollectionRankableIds,
        CollectionRankingInputs, ComparisonRecord, DynamicCollectionRule, RawCollectionRow,
        RawEntryRow, raw_entry_columns,
    },
};
use crate::{
    db::{
        WriteTx,
        sync::{SyncRepo, canonical_ordering_json, deterministic_comparison_id},
        types::{ActivityAction, ActivityEntityType, CollectionRole, EntryKind},
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
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;

        let mut resolved_ordering: Vec<Vec<String>> = Vec::with_capacity(ordering.len());
        for group in ordering {
            let mut resolved_group = Vec::with_capacity(group.len());
            for raw_id in group {
                let canon_id = Self::follow_merged_id_in_tx(tx.conn(), raw_id)
                    .await?
                    .ok_or_else(|| CatalogError::NotFound(format!("Entry '{raw_id}'")))?;
                resolved_group.push(canon_id);
            }
            resolved_ordering.push(resolved_group);
        }
        validate_ordering(&resolved_ordering).map_err(CatalogError::InvalidInput)?;

        let clean_scope = match scope_collection_id.map(str::trim).filter(|s| !s.is_empty()) {
            Some(sc) => {
                let col = Self::lookup_collection_row_in_tx(tx.conn(), sc, false).await?;
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

    /// Deletes a comparison by ID and records a sync tombstone so Rank Undo propagates cleanly.
    pub async fn delete_comparison(&self, comparison_id: &str) -> CatalogResult<bool> {
        let trimmed = comparison_id.trim();
        if trimmed.is_empty() {
            return Ok(false);
        }
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let res = sqlx::query("DELETE FROM comparisons WHERE id = ?")
            .bind(trimmed)
            .execute(tx.conn())
            .await?;
        let deleted = res.rows_affected() > 0;
        if deleted {
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::Comparison,
                trimmed,
                ActivityAction::Delete,
                now,
            )
            .await?;
            SyncRepo::record_tombstone(&mut tx, ActivityEntityType::Comparison, trimmed, now)
                .await?;
        }
        tx.commit().await?;
        Ok(deleted)
    }

    pub(super) async fn append_comparison_in_tx(
        tx: &mut WriteTx<'_>,
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
        let comp_hlc = tx.next_hlc().await?;
        let res = sqlx::query(
            "INSERT OR IGNORE INTO comparisons (id, ts, scope_collection_id, ordering_json, created_at, hlc)
             VALUES (?, ?, ?, ?, ?, ?)",
        )
        .bind(&comp_id)
        .bind(event_ts)
        .bind(clean_scope_ref)
        .bind(&ord_json)
        .bind(now)
        .bind(&comp_hlc)
        .execute(tx.conn())
        .await?;
        let inserted = res.rows_affected() > 0;
        if inserted {
            SyncRepo::record_activity(
                tx.conn(),
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
        let mut tx = crate::db::begin_read(self.pool).await?;
        let raw_col = if want {
            Self::lookup_want_collection_row_in_tx(tx.conn(), slug_or_id).await?.ok_or_else(
                || CatalogError::NotFound(format!("Want collection '{slug_or_id}' not found")),
            )?
        } else {
            Self::lookup_collection_row_in_tx(tx.conn(), slug_or_id, true).await?
        };
        let is_priority_scope = raw_col.collection_role == CollectionRole::Want;
        let members = Self::resolve_rankable_members_in_tx(tx.conn(), &raw_col).await?;
        tx.commit().await?;
        let summary = raw_col.into_summary(members.len(), 0);
        Ok(CollectionRankingInputs { collection: summary, is_priority_scope, members })
    }

    /// Loads a collection and ONLY its rankable member IDs without hydrating full entries,
    /// avoiding thousands of `entry_identifiers` queries and JSON parses during ranking sessions.
    ///
    /// Strictly read-only: when `want == true` and the queue does not yet exist, returns
    /// [`CatalogError::NotFound`] rather than creating rows on `GET`.
    pub async fn load_collection_rankable_ids(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> CatalogResult<CollectionRankableIds> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let raw_col =
            Self::resolve_target_collection_row_in_tx(tx.conn(), slug_or_id, want).await?;
        let is_priority_scope = raw_col.collection_role == CollectionRole::Want;
        let member_ids = Self::resolve_rankable_member_ids_in_tx(tx.conn(), &raw_col).await?;
        tx.commit().await?;
        let summary = raw_col.into_summary(member_ids.len(), 0);
        Ok(CollectionRankableIds { collection: summary, is_priority_scope, member_ids })
    }

    /// Hydrates ONLY the requested entry IDs in a single read snapshot, deduplicating input IDs
    /// so cross-chunk duplicates cannot drop hydrated identifiers.
    pub async fn load_entries_by_ids(
        &self,
        ids: &[String],
    ) -> CatalogResult<HashMap<String, CatalogEntry>> {
        if ids.is_empty() {
            return Ok(HashMap::new());
        }
        let mut seen: HashSet<&str> = HashSet::with_capacity(ids.len());
        let unique_ids: Vec<String> =
            ids.iter().filter(|id| seen.insert(id.as_str())).cloned().collect();
        let mut tx = crate::db::begin_read(self.pool).await?;
        let loaded = Self::load_entries_by_ids_in_tx(tx.conn(), &unique_ids).await?;
        tx.commit().await?;
        Ok(loaded)
    }

    /// Loads ONLY `(id, title)` pairs for the requested entry IDs without querying
    /// `entry_identifiers` or parsing JSON columns.
    pub async fn load_entry_titles_by_ids(
        &self,
        ids: &[String],
    ) -> CatalogResult<HashMap<String, String>> {
        if ids.is_empty() {
            return Ok(HashMap::new());
        }
        let mut seen: HashSet<&str> = HashSet::with_capacity(ids.len());
        let unique_ids: Vec<&str> =
            ids.iter().map(String::as_str).filter(|id| seen.insert(*id)).collect();
        let mut tx = crate::db::begin_read(self.pool).await?;
        let mut out = HashMap::with_capacity(unique_ids.len());
        for chunk in unique_ids.chunks(400) {
            let mut qb = QueryBuilder::<Sqlite>::new("SELECT id, title FROM entries WHERE id IN (");
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(*id);
            }
            sep.push_unseparated(")");
            let rows: Vec<(String, String)> = qb.build_query_as().fetch_all(tx.conn()).await?;
            for (id, title) in rows {
                out.insert(id, title);
            }
        }
        tx.commit().await?;
        Ok(out)
    }

    /// Lightweight count of rankable members in `slug_or_id` without hydrating entries,
    /// loading comparisons, or running EP inference. Strictly read-only.
    pub async fn count_rankable_members(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> CatalogResult<usize> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let raw_col = if want {
            let Some(col) = Self::lookup_want_collection_row_in_tx(tx.conn(), slug_or_id).await?
            else {
                return Ok(0);
            };
            col
        } else {
            Self::lookup_collection_row_in_tx(tx.conn(), slug_or_id, true).await?
        };
        let count = if raw_col.is_dynamic() {
            Self::count_dynamic_members_in_tx(tx.conn(), &raw_col).await?
        } else {
            let raw_count: i64 = if raw_col.has_generator {
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
                .fetch_one(tx.conn())
                .await?
            } else {
                sqlx::query_scalar(
                    "SELECT COUNT(*)
                     FROM collection_entries ce
                     JOIN entries e ON e.id = ce.entry_id
                     WHERE ce.collection_id = ? AND e.merged_into_id IS NULL",
                )
                .bind(&raw_col.id)
                .fetch_one(tx.conn())
                .await?
            };
            usize::try_from(raw_count).unwrap_or(0)
        };
        tx.commit().await?;
        Ok(count)
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

    /// Lightweight revision tuple `(count, max_sync_seq, sum_sync_seq)` for comparisons in
    /// `scope_collection_id` (`None` = global scope) used to invalidate cached EP scores cleanly
    /// across inserts, deletes, and entry-merge rewrites.
    pub async fn comparison_scope_revision(
        &self,
        scope_collection_id: Option<&str>,
    ) -> CatalogResult<(i64, i64, i64)> {
        let rev: (i64, i64, i64) = if let Some(scope_id) = scope_collection_id {
            sqlx::query_as(
                "SELECT COUNT(*), COALESCE(MAX(sync_seq), 0), COALESCE(SUM(sync_seq), 0)
                 FROM comparisons
                 WHERE scope_collection_id = ?",
            )
            .bind(scope_id)
            .fetch_one(self.pool)
            .await?
        } else {
            sqlx::query_as(
                "SELECT COUNT(*), COALESCE(MAX(sync_seq), 0), COALESCE(SUM(sync_seq), 0)
                 FROM comparisons
                 WHERE scope_collection_id IS NULL",
            )
            .fetch_one(self.pool)
            .await?
        };
        Ok(rev)
    }

    /// Lightweight lookup resolving whether `slug_or_id` ranks in priority (want) scope or global
    /// scope without hydrating collection members or running EP inference.
    pub async fn resolve_ranking_scope(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> CatalogResult<(bool, Option<String>)> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let raw_col = if want {
            Self::lookup_want_collection_row_in_tx(tx.conn(), slug_or_id).await?.ok_or_else(
                || CatalogError::NotFound(format!("Want collection '{slug_or_id}' not found")),
            )?
        } else {
            Self::lookup_collection_row_in_tx(tx.conn(), slug_or_id, true).await?
        };
        tx.commit().await?;
        if raw_col.collection_role == CollectionRole::Want {
            Ok((true, Some(raw_col.id)))
        } else {
            Ok((false, None))
        }
    }

    /// Loads `(revision, orderings)` for `scope_collection_id` inside a single read snapshot so
    /// cached EP scores can never pair newer orderings with an older revision (or vice versa).
    pub async fn load_revision_and_orderings_for_scope(
        &self,
        scope_collection_id: Option<&str>,
    ) -> CatalogResult<((i64, i64, i64), Vec<Vec<Vec<String>>>)> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let rev: (i64, i64, i64) = if let Some(scope_id) = scope_collection_id {
            sqlx::query_as(
                "SELECT COUNT(*), COALESCE(MAX(sync_seq), 0), COALESCE(SUM(sync_seq), 0)
                 FROM comparisons
                 WHERE scope_collection_id = ?",
            )
            .bind(scope_id)
            .fetch_one(tx.conn())
            .await?
        } else {
            sqlx::query_as(
                "SELECT COUNT(*), COALESCE(MAX(sync_seq), 0), COALESCE(SUM(sync_seq), 0)
                 FROM comparisons
                 WHERE scope_collection_id IS NULL",
            )
            .fetch_one(tx.conn())
            .await?
        };
        let orderings =
            Self::load_orderings_for_scope_in_tx(tx.conn(), scope_collection_id).await?;
        tx.commit().await?;
        Ok((rev, orderings))
    }

    pub(super) async fn load_orderings_for_scope_in_tx(
        conn: &mut SqliteConnection,
        scope_collection_id: Option<&str>,
    ) -> CatalogResult<Vec<Vec<Vec<String>>>> {
        let json_rows = if let Some(scope_id) = scope_collection_id {
            sqlx::query_scalar!(
                r#"SELECT ordering_json as "ordering_json!" FROM comparisons
                   WHERE scope_collection_id = ?
                   ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', ts) ASC, id ASC"#,
                scope_id,
            )
            .fetch_all(&mut *conn)
            .await?
        } else {
            sqlx::query_scalar!(
                r#"SELECT ordering_json as "ordering_json!" FROM comparisons
                   WHERE scope_collection_id IS NULL
                   ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', ts) ASC, id ASC"#,
            )
            .fetch_all(&mut *conn)
            .await?
        };
        Ok(json_rows
            .into_iter()
            .filter_map(|j| serde_json::from_str::<Vec<Vec<String>>>(&j).ok())
            .collect())
    }

    /// Loads comparison orderings for a priority collection scope (`Some(id)`) or global scope (`None`).
    #[cfg(test)]
    pub async fn load_orderings_for_scope(
        &self,
        scope_collection_id: Option<&str>,
    ) -> CatalogResult<Vec<Vec<Vec<String>>>> {
        let mut conn = self.pool.acquire().await?;
        Self::load_orderings_for_scope_in_tx(&mut conn, scope_collection_id).await
    }

    /// Loads collection metadata, exact total & per-kind counts, hydrated entries ONLY for
    /// `compared_ids` belonging to the collection, and membership status for `check_member_ids`
    /// (avoiding full-collection hydration).
    pub async fn load_collection_page_meta(
        &self,
        slug_or_id: &str,
        want: bool,
        compared_ids: &[String],
        check_member_ids: &[String],
    ) -> CatalogResult<CollectionPageMeta> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let raw_col =
            Self::resolve_target_collection_row_in_tx(tx.conn(), slug_or_id, want).await?;
        let is_priority_scope = raw_col.collection_role == CollectionRole::Want;
        let kind_counts = Self::load_collection_kind_counts_in_tx(tx.conn(), &raw_col).await?;
        let total_count: usize = kind_counts.iter().map(|(_, c)| *c).sum();

        let compared_members =
            Self::load_collection_members_in_id_set_in_tx(tx.conn(), &raw_col, compared_ids)
                .await?;
        let mut present_member_ids: HashSet<String> = HashSet::new();
        if !check_member_ids.is_empty() {
            let compared_id_set: HashSet<&str> =
                compared_members.iter().map(|e| e.id.as_str()).collect();
            let mut unresolved_checks: Vec<String> = Vec::new();
            let mut seen_checks: HashSet<&str> = HashSet::new();
            for id in check_member_ids {
                if !seen_checks.insert(id.as_str()) {
                    continue;
                }
                if compared_id_set.contains(id.as_str()) {
                    present_member_ids.insert(id.clone());
                } else {
                    unresolved_checks.push(id.clone());
                }
            }
            if !unresolved_checks.is_empty() {
                let checked = Self::check_collection_member_ids_in_tx(
                    tx.conn(),
                    &raw_col,
                    &unresolved_checks,
                )
                .await?;
                present_member_ids.extend(checked);
            }
        }

        tx.commit().await?;
        let summary = raw_col.into_summary(total_count, 0);
        Ok(CollectionPageMeta {
            collection: summary,
            is_priority_scope,
            total_count,
            kind_counts,
            compared_members,
            present_member_ids,
        })
    }

    /// Counts zero-band entries in `slug_or_id` matching `(kind_filter, query)` while directly
    /// excluding `exclude_ids` in SQL (no Rust subtraction drift and no redundant `COUNT(*)`).
    pub async fn count_collection_zero_band_filtered(
        &self,
        slug_or_id: &str,
        want: bool,
        kind_filter: Option<EntryKind>,
        query: Option<&str>,
        exclude_ids: &HashSet<String>,
    ) -> CatalogResult<usize> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let raw_col =
            Self::resolve_target_collection_row_in_tx(tx.conn(), slug_or_id, want).await?;
        let dyn_rule = if raw_col.is_dynamic() {
            let Some(rule) = parse_dynamic_collection_rule(raw_col.rule_json.as_deref()) else {
                tx.commit().await?;
                return Ok(0);
            };
            Some(rule)
        } else {
            None
        };
        if let Some(c_needle) = dyn_rule
            .as_ref()
            .and_then(|r| r.creator.as_deref())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_lowercase)
        {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT e.id, e.creators_json FROM entries e WHERE e.merged_into_id IS NULL",
            );
            if let Some(ref rule) = dyn_rule {
                append_dynamic_rule_sql_filters(&mut qb, rule);
            }
            append_optional_kind_and_query_filters(&mut qb, kind_filter, query);
            let rows: Vec<(String, String)> = qb.build_query_as().fetch_all(tx.conn()).await?;
            tx.commit().await?;
            return Ok(rows
                .into_iter()
                .filter(|(id, cj)| {
                    !exclude_ids.contains(id) && creators_json_matches_needle(cj, &c_needle)
                })
                .count());
        }

        let mut qb =
            build_collection_select_qb(&raw_col, dyn_rule.as_ref(), SelectMode::Count, false);
        append_optional_kind_and_query_filters(&mut qb, kind_filter, query);
        append_exclude_ids_filter(&mut qb, exclude_ids);
        let cnt: i64 = qb.build_query_scalar().fetch_one(tx.conn()).await?;
        tx.commit().await?;
        Ok(usize::try_from(cnt).unwrap_or(0))
    }

    /// Loads a paginated SQL slice of zero-band entries for `slug_or_id` (excluding
    /// `exclude_ids`, ordered alphabetically by `(LOWER(title), id)`), without running any
    /// redundant `COUNT(*)`.
    #[allow(clippy::too_many_arguments)]
    pub async fn load_collection_zero_band_slice(
        &self,
        slug_or_id: &str,
        want: bool,
        kind_filter: Option<EntryKind>,
        query: Option<&str>,
        exclude_ids: &HashSet<String>,
        limit: usize,
        offset: usize,
    ) -> CatalogResult<Vec<CatalogEntry>> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let mut tx = crate::db::begin_read(self.pool).await?;
        let raw_col =
            Self::resolve_target_collection_row_in_tx(tx.conn(), slug_or_id, want).await?;
        let dyn_rule = if raw_col.is_dynamic() {
            let Some(rule) = parse_dynamic_collection_rule(raw_col.rule_json.as_deref()) else {
                tx.commit().await?;
                return Ok(Vec::new());
            };
            Some(rule)
        } else {
            None
        };
        if let Some(c_needle) = dyn_rule
            .as_ref()
            .and_then(|r| r.creator.as_deref())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_lowercase)
        {
            let mut qb = QueryBuilder::<Sqlite>::new(concat!(
                "SELECT ",
                raw_entry_columns!(e),
                " FROM entries e WHERE e.merged_into_id IS NULL"
            ));
            if let Some(ref rule) = dyn_rule {
                append_dynamic_rule_sql_filters(&mut qb, rule);
            }
            append_optional_kind_and_query_filters(&mut qb, kind_filter, query);
            qb.push(" ORDER BY LOWER(e.title) ASC, e.id ASC");
            let all_rows: Vec<RawEntryRow> = qb.build_query_as().fetch_all(tx.conn()).await?;
            let page_raw: Vec<RawEntryRow> = all_rows
                .into_iter()
                .filter(|r| {
                    !exclude_ids.contains(&r.id)
                        && creators_json_matches_needle(&r.creators_json, &c_needle)
                })
                .skip(offset)
                .take(limit)
                .collect();
            let hydrated = Self::hydrate_entries_in_tx(tx.conn(), page_raw).await?;
            tx.commit().await?;
            return Ok(hydrated);
        }

        let mut qb =
            build_collection_select_qb(&raw_col, dyn_rule.as_ref(), SelectMode::Entries, false);
        append_optional_kind_and_query_filters(&mut qb, kind_filter, query);
        append_exclude_ids_filter(&mut qb, exclude_ids);
        qb.push(" ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT ")
            .push_bind(i64::try_from(limit).unwrap_or(200))
            .push(" OFFSET ")
            .push_bind(i64::try_from(offset).unwrap_or(0));
        let page_raw: Vec<RawEntryRow> = qb.build_query_as().fetch_all(tx.conn()).await?;
        let hydrated = Self::hydrate_entries_in_tx(tx.conn(), page_raw).await?;
        tx.commit().await?;
        Ok(hydrated)
    }

    /// Counts members in `slug_or_id` sorting strictly before `(target_ascii_lower_title, target_id)`
    /// by `(LOWER(e.title), e.id)` so entry rank appearances stay O(log N) without loading all rows.
    pub async fn count_collection_entries_alpha_before(
        &self,
        slug_or_id: &str,
        want: bool,
        target_ascii_lower_title: &str,
        target_id: &str,
    ) -> CatalogResult<usize> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let raw_col =
            Self::resolve_target_collection_row_in_tx(tx.conn(), slug_or_id, want).await?;
        let dyn_rule = if raw_col.is_dynamic() {
            let Some(rule) = parse_dynamic_collection_rule(raw_col.rule_json.as_deref()) else {
                tx.commit().await?;
                return Ok(0);
            };
            Some(rule)
        } else {
            None
        };
        if let Some(c_needle) = dyn_rule
            .as_ref()
            .and_then(|r| r.creator.as_deref())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_lowercase)
        {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT e.creators_json FROM entries e WHERE e.merged_into_id IS NULL",
            );
            if let Some(ref rule) = dyn_rule {
                append_dynamic_rule_sql_filters(&mut qb, rule);
            }
            qb.push(" AND (LOWER(e.title) < ")
                .push_bind(target_ascii_lower_title)
                .push(" OR (LOWER(e.title) = ")
                .push_bind(target_ascii_lower_title)
                .push(" AND e.id < ")
                .push_bind(target_id)
                .push("))");
            let rows: Vec<String> = qb.build_query_scalar().fetch_all(tx.conn()).await?;
            tx.commit().await?;
            return Ok(rows
                .into_iter()
                .filter(|c| creators_json_matches_needle(c, &c_needle))
                .count());
        }

        let mut qb =
            build_collection_select_qb(&raw_col, dyn_rule.as_ref(), SelectMode::Count, false);
        qb.push(" AND (LOWER(e.title) < ")
            .push_bind(target_ascii_lower_title)
            .push(" OR (LOWER(e.title) = ")
            .push_bind(target_ascii_lower_title)
            .push(" AND e.id < ")
            .push_bind(target_id)
            .push("))");
        let count: i64 = qb.build_query_scalar().fetch_one(tx.conn()).await?;
        tx.commit().await?;
        Ok(usize::try_from(count).unwrap_or(0))
    }

    async fn resolve_target_collection_row_in_tx(
        conn: &mut SqliteConnection,
        slug_or_id: &str,
        want: bool,
    ) -> CatalogResult<RawCollectionRow> {
        if want {
            Self::lookup_want_collection_row_in_tx(&mut *conn, slug_or_id).await?.ok_or_else(|| {
                CatalogError::NotFound(format!("Want collection '{slug_or_id}' not found"))
            })
        } else {
            Self::lookup_collection_row_in_tx(&mut *conn, slug_or_id, true).await
        }
    }

    async fn load_collection_kind_counts_in_tx(
        conn: &mut SqliteConnection,
        raw_col: &RawCollectionRow,
    ) -> CatalogResult<Vec<(EntryKind, usize)>> {
        let dyn_rule = if raw_col.is_dynamic() {
            let Some(rule) = parse_dynamic_collection_rule(raw_col.rule_json.as_deref()) else {
                return Ok(Vec::new());
            };
            Some(rule)
        } else {
            None
        };
        if let Some(c_needle) = dyn_rule
            .as_ref()
            .and_then(|r| r.creator.as_deref())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_lowercase)
        {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT e.kind, e.creators_json FROM entries e WHERE e.merged_into_id IS NULL",
            );
            if let Some(ref rule) = dyn_rule {
                append_dynamic_rule_sql_filters(&mut qb, rule);
            }
            let rows: Vec<(EntryKind, String)> = qb.build_query_as().fetch_all(&mut *conn).await?;
            let mut map: HashMap<EntryKind, usize> = HashMap::new();
            for (kind, creators_json) in rows {
                if creators_json_matches_needle(&creators_json, &c_needle) {
                    *map.entry(kind).or_insert(0) += 1;
                }
            }
            let mut out: Vec<(EntryKind, usize)> = map.into_iter().collect();
            out.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.as_str().cmp(b.0.as_str())));
            return Ok(out);
        }

        let mut qb = if raw_col.is_dynamic() {
            let mut b = QueryBuilder::<Sqlite>::new(
                "SELECT e.kind, COUNT(*) AS cnt FROM entries e WHERE e.merged_into_id IS NULL",
            );
            if let Some(ref rule) = dyn_rule {
                append_dynamic_rule_sql_filters(&mut b, rule);
            }
            b
        } else {
            let mut b = QueryBuilder::<Sqlite>::new(
                "SELECT e.kind, COUNT(*) AS cnt
                 FROM collection_entries ce
                 CROSS JOIN entries e ON e.id = ce.entry_id
                 WHERE ce.collection_id = ",
            );
            b.push_bind(&raw_col.id).push(" AND e.merged_into_id IS NULL");
            if raw_col.has_generator {
                b.push(
                    " AND EXISTS (
                        SELECT 1 FROM consumption_log cl
                        WHERE cl.entry_id = e.id AND cl.status = 'consumed'
                      )",
                );
            }
            b
        };
        qb.push(" GROUP BY e.kind ORDER BY cnt DESC, e.kind ASC");
        let rows: Vec<(EntryKind, i64)> = qb.build_query_as().fetch_all(&mut *conn).await?;
        Ok(rows
            .into_iter()
            .filter_map(|(k, c)| usize::try_from(c).ok().filter(|&n| n > 0).map(|n| (k, n)))
            .collect())
    }

    async fn load_collection_members_in_id_set_in_tx(
        conn: &mut SqliteConnection,
        raw_col: &RawCollectionRow,
        candidate_ids: &[String],
    ) -> CatalogResult<Vec<CatalogEntry>> {
        if candidate_ids.is_empty() {
            return Ok(Vec::new());
        }
        let dyn_rule = if raw_col.is_dynamic() {
            let Some(rule) = parse_dynamic_collection_rule(raw_col.rule_json.as_deref()) else {
                return Ok(Vec::new());
            };
            Some(rule)
        } else {
            None
        };
        let creator_needle = dyn_rule
            .as_ref()
            .and_then(|r| r.creator.as_deref())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_lowercase);

        let mut matched: Vec<RawEntryRow> = Vec::new();
        let id_col = if raw_col.is_dynamic() { "e.id" } else { "ce.entry_id" };
        for chunk in candidate_ids.chunks(450) {
            let mut qb =
                build_collection_select_qb(raw_col, dyn_rule.as_ref(), SelectMode::Entries, true);
            qb.push(" AND ").push(id_col).push(" IN (");
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id);
            }
            sep.push_unseparated(")");
            let mut rows: Vec<RawEntryRow> = qb.build_query_as().fetch_all(&mut *conn).await?;
            if let Some(ref needle) = creator_needle {
                rows.retain(|r| creators_json_matches_needle(&r.creators_json, needle));
            }
            matched.extend(rows);
        }
        Self::hydrate_entries_in_tx(&mut *conn, matched).await
    }

    async fn check_collection_member_ids_in_tx(
        conn: &mut SqliteConnection,
        raw_col: &RawCollectionRow,
        candidate_ids: &[String],
    ) -> CatalogResult<HashSet<String>> {
        if candidate_ids.is_empty() {
            return Ok(HashSet::new());
        }
        let dyn_rule = if raw_col.is_dynamic() {
            let Some(rule) = parse_dynamic_collection_rule(raw_col.rule_json.as_deref()) else {
                return Ok(HashSet::new());
            };
            Some(rule)
        } else {
            None
        };
        let creator_needle = dyn_rule
            .as_ref()
            .and_then(|r| r.creator.as_deref())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_lowercase);

        let mut present: HashSet<String> = HashSet::new();
        let id_col = if raw_col.is_dynamic() { "e.id" } else { "ce.entry_id" };
        let mode =
            if creator_needle.is_some() { SelectMode::IdAndCreators } else { SelectMode::IdOnly };
        for chunk in candidate_ids.chunks(450) {
            let mut qb = build_collection_select_qb(raw_col, dyn_rule.as_ref(), mode, true);
            qb.push(" AND ").push(id_col).push(" IN (");
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id);
            }
            sep.push_unseparated(")");
            if let Some(ref needle) = creator_needle {
                let rows: Vec<(String, String)> = qb.build_query_as().fetch_all(&mut *conn).await?;
                for (id, creators_json) in rows {
                    if creators_json_matches_needle(&creators_json, needle) {
                        present.insert(id);
                    }
                }
            } else {
                let ids: Vec<String> = qb.build_query_scalar().fetch_all(&mut *conn).await?;
                present.extend(ids);
            }
        }
        Ok(present)
    }
}

#[derive(Clone, Copy)]
enum SelectMode {
    Count,
    IdOnly,
    IdAndCreators,
    Entries,
}

fn build_collection_select_qb(
    raw_col: &RawCollectionRow,
    dyn_rule: Option<&DynamicCollectionRule>,
    mode: SelectMode,
    suppress_kind_index: bool,
) -> QueryBuilder<Sqlite> {
    let prefix = match mode {
        SelectMode::Count => "SELECT COUNT(*)",
        SelectMode::IdOnly => "SELECT e.id",
        SelectMode::IdAndCreators => "SELECT e.id, e.creators_json",
        SelectMode::Entries => concat!("SELECT ", raw_entry_columns!(e)),
    };
    let mut qb = QueryBuilder::<Sqlite>::new(prefix);
    if raw_col.is_dynamic() {
        qb.push(" FROM entries e WHERE e.merged_into_id IS NULL");
        if let Some(rule) = dyn_rule {
            append_dynamic_rule_sql_filters_opts(&mut qb, rule, suppress_kind_index);
        }
    } else {
        // Drive manual collections from `collection_entries` so filtering a small list by
        // `kind = 'song'` never flips SQLite into scanning 30k songs.
        qb.push(
            " FROM collection_entries ce
              CROSS JOIN entries e ON e.id = ce.entry_id
              WHERE ce.collection_id = ",
        )
        .push_bind(raw_col.id.clone())
        .push(" AND e.merged_into_id IS NULL");
        if raw_col.has_generator {
            qb.push(
                " AND EXISTS (
                    SELECT 1 FROM consumption_log cl
                    WHERE cl.entry_id = e.id AND cl.status = 'consumed'
                  )",
            );
        }
    }
    qb
}

fn append_exclude_ids_filter(qb: &mut QueryBuilder<Sqlite>, exclude_ids: &HashSet<String>) {
    if exclude_ids.is_empty() {
        return;
    }
    if exclude_ids.len() <= 450 {
        qb.push(" AND e.id NOT IN (");
        let mut sep = qb.separated(", ");
        for ex in exclude_ids {
            sep.push_bind(ex.clone());
        }
        sep.push_unseparated(")");
    } else {
        let ids: Vec<&str> = exclude_ids.iter().map(String::as_str).collect();
        let json_array = serde_json::to_string(&ids).unwrap_or_else(|_| "[]".to_owned());
        qb.push(" AND e.id NOT IN (SELECT value FROM json_each(").push_bind(json_array).push("))");
    }
}

fn append_optional_kind_and_query_filters(
    qb: &mut QueryBuilder<Sqlite>,
    kind_filter: Option<EntryKind>,
    query: Option<&str>,
) {
    if let Some(k) = kind_filter {
        qb.push(" AND e.kind = ").push_bind(k);
    }
    if let Some(raw_q) = query.map(str::trim).filter(|s| !s.is_empty()) {
        let bounded: String = raw_q.chars().take(256).collect();
        let lower_pat = format!("%{}%", crate::db::escape_like_literal(&bounded.to_lowercase()));
        let orig_pat = format!("%{}%", crate::db::escape_like_literal(&bounded));
        if lower_pat == orig_pat {
            qb.push(" AND (LOWER(e.title) LIKE ")
                .push_bind(lower_pat.clone())
                .push(" ESCAPE '\\' OR EXISTS (SELECT 1 FROM json_each(e.creators_json) jc WHERE LOWER(jc.value) LIKE ")
                .push_bind(lower_pat.clone())
                .push(" ESCAPE '\\') OR CAST(COALESCE(e.release_year, '') AS TEXT) LIKE ")
                .push_bind(lower_pat)
                .push(" ESCAPE '\\')");
        } else {
            qb.push(" AND (LOWER(e.title) LIKE ")
                .push_bind(lower_pat.clone())
                .push(" ESCAPE '\\' OR e.title LIKE ")
                .push_bind(orig_pat.clone())
                .push(" ESCAPE '\\' OR EXISTS (SELECT 1 FROM json_each(e.creators_json) jc WHERE LOWER(jc.value) LIKE ")
                .push_bind(lower_pat.clone())
                .push(" ESCAPE '\\' OR jc.value LIKE ")
                .push_bind(orig_pat)
                .push(" ESCAPE '\\') OR CAST(COALESCE(e.release_year, '') AS TEXT) LIKE ")
                .push_bind(lower_pat)
                .push(" ESCAPE '\\')");
        }
    }
}
