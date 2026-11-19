@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.CatalogListSummary
import com.grayvines.roche.data.CollectionDrawSnapshot
import com.grayvines.roche.data.CollectionMode
import com.grayvines.roche.data.CollectionRole
import com.grayvines.roche.data.ConsumptionStatus
import com.grayvines.roche.data.DiaryEntryItem
import com.grayvines.roche.data.RankedCatalogEntry
import com.grayvines.roche.data.STARRED_COLLECTION_ID
import com.grayvines.roche.data.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.generatorFor
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RefreshIcon
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import java.util.Locale

@Composable
fun MediaCompanionScreen(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit = {},
    onOpenExternalUrl: (String) -> Unit = {},
) {
    val colors = LocalRocheColors.current
    var showCreateListDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().testTag("lists_companion_screen")) {
        CompanionTopBar(state = state, onAction = onAction)
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        CompanionSubTabBar(
            activeSubTab = state.companionSubTab,
            wantCount = state.catalog.wantItemCount,
            onSelect = { onAction(MainAction.SelectCompanionSubTab(it)) },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = { onAction(MainAction.SyncNow) },
            modifier = Modifier.fillMaxSize().testTag("lists_pull_to_refresh"),
        ) {
            when (state.companionSubTab) {
                CompanionSubTab.LISTS ->
                    RankedListsPane(
                        state = state,
                        listState = listState,
                        onAction = onAction,
                        onOpenCreateList = { showCreateListDialog = true },
                        onOpenCollectionDraw = onOpenCollectionDraw,
                        onOpenExternalUrl = onOpenExternalUrl,
                    )
                CompanionSubTab.DIARY ->
                    DiaryHistoryPane(
                        state = state,
                        diaryEntries = state.catalog.diaryEntries,
                        listState = listState,
                        onAction = onAction,
                        onOpenExternalUrl = onOpenExternalUrl,
                        onOpenQuickLog = { onAction(MainAction.OpenQuickLogSheet(false)) },
                    )
                CompanionSubTab.WANT ->
                    WantQueuesPane(
                        state = state,
                        listState = listState,
                        onAction = onAction,
                        onOpenExternalUrl = onOpenExternalUrl,
                    )
            }
        }
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

@Composable
private fun CompanionTopBar(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val baseSubtitle =
        "${state.catalog.consumedCount} logged · ${state.catalog.comparisonCount} ranked"
    RocheTopHeader(
        title = "Lists & Diary",
        subtitle = if (state.isSyncing) "$baseSubtitle · Refreshing…" else baseSubtitle,
    ) {
        RocheIconActionButton(
            contentDescription = "Sync",
            onClick = { onAction(MainAction.SyncNow) },
            modifier = Modifier.testTag("companion_sync_btn"),
        ) {
            RefreshIcon(
                tint = if (state.isSyncing) colors.accentBlue else colors.textSecondary,
                size = 17.dp,
                isSpinning = state.isSyncing,
            )
        }
        HeaderActionButton(
            label = "+ Log / Want",
            style = RocheButtonStyle.PRIMARY,
            onClick = {
                onAction(
                    MainAction.OpenQuickLogSheet(
                        defaultToWant = state.companionSubTab == CompanionSubTab.WANT
                    )
                )
            },
            modifier = Modifier.testTag("companion_quick_log_btn"),
        )
    }
}

@Composable
private fun CompanionSubTabBar(
    activeSubTab: CompanionSubTab,
    wantCount: Int,
    onSelect: (CompanionSubTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompanionSubTab.entries.forEach { tab ->
            val label =
                if (tab == CompanionSubTab.WANT && wantCount > 0) {
                    "Want ($wantCount)"
                } else {
                    tab.label
                }
            FilterChipPill(
                label = label,
                selected = activeSubTab == tab,
                onClick = { onSelect(tab) },
                modifier = Modifier.testTag("companion_subtab_${tab.name.lowercase()}"),
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun RankedListsPane(
    state: MainUiState,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenCreateList: () -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    onOpenExternalUrl: (String) -> Unit = {},
) {
    val lists = state.catalog.lists
    val activeList =
        remember(lists, state.selectedCatalogCollectionId) {
            resolveActiveCatalogList(lists, state.selectedCatalogCollectionId)
        }
    val rankedEntries =
        activeList?.let { state.catalog.entriesByCollectionId[it.collectionId] }.orEmpty()
    val canEditManualList = isEditableManualUserList(activeList)
    val readableIds = remember(state.allEntries) { state.allEntries.mapTo(HashSet()) { it.id } }

    Column(modifier = Modifier.fillMaxSize()) {
        CatalogListChipsRow(
            lists = lists,
            activeCollectionId = activeList?.collectionId,
            onSelectCollection = { onAction(MainAction.SelectCatalogCollection(it)) },
            onOpenCreateList = onOpenCreateList,
        )
        if (activeList != null) {
            ActiveListToolbar(
                list = activeList,
                canRank = rankedEntries.size >= 2,
                canEditManualList = canEditManualList,
                onAction = onAction,
            )
        }
        val activeDraw = activeList?.let {
            state.catalog.generatorFor(it.collectionId)?.currentDraw
        }
        RankedListBody(
            activeList = activeList,
            activeDraw = activeDraw,
            canEditManualList = canEditManualList,
            rankedEntries = rankedEntries,
            readableIds = readableIds,
            listState = listState,
            onAction = onAction,
            onOpenCollectionDraw = onOpenCollectionDraw,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

private fun resolveActiveCatalogList(
    lists: List<CatalogListSummary>,
    selectedId: String?,
): CatalogListSummary? =
    lists.firstOrNull { it.collectionId == selectedId }
        ?: lists.firstOrNull { it.entryCount > 0 || it.generatorEnabled }
        ?: lists.firstOrNull()

private fun isEditableManualUserList(list: CatalogListSummary?): Boolean =
    list != null &&
        list.mode == CollectionMode.MANUAL &&
        list.role == CollectionRole.LIST &&
        !list.isPriorityScoped

@Composable
private fun CatalogListChipsRow(
    lists: List<CatalogListSummary>,
    activeCollectionId: String?,
    onSelectCollection: (String) -> Unit,
    onOpenCreateList: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lists.forEach { summary ->
            val chipLabel =
                when {
                    summary.entryCount > 0 -> "${summary.title} (${summary.entryCount})"
                    summary.generatorEnabled && summary.poolTotalCount > 0 ->
                        "${summary.title} (0/${summary.poolTotalCount})"
                    else -> summary.title
                }
            FilterChipPill(
                label = chipLabel,
                selected = summary.collectionId == activeCollectionId,
                onClick = { onSelectCollection(summary.collectionId) },
            )
        }
        FilterChipPill(
            label = "+ List",
            selected = false,
            onClick = onOpenCreateList,
            modifier = Modifier.testTag("companion_new_list_btn"),
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun RankedListBody(
    activeList: CatalogListSummary?,
    activeDraw: CollectionDrawSnapshot?,
    canEditManualList: Boolean,
    rankedEntries: List<RankedCatalogEntry>,
    readableIds: Set<String>,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    if (rankedEntries.isEmpty() && activeDraw == null) {
        CompanionEmptyCard(
            title = "No entries in ${activeList?.title ?: "this list"} yet",
            body = "Log movies, albums, books, games, or star/finish items to build your list.",
            ctaLabel = if (canEditManualList) "+ Log & Add Entry" else "+ Log Entry",
            onCta = {
                onAction(
                    MainAction.OpenQuickLogSheet(
                        defaultToWant = false,
                        targetCollectionId = activeList?.collectionId?.takeIf { canEditManualList },
                        targetCollectionTitle = activeList?.title?.takeIf { canEditManualList },
                    )
                )
            },
        )
        return
    }
    RankedListLazyColumn(
        activeList = activeList,
        activeDraw = activeDraw,
        canEditManualList = canEditManualList,
        rankedEntries = rankedEntries,
        readableIds = readableIds,
        listState = listState,
        onAction = onAction,
        onOpenCollectionDraw = onOpenCollectionDraw,
        onOpenExternalUrl = onOpenExternalUrl,
    )
}

@Suppress("LongParameterList")
@Composable
private fun RankedListLazyColumn(
    activeList: CatalogListSummary?,
    activeDraw: CollectionDrawSnapshot?,
    canEditManualList: Boolean,
    rankedEntries: List<RankedCatalogEntry>,
    readableIds: Set<String>,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (activeDraw != null) {
            item(key = "active_draw_${activeDraw.collectionId}_${activeDraw.entryId}") {
                ActiveCollectionDrawListCard(
                    draw = activeDraw,
                    onAction = onAction,
                    onOpen = { onOpenCollectionDraw(activeDraw) },
                )
            }
        }
        items(rankedEntries, key = { it.entry.id }) { item ->
            val onOpen =
                resolveCatalogEntryOpenAction(item, readableIds, onAction, onOpenExternalUrl)
            RankedEntryCardRow(
                item = item,
                showQualityMeta = activeList?.isPriorityScoped != true,
                onOpen = onOpen,
                onRemoveFromList =
                    buildRemoveListAction(activeList, canEditManualList, item, onAction),
                onFocusRank = {
                    activeList?.let {
                        onAction(MainAction.StartRankingSession(it.collectionId, item.entry.id))
                    }
                },
            )
        }
        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

private fun resolveCatalogEntryOpenAction(
    item: RankedCatalogEntry,
    readableIds: Set<String>,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
): (() -> Unit)? {
    val extUrl = item.entry.url?.takeIf { it.isNotBlank() }
    return when {
        item.entry.id in readableIds -> {
            { onAction(MainAction.OpenReader(item.entry.id)) }
        }
        extUrl != null -> {
            { onOpenExternalUrl(extUrl) }
        }
        else -> null
    }
}

private fun buildRemoveListAction(
    activeList: CatalogListSummary?,
    canEditManualList: Boolean,
    item: RankedCatalogEntry,
    onAction: (MainAction) -> Unit,
): (() -> Unit)? =
    if (canEditManualList && activeList != null) {
        { onAction(MainAction.RemoveWantEntry(activeList.collectionId, item.entry.id)) }
    } else {
        null
    }

@Composable
private fun ActiveCollectionDrawListCard(
    draw: CollectionDrawSnapshot,
    onAction: (MainAction) -> Unit,
    onOpen: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val titleLine = if (draw.creator.isNotBlank()) "${draw.title} — ${draw.creator}" else draw.title
    RocheSurfaceCard {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 11.dp)
                    .testTag("companion_active_draw_card"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "ACTIVE DRAW · #${draw.drawNumber} (${draw.remainingCount} LEFT)",
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = titleLine,
                color = colors.textPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ActiveDrawActionRow(draw = draw, onAction = onAction, onOpen = onOpen)
        }
    }
}

@Composable
private fun ActiveDrawActionRow(
    draw: CollectionDrawSnapshot,
    onAction: (MainAction) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderActionButton(
            label = if (draw.isMusicKind) "Play" else "Open",
            style = RocheButtonStyle.PRIMARY,
            onClick = onOpen,
            modifier = Modifier.testTag("companion_draw_open_btn"),
        )
        HeaderActionButton(
            label = "Finish & Rank",
            onClick = {
                onAction(
                    MainAction.OpenDrawNoteDialog(
                        draw.collectionId,
                        DrawNoteDialogMode.FINISH_AND_RANK,
                    )
                )
            },
            modifier = Modifier.testTag("companion_draw_finish_btn"),
        )
        HeaderActionButton(
            label = "Abandon",
            onClick = {
                onAction(
                    MainAction.OpenDrawNoteDialog(draw.collectionId, DrawNoteDialogMode.ABANDON)
                )
            },
            modifier = Modifier.testTag("companion_draw_abandon_btn"),
        )
    }
}

@Composable
private fun ActiveListToolbar(
    list: CatalogListSummary,
    canRank: Boolean,
    canEditManualList: Boolean = false,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${list.title.uppercase()} · ${list.entryCount} ENTRIES",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ActiveListToolbarButtons(
            list = list,
            canRank = canRank,
            canEditManualList = canEditManualList,
            onAction = onAction,
        )
    }
}

@Composable
private fun ActiveListToolbarButtons(
    list: CatalogListSummary,
    canRank: Boolean,
    canEditManualList: Boolean,
    onAction: (MainAction) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (list.collectionId == WANT_TO_READ_COLLECTION_ID) {
            HeaderActionButton(
                label = "+ Add URL",
                onClick = { onAction(MainAction.OpenAddSheet()) },
                modifier = Modifier.testTag("want_to_read_add_url_btn"),
            )
        }
        if (canEditManualList) {
            ManualListEditButtons(list = list, onAction = onAction)
        }
        if (canRank) {
            HeaderActionButton(
                label = if (list.isPriorityScoped) "Prioritize" else "Compare / Rank",
                style = RocheButtonStyle.PRIMARY,
                onClick = { onAction(MainAction.StartRankingSession(list.collectionId)) },
                modifier = Modifier.testTag("companion_rank_list_btn"),
            )
        }
    }
}

@Composable
private fun ManualListEditButtons(
    list: CatalogListSummary,
    onAction: (MainAction) -> Unit,
) {
    HeaderActionButton(
        label = "+ Add",
        onClick = {
            onAction(
                MainAction.OpenQuickLogSheet(
                    defaultToWant = false,
                    targetCollectionId = list.collectionId,
                    targetCollectionTitle = list.title,
                )
            )
        },
        modifier = Modifier.testTag("companion_add_list_entry_btn"),
    )
    if (list.collectionId != STARRED_COLLECTION_ID) {
        HeaderActionButton(
            label = if (list.generatorEnabled) "Pause Draw" else "Enable Draw",
            onClick = {
                onAction(
                    MainAction.SetCollectionGeneratorEnabled(
                        collectionIdOrSlug = list.collectionId,
                        enabled = !list.generatorEnabled,
                    )
                )
            },
            modifier = Modifier.testTag("companion_toggle_draw_btn"),
        )
    }
}

@Composable
private fun RankedEntryCardRow(
    item: RankedCatalogEntry,
    showQualityMeta: Boolean,
    onOpen: (() -> Unit)? = null,
    onRemoveFromList: (() -> Unit)? = null,
    onFocusRank: () -> Unit,
) {
    val rowMod =
        if (onOpen != null) {
            Modifier.fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("ranked_entry_row_${item.entry.id}")
        } else {
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("ranked_entry_row_${item.entry.id}")
        }
    RocheSurfaceCard {
        Column(
            modifier = rowMod,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RankedEntryHeaderRow(
                item = item,
                showQualityMeta = showQualityMeta,
                onRemoveFromList = onRemoveFromList,
                onFocusRank = onFocusRank,
            )
            if (showQualityMeta) {
                ScoreBarRow(scoreOutOfTen = item.scoreOutOfTen)
            }
        }
    }
}

@Composable
private fun RankedEntryHeaderRow(
    item: RankedCatalogEntry,
    showQualityMeta: Boolean,
    onRemoveFromList: (() -> Unit)?,
    onFocusRank: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "#${item.rank}",
            color = colors.accentEcru,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.entry.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = item.entry.subtitleLine
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showQualityMeta && item.tier != null) {
            TierBadgePill(tier = item.tier)
        }
        HeaderActionButton(label = "Rank", onClick = onFocusRank)
        if (onRemoveFromList != null) {
            HeaderActionButton(
                label = "✕",
                contentDescription = "Remove ${item.entry.title} from list",
                onClick = onRemoveFromList,
                modifier = Modifier.testTag("companion_remove_list_entry_${item.entry.id}"),
            )
        }
    }
}

@Composable
private fun ScoreBarRow(scoreOutOfTen: Double) {
    val colors = LocalRocheColors.current
    val fraction = (scoreOutOfTen / 10.0).toFloat().coerceIn(0.03f, 1f)
    val barShape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.weight(1f).height(4.dp).clip(barShape).background(colors.border)) {
            Box(
                modifier =
                    Modifier.fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(barShape)
                        .background(colors.accentVerdigris)
            )
        }
        Text(
            text = String.format(Locale.US, "%.1f", scoreOutOfTen),
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun DiaryHistoryPane(
    state: MainUiState,
    diaryEntries: List<DiaryEntryItem>,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenQuickLog: () -> Unit,
) {
    if (diaryEntries.isEmpty()) {
        CompanionEmptyCard(
            title = "Your consumption diary is empty",
            body =
                "Finished podcasts, archived read articles, active list draws, and logged media appear here chronologically.",
            ctaLabel = "+ Log First Entry",
            onCta = onOpenQuickLog,
        )
        return
    }
    val readableIds = remember(state.allEntries) { state.allEntries.mapTo(HashSet()) { it.id } }
    val catalogUrlsById =
        remember(state.catalog.entriesByCollectionId) {
            buildMap {
                state.catalog.entriesByCollectionId.values.flatten().forEach { item ->
                    item.entry.url?.takeIf { it.isNotBlank() }?.let { put(item.entry.id, it) }
                }
            }
        }
    val grouped = remember(diaryEntries) { diaryEntries.groupBy { it.monthKey } }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        grouped.forEach { (monthKey, itemsInMonth) ->
            item(key = "month_$monthKey") {
                RocheSectionHeader(label = monthKey)
            }
            items(itemsInMonth, key = { it.id }) { diary ->
                val extUrl = catalogUrlsById[diary.entryId]
                val onOpen: (() -> Unit)? =
                    when {
                        diary.entryId in readableIds -> {
                            { onAction(MainAction.OpenReader(diary.entryId)) }
                        }
                        extUrl != null -> {
                            { onOpenExternalUrl(extUrl) }
                        }
                        else -> null
                    }
                DiaryLogCard(diary = diary, onOpen = onOpen)
            }
        }
        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun DiaryLogCard(
    diary: DiaryEntryItem,
    onOpen: (() -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    val rowMod =
        if (onOpen != null) {
            Modifier.fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        } else {
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
        }
    RocheSurfaceCard {
        Column(
            modifier = rowMod,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DiaryLogHeaderRow(diary = diary)
            Text(
                text = diary.title,
                color = colors.textPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = formatDiaryMetaLine(diary)
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!diary.notes.isNullOrBlank()) {
                Text(
                    text = diary.notes,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun DiaryLogHeaderRow(diary: DiaryEntryItem) {
    val colors = LocalRocheColors.current
    val statusColor =
        when (diary.status) {
            ConsumptionStatus.CONSUMED -> colors.accentVerdigris
            ConsumptionStatus.STARTED -> colors.accentBlue
            ConsumptionStatus.ABANDONED -> colors.accentIndianRed
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${diary.dateLabel} · ${diary.kind.displayLabel.uppercase()}",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = diary.status.displayLabel.uppercase(),
                color = statusColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            if (diary.tier != null) {
                TierBadgePill(tier = diary.tier)
            }
        }
    }
}

private fun formatDiaryMetaLine(diary: DiaryEntryItem): String = buildList {
    if (diary.creators.isNotEmpty()) add(diary.creators.joinToString(", "))
    if (diary.releaseYear != null && diary.releaseYear > 0) {
        add(diary.releaseYear.toString())
    }
}
    .joinToString(" · ")

@Composable
private fun WantQueuesPane(
    state: MainUiState,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit = {},
) {
    val queues = state.catalog.wantQueues
    if (queues.isEmpty()) {
        CompanionEmptyCard(
            title = "Your Want Queue is clear",
            body =
                "Save articles, PDFs, movies, books, albums, or games you want to experience next and prioritize them locally.",
            ctaLabel = "+ Add to Want to Read",
            onCta = { onAction(MainAction.OpenAddSheet()) },
        )
        return
    }
    val activeQueue =
        remember(queues, state.selectedWantCollectionId) {
            resolveActiveWantQueue(queues, state.selectedWantCollectionId)
        }
    val entries =
        activeQueue?.let { state.catalog.entriesByCollectionId[it.collectionId] }.orEmpty()
    val readableIds = remember(state.allEntries) { state.allEntries.mapTo(HashSet()) { it.id } }

    Column(modifier = Modifier.fillMaxSize()) {
        WantQueueChipsRow(
            queues = queues,
            activeCollectionId = activeQueue?.collectionId,
            onSelect = { onAction(MainAction.SelectWantCollection(it)) },
        )
        if (activeQueue != null) {
            ActiveListToolbar(
                list = activeQueue,
                canRank = entries.size >= 2,
                onAction = onAction,
            )
        }
        if (entries.isEmpty()) {
            WantQueueEmptySection(activeQueue = activeQueue, onAction = onAction)
        } else {
            WantQueueEntriesList(
                entries = entries,
                activeQueueId = activeQueue?.collectionId,
                readableIds = readableIds,
                listState = listState,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }
}

private fun resolveActiveWantQueue(
    queues: List<CatalogListSummary>,
    selectedId: String?,
): CatalogListSummary? =
    queues.firstOrNull { it.collectionId == selectedId }
        ?: queues.firstOrNull { it.entryCount > 0 }
        ?: queues.firstOrNull()

@Composable
private fun WantQueueEmptySection(
    activeQueue: CatalogListSummary?,
    onAction: (MainAction) -> Unit,
) {
    val isWantToRead = activeQueue?.collectionId == WANT_TO_READ_COLLECTION_ID
    val bodyText =
        if (isWantToRead) {
            "Share any article or PDF from your browser, or tap + to save a URL directly to Want to Read."
        } else {
            "Save movies, books, albums, or games you want to experience next and prioritize them locally."
        }
    CompanionEmptyCard(
        title = "No entries in ${activeQueue?.title ?: "Want Queue"} yet",
        body = bodyText,
        ctaLabel = if (isWantToRead) "+ Add URL to Want to Read" else "+ Add to Want Queue",
        onCta = {
            if (isWantToRead) {
                onAction(MainAction.OpenAddSheet())
            } else {
                onAction(MainAction.OpenQuickLogSheet(true))
            }
        },
    )
}

@Composable
private fun WantQueueChipsRow(
    queues: List<CatalogListSummary>,
    activeCollectionId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        queues.forEach { q ->
            FilterChipPill(
                label = "${q.title} (${q.entryCount})",
                selected = q.collectionId == activeCollectionId,
                onClick = { onSelect(q.collectionId) },
            )
        }
    }
}

@Composable
private fun WantQueueEntriesList(
    entries: List<RankedCatalogEntry>,
    activeQueueId: String?,
    readableIds: Set<String>,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.entry.id }) { item ->
            val canOpenReader = item.entry.id in readableIds
            val extUrl = item.entry.url?.takeIf { it.isNotBlank() }
            val onOpen: (() -> Unit)? =
                when {
                    canOpenReader -> {
                        { onAction(MainAction.OpenReader(item.entry.id)) }
                    }
                    extUrl != null -> {
                        { onOpenExternalUrl(extUrl) }
                    }
                    else -> null
                }
            WantQueueRowCard(
                item = item,
                onOpen = onOpen,
                onComplete = {
                    onAction(MainAction.CompleteWantEntry(item.entry.id, rankImmediately = true))
                },
                onRemove = {
                    if (activeQueueId != null) {
                        onAction(MainAction.RemoveWantEntry(activeQueueId, item.entry.id))
                    }
                },
            )
        }
        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun WantQueueRowCard(
    item: RankedCatalogEntry,
    onOpen: (() -> Unit)? = null,
    onComplete: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val rowMod =
        Modifier.fillMaxWidth()
            .let { base -> if (onOpen != null) base.clickable(onClick = onOpen) else base }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("want_row_${item.entry.id}")
    RocheSurfaceCard {
        Row(
            modifier = rowMod,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "#${item.rank}",
                color = colors.accentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.entry.title,
                    color = colors.textPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.entry.subtitleLine.isNotBlank()) {
                    Text(
                        text = item.entry.subtitleLine,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HeaderActionButton(
                label = "✓ Done",
                style = RocheButtonStyle.PRIMARY,
                onClick = onComplete,
            )
            Spacer(modifier = Modifier.width(2.dp))
            HeaderActionButton(
                label = "✕",
                contentDescription = "Remove ${item.entry.title} from want queue",
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun CompanionEmptyCard(
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
                    fontFamily = FontFamily.Serif,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Custom List") },
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
