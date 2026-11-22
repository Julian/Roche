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

    fun scheduleWorkspaceSearch(rawQuery: String) {
        deepSearchJob?.cancel()
        val normalized = rawQuery.trim().lowercase()
        if (normalized.isBlank()) {
            deepSearchJob = null
            return
        }
        deepSearchJob = scope.launch {
            delay(DEEP_SEARCH_DEBOUNCE_MS)
            val workspace = repository.searchWorkspace(rawQuery)
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
    }

    fun cancelSearch() {
        deepSearchJob?.cancel()
        deepSearchJob = null
    }

    suspend fun awaitSearchForTest() {
        deepSearchJob?.join()
    }

    companion object {
        private const val DEEP_SEARCH_DEBOUNCE_MS = 140L
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
