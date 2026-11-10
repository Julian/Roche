package com.grayvines.roche.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
class MainViewModelTest {
    @TempDir lateinit var tempDir: File

    private lateinit var database: RocheDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: RocheRepository
    private lateinit var viewModel: MainViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database =
            Room.inMemoryDatabaseBuilder<RocheDatabase>().setDriver(BundledSQLiteDriver()).build()
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "vm_test_settings.preferences_pb") }
            )
        settings = SettingsRepository(dataStore)
        val apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") }
        repository =
            RocheRepository(database = database, apiClient = apiClient, settings = settings)
        viewModel = MainViewModel(repository = repository, settingsRepository = settings)
    }

    @AfterEach
    fun tearDown() {
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
                listOf(AppTab.FEEDS, AppTab.OFFLINE, AppTab.SEARCH, AppTab.SETTINGS),
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
