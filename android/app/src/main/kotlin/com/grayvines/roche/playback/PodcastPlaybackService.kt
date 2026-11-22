package com.grayvines.roche.playback

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.graphics.scale
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * Application-level host contract implemented by `RocheApp` so `playback` never depends on
 * `MainActivity` or `RocheApp` directly.
 */
interface PodcastPlaybackHost {
    val allowedLocalAudioDirs: List<File>

    val playbackController: PodcastPlayerController

    fun createOpenPlayerPendingIntent(): PendingIntent
}

internal data class SessionMetadataKey(
    val entryId: String,
    val episodeTitle: String,
    val podcastTitle: String,
    val artworkUrl: String?,
    val durationMs: Long,
    val hasArtworkBytes: Boolean = false,
)

internal fun AudioPlaybackMetadata.toMetadataKey(
    hasArtworkBytes: Boolean = false
): SessionMetadataKey =
    SessionMetadataKey(
        entryId = entryId,
        episodeTitle = episodeTitle,
        podcastTitle = podcastTitle,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
        hasArtworkBytes = hasArtworkBytes,
    )

@OptIn(UnstableApi::class)
internal fun buildTransportButtons(hasNextQueuedAudio: Boolean): List<CommandButton> = buildList {
    add(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
            .setDisplayName("Rewind 15s")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .build()
    )
    add(
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName("Fast-forward 30s")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .build()
    )
    if (hasNextQueuedAudio) {
        add(
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setDisplayName("Next episode")
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .build()
        )
    }
}

internal fun buildMedia3Metadata(
    metadata: AudioPlaybackMetadata,
    artworkBytes: ByteArray?,
): MediaMetadata {
    val title = metadata.episodeTitle.ifBlank { "Podcast Episode" }
    val podcast = metadata.podcastTitle.ifBlank { "Roche Podcasts" }
    val builder =
        MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist(podcast)
            .setAlbumTitle(podcast)
            .setSubtitle(podcast)
            .setIsBrowsable(false)
            .setIsPlayable(true)
    // Intentionally omit setArtworkUri so Media3/SystemUI never performs unvalidated or
    // cleartext artwork fetches; artwork is delivered via artworkData after safe loading.
    if (artworkBytes != null && artworkBytes.isNotEmpty()) {
        builder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    }
    return builder.build()
}

internal fun compressArtworkBytes(bitmap: Bitmap): ByteArray {
    val maxDim = maxOf(bitmap.width, bitmap.height)
    val scaled =
        if (maxDim > MAX_NOTIFICATION_ARTWORK_PX && maxDim > 0) {
            val scale = MAX_NOTIFICATION_ARTWORK_PX.toFloat() / maxDim.toFloat()
            val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            bitmap.scale(targetW, targetH)
        } else {
            null
        }
    val candidate = scaled ?: bitmap
    val softwareCopy =
        if (candidate.config == Bitmap.Config.HARDWARE) {
            candidate.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            null
        }
    val targetBitmap = softwareCopy ?: candidate
    return try {
        ByteArrayOutputStream().use { out ->
            targetBitmap.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, out)
            out.toByteArray()
        }
    } finally {
        if (softwareCopy != null && softwareCopy !== candidate) {
            softwareCopy.recycle()
        }
        if (scaled != null && scaled !== bitmap) {
            scaled.recycle()
        }
    }
}

internal fun isAllowedServiceMediaUri(
    isTrustedCaller: Boolean,
    uriString: String?,
    allowedLocalDirs: List<File>,
): Boolean =
    isTrustedCaller &&
        !uriString.isNullOrBlank() &&
        isSafeAudioPlaybackSource(uriString, allowedLocalDirs)

internal fun resolveSafeServiceMediaItems(
    isTrustedCaller: Boolean,
    mediaItems: List<MediaItem>,
    allowedLocalDirs: List<File>,
): List<MediaItem> {
    if (!isTrustedCaller) return emptyList()
    return mediaItems.mapNotNull { item ->
        val requestedUri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
        if (
            requestedUri != null &&
                isAllowedServiceMediaUri(isTrustedCaller, requestedUri.toString(), allowedLocalDirs)
        ) {
            item.buildUpon().setUri(requestedUri).build()
        } else {
            null
        }
    }
}

@OptIn(UnstableApi::class)
@Suppress("TooManyFunctions")
internal class RocheSessionForwardingPlayer(
    player: Player,
    private val hasNextProvider: () -> Boolean,
    private val consumeSmartRewindTargetMs: () -> Long?,
    private val onSkipToNextRequested: () -> Unit,
    private val onStopRequested: () -> Unit,
) : ForwardingPlayer(player) {
    private val registeredListeners = CopyOnWriteArraySet<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        registeredListeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        registeredListeners.remove(listener)
        super.removeListener(listener)
    }

    internal fun notifyAvailableCommandsChanged() {
        val commands = availableCommands
        for (listener in registeredListeners) {
            listener.onAvailableCommandsChanged(commands)
        }
    }

    override fun getAvailableCommands(): Player.Commands {
        val builder =
            super.getAvailableCommands()
                .buildUpon()
                .add(COMMAND_PLAY_PAUSE)
                .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(COMMAND_SEEK_BACK)
                .add(COMMAND_SEEK_FORWARD)
                .add(COMMAND_SET_SPEED_AND_PITCH)
                .add(COMMAND_STOP)
                .remove(COMMAND_SEEK_TO_PREVIOUS)
                .remove(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (hasNextProvider()) {
            builder.add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            builder.remove(COMMAND_SEEK_TO_NEXT).remove(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        getAvailableCommands().contains(command)

    override fun play() {
        consumeSmartRewindTargetMs()?.let { targetMs -> super.seekTo(targetMs) }
        super.play()
    }

    override fun seekToPrevious() {
        super.seekBack()
    }

    override fun seekToPreviousMediaItem() {
        super.seekBack()
    }

    override fun seekToNext() {
        if (hasNextProvider()) {
            onSkipToNextRequested()
        }
    }

    override fun seekToNextMediaItem() {
        seekToNext()
    }

    override fun stop() {
        onStopRequested()
    }
}

internal val EXTERNAL_BLOCKED_PLAYER_CMDS: Set<Int> =
    setOf(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)

internal fun isPlayerCmdAllowedForCaller(isTrustedCaller: Boolean, command: Int): Boolean =
    isTrustedCaller || command !in EXTERNAL_BLOCKED_PLAYER_CMDS

@OptIn(UnstableApi::class)
internal fun resolveControllerPlayerCmds(isTrustedCaller: Boolean): Player.Commands =
    if (isTrustedCaller) {
        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
    } else {
        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .apply { EXTERNAL_BLOCKED_PLAYER_CMDS.forEach(::remove) }
            .build()
    }

class PodcastPlaybackService : MediaSessionService() {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var exoPlayer: ExoPlayer? = null
    private var forwardingPlayer: RocheSessionForwardingPlayer? = null
    private var mediaSession: MediaSession? = null
    private var hasNextQueuedAudio: Boolean = false

    override fun onCreate() {
        super.onCreate()
        ensureSessionCreated()
    }

    @OptIn(UnstableApi::class)
    private fun ensureSessionCreated(): MediaSession? {
        mainHandler.removeCallbacksAndMessages(null)
        return mediaSession ?: (application as? PodcastPlaybackHost)?.let(::createSessionForHost)
    }

    @OptIn(UnstableApi::class)
    private fun createSessionForHost(host: PodcastPlaybackHost): MediaSession {
        val player = buildExoPlayer()
        exoPlayer = player
        hasNextQueuedAudio = host.playbackController.state.value.hasNextQueuedAudio
        val forwarding =
            RocheSessionForwardingPlayer(
                player = player,
                hasNextProvider = { hasNextQueuedAudio },
                consumeSmartRewindTargetMs = {
                    host.playbackController.consumeSmartRewindTargetMs()
                },
                onSkipToNextRequested = { host.playbackController.requestSkipToNext() },
                onStopRequested = {
                    host.playbackController.flushCurrentProgressIfNeeded()
                    host.playbackController.stopAndClear()
                    scheduleStopAndRelease()
                },
            )
        forwardingPlayer = forwarding
        return MediaSession.Builder(this, forwarding)
            .setId("${SESSION_ID}_${sessionSequence.incrementAndGet()}")
            .setCallback(buildSessionCallback(host))
            .setSessionActivity(host.createOpenPlayerPendingIntent())
            .setMediaButtonPreferences(buildTransportButtons(hasNextQueuedAudio))
            .build()
            .also { mediaSession = it }
    }

    @OptIn(UnstableApi::class)
    private fun buildExoPlayer(): ExoPlayer {
        val audioDataSourceFactory = createAudioDataSourceFactory(context = this)
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(audioDataSourceFactory)
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_INCREMENT_MS)
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun buildSessionCallback(host: PodcastPlaybackHost): MediaSession.Callback =
        object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val isTrusted = controller.packageName == packageName
                val commandsBuilder =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                if (isTrusted) {
                    commandsBuilder
                        .add(COMMAND_SET_SKIP_SILENCE)
                        .add(COMMAND_SET_HAS_NEXT)
                        .add(COMMAND_STOP_AND_RELEASE)
                }
                return MediaSession.ConnectionResult.accept(
                    commandsBuilder.build(),
                    resolveControllerPlayerCmds(isTrusted),
                )
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> {
                val resolved =
                    resolveSafeServiceMediaItems(
                        isTrustedCaller = controller.packageName == packageName,
                        mediaItems = mediaItems,
                        allowedLocalDirs = host.allowedLocalAudioDirs,
                    )
                return Futures.immediateFuture(resolved.toMutableList())
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                if (controller.packageName != packageName) {
                    return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                    )
                }
                handleTrustedCustomCommand(session, customCommand.customAction, args)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

    @OptIn(UnstableApi::class)
    internal fun handleTrustedCustomCommand(
        session: MediaSession,
        action: String,
        args: Bundle,
    ) {
        when (action) {
            ACTION_SET_SKIP_SILENCE -> {
                exoPlayer?.skipSilenceEnabled = args.getBoolean(EXTRA_SKIP_SILENCE, false)
            }
            ACTION_SET_HAS_NEXT -> {
                val hasNext = args.getBoolean(EXTRA_HAS_NEXT, false)
                if (hasNextQueuedAudio != hasNext) {
                    hasNextQueuedAudio = hasNext
                    session.setMediaButtonPreferences(buildTransportButtons(hasNext))
                    forwardingPlayer?.notifyAvailableCommandsChanged()
                }
            }
            ACTION_STOP_AND_RELEASE -> {
                scheduleStopAndRelease()
            }
        }
    }

    private fun scheduleStopAndRelease() {
        val targetSession = mediaSession
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
        }
        mainHandler.post {
            if (mediaSession === targetSession) {
                stopAndReleaseService()
            }
        }
    }

    private fun releaseSessionAndPlayer() {
        mediaSession?.let { session ->
            if (isSessionAdded(session)) {
                removeSession(session)
            }
            session.release()
        }
        mediaSession = null
        forwardingPlayer = null
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            player.release()
        }
        exoPlayer = null
    }

    private fun stopAndReleaseService() {
        releaseSessionAndPlayer()
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        ensureSessionCreated()

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = exoPlayer
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopAndReleaseService()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        releaseSessionAndPlayer()
        super.onDestroy()
    }

    companion object {
        private const val SESSION_ID = "RochePodcastMedia3Session"
        private val sessionSequence = AtomicLong(0L)
        internal const val SEEK_BACK_INCREMENT_MS = 15_000L
        internal const val SEEK_FORWARD_INCREMENT_MS = 30_000L
        const val ACTION_OPEN_PLAYER = "com.grayvines.roche.action.OPEN_PLAYER"
        const val EXTRA_OPEN_FULLSCREEN_PLAYER = "extra_open_fullscreen_player"

        internal const val ACTION_SET_SKIP_SILENCE = "com.grayvines.roche.session.SET_SKIP_SILENCE"
        internal const val ACTION_SET_HAS_NEXT = "com.grayvines.roche.session.SET_HAS_NEXT"
        internal const val ACTION_STOP_AND_RELEASE = "com.grayvines.roche.session.STOP_AND_RELEASE"
        internal const val EXTRA_SKIP_SILENCE = "extra_skip_silence"
        internal const val EXTRA_HAS_NEXT = "extra_has_next"

        internal val COMMAND_SET_SKIP_SILENCE =
            SessionCommand(ACTION_SET_SKIP_SILENCE, Bundle.EMPTY)
        internal val COMMAND_SET_HAS_NEXT = SessionCommand(ACTION_SET_HAS_NEXT, Bundle.EMPTY)
        internal val COMMAND_STOP_AND_RELEASE =
            SessionCommand(ACTION_STOP_AND_RELEASE, Bundle.EMPTY)
    }
}

private const val MAX_NOTIFICATION_ARTWORK_PX = 512
private const val ARTWORK_JPEG_QUALITY = 85
