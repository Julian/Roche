@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.ui.components.BottomTabIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RefreshIcon
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalRocheColors

@Composable
fun HomeTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
) {
    val colors = LocalRocheColors.current
    val isSettingsUnpinned =
        AppTab.SETTINGS !in AppTab.normalizeSideTabs(state.settings.bottomNavTabs)

    Column(modifier = Modifier.fillMaxSize().testTag("home_screen")) {
        RocheTopHeader(
            title = "Home",
            subtitle = "Overview",
        ) {
            RocheIconActionButton(
                contentDescription = "Sync",
                onClick = { onAction(MainAction.SyncNow) },
                modifier = Modifier.testTag("home_sync_btn"),
            ) {
                RefreshIcon(tint = colors.textSecondary, size = 17.dp)
            }
            if (isSettingsUnpinned) {
                RocheIconActionButton(
                    contentDescription = "Settings",
                    onClick = { onAction(MainAction.SelectTab(AppTab.SETTINGS)) },
                    modifier = Modifier.testTag("home_spare_settings_btn"),
                ) {
                    BottomTabIcon(tab = AppTab.SETTINGS, tint = colors.textSecondary, size = 17.dp)
                }
            }
        }
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HomePulseWidget(state = state, onAction = onAction)
            HomeUpNextWidget(state = state, onAction = onAction)
            HomeActiveSourcesWidget(state = state, onAction = onAction)
            Spacer(modifier = Modifier.height(76.dp))
        }
    }
}

@Composable
private fun HomePulseWidget(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val unreadCount =
        remember(state.allEntries) { state.allEntries.count { !it.isArchived && it.isUnread } }
    val readingCount =
        remember(state.allEntries) {
            state.allEntries.count {
                !it.isArchived &&
                    (it.contentType == ContentType.ARTICLE || it.contentType == ContentType.PDF)
            }
        }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("home_widget_pulse"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RocheSectionHeader(label = "Pulse")
        HomePulseStatGrid(
            unreadCount = unreadCount,
            readingCount = readingCount,
            queueCount = state.queueEntries.size,
            starredCount = state.starredEntries.size,
            onAction = onAction,
        )
    }
}

@Composable
private fun HomePulseStatGrid(
    unreadCount: Int,
    readingCount: Int,
    queueCount: Int,
    starredCount: Int,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeStatTile(
                label = "UNREAD",
                value = unreadCount.toString(),
                accent = colors.accentBlue,
                onClick = {
                    onAction(MainAction.SelectFilter(InboxFilter.UNREAD))
                    onAction(MainAction.SelectTab(AppTab.INBOX))
                },
                modifier = Modifier.weight(1f).testTag("home_stat_unread"),
            )
            HomeStatTile(
                label = "READING",
                value = readingCount.toString(),
                accent = colors.accentVerdigris,
                onClick = {
                    onAction(MainAction.SelectFilter(InboxFilter.ARTICLES))
                    onAction(MainAction.SelectTab(AppTab.INBOX))
                },
                modifier = Modifier.weight(1f).testTag("home_stat_reading"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeStatTile(
                label = "QUEUE",
                value = queueCount.toString(),
                accent = colors.accentBlue,
                onClick = { onAction(MainAction.SelectTab(AppTab.QUEUE)) },
                modifier = Modifier.weight(1f).testTag("home_stat_queue"),
            )
            HomeStatTile(
                label = "STARRED",
                value = starredCount.toString(),
                accent = colors.accentEcru,
                onClick = { onAction(MainAction.SelectTab(AppTab.STARRED)) },
                modifier = Modifier.weight(1f).testTag("home_stat_starred"),
            )
        }
    }
}

@Composable
private fun HomeStatTile(
    label: String,
    value: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(colors.surface)
                .border(width = 1.dp, color = colors.border, shape = shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun HomeUpNextWidget(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val spotlightEntries =
        remember(state.activePlayerEntry, state.queueEntries, state.allEntries) {
            buildHomeSpotlightEntries(state)
        }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("home_widget_up_next"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RocheSectionHeader(label = "Continue & Up Next")
        RocheSurfaceCard {
            if (spotlightEntries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Nothing queued right now.",
                        color = colors.textSecondary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeaderActionButton(
                            label = "Open Inbox",
                            onClick = { onAction(MainAction.SelectTab(AppTab.INBOX)) },
                        )
                        HeaderActionButton(
                            label = "Save URL or Feed",
                            onClick = { onAction(MainAction.OpenAddSheet()) },
                        )
                    }
                }
            } else {
                spotlightEntries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(color = colors.border, thickness = 1.dp)
                    }
                    HomeSpotlightRow(
                        entry = entry,
                        isPlayingAudio = entry.id == state.playerState.currentEntryId,
                        inQueue = state.isEntryInQueue(entry.id),
                        onClick = { onAction(MainAction.OpenReader(entry.id)) },
                    )
                }
            }
        }
    }
}

private fun buildHomeSpotlightEntries(state: MainUiState): List<InboxEntry> {
    val picked = LinkedHashMap<String, InboxEntry>()
    state.activePlayerEntry?.let { picked[it.id] = it }
    for (queued in state.queueEntries) {
        if (picked.size >= MAX_SPOTLIGHT_ITEMS) break
        picked.putIfAbsent(queued.id, queued)
    }
    if (picked.size < MAX_SPOTLIGHT_ITEMS) {
        state.allEntries
            .filter { !it.isArchived && it.readProgress > 0.0 && it.readProgress < 0.98 }
            .forEach { entry ->
                if (picked.size < MAX_SPOTLIGHT_ITEMS) {
                    picked.putIfAbsent(entry.id, entry)
                }
            }
    }
    if (picked.size < MAX_SPOTLIGHT_ITEMS) {
        state.allEntries
            .filter { !it.isArchived && it.isUnread }
            .forEach { entry ->
                if (picked.size < MAX_SPOTLIGHT_ITEMS) {
                    picked.putIfAbsent(entry.id, entry)
                }
            }
    }
    return picked.values.toList()
}

@Composable
private fun HomeSpotlightRow(
    entry: InboxEntry,
    isPlayingAudio: Boolean,
    inQueue: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
    val badge =
        when {
            isPlayingAudio -> "NOW PLAYING"
            inQueue -> "QUEUED"
            entry.readProgress > 0.0 -> "${(entry.readProgress * 100).toInt()}% READ"
            else -> "LATEST UNREAD"
        }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("home_spotlight_${entry.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.feedTitle,
                color = colors.textSecondary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text(
                text = badge,
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
private fun HomeActiveSourcesWidget(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val topUnreadFeeds =
        remember(state.activeFeeds) {
            state.activeFeeds
                .filter { it.unreadCount > 0 }
                .sortedByDescending { it.unreadCount }
                .take(MAX_WIDGET_FEEDS)
        }
    val topTags = remember(state.tags) { state.tags.take(MAX_WIDGET_TAGS) }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("home_widget_sources"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RocheSectionHeader(label = "Active Feeds & Topics")
        RocheSurfaceCard {
            if (topUnreadFeeds.isEmpty() && topTags.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = "All subscribed feeds are caught up.",
                        color = colors.textMuted,
                        fontSize = 13.sp,
                    )
                }
            } else {
                HomeActiveSourcesCardBody(
                    topUnreadFeeds = topUnreadFeeds,
                    topTags = topTags,
                    onAction = onAction,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeActiveSourcesCardBody(
    topUnreadFeeds: List<FeedItem>,
    topTags: List<com.grayvines.roche.model.TagItem>,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    topUnreadFeeds.forEachIndexed { index, feed ->
        if (index > 0) {
            HorizontalDivider(
                color = colors.border,
                thickness = 1.dp,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
        HomeFeedShortcutRow(
            feed = feed,
            onClick = { onAction(MainAction.FilterByFeed(feed.id)) },
        )
    }
    if (topTags.isNotEmpty()) {
        if (topUnreadFeeds.isNotEmpty()) {
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            topTags.forEach { tag ->
                FilterChipPill(
                    label = "#${tag.name} (${tag.unreadCount})",
                    selected = false,
                    onClick = { onAction(MainAction.FilterByTag(tag.name)) },
                )
            }
        }
    }
}

@Composable
private fun HomeFeedShortcutRow(
    feed: FeedItem,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("home_feed_${feed.id}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SiteFaviconBadge(
                primaryFaviconUrl = feed.faviconUrl,
                fallbackFaviconUrl = feed.fallbackFaviconUrl,
                domain = feed.displayDomain,
                title = feed.title,
                feedType = feed.feedType,
                size = 24.dp,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = feed.title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = feed.unreadCount.toString(),
            color = colors.accentBlue,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private const val MAX_SPOTLIGHT_ITEMS = 2
private const val MAX_WIDGET_FEEDS = 4
private const val MAX_WIDGET_TAGS = 6
