@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.ALBUMS_1001_COLLECTION_ID
import com.grayvines.roche.data.CatalogListSummary
import com.grayvines.roche.data.CollectionMode
import com.grayvines.roche.data.ConsumptionStatus
import com.grayvines.roche.data.DailyAlbumSnapshot
import com.grayvines.roche.data.DiaryEntryItem
import com.grayvines.roche.data.RankedCatalogEntry
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalRocheColors
import java.util.Locale

@Composable
fun MediaCompanionScreen(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onOpenDailyAlbumInMusicApp: (DailyAlbumSnapshot) -> Unit = {},
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

        when (state.companionSubTab) {
            CompanionSubTab.LISTS ->
                RankedListsPane(
                    state = state,
                    listState = listState,
                    onAction = onAction,
                    onOpenCreateList = { showCreateListDialog = true },
                    onOpenDailyAlbumInMusicApp = onOpenDailyAlbumInMusicApp,
                )
            CompanionSubTab.DIARY ->
                DiaryHistoryPane(
                    diaryEntries = state.catalog.diaryEntries,
                    listState = listState,
                    onOpenQuickLog = { onAction(MainAction.OpenQuickLogSheet(false)) },
                )
            CompanionSubTab.WANT ->
                WantQueuesPane(
                    state = state,
                    listState = listState,
                    onAction = onAction,
                )
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
    RocheTopHeader(
        title = "Lists & Diary",
        subtitle =
            "${state.catalog.consumedCount} logged · ${state.catalog.comparisonCount} ranked",
    ) {
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

@Composable
private fun RankedListsPane(
    state: MainUiState,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenCreateList: () -> Unit,
    onOpenDailyAlbumInMusicApp: (DailyAlbumSnapshot) -> Unit,
) {
    val lists = state.catalog.lists
    val activeList =
        remember(lists, state.selectedCatalogCollectionId) {
            lists.firstOrNull { it.collectionId == state.selectedCatalogCollectionId }
                ?: lists.firstOrNull { it.entryCount > 0 }
                ?: lists.firstOrNull()
        }
    val rankedEntries =
        activeList?.let { state.catalog.entriesByCollectionId[it.collectionId] }.orEmpty()
    val canEditManualList =
        activeList != null &&
            activeList.mode == CollectionMode.MANUAL &&
            activeList.collectionId != ALBUMS_1001_COLLECTION_ID &&
            !activeList.isPriorityScoped

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
                canAddEntry = canEditManualList,
                onAddEntry = {
                    onAction(
                        MainAction.OpenQuickLogSheet(
                            defaultToWant = false,
                            targetCollectionId = activeList.collectionId,
                            targetCollectionTitle = activeList.title,
                        )
                    )
                },
                onStartRank = {
                    onAction(MainAction.StartRankingSession(activeList.collectionId))
                },
            )
        }
        val active1001Album =
            state.dailyAlbum.takeIf { activeList?.collectionId == ALBUMS_1001_COLLECTION_ID }
        RankedListBody(
            activeList = activeList,
            active1001Album = active1001Album,
            canEditManualList = canEditManualList,
            rankedEntries = rankedEntries,
            listState = listState,
            onAction = onAction,
            onOpenDailyAlbumInMusicApp = onOpenDailyAlbumInMusicApp,
        )
    }
}

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
                if (summary.entryCount > 0) {
                    "${summary.title} (${summary.entryCount})"
                } else {
                    summary.title
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
    active1001Album: DailyAlbumSnapshot?,
    canEditManualList: Boolean,
    rankedEntries: List<RankedCatalogEntry>,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenDailyAlbumInMusicApp: (DailyAlbumSnapshot) -> Unit,
) {
    if (rankedEntries.isEmpty() && active1001Album == null) {
        CompanionEmptyCard(
            title = "No entries in ${activeList?.title ?: "this list"} yet",
            body = "Log movies, albums, books, games, or finish feed items to build your rankings.",
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
        active1001Album = active1001Album,
        canEditManualList = canEditManualList,
        rankedEntries = rankedEntries,
        listState = listState,
        onAction = onAction,
        onOpenDailyAlbumInMusicApp = onOpenDailyAlbumInMusicApp,
    )
}

@Suppress("LongParameterList")
@Composable
private fun RankedListLazyColumn(
    activeList: CatalogListSummary?,
    active1001Album: DailyAlbumSnapshot?,
    canEditManualList: Boolean,
    rankedEntries: List<RankedCatalogEntry>,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenDailyAlbumInMusicApp: (DailyAlbumSnapshot) -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (active1001Album != null) {
            item(key = "active_1001_album_${active1001Album.entryId}") {
                Active1001AlbumListCard(
                    album = active1001Album,
                    onAction = onAction,
                    onPlay = { onOpenDailyAlbumInMusicApp(active1001Album) },
                )
            }
        }
        items(rankedEntries, key = { it.entry.id }) { item ->
            RankedEntryCardRow(
                item = item,
                showQualityMeta = activeList?.isPriorityScoped != true,
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
private fun Active1001AlbumListCard(
    album: DailyAlbumSnapshot,
    onAction: (MainAction) -> Unit,
    onPlay: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheSurfaceCard {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 11.dp)
                    .testTag("companion_active_1001_card"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "ACTIVE DRAW · #${album.albumNumber} (${album.remainingCount} LEFT)",
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${album.name} — ${album.artist}",
                color = colors.textPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderActionButton(
                    label = "Play",
                    style = RocheButtonStyle.PRIMARY,
                    onClick = onPlay,
                    modifier = Modifier.testTag("companion_1001_play_btn"),
                )
                HeaderActionButton(
                    label = "Finish & Rank",
                    onClick = {
                        onAction(
                            MainAction.OpenAlbumNoteDialog(AlbumNoteDialogMode.FINISH_AND_RANK)
                        )
                    },
                    modifier = Modifier.testTag("companion_1001_finish_btn"),
                )
                HeaderActionButton(
                    label = "Abandon",
                    onClick = {
                        onAction(MainAction.OpenAlbumNoteDialog(AlbumNoteDialogMode.ABANDON))
                    },
                    modifier = Modifier.testTag("companion_1001_abandon_btn"),
                )
            }
        }
    }
}

@Composable
private fun ActiveListToolbar(
    list: CatalogListSummary,
    canRank: Boolean,
    canAddEntry: Boolean = false,
    onAddEntry: (() -> Unit)? = null,
    onStartRank: () -> Unit,
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
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canAddEntry && onAddEntry != null) {
                HeaderActionButton(
                    label = "+ Add",
                    onClick = onAddEntry,
                    modifier = Modifier.testTag("companion_add_list_entry_btn"),
                )
            }
            if (canRank) {
                HeaderActionButton(
                    label = if (list.isPriorityScoped) "Prioritize" else "Compare / Rank",
                    style = RocheButtonStyle.PRIMARY,
                    onClick = onStartRank,
                    modifier = Modifier.testTag("companion_rank_list_btn"),
                )
            }
        }
    }
}

@Composable
private fun RankedEntryCardRow(
    item: RankedCatalogEntry,
    showQualityMeta: Boolean,
    onRemoveFromList: (() -> Unit)? = null,
    onFocusRank: () -> Unit,
) {
    RocheSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
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
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
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

@Composable
private fun DiaryHistoryPane(
    diaryEntries: List<DiaryEntryItem>,
    listState: LazyListState,
    onOpenQuickLog: () -> Unit,
) {
    if (diaryEntries.isEmpty()) {
        CompanionEmptyCard(
            title = "Your consumption diary is empty",
            body =
                "Finished podcasts, archived read articles, 1001 Albums, and logged media appear here chronologically.",
            ctaLabel = "+ Log First Entry",
            onCta = onOpenQuickLog,
        )
        return
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
                DiaryLogCard(diary = diary)
            }
        }
        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun DiaryLogCard(diary: DiaryEntryItem) {
    val colors = LocalRocheColors.current
    RocheSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
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
) {
    val queues = state.catalog.wantQueues
    val activeQueue =
        remember(queues, state.selectedWantCollectionId) {
            queues.firstOrNull { it.collectionId == state.selectedWantCollectionId }
                ?: queues.firstOrNull { it.entryCount > 0 }
                ?: queues.firstOrNull()
        }
    val entries =
        activeQueue?.let { state.catalog.entriesByCollectionId[it.collectionId] }.orEmpty()

    if (queues.isEmpty()) {
        CompanionEmptyCard(
            title = "Your Want Queue is clear",
            body =
                "Save movies, books, albums, or games you want to experience next and prioritize them locally.",
            ctaLabel = "+ Add to Want Queue",
            onCta = { onAction(MainAction.OpenQuickLogSheet(true)) },
        )
        return
    }

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
                onStartRank = {
                    onAction(MainAction.StartRankingSession(activeQueue.collectionId))
                },
            )
        }
        if (entries.isEmpty()) {
            CompanionEmptyCard(
                title = "No entries in ${activeQueue?.title ?: "Want Queue"} yet",
                body =
                    "Save movies, books, albums, or games you want to experience next and prioritize them locally.",
                ctaLabel = "+ Add to Want Queue",
                onCta = { onAction(MainAction.OpenQuickLogSheet(true)) },
            )
        } else {
            WantQueueEntriesList(
                entries = entries,
                activeQueueId = activeQueue?.collectionId,
                listState = listState,
                onAction = onAction,
            )
        }
    }
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
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.entry.id }) { item ->
            WantQueueRowCard(
                item = item,
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
    onComplete: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
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
    Box(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
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
