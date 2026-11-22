@file:Suppress("TooManyFunctions", "LongMethod", "CognitiveComplexMethod")

package com.grayvines.roche.ui

import android.os.Bundle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.MusicTrackLaunchRequest
import com.grayvines.roche.data.catalog.generatorFor
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.playback.PodcastPlayerState
import com.grayvines.roche.ui.components.PlusIcon
import com.grayvines.roche.ui.components.RocheBottomBar
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.UndoOrStatusSnackbar
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

private val IdlePlayerStateFlow: StateFlow<PodcastPlayerState> =
    MutableStateFlow(PodcastPlayerState())

private fun observeActiveAudioEntryId(
    playerStateFlow: StateFlow<PodcastPlayerState>
): Flow<String?> = playerStateFlow.map { it.currentEntryId }.distinctUntilChanged()

@Composable
fun MainScreen(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    playerStateFlow: StateFlow<PodcastPlayerState> = IdlePlayerStateFlow,
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit = { draw ->
        onAction(MainAction.LaunchCollectionDraw(draw))
    },
    onPlayMusicTrack: (MusicTrackLaunchRequest) -> Unit = {},
    onRevealSavedBearerToken: suspend () -> String = { "" },
) {
    val colors = LocalRocheColors.current
    val scrollStates = rememberMainScrollStates()
    val coroutineScope = rememberCoroutineScope()
    val activeAudioIdFlow = remember(playerStateFlow) { observeActiveAudioEntryId(playerStateFlow) }
    val activeAudioEntryId by activeAudioIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val activePlayerEntry = state.resolveActivePlayerEntry(activeAudioEntryId)
    val activeReaderEntry = state.activeReaderEntry
    val currentState by rememberUpdatedState(state)
    val currentActiveAudioEntryId by rememberUpdatedState(activeAudioEntryId)
    val currentPlayerEntry by rememberUpdatedState(activePlayerEntry)
    val currentPlayerStateFlow by rememberUpdatedState(playerStateFlow)
    val currentReaderEntry by rememberUpdatedState(activeReaderEntry)
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnShareEntry by rememberUpdatedState(onShareEntry)
    val currentOnOpenExternalUrl by rememberUpdatedState(onOpenExternalUrl)
    val currentOnImportOpml by rememberUpdatedState(onImportOpml)
    val currentOnExportOpml by rememberUpdatedState(onExportOpml)
    val currentOnOpenCollectionDraw by rememberUpdatedState(onOpenCollectionDraw)
    val currentOnRevealSavedBearerToken by rememberUpdatedState(onRevealSavedBearerToken)
    val activeFullscreenPlayerEntryId =
        activePlayerEntry?.id?.takeIf { state.isPlayerFullscreenOpen }
    val desiredBackStack =
        remember(state, activeReaderEntry?.id, activeFullscreenPlayerEntryId) {
            deriveRocheBackStack(
                state = state,
                activeAudioEntryId = activeAudioEntryId,
                activeFullscreenPlayerEntryId = activeFullscreenPlayerEntryId,
            )
        }
    val navBackStack = rememberNavBackStack(*desiredBackStack.toTypedArray())
    LaunchedEffect(desiredBackStack) { syncRocheNavBackStack(navBackStack, desiredBackStack) }
    val topKey = desiredBackStack.last()
    val chromeFlags = computeChromeFlags(topKey)
    val activeTabForBar = resolveBarTab(topKey, state)
    var chromeBottomPx by remember { mutableIntStateOf(0) }
    val snackbarBottomOffset =
        if (chromeFlags.showChrome) {
            with(LocalDensity.current) { chromeBottomPx.toDp() }
        } else {
            0.dp
        }

    Box(
        modifier =
            modifier.fillMaxSize().background(colors.background).semantics {
                testTagsAsResourceId = true
            }
    ) {
        MainDestinationScaffold(
            state = state,
            activePlayerEntry = activePlayerEntry,
            playerStateFlow = playerStateFlow,
            showBottomBar = chromeFlags.showChrome,
            hasFab = chromeFlags.hasFab,
            showSearchFab = chromeFlags.showSearchFab,
            showAddFab = chromeFlags.showAddFab,
            activeTabForBar = activeTabForBar,
            onChromeBottomHeightChange = { chromeBottomPx = it },
            onSelectTab = { tappedTab ->
                handleBottomBarTabSelect(
                    tappedTab = tappedTab,
                    activeTab = activeTabForBar,
                    topKey = topKey,
                    state = currentState,
                    scrollStates = scrollStates,
                    onAction = currentOnAction,
                    launchScroll = { block -> coroutineScope.launch { block() } },
                )
            },
            onAction = onAction,
        ) {
            NavDisplay(
                backStack = navBackStack,
                modifier = Modifier.fillMaxSize(),
                onBack = {
                    (navBackStack.lastOrNull() as? RocheNavKey)
                        ?.let(::popActionForTopKey)
                        ?.let(currentOnAction)
                },
                entryProvider =
                    entryProvider {
                        entry<RocheNavKey.Tab> { tabKey ->
                            MainTabDestinationContent(
                                tab = tabKey.tab,
                                state = currentState,
                                activeAudioEntryId = currentActiveAudioEntryId,
                                scrollStates = scrollStates,
                                onAction = currentOnAction,
                                onOpenExternalUrl = currentOnOpenExternalUrl,
                                onOpenCollectionDraw = currentOnOpenCollectionDraw,
                                onImportOpml = currentOnImportOpml,
                                onExportOpml = currentOnExportOpml,
                                onRevealSavedBearerToken = currentOnRevealSavedBearerToken,
                            )
                        }

                        entry<RocheNavKey.ScopedInbox> { scopedKey ->
                            InboxTabContent(
                                state = currentState,
                                onAction = currentOnAction,
                                listState =
                                    scrollStates.lazyListState(scopedInboxScrollKey(scopedKey)),
                                activeAudioEntryId = currentActiveAudioEntryId,
                                entries =
                                    currentState.entriesForScope(scopedKey.feedId, scopedKey.tag),
                                selectedFeedId = scopedKey.feedId,
                                selectedTag = scopedKey.tag,
                            )
                        }

                        entry<RocheNavKey.Search> {
                            MainSearchDestination(
                                state = currentState,
                                activeAudioEntryId = currentActiveAudioEntryId,
                                scrollStates = scrollStates,
                                onAction = currentOnAction,
                            )
                        }

                        entry<RocheNavKey.Reader> { readerKey ->
                            MainReaderDestination(
                                readerKey = readerKey,
                                state = currentState,
                                activeReaderEntry = currentReaderEntry,
                                onAction = currentOnAction,
                                onShareEntry = currentOnShareEntry,
                                onOpenExternalUrl = currentOnOpenExternalUrl,
                            )
                        }

                        entry<RocheNavKey.FullscreenPlayer> { playerKey ->
                            MainPlayerDestination(
                                playerKey = playerKey,
                                state = currentState,
                                activePlayerEntry = currentPlayerEntry,
                                playerStateFlow = currentPlayerStateFlow,
                                onAction = currentOnAction,
                                onShareEntry = currentOnShareEntry,
                                onOpenExternalUrl = currentOnOpenExternalUrl,
                            )
                        }
                    },
            )
        }

        MainSnackbarOverlay(
            state = state,
            chromeFlags = chromeFlags,
            onAction = onAction,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .then(
                        if (!chromeFlags.showChrome) Modifier.navigationBarsPadding() else Modifier
                    )
                    .padding(bottom = snackbarBottomOffset)
                    .imePadding(),
        )
    }

    MainOverlaySheets(
        state = state,
        onAction = onAction,
        onShareEntry = onShareEntry,
        onOpenExternalUrl = onOpenExternalUrl,
        onOpenCollectionDraw = onOpenCollectionDraw,
        onPlayMusicTrack = onPlayMusicTrack,
    )
}

@Suppress("LongParameterList")
private fun handleBottomBarTabSelect(
    tappedTab: AppTab,
    activeTab: AppTab,
    topKey: RocheNavKey,
    state: MainUiState,
    scrollStates: MainScrollStateHolder,
    onAction: (MainAction) -> Unit,
    launchScroll: (suspend () -> Unit) -> Unit,
) {
    if (tappedTab != activeTab || topKey is RocheNavKey.Search) {
        onAction(MainAction.SelectTab(tappedTab))
        return
    }
    when {
        tappedTab == AppTab.SETTINGS -> onAction(MainAction.ResetSettingsToHub)
        topKey is RocheNavKey.ScopedInbox ||
            tappedTab == AppTab.LISTS && state.selectedCatalogCollectionId != null ->
            onAction(MainAction.SelectTab(tappedTab))
        else -> launchScroll { scrollStates.scrollToTop(tappedTab, state) }
    }
}

internal data class MainChromeFlags(
    val showChrome: Boolean,
    val hasFab: Boolean,
    val showSearchFab: Boolean,
    val showAddFab: Boolean,
)

private val HEADER_SEARCH_TABS =
    setOf(
        AppTab.HOME,
        AppTab.INBOX,
        AppTab.FEEDS,
        AppTab.LISTS,
        AppTab.DIARY,
        AppTab.QUEUE,
        AppTab.STARRED,
        AppTab.OFFLINE,
    )

internal fun computeChromeFlags(topKey: RocheNavKey): MainChromeFlags {
    val showChrome = topKey !is RocheNavKey.Reader && topKey !is RocheNavKey.FullscreenPlayer
    val topTab = (topKey as? RocheNavKey.Tab)?.tab
    val isInboxTop = topKey is RocheNavKey.ScopedInbox || topTab == AppTab.INBOX
    val hasHeaderSearch = isInboxTop || topTab in HEADER_SEARCH_TABS
    val hasFab = showChrome && topKey !is RocheNavKey.Search && topTab != AppTab.SETTINGS
    val showSearchFab = hasFab && !hasHeaderSearch
    val showAddFab = hasFab && !isInboxTop
    return MainChromeFlags(
        showChrome = showChrome,
        hasFab = showSearchFab || showAddFab,
        showSearchFab = showSearchFab,
        showAddFab = showAddFab,
    )
}

private fun resolveBarTab(topKey: RocheNavKey, state: MainUiState): AppTab =
    when (topKey) {
        is RocheNavKey.Tab -> topKey.tab
        is RocheNavKey.ScopedInbox -> AppTab.INBOX
        else -> state.activeTab
    }

@Composable
internal fun rememberMainScrollStates(): MainScrollStateHolder =
    rememberSaveable(saver = MainScrollStateHolder.Saver) { MainScrollStateHolder() }

@Composable
@Suppress("LongParameterList")
internal fun MainDestinationScaffold(
    state: MainUiState,
    activePlayerEntry: InboxEntry?,
    playerStateFlow: StateFlow<PodcastPlayerState>,
    showBottomBar: Boolean,
    hasFab: Boolean,
    showSearchFab: Boolean = hasFab,
    showAddFab: Boolean = hasFab,
    activeTabForBar: AppTab,
    onChromeBottomHeightChange: (Int) -> Unit,
    onSelectTab: (AppTab) -> Unit,
    onAction: (MainAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalRocheColors.current
    Scaffold(
        modifier = Modifier.fillMaxSize().background(colors.background),
        containerColor = colors.background,
        floatingActionButton = {
            if (hasFab && (showSearchFab || showAddFab)) {
                val addAction = resolveAddFabAction(state)
                val addLabel =
                    if (addAction is MainAction.OpenQuickLogSheet) {
                        "Log or Add Entry"
                    } else {
                        SAVE_FAB_DEFAULT_LABEL
                    }
                FloatingOverlayButtonStack(
                    showSearchFab = showSearchFab,
                    showAddFab = showAddFab,
                    addFabContentDescription = addLabel,
                    onOpenSearch = { onAction(MainAction.OpenSearch) },
                    onOpenAddSheet = { onAction(addAction) },
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                MainBottomBarChrome(
                    state = state,
                    activeTab = activeTabForBar,
                    activePlayerEntry = activePlayerEntry,
                    playerStateFlow = playerStateFlow,
                    onSelectTab = onSelectTab,
                    onAction = onAction,
                    modifier = Modifier.onSizeChanged { onChromeBottomHeightChange(it.height) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
        ) {
            content()
            if (state.isSyncing) {
                GlobalSyncActivityBanner(modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun GlobalSyncActivityBanner(modifier: Modifier = Modifier) {
    val colors = LocalRocheColors.current
    LinearProgressIndicator(
        color = colors.accentBlue,
        trackColor = Color.Transparent,
        modifier = modifier.fillMaxWidth().height(2.dp).testTag("global_sync_indicator"),
    )
}

@Composable
internal fun MainBottomBarChrome(
    state: MainUiState,
    activeTab: AppTab,
    activePlayerEntry: InboxEntry?,
    playerStateFlow: StateFlow<PodcastPlayerState>,
    onSelectTab: (AppTab) -> Unit,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (activePlayerEntry != null) {
            DockedMiniPlayerSlot(
                entry = activePlayerEntry,
                playerStateFlow = playerStateFlow,
                onAction = onAction,
            )
        }
        val inboxUnreadCount =
            remember(state.allEntries) {
                state.allEntries.count { it.inFeedInbox && !it.isArchived && it.isUnread }
            }
        RocheBottomBar(
            activeTab = activeTab,
            sideTabs = state.settings.bottomNavTabs,
            inboxUnreadCount = inboxUnreadCount,
            onSelectTab = onSelectTab,
        )
    }
}

@Composable
private fun DockedMiniPlayerSlot(
    entry: InboxEntry,
    playerStateFlow: StateFlow<PodcastPlayerState>,
    onAction: (MainAction) -> Unit,
) {
    val playerState by playerStateFlow.collectAsStateWithLifecycle()
    DockedMiniPlayer(
        entry = entry,
        playerState = playerState,
        onAction = onAction,
    )
}

@Composable
internal fun FloatingOverlayButtonStack(
    onOpenSearch: () -> Unit,
    onOpenAddSheet: () -> Unit,
    showSearchFab: Boolean = true,
    showAddFab: Boolean = true,
    addFabContentDescription: String = SAVE_FAB_DEFAULT_LABEL,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showSearchFab) {
            SearchFloatingActionButton(onClick = onOpenSearch)
        }
        if (showAddFab) {
            SaveFloatingActionButton(
                onClick = onOpenAddSheet,
                contentDescription = addFabContentDescription,
            )
        }
    }
}

@Composable
internal fun MainSnackbarOverlay(
    state: MainUiState,
    chromeFlags: MainChromeFlags,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val undo = state.undoEvent
    val banner = state.statusBanner
    val hasModalOpen = state.hasOpenModalSheet
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(undo, banner, state.messageSequence, hasModalOpen, accessibilityManager) {
        if ((undo != null || banner != null) && !hasModalOpen) {
            val baseTimeoutMs =
                if (undo != null) UNDO_SNACKBAR_AUTO_DISMISS_MS else STATUS_SNACKBAR_AUTO_DISMISS_MS
            val timeoutMs =
                accessibilityManager?.calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis = baseTimeoutMs,
                    containsIcons = false,
                    containsText = true,
                    containsControls = undo != null,
                ) ?: baseTimeoutMs
            delay(timeoutMs)
            onAction(MainAction.DismissMessages)
        }
    }

    if (undo != null || banner != null) {
        val bottomPadding =
            when {
                chromeFlags.showSearchFab && chromeFlags.showAddFab -> 134.dp
                chromeFlags.hasFab -> 76.dp
                else -> 14.dp
            }
        UndoOrStatusSnackbar(
            message = undo?.message ?: banner.orEmpty(),
            showUndo = undo != null,
            onUndo = { onAction(MainAction.UndoLastArchive) },
            onDismiss = { onAction(MainAction.DismissMessages) },
            modifier = modifier.padding(start = 14.dp, end = 14.dp, bottom = bottomPadding),
        )
    }
}

@Composable
internal fun SearchFloatingActionButton(onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = colors.surfaceElevated,
        contentColor = colors.textPrimary,
        modifier =
            Modifier.size(48.dp)
                .border(width = 1.dp, color = colors.controlOutline, shape = CircleShape)
                .semantics { contentDescription = "Search" }
                .testTag("search_fab"),
    ) {
        SearchIcon(tint = colors.textPrimary, size = 18.dp)
    }
}

@Composable
internal fun SaveFloatingActionButton(
    onClick: () -> Unit,
    contentDescription: String = SAVE_FAB_DEFAULT_LABEL,
) {
    val colors = LocalRocheColors.current
    FloatingActionButton(
        onClick = onClick,
        shape = RoundedCornerShape(15.dp),
        containerColor = colors.accentBlueFilled,
        contentColor = Color.White,
        modifier =
            Modifier.size(52.dp)
                .semantics { this.contentDescription = contentDescription }
                .testTag("save_fab"),
    ) {
        PlusIcon(tint = Color.White, size = 20.dp)
    }
}

@Composable
internal fun MainOverlaySheets(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit = {},
    onPlayMusicTrack: (MusicTrackLaunchRequest) -> Unit = {},
) {
    val contextEntry = state.contextSheetEntry
    if (contextEntry != null) {
        EntryContextBottomSheet(
            entry = contextEntry,
            availableTags = state.tags.map { it.name },
            isInQueue = state.isEntryInQueue(contextEntry.id),
            onAction = onAction,
            onShareEntry = onShareEntry,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }

    val contextFeed = state.contextSheetFeed
    if (contextFeed != null) {
        FeedContextBottomSheet(
            feed = contextFeed,
            availableTags = state.tags.map { it.name },
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }

    if (state.isAddSheetOpen) {
        val defaultMode =
            if (state.prefilledShareIsFeed || state.activeTab == AppTab.FEEDS) {
                AddSheetMode.SUBSCRIBE_FEED
            } else {
                AddSheetMode.WANT_TO_READ
            }
        AddUrlOrFeedBottomSheet(
            initialUrl = state.prefilledShareUrl,
            initialTitle = state.prefilledShareTitle,
            defaultMode = defaultMode,
            onDismiss = { onAction(MainAction.CloseAddSheet) },
            onSave = { url, title, tags, mode ->
                onAction(
                    MainAction.SaveUrlOrFeed(
                        url = url,
                        titleOverride = title,
                        tagCsv = tags,
                        mode = mode,
                    )
                )
            },
        )
    }

    val drawDialog = state.drawNoteDialog
    if (drawDialog != null) {
        CollectionDrawNoteModalSheet(
            dialog = drawDialog,
            generator = state.catalog.generatorFor(drawDialog.collectionId),
            onAction = onAction,
        )
    }

    val activeRankSession = state.rankSession
    if (activeRankSession != null) {
        FocusedRankBottomSheet(
            session = activeRankSession,
            onAction = onAction,
            catalog = state.catalog,
        )
    }

    val catalogDetailId = state.catalogDetailEntryId
    if (catalogDetailId != null) {
        CatalogDetailBottomSheet(
            entryId = catalogDetailId,
            state = state,
            onAction = onAction,
            onOpenCollectionDraw = onOpenCollectionDraw,
            onPlayMusicTrack = onPlayMusicTrack,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }

    if (state.isQuickLogSheetOpen) {
        QuickLogBottomSheet(
            defaultToWant = state.quickLogDefaultWant,
            targetCollectionId = state.quickLogTargetCollectionId,
            targetCollectionTitle = state.quickLogTargetCollectionTitle,
            prefillKind = state.quickLogPrefillKind,
            prefillTitle = state.quickLogPrefillTitle,
            prefillCreators = state.quickLogPrefillCreators,
            prefillReleaseYear = state.quickLogPrefillReleaseYear,
            onAction = onAction,
        )
    }
}

@Composable
private fun MainSearchDestination(
    state: MainUiState,
    activeAudioEntryId: String?,
    scrollStates: MainScrollStateHolder,
    onAction: (MainAction) -> Unit,
) {
    val normalizedQuery = state.searchQuery.trim().lowercase()
    val searchListState = scrollStates.lazyListState(SEARCH_SCROLL_STATE_KEY)
    var lastScrolledQuery by remember { mutableStateOf(normalizedQuery) }
    LaunchedEffect(normalizedQuery) {
        if (normalizedQuery != lastScrolledQuery) {
            lastScrolledQuery = normalizedQuery
            searchListState.scrollToItem(0)
        }
    }
    SearchOverlay(
        state = state,
        onAction = onAction,
        listState = searchListState,
        isSearchActive = state.isSearchOpen,
        activeAudioEntryId = activeAudioEntryId,
    )
}

@Composable
private fun MainReaderDestination(
    readerKey: RocheNavKey.Reader,
    state: MainUiState,
    activeReaderEntry: InboxEntry?,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val targetEntry =
        activeReaderEntry?.takeIf { it.id == readerKey.entryId }
            ?: state.entryById[readerKey.entryId]
    if (targetEntry != null) {
        CompositionLocalProvider(LocalReaderFontScale provides state.settings.readerFontScale) {
            ReaderView(
                entry = targetEntry,
                settings = state.settings,
                isHydrating = state.isReaderHydrating,
                isDownloadingOffline = targetEntry.id in state.inFlightOfflineEntryIds,
                onAction = onAction,
                onShareEntry = onShareEntry,
                onOpenExternalUrl = onOpenExternalUrl,
                backLabel = readerBackLabel(state),
            )
        }
    }
}

@Composable
private fun MainPlayerDestination(
    playerKey: RocheNavKey.FullscreenPlayer,
    state: MainUiState,
    activePlayerEntry: InboxEntry?,
    playerStateFlow: StateFlow<PodcastPlayerState>,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val playerState by playerStateFlow.collectAsStateWithLifecycle()
    val targetEntry =
        activePlayerEntry?.takeIf { it.id == playerKey.entryId }
            ?: state.entryById[playerKey.entryId]
    if (targetEntry != null) {
        val resolvedFeed =
            state.resolveActivePlayerFeed(playerState.currentEntryId)
                ?: targetEntry.feedId?.let { fid -> state.feeds.firstOrNull { it.id == fid } }
        FullscreenPodcastPlayer(
            entry = targetEntry,
            feed = resolvedFeed,
            playerState = playerState,
            nextQueuedAudioEntry = state.resolveNextQueuedAudioEntry(playerState.currentEntryId),
            onAction = onAction,
            onShareEntry = onShareEntry,
            onOpenExternalUrl = onOpenExternalUrl,
            isSpeedSheetOpen = state.isPlayerSpeedSheetOpen,
            activeDrawerTab = state.activePlayerDrawerTab,
        )
    }
}

@Composable
@Suppress("LongParameterList")
internal fun MainTabDestinationContent(
    tab: AppTab,
    state: MainUiState,
    activeAudioEntryId: String?,
    scrollStates: MainScrollStateHolder,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
    onRevealSavedBearerToken: suspend () -> String,
) {
    when (tab) {
        AppTab.HOME ->
            HomeTabContent(
                state = state,
                onAction = onAction,
                scrollState = scrollStates.scrollState("home"),
                activeAudioEntryId = activeAudioEntryId,
                onOpenExternalUrl = onOpenExternalUrl,
                onOpenCollectionDraw = onOpenCollectionDraw,
            )
        AppTab.INBOX ->
            InboxTabContent(
                state = state,
                onAction = onAction,
                listState = scrollStates.lazyListState("inbox:filter:${state.activeFilter.name}"),
                activeAudioEntryId = activeAudioEntryId,
                entries = state.unscopedInboxEntries,
                selectedFeedId = null,
                selectedTag = null,
            )
        AppTab.QUEUE ->
            QueueTabContent(
                state = state,
                onAction = onAction,
                upNextListState = scrollStates.lazyListState("queue:up_next"),
                wantListState = scrollStates.lazyListState("queue:want"),
                historyListState = scrollStates.lazyListState("queue:history"),
                activeAudioEntryId = activeAudioEntryId,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        AppTab.STARRED ->
            StarredTabContent(
                state = state,
                onAction = onAction,
                listState = scrollStates.lazyListState("starred"),
                activeAudioEntryId = activeAudioEntryId,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        AppTab.FEEDS ->
            FeedsAndTagsTabContent(
                state = state,
                onAction = onAction,
                scrollState = scrollStates.scrollState("feeds"),
            )
        AppTab.OFFLINE ->
            OfflineTabContent(
                state = state,
                onAction = onAction,
                listState = scrollStates.lazyListState("offline"),
            )
        AppTab.LISTS ->
            MediaCompanionScreen(
                state = state,
                onAction = onAction,
                listState = scrollStates.lazyListState(companionScrollKey(state)),
                overviewScrollState = scrollStates.scrollState("lists:overview"),
                onOpenCollectionDraw = onOpenCollectionDraw,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        AppTab.DIARY ->
            DiaryScreen(
                state = state,
                onAction = onAction,
                listState = scrollStates.lazyListState("diary"),
                onOpenExternalUrl = onOpenExternalUrl,
            )
        AppTab.SETTINGS ->
            SettingsTabContent(
                state = state,
                onAction = onAction,
                onImportOpml = onImportOpml,
                onExportOpml = onExportOpml,
                onRevealSavedBearerToken = onRevealSavedBearerToken,
            )
    }
}

internal fun resolveAddFabAction(state: MainUiState): MainAction =
    when (state.activeTab) {
        AppTab.LISTS -> resolveListsFabAction(state)
        AppTab.DIARY -> MainAction.OpenQuickLogSheet(defaultToWant = false)
        AppTab.STARRED ->
            MainAction.OpenQuickLogSheet(
                defaultToWant = false,
                targetCollectionId = STARRED_COLLECTION_ID,
                targetCollectionTitle = "Starred",
            )
        AppTab.QUEUE -> resolveQueueFabAction(state)
        else -> MainAction.OpenAddSheet()
    }

private fun resolveListsFabAction(state: MainUiState): MainAction {
    val activeList =
        state.selectedCatalogCollectionId?.let { id ->
            state.catalog.lists.firstOrNull { it.collectionId == id }
        }
    val editable = isEditableManualUserList(activeList)
    return MainAction.OpenQuickLogSheet(
        defaultToWant = false,
        targetCollectionId = activeList?.collectionId?.takeIf { editable },
        targetCollectionTitle = activeList?.title?.takeIf { editable },
        prefillKind = EntryKind.inferForCollection(activeList),
    )
}

private fun resolveQueueFabAction(state: MainUiState): MainAction {
    if (state.queueSubTab != QueueSubTab.WANT) {
        return MainAction.OpenAddSheet()
    }
    val activeQueue =
        resolveActiveWantQueue(
            queues = state.catalog.wantQueues,
            selectedId = state.selectedWantCollectionId,
        )
    return if (activeQueue != null && activeQueue.collectionId != WANT_TO_READ_COLLECTION_ID) {
        MainAction.OpenQuickLogSheet(
            defaultToWant = true,
            prefillKind = EntryKind.inferForCollection(activeQueue),
        )
    } else {
        MainAction.OpenAddSheet()
    }
}

internal fun scopedInboxScrollKey(key: RocheNavKey.ScopedInbox): String =
    when {
        key.feedId != null -> "inbox:feed:${key.feedId}"
        key.tag != null -> "inbox:tag:${key.tag}"
        else -> "inbox:scoped"
    }

internal fun companionScrollKey(state: MainUiState): String =
    when (state.companionSubTab) {
        CompanionSubTab.LISTS -> {
            val lists = state.catalog.lists
            val activeId =
                lists
                    .firstOrNull { it.collectionId == state.selectedCatalogCollectionId }
                    ?.collectionId
                    ?: lists.firstOrNull { it.entryCount > 0 || it.generatorEnabled }?.collectionId
                    ?: lists.firstOrNull()?.collectionId
                    ?: "default"
            "lists:LISTS:$activeId"
        }
        CompanionSubTab.WANT -> {
            val queues = state.catalog.wantQueues
            val activeId =
                queues
                    .firstOrNull { it.collectionId == state.selectedWantCollectionId }
                    ?.collectionId
                    ?: queues.firstOrNull { it.entryCount > 0 }?.collectionId
                    ?: queues.firstOrNull()?.collectionId
                    ?: "default"
            "lists:WANT:$activeId"
        }
        CompanionSubTab.DIARY -> {
            "lists:DIARY"
        }
    }

internal fun readerBackLabel(state: MainUiState): String =
    when {
        state.isSearchOpen -> "← Search"
        state.activeTab == AppTab.HOME -> "← Home"
        state.activeTab == AppTab.QUEUE -> "← Queue"
        state.activeTab == AppTab.STARRED -> "← Stars"
        state.activeTab == AppTab.OFFLINE -> "← Offline"
        state.activeTab == AppTab.LISTS -> "← Lists"
        state.activeTab == AppTab.DIARY -> "← Diary"
        state.selectedFeedId != null -> "← ${state.selectedFeedTitle ?: "Feed"}"
        state.selectedTag != null -> "← #${state.selectedTag}"
        else -> "← Inbox"
    }

@Serializable
internal data class SavedLazyListPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

@Serializable
internal data class SavedScrollStateSnapshot(
    val lazyPositions: Map<String, SavedLazyListPosition> = emptyMap(),
    val scrollOffsets: Map<String, Int> = emptyMap(),
)

internal class MainScrollStateHolder(
    initialLazyPositions: Map<String, Pair<Int, Int>> = emptyMap(),
    initialScrollOffsets: Map<String, Int> = emptyMap(),
) {
    internal val lazyStates =
        mutableMapOf<String, LazyListState>().apply {
            initialLazyPositions.forEach { (key, pos) ->
                put(key, LazyListState(pos.first, pos.second))
            }
        }
    internal val scrollStates =
        mutableMapOf<String, ScrollState>().apply {
            initialScrollOffsets.forEach { (key, offset) -> put(key, ScrollState(offset)) }
        }

    fun lazyListState(key: String): LazyListState = lazyStates.getOrPut(key) { LazyListState() }

    fun scrollState(key: String): ScrollState = scrollStates.getOrPut(key) { ScrollState(0) }

    suspend fun scrollToTop(tab: AppTab, state: MainUiState) {
        when (tab) {
            AppTab.HOME -> scrollState("home").animateScrollTo(0)
            AppTab.INBOX ->
                lazyListState("inbox:filter:${state.activeFilter.name}").animateScrollToItem(0)
            AppTab.QUEUE -> lazyListState(queueScrollKey(state.queueSubTab)).animateScrollToItem(0)
            AppTab.STARRED -> lazyListState("starred").animateScrollToItem(0)
            AppTab.FEEDS -> scrollState("feeds").animateScrollTo(0)
            AppTab.OFFLINE -> lazyListState("offline").animateScrollToItem(0)
            AppTab.LISTS -> scrollListsToTop(state)
            AppTab.DIARY -> lazyListState("diary").animateScrollToItem(0)
            AppTab.SETTINGS -> Unit
        }
    }

    private suspend fun scrollListsToTop(state: MainUiState) {
        if (state.selectedCatalogCollectionId == null) {
            scrollState("lists:overview").animateScrollTo(0)
        } else {
            lazyListState(companionScrollKey(state)).animateScrollToItem(0)
        }
    }

    private fun queueScrollKey(subTab: QueueSubTab): String =
        when (subTab) {
            QueueSubTab.UP_NEXT -> "queue:up_next"
            QueueSubTab.WANT -> "queue:want"
            QueueSubTab.HISTORY -> "queue:history"
        }

    internal fun toSavedSnapshot(): SavedScrollStateSnapshot =
        SavedScrollStateSnapshot(
            lazyPositions =
                lazyStates
                    .filterKeys { key ->
                        key != SEARCH_SCROLL_STATE_KEY &&
                            !key.startsWith("$SEARCH_SCROLL_STATE_KEY:")
                    }
                    .mapValues { (_, state) ->
                        SavedLazyListPosition(
                            firstVisibleItemIndex = state.firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset,
                        )
                    },
            scrollOffsets = scrollStates.mapValues { (_, state) -> state.value },
        )

    companion object {
        val Saver: Saver<MainScrollStateHolder, Bundle> =
            Saver(
                save = { holder ->
                    encodeToSavedState(
                        SavedScrollStateSnapshot.serializer(),
                        holder.toSavedSnapshot(),
                    )
                },
                restore = { savedState ->
                    fromSavedSnapshot(
                        decodeFromSavedState(SavedScrollStateSnapshot.serializer(), savedState)
                    )
                },
            )

        internal fun fromSavedSnapshot(snapshot: SavedScrollStateSnapshot): MainScrollStateHolder =
            MainScrollStateHolder(
                initialLazyPositions =
                    snapshot.lazyPositions.mapValues { (_, pos) ->
                        pos.firstVisibleItemIndex to pos.firstVisibleItemScrollOffset
                    },
                initialScrollOffsets = snapshot.scrollOffsets,
            )
    }
}

private const val SAVE_FAB_DEFAULT_LABEL = "Save URL or Feed"
private const val STATUS_SNACKBAR_AUTO_DISMISS_MS = 4_000L
private const val UNDO_SNACKBAR_AUTO_DISMISS_MS = 7_000L
internal const val SEARCH_SCROLL_STATE_KEY = "search"
