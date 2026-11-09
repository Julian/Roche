package com.grayvines.roche.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SettingsRepositoryTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `settings normalize server URL and persist updates`() = runTest {
        val store =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "settings_test.preferences_pb") }
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
}
