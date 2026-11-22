package com.grayvines.roche

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import com.grayvines.roche.data.sync.PeerSyncHost
import com.grayvines.roche.playback.PodcastPlaybackHost
import com.grayvines.roche.playback.PodcastPlaybackService
import com.grayvines.roche.playback.PodcastPlayerController
import java.io.File
import kotlinx.coroutines.CoroutineScope

class RocheApp : Application(), PeerSyncHost, PodcastPlaybackHost {
    val graph: AppGraph by lazy { AppGraph(this) }

    override val peerSyncScope: CoroutineScope
        get() = graph.appScope

    override val allowedLocalAudioDirs: List<File>
        get() = graph.allowedLocalAudioDirs

    override val playbackController: PodcastPlayerController
        get() = graph.playerController

    override suspend fun runBackgroundPeerSyncAndPoll() {
        graph.repository.runBackgroundPeerSyncAndPoll()
    }

    override fun createOpenPlayerPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_OPEN_PLAYER,
            Intent(this, MainActivity::class.java).apply {
                action = PodcastPlaybackService.ACTION_OPEN_PLAYER
                putExtra(PodcastPlaybackService.EXTRA_OPEN_FULLSCREEN_PLAYER, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun onCreate() {
        super.onCreate()
        graph.prewarm()
    }

    private companion object {
        const val REQUEST_OPEN_PLAYER = 400
    }
}
