use std::collections::{HashMap, HashSet};

use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use super::{
    CatalogRepo,
    models::{
        CatalogEntry, CatalogError, CatalogResult, ClusterSelectFilter, CollectionSummary,
        ComparisonRecord, RankedCollectionView, RankedEntryRow,
    },
};
use crate::{
    db::{
        sync::{SyncRepo, canonical_ordering_json, deterministic_comparison_id},
        types::{ActivityAction, ActivityEntityType, CollectionMode, CollectionRole},
    },
    ranking::{
        MAX_K_DEFAULT, N_QUANTILES, RANDOM_SEED_EVERY, Score, infer_scores, rescale_to_quantiles,
        select_cluster, sort_by_score_with_ties, tier_label, validate_ordering,
    },
};

const SPAWN_BLOCKING_EP_THRESHOLD: usize = 150;

impl CatalogRepo<'_> {
    pub async fn append_comparison(
        &self,
        scope_collection_id: Option<&str>,
        ordering: &[Vec<String>],
        ts: Option<DateTime<Utc>>,
    ) -> CatalogResult<ComparisonRecord> {
        let mut resolved_ordering: Vec<Vec<String>> = Vec::with_capacity(ordering.len());
        for group in ordering {
            let mut resolved_group = Vec::with_capacity(group.len());
            for raw_id in group {
                let canon_id = self
                    .follow_merged_id(raw_id)
                    .await?
                    .ok_or_else(|| CatalogError::NotFound(format!("Entry '{raw_id}'")))?;
                resolved_group.push(canon_id);
            }
            resolved_ordering.push(resolved_group);
        }
        validate_ordering(&resolved_ordering).map_err(CatalogError::InvalidInput)?;

        let clean_scope = match scope_collection_id.map(str::trim).filter(|s| !s.is_empty()) {
            Some(sc) => {
                let col = self.lookup_collection_row(sc, false).await?;
                Some(col.id)
            }
            None => None,
        };

        let now = SyncRepo::now_utc();
        let event_ts = ts.unwrap_or(now);
        let mut tx = crate::db::begin_write(self.pool).await?;
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

    pub async fn get_ranked_collection(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> CatalogResult<RankedCollectionView> {
        let raw_col = if want {
            let summary = self.get_or_create_want_collection(slug_or_id).await?;
            self.lookup_collection_row(&summary.id, false).await?
        } else {
            self.lookup_collection_row(slug_or_id, true).await?
        };
        let is_priority = raw_col.collection_role == CollectionRole::Want;
        let members = self.resolve_rankable_members(&raw_col).await?;
        let member_ids: Vec<String> = members.iter().map(|m| m.id.clone()).collect();
        let member_count = member_ids.len();

        let orderings = self
            .load_orderings_for_scope(if is_priority { Some(raw_col.id.as_str()) } else { None })
            .await?;
        let member_set: HashSet<&str> = member_ids.iter().map(String::as_str).collect();
        let relevant_comparison_count = orderings
            .iter()
            .filter(|ord| {
                ord.iter().flat_map(|g| g.iter()).any(|id| member_set.contains(id.as_str()))
            })
            .count();

        let scores = run_infer_scores(member_ids, orderings).await?;
        let tiers_by_id = if !is_priority && relevant_comparison_count > 0 {
            rescale_to_quantiles(&scores, N_QUANTILES)
                .into_iter()
                .map(|(id, q)| (id, tier_label(q)))
                .collect::<HashMap<_, _>>()
        } else {
            HashMap::new()
        };

        let mut sorted_members = members;
        sort_by_score_with_ties(
            &mut sorted_members,
            &scores,
            |entry| entry.id.as_str(),
            |a, b| {
                a.title.to_lowercase().cmp(&b.title.to_lowercase()).then_with(|| a.id.cmp(&b.id))
            },
        );

        let max_abs = scores.values().map(|s| s.mean.abs()).fold(0.0_f64, f64::max);
        let scale = if max_abs > 0.0 { max_abs } else { 1.0 };

        let rows: Vec<RankedEntryRow> = sorted_members
            .into_iter()
            .enumerate()
            .map(|(idx, entry)| {
                let score = scores.get(&entry.id).copied().unwrap_or_default();
                let tier = tiers_by_id.get(&entry.id).copied();
                let bar_pct = ((score.mean / scale + 1.0) * 50.0).clamp(2.0, 100.0);
                RankedEntryRow { rank: idx + 1, entry, score, tier, bar_pct }
            })
            .collect();

        let summary = CollectionSummary {
            id: raw_col.id,
            slug: raw_col.slug,
            title: raw_col.title,
            summary: raw_col.summary,
            kind: raw_col.kind,
            mode: raw_col.mode,
            role: raw_col.collection_role,
            rule_json: raw_col.rule_json,
            show_in_lists: raw_col.show_in_lists,
            color: raw_col.color,
            member_count,
            comparison_count: relevant_comparison_count,
            has_generator: raw_col.has_generator,
            created_at: raw_col.created_at,
            updated_at: raw_col.updated_at,
        };
        Ok(RankedCollectionView {
            collection: summary,
            is_priority_scope: is_priority,
            comparison_count: relevant_comparison_count,
            rows,
        })
    }

    /// Lightweight count of rankable members in `slug_or_id` without hydrating entries,
    /// loading comparisons, or running EP inference.
    pub async fn count_rankable_members(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> CatalogResult<usize> {
        let raw_col = if want {
            let summary = self.get_or_create_want_collection(slug_or_id).await?;
            self.lookup_collection_row(&summary.id, false).await?
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

    pub async fn select_next_cluster(
        &self,
        slug_or_id: &str,
        want: bool,
        filter: &ClusterSelectFilter,
    ) -> CatalogResult<(CollectionSummary, Vec<CatalogEntry>, HashMap<String, Score>)> {
        self.select_next_cluster_with_retries(slug_or_id, want, filter, 0, |_| false).await
    }

    #[allow(clippy::too_many_lines)]
    pub async fn select_next_cluster_with_retries<F>(
        &self,
        slug_or_id: &str,
        want: bool,
        filter: &ClusterSelectFilter,
        max_covered_retries: usize,
        is_covered: F,
    ) -> CatalogResult<(CollectionSummary, Vec<CatalogEntry>, HashMap<String, Score>)>
    where
        F: Fn(&[CatalogEntry]) -> bool,
    {
        let raw_col = if want {
            let summary = self.get_or_create_want_collection(slug_or_id).await?;
            self.lookup_collection_row(&summary.id, false).await?
        } else {
            self.lookup_collection_row(slug_or_id, true).await?
        };
        let is_priority = raw_col.collection_role == CollectionRole::Want;
        let all_members = self.resolve_rankable_members(&raw_col).await?;
        let total_member_count = all_members.len();
        let all_member_set: HashSet<&str> = all_members.iter().map(|m| m.id.as_str()).collect();

        let orderings = self
            .load_orderings_for_scope(if is_priority { Some(raw_col.id.as_str()) } else { None })
            .await?;
        let relevant_comparison_count = orderings
            .iter()
            .filter(|ord| {
                ord.iter().flat_map(|g| g.iter()).any(|id| all_member_set.contains(id.as_str()))
            })
            .count();

        let mut members = all_members;
        if let Some(creator_q) =
            filter.by_creator.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            let needle = creator_q.to_lowercase();
            members.retain(|e| e.creators.iter().any(|c| c.to_lowercase().contains(&needle)));
        }
        if let Some(raw_parent_id) =
            filter.from_parent_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            let resolved_parent = self
                .follow_merged_id(raw_parent_id)
                .await?
                .unwrap_or_else(|| raw_parent_id.to_owned());
            let child_ids: HashSet<String> = sqlx::query_scalar!(
                r#"SELECT entry_id as "entry_id!" FROM collection_entries WHERE collection_id = ?"#,
                &resolved_parent,
            )
            .fetch_all(self.pool)
            .await?
            .into_iter()
            .collect();
            members.retain(|e| child_ids.contains(&e.id));
        }

        let member_ids: Vec<String> = members.iter().map(|m| m.id.clone()).collect();
        let scores = run_infer_scores(member_ids.clone(), orderings).await?;

        let mut seed_pool: Option<Vec<String>> = None;
        if !filter.focus_ids.is_empty() {
            let mut resolved_focus = Vec::new();
            for fid in &filter.focus_ids {
                if let Some(canon) = self.follow_merged_id(fid).await? {
                    resolved_focus.push(canon);
                }
            }
            seed_pool = Some(resolved_focus);
        } else if let Some(recent_n) = filter.recent_limit.filter(|&n| n > 0) {
            let recent_cap = i64::try_from(recent_n).unwrap_or(50);
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
            if !recent_ids.is_empty() {
                seed_pool = Some(recent_ids);
            }
        }

        let by_id: HashMap<String, CatalogEntry> =
            members.into_iter().map(|e| (e.id.clone(), e)).collect();

        let max_k = if filter.max_k == 0 { MAX_K_DEFAULT } else { filter.max_k };
        let force_random = seed_pool.is_none()
            && filter.iteration > 0
            && filter.iteration.is_multiple_of(RANDOM_SEED_EVERY);

        let mut cluster_entries: Vec<CatalogEntry> = {
            let mut rng = rand::rng();
            let cluster_ids = select_cluster(
                &scores,
                &member_ids,
                max_k,
                force_random,
                seed_pool.as_deref(),
                &mut rng,
            );
            cluster_ids.iter().filter_map(|id| by_id.get(id).cloned()).collect()
        };

        if cluster_entries.len() >= 2 && is_covered(&cluster_entries) {
            if filter.focus_ids.is_empty() && max_covered_retries > 0 {
                let mut rng = rand::rng();
                for _ in 0..max_covered_retries {
                    let cand_ids = select_cluster(
                        &scores,
                        &member_ids,
                        max_k,
                        true,
                        seed_pool.as_deref(),
                        &mut rng,
                    );
                    let cand: Vec<CatalogEntry> =
                        cand_ids.iter().filter_map(|id| by_id.get(id).cloned()).collect();
                    if cand.len() >= 2 && !is_covered(&cand) {
                        cluster_entries = cand;
                        break;
                    }
                }
            }
            if is_covered(&cluster_entries) {
                cluster_entries.clear();
            }
        }

        let summary = CollectionSummary {
            id: raw_col.id,
            slug: raw_col.slug,
            title: raw_col.title,
            summary: raw_col.summary,
            kind: raw_col.kind,
            mode: raw_col.mode,
            role: raw_col.collection_role,
            rule_json: raw_col.rule_json,
            show_in_lists: raw_col.show_in_lists,
            color: raw_col.color,
            member_count: total_member_count,
            comparison_count: relevant_comparison_count,
            has_generator: raw_col.has_generator,
            created_at: raw_col.created_at,
            updated_at: raw_col.updated_at,
        };
        Ok((summary, cluster_entries, scores))
    }

    pub(super) async fn load_orderings_for_scope(
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

async fn run_infer_scores(
    member_ids: Vec<String>,
    orderings: Vec<Vec<Vec<String>>>,
) -> CatalogResult<HashMap<String, Score>> {
    if member_ids.len() >= SPAWN_BLOCKING_EP_THRESHOLD
        || orderings.len() >= SPAWN_BLOCKING_EP_THRESHOLD
    {
        tokio::task::spawn_blocking(move || infer_scores(&member_ids, &orderings))
            .await
            .map_err(|e| CatalogError::Internal(format!("EP ranking worker failed: {e}")))
    } else {
        Ok(infer_scores(&member_ids, &orderings))
    }
}
