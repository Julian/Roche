package com.grayvines.roche.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.grayvines.roche.data.ArchiveStateSnapshot
import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.PeerNodeDto
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.SavedResourceKind
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import com.grayvines.roche.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UndoArchiveEvent(
    val itemId: String,
    val previousStatus: ItemStatus,
    val message: String,
    val previousReadAt: String? = null,
    val batchSnapshots: List<ArchiveStateSnapshot> = emptyList(),
)

data class MainUiState(
    val activeTab: AppTab = AppTab.INBOX,
    val activeFilter: InboxFilter = InboxFilter.ALL,
    val searchQuery: String = "",
    val selectedTag: String? = null,
    val selectedFeedId: String? = null,
    val scopeOriginTab: AppTab? = null,
    val selectedReaderEntryId: String? = null,
    val contextSheetEntryId: String? = null,
    val feedContextSheetId: String? = null,
    val isAddSheetOpen: Boolean = false,
    val prefilledShareUrl: String = "",
    val prefilledShareTitle: String = "",
    val isSyncing: Boolean = false,
    val visibleEntries: List<InboxEntry> = emptyList(),
    val searchResults: List<InboxEntry> = emptyList(),
    val allEntries: List<InboxEntry> = emptyList(),
    val feeds: List<FeedItem> = emptyList(),
    val tags: List<TagItem> = emptyList(),
    val activePeers: List<PeerNodeDto> = emptyList(),
    val localNetworkTier: NetworkTier = NetworkTier.WIFI,
    val settings: Settings = Settings(),
    val undoEvent: UndoArchiveEvent? = null,
    val statusBanner: String? = null,
    val messageSequence: Long = 0L,
) {
    val activeReaderEntry: InboxEntry?
        get() = allEntries.firstOrNull { it.id == selectedReaderEntryId }

    val contextSheetEntry: InboxEntry?
        get() = allEntries.firstOrNull { it.id == contextSheetEntryId }

    val contextSheetFeed: FeedItem?
        get() = feeds.firstOrNull { it.id == feedContextSheetId }

    val starredEntries: List<InboxEntry>
        get() = allEntries.filter { it.isFavorite }

    val offlineEntries: List<InboxEntry>
        get() = allEntries.filter { it.isCachedOffline }

    val selectedFeedTitle: String?
        get() = feeds.firstOrNull { it.id == selectedFeedId }?.title
}

enum class ConfigSaveFeedback {
    SHOW_BANNER,
    SILENT,
}

internal enum class SyncErrorPolicy {
    SHOW_BANNER,
    SILENT,
}

sealed interface MainAction {
    sealed interface Ui : MainAction

    sealed interface Mutation : MainAction

    data class SelectTab(val tab: AppTab) : Ui

    data class SelectFilter(val filter: InboxFilter) : Ui

    data class FilterByTag(val tagName: String?) : Ui

    data class FilterByFeed(val feedId: String?) : Ui

    data object NavigateBackFromScope : Ui

    data class UpdateSearchQuery(val query: String) : Ui

    data class OpenReader(val entryId: String) : Ui

    data object CloseReader : Ui

    data class OpenContextSheet(val entryId: String) : Ui

    data object CloseContextSheet : Ui

    data class OpenFeedContextSheet(val feedId: String) : Ui

    data object CloseFeedContextSheet : Ui

    data class OpenAddSheet(
        val prefilledUrl: String = "",
        val prefilledTitle: String = "",
    ) : Ui

    data object CloseAddSheet : Ui

    data object DismissMessages : Ui

    data class ToggleArchive(val entry: InboxEntry) : Mutation

    data object UndoLastArchive : Mutation

    data object ArchiveVisibleEntries : Mutation

    data class ImportOpml(val opmlXml: String) : Mutation

    data class ToggleStar(val entryId: String) : Mutation

    data class ToggleReadState(val entryId: String) : Mutation

    data class ToggleOfflineCache(val entryId: String) : Mutation

    data class ToggleTagOnEntry(val entryId: String, val tagName: String) : Mutation

    data class UpdateReadProgress(val entryId: String, val progress: Double) : Mutation

    data class SaveUrlOrFeed(val url: String, val titleOverride: String?, val tagCsv: String) :
        Mutation

    data class RefreshFeed(val feedId: String) : Mutation

    data class RenameFeed(val feedId: String, val newTitle: String) : Mutation

    data class ApplyTagToFeed(val feedId: String, val tagName: String) : Mutation

    data class ArchiveFeedUnread(val feedId: String) : Mutation

    data class DeleteFeed(val feedId: String) : Mutation

    data class DeleteEntry(val entryId: String) : Mutation

    data object SyncNow : Mutation

    data object PollFeedsOnDeviceNow : Mutation

    data class SyncWithServerConfig(
        val serverUrl: String,
        val bearerToken: String,
    ) : Mutation

    data object StartupSyncIfConfigured : Mutation

    data class SetThemeMode(val mode: ThemeMode) : Mutation

    data class SetHeadlineFont(val font: HeadlineFont) : Mutation

    data class SetUltraDenseRows(val enabled: Boolean) : Mutation

    data class SetReaderFontScale(val scale: Float) : Mutation

    data class SetSwipeToArchive(val enabled: Boolean) : Mutation

    data class SetBottomNavSlot(val slotIndex: Int, val tab: AppTab) : Mutation

    data object ResetBottomNavTabs : Mutation

    data class UpdateServerConfig(
        val serverUrl: String,
        val bearerToken: String,
        val feedback: ConfigSaveFeedback = ConfigSaveFeedback.SHOW_BANNER,
    ) : Mutation

    data class PairWithServer(
        val serverUrl: String,
        val pairingCode: String,
        val deviceName: String = "Roche Android",
    ) : Mutation

    data object DiscoverServer : Mutation
}

internal data class TransientUiState(
    val activeTab: AppTab = AppTab.INBOX,
    val activeFilter: InboxFilter = InboxFilter.ALL,
    val searchQuery: String = "",
    val selectedTag: String? = null,
    val selectedFeedId: String? = null,
    val scopeOriginTab: AppTab? = null,
    val selectedReaderEntryId: String? = null,
    val contextSheetEntryId: String? = null,
    val feedContextSheetId: String? = null,
    val isAddSheetOpen: Boolean = false,
    val prefilledShareUrl: String = "",
    val prefilledShareTitle: String = "",
    val isSyncing: Boolean = false,
    val undoEvent: UndoArchiveEvent? = null,
    val statusBanner: String? = null,
    val messageSequence: Long = 0L,
)

class MainViewModel(
    private val repository: RocheRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val transientState = MutableStateFlow(TransientUiState())

    val uiState: StateFlow<MainUiState> =
        combine(
                repository.snapshot,
                settingsRepository.settings,
                transientState,
            ) { snapshot: RepositorySnapshot, settings: Settings, local: TransientUiState ->
                buildMainUiState(
                    snapshot = snapshot,
                    settings = settings,
                    local = local,
                    localNetworkTier = repository.currentNetworkTier(),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = MainUiState(),
            )

    fun dispatch(action: MainAction) {
        when (action) {
            is MainAction.Ui -> {
                transientState.update { reduceUiAction(it, action) }
                if (action is MainAction.OpenReader) {
                    viewModelScope.launch {
                        repository.setReadState(action.entryId, true)
                        repository.hydrateItemContentIfNeeded(action.entryId)
                    }
                }
            }
            is MainAction.Mutation -> {
                viewModelScope.launch { executeMutation(action) }
            }
        }
    }

    internal suspend fun exportOpmlXml(): Result<String> = repository.exportOpml()

    internal fun showStatusMessage(message: String) {
        transientState.update {
            it.copy(
                undoEvent = null,
                statusBanner = message,
                messageSequence = it.messageSequence + 1,
            )
        }
    }

    fun triggerStartupSyncIfConfigured() {
        dispatch(MainAction.StartupSyncIfConfigured)
    }

    private suspend fun executeMutation(action: MainAction.Mutation) {
        when (action) {
            is MainAction.ToggleArchive -> performToggleArchive(action.entry)
            MainAction.UndoLastArchive -> performUndoArchive()
            MainAction.ArchiveVisibleEntries -> performArchiveVisibleEntries()
            is MainAction.ImportOpml -> performImportOpml(action.opmlXml)
            is MainAction.ToggleStar -> performToggleStar(action.entryId)
            is MainAction.ToggleReadState -> performToggleReadState(action.entryId)
            is MainAction.ToggleOfflineCache -> performToggleOffline(action.entryId)
            is MainAction.ToggleTagOnEntry ->
                repository.toggleTagOnItem(itemId = action.entryId, tagName = action.tagName)
            is MainAction.UpdateReadProgress ->
                repository.updateReadProgress(itemId = action.entryId, progress = action.progress)
            is MainAction.SaveUrlOrFeed ->
                performSaveUrl(action.url, action.titleOverride, action.tagCsv)
            else -> executeSyncOrSettingsMutation(action)
        }
    }

    internal suspend fun executeSyncOrSettingsMutation(action: MainAction.Mutation) {
        when (action) {
            is MainAction.RefreshFeed -> performRefreshFeed(action.feedId)
            is MainAction.RenameFeed -> performRenameFeed(action.feedId, action.newTitle)
            is MainAction.ApplyTagToFeed -> performApplyTagToFeed(action.feedId, action.tagName)
            is MainAction.ArchiveFeedUnread -> performArchiveFeedUnread(action.feedId)
            is MainAction.DeleteFeed -> performDeleteFeed(action.feedId)
            is MainAction.DeleteEntry -> performDeleteEntry(action.entryId)
            MainAction.SyncNow -> performSync(errorPolicy = SyncErrorPolicy.SHOW_BANNER)
            MainAction.PollFeedsOnDeviceNow -> performPollFeedsOnDevice()
            is MainAction.SyncWithServerConfig -> performSyncWithServerConfig(action)
            MainAction.StartupSyncIfConfigured -> performStartupSync()
            else -> applySettingsMutation(action)
        }
    }

    internal suspend fun performSyncWithServerConfig(action: MainAction.SyncWithServerConfig) {
        performUpdateServerConfig(
            MainAction.UpdateServerConfig(
                serverUrl = action.serverUrl,
                bearerToken = action.bearerToken,
                feedback = ConfigSaveFeedback.SILENT,
            )
        )
        performSync(errorPolicy = SyncErrorPolicy.SHOW_BANNER)
    }

    private suspend fun applySettingsMutation(action: MainAction.Mutation) {
        when (action) {
            is MainAction.SetThemeMode ->
                settingsRepository.update { it.copy(themeMode = action.mode) }
            is MainAction.SetHeadlineFont ->
                settingsRepository.update { it.copy(headlineFont = action.font) }
            is MainAction.SetUltraDenseRows ->
                settingsRepository.update { it.copy(ultraDenseRows = action.enabled) }
            is MainAction.SetReaderFontScale ->
                settingsRepository.update { it.copy(readerFontScale = action.scale) }
            is MainAction.SetSwipeToArchive ->
                settingsRepository.update { it.copy(swipeToArchive = action.enabled) }
            is MainAction.SetBottomNavSlot ->
                settingsRepository.update { current ->
                    val updatedTabs =
                        updateBottomNavSlot(current.bottomNavTabs, action.slotIndex, action.tab)
                    current.copy(bottomNavTabs = updatedTabs)
                }
            MainAction.ResetBottomNavTabs ->
                settingsRepository.update { it.copy(bottomNavTabs = AppTab.defaultSideTabs) }
            is MainAction.UpdateServerConfig -> performUpdateServerConfig(action)
            is MainAction.PairWithServer -> performPairWithServer(action)
            MainAction.DiscoverServer -> performDiscoverServer()
            else -> Unit
        }
    }

    internal suspend fun performUpdateServerConfig(action: MainAction.UpdateServerConfig) {
        settingsRepository.update {
            it.copy(
                serverUrl = action.serverUrl,
                bearerToken = action.bearerToken,
            )
        }
        if (action.feedback == ConfigSaveFeedback.SHOW_BANNER) {
            showStatusMessage("Saved server settings")
        }
    }

    internal suspend fun performDiscoverServer() {
        val message =
            repository
                .discoverServer()
                .fold(
                    onSuccess = { url -> "Discovered Roche server at $url" },
                    onFailure = { err -> "Discovery failed (${err.message ?: "not found"})" },
                )
        showStatusMessage(message)
    }

    internal suspend fun performPairWithServer(action: MainAction.PairWithServer) {
        if (action.pairingCode.isBlank()) {
            showStatusMessage("Enter a pairing code")
            return
        }
        repository
            .pairWithCode(
                serverUrl = action.serverUrl,
                pairingCode = action.pairingCode,
                deviceName = action.deviceName,
            )
            .onSuccess {
                val syncCount = repository.syncWithServer().getOrNull()
                val suffix = syncCount?.let { " · Synced $it entries" }.orEmpty()
                showStatusMessage("Paired with server$suffix")
            }
            .onFailure { error ->
                showStatusMessage("Pairing failed (${error.message ?: "error"})")
            }
    }

    internal suspend fun performToggleStar(entryId: String) {
        val starred = repository.toggleStar(entryId)
        showStatusMessage(if (starred) "Starred" else "Removed star")
    }

    internal suspend fun performToggleReadState(entryId: String) {
        val isRead = repository.toggleReadState(entryId)
        transientState.update {
            it.copy(
                contextSheetEntryId = null,
                undoEvent = null,
                statusBanner = if (isRead) "Marked as read" else "Marked as unread",
                messageSequence = it.messageSequence + 1,
            )
        }
    }

    internal suspend fun performToggleOffline(entryId: String) {
        val cached = repository.toggleOfflineCache(entryId)
        val msg = if (cached) "Cached local copy" else "Removed local offline copy"
        showStatusMessage(msg)
    }

    internal suspend fun performToggleArchive(entry: InboxEntry) {
        val targetArchived = !entry.isArchived
        val snapshot =
            repository.setArchiveStateWithSnapshot(itemId = entry.id, archived = targetArchived)
        val previousStatus = snapshot?.previousStatus ?: entry.status
        val previousReadAt = snapshot?.previousReadAt ?: entry.readAt
        val label = if (targetArchived) "Archived" else "Restored to Unread"
        transientState.update { current ->
            val nextReaderId =
                if (current.selectedReaderEntryId == entry.id) {
                    null
                } else {
                    current.selectedReaderEntryId
                }
            current.copy(
                selectedReaderEntryId = nextReaderId,
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

    internal suspend fun performUndoArchive() {
        val pending = transientState.value.undoEvent ?: return
        if (pending.batchSnapshots.size > 1) {
            repository.restoreArchiveSnapshots(pending.batchSnapshots)
            showStatusMessage("Restored ${pending.batchSnapshots.size} entries")
        } else {
            repository.restoreStatus(
                itemId = pending.itemId,
                status = pending.previousStatus,
                readAt = pending.previousReadAt,
            )
            showStatusMessage("Restored entry")
        }
    }

    internal suspend fun performArchiveVisibleEntries() {
        val currentUi = uiState.value
        val unarchivedIds = currentUi.visibleEntries.filter { !it.isArchived }.map { it.id }
        if (unarchivedIds.isEmpty()) return
        val snapshots =
            repository.archiveBatchWithSnapshots(
                entryIds = unarchivedIds,
                feedId = currentUi.selectedFeedId,
                allUnread =
                    currentUi.selectedFeedId == null &&
                        currentUi.selectedTag == null &&
                        currentUi.activeFilter == InboxFilter.UNREAD,
            )
        if (snapshots.isNotEmpty()) {
            val archivedCount = snapshots.size
            val noun = entryCountNoun(archivedCount)
            val first = snapshots.first()
            transientState.update {
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
    }

    internal suspend fun performImportOpml(opmlXml: String) {
        if (!tryAcquireSyncLock()) return
        try {
            repository
                .importOpml(opmlXml)
                .onSuccess { importedCount ->
                    val noun = if (importedCount == 1) "feed" else "feeds"
                    showStatusMessage("Imported $importedCount $noun from OPML")
                }
                .onFailure { error ->
                    showStatusMessage("OPML import failed (${error.message ?: "error"})")
                }
        } finally {
            transientState.update { it.copy(isSyncing = false) }
        }
    }

    internal suspend fun performSaveUrl(url: String, titleOverride: String?, tagCsv: String) {
        if (url.isBlank()) return
        val created =
            repository.saveUrlOrFeed(
                inputUrl = url,
                titleOverride = titleOverride,
                tagCsv = tagCsv,
            )
        val (verb, nextTab) =
            when (created.kind) {
                SavedResourceKind.FEED -> "Subscribed" to AppTab.FEEDS
                SavedResourceKind.ITEM -> "Saved" to AppTab.INBOX
            }
        transientState.update {
            it.copy(
                isAddSheetOpen = false,
                prefilledShareUrl = "",
                prefilledShareTitle = "",
                selectedReaderEntryId = null,
                activeTab = nextTab,
                activeFilter = InboxFilter.ALL,
                undoEvent = null,
                statusBanner = "$verb \"${created.title.take(SNACKBAR_TITLE_LIMIT)}\"",
                messageSequence = it.messageSequence + 1,
            )
        }
    }

    internal suspend fun performRefreshFeed(feedId: String) {
        if (!tryAcquireSyncLock()) return
        try {
            repository
                .refreshFeed(feedId)
                .onSuccess { title ->
                    showStatusMessage("Refreshed \"${title.take(SNACKBAR_TITLE_LIMIT)}\"")
                }
                .onFailure { error ->
                    showStatusMessage("Refresh failed (${error.message ?: "unreachable"})")
                }
        } finally {
            transientState.update { it.copy(isSyncing = false) }
        }
    }

    internal suspend fun performRenameFeed(feedId: String, newTitle: String) {
        val updatedTitle = repository.renameFeed(feedId = feedId, newTitle = newTitle)
        if (updatedTitle != null) {
            showStatusMessage("Renamed feed to \"${updatedTitle.take(SNACKBAR_TITLE_LIMIT)}\"")
        }
    }

    internal suspend fun performApplyTagToFeed(feedId: String, tagName: String) {
        val count = repository.applyTagToFeedItems(feedId = feedId, tagName = tagName)
        val cleanTag = com.grayvines.roche.data.normalizeTagName(tagName)
        if (cleanTag.isNotBlank()) {
            val msg =
                if (count > 0) {
                    "Tagged $count ${entryCountNoun(count)} with #$cleanTag"
                } else {
                    "All active feed entries already have #$cleanTag"
                }
            showStatusMessage(msg)
        }
    }

    internal suspend fun performArchiveFeedUnread(feedId: String) {
        val unreadIds =
            uiState.value.allEntries.filter { it.feedId == feedId && !it.isArchived }.map { it.id }
        if (unreadIds.isEmpty()) return
        val snapshots =
            repository.archiveBatchWithSnapshots(
                entryIds = unreadIds,
                feedId = feedId,
                allUnread = false,
            )
        if (snapshots.isNotEmpty()) {
            val count = snapshots.size
            val noun = entryCountNoun(count)
            val first = snapshots.first()
            transientState.update {
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
    }

    internal suspend fun performDeleteFeed(feedId: String) {
        repository
            .deleteFeed(feedId)
            .onSuccess { title ->
                transientState.update { current ->
                    val nextFeedId =
                        if (current.selectedFeedId == feedId) null else current.selectedFeedId
                    val nextFeedSheetId =
                        if (current.feedContextSheetId == feedId) {
                            null
                        } else {
                            current.feedContextSheetId
                        }
                    current.copy(
                        selectedFeedId = nextFeedId,
                        feedContextSheetId = nextFeedSheetId,
                        undoEvent = null,
                        statusBanner = "Unsubscribed from \"${title.take(SNACKBAR_TITLE_LIMIT)}\"",
                        messageSequence = current.messageSequence + 1,
                    )
                }
            }
            .onFailure { error ->
                showStatusMessage("Unsubscribe failed (${error.message ?: "error"})")
            }
    }

    internal suspend fun performDeleteEntry(entryId: String) {
        repository
            .deleteItem(entryId)
            .onSuccess { title ->
                transientState.update { current ->
                    val nextReaderId =
                        if (current.selectedReaderEntryId == entryId) {
                            null
                        } else {
                            current.selectedReaderEntryId
                        }
                    val nextSheetId =
                        if (current.contextSheetEntryId == entryId) {
                            null
                        } else {
                            current.contextSheetEntryId
                        }
                    current.copy(
                        selectedReaderEntryId = nextReaderId,
                        contextSheetEntryId = nextSheetId,
                        undoEvent = null,
                        statusBanner = "Deleted \"${title.take(SNACKBAR_TITLE_LIMIT)}\"",
                        messageSequence = current.messageSequence + 1,
                    )
                }
            }
            .onFailure { error ->
                showStatusMessage("Delete failed (${error.message ?: "error"})")
            }
    }

    internal suspend fun performStartupSync() {
        val currentSettings = settingsRepository.settings.first()
        if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
            performSync(errorPolicy = SyncErrorPolicy.SILENT)
        }
    }

    internal suspend fun performPollFeedsOnDevice() {
        if (!tryAcquireSyncLock()) return
        try {
            repository
                .pollDueFeedsOnDeviceNow()
                .onSuccess { count ->
                    val msg =
                        if (count == 0) {
                            "All cooperative feeds are up to date"
                        } else {
                            val noun = if (count == 1) "feed" else "feeds"
                            "Polled $count due $noun on-device"
                        }
                    showStatusMessage(msg)
                }
                .onFailure { error ->
                    showStatusMessage("On-device poll skipped (${error.message ?: "offline"})")
                }
        } finally {
            transientState.update { it.copy(isSyncing = false) }
        }
    }

    private suspend fun performSync(errorPolicy: SyncErrorPolicy = SyncErrorPolicy.SHOW_BANNER) {
        if (!tryAcquireSyncLock()) return
        try {
            repository
                .syncWithServer()
                .onSuccess { count ->
                    showStatusMessage("Synced $count entries from server")
                }
                .onFailure { error ->
                    if (errorPolicy == SyncErrorPolicy.SHOW_BANNER) {
                        showStatusMessage("Sync offline (${error.message ?: "unreachable"})")
                    }
                }
        } finally {
            transientState.update { it.copy(isSyncing = false) }
        }
    }

    internal fun tryAcquireSyncLock(): Boolean {
        var acquired = false
        transientState.update { current ->
            if (current.isSyncing) {
                acquired = false
                current
            } else {
                acquired = true
                current.copy(isSyncing = true)
            }
        }
        return acquired
    }

    class Factory(
        private val repository: RocheRepository,
        private val settings: SettingsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repository = repository, settingsRepository = settings) as T
    }

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val SNACKBAR_TITLE_LIMIT = 36
    }
}

internal fun reduceUiAction(
    state: TransientUiState,
    action: MainAction.Ui,
): TransientUiState =
    when (action) {
        is MainAction.SelectTab ->
            state.copy(
                activeTab = action.tab,
                selectedReaderEntryId = null,
                selectedFeedId = null,
                selectedTag = null,
                scopeOriginTab = null,
            )
        is MainAction.SelectFilter ->
            state.copy(
                activeFilter = action.filter,
                selectedTag = null,
                selectedFeedId = null,
                scopeOriginTab = null,
            )
        is MainAction.FilterByTag ->
            state.copy(
                activeTab = AppTab.INBOX,
                selectedTag = action.tagName,
                selectedFeedId = null,
                scopeOriginTab = resolveNextScopeOriginTab(state, action.tagName != null),
                selectedReaderEntryId = null,
                contextSheetEntryId = null,
            )
        is MainAction.FilterByFeed ->
            state.copy(
                activeTab = AppTab.INBOX,
                selectedFeedId = action.feedId,
                selectedTag = null,
                scopeOriginTab = resolveNextScopeOriginTab(state, action.feedId != null),
                selectedReaderEntryId = null,
                contextSheetEntryId = null,
                feedContextSheetId = null,
            )
        MainAction.NavigateBackFromScope ->
            state.copy(
                activeTab = state.scopeOriginTab ?: AppTab.INBOX,
                selectedFeedId = null,
                selectedTag = null,
                scopeOriginTab = null,
                selectedReaderEntryId = null,
                contextSheetEntryId = null,
                feedContextSheetId = null,
            )
        is MainAction.UpdateSearchQuery -> state.copy(searchQuery = action.query)
        else -> reduceOverlayUiAction(state, action)
    }

private fun resolveNextScopeOriginTab(state: TransientUiState, hasNewScope: Boolean): AppTab? =
    when {
        !hasNewScope -> null
        state.activeTab != AppTab.INBOX -> state.activeTab
        else -> state.scopeOriginTab
    }

private fun reduceOverlayUiAction(
    state: TransientUiState,
    action: MainAction.Ui,
): TransientUiState =
    when (action) {
        is MainAction.OpenReader ->
            state.copy(selectedReaderEntryId = action.entryId, contextSheetEntryId = null)
        MainAction.CloseReader -> state.copy(selectedReaderEntryId = null)
        is MainAction.OpenContextSheet -> state.copy(contextSheetEntryId = action.entryId)
        MainAction.CloseContextSheet -> state.copy(contextSheetEntryId = null)
        is MainAction.OpenFeedContextSheet -> state.copy(feedContextSheetId = action.feedId)
        MainAction.CloseFeedContextSheet -> state.copy(feedContextSheetId = null)
        is MainAction.OpenAddSheet ->
            state.copy(
                isAddSheetOpen = true,
                prefilledShareUrl = action.prefilledUrl,
                prefilledShareTitle = action.prefilledTitle,
                contextSheetEntryId = null,
                feedContextSheetId = null,
            )
        MainAction.CloseAddSheet ->
            state.copy(
                isAddSheetOpen = false,
                prefilledShareUrl = "",
                prefilledShareTitle = "",
            )
        MainAction.DismissMessages -> state.copy(undoEvent = null, statusBanner = null)
        else -> state
    }

internal fun updateBottomNavSlot(
    current: List<AppTab>,
    slotIndex: Int,
    newTab: AppTab,
): List<AppTab> {
    if (newTab == AppTab.INBOX || newTab !in AppTab.sideTabCandidates) {
        return AppTab.normalizeSideTabs(current)
    }
    val normalized = AppTab.normalizeSideTabs(current).toMutableList()
    if (slotIndex !in normalized.indices) return normalized
    val existingIndex = normalized.indexOf(newTab)
    val oldTab = normalized[slotIndex]
    normalized[slotIndex] = newTab
    if (existingIndex != -1 && existingIndex != slotIndex) {
        normalized[existingIndex] = oldTab
    }
    return AppTab.normalizeSideTabs(normalized)
}

internal fun buildMainUiState(
    snapshot: RepositorySnapshot,
    settings: Settings,
    local: TransientUiState,
    localNetworkTier: NetworkTier = NetworkTier.WIFI,
): MainUiState {
    val filtered =
        RocheRepository.filterEntries(
            entries = snapshot.entries,
            filter = local.activeFilter,
            searchQuery = "",
            selectedTag = local.selectedTag,
            selectedFeedId = local.selectedFeedId,
        )
    val normalizedQuery = local.searchQuery.trim().lowercase()
    val searchResults =
        if (normalizedQuery.isBlank()) {
            snapshot.entries
        } else {
            snapshot.entries.filter { entry ->
                com.grayvines.roche.data.matchesSearchQuery(entry, normalizedQuery)
            }
        }
    return MainUiState(
        activeTab = local.activeTab,
        activeFilter = local.activeFilter,
        searchQuery = local.searchQuery,
        selectedTag = local.selectedTag,
        selectedFeedId = local.selectedFeedId,
        scopeOriginTab = local.scopeOriginTab,
        selectedReaderEntryId = local.selectedReaderEntryId,
        contextSheetEntryId = local.contextSheetEntryId,
        feedContextSheetId = local.feedContextSheetId,
        isAddSheetOpen = local.isAddSheetOpen,
        prefilledShareUrl = local.prefilledShareUrl,
        prefilledShareTitle = local.prefilledShareTitle,
        isSyncing = local.isSyncing,
        visibleEntries = filtered,
        searchResults = searchResults,
        allEntries = snapshot.entries,
        feeds = snapshot.feeds,
        tags = snapshot.tags,
        activePeers = snapshot.activePeers,
        localNetworkTier = localNetworkTier,
        settings = settings,
        undoEvent = local.undoEvent,
        statusBanner = local.statusBanner,
        messageSequence = local.messageSequence,
    )
}

internal fun entryCountNoun(count: Int): String = if (count == 1) "entry" else "entries"
