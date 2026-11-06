package com.grayvines.roche.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
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
)

data class MainUiState(
    val activeTab: AppTab = AppTab.INBOX,
    val activeFilter: InboxFilter = InboxFilter.UNREAD,
    val searchQuery: String = "",
    val selectedTag: String? = null,
    val selectedFeedId: String? = null,
    val selectedReaderEntryId: String? = null,
    val contextSheetEntryId: String? = null,
    val isAddSheetOpen: Boolean = false,
    val prefilledShareUrl: String = "",
    val prefilledShareTitle: String = "",
    val isSyncing: Boolean = false,
    val visibleEntries: List<InboxEntry> = emptyList(),
    val searchResults: List<InboxEntry> = emptyList(),
    val allEntries: List<InboxEntry> = emptyList(),
    val feeds: List<FeedItem> = emptyList(),
    val tags: List<TagItem> = emptyList(),
    val settings: Settings = Settings(),
    val undoEvent: UndoArchiveEvent? = null,
    val statusBanner: String? = null,
) {
    val activeReaderEntry: InboxEntry?
        get() = allEntries.firstOrNull { it.id == selectedReaderEntryId }

    val contextSheetEntry: InboxEntry?
        get() = allEntries.firstOrNull { it.id == contextSheetEntryId }

    val offlineEntries: List<InboxEntry>
        get() = allEntries.filter { it.isCachedOffline }

    val selectedFeedTitle: String?
        get() = feeds.firstOrNull { it.id == selectedFeedId }?.title
}

sealed interface MainAction {
    sealed interface Ui : MainAction

    sealed interface Mutation : MainAction

    data class SelectTab(val tab: AppTab) : Ui

    data class SelectFilter(val filter: InboxFilter) : Ui

    data class FilterByTag(val tagName: String?) : Ui

    data class FilterByFeed(val feedId: String?) : Ui

    data class UpdateSearchQuery(val query: String) : Ui

    data class OpenReader(val entryId: String) : Ui

    data object CloseReader : Ui

    data class OpenContextSheet(val entryId: String) : Ui

    data object CloseContextSheet : Ui

    data class OpenAddSheet(
        val prefilledUrl: String = "",
        val prefilledTitle: String = "",
    ) : Ui

    data object CloseAddSheet : Ui

    data object DismissMessages : Ui

    data class ToggleArchive(val entry: InboxEntry) : Mutation

    data object UndoLastArchive : Mutation

    data class ToggleStar(val entryId: String) : Mutation

    data class ToggleOfflineCache(val entryId: String) : Mutation

    data class ToggleTagOnEntry(val entryId: String, val tagName: String) : Mutation

    data class UpdateReadProgress(val entryId: String, val progress: Double) : Mutation

    data class SaveUrlOrFeed(val url: String, val titleOverride: String?, val tagCsv: String) :
        Mutation

    data class RefreshFeed(val feedId: String) : Mutation

    data class DeleteFeed(val feedId: String) : Mutation

    data class DeleteEntry(val entryId: String) : Mutation

    data object SyncNow : Mutation

    data class SyncWithServerConfig(
        val serverUrl: String,
        val bearerToken: String,
    ) : Mutation

    data object StartupSyncIfConfigured : Mutation

    data class SetThemeMode(val mode: ThemeMode) : Mutation

    data class SetHeadlineFont(val font: HeadlineFont) : Mutation

    data class SetUltraDenseRows(val enabled: Boolean) : Mutation

    data class SetSwipeToArchive(val enabled: Boolean) : Mutation

    data class UpdateServerConfig(
        val serverUrl: String,
        val bearerToken: String,
        val silent: Boolean = false,
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
    val activeFilter: InboxFilter = InboxFilter.UNREAD,
    val searchQuery: String = "",
    val selectedTag: String? = null,
    val selectedFeedId: String? = null,
    val selectedReaderEntryId: String? = null,
    val contextSheetEntryId: String? = null,
    val isAddSheetOpen: Boolean = false,
    val prefilledShareUrl: String = "",
    val prefilledShareTitle: String = "",
    val isSyncing: Boolean = false,
    val undoEvent: UndoArchiveEvent? = null,
    val statusBanner: String? = null,
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
                buildMainUiState(snapshot = snapshot, settings = settings, local = local)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = MainUiState(),
            )

    fun dispatch(action: MainAction) {
        when (action) {
            is MainAction.Ui -> transientState.update { reduceUiAction(it, action) }
            is MainAction.Mutation -> viewModelScope.launch { executeMutation(action) }
        }
    }

    fun triggerStartupSyncIfConfigured() {
        dispatch(MainAction.StartupSyncIfConfigured)
    }

    private suspend fun executeMutation(action: MainAction.Mutation) {
        when (action) {
            is MainAction.ToggleArchive -> performToggleArchive(action.entry)
            MainAction.UndoLastArchive -> performUndoArchive()
            is MainAction.ToggleStar -> performToggleStar(action.entryId)
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
            is MainAction.DeleteFeed -> performDeleteFeed(action.feedId)
            is MainAction.DeleteEntry -> performDeleteEntry(action.entryId)
            MainAction.SyncNow -> performSync(silentOnError = false)
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
                silent = true,
            )
        )
        performSync(silentOnError = false)
    }

    private suspend fun applySettingsMutation(action: MainAction.Mutation) {
        when (action) {
            is MainAction.SetThemeMode ->
                settingsRepository.update { it.copy(themeMode = action.mode) }
            is MainAction.SetHeadlineFont ->
                settingsRepository.update { it.copy(headlineFont = action.font) }
            is MainAction.SetUltraDenseRows ->
                settingsRepository.update { it.copy(ultraDenseRows = action.enabled) }
            is MainAction.SetSwipeToArchive ->
                settingsRepository.update { it.copy(swipeToArchive = action.enabled) }
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
        if (!action.silent) {
            transientState.update {
                it.copy(undoEvent = null, statusBanner = "Saved server settings")
            }
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
        transientState.update { it.copy(undoEvent = null, statusBanner = message) }
    }

    internal suspend fun performPairWithServer(action: MainAction.PairWithServer) {
        if (action.pairingCode.isBlank()) {
            transientState.update {
                it.copy(undoEvent = null, statusBanner = "Enter a pairing code")
            }
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
                transientState.update {
                    it.copy(undoEvent = null, statusBanner = "Paired with server$suffix")
                }
            }
            .onFailure { error ->
                transientState.update {
                    it.copy(
                        undoEvent = null,
                        statusBanner = "Pairing failed (${error.message ?: "error"})",
                    )
                }
            }
    }

    internal suspend fun performToggleStar(entryId: String) {
        val starred = repository.toggleStar(entryId)
        transientState.update {
            it.copy(undoEvent = null, statusBanner = if (starred) "Starred" else "Removed star")
        }
    }

    internal suspend fun performToggleOffline(entryId: String) {
        val cached = repository.toggleOfflineCache(entryId)
        val msg = if (cached) "Cached local copy" else "Removed local offline copy"
        transientState.update { it.copy(undoEvent = null, statusBanner = msg) }
    }

    internal suspend fun performToggleArchive(entry: InboxEntry) {
        val targetArchived = !entry.isArchived
        val previousStatus =
            repository.setArchiveState(itemId = entry.id, archived = targetArchived) ?: entry.status
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
                undoEvent =
                    UndoArchiveEvent(
                        itemId = entry.id,
                        previousStatus = previousStatus,
                        message = "$label · ${entry.title.take(SNACKBAR_TITLE_LIMIT)}",
                    ),
            )
        }
    }

    internal suspend fun performUndoArchive() {
        val pending = transientState.value.undoEvent ?: return
        repository.restoreStatus(itemId = pending.itemId, status = pending.previousStatus)
        transientState.update { it.copy(undoEvent = null, statusBanner = "Restored entry") }
    }

    internal suspend fun performSaveUrl(url: String, titleOverride: String?, tagCsv: String) {
        if (url.isBlank()) return
        val created =
            repository.saveUrlOrFeed(
                inputUrl = url,
                titleOverride = titleOverride,
                tagCsv = tagCsv,
            )
        val verb = if (created.isFeed) "Subscribed" else "Saved"
        val nextTab = if (created.isFeed) AppTab.FEEDS else AppTab.INBOX
        transientState.update {
            it.copy(
                isAddSheetOpen = false,
                prefilledShareUrl = "",
                prefilledShareTitle = "",
                selectedReaderEntryId = null,
                activeTab = nextTab,
                activeFilter = InboxFilter.UNREAD,
                undoEvent = null,
                statusBanner = "$verb \"${created.title.take(SNACKBAR_TITLE_LIMIT)}\"",
            )
        }
    }

    internal suspend fun performRefreshFeed(feedId: String) {
        if (!tryAcquireSyncLock()) return
        try {
            repository
                .refreshFeed(feedId)
                .onSuccess { title ->
                    transientState.update {
                        it.copy(
                            undoEvent = null,
                            statusBanner = "Refreshed \"${title.take(SNACKBAR_TITLE_LIMIT)}\"",
                        )
                    }
                }
                .onFailure { error ->
                    transientState.update {
                        it.copy(
                            undoEvent = null,
                            statusBanner = "Refresh failed (${error.message ?: "unreachable"})",
                        )
                    }
                }
        } finally {
            transientState.update { it.copy(isSyncing = false) }
        }
    }

    internal suspend fun performDeleteFeed(feedId: String) {
        repository
            .deleteFeed(feedId)
            .onSuccess { title ->
                transientState.update { current ->
                    val nextFeedId =
                        if (current.selectedFeedId == feedId) null else current.selectedFeedId
                    current.copy(
                        selectedFeedId = nextFeedId,
                        undoEvent = null,
                        statusBanner = "Unsubscribed from \"${title.take(SNACKBAR_TITLE_LIMIT)}\"",
                    )
                }
            }
            .onFailure { error ->
                transientState.update {
                    it.copy(
                        undoEvent = null,
                        statusBanner = "Unsubscribe failed (${error.message ?: "error"})",
                    )
                }
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
                    )
                }
            }
            .onFailure { error ->
                transientState.update {
                    it.copy(
                        undoEvent = null,
                        statusBanner = "Delete failed (${error.message ?: "error"})",
                    )
                }
            }
    }

    internal suspend fun performStartupSync() {
        val currentSettings = settingsRepository.settings.first()
        if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
            performSync(silentOnError = true)
        }
    }

    private suspend fun performSync(silentOnError: Boolean = false) {
        if (!tryAcquireSyncLock()) return
        try {
            repository
                .syncWithServer()
                .onSuccess { count ->
                    transientState.update {
                        it.copy(
                            undoEvent = null,
                            statusBanner = "Synced $count entries from server",
                        )
                    }
                }
                .onFailure { error ->
                    if (!silentOnError) {
                        transientState.update {
                            it.copy(
                                undoEvent = null,
                                statusBanner = "Sync offline (${error.message ?: "unreachable"})",
                            )
                        }
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
        is MainAction.SelectTab -> state.copy(activeTab = action.tab, selectedReaderEntryId = null)
        is MainAction.SelectFilter ->
            state.copy(
                activeFilter = action.filter,
                selectedTag = null,
                selectedFeedId = null,
            )
        is MainAction.FilterByTag ->
            state.copy(
                activeTab = AppTab.INBOX,
                selectedTag = action.tagName,
                selectedFeedId = null,
                selectedReaderEntryId = null,
                contextSheetEntryId = null,
            )
        is MainAction.FilterByFeed ->
            state.copy(
                activeTab = AppTab.INBOX,
                selectedFeedId = action.feedId,
                selectedTag = null,
                selectedReaderEntryId = null,
                contextSheetEntryId = null,
            )
        is MainAction.UpdateSearchQuery -> state.copy(searchQuery = action.query)
        else -> reduceOverlayUiAction(state, action)
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
        is MainAction.OpenAddSheet ->
            state.copy(
                isAddSheetOpen = true,
                prefilledShareUrl = action.prefilledUrl,
                prefilledShareTitle = action.prefilledTitle,
                contextSheetEntryId = null,
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

internal fun buildMainUiState(
    snapshot: RepositorySnapshot,
    settings: Settings,
    local: TransientUiState,
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
        selectedReaderEntryId = local.selectedReaderEntryId,
        contextSheetEntryId = local.contextSheetEntryId,
        isAddSheetOpen = local.isAddSheetOpen,
        prefilledShareUrl = local.prefilledShareUrl,
        prefilledShareTitle = local.prefilledShareTitle,
        isSyncing = local.isSyncing,
        visibleEntries = filtered,
        searchResults = searchResults,
        allEntries = snapshot.entries,
        feeds = snapshot.feeds,
        tags = snapshot.tags,
        settings = settings,
        undoEvent = local.undoEvent,
        statusBanner = local.statusBanner,
    )
}
