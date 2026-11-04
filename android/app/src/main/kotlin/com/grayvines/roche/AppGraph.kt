package com.grayvines.roche

import android.content.Context
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.remote.HttpUrlConnectionTransport
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: RocheDatabase by lazy { RocheDatabase.create(appContext) }

    val settings: SettingsRepository by lazy { SettingsRepository.create(appContext) }

    val apiClient: RocheApiClient by lazy { RocheApiClient(HttpUrlConnectionTransport()) }

    val repository: RocheRepository by lazy {
        RocheRepository(database = database, apiClient = apiClient, settings = settings)
    }
}
