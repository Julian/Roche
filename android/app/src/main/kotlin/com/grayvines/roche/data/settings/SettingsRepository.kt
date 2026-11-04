package com.grayvines.roche.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<Settings> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { prefs ->
                Settings(
                        serverUrl = prefs[KEY_SERVER_URL] ?: Settings.DEFAULT_SERVER_URL,
                        bearerToken = prefs[KEY_BEARER_TOKEN].orEmpty(),
                        swipeToArchive = prefs[KEY_SWIPE_TO_ARCHIVE] ?: true,
                        themeMode =
                            prefs[KEY_THEME_MODE]?.let { raw ->
                                ThemeMode.entries.firstOrNull { it.name == raw }
                            } ?: ThemeMode.DARK,
                        headlineFont =
                            prefs[KEY_HEADLINE_FONT]?.let { raw ->
                                HeadlineFont.entries.firstOrNull { it.name == raw }
                            } ?: HeadlineFont.SERIF,
                        ultraDenseRows = prefs[KEY_ULTRA_DENSE_ROWS] ?: false,
                    )
                    .clamped()
            }

    suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { prefs ->
            val current =
                Settings(
                        serverUrl = prefs[KEY_SERVER_URL] ?: Settings.DEFAULT_SERVER_URL,
                        bearerToken = prefs[KEY_BEARER_TOKEN].orEmpty(),
                        swipeToArchive = prefs[KEY_SWIPE_TO_ARCHIVE] ?: true,
                        themeMode =
                            prefs[KEY_THEME_MODE]?.let { raw ->
                                ThemeMode.entries.firstOrNull { it.name == raw }
                            } ?: ThemeMode.DARK,
                        headlineFont =
                            prefs[KEY_HEADLINE_FONT]?.let { raw ->
                                HeadlineFont.entries.firstOrNull { it.name == raw }
                            } ?: HeadlineFont.SERIF,
                        ultraDenseRows = prefs[KEY_ULTRA_DENSE_ROWS] ?: false,
                    )
                    .clamped()
            val updated = transform(current).clamped()
            prefs[KEY_SERVER_URL] = updated.serverUrl
            prefs[KEY_BEARER_TOKEN] = updated.bearerToken
            prefs[KEY_SWIPE_TO_ARCHIVE] = updated.swipeToArchive
            prefs[KEY_THEME_MODE] = updated.themeMode.name
            prefs[KEY_HEADLINE_FONT] = updated.headlineFont.name
            prefs[KEY_ULTRA_DENSE_ROWS] = updated.ultraDenseRows
        }
    }

    companion object {
        private const val STORE_NAME = "roche_settings"
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_BEARER_TOKEN = stringPreferencesKey("bearer_token")
        private val KEY_SWIPE_TO_ARCHIVE = booleanPreferencesKey("swipe_to_archive")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_HEADLINE_FONT = stringPreferencesKey("headline_font")
        private val KEY_ULTRA_DENSE_ROWS = booleanPreferencesKey("ultra_dense_rows")

        fun create(context: Context): SettingsRepository {
            val appContext = context.applicationContext
            val store =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    produceFile = { appContext.preferencesDataStoreFile(STORE_NAME) },
                )
            return SettingsRepository(store)
        }
    }
}
