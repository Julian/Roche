@file:Suppress("TooManyFunctions")

package com.grayvines.roche.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedListFilter
import com.grayvines.roche.model.FeedSortOrder
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.TagItem
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.FilterFunnelIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.PredictiveBackDrawerContainer
import com.grayvines.roche.ui.components.RefreshIcon
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheRefreshableTabScaffold
import com.grayvines.roche.ui.components.RocheStripTab
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.components.SortArrowsIcon
import com.grayvines.roche.ui.components.UnpinnedTabBackButton
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

private data class FeedsListDerived(
    val failingCount: Int,
    val availableFilters: List<FeedListFilter>,
    val effectiveFilter: FeedListFilter,
    val visibleFeeds: List<FeedItem>,
)

@Composable
private fun rememberFeedsListDerived(
    activeFeeds: List<FeedItem>,
    archivedFeeds: List<FeedItem>,
    activeFilter: FeedListFilter,
    sortOrder: FeedSortOrder,
    showArchived: Boolean,
): FeedsListDerived =
    remember(activeFeeds, archivedFeeds, activeFilter, sortOrder, showArchived) {
        val failingCount = activeFeeds.count { it.hasPollError }
        val pool = if (showArchived) activeFeeds + archivedFeeds else activeFeeds
        val available = resolveAvailableFeedFilters(feeds = pool, failingCount = failingCount)
        val effective = if (activeFilter in available) activeFilter else FeedListFilter.ALL
        val visible =
            filterAndSortFeeds(activeFeeds, archivedFeeds, effective, sortOrder, showArchived)
        FeedsListDerived(failingCount, available, effective, visible)
    }

@Composable
fun FeedsAndTagsTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
) {
    var showFilterDrawer by rememberSaveable { mutableStateOf(false) }
    var sortOrder by rememberSaveable { mutableStateOf(FeedSortOrder.ALPHABETICAL) }
    var activeFilter by rememberSaveable { mutableStateOf(FeedListFilter.ALL) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val derived =
        rememberFeedsListDerived(
            state.activeFeeds,
            state.archivedFeeds,
            activeFilter,
            sortOrder,
            showArchived,
        )
    val hasActiveDrawer = showFilterDrawer || showArchived || state.selectedTag != null

    RocheRefreshableTabScaffold(
        isRefreshing = state.isManualRefreshing,
        onRefresh = { onAction(MainAction.ManualRefresh) },
        pullToRefreshTestTag = "feeds_pull_to_refresh",
        header = {
            FeedsTabHeaderBlock(
                state = state,
                derived = derived,
                sortOrder = sortOrder,
                hasActiveDrawer = hasActiveDrawer,
                onSelectFilter = { activeFilter = it },
                onSelectSortOrder = { sortOrder = it },
                onToggleFilterDrawer = { showFilterDrawer = !showFilterDrawer },
                onAction = onAction,
            )
        },
        drawer = {
            FeedsFilterDrawerSection(
                visible = showFilterDrawer,
                state = state,
                archivedCount = state.archivedFeeds.size,
                showArchived = showArchived,
                onDismiss = { showFilterDrawer = false },
                onToggleShowArchived = { showArchived = !showArchived },
                onSelectTag = { tagName ->
                    showFilterDrawer = false
                    onAction(MainAction.FilterByTag(tagName))
                },
            )
        },
    ) {
        SubscribedFeedsCardList(
            feeds = derived.visibleFeeds,
            hasActiveFeeds = state.activeFeeds.isNotEmpty(),
            archivedCount = state.archivedFeeds.size,
            showArchived = showArchived,
            activeFilter = derived.effectiveFilter,
            onShowArchived = { showArchived = true },
            onAction = onAction,
            scrollState = scrollState,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun FeedsTabHeaderBlock(
    state: MainUiState,
    derived: FeedsListDerived,
    sortOrder: FeedSortOrder,
    hasActiveDrawer: Boolean,
    onSelectFilter: (FeedListFilter) -> Unit,
    onSelectSortOrder: (FeedSortOrder) -> Unit,
    onToggleFilterDrawer: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    FeedsTopHeaderBar(
        activeFeedCount = state.activeFeeds.size,
        showBackToHome = state.isUnpinnedDestination(AppTab.FEEDS),
        onBackToHome = { onAction(MainAction.SelectTab(AppTab.HOME)) },
        onOpenSearch = { onAction(MainAction.OpenSearch) },
    )
    FeedsFilterControlBar(
        availableFilters = derived.availableFilters,
        selectedFilter = derived.effectiveFilter,
        failingCount = derived.failingCount,
        sortOrder = sortOrder,
        hasActiveDrawerFilter = hasActiveDrawer,
        onSelectFilter = onSelectFilter,
        onSelectSortOrder = onSelectSortOrder,
        onToggleFilterDrawer = onToggleFilterDrawer,
    )
}

@Composable
internal fun FeedsTopHeaderBar(
    activeFeedCount: Int,
    showBackToHome: Boolean = false,
    onBackToHome: () -> Unit = {},
    onOpenSearch: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheTopHeader(
        title = "Feeds",
        badgeText = activeFeedCount.toString(),
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
            modifier = Modifier.testTag("feeds_search_btn"),
        ) {
            SearchIcon(tint = colors.textSecondary, size = 17.dp)
        }
    }
}

@Composable
private fun FeedsFilterControlBar(
    availableFilters: List<FeedListFilter>,
    selectedFilter: FeedListFilter,
    failingCount: Int,
    sortOrder: FeedSortOrder,
    hasActiveDrawerFilter: Boolean,
    onSelectFilter: (FeedListFilter) -> Unit,
    onSelectSortOrder: (FeedSortOrder) -> Unit,
    onToggleFilterDrawer: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            availableFilters.forEach { filter ->
                val label =
                    if (filter == FeedListFilter.ERRORS) {
                        "${filter.label} ($failingCount)"
                    } else {
                        filter.label
                    }
                RocheStripTab(
                    label = label,
                    selected = selectedFilter == filter,
                    warning = filter == FeedListFilter.ERRORS,
                    onClick = { onSelectFilter(filter) },
                    modifier = Modifier.testTag("feeds_filter_tab_${filter.name.lowercase()}"),
                )
            }
        }
        Box(
            modifier =
                Modifier.padding(horizontal = 4.dp)
                    .width(1.dp)
                    .height(20.dp)
                    .background(colors.border)
        )
        FeedsSortMenuButton(sortOrder = sortOrder, onSelectSortOrder = onSelectSortOrder)
        FeedsBarToolButton(
            contentDescription = "Filter Feeds & Tags",
            active = hasActiveDrawerFilter,
            onClick = onToggleFilterDrawer,
            modifier = Modifier.testTag("feeds_filter_btn"),
        ) {
            FilterFunnelIcon(
                tint = if (hasActiveDrawerFilter) colors.accentBlue else colors.textSecondary,
                size = 16.dp,
            )
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
private fun FeedsSortMenuButton(
    sortOrder: FeedSortOrder,
    onSelectSortOrder: (FeedSortOrder) -> Unit,
) {
    val colors = LocalRocheColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val active = expanded || sortOrder != FeedSortOrder.ALPHABETICAL

    Box {
        FeedsBarToolButton(
            contentDescription = "Sort Feeds",
            active = active,
            onClick = { expanded = true },
            modifier = Modifier.testTag("feeds_sort_btn"),
        ) {
            SortArrowsIcon(
                tint = if (active) colors.accentBlue else colors.textSecondary,
                size = 16.dp,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceElevated,
        ) {
            FeedSortMenuOption(
                label = "A–Z",
                selected = sortOrder == FeedSortOrder.ALPHABETICAL,
                testTag = "feeds_sort_alpha",
                onClick = {
                    expanded = false
                    onSelectSortOrder(FeedSortOrder.ALPHABETICAL)
                },
            )
            FeedSortMenuOption(
                label = "Unread first",
                selected = sortOrder == FeedSortOrder.UNREAD_FIRST,
                testTag = "feeds_sort_unread",
                onClick = {
                    expanded = false
                    onSelectSortOrder(FeedSortOrder.UNREAD_FIRST)
                },
            )
        }
    }
}

@Composable
private fun FeedSortMenuOption(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (selected) colors.accentBlue else colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

@Composable
private fun FeedsBarToolButton(
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val bg = if (active) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).clip(shape).background(bg).clearAndSetSemantics {},
        ) {
            content()
        }
    }
}

@Composable
internal fun FeedsFilterDrawerSection(
    visible: Boolean,
    state: MainUiState,
    archivedCount: Int,
    showArchived: Boolean,
    onDismiss: () -> Unit,
    onToggleShowArchived: () -> Unit,
    onSelectTag: (String?) -> Unit,
) {
    PredictiveBackDrawerContainer(visible = visible, onDismiss = onDismiss) { drawerModifier ->
        FeedsFilterDrawer(
            tags = state.tags,
            selectedTag = state.selectedTag,
            archivedCount = archivedCount,
            showArchived = showArchived,
            onToggleShowArchived = onToggleShowArchived,
            onSelectTag = onSelectTag,
            modifier = drawerModifier,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedsFilterDrawer(
    tags: List<TagItem>,
    selectedTag: String?,
    archivedCount: Int,
    showArchived: Boolean,
    onToggleShowArchived: () -> Unit,
    onSelectTag: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(max = MAX_FILTER_DRAWER_HEIGHT)
                .verticalScroll(rememberScrollState())
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("feeds_filter_drawer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (archivedCount > 0) {
            Text(
                text = "VISIBILITY",
                color = colors.textMuted,
                fontWeight = FontWeight.Medium,
                fontSize = 10.5.sp,
                letterSpacing = 0.8.sp,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChipPill(
                    label = "Show archived ($archivedCount)",
                    selected = showArchived,
                    onClick = onToggleShowArchived,
                    role = Role.Checkbox,
                    modifier = Modifier.testTag("feeds_show_archived_chip"),
                )
            }
        }
        if (tags.isNotEmpty()) {
            FeedsDrawerTagFilterSection(
                tags = tags,
                selectedTag = selectedTag,
                onSelectTag = onSelectTag,
            )
        }
        if (archivedCount == 0 && tags.isEmpty()) {
            Text(
                text = "No archived feeds or tags yet.",
                color = colors.textMuted,
                fontSize = 12.5.sp,
            )
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedsDrawerTagFilterSection(
    tags: List<TagItem>,
    selectedTag: String?,
    onSelectTag: (String?) -> Unit,
) {
    val colors = LocalRocheColors.current
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = "FILTER INBOX BY TAG",
        color = colors.textMuted,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        letterSpacing = 0.8.sp,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.forEach { tag ->
            val isSelected = selectedTag == tag.name
            FilterChipPill(
                label = "#${tag.name} (${tag.unreadCount})",
                selected = isSelected,
                onClick = { onSelectTag(if (isSelected) null else tag.name) },
            )
        }
    }
}

internal fun resolveAvailableFeedFilters(
    feeds: List<FeedItem>,
    failingCount: Int,
): List<FeedListFilter> = buildList {
    add(FeedListFilter.ALL)
    add(FeedListFilter.UNREAD)
    if (failingCount > 0) add(FeedListFilter.ERRORS)
    val types = feeds.mapTo(mutableSetOf()) { it.feedType }
    if (FeedType.RSS in types) add(FeedListFilter.RSS)
    if (FeedType.PODCAST in types) add(FeedListFilter.PODCAST)
    if (FeedType.YOUTUBE in types) add(FeedListFilter.YOUTUBE)
    if (FeedType.ARXIV in types) add(FeedListFilter.ARXIV)
}

internal fun sortAndFilterFeeds(
    feeds: List<FeedItem>,
    sortOrder: FeedSortOrder,
    unreadOnly: Boolean,
): List<FeedItem> {
    val base = if (unreadOnly) feeds.filter { it.unreadCount > 0 } else feeds
    return sortFeeds(base, sortOrder)
}

internal fun filterAndSortFeeds(
    activeFeeds: List<FeedItem>,
    archivedFeeds: List<FeedItem>,
    filter: FeedListFilter,
    sortOrder: FeedSortOrder,
    showArchived: Boolean,
): List<FeedItem> {
    val filteredActive = sortFeeds(activeFeeds.filter { it.matchesFilter(filter) }, sortOrder)
    if (!showArchived) return filteredActive
    val filteredArchived = sortFeeds(archivedFeeds.filter { it.matchesFilter(filter) }, sortOrder)
    return filteredActive + filteredArchived
}

private fun FeedItem.matchesFilter(filter: FeedListFilter): Boolean =
    when (filter) {
        FeedListFilter.ALL -> true
        FeedListFilter.UNREAD -> unreadCount > 0
        FeedListFilter.ERRORS -> isActive && hasPollError
        FeedListFilter.RSS -> feedType == FeedType.RSS
        FeedListFilter.PODCAST -> feedType == FeedType.PODCAST
        FeedListFilter.YOUTUBE -> feedType == FeedType.YOUTUBE
        FeedListFilter.ARXIV -> feedType == FeedType.ARXIV
    }

private fun sortFeeds(feeds: List<FeedItem>, sortOrder: FeedSortOrder): List<FeedItem> {
    val alphaComparator =
        compareBy<FeedItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.title }
    return when (sortOrder) {
        FeedSortOrder.ALPHABETICAL -> feeds.sortedWith(alphaComparator)
        FeedSortOrder.UNREAD_FIRST ->
            feeds.sortedWith(compareByDescending<FeedItem> { it.unreadCount }.then(alphaComparator))
    }
}

@Composable
private fun SubscribedFeedsCardList(
    feeds: List<FeedItem>,
    hasActiveFeeds: Boolean,
    archivedCount: Int,
    showArchived: Boolean,
    activeFilter: FeedListFilter,
    onShowArchived: () -> Unit,
    onAction: (MainAction) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
) {
    val colors = LocalRocheColors.current
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        if (feeds.isEmpty()) {
            FeedsEmptyState(
                hasActiveFeeds = hasActiveFeeds,
                archivedCount = archivedCount,
                showArchived = showArchived,
                activeFilter = activeFilter,
                onShowArchived = onShowArchived,
            )
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
                    if (feed.isArchived) {
                        ArchivedFeedRow(
                            feed = feed,
                            onClick = { onAction(MainAction.FilterByFeed(feed.id)) },
                            onLongPress = { onAction(MainAction.OpenFeedContextSheet(feed.id)) },
                            onUnarchive = { onAction(MainAction.ToggleFeedArchive(feed.id)) },
                        )
                    } else {
                        SubscribedFeedRow(
                            feed = feed,
                            onClick = { onAction(MainAction.FilterByFeed(feed.id)) },
                            onLongPress = { onAction(MainAction.OpenFeedContextSheet(feed.id)) },
                            onRefresh = { onAction(MainAction.RefreshFeed(feed.id)) },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(72.dp))
    }
}

@Composable
private fun FeedsEmptyState(
    hasActiveFeeds: Boolean,
    archivedCount: Int,
    showArchived: Boolean,
    activeFilter: FeedListFilter,
    onShowArchived: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val archivedOnly = !hasActiveFeeds && archivedCount > 0 && !showArchived
    val emptyMessage =
        when {
            archivedOnly ->
                "No active feeds. Enable Show archived in Filter to view archived feeds."
            !hasActiveFeeds && archivedCount == 0 ->
                "No subscribed feeds yet. Tap + to subscribe to RSS, arXiv, or YouTube."
            activeFilter == FeedListFilter.UNREAD -> "No feeds with unread items."
            activeFilter == FeedListFilter.ERRORS -> "All active feeds are healthy."
            else -> "No matching feeds."
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = emptyMessage,
            color = colors.textMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        if (archivedOnly) {
            HeaderActionButton(
                label = "Show archived ($archivedCount)",
                onClick = onShowArchived,
                modifier = Modifier.testTag("empty_show_archived_btn"),
            )
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
    val subtitle =
        if (feed.unreadCount > 0) {
            "${feed.displayDomain} · ${feed.unreadCount} unread"
        } else {
            feed.displayDomain
        }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .semantics { role = Role.Button }
                .padding(vertical = 10.dp, horizontal = 12.dp)
                .testTag("archived_feed_row_${feed.id}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedRowIdentityBlock(
            feed = feed,
            titleColor = colors.textSecondary,
            subtitle = subtitle,
            errorText = null,
            isArchived = true,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
        )
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
                .semantics { role = Role.Button }
                .padding(vertical = 10.dp, horizontal = 12.dp)
                .testTag("feed_row_${feed.id}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedRowIdentityBlock(
            feed = feed,
            titleColor = colors.textPrimary,
            subtitle = feed.displayDomain,
            errorText = feed.lastError?.takeIf { feed.hasPollError },
            isArchived = false,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
        )
        SubscribedFeedTrailingActions(feed = feed, onRefresh = onRefresh)
    }
}

@Composable
private fun FeedRowIdentityBlock(
    feed: FeedItem,
    titleColor: Color,
    subtitle: String,
    errorText: String?,
    isArchived: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        SiteFaviconBadge(
            primaryFaviconUrl = feed.faviconUrl,
            fallbackFaviconUrl = feed.fallbackFaviconUrl,
            domain = feed.displayDomain,
            title = feed.title,
            feedType = feed.feedType,
            size = 28.dp,
        )
        Spacer(modifier = Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = feed.title,
                color = titleColor,
                fontFamily = headlineFamily,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isArchived) {
                    ArchivedFeedBadge()
                } else {
                    FeedKindBadge(feedType = feed.feedType)
                }
                Text(
                    text = subtitle,
                    color = colors.textMuted,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!errorText.isNullOrBlank()) {
                Text(
                    text = errorText,
                    color = colors.accentIndianRed,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FeedKindBadge(feedType: FeedType) {
    val colors = LocalRocheColors.current
    val spec =
        when (feedType) {
            FeedType.PODCAST -> "Podcast" to colors.accentVerdigris
            FeedType.YOUTUBE -> "YouTube" to colors.accentIndianRed
            FeedType.ARXIV -> "arXiv" to colors.accentBlue
            FeedType.RSS,
            FeedType.SAVED -> return
        }
    val (label, tint) = spec
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.clip(RoundedCornerShape(4.dp))
                .background(tint.copy(alpha = 0.12f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = label,
            color = tint,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun ArchivedFeedBadge() {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = "Archived",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SubscribedFeedTrailingActions(
    feed: FeedItem,
    onRefresh: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (feed.unreadCount > 0) {
            Box(
                modifier =
                    Modifier.clip(RoundedCornerShape(99.dp))
                        .background(colors.accentBlue.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = feed.unreadCount.toString(),
                    color = colors.accentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
        RocheIconActionButton(
            contentDescription = "Refresh ${feed.title}",
            onClick = onRefresh,
            outlined = false,
            modifier = Modifier.testTag("refresh_feed_${feed.id}"),
        ) {
            RefreshIcon(tint = colors.textMuted, size = 16.dp)
        }
    }
}

@Composable
fun OfflineTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = LocalRocheColors.current
    val offlineEntries = state.offlineEntries

    RocheRefreshableTabScaffold(
        isRefreshing = state.isManualRefreshing,
        onRefresh = { onAction(MainAction.ManualRefresh) },
        pullToRefreshTestTag = "offline_pull_to_refresh",
        header = {
            OfflineTopHeaderBar(state = state, count = offlineEntries.size, onAction = onAction)
        },
    ) {
        if (offlineEntries.isEmpty()) {
            Box(
                modifier =
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
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
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
            ) {
                items(items = offlineEntries, key = { it.id }) { entry ->
                    OfflineVaultEntryRow(
                        entry = entry,
                        onOpen = { onAction(MainAction.OpenReader(entry.id)) },
                        onEvict = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
                    )
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
private fun OfflineTopHeaderBar(
    state: MainUiState,
    count: Int,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheTopHeader(
        title = "Offline Vault",
        badgeText = count.toString(),
        navigationAction =
            if (state.isUnpinnedDestination(AppTab.OFFLINE)) {
                { UnpinnedTabBackButton(onBack = { onAction(MainAction.SelectTab(AppTab.HOME)) }) }
            } else {
                null
            },
    ) {
        RocheIconActionButton(
            contentDescription = "Search",
            outlined = true,
            onClick = { onAction(MainAction.OpenSearch) },
            modifier = Modifier.testTag("offline_search_btn"),
        ) {
            SearchIcon(tint = colors.textSecondary, size = 17.dp)
        }
    }
}

@Composable
private fun OfflineVaultEntryRow(
    entry: InboxEntry,
    onOpen: () -> Unit,
    onEvict: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onOpen)
                .semantics { role = Role.Button }
                .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = entry.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
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
        HeaderActionButton(label = "Evict", onClick = onEvict)
    }
}
