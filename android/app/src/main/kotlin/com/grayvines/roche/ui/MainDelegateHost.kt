package com.grayvines.roche.ui

import com.grayvines.roche.data.ReaderItemContent
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.playback.PodcastPlaybackOrchestrator
import com.grayvines.roche.playback.PodcastPlayerController

internal interface MainDelegateHost {
    val repository: RocheRepository
    val settingsRepository: SettingsRepository

    fun currentTransientState(): TransientUiState

    fun updateTransient(transform: (TransientUiState) -> TransientUiState)

    fun showStatusMessage(message: String)

    fun showUndoAction(event: UndoActionEvent)

    suspend fun latestEntries(): List<InboxEntry>

    suspend fun cleanupRemovedEntries(removedIds: Set<String>)
}

internal interface InboxReaderHost : MainDelegateHost {
    val playerController: PodcastPlayerController
    val playbackOrchestrator: PodcastPlaybackOrchestrator

    fun cancelDeepSearch()

    fun applyReaderContentIfActive(entryId: String, content: ReaderItemContent)

    fun refreshOfflineSizes()
}

internal interface SyncFeedHost : MainDelegateHost {
    fun tryAcquireSyncLock(): Boolean

    fun isManualRefreshing(): Boolean

    fun setManualRefreshing(refreshing: Boolean)

    suspend fun awaitSyncFinished()

    fun refreshLocalNetworkTier()

    suspend fun awaitInitialLoad()
}
