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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.grayvines.roche.data.DailyAlbumSnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.PlusIcon
import com.grayvines.roche.ui.components.RocheBottomBar
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.UndoOrStatusSnackbar
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Composable
fun MainScreen(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
    onOpenDailyAlbumInMusicApp: (DailyAlbumSnapshot) -> Unit = { album ->
        onOpenExternalUrl(album.youtubeMusicUrl)
    },
) {
    val colors = LocalRocheColors.current
    val scrollStates = rememberMainScrollStates()
    val activePlayerEntry = state.activePlayerEntry
    val activeReaderEntry = state.activeReaderEntry
    var retainedSearchState by remember { mutableStateOf(state.takeIf { it.isSearchOpen }) }
    if (state.isSearchOpen) {
        retainedSearchState = state
    }
    val retainedScopedStates = remember {
        mutableStateMapOf<RocheNavKey.ScopedInbox, MainUiState>()
    }
    if (state.selectedFeedId != null || state.selectedTag != null) {
        val activeScopedKey =
            RocheNavKey.ScopedInbox(
                feedId = state.selectedFeedId,
                tag = state.selectedTag,
            )
        if (
            activeScopedKey !in retainedScopedStates &&
                retainedScopedStates.size >= MAX_RETAINED_SCOPED_STATES
        ) {
            retainedScopedStates.keys.firstOrNull()?.let(retainedScopedStates::remove)
        }
        retainedScopedStates[activeScopedKey] = state
    }
    val currentState by rememberUpdatedState(state)
    val currentRetainedSearch by rememberUpdatedState(retainedSearchState)
    val currentPlayerEntry by rememberUpdatedState(activePlayerEntry)
    val currentReaderEntry by rememberUpdatedState(activeReaderEntry)
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnShareEntry by rememberUpdatedState(onShareEntry)
    val currentOnOpenExternalUrl by rememberUpdatedState(onOpenExternalUrl)
    val currentOnImportOpml by rememberUpdatedState(onImportOpml)
    val currentOnExportOpml by rememberUpdatedState(onExportOpml)
    val currentOnOpenDailyAlbum by rememberUpdatedState(onOpenDailyAlbumInMusicApp)
    val activeFullscreenPlayerEntryId =
        activePlayerEntry?.id?.takeIf { state.playerState.isFullscreenOpen }
    val desiredBackStack =
        remember(state, activeReaderEntry?.id, activeFullscreenPlayerEntryId) {
            deriveRocheBackStack(
                state = state,
                activeFullscreenPlayerEntryId = activeFullscreenPlayerEntryId,
            )
        }
    val navBackStack = rememberNavBackStack(*desiredBackStack.toTypedArray())
    syncRocheNavBackStack(navBackStack, desiredBackStack)
    val topKey = navBackStack.lastOrNull() as? RocheNavKey ?: desiredBackStack.last()
    val showChrome = topKey !is RocheNavKey.Reader && topKey !is RocheNavKey.FullscreenPlayer
    val hasFab =
        showChrome &&
            topKey !is RocheNavKey.Search &&
            (topKey as? RocheNavKey.Tab)?.tab != AppTab.SETTINGS
    var chromeBottomPx by remember { mutableIntStateOf(0) }
    val snackbarBottomOffset =
        if (showChrome) {
            with(LocalDensity.current) { chromeBottomPx.toDp() }
        } else {
            0.dp
        }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
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
                        MainDestinationScaffold(
                            state = currentState,
                            activePlayerEntry = currentPlayerEntry,
                            showBottomBar = true,
                            hasFab = tabKey.tab != AppTab.SETTINGS,
                            activeTabForBar = tabKey.tab,
                            onChromeBottomHeightChange = { chromeBottomPx = it },
                            onAction = currentOnAction,
                        ) {
                            MainTabDestinationContent(
                                tab = tabKey.tab,
                                state = currentState,
                                scrollStates = scrollStates,
                                onAction = currentOnAction,
                                onOpenExternalUrl = currentOnOpenExternalUrl,
                                onOpenDailyAlbumInMusicApp = currentOnOpenDailyAlbum,
                                onImportOpml = currentOnImportOpml,
                                onExportOpml = currentOnExportOpml,
                            )
                        }
                    }

                    entry<RocheNavKey.ScopedInbox> { scopedKey ->
                        MainScopedInboxDestination(
                            scopedKey = scopedKey,
                            state = currentState,
                            retainedFromParent = retainedScopedStates[scopedKey],
                            activePlayerEntry = currentPlayerEntry,
                            scrollStates = scrollStates,
                            onChromeBottomHeightChange = { chromeBottomPx = it },
                            onAction = currentOnAction,
                        )
                    }

                    entry<RocheNavKey.Search> {
                        MainSearchDestination(
                            state = currentState,
                            retainedFromParent = currentRetainedSearch,
                            activePlayerEntry = currentPlayerEntry,
                            scrollStates = scrollStates,
                            onChromeBottomHeightChange = { chromeBottomPx = it },
                            onAction = currentOnAction,
                        )
                    }

                    entry<RocheNavKey.Reader> { readerKey ->
                        MainReaderDestination(
                            readerKey = readerKey,
                            state = currentState,
                            activeReaderEntry = currentReaderEntry,
                            activePlayerEntry = currentPlayerEntry,
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
                            onAction = currentOnAction,
                            onShareEntry = currentOnShareEntry,
                            onOpenExternalUrl = currentOnOpenExternalUrl,
                        )
                    }
                },
        )

        MainSnackbarOverlay(
            state = state,
            hasFab = hasFab,
            onAction = onAction,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .then(if (!showChrome) Modifier.navigationBarsPadding() else Modifier)
                    .padding(bottom = snackbarBottomOffset)
                    .imePadding(),
        )
    }

    MainOverlaySheets(
        state = state,
        onAction = onAction,
        onShareEntry = onShareEntry,
        onOpenExternalUrl = onOpenExternalUrl,
    )
}

@Composable
internal fun rememberMainScrollStates(): MainScrollStateHolder =
    rememberSaveable(saver = MainScrollStateHolder.Saver) { MainScrollStateHolder() }

@Composable
internal fun MainDestinationScaffold(
    state: MainUiState,
    activePlayerEntry: InboxEntry?,
    showBottomBar: Boolean,
    hasFab: Boolean,
    activeTabForBar: AppTab,
    onChromeBottomHeightChange: (Int) -> Unit,
    onAction: (MainAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalRocheColors.current
    Scaffold(
        modifier = Modifier.fillMaxSize().background(colors.background),
        containerColor = colors.background,
        floatingActionButton = {
            if (hasFab) {
                FloatingOverlayButtonStack(
                    onOpenSearch = { onAction(MainAction.OpenSearch) },
                    onOpenAddSheet = { onAction(MainAction.OpenAddSheet()) },
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                MainBottomBarChrome(
                    state = state,
                    activeTab = activeTabForBar,
                    activePlayerEntry = activePlayerEntry,
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
        }
    }
}

@Composable
internal fun MainBottomBarChrome(
    state: MainUiState,
    activeTab: AppTab,
    activePlayerEntry: InboxEntry?,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (activePlayerEntry != null) {
            DockedMiniPlayer(
                entry = activePlayerEntry,
                playerState = state.playerState,
                onAction = onAction,
            )
        }
        RocheBottomBar(
            activeTab = activeTab,
            sideTabs = state.settings.bottomNavTabs,
            onSelectTab = { onAction(MainAction.SelectTab(it)) },
        )
    }
}

@Composable
internal fun FloatingOverlayButtonStack(
    onOpenSearch: () -> Unit,
    onOpenAddSheet: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SearchFloatingActionButton(onClick = onOpenSearch)
        SaveFloatingActionButton(onClick = onOpenAddSheet)
    }
}

@Composable
internal fun MainSnackbarOverlay(
    state: MainUiState,
    hasFab: Boolean,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val undo = state.undoEvent
    val banner = state.statusBanner
    LaunchedEffect(undo, banner, state.messageSequence) {
        if (undo != null || banner != null) {
            delay(SNACKBAR_AUTO_DISMISS_MS)
            onAction(MainAction.DismissMessages)
        }
    }

    if (undo != null || banner != null) {
        val endPadding = if (hasFab) 80.dp else 14.dp
        UndoOrStatusSnackbar(
            message = undo?.message ?: banner.orEmpty(),
            showUndo = undo != null,
            onUndo = { onAction(MainAction.UndoLastArchive) },
            onDismiss = { onAction(MainAction.DismissMessages) },
            modifier = modifier.padding(start = 14.dp, end = endPadding, bottom = 14.dp),
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
            Modifier.size(46.dp)
                .border(width = 1.dp, color = colors.border, shape = CircleShape)
                .semantics { contentDescription = "Search" }
                .testTag("search_fab"),
    ) {
        SearchIcon(tint = colors.textPrimary, size = 18.dp)
    }
}

@Composable
internal fun SaveFloatingActionButton(onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = colors.accentBlue,
        contentColor = Color.White,
        modifier =
            Modifier.size(52.dp)
                .semantics { contentDescription = "Save URL or Feed" }
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
        AddUrlOrFeedBottomSheet(
            initialUrl = state.prefilledShareUrl,
            initialTitle = state.prefilledShareTitle,
            onDismiss = { onAction(MainAction.CloseAddSheet) },
            onSave = { url, title, tags ->
                onAction(MainAction.SaveUrlOrFeed(url = url, titleOverride = title, tagCsv = tags))
            },
        )
    }

    val albumDialogMode = state.albumNoteDialogMode
    if (albumDialogMode != null) {
        AlbumNoteModalSheet(
            album = state.dailyAlbum,
            mode = albumDialogMode,
            onAction = onAction,
        )
    }

    val activeRankSession = state.rankSession
    if (activeRankSession != null) {
        FocusedRankBottomSheet(
            session = activeRankSession,
            onAction = onAction,
        )
    }

    if (state.isQuickLogSheetOpen) {
        QuickLogBottomSheet(
            defaultToWant = state.quickLogDefaultWant,
            targetCollectionId = state.quickLogTargetCollectionId,
            targetCollectionTitle = state.quickLogTargetCollectionTitle,
            onAction = onAction,
        )
    }
}

@Composable
private fun MainScopedInboxDestination(
    scopedKey: RocheNavKey.ScopedInbox,
    state: MainUiState,
    retainedFromParent: MainUiState?,
    activePlayerEntry: InboxEntry?,
    scrollStates: MainScrollStateHolder,
    onChromeBottomHeightChange: (Int) -> Unit,
    onAction: (MainAction) -> Unit,
) {
    var retainedState by
        remember(scopedKey) {
            mutableStateOf(resolveScopedInboxState(state, scopedKey, retainedFromParent))
        }
    retainedState =
        resolveScopedInboxState(
            state = state,
            scopedKey = scopedKey,
            retainedState = retainedFromParent ?: retainedState,
        )
    MainDestinationScaffold(
        state = retainedState,
        activePlayerEntry = activePlayerEntry,
        showBottomBar = true,
        hasFab = true,
        activeTabForBar = AppTab.INBOX,
        onChromeBottomHeightChange = onChromeBottomHeightChange,
        onAction = onAction,
    ) {
        InboxTabContent(
            state = retainedState,
            onAction = onAction,
            listState = scrollStates.lazyListState(scopedInboxScrollKey(scopedKey)),
        )
    }
}

@Composable
private fun MainSearchDestination(
    state: MainUiState,
    retainedFromParent: MainUiState?,
    activePlayerEntry: InboxEntry?,
    scrollStates: MainScrollStateHolder,
    onChromeBottomHeightChange: (Int) -> Unit,
    onAction: (MainAction) -> Unit,
) {
    var retainedSearchState by remember {
        mutableStateOf(resolveSearchOverlayState(state, retainedFromParent))
    }
    if (state.isSearchOpen) {
        retainedSearchState = state
    } else if (retainedFromParent != null) {
        retainedSearchState = retainedFromParent
    }
    val searchScrollKey = "search:${retainedSearchState.searchQuery.trim().lowercase()}"
    MainDestinationScaffold(
        state = retainedSearchState,
        activePlayerEntry = activePlayerEntry,
        showBottomBar = true,
        hasFab = false,
        activeTabForBar = retainedSearchState.activeTab,
        onChromeBottomHeightChange = onChromeBottomHeightChange,
        onAction = onAction,
    ) {
        SearchOverlay(
            state = retainedSearchState,
            onAction = onAction,
            listState = scrollStates.lazyListState(searchScrollKey),
            isSearchActive = state.isSearchOpen,
        )
    }
}

@Composable
private fun MainReaderDestination(
    readerKey: RocheNavKey.Reader,
    state: MainUiState,
    activeReaderEntry: InboxEntry?,
    activePlayerEntry: InboxEntry?,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val liveEntry =
        activeReaderEntry?.takeIf { it.id == readerKey.entryId }
            ?: state.allEntries.firstOrNull { it.id == readerKey.entryId }
    var retainedEntry by remember(readerKey.entryId) { mutableStateOf(liveEntry) }
    if (liveEntry != null) {
        retainedEntry = liveEntry
    }
    var retainedBackLabel by remember(readerKey.entryId) { mutableStateOf(readerBackLabel(state)) }
    if (activeReaderEntry?.id == readerKey.entryId) {
        retainedBackLabel = readerBackLabel(state)
    }
    MainDestinationScaffold(
        state = state,
        activePlayerEntry = activePlayerEntry,
        showBottomBar = false,
        hasFab = false,
        activeTabForBar = state.activeTab,
        onChromeBottomHeightChange = {},
        onAction = onAction,
    ) {
        val targetEntry = retainedEntry
        if (targetEntry != null) {
            CompositionLocalProvider(LocalReaderFontScale provides state.settings.readerFontScale) {
                ReaderView(
                    entry = targetEntry,
                    settings = state.settings,
                    isHydrating = state.isReaderHydrating,
                    onAction = onAction,
                    onShareEntry = onShareEntry,
                    onOpenExternalUrl = onOpenExternalUrl,
                    backLabel = retainedBackLabel,
                )
            }
        }
    }
}

@Composable
private fun MainPlayerDestination(
    playerKey: RocheNavKey.FullscreenPlayer,
    state: MainUiState,
    activePlayerEntry: InboxEntry?,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val liveEntry =
        activePlayerEntry?.takeIf { it.id == playerKey.entryId }
            ?: state.allEntries.firstOrNull { it.id == playerKey.entryId }
    var retainedEntry by remember(playerKey.entryId) { mutableStateOf(liveEntry) }
    if (liveEntry != null) {
        retainedEntry = liveEntry
    }
    MainDestinationScaffold(
        state = state,
        activePlayerEntry = activePlayerEntry,
        showBottomBar = false,
        hasFab = false,
        activeTabForBar = state.activeTab,
        onChromeBottomHeightChange = {},
        onAction = onAction,
    ) {
        val targetEntry = retainedEntry
        if (targetEntry != null) {
            val resolvedFeed =
                state.activePlayerFeed
                    ?: targetEntry.feedId?.let { fid -> state.feeds.firstOrNull { it.id == fid } }
            FullscreenPodcastPlayer(
                entry = targetEntry,
                feed = resolvedFeed,
                playerState = state.playerState,
                nextQueuedAudioEntry = state.nextQueuedAudioEntry,
                onAction = onAction,
                onShareEntry = onShareEntry,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }
}

@Composable
private fun UnscopedInboxTabDestination(
    state: MainUiState,
    scrollStates: MainScrollStateHolder,
    onAction: (MainAction) -> Unit,
) {
    val hasActiveScope = state.selectedFeedId != null || state.selectedTag != null
    val unscopedEntries =
        remember(hasActiveScope, state.allEntries, state.activeFilter, state.visibleEntries) {
            if (hasActiveScope) {
                RocheRepository.filterEntries(
                    entries = state.allEntries,
                    filter = state.activeFilter,
                    searchQuery = "",
                    selectedTag = null,
                    selectedFeedId = null,
                )
            } else {
                state.visibleEntries
            }
        }
    val unscopedState = resolveUnscopedInboxState(state, unscopedEntries)
    InboxTabContent(
        state = unscopedState,
        onAction = onAction,
        listState = scrollStates.lazyListState("inbox:filter:${state.activeFilter.name}"),
    )
}

internal fun resolveUnscopedInboxState(
    state: MainUiState,
    unscopedEntries: List<InboxEntry> =
        if (state.selectedFeedId != null || state.selectedTag != null) {
            RocheRepository.filterEntries(
                entries = state.allEntries,
                filter = state.activeFilter,
                searchQuery = "",
                selectedTag = null,
                selectedFeedId = null,
            )
        } else {
            state.visibleEntries
        },
): MainUiState =
    if (state.selectedFeedId != null || state.selectedTag != null) {
        state.copy(
            selectedFeedId = null,
            selectedTag = null,
            visibleEntries = unscopedEntries,
        )
    } else {
        state
    }

internal fun resolveScopedInboxState(
    state: MainUiState,
    scopedKey: RocheNavKey.ScopedInbox,
    retainedState: MainUiState? = null,
): MainUiState =
    when {
        state.selectedFeedId == scopedKey.feedId && state.selectedTag == scopedKey.tag -> {
            state
        }
        retainedState != null &&
            retainedState.selectedFeedId == scopedKey.feedId &&
            retainedState.selectedTag == scopedKey.tag -> {
            retainedState
        }
        else -> {
            val scopedVisible =
                RocheRepository.filterEntries(
                    entries = state.allEntries,
                    filter = state.activeFilter,
                    searchQuery = "",
                    selectedTag = scopedKey.tag,
                    selectedFeedId = scopedKey.feedId,
                )
            state.copy(
                selectedFeedId = scopedKey.feedId,
                selectedTag = scopedKey.tag,
                visibleEntries = scopedVisible,
            )
        }
    }

internal fun resolveSearchOverlayState(
    state: MainUiState,
    retainedSearchState: MainUiState?,
): MainUiState =
    when {
        state.isSearchOpen -> state
        retainedSearchState != null -> retainedSearchState
        else -> state
    }

@Composable
internal fun MainTabDestinationContent(
    tab: AppTab,
    state: MainUiState,
    scrollStates: MainScrollStateHolder,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenDailyAlbumInMusicApp: (DailyAlbumSnapshot) -> Unit,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
) {
    when (tab) {
        AppTab.HOME ->
            HomeTabContent(
                state = state,
                onAction = onAction,
                scrollState = scrollStates.scrollState("home"),
                onOpenExternalUrl = onOpenExternalUrl,
                onOpenDailyAlbumInMusicApp = onOpenDailyAlbumInMusicApp,
            )
        AppTab.INBOX ->
            UnscopedInboxTabDestination(
                state = state,
                scrollStates = scrollStates,
                onAction = onAction,
            )
        AppTab.QUEUE ->
            QueueTabContent(
                state = state,
                onAction = onAction,
                upNextListState = scrollStates.lazyListState("queue:up_next"),
                historyListState = scrollStates.lazyListState("queue:history"),
            )
        AppTab.STARRED ->
            StarredTabContent(
                state = state,
                onAction = onAction,
                listState = scrollStates.lazyListState("starred"),
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
            )
        AppTab.SETTINGS ->
            SettingsTabContent(
                state = state,
                onAction = onAction,
                onImportOpml = onImportOpml,
                onExportOpml = onExportOpml,
            )
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
                    ?: lists.firstOrNull { it.entryCount > 0 }?.collectionId
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
        state.activeTab == AppTab.STARRED -> "← Starred"
        state.activeTab == AppTab.OFFLINE -> "← Offline"
        state.activeTab == AppTab.LISTS -> "← Lists"
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

    internal fun toSavedSnapshot(): SavedScrollStateSnapshot =
        SavedScrollStateSnapshot(
            lazyPositions =
                lazyStates.mapValues { (_, state) ->
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

private const val SNACKBAR_AUTO_DISMISS_MS = 4_000L
private const val MAX_RETAINED_SCOPED_STATES = 8
