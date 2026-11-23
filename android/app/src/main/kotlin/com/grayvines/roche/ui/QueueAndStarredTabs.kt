@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.catalog.CatalogListSummary
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.RankedCatalogEntry
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.extractCleanDomain
import com.grayvines.roche.ui.components.CatalogCoverThumbnail
import com.grayvines.roche.ui.components.EntrySourceMetadataRow
import com.grayvines.roche.ui.components.EntryThumbnailPreview
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.MoreDotsIcon
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheMetaPill
import com.grayvines.roche.ui.components.RocheRefreshableTabScaffold
import com.grayvines.roche.ui.components.RocheStripTab
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.components.TierBadgePill
import com.grayvines.roche.ui.components.UnpinnedTabBackButton
import com.grayvines.roche.ui.components.lazyGroupedCardRow
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

private enum class StarredFilterTab(
    val label: String,
    val emptyPluralLabel: String,
    val testTag: String,
) {
    ALL("All", "items", "starred_filter_all"),
    ARTICLES("Articles", "articles", "starred_filter_articles"),
    PAPERS("Papers", "papers", "starred_filter_papers"),
    VIDEOS("Videos", "videos", "starred_filter_videos"),
    PODCASTS("Podcasts", "podcasts", "starred_filter_podcasts"),
    AUDIO("Audio", "audio items", "starred_filter_audio"),
    CATALOG("Catalog", "catalog items", "starred_filter_catalog"),
}

private fun matchesStarredFilter(row: StarredDisplayRow, filter: StarredFilterTab): Boolean =
    when (filter) {
        StarredFilterTab.ALL -> true
        StarredFilterTab.CATALOG -> row is StarredDisplayRow.CatalogRow
        StarredFilterTab.ARTICLES -> matchesStarredContentType(row, ContentType.ARTICLE)
        StarredFilterTab.PAPERS -> matchesStarredContentType(row, ContentType.PDF)
        StarredFilterTab.VIDEOS -> matchesStarredContentType(row, ContentType.VIDEO)
        StarredFilterTab.PODCASTS ->
            when (row) {
                is StarredDisplayRow.ReaderRow -> row.entry.isPodcastEpisode
                is StarredDisplayRow.CatalogRow ->
                    row.item.entry.kind == EntryKind.PODCAST_EPISODE ||
                        row.item.entry.kind == EntryKind.PODCAST
            }
        StarredFilterTab.AUDIO ->
            when (row) {
                is StarredDisplayRow.ReaderRow -> row.entry.isPlayableAudio
                is StarredDisplayRow.CatalogRow ->
                    row.item.entry.kind == EntryKind.PODCAST_EPISODE ||
                        row.item.entry.kind == EntryKind.PODCAST
            }
    }

private fun matchesStarredContentType(row: StarredDisplayRow, target: ContentType): Boolean =
    when (row) {
        is StarredDisplayRow.ReaderRow -> row.entry.contentType == target
        is StarredDisplayRow.CatalogRow ->
            when (row.item.entry.kind) {
                EntryKind.PODCAST_EPISODE,
                EntryKind.PODCAST -> target == ContentType.AUDIO
                EntryKind.ARTICLE -> target == ContentType.ARTICLE
                EntryKind.PAPER -> target == ContentType.PDF
                EntryKind.VIDEO,
                EntryKind.CHANNEL -> target == ContentType.VIDEO
                else -> false
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
    var selectedFilter by rememberSaveable { mutableStateOf(StarredFilterTab.ALL) }
    val orderedRows =
        remember(state.starredEntries, state.catalog.entriesByCollectionId) {
            buildOrderedStarredRows(state)
        }
    val visibleRows =
        remember(orderedRows, selectedFilter) {
            orderedRows.filter { matchesStarredFilter(it, selectedFilter) }
        }
    val totalCount = orderedRows.size

    RocheRefreshableTabScaffold(
        isRefreshing = state.isManualRefreshing,
        onRefresh = { onAction(MainAction.ManualRefresh) },
        pullToRefreshTestTag = "starred_pull_to_refresh",
        header = {
            StarredListHeaderBar(
                totalCount = totalCount,
                showBackToHome = state.isUnpinnedDestination(AppTab.STARRED),
                onAction = onAction,
            )
            StarredTypeFilterStrip(
                allCount = totalCount,
                selectedFilter = selectedFilter,
                onSelectFilter = { selectedFilter = it },
            )
        },
    ) {
        if (visibleRows.isEmpty()) {
            StarredEmptyPlaceholder(selectedFilter = selectedFilter)
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
private fun StarredEmptyPlaceholder(selectedFilter: StarredFilterTab) {
    val colors = LocalRocheColors.current
    val message =
        if (selectedFilter == StarredFilterTab.ALL) {
            "No starred entries yet. Tap ★ on any post or catalog item to collect favorites here."
        } else {
            "No starred ${selectedFilter.emptyPluralLabel} yet."
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
    showBackToHome: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheTopHeader(
        title = "Stars",
        badgeText = totalCount.toString(),
        navigationAction =
            if (showBackToHome) {
                { UnpinnedTabBackButton(onBack = { onAction(MainAction.SelectTab(AppTab.HOME)) }) }
            } else {
                null
            },
    ) {
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
    selectedFilter: StarredFilterTab,
    onSelectFilter: (StarredFilterTab) -> Unit,
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
        StarredFilterTab.entries.forEach { tab ->
            val label = if (tab == StarredFilterTab.ALL) "All ($allCount)" else tab.label
            RocheStripTab(
                label = label,
                selected = selectedFilter == tab,
                onClick = { onSelectFilter(tab) },
                modifier = Modifier.testTag(tab.testTag),
            )
        }
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
    val nextQueuedAudioId = state.resolveNextQueuedAudioEntry(activeAudioEntryId)?.id
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactWidth = maxWidth < COMPACT_ROW_BREAKPOINT
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
                            isCompactWidth = isCompactWidth,
                            isPlayingAudio = entry.id == activeAudioEntryId,
                            isQueuedNextAudio = entry.id == nextQueuedAudioId,
                            isDownloadingOffline = entry.id in state.inFlightOfflineEntryIds,
                            onOpen = { onAction(MainAction.OpenReader(entry.id)) },
                            onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
                            onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                            onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
                            onPlayAudio = {
                                onAction(
                                    MainAction.PlayAudioEntry(entry.id, openFullscreen = false)
                                )
                            },
                            onDownloadOffline = {
                                onAction(MainAction.ToggleOfflineCache(entry.id))
                            },
                        )
                    }
                    is StarredDisplayRow.CatalogRow -> {
                        val catItem = row.item
                        val onOpen =
                            resolveCatalogEntryOpenAction(
                                entryId = catItem.entry.id,
                                externalUrl = catItem.entry.url,
                                readableIds = state.readableEntryIds,
                                onOpenReader = { entryId ->
                                    onAction(MainAction.OpenReader(entryId))
                                },
                                onOpenExternalUrl = onOpenExternalUrl,
                                entryKind = catItem.entry.kind,
                                onOpenCatalogDetail = { entryId ->
                                    onAction(MainAction.OpenCatalogDetailSheet(entryId))
                                },
                            )
                        StarredCatalogEntryRow(
                            item = catItem,
                            onOpen = onOpen,
                            onUnstar = {
                                onAction(
                                    MainAction.RemoveWantEntry(
                                        STARRED_COLLECTION_ID,
                                        catItem.entry.id,
                                    )
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
        StarredCatalogMetaColumn(item = item, modifier = Modifier.weight(1f))
        if (item.tier != null) {
            TierBadgePill(tier = item.tier)
        }
    }
}

@Composable
private fun StarredCatalogMetaColumn(
    item: RankedCatalogEntry,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = item.entry.title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RocheMetaPill(
                text = item.entry.kind.displayLabel,
                textColor = colors.textSecondary,
                backgroundColor = colors.surfaceElevated,
                borderColor = colors.border,
            )
            if (item.entry.subtitleLine.isNotBlank()) {
                Text(
                    text = item.entry.subtitleLine,
                    color = colors.textSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
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
        isRefreshing = state.isManualRefreshing,
        onRefresh = { onAction(MainAction.ManualRefresh) },
        pullToRefreshTestTag = "queue_pull_to_refresh",
        header = {
            QueueHeaderBar(
                countBadge = countBadge,
                showPlayAudio = showPlayAudio,
                showBackToHome = state.isUnpinnedDestination(AppTab.QUEUE),
                onBackToHome = { onAction(MainAction.SelectTab(AppTab.HOME)) },
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
@Suppress("LongParameterList")
private fun QueueHeaderBar(
    countBadge: Int,
    showPlayAudio: Boolean,
    showBackToHome: Boolean,
    onBackToHome: () -> Unit,
    onPlayAudio: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheTopHeader(
        title = "Queue",
        badgeText = countBadge.toString(),
        navigationAction =
            if (showBackToHome) {
                { UnpinnedTabBackButton(onBack = onBackToHome) }
            } else {
                null
            },
    ) {
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
        RocheStripTab(
            label = "Up Next ($queueCount)",
            selected = activeSubTab == QueueSubTab.UP_NEXT,
            onClick = { onSelectSubTab(QueueSubTab.UP_NEXT) },
            modifier = Modifier.testTag("queue_mode_up_next"),
        )
        RocheStripTab(
            label = if (wantCount > 0) "Want ($wantCount)" else "Want",
            selected = activeSubTab == QueueSubTab.WANT,
            onClick = { onSelectSubTab(QueueSubTab.WANT) },
            modifier = Modifier.testTag("queue_mode_want"),
        )
        RocheStripTab(
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
                readableIds = state.readableEntryIds,
                listState = listState,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }
}

internal fun resolveActiveWantQueue(
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
            RocheStripTab(
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
                onAction(
                    MainAction.OpenQuickLogSheet(
                        defaultToWant = true,
                        prefillKind = EntryKind.inferForCollection(activeQueue),
                    )
                )
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
        modifier = Modifier.fillMaxSize(),
    ) {
        if (activeQueue != null) {
            item(key = "want_head_${activeQueue.collectionId}") {
                WantQueueSectionHeader(
                    queue = activeQueue,
                    canPrioritize = entries.size >= 2,
                    onAction = onAction,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        itemsIndexed(
            items = entries,
            key = { _, item -> "${activeQueue?.collectionId ?: "all"}_${item.entry.id}" },
        ) { idx, item ->
            WantQueueLazyRowItem(
                item = item,
                activeQueue = activeQueue,
                readableIds = readableIds,
                isFirst = idx == 0,
                isLast = idx == entries.lastIndex,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                colors = colors,
            )
        }
        item(key = "want_bottom_spacer") { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Suppress("LongParameterList")
@Composable
private fun WantQueueLazyRowItem(
    item: RankedCatalogEntry,
    activeQueue: CatalogListSummary?,
    readableIds: Set<String>,
    isFirst: Boolean,
    isLast: Boolean,
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
        if (!isFirst) HorizontalDivider(color = colors.border, thickness = 1.dp)
        WantQueueRow(
            item = item,
            onOpen = onOpen,
            onComplete = {
                onAction(MainAction.CompleteWantEntry(item.entry.id, rankImmediately = true))
            },
            onRemove = {
                activeQueue?.collectionId?.let { qId ->
                    onAction(MainAction.RemoveWantEntry(qId, item.entry.id))
                }
            },
        )
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

@OptIn(ExperimentalFoundationApi::class)
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
    var menuExpanded by rememberSaveable(item.entry.id) { mutableStateOf(false) }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 52.dp)
                .combinedClickable(
                    onClick = onOpen ?: { menuExpanded = true },
                    onLongClick = { menuExpanded = true },
                )
                .semantics { role = Role.Button }
                .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp)
                .testTag("want_row_${item.entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
        WantQueueRowOverflowMenu(
            entryId = item.entry.id,
            title = item.entry.title,
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            onComplete = onComplete,
            onRemove = onRemove,
        )
    }
}

@Composable
private fun WantQueueRowOverflowMenu(
    entryId: String,
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onComplete: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Box {
        RocheIconActionButton(
            contentDescription = "Options for $title",
            outlined = false,
            onClick = { onExpandedChange(true) },
            modifier = Modifier.testTag("want_more_btn_$entryId"),
        ) {
            Text(
                text = "⋯",
                color = colors.textSecondary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = colors.surfaceElevated,
        ) {
            DropdownMenuItem(
                text = { Text("Finish & Rank", color = colors.textPrimary) },
                onClick = {
                    onExpandedChange(false)
                    onComplete()
                },
            )
            DropdownMenuItem(
                text = { Text("Remove from Want Queue", color = colors.accentIndianRed) },
                onClick = {
                    onExpandedChange(false)
                    onRemove()
                },
                modifier = Modifier.testTag("want_remove_item_$entryId"),
            )
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
    if (queueEntries.isEmpty()) {
        UpNextQueueEmptyState()
        return
    }
    val dragState =
        rememberQueueDragReorderState(
            listState = listState,
            entries = queueEntries,
            onCommitReorder = { orderedIds -> onAction(MainAction.ReorderQueue(orderedIds)) },
        )
    val displayEntries = dragState.resolveDisplayEntries(queueEntries)
    val currentIds = remember(displayEntries) { displayEntries.map { it.id } }
    val canReorder = displayEntries.size > 1
    val isAudioActuallyPlaying = LocalIsAudioActuallyPlaying.current ?: false

    LazyColumn(
        state = listState,
        userScrollEnabled = dragState.draggedEntryId == null,
        modifier = Modifier.fillMaxSize().testTag("queue_list"),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        itemsIndexed(items = displayEntries, key = { _, item -> item.id }) { index, entry ->
            val isDragging = dragState.draggedEntryId == entry.id
            UpNextDraggableQueueSlot(
                entry = entry,
                index = index,
                currentIds = currentIds,
                canReorder = canReorder,
                isDragging = isDragging,
                dragState = dragState,
                isPlayingAudio = entry.id == activeAudioEntryId,
                isAudioActuallyPlaying = isAudioActuallyPlaying,
                isQueuedNextAudio = activeAudioEntryId != null && entry.id == nextQueuedAudioId,
                onAction = onAction,
                modifier =
                    Modifier.fillMaxWidth()
                        .animateItem(
                            placementSpec =
                                if (isDragging) {
                                    null
                                } else {
                                    spring<IntOffset>(stiffness = Spring.StiffnessMediumLow)
                                }
                        ),
            )
        }
    }
}

@Composable
private fun UpNextQueueEmptyState() {
    val colors = LocalRocheColors.current
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
}

@Composable
private fun UpNextDraggableQueueSlot(
    entry: InboxEntry,
    index: Int,
    currentIds: List<String>,
    canReorder: Boolean,
    isDragging: Boolean,
    dragState: QueueDragReorderState,
    isPlayingAudio: Boolean,
    isAudioActuallyPlaying: Boolean,
    isQueuedNextAudio: Boolean,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier =
            modifier.zIndex(if (isDragging) 1f else 0f).graphicsLayer {
                if (isDragging) {
                    translationY = dragState.dragOffsetY
                    shadowElevation = QUEUE_DRAG_ELEVATION_DP.dp.toPx()
                }
            }
    ) {
        QueueEntryRowItem(
            entry = entry,
            queuePosition = index + 1,
            totalCount = currentIds.size,
            canReorder = canReorder,
            canMoveUp = index > 0,
            canMoveDown = index < currentIds.lastIndex,
            isDragging = isDragging,
            dragState = dragState,
            isPlayingAudio = isPlayingAudio,
            isAudioActuallyPlaying = isAudioActuallyPlaying,
            isQueuedNextAudio = isQueuedNextAudio,
            onOpen = { onAction(MainAction.OpenReader(entry.id)) },
            onOpenContextSheet = { onAction(MainAction.OpenContextSheet(entry.id)) },
            onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
            onMoveToTop = { onAction(MainAction.MoveQueueItemToTop(entry.id)) },
            onMoveUp = {
                onAction(MainAction.ReorderQueue(moveItemInList(currentIds, index, index - 1)))
            },
            onMoveDown = {
                onAction(MainAction.ReorderQueue(moveItemInList(currentIds, index, index + 1)))
            },
            onRemoveFromQueue = { onAction(MainAction.RemoveFromQueue(entry.id)) },
            onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)
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
    val isAudioActuallyPlaying = LocalIsAudioActuallyPlaying.current ?: false
    val historyLabel = entry.formattedListeningHistoryLabel ?: "Played"
    val rowBg =
        if (isPlaying) colors.accentBlue.copy(alpha = ROW_HIGHLIGHT_ALPHA) else colors.background
    val starDesc = if (entry.isFavorite) "Unstar ${entry.title}" else "Star ${entry.title}"
    val playingBadge =
        if (isPlaying) {
            if (isAudioActuallyPlaying) "▶ PLAYING" else "⏸ PAUSED"
        } else {
            null
        }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(rowBg)
                .drawLeftAccentBar(if (isPlaying) colors.accentBlue else null)
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
            playingBadge = playingBadge,
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
    playingBadge: String?,
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = historyLabel,
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.weight(1f, fill = false).testTag("history_timestamp_${entry.id}"),
            )
            if (playingBadge != null) {
                Text(
                    text = playingBadge,
                    color = colors.accentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun QueueEntryRowItem(
    entry: InboxEntry,
    queuePosition: Int,
    totalCount: Int,
    canReorder: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isDragging: Boolean,
    dragState: QueueDragReorderState,
    isPlayingAudio: Boolean,
    isAudioActuallyPlaying: Boolean,
    isQueuedNextAudio: Boolean,
    onOpen: () -> Unit,
    onOpenContextSheet: () -> Unit,
    onToggleStar: () -> Unit,
    onMoveToTop: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemoveFromQueue: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val (baseBg, leftAccentColor) = resolveQueueRowColors(colors, isPlayingAudio, isQueuedNextAudio)
    val statusBadge =
        resolveQueueRowStatusBadge(isPlayingAudio, isAudioActuallyPlaying, isQueuedNextAudio)
    val rowStateDesc =
        buildQueueRowStateDescription(
            queuePosition,
            totalCount,
            isPlayingAudio,
            isAudioActuallyPlaying,
            isQueuedNextAudio,
            entry.isFavorite,
        )
    val a11yActions =
        buildQueueRowA11yActions(canMoveUp, canMoveDown, onMoveUp, onMoveDown, onMoveToTop)

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(if (isDragging) colors.surfaceElevated else baseBg)
                .drawLeftAccentBar(if (isDragging) colors.accentBlue else leftAccentColor)
                .clickable(onClick = onOpen)
                .queueLongPressDrag(enabled = canReorder, entryId = entry.id, dragState = dragState)
                .semantics {
                    role = Role.Button
                    stateDescription = rowStateDesc
                    if (a11yActions.isNotEmpty()) customActions = a11yActions
                }
                .padding(start = 12.dp, end = 4.dp, top = 11.dp, bottom = 11.dp)
                .testTag("queue_row_${entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QueueRowLeadingSlot(entry = entry, queuePosition = queuePosition)
        QueueEntryTextColumn(entry, statusBadge, Modifier.weight(1f))
        QueueRowTrailingControls(
            entry,
            canReorder,
            canMoveUp,
            isDragging,
            dragState,
            onMoveToTop,
            onToggleStar,
            onToggleArchive,
            onOpenContextSheet,
            onRemoveFromQueue,
        )
    }
}

private fun buildQueueRowA11yActions(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToTop: () -> Unit,
): List<CustomAccessibilityAction> = buildList {
    if (canMoveUp) {
        add(
            CustomAccessibilityAction("Move up in queue") {
                onMoveUp()
                true
            }
        )
        add(
            CustomAccessibilityAction("Move to top of queue") {
                onMoveToTop()
                true
            }
        )
    }
    if (canMoveDown) {
        add(
            CustomAccessibilityAction("Move down in queue") {
                onMoveDown()
                true
            }
        )
    }
}

private fun resolveQueueRowColors(
    colors: com.grayvines.roche.ui.theme.RocheColors,
    isPlayingAudio: Boolean,
    isQueuedNextAudio: Boolean,
): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color?> =
    when {
        isPlayingAudio -> colors.accentBlue.copy(alpha = ROW_HIGHLIGHT_ALPHA) to colors.accentBlue
        isQueuedNextAudio ->
            colors.accentVerdigris.copy(alpha = QUEUE_NEXT_ALPHA) to colors.accentVerdigris
        else -> colors.background to null
    }

@Composable
private fun QueueRowLeadingSlot(
    entry: InboxEntry,
    queuePosition: Int,
) {
    val colors = LocalRocheColors.current
    Text(
        text = "$queuePosition",
        color = colors.textMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        textAlign = TextAlign.End,
        modifier = Modifier.width(18.dp),
    )
    if (entry.hasThumbnailSource()) {
        EntryThumbnailPreview(entry = entry, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun QueueEntryTextColumn(
    entry: InboxEntry,
    statusBadge: QueueRowStatusBadge?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val badgeColor =
        when (statusBadge?.tone) {
            QueueRowBadgeTone.ACTIVE_AUDIO -> colors.accentBlue
            QueueRowBadgeTone.UP_NEXT_AUDIO -> colors.accentVerdigris
            null -> null
        }
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
            trailingBadge = statusBadge?.label,
            trailingBadgeColor = badgeColor,
            showOfflineIndicator = true,
            showStarDot = entry.isFavorite,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun QueueRowTrailingControls(
    entry: InboxEntry,
    canReorder: Boolean,
    canMoveUp: Boolean,
    isDragging: Boolean,
    dragState: QueueDragReorderState,
    onMoveToTop: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
    onOpenContextSheet: () -> Unit,
    onRemoveFromQueue: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (canReorder) {
            QueueDragGripHandle(
                entryId = entry.id,
                isDragging = isDragging,
                dragState = dragState,
            )
        }
        QueueRowOverflowMenu(
            entry = entry,
            canMoveUp = canMoveUp,
            onMoveToTop = onMoveToTop,
            onToggleStar = onToggleStar,
            onToggleArchive = onToggleArchive,
            onOpenContextSheet = onOpenContextSheet,
            onRemoveFromQueue = onRemoveFromQueue,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun QueueRowOverflowMenu(
    entry: InboxEntry,
    canMoveUp: Boolean,
    onMoveToTop: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
    onOpenContextSheet: () -> Unit,
    onRemoveFromQueue: () -> Unit,
) {
    val colors = LocalRocheColors.current
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }

    Box {
        RocheIconActionButton(
            contentDescription = "Queue options for ${entry.title}",
            onClick = { expanded = true },
            outlined = false,
            modifier = Modifier.testTag("queue_more_btn_${entry.id}"),
        ) {
            MoreDotsIcon(tint = colors.textSecondary, size = 16.dp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceElevated,
        ) {
            QueueOverflowMenuItems(
                entry = entry,
                canMoveUp = canMoveUp,
                onDismiss = { expanded = false },
                onMoveToTop = onMoveToTop,
                onToggleStar = onToggleStar,
                onToggleArchive = onToggleArchive,
                onOpenContextSheet = onOpenContextSheet,
                onRemoveFromQueue = onRemoveFromQueue,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun QueueOverflowMenuItems(
    entry: InboxEntry,
    canMoveUp: Boolean,
    onDismiss: () -> Unit,
    onMoveToTop: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
    onOpenContextSheet: () -> Unit,
    onRemoveFromQueue: () -> Unit,
) {
    val colors = LocalRocheColors.current
    if (canMoveUp) {
        QueueOverflowOption("Move to Top", colors.textPrimary, "queue_move_top_item_${entry.id}") {
            onDismiss()
            onMoveToTop()
        }
    }
    QueueOverflowOption(if (entry.isFavorite) "Remove Star" else "Star", colors.textPrimary) {
        onDismiss()
        onToggleStar()
    }
    QueueOverflowOption(if (entry.isDismissed) "Mark New" else "Dismiss", colors.textPrimary) {
        onDismiss()
        onToggleArchive()
    }
    QueueOverflowOption("Details & Tags…", colors.textPrimary) {
        onDismiss()
        onOpenContextSheet()
    }
    QueueOverflowOption(
        "Remove from Queue",
        colors.accentIndianRed,
        "queue_remove_item_${entry.id}",
    ) {
        onDismiss()
        onRemoveFromQueue()
    }
}

@Composable
private fun QueueOverflowOption(
    label: String,
    textColor: androidx.compose.ui.graphics.Color,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, color = textColor) },
        onClick = onClick,
        modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
    )
}

private const val ROW_HIGHLIGHT_ALPHA = 0.12f
private const val QUEUE_NEXT_ALPHA = 0.11f
private const val QUEUE_DRAG_ELEVATION_DP = 8
