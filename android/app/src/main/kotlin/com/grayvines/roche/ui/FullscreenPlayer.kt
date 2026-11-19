@file:Suppress("LongMethod", "TooManyFunctions", "CognitiveComplexMethod", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.playback.PlayerDrawerTab
import com.grayvines.roche.playback.PodcastPlayerState
import com.grayvines.roche.playback.formatPlaybackSpeedLabel
import com.grayvines.roche.ui.components.FastForwardIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.PlayPauseIcon
import com.grayvines.roche.ui.components.RewindIcon
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.SkipNextIcon
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.components.drawPodcastArtworkCanvas
import com.grayvines.roche.ui.components.rememberEntryThumbnailBitmap
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import kotlin.math.roundToLong

/**
 * Persistent docked mini-player rendered directly above the bottom navigation bar across all tabs
 * whenever an audio episode is active.
 */
@Composable
fun DockedMiniPlayer(
    entry: InboxEntry,
    playerState: PodcastPlayerState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val bitmap =
        rememberEntryThumbnailBitmap(
            primaryKey = entry.thumbnailMediaId,
            fallbackKey = entry.effectiveThumbnailUrl ?: entry.faviconUrl,
        )
    val artShape = RoundedCornerShape(8.dp)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.surfaceElevated)
                .clickable { onAction(MainAction.OpenFullscreenPlayer) }
                .testTag("docked_mini_player")
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(colors.border)) {
            Box(
                modifier =
                    Modifier.fillMaxWidth(playerState.progressFraction.coerceIn(0.02f, 1f))
                        .height(2.dp)
                        .background(colors.accentBlue)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier =
                    Modifier.size(40.dp)
                        .clip(artShape)
                        .background(Color(0xFF1A242D))
                        .border(1.dp, colors.border, artShape)
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
                        drawPodcastArtworkCanvas(colors)
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    color = colors.textPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (playerState.isBuffering) {
                            "${entry.feedTitle} · Buffering…"
                        } else {
                            "${entry.feedTitle} · ${playerState.speedAdjustedRemainingLabel}"
                        },
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                RocheIconActionButton(
                    contentDescription = "Rewind 15 seconds",
                    onClick = { onAction(MainAction.SkipPlayerBySeconds(-15)) },
                    outlined = false,
                    modifier = Modifier.testTag("mini_player_rewind_btn"),
                ) {
                    RewindIcon(tint = colors.textSecondary, size = 16.dp)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .clickable { onAction(MainAction.TogglePlayPause) }
                            .semantics {
                                role = Role.Button
                                contentDescription =
                                    when {
                                        playerState.isBuffering -> "Buffering"
                                        playerState.isPlaying -> "Pause"
                                        else -> "Play"
                                    }
                            }
                            .testTag("mini_player_play_pause_btn"),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier.size(34.dp).clip(CircleShape).background(colors.accentBlue),
                    ) {
                        if (playerState.isBuffering) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            PlayPauseIcon(
                                isPlaying = playerState.isPlaying,
                                tint = Color.White,
                                size = 15.dp,
                            )
                        }
                    }
                }
                RocheIconActionButton(
                    contentDescription = "Fast-forward 30 seconds",
                    onClick = { onAction(MainAction.SkipPlayerBySeconds(30)) },
                    outlined = false,
                    modifier = Modifier.testTag("mini_player_ff_btn"),
                ) {
                    FastForwardIcon(tint = colors.textSecondary, size = 16.dp)
                }
            }
        }
    }
}

/** Fullscreen AntennaPod-style podcast player screen. */
@Composable
fun FullscreenPodcastPlayer(
    entry: InboxEntry,
    feed: FeedItem?,
    playerState: PodcastPlayerState,
    nextQueuedAudioEntry: InboxEntry?,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("fullscreen_podcast_player"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        FullscreenPlayerTopBar(
            entry = entry,
            playerState = playerState,
            onAction = onAction,
            onShareEntry = onShareEntry,
            onOpenExternalUrl = onOpenExternalUrl,
        )

        Spacer(modifier = Modifier.height(8.dp))

        FullscreenPlayerArtworkAndMeta(
            entry = entry,
            playerState = playerState,
            onAction = onAction,
        )

        Spacer(modifier = Modifier.height(8.dp))

        PlayerScrubberSection(
            playerState = playerState,
            onAction = onAction,
        )

        Spacer(modifier = Modifier.height(8.dp))

        FullscreenPlayerTransportRow(
            playerState = playerState,
            hasNextQueuedAudio = nextQueuedAudioEntry != null,
            onAction = onAction,
        )

        Spacer(modifier = Modifier.height(18.dp))

        FullscreenPlayerDrawerBar(
            playerState = playerState,
            onAction = onAction,
        )
    }

    if (playerState.isSpeedSheetOpen) {
        PlaybackSpeedAndPodcastSheet(
            feed = feed,
            entry = entry,
            playerState = playerState,
            onAction = onAction,
        )
    }

    val drawerTab = playerState.activeDrawerTab
    if (drawerTab != null) {
        PlayerContentDrawerSheet(
            entry = entry,
            playerState = playerState,
            activeTab = drawerTab,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

@Composable
private fun FullscreenPlayerTopBar(
    entry: InboxEntry,
    playerState: PodcastPlayerState,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val starTint = if (entry.isFavorite) colors.accentEcru else colors.textSecondary

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderActionButton(
            label = "⌄",
            contentDescription = "Collapse player",
            onClick = { onAction(MainAction.CloseFullscreenPlayer) },
            modifier = Modifier.testTag("player_collapse_btn"),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RocheIconActionButton(
                contentDescription = if (entry.isFavorite) "Remove Star" else "Star Episode",
                onClick = { onAction(MainAction.ToggleStar(entry.id)) },
                modifier = Modifier.testTag("player_star_btn"),
            ) {
                StarIcon(tint = starTint, isStarred = entry.isFavorite, size = 16.dp)
            }

            PlayerSleepTimerMenuButton(
                activeRemaining = playerState.sleepTimerRemainingMinutes,
                onSelectMinutes = { mins -> onAction(MainAction.SetSleepTimerMinutes(mins)) },
            )

            HeaderActionButton(
                label = "Cast",
                onClick = { onAction(MainAction.RequestAudioOutputCast) },
                modifier = Modifier.testTag("player_cast_btn"),
            )

            HeaderActionButton(
                label = "Share",
                onClick = { onShareEntry(entry) },
                modifier = Modifier.testTag("player_share_btn"),
            )

            PlayerOverflowMenuButton(
                entry = entry,
                playerState = playerState,
                onAction = onAction,
                onShareEntry = onShareEntry,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }
}

@Composable
private fun PlayerSleepTimerMenuButton(
    activeRemaining: Int?,
    onSelectMinutes: (Int?) -> Unit,
) {
    val colors = LocalRocheColors.current
    var showSleepMenu by rememberSaveable { mutableStateOf(false) }

    Box {
        HeaderActionButton(
            label = activeRemaining?.let { "Zz ${it}m" } ?: "Zz",
            contentDescription =
                activeRemaining?.let { "Sleep timer, $it minutes remaining" } ?: "Sleep timer",
            onClick = { showSleepMenu = true },
            modifier = Modifier.testTag("player_sleep_btn"),
        )
        DropdownMenu(
            expanded = showSleepMenu,
            onDismissRequest = { showSleepMenu = false },
        ) {
            listOf(
                    null to "Sleep timer off",
                    15 to "15 minutes",
                    30 to "30 minutes",
                    45 to "45 minutes",
                    60 to "60 minutes",
                )
                .forEach { (mins, label) ->
                    val isSelected = mins == activeRemaining
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                color =
                                    if (isSelected) {
                                        colors.accentEcru
                                    } else {
                                        colors.textPrimary
                                    },
                                fontWeight =
                                    if (isSelected) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                            )
                        },
                        onClick = {
                            showSleepMenu = false
                            onSelectMinutes(mins)
                        },
                    )
                }
        }
    }
}

@Composable
private fun PlayerOverflowMenuButton(
    entry: InboxEntry,
    playerState: PodcastPlayerState,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    var showOverflowMenu by rememberSaveable { mutableStateOf(false) }

    Box {
        HeaderActionButton(
            label = "•••",
            contentDescription = "More player options",
            onClick = { showOverflowMenu = true },
            modifier = Modifier.testTag("player_overflow_btn"),
        )
        DropdownMenu(
            expanded = showOverflowMenu,
            onDismissRequest = { showOverflowMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Open Full Show Notes in Reader") },
                onClick = {
                    showOverflowMenu = false
                    onAction(MainAction.OpenReader(entry.id, forceArticleView = true))
                },
                modifier = Modifier.testTag("player_open_reader_btn"),
            )
            if (entry.feedId != null) {
                DropdownMenuItem(
                    text = { Text("Open Podcast (${entry.feedTitle})") },
                    onClick = {
                        showOverflowMenu = false
                        onAction(MainAction.CloseFullscreenPlayer)
                        onAction(MainAction.FilterByFeed(entry.feedId))
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Finish & Rank Episode") },
                onClick = {
                    showOverflowMenu = false
                    onAction(MainAction.CloseFullscreenPlayer)
                    onAction(
                        MainAction.MarkFeedEntryFinished(
                            entryId = entry.id,
                            rankImmediately = true,
                        )
                    )
                },
            )
            DropdownMenuItem(
                text = { Text("Visit Episode Website ↗") },
                onClick = {
                    showOverflowMenu = false
                    onOpenExternalUrl(entry.url)
                },
            )
            DropdownMenuItem(
                text = { Text("Show Transcript") },
                onClick = {
                    showOverflowMenu = false
                    onAction(MainAction.SelectPlayerDrawerTab(PlayerDrawerTab.TRANSCRIPT))
                },
            )
            DropdownMenuItem(
                text = { Text("Share at ${playerState.elapsedFormatted}…") },
                onClick = {
                    showOverflowMenu = false
                    onShareEntry(
                        entry.copy(title = "${entry.title} (at ${playerState.elapsedFormatted})")
                    )
                },
            )
            if (entry.isArchived && entry.isCachedOffline) {
                DropdownMenuItem(
                    text = { Text("Delete Download", color = colors.accentIndianRed) },
                    onClick = {
                        showOverflowMenu = false
                        onAction(MainAction.ToggleOfflineCache(entry.id))
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text =
                            if (entry.isArchived) {
                                "Unarchive Episode"
                            } else {
                                "Archive & Delete Download"
                            },
                        color =
                            if (entry.isArchived) {
                                colors.textPrimary
                            } else {
                                colors.accentIndianRed
                            },
                    )
                },
                onClick = {
                    showOverflowMenu = false
                    if (!entry.isArchived) {
                        onAction(MainAction.CloseFullscreenPlayer)
                    }
                    onAction(MainAction.ToggleArchive(entry))
                },
            )
        }
    }
}

@Composable
private fun FullscreenPlayerArtworkAndMeta(
    entry: InboxEntry,
    playerState: PodcastPlayerState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val bitmap =
        rememberEntryThumbnailBitmap(
            primaryKey = entry.thumbnailMediaId,
            fallbackKey = entry.effectiveThumbnailUrl ?: entry.faviconUrl,
        )
    val artShape = RoundedCornerShape(18.dp)
    val currentChapter = playerState.currentChapter

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier =
                Modifier.size(264.dp)
                    .clip(artShape)
                    .background(Color(0xFF1A242D))
                    .border(1.dp, colors.border, artShape)
                    .clickable { onAction(MainAction.TogglePlayPause) }
                    .semantics {
                        role = Role.Button
                        contentDescription =
                            if (playerState.isPlaying) {
                                "Album artwork (Tap to pause)"
                            } else {
                                "Album artwork (Tap to play)"
                            }
                    }
                    .testTag("player_album_artwork")
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
                    drawPodcastArtworkCanvas(colors)
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xB8101418))
                        .border(1.dp, colors.border, CircleShape),
            ) {
                PlayPauseIcon(
                    isPlaying = playerState.isPlaying,
                    tint = Color.White,
                    size = 14.dp,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = entry.feedId != null) {
                        entry.feedId?.let { fid ->
                            onAction(MainAction.CloseFullscreenPlayer)
                            onAction(MainAction.FilterByFeed(fid))
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = entry.feedTitle,
                color = colors.accentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = entry.title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

        if (currentChapter != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(99.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.controlOutline, RoundedCornerShape(99.dp))
                        .clickable {
                            onAction(MainAction.SelectPlayerDrawerTab(PlayerDrawerTab.CHAPTERS))
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("player_chapter_pill"),
            ) {
                Text(
                    text = "Ch. ${currentChapter.index} · ${currentChapter.title} ›",
                    color = colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlayerScrubberSection(
    playerState: PodcastPlayerState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    var previewFraction by remember { mutableStateOf<Float?>(null) }
    val previewState =
        previewFraction?.let { frac ->
            val previewPosMs =
                (playerState.durationMs * frac.coerceIn(0f, 1f))
                    .roundToLong()
                    .coerceIn(0L, playerState.durationMs.coerceAtLeast(0L))
            playerState.copy(positionMs = previewPosMs)
        } ?: playerState
    val speedLabel = formatPlaybackSpeedLabel(previewState.playbackSpeed)

    Column(modifier = Modifier.fillMaxWidth()) {
        RocheInteractiveSlider(
            fraction = playerState.progressFraction,
            onFractionChangeFinished = { fraction ->
                onAction(MainAction.SeekPlayerToFraction(fraction))
            },
            onDragPreviewChange = { previewFraction = it },
            contentDescription = "Playback position",
            stateDescription =
                "${previewState.elapsedFormatted} elapsed, ${previewState.remainingFormatted} remaining",
            modifier = Modifier.fillMaxWidth().testTag("player_scrubber_slider"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = previewState.elapsedFormatted,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
            )
            Text(
                text = "${previewState.speedAdjustedRemainingLabel} (at $speedLabel)",
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = previewState.remainingFormatted,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun FullscreenPlayerTransportRow(
    playerState: PodcastPlayerState,
    hasNextQueuedAudio: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val speedLabel = formatPlaybackSpeedLabel(playerState.playbackSpeed)
    val speedPillShape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.minimumInteractiveComponentSize()
                    .clip(speedPillShape)
                    .clickable { onAction(MainAction.SetPlayerSpeedSheetOpen(true)) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Playback speed $speedLabel"
                    }
                    .testTag("player_speed_btn"),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.heightIn(min = 42.dp)
                        .widthIn(min = 62.dp)
                        .clip(speedPillShape)
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.accentBlue, speedPillShape)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = speedLabel,
                    color = colors.accentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.border, CircleShape)
                    .clickable { onAction(MainAction.SkipPlayerBySeconds(-15)) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Rewind 15 seconds"
                    }
                    .testTag("player_rewind_btn"),
        ) {
            RewindIcon(tint = colors.textPrimary, size = 20.dp)
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(68.dp)
                    .clip(CircleShape)
                    .background(colors.accentBlue)
                    .clickable { onAction(MainAction.TogglePlayPause) }
                    .semantics {
                        role = Role.Button
                        contentDescription =
                            when {
                                playerState.isBuffering -> "Buffering"
                                playerState.isPlaying -> "Pause"
                                else -> "Play"
                            }
                    }
                    .testTag("player_play_pause_btn"),
        ) {
            if (playerState.isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(26.dp),
                )
            } else {
                PlayPauseIcon(
                    isPlaying = playerState.isPlaying,
                    tint = Color.White,
                    size = 28.dp,
                )
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.border, CircleShape)
                    .clickable { onAction(MainAction.SkipPlayerBySeconds(30)) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Fast-forward 30 seconds"
                    }
                    .testTag("player_ff_btn"),
        ) {
            FastForwardIcon(tint = colors.textPrimary, size = 20.dp)
        }

        val skipTint = if (hasNextQueuedAudio) colors.textPrimary else colors.textMuted
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable { onAction(MainAction.SkipToNextQueuedAudio) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Skip to next episode in Queue"
                    }
                    .testTag("player_skip_next_btn"),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.size(42.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.border, CircleShape),
            ) {
                SkipNextIcon(tint = skipTint, size = 18.dp)
            }
        }
    }
}

@Composable
internal fun FullscreenPlayerDrawerBar(
    playerState: PodcastPlayerState,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChipPill(
            label = "Show Notes",
            selected = playerState.activeDrawerTab == PlayerDrawerTab.SHOW_NOTES,
            onClick = { onAction(MainAction.SelectPlayerDrawerTab(PlayerDrawerTab.SHOW_NOTES)) },
            modifier = Modifier.weight(1f).testTag("player_drawer_shownotes"),
        )
        FilterChipPill(
            label = "Chapters (${playerState.chapters.size})",
            selected = playerState.activeDrawerTab == PlayerDrawerTab.CHAPTERS,
            onClick = { onAction(MainAction.SelectPlayerDrawerTab(PlayerDrawerTab.CHAPTERS)) },
            modifier = Modifier.weight(1f).testTag("player_drawer_chapters"),
        )
        FilterChipPill(
            label = "Transcript",
            selected = playerState.activeDrawerTab == PlayerDrawerTab.TRANSCRIPT,
            onClick = { onAction(MainAction.SelectPlayerDrawerTab(PlayerDrawerTab.TRANSCRIPT)) },
            modifier = Modifier.weight(1f).testTag("player_drawer_transcript"),
        )
    }
}
