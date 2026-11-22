@file:Suppress("TooManyFunctions")

package com.grayvines.roche.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.grayvines.roche.core.isCanonicalFileWithinDirs
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.data.remote.PublicUrlValidator
import java.io.File
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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

internal interface Media3SessionClient {
    val player: Player

    fun sendSkipSilence(enabled: Boolean)

    fun sendHasNextQueued(hasNext: Boolean)

    fun sendStopAndRelease()

    fun disconnect()
}

private class RealMediaControllerClient(
    private val controller: MediaController,
    private val future: ListenableFuture<MediaController>,
) : Media3SessionClient {
    override val player: Player
        get() = controller

    override fun sendSkipSilence(enabled: Boolean) {
        val args = Bundle().apply { putBoolean(PodcastPlaybackService.EXTRA_SKIP_SILENCE, enabled) }
        runCatching {
            controller.sendCustomCommand(PodcastPlaybackService.COMMAND_SET_SKIP_SILENCE, args)
        }
    }

    override fun sendHasNextQueued(hasNext: Boolean) {
        val args = Bundle().apply { putBoolean(PodcastPlaybackService.EXTRA_HAS_NEXT, hasNext) }
        runCatching {
            controller.sendCustomCommand(PodcastPlaybackService.COMMAND_SET_HAS_NEXT, args)
        }
    }

    override fun sendStopAndRelease() {
        runCatching {
            controller.sendCustomCommand(
                PodcastPlaybackService.COMMAND_STOP_AND_RELEASE,
                Bundle.EMPTY,
            )
        }
    }

    override fun disconnect() {
        runCatching { MediaController.releaseFuture(future) }
    }
}

internal data class PendingLoadRequest(
    val source: String,
    val initialPositionMs: Long,
    val fallbackDurationMs: Long,
    val speed: Float,
    val skipSilenceEnabled: Boolean,
    val hasNextQueuedAudio: Boolean,
    val metadata: AudioPlaybackMetadata?,
    val playWhenReady: Boolean,
)

internal class NoOpAudioPlayerBackend : AudioPlayerBackend {
    private var positionMs: Long = 0L
    private var durationMs: Long = DEFAULT_FALLBACK_DURATION_MS
    private var playing: Boolean = false
    private var listener: AudioBackendListener? = null

    override fun bindListener(listener: AudioBackendListener?) {
        this.listener = listener
    }

    override fun loadAndPlay(request: AudioLoadRequest) {
        if (request.source.isBlank()) {
            playing = false
            listener?.onError()
            return
        }
        durationMs = request.fallbackDurationMs.takeIf { it > 0L } ?: DEFAULT_FALLBACK_DURATION_MS
        positionMs = request.initialPositionMs.coerceIn(0L, durationMs)
        playing = request.playWhenReady
        listener?.onPrepared(durationMs)
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

@Suppress("LongParameterList")
class Media3AudioBackend
internal constructor(
    private val appContext: Context?,
    private val allowedLocalDirs: List<File>,
    parentScope: CoroutineScope? = null,
    private val artworkBitmapLoader: (suspend (String) -> Bitmap?)? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val uriResolver: (String) -> Uri? = ::defaultPlaybackUri,
    private val customClientConnector:
        ((onDisconnected: () -> Unit) -> ListenableFuture<Media3SessionClient>)? =
        null,
    private val callbackExecutor: Executor? = null,
) : AudioPlayerBackend {
    private val parentJob: Job? = parentScope?.coroutineContext?.get(Job)
    private val scopeLock = Any()
    private val loadGeneration = AtomicLong(0L)
    private val artworkScope = CoroutineScope(SupervisorJob(parentJob) + Dispatchers.Default)

    @Volatile
    internal var backendScope: CoroutineScope = createBackendScope()
        private set

    internal var clientFuture: ListenableFuture<Media3SessionClient>? = null
    internal var connectedClient: Media3SessionClient? = null
    internal var activeLoadId: Long = 0L
    internal var notifiedReadyLoadId: Long = -1L
    internal var latestMetadata: AudioPlaybackMetadata? = null
    internal var latestMedia3Metadata: MediaMetadata = MediaMetadata.EMPTY
    internal var lastMetadataKey: SessionMetadataKey? = null
    internal var lastHasNextQueuedAudio: Boolean = false
    internal var lastSkipSilenceEnabled: Boolean = false
    internal var lastLoadRequest: PendingLoadRequest? = null
    internal var desiredPlayWhenReady: Boolean = false
    internal var cachedArtworkUrl: String? = null
    internal var cachedArtworkBytes: ByteArray? = null
    internal var loadingArtworkUrl: String? = null
    internal var artworkLoadJob: Job? = null

    @Volatile internal var cachedPositionMs: Long = 0L
    @Volatile internal var cachedDurationMs: Long = DEFAULT_FALLBACK_DURATION_MS
    @Volatile internal var cachedIsPlaying: Boolean = false
    @Volatile private var listener: AudioBackendListener? = null

    private val playerListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val player = connectedClient?.player ?: return
                if (playbackState == Player.STATE_ENDED) {
                    handlePlayerEnded()
                    return
                }
                syncVolatileStateFromPlayer(player)
                if (playbackState == Player.STATE_READY) {
                    handlePlayerReady(player)
                }
                emitPlayState(player)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val player = connectedClient?.player ?: return
                syncVolatileStateFromPlayer(player)
                emitPlayState(player)
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                val player = connectedClient?.player ?: return
                syncVolatileStateFromPlayer(player)
                emitPlayState(player)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                val player = connectedClient?.player ?: return
                syncVolatileStateFromPlayer(player)
                if (reason == Player.DISCONTINUITY_REASON_SEEK && activeLoadId != 0L) {
                    listener?.onPositionSeeked(cachedPositionMs)
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (activeLoadId != 0L && playbackParameters.speed > 0f) {
                    listener?.onSpeedChanged(playbackParameters.speed)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                cachedIsPlaying = false
                activeLoadId = 0L
                notifiedReadyLoadId = -1L
                listener?.onError()
            }
        }

    constructor(
        context: Context,
        allowedLocalDirs: List<File> =
            defaultAllowedLocalAudioDirs(
                File(context.applicationContext.filesDir, "offline_media")
            ),
        parentScope: CoroutineScope? = null,
        artworkBitmapLoader: (suspend (String) -> Bitmap?)? = null,
    ) : this(
        appContext = context.applicationContext,
        allowedLocalDirs = allowedLocalDirs,
        parentScope = parentScope,
        artworkBitmapLoader = artworkBitmapLoader,
        mainDispatcher = Dispatchers.Main.immediate,
        uriResolver = ::defaultPlaybackUri,
        customClientConnector = null,
        callbackExecutor = context.applicationContext.mainExecutor,
    )

    override fun bindListener(listener: AudioBackendListener?) {
        this.listener = listener
    }

    private fun createBackendScope(): CoroutineScope =
        CoroutineScope(SupervisorJob(parentJob) + mainDispatcher)

    private fun activeBackendScope(): CoroutineScope =
        synchronized(scopeLock) { activeBackendScopeLocked() }

    private fun activeBackendScopeLocked(): CoroutineScope {
        val current = backendScope
        val isCurrentActive = current.coroutineContext[Job]?.isActive == true
        return if (isCurrentActive || parentJob?.isCancelled == true) {
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
            connectedClient?.player?.let(::syncVolatileStateFromPlayer)
            cachedPositionMs
        }

    override fun loadAndPlay(request: AudioLoadRequest) {
        if (!isSafeAudioPlaybackSource(request.source, allowedLocalDirs)) {
            cachedIsPlaying = false
            listener?.onError()
            return
        }
        val safeFallback =
            request.fallbackDurationMs.takeIf { it > 0L } ?: DEFAULT_FALLBACK_DURATION_MS
        val clampedInitial = request.initialPositionMs.coerceIn(0L, safeFallback)
        val pending =
            PendingLoadRequest(
                source = request.source.trim(),
                initialPositionMs = clampedInitial,
                fallbackDurationMs = safeFallback,
                speed = request.speed,
                skipSilenceEnabled = request.skipSilenceEnabled,
                hasNextQueuedAudio = request.hasNextQueuedAudio,
                metadata = request.metadata,
                playWhenReady = request.playWhenReady,
            )
        val loadId: Long
        val targetScope: CoroutineScope
        synchronized(scopeLock) {
            loadId = loadGeneration.incrementAndGet()
            cachedDurationMs = safeFallback
            cachedPositionMs = clampedInitial
            cachedIsPlaying = request.playWhenReady
            targetScope = activeBackendScopeLocked()
        }
        targetScope.launch { startLoadOnMain(pending, loadId) }
    }

    private suspend fun startLoadOnMain(pending: PendingLoadRequest, loadId: Long) {
        if (loadGeneration.get() != loadId) return
        lastLoadRequest = pending
        desiredPlayWhenReady = pending.playWhenReady
        lastSkipSilenceEnabled = pending.skipSilenceEnabled
        lastHasNextQueuedAudio = pending.hasNextQueuedAudio
        updateMetadataCacheOnMain(pending.metadata)
        val client = runCatching {
            ensureConnectedClientOnMain()
        }
            .onFailure {
                if (
                    it is CancellationException && currentCoroutineContext()[Job]?.isActive != true
                ) {
                    throw it
                }
            }
            .getOrNull()
        if (loadGeneration.get() != loadId) return
        if (client == null) {
            cachedIsPlaying = false
            listener?.onError()
            return
        }
        runCatching { applyMediaItemOnMain(client, pending, loadId) }
            .onFailure {
                if (loadGeneration.get() == loadId) {
                    cachedIsPlaying = false
                    activeLoadId = 0L
                    notifiedReadyLoadId = -1L
                }
                listener?.onError()
            }
    }

    private fun applyMediaItemOnMain(
        client: Media3SessionClient,
        pending: PendingLoadRequest,
        loadId: Long,
    ) {
        val effective = lastLoadRequest ?: pending
        activeLoadId = 0L
        notifiedReadyLoadId = -1L
        cachedDurationMs = effective.fallbackDurationMs
        cachedPositionMs = effective.initialPositionMs
        val uri = uriResolver(effective.source)
        val mediaItem =
            MediaItem.Builder()
                .apply {
                    if (uri != null) {
                        setUri(uri)
                        setRequestMetadata(
                            MediaItem.RequestMetadata.Builder().setMediaUri(uri).build()
                        )
                    }
                }
                .setMediaMetadata(latestMedia3Metadata)
                .build()
        client.sendSkipSilence(lastSkipSilenceEnabled)
        client.sendHasNextQueued(lastHasNextQueuedAudio)
        val player = client.player
        player.playbackParameters =
            PlaybackParameters(effective.speed.coerceIn(MIN_SPEED, MAX_SPEED))
        player.setMediaItem(mediaItem, effective.initialPositionMs)
        player.playWhenReady = desiredPlayWhenReady
        player.prepare()
        activeLoadId = loadId
    }

    internal fun handlePlayerReady(player: Player) {
        if (notifiedReadyLoadId == activeLoadId || activeLoadId == 0L) return
        notifiedReadyLoadId = activeLoadId
        val resolvedDuration =
            player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: cachedDurationMs
        cachedDurationMs = resolvedDuration
        listener?.onPrepared(resolvedDuration)
    }

    internal fun handlePlayerEnded() {
        if (activeLoadId == 0L) return
        cachedIsPlaying = false
        activeLoadId = 0L
        notifiedReadyLoadId = -1L
        listener?.onCompletion()
    }

    override fun play() {
        cachedIsPlaying = true
        activeBackendScope().launch {
            desiredPlayWhenReady = true
            val player = connectedClient?.player
            if (player != null && player.mediaItemCount > 0) {
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                player.play()
                syncVolatileStateFromPlayer(player)
            } else {
                val saved = lastLoadRequest
                if (saved != null) {
                    val reloadId = loadGeneration.incrementAndGet()
                    startLoadOnMain(
                        saved.copy(initialPositionMs = cachedPositionMs, playWhenReady = true),
                        reloadId,
                    )
                }
            }
        }
    }

    override fun pause() {
        cachedIsPlaying = false
        activeBackendScope().launch {
            desiredPlayWhenReady = false
            connectedClient?.player?.let { player ->
                player.pause()
                syncVolatileStateFromPlayer(player)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, cachedDurationMs.coerceAtLeast(1L))
        cachedPositionMs = clamped
        activeBackendScope().launch {
            lastLoadRequest = lastLoadRequest?.copy(initialPositionMs = clamped)
            connectedClient?.player?.let { player ->
                player.seekTo(clamped)
                syncVolatileStateFromPlayer(player)
            }
        }
    }

    override fun setSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        activeBackendScope().launch {
            lastLoadRequest = lastLoadRequest?.copy(speed = safeSpeed)
            connectedClient?.player?.playbackParameters = PlaybackParameters(safeSpeed)
        }
    }

    override fun setSkipSilence(enabled: Boolean) {
        activeBackendScope().launch {
            lastSkipSilenceEnabled = enabled
            lastLoadRequest = lastLoadRequest?.copy(skipSilenceEnabled = enabled)
            connectedClient?.sendSkipSilence(enabled)
        }
    }

    override fun updateHasNextQueuedAudio(hasNext: Boolean) {
        activeBackendScope().launch {
            lastHasNextQueuedAudio = hasNext
            lastLoadRequest = lastLoadRequest?.copy(hasNextQueuedAudio = hasNext)
            connectedClient?.sendHasNextQueued(hasNext)
        }
    }

    override fun updateMetadata(metadata: AudioPlaybackMetadata?) {
        activeBackendScope().launch {
            updateMetadataCacheOnMain(metadata)
            applyMediaMetadataOnPlayer()
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
        if (scopeToCancel.coroutineContext[Job]?.isActive != true) return
        scopeToCancel.launch {
            if (loadGeneration.get() == generationAtRelease) {
                clearAndDisconnectOnMain(stopService = true)
            }
            scopeToCancel.coroutineContext[Job]?.cancel()
        }
    }

    internal fun shutdownBackend() {
        cachedIsPlaying = false
        val scopeToCancel = synchronized(scopeLock) { backendScope }
        val dispatchScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        dispatchScope.launch {
            clearAndDisconnectOnMain(stopService = true)
            artworkScope.coroutineContext[Job]?.cancel()
            scopeToCancel.coroutineContext[Job]?.cancel()
            dispatchScope.coroutineContext[Job]?.cancel()
        }
    }

    private fun clearAndDisconnectOnMain(stopService: Boolean) {
        cachedIsPlaying = false
        desiredPlayWhenReady = false
        activeLoadId = 0L
        notifiedReadyLoadId = -1L
        lastLoadRequest = null
        latestMetadata = null
        latestMedia3Metadata = MediaMetadata.EMPTY
        lastMetadataKey = null
        artworkLoadJob?.cancel()
        artworkLoadJob = null
        loadingArtworkUrl = null
        val client = connectedClient
        if (stopService && client != null) {
            client.sendStopAndRelease()
        }
        disconnectClientOnMain()
    }

    private suspend fun ensureConnectedClientOnMain(): Media3SessionClient? =
        connectedClient ?: connectNewClientOnMain()

    private suspend fun connectNewClientOnMain(): Media3SessionClient? {
        val future = getOrStartClientFutureOnMain() ?: return null
        val client = runCatching {
            awaitListenableFuture(future, callbackExecutor ?: Executor(Runnable::run))
        }
            .getOrElse { err ->
                if (clientFuture === future) {
                    clientFuture = null
                }
                throw err
            }
        return if (clientFuture !== future) {
            client.disconnect()
            connectedClient
        } else {
            if (connectedClient !== client) {
                connectedClient?.player?.removeListener(playerListener)
                connectedClient = client
                client.player.addListener(playerListener)
            }
            client
        }
    }

    private fun getOrStartClientFutureOnMain(): ListenableFuture<Media3SessionClient>? =
        clientFuture ?: startNewClientFutureOnMain()

    private fun startNewClientFutureOnMain(): ListenableFuture<Media3SessionClient>? {
        var createdFuture: ListenableFuture<Media3SessionClient>? = null
        val onDisconnected = { clearClientOnDisconnect(createdFuture) }
        val future =
            customClientConnector?.invoke(onDisconnected)
                ?: buildRealControllerFuture(onDisconnected)
        if (future != null) {
            createdFuture = future
            clientFuture = future
        }
        return future
    }

    private fun buildRealControllerFuture(
        onDisconnected: () -> Unit
    ): ListenableFuture<Media3SessionClient>? {
        val context = appContext ?: return null
        val token =
            SessionToken(context, ComponentName(context, PodcastPlaybackService::class.java))
        val rawFuture =
            MediaController.Builder(context, token)
                .setListener(
                    object : MediaController.Listener {
                        override fun onDisconnected(controller: MediaController) {
                            onDisconnected()
                        }
                    }
                )
                .buildAsync()
        return AdaptingControllerFuture(rawFuture)
    }

    internal fun clearClientOnDisconnect(
        disconnectedFuture: ListenableFuture<Media3SessionClient>?
    ) {
        val target = disconnectedFuture ?: clientFuture ?: return
        if (clientFuture === target) {
            connectedClient?.player?.removeListener(playerListener)
            connectedClient = null
            clientFuture = null
            runCatching { target.cancel(true) }
        }
    }

    internal fun disconnectClientOnMain() {
        val client = connectedClient
        val future = clientFuture
        connectedClient = null
        clientFuture = null
        client?.player?.removeListener(playerListener)
        if (client != null) {
            client.disconnect()
        } else if (future != null) {
            runCatching { future.cancel(true) }
        }
    }

    private fun updateMetadataCacheOnMain(metadata: AudioPlaybackMetadata?) {
        latestMetadata = metadata
        if (metadata == null) {
            artworkLoadJob?.cancel()
            artworkLoadJob = null
            loadingArtworkUrl = null
            lastMetadataKey = null
            latestMedia3Metadata = MediaMetadata.EMPTY
            return
        }
        val artworkBytes = resolveCachedArtworkBytes(metadata.artworkUrl)
        if (artworkBytes == null) {
            requestArtworkLoadOnMain(metadata.artworkUrl)
        }
        val key = metadata.toMetadataKey(hasArtworkBytes = artworkBytes != null)
        if (key != lastMetadataKey) {
            lastMetadataKey = key
            latestMedia3Metadata = buildMedia3Metadata(metadata, artworkBytes)
        }
    }

    private fun applyMediaMetadataOnPlayer() {
        val player = connectedClient?.player ?: return
        if (player.mediaItemCount > 0) {
            val current = player.getMediaItemAt(0)
            if (current.mediaMetadata != latestMedia3Metadata) {
                player.replaceMediaItem(
                    0,
                    current.buildUpon().setMediaMetadata(latestMedia3Metadata).build(),
                )
            }
        }
    }

    private fun resolveCachedArtworkBytes(artworkUrl: String?): ByteArray? {
        val cleanUrl = artworkUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (cleanUrl == cachedArtworkUrl) cachedArtworkBytes else null
    }

    private fun requestArtworkLoadOnMain(artworkUrl: String?) {
        val loader = artworkBitmapLoader ?: return
        val cleanUrl = artworkUrl?.trim()?.takeIf { it.isNotBlank() }
        if (cleanUrl == null) {
            artworkLoadJob?.cancel()
            artworkLoadJob = null
            loadingArtworkUrl = null
            return
        }
        if (loadingArtworkUrl == cleanUrl && artworkLoadJob?.isActive == true) return
        artworkLoadJob?.cancel()
        loadingArtworkUrl = cleanUrl
        artworkLoadJob = artworkScope.launch {
            val bytes = runCatching {
                loader(cleanUrl)?.let(::compressArtworkBytes)
            }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            withContext(mainDispatcher) {
                if (bytes != null && bytes.isNotEmpty()) {
                    cachedArtworkUrl = cleanUrl
                    cachedArtworkBytes = bytes
                    applyLoadedArtworkOnMain(cleanUrl, bytes)
                }
                if (loadingArtworkUrl == cleanUrl) {
                    loadingArtworkUrl = null
                }
            }
        }
    }

    internal fun applyLoadedArtworkOnMain(loadedUrl: String, bytes: ByteArray) {
        val metadata = latestMetadata ?: return
        if (metadata.artworkUrl?.trim() != loadedUrl) return
        lastMetadataKey = metadata.toMetadataKey(hasArtworkBytes = true)
        latestMedia3Metadata = buildMedia3Metadata(metadata, bytes)
        applyMediaMetadataOnPlayer()
    }

    internal fun syncVolatileStateFromPlayer(player: Player) {
        if (player.playbackState != Player.STATE_IDLE && player.mediaItemCount > 0) {
            val pos = player.currentPosition
            if (pos >= 0L && pos != C.TIME_UNSET) {
                cachedPositionMs = pos
            }
            val dur = player.duration
            if (dur > 0L && dur != C.TIME_UNSET) {
                cachedDurationMs = dur
            }
        }
        cachedIsPlaying = isActivelyPlayingOrBuffering(player)
    }

    internal fun emitPlayState(player: Player) {
        if (
            activeLoadId == 0L ||
                player.playbackState == Player.STATE_IDLE ||
                player.playbackState == Player.STATE_ENDED
        ) {
            return
        }
        val isPlayingNow = isActivelyPlayingOrBuffering(player)
        val isBufferingNow = player.playbackState == Player.STATE_BUFFERING
        listener?.onPlayStateChanged(
            isPlaying = isPlayingNow,
            isBuffering = isBufferingNow,
            positionMs = cachedPositionMs,
            durationMs = cachedDurationMs,
        )
    }

    private fun isActivelyPlayingOrBuffering(player: Player): Boolean =
        player.playWhenReady &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED

    internal companion object {
        internal const val MIN_SPEED = 0.50f
        internal const val MAX_SPEED = 3.50f
    }
}

private class AdaptingControllerFuture(private val delegate: ListenableFuture<MediaController>) :
    ListenableFuture<Media3SessionClient> {
    @Volatile private var wrapped: Media3SessionClient? = null

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        MediaController.releaseFuture(delegate)
        return delegate.isCancelled
    }

    override fun isCancelled(): Boolean = delegate.isCancelled

    override fun isDone(): Boolean = delegate.isDone

    override fun get(): Media3SessionClient =
        wrapped ?: RealMediaControllerClient(delegate.get(), delegate).also { wrapped = it }

    override fun get(timeout: Long, unit: java.util.concurrent.TimeUnit): Media3SessionClient =
        wrapped
            ?: RealMediaControllerClient(delegate.get(timeout, unit), delegate).also {
                wrapped = it
            }

    override fun addListener(listener: Runnable, executor: Executor) {
        delegate.addListener(listener, executor)
    }
}

internal suspend fun <T> awaitListenableFuture(
    future: ListenableFuture<T>,
    executor: Executor,
): T {
    if (future.isDone) {
        return future.get()
    }
    return suspendCancellableCoroutine { cont ->
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { if (cont.isActive) cont.resume(it) }
                    .onFailure { err ->
                        if (cont.isActive) {
                            cont.resumeWithException(err.cause ?: err)
                        }
                    }
            },
            executor,
        )
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
