package com.grayvines.roche.ui

import androidx.lifecycle.viewModelScope
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.TestRocheEnv
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.RankClusterCandidate
import com.grayvines.roche.data.collectInBackground
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.upsertFeeds
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.local.upsertItems
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.media.BitmapLoader
import com.grayvines.roche.media.ImageDecoders
import com.grayvines.roche.media.THUMBNAILS_SUBDIR
import com.grayvines.roche.media.ThumbnailDiskCache
import com.grayvines.roche.media.ThumbnailLoader
import com.grayvines.roche.media.computeProportionalTargetSize
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.reader.ArticleBlock
import com.grayvines.roche.reader.extractPlainTextHeadings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
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
@Suppress("TooManyFunctions", "LargeClass")
class RankBoardAndGate5UiLogicTest {
    @TempDir lateinit var tempDir: File

    private lateinit var testEnv: TestRocheEnv
    private val database: RocheDatabase
        get() = testEnv.database

    private val settings: SettingsRepository
        get() = testEnv.settings

    private val repository: RocheRepository
        get() = testEnv.repository

    private lateinit var viewModel: MainViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        testEnv = TestRocheEnv(tempDir, "gate5_settings.preferences_pb")
        viewModel = MainViewModel(repository = repository, settingsRepository = settings)
    }

    @AfterEach
    fun tearDown() {
        viewModel.playerController.releaseController()
        runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        testEnv.close()
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
                BitmapLoader(
                    apiClient = streamingClient,
                    settings = settings,
                    decoders = ImageDecoders(decodeBytes = { FakeImage() }, sizeOfKiB = { 16 }),
                    diskCache = ThumbnailDiskCache(cacheRoot),
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
            val collectJob = collectInBackground(viewModel.uiState)
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
        val collectJob = collectInBackground(viewModel.uiState)
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
            val collectJob = collectInBackground(viewModel.uiState)
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

    @Test
    fun `deriveRocheBackStack and popActionForTopKey model multi-layer navigation stacks`() {
        assertEquals(
            listOf(RocheNavKey.Tab(AppTab.HOME)),
            deriveRocheBackStack(MainUiState()),
        )
        assertNull(popActionForTopKey(RocheNavKey.Tab(AppTab.HOME)))
        assertEquals(
            MainAction.SelectTab(AppTab.HOME),
            popActionForTopKey(RocheNavKey.Tab(AppTab.INBOX)),
        )

        val scopedFromFeeds =
            MainUiState(
                activeTab = AppTab.INBOX,
                selectedFeedId = "f-1",
                scopeOriginTab = AppTab.FEEDS,
                isSearchOpen = true,
                isPlayerFullscreenOpen = true,
            )
        val fullStack =
            deriveRocheBackStack(
                state = scopedFromFeeds,
                activeReaderEntryId = "entry-search-1",
                activeFullscreenPlayerEntryId = "ep-1",
            )
        assertEquals(
            listOf(
                RocheNavKey.Tab(AppTab.HOME),
                RocheNavKey.Tab(AppTab.FEEDS),
                RocheNavKey.ScopedInbox(feedId = "f-1", tag = null),
                RocheNavKey.Search,
                RocheNavKey.Reader("entry-search-1"),
                RocheNavKey.FullscreenPlayer("ep-1"),
            ),
            fullStack,
        )
        assertEquals(MainAction.CloseFullscreenPlayer, popActionForTopKey(fullStack.last()))
        assertEquals(
            MainAction.NavigateBackFromScope,
            popActionForTopKey(RocheNavKey.ScopedInbox(feedId = "f-1", tag = null)),
        )
    }

    @Test
    fun `popActionForTopKey pops Reader back to Search and preserves searchQuery before Home`() =
        runTest {
            database.dao().upsertItem(sampleItem("entry-search-1", "Navigation 3 Deep Dive"))
            val collectJob = collectInBackground(viewModel.uiState)
            viewModel.uiState.first { it.allEntries.size == 1 }

            viewModel.dispatch(MainAction.SelectTab(AppTab.INBOX))
            viewModel.dispatch(MainAction.OpenSearch)
            viewModel.dispatch(MainAction.UpdateSearchQuery("Navigation"))
            viewModel.dispatch(MainAction.OpenReader("entry-search-1"))

            val readerOverSearchState =
                viewModel.uiState.first {
                    it.activeTab == AppTab.INBOX &&
                        it.isSearchOpen &&
                        it.activeReaderEntry?.id == "entry-search-1"
                }
            val stackInReader = deriveRocheBackStack(readerOverSearchState)
            assertEquals(
                listOf(
                    RocheNavKey.Tab(AppTab.HOME),
                    RocheNavKey.Tab(AppTab.INBOX),
                    RocheNavKey.Search,
                    RocheNavKey.Reader("entry-search-1"),
                ),
                stackInReader,
            )

            val popReader = popActionForTopKey(stackInReader.last())
            assertEquals(MainAction.CloseReader, popReader)
            viewModel.dispatch(popReader!!)

            val backInSearchState =
                viewModel.uiState.first { it.activeReaderEntry == null && it.isSearchOpen }
            val stackInSearch = deriveRocheBackStack(backInSearchState)
            assertEquals(
                listOf(
                    RocheNavKey.Tab(AppTab.HOME),
                    RocheNavKey.Tab(AppTab.INBOX),
                    RocheNavKey.Search,
                ),
                stackInSearch,
            )
            assertEquals("Navigation", backInSearchState.searchQuery)

            val popSearch = popActionForTopKey(stackInSearch.last())
            assertEquals(MainAction.CloseSearch, popSearch)
            viewModel.dispatch(popSearch!!)

            val backInInboxState = viewModel.uiState.first { !it.isSearchOpen }
            val stackInInbox = deriveRocheBackStack(backInInboxState)
            assertEquals(
                listOf(RocheNavKey.Tab(AppTab.HOME), RocheNavKey.Tab(AppTab.INBOX)),
                stackInInbox,
            )
            assertEquals(MainAction.SelectTab(AppTab.HOME), popActionForTopKey(stackInInbox.last()))

            collectJob.cancel()
        }

    @Test
    fun `computeProportionalTargetSize preserves aspect ratio and caps at MAX_BITMAP_DIMENSION_PX`() {
        assertNull(computeProportionalTargetSize(rawWidth = 128, rawHeight = 96, targetPx = 256))
        assertNull(computeProportionalTargetSize(rawWidth = 0, rawHeight = 100, targetPx = 256))
        assertEquals(
            256 to 128,
            computeProportionalTargetSize(rawWidth = 4000, rawHeight = 2000, targetPx = 256),
        )
        assertEquals(
            1024 to 2048,
            computeProportionalTargetSize(rawWidth = 2000, rawHeight = 4000, targetPx = 4096),
        )
    }

    @Test
    fun `OpenCompanionSection clears scoped inbox state and destination helpers preserve back lists`() =
        runTest {
            seedTwoScopedFeeds()
            val collectJob = collectInBackground(viewModel.uiState)
            viewModel.uiState.first { it.allEntries.size == 2 }

            viewModel.dispatch(MainAction.SelectTab(AppTab.FEEDS))
            viewModel.dispatch(MainAction.FilterByFeed("f-1"))
            val scopedState =
                viewModel.uiState.first {
                    it.selectedFeedId == "f-1" &&
                        it.visibleEntries.map(InboxEntry::id) == listOf("i-f1")
                }
            assertEquals(AppTab.FEEDS, scopedState.scopeOriginTab)

            val unscopedDuringBack = resolveUnscopedInboxState(scopedState)
            assertNull(unscopedDuringBack.selectedFeedId)
            assertNull(unscopedDuringBack.selectedTag)
            assertEquals(
                setOf("i-f1", "i-f2"),
                unscopedDuringBack.visibleEntries.map(InboxEntry::id).toSet(),
            )

            viewModel.dispatch(MainAction.OpenCompanionSection(CompanionSubTab.DIARY))
            val companionState = viewModel.uiState.first { it.activeTab == AppTab.LISTS }
            assertEquals(CompanionSubTab.DIARY, companionState.companionSubTab)
            assertNull(companionState.selectedFeedId)
            assertNull(companionState.selectedTag)
            assertNull(companionState.scopeOriginTab)
            assertEquals(
                listOf(RocheNavKey.Tab(AppTab.HOME), RocheNavKey.Tab(AppTab.LISTS)),
                deriveRocheBackStack(companionState),
            )

            viewModel.dispatch(
                MainAction.OpenCompanionSection(
                    CompanionSubTab.LISTS,
                    "col-essential-cinema",
                )
            )
            val customListState =
                viewModel.uiState.first {
                    it.companionSubTab == CompanionSubTab.LISTS &&
                        it.selectedCatalogCollectionId == "col-essential-cinema"
                }
            assertEquals(
                "col-essential-cinema",
                customListState.selectedCatalogCollectionId,
            )

            val scopedKey = RocheNavKey.ScopedInbox(feedId = "f-1", tag = null)
            val retainedScoped = resolveScopedInboxState(companionState, scopedKey, scopedState)
            assertEquals("Systems Weekly", retainedScoped.selectedFeedTitle)
            assertEquals(listOf("i-f1"), retainedScoped.visibleEntries.map(InboxEntry::id))

            val openSearch = companionState.copy(isSearchOpen = true, searchQuery = "Compiler")
            assertEquals(
                "Compiler",
                resolveSearchOverlayState(companionState, openSearch).searchQuery,
            )
            collectJob.cancel()
        }

    @Test
    fun `OpenReader forceArticleView and PlayAudioEntry transition cleanly between Player and Reader`() =
        runTest {
            seedAudioEpisode("ep-nav-1")
            val collectJob = collectInBackground(viewModel.uiState)
            viewModel.uiState.first { it.allEntries.size == 1 }

            viewModel.dispatch(MainAction.OpenReader("ep-nav-1"))
            viewModel.playerState.first { it.currentEntryId == "ep-nav-1" && !it.isBuffering }
            val inPlayer = viewModel.uiState.first { it.isPlayerFullscreenOpen }
            viewModel.playerController.cancelAutoTickerForTest()
            assertNull(inPlayer.selectedReaderEntryId)
            assertEquals(
                listOf(RocheNavKey.Tab(AppTab.HOME), RocheNavKey.FullscreenPlayer("ep-nav-1")),
                deriveRocheBackStack(inPlayer, activeAudioEntryId = "ep-nav-1"),
            )

            viewModel.dispatch(MainAction.SeekPlayerToMs(45_000L))
            viewModel.playerState.first { it.positionMs == 45_000L }

            viewModel.dispatch(MainAction.OpenReader("ep-nav-1", forceArticleView = true))
            val inReader =
                viewModel.uiState.first {
                    !it.isPlayerFullscreenOpen && it.selectedReaderEntryId == "ep-nav-1"
                }
            assertTrue(viewModel.playerState.value.isPlaying)
            assertEquals(45_000L, viewModel.playerState.value.positionMs)
            assertEquals(
                listOf(RocheNavKey.Tab(AppTab.HOME), RocheNavKey.Reader("ep-nav-1")),
                deriveRocheBackStack(inReader, activeAudioEntryId = "ep-nav-1"),
            )

            viewModel.dispatch(MainAction.PlayAudioEntry("ep-nav-1", openFullscreen = true))
            val backInPlayer =
                viewModel.uiState.first {
                    it.isPlayerFullscreenOpen && it.selectedReaderEntryId == null
                }
            val playerStack = deriveRocheBackStack(backInPlayer, activeAudioEntryId = "ep-nav-1")
            assertEquals(
                listOf(RocheNavKey.Tab(AppTab.HOME), RocheNavKey.FullscreenPlayer("ep-nav-1")),
                playerStack,
            )

            viewModel.dispatch(popActionForTopKey(playerStack.last())!!)
            val collapsed = viewModel.uiState.first { !it.isPlayerFullscreenOpen }
            assertEquals(
                listOf(RocheNavKey.Tab(AppTab.HOME)),
                deriveRocheBackStack(collapsed, activeAudioEntryId = "ep-nav-1"),
            )
            collectJob.cancel()
        }

    @Test
    fun `SavedStateHandle navigation restoration and SavedScrollStateSnapshot survive process death`() =
        runTest {
            seedTwoScopedFeeds()
            val deepBody = "Lead paragraph. " + "x".repeat(4_000) + " RestoredFullBodyToken55."
            database
                .dao()
                .upsertItem(
                    sampleItem("i-f1", "Systems Article", feedId = "f-1")
                        .copy(contentText = deepBody, contentHtml = "<p>$deepBody</p>")
                )
            val collectJob1 = collectInBackground(viewModel.uiState)
            viewModel.uiState.first { it.allEntries.size == 2 }

            viewModel.dispatch(MainAction.SelectTab(AppTab.FEEDS))
            viewModel.dispatch(MainAction.FilterByFeed("f-1"))
            viewModel.dispatch(MainAction.OpenSearch)
            viewModel.dispatch(MainAction.UpdateSearchQuery("RestoredFullBodyToken55"))
            viewModel.dispatch(MainAction.OpenReader("i-f1"))
            viewModel.uiState.first {
                it.activeReaderEntry?.contentText?.contains("RestoredFullBodyToken55") == true
            }
            val savedJson = viewModel.exportSavedNavigationJson()
            collectJob1.cancel()

            verifyRestoredViewModel(savedJson)
            verifyDeletedReaderPruned()
            verifySyncRocheNavBackStackAndScrollSnapshot()
        }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyRestoredViewModel(
        savedJson: String
    ) {
        val coldRepo =
            RocheRepository(
                database = database,
                apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") },
                settings = settings,
            )
        val restoredHandle =
            androidx.lifecycle.SavedStateHandle(
                mapOf(MainViewModel.SAVED_NAV_JSON_KEY to savedJson)
            )
        val recreatedVm =
            MainViewModel(
                repository = coldRepo,
                settingsRepository = settings,
                savedStateHandle = restoredHandle,
            )
        try {
            val initialFrame = recreatedVm.uiState.value
            assertTrue(initialFrame.isInitialLoading)
            assertEquals(
                listOf(
                    RocheNavKey.Tab(AppTab.HOME),
                    RocheNavKey.Tab(AppTab.FEEDS),
                    RocheNavKey.ScopedInbox(feedId = "f-1", tag = null),
                    RocheNavKey.Search,
                    RocheNavKey.Reader("i-f1"),
                ),
                deriveRocheBackStack(initialFrame),
            )

            val collectJob = collectInBackground(recreatedVm.uiState)
            val hydrated =
                recreatedVm.uiState.first {
                    !it.isInitialLoading &&
                        it.activeReaderEntry?.contentText?.contains("RestoredFullBodyToken55") ==
                            true &&
                        it.searchResults.any { hit -> hit.id == "i-f1" }
                }
            assertEquals(AppTab.FEEDS, hydrated.scopeOriginTab)
            assertEquals("f-1", hydrated.selectedFeedId)
            collectJob.cancel()
        } finally {
            recreatedVm.playerController.releaseController()
            recreatedVm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyDeletedReaderPruned() {
        val staleJson =
            kotlinx.serialization.json.Json.encodeToString(
                SavedNavigationUiState.serializer(),
                SavedNavigationUiState(
                    activeTab = AppTab.INBOX,
                    selectedReaderEntryId = "missing-entry-id",
                    selectedFeedId = "missing-feed-id",
                    scopeOriginTab = AppTab.FEEDS,
                ),
            )
        val coldRepo =
            RocheRepository(
                database = database,
                apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") },
                settings = settings,
            )
        val staleVm =
            MainViewModel(
                repository = coldRepo,
                settingsRepository = settings,
                savedStateHandle =
                    androidx.lifecycle.SavedStateHandle(
                        mapOf(MainViewModel.SAVED_NAV_JSON_KEY to staleJson)
                    ),
            )
        try {
            val collectJob = collectInBackground(staleVm.uiState)
            val reconciled =
                staleVm.uiState.first {
                    !it.isInitialLoading &&
                        it.selectedReaderEntryId == null &&
                        it.selectedFeedId == null
                }
            assertNull(reconciled.scopeOriginTab)
            collectJob.cancel()
        } finally {
            staleVm.playerController.releaseController()
            staleVm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private fun verifySyncRocheNavBackStackAndScrollSnapshot() {
        val liveStack =
            mutableListOf<androidx.navigation3.runtime.NavKey>(
                RocheNavKey.Tab(AppTab.HOME),
                RocheNavKey.Tab(AppTab.INBOX),
                RocheNavKey.Search,
            )
        syncRocheNavBackStack(
            liveStack,
            listOf(
                RocheNavKey.Tab(AppTab.HOME),
                RocheNavKey.Tab(AppTab.FEEDS),
                RocheNavKey.ScopedInbox(feedId = "f-1", tag = null),
            ),
        )
        assertEquals(
            listOf(
                RocheNavKey.Tab(AppTab.HOME),
                RocheNavKey.Tab(AppTab.FEEDS),
                RocheNavKey.ScopedInbox(feedId = "f-1", tag = null),
            ),
            liveStack,
        )

        val holder =
            MainScrollStateHolder(
                initialLazyPositions = mapOf("inbox:ALL" to (12 to 48)),
                initialScrollOffsets = mapOf("home" to 320),
            )
        val encoded =
            kotlinx.serialization.json.Json.encodeToString(
                SavedScrollStateSnapshot.serializer(),
                holder.toSavedSnapshot(),
            )
        val decoded =
            MainScrollStateHolder.fromSavedSnapshot(
                kotlinx.serialization.json.Json.decodeFromString(
                    SavedScrollStateSnapshot.serializer(),
                    encoded,
                )
            )
        assertEquals(12, decoded.lazyListState("inbox:ALL").firstVisibleItemIndex)
        assertEquals(48, decoded.lazyListState("inbox:ALL").firstVisibleItemScrollOffset)
        assertEquals(320, decoded.scrollState("home").value)
    }

    private suspend fun seedTwoScopedFeeds() {
        database
            .dao()
            .upsertFeeds(
                listOf(
                    FeedRow(
                        id = "f-1",
                        title = "Systems Weekly",
                        url = "https://a.test/rss",
                        siteUrl = null,
                        feedType = "rss",
                    ),
                    FeedRow(
                        id = "f-2",
                        title = "Compiler Notes",
                        url = "https://b.test/rss",
                        siteUrl = null,
                        feedType = "rss",
                    ),
                )
            )
        database
            .dao()
            .upsertItems(
                listOf(
                    sampleItem("i-f1", "Systems Article", feedId = "f-1"),
                    sampleItem("i-f2", "Compiler Article", feedId = "f-2"),
                )
            )
    }

    private suspend fun seedAudioEpisode(id: String) {
        database
            .dao()
            .upsertItem(
                sampleItem(id, "Podcast Episode")
                    .copy(
                        contentType = "audio",
                        audioUrl = "https://pod.example.com/$id.mp3",
                        duration = "10:00",
                        contentHtml = "<p>Episode show notes</p>",
                    )
            )
    }

    private class FakeImage

    private fun sampleItem(id: String, title: String, feedId: String = "f-1") =
        InboxItemRow(
            id = id,
            feedId = feedId,
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
