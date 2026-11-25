@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyListScope
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
import com.grayvines.roche.core.formatInlineMathAnnotated
import com.grayvines.roche.data.catalog.CatalogListSummary
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.RankedCatalogEntry
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.extractCleanDomain
import com.grayvines.roche.ui.components.CatalogCoverThumbnail
import com.grayvines.roche.ui.components.HeaderActionButton
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
    val catalogRanked = state.starredCollection.entries
    val seenIds = HashSet<String>(readerById.size + catalogRanked.size)
    val includeRemainingReaders = !state.starredCollection.hasMore
    val starredIds = state.catalog.starredEntryIds
    return buildList {
        catalogRanked.forEach { catItem ->
            if (seenIds.add(catItem.entry.id)) {
                add(toStarredDisplayRow(catItem, readerById))
            }
        }
        state.starredEntries
            .filter { includeRemainingReaders || it.id !in starredIds }
            .forEach { readerEntry ->
                if (seenIds.add(readerEntry.id)) {
                    add(StarredDisplayRow.ReaderRow(readerEntry))
                }
            }
    }
}

private fun toStarredDisplayRow(
    catItem: RankedCatalogEntry,
    readerById: Map<String, InboxEntry>,
): StarredDisplayRow =
    readerById[catItem.entry.id]?.let(StarredDisplayRow::ReaderRow)
        ?: StarredDisplayRow.CatalogRow(catItem)

private val STARRED_INBOX_KINDS =
    setOf(EntryKind.ARTICLE, EntryKind.PAPER, EntryKind.VIDEO, EntryKind.PODCAST_EPISODE)

private val STARRED_CATALOG_KINDS: Set<EntryKind> =
    EntryKind.entries.filterTo(LinkedHashSet()) { it !in STARRED_INBOX_KINDS }

private enum class StarredFilterTab(
    val key: String,
    val label: String,
    val emptyPluralLabel: String,
    val testTag: String,
    val sqlKinds: Set<EntryKind>,
) {
    ALL("all", "All", "items", "starred_filter_all", emptySet()),
    ARTICLES(
        "articles",
        "Articles",
        "articles",
        "starred_filter_articles",
        setOf(EntryKind.ARTICLE),
    ),
    PAPERS("papers", "Papers", "papers", "starred_filter_papers", setOf(EntryKind.PAPER)),
    VIDEOS(
        "videos",
        "Videos",
        "videos",
        "starred_filter_videos",
        setOf(EntryKind.VIDEO, EntryKind.CHANNEL),
    ),
    PODCASTS(
        "podcasts",
        "Podcasts",
        "podcasts",
        "starred_filter_podcasts",
        setOf(EntryKind.PODCAST_EPISODE, EntryKind.PODCAST),
    ),
    AUDIO(
        "audio",
        "Audio",
        "audio items",
        "starred_filter_audio",
        setOf(EntryKind.PODCAST_EPISODE, EntryKind.PODCAST),
    ),
    CATALOG(
        "catalog",
        "Catalog",
        "catalog items",
        "starred_filter_catalog",
        STARRED_CATALOG_KINDS,
    ),
}

private val STARRED_READER_FILTER_KINDS =
    setOf(
        EntryKind.ARTICLE,
        EntryKind.PAPER,
        EntryKind.VIDEO,
        EntryKind.PODCAST_EPISODE,
        EntryKind.PODCAST,
        EntryKind.CHANNEL,
    )

private data class StarredFilterChipSpec(
    val key: String,
    val label: String,
    val emptyPluralLabel: String,
    val testTag: String,
    val sqlKinds: Set<EntryKind>,
    val fixedTab: StarredFilterTab? = null,
    val catalogKind: EntryKind? = null,
)

private fun buildStarredFilterChips(
    allCount: Int,
    kindCounts: List<Pair<EntryKind, Int>>,
): List<StarredFilterChipSpec> = buildList {
    StarredFilterTab.entries.forEach { tab ->
        add(
            StarredFilterChipSpec(
                key = tab.key,
                label = if (tab == StarredFilterTab.ALL) "All ($allCount)" else tab.label,
                emptyPluralLabel = tab.emptyPluralLabel,
                testTag = tab.testTag,
                sqlKinds = tab.sqlKinds,
                fixedTab = tab,
            )
        )
    }
    kindCounts.forEach { (kind, count) ->
        if (kind !in STARRED_READER_FILTER_KINDS && count > 0) {
            add(
                StarredFilterChipSpec(
                    key = "kind_${kind.wireValue}",
                    label = "${kind.systemListTitle} ($count)",
                    emptyPluralLabel = kind.systemListTitle.lowercase(),
                    testTag = "starred_filter_${kind.wireValue}",
                    sqlKinds = setOf(kind),
                    catalogKind = kind,
                )
            )
        }
    }
}

private fun matchesStarredChip(row: StarredDisplayRow, chip: StarredFilterChipSpec): Boolean {
    val fixed = chip.fixedTab
    if (fixed != null) return matchesStarredFilter(row, fixed)
    val targetKind = chip.catalogKind ?: return true
    return when (row) {
        is StarredDisplayRow.ReaderRow -> row.entry.entryKind == targetKind
        is StarredDisplayRow.CatalogRow -> row.item.entry.kind == targetKind
    }
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
    val orderedRows =
        remember(
            state.starredEntries,
            state.starredCollection.entries,
            state.starredCollection.hasMore,
            state.catalog.starredEntryIds,
        ) {
            buildOrderedStarredRows(state)
        }
    val totalCount = resolveStarredTotalCount(state, orderedRows.size)
    val filterChips =
        remember(totalCount, state.starredCollection.kindCounts) {
            buildStarredFilterChips(totalCount, state.starredCollection.kindCounts)
        }
    val selectedFilterKey =
        state.starredCollection.requestedFilterKey
            ?: state.starredCollection.selectedFilterKey
            ?: StarredFilterTab.ALL.key
    val activeChip =
        remember(filterChips, selectedFilterKey) {
            filterChips.firstOrNull { it.key == selectedFilterKey } ?: filterChips.first()
        }
    val visibleRows =
        remember(orderedRows, activeChip) {
            orderedRows.filter { matchesStarredChip(it, activeChip) }
        }
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
                chips = filterChips,
                selectedKey = activeChip.key,
                onSelectChip = { dispatchStarredChipAction(it, onAction) },
            )
        },
    ) {
        StarredTabBody(
            visibleRows = visibleRows,
            activeChip = activeChip,
            state = state,
            activeAudioEntryId = activeAudioEntryId,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
            listState = listState,
        )
    }
}

private fun resolveStarredTotalCount(state: MainUiState, orderedCount: Int): Int =
    if (state.starredCollection.isLoaded) {
        maxOf(state.starredCollection.totalCount, orderedCount)
    } else {
        maxOf(orderedCount, state.catalog.starredEntryIds.size)
    }

private fun dispatchStarredChipAction(chip: StarredFilterChipSpec, onAction: (MainAction) -> Unit) {
    onAction(
        MainAction.SetStarredKindFilter(
            kind = chip.sqlKinds.singleOrNull(),
            filterKey = chip.key,
            kinds = chip.sqlKinds,
        )
    )
}

@Suppress("LongParameterList")
@Composable
private fun StarredTabBody(
    visibleRows: List<StarredDisplayRow>,
    activeChip: StarredFilterChipSpec,
    state: MainUiState,
    activeAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    listState: LazyListState,
) {
    val loadedKey = state.starredCollection.selectedFilterKey ?: StarredFilterTab.ALL.key
    val isFilterSettled = state.starredCollection.isLoaded && loadedKey == activeChip.key
    val remainingToLoad =
        (state.starredCollection.filteredTotalCount - state.starredCollection.entries.size)
            .coerceAtLeast(0)
    if (visibleRows.isEmpty()) {
        if (isFilterSettled && state.starredCollection.hasMore) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                StarredLoadMoreButton(
                    isLoadingMore = state.starredCollection.isLoadingMore,
                    remainingCount = remainingToLoad,
                    onLoadMore = { onAction(MainAction.LoadMoreStarredCollection) },
                )
            }
        } else if (isFilterSettled || state.catalog.starredEntryIds.isEmpty()) {
            StarredEmptyPlaceholder(activeChip = activeChip)
        }
        return
    }
    StarredEntriesList(
        rows = visibleRows,
        state = state,
        activeAudioEntryId = activeAudioEntryId,
        hasMore = state.starredCollection.hasMore,
        isLoadingMore = state.starredCollection.isLoadingMore,
        remainingCount = remainingToLoad,
        onAction = onAction,
        onOpenExternalUrl = onOpenExternalUrl,
        listState = listState,
    )
}

@Composable
private fun StarredEmptyPlaceholder(activeChip: StarredFilterChipSpec) {
    val colors = LocalRocheColors.current
    val message =
        if (activeChip.fixedTab == StarredFilterTab.ALL) {
            "No starred entries yet. Tap ★ on any post or catalog item to collect favorites here."
        } else {
            "No starred ${activeChip.emptyPluralLabel} yet."
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
    chips: List<StarredFilterChipSpec>,
    selectedKey: String,
    onSelectChip: (StarredFilterChipSpec) -> Unit,
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
        chips.forEach { chip ->
            RocheStripTab(
                label = chip.label,
                selected = selectedKey == chip.key,
                onClick = { onSelectChip(chip) },
                modifier = Modifier.testTag(chip.testTag),
            )
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun StarredEntriesList(
    rows: List<StarredDisplayRow>,
    state: MainUiState,
    activeAudioEntryId: String?,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    remainingCount: Int,
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
            if (hasMore) {
                item(key = "starred_load_more") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        StarredLoadMoreButton(
                            isLoadingMore = isLoadingMore,
                            remainingCount = remainingCount,
                            onLoadMore = { onAction(MainAction.LoadMoreStarredCollection) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StarredLoadMoreButton(
    isLoadingMore: Boolean,
    remainingCount: Int,
    onLoadMore: () -> Unit,
) {
    val colors = LocalRocheColors.current
    TextButton(
        enabled = !isLoadingMore,
        onClick = onLoadMore,
        modifier = Modifier.testTag("starred_load_more_btn"),
    ) {
        Text(
            text =
                when {
                    isLoadingMore -> "Loading…"
                    remainingCount > 0 -> "Load More ($remainingCount remaining)"
                    else -> "Load More"
                },
            color = colors.accentBlue,
            fontSize = 13.sp,
        )
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
    listState: LazyListState = rememberLazyListState(),
    activeAudioEntryId: String? = null,
    onOpenExternalUrl: (String) -> Unit = {},
) {
    val inProgressNotInWant =
        remember(state.inProgressItems, state.catalog.wantEntryIds) {
            state.inProgressItems.count { it.entryId !in state.catalog.wantEntryIds }
        }
    val countBadge = state.catalog.wantItemCount + inProgressNotInWant

    RocheRefreshableTabScaffold(
        isRefreshing = state.isManualRefreshing,
        onRefresh = { onAction(MainAction.ManualRefresh) },
        pullToRefreshTestTag = "queue_pull_to_refresh",
        header = {
            QueueHeaderBar(
                countBadge = countBadge,
                showBackToHome = state.isUnpinnedDestination(AppTab.QUEUE),
                onBackToHome = { onAction(MainAction.SelectTab(AppTab.HOME)) },
                onOpenSearch = { onAction(MainAction.OpenSearch) },
            )
        },
    ) {
        UnifiedQueuePane(
            state = state,
            listState = listState,
            activeAudioEntryId = activeAudioEntryId,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

@Composable
private fun QueueHeaderBar(
    countBadge: Int,
    showBackToHome: Boolean,
    onBackToHome: () -> Unit,
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
private fun UnifiedQueuePane(
    state: MainUiState,
    listState: LazyListState,
    activeAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val queues = state.catalog.wantQueues
    val inProgress = state.inProgressItems
    if (inProgress.isEmpty() && queues.all { it.entryCount == 0 }) {
        UnifiedQueueEmptyState(onAction = onAction)
        return
    }
    val activeQueue =
        remember(queues, state.selectedWantCollectionId) {
            resolveActiveWantQueue(queues, state.selectedWantCollectionId)
        }
    val entries = resolveLoadedWantQueueEntries(activeQueue, state.activeWantQueue)
    val isLoadingQueue = isWantQueueLoading(activeQueue, state.activeWantQueue)
    val inProgressBadges = remember(inProgress) { buildInProgressBadgeMap(inProgress) }

    UnifiedQueueLazyList(
        state = state,
        listState = listState,
        inProgress = inProgress,
        queues = queues,
        activeQueue = activeQueue,
        entries = entries,
        isLoadingQueue = isLoadingQueue,
        inProgressBadges = inProgressBadges,
        activeAudioEntryId = activeAudioEntryId,
        onAction = onAction,
        onOpenExternalUrl = onOpenExternalUrl,
    )
}

@Composable
private fun UnifiedQueueEmptyState(onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    Box(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag("queue_empty_state"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Your Queue is clear",
                color = colors.textPrimary,
                fontFamily = currentHeadlineFontFamily(),
                fontSize = 18.sp,
            )
            Text(
                text =
                    "Save articles, PDFs, movies, books, albums, or games you want to experience next, or start one to track it in progress.",
                color = colors.textMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeaderActionButton(
                    label = "+ Save URL",
                    style = RocheButtonStyle.PRIMARY,
                    onClick = { onAction(MainAction.OpenAddSheet()) },
                    modifier = Modifier.testTag("queue_empty_add_url_btn"),
                )
                HeaderActionButton(
                    label = "+ Add Work",
                    onClick = { onAction(MainAction.OpenQuickLogSheet(defaultToWant = true)) },
                    modifier = Modifier.testTag("queue_empty_add_work_btn"),
                )
            }
        }
    }
}

private fun resolveLoadedWantQueueEntries(
    activeQueue: CatalogListSummary?,
    wantState: CatalogCollectionUiState,
): List<RankedCatalogEntry> =
    if (activeQueue != null && wantState.collectionId == activeQueue.collectionId) {
        wantState.entries
    } else {
        emptyList()
    }

private fun isWantQueueLoading(
    activeQueue: CatalogListSummary?,
    wantState: CatalogCollectionUiState,
): Boolean =
    activeQueue != null &&
        activeQueue.entryCount > 0 &&
        (!wantState.isLoaded || wantState.collectionId != activeQueue.collectionId)

private fun buildInProgressBadgeMap(inProgress: List<InProgressItem>): Map<String, String> =
    inProgress.associate { item ->
        item.entryId to
            when (item) {
                is InProgressItem.ReaderItem -> "${item.entry.formattedProgressPct}%"
                is InProgressItem.StartedCatalogItem -> "Started"
            }
    }

@Composable
@Suppress("LongParameterList")
private fun UnifiedQueueLazyList(
    state: MainUiState,
    listState: LazyListState,
    inProgress: List<InProgressItem>,
    queues: List<CatalogListSummary>,
    activeQueue: CatalogListSummary?,
    entries: List<RankedCatalogEntry>,
    isLoadingQueue: Boolean,
    inProgressBadges: Map<String, String>,
    activeAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize().testTag("queue_list"),
    ) {
        if (inProgress.isNotEmpty()) {
            inProgressQueueItems(
                inProgress = inProgress,
                readableIds = state.readableEntryIds,
                activeAudioEntryId = activeAudioEntryId,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                colors = colors,
            )
        }
        if (queues.size > 1) {
            item(key = "want_queue_picker") {
                WantQueuePickerRow(
                    queues = queues,
                    activeCollectionId = activeQueue?.collectionId,
                    onSelect = { onAction(MainAction.SelectWantCollection(it)) },
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        if (activeQueue != null) {
            activeWantQueueItems(
                activeQueue = activeQueue,
                entries = entries,
                isLoadingQueue = isLoadingQueue,
                readableIds = state.readableEntryIds,
                inProgressBadges = inProgressBadges,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                colors = colors,
            )
        }
        item(key = "want_bottom_spacer") { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Suppress("LongParameterList")
private fun LazyListScope.inProgressQueueItems(
    inProgress: List<InProgressItem>,
    readableIds: Set<String>,
    activeAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    colors: com.grayvines.roche.ui.theme.RocheColors,
) {
    item(key = "queue_in_progress_head") {
        InProgressSectionHeader(count = inProgress.size)
        Spacer(modifier = Modifier.height(8.dp))
    }
    itemsIndexed(items = inProgress, key = { _, item -> "in_progress_${item.entryId}" }) { idx, item
        ->
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .lazyGroupedCardRow(
                        isFirst = idx == 0,
                        isLast = idx == inProgress.lastIndex,
                        surfaceColor = colors.surface,
                        borderColor = colors.border,
                    )
        ) {
            if (idx > 0) HorizontalDivider(color = colors.border, thickness = 1.dp)
            InProgressRowDispatcher(
                item = item,
                readableIds = readableIds,
                activeAudioEntryId = activeAudioEntryId,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                testTagPrefix = "queue_in_progress",
            )
        }
    }
    item(key = "queue_in_progress_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
}

@Suppress("LongParameterList")
private fun LazyListScope.activeWantQueueItems(
    activeQueue: CatalogListSummary,
    entries: List<RankedCatalogEntry>,
    isLoadingQueue: Boolean,
    readableIds: Set<String>,
    inProgressBadges: Map<String, String>,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    colors: com.grayvines.roche.ui.theme.RocheColors,
) {
    item(key = "want_head_${activeQueue.collectionId}") {
        WantQueueSectionHeader(
            queue = activeQueue,
            hasEntries = entries.isNotEmpty(),
            canPrioritize = entries.size >= 2,
            onAction = onAction,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (entries.isEmpty()) {
        if (!isLoadingQueue) {
            item(key = "want_empty_${activeQueue.collectionId}") {
                WantQueueEmptySection(activeQueue = activeQueue, onAction = onAction)
            }
        }
        return
    }
    itemsIndexed(
        items = entries,
        key = { _, item -> "${activeQueue.collectionId}_${item.entry.id}" },
    ) { idx, item ->
        WantQueueLazyRowItem(
            item = item,
            activeQueue = activeQueue,
            readableIds = readableIds,
            inProgressBadge = inProgressBadges[item.entry.id],
            isFirst = idx == 0,
            isLast = idx == entries.lastIndex,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
            colors = colors,
        )
    }
}

@Composable
private fun InProgressSectionHeader(count: Int) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = 2.dp).testTag("queue_in_progress_section"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "IN PROGRESS",
            color = colors.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = count.toString(),
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            queues.forEach { queue ->
                RocheStripTab(
                    label = "${queue.title} (${queue.entryCount})",
                    selected = queue.collectionId == activeCollectionId,
                    onClick = { onSelect(queue.collectionId) },
                    modifier = Modifier.testTag("want_queue_tab_${queue.slug}"),
                )
            }
        }
        HorizontalDivider(color = colors.border, thickness = 1.dp)
    }
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

@Suppress("LongParameterList")
@Composable
private fun WantQueueLazyRowItem(
    item: RankedCatalogEntry,
    activeQueue: CatalogListSummary?,
    readableIds: Set<String>,
    inProgressBadge: String?,
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
            inProgressBadge = inProgressBadge,
            onOpen = onOpen,
            onFinish = {
                onAction(MainAction.CompleteWantEntry(item.entry.id, rankImmediately = false))
            },
            onFinishAndRank = {
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
    hasEntries: Boolean,
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
            if (hasEntries) {
                if (queue.collectionId == WANT_TO_READ_COLLECTION_ID) {
                    TextButton(
                        onClick = { onAction(MainAction.OpenAddSheet()) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("want_to_read_add_url_btn"),
                    ) {
                        Text(text = "+ Add URL", color = colors.accentBlue, fontSize = 12.sp)
                    }
                } else {
                    TextButton(
                        onClick = {
                            onAction(
                                MainAction.OpenQuickLogSheet(
                                    defaultToWant = true,
                                    prefillKind = EntryKind.inferForCollection(queue),
                                )
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("want_add_work_btn"),
                    ) {
                        Text(text = "+ Add", color = colors.accentBlue, fontSize = 12.sp)
                    }
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
@Suppress("LongParameterList")
private fun WantQueueRow(
    item: RankedCatalogEntry,
    inProgressBadge: String?,
    onOpen: (() -> Unit)?,
    onFinish: () -> Unit,
    onFinishAndRank: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalRocheColors.current
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
        WantQueueRowMetaColumn(
            item = item,
            inProgressBadge = inProgressBadge,
            modifier = Modifier.weight(1f),
        )
        HeaderActionButton(label = "✓ Done", onClick = onFinish, horizontalPadding = 10.dp)
        WantQueueRowOverflowMenu(
            entryId = item.entry.id,
            title = item.entry.title,
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            onFinishAndRank = onFinishAndRank,
            onRemove = onRemove,
        )
    }
}

@Composable
private fun WantQueueRowMetaColumn(
    item: RankedCatalogEntry,
    inProgressBadge: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val meta = remember(item.entry) { buildWantMetaLine(item) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = remember(item.entry.title) { formatInlineMathAnnotated(item.entry.title) },
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
            if (inProgressBadge != null) {
                RocheMetaPill(text = inProgressBadge, textColor = colors.accentBlue)
            }
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
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
@Suppress("LongParameterList")
private fun WantQueueRowOverflowMenu(
    entryId: String,
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onFinishAndRank: () -> Unit,
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
                    onFinishAndRank()
                },
            )
            DropdownMenuItem(
                text = { Text("Remove from Queue", color = colors.accentIndianRed) },
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
