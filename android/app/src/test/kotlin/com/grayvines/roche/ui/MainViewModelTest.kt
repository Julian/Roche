@file:Suppress("LongMethod")

package com.grayvines.roche.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.NetworkTierProvider
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.SeedData
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedSortOrder
import com.grayvines.roche.model.extractCleanDomain
import com.grayvines.roche.model.isCrossPeerRescueError
import com.grayvines.roche.model.resolveFallbackFaviconUrl
import com.grayvines.roche.model.resolveOriginFaviconUrl
import com.grayvines.roche.ui.components.MAX_BITMAP_DIMENSION_PX
import com.grayvines.roche.ui.components.ThumbnailLoader
import com.grayvines.roche.ui.components.calculateInSampleSize
import com.grayvines.roche.ui.components.pruneThumbnailDiskCache
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions")
class MainViewModelTest {
    @TempDir lateinit var tempDir: File

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var database: RocheDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: RocheRepository
    private lateinit var playerBackend: FakeAudioPlayerBackend
    private lateinit var mediaSessionBridge: RecordingMediaSessionBridge
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
                produceFile = { File(tempDir, "vm_test_settings.preferences_pb") },
            )
        settings = SettingsRepository(dataStore)
        val apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") }
        repository =
            RocheRepository(database = database, apiClient = apiClient, settings = settings)
        playerBackend = FakeAudioPlayerBackend()
        mediaSessionBridge = RecordingMediaSessionBridge()
        viewModel =
            MainViewModel(
                repository = repository,
                settingsRepository = settings,
                playerBackend = playerBackend,
                mediaSessionBridge = mediaSessionBridge,
            )
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
    fun `OpenAddSheet populates prefilled url and title and CloseAddSheet resets both`() = runTest {
        val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

        viewModel.dispatch(MainAction.OpenContextSheet("item-1"))
        viewModel.dispatch(
            MainAction.OpenAddSheet(
                prefilledUrl = "https://example.com/shared",
                prefilledTitle = "Shared Headline",
            )
        )

        val opened = viewModel.uiState.first { it.isAddSheetOpen }
        assertTrue(opened.isAddSheetOpen)
        assertEquals("https://example.com/shared", opened.prefilledShareUrl)
        assertEquals("Shared Headline", opened.prefilledShareTitle)
        assertNull(opened.contextSheetEntryId)

        viewModel.dispatch(MainAction.CloseAddSheet)
        val closed = viewModel.uiState.first { !it.isAddSheetOpen }
        assertFalse(closed.isAddSheetOpen)
        assertEquals("", closed.prefilledShareUrl)
        assertEquals("", closed.prefilledShareTitle)

        collectJob.cancel()
    }

    @Test
    fun `SaveUrlOrFeed saves entry with prefilled title and clears reader and add sheet state`() =
        runTest {
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

            viewModel.dispatch(MainAction.OpenReader("item-1"))
            viewModel.dispatch(
                MainAction.OpenAddSheet(
                    prefilledUrl = "https://example.com/shared-article",
                    prefilledTitle = "Shared Article Title",
                )
            )
            viewModel.dispatch(
                MainAction.SaveUrlOrFeed(
                    url = "https://example.com/shared-article",
                    titleOverride = "Shared Article Title",
                    tagCsv = "systems, reading",
                )
            )

            val state = viewModel.uiState.first { it.statusBanner != null }
            assertFalse(state.isAddSheetOpen)
            assertEquals("", state.prefilledShareUrl)
            assertEquals("", state.prefilledShareTitle)
            assertNull(state.selectedReaderEntryId)

            val savedEntry =
                repository.snapshot
                    .first { snap ->
                        snap.entries.any {
                            it.url == "https://example.com/shared-article" && it.tags.isNotEmpty()
                        }
                    }
                    .entries
                    .first { it.url == "https://example.com/shared-article" }
            assertEquals("Shared Article Title", savedEntry.title)
            assertEquals(listOf("reading", "systems"), savedEntry.tags)

            collectJob.cancel()
        }

    @Test
    fun `UpdateReadProgress persists clamped progress to repository`() = runTest {
        database.dao().upsertItems(SeedData.items)
        val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

        viewModel.dispatch(MainAction.UpdateReadProgress(entryId = "item-1", progress = 0.65))

        val updated =
            repository.snapshot
                .first { snap ->
                    snap.entries.any { it.id == "item-1" && abs(it.readProgress - 0.65) < 1e-6 }
                }
                .entries
                .first { it.id == "item-1" }
        assertEquals(0.65, updated.readProgress, 1e-6)

        viewModel.dispatch(MainAction.UpdateReadProgress(entryId = "item-1", progress = 1.4))

        val clamped =
            repository.snapshot
                .first { snap ->
                    snap.entries.any { it.id == "item-1" && abs(it.readProgress - 1.0) < 1e-6 }
                }
                .entries
                .first { it.id == "item-1" }
        assertEquals(1.0, clamped.readProgress, 1e-6)

        collectJob.cancel()
    }

    @Test
    internal fun `DeleteEntry DeleteFeed RefreshFeed and SyncNow update UI state and isSyncing`() =
        runTest {
            database.dao().upsertFeeds(SeedData.feeds)
            database.dao().upsertItems(SeedData.items)
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

            // Select a feed and open reader + context sheet for item-1
            viewModel.dispatch(MainAction.FilterByFeed("feed-arxiv-cs-lg"))
            viewModel.dispatch(MainAction.OpenReader("item-1"))
            viewModel.dispatch(MainAction.OpenContextSheet("item-1"))
            val beforeDelete =
                viewModel.uiState.first { state ->
                    state.allEntries.isNotEmpty() && state.selectedReaderEntryId == "item-1"
                }
            assertEquals("item-1", beforeDelete.selectedReaderEntryId)

            // Archive sets undoEvent, and DeleteEntry clears undoEvent while setting statusBanner
            val item2 = beforeDelete.allEntries.first { it.id == "item-2" }
            viewModel.dispatch(MainAction.ToggleArchive(item2))
            val afterArchive = viewModel.uiState.first { it.undoEvent != null }
            assertEquals("item-2", afterArchive.undoEvent?.itemId)
            assertNull(afterArchive.statusBanner)

            // DeleteEntry closes reader & context sheet, clears undoEvent, and removes entry
            viewModel.dispatch(MainAction.DeleteEntry("item-1"))
            val afterDeleteEntry =
                viewModel.uiState.first { state ->
                    state.allEntries.none { it.id == "item-1" } &&
                        state.statusBanner?.startsWith("Deleted") == true
                }
            assertNull(afterDeleteEntry.selectedReaderEntryId)
            assertNull(afterDeleteEntry.contextSheetEntryId)
            assertNull(afterDeleteEntry.undoEvent)
            assertTrue(afterDeleteEntry.statusBanner?.startsWith("Deleted") == true)

            // DeleteFeed clears selectedFeedId when that feed was selected
            viewModel.dispatch(MainAction.DeleteFeed("feed-arxiv-cs-lg"))
            val afterDeleteFeed =
                viewModel.uiState.first { state ->
                    state.feeds.none { it.id == "feed-arxiv-cs-lg" } &&
                        state.statusBanner?.startsWith("Unsubscribed") == true
                }
            assertNull(afterDeleteFeed.selectedFeedId)
            assertTrue(afterDeleteFeed.statusBanner?.startsWith("Unsubscribed") == true)

            // StartupSyncIfConfigured is silent when unconfigured or offline, and resets isSyncing
            viewModel.dispatch(MainAction.DismissMessages)
            val dismissed = viewModel.uiState.first { it.statusBanner == null }
            assertFalse(dismissed.isSyncing)
            viewModel.triggerStartupSyncIfConfigured()
            assertFalse(viewModel.uiState.value.isSyncing)
            assertNull(viewModel.uiState.value.statusBanner)

            // SyncNow sets statusBanner and resets isSyncing to false in finally block
            viewModel.dispatch(MainAction.SyncNow)
            val afterSync =
                viewModel.uiState.first {
                    !it.isSyncing && it.statusBanner?.startsWith("Sync offline") == true
                }
            assertFalse(afterSync.isSyncing)
            assertTrue(afterSync.statusBanner?.startsWith("Sync offline") == true)

            // RefreshFeed sets statusBanner and resets isSyncing to false in finally block
            viewModel.dispatch(MainAction.DismissMessages)
            viewModel.uiState.first { it.statusBanner == null }
            viewModel.dispatch(MainAction.RefreshFeed("feed-2"))
            val afterRefresh =
                viewModel.uiState.first {
                    !it.isSyncing && it.statusBanner?.startsWith("Refresh failed") == true
                }
            assertFalse(afterRefresh.isSyncing)

            collectJob.cancel()
        }

    @Test
    internal fun `OpenReader marks entry read SelectTab Inbox clears feed scope and SetBottomNavSlot swaps slots`() =
        runTest {
            database.dao().upsertFeeds(SeedData.feeds)
            database.dao().upsertItems(SeedData.items)
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

            viewModel.dispatch(MainAction.SelectTab(AppTab.FEEDS))
            viewModel.dispatch(MainAction.FilterByFeed("feed-arxiv-cs-lg"))
            val scoped = viewModel.uiState.first { it.selectedFeedId == "feed-arxiv-cs-lg" }
            assertEquals("feed-arxiv-cs-lg", scoped.selectedFeedId)
            assertEquals(AppTab.FEEDS, scoped.scopeOriginTab)

            viewModel.dispatch(MainAction.NavigateBackFromScope)
            val returned =
                viewModel.uiState.first {
                    it.selectedFeedId == null && it.activeTab == AppTab.FEEDS
                }
            assertNull(returned.selectedFeedId)
            assertNull(returned.scopeOriginTab)

            viewModel.dispatch(MainAction.FilterByFeed("feed-arxiv-cs-lg"))
            viewModel.uiState.first { it.selectedFeedId == "feed-arxiv-cs-lg" }
            viewModel.dispatch(MainAction.SelectTab(AppTab.INBOX))
            val unscoped = viewModel.uiState.first { it.selectedFeedId == null }
            assertNull(unscoped.selectedFeedId)
            assertNull(unscoped.selectedTag)

            viewModel.dispatch(MainAction.OpenReader("item-1"))
            val afterRead =
                viewModel.uiState.first { state ->
                    state.allEntries.any { it.id == "item-1" && it.isRead && !it.isUnread }
                }
            assertTrue(afterRead.visibleEntries.any { it.id == "item-1" })
            assertTrue(!afterRead.allEntries.first { it.id == "item-1" }.readAt.isNullOrBlank())

            viewModel.dispatch(MainAction.ToggleReadState("item-1"))
            val afterUnread =
                viewModel.uiState.first { state ->
                    state.statusBanner == "Marked as unread" &&
                        state.allEntries.any { it.id == "item-1" && !it.isRead && it.isUnread }
                }
            assertNull(afterUnread.allEntries.first { it.id == "item-1" }.readAt)

            viewModel.dispatch(MainAction.SetBottomNavSlot(1, AppTab.OFFLINE))
            val afterNavUpdate =
                viewModel.uiState.first { it.settings.bottomNavTabs[1] == AppTab.OFFLINE }
            assertEquals(
                listOf(AppTab.FEEDS, AppTab.OFFLINE, AppTab.HOME, AppTab.SETTINGS),
                afterNavUpdate.settings.bottomNavTabs,
            )

            viewModel.dispatch(MainAction.ResetBottomNavTabs)
            val afterNavReset =
                viewModel.uiState.first { it.settings.bottomNavTabs == AppTab.defaultSideTabs }
            assertEquals(AppTab.defaultSideTabs, afterNavReset.settings.bottomNavTabs)
            collectJob.cancel()
        }

    @Test
    internal fun `observeFeeds observeTags and sortAndFilterFeeds sort case insensitively`() =
        runTest {
            val mixedFeeds =
                listOf(
                    testFeed("f-hn", "Hacker News"),
                    testFeed("f-arx", "arXiv cs.LG"),
                    testFeed("f-ars", "Ars Technica"),
                    testFeed("f-dan", "danluu.com"),
                )
            val mixedTags =
                listOf(
                    TagEntity(id = "t-sql", name = "SQLite"),
                    TagEntity(id = "t-arx", name = "arXiv"),
                    TagEntity(id = "t-dist", name = "Distributed"),
                    TagEntity(id = "t-comp", name = "compilers"),
                )
            database.dao().upsertFeeds(mixedFeeds)
            database.dao().upsertTags(mixedTags)
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
            val state =
                viewModel.uiState.first {
                    it.feeds.size == mixedFeeds.size && it.tags.size == mixedTags.size
                }
            assertEquals(
                listOf("Ars Technica", "arXiv cs.LG", "danluu.com", "Hacker News"),
                state.feeds.map { it.title },
            )
            assertEquals(
                listOf("arXiv", "compilers", "Distributed", "SQLite"),
                state.tags.map { it.name },
            )
            val unreadSorted =
                sortAndFilterFeeds(
                    feeds = state.feeds.mapIndexed { idx, feed -> feed.copy(unreadCount = idx) },
                    sortOrder = FeedSortOrder.UNREAD_FIRST,
                    unreadOnly = true,
                )
            assertEquals(
                listOf("Hacker News", "danluu.com", "arXiv cs.LG"),
                unreadSorted.map { it.title },
            )
            collectJob.cancel()
        }

    @Test
    internal fun `extractCleanDomain and favicon resolvers handle unescaped path characters`() {
        val urlWithSpaces = "https://www.ArsTechnica.com:8443/2026/04/my article [draft]|v2?q=a b#s"
        assertEquals("arstechnica.com", extractCleanDomain(urlWithSpaces))
        assertEquals(
            "https://www.arstechnica.com:8443/apple-touch-icon.png",
            resolveOriginFaviconUrl(urlWithSpaces),
        )
        assertEquals(
            "https://icons.duckduckgo.com/ip3/arstechnica.com.ico",
            resolveFallbackFaviconUrl(urlWithSpaces),
        )
        assertEquals("", extractCleanDomain("   "))
        assertNull(resolveOriginFaviconUrl("not a url"))
        assertNull(resolveFallbackFaviconUrl("http://localhost:8080/feed.xml"))
    }

    @Test
    internal fun `cross peer rescue check and on device poll action update ui state`() = runTest {
        val localPeerId = "peer-local-1"
        val onDeviceFeed =
            FeedItem(
                id = "f-1",
                title = "Local Feed",
                url = "https://example.com/1.xml",
                siteUrl = null,
                feedType = com.grayvines.roche.model.FeedType.RSS,
                unreadCount = 0,
                lastPolledBy = localPeerId,
            )
        val rescueFeed =
            onDeviceFeed.copy(
                id = "f-3",
                lastPolledBy = "server",
                lastError = "HTTP 403",
                lastErrorBy = "server",
            )
        assertFalse(onDeviceFeed.isCrossPeerRescueError(localPeerId))
        assertTrue(rescueFeed.isCrossPeerRescueError(localPeerId))
        assertEquals(
            "1 of 3 feeds last polled on-device · 1 peer error rescue ready",
            buildPeerMeshHealthSummary(
                totalFeeds = 3,
                onDevicePolledCount = 1,
                rescueReadyCount = 1,
            ),
        )

        val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
        viewModel.dispatch(MainAction.PollFeedsOnDeviceNow)
        val updated =
            viewModel.uiState.first {
                it.statusBanner == "All cooperative feeds are up to date"
            }
        assertEquals("All cooperative feeds are up to date", updated.statusBanner)
        collectJob.cancel()
    }

    @Test
    internal fun `rename feed bulk tag archive unread and reader font scale update state`() =
        runTest {
            database.dao().upsertFeeds(listOf(testFeed("f-polish", "Old Title")))
            database
                .dao()
                .upsertItem(
                    testItem(
                        id = "i-polish",
                        title = "Speculative Paper",
                        feedId = "f-polish",
                        status = "unread",
                    )
                )
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

            viewModel.dispatch(MainAction.SetReaderFontScale(1.15f))
            val scaled = viewModel.uiState.first { it.settings.readerFontScale == 1.15f }
            assertEquals(1.15f, scaled.settings.readerFontScale, 0.001f)

            viewModel.dispatch(MainAction.RenameFeed("f-polish", "Renamed Feed"))
            val renamed =
                viewModel.uiState.first { state ->
                    state.feeds.any { it.id == "f-polish" && it.title == "Renamed Feed" } &&
                        state.visibleEntries.firstOrNull { it.id == "i-polish" }?.feedTitle ==
                            "Renamed Feed" &&
                        state.statusBanner == "Renamed feed to \"Renamed Feed\""
                }
            assertEquals(
                "Renamed Feed",
                renamed.visibleEntries.first { it.id == "i-polish" }.feedTitle,
            )

            viewModel.dispatch(MainAction.ApplyTagToFeed("f-polish", "Systems"))
            val tagged =
                viewModel.uiState.first { state ->
                    state.visibleEntries
                        .firstOrNull { it.id == "i-polish" }
                        ?.tags
                        ?.contains("systems") == true &&
                        state.tags.any { it.name == "systems" } &&
                        state.statusBanner == "Tagged 1 entry with #systems"
                }
            assertTrue(tagged.tags.any { it.name == "systems" })

            viewModel.dispatch(MainAction.ArchiveFeedUnread("f-polish"))
            val archived =
                viewModel.uiState.first { state ->
                    state.visibleEntries.none { it.id == "i-polish" } && state.undoEvent != null
                }
            assertTrue(archived.undoEvent?.message?.isNotBlank() == true)
            collectJob.cancel()
        }

    @Test
    internal fun `toggle feed archive moves feed to archivedFeeds and preserves reading history`() =
        runTest {
            database.dao().upsertFeeds(listOf(testFeed("f-archive", "Archivable Feed")))
            database
                .dao()
                .upsertItem(
                    testItem(
                        id = "i-archive",
                        title = "Preserved Entry",
                        feedId = "f-archive",
                        status = "unread",
                    )
                )
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }

            viewModel.dispatch(MainAction.OpenFeedContextSheet("f-archive"))
            viewModel.uiState.first { it.feedContextSheetId == "f-archive" }
            viewModel.dispatch(MainAction.ToggleFeedArchive("f-archive"))
            val feedArchived =
                viewModel.uiState.first { state ->
                    state.archivedFeeds.any { it.id == "f-archive" } &&
                        state.activeFeeds.none { it.id == "f-archive" } &&
                        state.feedContextSheetId == null &&
                        state.statusBanner?.startsWith("Archived feed") == true
                }
            assertTrue(feedArchived.statusBanner?.startsWith("Archived feed") == true)
            assertTrue(feedArchived.allEntries.any { it.id == "i-archive" })

            viewModel.dispatch(MainAction.ToggleFeedArchive("f-archive"))
            val feedUnarchived =
                viewModel.uiState.first { state ->
                    state.activeFeeds.any { it.id == "f-archive" } &&
                        state.archivedFeeds.none { it.id == "f-archive" } &&
                        state.statusBanner?.startsWith("Unarchived feed") == true
                }
            assertTrue(feedUnarchived.statusBanner?.startsWith("Unarchived feed") == true)
            collectJob.cancel()
        }

    @Test
    internal fun `search scope filters match snippets and html headings extract cleanly`() {
        val entry =
            testItem(id = "i-1", title = "Title").let {
                com.grayvines.roche.model.InboxEntry(
                    id = it.id,
                    feedId = it.feedId,
                    feedTitle = it.feedTitle,
                    title = "Distributed Speculative Decoding",
                    url = it.url,
                    author = "A. Author",
                    summary = "Wait-free verification schedules reduce token latency on edge.",
                    contentText = null,
                    contentHtml = "<h2>Introduction</h2><p>Body</p><h3>Benchmarks</h3>",
                    contentType = com.grayvines.roche.model.ContentType.PDF,
                    status = com.grayvines.roche.model.ItemStatus.UNREAD,
                    isFavorite = true,
                    isCachedOffline = false,
                    publishedAt = "1m",
                    createdAt = it.createdAt,
                    readProgress = 0.0,
                    thumbnailMediaId = null,
                    duration = null,
                    tags = emptyList(),
                )
            }
        assertTrue(
            com.grayvines.roche.data.matchesSearchScopeFilter(
                entry,
                com.grayvines.roche.data.SearchScopeFilter.PAPERS,
            )
        )
        assertFalse(
            com.grayvines.roche.data.matchesSearchScopeFilter(
                entry,
                com.grayvines.roche.data.SearchScopeFilter.VIDEOS,
            )
        )
        val snippet = com.grayvines.roche.data.extractSearchMatchSnippet(entry, "latency")
        assertTrue(snippet?.contains("latency", ignoreCase = true) == true)
        assertNull(com.grayvines.roche.data.extractSearchMatchSnippet(entry, "Speculative"))
        assertEquals(
            listOf("Introduction", "Benchmarks"),
            extractPlainTextHeadings(parseHtmlArticleBlocks(entry.contentHtml.orEmpty())),
        )
    }

    @Test
    internal fun `initial load emits persisted Room items before startup sync and reader loads full oversized html`() =
        runTest {
            val oversizedHtml = "<p>" + "A".repeat(100_000) + "</p><p>Tail Marker</p>"
            database
                .dao()
                .upsertItem(
                    testItem(id = "item-large", title = "Persisted Oversized Entry")
                        .copy(contentHtml = oversizedHtml, contentText = "B".repeat(90_000))
                )

            assertTrue(viewModel.uiState.value.isInitialLoading)
            assertTrue(viewModel.uiState.value.visibleEntries.isEmpty())

            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
            val loadedState = viewModel.uiState.first { !it.isInitialLoading }
            assertFalse(loadedState.isInitialLoading)
            assertEquals(1, loadedState.visibleEntries.size)
            val listEntry = loadedState.visibleEntries.single()
            assertEquals("Persisted Oversized Entry", listEntry.title)
            assertEquals(
                com.grayvines.roche.data.local.LIST_PREVIEW_HTML_CHARS,
                listEntry.contentHtml?.length,
            )
            assertEquals(
                com.grayvines.roche.data.local.LIST_PREVIEW_TEXT_CHARS,
                listEntry.contentText?.length,
            )

            viewModel.dispatch(MainAction.OpenReader("item-large"))
            val readerState =
                viewModel.uiState.first {
                    it.activeReaderEntry?.isRead == true &&
                        it.activeReaderEntry?.contentHtml?.contains("Tail Marker") == true
                }
            assertEquals(oversizedHtml.length, readerState.activeReaderEntry?.contentHtml?.length)

            viewModel.dispatch(MainAction.CloseReader)
            val closedState = viewModel.uiState.first { it.selectedReaderEntryId == null }
            assertNull(closedState.readerContent)

            val mathPlainText = "if x < 5 " + "word ".repeat(540) + "then y > 10"
            assertEquals("3m", com.grayvines.roche.model.formatArticleMinutes(mathPlainText))

            collectJob.cancel()
        }

    @Test
    internal fun `interleaved queue and audio autoplay skip non-audio articles and apply podcast speed`() =
        runTest {
            val podcastFeed =
                FeedEntity(
                    id = "f-podcast",
                    title = "Oxide and Friends",
                    url = "https://oxide.computer/podcasts/rss.xml",
                    siteUrl = "https://oxide.computer",
                    feedType = "podcast",
                    autoDownloadEpisodes = true,
                    customPlaybackSpeed = 2.65f,
                )
            val ep1 =
                testItem(id = "ep-1", title = "Ep 1: Async Rust", feedId = "f-podcast")
                    .copy(
                        feedTitle = "Oxide and Friends",
                        contentType = "audio",
                        audioUrl = "https://cdn.oxide.computer/ep1.mp3",
                        duration = "01:12:00",
                        summary = "<p>00:00 Intro<br/>12:30 Tokio Internals<br/>45:10 Q&amp;A</p>",
                    )
            val articleMid =
                testItem(id = "art-mid", title = "Interleaved Article", feedId = "f-1")
                    .copy(contentType = "article")
            val ep2 =
                testItem(id = "ep-2", title = "Ep 2: Hubris OS", feedId = "f-podcast")
                    .copy(
                        feedTitle = "Oxide and Friends",
                        contentType = "audio",
                        audioUrl = "https://cdn.oxide.computer/ep2.mp3",
                        duration = "54:20",
                    )
            database.dao().upsertFeeds(listOf(testFeed("f-1", "Systems Blog"), podcastFeed))
            database.dao().upsertItems(listOf(ep1, articleMid, ep2))

            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
            viewModel.uiState.first { it.allEntries.size == 3 }

            // Queue: ep-1, art-mid, ep-2
            viewModel.dispatch(MainAction.AddToQueue("ep-1"))
            viewModel.dispatch(MainAction.AddToQueue("art-mid"))
            viewModel.dispatch(MainAction.AddToQueue("ep-2"))
            val queuedState = viewModel.uiState.first { it.queueEntries.size == 3 }
            assertEquals(listOf("ep-1", "art-mid", "ep-2"), queuedState.queueEntries.map { it.id })
            assertEquals("ep-1", queuedState.nextQueuedAudioEntryId)

            // Play ep-1: applies podcast customPlaybackSpeed (2.65x), parses chapters, sets
            // nextQueuedAudioEntryId to ep-2 (skipping art-mid)
            viewModel.dispatch(MainAction.PlayAudioEntry("ep-1", openFullscreen = true))
            val playingEp1 =
                viewModel.uiState.first {
                    it.playerState.currentEntryId == "ep-1" && it.playerState.isFullscreenOpen
                }
            assertEquals(2.65f, playingEp1.playerState.playbackSpeed, 1e-3f)
            assertEquals(3, playingEp1.playerState.chapters.size)
            assertEquals("Tokio Internals", playingEp1.playerState.chapters[1].title)
            assertEquals("ep-2", playingEp1.nextQueuedAudioEntryId)

            // Seek ep-1 to 25%, then SkipToNextQueuedAudio: must NOT archive ep-1 or mark its
            // progress 1.0; rotates ep-1 behind ep-2 and starts ep-2
            viewModel.dispatch(MainAction.SeekPlayerToFraction(0.25f))
            viewModel.uiState.first {
                abs(it.allEntries.first { e -> e.id == "ep-1" }.readProgress - 0.25) < 1e-2
            }
            viewModel.dispatch(MainAction.SkipToNextQueuedAudio)
            val playingEp2 =
                viewModel.uiState.first {
                    it.playerState.currentEntryId == "ep-2" &&
                        it.queueEntries.map { e -> e.id } == listOf("art-mid", "ep-2", "ep-1") &&
                        it.listeningHistoryEntries.size == 2
                }
            val skippedEp1 = playingEp2.allEntries.first { it.id == "ep-1" }
            assertFalse(skippedEp1.isArchived)
            assertEquals(0.25, skippedEp1.readProgress, 1e-2)
            assertEquals(
                listOf("art-mid", "ep-2", "ep-1"),
                playingEp2.queueEntries.map { it.id },
            )
            assertFalse(playingEp2.allEntries.first { it.id == "art-mid" }.isArchived)

            // Actual episode completion DOES mark ep-2 100% read & archived and autoplays ep-1
            viewModel.playerController.completeActiveEpisodeNow()
            val afterEp2Completed =
                viewModel.uiState.first {
                    it.playerState.currentEntryId == "ep-1" &&
                        it.allEntries.first { e -> e.id == "ep-2" }.isArchived
                }
            val ep2History = afterEp2Completed.listeningHistoryEntries.first { it.id == "ep-2" }
            val ep1History = afterEp2Completed.listeningHistoryEntries.first { it.id == "ep-1" }
            assertEquals(1.0, ep2History.readProgress, 1e-3)
            assertTrue(!ep1History.readAt.isNullOrBlank())
            assertTrue(!ep2History.readAt.isNullOrBlank())
            assertEquals("Played now · Completed", ep2History.formattedListeningHistoryLabel)
            assertEquals(listOf("art-mid", "ep-1"), afterEp2Completed.queueEntries.map { it.id })

            // Verify favoriting/starring a podcast episode adds it to starredEntries
            viewModel.dispatch(MainAction.ToggleStar("ep-1"))
            val starredState =
                viewModel.uiState.first { state ->
                    state.starredEntries.any { it.id == "ep-1" }
                }
            assertTrue(starredState.allEntries.first { it.id == "ep-1" }.isFavorite)

            // Reorder queue and test speed & podcast settings
            viewModel.dispatch(MainAction.MoveQueueItemUp("ep-1"))
            val reordered =
                viewModel.uiState.first {
                    it.queueEntries.map { e -> e.id } == listOf("ep-1", "art-mid")
                }
            assertEquals(listOf("ep-1", "art-mid"), reordered.queueEntries.map { it.id })

            viewModel.dispatch(
                MainAction.SetPlaybackSpeed(speed = 2.75f, applyToCurrentPodcast = true)
            )
            val updatedFeedSpeed =
                viewModel.uiState.first {
                    abs((it.activePlayerFeed?.customPlaybackSpeed ?: 0f) - 2.75f) < 1e-3f
                }
            assertEquals(2.75f, updatedFeedSpeed.playerState.playbackSpeed, 1e-3f)

            viewModel.playerController.stopAndClear()
            collectJob.cancel()
        }

    @Test
    fun `mediaSessionBridge handles lock screen transport audio focus and bluetooth headset keys`() =
        runTest {
            val podcastFeed = testFeed("f-pod", "Systems Cast").copy(feedType = "podcast")
            val ep1 =
                testItem("ep-a", "Episode A", "f-pod")
                    .copy(
                        feedTitle = "Systems Cast",
                        contentType = "audio",
                        audioUrl = "https://example.com/a.mp3",
                        duration = "30:00",
                    )
            val ep2 =
                testItem("ep-b", "Episode B", "f-pod")
                    .copy(
                        feedTitle = "Systems Cast",
                        contentType = "audio",
                        audioUrl = "https://example.com/b.mp3",
                        duration = "40:00",
                    )
            database.dao().upsertFeeds(listOf(podcastFeed))
            database.dao().upsertItems(listOf(ep1, ep2))

            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
            viewModel.uiState.first { it.allEntries.size == 2 }
            viewModel.dispatch(MainAction.AddToQueue("ep-a"))
            viewModel.dispatch(MainAction.AddToQueue("ep-b"))
            viewModel.uiState.first { it.queueEntries.size == 2 }
            viewModel.dispatch(MainAction.PlayAudioEntry("ep-a"))

            viewModel.uiState.first {
                it.playerState.currentEntryId == "ep-a" && it.playerState.hasNextQueuedAudio
            }
            val epASnapshot =
                mediaSessionBridge.snapshots.first {
                    it?.entryId == "ep-a" && it.hasNextQueuedAudio
                }
            assertTrue(mediaSessionBridge.focusRequestedCount > 0)
            assertEquals("Episode A", epASnapshot?.episodeTitle)
            assertTrue(epASnapshot?.hasNextQueuedAudio == true)

            // Seek, Fast-forward 30s & Rewind 15s via MediaSession commands
            mediaSessionBridge.callbacks?.onSeekToMs(60_000L)
            assertEquals(60_000L, viewModel.playerController.state.value.positionMs)
            mediaSessionBridge.callbacks?.onMediaCommand(MediaSessionCommand.FAST_FORWARD_30S)
            assertEquals(90_000L, viewModel.playerController.state.value.positionMs)
            mediaSessionBridge.callbacks?.onMediaCommand(MediaSessionCommand.REWIND_15S)
            assertEquals(75_000L, viewModel.playerController.state.value.positionMs)

            // Transient audio focus loss pauses playback; regaining focus auto-resumes
            mediaSessionBridge.callbacks?.onAudioFocusLost(transient = true)
            assertFalse(viewModel.playerController.state.value.isPlaying)
            mediaSessionBridge.callbacks?.onAudioFocusGained()
            assertTrue(viewModel.playerController.state.value.isPlaying)

            // Unplugging headphones (AUDIO_BECOMING_NOISY) pauses and does not auto-resume
            mediaSessionBridge.callbacks?.onAudioBecomingNoisy()
            assertFalse(viewModel.playerController.state.value.isPlaying)
            mediaSessionBridge.callbacks?.onAudioFocusGained()
            assertFalse(viewModel.playerController.state.value.isPlaying)

            // Hardware keycode mapping & lock-screen Skip to Next advances to ep-b even when
            // uiState has zero active UI subscribers (app in background).
            collectJob.cancel()
            assertEquals(
                MediaSessionCommand.TOGGLE_PLAY_PAUSE,
                mapMediaKeyCodeToCommand(android.view.KeyEvent.KEYCODE_HEADSETHOOK),
            )
            assertEquals(
                MediaSessionCommand.SKIP_TO_NEXT,
                mapMediaKeyCodeToCommand(android.view.KeyEvent.KEYCODE_MEDIA_NEXT),
            )
            mediaSessionBridge.callbacks?.onMediaCommand(MediaSessionCommand.SKIP_TO_NEXT)
            viewModel.playerController.state.first { it.currentEntryId == "ep-b" }
            val epBSnapshot =
                mediaSessionBridge.snapshots.first {
                    it?.entryId == "ep-b" && it.hasNextQueuedAudio
                }
            assertEquals("Episode B", epBSnapshot?.episodeTitle)
            assertTrue(epBSnapshot?.hasNextQueuedAudio == true)
            assertFalse(repository.snapshot.first().entries.first { it.id == "ep-a" }.isArchived)

            // Changing playback speed from MediaSession respects podcast customPlaybackSpeed
            viewModel.dispatch(MainAction.SetRememberPodcastSpeed("f-pod", true))
            repository.snapshot.first { snap ->
                snap.feeds.firstOrNull { it.id == "f-pod" }?.customPlaybackSpeed != null
            }
            mediaSessionBridge.callbacks?.onSetPlaybackSpeed(1.75f)
            val updatedSnap =
                repository.snapshot.first { snap ->
                    val spd = snap.feeds.firstOrNull { it.id == "f-pod" }?.customPlaybackSpeed ?: 0f
                    abs(spd - 1.75f) < 1e-3f
                }
            assertEquals(1.75f, viewModel.playerController.state.value.playbackSpeed, 1e-3f)
            assertEquals(
                1.75f,
                updatedSnap.feeds.first { it.id == "f-pod" }.customPlaybackSpeed ?: 0f,
                1e-3f,
            )
            viewModel.playerController.stopAndClear()
        }

    @Test
    internal fun `playback backend failure stops playback without silent ticking and SetSmartRewind persists`() =
        runTest {
            val podcastFeed = testFeed("f-pod", "Systems Cast").copy(feedType = "podcast")
            val ep =
                testItem("ep-fail", "Unreachable Episode", "f-pod")
                    .copy(
                        feedTitle = "Systems Cast",
                        contentType = "audio",
                        audioUrl = "https://example.com/unreachable.mp3",
                        duration = "20:00",
                    )
            database.dao().upsertFeeds(listOf(podcastFeed))
            database.dao().upsertItems(listOf(ep))

            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
            viewModel.uiState.first { it.allEntries.size == 1 }

            playerBackend.failNextLoad = true
            viewModel.dispatch(MainAction.PlayAudioEntry("ep-fail"))
            val failedState =
                viewModel.uiState.first {
                    it.statusBanner == PodcastPlayerController.DEFAULT_PLAYBACK_ERROR_MESSAGE
                }
            assertFalse(failedState.playerState.isPlaying)
            assertFalse(failedState.playerState.isBuffering)
            assertFalse(failedState.allEntries.first { it.id == "ep-fail" }.isArchived)
            assertEquals(
                0.0,
                failedState.allEntries.first { it.id == "ep-fail" }.readProgress,
                1e-4,
            )

            // Recovering when connection returns reloads and plays cleanly
            playerBackend.failNextLoad = false
            viewModel.dispatch(MainAction.TogglePlayPause)
            val resumedState = viewModel.uiState.first { it.playerState.isPlaying }
            assertTrue(resumedState.playerState.isPlaying)

            // Toggle Smart Rewind off and back on
            viewModel.dispatch(MainAction.SetSmartRewind(false))
            val rewindOff = viewModel.uiState.first { !it.settings.smartRewindEnabled }
            assertFalse(rewindOff.playerState.smartRewindEnabled)
            viewModel.dispatch(MainAction.SetSmartRewind(true))
            val rewindOn = viewModel.uiState.first { it.settings.smartRewindEnabled }
            assertTrue(rewindOn.playerState.smartRewindEnabled)

            viewModel.playerController.stopAndClear()
            collectJob.cancel()
        }

    @Test
    internal fun `HTML anchor sanitizer resolves relative links and ThumbnailLoader enforces bounds and cooldown`() =
        runTest {
            assertEquals(
                "https://blog.example.com/2026/other-post",
                resolveSafeAnchorHref("/2026/other-post", "https://blog.example.com/posts/intro"),
            )
            assertEquals(
                "https://blog.example.com/posts/part-2.html",
                resolveSafeAnchorHref("part-2.html", "https://blog.example.com/posts/intro"),
            )
            assertEquals(
                "mailto:editor@example.com",
                resolveSafeAnchorHref("mailto:editor@example.com", "https://blog.example.com"),
            )
            assertNull(resolveSafeAnchorHref("javascript:alert(1)", "https://blog.example.com"))
            assertNull(resolveSafeAnchorHref("/relative/only", null))

            val blocks =
                parseHtmlArticleBlocks(
                    rawHtml =
                        """<p>Read <a href="/archive">the archive</a> or <a href="javascript:void(0)">bad link</a>.</p>""",
                    baseUrl = "https://blog.example.com/entry/1",
                )
            val paragraphHtml = (blocks.single() as ArticleBlock.Paragraph).html
            assertTrue("""href="https://blog.example.com/archive"""" in paragraphHtml)
            assertFalse("javascript:" in paragraphHtml)
            assertTrue("bad link" in paragraphHtml)

            // Tall 1200x12000 image is downsampled so neither dimension exceeds 2048px
            val tallSample = calculateInSampleSize(1200, 12_000, 1440, 1440)
            assertTrue(12_000 / tallSample <= MAX_BITMAP_DIMENSION_PX)

            // ThumbnailLoader retries transient failures after FAILED_RETRY_COOLDOWN_MS
            var nowMs = 1_000L
            var networkSucceeds = false
            var fetchCalls = 0
            val flakyClient = RocheApiClient {
                fetchCalls++
                if (networkSucceeds) {
                    HttpResponse(statusCode = 200, body = "img", bodyBytes = byteArrayOf(1, 2, 3))
                } else {
                    HttpResponse(statusCode = 503, body = "")
                }
            }
            val loader =
                ThumbnailLoader(
                    apiClient = flakyClient,
                    settings = settings,
                    decoder = { fakeImageBitmap(width = 64, height = 64) },
                    clockMs = { nowMs },
                )
            assertNull(loader.loadThumbnail("https://example.com/thumb.jpg"))
            assertEquals(1, fetchCalls)

            // Within 60s cooldown, avoids hammering failed URL
            networkSucceeds = true
            nowMs += 10_000L
            assertNull(loader.loadThumbnail("https://example.com/thumb.jpg"))
            assertEquals(1, fetchCalls)

            // After 60s cooldown expires, retries and caches decoded bitmap
            nowMs += ThumbnailLoader.FAILED_RETRY_COOLDOWN_MS
            val recovered = loader.loadThumbnail("https://example.com/thumb.jpg")
            assertEquals(64, recovered?.width)
            assertEquals(2, fetchCalls)
        }

    @Test
    internal fun `uiState caches localNetworkTier without IPC on ticks and refreshes on sync`() =
        runTest {
            var currentTier = NetworkTier.CELLULAR
            var tierQueryCount = 0
            val countingRepo =
                RocheRepository(
                    database = database,
                    apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") },
                    settings = settings,
                    networkTierProvider =
                        NetworkTierProvider {
                            tierQueryCount++
                            currentTier
                        },
                )
            val localBackend = FakeAudioPlayerBackend()
            val localVm =
                MainViewModel(
                    repository = countingRepo,
                    settingsRepository = settings,
                    playerBackend = localBackend,
                    mediaSessionBridge = RecordingMediaSessionBridge(),
                )
            assertEquals(1, tierQueryCount)

            val collectJob = backgroundScope.launch { localVm.uiState.collect() }
            val initial = localVm.uiState.first { !it.isInitialLoading }
            assertEquals(NetworkTier.CELLULAR, initial.localNetworkTier)
            assertEquals(1, tierQueryCount)

            val podcastFeed = testFeed("f-tier-pod", "Systems Cast").copy(feedType = "podcast")
            val episode =
                testItem("ep-tier-1", "Episode 1", "f-tier-pod")
                    .copy(
                        contentType = "audio",
                        audioUrl = "https://example.com/tier-1.mp3",
                        duration = "10:00",
                    )
            database.dao().upsertFeeds(listOf(podcastFeed))
            database.dao().upsertItems(listOf(episode))
            localVm.uiState.first { it.allEntries.size == 1 }

            localVm.dispatch(MainAction.SelectTab(AppTab.FEEDS))
            localVm.uiState.first { it.activeTab == AppTab.FEEDS }
            localVm.dispatch(MainAction.UpdateSearchQuery("Episode"))
            localVm.uiState.first { it.searchQuery == "Episode" }
            localVm.dispatch(MainAction.PlayAudioEntry("ep-tier-1"))
            localVm.uiState.first { it.playerState.currentEntryId == "ep-tier-1" }
            localVm.dispatch(MainAction.SeekPlayerToMs(10_100L))
            localVm.uiState.first { it.playerState.positionMs == 10_100L }
            assertEquals(1, tierQueryCount)

            var playerEmissions = 0
            val playerCollectJob =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    localVm.playerController.state.collect { playerEmissions++ }
                }
            assertEquals(1, playerEmissions)

            // Stalled and sub-second ticks within the same whole second do not emit state updates
            assertTrue(localVm.playerController.advanceSingleTick(tickCount = 1))
            assertEquals(1, playerEmissions)
            localBackend.setSimulatedPositionMs(10_850L)
            assertTrue(localVm.playerController.advanceSingleTick(tickCount = 2))
            assertEquals(1, playerEmissions)

            // Crossing a whole-second boundary emits once and still avoids ConnectivityManager IPC
            localBackend.setSimulatedPositionMs(11_200L)
            assertTrue(localVm.playerController.advanceSingleTick(tickCount = 3))
            assertEquals(2, playerEmissions)
            assertEquals(11_200L, localVm.uiState.value.playerState.positionMs)
            assertEquals(1, tierQueryCount)

            // Active sleep timer ticks still emit countdown updates even when position is stalled
            localVm.playerController.setSleepTimerMinutes(1)
            assertEquals(3, playerEmissions)
            assertTrue(localVm.playerController.advanceSingleTick(tickCount = 4))
            assertEquals(4, playerEmissions)
            assertEquals(59, localVm.playerController.state.value.sleepTimerRemainingSec)
            playerCollectJob.cancel()

            // SyncNow and PollFeedsOnDeviceNow refresh localNetworkTier in TransientUiState
            currentTier = NetworkTier.WIFI
            assertEquals(NetworkTier.CELLULAR, localVm.uiState.value.localNetworkTier)
            localVm.dispatch(MainAction.SyncNow)
            val afterSync =
                localVm.uiState.first {
                    !it.isSyncing && it.localNetworkTier == NetworkTier.WIFI
                }
            assertEquals(NetworkTier.WIFI, afterSync.localNetworkTier)
            assertTrue(tierQueryCount > 1)

            currentTier = NetworkTier.OFFLINE
            localVm.dispatch(MainAction.PollFeedsOnDeviceNow)
            val afterPoll =
                localVm.uiState.first {
                    !it.isSyncing && it.localNetworkTier == NetworkTier.OFFLINE
                }
            assertEquals(NetworkTier.OFFLINE, afterPoll.localNetworkTier)

            localVm.playerController.stopAndClear()
            collectJob.cancel()
            localVm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }

    @Test
    internal fun `reader section outline heading map and scroll target use measured Y offsets`() {
        val html =
            "<p>Lead paragraph</p>" +
                "<h2>Overview</h2>" +
                "<p>Long uneven section with figures</p>" +
                "<h2>Overview</h2>" +
                "<h3>   </h3>" +
                "<h2>Benchmarks</h2>" +
                "<p>Short conclusion</p>"
        val blocks = parseHtmlArticleBlocks(rawHtml = html, baseUrl = "https://example.com/post")
        val headings = extractPlainTextHeadings(blocks)
        val blockHeadingMap = mapBlockHeadingIndices(blocks)

        assertEquals(listOf("Overview", "Benchmarks"), headings)
        assertEquals(mapOf(1 to 0, 4 to 1), blockHeadingMap)

        val measuredOffsets = mapOf(0 to 780, 1 to 4_650)
        assertEquals(
            780,
            resolveHeadingScrollPx(
                headingIndex = 0,
                totalHeadings = headings.size,
                maxScrollPx = 4_000,
                measuredOffsetsPx = measuredOffsets,
            ),
        )
        assertEquals(
            4_000,
            resolveHeadingScrollPx(
                headingIndex = 1,
                totalHeadings = headings.size,
                maxScrollPx = 4_000,
                measuredOffsetsPx = measuredOffsets,
            ),
        )
        assertEquals(
            2_000,
            resolveHeadingScrollPx(
                headingIndex = 1,
                totalHeadings = headings.size,
                maxScrollPx = 4_000,
                measuredOffsetsPx = mapOf(0 to 780),
            ),
        )
    }

    @Test
    internal fun `two-stage uiState keeps visibleEntries and searchResults reference-identical on player ticks`() =
        runTest {
            val activeFeed = testFeed("f-pod-stage", "Systems Podcast").copy(feedType = "podcast")
            val archivedFeed = testFeed("f-arch-stage", "Archived Blog").copy(isActive = false)
            val episode =
                testItem("ep-stage-1", "Systems Kernel Episode", "f-pod-stage")
                    .copy(
                        contentType = "audio",
                        audioUrl = "https://example.com/stage-1.mp3",
                        duration = "30:00",
                        isFavorite = true,
                        isCachedOffline = true,
                    )
            val article =
                testItem("art-stage-2", "Systems Storage Notes", "f-pod-stage")
                    .copy(contentType = "article")
            database.dao().upsertFeeds(listOf(activeFeed, archivedFeed))
            database.dao().upsertItems(listOf(episode, article))

            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
            viewModel.uiState.first { it.allEntries.size == 2 && it.feeds.size == 2 }

            viewModel.dispatch(MainAction.AddToQueue("ep-stage-1"))
            viewModel.dispatch(MainAction.AddToQueue("art-stage-2"))
            viewModel.dispatch(MainAction.UpdateSearchQuery("Systems"))
            viewModel.dispatch(MainAction.PlayAudioEntry("ep-stage-1"))

            val beforeState =
                viewModel.uiState.first {
                    it.playerState.currentEntryId == "ep-stage-1" &&
                        it.searchQuery == "Systems" &&
                        it.searchResults.size == 2 &&
                        it.listeningHistoryEntries.size == 1
                }
            assertEquals(2, beforeState.visibleEntries.size)
            assertEquals(2, beforeState.searchResults.size)
            assertEquals(1, beforeState.activeFeeds.size)
            assertEquals(1, beforeState.archivedFeeds.size)
            assertEquals(1, beforeState.starredEntries.size)
            assertEquals(1, beforeState.offlineEntries.size)

            // 1-second playback tick updates playerState without recomputing list filters
            playerBackend.setSimulatedPositionMs(5_200L)
            assertTrue(viewModel.playerController.advanceSingleTick(tickCount = 1))
            val afterTick = viewModel.uiState.first { it.playerState.positionMs == 5_200L }
            assertEquals(5_200L, afterTick.playerState.positionMs)
            assertSame(beforeState.visibleEntries, afterTick.visibleEntries)
            assertSame(beforeState.searchResults, afterTick.searchResults)
            assertSame(beforeState.queueEntries, afterTick.queueEntries)
            assertSame(beforeState.listeningHistoryEntries, afterTick.listeningHistoryEntries)
            assertSame(beforeState.activeFeeds, afterTick.activeFeeds)
            assertSame(beforeState.archivedFeeds, afterTick.archivedFeeds)
            assertSame(beforeState.starredEntries, afterTick.starredEntries)
            assertSame(beforeState.offlineEntries, afterTick.offlineEntries)

            // Seek updates playerState positionMs while keeping base lists reference-identical
            // prior to DB progress snapshot emission; test sub-progress seek on in-memory state
            viewModel.playerController.setSleepTimerMinutes(15)
            val afterSleepUpdate =
                viewModel.uiState.first { it.playerState.sleepTimerRemainingSec == 900 }
            assertSame(beforeState.visibleEntries, afterSleepUpdate.visibleEntries)
            assertSame(beforeState.searchResults, afterSleepUpdate.searchResults)

            playerBackend.setSimulatedPositionMs(8_400L)
            assertTrue(viewModel.playerController.advanceSingleTick(tickCount = 2))
            val afterSecondTick = viewModel.uiState.first { it.playerState.positionMs == 8_400L }
            assertEquals(899, afterSecondTick.playerState.sleepTimerRemainingSec)
            assertSame(beforeState.visibleEntries, afterSecondTick.visibleEntries)
            assertSame(beforeState.searchResults, afterSecondTick.searchResults)

            // Seeking on a shared controller without DB mutation updates playerState.positionMs
            // while keeping visibleEntries and searchResults reference-identical (assertSame)
            val seekBackend = FakeAudioPlayerBackend()
            val seekController =
                PodcastPlayerController(scope = backgroundScope, backend = seekBackend)
            val seekVm =
                MainViewModel(
                    repository = repository,
                    settingsRepository = settings,
                    sharedPlayerController = seekController,
                )
            val seekCollectJob = backgroundScope.launch { seekVm.uiState.collect() }
            seekVm.dispatch(MainAction.UpdateSearchQuery("Systems"))
            val initialSeekState =
                seekVm.uiState.first { !it.isInitialLoading && it.searchQuery == "Systems" }
            seekController.playEntry(
                entry = initialSeekState.allEntries.first { it.id == "ep-stage-1" },
                sourceUriOrPath = "https://example.com/stage-1.mp3",
                speed = 1.0f,
                skipSilenceEnabled = false,
            )
            val seekBefore =
                seekVm.uiState.first {
                    it.playerState.currentEntryId == "ep-stage-1" && it.readerContent != null
                }
            seekVm.dispatch(MainAction.SeekPlayerToMs(42_000L))
            val afterSeek = seekVm.uiState.first { it.playerState.positionMs == 42_000L }
            assertEquals(42_000L, afterSeek.playerState.positionMs)
            assertSame(seekBefore.visibleEntries, afterSeek.visibleEntries)
            assertSame(seekBefore.searchResults, afterSeek.searchResults)

            seekController.releaseController()
            seekCollectJob.cancel()
            seekVm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            viewModel.playerController.stopAndClear()
            collectJob.cancel()
        }

    @Test
    internal fun `sharedPlayerController survives MainViewModel clear and attaches to recreated ViewModel`() =
        runTest {
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sharedBackend = FakeAudioPlayerBackend()
            val sharedBridge = RecordingMediaSessionBridge()
            val sharedController =
                createManagedPlayerController(
                    scope = appScope,
                    repository = repository,
                    settingsRepository = settings,
                    playerBackend = sharedBackend,
                    mediaSessionBridge = sharedBridge,
                )
            val store1 = ViewModelStore()
            val store2 = ViewModelStore()
            try {
                val podcastFeed =
                    testFeed("f-shared-pod", "Durable Cast").copy(feedType = "podcast")
                val ep1 =
                    testItem("ep-shared-1", "Background Episode 1", "f-shared-pod")
                        .copy(
                            feedTitle = "Durable Cast",
                            contentType = "audio",
                            audioUrl = "https://example.com/shared-1.mp3",
                            duration = "25:00",
                        )
                val ep2 =
                    testItem("ep-shared-2", "Background Episode 2", "f-shared-pod")
                        .copy(
                            feedTitle = "Durable Cast",
                            contentType = "audio",
                            audioUrl = "https://example.com/shared-2.mp3",
                            duration = "30:00",
                        )
                database.dao().upsertFeeds(listOf(podcastFeed))
                database.dao().upsertItems(listOf(ep1, ep2))

                val factory =
                    MainViewModel.Factory(
                        repository = repository,
                        settings = settings,
                        mediaSessionBridge = sharedBridge,
                        sharedPlayerController = sharedController,
                    )
                val vm1 = ViewModelProvider(store1, factory)[MainViewModel::class.java]
                val collect1 = backgroundScope.launch { vm1.uiState.collect() }
                vm1.uiState.first { it.allEntries.size == 2 }

                vm1.dispatch(MainAction.AddToQueue("ep-shared-1"))
                vm1.dispatch(MainAction.AddToQueue("ep-shared-2"))
                vm1.uiState.first { it.queueEntries.size == 2 }
                vm1.dispatch(MainAction.PlayAudioEntry("ep-shared-1"))
                vm1.uiState.first {
                    it.playerState.currentEntryId == "ep-shared-1" &&
                        it.playerState.isPlaying &&
                        it.nextQueuedAudioEntryId == "ep-shared-2"
                }

                // Finish Activity 1: cancels vm1.viewModelScope and invokes vm1.onCleared()
                collect1.cancel()
                store1.clear()
                vm1.viewModelScope.coroutineContext[Job]?.join()

                assertTrue(sharedController.state.value.isPlaying)
                assertTrue(sharedBackend.isPlaying())
                assertEquals("ep-shared-1", sharedController.state.value.currentEntryId)

                // Background playback continues advancing while no ViewModel is alive
                sharedBackend.setSimulatedPositionMs(18_000L)
                assertTrue(sharedController.advanceSingleTick(tickCount = 5))
                assertEquals(18_000L, sharedController.state.value.positionMs)

                // Recreate MainViewModel: immediately observes live playing episode
                val vm2 = ViewModelProvider(store2, factory)[MainViewModel::class.java]
                assertEquals("ep-shared-1", vm2.uiState.value.playerState.currentEntryId)
                assertTrue(vm2.uiState.value.playerState.isPlaying)

                val collect2 = backgroundScope.launch { vm2.uiState.collect() }
                val liveState =
                    vm2.uiState.first {
                        !it.isInitialLoading && it.activePlayerEntry?.id == "ep-shared-1"
                    }
                assertTrue(liveState.playerState.isPlaying)
                assertEquals(18_000L, liveState.playerState.positionMs)
                assertEquals("ep-shared-2", liveState.nextQueuedAudioEntryId)

                collect2.cancel()
            } finally {
                store1.clear()
                store2.clear()
                sharedController.releaseController()
                appScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    internal fun `ThumbnailLoader coalesces concurrent requests and prunes disk cache over cap`() =
        runTest {
            val fetchCount = AtomicInteger(0)
            val decodeCount = AtomicInteger(0)
            val enteredTransport = CompletableDeferred<Unit>()
            val releaseTransport = CompletableDeferred<Unit>()
            val allCallersDispatched = CountDownLatch(8)
            val coalescingCacheDir = File(tempDir, "coalesce_cache")

            val coalescingClient = RocheApiClient { request ->
                if (request.url == "https://cdn.example.com/shared-cover.png") {
                    fetchCount.incrementAndGet()
                    enteredTransport.complete(Unit)
                    releaseTransport.await()
                }
                HttpResponse(
                    statusCode = 200,
                    body = "img",
                    bodyBytes = byteArrayOf(10, 20, 30, 40),
                )
            }
            var nowMs = 1_000_000L
            val loader =
                ThumbnailLoader(
                    apiClient = coalescingClient,
                    settings = settings,
                    cacheDir = coalescingCacheDir,
                    decoder = {
                        decodeCount.incrementAndGet()
                        fakeImageBitmap(width = 128, height = 128)
                    },
                    clockMs = { nowMs },
                )

            val jobs =
                (1..8).map {
                    async(Dispatchers.Default) {
                        allCallersDispatched.countDown()
                        allCallersDispatched.await(5, TimeUnit.SECONDS)
                        loader.loadThumbnail("https://cdn.example.com/shared-cover.png")
                    }
                }
            enteredTransport.await()
            releaseTransport.complete(Unit)
            val bitmaps = jobs.awaitAll()

            assertEquals(1, fetchCount.get())
            assertEquals(1, decodeCount.get())
            val firstBitmap = bitmaps.first()
            assertNotNull(firstBitmap)
            bitmaps.forEach { bitmap -> assertSame(firstBitmap, bitmap) }

            // Disk cache pruning: writing 516 thumbnails caps thumbnails/ at 512 and evicts 4
            // oldest
            val pruneCacheDir = File(tempDir, "prune_cache")
            settings.update {
                it.copy(
                    serverUrl = "http://100.64.0.12:8080",
                    bearerToken = "tok-thumbs",
                )
            }
            val pruneLoader =
                ThumbnailLoader(
                    apiClient =
                        RocheApiClient {
                            HttpResponse(
                                statusCode = 200,
                                body = "img",
                                bodyBytes = byteArrayOf(1, 2, 3, 4),
                            )
                        },
                    settings = settings,
                    cacheDir = pruneCacheDir,
                    decoder = { fakeImageBitmap(width = 32, height = 32) },
                    clockMs = { nowMs },
                )
            val totalWritten = ThumbnailLoader.MAX_DISK_CACHED_FILES + 4
            for (idx in 1..totalWritten) {
                nowMs = 2_000_000L + idx * 1_000L
                assertNotNull(pruneLoader.loadThumbnail("media-id-$idx"))
            }
            val thumbsDir = File(pruneCacheDir, "thumbnails")
            val cachedFiles = thumbsDir.listFiles().orEmpty().filter { it.isFile }
            assertEquals(ThumbnailLoader.MAX_DISK_CACHED_FILES, cachedFiles.size)
            for (evictedIdx in 1..4) {
                assertFalse(File(thumbsDir, "media-id-$evictedIdx").exists())
            }
            assertTrue(File(thumbsDir, "media-id-5").exists())
            assertTrue(File(thumbsDir, "media-id-$totalWritten").exists())

            // Byte-cap pruning also evicts oldest files until total bytes <= maxBytes
            val bytePruneDir = File(tempDir, "byte_prune_thumbs").apply { mkdirs() }
            for (idx in 1..5) {
                File(bytePruneDir, "chunk-$idx").apply {
                    writeBytes(ByteArray(100) { idx.toByte() })
                    setLastModified(3_000_000L + idx * 1_000L)
                }
            }
            pruneThumbnailDiskCache(thumbsDir = bytePruneDir, maxFiles = 100, maxBytes = 300L)
            assertEquals(
                listOf("chunk-3", "chunk-4", "chunk-5"),
                bytePruneDir.listFiles().orEmpty().map { it.name }.sorted(),
            )
        }

    @Test
    internal fun `prewarmed repository initializes MainViewModel with instant non-loading inbox`() =
        runTest {
            database.dao().upsertFeeds(SeedData.feeds)
            database.dao().upsertItems(SeedData.items)
            settings.prewarm()
            repository.prewarm()

            val warmViewModel =
                MainViewModel(
                    repository = repository,
                    settingsRepository = settings,
                    playerBackend = playerBackend,
                    mediaSessionBridge = mediaSessionBridge,
                )
            try {
                // Before even starting a collector, initialValue already has cached entries
                assertFalse(warmViewModel.uiState.value.isInitialLoading)
                assertTrue(warmViewModel.uiState.value.visibleEntries.isNotEmpty())
            } finally {
                warmViewModel.playerController.releaseController()
                warmViewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    internal fun `ToggleStar updates UI optimistically and SearchOverlay merges deep body matches`() =
        runTest {
            val deepText =
                "Lead text. " + "z".repeat(8_000) + " DeepOverlayKeyword42 inside full text."
            database
                .dao()
                .upsertItem(
                    testItem(id = "item-fast-star", title = "Optimistic Star Target")
                        .copy(contentText = deepText, contentHtml = "<p>$deepText</p>")
                )
            val collectJob = backgroundScope.launch { viewModel.uiState.collect() }
            viewModel.uiState.first { it.visibleEntries.any { e -> e.id == "item-fast-star" } }

            // ToggleStar flips favorite immediately in uiState
            viewModel.dispatch(MainAction.ToggleStar("item-fast-star"))
            val starredState =
                viewModel.uiState.first {
                    it.visibleEntries.firstOrNull { e -> e.id == "item-fast-star" }?.isFavorite ==
                        true
                }
            assertEquals("Starred", starredState.statusBanner)

            viewModel.dispatch(MainAction.ToggleStar("item-fast-star"))
            val unstarredState =
                viewModel.uiState.first {
                    it.visibleEntries.firstOrNull { e -> e.id == "item-fast-star" }?.isFavorite ==
                        false
                }
            assertEquals("Removed star", unstarredState.statusBanner)

            // OpenSearch -> UpdateSearchQuery finds deep text beyond 2KB list preview ->
            // CloseSearch
            viewModel.dispatch(MainAction.OpenSearch)
            assertTrue(viewModel.uiState.first { it.isSearchOpen }.isSearchOpen)

            viewModel.dispatch(MainAction.UpdateSearchQuery("DeepOverlayKeyword42"))
            val searched =
                viewModel.uiState.first { state ->
                    state.searchResults.any { it.id == "item-fast-star" }
                }
            assertEquals("item-fast-star", searched.searchResults.single().id)

            // Opening Reader from Search keeps isSearchOpen true so CloseReader returns to Search
            viewModel.dispatch(MainAction.OpenReader("item-fast-star"))
            assertEquals(
                "item-fast-star",
                viewModel.uiState
                    .first { it.selectedReaderEntryId == "item-fast-star" }
                    .selectedReaderEntryId,
            )
            viewModel.dispatch(MainAction.CloseReader)
            val backInSearch = viewModel.uiState.first { it.selectedReaderEntryId == null }
            assertTrue(backInSearch.isSearchOpen)
            assertEquals("DeepOverlayKeyword42", backInSearch.searchQuery)

            viewModel.dispatch(MainAction.CloseSearch)
            val closedSearch = viewModel.uiState.first { !it.isSearchOpen }
            assertEquals("", closedSearch.searchQuery)

            collectJob.cancel()
        }

    private class FakeAudioPlayerBackend : AudioPlayerBackend {
        var failNextLoad: Boolean = false
        private var positionMs: Long = 0L
        private var durationMs: Long = 1_800_000L
        private var playing: Boolean = false
        private var speed: Float = 1.0f

        fun setSimulatedPositionMs(nextPositionMs: Long) {
            positionMs = nextPositionMs.coerceAtLeast(0L)
        }

        override fun loadAndPlay(
            source: String,
            initialPositionMs: Long,
            fallbackDurationMs: Long,
            speed: Float,
            playWhenReady: Boolean,
            onPrepared: (durationMs: Long) -> Unit,
            onCompletion: () -> Unit,
            onError: () -> Unit,
        ) {
            if (failNextLoad || source.isBlank()) {
                playing = false
                onError()
                return
            }
            durationMs = fallbackDurationMs.takeIf { it > 0L } ?: 1_800_000L
            positionMs = initialPositionMs.coerceIn(0L, durationMs)
            this.speed = speed
            playing = playWhenReady
            onPrepared(durationMs)
        }

        override fun play() {
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun seekTo(positionMs: Long) {
            this.positionMs = positionMs.coerceIn(0L, durationMs)
        }

        override fun setSpeed(speed: Float) {
            this.speed = speed
        }

        override fun currentPositionMs(): Long = positionMs

        override fun durationMs(): Long = durationMs

        override fun isPlaying(): Boolean = playing

        override fun release() {
            playing = false
        }
    }

    private fun fakeImageBitmap(width: Int, height: Int): ImageBitmap =
        Proxy.newProxyInstance(
            ImageBitmap::class.java.classLoader,
            arrayOf(ImageBitmap::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getWidth" -> width
                "getHeight" -> height
                "getHasAlpha" -> true
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeImageBitmap(${width}x$height)"
                else -> null
            }
        } as ImageBitmap

    private class RecordingMediaSessionBridge : PodcastMediaSessionBridge {
        @Volatile var callbacks: PodcastMediaSessionCallbacks? = null
        private val focusRequested = AtomicInteger(0)
        private val focusAbandoned = AtomicInteger(0)
        val snapshots = MutableStateFlow<MediaSessionMetadataSnapshot?>(null)

        val focusRequestedCount: Int
            get() = focusRequested.get()

        override fun bindCallbacks(callbacks: PodcastMediaSessionCallbacks) {
            this.callbacks = callbacks
        }

        override fun requestAudioFocus(): Boolean {
            focusRequested.incrementAndGet()
            return true
        }

        override fun abandonAudioFocus() {
            focusAbandoned.incrementAndGet()
        }

        override fun updatePlaybackSession(snapshot: MediaSessionMetadataSnapshot?) {
            snapshots.value = snapshot
        }

        override fun release() {
            snapshots.value = null
        }
    }

    private fun testFeed(id: String, title: String) =
        FeedEntity(id, title, "https://example.com/$id.xml", "https://example.com", "rss")

    private fun testItem(
        id: String,
        title: String,
        feedId: String = "f-1",
        status: String = "unread",
    ) =
        com.grayvines.roche.data.local.ItemEntity(
            id = id,
            feedId = feedId,
            feedTitle = "Test Feed",
            title = title,
            url = "https://example.com/$id",
            author = "Author",
            summary = "Summary",
            contentText = null,
            contentHtml = "<p>Body</p>",
            contentType = "article",
            status = status,
            isFavorite = false,
            isCachedOffline = false,
            publishedAt = "2026-06-01T12:00:00Z",
            createdAt = "2026-06-01T12:00:00Z",
            readProgress = 0.0,
            thumbnailMediaId = null,
            duration = null,
        )
}
