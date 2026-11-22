package com.grayvines.roche.ui

import com.grayvines.roche.data.ReaderItemContent
import com.grayvines.roche.data.RocheRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ReaderFeatureModel(
    private val scope: CoroutineScope,
    private val repository: RocheRepository,
    private val transientState: MutableStateFlow<TransientUiState>,
    private val activePlayerIdProvider: () -> String?,
    private val onActivePlayerNotesUpdated: (String, ReaderItemContent) -> Unit,
) {
    fun hydrateEntryIfNeeded(entryId: String) {
        scope.launch {
            try {
                val hydrated =
                    repository.hydrateItemContentIfNeeded(entryId) { localContent ->
                        applyReaderContentIfActive(entryId, localContent)
                    }
                if (hydrated != null) {
                    applyReaderContentIfActive(entryId, hydrated)
                }
            } finally {
                transientState.update { current ->
                    if (entryId in current.hydratingEntryIds) {
                        current.copy(hydratingEntryIds = current.hydratingEntryIds - entryId)
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun applyReaderContentIfActive(entryId: String, content: ReaderItemContent) {
        val activePlayerId = activePlayerIdProvider()
        transientState.update { current ->
            val nextReader =
                if (current.selectedReaderEntryId == entryId) content else current.readerContent
            val nextPlayerNotes =
                if (activePlayerId == entryId) content else current.activePlayerNotesContent
            if (
                nextReader !== current.readerContent ||
                    nextPlayerNotes !== current.activePlayerNotesContent
            ) {
                current.copy(
                    readerContent = nextReader,
                    activePlayerNotesContent = nextPlayerNotes,
                )
            } else {
                current
            }
        }
        if (activePlayerId == entryId) {
            onActivePlayerNotesUpdated(entryId, content)
        }
    }

    suspend fun refreshActiveEpisodeNotes(entryId: String?) {
        if (entryId == null) {
            clearPlayerNotesUi()
            return
        }
        transientState.update { current ->
            val kept = current.activePlayerNotesContent?.takeIf { it.itemId == entryId }
            if (kept !== current.activePlayerNotesContent) {
                current.copy(activePlayerNotesContent = kept)
            } else {
                current
            }
        }
        val hydrated =
            repository.hydrateItemContentIfNeeded(entryId) { localContent ->
                applyReaderContentIfActive(entryId, localContent)
            }
        if (hydrated != null) {
            applyReaderContentIfActive(entryId, hydrated)
        }
    }

    private fun clearPlayerNotesUi() {
        transientState.update { current ->
            val hasActivePlayerUi =
                current.activePlayerNotesContent != null ||
                    current.isPlayerFullscreenOpen ||
                    current.isPlayerSpeedSheetOpen ||
                    current.activePlayerDrawerTab != null
            if (hasActivePlayerUi) {
                current.copy(
                    activePlayerNotesContent = null,
                    isPlayerFullscreenOpen = false,
                    isPlayerSpeedSheetOpen = false,
                    activePlayerDrawerTab = null,
                )
            } else {
                current
            }
        }
    }
}
