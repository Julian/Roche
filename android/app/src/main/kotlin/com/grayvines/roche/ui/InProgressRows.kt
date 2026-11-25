@file:Suppress("MagicNumber", "TooManyFunctions")

package com.grayvines.roche.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.core.formatInlineMathAnnotated
import com.grayvines.roche.data.catalog.DiaryEntryItem
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.CatalogCoverThumbnail
import com.grayvines.roche.ui.components.EntrySourceMetadataRow
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.MoreDotsIcon
import com.grayvines.roche.ui.components.PlayPauseIcon
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheMetaPill
import com.grayvines.roche.ui.components.rememberEntryThumbnailBitmap
import com.grayvines.roche.ui.components.resolveCatalogEntryOpenAction
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

@Composable
@Suppress("LongParameterList")
internal fun InProgressRowDispatcher(
    item: InProgressItem,
    readableIds: Set<String>,
    activeAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    testTagPrefix: String,
) {
    when (item) {
        is InProgressItem.ReaderItem ->
            InProgressReaderRowForItem(
                entry = item.entry,
                activeAudioEntryId = activeAudioEntryId,
                onAction = onAction,
                testTagPrefix = testTagPrefix,
            )
        is InProgressItem.StartedCatalogItem ->
            InProgressStartedRowForItem(
                item = item.item,
                readableIds = readableIds,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                testTagPrefix = testTagPrefix,
            )
    }
}

@Composable
private fun InProgressReaderRowForItem(
    entry: InboxEntry,
    activeAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
    testTagPrefix: String,
) {
    val onPlayAudio: (() -> Unit)? =
        if (entry.isPlayableAudio) {
            { onAction(MainAction.PlayAudioEntry(entry.id, openFullscreen = false)) }
        } else {
            null
        }
    InProgressReaderRow(
        entry = entry,
        isPlayingAudio = entry.id == activeAudioEntryId,
        onOpen = { onAction(MainAction.OpenReader(entry.id)) },
        onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
        onPlayAudio = onPlayAudio,
        onFinish = {
            onAction(MainAction.MarkFeedEntryFinished(entry.id, rankImmediately = false))
        },
        onFinishAndRank = {
            onAction(MainAction.MarkFeedEntryFinished(entry.id, rankImmediately = true))
        },
        onDismiss = { onAction(MainAction.ToggleArchive(entry)) },
        testTagPrefix = testTagPrefix,
    )
}

@Composable
private fun InProgressStartedRowForItem(
    item: DiaryEntryItem,
    readableIds: Set<String>,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    testTagPrefix: String,
) {
    val onOpen =
        resolveCatalogEntryOpenAction(
            entryId = item.entryId,
            externalUrl = item.url,
            readableIds = readableIds,
            onOpenReader = { onAction(MainAction.OpenReader(it)) },
            onOpenExternalUrl = onOpenExternalUrl,
            entryKind = item.kind,
            onOpenCatalogDetail = { onAction(MainAction.OpenCatalogDetailSheet(it)) },
        ) ?: { onAction(MainAction.OpenCatalogDetailSheet(item.entryId)) }
    InProgressStartedCatalogRow(
        item = item,
        onOpen = onOpen,
        onFinish = {
            onAction(MainAction.CompleteWantEntry(item.entryId, rankImmediately = false))
        },
        onFinishAndRank = {
            onAction(MainAction.CompleteWantEntry(item.entryId, rankImmediately = true))
        },
        onSkip = { onAction(MainAction.SkipStartedEntry(item.entryId)) },
        testTagPrefix = testTagPrefix,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongParameterList")
internal fun InProgressReaderRow(
    entry: InboxEntry,
    isPlayingAudio: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onPlayAudio: (() -> Unit)?,
    onFinish: () -> Unit,
    onFinishAndRank: () -> Unit,
    onDismiss: () -> Unit,
    testTagPrefix: String,
) {
    val colors = LocalRocheColors.current
    val rowBg = if (isPlayingAudio) colors.accentBlue.copy(alpha = 0.12f) else Color.Transparent
    var menuExpanded by rememberSaveable("${testTagPrefix}_${entry.id}") { mutableStateOf(false) }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(rowBg)
                .drawLeftAccentBar(if (isPlayingAudio) colors.accentBlue else null)
                .heightIn(min = 52.dp)
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .semantics { role = Role.Button }
                .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp)
                .testTag("${testTagPrefix}_${entry.id}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.hasThumbnailSource()) {
            InProgressEntryThumbnail(entry = entry)
        }
        InProgressReaderMetaColumn(entry = entry, modifier = Modifier.weight(1f))
        InProgressReaderActions(
            entry = entry,
            isPlayingAudio = isPlayingAudio,
            onPlayAudio = onPlayAudio,
            menuExpanded = menuExpanded,
            onMenuExpandedChange = { menuExpanded = it },
            onFinish = onFinish,
            onFinishAndRank = onFinishAndRank,
            onDismiss = onDismiss,
            onOpenDetails = onLongPress,
            testTagPrefix = testTagPrefix,
        )
    }
}

@Composable
private fun InProgressReaderMetaColumn(
    entry: InboxEntry,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val progressFraction = entry.readProgress.toFloat().coerceIn(0.03f, 0.98f)
    val metaSuffix =
        remember(entry.formattedProgressPct, entry.duration, entry.isPlayableAudio) {
            buildList {
                    if (entry.isPlayableAudio && !entry.duration.isNullOrBlank()) {
                        add(entry.duration)
                    }
                    add("${entry.formattedProgressPct}%")
                }
                .joinToString(" · ")
                .let { "· $it" }
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = remember(entry.title) { formatInlineMathAnnotated(entry.title) },
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 14.5.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        EntrySourceMetadataRow(
            entry = entry,
            trailingMetaText = metaSuffix,
            showOfflineIndicator = true,
            showStarDot = entry.isFavorite,
        )
        InProgressBar(progressFraction = progressFraction)
    }
}

@Composable
@Suppress("LongParameterList")
private fun InProgressReaderActions(
    entry: InboxEntry,
    isPlayingAudio: Boolean,
    onPlayAudio: (() -> Unit)?,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
    onFinishAndRank: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDetails: () -> Unit,
    testTagPrefix: String,
) {
    val colors = LocalRocheColors.current
    val playingFlag = LocalIsAudioActuallyPlaying.current ?: true
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onPlayAudio != null) {
            val showPause = isPlayingAudio && playingFlag
            RocheIconActionButton(
                contentDescription =
                    if (showPause) "Pause ${entry.title}" else "Play ${entry.title}",
                onClick = onPlayAudio,
                outlined = false,
                modifier = Modifier.testTag("${testTagPrefix}_play_${entry.id}"),
            ) {
                PlayPauseIcon(isPlaying = showPause, tint = colors.accentBlue, size = 15.dp)
            }
        }
        InProgressReaderOverflowMenu(
            entry = entry,
            menuExpanded = menuExpanded,
            onMenuExpandedChange = onMenuExpandedChange,
            onFinish = onFinish,
            onFinishAndRank = onFinishAndRank,
            onDismiss = onDismiss,
            onOpenDetails = onOpenDetails,
            testTagPrefix = testTagPrefix,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun InProgressReaderOverflowMenu(
    entry: InboxEntry,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
    onFinishAndRank: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDetails: () -> Unit,
    testTagPrefix: String,
) {
    val colors = LocalRocheColors.current
    Box {
        RocheIconActionButton(
            contentDescription = "Options for ${entry.title}",
            outlined = false,
            onClick = { onMenuExpandedChange(true) },
            modifier = Modifier.testTag("${testTagPrefix}_more_${entry.id}"),
        ) {
            MoreDotsIcon(tint = colors.textSecondary, size = 16.dp)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { onMenuExpandedChange(false) },
            containerColor = colors.surfaceElevated,
        ) {
            DropdownMenuItem(
                text = { Text("✓ Mark Finished", color = colors.accentVerdigris) },
                onClick = {
                    onMenuExpandedChange(false)
                    onFinish()
                },
            )
            DropdownMenuItem(
                text = { Text("Finish & Rank", color = colors.textPrimary) },
                onClick = {
                    onMenuExpandedChange(false)
                    onFinishAndRank()
                },
            )
            DropdownMenuItem(
                text = { Text("Dismiss", color = colors.textPrimary) },
                onClick = {
                    onMenuExpandedChange(false)
                    onDismiss()
                },
            )
            DropdownMenuItem(
                text = { Text("Details & Tags…", color = colors.textPrimary) },
                onClick = {
                    onMenuExpandedChange(false)
                    onOpenDetails()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongParameterList")
internal fun InProgressStartedCatalogRow(
    item: DiaryEntryItem,
    onOpen: () -> Unit,
    onFinish: () -> Unit,
    onFinishAndRank: () -> Unit,
    onSkip: () -> Unit,
    testTagPrefix: String,
) {
    var menuExpanded by
        rememberSaveable("${testTagPrefix}_cat_${item.entryId}") {
            mutableStateOf(false)
        }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 52.dp)
                .combinedClickable(onClick = onOpen, onLongClick = { menuExpanded = true })
                .semantics { role = Role.Button }
                .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp)
                .testTag("${testTagPrefix}_${item.entryId}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogCoverThumbnail(
            thumbnailUrl = item.thumbnailUrl,
            kind = item.kind,
            size = 38.dp,
            cornerRadius = 5.dp,
        )
        StartedCatalogMetaColumn(item = item, modifier = Modifier.weight(1f))
        HeaderActionButton(
            label = "✓ Done",
            onClick = onFinish,
            horizontalPadding = 10.dp,
            modifier = Modifier.testTag("${testTagPrefix}_done_${item.entryId}"),
        )
        StartedCatalogOverflowMenu(
            entryId = item.entryId,
            title = item.title,
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            onFinishAndRank = onFinishAndRank,
            onSkip = onSkip,
            testTagPrefix = testTagPrefix,
        )
    }
}

@Composable
private fun StartedCatalogMetaColumn(
    item: DiaryEntryItem,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val subtitle =
        remember(item.creators, item.releaseYear, item.dateLabel) {
            buildList {
                    val base = buildHomeDiarySubtitle(item)
                    if (base.isNotBlank()) add(base)
                    if (item.dateLabel.isNotBlank()) add("Started ${item.dateLabel}")
                }
                .joinToString(" · ")
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = remember(item.title) { formatInlineMathAnnotated(item.title) },
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 14.5.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RocheMetaPill(text = "Started", textColor = colors.accentBlue)
            RocheMetaPill(
                text = item.kind.displayLabel,
                textColor = colors.textSecondary,
                backgroundColor = colors.surfaceElevated,
                borderColor = colors.border,
            )
        }
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                color = colors.textMuted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!item.notes.isNullOrBlank()) {
            Text(
                text = item.notes,
                color = colors.textSecondary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun StartedCatalogOverflowMenu(
    entryId: String,
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onFinishAndRank: () -> Unit,
    onSkip: () -> Unit,
    testTagPrefix: String,
) {
    val colors = LocalRocheColors.current
    Box {
        RocheIconActionButton(
            contentDescription = "Options for $title",
            outlined = false,
            onClick = { onExpandedChange(true) },
            modifier = Modifier.testTag("${testTagPrefix}_more_$entryId"),
        ) {
            MoreDotsIcon(tint = colors.textSecondary, size = 16.dp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = colors.surfaceElevated,
        ) {
            DropdownMenuItem(
                text = { Text("Finish & Rank", color = colors.textPrimary) },
                onClick = {
                    onExpandedChange(false)
                    onFinishAndRank()
                },
            )
            DropdownMenuItem(
                text = { Text("Skip", color = colors.accentIndianRed) },
                onClick = {
                    onExpandedChange(false)
                    onSkip()
                },
                modifier = Modifier.testTag("${testTagPrefix}_skip_$entryId"),
            )
        }
    }
}

@Composable
private fun InProgressBar(progressFraction: Float) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(999.dp)
    Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(shape).background(colors.border)) {
        Box(
            modifier =
                Modifier.fillMaxWidth(progressFraction)
                    .height(3.dp)
                    .clip(shape)
                    .background(colors.accentBlue)
        )
    }
}

@Composable
private fun InProgressEntryThumbnail(entry: InboxEntry) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(5.dp)
    val bitmap =
        rememberEntryThumbnailBitmap(
            primaryKey = entry.thumbnailMediaId,
            fallbackKey = entry.effectiveThumbnailUrl,
        )
    Box(
        modifier =
            Modifier.size(width = 52.dp, height = 38.dp)
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
