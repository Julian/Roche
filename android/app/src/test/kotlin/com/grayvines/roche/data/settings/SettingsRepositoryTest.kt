@file:Suppress("LongMethod")

package com.grayvines.roche.data.settings

import android.content.pm.verify.domain.DomainVerificationUserState
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.grayvines.roche.MusicUrlCandidate
import com.grayvines.roche.MusicUrlResolution
import com.grayvines.roche.core.buildYoutubeMusicAlbumUrl
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.candidateStreamingUrls
import com.grayvines.roche.isHostDomainSelected
import com.grayvines.roche.isNonMusicVideoPackage
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode
import com.grayvines.roche.rewriteUrlForTargetPackage
import com.grayvines.roche.selectMusicUrlResolution
import com.grayvines.roche.usesYoutubePlaylistUrl
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.jupiter.api.Assertions.assertNull
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
            assertEquals("https://100.64.0.9:8080", current.serverUrl)
            assertEquals("secret-token", current.bearerToken)
            assertFalse(current.swipeToArchive)
            assertEquals(ThemeMode.LIGHT, current.themeMode)
            assertEquals(HeadlineFont.SANS, current.headlineFont)
            assertEquals(true, current.ultraDenseRows)
            assertEquals(
                listOf(
                    AppTab.HOME,
                    AppTab.INBOX,
                    AppTab.LISTS,
                    AppTab.FEEDS,
                    AppTab.STARRED,
                ),
                current.bottomNavTabs,
            )
            assertNull(store.data.first()[SettingsRepository.KEY_BOTTOM_NAV_TABS])

            repository.update { it.copy(lastPeerSyncCursor = "2026-06-01T12:01:00Z") }
            assertEquals("2026-06-01T12:01:00Z", repository.settings.first().lastPeerSyncCursor)
            assertNull(store.data.first()[SettingsRepository.KEY_BOTTOM_NAV_TABS])

            repository.update { it.copy(bearerToken = "new-token") }
            assertEquals("", repository.settings.first().lastPeerSyncCursor)
        } finally {
            dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `normalizeServerUrl parsePairingInput and Tailscale candidates handle MagicDNS and bundles`() {
        assertEquals("https://roche", normalizeServerUrl("roche"))
        assertEquals("https://roche:8080", normalizeServerUrl("roche:8080/"))
        assertEquals(
            "https://mac-mini.tail9876.ts.net:8080",
            normalizeServerUrl("mac-mini.tail9876.ts.net:8080"),
        )
        assertEquals(
            "https://airm.long-boa.ts.net:8080",
            normalizeServerUrl("https://airm.long-boa.ts.net:8080"),
        )
        assertEquals(
            "https://roche.tail9876.ts.net",
            normalizeServerUrl("roche.tail9876.ts.net"),
        )
        assertEquals("https://100.64.0.9:8080", normalizeServerUrl("100.64.0.9:8080"))
        assertEquals("https://100.63.0.1:8080", normalizeServerUrl("100.63.0.1:8080"))
        assertEquals("http://localhost:8080", normalizeServerUrl("localhost:8080"))
        assertEquals("http://127.0.0.1:8080", normalizeServerUrl("127.0.0.1:8080"))

        val (url, code) =
            parsePairingInput(
                rawServerUrl = "",
                rawPairingCode = "  https://roche.tail9876.ts.net#1234-guitarist-revenue  ",
            )
        assertEquals("https://roche.tail9876.ts.net", url)
        assertEquals("1234-guitarist-revenue", code)

        val candidates = tailscaleDiscoveryUrls(listOf("tail9876.ts.net.", "evil.com/path#@"))
        assertEquals(
            listOf(
                "https://roche.tail9876.ts.net",
                "https://roche",
            ),
            candidates,
        )
        assertEquals(
            listOf(
                "https://roche.tail9876.ts.net",
                "https://mac-mini.tail9876.ts.net",
                "https://mac-mini.tail9876.ts.net:8443",
            ),
            tailscaleHttpsUpgrades("http://mac-mini.tail9876.ts.net:8080"),
        )
        assertEquals(
            listOf("tail9876.ts.net"),
            extractTailnetDomains(listOf("tail9876.ts.net. lan.local, evil.com/bad")),
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

            repeat(64) {
                val sample = cipher.encrypt("super-secret-bearer")
                val sampleParts = sample.split(':')
                val ct = sampleParts[3]
                val flippedFirstCt = (if (ct.first() == 'A') 'B' else 'A') + ct.drop(1)
                val tamperedPayload =
                    "${sampleParts[0]}:${sampleParts[1]}:${sampleParts[2]}:$flippedFirstCt"
                val flippedLastSample = sample.dropLast(1) + if (sample.last() == 'A') 'B' else 'A'
                assertEquals("", cipher.decrypt(tamperedPayload))
                assertEquals("", cipher.decrypt(flippedLastSample))
            }
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

    @Test
    fun `legacy SEARCH bottom nav slot migrates to HOME and token decryption caches plaintext`() =
        runTest {
            val secretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val delegateCipher = AesGcmSecretKeyTokenCipher(secretKey)
            val decryptCount = AtomicInteger(0)
            val countingCipher =
                object : TokenCipher {
                    override fun encrypt(plaintext: String): String =
                        delegateCipher.encrypt(plaintext)

                    override fun decrypt(stored: String): String {
                        decryptCount.incrementAndGet()
                        return delegateCipher.decrypt(stored)
                    }
                }
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store =
                    PreferenceDataStoreFactory.create(
                        scope = dataStoreScope,
                        produceFile = { File(tempDir, "migrated_nav.preferences_pb") },
                    )
                val encryptedToken = delegateCipher.encrypt("cached-token-99")
                store.edit { prefs ->
                    prefs[SettingsRepository.KEY_BOTTOM_NAV_TABS] = "FEEDS,QUEUE,SEARCH,SETTINGS"
                    prefs[SettingsRepository.KEY_BEARER_TOKEN] = encryptedToken
                }

                val repository = SettingsRepository(store, countingCipher)
                val prewarmed = repository.prewarm()
                assertEquals(
                    listOf(
                        AppTab.FEEDS,
                        AppTab.QUEUE,
                        AppTab.LISTS,
                        AppTab.HOME,
                        AppTab.SETTINGS,
                    ),
                    prewarmed.bottomNavTabs,
                )
                assertEquals("cached-token-99", prewarmed.bearerToken)
                assertEquals(prewarmed, repository.latestSettings)
                val decryptsAfterPrewarm = decryptCount.get()
                assertEquals(1, decryptsAfterPrewarm)

                repository.update { it.copy(ultraDenseRows = true) }
                val updated = repository.settings.first()
                assertEquals("cached-token-99", updated.bearerToken)
                assertEquals(decryptsAfterPrewarm, decryptCount.get())
            } finally {
                dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `CollectionDrawSnapshot resolves vendor-neutral candidate streaming URLs`() {
        val parsed =
            CollectionDrawSnapshot(
                collectionId = "col-jazz",
                collectionSlug = "jazz_canon",
                collectionTitle = "Jazz Canon",
                entryId = "album-entry-1",
                title = "Sample Album",
                creator = "Sample Artist",
                releaseDate = "1972",
                imageUrl = "https://i.scdn.co/image/large",
                thumbnailUrl = "https://i.scdn.co/image/medium",
                tags = listOf("rock", "folk rock"),
                drawNumber = 3,
                totalCount = 1101,
                remainingCount = 1099,
                youtubeMusicId = "OLAK5uy_sample_playlist",
                spotifyId = "spotify_album_123",
                appleMusicId = "1015739190",
                tidalId = "49651320",
                qobuzId = "0075992723961",
                deezerId = "86605",
                amazonMusicId = "B001SAMPLE",
                wikipediaUrl = "https://en.wikipedia.org/wiki/Sample_Album",
            )
        assertEquals(
            "https://music.youtube.com/playlist?list=OLAK5uy_sample_playlist",
            parsed.youtubeMusicUrl,
        )
        assertEquals(
            "https://music.youtube.com/browse/MPREb_test123",
            parsed.copy(youtubeMusicId = "MPREb_test123").youtubeMusicUrl,
        )
        assertEquals(
            "https://music.youtube.com/search?q=Sample+Artist+Sample+Album",
            parsed.copy(youtubeMusicId = null).youtubeMusicUrl,
        )
        assertEquals(
            listOf(
                "https://music.youtube.com/playlist?list=OLAK5uy_sample_playlist",
                "https://open.spotify.com/album/spotify_album_123",
                "https://music.apple.com/album/1015739190",
                "https://tidal.com/browse/album/49651320",
                "https://listen.tidal.com/album/49651320",
                "https://open.qobuz.com/album/0075992723961",
                "https://www.deezer.com/album/86605",
                "https://music.amazon.com/albums/B001SAMPLE",
            ),
            parsed.candidateStreamingUrls(),
        )
        assertEquals(
            listOf("https://open.spotify.com/album/spotify_album_123"),
            parsed
                .copy(
                    youtubeMusicId = null,
                    appleMusicId = null,
                    tidalId = null,
                    qobuzId = null,
                    deezerId = null,
                    amazonMusicId = null,
                )
                .candidateStreamingUrls(),
        )
        assertTrue(
            parsed
                .copy(spotifyId = "spotify:album:spotify_album_123")
                .candidateStreamingUrls()
                .contains("https://open.spotify.com/album/spotify_album_123")
        )
    }

    @Test
    fun `selectMusicUrlResolution filters browsers deduplicates apps and honors user selection`() {
        val browsers = setOf("com.android.chrome", "org.mozilla.firefox")
        val chromeMatch =
            MusicUrlCandidate(
                packageName = "com.android.chrome",
                activityName = "com.google.android.apps.chrome.Main",
                url = "https://music.youtube.com/playlist?list=OLAK5uy_1",
                isUserSelectedForHost = false,
            )
        val ytClientExact =
            MusicUrlCandidate(
                packageName = "org.example.ytmusicclient",
                activityName = "org.example.ytmusicclient.MainActivity",
                url = "https://music.youtube.com/playlist?list=OLAK5uy_1",
                isUserSelectedForHost = false,
            )
        val ytClientSearch =
            MusicUrlCandidate(
                packageName = "org.example.ytmusicclient",
                activityName = "org.example.ytmusicclient.MainActivity",
                url = "https://music.youtube.com/search?q=Sample+Artist+Sample+Album",
                isUserSelectedForHost = false,
            )
        val spotifyMatch =
            MusicUrlCandidate(
                packageName = "com.spotify.music",
                activityName = "com.spotify.music.MainActivity",
                url = "https://open.spotify.com/album/spotify_album_123",
                isUserSelectedForHost = false,
            )

        assertEquals(
            MusicUrlResolution.None,
            selectMusicUrlResolution(
                candidates = listOf(chromeMatch),
                selfPackageName = "com.grayvines.roche",
                browserPackages = browsers,
            ),
        )
        assertEquals(
            MusicUrlResolution.SingleTarget(ytClientExact),
            selectMusicUrlResolution(
                candidates = listOf(chromeMatch, ytClientExact, ytClientSearch),
                selfPackageName = "com.grayvines.roche",
                browserPackages = browsers,
            ),
        )
        assertEquals(
            MusicUrlResolution.ChooserTargets(listOf(ytClientExact, spotifyMatch)),
            selectMusicUrlResolution(
                candidates = listOf(chromeMatch, ytClientExact, ytClientSearch, spotifyMatch),
                selfPackageName = "com.grayvines.roche",
                browserPackages = browsers,
            ),
        )
        val selectedClient = ytClientExact.copy(isUserSelectedForHost = true)
        assertEquals(
            MusicUrlResolution.SingleTarget(selectedClient),
            selectMusicUrlResolution(
                candidates = listOf(selectedClient, spotifyMatch),
                selfPackageName = "com.grayvines.roche",
                browserPackages = browsers,
            ),
        )

        val metrolistMatch =
            MusicUrlCandidate(
                packageName = "com.metrolist.music",
                activityName = "com.metrolist.music.MainActivity",
                url = "https://music.youtube.com/playlist?list=OLAK5uy_1",
                isUserSelectedForHost = false,
            )
        val newPipeSelected =
            MusicUrlCandidate(
                packageName = "org.schabi.newpipe",
                activityName = "org.schabi.newpipe.RouterActivity",
                url = "https://music.youtube.com/playlist?list=OLAK5uy_1",
                isUserSelectedForHost = true,
            )
        val mainYoutubeVideoApp =
            MusicUrlCandidate(
                packageName = "com.google.android.youtube",
                activityName = "com.google.android.youtube.UrlActivity",
                url = "https://music.youtube.com/playlist?list=OLAK5uy_1",
                isUserSelectedForHost = true,
            )
        assertEquals(
            MusicUrlResolution.SingleTarget(metrolistMatch),
            selectMusicUrlResolution(
                candidates = listOf(newPipeSelected, mainYoutubeVideoApp, metrolistMatch),
                selfPackageName = "com.grayvines.roche",
                browserPackages = browsers,
            ),
        )
        assertTrue(isNonMusicVideoPackage("org.schabi.newpipe"))
        assertTrue(isNonMusicVideoPackage("org.schabi.newpipe.debug"))
        assertTrue(isNonMusicVideoPackage("com.google.android.youtube"))
        assertTrue(isNonMusicVideoPackage("app.revanced.android.youtube"))
        assertFalse(isNonMusicVideoPackage("com.metrolist.music"))
        assertFalse(isNonMusicVideoPackage("com.google.android.apps.youtube.music"))
        assertFalse(isNonMusicVideoPackage("app.revanced.android.apps.youtube.music"))

        val selected = DomainVerificationUserState.DOMAIN_STATE_SELECTED
        assertFalse(isHostDomainSelected(mapOf("*.youtube.com" to selected), "music.youtube.com"))
        assertTrue(
            isHostDomainSelected(mapOf("music.youtube.com" to selected), "music.youtube.com")
        )
        assertTrue(isHostDomainSelected(mapOf("*.spotify.com" to selected), "open.spotify.com"))

        // Metrolist misroutes music.youtube.com/playlist?list=OLAK5uy_... to Album(OLAK5uy_...),
        // which fails InnerTube browse; fallback OLAK5uy_ playlists rewrite to www.youtube.com,
        // while MPREb_ album browse URLs stay on music.youtube.com.
        assertEquals(
            "https://www.youtube.com/playlist?list=OLAK5uy_1",
            rewriteUrlForTargetPackage(
                "com.metrolist.music",
                "https://music.youtube.com/playlist?list=OLAK5uy_1",
            ),
        )
        assertEquals(
            "https://music.youtube.com/browse/MPREb_1",
            rewriteUrlForTargetPackage(
                "com.metrolist.music",
                "https://music.youtube.com/browse/MPREb_1",
            ),
        )
        assertEquals(
            "https://music.youtube.com/playlist?list=OLAK5uy_1",
            rewriteUrlForTargetPackage(
                "com.google.android.apps.youtube.music",
                "https://music.youtube.com/playlist?list=OLAK5uy_1",
            ),
        )
        assertTrue(usesYoutubePlaylistUrl(MusicUrlResolution.SingleTarget(metrolistMatch)))
        assertFalse(usesYoutubePlaylistUrl(MusicUrlResolution.SingleTarget(spotifyMatch)))

        assertEquals(
            "https://music.youtube.com/playlist?list=OLAK5uy_mB7Rr5lil8SH5pkUFQ2F_OspPGHGjyTxY",
            buildYoutubeMusicAlbumUrl("OLAK5uy_ OLAK5uy_mB7Rr5lil8SH5pkUFQ2F_OspPGHGjyTxY"),
        )
        assertEquals(
            "https://music.youtube.com/playlist?list=OLAK5uy_kJfpwpJMDx4NaYhPHnNQLZfBZkX7-qmmQ",
            buildYoutubeMusicAlbumUrl("OLAK5uy_kJfpwpJMDx4NaYhPHnNQLZfBZkX7-qmmQ&feature=share"),
        )
    }

    @Test
    fun `SettingsRepository supports customizable 5-slot bottomNavTabs while preserving Home-first defaults`() =
        runTest {
            assertEquals(
                listOf(
                    AppTab.HOME,
                    AppTab.INBOX,
                    AppTab.LISTS,
                    AppTab.FEEDS,
                    AppTab.STARRED,
                ),
                AppTab.defaultBottomTabs,
            )
            assertTrue(AppTab.LISTS in AppTab.bottomTabCandidates)
            assertTrue(AppTab.INBOX in AppTab.bottomTabCandidates)

            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store =
                    PreferenceDataStoreFactory.create(
                        scope = dataStoreScope,
                        produceFile = { File(tempDir, "lists_nav_settings.preferences_pb") },
                    )
                val repository = SettingsRepository(store)
                repository.update {
                    it.copy(
                        bottomNavTabs =
                            listOf(
                                AppTab.HOME,
                                AppTab.LISTS,
                                AppTab.FEEDS,
                                AppTab.INBOX,
                                AppTab.SETTINGS,
                            )
                    )
                }
                assertEquals(
                    listOf(
                        AppTab.HOME,
                        AppTab.LISTS,
                        AppTab.FEEDS,
                        AppTab.INBOX,
                        AppTab.SETTINGS,
                    ),
                    repository.settings.first().bottomNavTabs,
                )
            } finally {
                dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `unrelated settings update preserves undecryptable bearerToken ciphertext until explicit clear`() =
        runTest {
            val keyA = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val keyB = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val cipherA = AesGcmSecretKeyTokenCipher(keyA)
            val cipherB = AesGcmSecretKeyTokenCipher(keyB)
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store =
                    PreferenceDataStoreFactory.create(
                        scope = dataStoreScope,
                        produceFile = { File(tempDir, "preserve_token.preferences_pb") },
                    )
                val encryptedWithA = cipherA.encrypt("recoverable-token")
                store.edit { prefs ->
                    prefs[SettingsRepository.KEY_SERVER_URL] = "http://100.64.0.9:8080"
                    prefs[SettingsRepository.KEY_BEARER_TOKEN] = encryptedWithA
                    prefs[SettingsRepository.KEY_LAST_PEER_SYNC_CURSOR] = "2026-06-01T12:00:00Z"
                }

                // Cipher B temporarily fails to decrypt -> settings.bearerToken reads as ""
                val brokenRepo = SettingsRepository(store, cipherB)
                assertEquals("", brokenRepo.settings.first().bearerToken)

                // Unrelated UI toggle MUST NOT overwrite the stored ciphertext or wipe sync cursor
                brokenRepo.update { it.copy(ultraDenseRows = true) }
                assertEquals(
                    encryptedWithA,
                    store.data.first()[SettingsRepository.KEY_BEARER_TOKEN],
                )
                assertEquals(
                    "2026-06-01T12:00:00Z",
                    store.data.first()[SettingsRepository.KEY_LAST_PEER_SYNC_CURSOR],
                )

                // Restoring Cipher A recovers the intact token!
                val recoveredRepo = SettingsRepository(store, cipherA)
                assertEquals("recoverable-token", recoveredRepo.settings.first().bearerToken)

                // Explicit blank clear DOES wipe KEY_BEARER_TOKEN
                brokenRepo.update { it.copy(bearerToken = "") }
                assertEquals("", store.data.first()[SettingsRepository.KEY_BEARER_TOKEN])
            } finally {
                dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
}
