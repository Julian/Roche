package com.grayvines.roche.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import com.grayvines.roche.data.ReaderItemContent
import com.grayvines.roche.data.THUMBNAILS_SUBDIR
import com.grayvines.roche.data.TestRocheEnv
import com.grayvines.roche.data.diskCacheKey
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.ui.components.ThumbnailLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@androidx.annotation.OptIn(UnstableApi::class)
class UiReviewFixesTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `SafeRedirectHttpDataSource reads Content-Range before disconnect and returns 0L on EOF HTTP 416`() {
        var fakeConn: FakeHttpURLConnection? = null
        val dataSource =
            SafeRedirectHttpDataSource(
                openConnection = { url ->
                    FakeHttpURLConnection(
                            url = url,
                            code = 416,
                            headers = mapOf("Content-Range" to listOf("bytes */1024")),
                            clearHeadersOnDisconnect = true,
                        )
                        .also { fakeConn = it }
                },
                validateUrl = { it },
            )

        val (openedLength, finalUrl) =
            dataSource.openStreamForUrl(
                rawUrl = "https://pod.example.com/episode.mp3",
                position = 1024L,
                length = 512L,
            )
        assertEquals(0L, openedLength)
        assertEquals("https://pod.example.com/episode.mp3", finalUrl)
        assertEquals(1, fakeConn?.disconnectCalls?.get())
        assertEquals(C.RESULT_END_OF_INPUT, dataSource.read(ByteArray(16), 0, 16))
        dataSource.close()

        val outOfRangeErr = runCatching {
            dataSource.openStreamForUrl(
                rawUrl = "https://pod.example.com/episode.mp3",
                position = 2048L,
                length = 512L,
            )
        }
            .exceptionOrNull()
        assertTrue(outOfRangeErr is DataSourceException)
        assertEquals(
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            (outOfRangeErr as DataSourceException).reason,
        )
    }

    @Test
    fun `SafeRedirectHttpDataSource disconnects HttpURLConnection on connect outputStream and responseCode errors`() {
        val connectFailConn =
            FakeHttpURLConnection(
                url = URL("https://pod.example.com/connect-fail.mp3"),
                code = 200,
                connectError = IOException("connect timeout"),
            )
        val dsConnect =
            SafeRedirectHttpDataSource(
                openConnection = { connectFailConn },
                validateUrl = { it },
            )
        assertTrue(
            runCatching { dsConnect.openValidatedUrl("https://pod.example.com/connect-fail.mp3") }
                .exceptionOrNull() is IOException
        )
        assertEquals(1, connectFailConn.disconnectCalls.get())

        val outputFailConn =
            FakeHttpURLConnection(
                url = URL("https://pod.example.com/body-fail.mp3"),
                code = 200,
                outputWriteError = IOException("broken pipe"),
            )
        val dsBody =
            SafeRedirectHttpDataSource(
                openConnection = { outputFailConn },
                validateUrl = { it },
            )
        assertTrue(
            runCatching {
                dsBody.openValidatedUrl(
                    rawUrl = "https://pod.example.com/body-fail.mp3",
                    httpMethod = "POST",
                    httpBody = byteArrayOf(1, 2, 3),
                )
            }
                .exceptionOrNull() is IOException
        )
        assertEquals(1, outputFailConn.disconnectCalls.get())

        val responseCodeFailConn =
            FakeHttpURLConnection(
                url = URL("https://pod.example.com/code-fail.mp3"),
                code = 200,
                responseCodeError = IOException("reset by peer"),
            )
        val dsCode =
            SafeRedirectHttpDataSource(
                openConnection = { responseCodeFailConn },
                validateUrl = { it },
            )
        assertTrue(
            runCatching { dsCode.openValidatedUrl("https://pod.example.com/code-fail.mp3") }
                .exceptionOrNull() is IOException
        )
        assertEquals(1, responseCodeFailConn.disconnectCalls.get())
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
                            if (host == "internal.local") {
                                arrayOf(privateAddr)
                            } else {
                                arrayOf(publicAddr)
                            }
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

    @Test
    fun `ThumbnailLoader deletes corrupt streamed files enforces cooldown and recovers after cooldown`() =
        runTest {
            val env = TestRocheEnv(tempDir, "thumb_corrupt_test.preferences_pb")
            try {
                val cacheRoot = File(tempDir, "thumb_cache")
                val imageUrl = "https://cdn.example.com/corrupt-then-ok.png"
                var nowMs = 1_000_000L
                var networkCalls = 0
                var decodeCalls = 0
                var serveValid = false

                val apiClient = RocheApiClient {
                    networkCalls++
                    val payload =
                        if (serveValid) {
                            "VALID_IMG".encodeToByteArray()
                        } else {
                            "<html>502 Bad Gateway</html>".encodeToByteArray()
                        }
                    HttpResponse(statusCode = 200, body = "", bodyBytes = payload)
                }
                val loader =
                    ThumbnailLoader(
                        apiClient = apiClient,
                        settings = env.settings,
                        cacheDir = cacheRoot,
                        decoder = { bytes ->
                            decodeCalls++
                            if (bytes.decodeToString() == "VALID_IMG") fakeImageBitmap() else null
                        },
                        clockMs = { nowMs },
                    )
                val expectedCacheFile =
                    File(File(cacheRoot, THUMBNAILS_SUBDIR), diskCacheKey(imageUrl))

                assertNull(loader.loadThumbnail(imageUrl))
                assertEquals(1, networkCalls)
                assertEquals(1, decodeCalls)
                assertFalse(expectedCacheFile.exists())
                assertTrue(loader.isRecentlyFailed(imageUrl))

                assertNull(loader.loadThumbnail(imageUrl))
                assertEquals(1, networkCalls)
                assertEquals(1, decodeCalls)

                serveValid = true
                nowMs += ThumbnailLoader.FAILED_RETRY_COOLDOWN_MS + 1L
                assertNotNull(loader.loadThumbnail(imageUrl))
                assertEquals(2, networkCalls)
                assertEquals(2, decodeCalls)
                assertTrue(expectedCacheFile.isFile)
                assertFalse(loader.isRecentlyFailed(imageUrl))
            } finally {
                env.close()
            }
        }

    @Test
    fun `ThumbnailLoader deletes pre-existing corrupt disk file so cooldown suppresses retries`() =
        runTest {
            val env = TestRocheEnv(tempDir, "thumb_precorrupt_test.preferences_pb")
            try {
                val cacheRoot = File(tempDir, "thumb_precorrupt_cache")
                val preCorruptUrl = "https://cdn.example.com/preexisting-corrupt.png"
                val preCorruptFile =
                    File(File(cacheRoot, THUMBNAILS_SUBDIR), diskCacheKey(preCorruptUrl)).apply {
                        parentFile?.mkdirs()
                        writeBytes("CORRUPT".encodeToByteArray())
                    }
                var decodeCalls = 0
                val loader =
                    ThumbnailLoader(
                        apiClient = RocheApiClient { HttpResponse(statusCode = 500, body = "") },
                        settings = env.settings,
                        cacheDir = cacheRoot,
                        decoder = {
                            decodeCalls++
                            null
                        },
                        clockMs = { 5_000_000L },
                    )

                assertNull(loader.loadThumbnail(preCorruptUrl))
                assertEquals(1, decodeCalls)
                assertFalse(preCorruptFile.exists())
                assertTrue(loader.isRecentlyFailed(preCorruptUrl))
                assertNull(loader.loadThumbnail(preCorruptUrl))
                assertEquals(1, decodeCalls)
            } finally {
                env.close()
            }
        }

    @Test
    fun `openSessionCancellably closes opened resource when cancelled at withContext resume boundary`() {
        val callerDispatcher = QueuedTestDispatcher()
        val ioDispatcher = QueuedTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + callerDispatcher)
        val closed = AtomicBoolean(false)
        val published = AtomicBoolean(false)
        var caughtCancellation = false
        val fakeResource = AutoCloseable { closed.set(true) }

        val job = scope.launch {
            try {
                openSessionCancellably(
                    ioDispatcher = ioDispatcher,
                    openOnIo = { fakeResource },
                    publishOnCaller = { published.set(true) },
                )
            } catch (ce: CancellationException) {
                caughtCancellation = true
                throw ce
            }
        }

        // Start coroutine on caller -> dispatches openOnIo to ioDispatcher.
        callerDispatcher.drain()
        // Run openOnIo on ioDispatcher -> creates fakeResource and queues resume onto
        // callerDispatcher.
        ioDispatcher.drain()
        assertFalse(closed.get())
        assertFalse(published.get())

        // Cancel right at the withContext resume boundary before callerDispatcher resumes.
        job.cancel()
        callerDispatcher.drain()

        assertTrue(caughtCancellation)
        assertTrue(closed.get())
        assertFalse(published.get())
        scope.cancel()
    }

    @Test
    fun `SearchDisplayRetentionState freezes last active search state on close without snapshot writes`() {
        val activeState = MainUiState(isSearchOpen = true, searchQuery = "distributed systems")
        val closedState = MainUiState(isSearchOpen = false, searchQuery = "")
        val holder =
            SearchDisplayRetentionState(sawActiveSearch = true, retainedState = activeState)

        val duringActive = computeRetainedSearchDisplay(holder, activeState, isSearchActive = true)
        commitRetainedSearchDisplay(holder, activeState, isSearchActive = true)
        assertEquals("distributed systems", duringActive.state.searchQuery)
        assertTrue(duringActive.canEditQuery)

        val duringExit = computeRetainedSearchDisplay(holder, closedState, isSearchActive = false)
        commitRetainedSearchDisplay(holder, closedState, isSearchActive = false)
        assertEquals("distributed systems", duringExit.state.searchQuery)
        assertFalse(duringExit.canEditQuery)
    }

    @Test
    fun `syncSavedBearerTokenChange clears pairingCode only when bearerToken updates to new non-blank value`() {
        // Unchanged token (e.g. failed pairing attempt) preserves pairing code.
        val unchanged =
            syncSavedBearerTokenChange(
                savedBearerToken = "",
                lastSavedBearerToken = "",
                currentBearerToken = "",
                currentPairingCode = "HOST#CODE-123",
            )
        assertEquals("HOST#CODE-123", unchanged.pairingCode)

        // Pairing succeeds: new non-blank token updates bearerToken and clears pairingCode.
        val paired =
            syncSavedBearerTokenChange(
                savedBearerToken = "tok-paired-1",
                lastSavedBearerToken = "",
                currentBearerToken = "",
                currentPairingCode = "HOST#CODE-123",
            )
        assertEquals("tok-paired-1", paired.lastSavedBearerToken)
        assertEquals("tok-paired-1", paired.bearerToken)
        assertEquals("", paired.pairingCode)

        // Re-pairing to a rotated token also clears pairingCode.
        val repaired =
            syncSavedBearerTokenChange(
                savedBearerToken = "tok-paired-2",
                lastSavedBearerToken = "tok-paired-1",
                currentBearerToken = "tok-paired-1",
                currentPairingCode = "HOST#CODE-456",
            )
        assertEquals("tok-paired-2", repaired.bearerToken)
        assertEquals("", repaired.pairingCode)

        // Clearing bearerToken to blank does not wipe an in-progress pairingCode.
        val clearedToken =
            syncSavedBearerTokenChange(
                savedBearerToken = "",
                lastSavedBearerToken = "tok-paired-2",
                currentBearerToken = "",
                currentPairingCode = "HOST#NEXT-789",
            )
        assertEquals("", clearedToken.lastSavedBearerToken)
        assertEquals("HOST#NEXT-789", clearedToken.pairingCode)
    }

    private fun fakeImageBitmap(): ImageBitmap =
        Proxy.newProxyInstance(
            ImageBitmap::class.java.classLoader,
            arrayOf(ImageBitmap::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getWidth",
                "getHeight" -> 16
                "toString" -> "FakeImageBitmap(16x16)"
                else -> 0
            }
        } as ImageBitmap

    private class QueuedTestDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun drain() {
            while (true) {
                val next = queue.poll() ?: break
                next.run()
            }
        }
    }

    private class FakeHttpURLConnection(
        url: URL,
        private val code: Int,
        private val headers: Map<String, List<String>> = emptyMap(),
        private val body: ByteArray = ByteArray(0),
        private val clearHeadersOnDisconnect: Boolean = false,
        private val connectError: IOException? = null,
        private val outputWriteError: IOException? = null,
        private val responseCodeError: IOException? = null,
        private val onConnect: (HttpURLConnection) -> Unit = {},
    ) : HttpURLConnection(url) {
        val disconnectCalls = AtomicInteger(0)
        private var disconnected = false

        override fun connect() {
            onConnect(this)
            connectError?.let { throw it }
            connected = true
        }

        override fun disconnect() {
            disconnectCalls.incrementAndGet()
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int {
            responseCodeError?.let { throw it }
            return code
        }

        override fun getResponseMessage(): String = "OK"

        override fun getHeaderField(name: String?): String? {
            if (clearHeadersOnDisconnect && disconnected) return null
            return headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
        }

        override fun getHeaderFields(): MutableMap<String, MutableList<String>> {
            if (clearHeadersOnDisconnect && disconnected) return mutableMapOf()
            return headers.mapValues { it.value.toMutableList() }.toMutableMap()
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)

        override fun getOutputStream(): OutputStream =
            object : OutputStream() {
                private val delegate = ByteArrayOutputStream()

                override fun write(b: Int) {
                    outputWriteError?.let { throw it }
                    delegate.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    outputWriteError?.let { throw it }
                    delegate.write(b, off, len)
                }
            }
    }
}
