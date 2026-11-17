package com.grayvines.roche.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.SeedData
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.collectInBackground(flow: Flow<*>): Job =
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect() }

class TestRocheEnv(tempDir: File, prefsFileName: String = "test_settings.preferences_pb") {
    val database: RocheDatabase =
        Room.inMemoryDatabaseBuilder<RocheDatabase>().setDriver(BundledSQLiteDriver()).build()
    val dataStoreScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings: SettingsRepository =
        SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(tempDir, prefsFileName) },
            )
        )
    val repository: RocheRepository =
        RocheRepository(
            database = database,
            apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") },
            settings = settings,
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
