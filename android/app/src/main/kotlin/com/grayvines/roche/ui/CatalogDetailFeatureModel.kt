package com.grayvines.roche.ui

import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.catalog.CatalogEntryDetailResult
import com.grayvines.roche.data.catalog.CatalogEntryItem
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
import kotlinx.coroutines.launch

private data class DetailInvalidationKey(
    val revisionStamp: String,
    val globalTiers: GlobalTierIndex,
)

private data class DetailStackTarget(
    val entryId: String? = null,
    val previousEntryId: String? = null,
)

internal class CatalogDetailFeatureModel(
    private val scope: CoroutineScope,
    private val repository: RocheRepository,
) {
    private val _detailState = MutableStateFlow<CatalogEntryDetailResult?>(null)
    val detailState: StateFlow<CatalogEntryDetailResult?> = _detailState.asStateFlow()

    private val _editingEntryState = MutableStateFlow<CatalogEntryItem?>(null)
    val editingEntryState: StateFlow<CatalogEntryItem?> = _editingEntryState.asStateFlow()

    fun startObserving(
        snapshotFlow: Flow<RepositorySnapshot>,
        transientFlow: Flow<TransientUiState>,
    ) {
        observeDetailSheet(snapshotFlow, transientFlow)
        observeEditingEntry(snapshotFlow, transientFlow)
    }

    private fun observeDetailSheet(
        snapshotFlow: Flow<RepositorySnapshot>,
        transientFlow: Flow<TransientUiState>,
    ) {
        val invalidationFlow =
            snapshotFlow
                .map { snap ->
                    DetailInvalidationKey(
                        revisionStamp = snap.catalogRevisionStamp,
                        globalTiers = snap.catalog.globalTiers,
                    )
                }
                .distinctUntilChanged()
        val detailTargetFlow =
            transientFlow
                .map { state ->
                    val stack = state.catalogDetailStack
                    DetailStackTarget(
                        entryId = stack.lastOrNull(),
                        previousEntryId = stack.dropLast(1).lastOrNull(),
                    )
                }
                .distinctUntilChanged()
        scope.launch {
            combine(invalidationFlow, detailTargetFlow) { inv, target -> inv to target }
                .collectLatest { (inv, target) -> refreshDetailSheet(inv, target) }
        }
    }

    private suspend fun refreshDetailSheet(
        inv: DetailInvalidationKey,
        target: DetailStackTarget,
    ) {
        val entryId = target.entryId?.trim()?.takeIf { it.isNotEmpty() }
        if (entryId == null) {
            _detailState.value = null
            return
        }
        val loaded = runSuspendCatching {
            repository.catalog.loadEntryDetail(
                entryId = entryId,
                previousEntryId = target.previousEntryId,
                globalTiers = inv.globalTiers,
            )
        }
            .getOrNull()
        if (loaded != null) {
            _detailState.value = loaded
        } else if (_detailState.value?.entry?.id != entryId) {
            _detailState.value = null
        }
    }

    private fun observeEditingEntry(
        snapshotFlow: Flow<RepositorySnapshot>,
        transientFlow: Flow<TransientUiState>,
    ) {
        val editIdFlow = transientFlow.map { it.editingCatalogEntryId }.distinctUntilChanged()
        scope.launch {
            combine(
                    snapshotFlow.map { it.catalogRevisionStamp }.distinctUntilChanged(),
                    editIdFlow,
                ) { rev, entryId ->
                    rev to entryId
                }
                .collectLatest { (_, rawId) -> refreshEditingEntry(rawId) }
        }
    }

    private suspend fun refreshEditingEntry(rawId: String?) {
        val entryId = rawId?.trim()?.takeIf { it.isNotEmpty() }
        if (entryId == null) {
            _editingEntryState.value = null
            return
        }
        val loaded = runSuspendCatching { repository.catalog.loadCatalogEntry(entryId) }.getOrNull()
        _editingEntryState.value = loaded
    }
}
