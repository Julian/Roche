package com.grayvines.roche.ui

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.grayvines.roche.data.ReaderItemContent
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.playback.AudioPlayerBackend
import com.grayvines.roche.playback.NoOpAudioPlayerBackend
import com.grayvines.roche.playback.NoOpPodcastMediaSessionBridge
import com.grayvines.roche.playback.PodcastMediaSessionBridge
import com.grayvines.roche.playback.PodcastPlaybackOrchestrator
import com.grayvines.roche.playback.PodcastPlayerController
import com.grayvines.roche.playback.PodcastPlayerState
import com.grayvines.roche.playback.createManagedPlayerController
import com.grayvines.roche.playback.hasNextQueuedAudio
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Suppress("TooManyFunctions", "LongParameterList")
class MainViewModel(
    internal val repository: RocheRepository,
    internal val settingsRepository: SettingsRepository,
    playerBackend: AudioPlayerBackend = NoOpAudioPlayerBackend(),
    private val mediaSessionBridge: PodcastMediaSessionBridge = NoOpPodcastMediaSessionBridge,
    sharedPlayerController: PodcastPlayerController? = null,
    private val savedStateHandle: SavedStateHandle? = null,
    sharedPlaybackOrchestrator: PodcastPlaybackOrchestrator? = null,
) : ViewModel() {

    private val ownsPlayerController: Boolean = sharedPlayerController == null
    private val restoredNavState: SavedNavigationUiState? =
        savedStateHandle?.let(::restoreSavedNavigationState)

    internal val playbackOrchestrator: PodcastPlaybackOrchestrator =
        sharedPlaybackOrchestrator
            ?: PodcastPlaybackOrchestrator(
                repository = repository,
                settingsRepository = settingsRepository,
            )

    internal val transientState =
        MutableStateFlow(
            restoredNavState?.toInitialTransientUiState(repository.currentNetworkTier())
                ?: TransientUiState(localNetworkTier = repository.currentNetworkTier())
        )

    private var deepSearchJob: Job? = null
    private val uiStateMemoizer = MainUiStateMemoizer()

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
                orchestrator = playbackOrchestrator,
                playerBackend = playerBackend,
                mediaSessionBridge = mediaSessionBridge,
            )

    val playerState: StateFlow<PodcastPlayerState> = playerController.state

    private val sharedSnapshotFlow =
        repository.snapshot.shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1,
        )

    private val baseUiStateFlow =
        combine(
            sharedSnapshotFlow,
            settingsForUi,
            transientState,
            repository.isSyncActive,
        ) {
            snapshot: RepositorySnapshot,
            settings: Settings,
            local: TransientUiState,
            repoSyncing: Boolean ->
            val effectiveLocal =
                if (repoSyncing && !local.isSyncing) local.copy(isSyncing = true) else local
            uiStateMemoizer.build(
                snapshot = snapshot,
                settings = settings,
                local = effectiveLocal,
                localNetworkTier = effectiveLocal.localNetworkTier,
            )
        }

    val uiState: StateFlow<MainUiState> =
        baseUiStateFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = buildInitialUiState(),
        )

    private val delegateHost = MainViewModelHost(this)
    private val inboxReaderDelegate = MainInboxReaderDelegate(delegateHost)
    private val syncFeedDelegate = MainSyncFeedDelegate(delegateHost)
    private val catalogActionsDelegate = MainCatalogActionsDelegate(delegateHost)

    init {
        savedStateHandle?.setSavedStateProvider(SAVED_NAV_STATE_KEY) {
            encodeToSavedState(
                SavedNavigationUiState.serializer(),
                transientState.value.toSavedNavigationUiState(),
            )
        }
        playerController.setUiErrorListener(uiErrorListener)
        settingsRepository.latestSettings?.let { cached ->
            playerController.updateSmartRewindEnabled(cached.smartRewindEnabled)
            playerController.syncSkipSilenceFromSettings(cached.skipSilenceEnabled)
        }
        observePlayerControllerSync()
        observeActiveEpisodeNotes()
        observeActiveCollectionDraws()
        observeSettledStars()
        restoredNavState?.let(::restoreNavigationSideEffects)
    }

    private fun observePlayerControllerSync() {
        viewModelScope.launch {
            combine(
                    sharedSnapshotFlow.map { it.entries }.distinctUntilChanged(),
                    settingsForUi
                        .map { settings ->
                            PlayerSettingsSlice(
                                queueItemIds = settings.queueItemIds,
                                smartRewindEnabled = settings.smartRewindEnabled,
                                skipSilenceEnabled = settings.skipSilenceEnabled,
                            )
                        }
                        .distinctUntilChanged(),
                    playerController.state.map { it.currentEntryId }.distinctUntilChanged(),
                ) { entries, slice, _ ->
                    entries to slice
                }
                .collect { (entries, slice) ->
                    val latestEntries = repository.latestSnapshot?.entries ?: entries
                    val latestSettings = settingsRepository.latestSettings
                    val effectiveQueueIds = latestSettings?.queueItemIds ?: slice.queueItemIds
                    val target =
                        PlayerControllerSyncTarget(
                            hasNextQueuedAudio =
                                hasNextQueuedAudio(
                                    latestEntries,
                                    effectiveQueueIds,
                                    playerController.state.value.currentEntryId,
                                ),
                            smartRewindEnabled =
                                latestSettings?.smartRewindEnabled ?: slice.smartRewindEnabled,
                            skipSilenceEnabled =
                                latestSettings?.skipSilenceEnabled ?: slice.skipSilenceEnabled,
                        )
                    playerController.updateHasNextQueuedAudio(target.hasNextQueuedAudio)
                    playerController.updateSmartRewindEnabled(target.smartRewindEnabled)
                    playerController.syncSkipSilenceFromSettings(target.skipSilenceEnabled)
                }
        }
    }

    private fun observeSettledStars() {
        viewModelScope.launch {
            sharedSnapshotFlow
                .map { snap -> snap.entries.associate { it.id to it.isFavorite } }
                .distinctUntilChanged()
                .collect { persistedById ->
                    inboxReaderDelegate.reconcileOptimisticStars(persistedById)
                }
        }
    }

    private fun observeActiveCollectionDraws() {
        viewModelScope.launch {
            sharedSnapshotFlow
                .map { snap ->
                    snap.catalog.generators
                        .filter { it.enabled }
                        .mapNotNull { it.currentDraw }
                        .filter { it.kind == EntryKind.ALBUM }
                }
                .distinctUntilChanged()
                .collect { draws ->
                    draws.forEach { draw -> repository.prewarmCollectionDraw(draw) }
                }
        }
    }

    private fun buildInitialUiState(): MainUiState {
        val local = transientState.value
        val cachedSettings = settingsRepository.latestSettings
        val cachedSnapshot =
            repository.latestSnapshot
                ?: return MainUiState(
                    activeTab = local.activeTab,
                    activeFilter = local.activeFilter,
                    isSearchOpen = local.isSearchOpen,
                    searchQuery = local.searchQuery,
                    selectedTag = local.selectedTag,
                    selectedFeedId = local.selectedFeedId,
                    scopeOriginTab = local.scopeOriginTab,
                    selectedReaderEntryId = local.selectedReaderEntryId,
                    hydratingEntryIds = local.hydratingEntryIds,
                    companionSubTab = local.companionSubTab,
                    selectedCatalogCollectionId = local.selectedCatalogCollectionId,
                    selectedWantCollectionId = local.selectedWantCollectionId,
                    isPlayerFullscreenOpen = local.isPlayerFullscreenOpen,
                    isPlayerSpeedSheetOpen = local.isPlayerSpeedSheetOpen,
                    activePlayerDrawerTab = local.activePlayerDrawerTab,
                    localNetworkTier = local.localNetworkTier,
                    settings = cachedSettings ?: Settings(),
                    isInitialLoading = true,
                )
        return uiStateMemoizer.build(
            snapshot = cachedSnapshot,
            settings = cachedSettings ?: Settings(),
            local = local,
            localNetworkTier = local.localNetworkTier,
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
                .collectLatest(::refreshActiveEpisodeNotes)
        }
    }

    private suspend fun refreshActiveEpisodeNotes(entryId: String?) {
        if (entryId == null) {
            transientState.update { current ->
                val hasActivePlayerUi =
                    current.activePlayerNotesContent != null ||
                        current.isPlayerFullscreenOpen ||
                        current.isPlayerSpeedSheetOpen ||
                        current.activePlayerDrawerTab != null
                if (hasActivePlayerUi) {
                    current.copy(
                        activePlayerNotesContent = null,
                        isPlayerFullscreenOpen = false,
                        isPlayerSpeedSheetOpen = false,
                        activePlayerDrawerTab = null,
                    )
                } else {
                    current
                }
            }
            return
        }
        transientState.update { current ->
            val kept = current.activePlayerNotesContent?.takeIf { it.itemId == entryId }
            if (kept !== current.activePlayerNotesContent) {
                current.copy(activePlayerNotesContent = kept)
            } else {
                current
            }
        }
        val hydrated =
            repository.hydrateItemContentIfNeeded(entryId) { localContent ->
                applyReaderContentIfActive(entryId, localContent)
            }
        if (hydrated != null) {
            applyReaderContentIfActive(entryId, hydrated)
        }
    }

    fun dispatch(action: MainAction) {
        when (action) {
            is MainAction.Ui -> handleUiAction(action)
            is MainAction.Mutation -> handleMutationAction(action)
        }
    }

    private fun handleUiAction(action: MainAction.Ui) {
        if (
            action is MainAction.OpenFullscreenPlayer &&
                !playerController.state.value.hasActiveEpisode
        ) {
            return
        }
        if (action is MainAction.OpenReader && !action.forceArticleView) {
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
        handleUiSideEffects(action)
    }

    private fun handleUiSideEffects(action: MainAction.Ui) {
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
        hydrateReaderEntryContent(entryId)
        viewModelScope.launch { repository.setReadState(entryId, true) }
    }

    private fun hydrateReaderEntryContent(entryId: String) {
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
    }

    private fun restoreNavigationSideEffects(restored: SavedNavigationUiState) {
        restored.selectedReaderEntryId?.let(::hydrateReaderEntryContent)
        if (restored.isSearchOpen && restored.searchQuery.isNotBlank()) {
            scheduleDeepSearch(restored.searchQuery)
        }
        if (restored.selectedReaderEntryId != null || restored.selectedFeedId != null) {
            viewModelScope.launch { reconcileRestoredSelection(restored) }
        }
    }

    private suspend fun reconcileRestoredSelection(restored: SavedNavigationUiState) {
        val snapshot = sharedSnapshotFlow.first()
        val missingReaderId =
            restored.selectedReaderEntryId?.takeIf { targetId ->
                snapshot.entries.none { it.id == targetId }
            }
        val missingFeedId =
            restored.selectedFeedId?.takeIf { targetFeedId ->
                snapshot.feeds.none { it.id == targetFeedId } &&
                    snapshot.entries.none { it.feedId == targetFeedId }
            }
        if (missingReaderId != null || missingFeedId != null) {
            transientState.update { current ->
                pruneMissingRestoredState(current, missingReaderId, missingFeedId)
            }
        }
    }

    internal fun exportSavedNavigationJson(): String =
        Json.encodeToString(
            SavedNavigationUiState.serializer(),
            transientState.value.toSavedNavigationUiState(),
        )

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
        when (action) {
            is MainAction.InboxReaderMutation -> inboxReaderDelegate.execute(action)
            is MainAction.SyncFeedMutation -> syncFeedDelegate.execute(action)
            is MainAction.CatalogMutation -> catalogActionsDelegate.execute(action)
        }
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
            delay(DEEP_SEARCH_DEBOUNCE_MS)
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
        val clearingActivePlayer = playerController.state.value.currentEntryId in removedIds
        if (clearingActivePlayer) {
            playerController.stopAndClear()
        }
        clearRemovedTransientSelection(removedIds, clearingActivePlayer)
    }

    private fun clearRemovedTransientSelection(
        removedIds: Set<String>,
        clearingActivePlayer: Boolean,
    ) {
        transientState.update { current ->
            val nextReaderId = current.selectedReaderEntryId?.takeUnless { it in removedIds }
            val nextContent = nextReaderId?.let { current.readerContent }
            val nextPlayerNotes =
                current.activePlayerNotesContent?.takeUnless { it.itemId in removedIds }
            val nextSheetId = current.contextSheetEntryId?.takeUnless { it in removedIds }
            current.copy(
                selectedReaderEntryId = nextReaderId,
                readerContent = nextContent,
                activePlayerNotesContent = nextPlayerNotes,
                contextSheetEntryId = nextSheetId,
                isPlayerFullscreenOpen =
                    if (clearingActivePlayer) false else current.isPlayerFullscreenOpen,
                isPlayerSpeedSheetOpen =
                    if (clearingActivePlayer) false else current.isPlayerSpeedSheetOpen,
                activePlayerDrawerTab =
                    if (clearingActivePlayer) null else current.activePlayerDrawerTab,
            )
        }
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
            val nextReaderContent =
                if (current.selectedReaderEntryId == entryId) content else current.readerContent
            val nextPlayerNotes =
                if (activePlayerId == entryId) content else current.activePlayerNotesContent
            if (
                nextReaderContent !== current.readerContent ||
                    nextPlayerNotes !== current.activePlayerNotesContent
            ) {
                current.copy(
                    readerContent = nextReaderContent,
                    activePlayerNotesContent = nextPlayerNotes,
                )
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
        private val sharedPlaybackOrchestrator: PodcastPlaybackOrchestrator? = null,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            create(modelClass, CreationExtras.Empty)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            val savedStateHandle = runCatching { extras.createSavedStateHandle() }.getOrNull()
            return MainViewModel(
                repository = repository,
                settingsRepository = settings,
                mediaSessionBridge = mediaSessionBridge,
                sharedPlayerController = sharedPlayerController,
                savedStateHandle = savedStateHandle,
                sharedPlaybackOrchestrator = sharedPlaybackOrchestrator,
            )
                as T
        }
    }

    internal companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val DEEP_SEARCH_DEBOUNCE_MS = 200L
        internal const val SAVED_NAV_STATE_KEY = "roche_saved_navigation_state"
        internal const val SAVED_NAV_JSON_KEY = "roche_saved_navigation_json"
    }
}

internal fun restoreSavedNavigationState(
    savedStateHandle: SavedStateHandle
): SavedNavigationUiState? {
    val savedBundle = savedStateHandle.remove<Bundle>(MainViewModel.SAVED_NAV_STATE_KEY)
    if (savedBundle != null) {
        runCatching { decodeFromSavedState(SavedNavigationUiState.serializer(), savedBundle) }
            .getOrNull()
            ?.let {
                return it
            }
    }
    val savedJson = savedStateHandle.remove<String>(MainViewModel.SAVED_NAV_JSON_KEY)
    return savedJson?.let { raw ->
        runCatching { Json.decodeFromString(SavedNavigationUiState.serializer(), raw) }.getOrNull()
    }
}

internal fun pruneMissingRestoredState(
    current: TransientUiState,
    missingReaderId: String?,
    missingFeedId: String?,
): TransientUiState {
    val clearReader = missingReaderId != null && current.selectedReaderEntryId == missingReaderId
    val clearFeed = missingFeedId != null && current.selectedFeedId == missingFeedId
    val nextOriginTab =
        if (clearFeed && current.selectedTag == null) null else current.scopeOriginTab
    return current.copy(
        selectedReaderEntryId = if (clearReader) null else current.selectedReaderEntryId,
        readerContent = if (clearReader) null else current.readerContent,
        selectedFeedId = if (clearFeed) null else current.selectedFeedId,
        scopeOriginTab = nextOriginTab,
    )
}

private class MainViewModelHost(private val vm: MainViewModel) : InboxReaderHost, SyncFeedHost {
    override val repository: RocheRepository
        get() = vm.repository

    override val settingsRepository: SettingsRepository
        get() = vm.settingsRepository

    override val playerController: PodcastPlayerController
        get() = vm.playerController

    override val playbackOrchestrator: PodcastPlaybackOrchestrator
        get() = vm.playbackOrchestrator

    override fun currentTransientState(): TransientUiState = vm.transientState.value

    override fun updateTransient(transform: (TransientUiState) -> TransientUiState) {
        vm.transientState.update(transform)
    }

    override fun showStatusMessage(message: String) {
        vm.showStatusMessage(message)
    }

    override suspend fun latestEntries(): List<InboxEntry> = vm.latestEntries()

    override suspend fun cleanupRemovedEntries(removedIds: Set<String>) {
        vm.cleanupRemovedEntries(removedIds)
    }

    override fun cancelDeepSearch() {
        vm.cancelDeepSearch()
    }

    override fun applyReaderContentIfActive(entryId: String, content: ReaderItemContent) {
        vm.applyReaderContentIfActive(entryId, content)
    }

    override fun tryAcquireSyncLock(): Boolean = vm.tryAcquireSyncLock()

    override fun refreshLocalNetworkTier() {
        vm.refreshLocalNetworkTier()
    }

    override suspend fun awaitInitialLoad() {
        vm.uiState.first { !it.isInitialLoading }
    }
}

private data class PlayerSettingsSlice(
    val queueItemIds: List<String>,
    val smartRewindEnabled: Boolean,
    val skipSilenceEnabled: Boolean,
)

private data class PlayerControllerSyncTarget(
    val hasNextQueuedAudio: Boolean,
    val smartRewindEnabled: Boolean,
    val skipSilenceEnabled: Boolean,
)
