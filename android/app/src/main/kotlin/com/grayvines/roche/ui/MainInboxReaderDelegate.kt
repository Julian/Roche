package com.grayvines.roche.ui

import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.isLikelyFeedUrl
import com.grayvines.roche.data.normalizeUserSubmittedUrl
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.extractCleanDomain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

@Suppress("TooManyFunctions")
internal class MainInboxReaderDelegate(private val vm: MainViewModel) {
    internal val inFlightStarCounts = mutableMapOf<String, Int>()

    internal suspend fun tryExecute(action: MainAction.Mutation): Boolean =
        when (action) {
            is MainAction.ToggleArchive -> {
                performToggleArchive(action.entry, action.liveReadProgress)
                true
            }
            MainAction.UndoLastArchive -> {
                performUndoArchive()
                true
            }
            MainAction.ArchiveVisibleEntries -> {
                performArchiveVisibleEntries()
                true
            }
            is MainAction.ToggleStar -> {
                performToggleStar(action.entryId)
                true
            }
            is MainAction.ToggleReadState -> {
                performToggleReadState(action.entryId)
                true
            }
            is MainAction.ToggleOfflineCache -> {
                performToggleOffline(action.entryId)
                true
            }
            is MainAction.ToggleTagOnEntry -> {
                vm.repository.toggleTagOnItem(itemId = action.entryId, tagName = action.tagName)
                true
            }
            is MainAction.UpdateReadProgress -> {
                vm.repository.updateReadProgress(
                    itemId = action.entryId,
                    progress = action.progress,
                )
                true
            }
            is MainAction.SaveUrlOrFeed -> {
                performSaveUrl(action.url, action.titleOverride, action.tagCsv, action.mode)
                true
            }
            is MainAction.DeleteEntry -> {
                performDeleteEntry(action.entryId)
                true
            }
            else -> {
                tryExecutePlayerOrQueue(action)
            }
        }

    private suspend fun tryExecutePlayerOrQueue(action: MainAction.Mutation): Boolean =
        when (action) {
            is MainAction.PlayAudioEntry -> {
                performPlayAudioEntry(action.entryId, action.openFullscreen)
                true
            }
            MainAction.TogglePlayPause -> {
                val smartRewind = vm.settingsRepository.settings.first().smartRewindEnabled
                vm.playerController.togglePlayPause(smartRewindEnabled = smartRewind)
                true
            }
            is MainAction.SeekPlayerToFraction -> {
                vm.playerController.seekToFraction(action.fraction)
                true
            }
            is MainAction.SeekPlayerToMs -> {
                vm.playerController.seekToMs(action.positionMs)
                true
            }
            is MainAction.SkipPlayerBySeconds -> {
                vm.playerController.skipBySeconds(action.deltaSeconds)
                true
            }
            MainAction.SkipToNextQueuedAudio -> {
                vm.playbackOrchestrator.performSkipToNextQueuedAudio(vm.playerController)
                true
            }
            is MainAction.SetPlaybackSpeed -> {
                vm.playbackOrchestrator.performSetPlaybackSpeed(
                    playerController = vm.playerController,
                    speed = action.speed,
                    applyToCurrentPodcast = action.applyToCurrentPodcast,
                )
                true
            }
            is MainAction.SetRememberPodcastSpeed -> {
                performSetRememberPodcastSpeed(action.feedId, action.enabled)
                true
            }
            is MainAction.SetSkipSilence -> {
                vm.playerController.setSkipSilence(action.enabled)
                vm.settingsRepository.update { it.copy(skipSilenceEnabled = action.enabled) }
                true
            }
            is MainAction.SetSmartRewind -> {
                vm.settingsRepository.update { it.copy(smartRewindEnabled = action.enabled) }
                vm.playerController.updateSmartRewindEnabled(action.enabled)
                true
            }
            is MainAction.SetSleepTimerMinutes -> {
                performSetSleepTimerMinutes(action.minutes)
                true
            }
            else -> {
                tryExecuteQueueOrFeedAudio(action)
            }
        }

    private suspend fun tryExecuteQueueOrFeedAudio(action: MainAction.Mutation): Boolean =
        when (action) {
            is MainAction.SetFeedAutoDownload -> {
                performSetFeedAutoDownload(action.feedId, action.enabled)
                true
            }
            is MainAction.SetFeedPlaybackSpeed -> {
                performSetFeedPlaybackSpeed(action.feedId, action.speed)
                true
            }
            is MainAction.ToggleQueueMembership -> {
                performToggleQueueMembership(action.entryId)
                true
            }
            is MainAction.AddToQueue -> {
                performAddToQueue(action.entryId, action.playNext)
                true
            }
            is MainAction.RemoveFromQueue -> {
                performRemoveFromQueue(action.entryId, showBanner = true)
                true
            }
            is MainAction.MoveQueueItemUp -> {
                performMoveQueueItemUp(action.entryId)
                true
            }
            is MainAction.MoveQueueItemToTop -> {
                performMoveQueueItemToTop(action.entryId)
                true
            }
            else -> {
                false
            }
        }

    internal suspend fun performPlayAudioEntry(
        entryId: String,
        openFullscreen: Boolean = true,
    ) {
        val played =
            vm.playbackOrchestrator.playAudioEntry(
                playerController = vm.playerController,
                entryId = entryId,
                openFullscreen = openFullscreen,
            )
        if (played) {
            vm.transientState.update { current ->
                if (openFullscreen) {
                    current.copy(
                        selectedReaderEntryId = null,
                        readerContent = current.readerContent?.takeIf { it.itemId == entryId },
                        contextSheetEntryId = null,
                    )
                } else {
                    current.copy(contextSheetEntryId = null)
                }
            }
        }
    }

    internal suspend fun performToggleStar(
        entryId: String,
        targetFavorite: Boolean? = null,
        bannerAlreadyShown: Boolean = false,
    ) {
        try {
            val starred =
                vm.repository.toggleStar(itemId = entryId, targetFavorite = targetFavorite)
            if (!bannerAlreadyShown) {
                vm.showStatusMessage(if (starred) "Starred" else "Removed star")
            }
        } finally {
            if (bannerAlreadyShown) {
                clearSettledOptimisticStar(entryId)
            }
        }
    }

    private fun clearSettledOptimisticStar(entryId: String) {
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
                vm.repository.latestSnapshot?.entries?.firstOrNull { it.id == entryId }?.isFavorite
            if (
                persistedFav != null &&
                    vm.transientState.value.optimisticFavorites[entryId] == persistedFav
            ) {
                vm.transientState.update { current ->
                    current.copy(optimisticFavorites = current.optimisticFavorites - entryId)
                }
            }
        }
    }

    private suspend fun performToggleReadState(entryId: String) {
        val isRead = vm.repository.toggleReadState(entryId)
        vm.transientState.update {
            it.copy(
                contextSheetEntryId = null,
                undoEvent = null,
                statusBanner = if (isRead) "Marked as read" else "Marked as unread",
                messageSequence = it.messageSequence + 1,
            )
        }
    }

    private suspend fun performToggleOffline(entryId: String) {
        val prevEntry = vm.latestEntries().firstOrNull { it.id == entryId }
        val wasCached = prevEntry?.isCachedOffline == true
        val cached = vm.repository.toggleOfflineCache(entryId)
        if (cached && vm.transientState.value.selectedReaderEntryId == entryId) {
            vm.repository.hydrateItemContentIfNeeded(entryId)?.let { content ->
                vm.applyReaderContentIfActive(entryId, content)
            }
        }
        val entry = vm.latestEntries().firstOrNull { it.id == entryId } ?: prevEntry
        val isAudio = entry?.contentType == ContentType.AUDIO
        val msg =
            when {
                cached && isAudio -> "Downloaded episode for offline playback"
                cached -> "Cached local copy"
                !wasCached && isAudio -> "Failed to download episode for offline playback"
                !wasCached -> "Failed to cache offline copy"
                isAudio -> "Deleted local episode download"
                else -> "Removed local offline copy"
            }
        vm.showStatusMessage(msg)
    }

    private suspend fun performToggleArchive(
        entry: InboxEntry,
        liveReadProgress: Double?,
    ) {
        val targetArchived = !entry.isArchived
        if (targetArchived && liveReadProgress != null && liveReadProgress > entry.readProgress) {
            vm.repository.updateReadProgress(itemId = entry.id, progress = liveReadProgress)
        }
        val snapshot =
            vm.repository.setArchiveStateWithSnapshot(itemId = entry.id, archived = targetArchived)
        if (targetArchived) {
            vm.cleanupRemovedEntries(setOf(entry.id))
        }
        val previousStatus = snapshot?.previousStatus ?: entry.status
        val previousReadAt = snapshot?.previousReadAt ?: entry.readAt
        val label = if (targetArchived) "Archived" else "Restored to Unread"
        vm.transientState.update { current ->
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
                    UndoArchiveEvent(
                        itemId = entry.id,
                        previousStatus = previousStatus,
                        previousReadAt = previousReadAt,
                        batchSnapshots = listOfNotNull(snapshot),
                        message = "$label · ${entry.title.take(SNACKBAR_TITLE_LIMIT)}",
                    ),
            )
        }
    }

    private suspend fun performUndoArchive() {
        val pending = vm.transientState.value.undoEvent ?: return
        if (pending.batchSnapshots.size > 1) {
            vm.repository.restoreArchiveSnapshots(pending.batchSnapshots)
            vm.showStatusMessage("Restored ${pending.batchSnapshots.size} entries")
        } else {
            vm.repository.restoreStatus(
                itemId = pending.itemId,
                status = pending.previousStatus,
                readAt = pending.previousReadAt,
            )
            vm.showStatusMessage("Restored entry")
        }
    }

    private suspend fun performArchiveVisibleEntries() {
        val local = vm.transientState.value
        val entries = applyOptimisticFavorites(vm.latestEntries(), local.optimisticFavorites)
        val visible =
            RocheRepository.filterEntries(
                entries = entries,
                filter = local.activeFilter,
                searchQuery = local.searchQuery,
                selectedTag = local.selectedTag,
                selectedFeedId = local.selectedFeedId,
            )
        val unarchivedIds = visible.filter { !it.isArchived }.map { it.id }
        if (unarchivedIds.isEmpty()) return
        val snapshots =
            vm.repository.archiveBatchWithSnapshots(
                entryIds = unarchivedIds,
                feedId = local.selectedFeedId,
                allUnread =
                    local.selectedFeedId == null &&
                        local.selectedTag == null &&
                        local.searchQuery.isBlank() &&
                        local.activeFilter == InboxFilter.UNREAD,
            )
        if (snapshots.isEmpty()) return
        val archivedIds = snapshots.map { it.itemId }.toSet()
        vm.cleanupRemovedEntries(archivedIds)
        val archivedCount = snapshots.size
        val noun = entryCountNoun(archivedCount)
        val first = snapshots.first()
        vm.transientState.update {
            it.copy(
                statusBanner = null,
                messageSequence = it.messageSequence + 1,
                undoEvent =
                    UndoArchiveEvent(
                        itemId = first.itemId,
                        previousStatus = first.previousStatus,
                        previousReadAt = first.previousReadAt,
                        batchSnapshots = snapshots,
                        message = "Archived $archivedCount $noun",
                    ),
            )
        }
    }

    private suspend fun performSaveUrl(
        url: String,
        titleOverride: String?,
        tagCsv: String,
        explicitMode: AddSheetMode?,
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
        vm.cancelDeepSearch()
        vm.transientState.update {
            it.clearSearch()
                .copy(
                    isAddSheetOpen = false,
                    prefilledShareUrl = "",
                    prefilledShareTitle = "",
                    selectedReaderEntryId = null,
                    readerContent = null,
                    undoEvent = null,
                )
        }
        if (resolvedMode == AddSheetMode.SUBSCRIBE_FEED) {
            performSubscribeFeedAction(cleanUrl, titleOverride)
        } else {
            val created =
                vm.repository.addUrlToWantToRead(
                    inputUrl = cleanUrl,
                    titleOverride = titleOverride,
                    tagCsv = tagCsv,
                )
            vm.showStatusMessage(
                "Added to Want to Read · \"${created.title.take(SNACKBAR_TITLE_LIMIT)}\""
            )
        }
    }

    private suspend fun performSubscribeFeedAction(url: String, titleOverride: String?) {
        val hostLabel = extractCleanDomain(url).ifBlank { url }.take(SNACKBAR_TITLE_LIMIT)
        if (vm.repository.hasBackgroundScope) {
            vm.transientState.update {
                it.copy(
                    activeTab = AppTab.FEEDS,
                    statusBanner = "Finding RSS feed for $hostLabel…",
                    messageSequence = it.messageSequence + 1,
                )
            }
            vm.repository.subscribeFeedInBackground(
                inputUrl = url,
                titleOverride = titleOverride,
            ) { result ->
                result
                    .onSuccess { saved ->
                        vm.showStatusMessage(
                            "Subscribed \"${saved.title.take(SNACKBAR_TITLE_LIMIT)}\""
                        )
                    }
                    .onFailure { err ->
                        vm.showStatusMessage(err.message ?: "Could not subscribe to feed")
                    }
            }
        } else {
            vm.repository
                .discoverAndSubscribeFeed(inputUrl = url, titleOverride = titleOverride)
                .onSuccess { saved ->
                    vm.transientState.update {
                        it.copy(
                            activeTab = AppTab.FEEDS,
                            statusBanner =
                                "Subscribed \"${saved.title.take(SNACKBAR_TITLE_LIMIT)}\"",
                            messageSequence = it.messageSequence + 1,
                        )
                    }
                }
                .onFailure { err ->
                    vm.showStatusMessage(err.message ?: "Could not subscribe to feed")
                }
        }
    }

    private suspend fun performDeleteEntry(entryId: String) {
        vm.repository
            .deleteItem(entryId)
            .onSuccess { title ->
                vm.cleanupRemovedEntries(setOf(entryId))
                vm.showStatusMessage("Deleted \"${title.take(SNACKBAR_TITLE_LIMIT)}\"")
            }
            .onFailure { error ->
                vm.showStatusMessage("Delete failed (${error.message ?: "error"})")
            }
    }

    private fun performSetSleepTimerMinutes(minutes: Int?) {
        vm.playerController.setSleepTimerMinutes(minutes)
        val msg = if (minutes == null) "Sleep timer off" else "Sleep timer set for ${minutes}m"
        vm.showStatusMessage(msg)
    }

    private suspend fun performSetRememberPodcastSpeed(feedId: String, enabled: Boolean) {
        val currentSpeed = vm.playerController.state.value.playbackSpeed
        vm.repository.setFeedPlaybackSpeed(
            feedId = feedId,
            speed = if (enabled) currentSpeed else null,
        )
    }

    private suspend fun performSetFeedAutoDownload(feedId: String, enabled: Boolean) {
        vm.repository.setFeedAutoDownload(feedId = feedId, enabled = enabled)
        val msg =
            if (enabled) {
                "Auto-downloading new episodes for this podcast"
            } else {
                "Disabled auto-download for this podcast"
            }
        vm.showStatusMessage(msg)
    }

    private suspend fun performSetFeedPlaybackSpeed(feedId: String, speed: Float?) {
        val clamped = speed?.let(::clampPlaybackSpeed)
        vm.repository.setFeedPlaybackSpeed(feedId = feedId, speed = clamped)
        val currentEntryId = vm.playerController.state.value.currentEntryId
        val activeEntry = currentEntryId?.let { id ->
            vm.latestEntries().firstOrNull { it.id == id }
        }
        if (activeEntry?.feedId == feedId && clamped != null) {
            vm.playerController.setPlaybackSpeed(clamped)
        }
    }

    private suspend fun performToggleQueueMembership(entryId: String) {
        val inQueue = entryId in vm.settingsRepository.settings.first().queueItemIds
        if (inQueue) {
            performRemoveFromQueue(entryId, showBanner = true)
        } else {
            performAddToQueue(entryId, playNext = false)
        }
    }

    private suspend fun performAddToQueue(entryId: String, playNext: Boolean) {
        val activeAudioId = vm.playerController.state.value.currentEntryId
        vm.settingsRepository.update { current ->
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
        vm.playbackOrchestrator.syncActivePlayerQueueState(vm.playerController)
        vm.transientState.update { it.copy(contextSheetEntryId = null) }
        vm.showStatusMessage(if (playNext) "Queued to play next" else "Added to Queue")
    }

    private suspend fun performRemoveFromQueue(entryId: String, showBanner: Boolean) {
        vm.settingsRepository.update { current ->
            current.copy(queueItemIds = current.queueItemIds.filterNot { it == entryId })
        }
        vm.playbackOrchestrator.syncActivePlayerQueueState(vm.playerController)
        vm.transientState.update { it.copy(contextSheetEntryId = null) }
        if (showBanner) {
            vm.showStatusMessage("Removed from Queue")
        }
    }

    private suspend fun performMoveQueueItemUp(entryId: String) {
        vm.settingsRepository.update { current ->
            val list = current.queueItemIds.toMutableList()
            val idx = list.indexOf(entryId)
            if (idx > 0) {
                val prev = list[idx - 1]
                list[idx - 1] = entryId
                list[idx] = prev
            }
            current.copy(queueItemIds = list)
        }
        vm.playbackOrchestrator.syncActivePlayerQueueState(vm.playerController)
    }

    private suspend fun performMoveQueueItemToTop(entryId: String) {
        val activeAudioId = vm.playerController.state.value.currentEntryId
        vm.settingsRepository.update { current ->
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
        vm.playbackOrchestrator.syncActivePlayerQueueState(vm.playerController)
        vm.showStatusMessage("Moved to top of Queue")
    }
}
