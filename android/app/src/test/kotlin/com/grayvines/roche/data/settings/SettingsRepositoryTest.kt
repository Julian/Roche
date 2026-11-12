package com.grayvines.roche.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SettingsRepositoryTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `settings normalize server URL and persist updates`() = runTest {
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store =
                PreferenceDataStoreFactory.create(
                    scope = dataStoreScope,
                    produceFile = { File(tempDir, "settings_test.preferences_pb") },
                )
            val repository = SettingsRepository(store)

            repository.update {
                it.copy(
                    serverUrl = "100.64.0.9:8080/",
                    bearerToken = "  secret-token  ",
                    swipeToArchive = false,
                    themeMode = ThemeMode.LIGHT,
                    headlineFont = HeadlineFont.SANS,
                    ultraDenseRows = true,
                )
            }

            val current = repository.settings.first()
            assertEquals("http://100.64.0.9:8080", current.serverUrl)
            assertEquals("secret-token", current.bearerToken)
            assertFalse(current.swipeToArchive)
            assertEquals(ThemeMode.LIGHT, current.themeMode)
            assertEquals(HeadlineFont.SANS, current.headlineFont)
            assertEquals(true, current.ultraDenseRows)

            repository.update { it.copy(lastPeerSyncCursor = "2026-06-01T12:01:00Z") }
            assertEquals("2026-06-01T12:01:00Z", repository.settings.first().lastPeerSyncCursor)

            repository.update { it.copy(bearerToken = "new-token") }
            assertEquals("", repository.settings.first().lastPeerSyncCursor)
        } finally {
            dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `normalizeServerUrl parsePairingInput and Tailscale candidates handle MagicDNS and bundles`() {
        assertEquals("http://roche:8080", normalizeServerUrl("roche"))
        assertEquals("http://roche:8080", normalizeServerUrl("roche:8080/"))
        assertEquals(
            "http://mac-mini.tail9876.ts.net:8080",
            normalizeServerUrl("mac-mini.tail9876.ts.net:8080"),
        )
        assertEquals(
            "http://airm.long-boa.ts.net:8080",
            normalizeServerUrl("https://airm.long-boa.ts.net:8080"),
        )
        assertEquals(
            "https://roche.tail9876.ts.net",
            normalizeServerUrl("roche.tail9876.ts.net"),
        )

        val (url, code) =
            parsePairingInput(
                rawServerUrl = "",
                rawPairingCode = "  http://roche.tail9876.ts.net:8080#1234-guitarist-revenue  ",
            )
        assertEquals("http://roche.tail9876.ts.net:8080", url)
        assertEquals("1234-guitarist-revenue", code)

        val candidates = tailscaleDiscoveryUrls(listOf("tail9876.ts.net.", "evil.com/path#@"))
        assertEquals(
            listOf(
                "https://roche.tail9876.ts.net",
                "http://roche.tail9876.ts.net:8080",
                "http://roche:8080",
                "https://roche",
            ),
            candidates,
        )
    }

    @Test
    fun `normalizeServerUrl and parsePairingInput reject adversarial URLs and malformed bundles`() {
        assertEquals("http://[::1]:8080", normalizeServerUrl("[::1]:8080"))
        assertEquals("", normalizeServerUrl("javascript:alert(1)"))
        assertEquals("", normalizeServerUrl("file:///etc/passwd"))
        assertEquals("", normalizeServerUrl("http://user:pass@100.64.0.1:8080"))
        assertEquals("", normalizeServerUrl("http://roche:8080/evil/path"))
        assertEquals("", normalizeServerUrl("http://roche:8080?query=1"))
        assertEquals("", normalizeServerUrl("http://roche:8080#frag"))

        assertEquals(
            "" to "",
            parsePairingInput("", "https://roche.tail9876.ts.net#evil#code"),
        )
        assertEquals(
            "" to "",
            parsePairingInput("", "javascript:alert(1)#1234-code"),
        )
    }

    @Test
    fun `peerId is generated once on first read and persisted across cold starts`() = runTest {
        val prefsFile = File(tempDir, "cold_start_peer_id.preferences_pb")

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store1 =
            PreferenceDataStoreFactory.create(
                scope = scope1,
                produceFile = { prefsFile },
            )
        val firstBootPeerId = SettingsRepository(store1).settings.first().peerId
        assertTrue(firstBootPeerId.startsWith("android-"))
        scope1.coroutineContext[Job]?.cancelAndJoin()

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store2 =
            PreferenceDataStoreFactory.create(
                scope = scope2,
                produceFile = { prefsFile },
            )
        val secondBootPeerId = SettingsRepository(store2).settings.first().peerId
        assertEquals(firstBootPeerId, secondBootPeerId)
        scope2.coroutineContext[Job]?.cancelAndJoin()
    }

    @Test
    fun `TokenCipher encrypts bearerToken at rest with distinct IVs and rejects tampering`() =
        runTest {
            val secretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val cipher = AesGcmSecretKeyTokenCipher(secretKey)

            val enc1 = cipher.encrypt("super-secret-bearer")
            val enc2 = cipher.encrypt("super-secret-bearer")
            assertTrue(enc1.startsWith("enc:v1:"))
            assertTrue(enc2.startsWith("enc:v1:"))
            assertFalse(enc1.contains("super-secret-bearer"))
            assertNotEquals(enc1.split(':')[2], enc2.split(':')[2])
            assertEquals("super-secret-bearer", cipher.decrypt(enc1))
            assertEquals("super-secret-bearer", cipher.decrypt(enc2))

            val flippedLast = enc1.dropLast(1) + if (enc1.last() == 'A') 'B' else 'A'
            assertEquals("", cipher.decrypt(flippedLast))
            assertEquals("", cipher.decrypt("enc:v1:bad-iv:bad-ct"))
            assertEquals("", AesGcmSecretKeyTokenCipher(otherKey).decrypt(enc1))

            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store =
                    PreferenceDataStoreFactory.create(
                        scope = dataStoreScope,
                        produceFile = { File(tempDir, "encrypted_token.preferences_pb") },
                    )
                val repository = SettingsRepository(store, cipher)
                repository.update { it.copy(bearerToken = "  live-bearer-42  ") }

                val rawStored = store.data.first()[SettingsRepository.KEY_BEARER_TOKEN].orEmpty()
                assertTrue(rawStored.startsWith("enc:v1:"))
                assertFalse(rawStored.contains("live-bearer-42"))
                assertEquals("live-bearer-42", repository.settings.first().bearerToken)

                store.edit { it[SettingsRepository.KEY_BEARER_TOKEN] = flippedLast }
                assertEquals("", repository.settings.first().bearerToken)

                repository.update { it.copy(bearerToken = "   ") }
                assertEquals("", store.data.first()[SettingsRepository.KEY_BEARER_TOKEN])
            } finally {
                dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `legacy plaintext bearerToken remains readable and migrates to AES-GCM on next update`() =
        runTest {
            val secretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val cipher = AesGcmSecretKeyTokenCipher(secretKey)
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store =
                    PreferenceDataStoreFactory.create(
                        scope = dataStoreScope,
                        produceFile = { File(tempDir, "legacy_token.preferences_pb") },
                    )
                store.edit { prefs ->
                    prefs[SettingsRepository.KEY_SERVER_URL] = "http://100.64.0.9:8080"
                    prefs[SettingsRepository.KEY_BEARER_TOKEN] = "legacy-plaintext-tok"
                    prefs[SettingsRepository.KEY_LAST_PEER_SYNC_CURSOR] = "2026-06-01T12:00:00Z"
                }

                val repository = SettingsRepository(store, cipher)
                val initialRead = repository.settings.first()
                assertEquals("legacy-plaintext-tok", initialRead.bearerToken)
                assertEquals("2026-06-01T12:00:00Z", initialRead.lastPeerSyncCursor)

                repository.update { it.copy(ultraDenseRows = true) }

                val migratedRaw = store.data.first()[SettingsRepository.KEY_BEARER_TOKEN].orEmpty()
                assertTrue(migratedRaw.startsWith("enc:v1:"))
                assertFalse(migratedRaw.contains("legacy-plaintext-tok"))
                val afterUpdate = repository.settings.first()
                assertEquals("legacy-plaintext-tok", afterUpdate.bearerToken)
                assertEquals("2026-06-01T12:00:00Z", afterUpdate.lastPeerSyncCursor)
            } finally {
                dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
}
