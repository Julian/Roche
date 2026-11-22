@file:Suppress("TooManyFunctions", "MagicNumber", "MatchingDeclarationName")

package com.grayvines.roche.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
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
import com.grayvines.roche.ui.components.EntrySourceMetadataRow
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.FilterFunnelIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.PlayPauseIcon
import com.grayvines.roche.ui.components.PredictiveBackDrawerContainer
import com.grayvines.roche.ui.components.RocheAlertDialog
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheRefreshableTabScaffold
import com.grayvines.roche.ui.components.RocheStripTab
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.components.UnpinnedTabBackButton
import com.grayvines.roche.ui.components.rememberEntryThumbnailBitmap
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

internal data class InboxRowLayout(
    val showLeadingStarButton: Boolean,
    val showCompactStarDot: Boolean,
    val showTrailingArchiveButton: Boolean,
)

internal fun resolveInboxRowLayout(
    isCompactWidth: Boolean,
    swipeToArchiveEnabled: Boolean,
): InboxRowLayout =
    InboxRowLayout(
        showLeadingStarButton = !isCompactWidth,
        showCompactStarDot = isCompactWidth,
        showTrailingArchiveButton = !isCompactWidth || !swipeToArchiveEnabled,
    )

@Composable
@Suppress("LongParameterList")
fun InboxTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    activeAudioEntryId: String? = null,
    entries: List<InboxEntry> = state.visibleEntries,
    selectedFeedId: String? = state.selectedFeedId,
    selectedTag: String? = state.selectedTag,
) {
    var showTagFilterDrawer by rememberSaveable { mutableStateOf(false) }

    RocheRefreshableTabScaffold(
        isRefreshing = state.isManualRefreshing,
        onRefresh = { onAction(MainAction.ManualRefresh) },
        pullToRefreshTestTag = "inbox_pull_to_refresh",
        header = {
            InboxHeaderSection(
                state = state,
                entries = entries,
                selectedFeedId = selectedFeedId,
                selectedTag = selectedTag,
                showTagFilterDrawer = showTagFilterDrawer,
                onToggleTagDrawer = { showTagFilterDrawer = !showTagFilterDrawer },
                onAction = onAction,
            )
        },
        subHeader = {
            InboxFilterStrip(
                state = state,
                selectedFeedId = selectedFeedId,
                onAction = onAction,
            )
        },
        drawer = {
            PredictiveBackDrawerContainer(
                visible = showTagFilterDrawer && state.tags.isNotEmpty(),
                onDismiss = { showTagFilterDrawer = false },
            ) { drawerModifier ->
                InboxTagFilterDrawer(
                    state = state,
                    selectedTag = selectedTag,
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
            entries = entries,
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
    entries: List<InboxEntry> = state.visibleEntries,
) {
    val colors = LocalRocheColors.current
    if (entries.isEmpty()) {
        InboxEmptyOrLoadingPane(state = state, onAction = onAction)
        return
    }

    val nextQueuedAudioId = state.resolveNextQueuedAudioEntry(activeAudioEntryId)?.id
    val zoneId = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zoneId) }
    val dayBuckets =
        remember(entries, today, zoneId) {
            entries.map { entry -> resolveInboxDayBucket(entry, today, zoneId) }
        }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactWidth = maxWidth < COMPACT_ROW_BREAKPOINT
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("inbox_list"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(items = entries, key = { _, item -> item.id }) { index, entry ->
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
                        isCompactWidth = isCompactWidth,
                        isPlayingAudio = entry.id == activeAudioEntryId,
                        isQueuedNextAudio = entry.id == nextQueuedAudioId,
                        isDownloadingOffline = entry.id in state.inFlightOfflineEntryIds,
                        onOpen = { onAction(MainAction.OpenReader(entry.id)) },
                        onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
                        onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                        onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
                        onPlayAudio = {
                            onAction(MainAction.PlayAudioEntry(entry.id, openFullscreen = false))
                        },
                        onDownloadOffline = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
                    )
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
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
            canResetFilter = state.activeFilter != InboxFilter.ALL,
            onShowAllClick = { onAction(MainAction.SelectFilter(InboxFilter.ALL)) },
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
@Suppress("LongParameterList")
private fun InboxHeaderSection(
    state: MainUiState,
    entries: List<InboxEntry>,
    selectedFeedId: String?,
    selectedTag: String?,
    showTagFilterDrawer: Boolean,
    onToggleTagDrawer: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val hasScopeFilter = selectedFeedId != null || selectedTag != null
    val unarchivedCount = entries.count { !it.isArchived }
    var showArchiveAllConfirm by rememberSaveable { mutableStateOf(false) }
    val title = resolveInboxHeaderTitle(state, selectedFeedId, selectedTag)
    val badgeText =
        if (state.isInitialLoading && entries.isEmpty()) {
            null
        } else {
            NumberFormat.getIntegerInstance(Locale.US).format(entries.size)
        }
    val navAction = resolveInboxNavAction(hasScopeFilter, state, onAction)

    RocheTopHeader(
        title = title,
        badgeText = badgeText,
        navigationAction = navAction,
    ) {
        InboxHeaderActions(
            state = state,
            selectedFeedId = selectedFeedId,
            selectedTag = selectedTag,
            unarchivedCount = unarchivedCount,
            showTagFilterDrawer = showTagFilterDrawer,
            onToggleTagDrawer = onToggleTagDrawer,
            onOpenFeedSettings =
                selectedFeedId?.let { feedId ->
                    { onAction(MainAction.OpenFeedContextSheet(feedId)) }
                },
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

private fun resolveInboxNavAction(
    hasScopeFilter: Boolean,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
): (@Composable () -> Unit)? =
    when {
        hasScopeFilter -> {
            {
                HeaderActionButton(
                    label = "←",
                    contentDescription = "Back",
                    onClick = { onAction(MainAction.NavigateBackFromScope) },
                    modifier = Modifier.testTag("clear_scope_btn"),
                )
            }
        }
        state.isUnpinnedDestination(AppTab.INBOX) -> {
            { UnpinnedTabBackButton(onBack = { onAction(MainAction.SelectTab(AppTab.HOME)) }) }
        }
        else -> null
    }

private fun resolveInboxHeaderTitle(
    state: MainUiState,
    selectedFeedId: String?,
    selectedTag: String?,
): String =
    when {
        selectedFeedId != null ->
            state.feeds.firstOrNull { it.id == selectedFeedId }?.title ?: "Feed"
        selectedTag != null -> "#$selectedTag"
        else -> "Inbox"
    }

@Composable
@Suppress("LongParameterList")
internal fun InboxHeaderActions(
    state: MainUiState,
    selectedFeedId: String? = null,
    selectedTag: String?,
    unarchivedCount: Int,
    showTagFilterDrawer: Boolean,
    onToggleTagDrawer: () -> Unit,
    onOpenFeedSettings: (() -> Unit)? = null,
    onShowArchiveConfirm: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val colors = LocalRocheColors.current
    if (selectedFeedId != null && onOpenFeedSettings != null) {
        RocheIconActionButton(
            contentDescription = "Feed settings",
            outlined = false,
            onClick = onOpenFeedSettings,
            modifier = Modifier.testTag("inbox_scoped_feed_settings_btn"),
        ) {
            Text(
                text = "⋯",
                color = colors.textSecondary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
    if (state.tags.isNotEmpty()) {
        val filterTint =
            if (showTagFilterDrawer || selectedTag != null) {
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
    selectedTag: String?,
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
                val isSelected = selectedTag == tag.name
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxEmptyState(
    hasEntriesAtAll: Boolean,
    canResetFilter: Boolean,
    onShowAllClick: () -> Unit,
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canResetFilter) {
                    HeaderActionButton(
                        label = "Show All",
                        onClick = onShowAllClick,
                        modifier = Modifier.testTag("inbox_show_all_btn"),
                    )
                }
                HeaderActionButton(label = "Manage Feeds", onClick = onManageFeedsClick)
                HeaderActionButton(label = "Sync", onClick = onSyncClick)
            }
        }
    }
}

internal fun resolveInboxStripFilters(
    state: MainUiState,
    selectedFeedId: String? = state.selectedFeedId,
): List<InboxFilter> {
    val feedId = selectedFeedId ?: return InboxFilter.inboxStripFilters
    val distinctShapes =
        state.allEntries
            .asSequence()
            .filter { it.feedId == feedId }
            .map { it.contentType to it.isPlayableAudio }
            .distinct()
            .take(2)
            .count()
    if (distinctShapes > 1) return InboxFilter.inboxStripFilters
    val base = listOf(InboxFilter.ALL, InboxFilter.UNREAD, InboxFilter.ARCHIVED)
    return if (state.activeFilter in base) base else base + state.activeFilter
}

@Composable
private fun InboxFilterStrip(
    state: MainUiState,
    selectedFeedId: String?,
    onAction: (MainAction) -> Unit,
) {
    val stripFilters =
        remember(selectedFeedId, state.activeFilter, state.allEntries) {
            resolveInboxStripFilters(state, selectedFeedId)
        }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stripFilters.forEach { filter ->
            RocheStripTab(
                label = filter.label,
                selected = state.activeFilter == filter,
                onClick = { onAction(MainAction.SelectFilter(filter)) },
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun InboxEntryRowItem(
    entry: InboxEntry,
    ultraDense: Boolean,
    swipeToArchiveEnabled: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
    isCompactWidth: Boolean = true,
    isPlayingAudio: Boolean = false,
    isQueuedNextAudio: Boolean = false,
    isDownloadingOffline: Boolean = false,
    onPlayAudio: (() -> Unit)? = null,
    onDownloadOffline: (() -> Unit)? = null,
) {
    val layout = resolveInboxRowLayout(isCompactWidth, swipeToArchiveEnabled)
    val rowContent: @Composable () -> Unit = {
        InboxEntryRowSurface(
            entry = entry,
            ultraDense = ultraDense,
            layout = layout,
            isPlayingAudio = isPlayingAudio,
            isQueuedNextAudio = isQueuedNextAudio,
            isDownloadingOffline = isDownloadingOffline,
            onOpen = onOpen,
            onLongPress = onLongPress,
            onToggleStar = onToggleStar,
            onToggleArchive = onToggleArchive,
            onPlayAudio = onPlayAudio,
            onDownloadOffline = onDownloadOffline,
        )
    }
    if (!swipeToArchiveEnabled) {
        rowContent()
        return
    }
    InboxSwipeableRowWrapper(
        entry = entry,
        onToggleArchive = onToggleArchive,
        content = rowContent,
    )
}

@Composable
private fun InboxSwipeableRowWrapper(
    entry: InboxEntry,
    onToggleArchive: () -> Unit,
    content: @Composable () -> Unit,
) {
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
        backgroundContent = { InboxSwipeArchiveBackground(isArchived = entry.isArchived) },
        content = { content() },
    )
}

@Composable
private fun InboxSwipeArchiveBackground(isArchived: Boolean) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxSize()
                .background(colors.accentVerdigris.copy(alpha = 0.22f))
                .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isArchived) "Restore" else "Archive",
            color = colors.accentVerdigris,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
        )
        FilingCabinetIcon(tint = colors.accentVerdigris, isArchived = isArchived)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongParameterList")
private fun InboxEntryRowSurface(
    entry: InboxEntry,
    ultraDense: Boolean,
    layout: InboxRowLayout,
    isPlayingAudio: Boolean,
    isQueuedNextAudio: Boolean,
    isDownloadingOffline: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
    onPlayAudio: (() -> Unit)?,
    onDownloadOffline: (() -> Unit)?,
) {
    val colors = LocalRocheColors.current
    val verticalPadding = if (ultraDense) 8.dp else 10.dp
    val startPadding = if (layout.showLeadingStarButton) 4.dp else 14.dp
    val endPadding = if (layout.showTrailingArchiveButton) 4.dp else 12.dp
    val (rowBackground, leftAccentColor) =
        resolveInboxRowHighlight(colors, isPlayingAudio, isQueuedNextAudio)
    val rowStateLabel = buildInboxRowStateLabel(entry, isPlayingAudio, isQueuedNextAudio)

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(rowBackground)
                .drawLeftAccentBar(leftAccentColor)
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .semantics {
                    if (rowStateLabel.isNotEmpty()) {
                        stateDescription = rowStateLabel
                    }
                }
                .padding(
                    start = startPadding,
                    end = endPadding,
                    top = verticalPadding,
                    bottom = verticalPadding,
                )
                .testTag("entry_row_${entry.id}"),
        verticalAlignment = if (ultraDense) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InboxEntryLeadingVisuals(
            entry = entry,
            ultraDense = ultraDense,
            showLeadingStarButton = layout.showLeadingStarButton,
            onToggleStar = onToggleStar,
        )
        InboxEntryTextColumn(
            entry = entry,
            ultraDense = ultraDense,
            showCompactStarDot = layout.showCompactStarDot,
            modifier = Modifier.weight(1f),
        )
        EntryTrailingActionButtons(
            entry = entry,
            isPlayingAudio = isPlayingAudio,
            showArchiveButton = layout.showTrailingArchiveButton,
            isDownloadingOffline = isDownloadingOffline,
            onPlayAudio = onPlayAudio,
            onDownloadOffline = onDownloadOffline,
            onToggleArchive = onToggleArchive,
        )
    }
}

internal fun Modifier.drawLeftAccentBar(accentColor: Color?): Modifier =
    if (accentColor == null) {
        this
    } else {
        drawBehind {
            drawRect(
                color = accentColor,
                topLeft = Offset.Zero,
                size = Size(width = 3.dp.toPx(), height = size.height),
            )
        }
    }

internal fun InboxEntry.hasThumbnailSource(): Boolean =
    showsThumbnailPreview &&
        (!thumbnailMediaId.isNullOrBlank() || !effectiveThumbnailUrl.isNullOrBlank())

@Composable
private fun InboxEntryLeadingVisuals(
    entry: InboxEntry,
    ultraDense: Boolean,
    showLeadingStarButton: Boolean,
    onToggleStar: () -> Unit,
) {
    if (showLeadingStarButton) {
        EntryLeadingStarButton(entry = entry, onToggleStar = onToggleStar)
    }
    if (entry.hasThumbnailSource()) {
        InboxLandscapeThumbnail(entry = entry, ultraDense = ultraDense)
    }
}

@Composable
private fun InboxLandscapeThumbnail(entry: InboxEntry, ultraDense: Boolean) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(5.dp)
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
    if (entry.isFavorite) add("Starred")
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
@Suppress("LongParameterList")
private fun EntryTrailingActionButtons(
    entry: InboxEntry,
    isPlayingAudio: Boolean,
    showArchiveButton: Boolean,
    isDownloadingOffline: Boolean,
    onPlayAudio: (() -> Unit)?,
    onDownloadOffline: (() -> Unit)?,
    onToggleArchive: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val showInlinePlay =
        entry.isPlayableAudio && entry.contentType != ContentType.AUDIO && onPlayAudio != null
    val showAudioDownload =
        entry.isPlayableAudio && !entry.isCachedOffline && onDownloadOffline != null
    if (!showInlinePlay && !showAudioDownload && !showArchiveButton) return

    val archiveDescription =
        if (entry.isArchived) "Unarchive ${entry.title}" else "Archive ${entry.title}"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (showInlinePlay) {
            EntryInlinePlayActionButton(
                entry = entry,
                isPlayingAudio = isPlayingAudio,
                onPlayAudio = onPlayAudio,
            )
        }
        if (showAudioDownload) {
            EntryDownloadActionButton(
                entry = entry,
                isDownloadingOffline = isDownloadingOffline,
                onDownloadOffline = onDownloadOffline,
            )
        }
        if (showArchiveButton) {
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
}

@Composable
private fun EntryInlinePlayActionButton(
    entry: InboxEntry,
    isPlayingAudio: Boolean,
    onPlayAudio: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val playingFlag = LocalIsAudioActuallyPlaying.current ?: true
    val showPause = isPlayingAudio && playingFlag
    RocheIconActionButton(
        contentDescription = if (showPause) "Pause ${entry.title}" else "Play ${entry.title}",
        onClick = onPlayAudio,
        outlined = false,
        modifier = Modifier.testTag("play_audio_btn_${entry.id}"),
    ) {
        PlayPauseIcon(isPlaying = showPause, tint = colors.accentBlue, size = 15.dp)
    }
}

internal val LocalIsAudioActuallyPlaying = compositionLocalOf<Boolean?> { null }

@Composable
private fun EntryDownloadActionButton(
    entry: InboxEntry,
    isDownloadingOffline: Boolean,
    onDownloadOffline: () -> Unit,
) {
    val colors = LocalRocheColors.current
    if (isDownloadingOffline) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).testTag("download_spinner_${entry.id}"),
        ) {
            CircularProgressIndicator(
                color = colors.accentBlue,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        }
    } else {
        RocheIconActionButton(
            contentDescription = "Download ${entry.title}",
            onClick = onDownloadOffline,
            outlined = false,
            modifier = Modifier.testTag("download_btn_${entry.id}"),
        ) {
            DownloadTrayIcon(tint = colors.accentBlue, size = 16.dp)
        }
    }
}

@Composable
private fun InboxEntryTextColumn(
    entry: InboxEntry,
    ultraDense: Boolean,
    showCompactStarDot: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val snippet =
        remember(entry.summary, ultraDense) {
            if (ultraDense) "" else extractInboxSnippet(entry.summary.orEmpty())
        }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        InboxEntryHeadlineRow(entry = entry, ultraDense = ultraDense)
        val audioBadge =
            if (entry.contentType != ContentType.AUDIO && entry.isPlayableAudio) "AUDIO" else null
        EntrySourceMetadataRow(
            entry = entry,
            trailingMetaText = "· ${entry.formattedPublishedLabel} · ${entry.readEstimateLabel}",
            trailingMetaFontSize = 11.sp,
            trailingBadge = audioBadge,
            trailingBadgeColor = colors.accentBlue,
            showOfflineIndicator = true,
            showStarDot = showCompactStarDot && entry.isFavorite,
        )
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
        if (entry.contentType == ContentType.AUDIO) {
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

internal val COMPACT_ROW_BREAKPOINT = 480.dp
internal val MAX_FILTER_DRAWER_HEIGHT = 220.dp
private const val PLAYING_ROW_BG_ALPHA = 0.12f
private const val QUEUED_ROW_BG_ALPHA = 0.11f
private const val DAYS_IN_WEEK = 7L
