package com.grayvines.roche

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import com.grayvines.roche.data.AndroidNetworkTierProvider
import com.grayvines.roche.data.NetworkTierProvider
import com.grayvines.roche.data.PeerSyncScheduler
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.remote.HttpUrlConnectionTransport
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.remote.VpnTransportCheck
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.ui.AndroidPodcastMediaSessionBridge
import com.grayvines.roche.ui.ExoPlayerAudioBackend
import com.grayvines.roche.ui.PodcastMediaSessionBridge
import com.grayvines.roche.ui.PodcastPlayerController
import com.grayvines.roche.ui.components.ThumbnailLoader
import com.grayvines.roche.ui.createManagedPlayerController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: RocheDatabase by lazy { RocheDatabase.create(appContext) }

    val settings: SettingsRepository by lazy { SettingsRepository.create(appContext) }

    val networkTierProvider: NetworkTierProvider by lazy {
        AndroidNetworkTierProvider(appContext)
    }

    val apiClient: RocheApiClient by lazy {
        val vpnCheck = VpnTransportCheck(networkTierProvider::isVpnTransportActive)
        RocheApiClient(
            vpnCheck = vpnCheck,
            transport = HttpUrlConnectionTransport(vpnCheck = vpnCheck),
        )
    }

    val offlineCacheDir: File by lazy { File(appContext.filesDir, "offline_media") }

    val repository: RocheRepository by lazy {
        RocheRepository(
            database = database,
            apiClient = apiClient,
            settings = settings,
            cacheDir = offlineCacheDir,
            networkTierProvider = networkTierProvider,
            backgroundScope = appScope,
            poolStreamProvider = {
                runCatching { appContext.assets.open("albums_1001_pool.json") }.getOrNull()
            },
        )
    }

    val thumbnailLoader: ThumbnailLoader by lazy {
        ThumbnailLoader(
            apiClient = apiClient,
            settings = settings,
            cacheDir = offlineCacheDir,
        )
    }

    val audioPlayerBackend: ExoPlayerAudioBackend by lazy {
        ExoPlayerAudioBackend(
            context = appContext,
            allowedLocalDirs = listOf(offlineCacheDir, appContext.cacheDir),
        )
    }

    val mediaSessionBridge: PodcastMediaSessionBridge by lazy {
        AndroidPodcastMediaSessionBridge(
            context = appContext,
            backend = audioPlayerBackend,
            scope = appScope,
            artworkBitmapLoader = { url -> thumbnailLoader.loadThumbnail(url)?.asAndroidBitmap() },
        )
    }

    val playerController: PodcastPlayerController by lazy {
        createManagedPlayerController(
            scope = appScope,
            repository = repository,
            settingsRepository = settings,
            playerBackend = audioPlayerBackend,
            mediaSessionBridge = mediaSessionBridge,
        )
    }

    fun prewarm() {
        appScope.launch {
            settings.prewarm()
            val current = settings.settings.first()
            PeerSyncScheduler.schedulePeriodicSync(appContext, current.serverUrl)
        }
        appScope.launch { repository.prewarm() }
    }
}
