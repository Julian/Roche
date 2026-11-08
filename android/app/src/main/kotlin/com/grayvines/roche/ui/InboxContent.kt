package com.grayvines.roche.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.ui.components.EntryThumbnailPreview
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalRocheColors

@Composable
fun InboxTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current

    Column(modifier = Modifier.fillMaxSize()) {
        InboxHeaderSection(state = state, onAction = onAction)
        InboxFilterStrip(state = state, onAction = onAction)
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = { onAction(MainAction.SyncNow) },
            modifier = Modifier.fillMaxSize().testTag("inbox_pull_to_refresh"),
        ) {
            if (state.visibleEntries.isEmpty()) {
                InboxEmptyState(
                    hasEntriesAtAll = state.allEntries.isNotEmpty(),
                    onSaveClick = { onAction(MainAction.OpenAddSheet()) },
                    onSyncClick = { onAction(MainAction.SyncNow) },
                )
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
}

@Composable
private fun InboxHeaderSection(state: MainUiState, onAction: (MainAction) -> Unit) {
    val hasScopeFilter = state.selectedFeedId != null || state.selectedTag != null
    val title =
        when {
            state.selectedFeedId != null -> state.selectedFeedTitle ?: "Feed"
            state.selectedTag != null -> "#${state.selectedTag}"
            else -> "Roche"
        }
    RocheTopHeader(
        title = title,
        subtitle = "${state.visibleEntries.size} items",
        navigationAction =
            if (hasScopeFilter) {
                {
                    HeaderActionButton(
                        label = "←",
                        onClick = {
                            onAction(MainAction.FilterByFeed(null))
                            onAction(MainAction.FilterByTag(null))
                        },
                        modifier = Modifier.testTag("clear_scope_btn"),
                    )
                }
            } else {
                null
            },
    ) {
        if (state.visibleEntries.any { !it.isArchived }) {
            HeaderActionButton(
                label = "Archive All",
                onClick = { onAction(MainAction.ArchiveVisibleEntries) },
                modifier = Modifier.testTag("archive_all_btn"),
            )
        }
        HeaderActionButton(label = "Sync", onClick = { onAction(MainAction.SyncNow) })
    }
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
                onClick = {
                    onAction(MainAction.FilterByTag(null))
                    onAction(MainAction.FilterByFeed(null))
                },
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
) {
    if (!swipeToArchiveEnabled) {
        InboxEntryRowSurface(
            entry = entry,
            ultraDense = ultraDense,
            onOpen = onOpen,
            onLongPress = onLongPress,
            onToggleStar = onToggleStar,
            onToggleArchive = onToggleArchive,
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
            onOpen = onOpen,
            onLongPress = onLongPress,
            onToggleStar = onToggleStar,
            onToggleArchive = onToggleArchive,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxEntryRowSurface(
    entry: InboxEntry,
    ultraDense: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val verticalPadding = if (ultraDense) 9.dp else 12.dp
    val rowAlpha = if (entry.isArchived) ARCHIVED_ROW_ALPHA else 1f

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.background)
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .padding(horizontal = 16.dp, vertical = verticalPadding)
                .alpha(rowAlpha)
                .testTag("entry_row_${entry.id}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (entry.showsThumbnailPreview) {
            EntryThumbnailPreview(entry = entry, modifier = Modifier.padding(top = 2.dp))
        }

        InboxEntryTextColumn(
            entry = entry,
            ultraDense = ultraDense,
            modifier = Modifier.weight(1f),
        )

        EntryPrimaryActionButtons(
            entry = entry,
            onToggleStar = onToggleStar,
            onToggleArchive = onToggleArchive,
        )
    }
}

@Composable
private fun EntryPrimaryActionButtons(
    entry: InboxEntry,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val starDescription = if (entry.isFavorite) "Unstar ${entry.title}" else "Star ${entry.title}"
    val archiveDescription =
        if (entry.isArchived) "Unarchive ${entry.title}" else "Archive ${entry.title}"
    val starTint = if (entry.isFavorite) colors.accentEcru else colors.textMuted

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
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
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
    val (titleColor, titleWeight) =
        if (entry.isUnread) {
            colors.textPrimary to FontWeight.SemiBold
        } else {
            colors.textSecondary to FontWeight.Normal
        }
    val (titleSize, titleLineHeight, maxTitleLines) =
        if (ultraDense) {
            Triple(14.sp, 19.sp, 1)
        } else {
            Triple(15.5.sp, 21.sp, 2)
        }
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
