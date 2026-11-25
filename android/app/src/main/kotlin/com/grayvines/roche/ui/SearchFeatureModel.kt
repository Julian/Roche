package com.grayvines.roche.ui

import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.WorkspaceSearchResult
import com.grayvines.roche.model.InboxEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SearchProjection(
    val results: List<InboxEntry> = emptyList(),
    val workspaceResult: WorkspaceSearchResult = WorkspaceSearchResult(),
)

internal class SearchFeatureModel(
    private val scope: CoroutineScope,
    private val repository: RocheRepository,
    private val transientState: MutableStateFlow<TransientUiState>,
) {
    private var deepSearchJob: Job? = null
    private var catalogSearchLimit: Int = DEFAULT_CATALOG_SEARCH_PAGE_SIZE

    fun scheduleWorkspaceSearch(rawQuery: String) {
        deepSearchJob?.cancel()
        catalogSearchLimit = DEFAULT_CATALOG_SEARCH_PAGE_SIZE
        val normalized = rawQuery.trim().lowercase()
        if (normalized.isBlank()) {
            deepSearchJob = null
            return
        }
        deepSearchJob = scope.launch {
            delay(DEEP_SEARCH_DEBOUNCE_MS)
            executeSearch(rawQuery, normalized, catalogSearchLimit)
        }
    }

    fun loadMoreCatalogSearch() {
        val rawQuery = transientState.value.searchQuery
        val normalized = rawQuery.trim().lowercase()
        if (normalized.isBlank()) return
        catalogSearchLimit += DEFAULT_CATALOG_SEARCH_PAGE_SIZE
        val targetLimit = catalogSearchLimit
        deepSearchJob?.cancel()
        deepSearchJob = scope.launch {
            val catalogPage =
                repository.catalog.searchCatalogEntries(
                    rawQuery = rawQuery,
                    includeWhenBlank = false,
                    limit = targetLimit,
                )
            transientState.update { current ->
                if (current.searchQuery.trim().lowercase() == normalized) {
                    current.copy(
                        deepSearchQuery = normalized,
                        workspaceSearch =
                            current.workspaceSearch.copy(
                                matchedCatalogEntries = catalogPage.matches,
                                catalogTotalCount = catalogPage.totalCount,
                            ),
                    )
                } else {
                    current
                }
            }
        }
    }

    private suspend fun executeSearch(rawQuery: String, normalized: String, catalogLimit: Int) {
        val workspace = repository.searchWorkspace(rawQuery, catalogLimit = catalogLimit)
        transientState.update { current ->
            if (current.searchQuery.trim().lowercase() == normalized) {
                current.copy(
                    deepSearchQuery = normalized,
                    workspaceSearch = workspace,
                )
            } else {
                current
            }
        }
    }

    fun cancelSearch() {
        deepSearchJob?.cancel()
        deepSearchJob = null
        catalogSearchLimit = DEFAULT_CATALOG_SEARCH_PAGE_SIZE
    }

    suspend fun awaitSearchForTest() {
        deepSearchJob?.join()
    }

    companion object {
        private const val DEEP_SEARCH_DEBOUNCE_MS = 140L
        private const val DEFAULT_CATALOG_SEARCH_PAGE_SIZE = 24
    }
}

internal fun buildSearchProjection(
    entries: List<InboxEntry>,
    normalizedQuery: String,
    deepSearchQuery: String,
    workspaceSearch: WorkspaceSearchResult,
): SearchProjection {
    val merged =
        resolveMergedSearchResults(
            entries = entries,
            normalizedQuery = normalizedQuery,
            deepSearchQuery = deepSearchQuery,
            deepSearchResults = workspaceSearch.entries,
        )
    val activeWorkspace =
        if (normalizedQuery.isNotBlank() && deepSearchQuery == normalizedQuery) {
            workspaceSearch
        } else {
            WorkspaceSearchResult()
        }
    return SearchProjection(results = merged, workspaceResult = activeWorkspace)
}
