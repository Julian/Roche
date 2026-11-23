@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.boards.BoardAdapterType
import com.grayvines.roche.data.boards.BoardItemView
import com.grayvines.roche.data.boards.BoardSummary
import com.grayvines.roche.data.boards.HackerNewsSection
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.ui.components.BottomTabIcon
import com.grayvines.roche.ui.components.CommentBubbleIcon
import com.grayvines.roche.ui.components.DismissIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.FilterFunnelIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.MoreDotsIcon
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheMetaPill
import com.grayvines.roche.ui.components.RocheRefreshableTabScaffold
import com.grayvines.roche.ui.components.RocheStripTab
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.components.UnpinnedTabBackButton
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import kotlinx.coroutines.delay

private const val BOARD_FRESHNESS_TICK_MS = 60_000L

@Composable
fun BoardsTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val activeBoard = state.activeBoard
    val stream = rememberActiveBoardStream(state, activeBoard)
    val freshnessTick = rememberBoardFreshnessTick(activeBoard?.id)
    var showBoardSearchBar by rememberSaveable(activeBoard?.id) { mutableStateOf(false) }
    val isSearchBarOpen = showBoardSearchBar || state.boardFilterQuery.isNotBlank()
    val isRefreshing =
        state.isManualRefreshing ||
            activeBoard != null && activeBoard.id in state.refreshingBoardIds

    RocheRefreshableTabScaffold(
        isRefreshing = isRefreshing,
        onRefresh = { triggerBoardTabRefresh(activeBoard, onAction) },
        pullToRefreshTestTag = "boards_pull_to_refresh",
        header = {
            BoardsTopHeaderBar(
                activeBoard = activeBoard,
                activeStoryCount = stream.activeCount,
                showBackToHome = state.isUnpinnedDestination(AppTab.BOARDS),
                onBackToHome = { onAction(MainAction.SelectTab(AppTab.HOME)) },
                onOpenSearch = { onAction(MainAction.OpenSearch) },
            )
        },
        subHeader =
            activeBoard?.let { board ->
                {
                    BoardsSubHeaderSection(
                        boards = state.boards,
                        activeBoard = board,
                        stream = stream,
                        streamFilter = state.boardStreamFilter,
                        filterQuery = state.boardFilterQuery,
                        freshnessTick = freshnessTick,
                        isSearchBarOpen = isSearchBarOpen,
                        onToggleSearchBar = {
                            showBoardSearchBar = !isSearchBarOpen
                            if (isSearchBarOpen && state.boardFilterQuery.isNotBlank()) {
                                onAction(MainAction.UpdateBoardFilterQuery(""))
                            }
                        },
                        onAction = onAction,
                    )
                }
            },
    ) {
        BoardsScreenBody(
            state = state,
            activeBoard = activeBoard,
            stream = stream,
            listState = listState,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

@Composable
private fun rememberBoardFreshnessTick(boardId: String?): Int {
    var tick by remember(boardId) { mutableIntStateOf(0) }
    LaunchedEffect(boardId) {
        if (boardId != null) {
            while (true) {
                delay(BOARD_FRESHNESS_TICK_MS)
                tick++
            }
        }
    }
    return tick
}

@Composable
private fun rememberActiveBoardStream(
    state: MainUiState,
    activeBoard: BoardSummary?,
): VisibleBoardStream {
    val boardId = activeBoard?.id
    val bufferedItems =
        remember(state.boardItemsByBoardId, boardId) {
            boardId?.let { state.boardItemsByBoardId[it] }.orEmpty()
        }
    val pagination = boardId?.let { state.boardPaginationByBoardId[it] } ?: BoardPaginationState()
    return remember(bufferedItems, pagination, state.boardStreamFilter, state.boardFilterQuery) {
        computeVisibleBoardStream(
            bufferedItems = bufferedItems,
            pagination = pagination,
            filter = state.boardStreamFilter,
            filterQuery = state.boardFilterQuery,
        )
    }
}

private fun triggerBoardTabRefresh(
    activeBoard: BoardSummary?,
    onAction: (MainAction) -> Unit,
) {
    val action =
        if (activeBoard != null) {
            MainAction.RefreshBoard(activeBoard.id)
        } else {
            MainAction.RefreshAllBoards
        }
    onAction(action)
}

@Composable
private fun BoardsTopHeaderBar(
    activeBoard: BoardSummary?,
    activeStoryCount: Int,
    showBackToHome: Boolean,
    onBackToHome: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val badgeText = activeBoard?.let { activeStoryCount.toString() }
    RocheTopHeader(
        title = "Boards",
        badgeText = badgeText,
        navigationAction =
            if (showBackToHome) {
                { UnpinnedTabBackButton(onBack = onBackToHome) }
            } else {
                null
            },
    ) {
        RocheIconActionButton(
            contentDescription = "Search",
            outlined = true,
            onClick = onOpenSearch,
            modifier = Modifier.testTag("boards_search_btn"),
        ) {
            SearchIcon(tint = colors.textSecondary, size = 17.dp)
        }
    }
}

@Composable
private fun BoardsSubHeaderSection(
    boards: List<BoardSummary>,
    activeBoard: BoardSummary,
    stream: VisibleBoardStream,
    streamFilter: BoardStreamFilter,
    filterQuery: String,
    freshnessTick: Int,
    isSearchBarOpen: Boolean,
    onToggleSearchBar: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        BoardSelectorStrip(
            boards = boards,
            activeBoardId = activeBoard.id,
            onSelectBoard = { onAction(MainAction.SelectBoard(it)) },
            onAddBoard = { onAction(MainAction.OpenAddBoardSheet) },
            onOpenSettings = { onAction(MainAction.OpenEditBoardSheet(activeBoard.id)) },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        BoardStreamControlBar(
            activeBoard = activeBoard,
            stream = stream,
            streamFilter = streamFilter,
            freshnessTick = freshnessTick,
            isSearchActive = isSearchBarOpen,
            onSelectFilter = { onAction(MainAction.SelectBoardStreamFilter(it)) },
            onToggleSearch = onToggleSearchBar,
        )
        if (isSearchBarOpen) {
            BoardInlineFilterBar(
                query = filterQuery,
                onQueryChange = { onAction(MainAction.UpdateBoardFilterQuery(it)) },
                onClear = { onAction(MainAction.UpdateBoardFilterQuery("")) },
            )
        }
    }
}

@Composable
private fun BoardSelectorStrip(
    boards: List<BoardSummary>,
    activeBoardId: String,
    onSelectBoard: (String) -> Unit,
    onAddBoard: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            boards.forEach { board ->
                val prefix =
                    when {
                        board.isPaused -> "⏸ "
                        board.hasPollError -> "⚠ "
                        else -> ""
                    }
                FilterChipPill(
                    label = "$prefix${board.title}",
                    selected = board.id == activeBoardId,
                    onClick = { onSelectBoard(board.id) },
                    modifier = Modifier.testTag("board_chip_${board.id}"),
                )
            }
            FilterChipPill(
                label = "+ Add",
                selected = false,
                onClick = onAddBoard,
                role = Role.Button,
                modifier = Modifier.testTag("board_selector_add_chip"),
            )
        }
        Box(
            modifier =
                Modifier.padding(horizontal = 4.dp)
                    .width(1.dp)
                    .height(22.dp)
                    .background(colors.border)
        )
        RocheIconActionButton(
            contentDescription = "Board settings",
            outlined = false,
            onClick = onOpenSettings,
            modifier = Modifier.testTag("board_settings_btn"),
        ) {
            BottomTabIcon(tab = AppTab.SETTINGS, tint = colors.textSecondary, size = 17.dp)
        }
    }
}

@Composable
private fun BoardStreamControlBar(
    activeBoard: BoardSummary,
    stream: VisibleBoardStream,
    streamFilter: BoardStreamFilter,
    freshnessTick: Int,
    isSearchActive: Boolean,
    onSelectFilter: (BoardStreamFilter) -> Unit,
    onToggleSearch: () -> Unit,
) {
    val dismissedLabel =
        if (stream.dismissedCount > 0) "Dismissed (${stream.dismissedCount})" else "Dismissed"
    Row(
        modifier =
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RocheStripTab(
                label = "Active",
                selected = streamFilter == BoardStreamFilter.ACTIVE,
                onClick = { onSelectFilter(BoardStreamFilter.ACTIVE) },
                modifier = Modifier.testTag("board_filter_active"),
            )
            RocheStripTab(
                label = dismissedLabel,
                selected = streamFilter == BoardStreamFilter.DISMISSED,
                onClick = { onSelectFilter(BoardStreamFilter.DISMISSED) },
                modifier = Modifier.testTag("board_filter_dismissed"),
            )
        }
        BoardStreamFreshnessAction(
            activeBoard = activeBoard,
            freshnessTick = freshnessTick,
            isSearchActive = isSearchActive,
            onToggleSearch = onToggleSearch,
        )
    }
}

@Composable
private fun BoardStreamFreshnessAction(
    activeBoard: BoardSummary,
    freshnessTick: Int,
    isSearchActive: Boolean,
    onToggleSearch: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val freshnessLabel =
        remember(
            activeBoard.isPaused,
            activeBoard.snapshotFetchedAt,
            activeBoard.lastPolledAt,
            freshnessTick,
        ) {
            formatBoardFreshnessLabel(activeBoard)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = freshnessLabel,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RocheIconActionButton(
            contentDescription =
                if (isSearchActive) "Close story filter" else "Filter loaded stories",
            outlined = false,
            onClick = onToggleSearch,
            modifier = Modifier.testTag("board_inline_filter_toggle"),
        ) {
            FilterFunnelIcon(
                tint = if (isSearchActive) colors.accentBlue else colors.textSecondary,
                size = 15.dp,
            )
        }
    }
}

@Composable
private fun BoardInlineFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Filter loaded stories…", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("board_filter_input"),
        )
        if (query.isNotEmpty()) {
            HeaderActionButton(
                label = "Clear",
                onClick = onClear,
                modifier = Modifier.testTag("board_filter_clear_btn"),
            )
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
private fun BoardsScreenBody(
    state: MainUiState,
    activeBoard: BoardSummary?,
    stream: VisibleBoardStream,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    if (state.isInitialLoading && state.boards.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp).testTag("boards_loading_state"),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Loading boards…", color = colors.textMuted, fontSize = 14.sp)
        }
        return
    }
    if (activeBoard == null) {
        BoardsOnboardingEmptyState(
            creatingBoardKeys = state.creatingBoardKeys,
            onAction = onAction,
        )
        return
    }
    BoardsStoryLazyColumn(
        state = state,
        activeBoard = activeBoard,
        stream = stream,
        listState = listState,
        onAction = onAction,
        onOpenExternalUrl = onOpenExternalUrl,
    )
}

@Composable
private fun BoardsStoryLazyColumn(
    state: MainUiState,
    activeBoard: BoardSummary,
    stream: VisibleBoardStream,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val isUnfilteredActive =
        state.boardFilterQuery.isBlank() && state.boardStreamFilter == BoardStreamFilter.ACTIVE
    val isInitialBoardLoad =
        stream.items.isEmpty() && isUnfilteredActive && activeBoard.id in state.refreshingBoardIds
    val swipeEnabled =
        state.settings.swipeToArchive && state.boardStreamFilter == BoardStreamFilter.ACTIVE
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("boards_story_list"),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        boardStatusBannerItems(activeBoard = activeBoard, onAction = onAction)
        when {
            isInitialBoardLoad ->
                item(key = "board_initial_loading") {
                    BoardInitialLoadingCard(boardTitle = activeBoard.title)
                }
            stream.items.isEmpty() ->
                item(key = "board_stream_empty") {
                    BoardStreamEmptyCard(
                        board = activeBoard,
                        streamFilter = state.boardStreamFilter,
                        filterQuery = state.boardFilterQuery,
                        dismissedCount = stream.dismissedCount,
                        onAction = onAction,
                    )
                }
            else ->
                boardStoryListItems(
                    boardId = activeBoard.id,
                    items = stream.items,
                    swipeEnabled = swipeEnabled,
                    ultraDense = state.settings.ultraDenseRows,
                    dividerColor = colors.border,
                    onAction = onAction,
                    onOpenExternalUrl = onOpenExternalUrl,
                )
        }
        if (isUnfilteredActive && !isInitialBoardLoad) {
            boardLoadMoreFooterItem(
                board = activeBoard,
                stream = stream,
                onLoadMore = { onAction(MainAction.LoadMoreBoardItems(activeBoard.id)) },
            )
        }
    }
}

@Suppress("LongParameterList")
private fun LazyListScope.boardStoryListItems(
    boardId: String,
    items: List<BoardItemView>,
    swipeEnabled: Boolean,
    ultraDense: Boolean,
    dividerColor: Color,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    items(items = items, key = { item -> "${item.boardId}:${item.externalId}" }) { item ->
        BoardStoryRowItem(
            item = item,
            swipeToDismissEnabled = swipeEnabled,
            ultraDense = ultraDense,
            onOpenReader = {
                onAction(MainAction.OpenBoardReader(boardId, item.externalId, item))
            },
            onOpenComments = onOpenExternalUrl,
            onToggleStar = {
                onAction(MainAction.ToggleBoardItemStar(boardId, item.externalId, item))
            },
            onToggleQueue = {
                onAction(MainAction.ToggleBoardItemQueue(boardId, item.externalId, item))
            },
            onToggleDismiss = { dispatchBoardDismissToggle(boardId, item, onAction) },
            onOpenContextSheet = {
                onAction(MainAction.OpenBoardStoryContextSheet(boardId, item.externalId, item))
            },
        )
        HorizontalDivider(color = dividerColor, thickness = 1.dp)
    }
}

private fun dispatchBoardDismissToggle(
    boardId: String,
    item: BoardItemView,
    onAction: (MainAction) -> Unit,
) {
    val action =
        if (item.isDismissed) {
            MainAction.UndismissBoardItem(boardId, item.externalId)
        } else {
            MainAction.DismissBoardItem(boardId, item.externalId, item.title)
        }
    onAction(action)
}

private fun LazyListScope.boardStatusBannerItems(
    activeBoard: BoardSummary,
    onAction: (MainAction) -> Unit,
) {
    if (activeBoard.isPaused) {
        item(key = "board_paused_banner") {
            BoardPausedBanner(
                board = activeBoard,
                onResume = { onAction(MainAction.UpdateBoard(activeBoard.id, isActive = true)) },
            )
        }
    }
    val err = activeBoard.lastError?.trim()?.takeIf { it.isNotEmpty() }
    if (err != null) {
        item(key = "board_error_banner") {
            BoardErrorBanner(
                errorMessage = err,
                onRetry = { onAction(MainAction.RefreshBoard(activeBoard.id)) },
            )
        }
    }
}

@Composable
private fun BoardPausedBanner(
    board: BoardSummary,
    onResume: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        RocheSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${board.title} is paused",
                        color = colors.textPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Automatic polling is turned off.",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                    )
                }
                HeaderActionButton(
                    label = "Resume",
                    onClick = onResume,
                    modifier = Modifier.testTag("board_paused_resume_btn"),
                )
            }
        }
    }
}

@Composable
private fun BoardErrorBanner(
    errorMessage: String,
    onRetry: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        RocheSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Last refresh had an error",
                        color = colors.accentIndianRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = errorMessage,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HeaderActionButton(
                    label = "Retry",
                    onClick = onRetry,
                    modifier = Modifier.testTag("board_error_retry_btn"),
                )
            }
        }
    }
}

@Composable
private fun BoardsOnboardingEmptyState(
    creatingBoardKeys: Set<String>,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .testTag("boards_empty_state"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RocheSurfaceCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Live Front Pages",
                    color = colors.textPrimary,
                    fontFamily = headlineFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text =
                        "Browse ranked boards like Hacker News without cluttering your Feed Inbox. Stories stay ephemeral until you star, queue, or finish reading them.",
                    color = colors.textSecondary,
                    fontSize = 13.5.sp,
                    lineHeight = 19.5.sp,
                )
                BoardsQuickAddPresetsBlock(
                    creatingBoardKeys = creatingBoardKeys,
                    onAction = onAction,
                )
                HeaderActionButton(
                    label = "+ Add Board",
                    style = RocheButtonStyle.PRIMARY,
                    onClick = { onAction(MainAction.OpenAddBoardSheet) },
                    modifier = Modifier.fillMaxWidth().testTag("boards_empty_add_btn"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardsQuickAddPresetsBlock(
    creatingBoardKeys: Set<String>,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "QUICK-ADD HACKER NEWS",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            letterSpacing = 0.6.sp,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QUICK_ADD_HN_PRESETS.forEach { (section, label) ->
                val key = boardCreationKey(BoardAdapterType.HACKER_NEWS, section.sourceKey)
                val isCreating = key in creatingBoardKeys
                BoardQuickAddPresetChip(
                    label = label,
                    isCreating = isCreating,
                    onClick = {
                        if (!isCreating) {
                            onAction(
                                MainAction.CreateBoard(
                                    adapterType = BoardAdapterType.HACKER_NEWS,
                                    sectionOrUrl = section.sourceKey,
                                )
                            )
                        }
                    },
                    modifier = Modifier.testTag("board_quick_add_${section.sourceKey}"),
                )
            }
        }
    }
}

@Composable
private fun BoardQuickAddPresetChip(
    label: String,
    isCreating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val bg = if (isCreating) colors.accentBlue.copy(alpha = 0.14f) else colors.surface
    val border = if (isCreating) colors.accentBlue else colors.border
    val textColor = if (isCreating) colors.accentBlue else colors.textSecondary
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(enabled = !isCreating, onClick = onClick)
                .semantics { role = Role.Button },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier.heightIn(min = 32.dp)
                    .clip(shape)
                    .background(bg)
                    .border(1.dp, border, shape)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
        ) {
            if (isCreating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.8.dp,
                    color = colors.accentBlue,
                )
            }
            Text(
                text = label,
                color = textColor,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BoardInitialLoadingCard(boardTitle: String) {
    val colors = LocalRocheColors.current
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("board_initial_loading_card")) {
        RocheSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = colors.accentBlue,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Loading $boardTitle…",
                        color = colors.textPrimary,
                        fontFamily = currentHeadlineFontFamily(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Fetching the latest front page stories.",
                        color = colors.textSecondary,
                        fontSize = 12.5.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardStreamEmptyCard(
    board: BoardSummary,
    streamFilter: BoardStreamFilter,
    filterQuery: String,
    dismissedCount: Int,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val (title, subtitle) = resolveBoardEmptyCopy(board, streamFilter, filterQuery, dismissedCount)
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        RocheSurfaceCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontFamily = currentHeadlineFontFamily(),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.5.sp,
                )
                BoardStreamEmptyActionButton(
                    board = board,
                    streamFilter = streamFilter,
                    filterQuery = filterQuery,
                    dismissedCount = dismissedCount,
                    onAction = onAction,
                )
            }
        }
    }
}

internal fun resolveBoardEmptyCopy(
    board: BoardSummary,
    streamFilter: BoardStreamFilter,
    filterQuery: String,
    dismissedCount: Int = 0,
): Pair<String, String> {
    val noun = if (dismissedCount == 1) "story" else "stories"
    return when {
        filterQuery.isNotBlank() ->
            "No matching stories" to "Nothing in ${board.title} matches \"${filterQuery.trim()}\"."
        streamFilter == BoardStreamFilter.DISMISSED ->
            "No dismissed stories" to
                "Stories you dismiss from ${board.title} appear here so you can restore them."
        dismissedCount > 0 ->
            "All caught up" to
                "You've dismissed all $dismissedCount loaded $noun in ${board.title}."
        else ->
            "No stories loaded yet" to
                "Pull down or tap Refresh to load the latest front page from ${board.title}."
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardStreamEmptyActionButton(
    board: BoardSummary,
    streamFilter: BoardStreamFilter,
    filterQuery: String,
    dismissedCount: Int,
    onAction: (MainAction) -> Unit,
) {
    when {
        filterQuery.isNotBlank() ->
            HeaderActionButton(
                label = "Clear Filter",
                onClick = { onAction(MainAction.UpdateBoardFilterQuery("")) },
            )
        streamFilter == BoardStreamFilter.DISMISSED ->
            HeaderActionButton(
                label = "Back to Active",
                onClick = {
                    onAction(MainAction.SelectBoardStreamFilter(BoardStreamFilter.ACTIVE))
                },
            )
        dismissedCount > 0 ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeaderActionButton(
                    label = "View Dismissed ($dismissedCount)",
                    onClick = {
                        onAction(MainAction.SelectBoardStreamFilter(BoardStreamFilter.DISMISSED))
                    },
                    modifier = Modifier.testTag("board_empty_view_dismissed_btn"),
                )
                HeaderActionButton(
                    label = "Refresh",
                    onClick = { onAction(MainAction.RefreshBoard(board.id)) },
                    modifier = Modifier.testTag("board_empty_refresh_btn"),
                )
            }
        else ->
            HeaderActionButton(
                label = "Refresh ${board.title}",
                style = RocheButtonStyle.PRIMARY,
                onClick = { onAction(MainAction.RefreshBoard(board.id)) },
                modifier = Modifier.testTag("board_empty_refresh_btn"),
            )
    }
}

@Composable
private fun BoardStoryRowItem(
    item: BoardItemView,
    swipeToDismissEnabled: Boolean,
    ultraDense: Boolean,
    onOpenReader: () -> Unit,
    onOpenComments: (String) -> Unit,
    onToggleStar: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleDismiss: () -> Unit,
    onOpenContextSheet: () -> Unit,
) {
    val rowContent: @Composable () -> Unit = {
        BoardStoryRowSurface(
            item = item,
            ultraDense = ultraDense,
            onOpenReader = onOpenReader,
            onOpenComments = onOpenComments,
            onToggleStar = onToggleStar,
            onToggleQueue = onToggleQueue,
            onToggleDismiss = onToggleDismiss,
            onOpenContextSheet = onOpenContextSheet,
        )
    }
    if (!swipeToDismissEnabled) {
        rowContent()
        return
    }
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue, item.isDismissed) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onToggleDismiss()
            }
        },
        backgroundContent = { BoardSwipeDismissBackground() },
        content = { rowContent() },
    )
}

@Composable
private fun BoardSwipeDismissBackground() {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxSize()
                .background(colors.accentVerdigris.copy(alpha = 0.22f))
                .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Dismiss",
            color = colors.accentVerdigris,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
        )
        DismissIcon(tint = colors.accentVerdigris, isDismissed = false)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardStoryRowSurface(
    item: BoardItemView,
    ultraDense: Boolean,
    onOpenReader: () -> Unit,
    onOpenComments: (String) -> Unit,
    onToggleStar: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleDismiss: () -> Unit,
    onOpenContextSheet: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val topPadding = if (ultraDense) 7.dp else 10.dp

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.background)
                .combinedClickable(
                    onClick = onOpenReader,
                    onLongClick = onOpenContextSheet,
                    onLongClickLabel = "More story actions",
                )
                .padding(start = 12.dp, end = 8.dp, top = topPadding, bottom = 1.dp)
                .testTag("board_story_row_${item.externalId}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoardStoryRankGutter(rank = item.rank, isUnseen = !item.isSeen && !item.isFinished)
            BoardStoryTitleBlock(
                item = item,
                ultraDense = ultraDense,
                modifier = Modifier.weight(1f),
            )
        }
        BoardStoryActionsBar(
            item = item,
            onOpenComments = onOpenComments,
            onToggleQueue = onToggleQueue,
            onToggleStar = onToggleStar,
            onToggleDismiss = onToggleDismiss,
            onOpenContextSheet = onOpenContextSheet,
        )
    }
}

@Composable
private fun BoardStoryTitleBlock(
    item: BoardItemView,
    ultraDense: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val metadataText = remember(item) { formatBoardStoryMetadata(item) }
    val titleSpacing = if (ultraDense) 3.dp else 4.dp

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(titleSpacing)) {
        BoardStoryHeadlineText(
            title = item.title,
            isDimmed = item.isSeen || item.isFinished,
            ultraDense = ultraDense,
        )
        BoardStoryStatusBadgesRow(item = item)
        if (metadataText.isNotBlank()) {
            Text(
                text = metadataText,
                color = colors.textMuted,
                fontSize = 11.5.sp,
                lineHeight = 15.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BoardStoryHeadlineText(title: String, isDimmed: Boolean, ultraDense: Boolean) {
    val colors = LocalRocheColors.current
    Text(
        text = title,
        color = if (isDimmed) colors.textSecondary else colors.textPrimary,
        fontFamily = currentHeadlineFontFamily(),
        fontSize = if (ultraDense) 14.5.sp else 15.5.sp,
        lineHeight = if (ultraDense) 19.sp else 20.sp,
        fontWeight = if (isDimmed) FontWeight.Normal else FontWeight.Medium,
        maxLines = if (ultraDense) 2 else 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun BoardStoryRankGutter(
    rank: Int,
    isUnseen: Boolean,
) {
    val colors = LocalRocheColors.current
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.widthIn(min = 32.dp).padding(top = 2.dp),
    ) {
        Text(
            text = rank.toString(),
            color = if (isUnseen) colors.textSecondary else colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (isUnseen) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        if (isUnseen) {
            Box(
                modifier =
                    Modifier.size(5.dp)
                        .clip(CircleShape)
                        .background(colors.accentBlue)
                        .clearAndSetSemantics {}
            )
        }
    }
}

@Composable
private fun BoardStoryStatusBadgesRow(item: BoardItemView) {
    if (!item.isFinished) return
    val colors = LocalRocheColors.current
    RocheMetaPill(
        text = "✓ Finished",
        textColor = colors.accentVerdigris,
        modifier = Modifier.testTag("board_badge_finished_${item.externalId}"),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardStoryActionsBar(
    item: BoardItemView,
    onOpenComments: (String) -> Unit,
    onToggleQueue: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleDismiss: () -> Unit,
    onOpenContextSheet: () -> Unit,
) {
    val discussionUrl = item.discussionUrl?.takeIf { it.isNotBlank() }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (discussionUrl != null) {
            BoardCommentsChip(
                commentCount = item.commentCount,
                onClick = { onOpenComments(discussionUrl) },
                modifier = Modifier.testTag("board_comments_${item.externalId}"),
            )
        } else {
            Spacer(modifier = Modifier.width(1.dp))
        }
        BoardStoryQuickActionButtons(
            item = item,
            onToggleQueue = onToggleQueue,
            onToggleStar = onToggleStar,
            onToggleDismiss = onToggleDismiss,
            onOpenContextSheet = onOpenContextSheet,
        )
    }
}

@Composable
private fun BoardStoryQuickActionButtons(
    item: BoardItemView,
    onToggleQueue: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleDismiss: () -> Unit,
    onOpenContextSheet: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        BoardQueueQuickButton(
            isInQueue = item.isInQueue,
            onClick = onToggleQueue,
            modifier = Modifier.testTag("board_queue_${item.externalId}"),
        )
        RocheIconActionButton(
            contentDescription = if (item.isStarred) "Remove star" else "Star story",
            outlined = false,
            onClick = onToggleStar,
            modifier = Modifier.testTag("board_star_${item.externalId}"),
        ) {
            StarIcon(
                tint = if (item.isStarred) colors.accentEcru else colors.textSecondary,
                isStarred = item.isStarred,
                size = 16.dp,
            )
        }
        if (item.isDismissed) {
            BoardRestoreQuickButton(
                onClick = onToggleDismiss,
                modifier = Modifier.testTag("board_dismiss_${item.externalId}"),
            )
        } else {
            RocheIconActionButton(
                contentDescription = "Dismiss story",
                outlined = false,
                onClick = onToggleDismiss,
                modifier = Modifier.testTag("board_dismiss_${item.externalId}"),
            ) {
                DismissIcon(
                    tint = colors.textSecondary,
                    isDismissed = false,
                    size = 16.dp,
                )
            }
        }
        RocheIconActionButton(
            contentDescription = "More story actions",
            outlined = false,
            onClick = onOpenContextSheet,
            modifier = Modifier.testTag("board_more_${item.externalId}"),
        ) {
            MoreDotsIcon(tint = colors.textSecondary, size = 16.dp)
        }
    }
}

@Composable
private fun BoardCommentsChip(
    commentCount: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(7.dp)
    val compactCount = formatBoardCompactComments(commentCount)
    val a11yDesc =
        if (commentCount != null && commentCount > 0) {
            "Open discussion, $commentCount comments"
        } else {
            "Open discussion"
        }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = a11yDesc
                },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier.heightIn(min = 28.dp)
                    .clip(shape)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.border, shape)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clearAndSetSemantics {},
        ) {
            CommentBubbleIcon(tint = colors.accentEcru, size = 13.dp)
            if (compactCount != null) {
                Text(
                    text = compactCount,
                    color = colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BoardQueueQuickButton(
    isInQueue: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(7.dp)
    val bg = if (isInQueue) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent
    val border = if (isInQueue) colors.accentBlue.copy(alpha = 0.5f) else colors.border
    val fg = if (isInQueue) colors.accentBlue else colors.textSecondary
    val label = if (isInQueue) "✓ Queue" else "+ Queue"
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = if (isInQueue) "Remove from Queue" else "Add to Queue"
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 28.dp)
                    .clip(shape)
                    .background(bg)
                    .border(1.dp, border, shape)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clearAndSetSemantics {},
        ) {
            Text(
                text = label,
                color = fg,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BoardRestoreQuickButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(7.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Restore story"
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 28.dp)
                    .clip(shape)
                    .background(colors.accentBlue.copy(alpha = 0.12f))
                    .border(1.dp, colors.accentBlue.copy(alpha = 0.45f), shape)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clearAndSetSemantics {},
        ) {
            Text(
                text = "Restore",
                color = colors.accentBlue,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

private fun LazyListScope.boardLoadMoreFooterItem(
    board: BoardSummary,
    stream: VisibleBoardStream,
    onLoadMore: () -> Unit,
) {
    val showFooter = stream.hasMoreLocalBuffer || stream.canLoadMoreLive || stream.isLoadingMore
    if (!showFooter) return
    item(key = "board_load_more_footer") {
        BoardLoadMoreFooter(board = board, stream = stream, onLoadMore = onLoadMore)
    }
}

@Composable
private fun BoardLoadMoreFooter(
    board: BoardSummary,
    stream: VisibleBoardStream,
    onLoadMore: () -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .testTag("board_load_more_footer"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (stream.isLoadingMore) {
            BoardLoadMoreSpinnerRow()
        } else {
            BoardLoadMoreControls(board = board, stream = stream, onLoadMore = onLoadMore)
        }
    }
}

@Composable
private fun BoardLoadMoreSpinnerRow() {
    val colors = LocalRocheColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = colors.accentBlue,
        )
        Text(
            text = "Loading more stories…",
            color = colors.textSecondary,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun BoardLoadMoreControls(
    board: BoardSummary,
    stream: VisibleBoardStream,
    onLoadMore: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val err = stream.loadMoreError
    if (!err.isNullOrBlank()) {
        Text(text = err, color = colors.accentIndianRed, fontSize = 12.sp)
    }
    val buttonLabel =
        when {
            !err.isNullOrBlank() -> "Retry Load More"
            stream.hasMoreLocalBuffer -> "Load More Stories"
            else -> "Load More from ${board.adapterType.displayLabel}"
        }
    HeaderActionButton(
        label = buttonLabel,
        onClick = onLoadMore,
        modifier = Modifier.testTag("board_load_more_btn"),
    )
    val countCaption =
        when {
            stream.hasMoreLocalBuffer ->
                "Showing ${stream.items.size} of ${stream.activeCount} cached"
            stream.items.isEmpty() -> "Fetch next 30 stories live"
            else -> "Showing ${stream.items.size} stories · fetches next 30 live"
        }
    Text(
        text = countCaption,
        color = colors.textMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
}

private val QUICK_ADD_HN_PRESETS: List<Pair<HackerNewsSection, String>> =
    listOf(
        HackerNewsSection.TOP to "+ Hacker News",
        HackerNewsSection.BEST to "+ HN Best",
        HackerNewsSection.ASK to "+ Ask HN",
        HackerNewsSection.SHOW to "+ Show HN",
    )
