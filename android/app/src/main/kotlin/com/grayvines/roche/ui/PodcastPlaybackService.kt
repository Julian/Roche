@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import com.grayvines.roche.MainActivity
import com.grayvines.roche.RocheApp

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
)

internal data class NotificationContentKey(
    val entryId: String,
    val episodeTitle: String,
    val podcastTitle: String,
    val playbackSpeed: Float,
    val isPlaying: Boolean,
    val hasNextQueuedAudio: Boolean,
)

internal fun MediaSessionMetadataSnapshot.toMetadataKey(): SessionMetadataKey =
    SessionMetadataKey(
        entryId = entryId,
        episodeTitle = episodeTitle,
        podcastTitle = podcastTitle,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
    )

internal fun MediaSessionMetadataSnapshot.toNotificationKey(): NotificationContentKey =
    NotificationContentKey(
        entryId = entryId,
        episodeTitle = episodeTitle,
        podcastTitle = podcastTitle,
        playbackSpeed = playbackSpeed,
        isPlaying = isPlaying,
        hasNextQueuedAudio = hasNextQueuedAudio,
    )

class AndroidPodcastMediaSessionBridge(context: Context) : PodcastMediaSessionBridge {
    private val appContext: Context = context.applicationContext
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val notificationManager: NotificationManager? =
        appContext.getSystemService(NotificationManager::class.java)

    @Volatile private var callbacks: PodcastMediaSessionCallbacks? = null
    @Volatile private var latestSnapshot: MediaSessionMetadataSnapshot? = null
    @Volatile private var lastMetadataKey: SessionMetadataKey? = null
    @Volatile private var lastNotificationKey: NotificationContentKey? = null
    @Volatile private var serviceRunningInForeground: Boolean = false
    @Volatile private var pendingForegroundStart: Boolean = false
    @Volatile private var inServiceCommand: Boolean = false
    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var noisyReceiverRegistered: Boolean = false
    private var dismissReceiverRegistered: Boolean = false

    private val becomingNoisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    callbacks?.onAudioBecomingNoisy()
                }
            }
        }

    private val notificationDismissReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == PodcastPlaybackService.ACTION_DISMISS_NOTIFICATION) {
                    callbacks?.onMediaCommand(MediaSessionCommand.STOP)
                }
            }
        }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> callbacks?.onAudioFocusGained()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                callbacks?.onAudioFocusLost(transient = true)
            AudioManager.AUDIOFOCUS_LOSS -> callbacks?.onAudioFocusLost(transient = false)
        }
    }

    override fun bindCallbacks(callbacks: PodcastMediaSessionCallbacks) {
        this.callbacks = callbacks
    }

    override fun unbindCallbacks(callbacks: PodcastMediaSessionCallbacks) {
        if (this.callbacks === callbacks) {
            this.callbacks = null
            release()
        }
    }

    internal fun currentSnapshot(): MediaSessionMetadataSnapshot? = latestSnapshot

    internal fun markServiceForegroundStarted() {
        pendingForegroundStart = false
        serviceRunningInForeground = true
    }

    internal fun markServiceStopped() {
        pendingForegroundStart = false
        serviceRunningInForeground = false
    }

    override fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return true
        val request =
            audioFocusRequest
                ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                    .build()
                    .also { audioFocusRequest = it }
        val res = runCatching { manager.requestAudioFocus(request) }.getOrNull()
        return res == null || res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonAudioFocus() {
        val manager = audioManager ?: return
        val request = audioFocusRequest ?: return
        runCatching { manager.abandonAudioFocusRequest(request) }
    }

    override fun updatePlaybackSession(snapshot: MediaSessionMetadataSnapshot?) {
        latestSnapshot = snapshot
        if (snapshot == null) {
            lastMetadataKey = null
            lastNotificationKey = null
            unregisterNoisyReceiver()
            unregisterDismissReceiver()
            runCatching { mediaSession?.isActive = false }
            if (!inServiceCommand) {
                stopPlaybackService()
            }
            return
        }
        val session = ensureMediaSession()
        registerDismissReceiver()
        syncNoisyReceiver(snapshot.isPlaying)
        session.isActive = true
        val metadataKey = snapshot.toMetadataKey()
        if (metadataKey != lastMetadataKey) {
            lastMetadataKey = metadataKey
            session.setMetadata(buildMediaMetadata(snapshot))
        }
        session.setPlaybackState(buildPlaybackState(snapshot))
        if (!inServiceCommand) {
            syncForegroundService(snapshot)
        }
    }

    override fun release() {
        unregisterNoisyReceiver()
        unregisterDismissReceiver()
        abandonAudioFocus()
        latestSnapshot = null
        lastMetadataKey = null
        lastNotificationKey = null
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null
        stopPlaybackService()
    }

    internal fun dispatchActionFromSession(action: String?) {
        executeActionCommand(action)
    }

    internal fun dispatchServiceAction(action: String?) {
        inServiceCommand = true
        try {
            executeActionCommand(action)
        } finally {
            inServiceCommand = false
        }
    }

    private fun executeActionCommand(action: String?) {
        val target = callbacks ?: return
        when (action) {
            PodcastPlaybackService.ACTION_PLAY -> target.onMediaCommand(MediaSessionCommand.PLAY)
            PodcastPlaybackService.ACTION_PAUSE -> target.onMediaCommand(MediaSessionCommand.PAUSE)
            PodcastPlaybackService.ACTION_TOGGLE_PLAY_PAUSE ->
                target.onMediaCommand(MediaSessionCommand.TOGGLE_PLAY_PAUSE)
            PodcastPlaybackService.ACTION_REWIND_15 ->
                target.onMediaCommand(MediaSessionCommand.REWIND_15S)
            PodcastPlaybackService.ACTION_FF_30 ->
                target.onMediaCommand(MediaSessionCommand.FAST_FORWARD_30S)
            PodcastPlaybackService.ACTION_SKIP_NEXT ->
                target.onMediaCommand(MediaSessionCommand.SKIP_TO_NEXT)
            PodcastPlaybackService.ACTION_STOP -> target.onMediaCommand(MediaSessionCommand.STOP)
        }
    }

    internal fun buildMediaNotification(): Notification? {
        val snapshot = latestSnapshot ?: return null
        val session = ensureMediaSession()
        ensureNotificationChannel()
        val smallIcon =
            if (snapshot.isPlaying) {
                android.R.drawable.ic_media_play
            } else {
                android.R.drawable.ic_media_pause
            }
        val style =
            Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)

        val builder =
            Notification.Builder(appContext, PodcastPlaybackService.CHANNEL_ID)
                .setSmallIcon(smallIcon)
                .setContentTitle(snapshot.episodeTitle.ifBlank { "Podcast Episode" })
                .setContentText(snapshot.podcastTitle.ifBlank { "Roche Podcasts" })
                .setSubText(formatPlaybackSpeedLabel(snapshot.playbackSpeed))
                .setContentIntent(createOpenPlayerPendingIntent())
                .setDeleteIntent(createDismissPendingIntent())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(snapshot.isPlaying)
                .setStyle(style)
        attachTransportActions(builder, snapshot)
        return builder.build()
    }

    private fun attachTransportActions(
        builder: Notification.Builder,
        snapshot: MediaSessionMetadataSnapshot,
    ) {
        val playPauseIcon =
            if (snapshot.isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
        val playPauseLabel = if (snapshot.isPlaying) "Pause" else "Play"
        val playPauseAction =
            if (snapshot.isPlaying) {
                PodcastPlaybackService.ACTION_PAUSE
            } else {
                PodcastPlaybackService.ACTION_PLAY
            }
        builder
            .addAction(
                buildNotificationAction(
                    iconRes = android.R.drawable.ic_media_rew,
                    title = "Rewind 15s",
                    action = PodcastPlaybackService.ACTION_REWIND_15,
                    requestCode = 1,
                )
            )
            .addAction(
                buildNotificationAction(
                    iconRes = playPauseIcon,
                    title = playPauseLabel,
                    action = playPauseAction,
                    requestCode = 2,
                )
            )
            .addAction(
                buildNotificationAction(
                    iconRes = android.R.drawable.ic_media_ff,
                    title = "Fast-forward 30s",
                    action = PodcastPlaybackService.ACTION_FF_30,
                    requestCode = 3,
                )
            )
        if (snapshot.hasNextQueuedAudio) {
            builder.addAction(
                buildNotificationAction(
                    iconRes = android.R.drawable.ic_media_next,
                    title = "Next episode",
                    action = PodcastPlaybackService.ACTION_SKIP_NEXT,
                    requestCode = 4,
                )
            )
        }
    }

    private fun buildNotificationAction(
        iconRes: Int,
        title: String,
        action: String,
        requestCode: Int,
    ): Notification.Action =
        Notification.Action.Builder(
                Icon.createWithResource(appContext, iconRes),
                title,
                serviceActionPendingIntent(action, requestCode),
            )
            .build()

    private fun createOpenPlayerPendingIntent(): PendingIntent =
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

    private fun createDismissPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            REQUEST_DISMISS_NOTIFICATION,
            Intent(PodcastPlaybackService.ACTION_DISMISS_NOTIFICATION).apply {
                setPackage(appContext.packageName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureMediaSession(): MediaSession {
        mediaSession?.let {
            return it
        }
        ensureNotificationChannel()
        val session = MediaSession(appContext, "RochePodcastSession")
        session.setSessionActivity(createOpenPlayerPendingIntent())
        val callback = createMediaSessionCallback()
        runCatching { session.setCallback(callback, mainHandler) }
            .onFailure { session.setCallback(callback) }
        mediaSession = session
        return session
    }

    private fun createMediaSessionCallback(): MediaSession.Callback =
        object : MediaSession.Callback() {
            override fun onPlay() {
                callbacks?.onMediaCommand(MediaSessionCommand.PLAY)
            }

            override fun onPause() {
                callbacks?.onMediaCommand(MediaSessionCommand.PAUSE)
            }

            override fun onSeekTo(pos: Long) {
                callbacks?.onSeekToMs(pos)
            }

            override fun onRewind() {
                callbacks?.onMediaCommand(MediaSessionCommand.REWIND_15S)
            }

            override fun onSkipToPrevious() {
                callbacks?.onMediaCommand(MediaSessionCommand.REWIND_15S)
            }

            override fun onFastForward() {
                callbacks?.onMediaCommand(MediaSessionCommand.FAST_FORWARD_30S)
            }

            override fun onSkipToNext() {
                callbacks?.onMediaCommand(MediaSessionCommand.SKIP_TO_NEXT)
            }

            override fun onSetPlaybackSpeed(speed: Float) {
                if (speed > 0f) {
                    callbacks?.onSetPlaybackSpeed(speed)
                }
            }

            override fun onStop() {
                callbacks?.onMediaCommand(MediaSessionCommand.STOP)
            }

            override fun onCustomAction(action: String, extras: Bundle?) {
                dispatchActionFromSession(action)
            }

            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val keyEvent =
                    mediaButtonIntent.getParcelableExtra(
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent::class.java,
                    ) ?: return super.onMediaButtonEvent(mediaButtonIntent)
                val cmd =
                    mapMediaKeyCodeToCommand(keyEvent.keyCode)
                        ?: return super.onMediaButtonEvent(mediaButtonIntent)
                if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                    callbacks?.onMediaCommand(cmd)
                }
                return true
            }
        }

    private fun buildMediaMetadata(snapshot: MediaSessionMetadataSnapshot): MediaMetadata {
        val builder =
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, snapshot.entryId)
                .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.episodeTitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, snapshot.episodeTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.podcastTitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, snapshot.podcastTitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, snapshot.podcastTitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs)
        snapshot.artworkUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { artUri ->
                builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, artUri)
                builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, artUri)
            }
        return builder.build()
    }

    private fun buildPlaybackState(snapshot: MediaSessionMetadataSnapshot): PlaybackState {
        val stateCode =
            when {
                snapshot.isBuffering && snapshot.isPlaying -> PlaybackState.STATE_BUFFERING
                snapshot.isPlaying -> PlaybackState.STATE_PLAYING
                else -> PlaybackState.STATE_PAUSED
            }
        val effectiveSpeed = if (snapshot.isPlaying) snapshot.playbackSpeed else 0f
        // ACTION_SKIP_TO_PREVIOUS is intentionally omitted from PlaybackState actions so
        // Android 13+ System Media Controls do not display both the reserved Previous slot and
        // our custom ACTION_REWIND_15 button side-by-side; hardware/Bluetooth Previous keys are
        // still handled via onMediaButtonEvent and onSkipToPrevious().
        var actions =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_SET_PLAYBACK_SPEED or
                PlaybackState.ACTION_STOP
        if (snapshot.hasNextQueuedAudio) {
            actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        }
        return PlaybackState.Builder()
            .setActions(actions)
            .setState(
                stateCode,
                snapshot.positionMs,
                effectiveSpeed,
                SystemClock.elapsedRealtime(),
            )
            .addCustomAction(
                PodcastPlaybackService.ACTION_REWIND_15,
                "Rewind 15s",
                android.R.drawable.ic_media_rew,
            )
            .addCustomAction(
                PodcastPlaybackService.ACTION_FF_30,
                "Fast-forward 30s",
                android.R.drawable.ic_media_ff,
            )
            .build()
    }

    private fun syncNoisyReceiver(isPlaying: Boolean) {
        if (isPlaying && !noisyReceiverRegistered) {
            runCatching {
                appContext.registerReceiver(
                    becomingNoisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    Context.RECEIVER_NOT_EXPORTED,
                )
                noisyReceiverRegistered = true
            }
        } else if (!isPlaying && noisyReceiverRegistered) {
            unregisterNoisyReceiver()
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        noisyReceiverRegistered = false
        runCatching { appContext.unregisterReceiver(becomingNoisyReceiver) }
    }

    private fun registerDismissReceiver() {
        if (dismissReceiverRegistered) return
        runCatching {
            appContext.registerReceiver(
                notificationDismissReceiver,
                IntentFilter(PodcastPlaybackService.ACTION_DISMISS_NOTIFICATION),
                Context.RECEIVER_NOT_EXPORTED,
            )
            dismissReceiverRegistered = true
        }
    }

    private fun unregisterDismissReceiver() {
        if (!dismissReceiverRegistered) return
        dismissReceiverRegistered = false
        runCatching { appContext.unregisterReceiver(notificationDismissReceiver) }
    }

    private fun syncForegroundService(snapshot: MediaSessionMetadataSnapshot) {
        runCatching {
            val notifKey = snapshot.toNotificationKey()
            val notificationChanged = notifKey != lastNotificationKey
            lastNotificationKey = notifKey
            if (snapshot.isPlaying) {
                syncPlayingForegroundService(notificationChanged)
            } else {
                syncPausedForegroundService(notificationChanged)
            }
        }
    }

    private fun syncPlayingForegroundService(notificationChanged: Boolean) {
        if (!serviceRunningInForeground && !pendingForegroundStart) {
            pendingForegroundStart = true
            val intent =
                Intent(appContext, PodcastPlaybackService::class.java).apply {
                    action = PodcastPlaybackService.ACTION_SYNC_NOTIFICATION
                    putExtra(PodcastPlaybackService.EXTRA_STARTED_FOREGROUND, true)
                }
            runCatching { appContext.startForegroundService(intent) }
                .onFailure {
                    pendingForegroundStart = false
                    lastNotificationKey = null
                }
        } else if (notificationChanged && serviceRunningInForeground) {
            postMediaNotificationIfPresent()
        }
    }

    private fun syncPausedForegroundService(notificationChanged: Boolean) {
        if (serviceRunningInForeground || pendingForegroundStart) {
            val intent =
                Intent(appContext, PodcastPlaybackService::class.java).apply {
                    action = PodcastPlaybackService.ACTION_DEMOTE_FOREGROUND
                }
            runCatching { appContext.startService(intent) }
                .onFailure {
                    pendingForegroundStart = false
                    serviceRunningInForeground = false
                    runCatching {
                        appContext.stopService(
                            Intent(appContext, PodcastPlaybackService::class.java)
                        )
                    }
                    postMediaNotificationIfPresent()
                }
        } else if (notificationChanged) {
            postMediaNotificationIfPresent()
        }
    }

    private fun postMediaNotificationIfPresent() {
        buildMediaNotification()?.let { notification ->
            notificationManager?.notify(PodcastPlaybackService.NOTIFICATION_ID, notification)
        }
    }

    private fun stopPlaybackService() {
        runCatching {
            lastNotificationKey = null
            notificationManager?.cancel(PodcastPlaybackService.NOTIFICATION_ID)
            if (serviceRunningInForeground || pendingForegroundStart) {
                val intent =
                    Intent(appContext, PodcastPlaybackService::class.java).apply {
                        action = PodcastPlaybackService.ACTION_STOP_SERVICE_SAFE
                    }
                runCatching { appContext.startService(intent) }
                    .onFailure {
                        pendingForegroundStart = false
                        serviceRunningInForeground = false
                        appContext.stopService(
                            Intent(appContext, PodcastPlaybackService::class.java)
                        )
                    }
            } else {
                appContext.stopService(Intent(appContext, PodcastPlaybackService::class.java))
            }
        }
    }

    internal fun ensureNotificationChannel() {
        val nm = notificationManager ?: return
        if (nm.getNotificationChannel(PodcastPlaybackService.CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                    PodcastPlaybackService.CHANNEL_ID,
                    "Podcast Playback",
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = "Lock-screen and background playback controls for podcasts"
                    setShowBadge(false)
                }
        nm.createNotificationChannel(channel)
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val startsPlayback =
            action == PodcastPlaybackService.ACTION_PLAY ||
                action == PodcastPlaybackService.ACTION_TOGGLE_PLAY_PAUSE ||
                action == PodcastPlaybackService.ACTION_SKIP_NEXT
        val intent =
            Intent(appContext, PodcastPlaybackService::class.java).apply {
                setAction(action)
                putExtra(PodcastPlaybackService.EXTRA_STARTED_FOREGROUND, startsPlayback)
            }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (startsPlayback) {
            PendingIntent.getForegroundService(appContext, requestCode, intent, flags)
        } else {
            PendingIntent.getService(appContext, requestCode, intent, flags)
        }
    }

    private companion object {
        private const val REQUEST_OPEN_PLAYER = 400
        private const val REQUEST_DISMISS_NOTIFICATION = 401
    }
}

class PodcastPlaybackService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bridge =
            (application as? RocheApp)?.graph?.mediaSessionBridge
                as? AndroidPodcastMediaSessionBridge
        val mustCallStartForeground =
            intent?.getBooleanExtra(EXTRA_STARTED_FOREGROUND, false) == true
        val action = intent?.action
        if (action != null && !isInternalLifecycleAction(action)) {
            bridge?.dispatchServiceAction(action)
        }
        val snapshot = bridge?.currentSnapshot()
        val notification = bridge?.buildMediaNotification()
        if (mustCallStartForeground) {
            bridge?.ensureNotificationChannel()
            val fgNotification = notification ?: buildFallbackNotification()
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    fgNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            }
        }
        if (snapshot == null || notification == null) {
            bridge?.markServiceStopped()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            stopSelf()
            return START_NOT_STICKY
        }
        if (snapshot.isPlaying) {
            bridge.markServiceForegroundStarted()
            acquireWakeLock()
            if (!mustCallStartForeground) {
                runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                }
            }
        } else {
            bridge.markServiceStopped()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun isInternalLifecycleAction(action: String): Boolean =
        action == ACTION_SYNC_NOTIFICATION ||
            action == ACTION_DEMOTE_FOREGROUND ||
            action == ACTION_STOP_SERVICE_SAFE

    private fun buildFallbackNotification(): Notification =
        Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("Roche Podcasts")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .build()

    override fun onDestroy() {
        val bridge =
            (application as? RocheApp)?.graph?.mediaSessionBridge
                as? AndroidPodcastMediaSessionBridge
        bridge?.markServiceStopped()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val current = wakeLock
        if (current != null && current.isHeld) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Roche:PodcastPlaybackWakeLock").apply {
                setReferenceCounted(false)
                runCatching { acquire(MAX_WAKE_LOCK_DURATION_MS) }
            }
    }

    private fun releaseWakeLock() {
        val current = wakeLock ?: return
        wakeLock = null
        if (current.isHeld) {
            runCatching { current.release() }
        }
    }

    companion object {
        const val CHANNEL_ID = "roche_podcast_playback"
        const val NOTIFICATION_ID = 4201
        const val ACTION_SYNC_NOTIFICATION = "com.grayvines.roche.action.SYNC_NOTIFICATION"
        const val ACTION_DEMOTE_FOREGROUND = "com.grayvines.roche.action.DEMOTE_FOREGROUND"
        const val ACTION_STOP_SERVICE_SAFE = "com.grayvines.roche.action.STOP_SERVICE_SAFE"
        const val ACTION_OPEN_PLAYER = "com.grayvines.roche.action.OPEN_PLAYER"
        const val ACTION_PLAY = "com.grayvines.roche.action.PLAY"
        const val ACTION_PAUSE = "com.grayvines.roche.action.PAUSE"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.grayvines.roche.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_REWIND_15 = "com.grayvines.roche.action.REWIND_15"
        const val ACTION_FF_30 = "com.grayvines.roche.action.FF_30"
        const val ACTION_SKIP_NEXT = "com.grayvines.roche.action.SKIP_NEXT"
        const val ACTION_STOP = "com.grayvines.roche.action.STOP"
        const val ACTION_DISMISS_NOTIFICATION = "com.grayvines.roche.action.DISMISS_NOTIFICATION"
        const val EXTRA_STARTED_FOREGROUND = "extra_started_foreground"
        const val EXTRA_OPEN_FULLSCREEN_PLAYER = "extra_open_fullscreen_player"
        private const val MAX_WAKE_LOCK_DURATION_MS = 4L * 60L * 60L * 1_000L
    }
}
