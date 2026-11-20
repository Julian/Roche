@file:Suppress("TooManyFunctions", "MagicNumber")

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.catalog.CatalogListSummary
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.RankedCatalogEntry
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.extractCleanDomain
import com.grayvines.roche.ui.components.EntrySourceMetadataRow
import com.grayvines.roche.ui.components.EntryThumbnailPreview
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheRefreshableTabScaffold
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.components.resolveCatalogEntryOpenAction
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

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
    activeAudioEntryId: String? = null,
    onOpenExternalUrl: (String) -> Unit = {},
) {
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

    RocheRefreshableTabScaffold(
        isRefreshing = state.isSyncing,
        onRefresh = { onAction(MainAction.SyncNow) },
        pullToRefreshTestTag = "starred_pull_to_refresh",
        header = {
            StarredListHeaderBar(totalCount = totalCount, onAction = onAction)
            StarredTypeFilterStrip(
                allCount = totalCount,
                selectedType = selectedTypeFilter,
                onSelectType = { selectedTypeFilter = it },
            )
        },
    ) {
        if (visibleRows.isEmpty()) {
            StarredEmptyPlaceholder(selectedTypeFilter = selectedTypeFilter)
        } else {
            StarredEntriesList(
                rows = visibleRows,
                state = state,
                activeAudioEntryId = activeAudioEntryId,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                listState = listState,
            )
        }
    }
}

@Composable
private fun StarredEmptyPlaceholder(selectedTypeFilter: ContentType?) {
    val colors = LocalRocheColors.current
    val message =
        if (selectedTypeFilter == null) {
            "No starred entries yet. Tap ★ on any post or tap + to add an item to Stars."
        } else {
            "No starred ${selectedTypeFilter.wireValue} items yet."
        }
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, color = colors.textMuted, fontSize = 13.sp)
    }
}

@Composable
private fun StarredListHeaderBar(
    totalCount: Int,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheTopHeader(title = "Stars", badgeText = totalCount.toString()) {
        if (totalCount >= 2) {
            HeaderActionButton(
                label = "Rank",
                style = RocheButtonStyle.PRIMARY,
                horizontalPadding = 12.dp,
                onClick = { onAction(MainAction.StartRankingSession(STARRED_COLLECTION_ID)) },
                modifier = Modifier.testTag("starred_rank_btn"),
            )
        }
        RocheIconActionButton(
            contentDescription = "Search",
            outlined = true,
            onClick = { onAction(MainAction.OpenSearch) },
            modifier = Modifier.testTag("starred_search_btn"),
        ) {
            SearchIcon(tint = colors.textSecondary, size = 17.dp)
        }
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
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftStripTab(
            label = "All ($allCount)",
            selected = selectedType == null,
            onClick = { onSelectType(null) },
            modifier = Modifier.testTag("starred_filter_all"),
        )
        SoftStripTab(
            label = "Articles",
            selected = selectedType == ContentType.ARTICLE,
            onClick = { onSelectType(ContentType.ARTICLE) },
            modifier = Modifier.testTag("starred_filter_articles"),
        )
        SoftStripTab(
            label = "Papers",
            selected = selectedType == ContentType.PDF,
            onClick = { onSelectType(ContentType.PDF) },
            modifier = Modifier.testTag("starred_filter_papers"),
        )
        SoftStripTab(
            label = "Videos",
            selected = selectedType == ContentType.VIDEO,
            onClick = { onSelectType(ContentType.VIDEO) },
            modifier = Modifier.testTag("starred_filter_videos"),
        )
        SoftStripTab(
            label = "Podcasts",
            selected = selectedType == ContentType.AUDIO,
            onClick = { onSelectType(ContentType.AUDIO) },
            modifier = Modifier.testTag("starred_filter_podcasts"),
        )
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Suppress("LongMethod")
@Composable
private fun StarredEntriesList(
    rows: List<StarredDisplayRow>,
    state: MainUiState,
    activeAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    listState: LazyListState,
) {
    val colors = LocalRocheColors.current
    val readableIds = remember(state.allEntries) { state.allEntries.mapTo(HashSet()) { it.id } }
    val nextQueuedAudioId = state.resolveNextQueuedAudioEntry(activeAudioEntryId)?.id
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
                        isPlayingAudio = entry.id == activeAudioEntryId,
                        isQueuedNextAudio = entry.id == nextQueuedAudioId,
                        onOpen = { onAction(MainAction.OpenReader(entry.id)) },
                        onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
                        onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                        onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
                        onDownloadOffline = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
                    )
                }
                is StarredDisplayRow.CatalogRow -> {
                    val catItem = row.item
                    val onOpen =
                        resolveCatalogEntryOpenAction(
                            entryId = catItem.entry.id,
                            externalUrl = catItem.entry.url,
                            readableIds = readableIds,
                            onOpenReader = { entryId -> onAction(MainAction.OpenReader(entryId)) },
                            onOpenExternalUrl = onOpenExternalUrl,
                        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StarredCatalogEntryRow(
    item: RankedCatalogEntry,
    onOpen: (() -> Unit)?,
    onUnstar: () -> Unit,
    onRank: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen ?: onRank, onLongClick = onRank)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 10.dp)
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
        if (!item.entry.thumbnailUrl.isNullOrBlank()) {
            CatalogCoverThumbnail(
                thumbnailUrl = item.entry.thumbnailUrl,
                kind = item.entry.kind,
                size = 38.dp,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.entry.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Normal,
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
    }
}

@Composable
fun QueueTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    upNextListState: LazyListState = rememberLazyListState(),
    wantListState: LazyListState = rememberLazyListState(),
    historyListState: LazyListState = rememberLazyListState(),
    activeAudioEntryId: String? = null,
    onOpenExternalUrl: (String) -> Unit = {},
) {
    var activeSubTab by rememberSaveable(state.queueSubTab) { mutableStateOf(state.queueSubTab) }
    val nextQueuedAudio = state.resolveNextQueuedAudioEntry(activeAudioEntryId)
    val countBadge =
        when (activeSubTab) {
            QueueSubTab.UP_NEXT -> state.queueEntries.size
            QueueSubTab.WANT -> state.catalog.wantItemCount
            QueueSubTab.HISTORY -> state.listeningHistoryEntries.size
        }
    val showPlayAudio =
        activeSubTab == QueueSubTab.UP_NEXT && nextQueuedAudio != null && activeAudioEntryId == null

    RocheRefreshableTabScaffold(
        isRefreshing = state.isSyncing,
        onRefresh = { onAction(MainAction.SyncNow) },
        pullToRefreshTestTag = "queue_pull_to_refresh",
        header = {
            QueueHeaderBar(
                countBadge = countBadge,
                showPlayAudio = showPlayAudio,
                onPlayAudio = { onAction(MainAction.SkipToNextQueuedAudio) },
                onOpenSearch = { onAction(MainAction.OpenSearch) },
            )
            QueueModeFilterRow(
                queueCount = state.queueEntries.size,
                wantCount = state.catalog.wantItemCount,
                historyCount = state.listeningHistoryEntries.size,
                activeSubTab = activeSubTab,
                onSelectSubTab = { subTab ->
                    activeSubTab = subTab
                    onAction(MainAction.SelectQueueSubTab(subTab))
                },
            )
        },
    ) {
        QueueTabBody(
            activeSubTab = activeSubTab,
            state = state,
            activeAudioEntryId = activeAudioEntryId,
            nextQueuedAudioId = nextQueuedAudio?.id,
            upNextListState = upNextListState,
            wantListState = wantListState,
            historyListState = historyListState,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

@Composable
private fun QueueTabBody(
    activeSubTab: QueueSubTab,
    state: MainUiState,
    activeAudioEntryId: String?,
    nextQueuedAudioId: String?,
    upNextListState: LazyListState,
    wantListState: LazyListState,
    historyListState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    when (activeSubTab) {
        QueueSubTab.UP_NEXT ->
            UpNextQueueListContent(
                queueEntries = state.queueEntries,
                activeAudioEntryId = activeAudioEntryId,
                nextQueuedAudioId = nextQueuedAudioId,
                onAction = onAction,
                listState = upNextListState,
            )
        QueueSubTab.WANT ->
            WantQueuesPane(
                state = state,
                listState = wantListState,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        QueueSubTab.HISTORY ->
            ListeningHistoryListContent(
                historyEntries = state.listeningHistoryEntries,
                state = state,
                activeAudioEntryId = activeAudioEntryId,
                onAction = onAction,
                listState = historyListState,
            )
    }
}

@Composable
private fun QueueHeaderBar(
    countBadge: Int,
    showPlayAudio: Boolean,
    onPlayAudio: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheTopHeader(title = "Queue", badgeText = countBadge.toString()) {
        if (showPlayAudio) {
            HeaderActionButton(
                label = "▶ Play",
                onClick = onPlayAudio,
                horizontalPadding = 11.dp,
                modifier = Modifier.testTag("queue_play_audio_btn"),
            )
        }
        RocheIconActionButton(
            contentDescription = "Search",
            outlined = true,
            onClick = onOpenSearch,
            modifier = Modifier.testTag("queue_search_btn"),
        ) {
            SearchIcon(tint = colors.textSecondary, size = 17.dp)
        }
    }
}

@Composable
private fun QueueModeFilterRow(
    queueCount: Int,
    wantCount: Int,
    historyCount: Int,
    activeSubTab: QueueSubTab,
    onSelectSubTab: (QueueSubTab) -> Unit,
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
        SoftStripTab(
            label = "Up Next ($queueCount)",
            selected = activeSubTab == QueueSubTab.UP_NEXT,
            onClick = { onSelectSubTab(QueueSubTab.UP_NEXT) },
            modifier = Modifier.testTag("queue_mode_up_next"),
        )
        SoftStripTab(
            label = if (wantCount > 0) "Want ($wantCount)" else "Want",
            selected = activeSubTab == QueueSubTab.WANT,
            onClick = { onSelectSubTab(QueueSubTab.WANT) },
            modifier = Modifier.testTag("queue_mode_want"),
        )
        SoftStripTab(
            label = if (historyCount > 0) "History ($historyCount)" else "History",
            selected = activeSubTab == QueueSubTab.HISTORY,
            onClick = { onSelectSubTab(QueueSubTab.HISTORY) },
            modifier = Modifier.testTag("queue_mode_history"),
        )
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
private fun WantQueuesPane(
    state: MainUiState,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
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
        if (queues.size > 1) {
            WantQueuePickerRow(
                queues = queues,
                activeCollectionId = activeQueue?.collectionId,
                onSelect = { onAction(MainAction.SelectWantCollection(it)) },
            )
        }
        if (entries.isEmpty()) {
            WantQueueEmptySection(activeQueue = activeQueue, onAction = onAction)
        } else {
            WantQueueEntriesList(
                activeQueue = activeQueue,
                entries = entries,
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
private fun WantQueuePickerRow(
    queues: List<CatalogListSummary>,
    activeCollectionId: String?,
    onSelect: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        queues.forEach { queue ->
            SoftStripTab(
                label = "${queue.title} (${queue.entryCount})",
                selected = queue.collectionId == activeCollectionId,
                onClick = { onSelect(queue.collectionId) },
            )
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

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
private fun WantQueueEntriesList(
    activeQueue: CatalogListSummary?,
    entries: List<RankedCatalogEntry>,
    readableIds: Set<String>,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (activeQueue != null) {
            item(key = "want_head_${activeQueue.collectionId}") {
                WantQueueSectionHeader(
                    queue = activeQueue,
                    canPrioritize = entries.size >= 2,
                    onAction = onAction,
                )
            }
        }
        item(key = "want_card_${activeQueue?.collectionId ?: "all"}") {
            RocheSurfaceCard {
                entries.forEachIndexed { idx, item ->
                    if (idx > 0) {
                        HorizontalDivider(color = colors.border, thickness = 1.dp)
                    }
                    val onOpen =
                        resolveCatalogEntryOpenAction(
                            entryId = item.entry.id,
                            externalUrl = item.entry.url,
                            readableIds = readableIds,
                            onOpenReader = { onAction(MainAction.OpenReader(it)) },
                            onOpenExternalUrl = onOpenExternalUrl,
                        )
                    WantQueueRow(
                        item = item,
                        onOpen = onOpen,
                        onComplete = {
                            onAction(
                                MainAction.CompleteWantEntry(item.entry.id, rankImmediately = true)
                            )
                        },
                        onRemove = {
                            activeQueue?.collectionId?.let { qId ->
                                onAction(MainAction.RemoveWantEntry(qId, item.entry.id))
                            }
                        },
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun WantQueueSectionHeader(
    queue: CatalogListSummary,
    canPrioritize: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = queue.title.uppercase(),
            color = colors.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (queue.collectionId == WANT_TO_READ_COLLECTION_ID) {
                TextButton(
                    onClick = { onAction(MainAction.OpenAddSheet()) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("want_to_read_add_url_btn"),
                ) {
                    Text(text = "+ Add URL", color = colors.accentBlue, fontSize = 12.sp)
                }
            }
            if (canPrioritize) {
                HeaderActionButton(
                    label = "Prioritize",
                    style = RocheButtonStyle.PRIMARY,
                    horizontalPadding = 10.dp,
                    onClick = { onAction(MainAction.StartRankingSession(queue.collectionId)) },
                    modifier = Modifier.testTag("want_prioritize_btn"),
                )
            }
        }
    }
}

@Composable
private fun WantQueueRow(
    item: RankedCatalogEntry,
    onOpen: (() -> Unit)?,
    onComplete: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val meta = remember(item.entry) { buildWantMetaLine(item) }
    val clickMod =
        if (onOpen != null) {
            Modifier.clickable(onClick = onOpen).semantics { role = Role.Button }
        } else {
            Modifier
        }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(clickMod)
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("want_row_${item.entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = item.rank.toString(),
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(20.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = item.entry.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    color = colors.textSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HeaderActionButton(label = "✓ Done", onClick = onComplete, horizontalPadding = 10.dp)
        RocheIconActionButton(
            contentDescription = "Remove ${item.entry.title} from want queue",
            outlined = false,
            onClick = onRemove,
        ) {
            Text(text = "✕", color = colors.textMuted, fontSize = 13.sp)
        }
    }
}

private fun buildWantMetaLine(item: RankedCatalogEntry): String = buildList {
    if (item.entry.creators.isNotEmpty()) add(item.entry.creators.joinToString(", "))
    val domain = item.entry.url?.let(::extractCleanDomain)?.takeIf { it.isNotBlank() }
    if (domain != null) add(domain)
    add(item.entry.kind.displayLabel)
}
    .joinToString(" · ")

@Composable
private fun UpNextQueueListContent(
    queueEntries: List<InboxEntry>,
    activeAudioEntryId: String?,
    nextQueuedAudioId: String?,
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
                isPlayingAudio = entry.id == activeAudioEntryId,
                isQueuedNextAudio = entry.id == nextQueuedAudioId,
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
    activeAudioEntryId: String?,
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
                isPlaying = entry.id == activeAudioEntryId,
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
    val headlineFamily = currentHeadlineFontFamily()
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = entry.title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 14.5.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${entry.feedTitle} · ${entry.readEstimateLabel}",
            color = colors.textSecondary,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val headlineFamily = currentHeadlineFontFamily()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = entry.title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 14.5.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        EntrySourceMetadataRow(
            entry = entry,
            trailingMetaText = "· ${entry.readEstimateLabel}",
            trailingMetaFontSize = 11.sp,
            showOfflineIndicator = true,
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
