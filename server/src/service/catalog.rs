use std::collections::{HashMap, HashSet};

use chrono::{DateTime, Utc};

use super::AppError;
use crate::{
    db::{
        CatalogBatchInput, CatalogBatchResult, CatalogEntry, CatalogRepo, ClusterSelectFilter,
        CollectionGeneratorState, CollectionRankingInputs, CollectionSummary, ComparisonRecord,
        ConsumptionLogView, Db, EntryDetailView, EntryIdentifier, RankedCollectionView,
        RankedEntryRow, UpsertEntryInput,
    },
    domain::{CollectionRole, ConsumptionStatus, EntryKind},
    ranking::{
        MAX_K_DEFAULT, N_QUANTILES, RANDOM_SEED_EVERY, Score, infer_scores, rescale_to_quantiles,
        select_cluster, sort_by_score_with_ties, tier_label,
    },
};

const MAX_COVERED_CLUSTER_RETRIES: usize = 4;
const SPAWN_BLOCKING_EP_THRESHOLD: usize = 150;

/// Summary of an entry's rank, list size, and optional quality tier on a global list.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntryListAppearance {
    pub slug: String,
    pub title: String,
    pub rank: usize,
    pub total: usize,
    pub tier: Option<char>,
}

/// Domain service wrapping [`CatalogRepo`] with explicit methods, EP ranking orchestration,
/// and shared cross-surface workflows used identically by the REST API, SSR Web UI, and CLI/TUI.
#[derive(Clone, Copy)]
pub struct CatalogService<'a> {
    repo: CatalogRepo<'a>,
}

impl<'a> CatalogService<'a> {
    #[must_use]
    pub const fn new(db: &'a Db) -> Self {
        Self { repo: db.catalog() }
    }

    /// Returns `true` when `input` carries explicit identifying or descriptive metadata beyond
    /// a bare `kind` + `title` lookup query.
    #[must_use]
    pub fn has_explicit_metadata(input: &UpsertEntryInput) -> bool {
        input.id.as_deref().map(str::trim).is_some_and(|s| !s.is_empty())
            || input.url.as_deref().map(str::trim).is_some_and(|s| !s.is_empty())
            || !input.identifiers.is_empty()
            || input.creators.iter().any(|c| !c.trim().is_empty())
            || input.release_year.is_some()
            || input.published_at.is_some()
            || input.summary.as_deref().map(str::trim).is_some_and(|s| !s.is_empty())
            || input.thumbnail_url.as_deref().map(str::trim).is_some_and(|s| !s.is_empty())
            || input.parent_collection_id.as_deref().map(str::trim).is_some_and(|s| !s.is_empty())
    }

    pub async fn list_collections(
        &self,
        include_want: bool,
        include_system: bool,
    ) -> Result<Vec<CollectionSummary>, AppError> {
        Ok(self.repo.list_collections(include_want, include_system).await?)
    }

    pub async fn load_collection_preview_thumbnails(
        &self,
        collections: &[CollectionSummary],
        max_per_collection: usize,
    ) -> Result<HashMap<String, Vec<(String, String)>>, AppError> {
        Ok(self.repo.load_collection_preview_thumbnails(collections, max_per_collection).await?)
    }

    pub async fn create_manual_collection(
        &self,
        title: &str,
        slug: Option<&str>,
        role: CollectionRole,
        summary: Option<&str>,
        color: Option<&str>,
    ) -> Result<CollectionSummary, AppError> {
        Ok(self.repo.create_manual_collection(title, slug, role, summary, color).await?)
    }

    pub async fn get_or_create_want_collection(
        &self,
        slug_or_name: &str,
    ) -> Result<CollectionSummary, AppError> {
        Ok(self.repo.get_or_create_want_collection(slug_or_name).await?)
    }

    pub async fn add_collection_entry(
        &self,
        slug_or_id: &str,
        entry_id: &str,
        position: Option<f64>,
    ) -> Result<(), AppError> {
        Ok(self.repo.add_collection_entry(slug_or_id, entry_id, position).await?)
    }

    pub async fn remove_collection_entry(
        &self,
        slug_or_id: &str,
        entry_id: &str,
    ) -> Result<bool, AppError> {
        Ok(self.repo.remove_collection_entry(slug_or_id, entry_id).await?)
    }

    pub async fn get_entry_detail(&self, id: &str) -> Result<Option<EntryDetailView>, AppError> {
        Ok(self.repo.get_entry_detail(id).await?)
    }

    pub async fn find_artist_ids_by_names(
        &self,
        names: &[String],
    ) -> Result<HashMap<String, String>, AppError> {
        Ok(self.repo.find_artist_ids_by_names(names).await?)
    }

    pub async fn resolve_entry(
        &self,
        query: &str,
        kind: Option<EntryKind>,
        only_collection_id: Option<&str>,
    ) -> Result<CatalogEntry, AppError> {
        Ok(self.repo.resolve_entry(query, kind, only_collection_id).await?)
    }

    pub async fn search_entries(
        &self,
        query: Option<&str>,
        kind: Option<EntryKind>,
        limit: usize,
    ) -> Result<Vec<CatalogEntry>, AppError> {
        Ok(self.repo.search_entries(query, kind, limit).await?)
    }

    pub async fn upsert_entry(&self, input: &UpsertEntryInput) -> Result<CatalogEntry, AppError> {
        Ok(self.repo.upsert_entry(input).await?)
    }

    pub async fn merge_entries(
        &self,
        duplicate_id: &str,
        canonical_id: &str,
    ) -> Result<CatalogEntry, AppError> {
        Ok(self.repo.merge_entries(duplicate_id, canonical_id).await?)
    }

    pub async fn list_diary(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
        limit: usize,
    ) -> Result<Vec<ConsumptionLogView>, AppError> {
        Ok(self.repo.list_diary(year, status, limit).await?)
    }

    pub async fn log_consumption(
        &self,
        entry_id: &str,
        status: ConsumptionStatus,
        notes: Option<&str>,
        ts: Option<DateTime<Utc>>,
    ) -> Result<ConsumptionLogView, AppError> {
        Ok(self.repo.log_consumption(entry_id, status, notes, ts).await?)
    }

    pub async fn delete_consumed_logs_for_entry(&self, entry_id: &str) -> Result<usize, AppError> {
        Ok(self.repo.delete_consumed_logs_for_entry(entry_id).await?)
    }

    /// Logs a consumption event for either an existing `entry_id` or an inline `UpsertEntryInput`.
    ///
    /// When logging an inline entry without explicit metadata (e.g. a bare title or external ID),
    /// attempts to resolve an existing catalog entry first; otherwise atomically upserts the
    /// entry and records the consumption log in a single SQLite write transaction.
    pub async fn log_entry_resolved_or_upsert(
        &self,
        entry_id: Option<&str>,
        entry: Option<&UpsertEntryInput>,
        status: ConsumptionStatus,
        notes: Option<&str>,
        ts: Option<DateTime<Utc>>,
    ) -> Result<ConsumptionLogView, AppError> {
        if let Some(eid) = entry_id.map(str::trim).filter(|s| !s.is_empty()) {
            return Ok(self.repo.log_consumption(eid, status, notes, ts).await?);
        }
        let Some(input) = entry else {
            return Err(AppError::bad_request(
                "Provide either `entry_id` or inline `entry` to log consumption",
            ));
        };
        if !Self::has_explicit_metadata(input)
            && let Ok(existing) = self.repo.resolve_entry(&input.title, input.kind, None).await
        {
            return Ok(self.repo.log_consumption(&existing.id, status, notes, ts).await?);
        }
        Ok(self.repo.upsert_and_log_consumption(input, status, notes, ts).await?)
    }

    /// Adds an entry to a Want queue, first attempting to resolve an existing catalog entry
    /// when `input` has no explicit metadata so `roche want movie <id_or_title>` reuses
    /// existing entries instead of creating duplicate title-keyed placeholders.
    pub async fn want_resolved_or_upsert(
        &self,
        input: &UpsertEntryInput,
        notes: Option<&str>,
        queue: Option<&str>,
    ) -> Result<(CollectionSummary, CatalogEntry), AppError> {
        let try_resolve = !Self::has_explicit_metadata(input);
        Ok(self.repo.want_resolved_or_upsert(input, notes, queue, try_resolve).await?)
    }

    pub async fn unwant_entry(
        &self,
        queue_slug_or_id: &str,
        entry_id_or_query: &str,
    ) -> Result<CatalogEntry, AppError> {
        Ok(self.repo.unwant_entry(queue_slug_or_id, entry_id_or_query).await?)
    }

    pub async fn append_comparison(
        &self,
        scope_collection_id: Option<&str>,
        ordering: &[Vec<String>],
        ts: Option<DateTime<Utc>>,
    ) -> Result<ComparisonRecord, AppError> {
        Ok(self.repo.append_comparison(scope_collection_id, ordering, ts).await?)
    }

    /// Returns `true` when `slug_or_id` contains at least 2 rankable entries.
    pub async fn can_rank_collection(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> Result<bool, AppError> {
        Ok(self.repo.count_rankable_members(slug_or_id, want).await? >= 2)
    }

    /// Determines whether an entry just logged as `consumed` can immediately enter a
    /// focused k-way ranking session (`Some(default_list_slug)` iff `>= 2` items exist).
    pub async fn rank_target_after_consumption(
        &self,
        entry: &CatalogEntry,
    ) -> Result<Option<String>, AppError> {
        let Some(slug) = entry.kind.default_list_slug() else {
            return Ok(None);
        };
        if self.can_rank_collection(slug, false).await? {
            Ok(Some(slug.to_owned()))
        } else {
            Ok(None)
        }
    }

    /// Determines the best ranking list target immediately after finishing a generator draw:
    /// prefers `list_slug_or_id` itself when it has at least 2 rankable members, falling back
    /// to the completed entry's default kind list.
    pub async fn rank_target_after_generator_finish(
        &self,
        list_slug_or_id: &str,
        completed_entry: &CatalogEntry,
    ) -> Result<Option<String>, AppError> {
        if self.can_rank_collection(list_slug_or_id, false).await.unwrap_or(false) {
            return Ok(Some(list_slug_or_id.to_owned()));
        }
        self.rank_target_after_consumption(completed_entry).await
    }

    /// Loads a collection's rankable members, runs EP inference (offloading large graphs to
    /// `spawn_blocking`), assigns S-D quantile tiers for global lists, and computes bar widths.
    pub async fn get_ranked_collection(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> Result<RankedCollectionView, AppError> {
        let mut inputs = self.repo.load_collection_ranking_inputs(slug_or_id, want).await?;
        let is_priority = inputs.is_priority_scope;
        let member_ids: Vec<String> = inputs.members.iter().map(|m| m.id.clone()).collect();

        let orderings = self
            .repo
            .load_orderings_for_scope(is_priority.then_some(inputs.collection.id.as_str()))
            .await?;
        let relevant_comparison_count = count_relevant_comparisons(&member_ids, &orderings);
        inputs.collection.comparison_count = relevant_comparison_count;

        let scores = run_infer_scores(member_ids, orderings).await?;
        let tiers_by_id = if !is_priority && relevant_comparison_count > 0 {
            rescale_to_quantiles(&scores, N_QUANTILES)
                .into_iter()
                .map(|(id, q)| (id, tier_label(q)))
                .collect::<HashMap<_, _>>()
        } else {
            HashMap::new()
        };

        let mut sorted_members = inputs.members;
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

        Ok(RankedCollectionView {
            collection: inputs.collection,
            is_priority_scope: is_priority,
            comparison_count: relevant_comparison_count,
            rows,
        })
    }

    pub async fn select_next_cluster(
        &self,
        slug_or_id: &str,
        want: bool,
        filter: &ClusterSelectFilter,
    ) -> Result<(CollectionSummary, Vec<CatalogEntry>, HashMap<String, Score>), AppError> {
        self.select_next_cluster_with_retries(slug_or_id, want, filter, 0, |_| false).await
    }

    /// Selects the next k-way comparison cluster for `slug_or_id`, retrying with random
    /// seed selection if `is_covered` reports that the candidate cluster is already covered
    /// by clusters judged earlier in the current session.
    pub async fn select_next_uncovered_cluster<F>(
        &self,
        slug_or_id: &str,
        want: bool,
        base_filter: &ClusterSelectFilter,
        is_covered: F,
    ) -> Result<(CollectionSummary, Vec<CatalogEntry>, HashMap<String, Score>), AppError>
    where
        F: Fn(&[CatalogEntry]) -> bool,
    {
        self.select_next_cluster_with_retries(
            slug_or_id,
            want,
            base_filter,
            MAX_COVERED_CLUSTER_RETRIES,
            is_covered,
        )
        .await
    }

    async fn select_next_cluster_with_retries<F>(
        &self,
        slug_or_id: &str,
        want: bool,
        filter: &ClusterSelectFilter,
        max_covered_retries: usize,
        is_covered: F,
    ) -> Result<(CollectionSummary, Vec<CatalogEntry>, HashMap<String, Score>), AppError>
    where
        F: Fn(&[CatalogEntry]) -> bool,
    {
        let mut inputs = self.repo.load_collection_ranking_inputs(slug_or_id, want).await?;
        let is_priority = inputs.is_priority_scope;
        let all_member_ids: Vec<String> = inputs.members.iter().map(|m| m.id.clone()).collect();

        let orderings = self
            .repo
            .load_orderings_for_scope(is_priority.then_some(inputs.collection.id.as_str()))
            .await?;
        inputs.collection.comparison_count =
            count_relevant_comparisons(&all_member_ids, &orderings);

        let members = self.filter_cluster_members(inputs.members, filter).await?;
        let member_ids: Vec<String> = members.iter().map(|m| m.id.clone()).collect();
        let scores = run_infer_scores(member_ids.clone(), orderings).await?;
        let seed_pool = self.resolve_cluster_seed_pool(filter).await?;

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

        Ok((inputs.collection, cluster_entries, scores))
    }

    async fn filter_cluster_members(
        &self,
        mut members: Vec<CatalogEntry>,
        filter: &ClusterSelectFilter,
    ) -> Result<Vec<CatalogEntry>, AppError> {
        if let Some(creator_q) =
            filter.by_creator.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            let needle = creator_q.to_lowercase();
            members.retain(|e| e.creators.iter().any(|c| c.to_lowercase().contains(&needle)));
        }
        if let Some(raw_parent_id) =
            filter.from_parent_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            let child_ids = self.repo.load_child_entry_ids_for_parent(raw_parent_id).await?;
            members.retain(|e| child_ids.contains(&e.id));
        }
        Ok(members)
    }

    async fn resolve_cluster_seed_pool(
        &self,
        filter: &ClusterSelectFilter,
    ) -> Result<Option<Vec<String>>, AppError> {
        if !filter.focus_ids.is_empty() {
            return Ok(Some(self.repo.resolve_canonical_ids(&filter.focus_ids).await?));
        }
        if let Some(recent_n) = filter.recent_limit.filter(|&n| n > 0) {
            let recent_ids = self.repo.load_recent_consumed_entry_ids(recent_n).await?;
            if !recent_ids.is_empty() {
                return Ok(Some(recent_ids));
            }
        }
        Ok(None)
    }

    /// Computes an entry's rank, list total, and optional S-D tier across its default kind list
    /// and all parent global lists using a **single** global EP inference pass.
    pub async fn load_entry_ranked_appearances(
        &self,
        detail: &EntryDetailView,
    ) -> Vec<EntryListAppearance> {
        let mut candidate_keys: Vec<String> = Vec::new();
        if let Some(default_slug) = detail.entry.kind.default_list_slug() {
            candidate_keys.push(default_slug.to_owned());
        }
        for parent in &detail.parents {
            if parent.kind == EntryKind::Collection {
                candidate_keys.push(parent.id.clone());
            }
        }

        let mut eligible_lists: Vec<CollectionRankingInputs> = Vec::new();
        let mut seen_col_ids: HashSet<String> = HashSet::new();
        for key in candidate_keys {
            // Only show genuine global Lists (`List` / `System`), never `Want`, `Tag`, or `Feed`.
            if let Ok(inputs) = self.repo.load_collection_ranking_inputs(&key, false).await
                && matches!(inputs.collection.role, CollectionRole::List | CollectionRole::System)
                && seen_col_ids.insert(inputs.collection.id.clone())
                && inputs.members.iter().any(|m| m.id == detail.entry.id)
            {
                eligible_lists.push(inputs);
            }
        }
        if eligible_lists.is_empty() {
            return Vec::new();
        }

        let Ok(orderings) = self.repo.load_orderings_for_scope(None).await else {
            return Vec::new();
        };

        let (union_member_ids, list_has_relevant_comp) = {
            let mut union_ids = Vec::new();
            let mut seen_member_ids: HashSet<&str> = HashSet::new();
            let mut has_rel_flags = Vec::with_capacity(eligible_lists.len());
            for inputs in &eligible_lists {
                let member_set: HashSet<&str> =
                    inputs.members.iter().map(|m| m.id.as_str()).collect();
                let has_rel = orderings.iter().any(|ord| {
                    ord.iter().flat_map(|g| g.iter()).any(|id| member_set.contains(id.as_str()))
                });
                has_rel_flags.push(has_rel);
                for member in &inputs.members {
                    if seen_member_ids.insert(member.id.as_str()) {
                        union_ids.push(member.id.clone());
                    }
                }
            }
            (union_ids, has_rel_flags)
        };

        let Ok(global_scores) = run_infer_scores(union_member_ids, orderings).await else {
            return Vec::new();
        };

        let mut out = Vec::with_capacity(eligible_lists.len());
        for (inputs, has_relevant_comp) in eligible_lists.into_iter().zip(list_has_relevant_comp) {
            let list_scores: HashMap<String, Score> = inputs
                .members
                .iter()
                .map(|m| (m.id.clone(), global_scores.get(&m.id).copied().unwrap_or_default()))
                .collect();
            let tier = if has_relevant_comp {
                rescale_to_quantiles(&list_scores, N_QUANTILES)
                    .get(&detail.entry.id)
                    .copied()
                    .map(tier_label)
            } else {
                None
            };

            let mut sorted_members = inputs.members;
            sort_by_score_with_ties(
                &mut sorted_members,
                &list_scores,
                |entry| entry.id.as_str(),
                |a, b| {
                    a.title
                        .to_lowercase()
                        .cmp(&b.title.to_lowercase())
                        .then_with(|| a.id.cmp(&b.id))
                },
            );
            let total = sorted_members.len();
            if let Some(idx) = sorted_members.iter().position(|e| e.id == detail.entry.id) {
                out.push(EntryListAppearance {
                    slug: inputs.collection.slug.unwrap_or(inputs.collection.id),
                    title: inputs.collection.title,
                    rank: idx + 1,
                    total,
                    tier,
                });
            }
        }
        out
    }

    pub async fn apply_batch(
        &self,
        batch: &CatalogBatchInput,
    ) -> Result<CatalogBatchResult, AppError> {
        Ok(self.repo.apply_batch(batch).await?)
    }

    pub async fn list_collection_generators(
        &self,
        enabled_only: bool,
    ) -> Result<Vec<CollectionGeneratorState>, AppError> {
        Ok(self.repo.list_collection_generators(enabled_only).await?)
    }

    pub async fn get_collection_generator(
        &self,
        slug_or_id: &str,
    ) -> Result<CollectionGeneratorState, AppError> {
        Ok(self.repo.get_collection_generator(slug_or_id).await?)
    }

    pub async fn set_collection_generator_enabled(
        &self,
        slug_or_id: &str,
        enabled: bool,
    ) -> Result<CollectionGeneratorState, AppError> {
        Ok(self.repo.set_collection_generator_enabled(slug_or_id, enabled).await?)
    }

    pub async fn update_collection_generator(
        &self,
        slug_or_id: &str,
        enabled: Option<bool>,
        explicit_entry_id: Option<&str>,
        explicit_drawn_at: Option<DateTime<Utc>>,
    ) -> Result<CollectionGeneratorState, AppError> {
        Ok(self
            .repo
            .update_collection_generator(slug_or_id, enabled, explicit_entry_id, explicit_drawn_at)
            .await?)
    }

    pub async fn remove_collection_generator(&self, slug_or_id: &str) -> Result<bool, AppError> {
        Ok(self.repo.remove_collection_generator(slug_or_id).await?)
    }

    pub async fn finish_active_generator_entry(
        &self,
        slug_or_id: &str,
        notes: Option<&str>,
    ) -> Result<(CatalogEntry, CollectionGeneratorState), AppError> {
        Ok(self.repo.finish_active_generator_entry(slug_or_id, notes).await?)
    }

    pub async fn abandon_active_generator_entry(
        &self,
        slug_or_id: &str,
        notes: Option<&str>,
    ) -> Result<(CatalogEntry, CollectionGeneratorState), AppError> {
        Ok(self.repo.abandon_active_generator_entry(slug_or_id, notes).await?)
    }

    /// Parses an explicit CLI `--id` flag (`scheme=value`, `spotify:...`, or `scheme:value`).
    pub fn parse_cli_identifier(raw: &str) -> Result<EntryIdentifier, AppError> {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            return Err(AppError::bad_request("Identifier cannot be empty"));
        }
        if let Some((scheme, val)) = trimmed.split_once('=') {
            let s = scheme.trim();
            let v = val.trim();
            if s.is_empty() || v.is_empty() {
                return Err(AppError::bad_request(format!(
                    "Identifier '{raw}' must have non-empty scheme and value"
                )));
            }
            return Ok(EntryIdentifier { scheme: s.to_owned(), value: v.to_owned() });
        }
        if trimmed.starts_with("spotify:") {
            return Ok(EntryIdentifier { scheme: "spotify".to_owned(), value: trimmed.to_owned() });
        }
        if let Some((scheme, val)) = trimmed.split_once(':')
            && !matches!(scheme.to_ascii_lowercase().as_str(), "http" | "https")
        {
            let s = scheme.trim();
            let v = val.trim();
            if !s.is_empty() && !v.is_empty() {
                return Ok(EntryIdentifier { scheme: s.to_owned(), value: v.to_owned() });
            }
        }
        Err(AppError::bad_request(format!("Identifier '{raw}' must use `scheme=value` format")))
    }

    /// Parses a free-form web/quick-log identifier field (HTTP URL, Spotify URI/URL, `IMDb` `tt...`,
    /// ISBN-10/13, `scheme=value`, `scheme:value`, or bare external ID).
    #[must_use]
    pub fn parse_smart_identifier(raw: Option<&str>) -> (Option<String>, Vec<EntryIdentifier>) {
        let Some(s) = raw.map(str::trim).filter(|s| !s.is_empty()) else {
            return (None, Vec::new());
        };
        if s.starts_with("spotify:") || s.contains("open.spotify.com/") {
            let url_opt =
                (s.starts_with("https://") || s.starts_with("http://")).then(|| s.to_owned());
            return (
                url_opt,
                vec![EntryIdentifier { scheme: "spotify".to_owned(), value: s.to_owned() }],
            );
        }
        if s.starts_with("https://") || s.starts_with("http://") {
            return (
                Some(s.to_owned()),
                vec![EntryIdentifier { scheme: "url".to_owned(), value: s.to_owned() }],
            );
        }
        if s.starts_with("tt") && s.len() >= 5 && s[2..].chars().all(|c| c.is_ascii_digit()) {
            return (
                None,
                vec![EntryIdentifier { scheme: "imdb".to_owned(), value: s.to_owned() }],
            );
        }
        if looks_like_isbn(s) {
            return (
                None,
                vec![EntryIdentifier { scheme: "isbn".to_owned(), value: s.to_owned() }],
            );
        }
        if let Some((scheme, val)) = s.split_once('=') {
            let sc = scheme.trim();
            let vl = val.trim();
            if !sc.is_empty() && !vl.is_empty() {
                return (
                    None,
                    vec![EntryIdentifier { scheme: sc.to_owned(), value: vl.to_owned() }],
                );
            }
        }
        if let Some((scheme, val)) = s.split_once(':') {
            let sc = scheme.trim();
            let vl = val.trim();
            if !sc.is_empty() && !vl.is_empty() {
                return (
                    None,
                    vec![EntryIdentifier { scheme: sc.to_owned(), value: vl.to_owned() }],
                );
            }
        }
        (None, vec![EntryIdentifier { scheme: "external".to_owned(), value: s.to_owned() }])
    }
}

fn count_relevant_comparisons(member_ids: &[String], orderings: &[Vec<Vec<String>>]) -> usize {
    let member_set: HashSet<&str> = member_ids.iter().map(String::as_str).collect();
    orderings
        .iter()
        .filter(|ord| ord.iter().flat_map(|g| g.iter()).any(|id| member_set.contains(id.as_str())))
        .count()
}

async fn run_infer_scores(
    member_ids: Vec<String>,
    orderings: Vec<Vec<Vec<String>>>,
) -> Result<HashMap<String, Score>, AppError> {
    if member_ids.len() >= SPAWN_BLOCKING_EP_THRESHOLD
        || orderings.len() >= SPAWN_BLOCKING_EP_THRESHOLD
    {
        tokio::task::spawn_blocking(move || infer_scores(&member_ids, &orderings))
            .await
            .map_err(|e| AppError::Internal(anyhow::anyhow!("EP inference task failed: {e}")))
    } else {
        Ok(infer_scores(&member_ids, &orderings))
    }
}

fn looks_like_isbn(raw: &str) -> bool {
    if !raw.chars().all(|c| c.is_ascii_digit() || c == '-' || c == ' ' || c == 'X' || c == 'x') {
        return false;
    }
    let stripped: String = raw.chars().filter(|c| *c != '-' && *c != ' ').collect();
    match stripped.len() {
        10 => {
            stripped[..9].chars().all(|c| c.is_ascii_digit())
                && matches!(stripped.as_bytes()[9], b'0'..=b'9' | b'X' | b'x')
        }
        13 => {
            (stripped.starts_with("978") || stripped.starts_with("979"))
                && stripped.chars().all(|c| c.is_ascii_digit())
        }
        _ => false,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::db::Db;

    #[tokio::test]
    async fn test_rank_target_after_consumption_requires_at_least_two_items() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let m1 = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Movie),
                title: "Stalker".to_owned(),
                creators: vec!["Andrei Tarkovsky".to_owned()],
                release_year: Some(1979),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        svc.log_consumption(&m1.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

        // First movie ever logged: cannot rank against itself!
        assert_eq!(svc.rank_target_after_consumption(&m1).await.unwrap(), None);

        let m2 = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Movie),
                title: "Solaris".to_owned(),
                creators: vec!["Andrei Tarkovsky".to_owned()],
                release_year: Some(1972),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        svc.log_consumption(&m2.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

        // Second movie logged: ranking is now eligible!
        assert_eq!(
            svc.rank_target_after_consumption(&m2).await.unwrap(),
            Some("movies".to_owned())
        );
    }

    #[tokio::test]
    async fn test_want_resolved_or_upsert_reuses_existing_entry_and_stores_notes() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let existing = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Movie),
                title: "Blade Runner".to_owned(),
                creators: vec!["Ridley Scott".to_owned()],
                release_year: Some(1982),
                identifiers: vec![EntryIdentifier {
                    scheme: "imdb".to_owned(),
                    value: "tt0083658".to_owned(),
                }],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();

        let bare_want_input = UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "tt0083658".to_owned(),
            ..UpsertEntryInput::default()
        };

        let (_, queued) = svc
            .want_resolved_or_upsert(&bare_want_input, Some("Watch Final Cut in 4K"), None)
            .await
            .unwrap();
        assert_eq!(queued.id, existing.id);
        assert_eq!(queued.title, "Blade Runner");
        assert_eq!(
            queued.metadata.get("want_notes").and_then(|v| v.as_str()),
            Some("Watch Final Cut in 4K")
        );
    }

    #[tokio::test]
    async fn test_load_entry_ranked_appearances_matches_per_list_ranking_in_single_pass() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let stalker = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Movie),
                title: "Stalker".to_owned(),
                release_year: Some(1979),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let solaris = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Movie),
                title: "Solaris".to_owned(),
                release_year: Some(1972),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let dune = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Book),
                title: "Dune".to_owned(),
                release_year: Some(1965),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();

        svc.log_consumption(&stalker.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
        svc.log_consumption(&solaris.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
        svc.log_consumption(&dune.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

        let sci_fi = svc
            .create_manual_collection(
                "Sci-Fi Canon",
                Some("sci-fi"),
                CollectionRole::List,
                None,
                None,
            )
            .await
            .unwrap();
        svc.add_collection_entry(&sci_fi.id, &stalker.id, None).await.unwrap();
        svc.add_collection_entry(&sci_fi.id, &dune.id, None).await.unwrap();

        svc.append_comparison(
            None,
            &[vec![stalker.id.clone()], vec![solaris.id.clone()], vec![dune.id.clone()]],
            None,
        )
        .await
        .unwrap();

        let detail = svc.get_entry_detail(&stalker.id).await.unwrap().unwrap();
        let appearances = svc.load_entry_ranked_appearances(&detail).await;
        assert_eq!(appearances.len(), 2);

        let movies_view = svc.get_ranked_collection("movies", false).await.unwrap();
        let scifi_view = svc.get_ranked_collection("sci-fi", false).await.unwrap();

        let movies_row = movies_view.rows.iter().find(|r| r.entry.id == stalker.id).unwrap();
        let scifi_row = scifi_view.rows.iter().find(|r| r.entry.id == stalker.id).unwrap();

        assert_eq!(appearances[0].slug, "movies");
        assert_eq!(appearances[0].rank, movies_row.rank);
        assert_eq!(appearances[0].total, movies_view.rows.len());
        assert_eq!(appearances[0].tier, movies_row.tier);

        assert_eq!(appearances[1].slug, "sci_fi");
        assert_eq!(appearances[1].rank, scifi_row.rank);
        assert_eq!(appearances[1].total, scifi_view.rows.len());
        assert_eq!(appearances[1].tier, scifi_row.tier);
    }
}
