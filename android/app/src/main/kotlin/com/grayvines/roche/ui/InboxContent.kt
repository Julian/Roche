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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.ui.components.EntryThumbnailPreview
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
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
        InboxTopBar(state = state, onAction = onAction)

        InboxFilterStrip(state = state, onAction = onAction)
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        if (state.visibleEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No entries match this view.",
                    color = colors.textMuted,
                    fontSize = 13.sp,
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
private fun InboxTopBar(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Roche",
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 23.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${state.visibleEntries.size} items",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        HeaderActionButton(label = "Sync", onClick = { onAction(MainAction.SyncNow) })
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
        Box(
            modifier =
                Modifier.size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleStar)
                    .semantics { contentDescription = starDescription }
                    .testTag("star_btn_${entry.id}"),
            contentAlignment = Alignment.Center,
        ) {
            StarIcon(
                tint = starTint,
                isStarred = entry.isFavorite,
                size = 17.dp,
            )
        }
        Box(
            modifier =
                Modifier.size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleArchive)
                    .semantics { contentDescription = archiveDescription }
                    .testTag("archive_btn_${entry.id}"),
            contentAlignment = Alignment.Center,
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
    val titleSize = if (ultraDense) 14.sp else 15.5.sp
    val titleLineHeight = if (ultraDense) 19.sp else 21.sp
    val maxTitleLines = if (ultraDense) 1 else 2

    Column(modifier = modifier) {
        EntrySourceMetaRow(entry = entry)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = entry.title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = titleSize,
            lineHeight = titleLineHeight,
            fontWeight = FontWeight.Medium,
            maxLines = maxTitleLines,
            overflow = TextOverflow.Ellipsis,
        )
        val snippet = entry.summary
        if (!ultraDense && !snippet.isNullOrBlank()) {
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
    val sourceDotColor =
        when (entry.contentType) {
            ContentType.PDF -> colors.accentBlue
            ContentType.ARTICLE -> colors.accentVerdigris
            ContentType.VIDEO -> colors.accentIndianRed
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(sourceDotColor))
        Text(
            text = entry.feedTitle,
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "· ${entry.publishedAt ?: "recent"} · ${entry.readEstimateLabel}",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
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
