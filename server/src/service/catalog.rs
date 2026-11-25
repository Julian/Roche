use std::{
    collections::{HashMap, HashSet},
    future::Future,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::Instant,
};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::{Semaphore, watch};

use super::AppError;
use crate::{
    db::{
        ActiveStartedEntry, CatalogBatchInput, CatalogBatchResult, CatalogEntry, CatalogRepo,
        CollectionGeneratorState, CollectionSummary, ComparisonRecord, ConsumptionLogView, Db,
        DiaryKindFilter, EntryDetailView, EntryIdentifier, UpdateEntryInput, UpsertEntryInput,
        WantSearchMatch,
    },
    domain::{CollectionRole, ConsumptionStatus, EntryKind},
    ranking::{
        EpComponent, MAX_K_DEFAULT, MEAN_TIE_EPSILON, MIN_K, N_QUANTILES, RANDOM_SEED_EVERY, Score,
        SelectedCluster, extract_ep_components, rescale_to_quantiles,
        rescale_to_quantiles_with_uncompared, select_cluster_prioritized_refs, solve_ep_component,
        sort_by_score_with_ties, tier_label,
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

/// Prepared state for one interactive ranking round without hydrating unselected collection entries.
#[derive(Debug, Clone)]
pub struct RankSessionSelection {
    pub collection: CollectionSummary,
    pub is_priority_scope: bool,
    pub comparison_count: usize,
    pub total_rows: usize,
    pub focus_row: Option<RankedEntryRow>,
    pub focus_missing: bool,
    pub cluster: PrioritizedCluster,
}

const MAX_COVERED_CLUSTER_RETRIES: usize = 4;
const MAX_FOCUSED_COVERED_RETRIES: usize = 12;
const SPAWN_BLOCKING_EP_THRESHOLD: usize = 150;
const MAX_CACHED_EP_SCOPES: usize = 64;
const MAX_CACHED_EP_COMPONENTS: usize = 512;
const MAX_CONCURRENT_EP_RUNS: usize = 4;

type ScopeRevision = (i64, i64, i64);
type OrderingsArc = Arc<Vec<Vec<Vec<String>>>>;
type ComparedIdsArc = Arc<Vec<String>>;
type ComponentDigest = [u8; 32];
type ComponentScoresArc = Arc<Option<Vec<Score>>>;

#[derive(Debug, Clone)]
struct IndexedEpComponent {
    digest: ComponentDigest,
    component: Arc<EpComponent>,
}

#[derive(Debug, Clone)]
struct ScopeOrderingsSnapshot {
    orderings: OrderingsArc,
    compared_ids: ComparedIdsArc,
    components: Arc<Vec<IndexedEpComponent>>,
    item_to_component: Arc<HashMap<String, usize>>,
}

#[derive(Debug, Clone)]
struct CachedScopeOrderings {
    revision: ScopeRevision,
    generation: u64,
    snapshot: ScopeOrderingsSnapshot,
    last_used: Instant,
}

#[derive(Debug, Clone)]
struct CachedComponentEp {
    scores: ComponentScoresArc,
    last_used: Instant,
}

type InFlightMap =
    Arc<Mutex<HashMap<ComponentDigest, watch::Receiver<Option<ComponentScoresArc>>>>>;

struct InFlightGuard {
    in_flight: InFlightMap,
    digest: ComponentDigest,
    tx: Option<watch::Sender<Option<ComponentScoresArc>>>,
}

impl InFlightGuard {
    fn finish(mut self, scores: ComponentScoresArc) {
        let mut guard = self.in_flight.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        guard.remove(&self.digest);
        drop(guard);
        if let Some(tx) = self.tx.take() {
            let _ = tx.send(Some(scores));
        }
    }
}

impl Drop for InFlightGuard {
    fn drop(&mut self) {
        if let Some(tx) = self.tx.take() {
            let mut guard =
                self.in_flight.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            guard.remove(&self.digest);
            drop(guard);
            drop(tx);
        }
    }
}

enum ComponentClaim {
    Hit(ComponentScoresArc),
    Leader(InFlightGuard),
    Wait(watch::Receiver<Option<ComponentScoresArc>>),
}

/// Bounded in-memory cache of scope-level comparison orderings + precomputed connected-component
/// indices keyed by `(scope_collection_id, (count, max_sync_seq, sum_sync_seq))` and per-component
/// Expectation Propagation scores keyed by 32-byte SHA-256 component digest, with in-flight solve
/// coalescing and a concurrency semaphore.
///
/// Caching EP per connected component ensures submitting or undoing a comparison in one list
/// (e.g. `artists`) never re-solves unrelated components (e.g. hundreds of `albums`).
#[derive(Debug, Clone)]
pub struct EpScoreCache {
    scopes: Arc<Mutex<HashMap<Option<String>, CachedScopeOrderings>>>,
    components: Arc<Mutex<HashMap<ComponentDigest, CachedComponentEp>>>,
    in_flight: InFlightMap,
    semaphore: Arc<Semaphore>,
    next_gen: Arc<AtomicU64>,
    #[cfg(test)]
    component_solves: Arc<AtomicU64>,
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
            scopes: Arc::new(Mutex::new(HashMap::new())),
            components: Arc::new(Mutex::new(HashMap::new())),
            in_flight: Arc::new(Mutex::new(HashMap::new())),
            semaphore: Arc::new(Semaphore::new(MAX_CONCURRENT_EP_RUNS)),
            next_gen: Arc::new(AtomicU64::new(1)),
            #[cfg(test)]
            component_solves: Arc::new(AtomicU64::new(0)),
        }
    }

    #[cfg(test)]
    #[must_use]
    pub fn component_solve_count(&self) -> u64 {
        self.component_solves.load(Ordering::Relaxed)
    }

    fn allocate_generation(&self) -> u64 {
        self.next_gen.fetch_add(1, Ordering::Relaxed)
    }

    fn get_scope(
        &self,
        scope: Option<&str>,
        revision: ScopeRevision,
    ) -> Option<ScopeOrderingsSnapshot> {
        let key = scope.map(ToOwned::to_owned);
        let mut guard = self.scopes.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let hit = guard.get_mut(&key).filter(|e| e.revision == revision).map(|entry| {
            entry.last_used = Instant::now();
            entry.snapshot.clone()
        });
        drop(guard);
        hit
    }

    fn insert_scope(
        &self,
        scope: Option<&str>,
        revision: ScopeRevision,
        generation: u64,
        snapshot: ScopeOrderingsSnapshot,
    ) {
        let mut guard = self.scopes.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
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
            CachedScopeOrderings { revision, generation, snapshot, last_used: Instant::now() },
        );
    }

    fn get_component(&self, digest: ComponentDigest) -> Option<ComponentScoresArc> {
        let mut guard = self.components.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let hit = guard.get_mut(&digest).map(|entry| {
            entry.last_used = Instant::now();
            Arc::clone(&entry.scores)
        });
        drop(guard);
        hit
    }

    fn insert_component(&self, digest: ComponentDigest, scores: ComponentScoresArc) {
        let mut guard = self.components.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if !guard.contains_key(&digest)
            && guard.len() >= MAX_CACHED_EP_COMPONENTS
            && let Some(&oldest_key) = guard.iter().min_by_key(|(_, v)| v.last_used).map(|(k, _)| k)
        {
            guard.remove(&oldest_key);
        }
        guard.insert(digest, CachedComponentEp { scores, last_used: Instant::now() });
    }

    fn claim_or_wait_component(&self, digest: ComponentDigest) -> ComponentClaim {
        if let Some(hit) = self.get_component(digest) {
            return ComponentClaim::Hit(hit);
        }
        let inflight_guard = {
            let mut guard =
                self.in_flight.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if let Some(rx) = guard.get(&digest) {
                return ComponentClaim::Wait(rx.clone());
            }
            let (tx, rx) = watch::channel(None);
            guard.insert(digest, rx);
            drop(guard);
            InFlightGuard { in_flight: Arc::clone(&self.in_flight), digest, tx: Some(tx) }
        };
        // Re-check after claiming leadership in case a previous leader inserted and dropped its
        // in-flight entry right before we acquired `in_flight`.
        if let Some(hit) = self.get_component(digest) {
            inflight_guard.finish(Arc::clone(&hit));
            return ComponentClaim::Hit(hit);
        }
        ComponentClaim::Leader(inflight_guard)
    }
}

fn component_digest(component: &EpComponent) -> ComponentDigest {
    let mut hasher = Sha256::new();
    hasher.update((component.items.len() as u64).to_le_bytes());
    for item in &component.items {
        hasher.update((item.len() as u64).to_le_bytes());
        hasher.update(item.as_bytes());
    }
    hasher.update((component.pairs.len() as u64).to_le_bytes());
    for &(a, b) in &component.pairs {
        hasher.update((a as u64).to_le_bytes());
        hasher.update((b as u64).to_le_bytes());
    }
    hasher.finalize().into()
}

fn build_scope_snapshot(orderings: Vec<Vec<Vec<String>>>) -> ScopeOrderingsSnapshot {
    let compared_ids = collect_compared_ids(&orderings);
    let raw_components = extract_ep_components(&compared_ids, &orderings);
    let mut components = Vec::with_capacity(raw_components.len());
    let mut item_to_component = HashMap::with_capacity(compared_ids.len());
    for comp in raw_components {
        let comp_idx = components.len();
        let digest = component_digest(&comp);
        for item in &comp.items {
            item_to_component.insert(item.clone(), comp_idx);
        }
        components.push(IndexedEpComponent { digest, component: Arc::new(comp) });
    }
    ScopeOrderingsSnapshot {
        orderings: Arc::new(orderings),
        compared_ids: Arc::new(compared_ids),
        components: Arc::new(components),
        item_to_component: Arc::new(item_to_component),
    }
}

/// Filter parameters for server-side tier, kind & search filtering over a ranked collection.
#[derive(Debug, Clone, Default)]
pub struct RankedCollectionFilter<'a> {
    pub tier: Option<char>,
    pub kind: Option<EntryKind>,
    pub query: Option<&'a str>,
}

/// SQL-paginated ranked collection slice containing ONLY the current page's rows while preserving
/// exact full-collection totals, S/A/B/C/D tier distribution, per-kind counts, and ranks.
#[derive(Debug, Clone)]
pub struct RankedCollectionPage {
    pub view: RankedCollectionView,
    pub total_matching: usize,
    pub full_total: usize,
    pub full_tier_counts: [usize; 5],
    pub kind_counts: Vec<(EntryKind, usize)>,
    pub page_num: usize,
    pub total_pages: usize,
}

/// Summary of an entry's rank, list size, and optional quality tier on a global list.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
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

    pub async fn update_entry(
        &self,
        id: &str,
        input: &UpdateEntryInput,
    ) -> Result<EntryDetailView, AppError> {
        Ok(self.repo.update_entry(id, input).await?)
    }

    pub async fn merge_entries(
        &self,
        duplicate_id: &str,
        canonical_id: &str,
    ) -> Result<CatalogEntry, AppError> {
        Ok(self.repo.merge_entries(duplicate_id, canonical_id).await?)
    }

    pub async fn infer_dominant_kind_in_collection(
        &self,
        slug_or_id: &str,
    ) -> Result<Option<EntryKind>, AppError> {
        Ok(self.repo.infer_dominant_kind_in_collection(slug_or_id).await?)
    }

    pub async fn list_diary(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
        limit: usize,
    ) -> Result<Vec<ConsumptionLogView>, AppError> {
        Ok(self.repo.list_diary(year, status, limit).await?)
    }

    pub async fn list_diary_with_kind(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
        kind_filter: DiaryKindFilter,
        limit: usize,
    ) -> Result<Vec<ConsumptionLogView>, AppError> {
        Ok(self.repo.list_diary_with_kind(year, status, kind_filter, limit).await?)
    }

    pub async fn list_diary_page_with_kind(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
        kind_filter: DiaryKindFilter,
        limit: usize,
        offset: usize,
    ) -> Result<Vec<ConsumptionLogView>, AppError> {
        Ok(self.repo.list_diary_page_with_kind(year, status, kind_filter, limit, offset).await?)
    }

    pub async fn list_diary_year_counts_with_kind(
        &self,
        status: Option<ConsumptionStatus>,
        kind_filter: DiaryKindFilter,
    ) -> Result<Vec<(i32, i64)>, AppError> {
        Ok(self.repo.list_diary_year_counts_with_kind(status, kind_filter).await?)
    }

    pub async fn list_diary_kind_summary(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
    ) -> Result<(Vec<(EntryKind, usize)>, bool), AppError> {
        Ok(self.repo.list_diary_kind_summary(year, status).await?)
    }

    pub async fn search_diary_logs(
        &self,
        query: &str,
        limit: usize,
    ) -> Result<Vec<ConsumptionLogView>, AppError> {
        Ok(self.repo.search_diary_logs(query, limit).await?)
    }

    pub async fn list_active_started_entries(
        &self,
        limit: Option<usize>,
    ) -> Result<Vec<ActiveStartedEntry>, AppError> {
        Ok(self.repo.list_active_started_entries(limit).await?)
    }

    pub async fn want_entry_ids_in(
        &self,
        entry_ids: &[String],
    ) -> Result<std::collections::HashSet<String>, AppError> {
        Ok(self.repo.want_entry_ids_in(entry_ids).await?)
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

    async fn load_scope_orderings(
        &self,
        scope_collection_id: Option<&str>,
    ) -> Result<ScopeOrderingsSnapshot, AppError> {
        if let Some(cache) = self.ep_cache {
            let rev = self.repo.comparison_scope_revision(scope_collection_id).await?;
            if let Some(hit) = cache.get_scope(scope_collection_id, rev) {
                return Ok(hit);
            }
        }

        // Read `(revision, orderings)` atomically inside one read snapshot so cached orderings
        // can never pair a newer revision with older orderings (or vice versa).
        let generation = self.ep_cache.map_or(0, EpScoreCache::allocate_generation);
        let (fresh_rev, orderings) =
            self.repo.load_revision_and_orderings_for_scope(scope_collection_id).await?;
        if let Some(cache) = self.ep_cache
            && let Some(hit) = cache.get_scope(scope_collection_id, fresh_rev)
        {
            return Ok(hit);
        }

        let snapshot = if orderings.len() >= SPAWN_BLOCKING_EP_THRESHOLD {
            tokio::task::spawn_blocking(move || build_scope_snapshot(orderings))
                .await
                .map_err(|e| AppError::Internal(anyhow::anyhow!("EP graph build failed: {e}")))?
        } else {
            build_scope_snapshot(orderings)
        };
        if let Some(cache) = self.ep_cache {
            cache.insert_scope(scope_collection_id, fresh_rev, generation, snapshot.clone());
        }
        Ok(snapshot)
    }

    /// Infers EP scores ONLY for connected components intersecting `target_ids`, using the
    /// precomputed revision-level component index on `snapshot` (O(`target_ids.len()`) on cache
    /// hits with zero graph rebuilding), reusing cached per-component solutions across revisions,
    /// and coalescing concurrent misses on the same component.
    async fn infer_scores_for_targets(
        &self,
        snapshot: &ScopeOrderingsSnapshot,
        target_ids: &[String],
    ) -> Result<HashMap<String, Score>, AppError> {
        if target_ids.is_empty() || snapshot.components.is_empty() {
            return Ok(HashMap::new());
        }

        let mut matched_indices: Vec<usize> = Vec::new();
        for id in target_ids {
            if let Some(&comp_idx) = snapshot.item_to_component.get(id) {
                matched_indices.push(comp_idx);
            }
        }
        if matched_indices.is_empty() {
            return Ok(HashMap::new());
        }
        matched_indices.sort_unstable();
        matched_indices.dedup();

        let mut pending: Vec<IndexedEpComponent> =
            matched_indices.into_iter().map(|idx| snapshot.components[idx].clone()).collect();
        let mut resolved: Vec<(IndexedEpComponent, ComponentScoresArc)> =
            Vec::with_capacity(pending.len());

        if let Some(cache) = self.ep_cache {
            while !pending.is_empty() {
                let mut claimed: Vec<(IndexedEpComponent, InFlightGuard)> = Vec::new();
                let mut waited: Vec<(
                    IndexedEpComponent,
                    watch::Receiver<Option<ComponentScoresArc>>,
                )> = Vec::new();
                for indexed in std::mem::take(&mut pending) {
                    match cache.claim_or_wait_component(indexed.digest) {
                        ComponentClaim::Hit(hit) => resolved.push((indexed, hit)),
                        ComponentClaim::Leader(guard) => claimed.push((indexed, guard)),
                        ComponentClaim::Wait(rx) => waited.push((indexed, rx)),
                    }
                }

                if !claimed.is_empty() {
                    let permit = cache.semaphore.acquire().await.map_err(|e| {
                        AppError::Internal(anyhow::anyhow!("EP semaphore closed: {e}"))
                    })?;

                    #[cfg(test)]
                    cache
                        .component_solves
                        .fetch_add(u64::try_from(claimed.len()).unwrap_or(0), Ordering::Relaxed);

                    let mut to_solve = Vec::with_capacity(claimed.len());
                    let mut guards = Vec::with_capacity(claimed.len());
                    for (indexed, guard) in claimed {
                        to_solve.push(indexed);
                        guards.push(guard);
                    }
                    let solved_batch = run_solve_ep_components(to_solve).await?;
                    for ((indexed, scores_opt), guard) in solved_batch.into_iter().zip(guards) {
                        let scores_arc = Arc::new(scores_opt);
                        cache.insert_component(indexed.digest, Arc::clone(&scores_arc));
                        // Deliver directly to waiters so LRU eviction under >512 components or
                        // concurrent cache churn can never drop a freshly solved result.
                        guard.finish(Arc::clone(&scores_arc));
                        resolved.push((indexed, scores_arc));
                    }
                    drop(permit);
                }

                for (indexed, mut rx) in waited {
                    if let Ok(val) = rx.wait_for(Option::is_some).await
                        && let Some(hit) = val.clone()
                    {
                        resolved.push((indexed, hit));
                    } else if let Some(hit) = cache.get_component(indexed.digest) {
                        resolved.push((indexed, hit));
                    } else {
                        // Leader exited without populating (cancelled/errored); retry and claim.
                        pending.push(indexed);
                    }
                }
            }
        } else {
            let solved_batch = run_solve_ep_components(pending).await?;
            for (indexed, scores_opt) in solved_batch {
                resolved.push((indexed, Arc::new(scores_opt)));
            }
        }

        let target_set: HashSet<&str> = target_ids.iter().map(String::as_str).collect();
        let mut out: HashMap<String, Score> = HashMap::with_capacity(target_set.len());
        for (indexed, scores_arc) in resolved {
            if let Some(ref comp_scores) = *scores_arc
                && comp_scores.len() == indexed.component.items.len()
            {
                for (id, &score) in indexed.component.items.iter().zip(comp_scores.iter()) {
                    if target_set.contains(id.as_str()) {
                        out.insert(id.clone(), score);
                    }
                }
            }
        }
        Ok(out)
    }

    /// Loads a collection's rankable members, runs (or reuses cached) EP inference for ONLY the
    /// connected components touching this collection, assigns S-D quantile tiers for global lists,
    /// and computes bar widths.
    pub async fn get_ranked_collection(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> Result<RankedCollectionView, AppError> {
        let mut inputs = self.repo.load_collection_ranking_inputs(slug_or_id, want).await?;
        let is_priority = inputs.is_priority_scope;
        let member_ids: Vec<String> = inputs.members.iter().map(|m| m.id.clone()).collect();
        let member_set: HashSet<&str> = member_ids.iter().map(String::as_str).collect();

        let scope_id = is_priority.then_some(inputs.collection.id.as_str());
        let scope_snap = self.load_scope_orderings(scope_id).await?;
        let relevant_comparison_count =
            count_relevant_comparisons_with_set(&member_set, &scope_snap.orderings);
        inputs.collection.comparison_count = relevant_comparison_count;

        let compared_in_col: Vec<String> = scope_snap
            .compared_ids
            .iter()
            .filter(|id| member_set.contains(id.as_str()))
            .cloned()
            .collect();
        let scope_scores = self.infer_scores_for_targets(&scope_snap, &compared_in_col).await?;

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
            cmp_entry_title_id_ascii,
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

    /// Loads ONLY the requested page of a ranked collection via banded SQL pagination, never
    /// hydrating thousands of uncompared rows outside the current page and solving EP ONLY for
    /// connected components touching this collection.
    ///
    /// When `clamp_page` is `true` (Web SSR), out-of-range page numbers clamp to `1..=total_pages`.
    /// When `clamp_page` is `false` (REST API), requesting `page > total_pages` returns empty rows
    /// so clients paging until `rows.is_empty()` terminate cleanly.
    #[allow(clippy::too_many_lines)]
    pub async fn get_ranked_collection_page(
        &self,
        slug_or_id: &str,
        want: bool,
        filter: &RankedCollectionFilter<'_>,
        requested_page: usize,
        page_size: usize,
        clamp_page: bool,
    ) -> Result<RankedCollectionPage, AppError> {
        let safe_page_size = page_size.clamp(1, 2_000);
        let (is_priority, scope_id) = self.repo.resolve_ranking_scope(slug_or_id, want).await?;
        let scope_snap = self.load_scope_orderings(scope_id.as_deref()).await?;

        let mut meta = self
            .repo
            .load_collection_page_meta(slug_or_id, want, &scope_snap.compared_ids, &[])
            .await?;

        let full_total = meta.total_count;
        let compared_ids_in_col: Vec<String> =
            meta.compared_members.iter().map(|m| m.id.clone()).collect();
        let relevant_comparison_count =
            count_relevant_comparisons(&compared_ids_in_col, &scope_snap.orderings);
        meta.collection.comparison_count = relevant_comparison_count;

        let scope_scores = self.infer_scores_for_targets(&scope_snap, &compared_ids_in_col).await?;

        let compared_scores: HashMap<String, Score> = meta
            .compared_members
            .iter()
            .map(|m| (m.id.clone(), scope_scores.get(&m.id).copied().unwrap_or_default()))
            .collect();
        let uncompared_count = full_total.saturating_sub(meta.compared_members.len());

        let (compared_tiers, uncompared_tier) = if !is_priority && relevant_comparison_count > 0 {
            let q = rescale_to_quantiles_with_uncompared(
                &compared_scores,
                uncompared_count,
                N_QUANTILES,
            );
            let mapped =
                q.compared.into_iter().map(|(id, bucket)| (id, tier_label(bucket))).collect();
            (mapped, q.uncompared.map(tier_label))
        } else {
            (HashMap::new(), None)
        };

        let mut full_tier_counts = [0usize; 5];
        for &t in compared_tiers.values() {
            if let Some(idx) = tier_index(t) {
                full_tier_counts[idx] += 1;
            }
        }
        if let Some(t) = uncompared_tier
            && let Some(idx) = tier_index(t)
        {
            full_tier_counts[idx] += uncompared_count;
        }

        let max_abs = compared_scores
            .values()
            .map(|s| s.mean.abs())
            .filter(|v| v.is_finite())
            .fold(0.0_f64, f64::max);
        let scale = if max_abs > 0.0 { max_abs } else { 1.0 };

        // Partition compared members into exact `sort_by_score_with_ties` bands around 0.0.
        let bands = split_compared_bands_around_zero(
            meta.compared_members,
            &compared_scores,
            uncompared_count > 0,
        );
        let full_zero_band_total = full_total.saturating_sub(bands.above.len() + bands.below.len());

        let target_tier = filter
            .tier
            .map(|t| t.to_ascii_uppercase())
            .filter(|t| matches!(t, 'S' | 'A' | 'B' | 'C' | 'D'));
        let needle = filter
            .query
            .map(str::trim)
            .filter(|q| !q.is_empty())
            .map(|q| q.chars().take(256).collect::<String>().to_lowercase());
        let is_unfiltered = target_tier.is_none() && filter.kind.is_none() && needle.is_none();

        let make_row = |rank: usize, entry: CatalogEntry| -> RankedEntryRow {
            let score = compared_scores.get(&entry.id).copied().unwrap_or_default();
            let tier = compared_tiers.get(&entry.id).copied().or(uncompared_tier);
            let bar_pct = ((score.mean / scale + 1.0) * 50.0).clamp(2.0, 100.0);
            RankedEntryRow { rank, entry, score, tier, bar_pct }
        };

        let mut above_filtered: Vec<RankedEntryRow> = Vec::new();
        for (idx, entry) in bands.above.iter().enumerate() {
            if entry_matches_kind_and_query(entry, filter.kind, needle.as_deref()) {
                let row_tier = compared_tiers.get(&entry.id).copied();
                if target_tier.is_none() || target_tier == row_tier {
                    let rank = if is_unfiltered { idx + 1 } else { above_filtered.len() + 1 };
                    above_filtered.push(make_row(rank, entry.clone()));
                }
            }
        }
        let mut below_candidates: Vec<(usize, CatalogEntry)> = Vec::new();
        for (idx, entry) in bands.below.iter().enumerate() {
            if entry_matches_kind_and_query(entry, filter.kind, needle.as_deref()) {
                let row_tier = compared_tiers.get(&entry.id).copied();
                if target_tier.is_none() || target_tier == row_tier {
                    below_candidates.push((idx, entry.clone()));
                }
            }
        }

        let uncompared_matches_tier =
            target_tier.is_none() || (target_tier == uncompared_tier && target_tier.is_some());

        // Determine zero-band matching count & SQL exclusion set directly in SQL (no Rust subtraction).
        let mut exclude_ids: HashSet<String> =
            HashSet::with_capacity(bands.above.len() + bands.below.len());
        for e in &bands.above {
            exclude_ids.insert(e.id.clone());
        }
        for e in &bands.below {
            exclude_ids.insert(e.id.clone());
        }

        let (zero_total_matching, near_zero_only_rows) = if uncompared_count == 0 {
            (0, Some(Vec::new()))
        } else if uncompared_matches_tier {
            if let Some(t) = target_tier {
                for e in &bands.zero_compared {
                    let row_tier = compared_tiers.get(&e.id).copied().or(uncompared_tier);
                    if row_tier != Some(t) {
                        exclude_ids.insert(e.id.clone());
                    }
                }
            }
            let z_total = if filter.kind.is_none() && needle.is_none() {
                full_total.saturating_sub(exclude_ids.len())
            } else {
                self.repo
                    .count_collection_zero_band_filtered(
                        slug_or_id,
                        want,
                        filter.kind,
                        filter.query,
                        &exclude_ids,
                    )
                    .await?
            };
            (z_total, None)
        } else {
            // Uncompared zero-band entries are excluded by tier filter; sort matching near-zero
            // compared entries BEFORE assigning filtered ordinal ranks.
            let mut matched_entries: Vec<CatalogEntry> = bands
                .zero_compared
                .into_iter()
                .filter(|e| {
                    let row_tier = compared_tiers.get(&e.id).copied().or(uncompared_tier);
                    row_tier == target_tier
                        && entry_matches_kind_and_query(e, filter.kind, needle.as_deref())
                })
                .collect();
            matched_entries.sort_by(cmp_entry_title_id_ascii);
            let above_len_now = above_filtered.len();
            let matched_near_zero: Vec<RankedEntryRow> = matched_entries
                .into_iter()
                .enumerate()
                .map(|(i, e)| make_row(above_len_now + i + 1, e))
                .collect();
            let z_total = matched_near_zero.len();
            (z_total, Some(matched_near_zero))
        };

        let total_matching = above_filtered.len() + zero_total_matching + below_candidates.len();
        let total_pages = total_matching.max(1).div_ceil(safe_page_size);
        let page_num =
            if clamp_page { requested_page.clamp(1, total_pages) } else { requested_page.max(1) };
        let start_idx = page_num.saturating_sub(1).saturating_mul(safe_page_size);
        let end_idx = start_idx.saturating_add(safe_page_size).min(total_matching);

        let mut page_rows: Vec<RankedEntryRow> =
            Vec::with_capacity(end_idx.saturating_sub(start_idx));

        if start_idx < end_idx {
            // 1. Slice from `above_filtered`.
            let above_len = above_filtered.len();
            if start_idx < above_len {
                let take_end = end_idx.min(above_len);
                page_rows.extend(above_filtered.drain(start_idx..take_end));
            }

            // 2. Slice from zero band (either in-memory `near_zero_only_rows` or SQL slice).
            let zero_start_global = above_len;
            let zero_end_global = above_len + zero_total_matching;
            if end_idx > zero_start_global && start_idx < zero_end_global {
                let z_offset = start_idx.saturating_sub(zero_start_global);
                let z_limit = end_idx.min(zero_end_global) - start_idx.max(zero_start_global);
                if let Some(mut near_only) = near_zero_only_rows {
                    let take_end = (z_offset + z_limit).min(near_only.len());
                    if z_offset < take_end {
                        page_rows.extend(near_only.drain(z_offset..take_end));
                    }
                } else if z_limit > 0 {
                    let zero_entries = self
                        .repo
                        .load_collection_zero_band_slice(
                            slug_or_id,
                            want,
                            filter.kind,
                            filter.query,
                            &exclude_ids,
                            z_limit,
                            z_offset,
                        )
                        .await?;
                    for (i, entry) in zero_entries.into_iter().enumerate() {
                        let rank = if is_unfiltered {
                            bands.above.len() + z_offset + i + 1
                        } else {
                            zero_start_global + z_offset + i + 1
                        };
                        page_rows.push(make_row(rank, entry));
                    }
                }
            }

            // 3. Slice from `below_candidates`.
            if end_idx > zero_end_global {
                let b_offset = start_idx.saturating_sub(zero_end_global);
                let b_limit = end_idx - start_idx.max(zero_end_global);
                let take_end = (b_offset + b_limit).min(below_candidates.len());
                if b_offset < take_end {
                    for (i, (global_idx, entry)) in
                        below_candidates.drain(b_offset..take_end).enumerate()
                    {
                        let rank = if is_unfiltered {
                            bands.above.len() + full_zero_band_total + global_idx + 1
                        } else {
                            zero_end_global + b_offset + i + 1
                        };
                        page_rows.push(make_row(rank, entry));
                    }
                }
            }
        }

        Ok(RankedCollectionPage {
            view: RankedCollectionView {
                collection: meta.collection,
                is_priority_scope: is_priority,
                comparison_count: relevant_comparison_count,
                rows: page_rows,
            },
            total_matching,
            full_total,
            full_tier_counts,
            kind_counts: meta.kind_counts,
            page_num,
            total_pages,
        })
    }

    /// Prepares one interactive ranking session round without hydrating thousands of unselected
    /// collection members and without solving unrelated global EP components.
    #[allow(clippy::too_many_arguments, clippy::too_many_lines)]
    pub async fn prepare_rank_session<F, T>(
        &self,
        slug_or_id: &str,
        want: bool,
        focus_id: Option<&str>,
        done: bool,
        random: bool,
        max_rounds: usize,
        round: usize,
        skip_count: usize,
        effective_k: usize,
        excluded_tokens: &HashSet<String>,
        token_of: T,
        is_covered: F,
    ) -> Result<RankSessionSelection, AppError>
    where
        F: Fn(&[String]) -> bool + Send + Sync,
        T: for<'s> Fn(&'s str) -> &'s str + Sync,
    {
        let mut inputs = self.repo.load_collection_rankable_ids(slug_or_id, want).await?;
        let is_priority = inputs.is_priority_scope;
        let total_rows = inputs.member_ids.len();
        let member_set: HashSet<&str> = inputs.member_ids.iter().map(String::as_str).collect();

        let scope_id = is_priority.then_some(inputs.collection.id.as_str());
        let scope = self.load_scope_orderings(scope_id).await?;
        let relevant_comparison_count =
            count_relevant_comparisons_with_set(&member_set, &scope.orderings);
        inputs.collection.comparison_count = relevant_comparison_count;

        let focus_clean = focus_id.map(str::trim).filter(|s| !s.is_empty());
        let focus_in_col = focus_clean.is_some_and(|fid| member_set.contains(fid));
        let initial_focus_missing = focus_clean.is_some() && !focus_in_col;
        let need_cluster_if_focus_ok = !(done
            || initial_focus_missing
            || (focus_in_col && round >= max_rounds)
            || total_rows < MIN_K);

        let compared_member_ids: Vec<String> = if focus_in_col || need_cluster_if_focus_ok {
            scope
                .compared_ids
                .iter()
                .filter(|id| member_set.contains(id.as_str()))
                .cloned()
                .collect()
        } else {
            Vec::new()
        };
        let compared_scores = if focus_in_col || need_cluster_if_focus_ok {
            self.infer_scores_for_targets(&scope, &compared_member_ids).await?
        } else {
            HashMap::new()
        };

        let (focus_row, focus_missing) = if let Some(fid) = focus_clean {
            if focus_in_col {
                let row = self
                    .compute_focus_ranked_row(
                        slug_or_id,
                        want,
                        fid,
                        is_priority,
                        total_rows,
                        relevant_comparison_count,
                        &compared_member_ids,
                        &compared_scores,
                    )
                    .await?;
                let missing = row.is_none();
                (row, missing)
            } else {
                (None, true)
            }
        } else {
            (None, false)
        };

        let cluster = if done
            || focus_missing
            || (focus_row.is_some() && round >= max_rounds)
            || total_rows < MIN_K
        {
            PrioritizedCluster::default()
        } else {
            let focus_ids: Vec<String> = if random || focus_row.is_none() {
                Vec::new()
            } else {
                focus_clean.into_iter().map(ToOwned::to_owned).collect()
            };
            let excluded_ids: HashSet<String> = if excluded_tokens.is_empty() {
                HashSet::new()
            } else {
                inputs
                    .member_ids
                    .iter()
                    .filter(|id| excluded_tokens.contains(token_of(id)))
                    .cloned()
                    .collect()
            };
            let iter_idx = if random {
                RANDOM_SEED_EVERY.saturating_add(skip_count)
            } else {
                round.saturating_add(skip_count)
            };
            let mut acceptor = IdClusterAcceptor { is_covered: &is_covered };
            let selected = pick_uncovered_cluster_async(
                &inputs.member_ids,
                &compared_scores,
                effective_k,
                iter_idx,
                !focus_ids.is_empty(),
                (!focus_ids.is_empty()).then_some(focus_ids.as_slice()),
                &excluded_ids,
                MAX_COVERED_CLUSTER_RETRIES,
                &mut acceptor,
            )
            .await?
            .unwrap_or(SelectedCluster { ordered_ids: Vec::new(), priority_ids: Vec::new() });
            self.materialize_prioritized_cluster(selected).await?
        };

        Ok(RankSessionSelection {
            collection: inputs.collection,
            is_priority_scope: is_priority,
            comparison_count: relevant_comparison_count,
            total_rows,
            focus_row,
            focus_missing,
            cluster,
        })
    }

    #[allow(clippy::too_many_arguments)]
    async fn compute_focus_ranked_row(
        &self,
        slug_or_id: &str,
        want: bool,
        focus_id: &str,
        is_priority: bool,
        total_rows: usize,
        relevant_comparison_count: usize,
        compared_member_ids: &[String],
        compared_scores: &HashMap<String, Score>,
    ) -> Result<Option<RankedEntryRow>, AppError> {
        let focus_loaded = self.repo.load_entries_by_ids(&[focus_id.to_owned()]).await?;
        let Some(focus_entry) = focus_loaded.into_values().next() else {
            return Ok(None);
        };

        let mut titles_by_id = self.repo.load_entry_titles_by_ids(compared_member_ids).await?;
        let mut compared_id_titles: Vec<(String, String)> =
            Vec::with_capacity(compared_member_ids.len());
        let mut full_compared_scores: HashMap<String, Score> =
            HashMap::with_capacity(compared_member_ids.len());
        for id in compared_member_ids {
            if let Some(title) = titles_by_id.remove(id) {
                let sc = compared_scores.get(id).copied().unwrap_or_default();
                full_compared_scores.insert(id.clone(), sc);
                compared_id_titles.push((id.clone(), title));
            }
        }

        let uncompared_count = total_rows.saturating_sub(compared_id_titles.len());
        let tier = if !is_priority && relevant_comparison_count > 0 {
            let q = rescale_to_quantiles_with_uncompared(
                &full_compared_scores,
                uncompared_count,
                N_QUANTILES,
            );
            q.compared.get(focus_id).copied().or(q.uncompared).map(tier_label)
        } else {
            None
        };

        let score = full_compared_scores.get(focus_id).copied().unwrap_or_default();
        let max_abs = full_compared_scores
            .values()
            .map(|s| s.mean.abs())
            .filter(|v| v.is_finite())
            .fold(0.0_f64, f64::max);
        let scale = if max_abs > 0.0 { max_abs } else { 1.0 };
        let bar_pct = ((score.mean / scale + 1.0) * 50.0).clamp(2.0, 100.0);

        let bands = split_compared_bands_around_zero_by(
            compared_id_titles,
            &full_compared_scores,
            uncompared_count > 0,
            |(id, _)| id.as_str(),
            |(_, title)| title.as_str(),
        );
        let zero_band_total = total_rows.saturating_sub(bands.above.len() + bands.below.len());
        let rank = if let Some(idx) = bands.above.iter().position(|(id, _)| id == focus_id) {
            idx + 1
        } else if let Some(idx) = bands.below.iter().position(|(id, _)| id == focus_id) {
            bands.above.len() + zero_band_total + idx + 1
        } else {
            let target_ascii_lower = focus_entry.title.to_ascii_lowercase();
            let all_before = self
                .repo
                .count_collection_entries_alpha_before(
                    slug_or_id,
                    want,
                    &target_ascii_lower,
                    focus_id,
                )
                .await?;
            let non_zero_before = bands
                .above
                .iter()
                .chain(bands.below.iter())
                .filter(|(id, title)| {
                    let lo = title.to_ascii_lowercase();
                    lo < target_ascii_lower || (lo == target_ascii_lower && id.as_str() < focus_id)
                })
                .count();
            (bands.above.len() + all_before.saturating_sub(non_zero_before) + 1)
                .clamp(1, total_rows.max(1))
        };

        Ok(Some(RankedEntryRow { rank, entry: focus_entry, score, tier, bar_pct }))
    }

    async fn materialize_prioritized_cluster(
        &self,
        selected: SelectedCluster,
    ) -> Result<PrioritizedCluster, AppError> {
        if selected.ordered_ids.is_empty() {
            return Ok(PrioritizedCluster::default());
        }
        let mut by_id = self.repo.load_entries_by_ids(&selected.ordered_ids).await?;
        let entries: Vec<CatalogEntry> =
            selected.ordered_ids.iter().filter_map(|id| by_id.remove(id)).collect();
        let entry_set: HashSet<&str> = entries.iter().map(|e| e.id.as_str()).collect();
        let priority_ids: Vec<String> = selected
            .priority_ids
            .into_iter()
            .filter(|id| entry_set.contains(id.as_str()))
            .collect();
        Ok(PrioritizedCluster { entries, priority_ids })
    }

    /// Selects the next uncovered cluster together with its selection-priority order
    /// (`[seed, most_confusable, ...]`) from an already-computed [`RankedCollectionView`].
    #[cfg_attr(not(test), allow(dead_code))]
    pub async fn select_uncovered_cluster_prioritized_from_ranked_view<F>(
        &self,
        ranked: &RankedCollectionView,
        filter: &ClusterSelectFilter,
        is_covered: F,
    ) -> Result<PrioritizedCluster, AppError>
    where
        F: Fn(&[CatalogEntry]) -> bool + Send + Sync,
    {
        let raw_members: Vec<CatalogEntry> = ranked.rows.iter().map(|r| r.entry.clone()).collect();
        let members = self.filter_cluster_members(raw_members, filter).await?;
        let scores: HashMap<String, Score> =
            ranked.rows.iter().map(|r| (r.entry.id.clone(), r.score)).collect();
        let seed_pool = self.resolve_cluster_seed_pool(filter).await?;
        let excluded_ids = self.resolve_cluster_excluded_ids(filter).await?;
        let all_member_ids: Vec<String> = members.iter().map(|m| m.id.clone()).collect();
        let by_id: HashMap<String, CatalogEntry> =
            members.into_iter().map(|e| (e.id.clone(), e)).collect();
        let mut acceptor = MapClusterAcceptor { by_id: &by_id, is_covered: &is_covered };
        Ok(pick_uncovered_cluster_async(
            &all_member_ids,
            &scores,
            filter.max_k,
            filter.iteration,
            !filter.focus_ids.is_empty(),
            seed_pool.as_deref(),
            &excluded_ids,
            MAX_COVERED_CLUSTER_RETRIES,
            &mut acceptor,
        )
        .await?
        .unwrap_or_default())
    }

    /// Computes quality tier letters (`S`..`D`) for a heterogeneous set of entries across their
    /// default kind lists using cached component-level EP inference and uncompared quantile folding
    /// (without hydrating uncompared catalog entries or solving unrelated components).
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
        let Ok(scope) = self.load_scope_orderings(None).await else {
            return HashMap::new();
        };
        let entry_ids_by_slug: HashMap<&'static str, Vec<String>> = {
            let mut map: HashMap<&'static str, Vec<String>> = HashMap::new();
            for e in entries {
                if e.kind != EntryKind::Song
                    && let Some(sl) = e.kind.default_list_slug()
                {
                    map.entry(sl).or_default().push(e.id.clone());
                }
            }
            map
        };
        let mut tiers = HashMap::new();
        for slug in slugs {
            let Some(target_ids) = entry_ids_by_slug.get(slug) else {
                continue;
            };
            let Ok(meta) = self
                .repo
                .load_collection_page_meta(slug, false, &scope.compared_ids, target_ids)
                .await
            else {
                continue;
            };
            if meta.is_priority_scope || meta.total_count == 0 {
                continue;
            }
            let compared_ids: Vec<String> =
                meta.compared_members.iter().map(|m| m.id.clone()).collect();
            if count_relevant_comparisons(&compared_ids, &scope.orderings) == 0 {
                continue;
            }
            let Ok(scope_scores) = self.infer_scores_for_targets(&scope, &compared_ids).await
            else {
                continue;
            };
            let compared_scores: HashMap<String, Score> = compared_ids
                .into_iter()
                .map(|id| {
                    let s = scope_scores.get(&id).copied().unwrap_or_default();
                    (id, s)
                })
                .collect();
            let uncompared_count = meta.total_count.saturating_sub(compared_scores.len());
            let q = rescale_to_quantiles_with_uncompared(
                &compared_scores,
                uncompared_count,
                N_QUANTILES,
            );
            let uncompared_tier = q.uncompared.map(tier_label);
            for eid in target_ids {
                // Only assign a tier to entries that actually belong to the list (e.g. consumed
                // entries for default kind lists), never unconsumed discography items.
                if !meta.present_member_ids.contains(eid) {
                    continue;
                }
                let letter =
                    q.compared.get(eid.as_str()).copied().map(tier_label).or(uncompared_tier);
                if let Some(t) = letter {
                    tiers.entry(eid.clone()).or_insert(t);
                }
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
        F: Fn(&[CatalogEntry]) -> bool + Send + Sync,
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
        F: Fn(&[CatalogEntry]) -> bool + Send + Sync,
    {
        let has_creator_filter =
            filter.by_creator.as_deref().map(str::trim).is_some_and(|s| !s.is_empty());
        if has_creator_filter {
            let mut inputs = self.repo.load_collection_ranking_inputs(slug_or_id, want).await?;
            let is_priority = inputs.is_priority_scope;
            let all_member_ids: Vec<String> = inputs.members.iter().map(|m| m.id.clone()).collect();
            let all_member_set: HashSet<&str> = all_member_ids.iter().map(String::as_str).collect();

            let scope_id = is_priority.then_some(inputs.collection.id.as_str());
            let scope = self.load_scope_orderings(scope_id).await?;
            inputs.collection.comparison_count =
                count_relevant_comparisons_with_set(&all_member_set, &scope.orderings);

            let members = self.filter_cluster_members(inputs.members, filter).await?;
            let filtered_ids: Vec<String> = members.iter().map(|m| m.id.clone()).collect();
            let filtered_set: HashSet<&str> = filtered_ids.iter().map(String::as_str).collect();
            let compared_in_filtered: Vec<String> = scope
                .compared_ids
                .iter()
                .filter(|id| filtered_set.contains(id.as_str()))
                .cloned()
                .collect();
            let scope_scores = self.infer_scores_for_targets(&scope, &compared_in_filtered).await?;
            let seed_pool = self.resolve_cluster_seed_pool(filter).await?;
            let excluded_ids = self.resolve_cluster_excluded_ids(filter).await?;
            let by_id: HashMap<String, CatalogEntry> =
                members.into_iter().map(|e| (e.id.clone(), e)).collect();
            let mut acceptor = MapClusterAcceptor { by_id: &by_id, is_covered: &is_covered };
            let cluster_entries = pick_uncovered_cluster_async(
                &filtered_ids,
                &scope_scores,
                filter.max_k,
                filter.iteration,
                !filter.focus_ids.is_empty(),
                seed_pool.as_deref(),
                &excluded_ids,
                max_covered_retries,
                &mut acceptor,
            )
            .await?
            .unwrap_or_default()
            .entries;
            let scores: HashMap<String, Score> = filtered_ids
                .iter()
                .map(|id| (id.clone(), scope_scores.get(id).copied().unwrap_or_default()))
                .collect();
            return Ok((inputs.collection, cluster_entries, scores));
        }

        // Fast path: load ONLY rankable member IDs and lazily hydrate (with per-call caching)
        // ONLY the small candidate clusters evaluated by `is_covered`.
        let mut inputs = self.repo.load_collection_rankable_ids(slug_or_id, want).await?;
        let is_priority = inputs.is_priority_scope;
        let all_member_set: HashSet<&str> = inputs.member_ids.iter().map(String::as_str).collect();

        let scope_id = is_priority.then_some(inputs.collection.id.as_str());
        let scope = self.load_scope_orderings(scope_id).await?;
        inputs.collection.comparison_count =
            count_relevant_comparisons_with_set(&all_member_set, &scope.orderings);

        let mut member_ids = inputs.member_ids;
        if let Some(raw_parent_id) =
            filter.from_parent_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            let child_ids = self.repo.load_child_entry_ids_for_parent(raw_parent_id).await?;
            member_ids.retain(|id| child_ids.contains(id));
        }
        let filtered_set: HashSet<&str> = member_ids.iter().map(String::as_str).collect();
        let compared_in_filtered: Vec<String> = scope
            .compared_ids
            .iter()
            .filter(|id| filtered_set.contains(id.as_str()))
            .cloned()
            .collect();
        let scope_scores = self.infer_scores_for_targets(&scope, &compared_in_filtered).await?;

        let seed_pool = self.resolve_cluster_seed_pool(filter).await?;
        let excluded_ids = self.resolve_cluster_excluded_ids(filter).await?;
        let mut acceptor = LazyHydrateClusterAcceptor {
            repo: self.repo,
            cache: HashMap::new(),
            is_covered: &is_covered,
        };
        let cluster_entries = pick_uncovered_cluster_async(
            &member_ids,
            &scope_scores,
            filter.max_k,
            filter.iteration,
            !filter.focus_ids.is_empty(),
            seed_pool.as_deref(),
            &excluded_ids,
            max_covered_retries,
            &mut acceptor,
        )
        .await?
        .unwrap_or_default();
        let scores: HashMap<String, Score> = member_ids
            .iter()
            .map(|id| (id.clone(), scope_scores.get(id).copied().unwrap_or_default()))
            .collect();
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
    /// and all parent global lists using a **single** global EP inference pass without hydrating
    /// uncompared list members.
    #[allow(clippy::too_many_lines)]
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
        if candidate_keys.is_empty() {
            return Vec::new();
        }

        let Ok(scope) = self.load_scope_orderings(None).await else {
            return Vec::new();
        };

        let mut seen_col_ids: HashSet<String> = HashSet::new();
        let mut out = Vec::new();
        let target_ascii_lower = detail.entry.title.to_ascii_lowercase();

        for key in candidate_keys {
            // Only show genuine global Lists (`List` / `System`), never `Want`, `Tag`, or `Feed`.
            let Ok(meta) = self
                .repo
                .load_collection_page_meta(
                    &key,
                    false,
                    &scope.compared_ids,
                    std::slice::from_ref(&detail.entry.id),
                )
                .await
            else {
                continue;
            };
            if !matches!(meta.collection.role, CollectionRole::List | CollectionRole::System)
                || !meta.present_member_ids.contains(&detail.entry.id)
                || !seen_col_ids.insert(meta.collection.id.clone())
            {
                continue;
            }

            let total = meta.total_count;
            let compared_ids: Vec<String> =
                meta.compared_members.iter().map(|m| m.id.clone()).collect();
            let has_relevant_comp = count_relevant_comparisons(&compared_ids, &scope.orderings) > 0;
            let Ok(global_scores) = self.infer_scores_for_targets(&scope, &compared_ids).await
            else {
                continue;
            };

            let compared_scores: HashMap<String, Score> = meta
                .compared_members
                .iter()
                .map(|m| (m.id.clone(), global_scores.get(&m.id).copied().unwrap_or_default()))
                .collect();
            let uncompared_count = total.saturating_sub(meta.compared_members.len());
            let tier = if has_relevant_comp {
                let q = rescale_to_quantiles_with_uncompared(
                    &compared_scores,
                    uncompared_count,
                    N_QUANTILES,
                );
                q.compared.get(&detail.entry.id).copied().or(q.uncompared).map(tier_label)
            } else {
                None
            };

            let bands = split_compared_bands_around_zero(
                meta.compared_members,
                &compared_scores,
                uncompared_count > 0,
            );
            let zero_band_total = total.saturating_sub(bands.above.len() + bands.below.len());

            let rank = if let Some(idx) = bands.above.iter().position(|e| e.id == detail.entry.id) {
                idx + 1
            } else if let Some(idx) = bands.below.iter().position(|e| e.id == detail.entry.id) {
                bands.above.len() + zero_band_total + idx + 1
            } else {
                let all_before = self
                    .repo
                    .count_collection_entries_alpha_before(
                        &key,
                        false,
                        &target_ascii_lower,
                        &detail.entry.id,
                    )
                    .await
                    .unwrap_or(0);
                let non_zero_before = bands
                    .above
                    .iter()
                    .chain(bands.below.iter())
                    .filter(|e| {
                        let lo = e.title.to_ascii_lowercase();
                        lo < target_ascii_lower
                            || (lo == target_ascii_lower && e.id < detail.entry.id)
                    })
                    .count();
                bands.above.len() + all_before.saturating_sub(non_zero_before) + 1
            };

            out.push(EntryListAppearance {
                slug: meta.collection.slug.unwrap_or(meta.collection.id),
                title: meta.collection.title,
                rank: rank.clamp(1, total.max(1)),
                total,
                tier,
            });
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

enum ClusterCheckOutcome<T> {
    Accepted(T),
    TooShort,
    Covered(Vec<String>),
}

trait ClusterAcceptor: Send {
    type Output: Send;

    fn evaluate(
        &mut self,
        selected: SelectedCluster,
    ) -> impl Future<Output = Result<ClusterCheckOutcome<Self::Output>, AppError>> + Send;
}

struct IdClusterAcceptor<'a, F> {
    is_covered: &'a F,
}

impl<F> ClusterAcceptor for IdClusterAcceptor<'_, F>
where
    F: Fn(&[String]) -> bool + Send + Sync,
{
    type Output = SelectedCluster;

    fn evaluate(
        &mut self,
        selected: SelectedCluster,
    ) -> impl Future<Output = Result<ClusterCheckOutcome<Self::Output>, AppError>> + Send {
        std::future::ready(if selected.ordered_ids.len() < MIN_K {
            Ok(ClusterCheckOutcome::TooShort)
        } else if (self.is_covered)(&selected.ordered_ids) {
            Ok(ClusterCheckOutcome::Covered(selected.ordered_ids))
        } else {
            Ok(ClusterCheckOutcome::Accepted(selected))
        })
    }
}

struct MapClusterAcceptor<'a, F> {
    by_id: &'a HashMap<String, CatalogEntry>,
    is_covered: &'a F,
}

impl<F> ClusterAcceptor for MapClusterAcceptor<'_, F>
where
    F: Fn(&[CatalogEntry]) -> bool + Send + Sync,
{
    type Output = PrioritizedCluster;

    fn evaluate(
        &mut self,
        selected: SelectedCluster,
    ) -> impl Future<Output = Result<ClusterCheckOutcome<Self::Output>, AppError>> + Send {
        if selected.ordered_ids.len() < MIN_K {
            return std::future::ready(Ok(ClusterCheckOutcome::TooShort));
        }
        let entries: Vec<CatalogEntry> =
            selected.ordered_ids.iter().filter_map(|id| self.by_id.get(id).cloned()).collect();
        std::future::ready(if entries.len() < MIN_K {
            Ok(ClusterCheckOutcome::TooShort)
        } else if (self.is_covered)(&entries) {
            let ids = entries.into_iter().map(|e| e.id).collect();
            Ok(ClusterCheckOutcome::Covered(ids))
        } else {
            Ok(ClusterCheckOutcome::Accepted(PrioritizedCluster {
                entries,
                priority_ids: selected.priority_ids,
            }))
        })
    }
}

struct LazyHydrateClusterAcceptor<'a, F> {
    repo: CatalogRepo<'a>,
    cache: HashMap<String, Option<CatalogEntry>>,
    is_covered: &'a F,
}

impl<F> ClusterAcceptor for LazyHydrateClusterAcceptor<'_, F>
where
    F: Fn(&[CatalogEntry]) -> bool + Send + Sync,
{
    type Output = Vec<CatalogEntry>;

    async fn evaluate(
        &mut self,
        selected: SelectedCluster,
    ) -> Result<ClusterCheckOutcome<Self::Output>, AppError> {
        if selected.ordered_ids.len() < MIN_K {
            return Ok(ClusterCheckOutcome::TooShort);
        }
        let missing: Vec<String> = selected
            .ordered_ids
            .iter()
            .filter(|id| !self.cache.contains_key(id.as_str()))
            .cloned()
            .collect();
        if !missing.is_empty() {
            let mut loaded = self.repo.load_entries_by_ids(&missing).await?;
            for id in missing {
                let entry = loaded.remove(&id);
                self.cache.insert(id, entry);
            }
        }
        let entries: Vec<CatalogEntry> = selected
            .ordered_ids
            .iter()
            .filter_map(|id| self.cache.get(id).and_then(Option::as_ref).cloned())
            .collect();
        if entries.len() < MIN_K {
            Ok(ClusterCheckOutcome::TooShort)
        } else if (self.is_covered)(&entries) {
            let ids = entries.into_iter().map(|e| e.id).collect();
            Ok(ClusterCheckOutcome::Covered(ids))
        } else {
            Ok(ClusterCheckOutcome::Accepted(entries))
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn pick_uncovered_cluster_async<A: ClusterAcceptor>(
    all_member_ids: &[String],
    scores: &HashMap<String, Score>,
    raw_max_k: usize,
    iteration: usize,
    has_focus: bool,
    seed_pool: Option<&[String]>,
    excluded_ids: &HashSet<String>,
    max_covered_retries: usize,
    acceptor: &mut A,
) -> Result<Option<A::Output>, AppError> {
    let max_k = if raw_max_k == 0 { MAX_K_DEFAULT } else { raw_max_k };
    let force_random =
        seed_pool.is_none() && iteration > 0 && iteration.is_multiple_of(RANDOM_SEED_EVERY);

    let focus_set: HashSet<&str> = if has_focus {
        seed_pool.map_or_else(HashSet::new, |pool| pool.iter().map(String::as_str).collect())
    } else {
        HashSet::new()
    };

    if !excluded_ids.is_empty() {
        let filtered_ids: Vec<&str> = all_member_ids
            .iter()
            .map(String::as_str)
            .filter(|id| focus_set.contains(*id) || !excluded_ids.contains(*id))
            .collect();
        if filtered_ids.len() >= MIN_K
            && let Some(picked) = pick_uncovered_from_pool_async(
                &filtered_ids,
                scores,
                max_k,
                force_random,
                seed_pool,
                &focus_set,
                max_covered_retries,
                acceptor,
            )
            .await?
        {
            return Ok(Some(picked));
        }
    }

    let all_refs: Vec<&str> = all_member_ids.iter().map(String::as_str).collect();
    pick_uncovered_from_pool_async(
        &all_refs,
        scores,
        max_k,
        force_random,
        seed_pool,
        &focus_set,
        max_covered_retries,
        acceptor,
    )
    .await
}

#[allow(clippy::too_many_arguments)]
async fn pick_uncovered_from_pool_async<A: ClusterAcceptor>(
    pool_member_ids: &[&str],
    scores: &HashMap<String, Score>,
    max_k: usize,
    force_random: bool,
    seed_pool: Option<&[String]>,
    focus_set: &HashSet<&str>,
    max_covered_retries: usize,
    acceptor: &mut A,
) -> Result<Option<A::Output>, AppError> {
    let initial = {
        let mut rng = rand::rng();
        select_cluster_prioritized_refs(
            scores,
            pool_member_ids,
            max_k,
            force_random,
            seed_pool,
            &mut rng,
        )
    };
    let mut covered_ids = match acceptor.evaluate(initial).await? {
        ClusterCheckOutcome::Accepted(out) => return Ok(Some(out)),
        ClusterCheckOutcome::TooShort => return Ok(None),
        ClusterCheckOutcome::Covered(ids) => ids,
    };

    if !focus_set.is_empty() {
        // In focused mode (especially at k = 2 or 3), a single tie or small score update can
        // leave the exact same opponent as the top confusable neighbor. Accumulate already-seen
        // colliding opponents across retries so we find an uncovered opponent instead of settling
        // prematurely.
        let mut retry_excluded: HashSet<String> = HashSet::new();
        for _ in 0..MAX_FOCUSED_COVERED_RETRIES {
            let mut added_any = false;
            for id in &covered_ids {
                if !focus_set.contains(id.as_str()) && retry_excluded.insert(id.clone()) {
                    added_any = true;
                }
            }
            if !added_any {
                break;
            }
            let remaining_ids: Vec<&str> = pool_member_ids
                .iter()
                .copied()
                .filter(|id| focus_set.contains(*id) || !retry_excluded.contains(*id))
                .collect();
            if remaining_ids.len() < MIN_K {
                break;
            }
            let cand = {
                let mut rng = rand::rng();
                select_cluster_prioritized_refs(
                    scores,
                    &remaining_ids,
                    max_k,
                    false,
                    seed_pool,
                    &mut rng,
                )
            };
            match acceptor.evaluate(cand).await? {
                ClusterCheckOutcome::Accepted(out) => return Ok(Some(out)),
                ClusterCheckOutcome::TooShort => break,
                ClusterCheckOutcome::Covered(ids) => covered_ids = ids,
            }
        }
        return Ok(None);
    }

    if max_covered_retries > 0 {
        for _ in 0..max_covered_retries {
            let cand = {
                let mut rng = rand::rng();
                select_cluster_prioritized_refs(
                    scores,
                    pool_member_ids,
                    max_k,
                    true,
                    seed_pool,
                    &mut rng,
                )
            };
            if let ClusterCheckOutcome::Accepted(out) = acceptor.evaluate(cand).await? {
                return Ok(Some(out));
            }
        }
    }

    Ok(None)
}

fn collect_compared_ids(orderings: &[Vec<Vec<String>>]) -> Vec<String> {
    let mut compared_ids: Vec<String> = Vec::new();
    let mut seen: HashSet<&str> = HashSet::new();
    for ord in orderings {
        for group in ord {
            for id in group {
                if seen.insert(id.as_str()) {
                    compared_ids.push(id.clone());
                }
            }
        }
    }
    compared_ids
}

fn count_relevant_comparisons(member_ids: &[String], orderings: &[Vec<Vec<String>>]) -> usize {
    let member_set: HashSet<&str> = member_ids.iter().map(String::as_str).collect();
    count_relevant_comparisons_with_set(&member_set, orderings)
}

fn count_relevant_comparisons_with_set(
    member_set: &HashSet<&str>,
    orderings: &[Vec<Vec<String>>],
) -> usize {
    orderings
        .iter()
        .filter(|ord| ord.iter().flat_map(|g| g.iter()).any(|id| member_set.contains(id.as_str())))
        .count()
}

async fn run_solve_ep_components(
    components: Vec<IndexedEpComponent>,
) -> Result<Vec<(IndexedEpComponent, Option<Vec<Score>>)>, AppError> {
    let total_items: usize = components.iter().map(|c| c.component.items.len()).sum();
    let total_pairs: usize = components.iter().map(|c| c.component.pairs.len()).sum();
    if total_items >= SPAWN_BLOCKING_EP_THRESHOLD || total_pairs >= SPAWN_BLOCKING_EP_THRESHOLD {
        tokio::task::spawn_blocking(move || {
            components
                .into_iter()
                .map(|indexed| {
                    let solved = solve_ep_component(&indexed.component);
                    (indexed, solved)
                })
                .collect()
        })
        .await
        .map_err(|e| AppError::Internal(anyhow::anyhow!("EP inference task failed: {e}")))
    } else {
        Ok(components
            .into_iter()
            .map(|indexed| {
                let solved = solve_ep_component(&indexed.component);
                (indexed, solved)
            })
            .collect())
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

const fn tier_index(tier: char) -> Option<usize> {
    match tier {
        'S' => Some(0),
        'A' => Some(1),
        'B' => Some(2),
        'C' => Some(3),
        'D' => Some(4),
        _ => None,
    }
}

struct ZeroSplitBands<T = CatalogEntry> {
    above: Vec<T>,
    zero_compared: Vec<T>,
    below: Vec<T>,
}

enum BandItem<T> {
    Compared(T),
    UncomparedMarker,
}

/// Partitions compared members into strictly-above, tied-with-0.0, and strictly-below bands
/// matching the exact descending tie-chaining behavior of [`sort_by_score_with_ties`].
fn split_compared_bands_around_zero(
    compared_members: Vec<CatalogEntry>,
    scores: &HashMap<String, Score>,
    has_uncompared: bool,
) -> ZeroSplitBands<CatalogEntry> {
    split_compared_bands_around_zero_by(
        compared_members,
        scores,
        has_uncompared,
        |e| e.id.as_str(),
        |e| e.title.as_str(),
    )
}

fn split_compared_bands_around_zero_by<T, IdFn, TitleFn>(
    mut compared_members: Vec<T>,
    scores: &HashMap<String, Score>,
    has_uncompared: bool,
    id_of: IdFn,
    title_of: TitleFn,
) -> ZeroSplitBands<T>
where
    IdFn: Fn(&T) -> &str,
    TitleFn: Fn(&T) -> &str,
{
    let cmp_title_id = |a: &T, b: &T| {
        title_of(a)
            .to_ascii_lowercase()
            .cmp(&title_of(b).to_ascii_lowercase())
            .then_with(|| id_of(a).cmp(id_of(b)))
    };

    if !has_uncompared {
        sort_by_score_with_ties(&mut compared_members, scores, &id_of, cmp_title_id);
        return ZeroSplitBands {
            above: compared_members,
            zero_compared: Vec::new(),
            below: Vec::new(),
        };
    }

    let mean_of = |item: &BandItem<T>| match item {
        BandItem::Compared(e) => scores.get(id_of(e)).copied().unwrap_or_default().mean,
        BandItem::UncomparedMarker => 0.0,
    };

    compared_members.sort_by(|a, b| {
        let ma = scores.get(id_of(a)).copied().unwrap_or_default().mean;
        let mb = scores.get(id_of(b)).copied().unwrap_or_default().mean;
        mb.total_cmp(&ma).then_with(|| cmp_title_id(a, b))
    });

    let mut seq: Vec<BandItem<T>> = Vec::with_capacity(compared_members.len() + 1);
    let mut inserted_marker = false;
    for entry in compared_members {
        let m = scores.get(id_of(&entry)).copied().unwrap_or_default().mean;
        if !inserted_marker && m.total_cmp(&0.0) != std::cmp::Ordering::Greater {
            seq.push(BandItem::UncomparedMarker);
            inserted_marker = true;
        }
        seq.push(BandItem::Compared(entry));
    }
    if !inserted_marker {
        seq.push(BandItem::UncomparedMarker);
    }

    let mut group_sizes: Vec<(usize, bool)> = Vec::new();
    let mut tie_start = 0usize;
    while tie_start < seq.len() {
        let base_mean = mean_of(&seq[tie_start]);
        let mut tie_end = tie_start + 1;
        let mut has_marker = matches!(seq[tie_start], BandItem::UncomparedMarker);
        while tie_end < seq.len() && (mean_of(&seq[tie_end]) - base_mean).abs() <= MEAN_TIE_EPSILON
        {
            if matches!(seq[tie_end], BandItem::UncomparedMarker) {
                has_marker = true;
            }
            tie_end += 1;
        }
        group_sizes.push((tie_end - tie_start, has_marker));
        tie_start = tie_end;
    }

    let mut above = Vec::new();
    let mut zero_compared = Vec::new();
    let mut below = Vec::new();
    let mut seen_marker_group = false;
    let mut seq_iter = seq.into_iter();
    for (count, has_marker) in group_sizes {
        let mut group_entries: Vec<T> = Vec::with_capacity(count);
        for item in seq_iter.by_ref().take(count) {
            if let BandItem::Compared(entry) = item {
                group_entries.push(entry);
            }
        }
        group_entries.sort_by(cmp_title_id);
        if has_marker {
            zero_compared.extend(group_entries);
            seen_marker_group = true;
        } else if seen_marker_group {
            below.extend(group_entries);
        } else {
            above.extend(group_entries);
        }
    }

    ZeroSplitBands { above, zero_compared, below }
}

fn cmp_entry_title_id_ascii(a: &CatalogEntry, b: &CatalogEntry) -> std::cmp::Ordering {
    a.title.to_ascii_lowercase().cmp(&b.title.to_ascii_lowercase()).then_with(|| a.id.cmp(&b.id))
}

fn entry_matches_kind_and_query(
    entry: &CatalogEntry,
    kind: Option<EntryKind>,
    lower_needle: Option<&str>,
) -> bool {
    if let Some(k) = kind
        && entry.kind != k
    {
        return false;
    }
    if let Some(q) = lower_needle {
        let title_hit = entry.title.to_lowercase().contains(q);
        let creator_hit = entry.creators.iter().any(|c| c.to_lowercase().contains(q));
        let year_hit = entry.release_year.is_some_and(|y| y.to_string().contains(q));
        if !title_hit && !creator_hit && !year_hit {
            return false;
        }
    }
    true
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
            .get_ranked_collection_page(
                "movies",
                false,
                &RankedCollectionFilter { tier: Some('S'), kind: None, query: None },
                1,
                50,
                true,
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

    #[tokio::test]
    async fn test_ranked_collection_page_matches_full_ranking_with_kind_and_tier_filters() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let mut song_ids = Vec::new();
        for i in 1..=12 {
            let s = svc
                .upsert_entry(&UpsertEntryInput {
                    kind: Some(EntryKind::Song),
                    title: format!("Track {i:02}"),
                    creators: vec!["The Band".to_owned()],
                    ..UpsertEntryInput::default()
                })
                .await
                .unwrap();
            svc.log_consumption(&s.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
            svc.add_collection_entry(crate::db::STARRED_COLLECTION_ID, &s.id, None).await.unwrap();
            song_ids.push(s.id);
        }
        let book = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Book),
                title: "Dune".to_owned(),
                creators: vec!["Frank Herbert".to_owned()],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        svc.log_consumption(&book.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
        svc.add_collection_entry(crate::db::STARRED_COLLECTION_ID, &book.id, None).await.unwrap();

        // Compare two songs so one sits in above-zero, one in below-zero, and 10 in zero-band.
        svc.append_comparison(None, &[vec![song_ids[10].clone()], vec![song_ids[1].clone()]], None)
            .await
            .unwrap();

        let full = svc.get_ranked_collection("songs", false).await.unwrap();
        let mut collected_ids = Vec::new();
        for p in 1..=3 {
            let page = svc
                .get_ranked_collection_page(
                    "songs",
                    false,
                    &RankedCollectionFilter::default(),
                    p,
                    5,
                    true,
                )
                .await
                .unwrap();
            assert_eq!(page.full_total, 12);
            assert_eq!(page.total_matching, 12);
            assert_eq!(page.total_pages, 3);
            for row in &page.view.rows {
                let full_row = full.rows.iter().find(|r| r.entry.id == row.entry.id).unwrap();
                assert_eq!(row.rank, full_row.rank);
                assert_eq!(row.tier, full_row.tier);
                collected_ids.push(row.entry.id.clone());
            }
        }
        let full_ids: Vec<String> = full.rows.iter().map(|r| r.entry.id.clone()).collect();
        assert_eq!(collected_ids, full_ids);

        // Starred has mixed kinds (12 songs + 1 book); kind filter isolates Book cleanly.
        let starred_books = svc
            .get_ranked_collection_page(
                "starred",
                false,
                &RankedCollectionFilter { tier: None, kind: Some(EntryKind::Book), query: None },
                1,
                50,
                true,
            )
            .await
            .unwrap();
        assert_eq!(starred_books.full_total, 13);
        assert_eq!(starred_books.total_matching, 1);
        assert_eq!(starred_books.view.rows[0].entry.id, book.id);
        assert_eq!(starred_books.kind_counts.len(), 2);
    }

    #[tokio::test]
    async fn test_diary_kind_filter_hides_songs_by_default_and_supports_all_and_specific_kinds() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let song = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Song),
                title: "Paranoid Android".to_owned(),
                creators: vec!["Radiohead".to_owned()],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let book = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Book),
                title: "Solaris".to_owned(),
                creators: vec!["Stanislaw Lem".to_owned()],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        svc.log_consumption(&song.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
        svc.log_consumption(&book.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

        // Default diary excludes individual song plays.
        let default_rows = svc.list_diary(None, None, 50).await.unwrap();
        assert_eq!(default_rows.len(), 1);
        assert_eq!(default_rows[0].entry.id, book.id);

        // Songs filter returns only song plays.
        let song_rows = svc
            .list_diary_with_kind(None, None, DiaryKindFilter::Only(EntryKind::Song), 50)
            .await
            .unwrap();
        assert_eq!(song_rows.len(), 1);
        assert_eq!(song_rows[0].entry.id, song.id);

        // All filter returns both.
        let all_rows =
            svc.list_diary_with_kind(None, None, DiaryKindFilter::All, 50).await.unwrap();
        assert_eq!(all_rows.len(), 2);

        // Kind summary reports non-song counts + has_songs=true.
        let (non_song_counts, has_songs) = svc.list_diary_kind_summary(None, None).await.unwrap();
        assert_eq!(non_song_counts, vec![(EntryKind::Book, 1)]);
        assert!(has_songs);
    }

    #[tokio::test]
    async fn test_find_artist_ids_by_names_resolves_ascii_and_unicode_artists() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let ascii = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Artist),
                title: "Radiohead".to_owned(),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let unicode = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Artist),
                title: "Édith Piaf".to_owned(),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let custom_unicode = svc
            .upsert_entry(&UpsertEntryInput {
                id: Some("custom-non-det-artist-id".to_owned()),
                kind: Some(EntryKind::Artist),
                title: "Ólafur Arnalds".to_owned(),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();

        let found = svc
            .find_artist_ids_by_names(&[
                "radiohead".to_owned(),
                "édith piaf".to_owned(),
                "ólafur arnalds".to_owned(),
            ])
            .await
            .unwrap();
        assert_eq!(found.get("radiohead"), Some(&ascii.id));
        assert_eq!(found.get("édith piaf"), Some(&unicode.id));
        assert_eq!(found.get("ólafur arnalds"), Some(&custom_unicode.id));
    }

    #[tokio::test]
    async fn test_entry_detail_caps_log_history_while_preserving_exact_total_log_count() {
        use chrono::TimeZone as _;

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let song = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Song),
                title: "Repeat Track".to_owned(),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let base = chrono::Utc.with_ymd_and_hms(2026, 1, 1, 0, 0, 0).unwrap();
        for i in 0..120 {
            let ts = base + chrono::Duration::minutes(i);
            svc.log_consumption(&song.id, ConsumptionStatus::Consumed, None, Some(ts))
                .await
                .unwrap();
        }
        let detail = svc.get_entry_detail(&song.id).await.unwrap().unwrap();
        assert_eq!(detail.total_log_count, 120);
        assert_eq!(detail.log_history.len(), 100);
        assert!(detail.has_consumed_log);
    }

    #[tokio::test]
    async fn test_load_tiers_for_entries_excludes_unconsumed_entries() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let a1 = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Album),
                title: "Kid A".to_owned(),
                creators: vec!["Radiohead".to_owned()],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let a2 = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Album),
                title: "OK Computer".to_owned(),
                creators: vec!["Radiohead".to_owned()],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let unconsumed = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Album),
                title: "Pablo Honey".to_owned(),
                creators: vec!["Radiohead".to_owned()],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        svc.log_consumption(&a1.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
        svc.log_consumption(&a2.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
        svc.append_comparison(None, &[vec![a2.id.clone()], vec![a1.id.clone()]], None)
            .await
            .unwrap();

        let tiers = svc.load_tiers_for_entries(&[a1.clone(), a2.clone(), unconsumed.clone()]).await;
        assert!(tiers.contains_key(&a1.id));
        assert!(tiers.contains_key(&a2.id));
        assert!(!tiers.contains_key(&unconsumed.id));
    }

    #[tokio::test]
    async fn test_ranked_collection_page_filtering_json_creators_monotonic_ranks_and_unclamped_page()
     {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        let mut ids = Vec::new();
        for (idx, (title, creator)) in [
            ("Alpha", "Frank Herbert"),
            ("Beta", "Ursula K. Le Guin"),
            ("Gamma", "Frank Herbert"),
            ("Delta", "Frank Herbert"),
            ("Epsilon", "Stanislaw Lem"),
        ]
        .into_iter()
        .enumerate()
        {
            let e = svc
                .upsert_entry(&UpsertEntryInput {
                    kind: Some(EntryKind::Book),
                    title: title.to_owned(),
                    creators: vec![creator.to_owned()],
                    release_year: Some(1960 + i32::try_from(idx).unwrap()),
                    ..UpsertEntryInput::default()
                })
                .await
                .unwrap();
            svc.log_consumption(&e.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
            ids.push(e.id);
        }
        // Alpha > Epsilon puts Alpha in above-band, Beta/Gamma/Delta in zero-band, Epsilon in below-band.
        svc.append_comparison(None, &[vec![ids[0].clone()], vec![ids[4].clone()]], None)
            .await
            .unwrap();

        // Searching raw JSON punctuation must NOT match creators_json syntax.
        let bracket_search = svc
            .get_ranked_collection_page(
                "books",
                false,
                &RankedCollectionFilter { tier: None, kind: None, query: Some("[") },
                1,
                50,
                true,
            )
            .await
            .unwrap();
        assert_eq!(bracket_search.total_matching, 0);
        assert!(bracket_search.view.rows.is_empty());

        let quote_search = svc
            .get_ranked_collection_page(
                "books",
                false,
                &RankedCollectionFilter { tier: None, kind: None, query: Some("\"") },
                1,
                50,
                true,
            )
            .await
            .unwrap();
        assert_eq!(quote_search.total_matching, 0);

        // Filtered query spanning above + zero bands produces strictly monotonic ordinal ranks 1..=3.
        let herbert = svc
            .get_ranked_collection_page(
                "books",
                false,
                &RankedCollectionFilter { tier: None, kind: None, query: Some("herbert") },
                1,
                50,
                true,
            )
            .await
            .unwrap();
        assert_eq!(herbert.total_matching, 3);
        let ranks: Vec<usize> = herbert.view.rows.iter().map(|r| r.rank).collect();
        assert_eq!(ranks, vec![1, 2, 3]);

        // Unclamped pagination (REST API mode) returns empty rows for out-of-range page numbers.
        let past_end = svc
            .get_ranked_collection_page(
                "books",
                false,
                &RankedCollectionFilter::default(),
                99,
                2,
                false,
            )
            .await
            .unwrap();
        assert_eq!(past_end.page_num, 99);
        assert_eq!(past_end.total_pages, 3);
        assert!(past_end.view.rows.is_empty());

        // Large exclusion set (>450 IDs) exercises the `json_each` SQL exclusion path cleanly.
        let mut large_exclude: HashSet<String> =
            (0..500).map(|i| format!("dummy-{i:04}")).collect();
        large_exclude.insert(ids[0].clone());
        large_exclude.insert(ids[4].clone());
        let repo = db.catalog();
        let cnt = repo
            .count_collection_zero_band_filtered("books", false, None, None, &large_exclude)
            .await
            .unwrap();
        assert_eq!(cnt, 3);
        let slice = repo
            .load_collection_zero_band_slice("books", false, None, None, &large_exclude, 10, 0)
            .await
            .unwrap();
        assert_eq!(slice.len(), 3);
    }

    #[tokio::test]
    async fn test_diary_validation_search_union_and_has_consumed_log_beyond_cap() {
        use chrono::TimeZone as _;

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let svc = CatalogService::new(&db);

        // L5: container kinds rejected in DiaryKindFilter.
        assert!(DiaryKindFilter::parse_optional(Some("artist")).is_err());
        assert!(DiaryKindFilter::parse_optional(Some("podcast")).is_err());

        // H5: i32::MAX year returns InvalidInput rather than overflowing.
        assert!(svc.list_diary_kind_summary(Some(i32::MAX), None).await.is_err());
        assert!(
            svc.list_diary_page_with_kind(
                Some(i32::MAX),
                None,
                DiaryKindFilter::DefaultNonSong,
                10,
                0,
            )
            .await
            .is_err()
        );

        let book = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Book),
                title: "Solaris".to_owned(),
                creators: vec!["Stanislaw Lem".to_owned()],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let ts_2025 = chrono::Utc.with_ymd_and_hms(2025, 6, 1, 12, 0, 0).unwrap();
        svc.log_consumption(&book.id, ConsumptionStatus::Consumed, Some("oceanic"), Some(ts_2025))
            .await
            .unwrap();

        // C1: year + status filter works cleanly.
        let filtered = svc
            .list_diary_page_with_kind(
                Some(2025),
                Some(ConsumptionStatus::Consumed),
                DiaryKindFilter::DefaultNonSong,
                10,
                0,
            )
            .await
            .unwrap();
        assert_eq!(filtered.len(), 1);
        let (counts, has_songs) = svc
            .list_diary_kind_summary(Some(2025), Some(ConsumptionStatus::Consumed))
            .await
            .unwrap();
        assert_eq!(counts, vec![(EntryKind::Book, 1)]);
        assert!(!has_songs);

        // H4: search_diary_logs matches both title (FTS) and notes (partial index UNION).
        let by_title = svc.search_diary_logs("Solaris", 10).await.unwrap();
        assert_eq!(by_title.len(), 1);
        let by_note = svc.search_diary_logs("oceanic", 10).await.unwrap();
        assert_eq!(by_note.len(), 1);

        // L6: has_consumed_log stays true even when >100 newer non-consumed logs exist.
        for i in 1..=105 {
            let ts = ts_2025 + chrono::Duration::hours(i);
            svc.log_consumption(&book.id, ConsumptionStatus::Started, None, Some(ts))
                .await
                .unwrap();
        }
        let detail = svc.get_entry_detail(&book.id).await.unwrap().unwrap();
        assert_eq!(detail.total_log_count, 106);
        assert_eq!(detail.log_history.len(), 100);
        assert!(detail.has_consumed_log);
        assert!(detail.log_history.iter().all(|l| l.status == ConsumptionStatus::Started));
    }

    #[test]
    fn test_split_compared_bands_matches_sort_by_score_with_ties_on_tie_chain_edges() {
        let make_entry = |id: &str, title: &str| CatalogEntry {
            id: id.to_owned(),
            kind: EntryKind::Book,
            title: title.to_owned(),
            creators: Vec::new(),
            url: None,
            published_at: None,
            release_year: None,
            summary: None,
            thumbnail_url: None,
            metadata: serde_json::json!({}),
            identifiers: Vec::new(),
            merged_into_id: None,
            created_at: chrono::Utc::now(),
            updated_at: chrono::Utc::now(),
        };

        // Case 1: (+1.4e-5, +0.8e-5) tie together strictly ABOVE uncompared (0.0).
        let entry_a = make_entry("a", "Zeta");
        let entry_b = make_entry("b", "Alpha");
        let uncomp_1 = make_entry("u", "Middle");
        let mut scores = HashMap::new();
        scores.insert("a".to_owned(), Score { mean: 1.4e-5, variance: 1.0 });
        scores.insert("b".to_owned(), Score { mean: 0.8e-5, variance: 1.0 });

        let mut full = vec![entry_a.clone(), entry_b.clone(), uncomp_1.clone()];
        sort_by_score_with_ties(&mut full, &scores, |e| e.id.as_str(), cmp_entry_title_id_ascii);

        let bands = split_compared_bands_around_zero(vec![entry_a, entry_b], &scores, true);
        let mut zero_band = bands.zero_compared;
        zero_band.push(uncomp_1);
        zero_band.sort_by(cmp_entry_title_id_ascii);
        let reconstructed: Vec<String> =
            bands.above.into_iter().chain(zero_band).chain(bands.below).map(|e| e.id).collect();
        let full_ids: Vec<String> = full.into_iter().map(|e| e.id).collect();
        assert_eq!(reconstructed, full_ids);

        // Case 2: (+0.6e-5, 0.0, -0.6e-5) puts +0.6e-5 in zero-band and -0.6e-5 strictly BELOW.
        let pos_entry = make_entry("p", "Zebra");
        let neg_entry = make_entry("n", "Aardvark");
        let uncomp_2 = make_entry("u2", "Mango");
        let mut scores2 = HashMap::new();
        scores2.insert("p".to_owned(), Score { mean: 0.6e-5, variance: 1.0 });
        scores2.insert("n".to_owned(), Score { mean: -0.6e-5, variance: 1.0 });

        let mut full2 = vec![pos_entry.clone(), neg_entry.clone(), uncomp_2.clone()];
        sort_by_score_with_ties(&mut full2, &scores2, |e| e.id.as_str(), cmp_entry_title_id_ascii);

        let bands2 = split_compared_bands_around_zero(vec![pos_entry, neg_entry], &scores2, true);
        assert_eq!(bands2.above.len(), 0);
        assert_eq!(bands2.below.len(), 1);
        assert_eq!(bands2.below[0].id, "n");
        let mut zero_band2 = bands2.zero_compared;
        zero_band2.push(uncomp_2);
        zero_band2.sort_by(cmp_entry_title_id_ascii);
        let reconstructed2: Vec<String> =
            bands2.above.into_iter().chain(zero_band2).chain(bands2.below).map(|e| e.id).collect();
        let full_ids2: Vec<String> = full2.into_iter().map(|e| e.id).collect();
        assert_eq!(reconstructed2, full_ids2);
    }

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_ep_component_cache_isolates_artists_from_albums_and_reuses_on_undo() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let cache = EpScoreCache::new();
        let svc = CatalogService::with_cache(&db, &cache);

        let mut album_ids = Vec::new();
        for title in ["Album One", "Album Two", "Album Three"] {
            let e = svc
                .upsert_entry(&UpsertEntryInput {
                    kind: Some(EntryKind::Album),
                    title: title.to_owned(),
                    creators: vec!["Some Band".to_owned()],
                    ..UpsertEntryInput::default()
                })
                .await
                .unwrap();
            svc.log_consumption(&e.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
            album_ids.push(e.id);
        }
        let mut artist_ids = Vec::new();
        for title in ["Artist Alpha", "Artist Beta", "Artist Gamma", "Artist Delta"] {
            let e = svc
                .upsert_entry(&UpsertEntryInput {
                    kind: Some(EntryKind::Artist),
                    title: title.to_owned(),
                    ..UpsertEntryInput::default()
                })
                .await
                .unwrap();
            svc.log_consumption(&e.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
            artist_ids.push(e.id);
        }

        // Seed an album comparison and an artist comparison in the shared global scope.
        svc.append_comparison(
            None,
            &[vec![album_ids[0].clone()], vec![album_ids[1].clone()], vec![album_ids[2].clone()]],
            None,
        )
        .await
        .unwrap();
        svc.append_comparison(
            None,
            &[vec![artist_ids[0].clone()], vec![artist_ids[1].clone()]],
            None,
        )
        .await
        .unwrap();

        // 1. Loading `albums` solves ONLY the album component (1 solve).
        let albums_before = svc.get_ranked_collection("albums", false).await.unwrap();
        assert_eq!(albums_before.rows[0].entry.id, album_ids[0]);
        assert_eq!(cache.component_solve_count(), 1);

        // 2. Preparing an `artists` rank session solves ONLY the artist component (2 total solves).
        let empty_excluded = HashSet::new();
        let sess1 = svc
            .prepare_rank_session(
                "artists",
                false,
                None,
                false,
                false,
                5,
                0,
                0,
                5,
                &empty_excluded,
                |id| id.char_indices().nth(8).map_or(id, |(idx, _)| &id[..idx]),
                |_| false,
            )
            .await
            .unwrap();
        assert_eq!(sess1.comparison_count, 1);
        assert!(sess1.cluster.entries.len() >= 2);
        assert_eq!(cache.component_solve_count(), 2);

        // 3. Submitting a new artist comparison solves ONLY the new artist component (3 total),
        //    and NEVER re-solves the untouched album component!
        let new_comp = svc
            .append_comparison(
                None,
                &[vec![artist_ids[2].clone()], vec![artist_ids[3].clone()]],
                None,
            )
            .await
            .unwrap();
        let sess2 = svc
            .prepare_rank_session(
                "artists",
                false,
                None,
                false,
                false,
                5,
                1,
                0,
                5,
                &empty_excluded,
                |id| id.char_indices().nth(8).map_or(id, |(idx, _)| &id[..idx]),
                |_| false,
            )
            .await
            .unwrap();
        assert_eq!(sess2.comparison_count, 2);
        assert_eq!(cache.component_solve_count(), 3);

        // 4. Reloading `albums` after the artist comparison hits the cached album component (0 solves!).
        let albums_after = svc.get_ranked_collection("albums", false).await.unwrap();
        assert_eq!(albums_after.rows[0].entry.id, album_ids[0]);
        assert_eq!(cache.component_solve_count(), 3);

        // 5. Undoing the artist comparison immediately hits the cached prior artist component (0 solves!).
        assert!(svc.delete_comparison(&new_comp.id).await.unwrap());
        let sess3 = svc
            .prepare_rank_session(
                "artists",
                false,
                None,
                false,
                false,
                5,
                0,
                0,
                5,
                &empty_excluded,
                |id| id.char_indices().nth(8).map_or(id, |(idx, _)| &id[..idx]),
                |_| false,
            )
            .await
            .unwrap();
        assert_eq!(sess3.comparison_count, 1);
        assert_eq!(cache.component_solve_count(), 3);
    }

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_prepare_rank_session_focus_row_matches_full_ranked_collection() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let cache = EpScoreCache::new();
        let svc = CatalogService::with_cache(&db, &cache);

        let mut ids = Vec::new();
        for title in ["Alpha", "Beta", "Gamma", "Delta", "Epsilon"] {
            let e = svc
                .upsert_entry(&UpsertEntryInput {
                    kind: Some(EntryKind::Book),
                    title: title.to_owned(),
                    ..UpsertEntryInput::default()
                })
                .await
                .unwrap();
            svc.log_consumption(&e.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
            ids.push(e.id);
        }
        // Alpha > Epsilon puts Alpha above-zero, Beta/Gamma/Delta in zero-band, Epsilon below-zero.
        svc.append_comparison(None, &[vec![ids[0].clone()], vec![ids[4].clone()]], None)
            .await
            .unwrap();

        let full = svc.get_ranked_collection("books", false).await.unwrap();
        let empty_excluded = HashSet::new();

        for expected_row in &full.rows {
            let sess = svc
                .prepare_rank_session(
                    "books",
                    false,
                    Some(&expected_row.entry.id),
                    false,
                    false,
                    5,
                    0,
                    0,
                    5,
                    &empty_excluded,
                    |id| id.char_indices().nth(8).map_or(id, |(idx, _)| &id[..idx]),
                    |_| false,
                )
                .await
                .unwrap();
            assert!(!sess.focus_missing);
            assert_eq!(sess.total_rows, full.rows.len());
            assert_eq!(sess.comparison_count, full.comparison_count);
            let actual_row = sess.focus_row.unwrap();
            assert_eq!(actual_row.entry.id, expected_row.entry.id);
            assert_eq!(actual_row.rank, expected_row.rank);
            assert_eq!(actual_row.tier, expected_row.tier);
            assert!((actual_row.score.mean - expected_row.score.mean).abs() <= 1e-12);
            assert!((actual_row.score.variance - expected_row.score.variance).abs() <= 1e-12);
            assert!((actual_row.bar_pct - expected_row.bar_pct).abs() <= 1e-9);
        }

        // Missing focus ID reports `focus_missing = true` and yields an empty settled cluster.
        let missing = svc
            .prepare_rank_session(
                "books",
                false,
                Some("nonexistent-entry-id"),
                false,
                false,
                5,
                0,
                0,
                5,
                &empty_excluded,
                |id| id.char_indices().nth(8).map_or(id, |(idx, _)| &id[..idx]),
                |_| false,
            )
            .await
            .unwrap();
        assert!(missing.focus_missing);
        assert!(missing.focus_row.is_none());
        assert_eq!(missing.cluster.entries.len(), 0);

        // Large `skip` in random mode saturates safely without overflowing.
        let random_max_skip = svc
            .prepare_rank_session(
                "books",
                false,
                None,
                false,
                true,
                5,
                0,
                usize::MAX,
                5,
                &empty_excluded,
                |id| id.char_indices().nth(8).map_or(id, |(idx, _)| &id[..idx]),
                |_| false,
            )
            .await
            .unwrap();
        assert!(random_max_skip.cluster.entries.len() >= 2);

        // `done=true` without focus skips EP inference completely on a fresh cache.
        let fresh_cache = EpScoreCache::new();
        let fresh_svc = CatalogService::with_cache(&db, &fresh_cache);
        let done_sess = fresh_svc
            .prepare_rank_session(
                "books",
                false,
                None,
                true,
                false,
                5,
                0,
                0,
                5,
                &empty_excluded,
                |id| id.char_indices().nth(8).map_or(id, |(idx, _)| &id[..idx]),
                |_| false,
            )
            .await
            .unwrap();
        assert_eq!(done_sess.cluster.entries.len(), 0);
        assert_eq!(fresh_cache.component_solve_count(), 0);
    }

    #[tokio::test]
    async fn test_ep_component_cache_coalesces_concurrent_misses() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let cache = EpScoreCache::new();
        let svc = CatalogService::with_cache(&db, &cache);

        let mut ids = Vec::new();
        for i in 0..8 {
            let e = svc
                .upsert_entry(&UpsertEntryInput {
                    kind: Some(EntryKind::Album),
                    title: format!("Album {i}"),
                    creators: vec!["Band".to_owned()],
                    ..UpsertEntryInput::default()
                })
                .await
                .unwrap();
            svc.log_consumption(&e.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
            ids.push(e.id);
        }
        for w in ids.windows(2) {
            svc.append_comparison(None, &[vec![w[0].clone()], vec![w[1].clone()]], None)
                .await
                .unwrap();
        }

        // Launch 8 concurrent requests against the same cold component; coalescing ensures EP
        // runs once rather than 4x across all semaphore permits.
        let mut join_set = tokio::task::JoinSet::new();
        for _ in 0..8 {
            let db_c = db.clone();
            let cache_c = cache.clone();
            join_set.spawn(async move {
                let s = CatalogService::with_cache(&db_c, &cache_c);
                s.get_ranked_collection("albums", false).await.unwrap()
            });
        }
        let mut results = Vec::with_capacity(8);
        while let Some(res) = join_set.join_next().await {
            results.push(res.unwrap());
        }
        assert_eq!(results.len(), 8);
        for view in &results {
            assert_eq!(view.rows[0].entry.id, ids[0]);
        }
        assert_eq!(cache.component_solve_count(), 1);

        // Verify `load_entries_by_ids` deduplicates across >400-ID chunks without dropping
        // hydrated identifiers.
        let with_ident = svc
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Book),
                title: "Chunk Dup Book".to_owned(),
                identifiers: vec![EntryIdentifier {
                    scheme: "isbn".to_owned(),
                    value: "9780140449136".to_owned(),
                }],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let mut dup_ids = vec![with_ident.id.clone()];
        for i in 0..410 {
            dup_ids.push(format!("missing-placeholder-{i}"));
        }
        dup_ids.push(with_ident.id.clone());
        let loaded = db.catalog().load_entries_by_ids(&dup_ids).await.unwrap();
        assert_eq!(loaded[&with_ident.id].identifiers.len(), 1);
    }

    #[tokio::test]
    async fn test_ep_component_cache_watch_delivers_directly_past_lru_cap() {
        // Deterministically stage a leader and waiter over `MAX_CACHED_EP_COMPONENTS + 16`
        // disjoint 2-item components. Because `InFlightGuard::finish` delivers each solved
        // `ComponentScoresArc` directly over the `watch` channel, LRU eviction of 16 entries
        // during batch insertion must NOT cause the waiting task to re-solve them.
        let total_comps = MAX_CACHED_EP_COMPONENTS + 16;
        let mut orderings = Vec::with_capacity(total_comps);
        let mut target_ids = Vec::with_capacity(total_comps * 2);
        for i in 0..total_comps {
            let w = format!("w_{i:04}");
            let l = format!("l_{i:04}");
            orderings.push(vec![vec![w.clone()], vec![l.clone()]]);
            target_ids.push(w);
            target_ids.push(l);
        }
        let snapshot = build_scope_snapshot(orderings);
        assert_eq!(snapshot.components.len(), total_comps);

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let cache = EpScoreCache::new();
        let snap_arc = Arc::new(snapshot);
        let targets_arc = Arc::new(target_ids);

        // 1. Leader claims all `total_comps` components before the waiter runs.
        let mut guards = Vec::with_capacity(total_comps);
        for comp in snap_arc.components.iter() {
            let ComponentClaim::Leader(guard) = cache.claim_or_wait_component(comp.digest) else {
                panic!("expected leader claim for cold component");
            };
            guards.push(guard);
        }

        // 2. Spawn the waiter and yield until it has attached to all 528 watch channels
        //    (1 receiver lives in `in_flight`, so count >= 2 means the waiter cloned it).
        let waiter = {
            let db_c = db.clone();
            let cache_c = cache.clone();
            let snap_c = Arc::clone(&snap_arc);
            let targets_c = Arc::clone(&targets_arc);
            tokio::spawn(async move {
                let s = CatalogService::with_cache(&db_c, &cache_c);
                s.infer_scores_for_targets(&snap_c, &targets_c).await.unwrap()
            })
        };
        while guards.last().unwrap().tx.as_ref().unwrap().receiver_count() < 2 {
            tokio::task::yield_now().await;
        }

        // 3. Solve and insert all 528 entries into the 512-cap LRU *before* notifying the waiter,
        //    proving 16 entries are genuinely evicted before watch delivery.
        let solved = run_solve_ep_components(snap_arc.components.as_ref().clone()).await.unwrap();
        let mut results = Vec::with_capacity(total_comps);
        for (indexed, scores_opt) in solved {
            let scores_arc = Arc::new(scores_opt);
            cache.insert_component(indexed.digest, Arc::clone(&scores_arc));
            results.push(scores_arc);
        }
        let evicted_count =
            snap_arc.components.iter().filter(|c| cache.get_component(c.digest).is_none()).count();
        assert_eq!(evicted_count, total_comps - MAX_CACHED_EP_COMPONENTS);

        for (guard, scores_arc) in guards.into_iter().zip(results) {
            guard.finish(scores_arc);
        }

        // 4. Waiter receives all 528 solved components directly over `watch` with zero re-solves.
        let scores = waiter.await.unwrap();
        assert_eq!(scores.len(), total_comps * 2);
        assert!(scores["w_0000"].mean > scores["l_0000"].mean);
        assert_eq!(cache.component_solve_count(), 0);
    }
}
