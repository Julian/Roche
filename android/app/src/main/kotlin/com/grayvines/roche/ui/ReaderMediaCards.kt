@file:Suppress("MatchingDeclarationName", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.core.formatAudioDurationEstimate
import com.grayvines.roche.core.isArxivPaperUrl
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.playback.PodcastPlayerState
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.PlayPauseIcon
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.rememberEntryThumbnailBitmap
import com.grayvines.roche.ui.components.rememberHighResImageBitmap
import com.grayvines.roche.ui.theme.LocalRocheColors
import java.net.URI
import java.util.Locale

internal fun isPdfLikeEntry(entry: InboxEntry): Boolean {
    if (entry.contentType == ContentType.PDF) return true
    val cleanUrl = entry.url.trim()
    if (cleanUrl.isEmpty()) return false
    if (isLocalPdfUrl(cleanUrl) || isArxivPaperUrl(cleanUrl)) return true
    val pathWithoutQuery = cleanUrl.substringBefore('?').substringBefore('#')
    return pathWithoutQuery.endsWith(".pdf", ignoreCase = true)
}

internal fun resolveVideoHeroThumbnailUrl(entry: InboxEntry): String? =
    entry.effectiveThumbnailUrl ?: youtubeFallbackThumbnailUrl(entry.url)

internal fun youtubeFallbackThumbnailUrl(url: String): String? =
    extractYoutubeVideoIdFromUrl(url)?.let { videoId ->
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

internal fun extractYoutubeVideoIdFromUrl(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.") ?: return null
    val rawCandidate =
        when (host) {
            "youtu.be" -> uri.path?.trim('/')?.substringBefore('/')
            "youtube.com",
            "m.youtube.com",
            "music.youtube.com" -> extractYoutubeUriVideoId(uri)
            else -> null
        }
    return rawCandidate?.takeIf { YOUTUBE_VIDEO_ID_REGEX.matches(it) }
}

private fun extractYoutubeUriVideoId(uri: URI): String? {
    val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
    val firstSegment = pathSegments.firstOrNull()?.lowercase(Locale.US)
    if (firstSegment in YOUTUBE_EMBED_PATH_PREFIXES && pathSegments.size >= 2) {
        return pathSegments[1]
    }
    return uri.rawQuery
        .orEmpty()
        .split('&')
        .firstOrNull { it.startsWith("v=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.substringBefore('#')
}

@Composable
@Suppress("LongParameterList")
internal fun ReaderVideoHeroCard(
    entry: InboxEntry,
    onWatchExternal: (String) -> Unit,
    onMarkFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onUnfinish: () -> Unit = {},
    onToggleDismiss: () -> Unit = {},
) {
    val externalUrl = entry.url.takeIf { it.isNotBlank() }
    Column(
        modifier = modifier.fillMaxWidth().testTag("reader_video_hero_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VideoHeroThumbnailSurface(
            entry = entry,
            externalUrl = externalUrl,
            onWatchExternal = onWatchExternal,
        )
        VideoHeroActionRow(
            entry = entry,
            externalUrl = externalUrl,
            onWatchExternal = onWatchExternal,
            onMarkFinished = onMarkFinished,
            onUnfinish = onUnfinish,
            onToggleDismiss = onToggleDismiss,
        )
    }
}

@Composable
private fun VideoHeroThumbnailSurface(
    entry: InboxEntry,
    externalUrl: String?,
    onWatchExternal: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val highResUrl = resolveVideoHeroThumbnailUrl(entry)
    val heroBitmap =
        rememberHighResImageBitmap(primaryKey = highResUrl)
            ?: rememberEntryThumbnailBitmap(
                primaryKey = entry.thumbnailMediaId,
                fallbackKey = highResUrl,
            )
    val shape = RoundedCornerShape(10.dp)
    val clickModifier =
        if (externalUrl != null) {
            Modifier.clickable { onWatchExternal(externalUrl) }
                .semantics {
                    role = Role.Button
                    contentDescription = "Watch video: ${entry.title}"
                }
        } else {
            Modifier
        }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.fillMaxWidth()
                .aspectRatio(VIDEO_HERO_ASPECT_RATIO)
                .clip(shape)
                .background(VIDEO_HERO_BG)
                .border(1.dp, colors.border, shape)
                .then(clickModifier),
    ) {
        if (heroBitmap != null) {
            Image(
                bitmap = heroBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            VideoHeroPlaceholderBackdrop()
        }
        VideoHeroOverlayBadges(duration = entry.duration)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.VideoHeroOverlayBadges(duration: String?) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(56.dp)
                .clip(CircleShape)
                .background(VIDEO_PLAY_SCRIM)
                .border(1.5.dp, VIDEO_PLAY_RING, CircleShape),
    ) {
        PlayPauseIcon(isPlaying = false, tint = VIDEO_LIGHT_INK, size = 24.dp)
    }
    duration
        ?.takeIf { it.isNotBlank() }
        ?.let { cleanDuration ->
            Text(
                text = cleanDuration,
                color = VIDEO_LIGHT_INK,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(VIDEO_BADGE_SCRIM)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
}

@Composable
private fun VideoHeroPlaceholderBackdrop() {
    val colors = LocalRocheColors.current
    val wavePath = remember { Path() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        wavePath.rewind()
        wavePath.moveTo(0f, h * 0.72f)
        wavePath.lineTo(w * 0.22f, h * 0.56f)
        wavePath.lineTo(w * 0.42f, h * 0.66f)
        wavePath.lineTo(w * 0.64f, h * 0.36f)
        wavePath.lineTo(w * 0.84f, h * 0.48f)
        wavePath.lineTo(w, h * 0.28f)
        drawPath(
            path = wavePath,
            color = colors.accentVerdigris.copy(alpha = 0.42f),
            style = Stroke(width = 3.5f, cap = StrokeCap.Round),
        )
        drawCircle(
            color = colors.accentIndianRed.copy(alpha = 0.22f),
            radius = h * 0.28f,
            center = Offset(w * 0.5f, h * 0.5f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList")
@Composable
private fun VideoHeroActionRow(
    entry: InboxEntry,
    externalUrl: String?,
    onWatchExternal: (String) -> Unit,
    onMarkFinished: () -> Unit,
    onUnfinish: () -> Unit,
    onToggleDismiss: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (externalUrl != null) {
            HeaderActionButton(
                label = "Watch Video ↗",
                style = RocheButtonStyle.PRIMARY,
                onClick = { onWatchExternal(externalUrl) },
                modifier = Modifier.testTag("reader_watch_video_btn"),
            )
        }
        if (entry.isFinished) {
            HeaderActionButton(
                label = "Undo Finished",
                style = RocheButtonStyle.SECONDARY,
                onClick = onUnfinish,
                modifier = Modifier.testTag("reader_video_unfinish_btn"),
            )
        } else {
            HeaderActionButton(
                label = "✓ Finished",
                style =
                    if (externalUrl == null) {
                        RocheButtonStyle.PRIMARY
                    } else {
                        RocheButtonStyle.SECONDARY
                    },
                onClick = onMarkFinished,
                modifier = Modifier.testTag("reader_video_finished_btn"),
            )
        }
        HeaderActionButton(
            label = if (entry.isDismissed) "Mark New" else "Dismiss",
            style = RocheButtonStyle.SECONDARY,
            onClick = onToggleDismiss,
            modifier = Modifier.testTag("reader_video_dismiss_btn"),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderUncachedPdfCard(
    externalUrl: String?,
    isDownloading: Boolean,
    onDownloadPdf: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    RocheSurfaceCard(modifier = modifier.fillMaxWidth().testTag("reader_uncached_pdf_card")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Download the PDF to read and zoom pages in-app.",
                color = colors.textSecondary,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isDownloading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(colors.accentBlueFilled.copy(alpha = 0.22f))
                                .border(1.dp, colors.accentBlue, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("reader_downloading_pdf_indicator"),
                    ) {
                        CircularProgressIndicator(
                            color = colors.accentBlue,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Downloading PDF…",
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    HeaderActionButton(
                        label = "Download PDF to read in-app",
                        style = RocheButtonStyle.PRIMARY,
                        onClick = onDownloadPdf,
                        modifier = Modifier.testTag("reader_download_pdf_btn"),
                    )
                }
                if (!externalUrl.isNullOrBlank()) {
                    HeaderActionButton(
                        label = "Original ↗",
                        style = RocheButtonStyle.SECONDARY,
                        onClick = { onOpenExternalUrl(externalUrl) },
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun ReaderAudioPlayerCard(
    entry: InboxEntry,
    playerState: PodcastPlayerState?,
    isDownloadingOffline: Boolean,
    onTogglePlay: () -> Unit,
    onOpenPlayer: () -> Unit,
    onToggleOffline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val isCurrent = playerState != null && playerState.currentEntryId == entry.id
    val isPlaying = isCurrent && playerState.isPlaying
    val heading = if (entry.isPodcastEpisode) "Podcast episode" else "Audio version"
    val subtitle = resolveReaderAudioSubtitle(entry, playerState, isCurrent)
    RocheSurfaceCard(modifier = modifier.fillMaxWidth().testTag("reader_audio_card")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReaderAudioPlayButton(isPlaying = isPlaying, onClick = onTogglePlay)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = heading,
                    color = colors.textPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isDownloadingOffline) {
                Text(
                    text = "Saving…",
                    color = colors.textMuted,
                    fontSize = 11.5.sp,
                    modifier = Modifier.testTag("reader_audio_saving_indicator"),
                )
            } else if (!entry.isCachedOffline) {
                HeaderActionButton(
                    label = "Save",
                    style = RocheButtonStyle.SECONDARY,
                    onClick = onToggleOffline,
                    modifier = Modifier.testTag("reader_audio_offline_btn"),
                )
            }
            HeaderActionButton(
                label = "Player",
                style = RocheButtonStyle.SECONDARY,
                onClick = onOpenPlayer,
                modifier = Modifier.testTag("reader_open_player_btn"),
            )
        }
    }
}

private fun resolveReaderAudioSubtitle(
    entry: InboxEntry,
    playerState: PodcastPlayerState?,
    isCurrent: Boolean,
): String =
    if (isCurrent && playerState != null && playerState.durationMs > 0L) {
        "${playerState.formattedElapsed} · ${playerState.formattedMiniRemaining}"
    } else {
        formatAudioDurationEstimate(entry.duration)
            ?: if (entry.isCachedOffline) "Saved offline" else "Listen in player"
    }

@Composable
private fun ReaderAudioPlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(40.dp)
                .clip(CircleShape)
                .background(colors.accentBlueFilled)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = if (isPlaying) "Pause audio" else "Play audio"
                }
                .testTag("reader_audio_play_btn"),
    ) {
        PlayPauseIcon(isPlaying = isPlaying, tint = VIDEO_LIGHT_INK, size = 18.dp)
    }
}

private const val VIDEO_HERO_ASPECT_RATIO = 16f / 9f
private val VIDEO_HERO_BG = Color(0xFF1A2226)
private val VIDEO_PLAY_SCRIM = Color(0xCC141413)
private val VIDEO_PLAY_RING = Color(0xFFD6CEC6)
private val VIDEO_BADGE_SCRIM = Color(0xCC10100F)
private val VIDEO_LIGHT_INK = Color(0xFFE4DFDA)
private val YOUTUBE_EMBED_PATH_PREFIXES = setOf("shorts", "embed", "live")
private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
