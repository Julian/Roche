use std::{
    collections::{HashMap, HashSet},
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::Instant,
};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::sync::Semaphore;

use super::AppError;
use crate::{
    db::{
        CatalogBatchInput, CatalogBatchResult, CatalogEntry, CatalogRepo, CollectionGeneratorState,
        CollectionRankingInputs, CollectionSummary, ComparisonRecord, ConsumptionLogView, Db,
        EntryDetailView, EntryIdentifier, UpsertEntryInput, WantSearchMatch,
    },
    domain::{CollectionRole, ConsumptionStatus, EntryKind},
    ranking::{
        MAX_K_DEFAULT, MIN_K, N_QUANTILES, RANDOM_SEED_EVERY, Score, infer_scores,
        rescale_to_quantiles, select_cluster_prioritized, sort_by_score_with_ties, tier_label,
    },
};

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

#[derive(Debug, Clone, Default)]
pub struct ClusterSelectFilter {
    pub max_k: usize,
    pub iteration: usize,
    pub focus_ids: Vec<String>,
    pub excluded_ids: Vec<String>,
    pub recent_limit: Option<usize>,
    pub by_creator: Option<String>,
    pub from_parent_id: Option<String>,
}

/// Selected comparison cluster along with its selection-priority order
/// (`[seed, most_confusable, ...]`) so UIs can safely shrink the board width.
#[derive(Debug, Clone, Default)]
pub struct PrioritizedCluster {
    pub entries: Vec<CatalogEntry>,
    pub priority_ids: Vec<String>,
}

const MAX_COVERED_CLUSTER_RETRIES: usize = 4;
const MAX_FOCUSED_COVERED_RETRIES: usize = 12;
const SPAWN_BLOCKING_EP_THRESHOLD: usize = 150;
const MAX_CACHED_EP_SCOPES: usize = 64;
const MAX_CONCURRENT_EP_RUNS: usize = 4;

type ScopeRevision = (i64, i64, i64);
type OrderingsArc = Arc<Vec<Vec<Vec<String>>>>;
type ScoresArc = Arc<HashMap<String, Score>>;

#[derive(Debug, Clone)]
struct CachedScopeEp {
    revision: ScopeRevision,
    generation: u64,
    orderings: OrderingsArc,
    scores: ScoresArc,
    last_used: Instant,
}

/// Bounded in-memory cache of scope-level Expectation Propagation scores keyed by
/// `(scope_collection_id, (count, max_sync_seq, sum_sync_seq))` behind a concurrency semaphore.
#[derive(Debug, Clone)]
pub struct EpScoreCache {
    entries: Arc<Mutex<HashMap<Option<String>, CachedScopeEp>>>,
    semaphore: Arc<Semaphore>,
    next_gen: Arc<AtomicU64>,
}

impl Default for EpScoreCache {
    fn default() -> Self {
        Self::new()
    }
}

impl EpScoreCache {
    #[must_use]
    pub fn new() -> Self {
        Self {
            entries: Arc::new(Mutex::new(HashMap::new())),
            semaphore: Arc::new(Semaphore::new(MAX_CONCURRENT_EP_RUNS)),
            next_gen: Arc::new(AtomicU64::new(1)),
        }
    }

    fn allocate_generation(&self) -> u64 {
        self.next_gen.fetch_add(1, Ordering::Relaxed)
    }

    fn get(
        &self,
        scope: Option<&str>,
        revision: ScopeRevision,
    ) -> Option<(OrderingsArc, ScoresArc)> {
        let key = scope.map(ToOwned::to_owned);
        let mut guard = self.entries.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let hit = guard.get_mut(&key).filter(|e| e.revision == revision).map(|entry| {
            entry.last_used = Instant::now();
            (Arc::clone(&entry.orderings), Arc::clone(&entry.scores))
        });
        drop(guard);
        hit
    }

    fn insert(
        &self,
        scope: Option<&str>,
        revision: ScopeRevision,
        generation: u64,
        orderings: OrderingsArc,
        scores: ScoresArc,
    ) {
        let mut guard = self.entries.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let key = scope.map(ToOwned::to_owned);
        if let Some(existing) = guard.get(&key)
            && existing.generation > generation
        {
            return;
        }
        if !guard.contains_key(&key)
            && guard.len() >= MAX_CACHED_EP_SCOPES
            && let Some(oldest_key) =
                guard.iter().min_by_key(|(_, v)| v.last_used).map(|(k, _)| k.clone())
        {
            guard.remove(&oldest_key);
        }
        guard.insert(
            key,
            CachedScopeEp { revision, generation, orderings, scores, last_used: Instant::now() },
        );
    }
}

/// Filter parameters for server-side tier & search filtering over a ranked collection.
#[derive(Debug, Clone, Default)]
pub struct RankedCollectionFilter<'a> {
    pub tier: Option<char>,
    pub query: Option<&'a str>,
}

/// Ranked collection view together with full-collection totals & S/A/B/C/D tier counts
/// computed before applying `RankedCollectionFilter` or pagination slices.
#[derive(Debug, Clone)]
pub struct FilteredRankedCollection {
    pub view: RankedCollectionView,
    pub full_total: usize,
    pub full_tier_counts: [usize; 5],
}

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
    ep_cache: Option<&'a EpScoreCache>,
}

impl<'a> CatalogService<'a> {
    #[must_use]
    pub const fn new(db: &'a Db) -> Self {
        Self { repo: db.catalog(), ep_cache: None }
    }

    #[must_use]
    pub const fn with_cache(db: &'a Db, ep_cache: &'a EpScoreCache) -> Self {
        Self { repo: db.catalog(), ep_cache: Some(ep_cache) }
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

    pub async fn list_diary_page(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
        limit: usize,
        offset: usize,
    ) -> Result<Vec<ConsumptionLogView>, AppError> {
        Ok(self.repo.list_diary_page(year, status, limit, offset).await?)
    }

    pub async fn list_diary_year_counts(
        &self,
        status: Option<ConsumptionStatus>,
    ) -> Result<Vec<(i32, i64)>, AppError> {
        Ok(self.repo.list_diary_year_counts(status).await?)
    }

    pub async fn search_diary_logs(
        &self,
        query: &str,
        limit: usize,
    ) -> Result<Vec<ConsumptionLogView>, AppError> {
        Ok(self.repo.search_diary_logs(query, limit).await?)
    }

    pub async fn search_want_entries(
        &self,
        query: &str,
        limit: usize,
    ) -> Result<Vec<WantSearchMatch>, AppError> {
        Ok(self.repo.search_want_entries(query, limit).await?)
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

    #[allow(clippy::option_option)]
    pub async fn update_consumption_log(
        &self,
        log_id: &str,
        status: Option<ConsumptionStatus>,
        notes: Option<Option<&str>>,
        ts: Option<DateTime<Utc>>,
    ) -> Result<ConsumptionLogView, AppError> {
        Ok(self.repo.update_consumption_log(log_id, status, notes, ts).await?)
    }

    pub async fn delete_consumption_log(&self, log_id: &str) -> Result<bool, AppError> {
        Ok(self.repo.delete_consumption_log(log_id).await?)
    }

    pub async fn delete_comparison(&self, comparison_id: &str) -> Result<bool, AppError> {
        Ok(self.repo.delete_comparison(comparison_id).await?)
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

    /// Lightweight lookup resolving whether `slug_or_id` ranks in priority (want) scope or global
    /// scope without running EP inference.
    pub async fn resolve_ranking_scope(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> Result<(bool, Option<String>), AppError> {
        Ok(self.repo.resolve_ranking_scope(slug_or_id, want).await?)
    }

    async fn load_scope_orderings_and_scores(
        &self,
        scope_collection_id: Option<&str>,
    ) -> Result<(OrderingsArc, ScoresArc), AppError> {
        if let Some(cache) = self.ep_cache {
            let rev = self.repo.comparison_scope_revision(scope_collection_id).await?;
            if let Some(hit) = cache.get(scope_collection_id, rev) {
                return Ok(hit);
            }
        }

        let _permit =
            match self.ep_cache {
                Some(cache) => Some(cache.semaphore.acquire().await.map_err(|e| {
                    AppError::Internal(anyhow::anyhow!("EP semaphore closed: {e}"))
                })?),
                None => None,
            };

        // Read `(revision, orderings)` atomically inside one read snapshot and re-check the cache
        // in case another caller populated it while we waited for the semaphore.
        let generation = self.ep_cache.map_or(0, EpScoreCache::allocate_generation);
        let (fresh_rev, orderings) =
            self.repo.load_revision_and_orderings_for_scope(scope_collection_id).await?;
        if let Some(cache) = self.ep_cache
            && let Some(hit) = cache.get(scope_collection_id, fresh_rev)
        {
            return Ok(hit);
        }

        let mut compared_ids: Vec<String> = Vec::new();
        let mut seen: HashSet<&str> = HashSet::new();
        for ord in &orderings {
            for group in ord {
                for id in group {
                    if seen.insert(id.as_str()) {
                        compared_ids.push(id.clone());
                    }
                }
            }
        }
        let scores = run_infer_scores(compared_ids, orderings.clone()).await?;
        let orderings_arc = Arc::new(orderings);
        let scores_arc = Arc::new(scores);
        if let Some(cache) = self.ep_cache {
            cache.insert(
                scope_collection_id,
                fresh_rev,
                generation,
                Arc::clone(&orderings_arc),
                Arc::clone(&scores_arc),
            );
        }
        Ok((orderings_arc, scores_arc))
    }

    /// Loads a collection's rankable members, runs (or reuses cached) EP inference, assigns S-D
    /// quantile tiers for global lists, and computes bar widths.
    pub async fn get_ranked_collection(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> Result<RankedCollectionView, AppError> {
        let mut inputs = self.repo.load_collection_ranking_inputs(slug_or_id, want).await?;
        let is_priority = inputs.is_priority_scope;
        let member_ids: Vec<String> = inputs.members.iter().map(|m| m.id.clone()).collect();

        let scope_id = is_priority.then_some(inputs.collection.id.as_str());
        let (orderings, scope_scores) = self.load_scope_orderings_and_scores(scope_id).await?;
        let relevant_comparison_count = count_relevant_comparisons(&member_ids, &orderings);
        inputs.collection.comparison_count = relevant_comparison_count;

        let scores: HashMap<String, Score> = member_ids
            .iter()
            .map(|id| (id.clone(), scope_scores.get(id).copied().unwrap_or_default()))
            .collect();
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

        let max_abs =
            scores.values().map(|s| s.mean.abs()).filter(|v| v.is_finite()).fold(0.0_f64, f64::max);
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

    /// Loads a ranked collection, computes full-list S/A/B/C/D tier counts across ALL rows, and
    /// applies optional server-side tier (`S`/`A`/`B`/`C`/`D`) and text filters before pagination.
    pub async fn get_filtered_ranked_collection(
        &self,
        slug_or_id: &str,
        want: bool,
        filter: &RankedCollectionFilter<'_>,
    ) -> Result<FilteredRankedCollection, AppError> {
        let mut view = self.get_ranked_collection(slug_or_id, want).await?;
        let full_total = view.rows.len();
        let full_tier_counts = count_row_tiers(&view.rows);

        let target_tier = filter
            .tier
            .map(|t| t.to_ascii_uppercase())
            .filter(|t| matches!(t, 'S' | 'A' | 'B' | 'C' | 'D'));
        let needle = filter.query.map(str::trim).filter(|q| !q.is_empty()).map(str::to_lowercase);

        if target_tier.is_some() || needle.is_some() {
            view.rows.retain(|row| {
                if let Some(t) = target_tier
                    && row.tier != Some(t)
                {
                    return false;
                }
                if let Some(ref q) = needle {
                    let title_hit = row.entry.title.to_lowercase().contains(q);
                    let creator_hit =
                        row.entry.creators.iter().any(|c| c.to_lowercase().contains(q));
                    let year_hit =
                        row.entry.release_year.is_some_and(|y| y.to_string().contains(q));
                    if !title_hit && !creator_hit && !year_hit {
                        return false;
                    }
                }
                true
            });
        }

        Ok(FilteredRankedCollection { view, full_total, full_tier_counts })
    }

    /// Selects the next uncovered cluster together with its selection-priority order
    /// (`[seed, most_confusable, ...]`) from an already-computed [`RankedCollectionView`].
    pub async fn select_uncovered_cluster_prioritized_from_ranked_view<F>(
        &self,
        ranked: &RankedCollectionView,
        filter: &ClusterSelectFilter,
        is_covered: F,
    ) -> Result<PrioritizedCluster, AppError>
    where
        F: Fn(&[CatalogEntry]) -> bool,
    {
        let raw_members: Vec<CatalogEntry> = ranked.rows.iter().map(|r| r.entry.clone()).collect();
        let members = self.filter_cluster_members(raw_members, filter).await?;
        let scores: HashMap<String, Score> =
            ranked.rows.iter().map(|r| (r.entry.id.clone(), r.score)).collect();
        let seed_pool = self.resolve_cluster_seed_pool(filter).await?;
        let excluded_ids = self.resolve_cluster_excluded_ids(filter).await?;
        Ok(pick_uncovered_cluster_prioritized(
            members,
            &scores,
            filter,
            seed_pool.as_deref(),
            &excluded_ids,
            MAX_COVERED_CLUSTER_RETRIES,
            is_covered,
        ))
    }

    /// Computes quality tier letters (`S`..`D`) for a heterogeneous set of entries across their
    /// default kind lists using a single cached global EP pass.
    pub async fn load_tiers_for_entries(&self, entries: &[CatalogEntry]) -> HashMap<String, char> {
        let mut slugs: Vec<&'static str> = entries
            .iter()
            .filter(|e| e.kind != EntryKind::Song)
            .filter_map(|e| e.kind.default_list_slug())
            .collect();
        slugs.sort_unstable();
        slugs.dedup();
        if slugs.is_empty() {
            return HashMap::new();
        }
        let Ok((orderings, scope_scores)) = self.load_scope_orderings_and_scores(None).await else {
            return HashMap::new();
        };
        let mut tiers = HashMap::new();
        for slug in slugs {
            let Ok(inputs) = self.repo.load_collection_ranking_inputs(slug, false).await else {
                continue;
            };
            if inputs.is_priority_scope {
                continue;
            }
            let member_ids: Vec<String> = inputs.members.iter().map(|m| m.id.clone()).collect();
            if count_relevant_comparisons(&member_ids, &orderings) == 0 {
                continue;
            }
            let list_scores: HashMap<String, Score> = member_ids
                .into_iter()
                .map(|id| {
                    let s = scope_scores.get(&id).copied().unwrap_or_default();
                    (id, s)
                })
                .collect();
            for (id, q) in rescale_to_quantiles(&list_scores, N_QUANTILES) {
                tiers.entry(id).or_insert_with(|| tier_label(q));
            }
        }
        tiers
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

        let scope_id = is_priority.then_some(inputs.collection.id.as_str());
        let (orderings, scope_scores) = self.load_scope_orderings_and_scores(scope_id).await?;
        inputs.collection.comparison_count =
            count_relevant_comparisons(&all_member_ids, &orderings);

        let members = self.filter_cluster_members(inputs.members, filter).await?;
        let scores: HashMap<String, Score> = members
            .iter()
            .map(|m| (m.id.clone(), scope_scores.get(&m.id).copied().unwrap_or_default()))
            .collect();
        let seed_pool = self.resolve_cluster_seed_pool(filter).await?;
        let excluded_ids = self.resolve_cluster_excluded_ids(filter).await?;
        let cluster_entries = pick_uncovered_cluster_prioritized(
            members,
            &scores,
            filter,
            seed_pool.as_deref(),
            &excluded_ids,
            max_covered_retries,
            is_covered,
        )
        .entries;

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

    async fn resolve_cluster_excluded_ids(
        &self,
        filter: &ClusterSelectFilter,
    ) -> Result<HashSet<String>, AppError> {
        if filter.excluded_ids.is_empty() {
            return Ok(HashSet::new());
        }
        let resolved = self.repo.resolve_canonical_ids(&filter.excluded_ids).await?;
        Ok(resolved.into_iter().collect())
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

        let Ok((orderings, global_scores)) = self.load_scope_orderings_and_scores(None).await
        else {
            return Vec::new();
        };

        let list_has_relevant_comp: Vec<bool> = eligible_lists
            .iter()
            .map(|inputs| {
                let member_set: HashSet<&str> =
                    inputs.members.iter().map(|m| m.id.as_str()).collect();
                orderings.iter().any(|ord| {
                    ord.iter().flat_map(|g| g.iter()).any(|id| member_set.contains(id.as_str()))
                })
            })
            .collect();

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
        if s.starts_with("spotify:") || is_spotify_web_url(s) {
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

fn is_spotify_web_url(raw: &str) -> bool {
    url::Url::parse(raw).is_ok_and(|parsed| {
        matches!(parsed.scheme(), "http" | "https")
            && parsed.host_str().is_some_and(|h| {
                let norm = h.trim_end_matches('.').to_ascii_lowercase();
                norm == "open.spotify.com" || norm == "play.spotify.com"
            })
    })
}

fn count_row_tiers(rows: &[RankedEntryRow]) -> [usize; 5] {
    let mut counts = [0usize; 5];
    for row in rows {
        match row.tier {
            Some('S') => counts[0] += 1,
            Some('A') => counts[1] += 1,
            Some('B') => counts[2] += 1,
            Some('C') => counts[3] += 1,
            Some('D') => counts[4] += 1,
            _ => {}
        }
    }
    counts
}

fn pick_uncovered_cluster_prioritized<F>(
    members: Vec<CatalogEntry>,
    scores: &HashMap<String, Score>,
    filter: &ClusterSelectFilter,
    seed_pool: Option<&[String]>,
    excluded_ids: &HashSet<String>,
    max_covered_retries: usize,
    is_covered: F,
) -> PrioritizedCluster
where
    F: Fn(&[CatalogEntry]) -> bool,
{
    let all_member_ids: Vec<String> = members.iter().map(|m| m.id.clone()).collect();
    let by_id: HashMap<String, CatalogEntry> =
        members.into_iter().map(|e| (e.id.clone(), e)).collect();

    let max_k = if filter.max_k == 0 { MAX_K_DEFAULT } else { filter.max_k };
    let force_random = seed_pool.is_none()
        && filter.iteration > 0
        && filter.iteration.is_multiple_of(RANDOM_SEED_EVERY);

    let focus_set: HashSet<&str> = if filter.focus_ids.is_empty() {
        HashSet::new()
    } else {
        seed_pool.map_or_else(HashSet::new, |pool| pool.iter().map(String::as_str).collect())
    };

    if !excluded_ids.is_empty() {
        let filtered_ids: Vec<String> = all_member_ids
            .iter()
            .filter(|id| focus_set.contains(id.as_str()) || !excluded_ids.contains(id.as_str()))
            .cloned()
            .collect();
        if filtered_ids.len() >= MIN_K {
            let picked = pick_uncovered_from_pool(
                &filtered_ids,
                &by_id,
                scores,
                max_k,
                force_random,
                seed_pool,
                &focus_set,
                max_covered_retries,
                &is_covered,
            );
            if picked.entries.len() >= MIN_K {
                return picked;
            }
        }
    }

    pick_uncovered_from_pool(
        &all_member_ids,
        &by_id,
        scores,
        max_k,
        force_random,
        seed_pool,
        &focus_set,
        max_covered_retries,
        &is_covered,
    )
}

#[allow(clippy::too_many_arguments)]
fn pick_uncovered_from_pool<F>(
    pool_member_ids: &[String],
    by_id: &HashMap<String, CatalogEntry>,
    scores: &HashMap<String, Score>,
    max_k: usize,
    force_random: bool,
    seed_pool: Option<&[String]>,
    focus_set: &HashSet<&str>,
    max_covered_retries: usize,
    is_covered: &F,
) -> PrioritizedCluster
where
    F: Fn(&[CatalogEntry]) -> bool,
{
    let materialize = |ordered_ids: &[String], priority_ids: Vec<String>| -> PrioritizedCluster {
        let entries = ordered_ids.iter().filter_map(|id| by_id.get(id).cloned()).collect();
        PrioritizedCluster { entries, priority_ids }
    };

    let mut rng = rand::rng();
    let initial = select_cluster_prioritized(
        scores,
        pool_member_ids,
        max_k,
        force_random,
        seed_pool,
        &mut rng,
    );
    let mut chosen = materialize(&initial.ordered_ids, initial.priority_ids);

    if chosen.entries.len() < MIN_K || !is_covered(&chosen.entries) {
        return chosen;
    }

    if !focus_set.is_empty() {
        // In focused mode (especially at k = 2 or 3), a single tie or small score update can
        // leave the exact same opponent as the top confusable neighbor. Accumulate already-seen
        // colliding opponents across retries so we find an uncovered opponent instead of settling
        // prematurely.
        let mut retry_excluded: HashSet<String> = HashSet::new();
        for _ in 0..MAX_FOCUSED_COVERED_RETRIES {
            let mut added_any = false;
            for entry in &chosen.entries {
                if !focus_set.contains(entry.id.as_str()) && retry_excluded.insert(entry.id.clone())
                {
                    added_any = true;
                }
            }
            if !added_any {
                break;
            }
            let remaining_ids: Vec<String> = pool_member_ids
                .iter()
                .filter(|id| {
                    focus_set.contains(id.as_str()) || !retry_excluded.contains(id.as_str())
                })
                .cloned()
                .collect();
            if remaining_ids.len() < MIN_K {
                break;
            }
            let cand_sel = select_cluster_prioritized(
                scores,
                &remaining_ids,
                max_k,
                false,
                seed_pool,
                &mut rng,
            );
            let cand = materialize(&cand_sel.ordered_ids, cand_sel.priority_ids);
            if cand.entries.len() < MIN_K {
                break;
            }
            chosen = cand;
            if !is_covered(&chosen.entries) {
                return chosen;
            }
        }
        return PrioritizedCluster::default();
    }

    if max_covered_retries > 0 {
        for _ in 0..max_covered_retries {
            let cand_sel = select_cluster_prioritized(
                scores,
                pool_member_ids,
                max_k,
                true,
                seed_pool,
                &mut rng,
            );
            let cand = materialize(&cand_sel.ordered_ids, cand_sel.priority_ids);
            if cand.entries.len() >= MIN_K && !is_covered(&cand.entries) {
                return cand;
            }
        }
    }

    PrioritizedCluster::default()
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

    #[tokio::test]
    async fn test_ep_score_cache_reuses_and_invalidates_and_filtered_collection_counts() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let cache = EpScoreCache::new();
        let svc = CatalogService::with_cache(&db, &cache);

        let a = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Movie),
                title: "Stalker".to_owned(),
                creators: vec!["Andrei Tarkovsky".to_owned()],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let b = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Movie),
                title: "Solaris".to_owned(),
                creators: vec!["Andrei Tarkovsky".to_owned()],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        svc.log_consumption(&a.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
        svc.log_consumption(&b.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
        svc.append_comparison(None, &[vec![a.id.clone()], vec![b.id.clone()]], None).await.unwrap();

        let first = svc
            .get_filtered_ranked_collection(
                "movies",
                false,
                &RankedCollectionFilter { tier: Some('S'), query: None },
            )
            .await
            .unwrap();
        assert_eq!(first.full_total, 2);
        assert_eq!(first.full_tier_counts.iter().sum::<usize>(), 2);
        assert_eq!(first.view.rows.len(), 1);
        assert_eq!(first.view.rows[0].entry.id, a.id);

        // Cached hit produces consistent tiers via load_tiers_for_entries
        let tiers = svc.load_tiers_for_entries(&[a.clone(), b.clone()]).await;
        assert_eq!(tiers.get(&a.id), Some(&'S'));

        // Appending a new comparison invalidates the cache immediately
        svc.append_comparison(None, &[vec![b.id.clone()], vec![a.id.clone()]], None).await.unwrap();
        svc.append_comparison(None, &[vec![b.id.clone()], vec![a.id.clone()]], None).await.unwrap();
        let after = svc.get_ranked_collection("movies", false).await.unwrap();
        assert_eq!(after.rows[0].entry.id, b.id);
    }

    #[tokio::test]
    async fn test_focused_cluster_at_k2_retries_covered_opponents_and_respects_excluded_ids() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let mut ids = Vec::new();
        for idx in 0..6 {
            let entry = svc
                .upsert_entry(&UpsertEntryInput {
                    kind: Some(EntryKind::Movie),
                    title: format!("Film {idx}"),
                    ..UpsertEntryInput::default()
                })
                .await
                .unwrap();
            svc.log_consumption(&entry.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
            ids.push(entry.id);
        }

        let focus = ids[0].clone();
        let opp1 = ids[1].clone();
        let opp2 = ids[2].clone();
        // Tie `focus` with `opp1` so `opp1` stays highly confusable with `focus`.
        svc.append_comparison(None, &[vec![focus.clone(), opp1.clone()]], None).await.unwrap();

        let ranked = svc.get_ranked_collection("movies", false).await.unwrap();
        let covered_pair: HashSet<String> = [focus.clone(), opp1.clone()].into_iter().collect();
        let filter = ClusterSelectFilter {
            max_k: 2,
            iteration: 1,
            focus_ids: vec![focus.clone()],
            excluded_ids: vec![opp2.clone()],
            ..ClusterSelectFilter::default()
        };
        let picked = svc
            .select_uncovered_cluster_prioritized_from_ranked_view(&ranked, &filter, |cand| {
                cand.iter().all(|c| covered_pair.contains(&c.id))
            })
            .await
            .unwrap();
        assert_eq!(picked.entries.len(), 2);
        assert_eq!(picked.priority_ids[0], focus);
        assert!(picked.entries.iter().any(|e| e.id == focus));
        assert!(picked.entries.iter().all(|e| e.id != opp1));
        assert!(picked.entries.iter().all(|e| e.id != opp2));

        // When excluded_ids omits every remaining opponent, falls back cleanly instead of stalling.
        let all_others: Vec<String> = ids[1..].to_vec();
        let fallback_filter = ClusterSelectFilter {
            max_k: 2,
            iteration: 2,
            focus_ids: vec![focus.clone()],
            excluded_ids: all_others,
            ..ClusterSelectFilter::default()
        };
        let fallback = svc
            .select_uncovered_cluster_prioritized_from_ranked_view(
                &ranked,
                &fallback_filter,
                |cand| cand.iter().all(|c| covered_pair.contains(&c.id)),
            )
            .await
            .unwrap();
        assert_eq!(fallback.entries.len(), 2);
        assert!(fallback.entries.iter().any(|e| e.id == focus));
        assert!(fallback.entries.iter().all(|e| e.id != opp1));
    }
}
