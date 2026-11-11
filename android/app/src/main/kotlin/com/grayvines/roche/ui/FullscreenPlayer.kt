@file:Suppress("LongMethod", "TooManyFunctions", "CognitiveComplexMethod", "MagicNumber")

package com.grayvines.roche.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.settings.PLAYBACK_SPEED_PRESETS
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.FastForwardIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.PlayPauseIcon
import com.grayvines.roche.ui.components.RewindIcon
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.SettingToggleRow
import com.grayvines.roche.ui.components.SkipNextIcon
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.components.drawPodcastArtworkCanvas
import com.grayvines.roche.ui.components.rememberEntryThumbnailBitmap
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlin.math.abs

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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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
                    text = "${entry.feedTitle} · ${playerState.speedAdjustedRemainingLabel}",
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                        Modifier.size(34.dp)
                            .clip(CircleShape)
                            .background(colors.accentBlue)
                            .clickable { onAction(MainAction.TogglePlayPause) }
                            .semantics {
                                role = Role.Button
                                contentDescription = if (playerState.isPlaying) "Pause" else "Play"
                            }
                            .testTag("mini_player_play_pause_btn"),
                ) {
                    PlayPauseIcon(
                        isPlaying = playerState.isPlaying,
                        tint = Color.White,
                        size = 15.dp,
                    )
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
    BackHandler { onAction(MainAction.CloseFullscreenPlayer) }
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

        Spacer(modifier = Modifier.height(16.dp))

        PlayerScrubberSection(
            playerState = playerState,
            onAction = onAction,
        )

        Spacer(modifier = Modifier.height(14.dp))

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
            onOpenExternalUrl = { onOpenExternalUrl(entry.url) },
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
    var showOverflowMenu by rememberSaveable { mutableStateOf(false) }
    var showSleepMenu by rememberSaveable { mutableStateOf(false) }
    val starTint = if (entry.isFavorite) colors.accentEcru else colors.textSecondary
    val sleepTint =
        if (playerState.sleepTimerRemainingMinutes != null) {
            colors.accentEcru
        } else {
            colors.textSecondary
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderActionButton(
            label = "⌄",
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

            Box {
                HeaderActionButton(
                    label = playerState.sleepTimerRemainingMinutes?.let { "Zz ${it}m" } ?: "Zz",
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
                            DropdownMenuItem(
                                text = { Text(label, color = sleepTint) },
                                onClick = {
                                    showSleepMenu = false
                                    onAction(MainAction.SetSleepTimerMinutes(mins))
                                },
                            )
                        }
                }
            }

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

            Box {
                HeaderActionButton(
                    label = "•••",
                    onClick = { showOverflowMenu = true },
                    modifier = Modifier.testTag("player_overflow_btn"),
                )
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false },
                ) {
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
                                entry.copy(
                                    title = "${entry.title} (at ${playerState.elapsedFormatted})"
                                )
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Archive & Delete Download",
                                color = colors.accentIndianRed,
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            onAction(MainAction.CloseFullscreenPlayer)
                            onAction(MainAction.ToggleArchive(entry))
                        },
                    )
                }
            }
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
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
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

        Text(
            text = entry.feedTitle,
            color = colors.accentBlue,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = entry.feedId != null) {
                        entry.feedId?.let { fid ->
                            onAction(MainAction.CloseFullscreenPlayer)
                            onAction(MainAction.FilterByFeed(fid))
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        )

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
                modifier =
                    Modifier.clip(RoundedCornerShape(99.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.border, RoundedCornerShape(99.dp))
                        .clickable {
                            onAction(MainAction.SelectPlayerDrawerTab(PlayerDrawerTab.CHAPTERS))
                        }
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                        .testTag("player_chapter_pill")
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
    val speedLabel = formatPlaybackSpeedLabel(playerState.playbackSpeed)

    Column(modifier = Modifier.fillMaxWidth()) {
        RocheInteractiveSlider(
            fraction = playerState.progressFraction,
            onFractionChange = { fraction -> onAction(MainAction.SeekPlayerToFraction(fraction)) },
            modifier = Modifier.fillMaxWidth().testTag("player_scrubber_slider"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = playerState.elapsedFormatted,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
            )
            Text(
                text = "${playerState.speedAdjustedRemainingLabel} (at $speedLabel)",
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = playerState.remainingFormatted,
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
        // 1. Playback Speed button on far left
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.height(42.dp)
                    .width(62.dp)
                    .clip(speedPillShape)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.accentBlue.copy(alpha = 0.7f), speedPillShape)
                    .clickable { onAction(MainAction.SetPlayerSpeedSheetOpen(true)) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Playback speed $speedLabel"
                    }
                    .testTag("player_speed_btn"),
        ) {
            Text(
                text = speedLabel,
                color = colors.accentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 2. Standard Rewind (◀◀) circle button
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

        // 3. Large center Play/Pause circle button
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(68.dp)
                    .clip(CircleShape)
                    .background(colors.accentBlue)
                    .clickable { onAction(MainAction.TogglePlayPause) }
                    .semantics {
                        role = Role.Button
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play"
                    }
                    .testTag("player_play_pause_btn"),
        ) {
            PlayPauseIcon(
                isPlaying = playerState.isPlaying,
                tint = Color.White,
                size = 28.dp,
            )
        }

        // 4. Standard Fast-Forward (▶▶) circle button
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

        // 5. Skip to Next Episode in Queue (⏭) circle button on far right
        val skipTint = if (hasNextQueuedAudio) colors.textPrimary else colors.textMuted
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(42.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.border, CircleShape)
                    .clickable { onAction(MainAction.SkipToNextQueuedAudio) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Skip to next episode in Queue"
                    }
                    .testTag("player_skip_next_btn"),
        ) {
            SkipNextIcon(tint = skipTint, size = 18.dp)
        }
    }
}

@Composable
private fun FullscreenPlayerDrawerBar(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedAndPodcastSheet(
    feed: FeedItem?,
    entry: InboxEntry,
    playerState: PodcastPlayerState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val currentSpeed = playerState.playbackSpeed
    val speedFormatted = formatPlaybackSpeedLabel(currentSpeed)
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

            val speedRange = Settings.MAX_PLAYBACK_SPEED - Settings.MIN_PLAYBACK_SPEED
            val speedFraction =
                ((currentSpeed - Settings.MIN_PLAYBACK_SPEED) / speedRange).coerceIn(0f, 1f)
            RocheInteractiveSlider(
                fraction = speedFraction,
                onFractionChange = { frac ->
                    val raw = Settings.MIN_PLAYBACK_SPEED + frac * speedRange
                    onAction(MainAction.SetPlaybackSpeed(clampPlaybackSpeed(raw)))
                },
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
                title = "Skip silence",
                subtitle = "Trim dead air without distorting voice pitch",
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
private fun PlayerContentDrawerSheet(
    entry: InboxEntry,
    playerState: PodcastPlayerState,
    activeTab: PlayerDrawerTab,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: () -> Unit,
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
                    playerState.chapters.forEach { chapter ->
                        val isCurrent = playerState.currentChapter?.index == chapter.index
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
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
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${chapter.index}. ${chapter.title}",
                                color = if (isCurrent) colors.accentBlue else colors.textPrimary,
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
                PlayerDrawerTab.TRANSCRIPT -> {
                    val transcriptText =
                        remember(entry.contentText, entry.summary) {
                            entry.contentText?.takeIf { it.isNotBlank() }
                                ?: entry.summary?.takeIf { it.isNotBlank() }
                                ?: "No episode transcript is embedded in this feed."
                        }
                    Text(
                        text = transcriptText,
                        color = colors.textPrimary,
                        fontFamily = FontFamily.Serif,
                        fontSize = 15.sp,
                        lineHeight = 23.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
internal fun RocheInteractiveSlider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val clamped = fraction.coerceIn(0f, 1f)
    Canvas(
        modifier =
            modifier
                .height(28.dp)
                .pointerInput(onFractionChange) {
                    detectTapGestures { offset ->
                        if (size.width > 0) {
                            onFractionChange((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
                .pointerInput(onFractionChange) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        if (size.width > 0) {
                            onFractionChange(
                                (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
    ) {
        val trackHeight = 4.dp.toPx()
        val thumbRadius = 7.dp.toPx()
        val centerY = size.height * 0.5f
        val activeWidth = (size.width * clamped).coerceIn(0f, size.width)
        val corner = CornerRadius(trackHeight * 0.5f, trackHeight * 0.5f)
        drawRoundRect(
            color = colors.border,
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
            center = Offset(activeWidth.coerceIn(thumbRadius, size.width - thumbRadius), centerY),
        )
    }
}
