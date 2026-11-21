package com.grayvines.roche

import android.content.pm.verify.domain.DomainVerificationUserState
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.grayvines.roche.core.externalIdentifierUrl
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.isLoopbackHost
import com.grayvines.roche.core.isPublicHost
import com.grayvines.roche.core.isSyncablePublicUrl
import com.grayvines.roche.core.isValidArxivId
import com.grayvines.roche.core.normalizeIdentifierValueRaw
import com.grayvines.roche.data.enrichment.parseOdesliLinksPayload
import com.grayvines.roche.data.feeds.writeSharedPdfToItemDir
import com.grayvines.roche.data.remote.PublicUrlValidator
import com.grayvines.roche.data.remote.serverEndpointViolation
import com.grayvines.roche.data.settings.ENCRYPTED_TOKEN_PREFIX
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.settings.TokenCipher
import com.grayvines.roche.data.settings.redactedForUi
import com.grayvines.roche.data.sync.PeerNodeDto
import com.grayvines.roche.data.sync.effectivePeerWeight
import com.grayvines.roche.data.sync.selectFeedPoller
import com.grayvines.roche.media.ITEMS_SUBDIR
import com.grayvines.roche.media.SHARED_PDF_FILENAME
import com.grayvines.roche.media.findCachedPdfFileForEntry
import com.grayvines.roche.model.resolveFallbackFaviconUrl
import com.grayvines.roche.playback.defaultAllowedLocalAudioDirs
import com.grayvines.roche.playback.isSafeAudioPlaybackSource
import com.grayvines.roche.ranking.EpGaussianMath
import com.grayvines.roche.ranking.EpRankingEngine
import com.grayvines.roche.ui.formatPlaybackSpeedLabel
import com.grayvines.roche.ui.isTrackingPixelSize
import com.grayvines.roche.ui.resolveNextBearerToken
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Wave1BSecurityAndUxTest {

    @Test
    fun `H1 blocks authority parser differentials and returns canonical reconstructed URL`() {
        val attacks =
            listOf(
                "https://example.com:443@roche.tail1234.ts.net/api/v1/health",
                "https://example.com%40roche.tail1234.ts.net/api/v1/health",
                "https://%31%32%37.0.0.1/feed.xml",
                "https://foo_bar.example.com/feed.xml",
                "https://example.com\\@roche.tail1234.ts.net/api",
            )
        attacks.forEach { bad ->
            assertThrows(
                IOException::class.java,
                { PublicUrlValidator.ensurePublicUrlSyntax(bad) },
                bad,
            )
            assertThrows(
                IOException::class.java,
                { PublicUrlValidator.ensureSafeServerEndpoint(bad) { true } },
                bad,
            )
        }

        val canonical =
            PublicUrlValidator.ensurePublicTarget(
                rawUrl = "HTTPS://Example.COM:443/feed.xml?a=1#frag",
                resolveAddresses = { arrayOf(InetAddress.getByName("93.184.216.34")) },
            )
        assertEquals("https://example.com:443/feed.xml?a=1", canonical)
    }

    @Test
    fun `H2 blocks octal and hex IPv4 literals and M3 gates 10_0_2_2 loopback`() {
        val octalAndHex =
            listOf(
                "0177.0.0.1",
                "012.0.0.1",
                "0144.0100.0.1",
                "0x7f.0.0.0x1",
                "0x7f.0x1",
                "0x7f.0.0.1",
                "2130706433",
                "127.1",
                "[::ffff:127.0.0.1]",
                "[::ffff:7f00:1]",
            )
        octalAndHex.forEach { host ->
            assertFalse(isPublicHost(host), "Expected isPublicHost to reject $host")
            assertFalse(
                isSyncablePublicUrl("https://$host/feed.xml"),
                "Expected isSyncablePublicUrl to reject $host",
            )
        }
        assertTrue(isPublicHost("example.com"))
        assertTrue(isPublicHost("xn--bcher-kva.example"))

        assertFalse(isLoopbackHost("10.0.2.2", allowEmulatorLoopback = false))
        assertTrue(isLoopbackHost("10.0.2.2", allowEmulatorLoopback = true))
        assertTrue(isLoopbackHost("localhost", allowEmulatorLoopback = false))
        assertTrue(isLoopbackHost("127.0.0.1", allowEmulatorLoopback = false))
        assertFalse(isLoopbackHost("0177.0.0.1", allowEmulatorLoopback = true))
        assertNotNull(
            serverEndpointViolation("http", "127.0.0.1", { false }, allowCleartextLoopback = false)
        )
        assertThrows(IOException::class.java) {
            PublicUrlValidator.ensureResolvedServerTarget(
                "http://127.0.0.1:8080/api/v1/health",
                allowCleartextLoopback = false,
            )
        }
    }

    @Test
    fun `M15 validates arXiv IDs strictly and M18 enforces branded HTTPS host allowlists`() {
        assertTrue(isValidArxivId("2301.07041v2"))
        assertTrue(isValidArxivId("hep-th/9901001"))
        assertFalse(isValidArxivId("2301.07041&max_results=999"))
        assertFalse(isValidArxivId("../secret"))
        assertNull(extractArxivIdentifier("https://arxiv.org/abs/2301.07041&foo=bar"))

        assertNull(externalIdentifierUrl("spotify", "https://evil.example/track/123"))
        assertNull(externalIdentifierUrl("wikipedia", "https://evilwikipedia.org/wiki/Foo"))
        assertNull(externalIdentifierUrl("bandcamp", "https://evilbandcamp.com/album/foo"))
        assertNull(externalIdentifierUrl("imdb", "https://evil.example/title/tt0133093/"))
        assertNull(externalIdentifierUrl("apple_music", "http://music.apple.com/us/album/foo/123"))
        assertNull(externalIdentifierUrl("url", "https://secret.tail1234.ts.net/private"))
        assertEquals(
            "https://evilwikipedia.org/wiki/Fake",
            normalizeIdentifierValueRaw(
                "movie",
                "wikipedia",
                "https://evilwikipedia.org/wiki/Fake",
            ),
        )
        assertEquals(
            "Stalker_(1979_film)",
            normalizeIdentifierValueRaw(
                "movie",
                "wikipedia",
                "https://en.wikipedia.org/wiki/Stalker_(1979_film)",
            ),
        )

        val spoofedOdesli =
            """{"linksByPlatform":{"appleMusic":{"url":"https://evil.example/album/123"},""" +
                """"tidal":{"url":"https://tidal.com/browse/album/42"},""" +
                """"deezer":{"url":"http://www.deezer.com/album/99"}}}"""
        val parsed = parseOdesliLinksPayload(spoofedOdesli)
        assertNull(parsed["apple_music"])
        assertNull(parsed["deezer"])
        assertEquals("https://tidal.com/browse/album/42", parsed["tidal"])
    }

    @Test
    fun `M18 prioritizes DOMAIN_STATE_VERIFIED over DOMAIN_STATE_SELECTED in AlbumMusicLauncher`() {
        val verifiedState = DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        val selectedState = DomainVerificationUserState.DOMAIN_STATE_SELECTED
        assertTrue(
            isHostDomainVerified(mapOf("open.spotify.com" to verifiedState), "open.spotify.com")
        )
        assertFalse(
            isHostDomainVerified(mapOf("open.spotify.com" to selectedState), "open.spotify.com")
        )
        assertTrue(
            isHostDomainSelected(mapOf("open.spotify.com" to verifiedState), "open.spotify.com")
        )

        val verified =
            MusicUrlCandidate(
                packageName = "com.spotify.music",
                activityName = "com.spotify.Main",
                url = "https://open.spotify.com/album/1",
                isUserSelectedForHost = true,
                isVerifiedForHost = true,
            )
        val userSelected =
            MusicUrlCandidate(
                packageName = "com.alt.music",
                activityName = "com.alt.Main",
                url = "https://music.youtube.com/browse/MPREb_1",
                isUserSelectedForHost = true,
                isVerifiedForHost = false,
            )
        val resolution =
            selectMusicUrlResolution(
                candidates = listOf(userSelected, verified),
                selfPackageName = "com.grayvines.roche",
                browserPackages = emptySet(),
            )
        assertEquals(MusicUrlResolution.SingleTarget(verified), resolution)
    }

    @Test
    fun `H9 hardens EP width cap, extreme logit tails, NaN deltas, and PeerConsensus NaN weights`() {
        val ok = (0 until EpRankingEngine.MAX_COMPARISON_WIDTH).map { listOf("e$it") }
        assertTrue(EpRankingEngine.validateOrdering(ok).isSuccess)
        val tooWide = (0..EpRankingEngine.MAX_COMPARISON_WIDTH).map { listOf("e$it") }
        assertTrue(EpRankingEngine.validateOrdering(tooWide).isFailure)

        for (meanCav in listOf(-1000.0, -50.0, 50.0, 1000.0)) {
            val (lp, dlp, d2lp) = EpGaussianMath.matchMomentsLogit(meanCav, 1.0)
            assertTrue(lp.isFinite(), "logpart non-finite for $meanCav: $lp")
            assertTrue(dlp.isFinite(), "dlogpart non-finite for $meanCav: $dlp")
            assertTrue(d2lp.isFinite(), "d2logpart non-finite for $meanCav: $d2lp")
        }

        assertNull(
            EpGaussianMath.maxFiniteSiteDelta(
                doubleArrayOf(Double.NaN),
                doubleArrayOf(0.0),
                doubleArrayOf(0.0),
                doubleArrayOf(0.0),
            )
        )
        assertNull(
            EpGaussianMath.maxFiniteSiteDelta(
                doubleArrayOf(0.0),
                doubleArrayOf(0.0),
                doubleArrayOf(Double.POSITIVE_INFINITY),
                doubleArrayOf(0.0),
            )
        )
        assertEquals(
            0.2,
            EpGaussianMath.maxFiniteSiteDelta(
                doubleArrayOf(0.1),
                doubleArrayOf(0.0),
                doubleArrayOf(0.0),
                doubleArrayOf(0.2),
            ),
        )

        val nanPeer = PeerNodeDto(peerId = "bad", weight = Double.NaN, networkTier = "wifi")
        val okPeer = PeerNodeDto(peerId = "good", weight = 1.0, networkTier = "wifi")
        assertEquals(0.0, effectivePeerWeight(nanPeer, null, null, null))
        assertEquals(
            "good",
            selectFeedPoller(
                activePeers = listOf(nanPeer, okPeer),
                feedUrl = "https://example.com/rss.xml",
                pollIntervalMinutes = 30,
                nowEpochSeconds = 1_700_000_000L,
                lastPolledBy = null,
                lastError = null,
                lastErrorBy = null,
            ),
        )
    }

    @Test
    fun `H10 gates DuckDuckGo favicons to public domains and suppresses tiny tracking pixels`() {
        assertNull(resolveFallbackFaviconUrl("https://roche.tail1234.ts.net/feed.xml"))
        assertNull(resolveFallbackFaviconUrl("https://192.168.1.10/feed.xml"))
        assertNull(resolveFallbackFaviconUrl("https://100.64.0.1/feed.xml"))
        assertNull(resolveFallbackFaviconUrl("https://93.184.216.34/feed.xml"))
        assertNull(resolveFallbackFaviconUrl("https://printer.local/feed.xml"))
        assertNull(resolveFallbackFaviconUrl("https://wiki.internal/feed.xml"))
        assertEquals(
            "https://icons.duckduckgo.com/ip3/example.com.ico",
            resolveFallbackFaviconUrl("https://www.example.com/rss.xml"),
        )

        assertTrue(isTrackingPixelSize(1, 1))
        assertTrue(isTrackingPixelSize(15, 24))
        assertFalse(isTrackingPixelSize(16, 16))
    }

    @Test
    fun `H4 and M10 clear bearerToken on host change, redact Settings toString, and migrate plaintext tokens`(
        @TempDir tempDir: File
    ) = runTest {
        val current =
            Settings(serverUrl = "https://roche.tail1.ts.net", bearerToken = "secret-live-token")
        assertFalse(current.toString().contains("secret-live-token"))
        assertTrue(current.toString().contains("<redacted>"))
        assertEquals("", current.redactedForUi().bearerToken)

        assertEquals(
            "",
            resolveNextBearerToken(
                current = current,
                requestedToken = null,
                normalizedTargetUrl = "https://other.tail2.ts.net",
                hostChanged = true,
            ),
        )
        assertEquals(
            "",
            resolveNextBearerToken(
                current = current,
                requestedToken = "secret-live-token",
                normalizedTargetUrl = "https://other.tail2.ts.net",
                hostChanged = true,
            ),
        )
        assertEquals(
            "brand-new-token",
            resolveNextBearerToken(
                current = current,
                requestedToken = "brand-new-token",
                normalizedTargetUrl = "https://other.tail2.ts.net",
                hostChanged = true,
            ),
        )
        assertEquals(
            "secret-live-token",
            resolveNextBearerToken(
                current = current,
                requestedToken = null,
                normalizedTargetUrl = "https://roche.tail1.ts.net",
                hostChanged = false,
            ),
        )

        val gen = AtomicLong(1L)
        val rotatingCipher =
            object : TokenCipher {
                override fun encrypt(plaintext: String): String =
                    "${ENCRYPTED_TOKEN_PREFIX}g${gen.get()}:$plaintext"

                override fun decrypt(stored: String): String =
                    stored
                        .removePrefix("${ENCRYPTED_TOKEN_PREFIX}g${gen.get()}:")
                        .takeIf {
                            stored.startsWith("${ENCRYPTED_TOKEN_PREFIX}g${gen.get()}:")
                        }
                        .orEmpty()

                override fun keyGeneration(): Long = gen.get()
            }
        val store =
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                File(tempDir, "settings_m10.preferences_pb")
            }
        store.edit { it[SettingsRepository.KEY_BEARER_TOKEN] = "legacy-plain" }
        val repo = SettingsRepository(dataStore = store, tokenCipher = rotatingCipher)
        assertEquals("legacy-plain", repo.settings.first().bearerToken)
        // Auto-migration encrypts legacy plaintext token on read.
        val migratedRaw = store.data.first()[SettingsRepository.KEY_BEARER_TOKEN].orEmpty()
        assertTrue(migratedRaw.startsWith(ENCRYPTED_TOKEN_PREFIX))

        // Keystore reset invalidates cached ciphertext so subsequent save re-encrypts under new
        // key.
        gen.incrementAndGet()
        repo.update { it.copy(bearerToken = "legacy-plain") }
        val reencryptedRaw = store.data.first()[SettingsRepository.KEY_BEARER_TOKEN].orEmpty()
        assertTrue(reencryptedRaw.startsWith("${ENCRYPTED_TOKEN_PREFIX}g2:"))
    }

    @Test
    fun `M8 and M11 block PDF traversal, prevent silent PDF overwrite, and enforce PDF disk quota`(
        @TempDir tempDir: File
    ) = runTest {
        val validPdfBytes =
            listOf("%PDF-1.7", "1 0 obj", "<<>>", "endobj", "").joinToString("\n").toByteArray()
        val otherPdfBytes =
            listOf("%PDF-1.7", "2 0 obj", "<<>>", "endobj", "").joinToString("\n").toByteArray()

        // First write succeeds
        assertTrue(
            writeSharedPdfToItemDir(
                rootDir = tempDir,
                itemId = "entry-1",
                stream = ByteArrayInputStream(validPdfBytes),
            )
        )
        // Silent overwrite refused by default
        assertFalse(
            writeSharedPdfToItemDir(
                rootDir = tempDir,
                itemId = "entry-1",
                stream = ByteArrayInputStream(otherPdfBytes),
                allowOverwriteExisting = false,
            )
        )
        val cached = findCachedPdfFileForEntry(tempDir, "entry-1")
        assertNotNull(cached)
        assertEquals(String(validPdfBytes), cached?.readText())

        // Sibling traversal blocked
        assertNull(findCachedPdfFileForEntry(tempDir, "x/../entry-1"))
        assertNull(findCachedPdfFileForEntry(tempDir, "../entry-1"))

        // Total disk quota enforced
        assertFalse(
            writeSharedPdfToItemDir(
                rootDir = tempDir,
                itemId = "entry-2",
                stream = ByteArrayInputStream(otherPdfBytes),
                maxTotalPdfBytes = validPdfBytes.size.toLong() + 5L,
            )
        )
        assertFalse(File(File(tempDir, ITEMS_SUBDIR), "entry-2/$SHARED_PDF_FILENAME").exists())

        assertEquals(
            "Saved \"Deep Learning Paper\" to Want to Read",
            formatWantToReadSuccessToast("Deep Learning Paper"),
        )
    }

    @Test
    fun `M11 restricts local audio dirs to items subdir and and_10 formats playback speeds cleanly`(
        @TempDir tempDir: File
    ) {
        val offlineRoot = File(tempDir, "offline_media").apply { mkdirs() }
        val allowedDirs = defaultAllowedLocalAudioDirs(offlineRoot)
        val itemAudio =
            File(offlineRoot, "items/entry-1/audio.mp3").apply {
                parentFile?.mkdirs()
                writeText("mp3")
            }
        val thumbFile =
            File(offlineRoot, "thumbnails/cover.jpg").apply {
                parentFile?.mkdirs()
                writeText("jpg")
            }
        val cacheSibling =
            File(tempDir, "cache_dir/http_engine/a.mp3").apply {
                parentFile?.mkdirs()
                writeText("mp3")
            }
        assertTrue(isSafeAudioPlaybackSource(itemAudio.absolutePath, allowedDirs))
        assertFalse(isSafeAudioPlaybackSource(thumbFile.absolutePath, allowedDirs))
        assertFalse(isSafeAudioPlaybackSource(cacheSibling.absolutePath, allowedDirs))

        assertEquals("1x", formatPlaybackSpeedLabel(1.0f))
        assertEquals("1.25x", formatPlaybackSpeedLabel(1.25f))
        assertEquals("1.5x", formatPlaybackSpeedLabel(1.5f))
        assertEquals("2x", formatPlaybackSpeedLabel(2.0f))
    }
}
