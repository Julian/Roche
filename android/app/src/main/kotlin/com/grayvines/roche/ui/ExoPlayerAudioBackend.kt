package com.grayvines.roche.ui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.grayvines.roche.data.remote.PublicUrlValidator
import java.io.File
import java.net.URI

internal fun isSafeAudioPlaybackSource(
    source: String,
    allowedLocalDirs: List<File>,
): Boolean {
    val trimmed = source.trim()
    return when {
        trimmed.isEmpty() -> false
        trimmed.startsWith(HTTPS_PREFIX, ignoreCase = true) ||
            trimmed.startsWith(HTTP_PREFIX, ignoreCase = true) -> isValidHttpPlaybackUri(trimmed)
        trimmed.startsWith(FILE_URI_PREFIX, ignoreCase = true) || trimmed.startsWith("/") ->
            isCanonicalFileAllowed(trimmed, allowedLocalDirs)
        else -> false
    }
}

private fun isValidHttpPlaybackUri(trimmed: String): Boolean = runCatching {
    val parsed = URI.create(trimmed.replace(" ", "%20"))
    val scheme = parsed.scheme?.lowercase()
    (scheme == "https" || scheme == "http") && !parsed.host.isNullOrBlank()
}
    .getOrDefault(false)

private fun isCanonicalFileAllowed(
    trimmed: String,
    allowedLocalDirs: List<File>,
): Boolean = runCatching {
    if (allowedLocalDirs.isEmpty()) return false
    val file =
        if (trimmed.startsWith(FILE_URI_PREFIX, ignoreCase = true)) {
            File(URI.create(trimmed.replace(" ", "%20")))
        } else {
            File(trimmed)
        }
    val canonicalTarget = file.canonicalPath
    allowedLocalDirs.any { allowedDir ->
        val prefix = allowedDir.canonicalPath.trimEnd(File.separatorChar) + File.separator
        canonicalTarget.startsWith(prefix)
    }
}
    .getOrDefault(false)

internal class NoOpAudioPlayerBackend : AudioPlayerBackend {
    private var positionMs: Long = 0L
    private var durationMs: Long = DEFAULT_FALLBACK_DURATION_MS
    private var playing: Boolean = false

    override fun loadAndPlay(
        source: String,
        initialPositionMs: Long,
        fallbackDurationMs: Long,
        speed: Float,
        skipSilenceEnabled: Boolean,
        playWhenReady: Boolean,
        onPrepared: (durationMs: Long) -> Unit,
        onCompletion: () -> Unit,
        onError: () -> Unit,
    ) {
        if (source.isBlank()) {
            playing = false
            onError()
            return
        }
        durationMs = fallbackDurationMs.takeIf { it > 0L } ?: DEFAULT_FALLBACK_DURATION_MS
        positionMs = initialPositionMs.coerceIn(0L, durationMs)
        playing = playWhenReady
        onPrepared(durationMs)
    }

    override fun play() {
        playing = true
    }

    override fun pause() {
        playing = false
    }

    override fun seekTo(positionMs: Long) {
        this.positionMs = positionMs.coerceIn(0L, durationMs)
    }

    override fun setSpeed(speed: Float) = Unit

    override fun setSkipSilence(enabled: Boolean) = Unit

    override fun currentPositionMs(): Long = positionMs

    override fun durationMs(): Long = durationMs

    override fun isPlaying(): Boolean = playing

    override fun release() {
        playing = false
    }
}

@Suppress("TooManyFunctions")
class ExoPlayerAudioBackend(
    context: Context,
    private val allowedLocalDirs: List<File> =
        listOf(File(context.applicationContext.filesDir, "offline_media"), context.cacheDir),
) : AudioPlayerBackend {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var exoPlayer: ExoPlayer? = null
    private var nextLoadId: Long = 0L
    private var activeLoadId: Long = 0L
    private var notifiedReadyLoadId: Long = -1L
    private var lastNotifiedExternalPlaying: Boolean? = null
    private var suppressExternalCallbacks: Boolean = false
    private var activeOnPrepared: ((Long) -> Unit)? = null
    private var activeOnCompletion: (() -> Unit)? = null
    private var activeOnError: (() -> Unit)? = null
    private var latestMetadata: MediaMetadata = MediaMetadata.EMPTY

    @Volatile private var cachedPositionMs: Long = 0L
    @Volatile private var cachedDurationMs: Long = DEFAULT_FALLBACK_DURATION_MS
    @Volatile private var cachedIsPlaying: Boolean = false
    @Volatile private var externalPlayStateListener: ((Boolean) -> Unit)? = null

    private val positionSyncRunnable =
        object : Runnable {
            override fun run() {
                val player = exoPlayer ?: return
                syncVolatileStateFromPlayer(player)
                if (player.isPlaying) {
                    mainHandler.postDelayed(this, POSITION_POLL_INTERVAL_MS)
                }
            }
        }

    private val playerListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val player = exoPlayer ?: return
                if (playbackState == Player.STATE_ENDED) {
                    handlePlayerEnded()
                    return
                }
                syncVolatileStateFromPlayer(player)
                if (playbackState == Player.STATE_READY) {
                    handlePlayerReady(player)
                }
                notifyExternalPlayState(player)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val player = exoPlayer ?: return
                syncVolatileStateFromPlayer(player)
                notifyExternalPlayState(player)
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                val player = exoPlayer ?: return
                syncVolatileStateFromPlayer(player)
                notifyExternalPlayState(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val player = exoPlayer ?: return
                syncVolatileStateFromPlayer(player)
                mainHandler.removeCallbacks(positionSyncRunnable)
                if (isPlaying) {
                    mainHandler.postDelayed(positionSyncRunnable, POSITION_POLL_INTERVAL_MS)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                cachedIsPlaying = false
                mainHandler.removeCallbacks(positionSyncRunnable)
                val callback = activeOnError
                clearActiveCallbacks()
                callback?.invoke()
            }
        }

    override fun bindExternalPlayStateListener(listener: ((Boolean) -> Unit)?) {
        externalPlayStateListener = listener
    }

    @OptIn(UnstableApi::class)
    internal fun getOrCreateExoPlayerOnMain(): ExoPlayer {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ExoPlayer must be initialized on the main thread"
        }
        exoPlayer?.let {
            return it
        }
        return ExoPlayer.Builder(appContext)
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
            .also { created ->
                created.addListener(playerListener)
                exoPlayer = created
            }
    }

    override fun loadAndPlay(
        source: String,
        initialPositionMs: Long,
        fallbackDurationMs: Long,
        speed: Float,
        skipSilenceEnabled: Boolean,
        playWhenReady: Boolean,
        onPrepared: (durationMs: Long) -> Unit,
        onCompletion: () -> Unit,
        onError: () -> Unit,
    ) {
        if (!isSafeAudioPlaybackSource(source, allowedLocalDirs)) {
            cachedIsPlaying = false
            onError()
            return
        }
        val safeFallback = fallbackDurationMs.takeIf { it > 0L } ?: DEFAULT_FALLBACK_DURATION_MS
        val clampedInitial = initialPositionMs.coerceIn(0L, safeFallback)
        cachedDurationMs = safeFallback
        cachedPositionMs = clampedInitial
        cachedIsPlaying = playWhenReady
        runOnPlayerThread {
            startMediaItemOnMain(
                source = source.trim(),
                initialPositionMs = clampedInitial,
                fallbackDurationMs = safeFallback,
                speed = speed,
                skipSilenceEnabled = skipSilenceEnabled,
                playWhenReady = playWhenReady,
                onPrepared = onPrepared,
                onCompletion = onCompletion,
                onError = onError,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun startMediaItemOnMain(
        source: String,
        initialPositionMs: Long,
        fallbackDurationMs: Long,
        speed: Float,
        skipSilenceEnabled: Boolean,
        playWhenReady: Boolean,
        onPrepared: (durationMs: Long) -> Unit,
        onCompletion: () -> Unit,
        onError: () -> Unit,
    ) {
        runCatching {
            val player = getOrCreateExoPlayerOnMain()
            activeLoadId = ++nextLoadId
            notifiedReadyLoadId = -1L
            lastNotifiedExternalPlaying = playWhenReady
            activeOnPrepared = onPrepared
            activeOnCompletion = onCompletion
            activeOnError = onError
            cachedDurationMs = fallbackDurationMs
            cachedPositionMs = initialPositionMs
            val uri = toPlaybackUri(source)
            val mediaItem = MediaItem.Builder().setUri(uri).setMediaMetadata(latestMetadata).build()
            withCallbacksSuppressed {
                applySkipSilenceOnPlayer(player, skipSilenceEnabled)
                player.playbackParameters = PlaybackParameters(speed.coerceIn(MIN_SPEED, MAX_SPEED))
                player.setMediaItem(mediaItem, initialPositionMs)
                player.playWhenReady = playWhenReady
                player.prepare()
            }
        }
            .onFailure {
                cachedIsPlaying = false
                clearActiveCallbacks()
                onError()
            }
    }

    internal fun handlePlayerReady(player: ExoPlayer) {
        if (notifiedReadyLoadId == activeLoadId || activeLoadId == 0L) return
        notifiedReadyLoadId = activeLoadId
        val resolvedDuration =
            player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: cachedDurationMs
        cachedDurationMs = resolvedDuration
        activeOnPrepared?.invoke(resolvedDuration)
    }

    internal fun handlePlayerEnded() {
        cachedIsPlaying = false
        mainHandler.removeCallbacks(positionSyncRunnable)
        val completion = activeOnCompletion
        clearActiveCallbacks()
        completion?.invoke()
    }

    override fun play() {
        cachedIsPlaying = true
        runOnPlayerThread {
            lastNotifiedExternalPlaying = true
            exoPlayer?.let { player ->
                withCallbacksSuppressed {
                    if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                        player.prepare()
                    }
                    player.play()
                }
                syncVolatileStateFromPlayer(player)
            }
        }
    }

    override fun pause() {
        cachedIsPlaying = false
        runOnPlayerThread {
            lastNotifiedExternalPlaying = false
            exoPlayer?.let { player ->
                withCallbacksSuppressed { player.pause() }
                syncVolatileStateFromPlayer(player)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, cachedDurationMs.coerceAtLeast(1L))
        cachedPositionMs = clamped
        runOnPlayerThread {
            exoPlayer?.let { player ->
                player.seekTo(clamped)
                syncVolatileStateFromPlayer(player)
            }
        }
    }

    override fun setSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        runOnPlayerThread {
            exoPlayer?.playbackParameters = PlaybackParameters(safeSpeed)
        }
    }

    override fun setSkipSilence(enabled: Boolean) {
        runOnPlayerThread {
            exoPlayer?.let { player -> applySkipSilenceOnPlayer(player, enabled) }
        }
    }

    override fun currentPositionMs(): Long {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            exoPlayer?.let(::syncVolatileStateFromPlayer)
        }
        return cachedPositionMs
    }

    override fun durationMs(): Long {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            exoPlayer?.let(::syncVolatileStateFromPlayer)
        }
        return cachedDurationMs
    }

    override fun isPlaying(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            exoPlayer?.let(::syncVolatileStateFromPlayer)
        }
        return cachedIsPlaying
    }

    override fun release() {
        cachedIsPlaying = false
        runOnPlayerThread {
            mainHandler.removeCallbacks(positionSyncRunnable)
            clearActiveCallbacks()
            activeLoadId = 0L
            notifiedReadyLoadId = -1L
            latestMetadata = MediaMetadata.EMPTY
            exoPlayer?.let { player ->
                withCallbacksSuppressed {
                    player.stop()
                    player.clearMediaItems()
                }
            }
        }
    }

    internal fun shutdownPlayer() {
        cachedIsPlaying = false
        runOnPlayerThread {
            mainHandler.removeCallbacks(positionSyncRunnable)
            clearActiveCallbacks()
            exoPlayer?.let { player ->
                player.removeListener(playerListener)
                player.release()
            }
            exoPlayer = null
        }
    }

    internal fun updateSessionMediaMetadata(metadata: MediaMetadata) {
        runOnPlayerThread {
            latestMetadata = metadata
            exoPlayer?.let { player ->
                if (player.mediaItemCount > 0) {
                    val current = player.getMediaItemAt(0)
                    if (current.mediaMetadata != metadata) {
                        player.replaceMediaItem(
                            0,
                            current.buildUpon().setMediaMetadata(metadata).build(),
                        )
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun applySkipSilenceOnPlayer(player: ExoPlayer, enabled: Boolean) {
        player.skipSilenceEnabled = enabled
    }

    internal fun syncVolatileStateFromPlayer(player: ExoPlayer) {
        val pos = player.currentPosition
        if (pos >= 0L && pos != C.TIME_UNSET) {
            cachedPositionMs = pos
        }
        val dur = player.duration
        if (dur > 0L && dur != C.TIME_UNSET) {
            cachedDurationMs = dur
        }
        cachedIsPlaying = isActivelyPlayingOrBuffering(player)
    }

    private fun isActivelyPlayingOrBuffering(player: ExoPlayer): Boolean =
        player.playWhenReady &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED

    internal fun notifyExternalPlayState(player: ExoPlayer) {
        if (suppressExternalCallbacks || activeLoadId == 0L) return
        if (player.playbackState == Player.STATE_ENDED) return
        val effectivePlaying = isActivelyPlayingOrBuffering(player)
        if (lastNotifiedExternalPlaying == effectivePlaying) return
        lastNotifiedExternalPlaying = effectivePlaying
        externalPlayStateListener?.invoke(effectivePlaying)
    }

    internal fun clearActiveCallbacks() {
        activeLoadId = 0L
        notifiedReadyLoadId = -1L
        lastNotifiedExternalPlaying = null
        activeOnPrepared = null
        activeOnCompletion = null
        activeOnError = null
    }

    private inline fun withCallbacksSuppressed(block: () -> Unit) {
        suppressExternalCallbacks = true
        try {
            block()
        } finally {
            suppressExternalCallbacks = false
        }
    }

    private fun runOnPlayerThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun toPlaybackUri(source: String): Uri =
        if (source.startsWith("/")) {
            Uri.fromFile(File(source))
        } else {
            PublicUrlValidator.upgradePublicHttp(source).toUri()
        }

    internal companion object {
        internal const val SEEK_BACK_INCREMENT_MS = 15_000L
        internal const val SEEK_FORWARD_INCREMENT_MS = 30_000L
        private const val POSITION_POLL_INTERVAL_MS = 250L
        private const val MIN_SPEED = 0.50f
        private const val MAX_SPEED = 3.50f
    }
}

private const val HTTPS_PREFIX = "https://"
private const val HTTP_PREFIX = "http://"
private const val FILE_URI_PREFIX = "file:"
internal const val DEFAULT_FALLBACK_DURATION_MS = 45L * 60L * 1_000L
