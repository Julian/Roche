@file:Suppress("LongMethod", "CognitiveComplexMethod", "MagicNumber", "TooManyFunctions")

package com.grayvines.roche.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.playback.PlayerDrawerTab
import com.grayvines.roche.playback.PodcastChapter
import com.grayvines.roche.playback.PodcastPlayerState
import com.grayvines.roche.playback.formatPlaybackClock
import com.grayvines.roche.playback.formatPlaybackSpeedLabel
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.MoreDotsIcon
import com.grayvines.roche.ui.components.PodcastArtworkBox
import com.grayvines.roche.ui.components.PodcastSpeedStrip
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.SettingToggleRow
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedAndPodcastSheet(
    feed: FeedItem?,
    playerState: PodcastPlayerState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val speedRange = Settings.MAX_PLAYBACK_SPEED - Settings.MIN_PLAYBACK_SPEED
    var previewSpeedFraction by remember { mutableStateOf<Float?>(null) }
    val currentSpeed = playerState.playbackSpeed
    val displayedSpeed =
        previewSpeedFraction?.let { frac ->
            clampPlaybackSpeed(Settings.MIN_PLAYBACK_SPEED + frac * speedRange)
        } ?: currentSpeed
    val speedFormatted = formatPlaybackSpeedLabel(displayedSpeed)
    val rememberSpeedForPodcast = feed?.customPlaybackSpeed != null
    val scopeLabel =
        if (feed?.customPlaybackSpeed != null) {
            "Podcast override · ${feed.title}"
        } else {
            "Global default"
        }
    val autoDownloadEpisodes = feed?.autoDownloadEpisodes == true

    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.SetPlayerSpeedSheetOpen(false)) },
        containerColor = colors.surface,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("playback_speed_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "PLAYBACK SPEED",
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = scopeLabel,
                    color = colors.accentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.weight(1f, fill = false)
                            .padding(start = 12.dp)
                            .testTag("speed_scope_label"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderActionButton(
                    label = "−0.05",
                    contentDescription = "Decrease playback speed by 0.05x",
                    onClick = {
                        onAction(
                            MainAction.SetPlaybackSpeed(
                                clampPlaybackSpeed(currentSpeed - Settings.PLAYBACK_SPEED_STEP)
                            )
                        )
                    },
                    modifier = Modifier.testTag("speed_minus_btn"),
                )

                Text(
                    text = speedFormatted,
                    color = colors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("speed_current_label"),
                )

                HeaderActionButton(
                    label = "+0.05",
                    contentDescription = "Increase playback speed by 0.05x",
                    onClick = {
                        onAction(
                            MainAction.SetPlaybackSpeed(
                                clampPlaybackSpeed(currentSpeed + Settings.PLAYBACK_SPEED_STEP)
                            )
                        )
                    },
                    modifier = Modifier.testTag("speed_plus_btn"),
                )
            }

            val speedFraction =
                ((currentSpeed - Settings.MIN_PLAYBACK_SPEED) / speedRange).coerceIn(0f, 1f)
            RocheInteractiveSlider(
                fraction = speedFraction,
                onFractionChangeFinished = { frac ->
                    val raw = Settings.MIN_PLAYBACK_SPEED + frac * speedRange
                    onAction(MainAction.SetPlaybackSpeed(clampPlaybackSpeed(raw)))
                },
                onDragPreviewChange = { previewSpeedFraction = it },
                contentDescription = "Playback speed",
                stateDescription = speedFormatted,
                modifier = Modifier.fillMaxWidth().testTag("speed_slider"),
            )

            PodcastSpeedStrip(
                selectedSpeed = currentSpeed,
                onSelectSpeed = { preset -> onAction(MainAction.SetPlaybackSpeed(preset)) },
                chipTestTagPrefix = "speed_preset_",
            )

            HorizontalDivider(color = colors.border, thickness = 1.dp)

            if (feed != null) {
                SettingToggleRow(
                    title = "Remember speed for ${feed.title}",
                    subtitle = "Override global default speed for episodes from ${feed.title}",
                    checked = rememberSpeedForPodcast,
                    onCheckedChange = { enabled ->
                        onAction(MainAction.SetRememberPodcastSpeed(feed.id, enabled))
                    },
                )
            }

            SettingToggleRow(
                title = "Smart rewind",
                subtitle = "Rewind a few seconds when resuming after a pause",
                checked = playerState.smartRewindEnabled,
                onCheckedChange = { enabled -> onAction(MainAction.SetSmartRewind(enabled)) },
            )

            SettingToggleRow(
                title = "Skip silence",
                subtitle = "Trim silent pauses during speech playback",
                checked = playerState.skipSilenceEnabled,
                onCheckedChange = { enabled -> onAction(MainAction.SetSkipSilence(enabled)) },
            )

            if (feed != null) {
                SettingToggleRow(
                    title = "Always download new episodes",
                    subtitle = "Automatically cache new episodes from ${feed.title} on this device",
                    checked = autoDownloadEpisodes,
                    onCheckedChange = { enabled ->
                        onAction(MainAction.SetFeedAutoDownload(feed.id, enabled))
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerContentDrawerSheet(
    entry: InboxEntry,
    playerState: PodcastPlayerState,
    upNextAudioEntries: List<InboxEntry>,
    activeTab: PlayerDrawerTab,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val scope = rememberCoroutineScope()
    val showNotesScrollState = rememberScrollState()
    val chaptersListState = rememberLazyListState()
    val upNextListState = rememberLazyListState()
    val activeChapterIndex = playerState.currentChapter?.index

    LaunchedEffect(activeTab) {
        if (activeTab == PlayerDrawerTab.CHAPTERS && activeChapterIndex != null) {
            val targetIdx = playerState.chapters.indexOfFirst { it.index == activeChapterIndex }
            if (targetIdx >= 0) {
                chaptersListState.scrollToItem((targetIdx - 1).coerceAtLeast(0))
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.SelectPlayerDrawerTab(null)) },
        containerColor = colors.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().testTag("player_content_drawer_sheet")) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                FullscreenPlayerDrawerBar(
                    playerState = playerState,
                    upNextCount = upNextAudioEntries.size,
                    activeDrawerTab = activeTab,
                    onSelectTab = { tapped ->
                        if (tapped == activeTab) {
                            scope.launch {
                                when (tapped) {
                                    PlayerDrawerTab.SHOW_NOTES ->
                                        showNotesScrollState.animateScrollTo(0)
                                    PlayerDrawerTab.CHAPTERS ->
                                        chaptersListState.animateScrollToItem(0)
                                    PlayerDrawerTab.UP_NEXT ->
                                        upNextListState.animateScrollToItem(0)
                                }
                            }
                        } else {
                            onAction(MainAction.SelectPlayerDrawerTab(tapped))
                        }
                    },
                )
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)

            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                when (activeTab) {
                    PlayerDrawerTab.SHOW_NOTES -> {
                        Column(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .verticalScroll(showNotesScrollState)
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            ReaderBodyContent(entry = entry, onOpenExternalUrl = onOpenExternalUrl)
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }
                    PlayerDrawerTab.CHAPTERS -> {
                        PlayerChaptersTabBody(
                            chapters = playerState.chapters,
                            currentChapterIndex = activeChapterIndex,
                            listState = chaptersListState,
                            onSelectChapter = { startMs ->
                                onAction(MainAction.SeekPlayerToMs(startMs))
                                onAction(MainAction.SelectPlayerDrawerTab(null))
                            },
                        )
                    }
                    PlayerDrawerTab.UP_NEXT -> {
                        PlayerUpNextTabBody(
                            entries = upNextAudioEntries,
                            listState = upNextListState,
                            onPlayEntry = { targetId ->
                                onAction(MainAction.PlayAudioEntry(targetId, openFullscreen = true))
                                onAction(MainAction.SelectPlayerDrawerTab(null))
                            },
                            onMoveToTop = { targetId ->
                                onAction(MainAction.MoveQueueItemToTop(targetId))
                            },
                            onRemoveFromQueue = { targetId ->
                                onAction(MainAction.RemoveFromQueue(targetId))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerChaptersTabBody(
    chapters: List<PodcastChapter>,
    currentChapterIndex: Int?,
    listState: LazyListState,
    onSelectChapter: (Long) -> Unit,
) {
    val colors = LocalRocheColors.current
    if (chapters.isEmpty()) {
        Text(
            text = "No chapters available for this episode.",
            color = colors.textMuted,
            fontSize = 13.5.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
        )
        return
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().testTag("player_chapters_list"),
    ) {
        itemsIndexed(items = chapters, key = { _, ch -> "${ch.index}_${ch.startMs}" }) { _, chapter
            ->
            val isCurrent = currentChapterIndex == chapter.index
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isCurrent) {
                                colors.accentBlue.copy(alpha = 0.14f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .clickable { onSelectChapter(chapter.startMs) }
                        .semantics { role = Role.Button }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${chapter.index}. ${chapter.title}",
                    color = if (isCurrent) colors.accentBlue else colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f).padding(end = 10.dp),
                )
                Text(
                    text = formatPlaybackClock(chapter.startMs),
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                )
            }
        }
    }
}

@Composable
private fun PlayerUpNextTabBody(
    entries: List<InboxEntry>,
    listState: LazyListState,
    onPlayEntry: (String) -> Unit,
    onMoveToTop: (String) -> Unit,
    onRemoveFromQueue: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    if (entries.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "No other episodes in Up Next.",
                color = colors.textSecondary,
                fontSize = 13.5.sp,
            )
            Text(
                text = "Queue podcast episodes from Inbox or Queue to play them next.",
                color = colors.textMuted,
                fontSize = 12.sp,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().testTag("player_up_next_list"),
    ) {
        itemsIndexed(items = entries, key = { _, item -> item.id }) { index, item ->
            PlayerUpNextEpisodeRow(
                position = index + 1,
                entry = item,
                canMoveToTop = index > 0,
                onPlay = { onPlayEntry(item.id) },
                onMoveToTop = { onMoveToTop(item.id) },
                onRemove = { onRemoveFromQueue(item.id) },
            )
        }
    }
}

@Composable
private fun PlayerUpNextEpisodeRow(
    position: Int,
    entry: InboxEntry,
    canMoveToTop: Boolean,
    onPlay: () -> Unit,
    onMoveToTop: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalRocheColors.current
    var showMenu by rememberSaveable(entry.id) { mutableStateOf(false) }
    val subtitle = buildString {
        append(entry.feedTitle)
        entry.duration
            ?.takeIf { it.isNotBlank() }
            ?.let { dur ->
                append(" · ")
                append(dur)
            }
    }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onPlay)
                .semantics { role = Role.Button }
                .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)
                .testTag("player_up_next_row_${entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "$position",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
        )
        PodcastArtworkBox(entry = entry, size = 40.dp, cornerRadius = 8.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.title,
                color = colors.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = colors.textMuted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            RocheIconActionButton(
                contentDescription = "Episode queue options",
                outlined = false,
                onClick = { showMenu = true },
                modifier = Modifier.testTag("player_up_next_more_${entry.id}"),
            ) {
                MoreDotsIcon(tint = colors.textSecondary, size = 16.dp)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Play Now") },
                    onClick = {
                        showMenu = false
                        onPlay()
                    },
                )
                if (canMoveToTop) {
                    DropdownMenuItem(
                        text = { Text("Move to Top") },
                        onClick = {
                            showMenu = false
                            onMoveToTop()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Remove from Queue", color = colors.accentIndianRed) },
                    onClick = {
                        showMenu = false
                        onRemove()
                    },
                )
            }
        }
    }
}

internal fun computeSliderThumbCenterX(
    width: Float,
    fraction: Float,
    thumbRadius: Float,
): Float {
    if (width <= 0f) return 0f
    val clampedFraction = fraction.coerceIn(0f, 1f)
    val activeWidth = (width * clampedFraction).coerceIn(0f, width)
    val maxCenter = (width - thumbRadius).coerceAtLeast(thumbRadius)
    return if (width <= thumbRadius * 2f) {
        width * 0.5f
    } else {
        activeWidth.coerceIn(thumbRadius, maxCenter)
    }
}

@Composable
private fun SliderSeekHoldEffect(
    fraction: Float,
    pendingSeekFraction: Float?,
    isDragging: Boolean,
    onClearPending: () -> Unit,
) {
    val currentOnClear by rememberUpdatedState(onClearPending)
    LaunchedEffect(fraction, pendingSeekFraction, isDragging) {
        if (
            pendingSeekFraction != null &&
                !isDragging &&
                abs(fraction - pendingSeekFraction) <= SEEK_SETTLE_TOLERANCE
        ) {
            currentOnClear()
        }
    }
    LaunchedEffect(pendingSeekFraction) {
        if (pendingSeekFraction != null) {
            delay(SEEK_HOLD_TIMEOUT_MS)
            currentOnClear()
        }
    }
}

@Composable
internal fun RocheInteractiveSlider(
    fraction: Float,
    onFractionChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDragPreviewChange: ((Float?) -> Unit)? = null,
    contentDescription: String? = null,
    stateDescription: String? = null,
) {
    val colors = LocalRocheColors.current
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var pendingSeekFraction by remember { mutableStateOf<Float?>(null) }
    val currentOnFinished by rememberUpdatedState(onFractionChangeFinished)
    val currentOnPreview by rememberUpdatedState(onDragPreviewChange)

    SliderSeekHoldEffect(
        fraction = fraction,
        pendingSeekFraction = pendingSeekFraction,
        isDragging = dragFraction != null,
        onClearPending = {
            pendingSeekFraction = null
            if (dragFraction == null) {
                currentOnPreview?.invoke(null)
            }
        },
    )

    val displayFraction = (dragFraction ?: pendingSeekFraction ?: fraction).coerceIn(0f, 1f)
    Canvas(
        modifier =
            modifier
                .height(48.dp)
                .semantics {
                    if (contentDescription != null) {
                        this.contentDescription = contentDescription
                    }
                    if (stateDescription != null) {
                        this.stateDescription = stateDescription
                    }
                    progressBarRangeInfo = ProgressBarRangeInfo(displayFraction, 0f..1f)
                    setProgress { targetValue ->
                        val target = targetValue.coerceIn(0f, 1f)
                        dragFraction = null
                        pendingSeekFraction = target
                        currentOnPreview?.invoke(target)
                        currentOnFinished(target)
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (size.width > 0) {
                            val target = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            dragFraction = null
                            pendingSeekFraction = target
                            currentOnPreview?.invoke(target)
                            currentOnFinished(target)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            if (size.width > 0) {
                                val next = (startOffset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                pendingSeekFraction = null
                                dragFraction = next
                                currentOnPreview?.invoke(next)
                            }
                        },
                        onDragEnd = {
                            val committed = dragFraction
                            dragFraction = null
                            if (committed != null) {
                                pendingSeekFraction = committed
                                currentOnPreview?.invoke(committed)
                                currentOnFinished(committed)
                            } else {
                                currentOnPreview?.invoke(null)
                            }
                        },
                        onDragCancel = {
                            dragFraction = null
                            pendingSeekFraction = null
                            currentOnPreview?.invoke(null)
                        },
                    ) { change, _ ->
                        change.consume()
                        if (size.width > 0) {
                            val next = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            dragFraction = next
                            currentOnPreview?.invoke(next)
                        }
                    }
                }
    ) {
        if (size.width > 0f) {
            val trackHeight = 4.dp.toPx()
            val thumbRadius = 7.dp.toPx()
            val centerY = size.height * 0.5f
            val activeWidth = (size.width * displayFraction).coerceIn(0f, size.width)
            val thumbCenterX = computeSliderThumbCenterX(size.width, displayFraction, thumbRadius)
            val corner = CornerRadius(trackHeight * 0.5f, trackHeight * 0.5f)
            drawRoundRect(
                color = colors.controlOutline,
                topLeft = Offset(0f, centerY - trackHeight * 0.5f),
                size = Size(size.width, trackHeight),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = colors.accentBlue,
                topLeft = Offset(0f, centerY - trackHeight * 0.5f),
                size = Size(activeWidth, trackHeight),
                cornerRadius = corner,
            )
            drawCircle(
                color = colors.accentBlue,
                radius = thumbRadius,
                center = Offset(thumbCenterX, centerY),
            )
        }
    }
}

private const val SEEK_SETTLE_TOLERANCE = 0.02f
private const val SEEK_HOLD_TIMEOUT_MS = 600L
