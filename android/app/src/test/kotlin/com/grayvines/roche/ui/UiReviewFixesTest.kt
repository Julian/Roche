package com.grayvines.roche.ui

import androidx.media3.common.util.UnstableApi
import com.grayvines.roche.data.ReaderItemContent
import com.grayvines.roche.data.TestRocheEnv
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.ui.components.ThumbnailLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@androidx.annotation.OptIn(UnstableApi::class)
class UiReviewFixesTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `resolveCachedAudioUrl avoids duplicate validateUrl on cache miss`() {
        val validated = mutableListOf<String>()
        val cache = AudioRedirectCache()
        val resolved =
            resolveCachedAudioUrl(
                rawUrl = "https://pod.example.com/ep.mp3",
                cache = cache,
                validateUrl = { url ->
                    validated += url
                    url
                },
                probeHop = { AudioHopOutcome.Final },
            )
        assertEquals("https://pod.example.com/ep.mp3", resolved)
        assertEquals(listOf("https://pod.example.com/ep.mp3"), validated)
    }

    @Test
    fun `SafeRedirectHttpDataSource disables auto-redirects and blocks private redirect hops pre-connect`() {
        val publicAddr = InetAddress.getByAddress("pod.example.com", byteArrayOf(93, -72, -40, 34))
        val privateAddr = InetAddress.getByAddress("internal.local", byteArrayOf(127, 0, 0, 1))
        val connectedUrls = mutableListOf<String>()
        val followRedirectsSettings = mutableListOf<Boolean>()

        val dataSource =
            SafeRedirectHttpDataSource(
                openConnection = { url ->
                    connectedUrls += url.toString()
                    FakeHttpURLConnection(
                        url = url,
                        code = 302,
                        headers = mapOf("Location" to listOf("https://internal.local/secret.mp3")),
                        onConnect = { conn ->
                            followRedirectsSettings += conn.instanceFollowRedirects
                        },
                    )
                },
                validateUrl = { url ->
                    resolveSafeHttpPlaybackUrl(
                        rawUrl = url,
                        resolveAddresses = { host ->
                            if (host == "internal.local") arrayOf(privateAddr)
                            else arrayOf(publicAddr)
                        },
                        isCleartextPermitted = { false },
                    )
                },
            )

        val err = runCatching {
            dataSource.openValidatedUrl("https://pod.example.com/start.mp3")
        }
            .exceptionOrNull()
        assertTrue(err is IOException)
        assertEquals(listOf("https://pod.example.com/start.mp3"), connectedUrls)
        assertEquals(listOf(false), followRedirectsSettings)
    }

    @Test
    fun `computeSliderThumbCenterX handles zero and narrow widths without throwing`() {
        assertEquals(0f, computeSliderThumbCenterX(width = 0f, fraction = 0.5f, thumbRadius = 7f))
        assertEquals(0f, computeSliderThumbCenterX(width = -10f, fraction = 0.5f, thumbRadius = 7f))
        assertEquals(5f, computeSliderThumbCenterX(width = 10f, fraction = 0.2f, thumbRadius = 7f))
        assertEquals(7f, computeSliderThumbCenterX(width = 100f, fraction = 0f, thumbRadius = 7f))
        assertEquals(93f, computeSliderThumbCenterX(width = 100f, fraction = 1f, thumbRadius = 7f))
        assertEquals(
            50f,
            computeSliderThumbCenterX(width = 100f, fraction = 0.5f, thumbRadius = 7f),
        )
    }

    @Test
    fun `readerContent and activePlayerNotesContent stay isolated`() {
        val article =
            InboxEntry(
                id = "article-1",
                feedId = "feed-1",
                title = "Deep Dive Article",
                url = "https://example.com/article",
                author = null,
                summary = null,
                contentHtml = "<p>Short preview</p>",
                contentText = null,
                contentType = ContentType.ARTICLE,
                status = ItemStatus.UNREAD,
                isFavorite = false,
                isCachedOffline = false,
                publishedAt = null,
                createdAt = "2026-01-01T00:00:00Z",
                readProgress = 0.0,
                thumbnailMediaId = null,
                duration = null,
                feedTitle = "Tech Blog",
                tags = emptyList(),
            )
        val podcast =
            InboxEntry(
                id = "pod-1",
                feedId = "feed-2",
                title = "Episode 42",
                url = "https://example.com/ep42",
                author = null,
                summary = null,
                contentHtml = "<p>Short notes</p>",
                contentText = null,
                contentType = ContentType.AUDIO,
                status = ItemStatus.UNREAD,
                isFavorite = false,
                isCachedOffline = false,
                publishedAt = null,
                createdAt = "2026-01-01T00:00:00Z",
                readProgress = 0.0,
                thumbnailMediaId = null,
                duration = "45:00",
                feedTitle = "Systems Pod",
                tags = emptyList(),
            )
        val articleFull =
            ReaderItemContent(
                itemId = "article-1",
                contentHtml = "<p>Full hydrated article body with lots of text</p>",
                contentText = "Full hydrated article body",
            )
        val podcastFull =
            ReaderItemContent(
                itemId = "pod-1",
                contentHtml = "<p>Full hydrated episode show notes with timestamps</p>",
                contentText = "Full hydrated episode show notes",
            )

        val state =
            MainUiState(
                selectedReaderEntryId = "article-1",
                readerContent = articleFull,
                activePlayerNotesContent = podcastFull,
                allEntries = listOf(article, podcast),
                playerState = PodcastPlayerState(currentEntryId = "pod-1"),
            )

        assertEquals(articleFull.contentHtml, state.activeReaderEntry?.contentHtml)
        assertEquals(podcastFull.contentHtml, state.activePlayerEntry?.contentHtml)

        val closedReaderTransient =
            reduceUiAction(
                TransientUiState(
                    selectedReaderEntryId = "article-1",
                    readerContent = articleFull,
                    activePlayerNotesContent = podcastFull,
                ),
                MainAction.CloseReader,
            )
        assertNull(closedReaderTransient.readerContent)
        assertEquals(podcastFull, closedReaderTransient.activePlayerNotesContent)
    }

    @Test
    fun `SelectFilter preserves feed and tag scope`() {
        val scoped =
            TransientUiState(
                activeTab = AppTab.INBOX,
                activeFilter = InboxFilter.ALL,
                selectedFeedId = "feed-1",
                selectedTag = "systems",
                scopeOriginTab = AppTab.FEEDS,
            )
        val updated = reduceUiAction(scoped, MainAction.SelectFilter(InboxFilter.ARCHIVED))
        assertEquals(InboxFilter.ARCHIVED, updated.activeFilter)
        assertEquals("feed-1", updated.selectedFeedId)
        assertEquals("systems", updated.selectedTag)
        assertEquals(AppTab.FEEDS, updated.scopeOriginTab)
    }

    @Test
    fun `MainScrollStateHolder excludes search keys from SavedScrollStateSnapshot`() {
        val holder = MainScrollStateHolder()
        holder.lazyListState("inbox")
        holder.lazyListState(SEARCH_SCROLL_STATE_KEY)
        holder.lazyListState("search:kotlin")

        val snapshot = holder.toSavedSnapshot()
        assertTrue("inbox" in snapshot.lazyPositions)
        assertFalse(SEARCH_SCROLL_STATE_KEY in snapshot.lazyPositions)
        assertFalse("search:kotlin" in snapshot.lazyPositions)
    }

    @Test
    fun `formatLatexAltToHtml preserves prefix-sharing commands and strips noscript template object embed`() {
        val latex =
            formatLatexAltToHtml(
                """\left( \int_0^1 x^\top \alpha \le \textbf{M} \in \mathbb{R} \right)"""
            )
        assertTrue(latex.contains("""\left"""))
        assertTrue(latex.contains("""\int"""))
        assertTrue(latex.contains("""\top"""))
        assertTrue(latex.contains("α"))
        assertTrue(latex.contains("≤"))
        assertTrue(latex.contains("∈"))
        assertFalse(latex.contains("≤ft"))
        assertFalse(latex.contains("∈t"))
        assertFalse(latex.contains("→p"))
        assertFalse(latex.contains("bf"))

        val body =
            parsePreprocessedHtmlBody(
                "<p>Visible</p>" +
                    "<noscript><p>Hidden noscript</p></noscript>" +
                    "<template><p>Hidden template</p></template>" +
                    "<object data='x'>Hidden object</object>" +
                    "<embed src='y'/>",
                "https://example.com",
            )
        val text = body.text()
        assertTrue("Visible" in text)
        assertFalse("Hidden noscript" in text)
        assertFalse("Hidden template" in text)
        assertFalse("Hidden object" in text)
    }

    @Test
    fun `parseChaptersFromEpisodeNotes decodes HTML entities`() {
        val notes =
            "<p>00:00 Intro &amp; Welcome</p>" +
                "<p>05:30 Bob&#39;s &quot;Big&quot; Idea &mdash; Deep Dive</p>"
        val chapters = parseChaptersFromEpisodeNotes(notes)
        assertEquals(2, chapters.size)
        assertEquals("Intro & Welcome", chapters[0].title)
        assertEquals("Bob's \"Big\" Idea — Deep Dive", chapters[1].title)
    }

    @Test
    fun `buildMedia3Metadata omits artworkUri to prevent unvalidated fetches`() {
        val snapshot =
            MediaSessionMetadataSnapshot(
                entryId = "ep-1",
                episodeTitle = "Ep 1",
                podcastTitle = "Pod",
                artworkUrl = "http://127.0.0.1/private.png",
                durationMs = 60_000L,
                positionMs = 0L,
                isPlaying = true,
                isBuffering = false,
                playbackSpeed = 1.0f,
                hasNextQueuedAudio = false,
            )
        val metadata = AndroidPodcastMediaSessionBridge.buildMedia3Metadata(snapshot, null)
        assertNull(metadata.artworkUri)
    }

    @Test
    fun `ThumbnailLoader enforces HTTPS for external images and blocks non-HTTP inline fallthrough`() =
        runTest {
            val env = TestRocheEnv(tempDir, "thumb_https_test.preferences_pb")
            try {
                val requestedUrls = mutableListOf<String>()
                val apiClient = RocheApiClient { req ->
                    requestedUrls += req.url
                    HttpResponse(statusCode = 200, body = "img", bodyBytes = byteArrayOf(1, 2, 3))
                }
                env.settings.update {
                    it.copy(
                        serverUrl = "https://roche.internal:8080",
                        bearerToken = "secret-token",
                    )
                }
                val loader =
                    ThumbnailLoader(
                        apiClient = apiClient,
                        settings = env.settings,
                        decoder = { null },
                    )

                assertNull(loader.loadInlineImage("http://example.com/cleartext.png"))
                assertNull(loader.loadInlineImage("media-id-should-not-hit-server"))
                assertNull(loader.loadFavicon("http://example.com/favicon.ico"))
                assertNull(loader.loadFavicon("not-a-url"))
                assertNull(loader.loadThumbnail("http://example.com/cleartext-thumb.jpg"))
                assertTrue(requestedUrls.isEmpty())
            } finally {
                env.close()
            }
        }

    private class FakeHttpURLConnection(
        url: URL,
        private val code: Int,
        private val headers: Map<String, List<String>> = emptyMap(),
        private val body: ByteArray = ByteArray(0),
        private val onConnect: (HttpURLConnection) -> Unit = {},
    ) : HttpURLConnection(url) {
        override fun connect() {
            onConnect(this)
            connected = true
        }

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = code

        override fun getResponseMessage(): String = "OK"

        override fun getHeaderField(name: String?): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()

        override fun getHeaderFields(): MutableMap<String, MutableList<String>> =
            headers.mapValues { it.value.toMutableList() }.toMutableMap()

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }
}
