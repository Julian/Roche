package com.grayvines.roche.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.grayvines.roche.data.runSuspendCatching
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    private val fallbackPeerId = "android-${UUID.randomUUID()}"

    val settings: Flow<Settings> =
        dataStore.data
            .onEach { prefs -> ensurePeerIdPersisted(prefs) }
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { prefs -> prefs.toSettings() }

    private suspend fun ensurePeerIdPersisted(prefs: Preferences) {
        if (!prefs[KEY_PEER_ID].isNullOrBlank()) return
        runSuspendCatching {
            dataStore.edit { mutablePrefs ->
                if (mutablePrefs[KEY_PEER_ID].isNullOrBlank()) {
                    mutablePrefs[KEY_PEER_ID] = fallbackPeerId
                }
            }
        }
    }

    suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { prefs ->
            val current = prefs.toSettings()
            val transformed = transform(current).clamped()
            val credentialsChanged =
                transformed.serverUrl != current.serverUrl ||
                    transformed.bearerToken != current.bearerToken
            val updated =
                if (
                    credentialsChanged &&
                        transformed.lastPeerSyncCursor == current.lastPeerSyncCursor
                ) {
                    transformed.copy(lastPeerSyncCursor = "")
                } else {
                    transformed
                }
            prefs[KEY_SERVER_URL] = updated.serverUrl
            prefs[KEY_BEARER_TOKEN] = updated.bearerToken
            prefs[KEY_PEER_ID] = updated.peerId
            prefs[KEY_DEVICE_NAME] = updated.deviceName
            prefs[KEY_SWIPE_TO_ARCHIVE] = updated.swipeToArchive
            prefs[KEY_THEME_MODE] = updated.themeMode.name
            prefs[KEY_HEADLINE_FONT] = updated.headlineFont.name
            prefs[KEY_ULTRA_DENSE_ROWS] = updated.ultraDenseRows
            prefs[KEY_READER_FONT_SCALE] = updated.readerFontScale
            prefs[KEY_BOTTOM_NAV_TABS] = updated.bottomNavTabs.joinToString(",") { it.name }
            prefs[KEY_LAST_PEER_SYNC_CURSOR] = updated.lastPeerSyncCursor
            prefs[KEY_QUEUE_ITEM_IDS] = updated.queueItemIds.joinToString(",")
            prefs[KEY_DEFAULT_PLAYBACK_SPEED] = updated.defaultPlaybackSpeed
            prefs[KEY_SKIP_SILENCE_ENABLED] = updated.skipSilenceEnabled
            prefs[KEY_SMART_REWIND_ENABLED] = updated.smartRewindEnabled
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun Preferences.toSettings(): Settings =
        Settings(
                serverUrl = this[KEY_SERVER_URL] ?: Settings.DEFAULT_SERVER_URL,
                bearerToken = this[KEY_BEARER_TOKEN].orEmpty(),
                peerId = this[KEY_PEER_ID]?.takeIf { it.isNotBlank() } ?: fallbackPeerId,
                deviceName =
                    this[KEY_DEVICE_NAME]?.takeIf { it.isNotBlank() }
                        ?: Settings.DEFAULT_DEVICE_NAME,
                swipeToArchive = this[KEY_SWIPE_TO_ARCHIVE] ?: true,
                themeMode =
                    this[KEY_THEME_MODE]?.let { raw ->
                        ThemeMode.entries.firstOrNull { it.name == raw }
                    } ?: ThemeMode.DARK,
                headlineFont =
                    this[KEY_HEADLINE_FONT]?.let { raw ->
                        HeadlineFont.entries.firstOrNull { it.name == raw }
                    } ?: HeadlineFont.SERIF,
                ultraDenseRows = this[KEY_ULTRA_DENSE_ROWS] ?: false,
                readerFontScale = this[KEY_READER_FONT_SCALE] ?: Settings.DEFAULT_READER_FONT_SCALE,
                bottomNavTabs =
                    this[KEY_BOTTOM_NAV_TABS]?.let { raw ->
                        raw.split(',')
                            .mapNotNull { token ->
                                AppTab.entries.firstOrNull { it.name == token.trim() }
                            }
                            .let { AppTab.normalizeSideTabs(it) }
                    } ?: AppTab.defaultSideTabs,
                lastPeerSyncCursor = this[KEY_LAST_PEER_SYNC_CURSOR].orEmpty(),
                queueItemIds =
                    this[KEY_QUEUE_ITEM_IDS]
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.distinct()
                        .orEmpty(),
                defaultPlaybackSpeed =
                    this[KEY_DEFAULT_PLAYBACK_SPEED] ?: Settings.DEFAULT_PLAYBACK_SPEED,
                skipSilenceEnabled = this[KEY_SKIP_SILENCE_ENABLED] ?: false,
                smartRewindEnabled = this[KEY_SMART_REWIND_ENABLED] ?: true,
            )
            .clamped()

    companion object {
        private const val STORE_NAME = "roche_settings"
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_BEARER_TOKEN = stringPreferencesKey("bearer_token")
        private val KEY_PEER_ID = stringPreferencesKey("peer_id")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_SWIPE_TO_ARCHIVE = booleanPreferencesKey("swipe_to_archive")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_HEADLINE_FONT = stringPreferencesKey("headline_font")
        private val KEY_ULTRA_DENSE_ROWS = booleanPreferencesKey("ultra_dense_rows")
        private val KEY_READER_FONT_SCALE = floatPreferencesKey("reader_font_scale")
        private val KEY_BOTTOM_NAV_TABS = stringPreferencesKey("bottom_nav_tabs")
        private val KEY_LAST_PEER_SYNC_CURSOR = stringPreferencesKey("last_peer_sync_cursor")
        private val KEY_QUEUE_ITEM_IDS = stringPreferencesKey("queue_item_ids")
        private val KEY_DEFAULT_PLAYBACK_SPEED = floatPreferencesKey("default_playback_speed")
        private val KEY_SKIP_SILENCE_ENABLED = booleanPreferencesKey("skip_silence_enabled")
        private val KEY_SMART_REWIND_ENABLED = booleanPreferencesKey("smart_rewind_enabled")

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
