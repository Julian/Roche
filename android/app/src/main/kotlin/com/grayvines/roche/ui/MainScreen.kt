@file:Suppress("TooManyFunctions", "LongMethod", "CognitiveComplexMethod")

package com.grayvines.roche.ui

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.PlusIcon
import com.grayvines.roche.ui.components.RocheBottomBar
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.UndoOrStatusSnackbar
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlinx.coroutines.delay

@Composable
fun MainScreen(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
    onMoveTaskToBack: () -> Unit = {},
) {
    val colors = LocalRocheColors.current
    val scrollStates =
        rememberSaveable(saver = MainScrollStateHolder.Saver) { MainScrollStateHolder() }
    val saveableStateHolder = rememberSaveableStateHolder()
    val activePlayerEntry = state.activePlayerEntry
    val showChrome =
        state.activeReaderEntry == null &&
            !(state.playerState.isFullscreenOpen && activePlayerEntry != null)
    val hasFab = showChrome && !state.isSearchOpen && state.activeTab != AppTab.SETTINGS

    MainBackNavigationHandler(
        state = state,
        showChrome = showChrome,
        hasActivePlayer = activePlayerEntry != null,
        onAction = onAction,
        onMoveTaskToBack = onMoveTaskToBack,
    )

    Scaffold(
        modifier = modifier.fillMaxSize().background(colors.background),
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
            if (showChrome) {
                MainBottomBarChrome(
                    state = state,
                    activePlayerEntry = activePlayerEntry,
                    onAction = onAction,
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
            MainBodyContent(
                state = state,
                scrollStates = scrollStates,
                saveableStateHolder = saveableStateHolder,
                onAction = onAction,
                onShareEntry = onShareEntry,
                onOpenExternalUrl = onOpenExternalUrl,
                onImportOpml = onImportOpml,
                onExportOpml = onExportOpml,
            )

            MainSnackbarOverlay(
                state = state,
                hasFab = hasFab,
                onAction = onAction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    MainOverlaySheets(
        state = state,
        onAction = onAction,
        onShareEntry = onShareEntry,
        onOpenExternalUrl = onOpenExternalUrl,
    )
}

@Composable
private fun MainBackNavigationHandler(
    state: MainUiState,
    showChrome: Boolean,
    hasActivePlayer: Boolean,
    onAction: (MainAction) -> Unit,
    onMoveTaskToBack: () -> Unit,
) {
    val hasScopeFilter = state.selectedFeedId != null || state.selectedTag != null
    val hasInnerBack = state.isSearchOpen || hasScopeFilter || state.activeTab != AppTab.INBOX
    BackHandler(enabled = showChrome && (hasInnerBack || hasActivePlayer)) {
        when {
            state.isSearchOpen -> onAction(MainAction.CloseSearch)
            hasScopeFilter -> onAction(MainAction.NavigateBackFromScope)
            state.activeTab != AppTab.INBOX -> onAction(MainAction.SelectTab(AppTab.INBOX))
            else -> onMoveTaskToBack()
        }
    }
}

@Composable
private fun MainBottomBarChrome(
    state: MainUiState,
    activePlayerEntry: InboxEntry?,
    onAction: (MainAction) -> Unit,
) {
    Column {
        if (activePlayerEntry != null) {
            DockedMiniPlayer(
                entry = activePlayerEntry,
                playerState = state.playerState,
                onAction = onAction,
            )
        }
        RocheBottomBar(
            activeTab = state.activeTab,
            sideTabs = state.settings.bottomNavTabs,
            onSelectTab = { onAction(MainAction.SelectTab(it)) },
        )
    }
}

@Composable
private fun FloatingOverlayButtonStack(
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
private fun MainSnackbarOverlay(
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
private fun SearchFloatingActionButton(onClick: () -> Unit) {
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
private fun SaveFloatingActionButton(onClick: () -> Unit) {
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
private fun MainOverlaySheets(
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
}

@Composable
@Suppress("LongParameterList")
private fun MainBodyContent(
    state: MainUiState,
    scrollStates: MainScrollStateHolder,
    saveableStateHolder: SaveableStateHolder,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
) {
    val activePlayerEntry = state.activePlayerEntry
    if (state.playerState.isFullscreenOpen && activePlayerEntry != null) {
        FullscreenPodcastPlayer(
            entry = activePlayerEntry,
            feed = state.activePlayerFeed,
            playerState = state.playerState,
            nextQueuedAudioEntry = state.nextQueuedAudioEntry,
            onAction = onAction,
            onShareEntry = onShareEntry,
            onOpenExternalUrl = onOpenExternalUrl,
        )
        return
    }
    val readerEntry = state.activeReaderEntry
    if (readerEntry != null) {
        CompositionLocalProvider(LocalReaderFontScale provides state.settings.readerFontScale) {
            ReaderView(
                entry = readerEntry,
                settings = state.settings,
                onAction = onAction,
                onShareEntry = onShareEntry,
                onOpenExternalUrl = onOpenExternalUrl,
                backLabel = readerBackLabel(state),
            )
        }
        return
    }
    if (state.isSearchOpen) {
        val searchScrollKey = "search:${state.searchQuery.trim().lowercase()}"
        saveableStateHolder.SaveableStateProvider("search_overlay") {
            SearchOverlay(
                state = state,
                onAction = onAction,
                listState = scrollStates.lazyListState(searchScrollKey),
            )
        }
        return
    }
    saveableStateHolder.SaveableStateProvider("tab_${state.activeTab.name}") {
        when (state.activeTab) {
            AppTab.HOME ->
                HomeTabContent(
                    state = state,
                    onAction = onAction,
                    scrollState = scrollStates.scrollState("home"),
                )
            AppTab.INBOX ->
                InboxTabContent(
                    state = state,
                    onAction = onAction,
                    listState = scrollStates.lazyListState(inboxScrollKey(state)),
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
            AppTab.SETTINGS ->
                SettingsTabContent(
                    state = state,
                    onAction = onAction,
                    onImportOpml = onImportOpml,
                    onExportOpml = onExportOpml,
                )
        }
    }
}

private fun inboxScrollKey(state: MainUiState): String =
    when {
        state.selectedFeedId != null -> "inbox:feed:${state.selectedFeedId}"
        state.selectedTag != null -> "inbox:tag:${state.selectedTag}"
        else -> "inbox:filter:${state.activeFilter.name}"
    }

private fun readerBackLabel(state: MainUiState): String =
    when {
        state.isSearchOpen -> "← Search"
        state.activeTab == AppTab.HOME -> "← Home"
        state.activeTab == AppTab.QUEUE -> "← Queue"
        state.activeTab == AppTab.STARRED -> "← Starred"
        state.activeTab == AppTab.OFFLINE -> "← Offline"
        state.selectedFeedId != null -> "← ${state.selectedFeedTitle ?: "Feed"}"
        state.selectedTag != null -> "← #${state.selectedTag}"
        else -> "← Inbox"
    }

internal class MainScrollStateHolder(
    initialLazyPositions: Map<String, Pair<Int, Int>> = emptyMap(),
    initialScrollOffsets: Map<String, Int> = emptyMap(),
) {
    private val lazyStates =
        mutableMapOf<String, LazyListState>().apply {
            initialLazyPositions.forEach { (key, pos) ->
                put(key, LazyListState(pos.first, pos.second))
            }
        }
    private val scrollStates =
        mutableMapOf<String, ScrollState>().apply {
            initialScrollOffsets.forEach { (key, offset) -> put(key, ScrollState(offset)) }
        }

    fun lazyListState(key: String): LazyListState = lazyStates.getOrPut(key) { LazyListState() }

    fun scrollState(key: String): ScrollState = scrollStates.getOrPut(key) { ScrollState(0) }

    companion object {
        val Saver: Saver<MainScrollStateHolder, Bundle> =
            Saver(
                save = { holder ->
                    Bundle().apply {
                        val lazyKeys = ArrayList<String>(holder.lazyStates.size)
                        val lazyIndices = IntArray(holder.lazyStates.size)
                        val lazyOffsets = IntArray(holder.lazyStates.size)
                        var idx = 0
                        holder.lazyStates.forEach { (key, state) ->
                            lazyKeys.add(key)
                            lazyIndices[idx] = state.firstVisibleItemIndex
                            lazyOffsets[idx] = state.firstVisibleItemScrollOffset
                            idx++
                        }
                        putStringArrayList("lazy_keys", lazyKeys)
                        putIntArray("lazy_indices", lazyIndices)
                        putIntArray("lazy_offsets", lazyOffsets)

                        val scrollKeys = ArrayList<String>(holder.scrollStates.size)
                        val scrollValues = IntArray(holder.scrollStates.size)
                        var sIdx = 0
                        holder.scrollStates.forEach { (key, state) ->
                            scrollKeys.add(key)
                            scrollValues[sIdx] = state.value
                            sIdx++
                        }
                        putStringArrayList("scroll_keys", scrollKeys)
                        putIntArray("scroll_values", scrollValues)
                    }
                },
                restore = { bundle ->
                    val lazyKeys = bundle.getStringArrayList("lazy_keys").orEmpty()
                    val lazyIndices = bundle.getIntArray("lazy_indices") ?: IntArray(0)
                    val lazyOffsets = bundle.getIntArray("lazy_offsets") ?: IntArray(0)
                    val lazyMap = buildMap {
                        for (i in lazyKeys.indices) {
                            put(
                                lazyKeys[i],
                                lazyIndices.getOrElse(i) { 0 } to lazyOffsets.getOrElse(i) { 0 },
                            )
                        }
                    }
                    val scrollKeys = bundle.getStringArrayList("scroll_keys").orEmpty()
                    val scrollValues = bundle.getIntArray("scroll_values") ?: IntArray(0)
                    val scrollMap = buildMap {
                        for (i in scrollKeys.indices) {
                            put(scrollKeys[i], scrollValues.getOrElse(i) { 0 })
                        }
                    }
                    MainScrollStateHolder(lazyMap, scrollMap)
                },
            )
    }
}

private const val SNACKBAR_AUTO_DISMISS_MS = 4_000L
