package com.grayvines.roche.ui

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.grayvines.roche.MainActivity
import com.grayvines.roche.RocheApp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal fun mapMediaKeyCodeToCommand(keyCode: Int): MediaSessionCommand? =
    when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> MediaSessionCommand.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaSessionCommand.PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK -> MediaSessionCommand.TOGGLE_PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaSessionCommand.REWIND_15S
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> MediaSessionCommand.FAST_FORWARD_30S
        KeyEvent.KEYCODE_MEDIA_NEXT -> MediaSessionCommand.SKIP_TO_NEXT
        KeyEvent.KEYCODE_MEDIA_STOP -> MediaSessionCommand.STOP
        else -> null
    }

internal data class SessionMetadataKey(
    val entryId: String,
    val episodeTitle: String,
    val podcastTitle: String,
    val artworkUrl: String?,
    val durationMs: Long,
    val hasArtworkBytes: Boolean = false,
)

internal fun MediaSessionMetadataSnapshot.toMetadataKey(
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

@Suppress("TooManyFunctions")
class AndroidPodcastMediaSessionBridge(
    context: Context,
    private val backend: ExoPlayerAudioBackend? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val artworkBitmapLoader: (suspend (String) -> Bitmap?)? = null,
) : PodcastMediaSessionBridge {
    private val appContext: Context = context.applicationContext
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    @Volatile private var callbacks: PodcastMediaSessionCallbacks? = null
    @Volatile private var latestSnapshot: MediaSessionMetadataSnapshot? = null
    @Volatile private var cachedArtworkUrl: String? = null
    @Volatile private var cachedArtworkBytes: ByteArray? = null
    @Volatile private var activeMediaSession: MediaSession? = null

    private var lastMetadataKey: SessionMetadataKey? = null
    private var lastHasNextQueuedAudio: Boolean? = null
    private var loadingArtworkUrl: String? = null
    private var artworkLoadJob: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun bindCallbacks(callbacks: PodcastMediaSessionCallbacks) {
        this.callbacks = callbacks
    }

    override fun unbindCallbacks(callbacks: PodcastMediaSessionCallbacks) {
        if (this.callbacks === callbacks) {
            this.callbacks = null
        }
    }

    internal fun activeCallbacks(): PodcastMediaSessionCallbacks? = callbacks

    internal fun currentSnapshot(): MediaSessionMetadataSnapshot? = latestSnapshot

    override fun requestAudioFocus(): Boolean = true

    override fun abandonAudioFocus() = Unit

    override fun updatePlaybackSession(snapshot: MediaSessionMetadataSnapshot?) {
        runOnMain { updateSessionOnMain(snapshot) }
    }

    override fun release() {
        runOnMain {
            artworkLoadJob?.cancel()
            artworkLoadJob = null
            loadingArtworkUrl = null
            cachedArtworkUrl = null
            cachedArtworkBytes = null
            latestSnapshot = null
            lastMetadataKey = null
            lastHasNextQueuedAudio = null
            disconnectControllerOnMain()
        }
    }

    internal fun attachServiceSessionOnMain(session: MediaSession) {
        activeMediaSession = session
        latestSnapshot?.let { snapshot ->
            lastHasNextQueuedAudio = null
            applyButtonPrefsOnMain(session, snapshot.hasNextQueuedAudio)
        }
    }

    internal fun detachServiceSessionOnMain(session: MediaSession) {
        if (activeMediaSession === session) {
            activeMediaSession = null
            lastHasNextQueuedAudio = null
        }
    }

    private fun updateSessionOnMain(snapshot: MediaSessionMetadataSnapshot?) {
        latestSnapshot = snapshot
        if (snapshot == null) {
            artworkLoadJob?.cancel()
            artworkLoadJob = null
            loadingArtworkUrl = null
            lastMetadataKey = null
            lastHasNextQueuedAudio = null
            disconnectControllerOnMain()
            return
        }
        connectControllerOnMain()
        val artworkBytes = resolveCachedArtworkBytes(snapshot.artworkUrl)
        if (artworkBytes == null) {
            requestArtworkLoadOnMain(snapshot.artworkUrl)
        }
        val key = snapshot.toMetadataKey(hasArtworkBytes = artworkBytes != null)
        if (key != lastMetadataKey) {
            lastMetadataKey = key
            backend?.updateSessionMediaMetadata(buildMedia3Metadata(snapshot, artworkBytes))
        }
        activeMediaSession?.let { session ->
            applyButtonPrefsOnMain(session, snapshot.hasNextQueuedAudio)
        }
    }

    @OptIn(UnstableApi::class)
    private fun applyButtonPrefsOnMain(
        session: MediaSession,
        hasNextQueuedAudio: Boolean,
    ) {
        if (lastHasNextQueuedAudio == hasNextQueuedAudio) return
        lastHasNextQueuedAudio = hasNextQueuedAudio
        session.setMediaButtonPreferences(buildTransportButtons(hasNextQueuedAudio))
    }

    private fun connectControllerOnMain() {
        if (controllerFuture != null || backend == null) return
        runCatching {
            val token =
                SessionToken(
                    appContext,
                    ComponentName(appContext, PodcastPlaybackService::class.java),
                )
            controllerFuture = MediaController.Builder(appContext, token).buildAsync()
        }
    }

    private fun disconnectControllerOnMain() {
        val existing = controllerFuture ?: return
        controllerFuture = null
        runCatching { MediaController.releaseFuture(existing) }
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
        artworkLoadJob = scope.launch {
            val pngBytes = runCatching {
                loader(cleanUrl)?.let(::encodeBitmapPng)
            }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (pngBytes != null && pngBytes.isNotEmpty()) {
                cachedArtworkUrl = cleanUrl
                cachedArtworkBytes = pngBytes
                runOnMain { applyLoadedArtworkOnMain(cleanUrl, pngBytes) }
            }
            runOnMain {
                if (loadingArtworkUrl == cleanUrl) {
                    loadingArtworkUrl = null
                }
            }
        }
    }

    private fun applyLoadedArtworkOnMain(loadedUrl: String, pngBytes: ByteArray) {
        val snapshot = latestSnapshot ?: return
        if (snapshot.artworkUrl?.trim() != loadedUrl) return
        lastMetadataKey = snapshot.toMetadataKey(hasArtworkBytes = true)
        backend?.updateSessionMediaMetadata(buildMedia3Metadata(snapshot, pngBytes))
    }

    internal fun createOpenPlayerPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            REQUEST_OPEN_PLAYER,
            Intent(appContext, MainActivity::class.java).apply {
                action = PodcastPlaybackService.ACTION_OPEN_PLAYER
                putExtra(PodcastPlaybackService.EXTRA_OPEN_FULLSCREEN_PLAYER, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    internal companion object {
        private const val REQUEST_OPEN_PLAYER = 400
        private const val PNG_QUALITY = 100

        @OptIn(UnstableApi::class)
        internal fun buildTransportButtons(hasNextQueuedAudio: Boolean): List<CommandButton> =
            buildList {
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
            snapshot: MediaSessionMetadataSnapshot,
            artworkBytes: ByteArray?,
        ): MediaMetadata {
            val title = snapshot.episodeTitle.ifBlank { "Podcast Episode" }
            val podcast = snapshot.podcastTitle.ifBlank { "Roche Podcasts" }
            val builder =
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setArtist(podcast)
                    .setAlbumTitle(podcast)
                    .setSubtitle(podcast)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
            snapshot.artworkUrl
                ?.trim()
                ?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
                ?.let { builder.setArtworkUri(it.toUri()) }
            if (artworkBytes != null && artworkBytes.isNotEmpty()) {
                builder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
            return builder.build()
        }

        private fun encodeBitmapPng(bitmap: Bitmap): ByteArray =
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                out.toByteArray()
            }
    }
}

@OptIn(UnstableApi::class)
@Suppress("TooManyFunctions")
private class RocheSessionForwardingPlayer(
    player: Player,
    private val bridge: AndroidPodcastMediaSessionBridge,
) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands {
        val hasNext = bridge.currentSnapshot()?.hasNextQueuedAudio == true
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
        if (hasNext) {
            builder.add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            builder.remove(COMMAND_SEEK_TO_NEXT).remove(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        getAvailableCommands().contains(command)

    override fun play() {
        val cb = bridge.activeCallbacks()
        if (cb != null) cb.onMediaCommand(MediaSessionCommand.PLAY) else super.play()
    }

    override fun pause() {
        val cb = bridge.activeCallbacks()
        if (cb != null) cb.onMediaCommand(MediaSessionCommand.PAUSE) else super.pause()
    }

    override fun seekBack() {
        val cb = bridge.activeCallbacks()
        if (cb != null) cb.onMediaCommand(MediaSessionCommand.REWIND_15S) else super.seekBack()
    }

    override fun seekForward() {
        val cb = bridge.activeCallbacks()
        if (cb != null) {
            cb.onMediaCommand(MediaSessionCommand.FAST_FORWARD_30S)
        } else {
            super.seekForward()
        }
    }

    override fun seekToPrevious() {
        val cb = bridge.activeCallbacks()
        if (cb != null) cb.onMediaCommand(MediaSessionCommand.REWIND_15S) else super.seekBack()
    }

    override fun seekToPreviousMediaItem() {
        seekToPrevious()
    }

    override fun seekToNext() {
        bridge.activeCallbacks()?.onMediaCommand(MediaSessionCommand.SKIP_TO_NEXT)
    }

    override fun seekToNextMediaItem() {
        seekToNext()
    }

    override fun seekTo(positionMs: Long) {
        val cb = bridge.activeCallbacks()
        if (cb != null) cb.onSeekToMs(positionMs) else super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        seekTo(positionMs)
    }

    override fun setPlaybackSpeed(speed: Float) {
        val cb = bridge.activeCallbacks()
        if (cb != null && speed > 0f) {
            cb.onSetPlaybackSpeed(speed)
        } else {
            super.setPlaybackSpeed(speed)
        }
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        setPlaybackSpeed(playbackParameters.speed)
    }

    override fun stop() {
        val cb = bridge.activeCallbacks()
        if (cb != null) cb.onMediaCommand(MediaSessionCommand.STOP) else super.stop()
    }
}

class PodcastPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val graph = (application as? RocheApp)?.graph ?: return
        val bridge = graph.mediaSessionBridge as? AndroidPodcastMediaSessionBridge ?: return
        val exoPlayer = graph.audioPlayerBackend.getOrCreateExoPlayerOnMain()
        val forwardingPlayer = RocheSessionForwardingPlayer(exoPlayer, bridge)
        val hasNext = bridge.currentSnapshot()?.hasNextQueuedAudio == true
        val session =
            MediaSession.Builder(this, forwardingPlayer)
                .setId(SESSION_ID)
                .setSessionActivity(bridge.createOpenPlayerPendingIntent())
                .setMediaButtonPreferences(
                    AndroidPodcastMediaSessionBridge.buildTransportButtons(hasNext)
                )
                .build()
        mediaSession = session
        bridge.attachServiceSessionOnMain(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        val bridge =
            (application as? RocheApp)?.graph?.mediaSessionBridge
                as? AndroidPodcastMediaSessionBridge
        mediaSession?.let { session ->
            bridge?.detachServiceSessionOnMain(session)
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val SESSION_ID = "RochePodcastMedia3Session"
        const val ACTION_OPEN_PLAYER = "com.grayvines.roche.action.OPEN_PLAYER"
        const val EXTRA_OPEN_FULLSCREEN_PLAYER = "extra_open_fullscreen_player"
    }
}
