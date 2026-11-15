package com.grayvines.roche.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.data.ConsumptionStatus
import com.grayvines.roche.data.EntryKind
import com.grayvines.roche.data.RankClusterCandidate
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.THUMBNAILS_SUBDIR
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.ui.components.ThumbnailLoader
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class RankBoardAndGate5UiLogicTest {
    @TempDir lateinit var tempDir: File

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var database: RocheDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: RocheRepository
    private lateinit var viewModel: MainViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database =
            Room.inMemoryDatabaseBuilder<RocheDatabase>().setDriver(BundledSQLiteDriver()).build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(tempDir, "gate5_settings.preferences_pb") },
            )
        settings = SettingsRepository(dataStore)
        val apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") }
        repository =
            RocheRepository(database = database, apiClient = apiClient, settings = settings)
        viewModel = MainViewModel(repository = repository, settingsRepository = settings)
    }

    @AfterEach
    fun tearDown() {
        viewModel.playerController.releaseController()
        runBlocking {
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
        }
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `RankBoardState preserves neighbor ties when reordering multi-item buckets`() {
        val candidates =
            listOf("a", "b", "c", "d", "e").map { id ->
                RankClusterCandidate(
                    entryId = id,
                    title = id.uppercase(),
                    subtitle = "",
                    thumbnailUrl = null,
                    mean = 0.0,
                    variance = 1.0,
                )
            }
        var board = RankBoardState.fromCandidates(candidates)
        assertEquals(listOf(1, 2, 3, 4, 5), board.displayRanks())

        // Form a 3-way tie [b, c, d]: buckets = [[a], [b, c, d], [e]]
        board = board.toggleTieWithAbove("c").toggleTieWithAbove("d")
        assertEquals(
            listOf(listOf("a"), listOf("b", "c", "d"), listOf("e")),
            board.toOrderingGroups(),
        )
        assertEquals(listOf(1, 2, 2, 2, 5), board.displayRanks())

        // Moving "c" up extracts "c" into its own bucket above [b, d] without breaking [b, d]
        val extractedUp = board.moveUp("c")
        assertEquals(
            listOf(listOf("a"), listOf("c"), listOf("b", "d"), listOf("e")),
            extractedUp.toOrderingGroups(),
        )
        assertEquals(listOf(1, 2, 3, 3, 5), extractedUp.displayRanks())

        // Moving solo "e" up swaps [e] across the entire tied [b, d] bucket
        val swappedAcrossTie = extractedUp.moveUp("e")
        assertEquals(
            listOf(listOf("a"), listOf("c"), listOf("e"), listOf("b", "d")),
            swappedAcrossTie.toOrderingGroups(),
        )
        assertEquals(listOf(1, 2, 3, 4, 4), swappedAcrossTie.displayRanks())

        // Untying middle item of 3-way tie [b, c, d] splits into [b] and [c, d]
        val splitTie = board.toggleTieWithAbove("c")
        assertEquals(
            listOf(listOf("a"), listOf("b"), listOf("c", "d"), listOf("e")),
            splitTie.toOrderingGroups(),
        )

        // tieAll collapses all items into one bucket
        val allTied = splitTie.tieAll()
        assertTrue(allTied.isAllTied)
        assertEquals(listOf(listOf("a", "b", "c", "d", "e")), allTied.toOrderingGroups())
        assertEquals(listOf(1, 1, 1, 1, 1), allTied.displayRanks())
    }

    @Test
    fun `ThumbnailLoader caps concurrent network streams at MAX_CONCURRENT_LOADS and cleans tmp files`() =
        runBlocking {
            settings.update {
                it.copy(serverUrl = "https://roche.tailnet.ts.net", bearerToken = "secret-token")
            }
            val activeStreams = AtomicInteger(0)
            val peakStreams = AtomicInteger(0)
            val barrier = CountDownLatch(ThumbnailLoader.MAX_CONCURRENT_LOADS)
            val releaseGate = CountDownLatch(1)

            val streamingClient = RocheApiClient { _ ->
                val current = activeStreams.incrementAndGet()
                peakStreams.accumulateAndGet(current, ::maxOf)
                barrier.countDown()
                releaseGate.await(3, TimeUnit.SECONDS)
                try {
                    HttpResponse(
                        statusCode = 200,
                        body = "",
                        bodyBytes = ByteArray(32) { 0x42 },
                    )
                } finally {
                    activeStreams.decrementAndGet()
                }
            }

            val cacheRoot = File(tempDir, "thumb_cache").apply { mkdirs() }
            val loader =
                ThumbnailLoader(
                    apiClient = streamingClient,
                    settings = settings,
                    cacheDir = cacheRoot,
                    decoder = { fakeBitmap() },
                )

            val jobs =
                (1..10).map { idx ->
                    async(Dispatchers.IO) { loader.loadThumbnail("media-$idx") }
                }
            assertTrue(barrier.await(3, TimeUnit.SECONDS))
            assertTrue(peakStreams.get() <= ThumbnailLoader.MAX_CONCURRENT_LOADS)
            releaseGate.countDown()

            val loaded = jobs.awaitAll()
            assertEquals(10, loaded.count { it != null })
            assertTrue(peakStreams.get() <= ThumbnailLoader.MAX_CONCURRENT_LOADS)

            val thumbsDir = File(cacheRoot, THUMBNAILS_SUBDIR)
            val cachedFiles = thumbsDir.listFiles().orEmpty()
            assertEquals(10, cachedFiles.size)
            assertTrue(cachedFiles.none { it.name.endsWith(".tmp") })
        }

    @Test
    fun `ArchiveVisibleEntries with active searchQuery archives only filtered entries and SelectTab clears search`() =
        runTest {
            database
                .dao()
                .upsertItems(
                    listOf(
                        sampleItem("i-match", "Rust Memory Model"),
                        sampleItem("i-other", "Gardening Notes"),
                    )
                )
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
            viewModel.uiState.first { it.allEntries.size == 2 }

            viewModel.dispatch(MainAction.OpenSearch)
            viewModel.dispatch(MainAction.UpdateSearchQuery("Rust"))
            val searched =
                viewModel.uiState.first {
                    it.isSearchOpen &&
                        it.searchQuery == "Rust" &&
                        it.searchResults.map(InboxEntry::id) == listOf("i-match")
                }
            assertEquals(listOf("i-match"), searched.searchResults.map(InboxEntry::id))

            viewModel.dispatch(MainAction.ArchiveVisibleEntries)
            val afterArchive =
                viewModel.uiState.first { state ->
                    state.allEntries.first { it.id == "i-match" }.isArchived
                }
            assertFalse(afterArchive.allEntries.first { it.id == "i-other" }.isArchived)

            viewModel.dispatch(MainAction.SelectTab(AppTab.STARRED))
            val switched = viewModel.uiState.first { it.activeTab == AppTab.STARRED }
            assertFalse(switched.isSearchOpen)
            assertEquals("", switched.searchQuery)

            collectJob.cancel()
        }

    @Test
    fun `resolveMergedSearchResults preserves live InboxEntry fields`() {
        val liveEntry =
            InboxEntry(
                id = "i-live",
                feedId = "f-1",
                feedTitle = "Systems Feed",
                title = "Speculative Execution",
                url = "https://example.com/spec",
                author = null,
                summary = "Short summary",
                contentText = "Preview",
                contentHtml = "<p>Preview</p>",
                contentType = ContentType.ARTICLE,
                status = ItemStatus.UNREAD,
                isFavorite = true,
                isCachedOffline = true,
                publishedAt = "1h",
                createdAt = "2026-06-01T12:00:00Z",
                readProgress = 0.45,
                thumbnailMediaId = "thumb-live",
                duration = null,
                tags = listOf("security"),
            )
        val staleDeepMatch =
            liveEntry.copy(
                isFavorite = false,
                isCachedOffline = false,
                readProgress = 0.0,
                thumbnailMediaId = null,
                tags = emptyList(),
                contentText = "Preview with hidden deep keyword xylophone",
                contentHtml = "<p>Preview with hidden deep keyword xylophone</p>",
            )

        val merged =
            resolveMergedSearchResults(
                entries = listOf(liveEntry),
                normalizedQuery = "xylophone",
                deepSearchQuery = "xylophone",
                deepSearchResults = listOf(staleDeepMatch),
            )
        assertEquals(1, merged.size)
        val result = merged.single()
        assertTrue(result.isFavorite)
        assertTrue(result.isCachedOffline)
        assertEquals(0.45, result.readProgress, 1e-6)
        assertEquals("thumb-live", result.thumbnailMediaId)
        assertEquals(listOf("security"), result.tags)
        assertTrue(result.contentText?.contains("xylophone") == true)
    }

    @Test
    fun `ToggleArchive flushes liveReadProgress sequentially before archiving`() = runTest {
        database.dao().upsertItem(sampleItem("i-flush", "Reader Article"))
        val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
        val loaded = viewModel.uiState.first { it.allEntries.any { e -> e.id == "i-flush" } }
        val target = loaded.allEntries.first { it.id == "i-flush" }

        viewModel.dispatch(MainAction.ToggleArchive(entry = target, liveReadProgress = 0.82))
        val archived =
            viewModel.uiState.first { state ->
                state.allEntries.first { it.id == "i-flush" }.isArchived
            }
        assertEquals(
            0.82,
            archived.allEntries.first { it.id == "i-flush" }.readProgress,
            1e-6,
        )
        collectJob.cancel()
    }

    @Test
    fun `logging item number 1 with rankAfterLog shows status banner instead of zero-comparison sheet`() =
        runTest {
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
            viewModel.dispatch(
                MainAction.SubmitQuickLog(
                    kind = EntryKind.BOOK,
                    title = "Designing Data-Intensive Applications",
                    creatorsCsv = "Martin Kleppmann",
                    releaseYear = 2017,
                    status = ConsumptionStatus.CONSUMED,
                    notes = "",
                    addToWant = false,
                    rankAfterLog = true,
                )
            )
            val state =
                viewModel.uiState.first {
                    it.statusBanner?.contains("rank #1") == true
                }
            assertNull(state.rankSession)
            assertTrue(state.statusBanner!!.contains("log at least 2 items to compare"))
            collectJob.cancel()
        }

    @Test
    fun `orderedHeadingBlockIndices aligns 1-to-1 with extractPlainTextHeadings across blank and duplicate headings`() {
        val blocks =
            listOf(
                ArticleBlock.Heading(level = 2, html = "<span></span>"),
                ArticleBlock.Heading(level = 2, html = "Introduction"),
                ArticleBlock.Paragraph(html = "First section body."),
                ArticleBlock.Heading(level = 2, html = "Introduction"),
                ArticleBlock.Paragraph(html = "Repeated heading body."),
                ArticleBlock.Heading(level = 2, html = "Benchmarks"),
            )
        val headings = extractPlainTextHeadings(blocks)
        val targetBlockIndices = orderedHeadingBlockIndices(blocks)
        assertEquals(listOf("Introduction", "Benchmarks"), headings)
        assertEquals(listOf(1, 5), targetBlockIndices)
    }

    private fun fakeBitmap(): ImageBitmap =
        Proxy.newProxyInstance(
            ImageBitmap::class.java.classLoader,
            arrayOf(ImageBitmap::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getWidth" -> 64
                "getHeight" -> 64
                "getHasAlpha" -> true
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                else -> null
            }
        } as ImageBitmap

    private fun sampleItem(id: String, title: String) =
        ItemEntity(
            id = id,
            feedId = "f-1",
            feedTitle = "Feed",
            title = title,
            url = "https://example.com/$id",
            author = null,
            summary = "Summary",
            contentText = "Body",
            contentHtml = "<p>Body</p>",
            contentType = "article",
            status = "unread",
            isFavorite = false,
            isCachedOffline = false,
            publishedAt = "2026-06-01T12:00:00Z",
            createdAt = "2026-06-01T12:00:00Z",
            readProgress = 0.0,
            thumbnailMediaId = null,
            duration = null,
        )
}
