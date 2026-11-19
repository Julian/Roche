package com.grayvines.roche.ui

import com.grayvines.roche.data.normalizeTagName
import com.grayvines.roche.data.settings.normalizeServerUrl
import com.grayvines.roche.model.AppTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

@Suppress("TooManyFunctions")
internal class MainSyncFeedDelegate(private val vm: MainViewModel) {
    internal suspend fun tryExecute(action: MainAction.Mutation): Boolean =
        when (action) {
            is MainAction.ImportOpml -> {
                performImportOpml(action.opmlXml)
                true
            }
            is MainAction.RefreshFeed -> {
                performRefreshFeed(action.feedId)
                true
            }
            is MainAction.RenameFeed -> {
                performRenameFeed(action.feedId, action.newTitle)
                true
            }
            is MainAction.ApplyTagToFeed -> {
                performApplyTagToFeed(action.feedId, action.tagName)
                true
            }
            is MainAction.ArchiveFeedUnread -> {
                performArchiveFeedUnread(action.feedId)
                true
            }
            is MainAction.ToggleFeedArchive -> {
                performToggleFeedArchive(action.feedId)
                true
            }
            is MainAction.DeleteFeed -> {
                performDeleteFeed(action.feedId)
                true
            }
            MainAction.SyncNow -> {
                performSync(errorPolicy = SyncErrorPolicy.SHOW_BANNER)
                true
            }
            MainAction.PollFeedsOnDeviceNow -> {
                performPollFeedsOnDevice()
                true
            }
            is MainAction.SyncWithServerConfig -> {
                performSyncWithServerConfig(action)
                true
            }
            MainAction.StartupSyncIfConfigured -> {
                performStartupSync()
                true
            }
            else -> {
                tryExecuteSettings(action)
            }
        }

    private suspend fun tryExecuteSettings(action: MainAction.Mutation): Boolean =
        when (action) {
            is MainAction.SetThemeMode -> {
                vm.settingsRepository.update { it.copy(themeMode = action.mode) }
                true
            }
            is MainAction.SetHeadlineFont -> {
                vm.settingsRepository.update { it.copy(headlineFont = action.font) }
                true
            }
            is MainAction.SetUltraDenseRows -> {
                vm.settingsRepository.update { it.copy(ultraDenseRows = action.enabled) }
                true
            }
            is MainAction.SetReaderFontScale -> {
                vm.settingsRepository.update { it.copy(readerFontScale = action.scale) }
                true
            }
            is MainAction.SetSwipeToArchive -> {
                vm.settingsRepository.update { it.copy(swipeToArchive = action.enabled) }
                true
            }
            is MainAction.SetShowCollectionDrawsOnHome -> {
                vm.settingsRepository.update {
                    it.copy(showCollectionDrawsOnHome = action.enabled)
                }
                true
            }
            is MainAction.SetBottomNavSlot -> {
                vm.settingsRepository.update { current ->
                    val updatedTabs =
                        updateBottomNavSlot(current.bottomNavTabs, action.slotIndex, action.tab)
                    current.copy(bottomNavTabs = updatedTabs)
                }
                true
            }
            MainAction.ResetBottomNavTabs -> {
                vm.settingsRepository.update { it.copy(bottomNavTabs = AppTab.defaultSideTabs) }
                true
            }
            is MainAction.UpdateServerConfig -> {
                performUpdateServerConfig(action)
                true
            }
            is MainAction.PairWithServer -> {
                performPairWithServer(action)
                true
            }
            MainAction.DiscoverServer -> {
                performDiscoverServer()
                true
            }
            else -> {
                false
            }
        }

    internal suspend fun performImportOpml(opmlXml: String) {
        if (!vm.tryAcquireSyncLock()) return
        try {
            vm.repository
                .importOpml(opmlXml)
                .onSuccess { importedCount ->
                    val noun = if (importedCount == 1) "feed" else "feeds"
                    vm.showStatusMessage("Imported $importedCount $noun from OPML")
                }
                .onFailure { error ->
                    vm.showStatusMessage("OPML import failed (${error.message ?: "error"})")
                }
        } finally {
            vm.transientState.update { it.copy(isSyncing = false) }
        }
    }

    private suspend fun performRefreshFeed(feedId: String) {
        if (!vm.tryAcquireSyncLock()) return
        try {
            vm.repository
                .refreshFeed(feedId)
                .onSuccess { title ->
                    vm.showStatusMessage("Refreshed \"${title.take(SNACKBAR_TITLE_LIMIT)}\"")
                }
                .onFailure { error ->
                    vm.showStatusMessage("Refresh failed (${error.message ?: "unreachable"})")
                }
        } finally {
            vm.transientState.update { it.copy(isSyncing = false) }
        }
    }

    private suspend fun performRenameFeed(feedId: String, newTitle: String) {
        val updatedTitle = vm.repository.renameFeed(feedId = feedId, newTitle = newTitle)
        if (updatedTitle != null) {
            vm.showStatusMessage("Renamed feed to \"${updatedTitle.take(SNACKBAR_TITLE_LIMIT)}\"")
        }
    }

    private suspend fun performApplyTagToFeed(feedId: String, tagName: String) {
        val count = vm.repository.applyTagToFeedItems(feedId = feedId, tagName = tagName)
        val cleanTag = normalizeTagName(tagName)
        if (cleanTag.isNotBlank()) {
            val msg =
                if (count > 0) {
                    "Tagged $count ${entryCountNoun(count)} with #$cleanTag"
                } else {
                    "All active feed entries already have #$cleanTag"
                }
            vm.showStatusMessage(msg)
        }
    }

    private suspend fun performArchiveFeedUnread(feedId: String) {
        val unreadIds =
            vm.latestEntries().filter { it.feedId == feedId && !it.isArchived }.map { it.id }
        if (unreadIds.isEmpty()) return
        val snapshots =
            vm.repository.archiveBatchWithSnapshots(
                entryIds = unreadIds,
                feedId = feedId,
                allUnread = false,
            )
        if (snapshots.isEmpty()) return
        val archivedIds = snapshots.map { it.itemId }.toSet()
        vm.cleanupRemovedEntries(archivedIds)
        val count = snapshots.size
        val noun = entryCountNoun(count)
        val first = snapshots.first()
        vm.transientState.update {
            it.copy(
                feedContextSheetId = null,
                statusBanner = null,
                messageSequence = it.messageSequence + 1,
                undoEvent =
                    UndoArchiveEvent(
                        itemId = first.itemId,
                        previousStatus = first.previousStatus,
                        previousReadAt = first.previousReadAt,
                        batchSnapshots = snapshots,
                        message = "Archived $count $noun in feed",
                    ),
            )
        }
    }

    private suspend fun performToggleFeedArchive(feedId: String) {
        vm.repository
            .toggleFeedArchive(feedId)
            .onSuccess { (title, isArchived) ->
                val verb = if (isArchived) "Archived feed" else "Unarchived feed"
                vm.transientState.update { current ->
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
                vm.showStatusMessage("Feed update failed (${error.message ?: "error"})")
            }
    }

    private suspend fun performDeleteFeed(feedId: String) {
        val feedEntryIds = vm.latestEntries().filter { it.feedId == feedId }.map { it.id }.toSet()
        vm.repository
            .deleteFeed(feedId)
            .onSuccess { title ->
                if (feedEntryIds.isNotEmpty()) {
                    vm.cleanupRemovedEntries(feedEntryIds)
                }
                vm.transientState.update { current ->
                    val deletingScopedFeed = current.selectedFeedId == feedId
                    val nextFeedId = if (deletingScopedFeed) null else current.selectedFeedId
                    val nextScopeOriginTab =
                        if (deletingScopedFeed) null else current.scopeOriginTab
                    val nextActiveTab =
                        if (deletingScopedFeed) {
                            current.scopeOriginTab ?: AppTab.FEEDS
                        } else {
                            current.activeTab
                        }
                    val nextFeedSheetId =
                        if (current.feedContextSheetId == feedId) {
                            null
                        } else {
                            current.feedContextSheetId
                        }
                    current.copy(
                        activeTab = nextActiveTab,
                        selectedFeedId = nextFeedId,
                        scopeOriginTab = nextScopeOriginTab,
                        feedContextSheetId = nextFeedSheetId,
                        undoEvent = null,
                        statusBanner = "Unsubscribed from \"${title.take(SNACKBAR_TITLE_LIMIT)}\"",
                        messageSequence = current.messageSequence + 1,
                    )
                }
            }
            .onFailure { error ->
                vm.showStatusMessage("Unsubscribe failed (${error.message ?: "error"})")
            }
    }

    private suspend fun performSyncWithServerConfig(action: MainAction.SyncWithServerConfig) {
        vm.refreshLocalNetworkTier()
        performUpdateServerConfig(
            MainAction.UpdateServerConfig(
                serverUrl = action.serverUrl,
                bearerToken = action.bearerToken,
                feedback = ConfigSaveFeedback.SILENT,
            )
        )
        if (normalizeServerUrl(action.serverUrl).isBlank()) {
            vm.showStatusMessage("Configure Server URL in Settings")
            return
        }
        performSync(errorPolicy = SyncErrorPolicy.SHOW_BANNER)
        vm.refreshLocalNetworkTier()
    }

    private suspend fun performUpdateServerConfig(action: MainAction.UpdateServerConfig) {
        val normalizedTargetUrl = normalizeServerUrl(action.serverUrl)
        vm.settingsRepository.update { current ->
            val preserveToken =
                current.hasUndecryptableToken &&
                    action.bearerToken.trim().isEmpty() &&
                    normalizedTargetUrl.isNotEmpty()
            current.copy(
                serverUrl = action.serverUrl,
                bearerToken = if (preserveToken) current.bearerToken else action.bearerToken,
            )
        }
        if (action.feedback == ConfigSaveFeedback.SHOW_BANNER) {
            vm.showStatusMessage("Saved server settings")
        }
    }

    private suspend fun performDiscoverServer() {
        val message =
            vm.repository
                .discoverServer()
                .fold(
                    onSuccess = { url -> "Discovered Roche server at $url" },
                    onFailure = { err -> "Discovery failed (${err.message ?: "not found"})" },
                )
        vm.showStatusMessage(message)
    }

    private suspend fun performPairWithServer(action: MainAction.PairWithServer) {
        if (action.pairingCode.isBlank()) {
            vm.showStatusMessage("Enter a pairing code")
            return
        }
        vm.refreshLocalNetworkTier()
        vm.repository
            .pairWithCode(
                serverUrl = action.serverUrl,
                pairingCode = action.pairingCode,
                deviceName = action.deviceName,
            )
            .onSuccess {
                val syncCount = vm.repository.syncWithServer().getOrNull()
                vm.refreshLocalNetworkTier()
                val suffix = syncCount?.let { " · Synced $it entries" }.orEmpty()
                vm.showStatusMessage("Paired with server$suffix")
            }
            .onFailure { error ->
                vm.refreshLocalNetworkTier()
                vm.showStatusMessage("Pairing failed (${error.message ?: "error"})")
            }
    }

    private suspend fun performStartupSync() {
        vm.refreshLocalNetworkTier()
        vm.uiState.first { !it.isInitialLoading }
        val currentSettings = vm.settingsRepository.settings.first()
        if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
            performSync(errorPolicy = SyncErrorPolicy.SILENT)
        }
        vm.refreshLocalNetworkTier()
    }

    private suspend fun performPollFeedsOnDevice() {
        vm.refreshLocalNetworkTier()
        if (!vm.tryAcquireSyncLock()) return
        try {
            val pollResult = vm.repository.pollDueFeedsOnDeviceNow()
            vm.refreshLocalNetworkTier()
            pollResult
                .onSuccess { count ->
                    val msg =
                        if (count == 0) {
                            "All cooperative feeds are up to date"
                        } else {
                            val noun = if (count == 1) "feed" else "feeds"
                            "Polled $count due $noun on-device"
                        }
                    vm.showStatusMessage(msg)
                }
                .onFailure { error ->
                    vm.showStatusMessage("On-device poll skipped (${error.message ?: "offline"})")
                }
        } finally {
            vm.refreshLocalNetworkTier()
            vm.transientState.update { it.copy(isSyncing = false) }
        }
    }

    private suspend fun performSync(errorPolicy: SyncErrorPolicy) {
        vm.refreshLocalNetworkTier()
        if (!vm.tryAcquireSyncLock()) return
        try {
            val hasServer = vm.settingsRepository.settings.first().serverUrl.isNotBlank()
            val syncResult = vm.repository.syncWithServer()
            vm.refreshLocalNetworkTier()
            syncResult
                .onSuccess { count ->
                    val message =
                        when {
                            hasServer -> "Synced $count entries from server"
                            count == 0 -> "Feeds are up to date"
                            else -> "Refreshed $count ${if (count == 1) "feed" else "feeds"}"
                        }
                    vm.showStatusMessage(message)
                }
                .onFailure { error ->
                    if (errorPolicy == SyncErrorPolicy.SHOW_BANNER) {
                        vm.showStatusMessage("Sync offline (${error.message ?: "unreachable"})")
                    }
                }
        } finally {
            vm.refreshLocalNetworkTier()
            vm.transientState.update { it.copy(isSyncing = false) }
        }
    }
}
