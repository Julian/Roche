package com.grayvines.roche.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.SeedData
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.ui.ArticleBlock
import com.grayvines.roche.ui.MainViewModel
import com.grayvines.roche.ui.extractInboxSnippet
import com.grayvines.roche.ui.parseHtmlArticleBlocks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class RocheRssWorkflowTest {
    @TempDir lateinit var tempDir: File

    private lateinit var database: RocheDatabase
    private lateinit var settings: SettingsRepository

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database =
            Room.inMemoryDatabaseBuilder<RocheDatabase>().setDriver(BundledSQLiteDriver()).build()
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "workflow_settings.preferences_pb") }
            )
        settings = SettingsRepository(dataStore)
    }

    @AfterEach
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedDatabase() {
        val dao = database.dao()
        dao.upsertFeeds(SeedData.feeds)
        dao.upsertTags(SeedData.tags)
        dao.upsertItems(SeedData.items)
        dao.insertItemTags(SeedData.itemTags)
    }

    @Test
    fun `hydrateItemContentIfNeeded archiveEntriesBatch OpmlImportExport and HtmlArticleBlocks work end to end`() =
        runTest {
            seedDatabase()
            settings.update {
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val requests = mutableListOf<String>()
            val batchRepo =
                RocheRepository(
                    database = database,
                    apiClient = buildBatchAndOpmlMockClient(requests),
                    settings = settings,
                )

            // 1. Lazy reader hydration for item whose contentHtml was omitted on list
            database
                .dao()
                .upsertItem(database.dao().getItemById("item-5")!!.copy(contentHtml = null))
            batchRepo.hydrateItemContentIfNeeded("item-5")
            val hydrated = batchRepo.snapshot.first().entries.first { it.id == "item-5" }
            assertTrue(hydrated.contentHtml?.contains("<h2>Section 1</h2>") == true)

            // 2. Structured HTML parser extracts Heading, CodeBlock, Blockquote, ListBlock, and
            // Paragraph (and strips nested <p> inside blockquote and li)
            val parsedBlocks =
                parseHtmlArticleBlocks(
                    hydrated.contentHtml!! +
                        "<blockquote><p>Quote</p></blockquote><ul><li><p>Item A</p></li></ul><hr/>"
                )
            assertEquals(6, parsedBlocks.size)
            assertTrue(parsedBlocks[0] is ArticleBlock.Heading)
            assertEquals("fn main() <T> {}", (parsedBlocks[1] as ArticleBlock.CodeBlock).code)
            assertEquals("Quote", (parsedBlocks[3] as ArticleBlock.Blockquote).html)
            assertEquals(listOf("Item A"), (parsedBlocks[4] as ArticleBlock.ListBlock).itemsHtml)
            assertEquals("Plain snippet", extractInboxSnippet("  Plain\nsnippet  "))

            // 3. Batch archive updates Room and calls /api/v1/items/archive-batch
            val archivedCount =
                batchRepo.archiveEntriesBatch(
                    entryIds = listOf("item-1", "item-2"),
                    allUnread = false,
                )
            assertEquals(2, archivedCount)
            assertTrue(requests.contains("POST http://100.64.0.12:8080/api/v1/items/archive-batch"))
            val afterBatch = batchRepo.snapshot.first().entries.associateBy { it.id }
            assertTrue(afterBatch.getValue("item-1").isArchived)
            assertTrue(afterBatch.getValue("item-2").isArchived)

            // 4. Offline exportOpml falls back to local OPML 2.0 XML generation
            val exportedXml = batchRepo.exportOpml().getOrThrow()
            assertTrue(exportedXml.contains("<opml version=\"2.0\">"))
            assertTrue(exportedXml.contains("arXiv cs.LG"))

            // 5. importOpml posts to server and triggers sync
            assertEquals(3, batchRepo.importOpml("<opml version=\"2.0\"/>").getOrThrow())
        }

    @Test
    fun `parseHtmlArticleBlocks extracts inline figures and paragraph images and skips tracking pixels`() {
        val html =
            """<figure><img src="/static/images/circus.jpg" alt="Circus show" width="1024" height="579">""" +
                """<figcaption>The Halloween <a href="https://dark.ronacher.eu">circus</a> show.</figcaption></figure>""" +
                """<p><a href="https://minimaxir.com/full.png"><img src="costpertask.png" alt="Chart"></a> """ +
                """Model quality vs cost-per-task.</p>""" +
                """<p><img src="https://example.com/pixel.gif" width="1" height="1">""" +
                """<img src="https://example.com/icon.svg">Visible paragraph</p>"""

        val blocks =
            parseHtmlArticleBlocks(
                rawHtml = html,
                baseUrl = "https://dark.ronacher.eu/2026/10/31/golden-age/",
            )

        assertEquals(4, blocks.size)
        val figImg = blocks[0] as ArticleBlock.ImageBlock
        assertEquals("https://dark.ronacher.eu/static/images/circus.jpg", figImg.url)
        assertEquals("Circus show", figImg.alt)
        assertEquals(1024, figImg.width)
        assertEquals(579, figImg.height)
        assertTrue(figImg.captionHtml?.contains("Halloween") == true)

        val inlineImg = blocks[1] as ArticleBlock.ImageBlock
        assertEquals(
            "https://dark.ronacher.eu/2026/10/31/golden-age/costpertask.png",
            inlineImg.url,
        )
        assertEquals("Chart", inlineImg.alt)

        assertEquals(
            "Model quality vs cost-per-task.",
            (blocks[2] as ArticleBlock.Paragraph).html,
        )
        assertEquals("Visible paragraph", (blocks[3] as ArticleBlock.Paragraph).html)
    }

    @Test
    fun `MainViewModel performImportOpml and local batch archive work`() = runTest {
        seedDatabase()
        settings.update { it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live") }
        val requests = mutableListOf<String>()
        val batchRepo =
            RocheRepository(
                database = database,
                apiClient = buildBatchAndOpmlMockClient(requests),
                settings = settings,
            )
        val vm = MainViewModel(repository = batchRepo, settingsRepository = settings)

        // Local-only unpushed item is archived locally without sending local UUID to server
        val localEntity =
            createSavedItemEntity(
                url = "https://example.com/local-only",
                title = "Local Unpushed",
                hostTitle = "example.com",
                type = ContentType.ARTICLE,
            )
        database.dao().upsertItem(localEntity)
        database
            .dao()
            .upsertPendingMutation(
                PendingMutationEntity(
                    entityId = localEntity.id,
                    isLocalItem = true,
                )
            )
        assertEquals(1, batchRepo.archiveEntriesBatch(listOf(localEntity.id)))
        assertFalse(requests.any { it.endsWith("/api/v1/items/archive-batch") })
        assertEquals(
            "archived",
            database.dao().getPendingMutation(localEntity.id)?.dirtyStatus,
        )

        // ViewModel importOpml updates statusBanner with imported count
        vm.performImportOpml("<opml version=\"2.0\"/>")
        assertEquals(
            "Imported 3 feeds from OPML",
            vm.uiState.first { it.statusBanner != null }.statusBanner,
        )
        vm.viewModelScope.cancel()
    }

    @Test
    fun `PeerConsensus HRW work splitting and cross-peer error rescue match server protocol`() {
        val feedId1 = deterministicFeedId("https://example.com/feed.xml")
        assertEquals(feedId1, deterministicFeedId("https://example.com/feed.xml"))
        assertEquals(36, feedId1.length)
        val itemId1 = deterministicItemId("https://example.com/feed.xml", "https://example.com/1")
        assertEquals(
            itemId1,
            deterministicItemId("https://example.com/feed.xml", "https://example.com/1"),
        )

        val peers =
            listOf(
                PeerNodeDto("server", "Headless", "wifi", computeThroughputWeight(0, 0, 0.0)),
                PeerNodeDto("android-1", "Pixel", "wifi", computeThroughputWeight(0, 0, 0.0)),
            )
        val countWins = { active: List<PeerNodeDto>, targetId: String ->
            (0 until 100).count { i ->
                selectFeedPoller(
                    active,
                    "https://example.com/feed-$i.xml",
                    30,
                    1_780_000_000L,
                    null,
                    null,
                    null,
                ) == targetId
            }
        }
        val serverWins = countWins(peers, "server")
        val androidWins = countWins(peers, "android-1")
        assertEquals(100, serverWins + androidWins)
        assertTrue(serverWins in 35..65 && androidWins in 35..65)

        val dynamicPeers =
            listOf(
                peers[0].copy(weight = computeThroughputWeight(8, 200, 2.0)),
                peers[1].copy(weight = computeThroughputWeight(2, 25, 3.0)),
            )
        val fasterPeerWins = countWins(dynamicPeers, "server")
        assertTrue(fasterPeerWins > serverWins && fasterPeerWins >= 75)

        val now = java.time.Instant.parse("2026-06-01T12:00:00Z")
        val blockedFeed =
            com.grayvines.roche.data.local.FeedEntity(
                id = feedId1,
                title = "Blocked on Server",
                url = "https://protected.example.com/rss.xml",
                siteUrl = null,
                feedType = "rss",
                pollInterval = 30,
                lastPolled = "2026-06-01T11:59:00Z",
                lastPolledBy = "server",
                lastError = "HTTP 403 Forbidden",
                lastErrorBy = "server",
                isActive = true,
            )
        assertFalse(shouldPeerPollFeed("server", peers, blockedFeed, now))
        assertTrue(shouldPeerPollFeed("android-1", peers, blockedFeed, now))
    }

    @Test
    fun `Android polls feeds on device shares state via peer sync and rescues server-blocked feeds without duplication`() =
        runTest {
            settings.update {
                it.copy(
                    serverUrl = "",
                    bearerToken = "",
                    peerId = "android-peer-1",
                    deviceName = "Pixel 10 Pro",
                )
            }
            val peerSyncBodies = mutableListOf<String>()
            val peerRepo =
                RocheRepository(
                    database = database,
                    apiClient = buildPeerSyncAndExternalFeedMockClient(peerSyncBodies),
                    settings = settings,
                )

            // 1. Offline from server: subscribe to an RSS feed on Android -> Android polls XML
            // directly!
            val subResult =
                peerRepo.saveUrlOrFeed(
                    inputUrl = "https://blog.example.com/rss.xml",
                    titleOverride = null,
                    tagCsv = "",
                )
            assertTrue(subResult.isFeed)
            assertEquals("Systems Engineering Blog", subResult.title)

            val offlineEntries = peerRepo.snapshot.first().entries
            assertEquals(2, offlineEntries.size)
            val post1 = offlineEntries.first { it.url == "https://blog.example.com/post-1" }
            val post2 = offlineEntries.first { it.url == "https://blog.example.com/post-2" }
            assertEquals("https://blog.example.com/images/diagram.png", post1.thumbnailUrl)

            // 2. Interact with Post 1 and delete Post 2 while offline
            peerRepo.setReadState(post1.id, isRead = true)
            peerRepo.toggleStar(post1.id)
            peerRepo.toggleTagOnItem(post1.id, "distributed")
            peerRepo.deleteItem(post2.id).getOrThrow()

            // Re-polling while offline must NOT resurrect deleted Post 2 (tombstone suppression)
            val feedId = deterministicFeedId("https://blog.example.com/rss.xml")
            peerRepo.refreshFeed(feedId).getOrThrow()
            assertEquals(1, peerRepo.snapshot.first().entries.size)

            // 3. Connect to peer server: syncWithServer() exchanges state via /api/v1/sync/peer,
            // receives a server-blocked feed (last_error_by = "server"), rescues it on-device,
            // and pushes the rescued items back to the server in the follow-up peer sync!
            settings.update {
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-peer")
            }
            val totalCount = peerRepo.syncWithServer().getOrThrow()
            assertEquals(2, totalCount)
            assertEquals(2, peerSyncBodies.size)
            assertTrue(peerSyncBodies[0].contains("https://blog.example.com/post-1"))
            assertTrue(peerSyncBodies[0].contains("distributed"))
            assertTrue(
                peerSyncBodies[1].contains("https://protected.example.com/rescued-article"),
                "Follow-up peer sync must share the item rescued on-device with the server",
            )

            val finalEntries = peerRepo.snapshot.first().entries
            assertEquals(2, finalEntries.size)
            assertTrue(
                finalEntries.any { it.url == "https://blog.example.com/post-1" && it.isFavorite }
            )
            assertTrue(
                finalEntries.any { it.url == "https://protected.example.com/rescued-article" }
            )
        }

    @Test
    fun `classifyNetworkCapabilities maps ethernet wifi cellular and unvalidated states`() {
        assertEquals(
            NetworkTier.WIRED,
            classifyNetworkCapabilities(
                hasInternet = true,
                isValidated = true,
                hasEthernet = true,
                hasWifi = true,
                hasCellular = false,
                isMetered = false,
            ),
        )
        assertEquals(
            NetworkTier.WIFI,
            classifyNetworkCapabilities(
                hasInternet = true,
                isValidated = true,
                hasEthernet = false,
                hasWifi = true,
                hasCellular = false,
                isMetered = false,
            ),
        )
        assertEquals(
            NetworkTier.CELLULAR,
            classifyNetworkCapabilities(
                hasInternet = true,
                isValidated = true,
                hasEthernet = false,
                hasWifi = true,
                hasCellular = false,
                isMetered = true,
            ),
        )
        assertEquals(
            NetworkTier.WIFI,
            classifyNetworkCapabilities(
                hasInternet = true,
                isValidated = true,
                hasEthernet = false,
                hasWifi = false,
                hasCellular = false,
                isMetered = false,
            ),
        )
        assertEquals(
            NetworkTier.OFFLINE,
            classifyNetworkCapabilities(
                hasInternet = true,
                isValidated = false,
                hasEthernet = false,
                hasWifi = true,
                hasCellular = false,
                isMetered = false,
            ),
        )
    }

    @Test
    fun `live network tier provider and background sync adapt weight and offline fallback`() =
        runTest {
            val peerSyncBodies = mutableListOf<String>()
            var currentTier = NetworkTier.CELLULAR
            settings.update {
                it.copy(
                    serverUrl = "http://100.64.0.12:8080",
                    bearerToken = "tok-peer",
                    peerId = "android-peer-1",
                    deviceName = "Pixel 10 Pro",
                )
            }
            val peerRepo =
                RocheRepository(
                    database = database,
                    apiClient = buildPeerSyncAndExternalFeedMockClient(peerSyncBodies),
                    settings = settings,
                    networkTierProvider = NetworkTierProvider { currentTier },
                )

            // 1. Pre-poll sync omits weight so previously measured throughput is preserved,
            // then reports the measured throughput weight on the follow-up round after polling
            val bgSynced = peerRepo.runBackgroundPeerSyncAndPoll().getOrThrow()
            assertEquals(3, bgSynced)
            assertEquals(2, peerRepo.snapshot.first().activePeers.size)
            assertTrue(peerSyncBodies.first().contains(""""network_tier":"cellular""""))
            assertFalse(peerSyncBodies.first().contains(""""weight":"""))
            assertTrue(peerSyncBodies.last().contains(""""weight":10.0"""))

            // 2. When OFFLINE, background sync and manual on-device poll respect offline state
            currentTier = NetworkTier.OFFLINE
            assertEquals(0, peerRepo.runBackgroundPeerSyncAndPoll().getOrThrow())
            assertTrue(peerRepo.pollDueFeedsOnDeviceNow().isFailure)

            // 3. When back on WIFI, manual on-device poll succeeds
            currentTier = NetworkTier.WIFI
            assertEquals(0, peerRepo.pollDueFeedsOnDeviceNow().getOrThrow())

            // 4. Subsequent peer sync sends updated_after cursor, omits unchanged items outbound,
            // and preserves unchanged local items + tags when server returns a delta items payload
            assertEquals("2026-06-01T12:01:00Z", settings.settings.first().lastPeerSyncCursor)
            peerSyncBodies.clear()
            val afterDeltaCount = peerRepo.syncWithServer().getOrThrow()
            assertEquals(3, afterDeltaCount)
            assertEquals(1, peerSyncBodies.size)
            assertTrue(
                peerSyncBodies[0].contains(""""updated_after":"2026-06-01T12:01:00Z""""),
                "Second sync must include updated_after watermark cursor",
            )
            assertFalse(
                peerSyncBodies[0].contains("https://blog.example.com/post-1"),
                "Unchanged item must not be re-uploaded during delta sync",
            )
            val deltaEntries = peerRepo.snapshot.first().entries
            assertEquals(3, deltaEntries.size)
            val preservedPost1 = deltaEntries.first { it.url == "https://blog.example.com/post-1" }
            assertTrue(preservedPost1.isFavorite)
            assertEquals(listOf("distributed"), preservedPost1.tags)
        }

    @Test
    fun `initial peer sync oversized response falls back to paginated snapshot and migrations preserve data`() =
        runTest {
            verifyRoomMigrationsPreserveData(tempDir)
            val peerSyncBodies = mutableListOf<String>()
            val fallbackClient = buildOversizedPeerSyncFallbackClient(peerSyncBodies)
            settings.update {
                it.copy(
                    serverUrl = "http://100.64.0.12:8080",
                    bearerToken = "tok-peer",
                    peerId = "android-peer-1",
                )
            }
            val repo =
                RocheRepository(
                    database = database,
                    apiClient = fallbackClient,
                    settings = settings,
                )
            val count = repo.syncWithServer().getOrThrow()
            assertTrue(count >= 1)
            assertEquals("2026-06-01T12:01:00Z", settings.settings.first().lastPeerSyncCursor)
            assertTrue(peerSyncBodies.last().contains(""""updated_after""""))
        }

    @Test
    fun `offline toggleFeedArchive persists pending feed mutation and resubscribing unarchives feed`() =
        runTest {
            seedDatabase()
            val patchedFeeds = mutableListOf<String>()
            var failPatch = true
            val client = buildOfflineFeedArchiveMockClient(patchedFeeds) { failPatch }
            settings.update {
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val repo = RocheRepository(database = database, apiClient = client, settings = settings)

            // 1. Archive feed while PATCH fails (offline) -> queues pending feed mutation
            val (title, isArchived) = repo.toggleFeedArchive("feed-arxiv-cs-lg").getOrThrow()
            assertEquals("arXiv cs.LG", title)
            assertTrue(isArchived)
            assertFalse(database.dao().getFeedById("feed-arxiv-cs-lg")!!.isActive)
            assertEquals(
                FEED_ARCHIVED_STATUS_MUTATION,
                database.dao().getPendingMutation("feed-arxiv-cs-lg")?.dirtyStatus,
            )

            // 2. Sync while PATCH still fails must preserve local archived state even if server
            // returns is_active: true
            repo.syncWithServer().getOrThrow()
            assertFalse(database.dao().getFeedById("feed-arxiv-cs-lg")!!.isActive)

            // 3. Sync when back online pushes the pending PATCH and clears the mutation
            failPatch = false
            repo.syncWithServer().getOrThrow()
            assertEquals(1, patchedFeeds.size)
            assertTrue(patchedFeeds.single().contains(""""is_active":false"""))
            assertEquals(null, database.dao().getPendingMutation("feed-arxiv-cs-lg"))

            // 4. Re-subscribing to the archived feed URL unarchives it in place
            database.dao().updateFeedActive("feed-arxiv-cs-lg", false)
            val saved =
                repo.saveUrlOrFeed(
                    inputUrl = "https://export.arxiv.org/rss/cs.LG",
                    titleOverride = null,
                    tagCsv = "",
                )
            assertEquals("arXiv cs.LG", saved.title)
            assertTrue(database.dao().getFeedById("feed-arxiv-cs-lg")!!.isActive)
        }
}

private fun buildOfflineFeedArchiveMockClient(
    patchedFeeds: MutableList<String>,
    shouldFailPatch: () -> Boolean,
): RocheApiClient = RocheApiClient { request ->
    when {
        request.method == "PATCH" && request.url.contains("/api/v1/feeds/") -> {
            if (shouldFailPatch()) {
                throw java.io.IOException("Offline")
            }
            patchedFeeds.add("${request.url}:${request.body}")
            HttpResponse(
                statusCode = 200,
                body =
                    """{"id":"feed-arxiv-cs-lg","title":"arXiv cs.LG","url":"https://export.arxiv.org/rss/cs.LG","feed_type":"arxiv","is_active":false,"unread_count":1}""",
            )
        }
        request.method == "POST" && request.url.endsWith("/api/v1/sync/peer") -> {
            HttpResponse(statusCode = 404, body = "{}")
        }
        request.method == "GET" && request.url.endsWith("/api/v1/feeds") -> {
            HttpResponse(
                statusCode = 200,
                body =
                    """{"feeds":[{"id":"feed-arxiv-cs-lg","title":"arXiv cs.LG","url":"https://export.arxiv.org/rss/cs.LG","feed_type":"arxiv","is_active":true,"unread_count":1}]}""",
            )
        }
        request.method == "GET" && request.url.endsWith("/api/v1/tags") -> {
            HttpResponse(statusCode = 200, body = """{"tags":[]}""")
        }
        request.method == "GET" && request.url.contains("/api/v1/items") -> {
            HttpResponse(statusCode = 200, body = """{"items":[]}""")
        }
        else -> {
            HttpResponse(statusCode = 404, body = "{}")
        }
    }
}

private suspend fun verifyRoomMigrationsPreserveData(tempDir: File) {
    val driver = BundledSQLiteDriver()
    val dbFile = File(tempDir, "migrated_v1_to_v4.db")
    driver.open(dbFile.absolutePath).use { conn ->
        conn
            .prepare(
                "CREATE TABLE feeds (id TEXT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, siteUrl TEXT, feedType TEXT NOT NULL, PRIMARY KEY(id))"
            )
            .use { it.step() }
        conn
            .prepare(
                "CREATE TABLE items (id TEXT NOT NULL, feedId TEXT, feedTitle TEXT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, author TEXT, summary TEXT, contentText TEXT, contentHtml TEXT, contentType TEXT NOT NULL, status TEXT NOT NULL, isFavorite INTEGER NOT NULL, isCachedOffline INTEGER NOT NULL, publishedAt TEXT, createdAt TEXT NOT NULL, readProgress REAL NOT NULL, thumbnailMediaId TEXT, duration TEXT, PRIMARY KEY(id))"
            )
            .use { it.step() }
        conn
            .prepare("CREATE TABLE tags (id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")
            .use { it.step() }
        conn
            .prepare(
                "CREATE TABLE item_tags (itemId TEXT NOT NULL, tagId TEXT NOT NULL, PRIMARY KEY(itemId, tagId))"
            )
            .use { it.step() }
        conn
            .prepare(
                "INSERT INTO feeds (id, title, url, siteUrl, feedType) VALUES ('f-1', 'Systems Blog', 'https://blog.example.com/rss.xml', null, 'rss')"
            )
            .use { it.step() }
        conn
            .prepare(
                "INSERT INTO items (id, feedId, feedTitle, title, url, author, summary, contentText, contentHtml, contentType, status, isFavorite, isCachedOffline, publishedAt, createdAt, readProgress, thumbnailMediaId, duration) VALUES ('i-1', 'f-1', 'Systems Blog', 'Existing Article', 'https://blog.example.com/existing', null, 'Saved summary', null, null, 'article', 'unread', 1, 0, null, '2026-06-01T10:00:00Z', 0.0, null, null)"
            )
            .use { it.step() }
        RocheDatabase.MIGRATION_1_2.migrate(conn)
        RocheDatabase.MIGRATION_2_3.migrate(conn)
        RocheDatabase.MIGRATION_3_4.migrate(conn)
        RocheDatabase.MIGRATION_4_5.migrate(conn)
        conn.prepare("SELECT COUNT(*) FROM items WHERE id = 'i-1'").use { stmt ->
            assertTrue(stmt.step())
            assertEquals(1L, stmt.getLong(0))
        }
        conn
            .prepare("SELECT autoDownloadEpisodes, customPlaybackSpeed FROM feeds WHERE id = 'f-1'")
            .use { stmt ->
                assertTrue(stmt.step())
                assertEquals(0L, stmt.getLong(0))
                assertTrue(stmt.isNull(1))
            }
        conn.prepare("SELECT audioUrl FROM items WHERE id = 'i-1'").use { stmt ->
            assertTrue(stmt.step())
            assertTrue(stmt.isNull(0))
        }
    }
}

private fun buildOversizedPeerSyncFallbackClient(
    peerSyncBodies: MutableList<String>
): RocheApiClient = RocheApiClient { request ->
    when {
        request.method == "POST" && request.url.endsWith("/api/v1/sync/peer") -> {
            val body = request.body.orEmpty()
            peerSyncBodies.add(body)
            if (!body.contains(""""updated_after"""")) {
                throw java.io.IOException("Response exceeds maximum allowed size of 67108864 bytes")
            }
            mockPeerSyncResponse(body, mutableListOf())
        }
        request.method == "GET" && request.url.endsWith("/api/v1/feeds") -> {
            val feedId = deterministicFeedId("https://blog.example.com/rss.xml")
            HttpResponse(
                statusCode = 200,
                body =
                    """{"feeds":[{"id":"$feedId","title":"Systems Engineering Blog","url":"https://blog.example.com/rss.xml","feed_type":"rss","unread_count":1}]}""",
            )
        }
        request.method == "GET" && request.url.endsWith("/api/v1/tags") -> {
            val tagId = deterministicTagId("distributed")
            HttpResponse(
                statusCode = 200,
                body = """{"tags":[{"id":"$tagId","name":"distributed","unread_count":0}]}""",
            )
        }
        request.method == "GET" && request.url.contains("/api/v1/items") -> {
            val feedId = deterministicFeedId("https://blog.example.com/rss.xml")
            val itemId =
                deterministicItemId(
                    "https://blog.example.com/rss.xml",
                    "https://blog.example.com/post-1",
                )
            HttpResponse(
                statusCode = 200,
                body =
                    """{"items":[{"id":"$itemId","feed_id":"$feedId","feed_title":"Systems Engineering Blog","title":"Post 1: Consensus","url":"https://blog.example.com/post-1","content_type":"article","status":"unread","is_favorite":true,"created_at":"2026-06-01T12:00:00Z","tag_names":["distributed"]}]}""",
            )
        }
        else -> {
            HttpResponse(statusCode = 404, body = "{}")
        }
    }
}

internal fun buildBatchAndOpmlMockClient(requests: MutableList<String>): RocheApiClient =
    RocheApiClient { request ->
        requests.add("${request.method} ${request.url}")
        when {
            request.method == "GET" && request.url.endsWith("/api/v1/items/item-5") ->
                HttpResponse(
                    statusCode = 200,
                    body =
                        """{"id":"item-5","title":"Deterministic Simulation Testing","url":"https://arxiv.org/abs/2603.09910","content_html":"<h2>Section 1</h2><pre><code>fn main() &lt;T&gt; {}</code></pre><p>Body text</p>","content_type":"paper","status":"unread","is_favorite":false,"created_at":"2026-03-27T10:00:00Z"}""",
                )
            request.method == "POST" && request.url.endsWith("/api/v1/items/archive-batch") ->
                HttpResponse(statusCode = 200, body = """{"archived_count":2}""")
            request.method == "POST" && request.url.endsWith("/api/v1/import/opml") ->
                HttpResponse(statusCode = 200, body = """{"imported_count":3,"feeds":[]}""")
            request.method == "GET" && request.url.endsWith("/api/v1/feeds") ->
                HttpResponse(statusCode = 200, body = """{"feeds":[]}""")
            request.method == "GET" && request.url.endsWith("/api/v1/tags") ->
                HttpResponse(statusCode = 200, body = """{"tags":[]}""")
            request.method == "GET" && request.url.contains("/api/v1/items") ->
                HttpResponse(statusCode = 200, body = """{"items":[]}""")
            else -> HttpResponse(statusCode = 404, body = "{}")
        }
    }

internal fun buildPeerSyncAndExternalFeedMockClient(
    peerSyncBodies: MutableList<String>
): RocheApiClient = RocheApiClient { request ->
    when {
        request.method == "GET" && request.url == "https://blog.example.com/rss.xml" ->
            mockBlogRssResponse()
        request.method == "GET" && request.url == "https://protected.example.com/feed.xml" ->
            mockProtectedAtomResponse()
        request.method == "POST" && request.url.endsWith("/api/v1/sync/peer") ->
            mockPeerSyncResponse(request.body.orEmpty(), peerSyncBodies)
        else -> HttpResponse(statusCode = 404, body = "{}")
    }
}

private fun mockBlogRssResponse(): HttpResponse =
    HttpResponse(
        statusCode = 200,
        body =
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<rss version="2.0"><channel>""" +
                """<title>Systems Engineering Blog</title>""" +
                """<link>https://blog.example.com</link>""" +
                """<description>Deep dives</description>""" +
                """<item><title>Post 1: Consensus</title>""" +
                """<link>https://blog.example.com/post-1</link>""" +
                """<description>&lt;p&gt;Full article with &lt;img src="/images/diagram.png"&gt;&lt;/p&gt;</description></item>""" +
                """<item><title>Post 2: Ephemeral</title>""" +
                """<link>https://blog.example.com/post-2</link>""" +
                """<description>&lt;p&gt;Will be deleted&lt;/p&gt;</description></item>""" +
                """</channel></rss>""",
    )

private fun mockProtectedAtomResponse(): HttpResponse =
    HttpResponse(
        statusCode = 200,
        body =
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<feed xmlns="http://www.w3.org/2005/Atom">""" +
                """<title>Protected Feed</title>""" +
                """<link href="https://protected.example.com" rel="alternate"/>""" +
                """<entry><title>Rescued by Android</title>""" +
                """<link href="https://protected.example.com/rescued-article" rel="alternate"/>""" +
                """<id>https://protected.example.com/rescued-article</id>""" +
                """<published>2026-06-01T12:00:00Z</published>""" +
                """<content type="html">&lt;p&gt;Fetched via phone Wi-Fi&lt;/p&gt;</content></entry>""" +
                """</feed>""",
    )

private fun mockPeerSyncResponse(
    body: String,
    peerSyncBodies: MutableList<String>,
): HttpResponse {
    peerSyncBodies.add(body)
    val post1Id =
        deterministicItemId("https://blog.example.com/rss.xml", "https://blog.example.com/post-1")
    val rescuedId =
        deterministicItemId(
            "https://protected.example.com/feed.xml",
            "https://protected.example.com/rescued-article",
        )
    val blogFeedId = deterministicFeedId("https://blog.example.com/rss.xml")
    val protectedFeedId = deterministicFeedId("https://protected.example.com/feed.xml")
    val hasRescued =
        body.contains("https://protected.example.com/rescued-article") ||
            body.contains(""""updated_after"""")
    val isDeltaRequest = body.contains(""""updated_after"""")
    val post1Json =
        """{"id":"$post1Id","feed_id":"$blogFeedId","feed_url":"https://blog.example.com/rss.xml","feed_title":"Systems Engineering Blog","title":"Post 1: Consensus","url":"https://blog.example.com/post-1","summary":"Full article with","content_html":"<p>Full article with <img src=\"https://blog.example.com/images/diagram.png\"></p>","thumbnail_url":"https://blog.example.com/images/diagram.png","content_type":"article","status":"unread","is_favorite":true,"read_at":"2026-06-01T12:00:00Z","created_at":"2026-06-01T12:00:00Z","tag_names":["distributed"]}"""
    val rescuedJson =
        """{"id":"$rescuedId","feed_id":"$protectedFeedId","feed_url":"https://protected.example.com/feed.xml","feed_title":"Protected Feed","title":"Rescued by Android","url":"https://protected.example.com/rescued-article","summary":"Fetched via phone Wi-Fi","content_html":"<p>Fetched via phone Wi-Fi</p>","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-06-01T12:01:00Z","tag_names":[]}"""
    val itemsJson =
        when {
            isDeltaRequest -> "[$rescuedJson]"
            hasRescued -> "[$post1Json,$rescuedJson]"
            else -> "[$post1Json]"
        }
    val protectedFeedState =
        if (hasRescued) {
            """{"id":"$protectedFeedId","title":"Protected Feed","url":"https://protected.example.com/feed.xml","feed_type":"rss","poll_interval":30,"last_polled":"2026-06-01T12:01:00Z","last_polled_by":"android-peer-1","last_error":null,"last_error_by":null,"is_active":true,"unread_count":1}"""
        } else {
            """{"id":"$protectedFeedId","title":"Protected Feed","url":"https://protected.example.com/feed.xml","feed_type":"rss","poll_interval":30,"last_polled":"2026-06-01T11:59:00Z","last_polled_by":"server","last_error":"HTTP 403 Forbidden","last_error_by":"server","is_active":true,"unread_count":0}"""
        }
    return HttpResponse(
        statusCode = 200,
        body =
            """{"server_peer_id":"server","sync_timestamp":"2026-06-01T12:01:00Z",""" +
                """"active_peers":[{"peer_id":"server","device_name":"Headless","network_tier":"wired","weight":1.0,"last_seen_at":"2026-06-01T12:01:00Z"},""" +
                """{"peer_id":"android-peer-1","device_name":"Pixel 10 Pro","network_tier":"wifi","weight":0.6,"last_seen_at":"2026-06-01T12:01:00Z"}],""" +
                """"feeds":[{"id":"$blogFeedId","title":"Systems Engineering Blog","url":"https://blog.example.com/rss.xml","feed_type":"rss","poll_interval":30,"last_polled":"2026-06-01T12:00:00Z","last_polled_by":"android-peer-1","is_active":true,"unread_count":0},$protectedFeedState],""" +
                """"items":$itemsJson,"tags":[{"id":"${deterministicTagId("distributed")}","name":"distributed","unread_count":0}],"tombstones":[]}""",
    )
}
