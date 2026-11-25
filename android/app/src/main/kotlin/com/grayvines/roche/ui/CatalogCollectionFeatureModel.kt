package com.grayvines.roche.ui

import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.catalog.CatalogSearchPageResult
import com.grayvines.roche.data.catalog.DEFAULT_COLLECTION_PAGE_SIZE
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.GlobalTierIndex
import com.grayvines.roche.data.catalog.RankedCatalogEntry
import com.grayvines.roche.model.AppTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogCollectionUiState(
    val collectionId: String? = null,
    val entries: List<RankedCatalogEntry> = emptyList(),
    val totalCount: Int = 0,
    val filteredTotalCount: Int = totalCount,
    val tierCounts: Map<Char, Int> = emptyMap(),
    val kindCounts: List<Pair<EntryKind, Int>> = emptyList(),
    val selectedTier: Char? = null,
    val selectedKind: EntryKind? = null,
    val selectedKinds: Set<EntryKind> = emptySet(),
    val selectedFilterKey: String? = null,
    val requestedFilterKey: String? = selectedFilterKey,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoaded: Boolean = false,
)

private const val DEFAULT_STARRED_FILTER_KEY = "all"

private data class StarredFilterQuery(
    val filterKey: String = DEFAULT_STARRED_FILTER_KEY,
    val kindFilters: Set<EntryKind> = emptySet(),
)

private data class ActiveCollectionQueryParams(
    val collectionId: String? = null,
    val tierFilter: Char? = null,
    val kindFilter: EntryKind? = null,
    val pageLimit: Int = DEFAULT_COLLECTION_PAGE_SIZE,
    val loadMoreNonce: Int = 0,
    val isListsTabActive: Boolean = false,
)

private data class ActiveCollectionCacheKey(
    val revisionStamp: String,
    val collectionId: String,
    val tierFilter: Char?,
    val kindFilter: EntryKind?,
)

private data class StarredQueryParams(
    val filterKey: String = DEFAULT_STARRED_FILTER_KEY,
    val kindFilters: Set<EntryKind> = emptySet(),
    val pageLimit: Int = DEFAULT_COLLECTION_PAGE_SIZE,
    val loadMoreNonce: Int = 0,
    val isStarredTabActive: Boolean = false,
)

private data class StarredCacheKey(
    val revisionStamp: String,
    val filterKey: String,
    val kindFilters: Set<EntryKind>,
)

private data class BrowseCatalogQueryKey(
    val revisionStamp: String,
    val globalTiers: GlobalTierIndex,
    val active: Boolean,
    val limit: Int,
)

@Suppress("TooManyFunctions")
internal class CatalogCollectionFeatureModel(
    private val scope: CoroutineScope,
    private val repository: RocheRepository,
) {
    private val activeTierOverride = MutableStateFlow<Pair<String, Char?>?>(null)
    private val activeKindOverride = MutableStateFlow<Pair<String, EntryKind?>?>(null)
    private val activeLimitOverride = MutableStateFlow<Triple<String, Int, Int>?>(null)
    private val starredFilterOverride = MutableStateFlow(StarredFilterQuery())
    private val starredLimitOverride = MutableStateFlow(DEFAULT_COLLECTION_PAGE_SIZE to 0)
    private val browseCatalogLimit = MutableStateFlow(DEFAULT_BROWSE_CATALOG_LIMIT)

    private var lastActiveCacheKey: ActiveCollectionCacheKey? = null
    private var lastStarredCacheKey: StarredCacheKey? = null

    private val _activeCollectionState = MutableStateFlow(CatalogCollectionUiState())
    val activeCollectionState: StateFlow<CatalogCollectionUiState> =
        _activeCollectionState.asStateFlow()

    private val _starredCollectionState = MutableStateFlow(CatalogCollectionUiState())
    val starredCollectionState: StateFlow<CatalogCollectionUiState> =
        _starredCollectionState.asStateFlow()

    private val _activeWantQueueState = MutableStateFlow(CatalogCollectionUiState())
    val activeWantQueueState: StateFlow<CatalogCollectionUiState> =
        _activeWantQueueState.asStateFlow()

    private val _catalogBrowseState = MutableStateFlow(CatalogSearchPageResult())
    val catalogBrowseState: StateFlow<CatalogSearchPageResult> = _catalogBrowseState.asStateFlow()

    fun startObserving(
        snapshotFlow: Flow<RepositorySnapshot>,
        transientFlow: Flow<TransientUiState>,
    ) {
        val revisionFlow = snapshotFlow.map { it.catalogRevisionStamp }.distinctUntilChanged()
        observeActiveListCollection(revisionFlow, transientFlow)
        observeStarredCollection(revisionFlow, transientFlow)
        observeActiveWantQueue(snapshotFlow, transientFlow)
        observeCatalogBrowseSearch(snapshotFlow, transientFlow)
    }

    fun setCollectionTierFilter(collectionId: String?, tier: Char?) {
        val colId = collectionId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        activeTierOverride.value = colId to tier
        activeLimitOverride.value = Triple(colId, DEFAULT_COLLECTION_PAGE_SIZE, 0)
    }

    fun setCollectionKindFilter(collectionId: String?, kind: EntryKind?) {
        val colId = collectionId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        activeKindOverride.value = colId to kind
        activeLimitOverride.value = Triple(colId, DEFAULT_COLLECTION_PAGE_SIZE, 0)
    }

    fun loadMoreActiveCollection() {
        val current = _activeCollectionState.value
        val colId = current.collectionId ?: return
        if (!current.hasMore || current.isLoadingMore) return
        _activeCollectionState.update { it.copy(isLoadingMore = true) }
        val nextLimit = current.entries.size + DEFAULT_COLLECTION_PAGE_SIZE
        val nextNonce = (activeLimitOverride.value?.third ?: 0) + 1
        activeLimitOverride.value = Triple(colId, nextLimit, nextNonce)
    }

    fun setStarredKindFilter(
        kind: EntryKind?,
        filterKey: String? = null,
        kinds: Set<EntryKind> = emptySet(),
    ) {
        val effectiveKinds = kinds.ifEmpty { kind?.let(::setOf) ?: emptySet() }
        val effectiveKey =
            filterKey ?: kind?.let { "kind_${it.wireValue}" } ?: DEFAULT_STARRED_FILTER_KEY
        val next = StarredFilterQuery(filterKey = effectiveKey, kindFilters = effectiveKinds)
        if (starredFilterOverride.value == next) return
        _starredCollectionState.update { it.copy(requestedFilterKey = effectiveKey) }
        starredFilterOverride.value = next
        starredLimitOverride.value = DEFAULT_COLLECTION_PAGE_SIZE to 0
    }

    fun loadMoreStarredCollection() {
        val current = _starredCollectionState.value
        if (!current.hasMore || current.isLoadingMore) return
        _starredCollectionState.update { it.copy(isLoadingMore = true) }
        val nextLimit = current.entries.size + DEFAULT_COLLECTION_PAGE_SIZE
        val nextNonce = starredLimitOverride.value.second + 1
        starredLimitOverride.value = nextLimit to nextNonce
    }

    fun loadMoreBrowseCatalog() {
        val current = _catalogBrowseState.value
        if (!current.hasMore) return
        browseCatalogLimit.update { it + DEFAULT_BROWSE_CATALOG_LIMIT }
    }

    fun resetBrowseCatalogLimit() {
        browseCatalogLimit.value = DEFAULT_BROWSE_CATALOG_LIMIT
    }

    fun handleUiAction(action: MainAction.Ui): Boolean {
        when (action) {
            is MainAction.SetCatalogCollectionTierFilter ->
                setCollectionTierFilter(action.collectionId, action.tier)
            is MainAction.SetCatalogCollectionKindFilter ->
                setCollectionKindFilter(action.collectionId, action.kind)
            MainAction.LoadMoreCatalogCollection -> loadMoreActiveCollection()
            is MainAction.SetStarredKindFilter ->
                setStarredKindFilter(action.kind, action.filterKey, action.kinds)
            MainAction.LoadMoreStarredCollection -> loadMoreStarredCollection()
            else -> return false
        }
        return true
    }

    private fun observeActiveListCollection(
        revisionFlow: Flow<String>,
        transientFlow: Flow<TransientUiState>,
    ) {
        val selectedTargetFlow =
            transientFlow
                .map { Pair(it.activeTab == AppTab.LISTS, it.selectedCatalogCollectionId) }
                .distinctUntilChanged()
        val paramsFlow =
            combine(
                    selectedTargetFlow,
                    activeTierOverride,
                    activeKindOverride,
                    activeLimitOverride,
                ) { (isListsTab, colId), tierOverride, kindOverride, limitOverride ->
                    buildActiveCollectionParams(
                        isListsTab = isListsTab,
                        colId = colId,
                        tierOverride = tierOverride,
                        kindOverride = kindOverride,
                        limitOverride = limitOverride,
                    )
                }
                .distinctUntilChanged()
        scope.launch {
            combine(revisionFlow, paramsFlow) { rev, params -> rev to params }
                .collectLatest { (rev, params) ->
                    refreshActiveCollection(rev, params)
                }
        }
    }

    private fun buildActiveCollectionParams(
        isListsTab: Boolean,
        colId: String?,
        tierOverride: Pair<String, Char?>?,
        kindOverride: Pair<String, EntryKind?>?,
        limitOverride: Triple<String, Int, Int>?,
    ): ActiveCollectionQueryParams {
        val sameTier = colId != null && tierOverride?.first == colId
        val sameKind = colId != null && kindOverride?.first == colId
        val sameLimit = colId != null && limitOverride?.first == colId
        return ActiveCollectionQueryParams(
            collectionId = colId,
            tierFilter = if (sameTier) tierOverride.second else null,
            kindFilter = if (sameKind) kindOverride.second else null,
            pageLimit = if (sameLimit) limitOverride.second else DEFAULT_COLLECTION_PAGE_SIZE,
            loadMoreNonce = if (sameLimit) limitOverride.third else 0,
            isListsTabActive = isListsTab,
        )
    }

    private fun observeStarredCollection(
        revisionFlow: Flow<String>,
        transientFlow: Flow<TransientUiState>,
    ) {
        val starredActiveFlow =
            transientFlow.map { it.activeTab == AppTab.STARRED }.distinctUntilChanged()
        val paramsFlow =
            combine(starredActiveFlow, starredFilterOverride, starredLimitOverride) {
                    active,
                    filter,
                    (limit, nonce) ->
                    StarredQueryParams(
                        filterKey = filter.filterKey,
                        kindFilters = filter.kindFilters,
                        pageLimit = limit,
                        loadMoreNonce = nonce,
                        isStarredTabActive = active,
                    )
                }
                .distinctUntilChanged()
        scope.launch {
            combine(revisionFlow, paramsFlow) { rev, params -> rev to params }
                .collectLatest { (rev, params) ->
                    if (params.isStarredTabActive) {
                        refreshStarredCollection(rev, params)
                    }
                }
        }
    }

    private suspend fun refreshStarredCollection(
        revisionStamp: String,
        params: StarredQueryParams,
    ) {
        val cacheKey = StarredCacheKey(revisionStamp, params.filterKey, params.kindFilters)
        val prev = _starredCollectionState.value
        val sameCache =
            prev.isLoaded &&
                lastStarredCacheKey == cacheKey &&
                prev.collectionId == STARRED_COLLECTION_ID
        if (sameCache && (params.pageLimit <= prev.entries.size || !prev.hasMore)) {
            if (prev.isLoadingMore) {
                _starredCollectionState.update { it.copy(isLoadingMore = false) }
            }
            return
        }
        val canAppend = sameCache && params.pageLimit > prev.entries.size
        val offset = if (canAppend) prev.entries.size else 0
        val limit = if (canAppend) params.pageLimit - prev.entries.size else params.pageLimit
        val loaded = runSuspendCatching {
            repository.catalog.loadCollectionPage(
                collectionIdOrSlug = STARRED_COLLECTION_ID,
                kindFilters = params.kindFilters,
                limit = limit,
                offset = offset,
            )
        }
            .getOrNull()
        if (loaded == null) {
            _starredCollectionState.update { it.copy(isLoadingMore = false) }
            return
        }
        applyStarredPageResult(cacheKey, params, prev, canAppend, loaded)
    }

    private fun applyStarredPageResult(
        cacheKey: StarredCacheKey,
        params: StarredQueryParams,
        prev: CatalogCollectionUiState,
        canAppend: Boolean,
        loaded: com.grayvines.roche.data.catalog.CatalogCollectionPageResult,
    ) {
        val vanishedDynamicKind =
            params.filterKey.startsWith("kind_") &&
                loaded.filteredTotalCount == 0 &&
                loaded.kindCounts.none { "kind_${it.first.wireValue}" == params.filterKey }
        val mergedEntries =
            if (canAppend && loaded.collectionId == prev.collectionId) {
                mergeUniqueRankedEntries(prev.entries, loaded.entries)
            } else {
                loaded.entries
            }
        lastStarredCacheKey = cacheKey
        _starredCollectionState.value =
            CatalogCollectionUiState(
                collectionId = loaded.collectionId,
                entries = mergedEntries,
                totalCount = loaded.totalCount,
                filteredTotalCount = loaded.filteredTotalCount,
                tierCounts = loaded.tierCounts,
                kindCounts = loaded.kindCounts,
                selectedKind = params.kindFilters.singleOrNull(),
                selectedKinds = params.kindFilters,
                selectedFilterKey = params.filterKey,
                requestedFilterKey = starredFilterOverride.value.filterKey,
                hasMore = loaded.hasMore,
                isLoadingMore = false,
                isLoaded = true,
            )
        if (vanishedDynamicKind && starredFilterOverride.value.filterKey == params.filterKey) {
            setStarredKindFilter(kind = null, filterKey = DEFAULT_STARRED_FILTER_KEY)
        }
    }

    private fun observeActiveWantQueue(
        snapshotFlow: Flow<RepositorySnapshot>,
        transientFlow: Flow<TransientUiState>,
    ) {
        val wantQueueTargetFlow =
            combine(
                    snapshotFlow
                        .map { it.catalogRevisionStamp to it.catalog.wantQueues }
                        .distinctUntilChanged(),
                    transientFlow
                        .map { Triple(it.activeTab, it.queueSubTab, it.selectedWantCollectionId) }
                        .distinctUntilChanged(),
                ) { (rev, queues), (tab, subTab, selectedId) ->
                    val targetId =
                        if (tab == AppTab.QUEUE && subTab == QueueSubTab.WANT) {
                            resolveActiveWantQueue(queues, selectedId)?.collectionId
                        } else {
                            null
                        }
                    rev to targetId
                }
                .distinctUntilChanged()
        scope.launch {
            wantQueueTargetFlow.collectLatest { (_, targetColId) ->
                if (targetColId != null) {
                    refreshStaticCollection(targetColId, _activeWantQueueState)
                }
            }
        }
    }

    private suspend fun refreshStaticCollection(
        collectionIdOrSlug: String,
        targetFlow: MutableStateFlow<CatalogCollectionUiState>,
    ) {
        val result = runSuspendCatching {
            repository.catalog.loadCollectionPage(
                collectionIdOrSlug = collectionIdOrSlug,
                limit = WANT_QUEUE_FULL_LIMIT,
                offset = 0,
            )
        }
        val loaded = result.getOrNull() ?: return
        targetFlow.value =
            CatalogCollectionUiState(
                collectionId = loaded.collectionId,
                entries = loaded.entries,
                totalCount = loaded.totalCount,
                filteredTotalCount = loaded.filteredTotalCount,
                tierCounts = loaded.tierCounts,
                kindCounts = loaded.kindCounts,
                hasMore = loaded.hasMore,
                isLoaded = true,
            )
    }

    private fun observeCatalogBrowseSearch(
        snapshotFlow: Flow<RepositorySnapshot>,
        transientFlow: Flow<TransientUiState>,
    ) {
        val browseKeyFlow =
            combine(
                    snapshotFlow
                        .map { it.catalogRevisionStamp to it.catalog.globalTiers }
                        .distinctUntilChanged(),
                    transientFlow
                        .map { it.isSearchOpen && it.searchQuery.isBlank() }
                        .distinctUntilChanged(),
                    browseCatalogLimit,
                ) { (rev, tiers), isBlankSearchOpen, limit ->
                    BrowseCatalogQueryKey(rev, tiers, isBlankSearchOpen, limit)
                }
                .distinctUntilChanged()
        scope.launch {
            browseKeyFlow.collectLatest { key ->
                if (key.active) refreshCatalogBrowse(key)
            }
        }
    }

    private suspend fun refreshCatalogBrowse(key: BrowseCatalogQueryKey) {
        val result = runSuspendCatching {
            repository.catalog.searchCatalogEntries(
                rawQuery = "",
                includeWhenBlank = true,
                limit = key.limit,
                globalTiers = key.globalTiers,
            )
        }
        val loaded = result.getOrNull() ?: return
        _catalogBrowseState.value = loaded
    }

    private suspend fun refreshActiveCollection(
        revisionStamp: String,
        params: ActiveCollectionQueryParams,
    ) {
        val colId = params.collectionId
        if (colId == null) {
            clearActiveCollectionState()
            return
        }
        val prev = _activeCollectionState.value
        if (prev.collectionId != null && prev.collectionId != colId) {
            clearStaleActiveOverridesFor(colId)
        }
        if (!params.isListsTabActive) return
        val cacheKey =
            ActiveCollectionCacheKey(revisionStamp, colId, params.tierFilter, params.kindFilter)
        val sameCache =
            prev.isLoaded && lastActiveCacheKey == cacheKey && prev.collectionId == colId
        if (sameCache && (params.pageLimit <= prev.entries.size || !prev.hasMore)) {
            if (prev.isLoadingMore) {
                _activeCollectionState.update { it.copy(isLoadingMore = false) }
            }
            return
        }
        fetchAndApplyActiveCollection(cacheKey, colId, params, prev, sameCache)
    }

    private fun clearActiveCollectionState() {
        activeTierOverride.value = null
        activeKindOverride.value = null
        activeLimitOverride.value = null
        lastActiveCacheKey = null
        _activeCollectionState.value = CatalogCollectionUiState()
    }

    private fun clearStaleActiveOverridesFor(colId: String) {
        if (activeTierOverride.value?.first != colId) activeTierOverride.value = null
        if (activeKindOverride.value?.first != colId) activeKindOverride.value = null
        if (activeLimitOverride.value?.first != colId) activeLimitOverride.value = null
    }

    private suspend fun fetchAndApplyActiveCollection(
        cacheKey: ActiveCollectionCacheKey,
        colId: String,
        params: ActiveCollectionQueryParams,
        prev: CatalogCollectionUiState,
        sameCache: Boolean,
    ) {
        val canAppend = sameCache && params.pageLimit > prev.entries.size
        val offset = if (canAppend) prev.entries.size else 0
        val limit = if (canAppend) params.pageLimit - prev.entries.size else params.pageLimit
        val loaded = runSuspendCatching {
            repository.catalog.loadCollectionPage(
                collectionIdOrSlug = colId,
                tierFilter = params.tierFilter,
                kindFilter = params.kindFilter,
                limit = limit,
                offset = offset,
            )
        }
            .getOrNull()
        if (loaded == null) {
            _activeCollectionState.update { it.copy(isLoadingMore = false) }
            return
        }
        val mergedEntries =
            if (canAppend && loaded.collectionId == prev.collectionId) {
                mergeUniqueRankedEntries(prev.entries, loaded.entries)
            } else {
                loaded.entries
            }
        lastActiveCacheKey = cacheKey
        _activeCollectionState.value =
            CatalogCollectionUiState(
                collectionId = loaded.collectionId,
                entries = mergedEntries,
                totalCount = loaded.totalCount,
                filteredTotalCount = loaded.filteredTotalCount,
                tierCounts = loaded.tierCounts,
                kindCounts = loaded.kindCounts,
                selectedTier = params.tierFilter,
                selectedKind = params.kindFilter,
                hasMore = loaded.hasMore,
                isLoadingMore = false,
                isLoaded = true,
            )
    }

    private companion object {
        const val DEFAULT_BROWSE_CATALOG_LIMIT = 24
        const val WANT_QUEUE_FULL_LIMIT = 10_000
    }
}

internal fun mergeUniqueRankedEntries(
    existing: List<RankedCatalogEntry>,
    incoming: List<RankedCatalogEntry>,
): List<RankedCatalogEntry> {
    if (incoming.isEmpty()) return existing
    val seen = HashSet<String>(existing.size + incoming.size)
    val merged = ArrayList<RankedCatalogEntry>(existing.size + incoming.size)
    existing.forEach { if (seen.add(it.entry.id)) merged.add(it) }
    incoming.forEach { if (seen.add(it.entry.id)) merged.add(it) }
    return merged
}
