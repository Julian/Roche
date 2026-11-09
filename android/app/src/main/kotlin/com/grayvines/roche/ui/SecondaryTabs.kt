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
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedSortOrder
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.isCrossPeerRescueError
import com.grayvines.roche.model.pollerAttributionLabel
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.FilterFunnelIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RefreshIcon
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.theme.LocalRocheColors

@Composable
fun StarredTabContent(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    val starredEntries = state.starredEntries

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
                        onOpen = { onAction(MainAction.OpenReader(entry.id)) },
                        onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
                        onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                        onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
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
            ultraDense = state.settings.ultraDenseRows,
            swipeToArchive = state.settings.swipeToArchive,
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
    ultraDense: Boolean,
    swipeToArchive: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    if (results.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No entries match \"${searchQuery.ifBlank { "current filter" }}\".",
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
                    onOpen = { onAction(MainAction.OpenReader(entry.id)) },
                    onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
                    onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                    onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
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

    val totalUnread = remember(state.feeds) { state.feeds.sumOf { it.unreadCount } }
    val subtitle =
        if (totalUnread > 0) {
            "${state.feeds.size} feeds · $totalUnread unread"
        } else {
            "${state.feeds.size} feeds"
        }
    val visibleFeeds =
        remember(state.feeds, sortOrder, unreadOnly) {
            sortAndFilterFeeds(state.feeds, sortOrder, unreadOnly)
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
                hasFeedsAtAll = state.feeds.isNotEmpty(),
                localPeerId = state.settings.peerId,
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
    hasFeedsAtAll: Boolean,
    localPeerId: String,
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
                if (hasFeedsAtAll) {
                    "No feeds with unread items."
                } else {
                    "No subscribed feeds yet. Tap + to subscribe to RSS, arXiv, or YouTube."
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
                        localPeerId = localPeerId,
                        onClick = { onAction(MainAction.FilterByFeed(feed.id)) },
                        onLongPress = { onAction(MainAction.OpenFeedContextSheet(feed.id)) },
                        onRefresh = { onAction(MainAction.RefreshFeed(feed.id)) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(72.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscribedFeedRow(
    feed: FeedItem,
    localPeerId: String,
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
                SubscribedFeedMetadataLine(feed = feed, localPeerId = localPeerId)
            }
        }
        SubscribedFeedTrailingActions(feed = feed, onRefresh = onRefresh)
    }
}

@Composable
private fun SubscribedFeedMetadataLine(
    feed: FeedItem,
    localPeerId: String,
) {
    val colors = LocalRocheColors.current
    val attribution = feed.pollerAttributionLabel(localPeerId)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = feed.displayDomain,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (attribution != null) {
            val badgeColor =
                when {
                    feed.isCrossPeerRescueError(localPeerId) -> colors.accentEcru
                    feed.hasPollError -> colors.accentIndianRed
                    feed.lastPolledBy == localPeerId -> colors.accentVerdigris
                    else -> colors.textSecondary
                }
            Text(
                text = "· $attribution",
                color = badgeColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
