package com.grayvines.roche.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.grayvines.roche.data.ReaderItemContent
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class MainViewModel(
    internal val repository: RocheRepository,
    internal val settingsRepository: SettingsRepository,
    playerBackend: AudioPlayerBackend = NoOpAudioPlayerBackend(),
    private val mediaSessionBridge: PodcastMediaSessionBridge = NoOpPodcastMediaSessionBridge,
    sharedPlayerController: PodcastPlayerController? = null,
) : ViewModel() {

    private val ownsPlayerController: Boolean = sharedPlayerController == null

    internal val playbackOrchestrator =
        PodcastPlaybackOrchestrator(
            repository = repository,
            settingsRepository = settingsRepository,
        )

    internal val transientState =
        MutableStateFlow(TransientUiState(localNetworkTier = repository.currentNetworkTier()))

    private var deepSearchJob: Job? = null
    private val uiStateMemoizer = MainUiStateMemoizer()

    private val inboxReaderDelegate = MainInboxReaderDelegate(this)
    private val syncFeedDelegate = MainSyncFeedDelegate(this)
    private val catalogActionsDelegate = MainCatalogActionsDelegate(this)

    private val settingsForUi =
        settingsRepository.settings
            .onStart { emit(settingsRepository.latestSettings ?: Settings()) }
            .distinctUntilChanged()

    private val uiErrorListener: (String) -> Unit = { message -> showStatusMessage(message) }

    internal val playerController: PodcastPlayerController =
        sharedPlayerController
            ?: createManagedPlayerController(
                scope = viewModelScope,
                repository = repository,
                settingsRepository = settingsRepository,
                playerBackend = playerBackend,
                mediaSessionBridge = mediaSessionBridge,
            )

    private val baseUiStateFlow =
        combine(
            repository.snapshot,
            settingsForUi,
            transientState,
        ) { snapshot: RepositorySnapshot, settings: Settings, local: TransientUiState ->
            uiStateMemoizer.build(
                snapshot = snapshot,
                settings = settings,
                local = local,
                localNetworkTier = local.localNetworkTier,
            )
        }

    val uiState: StateFlow<MainUiState> =
        mergeBaseWithPlayerState(
                baseFlow = baseUiStateFlow,
                playerFlow = playerController.state,
            ) { built ->
                playerController.updateHasNextQueuedAudio(built.nextQueuedAudioEntryId != null)
                playerController.updateSmartRewindEnabled(built.settings.smartRewindEnabled)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = buildInitialUiState(),
            )

    init {
        playerController.setUiErrorListener(uiErrorListener)
        observeActiveEpisodeNotes()
    }

    private fun buildInitialUiState(): MainUiState {
        val cachedSnapshot =
            repository.latestSnapshot
                ?: return MainUiState(
                    isInitialLoading = true,
                    playerState = playerController.state.value,
                )
        val cachedSettings = settingsRepository.latestSettings ?: Settings()
        return uiStateMemoizer.build(
            snapshot = cachedSnapshot,
            settings = cachedSettings,
            local = transientState.value,
            localNetworkTier = transientState.value.localNetworkTier,
            playerState =
                playerController.state.value.copy(
                    smartRewindEnabled = cachedSettings.smartRewindEnabled
                ),
        )
    }

    internal suspend fun awaitBackgroundUiWorkForTest() {
        deepSearchJob?.join()
    }

    override fun onCleared() {
        deepSearchJob?.cancel()
        playerController.clearUiErrorListener(uiErrorListener)
        if (ownsPlayerController) {
            playerController.releaseController()
        }
    }

    private fun observeActiveEpisodeNotes() {
        viewModelScope.launch {
            playerController.state
                .map { it.currentEntryId }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { entryId ->
                    val hydrated =
                        repository.hydrateItemContentIfNeeded(entryId) { localContent ->
                            applyReaderContentIfActive(entryId, localContent)
                        }
                    if (hydrated != null) {
                        applyReaderContentIfActive(entryId, hydrated)
                    }
                }
        }
    }

    fun dispatch(action: MainAction) {
        when (action) {
            is MainAction.Ui -> handleUiAction(action)
            is MainAction.Mutation -> handleMutationAction(action)
        }
    }

    private fun handleUiAction(action: MainAction.Ui) {
        if (handlePlayerUiAction(action)) return
        if (action is MainAction.OpenReader) {
            val existing = peekLatestEntries().firstOrNull { it.id == action.entryId }
            if (existing?.contentType == ContentType.AUDIO) {
                viewModelScope.launch {
                    inboxReaderDelegate.performPlayAudioEntry(
                        entryId = action.entryId,
                        openFullscreen = true,
                    )
                }
                return
            }
        }
        transientState.update { reduceUiAction(it, action) }
        when (action) {
            is MainAction.OpenReader -> launchReaderOpenSideEffects(action.entryId)
            is MainAction.UpdateSearchQuery -> scheduleDeepSearch(action.query)
            MainAction.CloseSearch,
            is MainAction.SelectTab,
            is MainAction.FilterByTag,
            is MainAction.FilterByFeed,
            is MainAction.OpenCompanionSection -> cancelDeepSearch()
            else -> Unit
        }
    }

    private fun launchReaderOpenSideEffects(entryId: String) {
        viewModelScope.launch {
            try {
                val hydrated =
                    repository.hydrateItemContentIfNeeded(entryId) { localContent ->
                        applyReaderContentIfActive(entryId, localContent)
                    }
                if (hydrated != null) {
                    applyReaderContentIfActive(entryId, hydrated)
                }
            } finally {
                transientState.update { current ->
                    if (entryId in current.hydratingEntryIds) {
                        current.copy(hydratingEntryIds = current.hydratingEntryIds - entryId)
                    } else {
                        current
                    }
                }
            }
        }
        viewModelScope.launch { repository.setReadState(entryId, true) }
    }

    internal fun cancelDeepSearch() {
        deepSearchJob?.cancel()
        deepSearchJob = null
    }

    private fun handleMutationAction(action: MainAction.Mutation) {
        if (action is MainAction.ToggleStar) {
            val currentFav = resolveCurrentFavoriteState(action.entryId)
            if (currentFav != null) {
                val targetFav = !currentFav
                val inFlight = inboxReaderDelegate.inFlightStarCounts
                synchronized(inFlight) {
                    inFlight[action.entryId] = (inFlight[action.entryId] ?: 0) + 1
                }
                transientState.update { current ->
                    current.copy(
                        optimisticFavorites =
                            current.optimisticFavorites + (action.entryId to targetFav),
                        undoEvent = null,
                        statusBanner = if (targetFav) "Starred" else "Removed star",
                        messageSequence = current.messageSequence + 1,
                    )
                }
                viewModelScope.launch {
                    inboxReaderDelegate.performToggleStar(
                        entryId = action.entryId,
                        targetFavorite = targetFav,
                        bannerAlreadyShown = true,
                    )
                }
                return
            }
        }
        viewModelScope.launch { executeMutation(action) }
    }

    private suspend fun executeMutation(action: MainAction.Mutation) {
        if (inboxReaderDelegate.tryExecute(action)) return
        if (syncFeedDelegate.tryExecute(action)) return
        catalogActionsDelegate.execute(action)
    }

    private fun resolveCurrentFavoriteState(entryId: String): Boolean? =
        transientState.value.optimisticFavorites[entryId]
            ?: peekLatestEntries().firstOrNull { it.id == entryId }?.isFavorite

    private fun scheduleDeepSearch(rawQuery: String) {
        deepSearchJob?.cancel()
        val normalized = rawQuery.trim().lowercase()
        if (normalized.isBlank()) {
            deepSearchJob = null
            return
        }
        deepSearchJob = viewModelScope.launch {
            val deepMatches = repository.searchDeepEntries(rawQuery)
            transientState.update { current ->
                if (current.searchQuery.trim().lowercase() == normalized) {
                    current.copy(
                        deepSearchQuery = normalized,
                        deepSearchResults = deepMatches,
                    )
                } else {
                    current
                }
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

    internal suspend fun latestEntries(): List<InboxEntry> =
        repository.latestSnapshot?.entries ?: repository.snapshot.first().entries

    internal fun peekLatestEntries(): List<InboxEntry> =
        repository.latestSnapshot?.entries ?: uiState.value.allEntries

    internal suspend fun cleanupRemovedEntries(removedIds: Set<String>) {
        if (removedIds.isEmpty()) return
        val currentQueue =
            (settingsRepository.latestSettings ?: settingsRepository.settings.first()).queueItemIds
        if (currentQueue.any { it in removedIds }) {
            settingsRepository.update { current ->
                current.copy(queueItemIds = current.queueItemIds.filterNot { it in removedIds })
            }
            playbackOrchestrator.syncActivePlayerQueueState(playerController)
        }
        if (playerController.state.value.currentEntryId in removedIds) {
            playerController.stopAndClear()
        }
        clearRemovedTransientSelection(removedIds)
    }

    private fun clearRemovedTransientSelection(removedIds: Set<String>) {
        transientState.update { current ->
            val nextReaderId = current.selectedReaderEntryId?.takeUnless { it in removedIds }
            val nextContent = nextReaderId?.let { current.readerContent }
            val nextSheetId = current.contextSheetEntryId?.takeUnless { it in removedIds }
            current.copy(
                selectedReaderEntryId = nextReaderId,
                readerContent = nextContent,
                contextSheetEntryId = nextSheetId,
            )
        }
    }

    internal suspend fun handleAudioEpisodeCompleted(completedEntryId: String) {
        playbackOrchestrator.handleAudioEpisodeCompleted(playerController, completedEntryId)
    }

    internal suspend fun exportOpmlXml(): Result<String> = repository.exportOpml()

    internal suspend fun performImportOpml(opmlXml: String) {
        syncFeedDelegate.performImportOpml(opmlXml)
    }

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

    internal fun refreshLocalNetworkTier() {
        val tier = repository.currentNetworkTier()
        transientState.update { it.copy(localNetworkTier = tier) }
    }

    internal fun applyReaderContentIfActive(entryId: String, content: ReaderItemContent) {
        val activePlayerId = playerController.state.value.currentEntryId
        transientState.update { current ->
            if (current.selectedReaderEntryId == entryId || activePlayerId == entryId) {
                current.copy(readerContent = content)
            } else {
                current
            }
        }
        if (activePlayerId == entryId) {
            val base = peekLatestEntries().firstOrNull { it.id == entryId }
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
        private val sharedPlayerController: PodcastPlayerController? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(
                repository = repository,
                settingsRepository = settings,
                mediaSessionBridge = mediaSessionBridge,
                sharedPlayerController = sharedPlayerController,
            )
                as T
    }

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
