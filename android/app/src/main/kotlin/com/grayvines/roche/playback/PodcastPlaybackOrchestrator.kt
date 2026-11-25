package com.grayvines.roche.playback

import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.InboxEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val UP_NEXT_TITLE_LIMIT = 36

class PodcastPlaybackOrchestrator(
    private val repository: RocheRepository,
    private val settingsRepository: SettingsRepository,
) {
    internal fun syncPlayerFromLatestSettings(
        playerController: PodcastPlayerController,
        fallbackEntries: List<InboxEntry>? = null,
        fallbackQueueIds: List<String>? = null,
        fallbackSmartRewind: Boolean? = null,
        fallbackSkipSilence: Boolean? = null,
    ) {
        synchronized(playerController.settingsSyncLock) {
            val latestSettings = settingsRepository.latestSettings
            val latestEntries = repository.latestSnapshot?.entries ?: fallbackEntries
            val currentId = playerController.state.value.currentEntryId
            if (latestEntries != null) {
                val effectiveQueueIds = latestSettings?.queueItemIds ?: fallbackQueueIds
                if (effectiveQueueIds != null) {
                    val hasNext =
                        hasNextQueuedAudio(
                            latestEntries,
                            effectiveQueueIds,
                            excludeEntryId = currentId,
                        )
                    playerController.updateHasNextQueuedAudio(hasNext)
                }
            }
            val effectiveSmartRewind = latestSettings?.smartRewindEnabled ?: fallbackSmartRewind
            if (effectiveSmartRewind != null) {
                playerController.updateSmartRewindEnabled(effectiveSmartRewind)
            }
            val effectiveSkipSilence = latestSettings?.skipSilenceEnabled ?: fallbackSkipSilence
            if (effectiveSkipSilence != null) {
                playerController.syncSkipSilenceFromSettings(effectiveSkipSilence)
            }
        }
    }

    suspend fun playAudioEntry(
        playerController: PodcastPlayerController,
        entryId: String,
        toggleIfCurrent: Boolean = false,
    ): Boolean {
        val latestSnapshot = repository.snapshot.first()
        val latestSettings = settingsRepository.settings.first()
        val allEntries = latestSnapshot.entries
        val entry = allEntries.firstOrNull { it.id == entryId } ?: return false
        if (playerController.state.value.currentEntryId == entryId) {
            if (toggleIfCurrent || !playerController.state.value.isPlaying) {
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
        val currentSettings = settingsRepository.latestSettings ?: latestSettings
        val hasNextAudio = hasNextQueuedAudio(allEntries, updatedQueue, excludeEntryId = entryId)
        playerController.playEntry(
            entry = entry,
            sourceUriOrPath = source,
            speed = effectiveSpeed,
            skipSilenceEnabled = currentSettings.skipSilenceEnabled,
            hasNextQueuedAudio = hasNextAudio,
            smartRewindEnabled = currentSettings.smartRewindEnabled,
        )
        repository.recordEpisodePlaybackStarted(entryId)
        return true
    }

    suspend fun syncActivePlayerQueueState(playerController: PodcastPlayerController) {
        if (playerController.state.value.currentEntryId == null) return
        val fallbackSettings =
            settingsRepository.latestSettings ?: settingsRepository.settings.first()
        val fallbackEntries =
            repository.latestSnapshot?.entries ?: repository.snapshot.first().entries
        syncPlayerFromLatestSettings(
            playerController = playerController,
            fallbackEntries = fallbackEntries,
            fallbackQueueIds = fallbackSettings.queueItemIds,
            fallbackSmartRewind = fallbackSettings.smartRewindEnabled,
        )
    }

    fun startBackgroundObservers(
        scope: CoroutineScope,
        playerController: PodcastPlayerController,
        snapshotFlow: Flow<RepositorySnapshot> = repository.snapshot,
    ): Job {
        playerController.hasBackgroundObserver = true
        return scope.launch {
            combine(
                    snapshotFlow.map { it.entries }.distinctUntilChanged(),
                    settingsRepository.settings
                        .map { settings ->
                            Triple(
                                settings.queueItemIds,
                                settings.smartRewindEnabled,
                                settings.skipSilenceEnabled,
                            )
                        }
                        .distinctUntilChanged(),
                    playerController.state.map { it.currentEntryId }.distinctUntilChanged(),
                ) { entries, settingsSlice, currentEntryId ->
                    Triple(entries, settingsSlice, currentEntryId)
                }
                .collect { (entries, settingsSlice, currentEntryId) ->
                    syncPlayerFromLatestSettings(
                        playerController = playerController,
                        fallbackEntries = entries,
                        fallbackQueueIds = settingsSlice.first,
                        fallbackSmartRewind = settingsSlice.second,
                        fallbackSkipSilence = settingsSlice.third,
                    )
                    val latestEntries = repository.latestSnapshot?.entries ?: entries
                    currentEntryId
                        ?.let { id -> latestEntries.firstOrNull { it.id == id } }
                        ?.let(playerController::refreshChaptersFromEntry)
                }
        }
    }

    suspend fun handleAudioEpisodeCompleted(
        playerController: PodcastPlayerController,
        completedEntryId: String,
    ) {
        repository.updateReadProgress(
            itemId = completedEntryId,
            progress = 1.0,
            recordPlayedTimestamp = true,
            syncRemote = false,
            fromAudioPlayback = true,
        )
        repository.markFeedItemFinished(completedEntryId)
        settingsRepository.update { current ->
            current.copy(queueItemIds = current.queueItemIds.filterNot { it == completedEntryId })
        }
        val nextAudio = resolveNextQueuedAudioEntry(excludeEntryId = completedEntryId)
        if (nextAudio != null) {
            val started =
                playAudioEntry(
                    playerController = playerController,
                    entryId = nextAudio.id,
                    toggleIfCurrent = false,
                )
            if (started) {
                playerController.emitUiMessage(
                    "Up next · ${nextAudio.title.take(UP_NEXT_TITLE_LIMIT)}"
                )
            } else {
                playerController.stopAndClear()
                playerController.emitUiMessage("Could not play next episode")
            }
        } else {
            playerController.stopAndClear()
            playerController.emitUiMessage("Finished episode")
        }
    }

    suspend fun performSkipToNextQueuedAudio(playerController: PodcastPlayerController): Boolean {
        val currentId = playerController.state.value.currentEntryId
        val nextAudio = resolveNextQueuedAudioEntry(excludeEntryId = currentId)
        if (nextAudio == null) {
            playerController.emitUiMessage("No next audio episode in Audio Up Next")
            return false
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
        val started =
            playAudioEntry(
                playerController = playerController,
                entryId = nextAudio.id,
                toggleIfCurrent = false,
            )
        if (started) {
            playerController.emitUiMessage("Up next · ${nextAudio.title.take(UP_NEXT_TITLE_LIMIT)}")
        } else {
            playerController.stopAndClear()
            playerController.emitUiMessage("Could not play next episode")
        }
        return started
    }

    private suspend fun resolveNextQueuedAudioEntry(excludeEntryId: String?): InboxEntry? {
        val latestSettings = settingsRepository.settings.first()
        val latestEntries = repository.snapshot.first().entries
        return findNextQueuedAudioEntry(latestEntries, latestSettings.queueItemIds, excludeEntryId)
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

@Suppress("LongParameterList")
internal fun createManagedPlayerController(
    scope: CoroutineScope,
    repository: RocheRepository,
    settingsRepository: SettingsRepository,
    orchestrator: PodcastPlaybackOrchestrator =
        PodcastPlaybackOrchestrator(
            repository = repository,
            settingsRepository = settingsRepository,
        ),
    playerBackend: AudioPlayerBackend = NoOpAudioPlayerBackend(),
    clockMs: () -> Long = System::currentTimeMillis,
    observerScope: CoroutineScope? = null,
): PodcastPlayerController =
    PodcastPlayerController(
            scope = scope,
            backend = playerBackend,
            onProgressSaved = buildProgressSaveHandler(scope, repository, clockMs),
            onEpisodeCompleted = { active, completedEntryId ->
                scope.launch { orchestrator.handleAudioEpisodeCompleted(active, completedEntryId) }
            },
            onSkipToNextRequested = { active ->
                scope.launch { orchestrator.performSkipToNextQueuedAudio(active) }
            },
            onSpeedChangedFromSession = { active, speed ->
                scope.launch {
                    orchestrator.performSetPlaybackSpeed(
                        playerController = active,
                        speed = speed,
                        applyToCurrentPodcast = null,
                    )
                }
            },
        )
        .also { controller ->
            if (observerScope != null) {
                orchestrator.startBackgroundObservers(observerScope, controller)
            }
        }

private fun buildProgressSaveHandler(
    scope: CoroutineScope,
    repository: RocheRepository,
    clockMs: () -> Long,
): (String, Double, ProgressSaveReason) -> Unit {
    var lastRemoteSyncEntryId: String? = null
    var lastRemoteSyncEpochMs = 0L
    return { entryId, progress, reason ->
        val nowMs = clockMs()
        val isPeriodic = reason == ProgressSaveReason.PERIODIC_TICK
        val shouldSyncRemote =
            !isPeriodic ||
                lastRemoteSyncEntryId != entryId ||
                nowMs - lastRemoteSyncEpochMs >= REMOTE_PROGRESS_SYNC_INTERVAL_MS
        if (shouldSyncRemote) {
            lastRemoteSyncEntryId = entryId
            lastRemoteSyncEpochMs = nowMs
        }
        val shouldRecordPlayedAt =
            reason == ProgressSaveReason.PAUSE || reason == ProgressSaveReason.FINISH
        scope.launch {
            repository.updateReadProgress(
                itemId = entryId,
                progress = progress,
                recordPlayedTimestamp = shouldRecordPlayedAt,
                syncRemote = shouldSyncRemote,
                fromAudioPlayback = true,
            )
        }
    }
}

private const val REMOTE_PROGRESS_SYNC_INTERVAL_MS = 30_000L
