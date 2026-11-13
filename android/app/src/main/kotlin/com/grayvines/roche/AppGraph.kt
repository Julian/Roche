package com.grayvines.roche

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import com.grayvines.roche.data.AndroidNetworkTierProvider
import com.grayvines.roche.data.NetworkTierProvider
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.remote.HttpUrlConnectionTransport
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.ui.AndroidPodcastMediaSessionBridge
import com.grayvines.roche.ui.PodcastMediaSessionBridge
import com.grayvines.roche.ui.PodcastPlayerController
import com.grayvines.roche.ui.components.ThumbnailLoader
import com.grayvines.roche.ui.createManagedPlayerController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: RocheDatabase by lazy { RocheDatabase.create(appContext) }

    val settings: SettingsRepository by lazy { SettingsRepository.create(appContext) }

    val apiClient: RocheApiClient by lazy { RocheApiClient(HttpUrlConnectionTransport()) }

    val networkTierProvider: NetworkTierProvider by lazy {
        AndroidNetworkTierProvider(appContext)
    }

    val offlineCacheDir: File by lazy { File(appContext.filesDir, "offline_media") }

    val repository: RocheRepository by lazy {
        RocheRepository(
            database = database,
            apiClient = apiClient,
            settings = settings,
            cacheDir = offlineCacheDir,
            networkTierProvider = networkTierProvider,
        )
    }

    val thumbnailLoader: ThumbnailLoader by lazy {
        ThumbnailLoader(
            apiClient = apiClient,
            settings = settings,
            cacheDir = offlineCacheDir,
        )
    }

    val mediaSessionBridge: PodcastMediaSessionBridge by lazy {
        AndroidPodcastMediaSessionBridge(
            context = appContext,
            scope = appScope,
            artworkBitmapLoader = { url -> thumbnailLoader.loadThumbnail(url)?.asAndroidBitmap() },
        )
    }

    val playerController: PodcastPlayerController by lazy {
        createManagedPlayerController(
            scope = appScope,
            repository = repository,
            settingsRepository = settings,
            mediaSessionBridge = mediaSessionBridge,
        )
    }
}
