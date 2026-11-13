package com.grayvines.roche.ui

import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class PodcastPlaybackOrchestrator(
    private val repository: RocheRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun playAudioEntry(
        playerController: PodcastPlayerController,
        entryId: String,
        openFullscreen: Boolean = true,
    ): Boolean {
        val latestSnapshot = repository.snapshot.first()
        val latestSettings = settingsRepository.settings.first()
        val allEntries = latestSnapshot.entries
        val entry = allEntries.firstOrNull { it.id == entryId } ?: return false
        if (playerController.state.value.currentEntryId == entryId) {
            if (openFullscreen) {
                playerController.openFullscreen()
                if (!playerController.state.value.isPlaying) {
                    playerController.togglePlayPause(latestSettings.smartRewindEnabled)
                }
            } else {
                playerController.togglePlayPause(latestSettings.smartRewindEnabled)
            }
            return true
        }
        val feed = entry.feedId?.let { fid -> latestSnapshot.feeds.firstOrNull { it.id == fid } }
        val effectiveSpeed = feed?.customPlaybackSpeed ?: latestSettings.defaultPlaybackSpeed
        val source = repository.resolveAudioPlaybackSource(entry)
        var updatedQueue: List<String> = latestSettings.queueItemIds
        settingsRepository.update { current ->
            val nextQueue =
                if (entryId in current.queueItemIds) {
                    current.queueItemIds
                } else {
                    listOf(entryId) + current.queueItemIds
                }
            updatedQueue = nextQueue
            current.copy(queueItemIds = nextQueue)
        }
        val entriesById = allEntries.associateBy { it.id }
        val hasNextAudio = updatedQueue.any { id ->
            id != entryId &&
                entriesById[id]?.let { !it.isArchived && it.contentType == ContentType.AUDIO } ==
                    true
        }
        playerController.playEntry(
            entry = entry,
            sourceUriOrPath = source,
            speed = effectiveSpeed,
            skipSilenceEnabled = latestSettings.skipSilenceEnabled,
            openFullscreen = openFullscreen,
            hasNextQueuedAudio = hasNextAudio,
            smartRewindEnabled = latestSettings.smartRewindEnabled,
        )
        repository.recordEpisodePlaybackStarted(entryId)
        return true
    }

    suspend fun syncActivePlayerQueueState(playerController: PodcastPlayerController) {
        val currentId = playerController.state.value.currentEntryId ?: return
        val latestSettings = settingsRepository.settings.first()
        val latestEntries = repository.snapshot.first().entries
        val byId = latestEntries.associateBy { it.id }
        val hasNext =
            latestSettings.queueItemIds.any { id ->
                id != currentId &&
                    byId[id]?.let { !it.isArchived && it.contentType == ContentType.AUDIO } == true
            }
        playerController.updateHasNextQueuedAudio(hasNext)
        playerController.updateSmartRewindEnabled(latestSettings.smartRewindEnabled)
    }

    suspend fun handleAudioEpisodeCompleted(
        playerController: PodcastPlayerController,
        completedEntryId: String,
    ) {
        val wasFullscreen = playerController.state.value.isFullscreenOpen
        repository.updateReadProgress(itemId = completedEntryId, progress = 1.0)
        repository.setArchiveStateWithSnapshot(itemId = completedEntryId, archived = true)
        settingsRepository.update { current ->
            current.copy(queueItemIds = current.queueItemIds.filterNot { it == completedEntryId })
        }
        val nextAudio = resolveNextQueuedAudioEntry(excludeEntryId = completedEntryId)
        if (nextAudio != null) {
            playAudioEntry(
                playerController = playerController,
                entryId = nextAudio.id,
                openFullscreen = wasFullscreen,
            )
            playerController.emitUiMessage(
                "Up next · ${nextAudio.title.take(SNACKBAR_TITLE_LIMIT)}"
            )
        } else {
            playerController.stopAndClear()
            playerController.emitUiMessage("Finished episode · Archived & deleted download")
        }
    }

    suspend fun performSkipToNextQueuedAudio(playerController: PodcastPlayerController) {
        val currentState = playerController.state.value
        val currentId = currentState.currentEntryId
        val nextAudio = resolveNextQueuedAudioEntry(excludeEntryId = currentId)
        if (nextAudio == null) {
            playerController.emitUiMessage("No next audio episode in Queue")
            return
        }
        if (currentId != null) {
            playerController.flushCurrentProgressIfNeeded()
            settingsRepository.update { current ->
                if (currentId in current.queueItemIds) {
                    current.copy(
                        queueItemIds =
                            current.queueItemIds.filterNot { it == currentId } + currentId
                    )
                } else {
                    current
                }
            }
        }
        val openFullscreen = if (currentId != null) currentState.isFullscreenOpen else true
        playAudioEntry(
            playerController = playerController,
            entryId = nextAudio.id,
            openFullscreen = openFullscreen,
        )
        playerController.emitUiMessage("Up next · ${nextAudio.title.take(SNACKBAR_TITLE_LIMIT)}")
    }

    private suspend fun resolveNextQueuedAudioEntry(excludeEntryId: String?): InboxEntry? {
        val latestSettings = settingsRepository.settings.first()
        val latestEntries = repository.snapshot.first().entries
        val byId = latestEntries.associateBy { it.id }
        return latestSettings.queueItemIds
            .mapNotNull { byId[it] }
            .firstOrNull {
                !it.isArchived && it.contentType == ContentType.AUDIO && it.id != excludeEntryId
            }
    }

    suspend fun performSetPlaybackSpeed(
        playerController: PodcastPlayerController,
        speed: Float,
        applyToCurrentPodcast: Boolean?,
    ) {
        val clamped = clampPlaybackSpeed(speed)
        playerController.setPlaybackSpeed(clamped)
        val currentEntryId = playerController.state.value.currentEntryId
        val latestSnapshot = repository.snapshot.first()
        val activeEntry = currentEntryId?.let { id ->
            latestSnapshot.entries.firstOrNull { it.id == id }
        }
        val activeFeed =
            activeEntry?.feedId?.let { fid -> latestSnapshot.feeds.firstOrNull { it.id == fid } }
        val rememberForFeed = applyToCurrentPodcast ?: (activeFeed?.customPlaybackSpeed != null)
        if (rememberForFeed && activeFeed != null) {
            repository.setFeedPlaybackSpeed(feedId = activeFeed.id, speed = clamped)
        } else {
            settingsRepository.update { it.copy(defaultPlaybackSpeed = clamped) }
        }
    }
}

internal fun createManagedPlayerController(
    scope: CoroutineScope,
    repository: RocheRepository,
    settingsRepository: SettingsRepository,
    playerBackend: AudioPlayerBackend = ResilientMediaPlayerBackend(),
    mediaSessionBridge: PodcastMediaSessionBridge = NoOpPodcastMediaSessionBridge,
): PodcastPlayerController {
    val orchestrator =
        PodcastPlaybackOrchestrator(
            repository = repository,
            settingsRepository = settingsRepository,
        )
    var controllerRef: PodcastPlayerController? = null
    val controller =
        PodcastPlayerController(
            scope = scope,
            backend = playerBackend,
            mediaSessionBridge = mediaSessionBridge,
            onProgressChanged = { entryId, progress ->
                scope.launch {
                    repository.updateReadProgress(
                        itemId = entryId,
                        progress = progress,
                        recordPlayedTimestamp = true,
                    )
                }
            },
            onEpisodeCompleted = { completedEntryId ->
                controllerRef?.let { active ->
                    scope.launch {
                        orchestrator.handleAudioEpisodeCompleted(active, completedEntryId)
                    }
                }
            },
            onSkipToNextRequested = {
                controllerRef?.let { active ->
                    scope.launch {
                        orchestrator.performSkipToNextQueuedAudio(active)
                    }
                }
            },
            onSpeedChangedFromSession = { speed ->
                controllerRef?.let { active ->
                    scope.launch {
                        orchestrator.performSetPlaybackSpeed(
                            playerController = active,
                            speed = speed,
                            applyToCurrentPodcast = null,
                        )
                    }
                }
            },
        )
    controllerRef = controller
    return controller
}
