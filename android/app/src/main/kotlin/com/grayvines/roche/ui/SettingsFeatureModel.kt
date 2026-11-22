package com.grayvines.roche.ui

import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

internal class SettingsFeatureModel(private val settingsRepository: SettingsRepository) {
    val settingsForUi: Flow<Settings> =
        settingsRepository.settings
            .onStart { emit(settingsRepository.latestSettings ?: Settings()) }
            .distinctUntilChanged()

    private val _settingsHubResetTick = MutableStateFlow(0)
    val settingsHubResetTick: StateFlow<Int> = _settingsHubResetTick.asStateFlow()

    fun latestSettings(): Settings = settingsRepository.latestSettings ?: Settings()

    suspend fun readSavedBearerToken(): String = settingsRepository.readBearerToken()

    fun requestResetToHub() {
        _settingsHubResetTick.update { it + 1 }
    }
}
