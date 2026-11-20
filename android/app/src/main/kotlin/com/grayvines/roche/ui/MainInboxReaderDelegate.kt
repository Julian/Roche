package com.grayvines.roche.ui

import com.grayvines.roche.core.isLikelyFeedUrl
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.SaveUrlResult
import com.grayvines.roche.data.feeds.normalizeUserSubmittedUrl
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.extractCleanDomain
import kotlinx.coroutines.flow.first

@Suppress("TooManyFunctions")
internal class MainInboxReaderDelegate(private val host: InboxReaderHost) {
    internal val inFlightStarCounts = mutableMapOf<String, Int>()

    internal suspend fun execute(action: MainAction.InboxReaderMutation) {
        when (action) {
            is MainAction.EntryMutation -> executeEntryMutation(action)
            is MainAction.PlaybackMutation -> executePlaybackMutation(action)
            is MainAction.QueueFeedAudioMutation -> executeQueueFeedAudioMutation(action)
        }
    }

    private suspend fun executeEntryMutation(action: MainAction.EntryMutation) {
        when (action) {
            is MainAction.ToggleArchive ->
                performToggleArchive(action.entry, action.liveReadProgress)
            MainAction.UndoLastArchive -> performUndoArchive()
            MainAction.ArchiveVisibleEntries -> performArchiveVisibleEntries()
            is MainAction.ToggleStar -> performToggleStar(action.entryId)
            is MainAction.ToggleReadState -> performToggleReadState(action.entryId)
            is MainAction.ToggleOfflineCache -> performToggleOffline(action.entryId)
            is MainAction.ToggleTagOnEntry ->
                host.repository.toggleTagOnItem(itemId = action.entryId, tagName = action.tagName)
            is MainAction.UpdateReadProgress ->
                host.repository.updateReadProgress(
                    itemId = action.entryId,
                    progress = action.progress,
                )
            is MainAction.SaveUrlOrFeed ->
                performSaveUrl(action.url, action.titleOverride, action.tagCsv, action.mode)
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

    private suspend fun performSetSkipSilence(enabled: Boolean) {
        host.settingsRepository.update { it.copy(skipSilenceEnabled = enabled) }
        host.playerController.setSkipSilence(enabled)
    }

    private suspend fun performSetSmartRewind(enabled: Boolean) {
        host.settingsRepository.update { it.copy(smartRewindEnabled = enabled) }
        host.playerController.updateSmartRewindEnabled(enabled)
    }

    private suspend fun executeQueueFeedAudioMutation(action: MainAction.QueueFeedAudioMutation) {
        when (action) {
            is MainAction.SetFeedAutoDownload ->
                performSetFeedAutoDownload(action.feedId, action.enabled)
            is MainAction.SetFeedPlaybackSpeed ->
                performSetFeedPlaybackSpeed(action.feedId, action.speed)
            is MainAction.ToggleQueueMembership -> performToggleQueueMembership(action.entryId)
            is MainAction.AddToQueue -> performAddToQueue(action.entryId, action.playNext)
            is MainAction.RemoveFromQueue ->
                performRemoveFromQueue(action.entryId, showBanner = true)
            is MainAction.MoveQueueItemUp -> performMoveQueueItemUp(action.entryId)
            is MainAction.MoveQueueItemToTop -> performMoveQueueItemToTop(action.entryId)
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
        if (cached && host.currentTransientState().selectedReaderEntryId == entryId) {
            host.repository.hydrateItemContentIfNeeded(entryId)?.let { content ->
                host.applyReaderContentIfActive(entryId, content)
            }
        }
        val entry = host.latestEntries().firstOrNull { it.id == entryId } ?: prevEntry
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
        host.showStatusMessage(msg)
    }

    private suspend fun performToggleArchive(
        entry: InboxEntry,
        liveReadProgress: Double?,
    ) {
        val targetArchived = !entry.isArchived
        if (targetArchived && liveReadProgress != null && liveReadProgress > entry.readProgress) {
            host.repository.updateReadProgress(itemId = entry.id, progress = liveReadProgress)
        }
        val snapshot =
            host.repository.setArchiveStateWithSnapshot(
                itemId = entry.id,
                archived = targetArchived,
            )
        if (targetArchived) {
            host.cleanupRemovedEntries(setOf(entry.id))
        }
        val previousStatus = snapshot?.previousStatus ?: entry.status
        val previousReadAt = snapshot?.previousReadAt ?: entry.readAt
        val label =
            when {
                !entry.inFeedInbox && targetArchived -> "Removed from Want to Read"
                !entry.inFeedInbox -> "Restored to Want to Read"
                targetArchived -> "Archived"
                else -> "Restored to Unread"
            }
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
        val pending = host.currentTransientState().undoEvent ?: return
        if (pending.batchSnapshots.size > 1) {
            host.repository.restoreArchiveSnapshots(pending.batchSnapshots)
            host.showStatusMessage("Restored ${pending.batchSnapshots.size} entries")
        } else {
            host.repository.restoreStatus(
                itemId = pending.itemId,
                status = pending.previousStatus,
                readAt = pending.previousReadAt,
            )
            host.showStatusMessage("Restored entry")
        }
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
        val unarchivedIds = visible.filter { !it.isArchived }.map { it.id }
        if (unarchivedIds.isEmpty()) return
        val snapshots =
            host.repository.archiveBatchWithSnapshots(
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
        host.cleanupRemovedEntries(archivedIds)
        val archivedCount = snapshots.size
        val noun = entryCountNoun(archivedCount)
        val first = snapshots.first()
        host.updateTransient {
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
        host.cancelDeepSearch()
        host.updateTransient {
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

    private suspend fun performSubscribeFeedAction(url: String, titleOverride: String?) {
        val hostLabel = extractCleanDomain(url).ifBlank { url }.take(SNACKBAR_TITLE_LIMIT)
        host.showStatusMessage("Finding RSS feed for $hostLabel…")
        if (host.repository.hasBackgroundScope) {
            host.repository.subscribeFeedInBackground(
                inputUrl = url,
                titleOverride = titleOverride,
                onComplete = ::handleSubscribeFeedResult,
            )
        } else {
            val result =
                host.repository.discoverAndSubscribeFeed(
                    inputUrl = url,
                    titleOverride = titleOverride,
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

    private suspend fun performToggleQueueMembership(entryId: String) {
        val inQueue = entryId in host.settingsRepository.settings.first().queueItemIds
        if (inQueue) {
            performRemoveFromQueue(entryId, showBanner = true)
        } else {
            performAddToQueue(entryId, playNext = false)
        }
    }

    private suspend fun performAddToQueue(entryId: String, playNext: Boolean) {
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
        host.showStatusMessage(if (playNext) "Queued to play next" else "Added to Queue")
    }

    private suspend fun performRemoveFromQueue(entryId: String, showBanner: Boolean) {
        host.settingsRepository.update { current ->
            current.copy(queueItemIds = current.queueItemIds.filterNot { it == entryId })
        }
        host.playbackOrchestrator.syncActivePlayerQueueState(host.playerController)
        host.updateTransient { it.copy(contextSheetEntryId = null) }
        if (showBanner) {
            host.showStatusMessage("Removed from Queue")
        }
    }

    private suspend fun performMoveQueueItemUp(entryId: String) {
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

    private suspend fun performMoveQueueItemToTop(entryId: String) {
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
        host.showStatusMessage("Moved to top of Queue")
    }
}
