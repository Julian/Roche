package com.grayvines.roche.playback

import android.content.Context
import android.net.Uri
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.grayvines.roche.core.isCanonicalFileWithinDirs
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.data.remote.PublicUrlValidator
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun isSafeAudioPlaybackSource(
    source: String,
    allowedLocalDirs: List<File>,
): Boolean {
    val trimmed = source.trim()
    return when {
        trimmed.isEmpty() -> false
        isHttpOrHttpsUrl(trimmed) -> isValidHttpPlaybackUri(trimmed)
        trimmed.startsWith(FILE_URI_PREFIX, ignoreCase = true) || trimmed.startsWith("/") ->
            isCanonicalFileAllowed(trimmed, allowedLocalDirs)
        else -> false
    }
}

private fun isValidHttpPlaybackUri(trimmed: String): Boolean = runCatching {
    PublicUrlValidator.ensurePublicUrlSyntax(trimmed)
    val parsed = URI.create(trimmed.replace(" ", "%20"))
    val scheme = parsed.scheme?.lowercase()
    (scheme == "https" || scheme == "http") && !parsed.host.isNullOrBlank()
}
    .getOrDefault(false)

private fun isCanonicalFileAllowed(
    trimmed: String,
    allowedLocalDirs: List<File>,
): Boolean = runCatching {
    val file =
        if (trimmed.startsWith(FILE_URI_PREFIX, ignoreCase = true)) {
            File(URI.create(trimmed.replace(" ", "%20")))
        } else {
            File(trimmed)
        }
    isCanonicalFileWithinDirs(file, allowedLocalDirs)
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

@Suppress("TooManyFunctions", "LongParameterList")
class ExoPlayerAudioBackend
internal constructor(
    private val appContext: Context?,
    private val allowedLocalDirs: List<File>,
    parentScope: CoroutineScope? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val uriResolver: (String) -> Uri? = ::defaultPlaybackUri,
) : AudioPlayerBackend {
    private val parentJob: Job? = parentScope?.coroutineContext?.get(Job)
    private val scopeLock = Any()
    private val loadGeneration = AtomicLong(0L)
    @Volatile
    internal var backendScope: CoroutineScope = createBackendScope()
        private set

    internal var exoPlayer: ExoPlayer? = null
    internal var activeLoadId: Long = 0L
    internal var notifiedReadyLoadId: Long = -1L
    internal var lastNotifiedExternalPlaying: Boolean? = null
    internal var suppressExternalCallbacks: Boolean = false
    internal var activeOnPrepared: ((Long) -> Unit)? = null
    internal var activeOnCompletion: (() -> Unit)? = null
    internal var activeOnError: (() -> Unit)? = null
    internal var latestMetadata: MediaMetadata = MediaMetadata.EMPTY

    @Volatile internal var cachedPositionMs: Long = 0L
    @Volatile internal var cachedDurationMs: Long = DEFAULT_FALLBACK_DURATION_MS
    @Volatile internal var cachedIsPlaying: Boolean = false
    @Volatile private var externalPlayStateListener: ((Boolean) -> Unit)? = null

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

            override fun onPlayerError(error: PlaybackException) {
                cachedIsPlaying = false
                val callback = activeOnError
                clearActiveCallbacks()
                callback?.invoke()
            }
        }

    constructor(
        context: Context,
        allowedLocalDirs: List<File> =
            defaultAllowedLocalAudioDirs(
                File(context.applicationContext.filesDir, "offline_media")
            ),
        parentScope: CoroutineScope? = null,
    ) : this(
        appContext = context.applicationContext,
        allowedLocalDirs = allowedLocalDirs,
        parentScope = parentScope,
        mainDispatcher = Dispatchers.Main.immediate,
        uriResolver = ::defaultPlaybackUri,
    )

    private fun createBackendScope(): CoroutineScope =
        CoroutineScope(SupervisorJob(parentJob) + mainDispatcher)

    private fun activeBackendScope(): CoroutineScope =
        synchronized(scopeLock) { activeBackendScopeLocked() }

    private fun activeBackendScopeLocked(): CoroutineScope {
        val current = backendScope
        return if (current.isActive || parentJob?.isCancelled == true) {
            current
        } else {
            createBackendScope().also { backendScope = it }
        }
    }

    private fun rotateBackendScopeLocked(): CoroutineScope {
        val previous = backendScope
        if (parentJob?.isCancelled != true) {
            backendScope = createBackendScope()
        }
        return previous
    }

    override suspend fun syncPositionFromPlayer(): Long =
        withContext(mainDispatcher) {
            exoPlayer?.let { player -> syncVolatileStateFromPlayer(player) }
            cachedPositionMs
        }

    override fun bindExternalPlayStateListener(listener: ((Boolean) -> Unit)?) {
        externalPlayStateListener = listener
    }

    @OptIn(UnstableApi::class)
    internal fun getOrCreateExoPlayerOnMain(): ExoPlayer {
        exoPlayer?.let {
            return it
        }
        val context = checkNotNull(appContext) { "Context required to build ExoPlayer" }
        val audioDataSourceFactory = createAudioDataSourceFactory(context = context)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(audioDataSourceFactory)
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
        val loadId: Long
        val targetScope: CoroutineScope
        synchronized(scopeLock) {
            loadId = loadGeneration.incrementAndGet()
            cachedDurationMs = safeFallback
            cachedPositionMs = clampedInitial
            cachedIsPlaying = playWhenReady
            targetScope = activeBackendScopeLocked()
        }
        targetScope.launch {
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
                loadId = loadId,
            )
        }
    }

    @Suppress("LongParameterList")
    internal fun startMediaItemOnMain(
        source: String,
        initialPositionMs: Long,
        fallbackDurationMs: Long,
        speed: Float,
        skipSilenceEnabled: Boolean,
        playWhenReady: Boolean,
        onPrepared: (durationMs: Long) -> Unit,
        onCompletion: () -> Unit,
        onError: () -> Unit,
        loadId: Long = loadGeneration.incrementAndGet(),
    ) {
        if (loadGeneration.get() != loadId) return
        runCatching {
            val player = getOrCreateExoPlayerOnMain()
            activeLoadId = loadId
            notifiedReadyLoadId = -1L
            lastNotifiedExternalPlaying = playWhenReady
            activeOnPrepared = onPrepared
            activeOnCompletion = onCompletion
            activeOnError = onError
            cachedDurationMs = fallbackDurationMs
            cachedPositionMs = initialPositionMs
            val uri = uriResolver(source)
            val mediaItem =
                MediaItem.Builder()
                    .apply { if (uri != null) setUri(uri) }
                    .setMediaMetadata(latestMetadata)
                    .build()
            withCallbacksSuppressed {
                applySkipSilenceOnPlayer(player, skipSilenceEnabled)
                player.playbackParameters = PlaybackParameters(speed.coerceIn(MIN_SPEED, MAX_SPEED))
                player.setMediaItem(mediaItem, initialPositionMs)
                player.playWhenReady = playWhenReady
                player.prepare()
            }
        }
            .onFailure {
                if (loadGeneration.get() == loadId) {
                    cachedIsPlaying = false
                    clearActiveCallbacks()
                }
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
        val completion = activeOnCompletion
        clearActiveCallbacks()
        completion?.invoke()
    }

    override fun play() {
        cachedIsPlaying = true
        activeBackendScope().launch {
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
        activeBackendScope().launch {
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
        activeBackendScope().launch {
            exoPlayer?.let { player ->
                player.seekTo(clamped)
                syncVolatileStateFromPlayer(player)
            }
        }
    }

    override fun setSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        activeBackendScope().launch {
            exoPlayer?.playbackParameters = PlaybackParameters(safeSpeed)
        }
    }

    override fun setSkipSilence(enabled: Boolean) {
        activeBackendScope().launch {
            exoPlayer?.let { player -> applySkipSilenceOnPlayer(player, enabled) }
        }
    }

    override fun currentPositionMs(): Long = cachedPositionMs

    override fun durationMs(): Long = cachedDurationMs

    override fun isPlaying(): Boolean = cachedIsPlaying

    override fun release() {
        val generationAtRelease: Long
        val scopeToCancel: CoroutineScope
        synchronized(scopeLock) {
            generationAtRelease = loadGeneration.get()
            cachedIsPlaying = false
            scopeToCancel = rotateBackendScopeLocked()
        }
        if (!scopeToCancel.isActive) return
        scopeToCancel.launch {
            if (loadGeneration.get() == generationAtRelease) {
                cachedIsPlaying = false
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
            scopeToCancel.coroutineContext[Job]?.cancel()
        }
    }

    internal fun shutdownPlayer() {
        cachedIsPlaying = false
        val scopeToCancel = synchronized(scopeLock) { backendScope }
        val dispatchScope =
            scopeToCancel.takeIf { it.isActive } ?: CoroutineScope(SupervisorJob() + mainDispatcher)
        dispatchScope.launch {
            clearActiveCallbacks()
            exoPlayer?.let { player ->
                player.removeListener(playerListener)
                player.release()
            }
            exoPlayer = null
            scopeToCancel.coroutineContext[Job]?.cancel()
            dispatchScope.coroutineContext[Job]?.cancel()
        }
    }

    internal fun updateSessionMediaMetadata(metadata: MediaMetadata) {
        activeBackendScope().launch {
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
    internal fun applySkipSilenceOnPlayer(player: ExoPlayer, enabled: Boolean) {
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

    internal inline fun withCallbacksSuppressed(block: () -> Unit) {
        suppressExternalCallbacks = true
        try {
            block()
        } finally {
            suppressExternalCallbacks = false
        }
    }

    internal fun toPlaybackUri(source: String): Uri = defaultPlaybackUri(source)

    internal companion object {
        internal const val SEEK_BACK_INCREMENT_MS = 15_000L
        internal const val SEEK_FORWARD_INCREMENT_MS = 30_000L
        internal const val MIN_SPEED = 0.50f
        internal const val MAX_SPEED = 3.50f
    }
}

internal fun defaultAllowedLocalAudioDirs(offlineMediaRoot: File): List<File> =
    listOf(File(offlineMediaRoot, "items"))

internal fun defaultPlaybackUri(source: String): Uri =
    if (source.startsWith("/")) {
        Uri.fromFile(File(source))
    } else {
        PublicUrlValidator.upgradePublicHttp(source).toUri()
    }

private const val FILE_URI_PREFIX = "file:"
internal const val DEFAULT_FALLBACK_DURATION_MS = 45L * 60L * 1_000L
