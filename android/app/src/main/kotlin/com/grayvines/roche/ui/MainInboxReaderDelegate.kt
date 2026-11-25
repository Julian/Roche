package com.grayvines.roche.ui

import com.grayvines.roche.core.isLikelyFeedUrl
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.SaveUrlResult
import com.grayvines.roche.data.feeds.normalizeUserSubmittedUrl
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.extractCleanDomain
import kotlinx.coroutines.flow.first

@Suppress("TooManyFunctions")
internal class MainInboxReaderDelegate(private val host: InboxReaderHost) {
    internal val inFlightStarCounts = mutableMapOf<String, Int>()
    private val queueDelegate = MainQueueActionsDelegate(host)

    internal suspend fun execute(action: MainAction.InboxReaderMutation) {
        when (action) {
            is MainAction.EntryMutation -> executeEntryMutation(action)
            is MainAction.PlaybackMutation -> executePlaybackMutation(action)
            is MainAction.QueueFeedAudioMutation -> executeQueueFeedAudioMutation(action)
        }
    }

    private suspend fun executeEntryMutation(action: MainAction.EntryMutation) {
        when (action) {
            is MainAction.ToggleArchive -> performToggleArchive(action.entry)
            MainAction.UndoLastArchive -> performUndoArchive()
            MainAction.ArchiveVisibleEntries -> performArchiveVisibleEntries()
            is MainAction.ToggleStar -> performToggleStar(action.entryId)
            is MainAction.ToggleReadState -> performToggleReadState(action.entryId)
            is MainAction.ToggleOfflineCache -> performToggleOffline(action.entryId)
            is MainAction.CacheEntryOffline -> performCacheOffline(action.entryId)
            MainAction.EvictPlayedOfflineEntries -> performEvictPlayedOffline(host)
            is MainAction.ToggleTagOnEntry ->
                host.repository.toggleTagOnItem(itemId = action.entryId, tagName = action.tagName)
            is MainAction.UpdateReadProgress ->
                performUpdateReadProgress(action.entryId, action.progress)
            is MainAction.SaveUrlOrFeed ->
                performSaveUrl(
                    url = action.url,
                    titleOverride = action.titleOverride,
                    tagCsv = action.tagCsv,
                    explicitMode = action.mode,
                    feedType = action.feedType,
                )
            is MainAction.DeleteEntry -> performDeleteEntry(action.entryId)
        }
    }

    private suspend fun executePlaybackMutation(action: MainAction.PlaybackMutation) {
        when (action) {
            is MainAction.PlayAudioEntry ->
                performPlayAudioEntry(action.entryId, action.openFullscreen)
            MainAction.TogglePlayPause -> performTogglePlayPause()
            is MainAction.SeekPlayerToFraction ->
                host.playerController.seekToFraction(action.fraction)
            is MainAction.SeekPlayerToMs -> host.playerController.seekToMs(action.positionMs)
            is MainAction.SkipPlayerBySeconds ->
                host.playerController.skipBySeconds(action.deltaSeconds)
            MainAction.SkipToNextQueuedAudio -> performSkipToNextQueuedAudio()
            MainAction.DismissMiniPlayer -> performDismissMiniPlayer()
            is MainAction.SetPlaybackSpeed ->
                host.playbackOrchestrator.performSetPlaybackSpeed(
                    playerController = host.playerController,
                    speed = action.speed,
                    applyToCurrentPodcast = action.applyToCurrentPodcast,
                )
            is MainAction.SetRememberPodcastSpeed ->
                performSetRememberPodcastSpeed(action.feedId, action.enabled)
            is MainAction.SetSkipSilence -> performSetSkipSilence(action.enabled)
            is MainAction.SetSmartRewind -> performSetSmartRewind(action.enabled)
            is MainAction.SetSleepTimerMinutes -> performSetSleepTimerMinutes(action.minutes)
        }
    }

    private suspend fun performTogglePlayPause() {
        val smartRewind = host.settingsRepository.settings.first().smartRewindEnabled
        host.playerController.togglePlayPause(smartRewindEnabled = smartRewind)
    }

    private fun performDismissMiniPlayer() {
        host.playerController.flushCurrentProgressIfNeeded()
        host.playerController.stopAndClear()
        host.updateTransient {
            it.copy(
                isPlayerFullscreenOpen = false,
                isPlayerSpeedSheetOpen = false,
                activePlayerDrawerTab = null,
            )
        }
    }

    private suspend fun performSetSkipSilence(enabled: Boolean) {
        host.settingsRepository.update { it.copy(skipSilenceEnabled = enabled) }
        host.playbackOrchestrator.syncPlayerFromLatestSettings(
            playerController = host.playerController,
            fallbackSkipSilence = enabled,
        )
    }

    private suspend fun performSetSmartRewind(enabled: Boolean) {
        host.settingsRepository.update { it.copy(smartRewindEnabled = enabled) }
        host.playbackOrchestrator.syncPlayerFromLatestSettings(
            playerController = host.playerController,
            fallbackSmartRewind = enabled,
        )
    }

    private suspend fun executeQueueFeedAudioMutation(action: MainAction.QueueFeedAudioMutation) {
        when (action) {
            is MainAction.SetFeedAutoDownload ->
                performSetFeedAutoDownload(action.feedId, action.enabled)
            is MainAction.SetFeedPlaybackSpeed ->
                performSetFeedPlaybackSpeed(action.feedId, action.speed)
            is MainAction.ToggleQueueMembership -> queueDelegate.toggleMembership(action.entryId)
            is MainAction.AddToQueue -> queueDelegate.addToQueue(action.entryId, action.playNext)
            is MainAction.RemoveFromQueue ->
                queueDelegate.removeFromQueue(action.entryId, showBanner = true)
            is MainAction.MoveQueueItemUp -> queueDelegate.moveUp(action.entryId)
            is MainAction.MoveQueueItemToTop -> queueDelegate.moveToTop(action.entryId)
            is MainAction.ReorderQueue -> queueDelegate.reorder(action.orderedEntryIds)
        }
    }

    internal suspend fun performPlayAudioEntry(
        entryId: String,
        openFullscreen: Boolean = true,
    ) {
        val played =
            host.playbackOrchestrator.playAudioEntry(
                playerController = host.playerController,
                entryId = entryId,
                toggleIfCurrent = !openFullscreen,
            )
        if (played) {
            host.updateTransient { current ->
                if (openFullscreen) {
                    current.copy(
                        selectedReaderEntryId = null,
                        readerContent = current.readerContent?.takeIf { it.itemId == entryId },
                        contextSheetEntryId = null,
                        isPlayerFullscreenOpen = true,
                        activePlayerDrawerTab = null,
                    )
                } else {
                    current.copy(contextSheetEntryId = null)
                }
            }
        }
    }

    private suspend fun performSkipToNextQueuedAudio() {
        val wasIdle = !host.playerController.state.value.hasActiveEpisode
        val started = host.playbackOrchestrator.performSkipToNextQueuedAudio(host.playerController)
        if (started && wasIdle) {
            host.updateTransient {
                it.copy(
                    isPlayerFullscreenOpen = true,
                    activePlayerDrawerTab = null,
                )
            }
        }
    }

    internal suspend fun performToggleStar(
        entryId: String,
        targetFavorite: Boolean? = null,
        bannerAlreadyShown: Boolean = false,
    ) {
        var failed = false
        try {
            runSuspendCatching {
                host.repository.toggleStar(itemId = entryId, targetFavorite = targetFavorite)
            }
                .onSuccess { starred ->
                    if (!bannerAlreadyShown) {
                        host.showStatusMessage(if (starred) "Starred" else "Removed star")
                    }
                }
                .onFailure { err ->
                    failed = true
                    host.showStatusMessage("Could not update star (${err.message ?: "error"})")
                }
        } finally {
            if (bannerAlreadyShown) {
                clearSettledOptimisticStar(entryId, forceClear = failed)
            }
        }
    }

    private fun clearSettledOptimisticStar(entryId: String, forceClear: Boolean = false) {
        val remaining =
            synchronized(inFlightStarCounts) {
                val next = ((inFlightStarCounts[entryId] ?: 1) - 1).coerceAtLeast(0)
                if (next == 0) {
                    inFlightStarCounts.remove(entryId)
                } else {
                    inFlightStarCounts[entryId] = next
                }
                next
            }
        if (remaining == 0) {
            val persistedFav =
                host.repository.latestSnapshot
                    ?.entries
                    ?.firstOrNull { it.id == entryId }
                    ?.isFavorite
            val settled =
                persistedFav != null &&
                    host.currentTransientState().optimisticFavorites[entryId] == persistedFav
            if (forceClear || settled) {
                host.updateTransient { current ->
                    current.copy(optimisticFavorites = current.optimisticFavorites - entryId)
                }
            }
        }
    }

    internal fun reconcileOptimisticStars(persistedById: Map<String, Boolean>) {
        val currentOpt = host.currentTransientState().optimisticFavorites
        if (currentOpt.isEmpty()) return
        val settledIds =
            synchronized(inFlightStarCounts) {
                currentOpt.keys.filter { id ->
                    inFlightStarCounts[id] ?: 0 == 0 && persistedById[id] == currentOpt[id]
                }
            }
        if (settledIds.isNotEmpty()) {
            host.updateTransient { current ->
                current.copy(optimisticFavorites = current.optimisticFavorites - settledIds.toSet())
            }
        }
    }

    private suspend fun performToggleReadState(entryId: String) {
        val isRead = host.repository.toggleReadState(entryId)
        host.updateTransient {
            it.copy(
                contextSheetEntryId = null,
                undoEvent = null,
                statusBanner = if (isRead) "Marked as read" else "Marked as unread",
                messageSequence = it.messageSequence + 1,
            )
        }
    }

    private suspend fun performToggleOffline(entryId: String) {
        val prevEntry = host.latestEntries().firstOrNull { it.id == entryId }
        val wasCached = prevEntry?.isCachedOffline == true
        val cached = host.repository.toggleOfflineCache(entryId)
        host.refreshOfflineSizes()
        if (cached && host.currentTransientState().selectedReaderEntryId == entryId) {
            host.repository.hydrateItemContentIfNeeded(entryId)?.let { content ->
                host.applyReaderContentIfActive(entryId, content)
            }
        }
        val entry = host.latestEntries().firstOrNull { it.id == entryId } ?: prevEntry
        val isAudio = entry?.isPlayableAudio == true
        if (wasCached && !cached) {
            val evictedLabel = if (isAudio) "Removed audio download" else "Removed offline copy"
            val titlePart = entry?.title?.take(SNACKBAR_TITLE_LIMIT)?.let { " · $it" }.orEmpty()
            host.showUndoAction(
                UndoActionEvent(
                    message = "$evictedLabel$titlePart",
                    kind = UndoActionKind.RestoreOfflineCache(entryId),
                )
            )
            return
        }
        val msg =
            when {
                cached && isAudio -> "Downloaded audio for offline playback"
                cached -> "Cached local copy"
                isAudio -> "Failed to download audio for offline playback"
                else -> "Failed to cache offline copy"
            }
        host.showStatusMessage(msg)
    }

    private suspend fun performCacheOffline(entryId: String) {
        val prevEntry = host.latestEntries().firstOrNull { it.id == entryId }
        val cached = host.repository.cacheEntryOffline(entryId)
        host.refreshOfflineSizes()
        if (cached && host.currentTransientState().selectedReaderEntryId == entryId) {
            host.repository.hydrateItemContentIfNeeded(entryId)?.let { content ->
                host.applyReaderContentIfActive(entryId, content)
            }
        }
        val entry = host.latestEntries().firstOrNull { it.id == entryId } ?: prevEntry
        val isAudio = entry?.isPlayableAudio == true
        val isPdf = entry != null && isPdfLikeEntry(entry)
        val msg =
            when {
                cached && isAudio -> "Downloaded audio for offline playback"
                cached && isPdf -> "Downloaded PDF for offline reading"
                cached -> "Cached local copy"
                isAudio -> "Failed to download audio for offline playback"
                isPdf -> "Failed to download PDF"
                else -> "Failed to cache offline copy"
            }
        host.showStatusMessage(msg)
    }

    private suspend fun performToggleArchive(entry: InboxEntry) {
        val targetDismiss = !entry.isDismissed
        val snapshot =
            host.repository.setArchiveStateWithSnapshot(
                itemId = entry.id,
                archived = targetDismiss,
            )
        if (targetDismiss) {
            host.cleanupRemovedEntries(setOf(entry.id))
        }
        val previousStatus = snapshot?.previousStatus ?: entry.status
        val previousReadAt = snapshot?.previousReadAt ?: entry.readAt
        val wasFinished = snapshot?.wasFinished ?: entry.isFinished
        val previousProgress = snapshot?.previousProgress ?: entry.readProgress
        val label = resolveDismissSnackbarLabel(entry, targetDismiss, wasFinished)
        host.updateTransient { current ->
            val nextReaderId =
                if (current.selectedReaderEntryId == entry.id) {
                    null
                } else {
                    current.selectedReaderEntryId
                }
            current.copy(
                selectedReaderEntryId = nextReaderId,
                readerContent = nextReaderId?.let { current.readerContent },
                contextSheetEntryId = null,
                statusBanner = null,
                messageSequence = current.messageSequence + 1,
                undoEvent =
                    UndoActionEvent(
                        message = "$label · ${entry.title.take(SNACKBAR_TITLE_LIMIT)}",
                        kind =
                            UndoActionKind.RestoreEntryArchive(
                                itemId = entry.id,
                                previousStatus = previousStatus,
                                previousReadAt = previousReadAt,
                                wasFinished = wasFinished,
                                previousProgress = previousProgress,
                            ),
                    ),
            )
        }
    }

    private fun resolveDismissSnackbarLabel(
        entry: InboxEntry,
        targetDismiss: Boolean,
        wasFinished: Boolean,
    ): String =
        when {
            !entry.inFeedInbox && targetDismiss -> "Dismissed from Queue"
            !entry.inFeedInbox -> "Restored to Queue"
            targetDismiss && wasFinished -> "Dismissed · Removed from Diary"
            targetDismiss -> "Dismissed"
            else -> "Marked new"
        }

    private suspend fun performUndoArchive() {
        var pending: UndoActionEvent? = null
        host.updateTransient { current ->
            pending = current.undoEvent
            if (current.undoEvent != null) current.copy(undoEvent = null) else current
        }
        val event = pending ?: return
        runSuspendCatching { executeUndoKind(event.kind) }
            .onFailure { err ->
                host.showStatusMessage("Could not undo (${err.message ?: "error"})")
            }
    }

    private suspend fun executeUndoKind(kind: UndoActionKind) {
        when (kind) {
            is UndoActionKind.RestoreEntryArchive -> {
                host.repository.restoreStatus(
                    itemId = kind.itemId,
                    status = kind.previousStatus,
                    readAt = kind.previousReadAt,
                    wasFinished = kind.wasFinished,
                    previousProgress = kind.previousProgress,
                )
                host.showStatusMessage("Restored entry")
            }
            is UndoActionKind.RestoreBatchArchive -> {
                host.repository.restoreArchiveSnapshots(kind.batchSnapshots)
                host.showStatusMessage("Restored ${kind.batchSnapshots.size} entries")
            }
            is UndoActionKind.RestoreCollectionEntry -> {
                val targetCols = kind.collectionIds.ifEmpty { listOf(kind.collectionId) }
                val restored =
                    host.repository.catalog.addEntryToCollections(
                        collectionIds = targetCols,
                        entryId = kind.entryId,
                        ignoreMissingCollections = true,
                    )
                val msg =
                    if (restored.isNotEmpty()) {
                        "Restored to ${kind.label}"
                    } else {
                        "Could not restore to ${kind.label}"
                    }
                host.showStatusMessage(msg)
            }
            is UndoActionKind.RestoreQueueItem -> {
                restoreQueueItemAtIndex(kind.entryId, kind.previousIndex)
                host.showStatusMessage("Restored to Audio Up Next")
            }
            is UndoActionKind.RestoreOfflineCache -> {
                val restored = host.repository.cacheEntryOffline(kind.entryId)
                host.refreshOfflineSizes()
                val msg = if (restored) "Restored local offline copy" else "Could not restore copy"
                host.showStatusMessage(msg)
            }
            is UndoActionKind.RestoreBoardDismiss -> {
                host.repository.undismissBoardItem(kind.boardId, kind.externalId)
                host.updateTransient { current ->
                    patchBoardItemInTransient(current, kind.boardId, kind.externalId) {
                        it.copy(dismissedAt = null)
                    }
                }
                host.showStatusMessage("Restored story")
            }
        }
    }

    private suspend fun restoreQueueItemAtIndex(entryId: String, previousIndex: Int) {
        host.settingsRepository.update { current ->
            val next = current.queueItemIds.filterNot { it == entryId }.toMutableList()
            next.add(previousIndex.coerceIn(0, next.size), entryId)
            current.copy(queueItemIds = next)
        }
        host.playbackOrchestrator.syncActivePlayerQueueState(host.playerController)
    }

    private suspend fun performArchiveVisibleEntries() {
        val local = host.currentTransientState()
        val entries = applyOptimisticFavorites(host.latestEntries(), local.optimisticFavorites)
        val visible =
            RocheRepository.filterEntries(
                entries = entries,
                filter = local.activeFilter,
                searchQuery = local.searchQuery,
                selectedTag = local.selectedTag,
                selectedFeedId = local.selectedFeedId,
            )
        val newIds = visible.filter { it.isNew }.map { it.id }
        if (newIds.isEmpty()) return
        val snapshots =
            host.repository.archiveBatchWithSnapshots(
                entryIds = newIds,
                feedId = local.selectedFeedId,
                allUnread =
                    local.selectedFeedId == null &&
                        local.selectedTag == null &&
                        local.searchQuery.isBlank() &&
                        local.activeFilter == InboxFilter.UNREAD,
            )
        if (snapshots.isEmpty()) return
        val dismissedIds = snapshots.map { it.itemId }.toSet()
        host.cleanupRemovedEntries(dismissedIds)
        val dismissedCount = snapshots.size
        val noun = entryCountNoun(dismissedCount)
        host.showUndoAction(
            UndoActionEvent(
                message = "Dismissed $dismissedCount $noun",
                kind = UndoActionKind.RestoreBatchArchive(snapshots),
            )
        )
    }

    private suspend fun performUpdateReadProgress(entryId: String, progress: Double) {
        val activePlayer = host.playerController.state.value
        val isAudioActiveForEntry =
            activePlayer.currentEntryId == entryId && activePlayer.hasActiveEpisode
        if (!isAudioActiveForEntry) {
            host.repository.updateReadProgress(
                itemId = entryId,
                progress = progress,
                fromAudioPlayback = false,
            )
        }
    }

    private suspend fun performSaveUrl(
        url: String,
        titleOverride: String?,
        tagCsv: String,
        explicitMode: AddSheetMode?,
        feedType: FeedType? = null,
    ) {
        val cleanUrl = normalizeUserSubmittedUrl(url)
        if (cleanUrl.isBlank()) return
        val resolvedMode =
            explicitMode
                ?: if (isLikelyFeedUrl(cleanUrl)) {
                    AddSheetMode.SUBSCRIBE_FEED
                } else {
                    AddSheetMode.WANT_TO_READ
                }
        host.cancelDeepSearch()
        host.updateTransient {
            it.clearSearch()
                .copy(
                    isAddSheetOpen = false,
                    prefilledShareUrl = "",
                    prefilledShareTitle = "",
                    prefilledShareIsFeed = false,
                    selectedReaderEntryId = null,
                    readerContent = null,
                    undoEvent = null,
                )
        }
        if (resolvedMode == AddSheetMode.SUBSCRIBE_FEED) {
            performSubscribeFeedAction(cleanUrl, titleOverride, feedType)
        } else {
            val created =
                host.repository.addUrlToWantToRead(
                    inputUrl = cleanUrl,
                    titleOverride = titleOverride,
                    tagCsv = tagCsv,
                )
            host.showStatusMessage(
                "Added to Want to Read · \"${created.title.take(SNACKBAR_TITLE_LIMIT)}\""
            )
        }
    }

    private suspend fun performSubscribeFeedAction(
        url: String,
        titleOverride: String?,
        feedType: FeedType? = null,
    ) {
        val hostLabel = extractCleanDomain(url).ifBlank { url }.take(SNACKBAR_TITLE_LIMIT)
        val kindLabel = if (feedType == FeedType.PODCAST) "podcast" else "RSS"
        host.showStatusMessage("Finding $kindLabel feed for $hostLabel…")
        if (host.repository.hasBackgroundScope) {
            host.repository.subscribeFeedInBackground(
                inputUrl = url,
                titleOverride = titleOverride,
                feedType = feedType,
                onComplete = ::handleSubscribeFeedResult,
            )
        } else {
            val result =
                host.repository.discoverAndSubscribeFeed(
                    inputUrl = url,
                    titleOverride = titleOverride,
                    feedType = feedType,
                )
            handleSubscribeFeedResult(result)
        }
    }

    private fun handleSubscribeFeedResult(result: Result<SaveUrlResult>) {
        result
            .onSuccess { saved ->
                host.showStatusMessage("Subscribed \"${saved.title.take(SNACKBAR_TITLE_LIMIT)}\"")
            }
            .onFailure { err ->
                host.showStatusMessage(err.message ?: "Could not subscribe to feed")
            }
    }

    private suspend fun performDeleteEntry(entryId: String) {
        host.repository
            .deleteItem(entryId)
            .onSuccess { title ->
                host.cleanupRemovedEntries(setOf(entryId))
                host.showStatusMessage("Deleted \"${title.take(SNACKBAR_TITLE_LIMIT)}\"")
            }
            .onFailure { error ->
                host.showStatusMessage("Delete failed (${error.message ?: "error"})")
            }
    }

    private fun performSetSleepTimerMinutes(minutes: Int?) {
        host.playerController.setSleepTimerMinutes(minutes)
        val msg = if (minutes == null) "Sleep timer off" else "Sleep timer set for ${minutes}m"
        host.showStatusMessage(msg)
    }

    private suspend fun performSetRememberPodcastSpeed(feedId: String, enabled: Boolean) {
        val currentSpeed = host.playerController.state.value.playbackSpeed
        host.repository.setFeedPlaybackSpeed(
            feedId = feedId,
            speed = if (enabled) currentSpeed else null,
        )
    }

    private suspend fun performSetFeedAutoDownload(feedId: String, enabled: Boolean) {
        host.repository.setFeedAutoDownload(feedId = feedId, enabled = enabled)
        val msg =
            if (enabled) {
                "Auto-downloading new episodes for this podcast"
            } else {
                "Disabled auto-download for this podcast"
            }
        host.showStatusMessage(msg)
    }

    private suspend fun performSetFeedPlaybackSpeed(feedId: String, speed: Float?) {
        val clamped = speed?.let(::clampPlaybackSpeed)
        host.repository.setFeedPlaybackSpeed(feedId = feedId, speed = clamped)
        val currentEntryId = host.playerController.state.value.currentEntryId
        val activeEntry = currentEntryId?.let { id ->
            host.latestEntries().firstOrNull { it.id == id }
        }
        if (activeEntry?.feedId == feedId && clamped != null) {
            host.playerController.setPlaybackSpeed(clamped)
        }
    }
}

internal class MainQueueActionsDelegate(private val host: InboxReaderHost) {
    suspend fun toggleMembership(entryId: String) {
        val inQueue = entryId in host.settingsRepository.settings.first().queueItemIds
        if (inQueue) {
            removeFromQueue(entryId, showBanner = true)
        } else {
            addToQueue(entryId, playNext = false)
        }
    }

    suspend fun addToQueue(entryId: String, playNext: Boolean) {
        val targetEntry = host.latestEntries().firstOrNull { it.id == entryId }
        if (targetEntry == null || !targetEntry.isPlayableAudio) {
            host.updateTransient { it.copy(contextSheetEntryId = null) }
            return
        }
        val activeAudioId = host.playerController.state.value.currentEntryId
        host.settingsRepository.update { current ->
            val withoutTarget = current.queueItemIds.filterNot { it == entryId }.toMutableList()
            if (playNext) {
                val insertIndex =
                    if (activeAudioId != null && withoutTarget.firstOrNull() == activeAudioId) {
                        1
                    } else {
                        0
                    }
                withoutTarget.add(insertIndex.coerceAtMost(withoutTarget.size), entryId)
            } else {
                withoutTarget.add(entryId)
            }
            current.copy(queueItemIds = withoutTarget)
        }
        host.playbackOrchestrator.syncActivePlayerQueueState(host.playerController)
        host.updateTransient { it.copy(contextSheetEntryId = null) }
        host.showStatusMessage(if (playNext) "Queued to play next" else "Added to Audio Up Next")
    }

    suspend fun removeFromQueue(entryId: String, showBanner: Boolean) {
        var previousIndex = -1
        host.settingsRepository.update { current ->
            previousIndex = current.queueItemIds.indexOf(entryId)
            current.copy(queueItemIds = current.queueItemIds.filterNot { it == entryId })
        }
        host.playbackOrchestrator.syncActivePlayerQueueState(host.playerController)
        host.updateTransient { it.copy(contextSheetEntryId = null) }
        if (showBanner && previousIndex >= 0) {
            val titlePart =
                host
                    .latestEntries()
                    .firstOrNull { it.id == entryId }
                    ?.title
                    ?.take(SNACKBAR_TITLE_LIMIT)
                    ?.let { " · $it" }
                    .orEmpty()
            host.showUndoAction(
                UndoActionEvent(
                    message = "Removed from Audio Up Next$titlePart",
                    kind = UndoActionKind.RestoreQueueItem(entryId, previousIndex),
                )
            )
        } else if (showBanner) {
            host.showStatusMessage("Removed from Audio Up Next")
        }
    }

    suspend fun moveUp(entryId: String) {
        host.settingsRepository.update { current ->
            val list = current.queueItemIds.toMutableList()
            val idx = list.indexOf(entryId)
            if (idx > 0) {
                val prev = list[idx - 1]
                list[idx - 1] = entryId
                list[idx] = prev
            }
            current.copy(queueItemIds = list)
        }
        host.playbackOrchestrator.syncActivePlayerQueueState(host.playerController)
    }

    suspend fun moveToTop(entryId: String) {
        val activeAudioId = host.playerController.state.value.currentEntryId
        host.settingsRepository.update { current ->
            val withoutTarget = current.queueItemIds.filterNot { it == entryId }.toMutableList()
            val targetIdx =
                if (activeAudioId != null && withoutTarget.firstOrNull() == activeAudioId) {
                    1
                } else {
                    0
                }
            withoutTarget.add(targetIdx.coerceAtMost(withoutTarget.size), entryId)
            current.copy(queueItemIds = withoutTarget)
        }
        host.playbackOrchestrator.syncActivePlayerQueueState(host.playerController)
        host.showStatusMessage("Moved to top of Audio Up Next")
    }

    suspend fun reorder(orderedVisibleIds: List<String>) {
        host.settingsRepository.update { current ->
            val next = applyVisibleQueueReorder(current.queueItemIds, orderedVisibleIds)
            if (next == current.queueItemIds) current else current.copy(queueItemIds = next)
        }
        host.playbackOrchestrator.syncActivePlayerQueueState(host.playerController)
    }
}

internal fun applyVisibleQueueReorder(
    currentQueueIds: List<String>,
    orderedVisibleIds: List<String>,
): List<String> {
    val currentSet = currentQueueIds.toHashSet()
    val validReordered = orderedVisibleIds.distinct().filter { it in currentSet }
    if (validReordered.size <= 1) return currentQueueIds
    val reorderedSet = validReordered.toHashSet()
    val iterator = validReordered.iterator()
    return currentQueueIds.map { existingId ->
        if (existingId in reorderedSet && iterator.hasNext()) {
            iterator.next()
        } else {
            existingId
        }
    }
}

internal suspend fun performEvictPlayedOffline(host: InboxReaderHost) {
    val targets =
        host.latestEntries().filter { it.isCachedOffline && isPlayedOrReadOfflineEntry(it) }
    if (targets.isEmpty()) {
        host.showStatusMessage("No played or read offline copies to remove")
        return
    }
    var removedCount = 0
    for (entry in targets) {
        runSuspendCatching { host.repository.removeOfflineCache(entry.id) }
            .onSuccess { removedCount += 1 }
    }
    host.refreshOfflineSizes()
    val noun = if (removedCount == 1) "copy" else "copies"
    host.showStatusMessage("Removed $removedCount played/read offline $noun")
}

internal fun isPlayedOrReadOfflineEntry(entry: InboxEntry): Boolean =
    entry.isArchived ||
        entry.isRead ||
        entry.isPlayableAudio && entry.readProgress >= PLAYED_OFFLINE_THRESHOLD

private const val PLAYED_OFFLINE_THRESHOLD = 0.90
