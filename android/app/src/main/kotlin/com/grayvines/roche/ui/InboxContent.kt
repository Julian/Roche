@file:Suppress("TooManyFunctions")

package com.grayvines.roche.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.ui.components.BottomTabIcon
import com.grayvines.roche.ui.components.DownloadTrayIcon
import com.grayvines.roche.ui.components.EntryThumbnailPreview
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.FilterFunnelIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RefreshIcon
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalRocheColors

@Composable
fun InboxTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    var showTagFilterDrawer by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        InboxHeaderSection(
            state = state,
            showTagFilterDrawer = showTagFilterDrawer,
            onToggleTagDrawer = { showTagFilterDrawer = !showTagFilterDrawer },
            onAction = onAction,
        )
        InboxFilterStrip(state = state, onAction = onAction)
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        if (showTagFilterDrawer && state.tags.isNotEmpty()) {
            InboxTagFilterDrawer(
                state = state,
                onSelectTag = { tagName ->
                    showTagFilterDrawer = false
                    onAction(MainAction.FilterByTag(tagName))
                },
            )
        }

        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = { onAction(MainAction.SyncNow) },
            modifier = Modifier.fillMaxSize().testTag("inbox_pull_to_refresh"),
        ) {
            InboxEntryListBody(state = state, onAction = onAction)
        }
    }
}

@Composable
internal fun InboxEntryListBody(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    if (state.visibleEntries.isEmpty()) {
        if (state.isInitialLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp).testTag("inbox_loading_state"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Loading inbox…",
                    color = colors.textMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            InboxEmptyState(
                hasEntriesAtAll = state.allEntries.isNotEmpty(),
                onSaveClick = { onAction(MainAction.OpenAddSheet()) },
                onSyncClick = { onAction(MainAction.SyncNow) },
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("inbox_list"),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            items(items = state.visibleEntries, key = { it.id }) { entry ->
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

@Composable
private fun InboxHeaderSection(
    state: MainUiState,
    showTagFilterDrawer: Boolean,
    onToggleTagDrawer: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val hasScopeFilter = state.selectedFeedId != null || state.selectedTag != null
    val unarchivedCount = state.visibleEntries.count { !it.isArchived }
    var showArchiveAllConfirm by rememberSaveable { mutableStateOf(false) }
    val title = resolveInboxHeaderTitle(state)
    val subtitle =
        if (state.isInitialLoading && state.visibleEntries.isEmpty()) {
            "Loading…"
        } else {
            "${state.visibleEntries.size} items"
        }

    RocheTopHeader(
        title = title,
        subtitle = subtitle,
        navigationAction =
            if (hasScopeFilter) {
                {
                    HeaderActionButton(
                        label = "←",
                        onClick = { onAction(MainAction.NavigateBackFromScope) },
                        modifier = Modifier.testTag("clear_scope_btn"),
                    )
                }
            } else {
                null
            },
    ) {
        InboxHeaderActions(
            state = state,
            unarchivedCount = unarchivedCount,
            showTagFilterDrawer = showTagFilterDrawer,
            onToggleTagDrawer = onToggleTagDrawer,
            onShowArchiveConfirm = { showArchiveAllConfirm = true },
            onSync = { onAction(MainAction.SyncNow) },
            onOpenSettings = { onAction(MainAction.SelectTab(AppTab.SETTINGS)) },
        )
    }
    if (showArchiveAllConfirm) {
        ArchiveAllConfirmDialog(
            unarchivedCount = unarchivedCount,
            scopeTitle = title,
            onDismiss = { showArchiveAllConfirm = false },
            onConfirm = {
                showArchiveAllConfirm = false
                onAction(MainAction.ArchiveVisibleEntries)
            },
        )
    }
}

private fun resolveInboxHeaderTitle(state: MainUiState): String =
    when {
        state.selectedFeedId != null -> state.selectedFeedTitle ?: "Feed"
        state.selectedTag != null -> "#${state.selectedTag}"
        else -> "Roche"
    }

@Composable
internal fun InboxHeaderActions(
    state: MainUiState,
    unarchivedCount: Int,
    showTagFilterDrawer: Boolean,
    onToggleTagDrawer: () -> Unit,
    onShowArchiveConfirm: () -> Unit,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val isSettingsUnpinned =
        AppTab.SETTINGS !in AppTab.normalizeSideTabs(state.settings.bottomNavTabs)
    if (state.tags.isNotEmpty()) {
        val filterTint =
            if (showTagFilterDrawer || state.selectedTag != null) {
                colors.accentBlue
            } else {
                colors.textSecondary
            }
        RocheIconActionButton(
            contentDescription = "Filter by Tag",
            onClick = onToggleTagDrawer,
            modifier = Modifier.testTag("inbox_tag_filter_btn"),
        ) {
            FilterFunnelIcon(tint = filterTint, size = 17.dp)
        }
    }
    if (unarchivedCount > 0) {
        RocheIconActionButton(
            contentDescription = "Archive All",
            onClick = onShowArchiveConfirm,
            modifier = Modifier.testTag("archive_all_btn"),
        ) {
            FilingCabinetIcon(tint = colors.textSecondary, isArchived = false, size = 17.dp)
        }
    }
    RocheIconActionButton(
        contentDescription = "Sync",
        onClick = onSync,
        modifier = Modifier.testTag("inbox_sync_btn"),
    ) {
        RefreshIcon(tint = colors.textSecondary, size = 17.dp)
    }
    if (isSettingsUnpinned) {
        RocheIconActionButton(
            contentDescription = "Settings",
            onClick = onOpenSettings,
            modifier = Modifier.testTag("inbox_spare_settings_btn"),
        ) {
            BottomTabIcon(tab = AppTab.SETTINGS, tint = colors.textSecondary, size = 17.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxTagFilterDrawer(
    state: MainUiState,
    onSelectTag: (String?) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("inbox_tag_filter_drawer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "FILTER BY TAG",
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
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
private fun ArchiveAllConfirmDialog(
    unarchivedCount: Int,
    scopeTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val noun = if (unarchivedCount == 1) "entry" else "entries"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = "Archive $unarchivedCount $noun?",
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
        },
        text = {
            Text(
                text =
                    "This will archive all $unarchivedCount visible $noun in \"$scopeTitle\" and mark them as read.",
                color = colors.textSecondary,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_archive_all_btn"),
            ) {
                Text("Archive All", color = colors.accentVerdigris)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
        },
    )
}

@Composable
private fun InboxEmptyState(
    hasEntriesAtAll: Boolean,
    onSaveClick: () -> Unit,
    onSyncClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text =
                    if (hasEntriesAtAll) {
                        "No entries match this filter."
                    } else {
                        "Your inbox is empty."
                    },
                color = colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeaderActionButton(label = "Save URL or Feed", onClick = onSaveClick)
                HeaderActionButton(label = "Sync", onClick = onSyncClick)
            }
        }
    }
}

@Composable
private fun InboxFilterStrip(state: MainUiState, onAction: (MainAction) -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.selectedTag != null || state.selectedFeedId != null) {
            val scopeLabel =
                state.selectedTag?.let { "#$it ✕" } ?: "${state.selectedFeedTitle ?: "Feed"} ✕"
            FilterChipPill(
                label = scopeLabel,
                selected = true,
                onClick = { onAction(MainAction.NavigateBackFromScope) },
            )
        }
        val hasScopeFilter = state.selectedTag != null || state.selectedFeedId != null
        InboxFilter.entries.forEach { filter ->
            FilterChipPill(
                label = filter.label,
                selected = !hasScopeFilter && state.activeFilter == filter,
                onClick = { onAction(MainAction.SelectFilter(filter)) },
            )
        }
    }
}

@Composable
internal fun InboxEntryRowItem(
    entry: InboxEntry,
    ultraDense: Boolean,
    swipeToArchiveEnabled: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
    isPlayingAudio: Boolean = false,
    isQueuedNextAudio: Boolean = false,
    onDownloadOffline: (() -> Unit)? = null,
) {
    if (!swipeToArchiveEnabled) {
        InboxEntryRowSurface(
            entry = entry,
            ultraDense = ultraDense,
            isPlayingAudio = isPlayingAudio,
            isQueuedNextAudio = isQueuedNextAudio,
            onOpen = onOpen,
            onLongPress = onLongPress,
            onToggleStar = onToggleStar,
            onToggleArchive = onToggleArchive,
            onDownloadOffline = onDownloadOffline,
        )
        return
    }

    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue, entry.status) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onToggleArchive()
            }
        },
        backgroundContent = {
            val colors = LocalRocheColors.current
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(colors.accentVerdigris.copy(alpha = 0.22f))
                        .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                FilingCabinetIcon(tint = colors.accentVerdigris, isArchived = entry.isArchived)
            }
        },
    ) {
        InboxEntryRowSurface(
            entry = entry,
            ultraDense = ultraDense,
            isPlayingAudio = isPlayingAudio,
            isQueuedNextAudio = isQueuedNextAudio,
            onOpen = onOpen,
            onLongPress = onLongPress,
            onToggleStar = onToggleStar,
            onToggleArchive = onToggleArchive,
            onDownloadOffline = onDownloadOffline,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxEntryRowSurface(
    entry: InboxEntry,
    ultraDense: Boolean,
    isPlayingAudio: Boolean,
    isQueuedNextAudio: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
    onDownloadOffline: (() -> Unit)?,
) {
    val colors = LocalRocheColors.current
    val verticalPadding = if (ultraDense) 9.dp else 12.dp
    val rowAlpha = if (entry.isArchived) ARCHIVED_ROW_ALPHA else 1f
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
                .padding(
                    start = 10.dp,
                    end = 12.dp,
                    top = verticalPadding,
                    bottom = verticalPadding,
                )
                .alpha(rowAlpha)
                .testTag("entry_row_${entry.id}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EntryLeadingStarButton(
            entry = entry,
            onToggleStar = onToggleStar,
        )

        if (entry.showsThumbnailPreview) {
            EntryThumbnailPreview(entry = entry, modifier = Modifier.padding(top = 2.dp))
        }

        InboxEntryTextColumn(
            entry = entry,
            ultraDense = ultraDense,
            modifier = Modifier.weight(1f),
        )

        EntryTrailingActionButtons(
            entry = entry,
            onDownloadOffline = onDownloadOffline,
            onToggleArchive = onToggleArchive,
        )
    }
}

@Composable
private fun EntryLeadingStarButton(
    entry: InboxEntry,
    onToggleStar: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val starDescription = if (entry.isFavorite) "Unstar ${entry.title}" else "Star ${entry.title}"
    val starTint = if (entry.isFavorite) colors.accentEcru else colors.textMuted

    RocheIconActionButton(
        contentDescription = starDescription,
        onClick = onToggleStar,
        outlined = false,
        modifier = Modifier.testTag("star_btn_${entry.id}"),
    ) {
        StarIcon(
            tint = starTint,
            isStarred = entry.isFavorite,
            size = 17.dp,
        )
    }
}

@Composable
private fun EntryTrailingActionButtons(
    entry: InboxEntry,
    onDownloadOffline: (() -> Unit)?,
    onToggleArchive: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val archiveDescription =
        if (entry.isArchived) "Unarchive ${entry.title}" else "Archive ${entry.title}"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (
            entry.contentType == ContentType.AUDIO &&
                !entry.isCachedOffline &&
                onDownloadOffline != null
        ) {
            RocheIconActionButton(
                contentDescription = "Download ${entry.title}",
                onClick = onDownloadOffline,
                outlined = false,
                modifier = Modifier.testTag("download_btn_${entry.id}"),
            ) {
                DownloadTrayIcon(
                    tint = colors.accentBlue,
                    size = 17.dp,
                )
            }
        }
        RocheIconActionButton(
            contentDescription = archiveDescription,
            onClick = onToggleArchive,
            outlined = false,
            modifier = Modifier.testTag("archive_btn_${entry.id}"),
        ) {
            FilingCabinetIcon(
                tint = colors.textMuted,
                isArchived = entry.isArchived,
                size = 17.dp,
            )
        }
    }
}

@Composable
private fun InboxEntryTextColumn(
    entry: InboxEntry,
    ultraDense: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val isSerif = LocalHeadlineFont.current == HeadlineFont.SERIF
    val headlineFamily = if (isSerif) FontFamily.Serif else FontFamily.SansSerif
    val titleColor = if (entry.isUnread) colors.textPrimary else colors.textSecondary
    val titleWeight = if (entry.isUnread) FontWeight.SemiBold else FontWeight.Normal
    val (titleSize, titleLineHeight, maxTitleLines) =
        if (ultraDense) Triple(14.sp, 19.sp, 1) else Triple(15.5.sp, 21.sp, 2)
    val snippet =
        androidx.compose.runtime.remember(entry.summary, ultraDense) {
            if (ultraDense) "" else extractInboxSnippet(entry.summary.orEmpty())
        }

    Column(modifier = modifier) {
        EntrySourceMetaRow(entry = entry)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = entry.title,
            color = titleColor,
            fontFamily = headlineFamily,
            fontSize = titleSize,
            lineHeight = titleLineHeight,
            fontWeight = titleWeight,
            maxLines = maxTitleLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (snippet.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = snippet,
                color = colors.textSecondary,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        entry.formattedListeningHistoryLabel?.let { historyLabel ->
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = historyLabel,
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("listening_history_badge_${entry.id}"),
            )
        }
    }
}

@Composable
private fun EntrySourceMetaRow(entry: InboxEntry) {
    val colors = LocalRocheColors.current
    val feedLabelColor = if (entry.isUnread) colors.textPrimary else colors.textSecondary
    val feedLabelWeight = if (entry.isUnread) FontWeight.SemiBold else FontWeight.Medium

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (entry.isUnread) {
            Box(
                modifier =
                    Modifier.size(6.dp)
                        .clip(CircleShape)
                        .background(colors.accentBlue)
                        .testTag("unread_dot_${entry.id}")
            )
        }
        SiteFaviconBadge(
            primaryFaviconUrl = entry.faviconUrl,
            fallbackFaviconUrl = entry.fallbackFaviconUrl,
            domain = entry.displayDomain,
            title = entry.feedTitle,
            size = 14.dp,
        )
        Text(
            text = entry.feedTitle,
            color = feedLabelColor,
            fontSize = 11.sp,
            fontWeight = feedLabelWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = "· ${entry.formattedPublishedLabel} · ${entry.readEstimateLabel}",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
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
}

private const val ARCHIVED_ROW_ALPHA = 0.55f
