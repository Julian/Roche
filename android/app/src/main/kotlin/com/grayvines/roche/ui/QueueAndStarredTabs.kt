@file:Suppress("TooManyFunctions")

package com.grayvines.roche.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.EntryKind
import com.grayvines.roche.data.RankedCatalogEntry
import com.grayvines.roche.data.STARRED_COLLECTION_ID
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.EntryThumbnailPreview
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RefreshIcon
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalRocheColors

private sealed interface StarredDisplayRow {
    val stableKey: String

    data class ReaderRow(val entry: InboxEntry) : StarredDisplayRow {
        override val stableKey: String = entry.id
    }

    data class CatalogRow(val item: RankedCatalogEntry) : StarredDisplayRow {
        override val stableKey: String = "cat_${item.entry.id}"
    }
}

private fun buildOrderedStarredRows(state: MainUiState): List<StarredDisplayRow> {
    val readerById = state.starredEntries.associateBy { it.id }
    val catalogRanked = state.catalog.entriesByCollectionId[STARRED_COLLECTION_ID].orEmpty()
    val seenIds = HashSet<String>(readerById.size + catalogRanked.size)
    return buildList {
        for (catItem in catalogRanked) {
            val id = catItem.entry.id
            if (seenIds.add(id)) {
                val readerEntry = readerById[id]
                if (readerEntry != null) {
                    add(StarredDisplayRow.ReaderRow(readerEntry))
                } else {
                    add(StarredDisplayRow.CatalogRow(catItem))
                }
            }
        }
        for (readerEntry in state.starredEntries) {
            if (seenIds.add(readerEntry.id)) {
                add(StarredDisplayRow.ReaderRow(readerEntry))
            }
        }
    }
}

private fun matchesStarredTypeFilter(row: StarredDisplayRow, filter: ContentType?): Boolean {
    if (filter == null) return true
    return when (row) {
        is StarredDisplayRow.ReaderRow -> row.entry.contentType == filter
        is StarredDisplayRow.CatalogRow ->
            when (row.item.entry.kind) {
                EntryKind.PODCAST_EPISODE,
                EntryKind.PODCAST -> filter == ContentType.AUDIO
                EntryKind.ARTICLE -> filter == ContentType.ARTICLE
                EntryKind.PAPER -> filter == ContentType.PDF
                EntryKind.VIDEO,
                EntryKind.CHANNEL -> filter == ContentType.VIDEO
                else -> false
            }
    }
}

@Composable
fun StarredTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onOpenExternalUrl: (String) -> Unit = {},
) {
    val colors = LocalRocheColors.current
    var selectedTypeFilter by rememberSaveable { mutableStateOf<ContentType?>(null) }
    val orderedRows =
        remember(state.starredEntries, state.catalog.entriesByCollectionId) {
            buildOrderedStarredRows(state)
        }
    val visibleRows =
        remember(orderedRows, selectedTypeFilter) {
            orderedRows.filter { matchesStarredTypeFilter(it, selectedTypeFilter) }
        }
    val totalCount = orderedRows.size

    Column(modifier = Modifier.fillMaxSize()) {
        StarredListHeaderBar(
            totalCount = totalCount,
            isSyncing = state.isSyncing,
            onAction = onAction,
        )
        StarredTypeFilterStrip(
            allCount = totalCount,
            selectedType = selectedTypeFilter,
            onSelectType = { selectedTypeFilter = it },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = { onAction(MainAction.SyncNow) },
            modifier = Modifier.fillMaxSize().testTag("starred_pull_to_refresh"),
        ) {
            if (visibleRows.isEmpty()) {
                StarredEmptyPlaceholder()
            } else {
                StarredEntriesList(
                    rows = visibleRows,
                    state = state,
                    onAction = onAction,
                    onOpenExternalUrl = onOpenExternalUrl,
                    listState = listState,
                )
            }
        }
    }
}

@Composable
private fun StarredEmptyPlaceholder() {
    val colors = LocalRocheColors.current
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text =
                "No entries in your Starred list yet. Tap ★ on any post or tap + Add to add items to Starred.",
            color = colors.textMuted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun StarredListHeaderBar(
    totalCount: Int,
    isSyncing: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val subtitle = if (isSyncing) "$totalCount in list · Refreshing…" else "$totalCount in list"
    RocheTopHeader(
        title = "Starred",
        subtitle = subtitle,
    ) {
        HeaderActionButton(
            label = "+ Add",
            onClick = {
                onAction(
                    MainAction.OpenQuickLogSheet(
                        defaultToWant = false,
                        targetCollectionId = STARRED_COLLECTION_ID,
                        targetCollectionTitle = "Starred",
                    )
                )
            },
            modifier = Modifier.testTag("starred_add_btn"),
        )
        if (totalCount >= 2) {
            HeaderActionButton(
                label = "Rank",
                style = RocheButtonStyle.PRIMARY,
                onClick = { onAction(MainAction.StartRankingSession(STARRED_COLLECTION_ID)) },
                modifier = Modifier.testTag("starred_rank_btn"),
            )
        }
        RocheIconActionButton(
            contentDescription = "Sync",
            onClick = { onAction(MainAction.SyncNow) },
            modifier = Modifier.testTag("starred_sync_btn"),
        ) {
            RefreshIcon(
                tint = if (isSyncing) colors.accentBlue else colors.textSecondary,
                size = 17.dp,
                isSpinning = isSyncing,
            )
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun StarredEntriesList(
    rows: List<StarredDisplayRow>,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    listState: LazyListState,
) {
    val colors = LocalRocheColors.current
    val readableIds = remember(state.allEntries) { state.allEntries.mapTo(HashSet()) { it.id } }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("starred_list"),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        items(items = rows, key = { it.stableKey }) { row ->
            when (row) {
                is StarredDisplayRow.ReaderRow -> {
                    val entry = row.entry
                    InboxEntryRowItem(
                        entry = entry,
                        ultraDense = state.settings.ultraDenseRows,
                        swipeToArchiveEnabled = state.settings.swipeToArchive,
                        isPlayingAudio = entry.id == state.playerState.currentEntryId,
                        isQueuedNextAudio = entry.id == state.nextQueuedAudioEntryId,
                        onOpen = { onAction(MainAction.OpenReader(entry.id)) },
                        onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
                        onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                        onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
                        onDownloadOffline = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
                    )
                }
                is StarredDisplayRow.CatalogRow -> {
                    val catItem = row.item
                    val extUrl = catItem.entry.url?.takeIf { it.isNotBlank() }
                    val onOpen: (() -> Unit)? =
                        when {
                            catItem.entry.id in readableIds -> {
                                { onAction(MainAction.OpenReader(catItem.entry.id)) }
                            }
                            extUrl != null -> {
                                { onOpenExternalUrl(extUrl) }
                            }
                            else -> null
                        }
                    StarredCatalogEntryRow(
                        item = catItem,
                        onOpen = onOpen,
                        onUnstar = {
                            onAction(
                                MainAction.RemoveWantEntry(STARRED_COLLECTION_ID, catItem.entry.id)
                            )
                        },
                        onRank = {
                            onAction(
                                MainAction.StartRankingSession(
                                    STARRED_COLLECTION_ID,
                                    catItem.entry.id,
                                )
                            )
                        },
                    )
                }
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
    }
}

@Composable
private fun StarredCatalogEntryRow(
    item: RankedCatalogEntry,
    onOpen: (() -> Unit)?,
    onUnstar: () -> Unit,
    onRank: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
    val clickMod = if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .then(clickMod)
                .padding(horizontal = 12.dp, vertical = 11.dp)
                .testTag("starred_catalog_row_${item.entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RocheIconActionButton(
            contentDescription = "Unstar ${item.entry.title}",
            onClick = onUnstar,
            outlined = false,
        ) {
            StarIcon(tint = colors.accentEcru, isStarred = true, size = 17.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.entry.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
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
        HeaderActionButton(label = "Rank", onClick = onRank)
    }
}

@Composable
private fun StarredTypeFilterStrip(
    allCount: Int,
    selectedType: ContentType?,
    onSelectType: (ContentType?) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChipPill(
            label = "All ($allCount)",
            selected = selectedType == null,
            onClick = { onSelectType(null) },
            modifier = Modifier.testTag("starred_filter_all"),
        )
        FilterChipPill(
            label = "Podcasts",
            selected = selectedType == ContentType.AUDIO,
            onClick = { onSelectType(ContentType.AUDIO) },
            modifier = Modifier.testTag("starred_filter_podcasts"),
        )
        FilterChipPill(
            label = "Articles",
            selected = selectedType == ContentType.ARTICLE,
            onClick = { onSelectType(ContentType.ARTICLE) },
            modifier = Modifier.testTag("starred_filter_articles"),
        )
        FilterChipPill(
            label = "Papers",
            selected = selectedType == ContentType.PDF,
            onClick = { onSelectType(ContentType.PDF) },
            modifier = Modifier.testTag("starred_filter_papers"),
        )
        FilterChipPill(
            label = "Videos",
            selected = selectedType == ContentType.VIDEO,
            onClick = { onSelectType(ContentType.VIDEO) },
            modifier = Modifier.testTag("starred_filter_videos"),
        )
    }
}

@Composable
fun QueueTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    upNextListState: LazyListState = rememberLazyListState(),
    historyListState: LazyListState = rememberLazyListState(),
) {
    val colors = LocalRocheColors.current
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val queueEntries = state.queueEntries
    val historyEntries = state.listeningHistoryEntries
    val audioCount = queueEntries.count { it.contentType == ContentType.AUDIO }
    val subtitle =
        when {
            showHistory -> "${historyEntries.size} played"
            queueEntries.isEmpty() -> "0 items"
            audioCount > 0 -> "${queueEntries.size} items · $audioCount audio"
            else -> "${queueEntries.size} items"
        }

    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(
            title = "Queue",
            subtitle = subtitle,
        ) {
            if (state.nextQueuedAudioEntry != null && state.playerState.currentEntryId == null) {
                HeaderActionButton(
                    label = "▶ Play Audio",
                    onClick = { onAction(MainAction.SkipToNextQueuedAudio) },
                    modifier = Modifier.testTag("queue_play_audio_btn"),
                )
            }
        }
        QueueModeFilterRow(
            queueCount = queueEntries.size,
            historyCount = historyEntries.size,
            showHistory = showHistory,
            onSelectHistory = { showHistory = it },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = { onAction(MainAction.SyncNow) },
            modifier = Modifier.fillMaxSize().testTag("queue_pull_to_refresh"),
        ) {
            if (showHistory) {
                ListeningHistoryListContent(
                    historyEntries = historyEntries,
                    state = state,
                    onAction = onAction,
                    listState = historyListState,
                )
            } else {
                UpNextQueueListContent(
                    queueEntries = queueEntries,
                    state = state,
                    onAction = onAction,
                    listState = upNextListState,
                )
            }
        }
    }
}

@Composable
private fun QueueModeFilterRow(
    queueCount: Int,
    historyCount: Int,
    showHistory: Boolean,
    onSelectHistory: (Boolean) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChipPill(
            label = "Up Next ($queueCount)",
            selected = !showHistory,
            onClick = { onSelectHistory(false) },
            modifier = Modifier.testTag("queue_mode_up_next"),
        )
        FilterChipPill(
            label = "Listening History ($historyCount)",
            selected = showHistory,
            onClick = { onSelectHistory(true) },
            modifier = Modifier.testTag("queue_mode_history"),
        )
    }
}

@Composable
private fun UpNextQueueListContent(
    queueEntries: List<InboxEntry>,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState,
) {
    val colors = LocalRocheColors.current
    if (queueEntries.isEmpty()) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp)
                    .testTag("queue_empty_state"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text =
                    "Your Queue is empty. Long-press any article, paper, video, or podcast episode in Inbox to add it to your Queue.",
                color = colors.textMuted,
                fontSize = 13.sp,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("queue_list"),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        itemsIndexed(items = queueEntries, key = { _, item -> item.id }) { index, entry ->
            QueueEntryRowItem(
                entry = entry,
                canMoveUp = index > 0,
                isPlayingAudio = entry.id == state.playerState.currentEntryId,
                isQueuedNextAudio = entry.id == state.nextQueuedAudioEntryId,
                onOpen = { onAction(MainAction.OpenReader(entry.id)) },
                onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
                onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                onMoveUp = { onAction(MainAction.MoveQueueItemUp(entry.id)) },
                onRemoveFromQueue = { onAction(MainAction.RemoveFromQueue(entry.id)) },
                onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
            )
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListeningHistoryListContent(
    historyEntries: List<InboxEntry>,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = LocalRocheColors.current
    if (historyEntries.isEmpty()) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp)
                    .testTag("listening_history_empty"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text =
                    "No podcast episodes played yet. Episodes you listen to appear here with their playback timestamp and progress.",
                color = colors.textMuted,
                fontSize = 13.sp,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("listening_history_list"),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(items = historyEntries, key = { it.id }) { entry ->
            ListeningHistoryRowItem(
                entry = entry,
                isPlaying = entry.id == state.playerState.currentEntryId,
                inQueue = state.isEntryInQueue(entry.id),
                onAction = onAction,
            )
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListeningHistoryRowItem(
    entry: InboxEntry,
    isPlaying: Boolean,
    inQueue: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
    val starTint = if (entry.isFavorite) colors.accentEcru else colors.textMuted
    val historyLabel = entry.formattedListeningHistoryLabel ?: "Played"
    val rowBg =
        if (isPlaying) colors.accentBlue.copy(alpha = ROW_HIGHLIGHT_ALPHA) else colors.background
    val starDesc = if (entry.isFavorite) "Unstar ${entry.title}" else "Star ${entry.title}"

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(rowBg)
                .combinedClickable(
                    onClick = { onAction(MainAction.PlayAudioEntry(entry.id)) },
                    onLongClick = { onAction(MainAction.OpenContextSheet(entry.id)) },
                )
                .semantics { role = Role.Button }
                .padding(start = 10.dp, end = 12.dp, top = 11.dp, bottom = 11.dp)
                .testTag("history_row_${entry.id}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RocheIconActionButton(
            contentDescription = starDesc,
            onClick = { onAction(MainAction.ToggleStar(entry.id)) },
            outlined = false,
            modifier = Modifier.testTag("history_star_btn_${entry.id}"),
        ) {
            StarIcon(tint = starTint, isStarred = entry.isFavorite, size = 17.dp)
        }
        EntryThumbnailPreview(entry = entry, modifier = Modifier.padding(top = 2.dp))
        ListeningHistoryMetaColumn(
            entry = entry,
            headlineFamily = headlineFamily,
            historyLabel = historyLabel,
            modifier = Modifier.weight(1f),
        )
        if (!inQueue) {
            HeaderActionButton(
                label = "+ Queue",
                onClick = { onAction(MainAction.AddToQueue(entry.id)) },
                modifier = Modifier.testTag("history_requeue_btn_${entry.id}"),
            )
        }
    }
}

@Composable
private fun ListeningHistoryMetaColumn(
    entry: InboxEntry,
    headlineFamily: FontFamily,
    historyLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    Column(modifier = modifier) {
        Text(
            text = "${entry.feedTitle} · ${entry.readEstimateLabel}",
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = entry.title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = historyLabel,
            color = colors.accentVerdigris,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("history_timestamp_${entry.id}"),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongParameterList")
private fun QueueEntryRowItem(
    entry: InboxEntry,
    canMoveUp: Boolean,
    isPlayingAudio: Boolean,
    isQueuedNextAudio: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleStar: () -> Unit,
    onMoveUp: () -> Unit,
    onRemoveFromQueue: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val rowBackground =
        when {
            isPlayingAudio -> colors.accentBlue.copy(alpha = ROW_HIGHLIGHT_ALPHA)
            isQueuedNextAudio -> colors.accentVerdigris.copy(alpha = QUEUE_NEXT_ALPHA)
            else -> colors.background
        }
    val leftAccentColor =
        when {
            isPlayingAudio -> colors.accentBlue
            isQueuedNextAudio -> colors.accentVerdigris
            else -> null
        }
    val starTint = if (entry.isFavorite) colors.accentEcru else colors.textMuted

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(rowBackground)
                .drawBehind {
                    if (leftAccentColor != null) {
                        drawRect(
                            color = leftAccentColor,
                            topLeft = Offset.Zero,
                            size = Size(width = 3.dp.toPx(), height = size.height),
                        )
                    }
                }
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .semantics { role = Role.Button }
                .padding(start = 10.dp, end = 12.dp, top = 11.dp, bottom = 11.dp)
                .testTag("queue_row_${entry.id}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RocheIconActionButton(
            contentDescription =
                if (entry.isFavorite) "Unstar ${entry.title}" else "Star ${entry.title}",
            onClick = onToggleStar,
            outlined = false,
            modifier = Modifier.testTag("queue_star_btn_${entry.id}"),
        ) {
            StarIcon(tint = starTint, isStarred = entry.isFavorite, size = 17.dp)
        }

        if (entry.showsThumbnailPreview) {
            EntryThumbnailPreview(entry = entry, modifier = Modifier.padding(top = 2.dp))
        }

        QueueEntryTextColumn(entry = entry, modifier = Modifier.weight(1f))

        QueueEntryTrailingActions(
            entry = entry,
            canMoveUp = canMoveUp,
            onMoveUp = onMoveUp,
            onRemoveFromQueue = onRemoveFromQueue,
            onToggleArchive = onToggleArchive,
        )
    }
}

@Composable
private fun QueueEntryTextColumn(
    entry: InboxEntry,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SiteFaviconBadge(
                primaryFaviconUrl = entry.faviconUrl,
                fallbackFaviconUrl = entry.fallbackFaviconUrl,
                domain = entry.displayDomain,
                title = entry.feedTitle,
                size = 14.dp,
            )
            Text(
                text = entry.feedTitle,
                color = colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "· ${entry.contentType.wireValue.uppercase()} · ${entry.readEstimateLabel}",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                maxLines = 1,
            )
            if (entry.isCachedOffline) {
                Text(
                    text = "↓",
                    color = colors.accentVerdigris,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = entry.title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QueueEntryTrailingActions(
    entry: InboxEntry,
    canMoveUp: Boolean,
    onMoveUp: () -> Unit,
    onRemoveFromQueue: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (canMoveUp) {
            RocheIconActionButton(
                contentDescription = "Move up ${entry.title}",
                onClick = onMoveUp,
                outlined = false,
                modifier = Modifier.testTag("queue_up_btn_${entry.id}"),
            ) {
                Text(
                    text = "↑",
                    color = colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
            }
        }
        RocheIconActionButton(
            contentDescription = "Remove ${entry.title} from Queue",
            onClick = onRemoveFromQueue,
            outlined = false,
            modifier = Modifier.testTag("queue_remove_btn_${entry.id}"),
        ) {
            Text(
                text = "✕",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
        RocheIconActionButton(
            contentDescription = "Archive ${entry.title}",
            onClick = onToggleArchive,
            outlined = false,
            modifier = Modifier.testTag("queue_archive_btn_${entry.id}"),
        ) {
            FilingCabinetIcon(
                tint = colors.textMuted,
                isArchived = entry.isArchived,
                size = 17.dp,
            )
        }
    }
}

private const val ROW_HIGHLIGHT_ALPHA = 0.12f
private const val QUEUE_NEXT_ALPHA = 0.11f
