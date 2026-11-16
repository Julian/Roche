package com.grayvines.roche

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import com.grayvines.roche.data.AlbumStreamingResolver
import com.grayvines.roche.data.AndroidNetworkTierProvider
import com.grayvines.roche.data.NetworkTierProvider
import com.grayvines.roche.data.PeerSyncScheduler
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.remote.HttpEngineTransport
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppGraph(context: Context) : AutoCloseable {
    internal val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: RocheDatabase by lazy { RocheDatabase.create(appContext) }

    val settings: SettingsRepository by lazy { SettingsRepository.create(appContext) }

    private val networkTierProviderDelegate = lazy { AndroidNetworkTierProvider(appContext) }

    val networkTierProvider: NetworkTierProvider by networkTierProviderDelegate

    internal val vpnCheck: VpnTransportCheck by lazy {
        VpnTransportCheck(networkTierProvider::isVpnTransportActive)
    }

    private val httpTransportDelegate = lazy { HttpEngineTransport(appContext, vpnCheck) }

    internal val httpTransport: HttpEngineTransport by httpTransportDelegate

    val apiClient: RocheApiClient by lazy {
        RocheApiClient(
            vpnCheck = vpnCheck,
            transport = httpTransport,
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
            albumStreamingResolver = AlbumStreamingResolver(httpTransport),
        )
    }

    val thumbnailLoader: ThumbnailLoader by lazy {
        ThumbnailLoader(
            apiClient = apiClient,
            settings = settings,
            cacheDir = offlineCacheDir,
        )
    }

    private val audioPlayerBackendDelegate = lazy {
        ExoPlayerAudioBackend(
            context = appContext,
            allowedLocalDirs = listOf(offlineCacheDir, appContext.cacheDir),
            parentScope = appScope,
            httpEngineProvider = { httpTransport.sharedHttpEngine },
            httpEngineExecutorProvider = { httpTransport.sharedCallbackExecutor },
        )
    }

    val audioPlayerBackend: ExoPlayerAudioBackend by audioPlayerBackendDelegate

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
        networkTierProvider.isVpnTransportActive()
        appScope.launch {
            settings.prewarm()
            val current = settings.settings.first()
            PeerSyncScheduler.schedulePeriodicSync(appContext, current.serverUrl)
        }
        appScope.launch { repository.prewarm() }
    }

    override fun close() {
        if (audioPlayerBackendDelegate.isInitialized()) {
            runCatching { audioPlayerBackend.shutdownPlayer() }
        }
        appScope.cancel()
        if (httpTransportDelegate.isInitialized()) {
            runCatching { httpTransport.close() }
        }
        if (networkTierProviderDelegate.isInitialized()) {
            runCatching { networkTierProviderDelegate.value.close() }
        }
    }
}
