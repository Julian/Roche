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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.generatorFor
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
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit = { draw ->
        openCollectionDrawFallback(draw, onOpenExternalUrl)
    },
) {
    val colors = LocalRocheColors.current
    val scrollStates = rememberMainScrollStates()
    val activePlayerEntry = state.activePlayerEntry
    val activeReaderEntry = state.activeReaderEntry
    val retainedSearchStateRef = remember { RetentionRef(state.takeIf { it.isSearchOpen }) }
    val retainedScopedStates = remember { LinkedHashMap<RocheNavKey.ScopedInbox, MainUiState>() }
    SideEffect {
        if (state.isSearchOpen) {
            retainedSearchStateRef.value = state
        }
        cacheActiveScopedState(state, retainedScopedStates)
    }
    val currentState by rememberUpdatedState(state)
    val currentPlayerEntry by rememberUpdatedState(activePlayerEntry)
    val currentReaderEntry by rememberUpdatedState(activeReaderEntry)
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnShareEntry by rememberUpdatedState(onShareEntry)
    val currentOnOpenExternalUrl by rememberUpdatedState(onOpenExternalUrl)
    val currentOnImportOpml by rememberUpdatedState(onImportOpml)
    val currentOnExportOpml by rememberUpdatedState(onExportOpml)
    val currentOnOpenCollectionDraw by rememberUpdatedState(onOpenCollectionDraw)
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
            showBottomBar = chromeFlags.showChrome,
            hasFab = chromeFlags.hasFab,
            showAddFab = chromeFlags.showAddFab,
            activeTabForBar = activeTabForBar,
            onChromeBottomHeightChange = { chromeBottomPx = it },
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
                                scrollStates = scrollStates,
                                onAction = currentOnAction,
                                onOpenExternalUrl = currentOnOpenExternalUrl,
                                onOpenCollectionDraw = currentOnOpenCollectionDraw,
                                onImportOpml = currentOnImportOpml,
                                onExportOpml = currentOnExportOpml,
                            )
                        }

                        entry<RocheNavKey.ScopedInbox> { scopedKey ->
                            MainScopedInboxDestination(
                                scopedKey = scopedKey,
                                state = currentState,
                                retainedFromParent = retainedScopedStates[scopedKey],
                                scrollStates = scrollStates,
                                onAction = currentOnAction,
                            )
                        }

                        entry<RocheNavKey.Search> {
                            MainSearchDestination(
                                state = currentState,
                                retainedFromParent =
                                    if (currentState.isSearchOpen) {
                                        currentState
                                    } else {
                                        retainedSearchStateRef.value
                                    },
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
            hasFab = chromeFlags.hasFab,
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
    )
}

private class RetentionRef<T>(var value: T)

private data class MainChromeFlags(
    val showChrome: Boolean,
    val hasFab: Boolean,
    val showAddFab: Boolean,
)

private fun computeChromeFlags(topKey: RocheNavKey): MainChromeFlags {
    val showChrome = topKey !is RocheNavKey.Reader && topKey !is RocheNavKey.FullscreenPlayer
    val isInboxTop =
        topKey is RocheNavKey.ScopedInbox || (topKey as? RocheNavKey.Tab)?.tab == AppTab.INBOX
    val hasFab =
        showChrome &&
            topKey !is RocheNavKey.Search &&
            (topKey as? RocheNavKey.Tab)?.tab != AppTab.SETTINGS
    return MainChromeFlags(
        showChrome = showChrome,
        hasFab = hasFab,
        showAddFab = hasFab && !isInboxTop,
    )
}

private fun cacheActiveScopedState(
    state: MainUiState,
    retainedScopedStates: MutableMap<RocheNavKey.ScopedInbox, MainUiState>,
) {
    if (state.selectedFeedId == null && state.selectedTag == null) return
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
internal fun MainDestinationScaffold(
    state: MainUiState,
    activePlayerEntry: InboxEntry?,
    showBottomBar: Boolean,
    hasFab: Boolean,
    showAddFab: Boolean = hasFab,
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
                    showAddFab = showAddFab,
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
    showAddFab: Boolean = true,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SearchFloatingActionButton(onClick = onOpenSearch)
        if (showAddFab) {
            SaveFloatingActionButton(onClick = onOpenAddSheet)
        }
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
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(undo, banner, state.messageSequence, accessibilityManager) {
        if (undo != null || banner != null) {
            val timeoutMs =
                accessibilityManager?.calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis = SNACKBAR_AUTO_DISMISS_MS,
                    containsIcons = false,
                    containsText = true,
                    containsControls = undo != null,
                ) ?: SNACKBAR_AUTO_DISMISS_MS
            delay(timeoutMs)
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
            Modifier.size(48.dp)
                .border(width = 1.dp, color = colors.controlOutline, shape = CircleShape)
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
        containerColor = colors.accentBlueFilled,
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
        val defaultMode =
            if (state.activeTab == AppTab.FEEDS) {
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
    scrollStates: MainScrollStateHolder,
    onAction: (MainAction) -> Unit,
) {
    val retainedStateRef =
        remember(scopedKey) {
            RetentionRef(resolveScopedInboxState(state, scopedKey, retainedFromParent))
        }
    val displayState =
        resolveScopedInboxState(
            state = state,
            scopedKey = scopedKey,
            retainedState = retainedFromParent ?: retainedStateRef.value,
        )
    SideEffect { retainedStateRef.value = displayState }
    InboxTabContent(
        state = displayState,
        onAction = onAction,
        listState = scrollStates.lazyListState(scopedInboxScrollKey(scopedKey)),
    )
}

@Composable
private fun MainSearchDestination(
    state: MainUiState,
    retainedFromParent: MainUiState?,
    scrollStates: MainScrollStateHolder,
    onAction: (MainAction) -> Unit,
) {
    val retainedSearchStateRef = remember {
        RetentionRef(resolveSearchOverlayState(state, retainedFromParent))
    }
    val displayState =
        when {
            state.isSearchOpen -> state
            retainedFromParent != null -> retainedFromParent
            else -> retainedSearchStateRef.value
        }
    SideEffect { retainedSearchStateRef.value = displayState }
    val normalizedQuery = displayState.searchQuery.trim().lowercase()
    val searchListState = scrollStates.lazyListState(SEARCH_SCROLL_STATE_KEY)
    var lastScrolledQuery by remember { mutableStateOf(normalizedQuery) }
    LaunchedEffect(normalizedQuery) {
        if (normalizedQuery != lastScrolledQuery) {
            lastScrolledQuery = normalizedQuery
            searchListState.scrollToItem(0)
        }
    }
    SearchOverlay(
        state = displayState,
        onAction = onAction,
        listState = searchListState,
        isSearchActive = state.isSearchOpen,
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
    val liveEntry =
        activeReaderEntry?.takeIf { it.id == readerKey.entryId }
            ?: state.allEntries.firstOrNull { it.id == readerKey.entryId }
    val retainedEntryRef = remember(readerKey.entryId) { RetentionRef(liveEntry) }
    val targetEntry = liveEntry ?: retainedEntryRef.value
    val retainedBackLabelRef = remember(readerKey.entryId) { RetentionRef(readerBackLabel(state)) }
    val backLabel =
        if (activeReaderEntry?.id == readerKey.entryId) {
            readerBackLabel(state)
        } else {
            retainedBackLabelRef.value
        }
    SideEffect {
        if (liveEntry != null) {
            retainedEntryRef.value = liveEntry
        }
        if (activeReaderEntry?.id == readerKey.entryId) {
            retainedBackLabelRef.value = backLabel
        }
    }
    if (targetEntry != null) {
        CompositionLocalProvider(LocalReaderFontScale provides state.settings.readerFontScale) {
            ReaderView(
                entry = targetEntry,
                settings = state.settings,
                isHydrating = state.isReaderHydrating,
                onAction = onAction,
                onShareEntry = onShareEntry,
                onOpenExternalUrl = onOpenExternalUrl,
                backLabel = backLabel,
            )
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
    val retainedEntryRef = remember(playerKey.entryId) { RetentionRef(liveEntry) }
    val targetEntry = liveEntry ?: retainedEntryRef.value
    SideEffect {
        if (liveEntry != null) {
            retainedEntryRef.value = liveEntry
        }
    }
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
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
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
                onOpenCollectionDraw = onOpenCollectionDraw,
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
                onOpenCollectionDraw = onOpenCollectionDraw,
                onOpenExternalUrl = onOpenExternalUrl,
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

internal fun openCollectionDrawFallback(
    draw: CollectionDrawSnapshot,
    onOpenExternalUrl: (String) -> Unit,
) {
    val fallback = if (draw.isMusicKind) draw.youtubeMusicUrl else draw.primaryExternalUrl.orEmpty()
    if (fallback.isNotBlank()) {
        onOpenExternalUrl(fallback)
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

private const val SNACKBAR_AUTO_DISMISS_MS = 4_000L
private const val MAX_RETAINED_SCOPED_STATES = 8
internal const val SEARCH_SCROLL_STATE_KEY = "search"
