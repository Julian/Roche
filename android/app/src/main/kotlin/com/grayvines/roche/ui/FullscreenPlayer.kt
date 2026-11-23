@file:Suppress("LongMethod", "TooManyFunctions", "CognitiveComplexMethod", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.playback.PlayerDrawerTab
import com.grayvines.roche.playback.PodcastPlayerState
import com.grayvines.roche.playback.formatPlaybackSpeedLabel
import com.grayvines.roche.ui.components.ChevronDownIcon
import com.grayvines.roche.ui.components.FastForwardIcon
import com.grayvines.roche.ui.components.MoonSleepIcon
import com.grayvines.roche.ui.components.MoreDotsIcon
import com.grayvines.roche.ui.components.PlayPauseIcon
import com.grayvines.roche.ui.components.PodcastArtworkBox
import com.grayvines.roche.ui.components.RewindIcon
import com.grayvines.roche.ui.components.RocheAlertDialog
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.SkipNextIcon
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val MINI_PLAYER_WIDE_BREAKPOINT = 450.dp
private val MINI_PLAYER_SWIPE_DISMISS_THRESHOLD = 96.dp

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
    val currentOnAction by rememberUpdatedState(onAction)
    val canDismiss = !playerState.isPlaying && !playerState.isBuffering
    val dismissThresholdPx =
        with(LocalDensity.current) { MINI_PLAYER_SWIPE_DISMISS_THRESHOLD.toPx() }
    var dragOffsetX by remember(entry.id, canDismiss) { mutableFloatStateOf(0f) }
    val swipeModifier =
        if (canDismiss) {
            Modifier.pointerInput(entry.id, dismissThresholdPx) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetX += dragAmount
                    },
                    onDragEnd = {
                        if (abs(dragOffsetX) >= dismissThresholdPx) {
                            currentOnAction(MainAction.DismissMiniPlayer)
                        }
                        dragOffsetX = 0f
                    },
                    onDragCancel = { dragOffsetX = 0f },
                )
            }
        } else {
            Modifier
        }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val showRewindControl = maxWidth >= MINI_PLAYER_WIDE_BREAKPOINT
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .offset { IntOffset(dragOffsetX.roundToInt(), 0) }
                    .graphicsLayer {
                        alpha =
                            if (canDismiss && dragOffsetX != 0f) {
                                (1f - abs(dragOffsetX) / (dismissThresholdPx * 1.5f)).coerceIn(
                                    0.35f,
                                    1f,
                                )
                            } else {
                                1f
                            }
                    }
                    .background(colors.surfaceElevated)
                    .then(swipeModifier)
                    .clickable { currentOnAction(MainAction.OpenFullscreenPlayer) }
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
                PodcastArtworkBox(entry = entry, size = 38.dp, cornerRadius = 7.dp)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        color = colors.textPrimary,
                        fontFamily = currentHeadlineFontFamily(),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            if (playerState.isBuffering) {
                                "${entry.feedTitle} · Buffering…"
                            } else {
                                "${entry.feedTitle} · ${playerState.formattedMiniRemaining}"
                            },
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (showRewindControl) {
                        RocheIconActionButton(
                            contentDescription = "Rewind 15 seconds",
                            onClick = { onAction(MainAction.SkipPlayerBySeconds(-15)) },
                            outlined = false,
                            modifier = Modifier.testTag("mini_player_rewind_btn"),
                        ) {
                            RewindIcon(tint = colors.textSecondary, size = 16.dp)
                        }
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
                                Modifier.size(34.dp)
                                    .clip(CircleShape)
                                    .background(colors.accentBlueFilled),
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
                    if (canDismiss) {
                        RocheIconActionButton(
                            contentDescription = "Dismiss player",
                            onClick = { onAction(MainAction.DismissMiniPlayer) },
                            outlined = false,
                            modifier = Modifier.testTag("mini_player_dismiss_btn"),
                        ) {
                            Text(
                                text = "✕",
                                color = colors.textMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    }
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
    upNextAudioEntries: List<InboxEntry>,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    isEntryDownloading: Boolean = false,
    isSpeedSheetOpen: Boolean = false,
    activeDrawerTab: PlayerDrawerTab? = null,
) {
    val colors = LocalRocheColors.current
    var pendingDownloadConfirm by rememberSaveable(entry.id) { mutableStateOf<String?>(null) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("fullscreen_podcast_player"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FullscreenPlayerTopBar(
            entry = entry,
            playerState = playerState,
            upNextCount = upNextAudioEntries.size,
            isEntryDownloading = isEntryDownloading,
            onAction = onAction,
            onRequestDeleteDownload = { pendingDownloadConfirm = CONFIRM_DELETE_DOWNLOAD },
            onRequestArchiveDeleteDownload = { pendingDownloadConfirm = CONFIRM_ARCHIVE_DOWNLOAD },
            onShareEntry = onShareEntry,
            onOpenExternalUrl = onOpenExternalUrl,
        )

        Column(
            modifier =
                Modifier.weight(1f)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            FullscreenPlayerArtworkAndMeta(
                entry = entry,
                playerState = playerState,
                onAction = onAction,
            )
            PlayerScrubberSection(
                playerState = playerState,
                onAction = onAction,
            )
            FullscreenPlayerTransportRow(
                playerState = playerState,
                hasNextQueuedAudio = upNextAudioEntries.isNotEmpty(),
                onAction = onAction,
            )
        }

        Box(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 6.dp)) {
            FullscreenPlayerDrawerBar(
                playerState = playerState,
                upNextCount = upNextAudioEntries.size,
                activeDrawerTab = activeDrawerTab,
                onSelectTab = { tab -> onAction(MainAction.SelectPlayerDrawerTab(tab)) },
            )
        }
    }

    if (isSpeedSheetOpen) {
        PlaybackSpeedAndPodcastSheet(
            feed = feed,
            playerState = playerState,
            onAction = onAction,
        )
    }

    if (activeDrawerTab != null) {
        PlayerContentDrawerSheet(
            entry = entry,
            playerState = playerState,
            upNextAudioEntries = upNextAudioEntries,
            activeTab = activeDrawerTab,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }

    if (pendingDownloadConfirm == CONFIRM_DELETE_DOWNLOAD) {
        PlayerConfirmDownloadDialog(
            title = "Delete local download?",
            body = "Remove the downloaded audio file for \"${entry.title}\" from this device?",
            confirmLabel = "Delete",
            confirmTestTag = "player_confirm_delete_download_btn",
            onDismiss = { pendingDownloadConfirm = null },
            onConfirm = {
                pendingDownloadConfirm = null
                onAction(MainAction.ToggleOfflineCache(entry.id))
            },
        )
    } else if (pendingDownloadConfirm == CONFIRM_ARCHIVE_DOWNLOAD) {
        PlayerConfirmDownloadDialog(
            title = "Dismiss & delete download?",
            body =
                "Dismiss \"${entry.title}\" and delete its downloaded audio file from this device?",
            confirmLabel = "Dismiss & Delete",
            confirmTestTag = "player_confirm_archive_delete_btn",
            onDismiss = { pendingDownloadConfirm = null },
            onConfirm = {
                pendingDownloadConfirm = null
                onAction(MainAction.CloseFullscreenPlayer)
                onAction(MainAction.ToggleArchive(entry))
            },
        )
    }
}

private const val CONFIRM_DELETE_DOWNLOAD = "delete_download"
private const val CONFIRM_ARCHIVE_DOWNLOAD = "archive_download"

@Composable
private fun PlayerConfirmDownloadDialog(
    title: String,
    body: String,
    confirmLabel: String,
    confirmTestTag: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheAlertDialog(
        title = title,
        onDismissRequest = onDismiss,
        text = { Text(text = body, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(confirmTestTag)) {
                Text(confirmLabel, color = colors.accentIndianRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
        },
    )
}

@Suppress("LongParameterList")
@Composable
private fun FullscreenPlayerTopBar(
    entry: InboxEntry,
    playerState: PodcastPlayerState,
    upNextCount: Int,
    isEntryDownloading: Boolean,
    onAction: (MainAction) -> Unit,
    onRequestDeleteDownload: () -> Unit,
    onRequestArchiveDeleteDownload: () -> Unit,
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
        RocheIconActionButton(
            contentDescription = "Collapse player",
            outlined = false,
            onClick = { onAction(MainAction.CloseFullscreenPlayer) },
            modifier = Modifier.testTag("player_collapse_btn"),
        ) {
            ChevronDownIcon(tint = colors.textSecondary, size = 19.dp)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RocheIconActionButton(
                contentDescription = if (entry.isFavorite) "Remove Star" else "Star Episode",
                outlined = false,
                onClick = { onAction(MainAction.ToggleStar(entry.id)) },
                modifier = Modifier.testTag("player_star_btn"),
            ) {
                StarIcon(tint = starTint, isStarred = entry.isFavorite, size = 17.dp)
            }

            PlayerSleepTimerMenuButton(
                activeRemaining = playerState.sleepTimerRemainingMinutes,
                onSelectMinutes = { mins -> onAction(MainAction.SetSleepTimerMinutes(mins)) },
            )

            PlayerOverflowMenuButton(
                entry = entry,
                playerState = playerState,
                upNextCount = upNextCount,
                isEntryDownloading = isEntryDownloading,
                onAction = onAction,
                onRequestDeleteDownload = onRequestDeleteDownload,
                onRequestArchiveDeleteDownload = onRequestArchiveDeleteDownload,
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
        RocheIconActionButton(
            contentDescription =
                activeRemaining?.let { "Sleep timer, $it minutes remaining" } ?: "Sleep timer",
            outlined = false,
            onClick = { showSleepMenu = true },
            modifier = Modifier.testTag("player_sleep_btn"),
        ) {
            if (activeRemaining != null) {
                Text(
                    text = "${activeRemaining}m",
                    color = colors.accentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                MoonSleepIcon(tint = colors.textSecondary, size = 17.dp)
            }
        }
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
                                        colors.accentBlue
                                    } else {
                                        colors.textPrimary
                                    },
                                fontWeight =
                                    if (isSelected) {
                                        FontWeight.Medium
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

@Suppress("LongParameterList")
@Composable
private fun PlayerOverflowMenuButton(
    entry: InboxEntry,
    playerState: PodcastPlayerState,
    upNextCount: Int,
    isEntryDownloading: Boolean,
    onAction: (MainAction) -> Unit,
    onRequestDeleteDownload: () -> Unit,
    onRequestArchiveDeleteDownload: () -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    var showOverflowMenu by rememberSaveable { mutableStateOf(false) }

    Box {
        RocheIconActionButton(
            contentDescription = "More player options",
            outlined = false,
            onClick = { showOverflowMenu = true },
            modifier = Modifier.testTag("player_overflow_btn"),
        ) {
            MoreDotsIcon(tint = colors.textSecondary, size = 17.dp)
        }
        DropdownMenu(
            expanded = showOverflowMenu,
            onDismissRequest = { showOverflowMenu = false },
        ) {
            if (!entry.isCachedOffline) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isEntryDownloading) "Downloading…" else "Download Episode",
                            color =
                                if (isEntryDownloading) colors.textMuted else colors.textPrimary,
                        )
                    },
                    enabled = !isEntryDownloading,
                    onClick = {
                        showOverflowMenu = false
                        onAction(MainAction.ToggleOfflineCache(entry.id))
                    },
                    modifier = Modifier.testTag("player_download_btn"),
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Delete Download", color = colors.accentIndianRed) },
                    onClick = {
                        showOverflowMenu = false
                        onRequestDeleteDownload()
                    },
                    modifier = Modifier.testTag("player_delete_download_btn"),
                )
            }
            DropdownMenuItem(
                text = { Text("Share Episode") },
                onClick = {
                    showOverflowMenu = false
                    onShareEntry(entry)
                },
                modifier = Modifier.testTag("player_share_btn"),
            )
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
            if (entry.isFinished) {
                DropdownMenuItem(
                    text = { Text("Undo Finished") },
                    onClick = {
                        showOverflowMenu = false
                        onAction(MainAction.UnfinishFeedEntry(entry.id))
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("✓ Mark Finished", color = colors.accentVerdigris) },
                    onClick = {
                        showOverflowMenu = false
                        onAction(MainAction.CloseFullscreenPlayer)
                        onAction(
                            MainAction.MarkFeedEntryFinished(
                                entryId = entry.id,
                                rankImmediately = false,
                            )
                        )
                    },
                )
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
            }
            DropdownMenuItem(
                text = { Text("Visit Episode Website ↗") },
                onClick = {
                    showOverflowMenu = false
                    onOpenExternalUrl(entry.url)
                },
            )
            DropdownMenuItem(
                text = {
                    Text(if (upNextCount > 0) "Up Next Queue ($upNextCount)" else "Up Next Queue")
                },
                onClick = {
                    showOverflowMenu = false
                    onAction(MainAction.SelectPlayerDrawerTab(PlayerDrawerTab.UP_NEXT))
                },
                modifier = Modifier.testTag("player_overflow_up_next_btn"),
            )
            DropdownMenuItem(
                text = { Text("Share at ${playerState.formattedElapsed}…") },
                onClick = {
                    showOverflowMenu = false
                    onShareEntry(
                        entry.copy(title = "${entry.title} (at ${playerState.formattedElapsed})")
                    )
                },
            )
            DropdownMenuItem(
                text = { Text("Stop & Dismiss Player") },
                onClick = {
                    showOverflowMenu = false
                    onAction(MainAction.DismissMiniPlayer)
                },
                modifier = Modifier.testTag("player_dismiss_btn"),
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text =
                            when {
                                entry.isDismissed -> "Mark New"
                                entry.isCachedOffline -> "Dismiss & Delete Download"
                                else -> "Dismiss Episode"
                            },
                        color =
                            if (entry.isDismissed) {
                                colors.textPrimary
                            } else {
                                colors.accentIndianRed
                            },
                    )
                },
                onClick = {
                    showOverflowMenu = false
                    if (!entry.isDismissed && entry.isCachedOffline) {
                        onRequestArchiveDeleteDownload()
                    } else {
                        if (!entry.isDismissed) {
                            onAction(MainAction.CloseFullscreenPlayer)
                        }
                        onAction(MainAction.ToggleArchive(entry))
                    }
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
    val currentChapter = playerState.currentChapter

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        PodcastArtworkBox(
            entry = entry,
            size = 240.dp,
            cornerRadius = 16.dp,
            modifier =
                Modifier.clickable { onAction(MainAction.TogglePlayPause) }
                    .semantics {
                        role = Role.Button
                        contentDescription =
                            if (playerState.isPlaying) {
                                "Album artwork (Tap to pause)"
                            } else {
                                "Album artwork (Tap to play)"
                            }
                    }
                    .testTag("player_album_artwork"),
        ) {
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

        Spacer(modifier = Modifier.height(14.dp))

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
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = entry.feedTitle,
                color = colors.accentBlue,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

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
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(99.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(99.dp))
                        .clickable {
                            onAction(MainAction.SelectPlayerDrawerTab(PlayerDrawerTab.CHAPTERS))
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                        .testTag("player_chapter_pill"),
            ) {
                Text(
                    text = "Ch. ${currentChapter.index} · ${currentChapter.title} ›",
                    color = colors.textSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Normal,
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

    Column(modifier = Modifier.fillMaxWidth()) {
        RocheInteractiveSlider(
            fraction = playerState.progressFraction,
            onFractionChangeFinished = { fraction ->
                onAction(MainAction.SeekPlayerToFraction(fraction))
            },
            onDragPreviewChange = { previewFraction = it },
            contentDescription = "Playback position",
            stateDescription =
                "${previewState.formattedElapsed} elapsed, ${previewState.formattedRemaining} remaining",
            modifier = Modifier.fillMaxWidth().testTag("player_scrubber_slider"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = previewState.formattedElapsed,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
            )
            Text(
                text = previewState.formattedSpeedAdjustedRemaining,
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = previewState.formattedRemaining,
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
    val speedPillShape = RoundedCornerShape(10.dp)

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
                    Modifier.heightIn(min = 36.dp)
                        .widthIn(min = 52.dp)
                        .clip(speedPillShape)
                        .background(colors.surface)
                        .border(1.dp, colors.border, speedPillShape)
                        .padding(horizontal = 9.dp, vertical = 6.dp),
            ) {
                Text(
                    text = speedLabel,
                    color = colors.accentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .clickable { onAction(MainAction.SkipPlayerBySeconds(-15)) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Rewind 15 seconds"
                    }
                    .testTag("player_rewind_btn"),
        ) {
            RewindIcon(tint = colors.textPrimary, size = 20.dp)
            Text(
                text = "15",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(64.dp)
                    .clip(CircleShape)
                    .background(colors.accentBlueFilled)
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
                    size = 26.dp,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .clickable { onAction(MainAction.SkipPlayerBySeconds(30)) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Fast-forward 30 seconds"
                    }
                    .testTag("player_ff_btn"),
        ) {
            FastForwardIcon(tint = colors.textPrimary, size = 20.dp)
            Text(
                text = "30",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }

        val skipTint = if (hasNextQueuedAudio) colors.textPrimary else colors.textMuted
        RocheIconActionButton(
            contentDescription = "Skip to next episode in Queue",
            outlined = false,
            onClick = { onAction(MainAction.SkipToNextQueuedAudio) },
            modifier = Modifier.testTag("player_skip_next_btn"),
        ) {
            SkipNextIcon(tint = skipTint, size = 18.dp)
        }
    }
}

@Composable
internal fun FullscreenPlayerDrawerBar(
    playerState: PodcastPlayerState,
    upNextCount: Int,
    activeDrawerTab: PlayerDrawerTab?,
    onSelectTab: (PlayerDrawerTab) -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(10.dp)
    val upNextLabel = if (upNextCount > 0) "Up Next ($upNextCount)" else "Up Next"
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.border, shape)
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlayerDrawerSegmentTab(
            label = "Show Notes",
            selected = activeDrawerTab == PlayerDrawerTab.SHOW_NOTES,
            onClick = { onSelectTab(PlayerDrawerTab.SHOW_NOTES) },
            modifier = Modifier.weight(1f).testTag("player_drawer_shownotes"),
        )
        PlayerDrawerSegmentTab(
            label = "Chapters (${playerState.chapters.size})",
            selected = activeDrawerTab == PlayerDrawerTab.CHAPTERS,
            onClick = { onSelectTab(PlayerDrawerTab.CHAPTERS) },
            modifier = Modifier.weight(1f).testTag("player_drawer_chapters"),
        )
        PlayerDrawerSegmentTab(
            label = upNextLabel,
            selected = activeDrawerTab == PlayerDrawerTab.UP_NEXT,
            onClick = { onSelectTab(PlayerDrawerTab.UP_NEXT) },
            modifier = Modifier.weight(1f).testTag("player_drawer_up_next"),
        )
    }
}

@Composable
private fun PlayerDrawerSegmentTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(7.dp)
    val bg = if (selected) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent
    val fg = if (selected) colors.accentBlue else colors.textSecondary
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    this.selected = selected
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 34.dp)
                    .clip(shape)
                    .background(bg)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Text(
                text = label,
                color = fg,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
