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

    data object SyncNow : Mutation

    data class SetThemeMode(val mode: ThemeMode) : Mutation

    data class SetHeadlineFont(val font: HeadlineFont) : Mutation

    data class SetUltraDenseRows(val enabled: Boolean) : Mutation

    data class SetSwipeToArchive(val enabled: Boolean) : Mutation

    data class UpdateServerConfig(
        val serverUrl: String,
        val bearerToken: String,
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
            MainAction.SyncNow -> performSync()
            else -> applySettingsMutation(action)
        }
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
        transientState.update { it.copy(statusBanner = "Saved server settings") }
    }

    internal suspend fun performDiscoverServer() {
        val message =
            repository
                .discoverServer()
                .fold(
                    onSuccess = { url -> "Discovered Roche server at $url" },
                    onFailure = { err -> "Discovery failed (${err.message ?: "not found"})" },
                )
        transientState.update { it.copy(statusBanner = message) }
    }

    private suspend fun performPairWithServer(action: MainAction.PairWithServer) {
        if (action.pairingCode.isBlank()) {
            transientState.update { it.copy(statusBanner = "Enter a pairing code") }
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
                transientState.update { it.copy(statusBanner = "Paired with server$suffix") }
            }
            .onFailure { error ->
                transientState.update {
                    it.copy(statusBanner = "Pairing failed (${error.message ?: "error"})")
                }
            }
    }

    private suspend fun performToggleStar(entryId: String) {
        val starred = repository.toggleStar(entryId)
        transientState.update {
            it.copy(statusBanner = if (starred) "Starred" else "Removed star")
        }
    }

    private suspend fun performToggleOffline(entryId: String) {
        val cached = repository.toggleOfflineCache(entryId)
        val msg = if (cached) "Cached local copy" else "Removed local offline copy"
        transientState.update { it.copy(statusBanner = msg) }
    }

    private suspend fun performToggleArchive(entry: InboxEntry) {
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
                undoEvent =
                    UndoArchiveEvent(
                        itemId = entry.id,
                        previousStatus = previousStatus,
                        message = "$label · ${entry.title.take(SNACKBAR_TITLE_LIMIT)}",
                    ),
            )
        }
    }

    private suspend fun performUndoArchive() {
        val pending = transientState.value.undoEvent ?: return
        repository.restoreStatus(itemId = pending.itemId, status = pending.previousStatus)
        transientState.update { it.copy(undoEvent = null, statusBanner = "Restored entry") }
    }

    private suspend fun performSaveUrl(url: String, titleOverride: String?, tagCsv: String) {
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
                statusBanner = "$verb \"${created.title.take(SNACKBAR_TITLE_LIMIT)}\"",
            )
        }
    }

    private suspend fun performSync() {
        repository
            .syncWithServer()
            .onSuccess { count ->
                transientState.update {
                    it.copy(statusBanner = "Synced $count entries from server")
                }
            }
            .onFailure { error ->
                transientState.update {
                    it.copy(statusBanner = "Sync offline (${error.message ?: "unreachable"})")
                }
            }
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
