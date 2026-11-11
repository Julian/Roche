@file:Suppress("TooManyFunctions", "LongMethod", "CognitiveComplexMethod")

package com.grayvines.roche.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.SearchScopeFilter
import com.grayvines.roche.data.extractSearchMatchSnippet
import com.grayvines.roche.data.matchesSearchScopeFilter
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedSortOrder
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.EntryThumbnailPreview
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.FilterFunnelIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RefreshIcon
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalRocheColors

@Composable
fun StarredTabContent(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    var selectedTypeFilter by rememberSaveable { mutableStateOf<ContentType?>(null) }
    val allStarred = state.starredEntries
    val starredEntries =
        remember(allStarred, selectedTypeFilter) {
            val filter = selectedTypeFilter
            if (filter == null) allStarred else allStarred.filter { it.contentType == filter }
        }

    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(
            title = "Starred",
            subtitle = "${starredEntries.size} saved",
        ) {
            RocheIconActionButton(
                contentDescription = "Sync",
                onClick = { onAction(MainAction.SyncNow) },
            ) {
                RefreshIcon(tint = colors.textSecondary, size = 17.dp)
            }
        }
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(colors.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChipPill(
                label = "All (${allStarred.size})",
                selected = selectedTypeFilter == null,
                onClick = { selectedTypeFilter = null },
                modifier = Modifier.testTag("starred_filter_all"),
            )
            FilterChipPill(
                label = "Podcasts",
                selected = selectedTypeFilter == ContentType.AUDIO,
                onClick = { selectedTypeFilter = ContentType.AUDIO },
                modifier = Modifier.testTag("starred_filter_podcasts"),
            )
            FilterChipPill(
                label = "Articles",
                selected = selectedTypeFilter == ContentType.ARTICLE,
                onClick = { selectedTypeFilter = ContentType.ARTICLE },
                modifier = Modifier.testTag("starred_filter_articles"),
            )
            FilterChipPill(
                label = "Papers",
                selected = selectedTypeFilter == ContentType.PDF,
                onClick = { selectedTypeFilter = ContentType.PDF },
                modifier = Modifier.testTag("starred_filter_papers"),
            )
            FilterChipPill(
                label = "Videos",
                selected = selectedTypeFilter == ContentType.VIDEO,
                onClick = { selectedTypeFilter = ContentType.VIDEO },
                modifier = Modifier.testTag("starred_filter_videos"),
            )
        }
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        if (starredEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No starred entries yet. Tap ★ on any post to save it to Starred.",
                    color = colors.textMuted,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("starred_list"),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(items = starredEntries, key = { it.id }) { entry ->
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
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
fun SearchTabContent(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    var scopeFilter by rememberSaveable { mutableStateOf(SearchScopeFilter.ALL) }
    val visibleResults =
        remember(state.searchResults, scopeFilter) {
            state.searchResults.filter { matchesSearchScopeFilter(it, scopeFilter) }
        }
    val subtitleText =
        if (state.searchQuery.isBlank() && scopeFilter == SearchScopeFilter.ALL) {
            "Type to search ${state.allEntries.size} entries"
        } else {
            "${visibleResults.size} matches"
        }

    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(
            title = "Search",
            subtitle = subtitleText,
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { onAction(MainAction.UpdateSearchQuery(it)) },
                placeholder = {
                    Text("Search titles, full text, authors, feeds, #tags…", fontSize = 13.sp)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("search_input"),
            )
            Spacer(modifier = Modifier.height(8.dp))
            SearchScopeFilterStrip(
                selectedScope = scopeFilter,
                onSelectScope = { scopeFilter = it },
            )
            if (state.tags.isNotEmpty() || state.searchQuery.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                SearchQuickTagStrip(state = state, onAction = onAction)
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        SearchResultsBody(
            results = visibleResults,
            searchQuery = state.searchQuery,
            selectedScope = scopeFilter,
            ultraDense = state.settings.ultraDenseRows,
            swipeToArchive = state.settings.swipeToArchive,
            playingEntryId = state.playerState.currentEntryId,
            nextQueuedAudioEntryId = state.nextQueuedAudioEntryId,
            onAction = onAction,
        )
    }
}

@Composable
internal fun SearchScopeFilterStrip(
    selectedScope: SearchScopeFilter,
    onSelectScope: (SearchScopeFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SearchScopeFilter.entries.forEach { scope ->
            FilterChipPill(
                label = scope.label,
                selected = selectedScope == scope,
                onClick = { onSelectScope(scope) },
                modifier = Modifier.testTag("search_scope_${scope.name.lowercase()}"),
            )
        }
    }
}

@Composable
internal fun SearchResultsBody(
    results: List<InboxEntry>,
    searchQuery: String,
    selectedScope: SearchScopeFilter = SearchScopeFilter.ALL,
    ultraDense: Boolean,
    swipeToArchive: Boolean,
    playingEntryId: String? = null,
    nextQueuedAudioEntryId: String? = null,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    if (results.isEmpty()) {
        val queryLabel = searchQuery.ifBlank { "current filter" }
        val emptyMessage =
            if (selectedScope == SearchScopeFilter.ALL) {
                "No entries match \"$queryLabel\"."
            } else {
                "No results in ${selectedScope.label} for \"$queryLabel\"."
            }
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyMessage,
                color = colors.textMuted,
                fontSize = 13.sp,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("search_results_list"),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            items(items = results, key = { it.id }) { entry ->
                InboxEntryRowItem(
                    entry = entry,
                    ultraDense = ultraDense,
                    swipeToArchiveEnabled = swipeToArchive,
                    isPlayingAudio = playingEntryId == entry.id,
                    isQueuedNextAudio =
                        nextQueuedAudioEntryId == entry.id && playingEntryId != entry.id,
                    onOpen = { onAction(MainAction.OpenReader(entry.id)) },
                    onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
                    onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                    onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
                    onDownloadOffline = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
                )
                val matchSnippet =
                    remember(entry.id, searchQuery) {
                        extractSearchMatchSnippet(entry = entry, rawQuery = searchQuery)
                    }
                if (matchSnippet != null) {
                    SearchMatchContextBanner(
                        entryId = entry.id,
                        snippet = matchSnippet,
                        query = searchQuery,
                        onClick = { onAction(MainAction.OpenReader(entry.id)) },
                    )
                }
                HorizontalDivider(color = colors.border, thickness = 1.dp)
            }
        }
    }
}

@Composable
internal fun SearchMatchContextBanner(
    entryId: String,
    snippet: String,
    query: String,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val cleanQuery = query.trim().trimStart('#')
    val highlighted =
        remember(snippet, cleanQuery, colors.accentEcru) {
            buildAnnotatedString {
                if (cleanQuery.isBlank()) {
                    append(snippet)
                } else {
                    val idx = snippet.indexOf(cleanQuery, ignoreCase = true)
                    if (idx == -1) {
                        append(snippet)
                    } else {
                        append(snippet.substring(0, idx))
                        withStyle(
                            SpanStyle(
                                color = colors.accentEcru,
                                fontWeight = FontWeight.SemiBold,
                            )
                        ) {
                            append(snippet.substring(idx, idx + cleanQuery.length))
                        }
                        append(snippet.substring(idx + cleanQuery.length))
                    }
                }
            }
        }

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("search_match_snippet_$entryId")
    ) {
        Text(
            text = highlighted,
            color = colors.textSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchQuickTagStrip(state: MainUiState, onAction: (MainAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.searchQuery.isNotBlank()) {
            FilterChipPill(
                label = "Clear ✕",
                selected = true,
                onClick = { onAction(MainAction.UpdateSearchQuery("")) },
            )
        }
        state.tags.forEach { tag ->
            val tagToken = "#${tag.name}"
            val isSelected = state.searchQuery.trim().equals(tagToken, ignoreCase = true)
            FilterChipPill(
                label = tagToken,
                selected = isSelected,
                onClick = {
                    val nextQuery = if (isSelected) "" else tagToken
                    onAction(MainAction.UpdateSearchQuery(nextQuery))
                },
            )
        }
    }
}

@Composable
fun FeedsAndTagsTabContent(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    var showFilterDrawer by rememberSaveable { mutableStateOf(false) }
    var sortOrder by rememberSaveable { mutableStateOf(FeedSortOrder.ALPHABETICAL) }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }

    val activeFeeds = state.activeFeeds
    val archivedFeeds = state.archivedFeeds
    val totalUnread = remember(activeFeeds) { activeFeeds.sumOf { it.unreadCount } }
    val subtitle =
        if (totalUnread > 0) {
            "${activeFeeds.size} feeds · $totalUnread unread"
        } else {
            "${activeFeeds.size} feeds"
        }
    val visibleFeeds =
        remember(activeFeeds, sortOrder, unreadOnly) {
            sortAndFilterFeeds(activeFeeds, sortOrder, unreadOnly)
        }
    val hasActiveFilter = showFilterDrawer || unreadOnly || sortOrder != FeedSortOrder.ALPHABETICAL

    Column(modifier = Modifier.fillMaxSize()) {
        FeedsTopHeaderBar(
            subtitle = subtitle,
            hasActiveFilter = hasActiveFilter,
            onToggleFilterDrawer = { showFilterDrawer = !showFilterDrawer },
            onSync = { onAction(MainAction.SyncNow) },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        if (showFilterDrawer) {
            FeedsFilterDrawer(
                state = state,
                sortOrder = sortOrder,
                unreadOnly = unreadOnly,
                onSelectSortOrder = { sortOrder = it },
                onToggleUnreadOnly = { unreadOnly = !unreadOnly },
                onSelectTag = { tagName ->
                    showFilterDrawer = false
                    onAction(MainAction.FilterByTag(tagName))
                },
            )
        }

        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = { onAction(MainAction.SyncNow) },
            modifier = Modifier.fillMaxSize().testTag("feeds_pull_to_refresh"),
        ) {
            SubscribedFeedsCardList(
                feeds = visibleFeeds,
                archivedFeeds = archivedFeeds,
                hasFeedsAtAll = activeFeeds.isNotEmpty(),
                onAction = onAction,
            )
        }
    }
}

@Composable
internal fun FeedsTopHeaderBar(
    subtitle: String,
    hasActiveFilter: Boolean,
    onToggleFilterDrawer: () -> Unit,
    onSync: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheTopHeader(
        title = "Feeds",
        subtitle = subtitle,
    ) {
        RocheIconActionButton(
            contentDescription = "Filter Feeds & Tags",
            onClick = onToggleFilterDrawer,
            modifier = Modifier.testTag("feeds_filter_btn"),
        ) {
            FilterFunnelIcon(
                tint = if (hasActiveFilter) colors.accentBlue else colors.textSecondary,
                size = 17.dp,
            )
        }
        RocheIconActionButton(
            contentDescription = "Sync Feeds",
            onClick = onSync,
            modifier = Modifier.testTag("feeds_sync_btn"),
        ) {
            RefreshIcon(tint = colors.textSecondary, size = 17.dp)
        }
    }
}

internal fun sortAndFilterFeeds(
    feeds: List<FeedItem>,
    sortOrder: FeedSortOrder,
    unreadOnly: Boolean,
): List<FeedItem> {
    val base = if (unreadOnly) feeds.filter { it.unreadCount > 0 } else feeds
    val alphaComparator =
        compareBy<FeedItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.title }
    return when (sortOrder) {
        FeedSortOrder.ALPHABETICAL -> base.sortedWith(alphaComparator)
        FeedSortOrder.UNREAD_FIRST ->
            base.sortedWith(compareByDescending<FeedItem> { it.unreadCount }.then(alphaComparator))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedsFilterDrawer(
    state: MainUiState,
    sortOrder: FeedSortOrder,
    unreadOnly: Boolean,
    onSelectSortOrder: (FeedSortOrder) -> Unit,
    onToggleUnreadOnly: () -> Unit,
    onSelectTag: (String?) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("feeds_filter_drawer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "VIEW & SORT",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FeedSortOrder.entries.forEach { order ->
                FilterChipPill(
                    label = order.label,
                    selected = sortOrder == order,
                    onClick = { onSelectSortOrder(order) },
                )
            }
            FilterChipPill(
                label = "Unread Feeds Only",
                selected = unreadOnly,
                onClick = onToggleUnreadOnly,
            )
        }
        if (state.tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "FILTER INBOX BY TAG",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.tags.forEach { tag ->
                    val isSelected = state.selectedTag == tag.name
                    FilterChipPill(
                        label = "#${tag.name} (${tag.unreadCount})",
                        selected = isSelected,
                        onClick = { onSelectTag(if (isSelected) null else tag.name) },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
private fun SubscribedFeedsCardList(
    feeds: List<FeedItem>,
    archivedFeeds: List<FeedItem>,
    hasFeedsAtAll: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        if (feeds.isEmpty()) {
            val emptyMessage =
                when {
                    hasFeedsAtAll -> "No feeds with unread items."
                    archivedFeeds.isNotEmpty() ->
                        "No active feeds. Expand Archived Feeds below to unarchive."
                    else -> "No subscribed feeds yet. Tap + to subscribe to RSS, arXiv, or YouTube."
                }
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emptyMessage, color = colors.textMuted, fontSize = 13.sp)
            }
        } else {
            RocheSurfaceCard {
                feeds.forEachIndexed { index, feed ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = colors.border,
                            thickness = 1.dp,
                            modifier = Modifier.padding(start = 52.dp),
                        )
                    }
                    SubscribedFeedRow(
                        feed = feed,
                        onClick = { onAction(MainAction.FilterByFeed(feed.id)) },
                        onLongPress = { onAction(MainAction.OpenFeedContextSheet(feed.id)) },
                        onRefresh = { onAction(MainAction.RefreshFeed(feed.id)) },
                    )
                }
            }
        }
        if (archivedFeeds.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            ArchivedFeedsBinSection(
                archivedFeeds = archivedFeeds,
                onAction = onAction,
            )
        }
        Spacer(modifier = Modifier.height(72.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchivedFeedsBinSection(
    archivedFeeds: List<FeedItem>,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron = if (expanded) "▾" else "▸"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 6.dp, horizontal = 4.dp)
                    .testTag("archived_feeds_header"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$chevron Archived Feeds (${archivedFeeds.size})",
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Paused polling",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
            )
        }
        if (expanded) {
            RocheSurfaceCard {
                archivedFeeds.forEachIndexed { index, feed ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = colors.border,
                            thickness = 1.dp,
                            modifier = Modifier.padding(start = 52.dp),
                        )
                    }
                    ArchivedFeedRow(
                        feed = feed,
                        onClick = { onAction(MainAction.FilterByFeed(feed.id)) },
                        onLongPress = { onAction(MainAction.OpenFeedContextSheet(feed.id)) },
                        onUnarchive = { onAction(MainAction.ToggleFeedArchive(feed.id)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchivedFeedRow(
    feed: FeedItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onUnarchive: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(vertical = 11.dp, horizontal = 12.dp)
                .testTag("archived_feed_row_${feed.id}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SiteFaviconBadge(
                primaryFaviconUrl = feed.faviconUrl,
                fallbackFaviconUrl = feed.fallbackFaviconUrl,
                domain = feed.displayDomain,
                title = feed.title,
                feedType = feed.feedType,
                size = 28.dp,
            )
            Spacer(modifier = Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = feed.title,
                    color = colors.textSecondary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle =
                    if (feed.unreadCount > 0) {
                        "${feed.displayDomain} · ${feed.unreadCount} unread"
                    } else {
                        feed.displayDomain
                    }
                Text(
                    text = subtitle,
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HeaderActionButton(
            label = "Unarchive",
            onClick = onUnarchive,
            modifier = Modifier.testTag("unarchive_feed_${feed.id}"),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscribedFeedRow(
    feed: FeedItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(vertical = 11.dp, horizontal = 12.dp)
                .testTag("feed_row_${feed.id}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SiteFaviconBadge(
                primaryFaviconUrl = feed.faviconUrl,
                fallbackFaviconUrl = feed.fallbackFaviconUrl,
                domain = feed.displayDomain,
                title = feed.title,
                feedType = feed.feedType,
                size = 28.dp,
            )
            Spacer(modifier = Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = feed.title,
                    color = colors.textPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = feed.displayDomain,
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SubscribedFeedTrailingActions(feed = feed, onRefresh = onRefresh)
    }
}

@Composable
internal fun SubscribedFeedTrailingActions(
    feed: FeedItem,
    onRefresh: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (feed.unreadCount > 0) {
            Box(
                modifier =
                    Modifier.clip(RoundedCornerShape(99.dp))
                        .background(colors.accentBlue.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = feed.unreadCount.toString(),
                    color = colors.accentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        RocheIconActionButton(
            contentDescription = "Refresh ${feed.title}",
            onClick = onRefresh,
            outlined = false,
            modifier = Modifier.testTag("refresh_feed_${feed.id}"),
        ) {
            RefreshIcon(tint = colors.textSecondary, size = 17.dp)
        }
    }
}

@Composable
fun OfflineTabContent(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    val offlineEntries = state.offlineEntries

    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(title = "Offline Vault", subtitle = "${offlineEntries.size} cached")
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        if (offlineEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No offline entries cached on this device.",
                    color = colors.textMuted,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
            ) {
                items(items = offlineEntries, key = { it.id }) { entry ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable { onAction(MainAction.OpenReader(entry.id)) }
                                .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = entry.title,
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text =
                                    "${entry.feedTitle} · ${entry.contentType.wireValue.uppercase()} · ${entry.readEstimateLabel}",
                                color = colors.textMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                        HeaderActionButton(
                            label = "Evict",
                            onClick = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
                        )
                    }
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
fun QueueTabContent(state: MainUiState, onAction: (MainAction) -> Unit) {
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
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChipPill(
                label = "Up Next (${queueEntries.size})",
                selected = !showHistory,
                onClick = { showHistory = false },
                modifier = Modifier.testTag("queue_mode_up_next"),
            )
            FilterChipPill(
                label = "Listening History (${historyEntries.size})",
                selected = showHistory,
                onClick = { showHistory = true },
                modifier = Modifier.testTag("queue_mode_history"),
            )
        }
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        if (showHistory) {
            ListeningHistoryListContent(
                historyEntries = historyEntries,
                state = state,
                onAction = onAction,
            )
        } else if (queueEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp).testTag("queue_empty_state"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text =
                        "Your Queue is empty. Long-press any article, paper, video, or podcast episode in Inbox to add it to your Queue.",
                    color = colors.textMuted,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("queue_list"),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(items = queueEntries, key = { it.id }) { entry ->
                    val index = queueEntries.indexOfFirst { it.id == entry.id }
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListeningHistoryListContent(
    historyEntries: List<InboxEntry>,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
    if (historyEntries.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp).testTag("listening_history_empty"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text =
                    "No podcast episodes played yet. Episodes you listen to appear here with their playback timestamp and progress.",
                color = colors.textMuted,
                fontSize = 13.sp,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("listening_history_list"),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(items = historyEntries, key = { it.id }) { entry ->
                val isPlaying = entry.id == state.playerState.currentEntryId
                val inQueue = state.isEntryInQueue(entry.id)
                val starTint = if (entry.isFavorite) colors.accentEcru else colors.textMuted
                val historyLabel = entry.formattedListeningHistoryLabel ?: "Played"
                val rowBg =
                    if (isPlaying) {
                        colors.accentBlue.copy(alpha = 0.12f)
                    } else {
                        colors.background
                    }
                val starDesc =
                    if (entry.isFavorite) {
                        "Unstar ${entry.title}"
                    } else {
                        "Star ${entry.title}"
                    }
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(rowBg)
                            .combinedClickable(
                                onClick = { onAction(MainAction.PlayAudioEntry(entry.id)) },
                                onLongClick = { onAction(MainAction.OpenContextSheet(entry.id)) },
                            )
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
                    Column(modifier = Modifier.weight(1f)) {
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
                        )
                    }
                    if (!inQueue) {
                        HeaderActionButton(
                            label = "+ Queue",
                            onClick = { onAction(MainAction.AddToQueue(entry.id)) },
                            modifier = Modifier.testTag("history_requeue_btn_${entry.id}"),
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
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
    val rowBackground =
        when {
            isPlayingAudio -> colors.accentBlue.copy(alpha = 0.12f)
            isQueuedNextAudio -> colors.accentVerdigris.copy(alpha = 0.11f)
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
                .padding(start = 10.dp, end = 12.dp, top = 11.dp, bottom = 11.dp)
                .testTag("queue_row_${entry.id}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Left: Star action separated from right-side destructive actions
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

        Column(modifier = Modifier.weight(1f)) {
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
                    text =
                        "· ${entry.contentType.wireValue.uppercase()} · ${entry.readEstimateLabel}",
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
}
