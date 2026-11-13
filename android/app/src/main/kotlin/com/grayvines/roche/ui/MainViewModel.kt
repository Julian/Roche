package com.grayvines.roche.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.grayvines.roche.data.ReaderItemContent
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.SavedResourceKind
import com.grayvines.roche.data.normalizeTagName
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
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

@Suppress("TooManyFunctions", "LargeClass")
class MainViewModel(
    private val repository: RocheRepository,
    private val settingsRepository: SettingsRepository,
    playerBackend: AudioPlayerBackend = ResilientMediaPlayerBackend(),
    private val mediaSessionBridge: PodcastMediaSessionBridge = NoOpPodcastMediaSessionBridge,
    sharedPlayerController: PodcastPlayerController? = null,
) : ViewModel() {

    private val ownsPlayerController: Boolean = sharedPlayerController == null

    private val playbackOrchestrator =
        PodcastPlaybackOrchestrator(
            repository = repository,
            settingsRepository = settingsRepository,
        )

    private val transientState =
        MutableStateFlow(TransientUiState(localNetworkTier = repository.currentNetworkTier()))

    private var deepSearchJob: Job? = null
    private val inFlightStarCounts = mutableMapOf<String, Int>()

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
            buildMainUiState(
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
        return buildMainUiState(
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
        when (action) {
            is MainAction.OpenReader -> launchReaderOpenSideEffects(action.entryId)
            is MainAction.UpdateSearchQuery -> scheduleDeepSearch(action.query)
            MainAction.CloseSearch -> cancelDeepSearch()
            else -> Unit
        }
    }

    private fun launchReaderOpenSideEffects(entryId: String) {
        viewModelScope.launch {
            val hydrated =
                repository.hydrateItemContentIfNeeded(entryId) { localContent ->
                    applyReaderContentIfActive(entryId, localContent)
                }
            if (hydrated != null) {
                applyReaderContentIfActive(entryId, hydrated)
            }
        }
        viewModelScope.launch { repository.setReadState(entryId, true) }
    }

    private fun cancelDeepSearch() {
        deepSearchJob?.cancel()
        deepSearchJob = null
    }

    private fun handleMutationAction(action: MainAction.Mutation) {
        if (action is MainAction.ToggleStar) {
            val currentFav = resolveCurrentFavoriteState(action.entryId)
            if (currentFav != null) {
                val targetFav = !currentFav
                synchronized(inFlightStarCounts) {
                    inFlightStarCounts[action.entryId] =
                        (inFlightStarCounts[action.entryId] ?: 0) + 1
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
                    performToggleStar(
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

    private fun resolveCurrentFavoriteState(entryId: String): Boolean? =
        transientState.value.optimisticFavorites[entryId]
            ?: uiState.value.allEntries.firstOrNull { it.id == entryId }?.isFavorite
            ?: repository.latestSnapshot?.entries?.firstOrNull { it.id == entryId }?.isFavorite

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

    internal fun refreshLocalNetworkTier() {
        val tier = repository.currentNetworkTier()
        transientState.update { it.copy(localNetworkTier = tier) }
    }

    internal suspend fun performSyncWithServerConfig(action: MainAction.SyncWithServerConfig) {
        refreshLocalNetworkTier()
        performUpdateServerConfig(
            MainAction.UpdateServerConfig(
                serverUrl = action.serverUrl,
                bearerToken = action.bearerToken,
                feedback = ConfigSaveFeedback.SILENT,
            )
        )
        performSync(errorPolicy = SyncErrorPolicy.SHOW_BANNER)
        refreshLocalNetworkTier()
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
        refreshLocalNetworkTier()
        repository
            .pairWithCode(
                serverUrl = action.serverUrl,
                pairingCode = action.pairingCode,
                deviceName = action.deviceName,
            )
            .onSuccess {
                val syncCount = repository.syncWithServer().getOrNull()
                refreshLocalNetworkTier()
                val suffix = syncCount?.let { " · Synced $it entries" }.orEmpty()
                showStatusMessage("Paired with server$suffix")
            }
            .onFailure { error ->
                refreshLocalNetworkTier()
                showStatusMessage("Pairing failed (${error.message ?: "error"})")
            }
    }

    internal suspend fun performToggleStar(
        entryId: String,
        targetFavorite: Boolean? = null,
        bannerAlreadyShown: Boolean = false,
    ) {
        try {
            val starred = repository.toggleStar(itemId = entryId, targetFavorite = targetFavorite)
            if (!bannerAlreadyShown) {
                showStatusMessage(if (starred) "Starred" else "Removed star")
            }
        } finally {
            if (bannerAlreadyShown) {
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
                        repository.latestSnapshot
                            ?.entries
                            ?.firstOrNull { it.id == entryId }
                            ?.isFavorite
                    if (
                        persistedFav != null &&
                            transientState.value.optimisticFavorites[entryId] == persistedFav
                    ) {
                        transientState.update { current ->
                            current.copy(
                                optimisticFavorites = current.optimisticFavorites - entryId
                            )
                        }
                    }
                }
            }
        }
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
        val played =
            playbackOrchestrator.playAudioEntry(
                playerController = playerController,
                entryId = entryId,
                openFullscreen = openFullscreen,
            )
        if (played) {
            transientState.update { it.copy(contextSheetEntryId = null) }
        }
    }

    private suspend fun syncActivePlayerQueueState() {
        playbackOrchestrator.syncActivePlayerQueueState(playerController)
    }

    internal suspend fun handleAudioEpisodeCompleted(completedEntryId: String) {
        playbackOrchestrator.handleAudioEpisodeCompleted(playerController, completedEntryId)
    }

    internal suspend fun performSkipToNextQueuedAudio() {
        playbackOrchestrator.performSkipToNextQueuedAudio(playerController)
    }

    internal suspend fun performSetPlaybackSpeed(
        speed: Float,
        applyToCurrentPodcast: Boolean?,
    ) {
        playbackOrchestrator.performSetPlaybackSpeed(
            playerController = playerController,
            speed = speed,
            applyToCurrentPodcast = applyToCurrentPodcast,
        )
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
                isSearchOpen = false,
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
        val cleanTag = normalizeTagName(tagName)
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
        refreshLocalNetworkTier()
        uiState.first { !it.isInitialLoading }
        val currentSettings = settingsRepository.settings.first()
        if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
            performSync(errorPolicy = SyncErrorPolicy.SILENT)
        }
        refreshLocalNetworkTier()
    }

    internal suspend fun performPollFeedsOnDevice() {
        refreshLocalNetworkTier()
        if (!tryAcquireSyncLock()) return
        try {
            val pollResult = repository.pollDueFeedsOnDeviceNow()
            refreshLocalNetworkTier()
            pollResult
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
            refreshLocalNetworkTier()
            transientState.update { it.copy(isSyncing = false) }
        }
    }

    private suspend fun performSync(errorPolicy: SyncErrorPolicy = SyncErrorPolicy.SHOW_BANNER) {
        refreshLocalNetworkTier()
        if (!tryAcquireSyncLock()) return
        try {
            val syncResult = repository.syncWithServer()
            refreshLocalNetworkTier()
            syncResult
                .onSuccess { count ->
                    showStatusMessage("Synced $count entries from server")
                }
                .onFailure { error ->
                    if (errorPolicy == SyncErrorPolicy.SHOW_BANNER) {
                        showStatusMessage("Sync offline (${error.message ?: "unreachable"})")
                    }
                }
        } finally {
            refreshLocalNetworkTier()
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
