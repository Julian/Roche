package com.grayvines.roche.ui

import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.catalog.CatalogSearchPageResult
import com.grayvines.roche.data.catalog.DEFAULT_COLLECTION_PAGE_SIZE
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
    val selectedTier: Char? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoaded: Boolean = false,
)

private data class ActiveCollectionQueryParams(
    val collectionId: String? = null,
    val tierFilter: Char? = null,
    val pageLimit: Int = DEFAULT_COLLECTION_PAGE_SIZE,
    val loadMoreNonce: Int = 0,
    val isListsTabActive: Boolean = false,
)

private data class ActiveCollectionCacheKey(
    val revisionStamp: String,
    val collectionId: String,
    val tierFilter: Char?,
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
    private val activeLimitOverride = MutableStateFlow<Triple<String, Int, Int>?>(null)
    private val browseCatalogLimit = MutableStateFlow(DEFAULT_BROWSE_CATALOG_LIMIT)

    private var lastActiveCacheKey: ActiveCollectionCacheKey? = null

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

    fun loadMoreActiveCollection() {
        val current = _activeCollectionState.value
        val colId = current.collectionId ?: return
        if (!current.hasMore || current.isLoadingMore) return
        _activeCollectionState.update { it.copy(isLoadingMore = true) }
        val nextLimit = current.entries.size + DEFAULT_COLLECTION_PAGE_SIZE
        val nextNonce = (activeLimitOverride.value?.third ?: 0) + 1
        activeLimitOverride.value = Triple(colId, nextLimit, nextNonce)
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
            MainAction.LoadMoreCatalogCollection -> loadMoreActiveCollection()
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
            combine(selectedTargetFlow, activeTierOverride, activeLimitOverride) {
                    (isListsTab, colId),
                    tierOverride,
                    limitOverride ->
                    val tier =
                        if (colId != null && tierOverride?.first == colId) {
                            tierOverride.second
                        } else {
                            null
                        }
                    val limit =
                        if (colId != null && limitOverride?.first == colId) {
                            limitOverride.second
                        } else {
                            DEFAULT_COLLECTION_PAGE_SIZE
                        }
                    val nonce =
                        if (colId != null && limitOverride?.first == colId) {
                            limitOverride.third
                        } else {
                            0
                        }
                    ActiveCollectionQueryParams(
                        collectionId = colId,
                        tierFilter = tier,
                        pageLimit = limit,
                        loadMoreNonce = nonce,
                        isListsTabActive = isListsTab,
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

    private fun observeStarredCollection(
        revisionFlow: Flow<String>,
        transientFlow: Flow<TransientUiState>,
    ) {
        val starredActiveFlow =
            transientFlow.map { it.activeTab == AppTab.STARRED }.distinctUntilChanged()
        scope.launch {
            combine(revisionFlow, starredActiveFlow) { rev, active -> rev to active }
                .collectLatest { (_, active) ->
                    if (active) refreshStarredCollection()
                }
        }
    }

    private suspend fun refreshStarredCollection() {
        refreshStaticCollection(STARRED_COLLECTION_ID, _starredCollectionState)
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
                limit = STARRED_AND_WANT_FULL_LIMIT,
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
            activeTierOverride.value = null
            activeLimitOverride.value = null
            lastActiveCacheKey = null
            _activeCollectionState.value = CatalogCollectionUiState()
            return
        }
        if (!params.isListsTabActive) return
        val cacheKey = ActiveCollectionCacheKey(revisionStamp, colId, params.tierFilter)
        val prev = _activeCollectionState.value
        val canAppend =
            prev.isLoaded &&
                lastActiveCacheKey == cacheKey &&
                prev.collectionId == colId &&
                params.pageLimit > prev.entries.size
        val offset = if (canAppend) prev.entries.size else 0
        val limit = if (canAppend) params.pageLimit - prev.entries.size else params.pageLimit
        val result = runSuspendCatching {
            repository.catalog.loadCollectionPage(
                collectionIdOrSlug = colId,
                tierFilter = params.tierFilter,
                limit = limit,
                offset = offset,
            )
        }
        val loaded = result.getOrNull()
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
                selectedTier = params.tierFilter,
                hasMore = loaded.hasMore,
                isLoadingMore = false,
                isLoaded = true,
            )
    }

    private companion object {
        const val DEFAULT_BROWSE_CATALOG_LIMIT = 24
        const val STARRED_AND_WANT_FULL_LIMIT = Int.MAX_VALUE
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
