@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.data.catalog.CatalogListSummary
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.CollectionRole
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.RankedCatalogEntry
import com.grayvines.roche.data.catalog.generatorFor
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.ui.components.CatalogCoverThumbnail
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheAlertDialog
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheRefreshableTabScaffold
import com.grayvines.roche.ui.components.RocheStripTab
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.SkipDrawIcon
import com.grayvines.roche.ui.components.TierBadgePill
import com.grayvines.roche.ui.components.TopHeaderCountPill
import com.grayvines.roche.ui.components.UnpinnedTabBackButton
import com.grayvines.roche.ui.components.lazyGroupedCardRow
import com.grayvines.roche.ui.components.resolveCatalogEntryOpenAction
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.sqrt

@Composable
fun MediaCompanionScreen(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    overviewScrollState: ScrollState = rememberScrollState(),
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit = {},
    onOpenExternalUrl: (String) -> Unit = {},
) {
    var showCreateListDialog by rememberSaveable { mutableStateOf(false) }
    val lists = state.catalog.lists
    val activeList =
        remember(lists, state.selectedCatalogCollectionId) {
            state.selectedCatalogCollectionId?.let { id ->
                lists.firstOrNull { it.collectionId == id }
            }
        }
    val activeCollection = resolveActiveCollectionState(activeList, state.activeCollection)
    val overviewCount =
        remember(lists) {
            lists.count { !it.isDynamic || it.entryCount > 0 || it.generatorEnabled }
        }
    val headerCount = formatCompactInt(activeList?.entryCount ?: overviewCount)

    BackHandler(enabled = activeList != null) { onAction(MainAction.SelectCatalogCollection(null)) }
    RocheRefreshableTabScaffold(
        isRefreshing = state.isManualRefreshing,
        onRefresh = { onAction(MainAction.ManualRefresh) },
        pullToRefreshTestTag = "lists_pull_to_refresh",
        modifier = Modifier.testTag("lists_companion_screen"),
        header = {
            ListsTopHeaderBar(
                countBadge = headerCount,
                showBackToHome = activeList == null && state.isUnpinnedDestination(AppTab.LISTS),
                onBackToHome = { onAction(MainAction.SelectTab(AppTab.HOME)) },
                onOpenSearch = { onAction(MainAction.OpenSearch) },
            )
        },
    ) {
        MediaCompanionBody(
            state = state,
            lists = lists,
            activeList = activeList,
            activeCollection = activeCollection,
            listState = listState,
            overviewScrollState = overviewScrollState,
            onAction = onAction,
            onOpenCreateList = { showCreateListDialog = true },
            onOpenCollectionDraw = onOpenCollectionDraw,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
    if (showCreateListDialog) {
        CreateCustomListDialog(
            onDismiss = { showCreateListDialog = false },
            onCreate = { title ->
                showCreateListDialog = false
                onAction(MainAction.CreateCustomCatalogList(title))
            },
        )
    }
}

private fun resolveActiveCollectionState(
    activeList: CatalogListSummary?,
    loaded: CatalogCollectionUiState,
): CatalogCollectionUiState =
    if (activeList != null && loaded.collectionId == activeList.collectionId) {
        loaded
    } else {
        CatalogCollectionUiState(collectionId = activeList?.collectionId)
    }

@Composable
@Suppress("LongParameterList")
private fun MediaCompanionBody(
    state: MainUiState,
    lists: List<CatalogListSummary>,
    activeList: CatalogListSummary?,
    activeCollection: CatalogCollectionUiState,
    listState: LazyListState,
    overviewScrollState: ScrollState,
    onAction: (MainAction) -> Unit,
    onOpenCreateList: () -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    if (activeList == null) {
        ListsOverviewPane(
            lists = lists,
            overviewScrollState = overviewScrollState,
            onOpenList = { onAction(MainAction.SelectCatalogCollection(it)) },
            onRankList = { onAction(MainAction.StartRankingSession(it)) },
            onOpenCreateList = onOpenCreateList,
        )
    } else {
        RankedListDetailPane(
            state = state,
            activeList = activeList,
            collectionState = activeCollection,
            listState = listState,
            onAction = onAction,
            onOpenCollectionDraw = onOpenCollectionDraw,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

@Composable
private fun ListsTopHeaderBar(
    countBadge: String,
    showBackToHome: Boolean,
    onBackToHome: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheTopHeader(
        title = "Lists",
        badgeText = countBadge,
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
            modifier = Modifier.testTag("lists_search_btn"),
        ) {
            SearchIcon(tint = colors.textSecondary, size = 17.dp)
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
private fun ListsOverviewPane(
    lists: List<CatalogListSummary>,
    overviewScrollState: ScrollState,
    onOpenList: (String) -> Unit,
    onRankList: (String) -> Unit,
    onOpenCreateList: () -> Unit,
) {
    val manualLists =
        remember(lists) {
            lists
                .filter { !it.isDynamic }
                .sortedWith(
                    compareByDescending<CatalogListSummary> { it.generatorEnabled }
                        .thenByDescending { it.entryCount }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
        }
    val activeDynamic =
        remember(lists) {
            lists
                .filter { it.isDynamic && it.entryCount > 0 }
                .sortedWith(
                    compareByDescending<CatalogListSummary> { it.entryCount }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
        }
    val emptyDynamic =
        remember(lists) {
            lists
                .filter { it.isDynamic && it.entryCount == 0 }
                .sortedBy { it.title.lowercase(Locale.US) }
        }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= LISTS_WIDE_BREAKPOINT
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(overviewScrollState)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            YourListsOverviewSection(
                manualLists = manualLists,
                isWide = isWide,
                onOpenList = onOpenList,
                onRankList = onRankList,
                onOpenCreateList = onOpenCreateList,
            )
            if (activeDynamic.isNotEmpty() || emptyDynamic.isNotEmpty()) {
                MediaTypeOverviewSection(
                    activeDynamic = activeDynamic,
                    emptyDynamic = emptyDynamic,
                    isWide = isWide,
                    onOpenList = onOpenList,
                    onRankList = onRankList,
                )
            }
            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}

@Composable
private fun YourListsOverviewSection(
    manualLists: List<CatalogListSummary>,
    isWide: Boolean,
    onOpenList: (String) -> Unit,
    onRankList: (String) -> Unit,
    onOpenCreateList: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "YOUR LISTS",
                color = colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                modifier = Modifier.semantics { heading() },
            )
            TextButton(
                onClick = onOpenCreateList,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.testTag("companion_new_list_btn"),
            ) {
                Text(
                    text = "+ New List",
                    color = colors.accentBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
        if (manualLists.isEmpty()) {
            RocheSurfaceCard {
                Text(
                    text = "No custom lists yet. Tap + New List to start one.",
                    color = colors.textMuted,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else {
            OverviewListCardGroup(
                summaries = manualLists,
                isWide = isWide,
                onOpenList = onOpenList,
                onRankList = onRankList,
            )
        }
    }
}

@Composable
private fun MediaTypeOverviewSection(
    activeDynamic: List<CatalogListSummary>,
    emptyDynamic: List<CatalogListSummary>,
    isWide: Boolean,
    onOpenList: (String) -> Unit,
    onRankList: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "BY MEDIA TYPE",
                color = colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "${activeDynamic.size} active · ${emptyDynamic.size} empty",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
            )
        }
        if (activeDynamic.isNotEmpty()) {
            OverviewListCardGroup(
                summaries = activeDynamic,
                isWide = isWide,
                onOpenList = onOpenList,
                onRankList = onRankList,
            )
        }
        if (emptyDynamic.isNotEmpty()) {
            EmptyDynamicListsGrid(
                emptyLists = emptyDynamic,
                columns = if (isWide) 3 else 2,
                onOpenList = onOpenList,
            )
        }
    }
}

@Composable
private fun OverviewListCardGroup(
    summaries: List<CatalogListSummary>,
    isWide: Boolean,
    onOpenList: (String) -> Unit,
    onRankList: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheSurfaceCard {
        summaries.forEachIndexed { idx, summary ->
            if (idx > 0) {
                HorizontalDivider(color = colors.border, thickness = 1.dp)
            }
            ListOverviewRow(
                summary = summary,
                showThumbs = isWide,
                onOpen = { onOpenList(summary.collectionId) },
                onRank = { onRankList(summary.collectionId) },
            )
        }
    }
}

@Composable
private fun ListOverviewRow(
    summary: CatalogListSummary,
    showThumbs: Boolean,
    onOpen: () -> Unit,
    onRank: () -> Unit,
) {
    val previewThumbs = summary.previewThumbnails

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onOpen)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("list_overview_row_${summary.collectionId}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListOverviewTextColumn(
            summary = summary,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showThumbs && previewThumbs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    previewThumbs.forEach { preview ->
                        CatalogCoverThumbnail(
                            thumbnailUrl = preview.thumbnailUrl,
                            kind = preview.kind,
                            size = 28.dp,
                        )
                    }
                }
            }
            if (summary.entryCount >= 2) {
                HeaderActionButton(
                    label = "Rank",
                    onClick = onRank,
                    horizontalPadding = 10.dp,
                    modifier = Modifier.testTag("overview_rank_${summary.collectionId}"),
                )
            }
        }
    }
}

@Composable
private fun ListOverviewTextColumn(
    summary: CatalogListSummary,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val countLabel =
        if (summary.hasGenerator && summary.poolTotalCount > 0) {
            "${formatCompactInt(summary.entryCount)} / ${formatCompactInt(summary.poolTotalCount)}"
        } else {
            formatCompactInt(summary.entryCount)
        }
    val subText = resolveOverviewSubtitle(summary)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            TopHeaderCountPill(text = countLabel)
            if (summary.generatorEnabled) {
                OverviewDrawPill()
            }
        }
        if (subText.isNotBlank()) {
            Text(
                text = subText,
                color = colors.textMuted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OverviewDrawPill() {
    val colors = LocalRocheColors.current
    Box(
        modifier =
            Modifier.clip(RoundedCornerShape(999.dp))
                .background(colors.accentVerdigris.copy(alpha = 0.14f))
                .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = "Draw",
            color = colors.accentVerdigris,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

private fun resolveOverviewSubtitle(summary: CatalogListSummary): String =
    when {
        summary.generatorEnabled ->
            "Daily draw active · ${formatCompactInt(summary.poolRemainingCount)} left"
        summary.collectionId == STARRED_COLLECTION_ID -> "Saved favorites from Inbox & catalog"
        summary.entryCount == 1 -> "1 item"
        summary.entryCount > 1 -> "${formatCompactInt(summary.entryCount)} items"
        else -> ""
    }

@Composable
private fun EmptyDynamicListsGrid(
    emptyLists: List<CatalogListSummary>,
    columns: Int,
    onOpenList: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(9.dp)
    val rows = remember(emptyLists, columns) { emptyLists.chunked(columns) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chunk.forEach { item ->
                    Row(
                        modifier =
                            Modifier.weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(shape)
                                .background(colors.surface)
                                .border(1.dp, colors.border, shape)
                                .clickable { onOpenList(item.collectionId) }
                                .semantics { role = Role.Button }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.title,
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "0",
                            color = colors.textMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
                repeat(columns - chunk.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RankedListDetailPane(
    state: MainUiState,
    activeList: CatalogListSummary,
    collectionState: CatalogCollectionUiState,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val tierCounts =
        remember(collectionState.tierCounts) {
            TIER_DISPLAY_ORDER.mapNotNull { tier ->
                collectionState.tierCounts[tier]?.takeIf { it > 0 }?.let { tier to it }
            }
        }
    val canEditManualList = isEditableManualUserList(activeList)
    val activeDraw = state.catalog.generatorFor(activeList.collectionId)?.currentDraw
    val effectiveTotalCount =
        if (collectionState.isLoaded) collectionState.totalCount else activeList.entryCount

    Column(modifier = Modifier.fillMaxSize()) {
        ListDetailSubHeader(
            list = activeList,
            canRank = effectiveTotalCount >= 2,
            canEditManualList = canEditManualList,
            onBack = { onAction(MainAction.SelectCatalogCollection(null)) },
            onAction = onAction,
        )
        if (tierCounts.isNotEmpty()) {
            ListTierFilterStrip(
                totalCount = effectiveTotalCount,
                tierCounts = tierCounts,
                selectedTier = collectionState.selectedTier,
                onSelectTier = { tier ->
                    onAction(
                        MainAction.SetCatalogCollectionTierFilter(activeList.collectionId, tier)
                    )
                },
            )
        }
        RankedDetailBody(
            activeList = activeList,
            activeDraw = activeDraw,
            canEditManualList = canEditManualList,
            collectionState = collectionState,
            readableIds = state.readableEntryIds,
            listState = listState,
            onAction = onAction,
            onOpenCollectionDraw = onOpenCollectionDraw,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

@Composable
private fun ListDetailSubHeader(
    list: CatalogListSummary,
    canRank: Boolean,
    canEditManualList: Boolean,
    onBack: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    Row(
        modifier =
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RocheIconActionButton(
            contentDescription = "Back to Lists",
            outlined = false,
            onClick = onBack,
            modifier = Modifier.testTag("lists_back_to_overview_btn"),
        ) {
            Text(text = "←", color = colors.accentBlue, fontSize = 18.sp)
        }
        Text(
            text = list.title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ListDetailHeaderActions(list, canRank, canEditManualList, onAction)
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
private fun ListDetailHeaderActions(
    list: CatalogListSummary,
    canRank: Boolean,
    canEditManualList: Boolean,
    onAction: (MainAction) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canRank) {
            HeaderActionButton(
                label = "Rank",
                style = RocheButtonStyle.PRIMARY,
                horizontalPadding = 12.dp,
                onClick = { onAction(MainAction.StartRankingSession(list.collectionId)) },
                modifier = Modifier.testTag("companion_rank_list_btn"),
            )
        }
        if (canEditManualList && list.collectionId != STARRED_COLLECTION_ID) {
            ManualListOverflowMenu(list = list, onAction = onAction)
        }
    }
}

@Composable
private fun ManualListOverflowMenu(
    list: CatalogListSummary,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    var menuExpanded by rememberSaveable(list.collectionId) { mutableStateOf(false) }
    val drawLabel = if (list.generatorEnabled) "Pause Daily Draw" else "Enable Daily Draw"
    Box {
        RocheIconActionButton(
            contentDescription = "List options",
            outlined = false,
            onClick = { menuExpanded = true },
            modifier = Modifier.testTag("companion_list_more_btn"),
        ) {
            Text(
                text = "⋯",
                color = colors.textSecondary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = colors.surfaceElevated,
        ) {
            DropdownMenuItem(
                text = { Text(text = drawLabel, color = colors.textPrimary) },
                onClick = {
                    menuExpanded = false
                    onAction(
                        MainAction.SetCollectionGeneratorEnabled(
                            collectionIdOrSlug = list.collectionId,
                            enabled = !list.generatorEnabled,
                        )
                    )
                },
                modifier = Modifier.testTag("companion_toggle_draw_btn"),
            )
            DropdownMenuItem(
                text = { Text("+ Log & Add Entry", color = colors.accentBlue) },
                onClick = {
                    menuExpanded = false
                    onAction(
                        MainAction.OpenQuickLogSheet(
                            defaultToWant = false,
                            targetCollectionId = list.collectionId,
                            targetCollectionTitle = list.title,
                            prefillKind = EntryKind.inferForCollection(list),
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun ListTierFilterStrip(
    totalCount: Int,
    tierCounts: List<Pair<Char, Int>>,
    selectedTier: Char?,
    onSelectTier: (Char?) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RocheStripTab(
            label = "All (${formatCompactInt(totalCount)})",
            selected = selectedTier == null,
            onClick = { onSelectTier(null) },
            modifier = Modifier.testTag("list_tier_all"),
        )
        tierCounts.forEach { (tier, count) ->
            RocheStripTab(
                label = "$tier ($count)",
                selected = selectedTier == tier,
                onClick = { onSelectTier(tier) },
                modifier = Modifier.testTag("list_tier_${tier.lowercaseChar()}"),
            )
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Suppress("LongParameterList")
@Composable
private fun RankedDetailBody(
    activeList: CatalogListSummary,
    activeDraw: CollectionDrawSnapshot?,
    canEditManualList: Boolean,
    collectionState: CatalogCollectionUiState,
    readableIds: Set<String>,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    if (collectionState.entries.isEmpty() && activeDraw == null) {
        if (collectionState.isLoaded || activeList.entryCount == 0) {
            RankedDetailEmptyCard(activeList, canEditManualList, onAction)
        }
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        RankedDetailLazyColumn(
            activeList = activeList,
            activeDraw = activeDraw,
            canEditManualList = canEditManualList,
            collectionState = collectionState,
            readableIds = readableIds,
            isWide = maxWidth >= LISTS_WIDE_BREAKPOINT,
            listState = listState,
            onAction = onAction,
            onOpenCollectionDraw = onOpenCollectionDraw,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun RankedDetailLazyColumn(
    activeList: CatalogListSummary,
    activeDraw: CollectionDrawSnapshot?,
    canEditManualList: Boolean,
    collectionState: CatalogCollectionUiState,
    readableIds: Set<String>,
    isWide: Boolean,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val rankedEntries = collectionState.entries
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (activeDraw != null) {
            item(key = "active_draw_${activeDraw.collectionId}_${activeDraw.entryId}") {
                ActiveCollectionDrawListCard(
                    draw = activeDraw,
                    onAction = onAction,
                    onOpen = { onOpenCollectionDraw(activeDraw) },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        itemsIndexed(
            items = rankedEntries,
            key = { _, item -> "${activeList.collectionId}_${item.entry.id}" },
        ) { idx, item ->
            val prevTier = if (idx > 0) rankedEntries[idx - 1].tier else null
            RankedLazyCardRowItem(
                item = item,
                activeList = activeList,
                canEditManualList = canEditManualList,
                readableIds = readableIds,
                isWide = isWide,
                isFirst = idx == 0,
                isLast = idx == rankedEntries.lastIndex,
                isTierBreak = idx > 0 && item.tier != prevTier && prevTier != null,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                colors = colors,
            )
        }
        if (collectionState.hasMore) {
            item(key = "lists_load_more") {
                val remaining =
                    (collectionState.filteredTotalCount - rankedEntries.size).coerceAtLeast(0)
                CollectionLoadMoreFooter(
                    remaining = remaining,
                    isLoadingMore = collectionState.isLoadingMore,
                    onLoadMore = { onAction(MainAction.LoadMoreCatalogCollection) },
                )
            }
        }
        item(key = "ranked_bottom_spacer") { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun CollectionLoadMoreFooter(
    remaining: Int,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Spacer(modifier = Modifier.height(12.dp))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(
            enabled = !isLoadingMore,
            onClick = onLoadMore,
            modifier = Modifier.testTag("lists_load_more_btn"),
        ) {
            Text(
                text = if (isLoadingMore) "Loading…" else "Load More ($remaining remaining)",
                color = colors.accentBlue,
                fontSize = 13.sp,
            )
        }
    }
}

private val TIER_DISPLAY_ORDER = listOf('S', 'A', 'B', 'C', 'D')

@Composable
private fun RankedDetailEmptyCard(
    activeList: CatalogListSummary,
    canEditManualList: Boolean,
    onAction: (MainAction) -> Unit,
) {
    CompanionEmptyCard(
        title = "No entries in ${activeList.title} yet",
        body = "Log movies, albums, books, games, or star/finish items to build this list.",
        ctaLabel = if (canEditManualList) "+ Log & Add Entry" else "+ Log Entry",
        onCta = {
            onAction(
                MainAction.OpenQuickLogSheet(
                    defaultToWant = false,
                    targetCollectionId = activeList.collectionId.takeIf { canEditManualList },
                    targetCollectionTitle = activeList.title.takeIf { canEditManualList },
                    prefillKind = EntryKind.inferForCollection(activeList),
                )
            )
        },
    )
}

@Suppress("LongParameterList")
@Composable
private fun RankedLazyCardRowItem(
    item: RankedCatalogEntry,
    activeList: CatalogListSummary,
    canEditManualList: Boolean,
    readableIds: Set<String>,
    isWide: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    isTierBreak: Boolean,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    colors: com.grayvines.roche.ui.theme.RocheColors,
) {
    val onOpen =
        resolveCatalogEntryOpenAction(
            entryId = item.entry.id,
            externalUrl = item.entry.url,
            readableIds = readableIds,
            onOpenReader = { onAction(MainAction.OpenReader(it)) },
            onOpenExternalUrl = onOpenExternalUrl,
            entryKind = item.entry.kind,
            onOpenCatalogDetail = { onAction(MainAction.OpenCatalogDetailSheet(it)) },
        )
    val removeAction =
        if (canEditManualList && !activeList.hasGenerator) {
            { onAction(MainAction.RemoveWantEntry(activeList.collectionId, item.entry.id)) }
        } else {
            null
        }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .lazyGroupedCardRow(
                    isFirst = isFirst,
                    isLast = isLast,
                    surfaceColor = colors.surface,
                    borderColor = colors.border,
                )
    ) {
        if (!isFirst) {
            HorizontalDivider(
                color = colors.border,
                thickness = if (isTierBreak) 2.dp else 1.dp,
            )
        }
        RankedEntryTableRow(
            item = item,
            isWide = isWide,
            showQualityMeta = !activeList.isPriorityScoped,
            onOpen = onOpen,
            onFocusRank = {
                onAction(MainAction.StartRankingSession(activeList.collectionId, item.entry.id))
            },
            onRemoveFromList = removeAction,
        )
    }
}

@Composable
private fun ActiveCollectionDrawListCard(
    draw: CollectionDrawSnapshot,
    onAction: (MainAction) -> Unit,
    onOpen: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val yearSuffix = draw.releaseDate.take(4).takeIf { it.length == 4 }?.let { "  $it" }.orEmpty()
    RocheSurfaceCard {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("companion_active_draw_card"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActiveDrawListHeaderRow(draw = draw, onAction = onAction)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CatalogCoverThumbnail(
                    thumbnailUrl = draw.thumbnailUrl ?: draw.imageUrl,
                    kind = draw.kind,
                    size = 44.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${draw.title}$yearSuffix",
                        color = colors.textPrimary,
                        fontFamily = headlineFamily,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (draw.creator.isNotBlank()) {
                        Text(
                            text = draw.creator,
                            color = colors.textSecondary,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ActiveDrawCardButtons(draw = draw, onOpen = onOpen, onAction = onAction)
            }
        }
    }
}

@Composable
private fun ActiveDrawCardButtons(
    draw: CollectionDrawSnapshot,
    onOpen: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    HeaderActionButton(
        label = if (draw.isMusicKind) "Play" else "Open",
        onClick = onOpen,
        horizontalPadding = 10.dp,
        modifier = Modifier.testTag("companion_draw_open_btn"),
    )
    HeaderActionButton(
        label = "Rank",
        style = RocheButtonStyle.PRIMARY,
        onClick = {
            onAction(
                MainAction.OpenDrawNoteDialog(
                    draw.collectionId,
                    DrawNoteDialogMode.FINISH_AND_RANK,
                )
            )
        },
        horizontalPadding = 10.dp,
        modifier = Modifier.testTag("companion_draw_finish_btn"),
    )
}

@Composable
private fun ActiveDrawListHeaderRow(
    draw: CollectionDrawSnapshot,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "TODAY'S DRAW · #${draw.drawNumber} / ${formatCompactInt(draw.totalCount)}",
            color = colors.accentVerdigris,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
        )
        RocheIconActionButton(
            contentDescription = "Skip draw",
            outlined = false,
            onClick = {
                onAction(
                    MainAction.OpenDrawNoteDialog(draw.collectionId, DrawNoteDialogMode.ABANDON)
                )
            },
            modifier = Modifier.testTag("companion_draw_abandon_btn"),
        ) {
            SkipDrawIcon(tint = colors.textMuted, size = 15.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RankedEntryTableRow(
    item: RankedCatalogEntry,
    isWide: Boolean,
    showQualityMeta: Boolean,
    onOpen: (() -> Unit)?,
    onFocusRank: () -> Unit,
    onRemoveFromList: (() -> Unit)?,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 52.dp)
                .combinedClickable(onClick = onOpen ?: onFocusRank, onLongClick = onFocusRank)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("ranked_entry_row_${item.entry.id}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.rank.toString(),
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(24.dp),
        )
        if (!item.entry.thumbnailUrl.isNullOrBlank() || item.entry.kind == EntryKind.ALBUM) {
            CatalogCoverThumbnail(item.entry.thumbnailUrl, item.entry.kind, size = 36.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = item.entry.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.entry.subtitleLine.isNotBlank()) {
                Text(
                    text = item.entry.subtitleLine,
                    color = colors.textSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RankedEntryTrailingCluster(item, isWide, showQualityMeta, onRemoveFromList)
    }
}

@Composable
private fun RankedEntryTrailingCluster(
    item: RankedCatalogEntry,
    isWide: Boolean,
    showQualityMeta: Boolean,
    onRemoveFromList: (() -> Unit)?,
) {
    val colors = LocalRocheColors.current
    if (showQualityMeta && isWide) {
        RankedScoreMark(scoreOutOfTen = item.scoreOutOfTen, variance = item.variance)
    }
    if (showQualityMeta && item.tier != null) {
        TierBadgePill(tier = item.tier)
    }
    if (onRemoveFromList != null) {
        RocheIconActionButton(
            contentDescription = "Remove ${item.entry.title} from list",
            outlined = false,
            onClick = onRemoveFromList,
            modifier = Modifier.testTag("companion_remove_list_entry_${item.entry.id}"),
        ) {
            Text(text = "✕", color = colors.textMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RankedScoreMark(scoreOutOfTen: Double, variance: Double) {
    val colors = LocalRocheColors.current
    Canvas(modifier = Modifier.size(width = 72.dp, height = 12.dp)) {
        val midY = size.height * 0.5f
        val fraction = (scoreOutOfTen / 10.0).toFloat().coerceIn(0.05f, 0.95f)
        val cx = size.width * fraction
        val sigma = sqrt(variance.coerceAtLeast(0.0)).toFloat()
        val halfW = (size.width * (sigma * 0.14f)).coerceIn(3.dp.toPx(), size.width * 0.22f)
        val x1 = (cx - halfW).coerceAtLeast(2f)
        val x2 = (cx + halfW).coerceAtMost(size.width - 2f)

        drawLine(
            colors.border,
            Offset(0f, midY),
            Offset(size.width, midY),
            strokeWidth = 1.2.dp.toPx(),
        )
        drawLine(
            colors.controlOutline.copy(alpha = 0.5f),
            Offset(size.width * 0.5f, 2.dp.toPx()),
            Offset(size.width * 0.5f, size.height - 2.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = colors.accentVerdigris.copy(alpha = 0.45f),
            start = Offset(x1, midY),
            end = Offset(x2, midY),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(color = colors.accentVerdigris, radius = 3.dp.toPx(), center = Offset(cx, midY))
    }
}

internal fun isEditableManualUserList(list: CatalogListSummary?): Boolean =
    list != null && !list.isDynamic && list.role == CollectionRole.LIST && !list.isPriorityScoped

private fun formatCompactInt(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(value)

@Composable
internal fun CompanionEmptyCard(
    title: String,
    body: String,
    ctaLabel: String,
    onCta: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        RocheSurfaceCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontFamily = currentHeadlineFontFamily(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = body,
                    color = colors.textSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
                Spacer(modifier = Modifier.height(2.dp))
                HeaderActionButton(
                    label = ctaLabel,
                    style = RocheButtonStyle.PRIMARY,
                    onClick = onCta,
                )
            }
        }
    }
}

@Composable
private fun CreateCustomListDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    RocheAlertDialog(
        title = "Create Custom List",
        onDismissRequest = onDismiss,
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("List title") },
                placeholder = { Text("e.g. Sci-Fi Canon") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank()) onCreate(title) },
                enabled = title.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private val LISTS_WIDE_BREAKPOINT = 480.dp
