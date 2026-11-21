package com.grayvines.roche.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.grayvines.roche.data.enrichment.AlbumStreamingResolver
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.SeedData
import com.grayvines.roche.data.local.insertItemTags
import com.grayvines.roche.data.local.upsertFeeds
import com.grayvines.roche.data.local.upsertItems
import com.grayvines.roche.data.local.upsertTags
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.HttpTransport
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.RocheServerApi
import com.grayvines.roche.data.remote.VpnTransportCheck
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.sync.NetworkTierProvider
import com.grayvines.roche.media.DEFAULT_MAX_OFFLINE_CACHE_BYTES
import com.grayvines.roche.media.ThumbnailDiskCache
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

fun tailnetNetworkTierProvider(
    tierProvider: () -> NetworkTier = { NetworkTier.WIFI }
): NetworkTierProvider =
    object : NetworkTierProvider {
        override fun currentNetworkTier(): NetworkTier = tierProvider()

        override fun isVpnTransportActive(): Boolean = true
    }

fun tailnetServerApi(transport: HttpTransport): RocheServerApi =
    RocheServerApi(vpnCheck = VpnTransportCheck { true }, transport = transport)

@Suppress("LongParameterList")
fun mockRepository(
    database: RocheDatabase,
    settings: SettingsRepository,
    transport: HttpTransport,
    cacheDir: File? = null,
    networkTierProvider: NetworkTierProvider = tailnetNetworkTierProvider(),
    backgroundScope: CoroutineScope? = null,
    albumStreamingResolver: AlbumStreamingResolver? = null,
    thumbnailDiskCache: ThumbnailDiskCache = ThumbnailDiskCache(cacheDir),
    maxOfflineCacheBytes: Long = DEFAULT_MAX_OFFLINE_CACHE_BYTES,
): RocheRepository =
    RocheRepository(
        database = database,
        serverApi = tailnetServerApi(transport),
        publicWebFetcher = PublicWebFetcher(transport),
        settings = settings,
        cacheDir = cacheDir,
        networkTierProvider = networkTierProvider,
        backgroundScope = backgroundScope,
        albumStreamingResolver = albumStreamingResolver,
        thumbnailDiskCache = thumbnailDiskCache,
        maxOfflineCacheBytes = maxOfflineCacheBytes,
    )

@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.collectInBackground(flow: Flow<*>): Job =
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect() }

class TestRocheEnv(tempDir: File, prefsFileName: String = "test_settings.preferences_pb") {
    val database: RocheDatabase = RocheDatabase.createInMemory()
    val dataStoreScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings: SettingsRepository =
        SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(tempDir, prefsFileName) },
            )
        )
    val repository: RocheRepository =
        mockRepository(
            database = database,
            settings = settings,
            transport = { HttpResponse(statusCode = 200, body = "{}") },
        )

    suspend fun seedDefaultData() {
        val dao = database.dao()
        dao.upsertFeeds(SeedData.feeds)
        dao.upsertTags(SeedData.tags)
        dao.upsertItems(SeedData.items)
        dao.insertItemTags(SeedData.itemTags)
    }

    fun close() {
        runBlocking { dataStoreScope.coroutineContext[Job]?.cancelAndJoin() }
        database.close()
    }
}
