@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.core.parseIsoInstant
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.reader.extractInboxSnippet
import com.grayvines.roche.ui.components.DownloadTrayIcon
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.FilterFunnelIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.PredictiveBackDrawerContainer
import com.grayvines.roche.ui.components.RocheAlertDialog
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheRefreshableTabScaffold
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.components.drawArticleImagePlaceholder
import com.grayvines.roche.ui.components.rememberEntryThumbnailBitmap
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun InboxTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    activeAudioEntryId: String? = null,
) {
    var showTagFilterDrawer by rememberSaveable { mutableStateOf(false) }

    RocheRefreshableTabScaffold(
        isRefreshing = state.isSyncing,
        onRefresh = { onAction(MainAction.SyncNow) },
        pullToRefreshTestTag = "inbox_pull_to_refresh",
        header = {
            InboxHeaderSection(
                state = state,
                showTagFilterDrawer = showTagFilterDrawer,
                onToggleTagDrawer = { showTagFilterDrawer = !showTagFilterDrawer },
                onAction = onAction,
            )
        },
        subHeader = { InboxFilterStrip(state = state, onAction = onAction) },
        drawer = {
            PredictiveBackDrawerContainer(
                visible = showTagFilterDrawer && state.tags.isNotEmpty(),
                onDismiss = { showTagFilterDrawer = false },
            ) { drawerModifier ->
                InboxTagFilterDrawer(
                    state = state,
                    modifier = drawerModifier,
                    onSelectTag = { tagName ->
                        showTagFilterDrawer = false
                        onAction(MainAction.FilterByTag(tagName))
                    },
                )
            }
        },
    ) {
        InboxEntryListBody(
            state = state,
            onAction = onAction,
            listState = listState,
            activeAudioEntryId = activeAudioEntryId,
        )
    }
}

@Composable
internal fun InboxEntryListBody(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    activeAudioEntryId: String? = null,
) {
    val colors = LocalRocheColors.current
    if (state.visibleEntries.isEmpty()) {
        InboxEmptyOrLoadingPane(state = state, onAction = onAction)
        return
    }

    val nextQueuedAudioId = state.resolveNextQueuedAudioEntry(activeAudioEntryId)?.id
    val zoneId = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zoneId) }
    val dayBuckets =
        remember(state.visibleEntries, today, zoneId) {
            state.visibleEntries.map { entry -> resolveInboxDayBucket(entry, today, zoneId) }
        }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("inbox_list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        itemsIndexed(items = state.visibleEntries, key = { _, item -> item.id }) { index, entry ->
            val bucket = dayBuckets[index]
            val showDividerHeader =
                bucket != null && (index == 0 || dayBuckets[index - 1] != bucket)
            Column(modifier = Modifier.fillMaxWidth()) {
                if (showDividerHeader) {
                    InboxDayDivider(label = bucket)
                }
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
                HorizontalDivider(color = colors.border, thickness = 1.dp)
            }
        }
    }
}

@Composable
private fun InboxEmptyOrLoadingPane(state: MainUiState, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    if (state.isInitialLoading) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp).testTag("inbox_loading_state"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Loading inbox…",
                color = colors.textMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
            )
        }
    } else {
        InboxEmptyState(
            hasEntriesAtAll = state.allEntries.any { it.inFeedInbox },
            onManageFeedsClick = { onAction(MainAction.SelectTab(AppTab.FEEDS)) },
            onSyncClick = { onAction(MainAction.SyncNow) },
        )
    }
}

@Composable
private fun InboxDayDivider(label: String) {
    val colors = LocalRocheColors.current
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.background)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 5.dp)
    ) {
        Text(
            text = label.uppercase(),
            color = colors.textMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.semantics { heading() },
        )
    }
}

internal fun resolveInboxDayBucket(
    entry: InboxEntry,
    today: LocalDate,
    zoneId: ZoneId,
): String? {
    val instant =
        parseIsoInstant(entry.publishedAt) ?: parseIsoInstant(entry.createdAt) ?: return null
    val entryDate = instant.atZone(zoneId).toLocalDate()
    val daysAgo = ChronoUnit.DAYS.between(entryDate, today)
    return when {
        daysAgo <= 0L -> "Today"
        daysAgo == 1L -> "Yesterday"
        daysAgo <= DAYS_IN_WEEK -> "This Week"
        else -> "Earlier"
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
    val badgeText =
        if (state.isInitialLoading && state.visibleEntries.isEmpty()) {
            null
        } else {
            NumberFormat.getIntegerInstance(Locale.US).format(state.visibleEntries.size)
        }

    RocheTopHeader(
        title = title,
        badgeText = badgeText,
        navigationAction =
            if (hasScopeFilter) {
                {
                    HeaderActionButton(
                        label = "←",
                        contentDescription = "Back",
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
            onOpenSearch = { onAction(MainAction.OpenSearch) },
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
        else -> "Inbox"
    }

@Composable
internal fun InboxHeaderActions(
    state: MainUiState,
    unarchivedCount: Int,
    showTagFilterDrawer: Boolean,
    onToggleTagDrawer: () -> Unit,
    onShowArchiveConfirm: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val colors = LocalRocheColors.current
    if (state.tags.isNotEmpty()) {
        val filterTint =
            if (showTagFilterDrawer || state.selectedTag != null) {
                colors.accentBlue
            } else {
                colors.textSecondary
            }
        RocheIconActionButton(
            contentDescription = "Filter by Tag",
            outlined = false,
            onClick = onToggleTagDrawer,
            modifier = Modifier.testTag("inbox_tag_filter_btn"),
        ) {
            FilterFunnelIcon(tint = filterTint, size = 17.dp)
        }
    }
    if (unarchivedCount > 0) {
        RocheIconActionButton(
            contentDescription = "Archive All",
            outlined = false,
            onClick = onShowArchiveConfirm,
            modifier = Modifier.testTag("archive_all_btn"),
        ) {
            FilingCabinetIcon(tint = colors.textSecondary, isArchived = false, size = 17.dp)
        }
    }
    RocheIconActionButton(
        contentDescription = "Search",
        outlined = true,
        onClick = onOpenSearch,
        modifier = Modifier.testTag("inbox_search_btn"),
    ) {
        SearchIcon(tint = colors.textSecondary, size = 17.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxTagFilterDrawer(
    state: MainUiState,
    onSelectTag: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("inbox_tag_filter_drawer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "FILTER BY TAG",
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
    RocheAlertDialog(
        title = "Archive $unarchivedCount $noun?",
        onDismissRequest = onDismiss,
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
    onManageFeedsClick: () -> Unit,
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
                        "No feed items match this filter."
                    } else {
                        "Your feed inbox is empty."
                    },
                color = colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeaderActionButton(label = "Manage Feeds", onClick = onManageFeedsClick)
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
                .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.selectedTag != null || state.selectedFeedId != null) {
            val scopeLabel =
                state.selectedTag?.let { "#$it ✕" } ?: "${state.selectedFeedTitle ?: "Feed"} ✕"
            InboxFilterTab(
                label = scopeLabel,
                selected = true,
                role = Role.Button,
                onClick = { onAction(MainAction.NavigateBackFromScope) },
            )
        }
        InboxFilter.inboxStripFilters.forEach { filter ->
            InboxFilterTab(
                label = filter.label,
                selected = state.activeFilter == filter,
                onClick = { onAction(MainAction.SelectFilter(filter)) },
            )
        }
    }
}

@Composable
private fun InboxFilterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    role: Role = Role.Tab,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val bg = if (selected) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent
    val textColor = if (selected) colors.accentBlue else colors.textSecondary
    val weight = if (selected) FontWeight.Medium else FontWeight.Normal

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    this.role = role
                    this.selected = selected
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 30.dp)
                    .clip(shape)
                    .background(bg)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = weight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
    val verticalPadding = if (ultraDense) 8.dp else 10.dp
    val (rowBackground, leftAccentColor) =
        resolveInboxRowHighlight(colors, isPlayingAudio, isQueuedNextAudio)
    val rowStateLabel = buildInboxRowStateLabel(entry, isPlayingAudio, isQueuedNextAudio)

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
                .semantics {
                    if (rowStateLabel.isNotEmpty()) {
                        stateDescription = rowStateLabel
                    }
                }
                .padding(
                    start = 4.dp,
                    end = 4.dp,
                    top = verticalPadding,
                    bottom = verticalPadding,
                )
                .testTag("entry_row_${entry.id}"),
        verticalAlignment = if (ultraDense) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EntryLeadingStarButton(entry = entry, onToggleStar = onToggleStar)

        if (entry.showsThumbnailPreview) {
            InboxLandscapeThumbnail(entry = entry, ultraDense = ultraDense)
        }

        InboxEntryTextColumn(
            entry = entry,
            ultraDense = ultraDense,
            modifier = Modifier.weight(1f).padding(start = 2.dp),
        )

        EntryTrailingActionButtons(
            entry = entry,
            onDownloadOffline = onDownloadOffline,
            onToggleArchive = onToggleArchive,
        )
    }
}

@Composable
private fun InboxLandscapeThumbnail(entry: InboxEntry, ultraDense: Boolean) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(5.dp)
    val landscapePath = remember { Path() }
    val width = if (ultraDense) 46.dp else 62.dp
    val height = if (ultraDense) 34.dp else 44.dp
    val bitmap =
        rememberEntryThumbnailBitmap(
            primaryKey = entry.thumbnailMediaId,
            fallbackKey = entry.effectiveThumbnailUrl,
        )
    Box(
        modifier =
            Modifier.padding(top = if (ultraDense) 0.dp else 2.dp)
                .size(width = width, height = height)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawArticleImagePlaceholder(colors = colors, landscape = landscapePath)
            }
        }
    }
}

private fun resolveInboxRowHighlight(
    colors: RocheColors,
    isPlayingAudio: Boolean,
    isQueuedNextAudio: Boolean,
): Pair<Color, Color?> =
    when {
        isPlayingAudio -> colors.accentBlue.copy(alpha = PLAYING_ROW_BG_ALPHA) to colors.accentBlue
        isQueuedNextAudio ->
            colors.accentVerdigris.copy(alpha = QUEUED_ROW_BG_ALPHA) to colors.accentVerdigris
        else -> colors.background to null
    }

private fun buildInboxRowStateLabel(
    entry: InboxEntry,
    isPlayingAudio: Boolean,
    isQueuedNextAudio: Boolean,
): String = buildList {
    when {
        isPlayingAudio -> add("Now playing")
        isQueuedNextAudio -> add("Queued next")
    }
    when {
        entry.isArchived -> add("Archived")
        entry.isUnread -> add("Unread")
    }
}
    .joinToString(", ")

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
            size = 16.dp,
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
        horizontalArrangement = Arrangement.spacedBy(0.dp),
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
                DownloadTrayIcon(tint = colors.accentBlue, size = 16.dp)
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
                size = 16.dp,
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
    val snippet =
        remember(entry.summary, ultraDense) {
            if (ultraDense) "" else extractInboxSnippet(entry.summary.orEmpty())
        }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        InboxEntryHeadlineRow(entry = entry, ultraDense = ultraDense)
        InboxEntryBylineRow(entry = entry)
        if (snippet.isNotBlank()) {
            Text(
                text = snippet,
                color = colors.textMuted,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        entry.formattedListeningHistoryLabel?.let { historyLabel ->
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
private fun InboxEntryHeadlineRow(entry: InboxEntry, ultraDense: Boolean) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val titleColor = if (entry.isUnread) colors.textPrimary else colors.textSecondary
    val (titleSize, titleLineHeight, maxTitleLines) =
        if (ultraDense) Triple(14.sp, 19.sp, 1) else Triple(15.sp, 20.sp, 2)

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
            text = entry.title,
            color = titleColor,
            fontFamily = headlineFamily,
            fontSize = titleSize,
            lineHeight = titleLineHeight,
            fontWeight = FontWeight.Normal,
            maxLines = maxTitleLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InboxEntryBylineRow(entry: InboxEntry) {
    val colors = LocalRocheColors.current
    val kindLabel =
        when (entry.contentType) {
            ContentType.PDF -> "PDF"
            ContentType.AUDIO -> "Audio"
            ContentType.VIDEO -> "Video"
            ContentType.ARTICLE -> null
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (kindLabel != null) {
            InboxEntryKindPill(label = kindLabel)
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
            color = colors.textSecondary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = "· ${entry.formattedPublishedLabel} · ${entry.readEstimateLabel}",
            color = colors.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
        )
        if (entry.isCachedOffline) {
            Text(
                text = "↓",
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.semantics { contentDescription = "Cached offline" },
            )
        }
    }
}

@Composable
private fun InboxEntryKindPill(label: String) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier =
            Modifier.clip(shape)
                .background(colors.accentVerdigris.copy(alpha = 0.14f))
                .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            color = colors.accentVerdigris,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
        )
    }
}

private const val PLAYING_ROW_BG_ALPHA = 0.12f
private const val QUEUED_ROW_BG_ALPHA = 0.11f
private const val DAYS_IN_WEEK = 7L
