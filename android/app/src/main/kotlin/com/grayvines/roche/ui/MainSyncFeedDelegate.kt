package com.grayvines.roche.ui

import com.grayvines.roche.core.normalizeTagName
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.RocheProblemTypes
import com.grayvines.roche.data.remote.formatProblemDetailsMessage
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.extractNormalizedServerHost
import com.grayvines.roche.data.settings.normalizeServerUrl
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.FeedType
import kotlinx.coroutines.flow.first

@Suppress("TooManyFunctions")
internal class MainSyncFeedDelegate(private val host: SyncFeedHost) {
    internal suspend fun execute(action: MainAction.SyncFeedMutation) {
        when (action) {
            is MainAction.FeedSyncAction -> executeFeedSyncAction(action)
            is MainAction.SettingsServerAction -> executeSettingsServerAction(action)
        }
    }

    private suspend fun executeFeedSyncAction(action: MainAction.FeedSyncAction) {
        when (action) {
            is MainAction.ImportOpml -> performImportOpml(action.opmlXml)
            is MainAction.RefreshFeed -> performRefreshFeed(action.feedId)
            is MainAction.RenameFeed -> performRenameFeed(action.feedId, action.newTitle)
            is MainAction.SetFeedType -> performSetFeedType(action.feedId, action.feedType)
            is MainAction.ApplyTagToFeed -> performApplyTagToFeed(action.feedId, action.tagName)
            is MainAction.ArchiveFeedUnread -> performArchiveFeedUnread(action.feedId)
            is MainAction.ToggleFeedArchive -> performToggleFeedArchive(action.feedId)
            is MainAction.DeleteFeed -> performDeleteFeed(action.feedId)
            MainAction.SyncNow -> performSync(errorPolicy = SyncErrorPolicy.SHOW_BANNER)
            MainAction.ManualRefresh -> performManualRefresh()
            MainAction.PollFeedsOnDeviceNow -> performPollFeedsOnDevice()
            is MainAction.SyncWithServerConfig -> performSyncWithServerConfig(action)
            MainAction.StartupSyncIfConfigured -> performStartupSync()
        }
    }

    private suspend fun executeSettingsServerAction(action: MainAction.SettingsServerAction) {
        when (action) {
            is MainAction.SetThemeMode ->
                host.settingsRepository.update { it.copy(themeMode = action.mode) }
            is MainAction.SetHeadlineFont ->
                host.settingsRepository.update { it.copy(headlineFont = action.font) }
            is MainAction.SetUltraDenseRows ->
                host.settingsRepository.update { it.copy(ultraDenseRows = action.enabled) }
            is MainAction.SetReaderFontScale ->
                host.settingsRepository.update { it.copy(readerFontScale = action.scale) }
            is MainAction.SetSwipeToArchive ->
                host.settingsRepository.update { it.copy(swipeToArchive = action.enabled) }
            is MainAction.SetShowCollectionDrawsOnHome ->
                host.settingsRepository.update {
                    it.copy(showCollectionDrawsOnHome = action.enabled)
                }
            is MainAction.SetBottomNavSlot -> performSetBottomNavSlot(action.slotIndex, action.tab)
            MainAction.ResetBottomNavTabs ->
                host.settingsRepository.update { it.copy(bottomNavTabs = AppTab.defaultSideTabs) }
            is MainAction.UpdateServerConfig -> performUpdateServerConfig(action)
            MainAction.DisconnectServer -> performDisconnectServer()
            is MainAction.PairWithServer -> performPairWithServer(action)
            MainAction.DiscoverServer -> performDiscoverServer()
        }
    }

    private suspend fun performSetBottomNavSlot(slotIndex: Int, tab: AppTab) {
        var blockedForSettingsAccess = false
        host.settingsRepository.update { current ->
            val before = AppTab.normalizeBottomTabs(current.bottomNavTabs)
            val updatedTabs = updateBottomNavSlot(before, slotIndex, tab)
            val isValidSwap = slotIndex in before.indices && tab in AppTab.bottomTabCandidates
            blockedForSettingsAccess =
                isValidSwap && before[slotIndex] != tab && updatedTabs == before
            current.copy(bottomNavTabs = updatedTabs)
        }
        if (blockedForSettingsAccess) {
            host.showStatusMessage("Keep Home or Settings in the bar to access Settings")
        }
    }

    internal suspend fun performImportOpml(opmlXml: String) {
        if (!host.tryAcquireSyncLock()) return
        try {
            host.repository
                .importOpml(opmlXml)
                .onSuccess { importedCount ->
                    val noun = if (importedCount == 1) "feed" else "feeds"
                    host.showStatusMessage("Imported $importedCount $noun from OPML")
                }
                .onFailure { error ->
                    host.showStatusMessage(
                        "OPML import failed (${formatErrorDetail(error, "error")})"
                    )
                }
        } finally {
            host.updateTransient { it.copy(isSyncing = false) }
        }
    }

    private suspend fun performRefreshFeed(feedId: String) {
        if (!host.tryAcquireSyncLock()) return
        host.updateTransient { it.copy(refreshingFeedIds = it.refreshingFeedIds + feedId) }
        try {
            host.repository
                .refreshFeed(feedId)
                .onSuccess { title ->
                    host.showStatusMessage("Refreshed \"${title.take(SNACKBAR_TITLE_LIMIT)}\"")
                }
                .onFailure { error ->
                    host.showStatusMessage(
                        "Refresh failed (${formatErrorDetail(error, "unreachable")})"
                    )
                }
        } finally {
            host.updateTransient {
                it.copy(isSyncing = false, refreshingFeedIds = it.refreshingFeedIds - feedId)
            }
        }
    }

    private suspend fun performRenameFeed(feedId: String, newTitle: String) {
        val updatedTitle = host.repository.renameFeed(feedId = feedId, newTitle = newTitle)
        if (updatedTitle != null) {
            host.showStatusMessage("Renamed feed to \"${updatedTitle.take(SNACKBAR_TITLE_LIMIT)}\"")
        }
    }

    private suspend fun performSetFeedType(feedId: String, feedType: FeedType) {
        host.repository
            .setFeedType(feedId = feedId, targetType = feedType)
            .onSuccess { updated ->
                val label = if (updated == FeedType.PODCAST) "Podcast" else "RSS / Newsletter"
                host.showStatusMessage("Updated feed type to $label")
            }
            .onFailure { error ->
                host.showStatusMessage(
                    "Feed type update failed (${formatErrorDetail(error, "error")})"
                )
            }
    }

    private suspend fun performApplyTagToFeed(feedId: String, tagName: String) {
        val count = host.repository.applyTagToFeedItems(feedId = feedId, tagName = tagName)
        val cleanTag = normalizeTagName(tagName)
        if (cleanTag.isNotBlank()) {
            val msg =
                if (count > 0) {
                    "Tagged $count ${entryCountNoun(count)} with #$cleanTag"
                } else {
                    "All active feed entries already have #$cleanTag"
                }
            host.showStatusMessage(msg)
        }
    }

    private suspend fun performArchiveFeedUnread(feedId: String) {
        val unreadIds =
            host.latestEntries().filter { it.feedId == feedId && !it.isArchived }.map { it.id }
        if (unreadIds.isEmpty()) return
        val snapshots =
            host.repository.archiveBatchWithSnapshots(
                entryIds = unreadIds,
                feedId = feedId,
                allUnread = false,
            )
        if (snapshots.isEmpty()) return
        val archivedIds = snapshots.map { it.itemId }.toSet()
        host.cleanupRemovedEntries(archivedIds)
        val count = snapshots.size
        val noun = entryCountNoun(count)
        host.updateTransient {
            it.copy(
                feedContextSheetId = null,
                statusBanner = null,
                messageSequence = it.messageSequence + 1,
                undoEvent =
                    UndoActionEvent(
                        message = "Archived $count $noun in feed",
                        kind = UndoActionKind.RestoreBatchArchive(snapshots),
                    ),
            )
        }
    }

    private suspend fun performToggleFeedArchive(feedId: String) {
        host.repository
            .toggleFeedArchive(feedId)
            .onSuccess { (title, isArchived) ->
                val verb = if (isArchived) "Archived feed" else "Unarchived feed"
                host.updateTransient { current ->
                    current.copy(
                        feedContextSheetId =
                            if (current.feedContextSheetId == feedId) {
                                null
                            } else {
                                current.feedContextSheetId
                            },
                        undoEvent = null,
                        statusBanner = "$verb \"${title.take(SNACKBAR_TITLE_LIMIT)}\"",
                        messageSequence = current.messageSequence + 1,
                    )
                }
            }
            .onFailure { error ->
                host.showStatusMessage("Feed update failed (${formatErrorDetail(error, "error")})")
            }
    }

    private suspend fun performDeleteFeed(feedId: String) {
        val feedEntryIds = host.latestEntries().filter { it.feedId == feedId }.map { it.id }.toSet()
        host.repository
            .deleteFeed(feedId)
            .onSuccess { title ->
                if (feedEntryIds.isNotEmpty()) {
                    host.cleanupRemovedEntries(feedEntryIds)
                }
                host.updateTransient { current ->
                    applyDeletedFeedState(current = current, feedId = feedId, title = title)
                }
            }
            .onFailure { error ->
                host.showStatusMessage("Unsubscribe failed (${formatErrorDetail(error, "error")})")
            }
    }

    private fun applyDeletedFeedState(
        current: TransientUiState,
        feedId: String,
        title: String,
    ): TransientUiState {
        val deletingScopedFeed = current.selectedFeedId == feedId
        return current.copy(
            activeTab =
                if (deletingScopedFeed) {
                    current.scopeOriginTab ?: AppTab.FEEDS
                } else {
                    current.activeTab
                },
            selectedFeedId = if (deletingScopedFeed) null else current.selectedFeedId,
            scopeOriginTab = if (deletingScopedFeed) null else current.scopeOriginTab,
            feedContextSheetId =
                if (current.feedContextSheetId == feedId) null else current.feedContextSheetId,
            undoEvent = null,
            statusBanner = "Unsubscribed from \"${title.take(SNACKBAR_TITLE_LIMIT)}\"",
            messageSequence = current.messageSequence + 1,
        )
    }

    private suspend fun performSyncWithServerConfig(action: MainAction.SyncWithServerConfig) {
        host.refreshLocalNetworkTier()
        performUpdateServerConfig(
            MainAction.UpdateServerConfig(
                serverUrl = action.serverUrl,
                bearerToken = action.bearerToken,
                feedback = ConfigSaveFeedback.SILENT,
            )
        )
        if (normalizeServerUrl(action.serverUrl).isBlank()) {
            host.showStatusMessage("Configure Server URL in Settings")
            return
        }
        performSync(errorPolicy = SyncErrorPolicy.SHOW_BANNER)
        host.refreshLocalNetworkTier()
    }

    private suspend fun performUpdateServerConfig(action: MainAction.UpdateServerConfig) {
        val normalizedTargetUrl = normalizeServerUrl(action.serverUrl)
        val nextHost = extractNormalizedServerHost(normalizedTargetUrl)
        var clearedTokenOnHostChange = false
        host.settingsRepository.update { current ->
            val currentHost = extractNormalizedServerHost(current.serverUrl)
            val hostChanged = currentHost.isNotEmpty() && nextHost != currentHost
            val nextToken =
                resolveNextBearerToken(
                    current = current,
                    requestedToken = action.bearerToken,
                    normalizedTargetUrl = normalizedTargetUrl,
                    hostChanged = hostChanged,
                )
            if (hostChanged && current.bearerToken.isNotBlank() && nextToken.isBlank()) {
                clearedTokenOnHostChange = true
            }
            current.copy(serverUrl = action.serverUrl, bearerToken = nextToken)
        }
        if (action.feedback == ConfigSaveFeedback.SHOW_BANNER) {
            val msg =
                if (clearedTokenOnHostChange) {
                    "Saved new server URL · Previous token cleared"
                } else {
                    "Saved server settings"
                }
            host.showStatusMessage(msg)
        }
    }

    private suspend fun performDisconnectServer() {
        host.settingsRepository.update { current -> current.copy(serverUrl = "", bearerToken = "") }
        host.showStatusMessage("Disconnected from server")
    }

    private suspend fun performDiscoverServer() {
        val message =
            host.repository
                .discoverServer()
                .fold(
                    onSuccess = { url -> "Discovered Roche server at $url" },
                    onFailure = { err ->
                        "Discovery failed (${formatErrorDetail(err, "not found")})"
                    },
                )
        host.showStatusMessage(message)
    }

    private suspend fun performPairWithServer(action: MainAction.PairWithServer) {
        if (action.pairingCode.isBlank()) {
            host.showStatusMessage("Enter a pairing code")
            return
        }
        host.refreshLocalNetworkTier()
        host.repository
            .pairWithCode(
                serverUrl = action.serverUrl,
                pairingCode = action.pairingCode,
                deviceName = action.deviceName,
            )
            .onSuccess {
                val syncCount = host.repository.syncWithServer().getOrNull()
                host.refreshLocalNetworkTier()
                val suffix = syncCount?.let { " · Synced $it entries" }.orEmpty()
                host.showStatusMessage("Paired with server$suffix")
            }
            .onFailure { error ->
                host.refreshLocalNetworkTier()
                host.showStatusMessage("Pairing failed (${formatErrorDetail(error, "error")})")
            }
    }

    private suspend fun performStartupSync() {
        host.refreshLocalNetworkTier()
        host.awaitInitialLoad()
        val currentSettings = host.settingsRepository.settings.first()
        if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
            performSync(errorPolicy = SyncErrorPolicy.SILENT)
        }
        host.refreshLocalNetworkTier()
    }

    private suspend fun performPollFeedsOnDevice() {
        host.refreshLocalNetworkTier()
        if (!host.tryAcquireSyncLock()) return
        try {
            val pollResult = host.repository.pollDueFeedsOnDeviceNow()
            host.refreshLocalNetworkTier()
            pollResult
                .onSuccess { count ->
                    val msg =
                        if (count == 0) {
                            "All cooperative feeds are up to date"
                        } else {
                            val noun = if (count == 1) "feed" else "feeds"
                            "Polled $count due $noun on-device"
                        }
                    host.showStatusMessage(msg)
                }
                .onFailure { error ->
                    host.showStatusMessage(
                        "On-device poll skipped (${formatErrorDetail(error, "offline")})"
                    )
                }
        } finally {
            host.refreshLocalNetworkTier()
            host.updateTransient { it.copy(isSyncing = false) }
        }
    }

    private suspend fun performManualRefresh() {
        if (host.isManualRefreshing()) return
        if (!host.tryAcquireSyncLock()) {
            host.setManualRefreshing(true)
            try {
                host.awaitSyncFinished()
                host.repository.syncStatus.value.lastError
                    ?.takeIf { it.isNotBlank() }
                    ?.let { err -> host.showStatusMessage(err) }
            } finally {
                host.setManualRefreshing(false)
            }
            return
        }
        host.setManualRefreshing(true)
        try {
            executeLockedSync(errorPolicy = SyncErrorPolicy.SHOW_BANNER)
        } finally {
            host.setManualRefreshing(false)
            host.refreshLocalNetworkTier()
            host.updateTransient { it.copy(isSyncing = false) }
        }
    }

    private suspend fun performSync(errorPolicy: SyncErrorPolicy) {
        host.refreshLocalNetworkTier()
        if (!host.tryAcquireSyncLock()) return
        try {
            executeLockedSync(errorPolicy = errorPolicy)
        } finally {
            host.refreshLocalNetworkTier()
            host.updateTransient { it.copy(isSyncing = false) }
        }
    }

    private suspend fun executeLockedSync(errorPolicy: SyncErrorPolicy) {
        host.refreshLocalNetworkTier()
        val hasServer = host.settingsRepository.settings.first().serverUrl.isNotBlank()
        val syncResult = host.repository.syncWithServer()
        host.refreshLocalNetworkTier()
        syncResult
            .onSuccess { count ->
                val message =
                    when {
                        hasServer -> "Synced $count entries from server"
                        count == 0 -> "Feeds are up to date"
                        else -> "Refreshed $count ${if (count == 1) "feed" else "feeds"}"
                    }
                host.showStatusMessage(message)
            }
            .onFailure { error ->
                if (errorPolicy == SyncErrorPolicy.SHOW_BANNER) {
                    val detail = formatErrorDetail(error, "unreachable")
                    val banner =
                        if (
                            error is ProblemDetailsException &&
                                error.problemType == RocheProblemTypes.TOKEN_INVALID
                        ) {
                            detail
                        } else {
                            "Sync offline ($detail)"
                        }
                    host.showStatusMessage(banner)
                }
            }
    }

    private fun formatErrorDetail(error: Throwable, fallback: String): String =
        if (error is ProblemDetailsException) {
            formatProblemDetailsMessage(error)
        } else {
            error.message?.takeIf { it.isNotBlank() } ?: fallback
        }
}

internal fun resolveNextBearerToken(
    current: Settings,
    requestedToken: String?,
    normalizedTargetUrl: String,
    hostChanged: Boolean,
): String {
    val trimmedRequested = requestedToken?.trim()
    return when {
        requestedToken == null ->
            if (hostChanged || normalizedTargetUrl.isEmpty()) "" else current.bearerToken
        trimmedRequested.isNullOrEmpty() ->
            resolveBlankTokenFallback(current, normalizedTargetUrl, hostChanged)
        hostChanged && trimmedRequested == current.bearerToken.trim() -> ""
        else -> trimmedRequested
    }
}

private fun resolveBlankTokenFallback(
    current: Settings,
    normalizedTargetUrl: String,
    hostChanged: Boolean,
): String {
    val preserveUndecryptable =
        !hostChanged && current.hasUndecryptableToken && normalizedTargetUrl.isNotEmpty()
    return if (preserveUndecryptable) current.bearerToken else ""
}
