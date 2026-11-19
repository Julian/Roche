@file:Suppress("LongMethod", "CognitiveComplexMethod", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.grayvines.roche.data.settings.PLAYBACK_SPEED_PRESETS
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.SettingToggleRow
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedAndPodcastSheet(
    feed: FeedItem?,
    entry: InboxEntry,
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
    val podcastTitle = feed?.title ?: entry.feedTitle
    val rememberSpeedForPodcast = feed?.customPlaybackSpeed != null
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
                    text = podcastTitle,
                    color = colors.accentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PLAYBACK_SPEED_PRESETS.forEach { preset ->
                    val label = formatPlaybackSpeedLabel(preset)
                    FilterChipPill(
                        label = label,
                        selected = abs(currentSpeed - preset) < 0.02f,
                        onClick = { onAction(MainAction.SetPlaybackSpeed(preset)) },
                        modifier = Modifier.testTag("speed_preset_$label"),
                    )
                }
            }

            HorizontalDivider(color = colors.border, thickness = 1.dp)

            if (feed != null) {
                SettingToggleRow(
                    title = "Remember speed for ${feed.title}",
                    subtitle = "Override global default speed for episodes from this podcast",
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
    activeTab: PlayerDrawerTab,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.SelectPlayerDrawerTab(null)) },
        containerColor = colors.surface,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("player_content_drawer_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FullscreenPlayerDrawerBar(playerState = playerState, onAction = onAction)
            HorizontalDivider(color = colors.border, thickness = 1.dp)

            when (activeTab) {
                PlayerDrawerTab.SHOW_NOTES -> {
                    ReaderBodyContent(entry = entry, onOpenExternalUrl = onOpenExternalUrl)
                }
                PlayerDrawerTab.CHAPTERS -> {
                    if (playerState.chapters.isEmpty()) {
                        Text(
                            text = "No chapters available for this episode.",
                            color = colors.textMuted,
                            fontSize = 13.5.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        playerState.chapters.forEach { chapter ->
                            val isCurrent = playerState.currentChapter?.index == chapter.index
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
                                        .clickable {
                                            onAction(MainAction.SeekPlayerToMs(chapter.startMs))
                                            onAction(MainAction.SelectPlayerDrawerTab(null))
                                        }
                                        .semantics { role = Role.Button }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${chapter.index}. ${chapter.title}",
                                    color =
                                        if (isCurrent) colors.accentBlue else colors.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight =
                                        if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
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
                PlayerDrawerTab.TRANSCRIPT -> {
                    val transcriptText =
                        remember(entry.contentText, entry.summary) {
                            entry.contentText?.takeIf { it.isNotBlank() }
                                ?: entry.summary?.takeIf { it.isNotBlank() }
                                ?: "No episode transcript is embedded in this feed."
                        }
                    SelectionContainer {
                        Text(
                            text = transcriptText,
                            color = colors.textPrimary,
                            fontFamily = FontFamily.Serif,
                            fontSize = 15.sp,
                            lineHeight = 23.sp,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
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
    val currentOnFinished by rememberUpdatedState(onFractionChangeFinished)
    val currentOnPreview by rememberUpdatedState(onDragPreviewChange)
    val displayFraction = (dragFraction ?: fraction).coerceIn(0f, 1f)
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
                        currentOnPreview?.invoke(null)
                        currentOnFinished(target)
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (size.width > 0) {
                            val target = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            dragFraction = null
                            currentOnPreview?.invoke(null)
                            currentOnFinished(target)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            if (size.width > 0) {
                                val next = (startOffset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                dragFraction = next
                                currentOnPreview?.invoke(next)
                            }
                        },
                        onDragEnd = {
                            val committed = dragFraction
                            dragFraction = null
                            currentOnPreview?.invoke(null)
                            if (committed != null) {
                                currentOnFinished(committed)
                            }
                        },
                        onDragCancel = {
                            dragFraction = null
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
