package com.grayvines.roche.ui

import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.DEFAULT_DIARY_PAGE_SIZE
import com.grayvines.roche.data.catalog.DEFAULT_ENTRY_DETAIL_LOGS_LIMIT
import com.grayvines.roche.data.catalog.DiaryEntryItem
import com.grayvines.roche.data.catalog.EntryDiaryLogsResult
import com.grayvines.roche.data.catalog.GlobalTierIndex
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

data class DiaryUiState(
    val entries: List<DiaryEntryItem> = emptyList(),
    val availableYears: List<String> = emptyList(),
    val monthCounts: Map<String, Int> = emptyMap(),
    val totalFilteredCount: Int = 0,
    val hasAnyEntries: Boolean = false,
    val statusFilter: ConsumptionStatus? = null,
    val yearFilter: String? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoaded: Boolean = false,
)

private data class DiaryInvalidationKey(
    val revisionStamp: String,
    val globalTiers: GlobalTierIndex,
)

private data class DiaryQueryParams(
    val statusFilter: ConsumptionStatus? = null,
    val yearFilter: String? = null,
    val pageLimit: Int = DEFAULT_DIARY_PAGE_SIZE,
    val loadMoreNonce: Int = 0,
)

private data class LoadedDiaryCacheKey(
    val invalidation: DiaryInvalidationKey,
    val statusFilter: ConsumptionStatus?,
    val effectiveYear: String?,
)

private data class DetailLogsQueryParams(
    val entryId: String? = null,
    val limit: Int = DEFAULT_ENTRY_DETAIL_LOGS_LIMIT,
)

private data class LoadedDetailCacheKey(
    val invalidation: DiaryInvalidationKey,
    val entryId: String,
)

internal class DiaryFeatureModel(
    private val scope: CoroutineScope,
    private val repository: RocheRepository,
) {
    private val diaryQuery = MutableStateFlow(DiaryQueryParams())
    private val detailLogsLimitOverride = MutableStateFlow<Pair<String, Int>?>(null)

    private var lastLoadedDiaryKey: LoadedDiaryCacheKey? = null
    private var lastLoadedDetailKey: LoadedDetailCacheKey? = null

    private val _diaryState = MutableStateFlow(DiaryUiState())
    val diaryState: StateFlow<DiaryUiState> = _diaryState.asStateFlow()

    private val _detailLogsState = MutableStateFlow<EntryDiaryLogsResult?>(null)
    val detailLogsState: StateFlow<EntryDiaryLogsResult?> = _detailLogsState.asStateFlow()

    fun startObserving(
        snapshotFlow: Flow<RepositorySnapshot>,
        detailEntryIdFlow: Flow<String?>,
    ) {
        val invalidationFlow: Flow<DiaryInvalidationKey> =
            snapshotFlow
                .map { snap ->
                    DiaryInvalidationKey(
                        revisionStamp = snap.diaryRevisionStamp,
                        globalTiers = snap.catalog.globalTiers,
                    )
                }
                .distinctUntilChanged()
        scope.launch {
            combine(invalidationFlow, diaryQuery) { invalidation, query -> invalidation to query }
                .collectLatest { (invalidation, query) ->
                    refreshDiaryPage(invalidation, query)
                }
        }
        scope.launch {
            val detailParamsFlow =
                combine(detailEntryIdFlow.distinctUntilChanged(), detailLogsLimitOverride) {
                        entryId,
                        override ->
                        val normalizedId = entryId?.trim()?.takeIf { it.isNotEmpty() }
                        val limit =
                            if (normalizedId != null && override?.first == normalizedId) {
                                override.second
                            } else {
                                DEFAULT_ENTRY_DETAIL_LOGS_LIMIT
                            }
                        DetailLogsQueryParams(entryId = normalizedId, limit = limit)
                    }
                    .distinctUntilChanged()
            combine(invalidationFlow, detailParamsFlow) { invalidation, params ->
                    invalidation to params
                }
                .collectLatest { (invalidation, params) ->
                    refreshDetailLogs(invalidation, params)
                }
        }
    }

    fun setStatusFilter(status: ConsumptionStatus?) {
        diaryQuery.update { current ->
            if (current.statusFilter == status) {
                current
            } else {
                current.copy(statusFilter = status, pageLimit = DEFAULT_DIARY_PAGE_SIZE)
            }
        }
    }

    fun setYearFilter(year: String?) {
        val cleanYear = year?.trim()?.takeIf { it.isNotEmpty() }
        diaryQuery.update { current ->
            if (current.yearFilter == cleanYear) {
                current
            } else {
                current.copy(yearFilter = cleanYear, pageLimit = DEFAULT_DIARY_PAGE_SIZE)
            }
        }
    }

    fun loadMoreDiary() {
        val currentState = _diaryState.value
        if (!currentState.hasMore || currentState.isLoadingMore) return
        _diaryState.update { it.copy(isLoadingMore = true) }
        val nextLimit = currentState.entries.size + DEFAULT_DIARY_PAGE_SIZE
        diaryQuery.update { current ->
            current.copy(pageLimit = nextLimit, loadMoreNonce = current.loadMoreNonce + 1)
        }
    }

    fun loadMoreDetailLogs() {
        val current = _detailLogsState.value ?: return
        if (!current.hasMore) return
        val nextLimit = current.logs.size + DEFAULT_ENTRY_DETAIL_LOGS_LIMIT
        detailLogsLimitOverride.value = current.entryId to nextLimit
    }

    fun handleUiAction(action: MainAction.Ui): Boolean {
        when (action) {
            is MainAction.SetDiaryStatusFilter -> setStatusFilter(action.status)
            is MainAction.SetDiaryYearFilter -> setYearFilter(action.year)
            MainAction.LoadMoreDiary -> loadMoreDiary()
            MainAction.LoadMoreCatalogDetailLogs -> loadMoreDetailLogs()
            else -> return false
        }
        return true
    }

    private suspend fun refreshDiaryPage(
        invalidation: DiaryInvalidationKey,
        query: DiaryQueryParams,
    ) {
        val prevState = _diaryState.value
        val desiredCacheKey =
            LoadedDiaryCacheKey(
                invalidation = invalidation,
                statusFilter = query.statusFilter,
                effectiveYear = query.yearFilter,
            )
        if (prevState.isLoaded && lastLoadedDiaryKey == desiredCacheKey) {
            if (query.pageLimit <= prevState.entries.size) {
                if (prevState.isLoadingMore) {
                    _diaryState.update { it.copy(isLoadingMore = false) }
                }
                return
            }
            if (appendNextDiarySlice(invalidation, query, prevState, desiredCacheKey)) {
                return
            }
        }
        reloadFullDiaryWindow(invalidation, query)
    }

    private suspend fun appendNextDiarySlice(
        invalidation: DiaryInvalidationKey,
        query: DiaryQueryParams,
        prevState: DiaryUiState,
        cacheKey: LoadedDiaryCacheKey,
    ): Boolean {
        val delta = (query.pageLimit - prevState.entries.size).coerceAtLeast(1)
        val result = runSuspendCatching {
            repository.catalog.loadDiaryPage(
                status = query.statusFilter,
                year = query.yearFilter,
                limit = delta,
                offset = prevState.entries.size,
                globalTiers = invalidation.globalTiers,
            )
        }
        val loaded = result.getOrNull()
        if (loaded == null) {
            _diaryState.update { it.copy(isLoadingMore = false) }
            return true
        }
        if (loaded.effectiveYear != prevState.yearFilter) return false
        val merged = mergeUniqueDiaryItems(prevState.entries, loaded.entries)
        lastLoadedDiaryKey = cacheKey.copy(effectiveYear = loaded.effectiveYear)
        _diaryState.value =
            DiaryUiState(
                entries = merged,
                availableYears = loaded.availableYears,
                monthCounts = loaded.monthCounts,
                totalFilteredCount = loaded.totalFilteredCount,
                hasAnyEntries = loaded.hasAnyEntries,
                statusFilter = query.statusFilter,
                yearFilter = loaded.effectiveYear,
                hasMore = loaded.hasMore,
                isLoadingMore = false,
                isLoaded = true,
            )
        return true
    }

    private suspend fun reloadFullDiaryWindow(
        invalidation: DiaryInvalidationKey,
        query: DiaryQueryParams,
    ) {
        val result = runSuspendCatching {
            repository.catalog.loadDiaryPage(
                status = query.statusFilter,
                year = query.yearFilter,
                limit = query.pageLimit,
                offset = 0,
                globalTiers = invalidation.globalTiers,
            )
        }
        val loaded = result.getOrNull()
        if (loaded == null) {
            _diaryState.update { it.copy(isLoadingMore = false) }
            return
        }
        lastLoadedDiaryKey =
            LoadedDiaryCacheKey(
                invalidation = invalidation,
                statusFilter = query.statusFilter,
                effectiveYear = loaded.effectiveYear,
            )
        _diaryState.value =
            DiaryUiState(
                entries = loaded.entries,
                availableYears = loaded.availableYears,
                monthCounts = loaded.monthCounts,
                totalFilteredCount = loaded.totalFilteredCount,
                hasAnyEntries = loaded.hasAnyEntries,
                statusFilter = query.statusFilter,
                yearFilter = loaded.effectiveYear,
                hasMore = loaded.hasMore,
                isLoadingMore = false,
                isLoaded = true,
            )
        if (query.yearFilter != null && loaded.effectiveYear == null) {
            diaryQuery.update { cur ->
                if (cur.yearFilter == query.yearFilter) cur.copy(yearFilter = null) else cur
            }
        }
    }

    private suspend fun refreshDetailLogs(
        invalidation: DiaryInvalidationKey,
        params: DetailLogsQueryParams,
    ) {
        val entryId = params.entryId
        if (entryId == null) {
            lastLoadedDetailKey = null
            _detailLogsState.value = null
            return
        }
        val cacheKey = LoadedDetailCacheKey(invalidation = invalidation, entryId = entryId)
        val prev = _detailLogsState.value
        val canAppend =
            prev != null &&
                lastLoadedDetailKey == cacheKey &&
                prev.entryId == entryId &&
                params.limit > prev.logs.size
        val offset = if (canAppend) prev.logs.size else 0
        val limit = if (canAppend) params.limit - prev.logs.size else params.limit
        val result = runSuspendCatching {
            repository.catalog.loadEntryDiaryLogs(
                entryId = entryId,
                limit = limit,
                offset = offset,
                globalTiers = invalidation.globalTiers,
            )
        }
        val loaded = result.getOrNull() ?: return
        lastLoadedDetailKey = cacheKey
        _detailLogsState.value =
            if (canAppend && loaded.entryId == prev.entryId) {
                loaded.copy(logs = mergeUniqueDiaryItems(prev.logs, loaded.logs))
            } else {
                loaded
            }
    }
}

internal fun mergeUniqueDiaryItems(
    existing: List<DiaryEntryItem>,
    incoming: List<DiaryEntryItem>,
): List<DiaryEntryItem> {
    if (incoming.isEmpty()) return existing
    val seen = HashSet<String>(existing.size + incoming.size)
    val merged = ArrayList<DiaryEntryItem>(existing.size + incoming.size)
    existing.forEach { if (seen.add(it.id)) merged.add(it) }
    incoming.forEach { if (seen.add(it.id)) merged.add(it) }
    return merged
}
