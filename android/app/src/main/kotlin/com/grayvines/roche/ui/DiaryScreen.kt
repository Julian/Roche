package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheRefreshableTabScaffold
import com.grayvines.roche.ui.components.RocheStripTab
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.TierBadgePill
import com.grayvines.roche.ui.components.UnpinnedTabBackButton
import com.grayvines.roche.ui.components.resolveCatalogEntryOpenAction
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
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
    var statusFilter by rememberSaveable { mutableStateOf<ConsumptionStatus?>(null) }
    val allDiary = state.catalog.diaryEntries
    val filteredEntries =
        remember(allDiary, statusFilter) {
            if (statusFilter == null) allDiary else allDiary.filter { it.status == statusFilter }
        }

    RocheRefreshableTabScaffold(
        isRefreshing = state.isManualRefreshing,
        onRefresh = { onAction(MainAction.ManualRefresh) },
        pullToRefreshTestTag = "diary_pull_to_refresh",
        modifier = Modifier.testTag("diary_screen"),
        header = {
            DiaryTopHeaderBar(
                entryCount = filteredEntries.size,
                showBackToHome = state.isUnpinnedDestination(AppTab.DIARY),
                onBackToHome = { onAction(MainAction.SelectTab(AppTab.HOME)) },
                onOpenSearch = { onAction(MainAction.OpenSearch) },
            )
            DiaryStatusFilterStrip(
                selectedStatus = statusFilter,
                onSelectStatus = { statusFilter = it },
            )
        },
    ) {
        if (filteredEntries.isEmpty()) {
            DiaryEmptyBody(
                hasAnyEntries = allDiary.isNotEmpty(),
                onQuickLog = { onAction(MainAction.OpenQuickLogSheet(false)) },
            )
        } else {
            DiaryMonthsList(
                state = state,
                diaryEntries = filteredEntries,
                listState = listState,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }
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
            label = "Consumed",
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
            label = "Abandoned",
            selected = selectedStatus == ConsumptionStatus.ABANDONED,
            onClick = { onSelectStatus(ConsumptionStatus.ABANDONED) },
            modifier = Modifier.testTag("diary_filter_abandoned"),
        )
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
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
                "Finished podcasts, archived read articles, active list draws, and logged media appear here chronologically.",
            ctaLabel = "+ Log First Entry",
            onCta = onQuickLog,
        )
    }
}

@Composable
private fun DiaryMonthsList(
    state: MainUiState,
    diaryEntries: List<DiaryEntryItem>,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val grouped = remember(diaryEntries) { diaryEntries.groupBy { it.monthKey } }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        grouped.forEach { (monthKey, itemsInMonth) ->
            item(key = "diary_month_$monthKey") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DiaryMonthSectionHeader(
                        label = formatDiaryMonthLabel(monthKey),
                        count = itemsInMonth.size,
                    )
                    RocheSurfaceCard {
                        itemsInMonth.forEachIndexed { idx, diary ->
                            if (idx > 0) {
                                HorizontalDivider(color = colors.border, thickness = 1.dp)
                            }
                            val onOpen =
                                resolveCatalogEntryOpenAction(
                                    entryId = diary.entryId,
                                    externalUrl = state.catalogUrlsById[diary.entryId],
                                    readableIds = state.readableEntryIds,
                                    onOpenReader = { onAction(MainAction.OpenReader(it)) },
                                    onOpenExternalUrl = onOpenExternalUrl,
                                    entryKind = diary.kind,
                                    onOpenCatalogDetail = {
                                        onAction(MainAction.OpenCatalogDetailSheet(it))
                                    },
                                )
                            DiaryLogRow(diary = diary, onOpen = onOpen)
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(72.dp)) }
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

@Composable
private fun DiaryLogRow(diary: DiaryEntryItem, onOpen: (() -> Unit)?) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val meta = remember(diary.creators, diary.releaseYear) { formatDiaryMetaLine(diary) }
    val clickMod =
        onOpen?.let { Modifier.clickable(onClick = it).semantics { role = Role.Button } }
            ?: Modifier
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(clickMod)
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("diary_row_${diary.id}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = diary.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
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
        DiaryTrailingBadge(diary = diary)
    }
}

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
