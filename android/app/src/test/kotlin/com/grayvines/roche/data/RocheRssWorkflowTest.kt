@file:Suppress("LongMethod")

package com.grayvines.roche.data

import androidx.lifecycle.viewModelScope
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.SeedData
import com.grayvines.roche.data.local.updateFeedActiveGraph
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.ui.ArticleBlock
import com.grayvines.roche.ui.MainViewModel
import com.grayvines.roche.ui.extractInboxSnippet
import com.grayvines.roche.ui.parseHtmlArticleBlocks
import com.grayvines.roche.ui.prepareInlineHtmlForCompose
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
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
@Suppress("LargeClass")
class RocheRssWorkflowTest {
    @TempDir lateinit var tempDir: File

    private lateinit var testEnv: TestRocheEnv
    private val database: RocheDatabase
        get() = testEnv.database

    private val settings: SettingsRepository
        get() = testEnv.settings

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        testEnv = TestRocheEnv(tempDir, "workflow_settings.preferences_pb")
    }

    @AfterEach
    fun tearDown() {
        testEnv.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedDatabase() = testEnv.seedDefaultData()

    @Test
    fun `hydrateItemContentIfNeeded archiveEntriesBatch OpmlImportExport and HtmlArticleBlocks work end to end`() =
        runTest {
            seedDatabase()
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
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

            // 3. Batch archive updates Room entry_state to dismissed and marks dirty for peer sync
            val archivedCount =
                batchRepo.archiveEntriesBatch(
                    entryIds = listOf("item-1", "item-2"),
                    allUnread = false,
                )
            assertEquals(2, archivedCount)
            assertEquals("dismissed", database.dao().getEntryStateById("item-1")?.inboxState)
            assertTrue(database.dao().getEntryStateById("item-1")?.isLocalDirty == true)
            val afterBatch = batchRepo.snapshot.first().entries.associateBy { it.id }
            assertTrue(afterBatch.getValue("item-1").isArchived)
            assertTrue(afterBatch.getValue("item-2").isArchived)

            // 4. Offline exportOpml falls back to local OPML 2.0 XML generation
            val exportedXml = batchRepo.exportOpml().getOrThrow()
            assertTrue(exportedXml.contains("<opml version=\"2.0\">"))
            assertTrue(exportedXml.contains("arXiv cs.LG"))

            // 5. importOpml posts to server and triggers peer sync
            assertEquals(3, batchRepo.importOpml("<opml version=\"2.0\"/>").getOrThrow())
            assertTrue(requests.contains("POST https://100.64.0.12:8080/api/v1/sync/peer"))
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
    fun `parseHtmlArticleBlocks separates blockquote attribution from quote body`() {
        val html =
            "<blockquote>\n" +
                "<p>We mathematicians need to put far greater effort into communicating " +
                "mathematical ideas. To accomplish this, we need to pay much more attention " +
                "to communicating not just our definitions, theorems, and proofs, but also " +
                "our ways of thinking.</p>\n" +
                "<cite>William P. Thurston, <em>On Proof and Progress in Mathematics</em> (1994)</cite></blockquote>" +
                """<figure><blockquote>Agents make it easy to build personal tools.</blockquote>""" +
                """<figcaption>— <a href="https://x.com/ycombinator">Y Combinator</a></figcaption></figure>""" +
                """<blockquote><p>“Every child comes with the message that God is not yet discouraged of man.”</p><p>— Rabindranath Tagore</p></blockquote>""" +
                """<blockquote><p>I watched <cite>Voyager</cite> last night.</p></blockquote>"""

        val blocks = parseHtmlArticleBlocks(rawHtml = html)
        assertEquals(4, blocks.size)

        val xenaQuote = blocks[0] as ArticleBlock.Blockquote
        assertEquals(
            "We mathematicians need to put far greater effort into communicating mathematical ideas. " +
                "To accomplish this, we need to pay much more attention to communicating not just " +
                "our definitions, theorems, and proofs, but also our ways of thinking.",
            xenaQuote.html,
        )
        assertEquals(
            "William P. Thurston, <em>On Proof and Progress in Mathematics</em> (1994)",
            xenaQuote.attributionHtml,
        )

        val figureQuote = blocks[1] as ArticleBlock.Blockquote
        assertEquals("Agents make it easy to build personal tools.", figureQuote.html)
        assertEquals(
            """— <a href="https://x.com/ycombinator">Y Combinator</a>""",
            figureQuote.attributionHtml,
        )

        val dashQuote = blocks[2] as ArticleBlock.Blockquote
        assertEquals(
            "“Every child comes with the message that God is not yet discouraged of man.”",
            dashQuote.html,
        )
        assertEquals("— Rabindranath Tagore", dashQuote.attributionHtml)

        val inlineCiteQuote = blocks[3] as ArticleBlock.Blockquote
        assertEquals("I watched <cite>Voyager</cite> last night.", inlineCiteQuote.html)
        assertEquals(null, inlineCiteQuote.attributionHtml)

        val richHtml =
            """<p>Assume <img src="https://s0.wp.com/latex.php?latex=p%5Cgeq+17" alt="p\geq 17">""" +
                """ and smile <img src="https://s0.wp.com/wp-content/mu-plugins/wpcom-smileys/twemoji/2/svg/1f61b.svg" alt="😛">.</p>""" +
                """<ol><li><img src="https://s0.wp.com/wp-content/mu-plugins/wpcom-smileys/twemoji/2/svg/1f389.svg" alt="🎉"> """ +
                """<a href="#identifier_0_3289"><img src="https://s.w.org/images/core/emoji/17.0.2/72x72/21a9.png" alt="↩">︎</a></li></ol>""" +
                "<ul><li>Outer 1<ul><li>Nested 1a</li><li>Nested 1b</li></ul></li><li>Outer 2</li></ul>" +
                "<table><tbody><tr><td><pre>1\n2</pre></td>" +
                "<td><pre><code>val x = 1\nval y = 2</code></pre></td></tr></tbody></table>" +
                "<table><thead><tr><th>Name</th><th>Score</th></tr></thead>" +
                "<tbody><tr><td>Alice</td><td>99</td></tr></tbody></table>"

        val richBlocks = parseHtmlArticleBlocks(rawHtml = richHtml)
        assertEquals(5, richBlocks.size)
        assertEquals(
            "Assume <em>p ≥ 17</em> and smile 😛.",
            (richBlocks[0] as ArticleBlock.Paragraph).html,
        )
        assertEquals(
            listOf("🎉"),
            (richBlocks[1] as ArticleBlock.ListBlock).itemsHtml,
        )
        assertEquals(
            listOf(
                "Outer 1<br/>&nbsp;&nbsp;◦ Nested 1a<br/>&nbsp;&nbsp;◦ Nested 1b",
                "Outer 2",
            ),
            (richBlocks[2] as ArticleBlock.ListBlock).itemsHtml,
        )
        assertEquals(
            "val x = 1\nval y = 2",
            (richBlocks[3] as ArticleBlock.CodeBlock).code,
        )
        val tableBlock = richBlocks[4] as ArticleBlock.TableBlock
        assertEquals(listOf("Name", "Score"), tableBlock.headers)
        assertEquals(listOf(listOf("Alice", "99")), tableBlock.rows)
        assertEquals(
            "Run <tt>cargo test</tt> with <tt>Ctrl+C</tt> for <em>x</em>",
            prepareInlineHtmlForCompose(
                "Run <code>cargo test</code> with <kbd>Ctrl+C</kbd> for <var>x</var>"
            ),
        )

        val edgeHtml =
            "<p>Check out\n" +
                """<a href="https://example.com">this tool</a>""" +
                "\nto run.</p>" +
                "<ul><b><li>Bold item</li></b></ul>" +
                "<dl><dt>Term</dt><dd>Definition</dd></dl>" +
                "<blockquote><p>Before code</p><pre><code>val a = 1</code></pre><p>After code</p><cite>Author</cite></blockquote>"
        val edgeBlocks = parseHtmlArticleBlocks(rawHtml = edgeHtml)
        assertEquals(6, edgeBlocks.size)
        assertEquals(
            """Check out <a href="https://example.com">this tool</a> to run.""",
            (edgeBlocks[0] as ArticleBlock.Paragraph).html,
        )
        assertEquals(
            listOf("<b>Bold item</b>"),
            (edgeBlocks[1] as ArticleBlock.ListBlock).itemsHtml,
        )
        assertEquals(
            "<strong>Term</strong><br/>Definition",
            (edgeBlocks[2] as ArticleBlock.Paragraph).html,
        )
        assertEquals("Before code", (edgeBlocks[3] as ArticleBlock.Blockquote).html)
        assertEquals("val a = 1", (edgeBlocks[4] as ArticleBlock.CodeBlock).code)
        assertEquals("After code", (edgeBlocks[5] as ArticleBlock.Blockquote).html)
        assertEquals("Author", (edgeBlocks[5] as ArticleBlock.Blockquote).attributionHtml)
    }

    @Test
    fun `MainViewModel performImportOpml and local batch archive work`() = runTest {
        seedDatabase()
        settings.update {
            it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
        }
        val requests = mutableListOf<String>()
        val batchRepo =
            RocheRepository(
                database = database,
                apiClient = buildBatchAndOpmlMockClient(requests),
                settings = settings,
            )
        val vm = MainViewModel(repository = batchRepo, settingsRepository = settings)

        // Local-only unpushed item is archived locally in entry_state and marked dirty
        val localEntity =
            createSavedItemEntity(
                url = "https://example.com/local-only",
                title = "Local Unpushed",
                hostTitle = "example.com",
                type = ContentType.ARTICLE,
            )
        database.dao().upsertItem(localEntity, markDirty = true)
        assertEquals(1, batchRepo.archiveEntriesBatch(listOf(localEntity.id)))
        assertEquals(
            "dismissed",
            database.dao().getEntryStateById(localEntity.id)?.inboxState,
        )
        assertTrue(database.dao().getEntryStateById(localEntity.id)?.isLocalDirty == true)

        // ViewModel importOpml updates statusBanner with imported count
        try {
            vm.performImportOpml("<opml version=\"2.0\"/>")
            assertEquals(
                "Imported 3 feeds from OPML",
                vm.uiState.first { it.statusBanner != null }.statusBanner,
            )
        } finally {
            vm.playerController.releaseController()
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
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
            FeedRow(
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
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-peer")
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
                    serverUrl = "https://100.64.0.12:8080",
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

            // 4. Subsequent peer sync sends since_seq cursor, omits unchanged items outbound,
            // and preserves unchanged local items + tags when server returns a delta entries
            // payload
            assertEquals("2", settings.settings.first().lastPeerSyncCursor)
            peerSyncBodies.clear()
            val afterDeltaCount = peerRepo.syncWithServer().getOrThrow()
            assertEquals(3, afterDeltaCount)
            assertEquals(1, peerSyncBodies.size)
            assertTrue(
                peerSyncBodies[0].contains(""""since_seq":2"""),
                "Second sync must include since_seq watermark cursor",
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
            val pagedClient = buildPaginatedAndResetPeerSyncClient(peerSyncBodies)
            settings.update {
                it.copy(
                    serverUrl = "https://100.64.0.12:8080",
                    bearerToken = "tok-peer",
                    peerId = "android-peer-1",
                    lastPeerSyncCursor = "999",
                )
            }
            val repo =
                RocheRepository(
                    database = database,
                    apiClient = pagedClient,
                    settings = settings,
                    networkTierProvider = { NetworkTier.OFFLINE },
                )
            val count = repo.syncWithServer().getOrThrow()
            assertEquals(2, count)
            assertEquals("20", settings.settings.first().lastPeerSyncCursor)
            assertEquals(3, peerSyncBodies.size)
            assertTrue(peerSyncBodies[0].contains(""""since_seq":999"""))
            assertFalse(peerSyncBodies[1].contains(""""since_seq":"""))
            assertTrue(peerSyncBodies[2].contains(""""since_seq":10"""))
        }

    @Test
    fun `offline toggleFeedArchive persists pending feed mutation and resubscribing unarchives feed`() =
        runTest {
            seedDatabase()
            val peerSyncBodies = mutableListOf<String>()
            var failSync = true
            val client = buildOfflineFeedArchiveMockClient(peerSyncBodies) { failSync }
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val repo =
                RocheRepository(
                    database = database,
                    apiClient = client,
                    settings = settings,
                    networkTierProvider = { NetworkTier.OFFLINE },
                )

            // 1. Archive feed while offline -> marks feed_sources dirty locally
            val (title, isArchived) = repo.toggleFeedArchive("feed-arxiv-cs-lg").getOrThrow()
            assertEquals("arXiv cs.LG", title)
            assertTrue(isArchived)
            assertFalse(database.dao().getFeedById("feed-arxiv-cs-lg")!!.isActive)
            assertTrue(database.dao().getFeedSourceById("feed-arxiv-cs-lg")!!.isLocalDirty)

            // 2. Sync while server unreachable fails cleanly and preserves local archived state
            assertTrue(repo.syncWithServer().isFailure)
            assertFalse(database.dao().getFeedById("feed-arxiv-cs-lg")!!.isActive)

            // 3. Sync when back online pushes feed_source is_active:false with newer HLC
            // and preserves local archived state even if server returns stale HLC is_active:true
            failSync = false
            repo.syncWithServer().getOrThrow()
            assertEquals(1, peerSyncBodies.size)
            assertTrue(peerSyncBodies.single().contains(""""is_active":false"""))
            assertFalse(database.dao().getFeedById("feed-arxiv-cs-lg")!!.isActive)
            assertFalse(database.dao().getFeedSourceById("feed-arxiv-cs-lg")!!.isLocalDirty)

            // 4. Re-subscribing to the archived feed URL unarchives it in place
            database
                .dao()
                .updateFeedActiveGraph("feed-arxiv-cs-lg", isActive = false, markDirty = false)
            val saved =
                repo.saveUrlOrFeed(
                    inputUrl = "https://export.arxiv.org/api/query?search_query=cat:cs.LG",
                    titleOverride = null,
                    tagCsv = "",
                )
            assertEquals("arXiv cs.LG", saved.title)
            assertTrue(database.dao().getFeedById("feed-arxiv-cs-lg")!!.isActive)
        }

    @Test
    fun `canonicalizeSyncUrl isSyncablePublicUrl and isSyncableItemUrl align with server rules`() {
        assertEquals("https://example.com/", canonicalizeSyncUrl("HTTPS://Example.COM:443"))
        assertEquals(
            "http://example.com/rss?a=1#top",
            canonicalizeSyncUrl("http://Example.com:80/rss?a=1#top"),
        )
        assertEquals(
            deterministicFeedId("https://example.com"),
            deterministicFeedId("HTTPS://EXAMPLE.COM:443/"),
        )
        assertEquals(
            deterministicItemId("https://example.com", "https://example.com:8443/post"),
            deterministicItemId("https://example.com/", "HTTPS://EXAMPLE.COM:8443/post"),
        )

        val blockedFeedUrls =
            listOf(
                "http://intranet/rss.xml",
                "https://user:pass@example.com/rss.xml",
                "https://example.com:8443/rss.xml",
                "http://127.0.0.1/rss.xml",
                "http://[::1]/rss.xml",
                "http://10.0.0.1/rss.xml",
                "http://192.168.1.50/rss.xml",
                "http://172.16.0.1/rss.xml",
                "http://172.31.255.254/rss.xml",
                "http://169.254.169.254/rss.xml",
                "http://100.64.0.1/rss.xml",
                "http://100.127.255.1/rss.xml",
                "http://0.0.0.0/rss.xml",
                "http://nas.local/rss.xml",
                "http://router.lan/rss.xml",
                "http://svc.internal/rss.xml",
                "http://hub.home.arpa/rss.xml",
                "https://box.tail9876.ts.net/rss.xml",
                "http://dev.localhost/rss.xml",
            )
        blockedFeedUrls.forEach { url -> assertFalse(isSyncablePublicUrl(url), url) }

        assertTrue(isSyncablePublicUrl("https://blog.example.com/rss.xml"))
        assertTrue(isSyncablePublicUrl("http://172.15.0.1/rss.xml"))
        assertTrue(isSyncablePublicUrl("http://172.32.0.1/rss.xml"))
        assertTrue(isSyncablePublicUrl("http://100.63.255.1/rss.xml"))
        assertTrue(isSyncablePublicUrl("http://100.128.0.1/rss.xml"))

        assertTrue(isSyncableItemUrl("https://example.com:8443/article"))
        assertFalse(isSyncableItemUrl("https://user:pass@example.com/article"))
        assertFalse(isSyncableItemUrl("ftp://example.com/file"))
    }

    @Test
    fun `LocalFeedPoller leaves contentText null and computes reading estimate from long HTML`() =
        runTest {
            seedDatabase()
            val longHtmlBody = "<p>" + List(2_200) { "word$it" }.joinToString(" ") + "</p>"
            val rssXml =
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">""" +
                    """<channel><title>Longform Essays</title><link>https://essays.example.com</link>""" +
                    """<item><title>Deep Architecture Essay</title>""" +
                    """<link>https://essays.example.com/deep-essay</link>""" +
                    """<description>Short teaser excerpt only.</description>""" +
                    """<content:encoded><![CDATA[$longHtmlBody]]></content:encoded></item>""" +
                    """</channel></rss>"""
            val pollClient = RocheApiClient { request ->
                if (request.url == "https://essays.example.com/rss.xml") {
                    HttpResponse(statusCode = 200, body = rssXml)
                } else {
                    HttpResponse(statusCode = 404, body = "{}")
                }
            }
            val pollRepo =
                RocheRepository(database = database, apiClient = pollClient, settings = settings)
            pollRepo.saveUrlOrFeed(
                inputUrl = "https://essays.example.com/rss.xml",
                titleOverride = null,
                tagCsv = "",
            )

            val essayId =
                deterministicItemId(
                    "https://essays.example.com/rss.xml",
                    "https://essays.example.com/deep-essay",
                )
            val storedEntity = database.dao().getItemById(essayId)
            assertNull(storedEntity?.contentText)
            val entry = pollRepo.snapshot.first().entries.first { it.id == essayId }
            assertNull(entry.contentText)
            assertEquals("12m", entry.readEstimateLabel)
        }

    @Test
    fun `syncWithServer chunks large HTML item payloads under server body limit without 413`() =
        runTest {
            val largeHtml = "<p>" + "a".repeat(350_000) + "</p>"
            val baseItem = SeedData.items.first()
            val expectedUrls = mutableSetOf<String>()
            for (idx in 1..20) {
                val url = "https://essays.example.com/longform-$idx"
                expectedUrls.add(url)
                database
                    .dao()
                    .upsertItem(
                        baseItem.copy(
                            id = "large-item-$idx",
                            feedId = null,
                            feedUrl = null,
                            title = "Longform Essay $idx",
                            url = url,
                            contentHtml = largeHtml,
                        ),
                        markDirty = true,
                    )
            }
            settings.update {
                it.copy(serverUrl = "https://roche.tail9876.ts.net", bearerToken = "tok-chunked")
            }

            val receivedBodies = mutableListOf<String>()
            val strictLimitClient = buildStrictLimitChunkingClient(receivedBodies)

            val chunkRepo =
                RocheRepository(
                    database = database,
                    apiClient = strictLimitClient,
                    settings = settings,
                    networkTierProvider = { NetworkTier.OFFLINE },
                )

            val syncedCount = chunkRepo.syncWithServer().getOrThrow()
            assertEquals(20, syncedCount)
            assertTrue(receivedBodies.size >= 4)
            expectedUrls.forEach { url ->
                assertTrue(
                    receivedBodies.any { it.contains(url) },
                    "Expected $url to be pushed across chunked sync requests",
                )
            }
        }
}

private fun buildStrictLimitChunkingClient(receivedBodies: MutableList<String>): RocheApiClient {
    val storedEntryJsons = mutableListOf<String>()
    var nextSeq = 0L
    return RocheApiClient { request ->
        if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
            val body = request.body.orEmpty()
            if (body.toByteArray().size > 6 * 1024 * 1024) {
                HttpResponse(statusCode = 413, body = "length limit exceeded")
            } else {
                receivedBodies.add(body)
                nextSeq += 1L
                val parsedObj =
                    kotlinx.serialization.json.Json.parseToJsonElement(body)
                        as kotlinx.serialization.json.JsonObject
                val incomingEntries = parsedObj["entries"] as? kotlinx.serialization.json.JsonArray
                incomingEntries?.forEach { storedEntryJsons.add(it.toString()) }
                val isDelta = parsedObj.containsKey("since_seq")
                val respEntries = if (isDelta) "[]" else "[${storedEntryJsons.joinToString(",")}]"
                HttpResponse(
                    statusCode = 200,
                    body =
                        """{"server_peer_id":"server","sync_seq":$nextSeq,"server_hlc":"00179492000000$nextSeq-0000-server","sync_timestamp":"2026-11-16T20:00:00Z","active_peers":[],"entries":$respEntries,"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
                )
            }
        } else {
            HttpResponse(statusCode = 404, body = "{}")
        }
    }
}

private fun buildOfflineFeedArchiveMockClient(
    peerSyncBodies: MutableList<String>,
    shouldFailSync: () -> Boolean,
): RocheApiClient = RocheApiClient { request ->
    if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
        if (shouldFailSync()) {
            throw java.io.IOException("Offline")
        }
        val body = request.body.orEmpty()
        peerSyncBodies.add(body)
        HttpResponse(
            statusCode = 200,
            body =
                """{"server_peer_id":"server","sync_seq":1,"server_hlc":"001780000000000-0001-server",""" +
                    """"sync_timestamp":"2026-06-01T12:00:00Z","active_peers":[],""" +
                    """"entries":[{"id":"feed-arxiv-cs-lg","kind":"feed","title":"arXiv cs.LG",""" +
                    """"url":"https://export.arxiv.org/list/cs.LG/recent","hlc":"000000000000001-0000-server",""" +
                    """"feed_source":{"feed_url":"https://export.arxiv.org/api/query?search_query=cat:cs.LG",""" +
                    """"source_type":"arxiv","feed_title":"arXiv cs.LG","is_active":true,"hlc":"000000000000001-0000-server"}}],""" +
                    """"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
        )
    } else {
        HttpResponse(statusCode = 404, body = "{}")
    }
}

private suspend fun verifyRoomMigrationsPreserveData(tempDir: File) {
    val driver = BundledSQLiteDriver()
    val dbFile = File(tempDir, "migrated_v1_to_v10.db")
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
        RocheDatabase.MIGRATION_5_6.migrate(conn)
        RocheDatabase.MIGRATION_6_7.migrate(conn)
        RocheDatabase.MIGRATION_7_8.migrate(conn)
        RocheDatabase.MIGRATION_8_9.migrate(conn)
        RocheDatabase.MIGRATION_9_10.migrate(conn)
        conn.prepare("SELECT COUNT(*) FROM entries WHERE id = 'i-1'").use { stmt ->
            assertTrue(stmt.step())
            assertEquals(1L, stmt.getLong(0))
        }
        conn
            .prepare(
                "SELECT autoDownloadEpisodes, customPlaybackSpeed FROM feed_sources WHERE collectionId = 'f-1'"
            )
            .use { stmt ->
                assertTrue(stmt.step())
                assertEquals(0L, stmt.getLong(0))
                assertTrue(stmt.isNull(1))
            }
        conn.prepare("SELECT audioUrl FROM entry_content WHERE entryId = 'i-1'").use { stmt ->
            assertTrue(stmt.step())
            assertTrue(stmt.isNull(0))
        }
    }
}

private fun buildPaginatedAndResetPeerSyncClient(
    peerSyncBodies: MutableList<String>
): RocheApiClient = RocheApiClient { request ->
    if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
        val body = request.body.orEmpty()
        peerSyncBodies.add(body)
        val feedId = deterministicFeedId("https://blog.example.com/rss.xml")
        val item1Id =
            deterministicItemId(
                "https://blog.example.com/rss.xml",
                "https://blog.example.com/post-1",
            )
        val item2Id =
            deterministicItemId(
                "https://blog.example.com/rss.xml",
                "https://blog.example.com/post-2",
            )
        val respBody =
            when {
                body.contains(""""since_seq":999""") ->
                    """{"server_peer_id":"server","sync_seq":20,"server_hlc":"001780000020000-0000-server","sync_timestamp":"2026-06-01T12:01:00Z","reset_required":true,"active_peers":[]}"""
                body.contains(""""since_seq":10""") ->
                    """{"server_peer_id":"server","sync_seq":20,"server_hlc":"001780000020000-0000-server","sync_timestamp":"2026-06-01T12:01:00Z","has_more":false,"active_peers":[],""" +
                        """"entries":[{"id":"$item2Id","kind":"article","title":"Post 2","url":"https://blog.example.com/post-2","created_at":"2026-06-01T12:01:00Z","hlc":"001780000020000-0000-server","content":{"content_type":"article"},"state":{"inbox_state":"unread","hlc":"001780000020000-0000-server"}}],""" +
                        """"collection_entries":[{"collection_id":"$feedId","entry_id":"$item2Id","added_at":"2026-06-01T12:01:00Z","hlc":"001780000020000-0000-server"}],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}"""
                else ->
                    """{"server_peer_id":"server","sync_seq":10,"server_hlc":"001780000010000-0000-server","sync_timestamp":"2026-06-01T12:00:00Z","has_more":true,"active_peers":[],""" +
                        """"entries":[{"id":"$feedId","kind":"feed","title":"Systems Engineering Blog","url":"https://blog.example.com","hlc":"001780000010000-0000-server","feed_source":{"feed_url":"https://blog.example.com/rss.xml","source_type":"rss","feed_title":"Systems Engineering Blog","is_active":true,"hlc":"001780000010000-0000-server"}},""" +
                        """{"id":"$item1Id","kind":"article","title":"Post 1","url":"https://blog.example.com/post-1","created_at":"2026-06-01T12:00:00Z","hlc":"001780000010000-0000-server","content":{"content_type":"article"},"state":{"inbox_state":"unread","hlc":"001780000010000-0000-server"}}],""" +
                        """"collection_entries":[{"collection_id":"$feedId","entry_id":"$item1Id","added_at":"2026-06-01T12:00:00Z","hlc":"001780000010000-0000-server"}],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}"""
            }
        HttpResponse(statusCode = 200, body = respBody)
    } else {
        HttpResponse(statusCode = 404, body = "{}")
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
            request.method == "POST" && request.url.endsWith("/api/v1/import/opml") ->
                HttpResponse(statusCode = 200, body = """{"imported_count":3,"feeds":[]}""")
            request.method == "POST" && request.url.endsWith("/api/v1/sync/peer") ->
                HttpResponse(
                    statusCode = 200,
                    body =
                        """{"server_peer_id":"server","sync_seq":1,"server_hlc":"001780000000000-0001-server","sync_timestamp":"2026-06-01T12:00:00Z","active_peers":[],"entries":[],"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
                )
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
    val tagId = deterministicTagId("distributed")
    val isDeltaRequest = body.contains(""""since_seq":""")
    val hasRescued =
        body.contains("https://protected.example.com/rescued-article") || isDeltaRequest
    val syncSeq = if (isDeltaRequest) 2 else 1
    val hlc = "00178000000${syncSeq}000-0000-server"
    val blogFeedEntry =
        """{"id":"$blogFeedId","kind":"feed","title":"Systems Engineering Blog","url":"https://blog.example.com","hlc":"$hlc","feed_source":{"feed_url":"https://blog.example.com/rss.xml","source_type":"rss","feed_title":"Systems Engineering Blog","poll_interval":30,"is_active":true,"last_polled_at":"2026-06-01T12:00:00Z","last_polled_by":"android-peer-1","hlc":"$hlc"}}"""
    val protectedFeedEntry =
        if (hasRescued) {
            """{"id":"$protectedFeedId","kind":"feed","title":"Protected Feed","url":"https://protected.example.com","hlc":"$hlc","feed_source":{"feed_url":"https://protected.example.com/feed.xml","source_type":"rss","feed_title":"Protected Feed","poll_interval":30,"is_active":true,"last_polled_at":"2026-06-01T12:01:00Z","last_polled_by":"android-peer-1","last_error":null,"last_error_by":null,"hlc":"$hlc"}}"""
        } else {
            """{"id":"$protectedFeedId","kind":"feed","title":"Protected Feed","url":"https://protected.example.com","hlc":"$hlc","feed_source":{"feed_url":"https://protected.example.com/feed.xml","source_type":"rss","feed_title":"Protected Feed","poll_interval":30,"is_active":true,"last_polled_at":"2026-06-01T11:59:00Z","last_polled_by":"server","last_error":"HTTP 403 Forbidden","last_error_by":"server","hlc":"$hlc"}}"""
        }
    val tagEntry =
        """{"id":"$tagId","kind":"collection","title":"distributed","hlc":"$hlc","collection_spec":{"mode":"manual","role":"tag","show_in_lists":false}}"""
    val post1Entry =
        """{"id":"$post1Id","kind":"article","title":"Post 1: Consensus","url":"https://blog.example.com/post-1","summary":"Full article with","thumbnail_url":"https://blog.example.com/images/diagram.png","created_at":"2026-06-01T12:00:00Z","hlc":"$hlc","content":{"content_type":"article","content_html":"<p>Full article with <img src=\"https://blog.example.com/images/diagram.png\"></p>"},"state":{"inbox_state":"unread","read_at":"2026-06-01T12:00:00Z","progress":0.0,"hlc":"$hlc"}}"""
    val rescuedEntry =
        """{"id":"$rescuedId","kind":"article","title":"Rescued by Android","url":"https://protected.example.com/rescued-article","summary":"Fetched via phone Wi-Fi","created_at":"2026-06-01T12:01:00Z","hlc":"$hlc","content":{"content_type":"article","content_html":"<p>Fetched via phone Wi-Fi</p>"},"state":{"inbox_state":"unread","progress":0.0,"hlc":"$hlc"}}"""
    val entriesJson =
        when {
            isDeltaRequest -> "[$protectedFeedEntry,$rescuedEntry]"
            hasRescued -> "[$blogFeedEntry,$protectedFeedEntry,$tagEntry,$post1Entry,$rescuedEntry]"
            else -> "[$blogFeedEntry,$protectedFeedEntry,$tagEntry,$post1Entry]"
        }
    val post1Memberships =
        """{"collection_id":"$blogFeedId","entry_id":"$post1Id","added_at":"2026-06-01T12:00:00Z","hlc":"$hlc"},{"collection_id":"$STARRED_COLLECTION_ID","entry_id":"$post1Id","added_at":"2026-06-01T12:00:00Z","hlc":"$hlc"},{"collection_id":"$tagId","entry_id":"$post1Id","added_at":"2026-06-01T12:00:00Z","hlc":"$hlc"}"""
    val rescuedMembership =
        """{"collection_id":"$protectedFeedId","entry_id":"$rescuedId","added_at":"2026-06-01T12:01:00Z","hlc":"$hlc"}"""
    val membershipsJson =
        when {
            isDeltaRequest -> "[$rescuedMembership]"
            hasRescued -> "[$post1Memberships,$rescuedMembership]"
            else -> "[$post1Memberships]"
        }
    return HttpResponse(
        statusCode = 200,
        body =
            """{"server_peer_id":"server","sync_seq":$syncSeq,"server_hlc":"$hlc","sync_timestamp":"2026-06-01T12:01:00Z",""" +
                """"active_peers":[{"peer_id":"server","device_name":"Headless","network_tier":"wired","weight":1.0,"last_seen_at":"2026-06-01T12:01:00Z"},""" +
                """{"peer_id":"android-peer-1","device_name":"Pixel 10 Pro","network_tier":"wifi","weight":0.6,"last_seen_at":"2026-06-01T12:01:00Z"}],""" +
                """"entries":$entriesJson,"collection_entries":$membershipsJson,"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
    )
}
