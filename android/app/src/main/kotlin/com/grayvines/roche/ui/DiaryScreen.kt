@file:Suppress("TooManyFunctions", "MagicNumber")

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.DiaryEntryItem
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.ui.components.CatalogCoverThumbnail
import com.grayvines.roche.ui.components.RocheAlertDialog
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheRefreshableTabScaffold
import com.grayvines.roche.ui.components.RocheStripTab
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.TierBadgePill
import com.grayvines.roche.ui.components.UnpinnedTabBackButton
import com.grayvines.roche.ui.components.lazyGroupedCardRow
import com.grayvines.roche.ui.components.resolveCatalogEntryOpenAction
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DiaryScreen(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onOpenExternalUrl: (String) -> Unit = {},
) {
    val diary = state.diary
    var editingLogId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingLog =
        remember(editingLogId, diary.entries) {
            editingLogId?.let { id -> diary.entries.firstOrNull { it.id == id } }
        }

    RocheRefreshableTabScaffold(
        isRefreshing = state.isManualRefreshing,
        onRefresh = { onAction(MainAction.ManualRefresh) },
        pullToRefreshTestTag = "diary_pull_to_refresh",
        modifier = Modifier.testTag("diary_screen"),
        header = {
            DiaryTopHeaderBar(
                entryCount = diary.totalFilteredCount,
                showBackToHome = state.isUnpinnedDestination(AppTab.DIARY),
                onBackToHome = { onAction(MainAction.SelectTab(AppTab.HOME)) },
                onOpenSearch = { onAction(MainAction.OpenSearch) },
            )
            DiaryStatusFilterStrip(
                selectedStatus = diary.statusFilter,
                onSelectStatus = { onAction(MainAction.SetDiaryStatusFilter(it)) },
                availableYears = diary.availableYears,
                selectedYear = diary.yearFilter,
                onSelectYear = { onAction(MainAction.SetDiaryYearFilter(it)) },
            )
        },
    ) {
        if (diary.entries.isEmpty()) {
            if (diary.isLoaded || state.catalog.totalDiaryCount == 0) {
                DiaryEmptyBody(
                    hasAnyEntries = diary.hasAnyEntries,
                    onQuickLog = { onAction(MainAction.OpenQuickLogSheet(false)) },
                )
            }
        } else {
            DiaryMonthsList(
                state = state,
                diaryEntries = diary.entries,
                monthCounts = diary.monthCounts,
                hasMore = diary.hasMore,
                isLoadingMore = diary.isLoadingMore,
                totalFilteredCount = diary.totalFilteredCount,
                listState = listState,
                onAction = onAction,
                onEditLog = { editingLogId = it.id },
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }
    if (editingLog != null) {
        DiaryEditLogDialogHost(
            log = editingLog,
            onDismiss = { editingLogId = null },
            onAction = onAction,
        )
    }
}

@Composable
internal fun DiaryEditLogDialogHost(
    log: DiaryEntryItem,
    onDismiss: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    EditDiaryLogDialog(
        log = log,
        onDismiss = onDismiss,
        onSave = { status, notes, tsIso ->
            onDismiss()
            onAction(
                MainAction.UpdateConsumptionLog(
                    logId = log.id,
                    status = status,
                    notes = notes.takeIf { it.isNotEmpty() },
                    clearNotes = notes.isEmpty(),
                    tsIso = tsIso,
                )
            )
        },
        onDelete = {
            onDismiss()
            onAction(MainAction.DeleteConsumptionLog(log.id))
        },
    )
}

@Composable
private fun DiaryTopHeaderBar(
    entryCount: Int,
    showBackToHome: Boolean,
    onBackToHome: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheTopHeader(
        title = "Diary",
        badgeText = entryCount.toString(),
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
            modifier = Modifier.testTag("diary_search_btn"),
        ) {
            SearchIcon(tint = colors.textSecondary, size = 17.dp)
        }
    }
}

@Composable
private fun DiaryStatusFilterStrip(
    selectedStatus: ConsumptionStatus?,
    onSelectStatus: (ConsumptionStatus?) -> Unit,
    availableYears: List<String>,
    selectedYear: String?,
    onSelectYear: (String?) -> Unit,
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
        RocheStripTab(
            label = "All",
            selected = selectedStatus == null,
            onClick = { onSelectStatus(null) },
            modifier = Modifier.testTag("diary_filter_all"),
        )
        RocheStripTab(
            label = "Finished",
            selected = selectedStatus == ConsumptionStatus.CONSUMED,
            onClick = { onSelectStatus(ConsumptionStatus.CONSUMED) },
            modifier = Modifier.testTag("diary_filter_consumed"),
        )
        RocheStripTab(
            label = "Started",
            selected = selectedStatus == ConsumptionStatus.STARTED,
            onClick = { onSelectStatus(ConsumptionStatus.STARTED) },
            modifier = Modifier.testTag("diary_filter_started"),
        )
        RocheStripTab(
            label = "Skipped",
            selected = selectedStatus == ConsumptionStatus.ABANDONED,
            onClick = { onSelectStatus(ConsumptionStatus.ABANDONED) },
            modifier = Modifier.testTag("diary_filter_abandoned"),
        )
    }
    if (availableYears.size >= 2) {
        DiaryYearFilterStrip(
            years = availableYears,
            selectedYear = selectedYear,
            onSelectYear = onSelectYear,
        )
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
private fun DiaryYearFilterStrip(
    years: List<String>,
    selectedYear: String?,
    onSelectYear: (String?) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RocheStripTab(
            label = "All Years",
            selected = selectedYear == null,
            onClick = { onSelectYear(null) },
            modifier = Modifier.testTag("diary_year_all"),
        )
        years.forEach { year ->
            RocheStripTab(
                label = year,
                selected = selectedYear == year,
                onClick = { onSelectYear(year) },
                modifier = Modifier.testTag("diary_year_$year"),
            )
        }
    }
}

@Composable
private fun DiaryEmptyBody(
    hasAnyEntries: Boolean,
    onQuickLog: () -> Unit,
) {
    if (hasAnyEntries) {
        val colors = LocalRocheColors.current
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No diary entries match this filter.",
                color = colors.textMuted,
                fontSize = 13.sp,
            )
        }
    } else {
        CompanionEmptyCard(
            title = "Your consumption diary is empty",
            body =
                "Finished articles, podcasts, videos, active list draws, and logged media appear here chronologically.",
            ctaLabel = "+ Log First Entry",
            onCta = onQuickLog,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun DiaryMonthsList(
    state: MainUiState,
    diaryEntries: List<DiaryEntryItem>,
    monthCounts: Map<String, Int>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    totalFilteredCount: Int,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onEditLog: (DiaryEntryItem) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val grouped = remember(diaryEntries) { diaryEntries.groupBy { it.monthKey }.entries.toList() }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        grouped.forEachIndexed { groupIdx, (monthKey, itemsInMonth) ->
            item(key = "diary_month_head_$monthKey") {
                if (groupIdx > 0) Spacer(modifier = Modifier.height(14.dp))
                DiaryMonthSectionHeader(
                    label = formatDiaryMonthLabel(monthKey),
                    count = monthCounts[monthKey] ?: itemsInMonth.size,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            itemsIndexed(
                items = itemsInMonth,
                key = { _, diary -> diary.id },
            ) { idx, diary ->
                DiaryGroupedLogRow(
                    diary = diary,
                    isFirst = idx == 0,
                    isLast = idx == itemsInMonth.lastIndex,
                    readableEntryIds = state.readableEntryIds,
                    onAction = onAction,
                    onEditLog = onEditLog,
                    onOpenExternalUrl = onOpenExternalUrl,
                )
            }
        }
        if (hasMore) {
            item(key = "diary_load_more") {
                DiaryLoadMoreFooter(
                    remaining = (totalFilteredCount - diaryEntries.size).coerceAtLeast(0),
                    isLoadingMore = isLoadingMore,
                    onLoadMore = { onAction(MainAction.LoadMoreDiary) },
                )
            }
        }
        item(key = "diary_bottom_spacer") { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Suppress("LongParameterList")
@Composable
private fun DiaryGroupedLogRow(
    diary: DiaryEntryItem,
    isFirst: Boolean,
    isLast: Boolean,
    readableEntryIds: Set<String>,
    onAction: (MainAction) -> Unit,
    onEditLog: (DiaryEntryItem) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val onOpen =
        resolveCatalogEntryOpenAction(
            entryId = diary.entryId,
            externalUrl = diary.url,
            readableIds = readableEntryIds,
            onOpenReader = { onAction(MainAction.OpenReader(it)) },
            onOpenExternalUrl = onOpenExternalUrl,
            entryKind = diary.kind,
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
        DiaryLogRow(diary = diary, onOpen = onOpen, onEdit = { onEditLog(diary) })
    }
}

@Composable
private fun DiaryLoadMoreFooter(
    remaining: Int,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Spacer(modifier = Modifier.height(12.dp))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(
            enabled = !isLoadingMore,
            onClick = onLoadMore,
            modifier = Modifier.testTag("diary_load_more_btn"),
        ) {
            Text(
                text = if (isLoadingMore) "Loading…" else "Load More ($remaining remaining)",
                color = colors.accentBlue,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun DiaryMonthSectionHeader(label: String, count: Int) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiaryLogRow(
    diary: DiaryEntryItem,
    onOpen: (() -> Unit)?,
    onEdit: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 52.dp)
                .combinedClickable(onClick = onOpen ?: onEdit, onLongClick = onEdit)
                .semantics { role = Role.Button }
                .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp)
                .testTag("diary_row_${diary.id}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = diary.dateLabel,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 44.dp),
        )
        CatalogCoverThumbnail(thumbnailUrl = diary.thumbnailUrl, kind = diary.kind, size = 34.dp)
        DiaryLogMetaColumn(diary = diary, modifier = Modifier.weight(1f))
        DiaryTrailingBadge(diary = diary)
        RocheIconActionButton(
            contentDescription = "Edit diary entry for ${diary.title}",
            outlined = false,
            onClick = onEdit,
            modifier = Modifier.testTag("diary_edit_btn_${diary.id}"),
        ) {
            Text(
                text = "⋯",
                color = colors.textMuted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DiaryLogMetaColumn(
    diary: DiaryEntryItem,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val meta = remember(diary.creators, diary.releaseYear) { formatDiaryMetaLine(diary) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = diary.title,
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                color = colors.textSecondary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!diary.notes.isNullOrBlank()) {
            Text(
                text = diary.notes,
                color = colors.accentEcru,
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun EditDiaryLogDialog(
    log: DiaryEntryItem,
    onDismiss: () -> Unit,
    onSave: (status: ConsumptionStatus, notes: String, tsIso: String?) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val initialDate = remember(log.ts) { log.ts.take(10) }
    var status by rememberSaveable(log.id) { mutableStateOf(log.status) }
    var dateText by rememberSaveable(log.id) { mutableStateOf(initialDate) }
    var notesText by rememberSaveable(log.id) { mutableStateOf(log.notes.orEmpty()) }
    var confirmingDelete by rememberSaveable(log.id) { mutableStateOf(false) }

    if (confirmingDelete) {
        DeleteDiaryConfirmDialog(
            log = log,
            onDismiss = { confirmingDelete = false },
            onDelete = onDelete,
        )
        return
    }

    val cleanDate = dateText.trim()
    val isDateValid = remember(cleanDate) { parseIsoDateOrNull(cleanDate) != null }
    RocheAlertDialog(
        title = "Edit Diary Entry",
        onDismissRequest = onDismiss,
        text = {
            EditDiaryDialogFields(
                title = log.title,
                status = status,
                dateText = dateText,
                isDateValid = isDateValid,
                notesText = notesText,
                onStatusChange = { status = it },
                onDateChange = { dateText = it },
                onNotesChange = { notesText = it },
                onRequestDelete = { confirmingDelete = true },
            )
        },
        confirmButton = {
            TextButton(
                enabled = isDateValid,
                onClick = {
                    val nextTs = if (cleanDate != initialDate) "${cleanDate}T12:00:00Z" else null
                    onSave(status, notesText.trim(), nextTs)
                },
                modifier = Modifier.testTag("diary_edit_save_btn"),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
        },
    )
}

@Composable
private fun DeleteDiaryConfirmDialog(
    log: DiaryEntryItem,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheAlertDialog(
        title = "Delete diary entry?",
        onDismissRequest = onDismiss,
        text = {
            Text(
                text =
                    "Remove this ${log.status.displayLabel.lowercase()} log for \"${log.title}\"?",
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDelete,
                modifier = Modifier.testTag("diary_delete_confirm_btn"),
            ) {
                Text("Delete", color = colors.accentIndianRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
        },
    )
}

@Suppress("LongParameterList")
@Composable
private fun EditDiaryDialogFields(
    title: String,
    status: ConsumptionStatus,
    dateText: String,
    isDateValid: Boolean,
    notesText: String,
    onStatusChange: (ConsumptionStatus) -> Unit,
    onDateChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onRequestDelete: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ConsumptionStatus.entries.forEach { option ->
                RocheStripTab(
                    label = option.displayLabel,
                    selected = status == option,
                    onClick = { onStatusChange(option) },
                )
            }
        }
        OutlinedTextField(
            value = dateText,
            onValueChange = onDateChange,
            label = { Text("Date (YYYY-MM-DD)") },
            isError = !isDateValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("diary_edit_date_input"),
        )
        OutlinedTextField(
            value = notesText,
            onValueChange = onNotesChange,
            label = { Text("Notes") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("diary_edit_notes_input"),
        )
        TextButton(
            onClick = onRequestDelete,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            modifier = Modifier.testTag("diary_delete_btn"),
        ) {
            Text("Delete Diary Entry", color = colors.accentIndianRed, fontSize = 12.5.sp)
        }
    }
}

private fun parseIsoDateOrNull(raw: String): LocalDate? = runCatching {
    LocalDate.parse(raw)
}
    .getOrNull()

@Composable
private fun DiaryTrailingBadge(diary: DiaryEntryItem) {
    val colors = LocalRocheColors.current
    if (diary.status == ConsumptionStatus.CONSUMED && diary.tier != null) {
        TierBadgePill(tier = diary.tier)
        return
    }
    if (diary.status == ConsumptionStatus.CONSUMED) return
    val tint =
        if (diary.status == ConsumptionStatus.STARTED) {
            colors.accentBlue
        } else {
            colors.accentIndianRed
        }
    Box(
        modifier =
            Modifier.clip(RoundedCornerShape(5.dp))
                .background(tint.copy(alpha = 0.14f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = diary.status.displayLabel,
            color = tint,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

private fun formatDiaryMonthLabel(monthKey: String): String = runCatching {
    YearMonth.parse(monthKey).format(DIARY_MONTH_HEADER_FMT)
}
    .getOrDefault(monthKey)

private fun formatDiaryMetaLine(diary: DiaryEntryItem): String = buildList {
    if (diary.creators.isNotEmpty()) add(diary.creators.joinToString(", "))
    if (diary.releaseYear != null && diary.releaseYear > 0) {
        add(diary.releaseYear.toString())
    }
}
    .joinToString(" · ")

private val DIARY_MONTH_HEADER_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
