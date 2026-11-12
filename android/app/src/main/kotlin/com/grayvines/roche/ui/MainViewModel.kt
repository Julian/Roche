package com.grayvines.roche.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.grayvines.roche.data.ArchiveStateSnapshot
import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.PeerNodeDto
import com.grayvines.roche.data.ReaderItemContent
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.SavedResourceKind
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
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
    val readerContent: ReaderItemContent? = null,
    val contextSheetEntryId: String? = null,
    val feedContextSheetId: String? = null,
    val isAddSheetOpen: Boolean = false,
    val prefilledShareUrl: String = "",
    val prefilledShareTitle: String = "",
    val isInitialLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val visibleEntries: List<InboxEntry> = emptyList(),
    val searchResults: List<InboxEntry> = emptyList(),
    val allEntries: List<InboxEntry> = emptyList(),
    val feeds: List<FeedItem> = emptyList(),
    val tags: List<TagItem> = emptyList(),
    val activePeers: List<PeerNodeDto> = emptyList(),
    val localNetworkTier: NetworkTier = NetworkTier.WIFI,
    val settings: Settings = Settings(),
    val playerState: PodcastPlayerState = PodcastPlayerState(),
    val undoEvent: UndoArchiveEvent? = null,
    val statusBanner: String? = null,
    val messageSequence: Long = 0L,
) {
    val activePlayerEntry: InboxEntry?
        get() {
            val activeId = playerState.currentEntryId ?: return null
            val base = allEntries.firstOrNull { it.id == activeId } ?: return null
            val full = readerContent?.takeIf { it.itemId == base.id } ?: return base
            val mergedHtml = pickLongerReaderText(full.contentHtml, base.contentHtml)
            val mergedText = pickLongerReaderText(full.contentText, base.contentText)
            return if (mergedHtml != base.contentHtml || mergedText != base.contentText) {
                base.copy(
                    contentHtml = mergedHtml,
                    contentText = mergedText,
                )
            } else {
                base
            }
        }

    val activePlayerFeed: FeedItem?
        get() =
            activePlayerEntry?.feedId?.let { fid ->
                feeds.firstOrNull { it.id == fid }
            }

    val queueEntries: List<InboxEntry>
        get() {
            if (settings.queueItemIds.isEmpty()) return emptyList()
            val byId = allEntries.associateBy { it.id }
            return settings.queueItemIds.mapNotNull { id ->
                byId[id]?.takeIf { !it.isArchived }
            }
        }

    val nextQueuedAudioEntry: InboxEntry?
        get() {
            val activeAudioId = playerState.currentEntryId
            return queueEntries.firstOrNull { entry ->
                entry.contentType == ContentType.AUDIO && entry.id != activeAudioId
            }
        }

    val nextQueuedAudioEntryId: String?
        get() = nextQueuedAudioEntry?.id

    val activeReaderEntry: InboxEntry?
        get() {
            val base = allEntries.firstOrNull { it.id == selectedReaderEntryId } ?: return null
            val full = readerContent?.takeIf { it.itemId == base.id } ?: return base
            val mergedHtml = pickLongerReaderText(full.contentHtml, base.contentHtml)
            val mergedText = pickLongerReaderText(full.contentText, base.contentText)
            return if (mergedHtml != base.contentHtml || mergedText != base.contentText) {
                base.copy(
                    contentHtml = mergedHtml,
                    contentText = mergedText,
                )
            } else {
                base
            }
        }

    val contextSheetEntry: InboxEntry?
        get() = allEntries.firstOrNull { it.id == contextSheetEntryId }

    val contextSheetFeed: FeedItem?
        get() = feeds.firstOrNull { it.id == feedContextSheetId }

    val activeFeeds: List<FeedItem>
        get() = feeds.filter { !it.isArchived }

    val archivedFeeds: List<FeedItem>
        get() = feeds.filter { it.isArchived }

    val starredEntries: List<InboxEntry>
        get() = allEntries.filter { it.isFavorite }

    val listeningHistoryEntries: List<InboxEntry>
        get() =
            allEntries
                .filter { entry ->
                    entry.contentType == ContentType.AUDIO &&
                        (!entry.readAt.isNullOrBlank() ||
                            entry.readProgress > 0.0 ||
                            entry.isArchived)
                }
                .sortedByDescending { entry ->
                    entry.readAt?.takeIf { it.isNotBlank() }
                        ?: entry.publishedAt?.takeIf { it.isNotBlank() }
                        ?: entry.createdAt
                }

    val offlineEntries: List<InboxEntry>
        get() = allEntries.filter { it.isCachedOffline }

    val selectedFeedTitle: String?
        get() = feeds.firstOrNull { it.id == selectedFeedId }?.title
}

fun MainUiState.isEntryInQueue(entryId: String): Boolean = entryId in settings.queueItemIds

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

    data object OpenFullscreenPlayer : Ui

    data object CloseFullscreenPlayer : Ui

    data class SetPlayerSpeedSheetOpen(val open: Boolean) : Ui

    data class SelectPlayerDrawerTab(val tab: PlayerDrawerTab?) : Ui

    data object RequestAudioOutputCast : Ui

    data class PlayAudioEntry(
        val entryId: String,
        val openFullscreen: Boolean = true,
    ) : Mutation

    data object TogglePlayPause : Mutation

    data class SeekPlayerToFraction(val fraction: Float) : Mutation

    data class SeekPlayerToMs(val positionMs: Long) : Mutation

    data class SkipPlayerBySeconds(val deltaSeconds: Int) : Mutation

    data object SkipToNextQueuedAudio : Mutation

    data class SetPlaybackSpeed(
        val speed: Float,
        val applyToCurrentPodcast: Boolean? = null,
    ) : Mutation

    data class SetRememberPodcastSpeed(
        val feedId: String,
        val enabled: Boolean,
    ) : Mutation

    data class SetSkipSilence(val enabled: Boolean) : Mutation

    data class SetSmartRewind(val enabled: Boolean) : Mutation

    data class SetSleepTimerMinutes(val minutes: Int?) : Mutation

    data class SetFeedAutoDownload(
        val feedId: String,
        val enabled: Boolean,
    ) : Mutation

    data class SetFeedPlaybackSpeed(
        val feedId: String,
        val speed: Float?,
    ) : Mutation

    data class ToggleQueueMembership(val entryId: String) : Mutation

    data class AddToQueue(
        val entryId: String,
        val playNext: Boolean = false,
    ) : Mutation

    data class RemoveFromQueue(val entryId: String) : Mutation

    data class MoveQueueItemUp(val entryId: String) : Mutation

    data class MoveQueueItemToTop(val entryId: String) : Mutation

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

    data class ToggleFeedArchive(val feedId: String) : Mutation

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
    val readerContent: ReaderItemContent? = null,
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

@Suppress("TooManyFunctions", "LargeClass")
class MainViewModel(
    private val repository: RocheRepository,
    private val settingsRepository: SettingsRepository,
    playerBackend: AudioPlayerBackend = ResilientMediaPlayerBackend(),
    private val mediaSessionBridge: PodcastMediaSessionBridge = NoOpPodcastMediaSessionBridge,
) : ViewModel() {

    private val transientState = MutableStateFlow(TransientUiState())

    internal val playerController =
        PodcastPlayerController(
            scope = viewModelScope,
            backend = playerBackend,
            mediaSessionBridge = mediaSessionBridge,
            onProgressChanged = { entryId, progress ->
                viewModelScope.launch {
                    repository.updateReadProgress(
                        itemId = entryId,
                        progress = progress,
                        recordPlayedTimestamp = true,
                    )
                }
            },
            onEpisodeCompleted = { completedEntryId ->
                viewModelScope.launch {
                    handleAudioEpisodeCompleted(completedEntryId)
                }
            },
            onSkipToNextRequested = {
                viewModelScope.launch {
                    performSkipToNextQueuedAudio()
                }
            },
            onSpeedChangedFromSession = { speed ->
                viewModelScope.launch {
                    performSetPlaybackSpeed(speed = speed, applyToCurrentPodcast = null)
                }
            },
            onPlaybackError = { message -> showStatusMessage(message) },
        )

    val uiState: StateFlow<MainUiState> =
        combine(
                repository.snapshot,
                settingsRepository.settings,
                transientState,
                playerController.state,
            ) {
                snapshot: RepositorySnapshot,
                settings: Settings,
                local: TransientUiState,
                playerState: PodcastPlayerState ->
                buildMainUiState(
                        snapshot = snapshot,
                        settings = settings,
                        local = local,
                        localNetworkTier = repository.currentNetworkTier(),
                        playerState =
                            playerState.copy(smartRewindEnabled = settings.smartRewindEnabled),
                    )
                    .also { built ->
                        playerController.updateHasNextQueuedAudio(
                            built.nextQueuedAudioEntryId != null
                        )
                        playerController.updateSmartRewindEnabled(settings.smartRewindEnabled)
                    }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = MainUiState(isInitialLoading = true),
            )

    override fun onCleared() {
        playerController.releaseController()
    }

    fun dispatch(action: MainAction) {
        when (action) {
            is MainAction.Ui -> {
                if (handlePlayerUiAction(action)) return
                if (action is MainAction.OpenReader) {
                    val existing = uiState.value.allEntries.firstOrNull { it.id == action.entryId }
                    if (existing?.contentType == ContentType.AUDIO) {
                        viewModelScope.launch {
                            performPlayAudioEntry(
                                entryId = action.entryId,
                                openFullscreen = true,
                            )
                        }
                        return
                    }
                }
                transientState.update { reduceUiAction(it, action) }
                if (action is MainAction.OpenReader) {
                    viewModelScope.launch {
                        val hydrated =
                            repository.hydrateItemContentIfNeeded(action.entryId) { localContent ->
                                applyReaderContentIfActive(action.entryId, localContent)
                            }
                        if (hydrated != null) {
                            applyReaderContentIfActive(action.entryId, hydrated)
                        }
                    }
                    viewModelScope.launch { repository.setReadState(action.entryId, true) }
                }
            }
            is MainAction.Mutation -> {
                viewModelScope.launch { executeMutation(action) }
            }
        }
    }

    private fun handlePlayerUiAction(action: MainAction.Ui): Boolean =
        when (action) {
            MainAction.OpenFullscreenPlayer -> {
                playerController.openFullscreen()
                true
            }
            MainAction.CloseFullscreenPlayer -> {
                playerController.closeFullscreen()
                true
            }
            is MainAction.SetPlayerSpeedSheetOpen -> {
                playerController.setSpeedSheetOpen(action.open)
                true
            }
            is MainAction.SelectPlayerDrawerTab -> {
                playerController.selectDrawerTab(action.tab)
                true
            }
            MainAction.RequestAudioOutputCast -> {
                showStatusMessage("Use system output switcher for Cast / Bluetooth audio")
                true
            }
            else -> {
                false
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
            else -> executePlayerMutation(action)
        }
    }

    internal suspend fun executePlayerMutation(action: MainAction.Mutation) {
        when (action) {
            is MainAction.PlayAudioEntry ->
                performPlayAudioEntry(action.entryId, action.openFullscreen)
            MainAction.TogglePlayPause ->
                playerController.togglePlayPause(
                    smartRewindEnabled = settingsRepository.settings.first().smartRewindEnabled
                )
            is MainAction.SeekPlayerToFraction -> playerController.seekToFraction(action.fraction)
            is MainAction.SeekPlayerToMs -> playerController.seekToMs(action.positionMs)
            is MainAction.SkipPlayerBySeconds -> playerController.skipBySeconds(action.deltaSeconds)
            MainAction.SkipToNextQueuedAudio -> performSkipToNextQueuedAudio()
            is MainAction.SetPlaybackSpeed ->
                performSetPlaybackSpeed(action.speed, action.applyToCurrentPodcast)
            is MainAction.SetRememberPodcastSpeed ->
                performSetRememberPodcastSpeed(action.feedId, action.enabled)
            is MainAction.SetSkipSilence -> performSetSkipSilence(action.enabled)
            is MainAction.SetSmartRewind -> performSetSmartRewind(action.enabled)
            is MainAction.SetSleepTimerMinutes -> performSetSleepTimerMinutes(action.minutes)
            else -> executeQueueOrSyncMutation(action)
        }
    }

    internal suspend fun executeQueueOrSyncMutation(action: MainAction.Mutation) {
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
            else -> executeSyncOrSettingsMutation(action)
        }
    }

    internal fun performSetSleepTimerMinutes(minutes: Int?) {
        playerController.setSleepTimerMinutes(minutes)
        val msg =
            if (minutes == null) {
                "Sleep timer off"
            } else {
                "Sleep timer set for ${minutes}m"
            }
        showStatusMessage(msg)
    }

    internal suspend fun executeSyncOrSettingsMutation(action: MainAction.Mutation) {
        when (action) {
            is MainAction.RefreshFeed -> performRefreshFeed(action.feedId)
            is MainAction.RenameFeed -> performRenameFeed(action.feedId, action.newTitle)
            is MainAction.ApplyTagToFeed -> performApplyTagToFeed(action.feedId, action.tagName)
            is MainAction.ArchiveFeedUnread -> performArchiveFeedUnread(action.feedId)
            is MainAction.ToggleFeedArchive -> performToggleFeedArchive(action.feedId)
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
        val prevEntry = uiState.value.allEntries.firstOrNull { it.id == entryId }
        val wasCached = prevEntry?.isCachedOffline == true
        val cached = repository.toggleOfflineCache(entryId)
        if (cached && transientState.value.selectedReaderEntryId == entryId) {
            repository.hydrateItemContentIfNeeded(entryId)?.let { content ->
                applyReaderContentIfActive(entryId, content)
            }
        }
        val entry = uiState.value.allEntries.firstOrNull { it.id == entryId } ?: prevEntry
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
        showStatusMessage(msg)
    }

    internal suspend fun performPlayAudioEntry(
        entryId: String,
        openFullscreen: Boolean = true,
    ) {
        val latestSnapshot = repository.snapshot.first()
        val latestSettings = settingsRepository.settings.first()
        val allEntries = latestSnapshot.entries
        val allFeeds = latestSnapshot.feeds
        val entry = allEntries.firstOrNull { it.id == entryId } ?: return
        if (playerController.state.value.currentEntryId == entryId) {
            if (openFullscreen) {
                playerController.openFullscreen()
                if (!playerController.state.value.isPlaying) {
                    playerController.togglePlayPause(latestSettings.smartRewindEnabled)
                }
            } else {
                playerController.togglePlayPause(latestSettings.smartRewindEnabled)
            }
            transientState.update { it.copy(contextSheetEntryId = null) }
            return
        }
        val feed = entry.feedId?.let { fid -> allFeeds.firstOrNull { it.id == fid } }
        val effectiveSpeed = feed?.customPlaybackSpeed ?: latestSettings.defaultPlaybackSpeed
        val source = repository.resolveAudioPlaybackSource(entry)
        val updatedQueue =
            if (entryId in latestSettings.queueItemIds) {
                latestSettings.queueItemIds
            } else {
                listOf(entryId) + latestSettings.queueItemIds
            }
        settingsRepository.update { current -> current.copy(queueItemIds = updatedQueue) }
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
        transientState.update { it.copy(contextSheetEntryId = null) }
        repository.recordEpisodePlaybackStarted(entryId)
        viewModelScope.launch {
            val hydrated =
                repository.hydrateItemContentIfNeeded(entryId) { localContent ->
                    applyReaderContentIfActive(entryId, localContent)
                }
            if (hydrated != null) {
                applyReaderContentIfActive(entryId, hydrated)
            }
        }
    }

    private suspend fun syncActivePlayerQueueState() {
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

    internal suspend fun handleAudioEpisodeCompleted(completedEntryId: String) {
        val wasFullscreen = playerController.state.value.isFullscreenOpen
        repository.updateReadProgress(itemId = completedEntryId, progress = 1.0)
        repository.setArchiveStateWithSnapshot(itemId = completedEntryId, archived = true)
        settingsRepository.update { current ->
            current.copy(queueItemIds = current.queueItemIds.filterNot { it == completedEntryId })
        }
        val nextAudio = resolveNextQueuedAudioEntry(excludeEntryId = completedEntryId)
        if (nextAudio != null) {
            performPlayAudioEntry(
                entryId = nextAudio.id,
                openFullscreen = wasFullscreen,
            )
            showStatusMessage("Up next · ${nextAudio.title.take(SNACKBAR_TITLE_LIMIT)}")
        } else {
            playerController.stopAndClear()
            showStatusMessage("Finished episode · Archived & deleted download")
        }
    }

    internal suspend fun performSkipToNextQueuedAudio() {
        val currentState = playerController.state.value
        val currentId = currentState.currentEntryId
        val nextAudio = resolveNextQueuedAudioEntry(excludeEntryId = currentId)
        if (nextAudio == null) {
            showStatusMessage("No next audio episode in Queue")
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
        performPlayAudioEntry(entryId = nextAudio.id, openFullscreen = openFullscreen)
        showStatusMessage("Up next · ${nextAudio.title.take(SNACKBAR_TITLE_LIMIT)}")
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

    internal suspend fun performSetPlaybackSpeed(
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

    internal suspend fun performSetRememberPodcastSpeed(
        feedId: String,
        enabled: Boolean,
    ) {
        val currentSpeed = playerController.state.value.playbackSpeed
        if (enabled) {
            repository.setFeedPlaybackSpeed(feedId = feedId, speed = currentSpeed)
        } else {
            repository.setFeedPlaybackSpeed(feedId = feedId, speed = null)
        }
    }

    internal suspend fun performSetSkipSilence(enabled: Boolean) {
        playerController.setSkipSilence(enabled)
        settingsRepository.update { it.copy(skipSilenceEnabled = enabled) }
    }

    internal suspend fun performSetSmartRewind(enabled: Boolean) {
        settingsRepository.update { it.copy(smartRewindEnabled = enabled) }
        playerController.updateSmartRewindEnabled(enabled)
    }

    internal suspend fun performSetFeedAutoDownload(
        feedId: String,
        enabled: Boolean,
    ) {
        repository.setFeedAutoDownload(feedId = feedId, enabled = enabled)
        val msg =
            if (enabled) {
                "Auto-downloading new episodes for this podcast"
            } else {
                "Disabled auto-download for this podcast"
            }
        showStatusMessage(msg)
    }

    internal suspend fun performSetFeedPlaybackSpeed(
        feedId: String,
        speed: Float?,
    ) {
        val clamped = speed?.let(::clampPlaybackSpeed)
        repository.setFeedPlaybackSpeed(feedId = feedId, speed = clamped)
        val currentEntryId = playerController.state.value.currentEntryId
        val activeEntry = currentEntryId?.let { id ->
            repository.snapshot.first().entries.firstOrNull { it.id == id }
        }
        if (activeEntry?.feedId == feedId && clamped != null) {
            playerController.setPlaybackSpeed(clamped)
        }
    }

    internal suspend fun performToggleQueueMembership(entryId: String) {
        val inQueue = entryId in settingsRepository.settings.first().queueItemIds
        if (inQueue) {
            performRemoveFromQueue(entryId, showBanner = true)
        } else {
            performAddToQueue(entryId, playNext = false)
        }
    }

    internal suspend fun performAddToQueue(
        entryId: String,
        playNext: Boolean = false,
    ) {
        val activeAudioId = playerController.state.value.currentEntryId
        settingsRepository.update { current ->
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
        syncActivePlayerQueueState()
        transientState.update { it.copy(contextSheetEntryId = null) }
        showStatusMessage(if (playNext) "Queued to play next" else "Added to Queue")
    }

    internal suspend fun performRemoveFromQueue(
        entryId: String,
        showBanner: Boolean = true,
    ) {
        settingsRepository.update { current ->
            current.copy(queueItemIds = current.queueItemIds.filterNot { it == entryId })
        }
        syncActivePlayerQueueState()
        transientState.update { it.copy(contextSheetEntryId = null) }
        if (showBanner) {
            showStatusMessage("Removed from Queue")
        }
    }

    internal suspend fun performMoveQueueItemUp(entryId: String) {
        settingsRepository.update { current ->
            val list = current.queueItemIds.toMutableList()
            val idx = list.indexOf(entryId)
            if (idx > 0) {
                val prev = list[idx - 1]
                list[idx - 1] = entryId
                list[idx] = prev
            }
            current.copy(queueItemIds = list)
        }
        syncActivePlayerQueueState()
    }

    internal suspend fun performMoveQueueItemToTop(entryId: String) {
        val activeAudioId = playerController.state.value.currentEntryId
        settingsRepository.update { current ->
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
        syncActivePlayerQueueState()
        showStatusMessage("Moved to top of Queue")
    }

    internal suspend fun performToggleArchive(entry: InboxEntry) {
        val targetArchived = !entry.isArchived
        val snapshot =
            repository.setArchiveStateWithSnapshot(itemId = entry.id, archived = targetArchived)
        if (targetArchived) {
            settingsRepository.update { current ->
                current.copy(queueItemIds = current.queueItemIds.filterNot { it == entry.id })
            }
            if (playerController.state.value.currentEntryId == entry.id) {
                playerController.stopAndClear()
            }
        }
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
            val archivedIds = snapshots.map { it.itemId }.toSet()
            settingsRepository.update { current ->
                current.copy(queueItemIds = current.queueItemIds.filterNot { it in archivedIds })
            }
            if (playerController.state.value.currentEntryId in archivedIds) {
                playerController.stopAndClear()
            }
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
                readerContent = null,
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
            val archivedIds = snapshots.map { it.itemId }.toSet()
            settingsRepository.update { current ->
                current.copy(queueItemIds = current.queueItemIds.filterNot { it in archivedIds })
            }
            if (playerController.state.value.currentEntryId in archivedIds) {
                playerController.stopAndClear()
            }
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

    internal suspend fun performToggleFeedArchive(feedId: String) {
        repository
            .toggleFeedArchive(feedId)
            .onSuccess { (title, isArchived) ->
                val verb = if (isArchived) "Archived feed" else "Unarchived feed"
                transientState.update { current ->
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
                showStatusMessage("Feed update failed (${error.message ?: "error"})")
            }
    }

    internal suspend fun performDeleteFeed(feedId: String) {
        val feedEntryIds =
            uiState.value.allEntries.filter { it.feedId == feedId }.map { it.id }.toSet()
        repository
            .deleteFeed(feedId)
            .onSuccess { title ->
                if (feedEntryIds.isNotEmpty()) {
                    settingsRepository.update { current ->
                        current.copy(
                            queueItemIds = current.queueItemIds.filterNot { it in feedEntryIds }
                        )
                    }
                    if (playerController.state.value.currentEntryId in feedEntryIds) {
                        playerController.stopAndClear()
                    }
                }
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
                settingsRepository.update { current ->
                    current.copy(queueItemIds = current.queueItemIds.filterNot { it == entryId })
                }
                if (playerController.state.value.currentEntryId == entryId) {
                    playerController.stopAndClear()
                }
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
                        readerContent = nextReaderId?.let { current.readerContent },
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
        val initialState = uiState.first { !it.isInitialLoading }
        val currentSettings = initialState.settings
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

    private fun applyReaderContentIfActive(entryId: String, content: ReaderItemContent) {
        val activePlayerId = playerController.state.value.currentEntryId
        transientState.update { current ->
            if (current.selectedReaderEntryId == entryId || activePlayerId == entryId) {
                current.copy(readerContent = content)
            } else {
                current
            }
        }
        if (activePlayerId == entryId) {
            val base = uiState.value.allEntries.firstOrNull { it.id == entryId }
            playerController.refreshChaptersFromNotes(
                entryId = entryId,
                pickLongerReaderText(content.contentHtml, base?.contentHtml),
                pickLongerReaderText(content.contentText, base?.contentText),
                base?.summary,
            )
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
        private val mediaSessionBridge: PodcastMediaSessionBridge = NoOpPodcastMediaSessionBridge,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(
                repository = repository,
                settingsRepository = settings,
                mediaSessionBridge = mediaSessionBridge,
            )
                as T
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
                readerContent = null,
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
                readerContent = null,
                contextSheetEntryId = null,
            )
        is MainAction.FilterByFeed ->
            state.copy(
                activeTab = AppTab.INBOX,
                selectedFeedId = action.feedId,
                selectedTag = null,
                scopeOriginTab = resolveNextScopeOriginTab(state, action.feedId != null),
                selectedReaderEntryId = null,
                readerContent = null,
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
                readerContent = null,
                contextSheetEntryId = null,
                feedContextSheetId = null,
            )
        is MainAction.UpdateSearchQuery -> state.copy(searchQuery = action.query)
        else -> reduceOverlayUiAction(state, action)
    }

internal fun pickLongerReaderText(first: String?, second: String?): String? {
    val a = first?.takeIf { it.isNotBlank() }
    val b = second?.takeIf { it.isNotBlank() }
    return when {
        a == null -> b
        b == null -> a
        a.length >= b.length -> a
        else -> b
    }
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
            state.copy(
                selectedReaderEntryId = action.entryId,
                readerContent = null,
                contextSheetEntryId = null,
            )
        MainAction.CloseReader -> state.copy(selectedReaderEntryId = null, readerContent = null)
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
    playerState: PodcastPlayerState = PodcastPlayerState(),
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
        readerContent = local.readerContent,
        contextSheetEntryId = local.contextSheetEntryId,
        feedContextSheetId = local.feedContextSheetId,
        isAddSheetOpen = local.isAddSheetOpen,
        prefilledShareUrl = local.prefilledShareUrl,
        prefilledShareTitle = local.prefilledShareTitle,
        isInitialLoading = false,
        isSyncing = local.isSyncing,
        visibleEntries = filtered,
        searchResults = searchResults,
        allEntries = snapshot.entries,
        feeds = snapshot.feeds,
        tags = snapshot.tags,
        activePeers = snapshot.activePeers,
        localNetworkTier = localNetworkTier,
        settings = settings,
        playerState = playerState,
        undoEvent = local.undoEvent,
        statusBanner = local.statusBanner,
        messageSequence = local.messageSequence,
    )
}

internal fun entryCountNoun(count: Int): String = if (count == 1) "entry" else "entries"
