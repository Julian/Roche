package com.grayvines.roche

import android.app.Application
import com.grayvines.roche.data.sync.PeerSyncHost
import kotlinx.coroutines.CoroutineScope

class RocheApp : Application(), PeerSyncHost {
    val graph: AppGraph by lazy { AppGraph(this) }

    override val peerSyncScope: CoroutineScope
        get() = graph.appScope

    override suspend fun runBackgroundPeerSyncAndPoll() {
        graph.repository.runBackgroundPeerSyncAndPoll()
    }

    override fun onCreate() {
        super.onCreate()
        graph.prewarm()
    }
}
