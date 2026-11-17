@file:Suppress("TooManyFunctions", "LongMethod")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.ItemTagRow
import com.grayvines.roche.data.local.LIST_PREVIEW_HTML_CHARS
import com.grayvines.roche.data.local.LIST_PREVIEW_TEXT_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.SeedData
import com.grayvines.roche.data.local.TagRow
import com.grayvines.roche.data.local.buildFts5MatchQuery
import com.grayvines.roche.data.local.buildSqlContainsPattern
import com.grayvines.roche.data.local.insertItemTag
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.local.upsertFeeds
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.local.upsertItems
import com.grayvines.roche.data.local.upsertTag
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("LargeClass")
class RocheRepositoryTest {
    @TempDir lateinit var tempDir: File

    private lateinit var testEnv: TestRocheEnv
    private val database: RocheDatabase
        get() = testEnv.database

    private val settings: SettingsRepository
        get() = testEnv.settings

    private val repository: RocheRepository
        get() = testEnv.repository

    @BeforeEach
    fun setUp() {
        testEnv = TestRocheEnv(tempDir, "test_settings.preferences_pb")
    }

    @AfterEach
    fun tearDown() {
        testEnv.close()
    }

    internal suspend fun seedTestDatabase() = testEnv.seedDefaultData()

    @Test
    fun `seedTestDatabase populates initial items feeds and tags`() = runTest {
        seedTestDatabase()
        val snapshot = repository.snapshot.first()

        assertEquals(6, snapshot.entries.size)
        assertEquals(5, snapshot.feeds.size)
        assertEquals(5, snapshot.tags.size)

        val unread =
            RocheRepository.filterEntries(
                entries = snapshot.entries,
                filter = InboxFilter.UNREAD,
            )
        assertEquals(5, unread.size)
    }

    @Test
    fun `inbox filters partition articles papers videos starred and archived`() = runTest {
        seedTestDatabase()
        val entries = repository.snapshot.first().entries

        val articles = RocheRepository.filterEntries(entries, InboxFilter.ARTICLES)
        assertTrue(articles.all { it.contentType == ContentType.ARTICLE && !it.isArchived })
        assertTrue(articles.none { it.showsThumbnailPreview })

        val papers = RocheRepository.filterEntries(entries, InboxFilter.PAPERS)
        assertTrue(papers.all { it.contentType == ContentType.PDF && it.showsThumbnailPreview })

        val videos = RocheRepository.filterEntries(entries, InboxFilter.VIDEOS)
        assertTrue(videos.all { it.contentType == ContentType.VIDEO && it.showsThumbnailPreview })

        val archived = RocheRepository.filterEntries(entries, InboxFilter.ARCHIVED)
        assertEquals(1, archived.size)
        assertEquals("item-6", archived.first().id)
    }

    @Test
    fun `setArchiveState and restoreStatus support filing cabinet undo`() = runTest {
        seedTestDatabase()

        val previousStatus = repository.setArchiveState(itemId = "item-1", archived = true)
        assertEquals(ItemStatus.UNREAD, previousStatus)

        val afterArchive = repository.snapshot.first().entries.first { it.id == "item-1" }
        assertTrue(afterArchive.isArchived)
        assertTrue(afterArchive.isRead)

        repository.restoreStatus(itemId = "item-1", status = previousStatus!!)
        val afterUndo = repository.snapshot.first().entries.first { it.id == "item-1" }
        assertFalse(afterUndo.isArchived)
        assertFalse(afterUndo.isRead)
        assertEquals(ItemStatus.UNREAD, afterUndo.status)

        // Mark item-1 read first, then archive, then undo archive — readAt is preserved
        repository.setReadState(itemId = "item-1", isRead = true)
        val readBeforeArchive = repository.snapshot.first().entries.first { it.id == "item-1" }
        assertTrue(readBeforeArchive.isRead)
        val snap = repository.setArchiveStateWithSnapshot(itemId = "item-1", archived = true)!!
        assertEquals(readBeforeArchive.readAt, snap.previousReadAt)
        repository.restoreStatus(
            itemId = "item-1",
            status = snap.previousStatus,
            readAt = snap.previousReadAt,
        )
        val afterReadUndo = repository.snapshot.first().entries.first { it.id == "item-1" }
        assertFalse(afterReadUndo.isArchived)
        assertTrue(afterReadUndo.isRead)
        assertEquals(readBeforeArchive.readAt, afterReadUndo.readAt)
    }

    @Test
    fun `toggleTagOnItem adds and removes normalized tag on entry`() = runTest {
        seedTestDatabase()
        repository.toggleTagOnItem(itemId = "item-2", tagName = "nvme")

        val entry = repository.snapshot.first().entries.first { it.id == "item-2" }
        assertTrue(entry.tags.contains("nvme"))

        repository.toggleTagOnItem(itemId = "item-2", tagName = "nvme")
        val afterRemove = repository.snapshot.first().entries.first { it.id == "item-2" }
        assertFalse(afterRemove.tags.contains("nvme"))
    }

    @Test
    fun `matchesSearchQuery searches titles authors feeds urls bodies and hashtag tokens`() =
        runTest {
            seedTestDatabase()
            val entries = repository.snapshot.first().entries

            val byTag = entries.filter { matchesSearchQuery(it, "#llm-inference") }
            assertEquals(1, byTag.size)
            assertEquals("item-1", byTag.first().id)

            val byAuthor = entries.filter { matchesSearchQuery(it, "cantrill") }
            assertEquals(1, byAuthor.size)
            assertEquals("item-3", byAuthor.first().id)

            val byBody = entries.filter { matchesSearchQuery(it, "lpddr5x") }
            assertEquals(1, byBody.size)
            assertEquals("item-1", byBody.first().id)
        }

    @Test
    fun `pairWithCode exchanges 6 digit code and persists serverUrl and bearerToken`() = runTest {
        val pairingClient = RocheApiClient { request ->
            assertEquals("https://100.64.0.12:8080/auth/pair", request.url)
            HttpResponse(statusCode = 200, body = """{"token":"paired-bearer-999"}""")
        }
        val pairingRepo =
            RocheRepository(database = database, apiClient = pairingClient, settings = settings)

        val result =
            pairingRepo.pairWithCode(
                serverUrl = "100.64.0.12:8080/",
                pairingCode = " 482910 ",
                deviceName = "Pixel 9",
            )
        assertTrue(result.isSuccess)
        assertEquals("paired-bearer-999", result.getOrNull())

        val currentSettings = settings.settings.first()
        assertEquals("https://100.64.0.12:8080", currentSettings.serverUrl)
        assertEquals("paired-bearer-999", currentSettings.bearerToken)
    }

    @Test
    fun `pairWithCode auto-discovers Roche server on Tailnet when serverUrl is blank`() = runTest {
        val autoDiscoverClient = RocheApiClient { request ->
            when (request.url) {
                "https://roche/health" ->
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"status":"ok","service":"roche","canonical_url":"https://roche.tail9876.ts.net"}""",
                    )
                "https://roche.tail9876.ts.net/auth/pair" ->
                    HttpResponse(statusCode = 200, body = """{"token":"auto-paired-tok"}""")
                else -> HttpResponse(statusCode = 404, body = "{}")
            }
        }
        val autoRepo =
            RocheRepository(
                database = database,
                apiClient = autoDiscoverClient,
                settings = settings,
            )

        val result =
            autoRepo.pairWithCode(
                serverUrl = "",
                pairingCode = "9999-guitarist-whisper-orbit-falcon",
                deviceName = "Pixel 9",
            )
        assertTrue(result.isSuccess)
        assertEquals("auto-paired-tok", result.getOrNull())

        val currentSettings = settings.settings.first()
        assertEquals("https://roche.tail9876.ts.net", currentSettings.serverUrl)
        assertEquals("auto-paired-tok", currentSettings.bearerToken)
    }

    @Test
    fun `discoverServer clears existing bearerToken when switching to a different server host`() =
        runTest {
            settings.update {
                it.copy(
                    serverUrl = "https://100.64.0.12:8080",
                    bearerToken = "secret-existing-token",
                )
            }
            val discoverClient = RocheApiClient { request ->
                if (request.url == "https://roche/health") {
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"status":"ok","service":"roche","canonical_url":"https://roche.tail9876.ts.net"}""",
                    )
                } else {
                    HttpResponse(statusCode = 404, body = "{}")
                }
            }
            val repo =
                RocheRepository(
                    database = database,
                    apiClient = discoverClient,
                    settings = settings,
                )

            val discoveredUrl = repo.discoverServer().getOrThrow()
            assertEquals("https://roche.tail9876.ts.net", discoveredUrl)

            val updated = settings.settings.first()
            assertEquals("https://roche.tail9876.ts.net", updated.serverUrl)
            assertEquals("", updated.bearerToken)
        }

    @Test
    fun `discoverServer upgrades legacy cleartext ts_net URL to HTTPS and preserves token on same host`() =
        runTest {
            settings.update {
                it.copy(
                    serverUrl = "http://mac-mini.tail9876.ts.net:8080",
                    bearerToken = "keep-my-paired-token",
                )
            }
            val discoverClient = RocheApiClient { request ->
                if (request.url == "https://mac-mini.tail9876.ts.net/health") {
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"status":"ok","service":"roche","canonical_url":"https://mac-mini.tail9876.ts.net"}""",
                    )
                } else {
                    HttpResponse(statusCode = 404, body = "{}")
                }
            }
            val repo =
                RocheRepository(
                    database = database,
                    apiClient = discoverClient,
                    settings = settings,
                )

            val discoveredUrl = repo.discoverServer().getOrThrow()
            assertEquals("https://mac-mini.tail9876.ts.net", discoveredUrl)

            val updated = settings.settings.first()
            assertEquals("https://mac-mini.tail9876.ts.net", updated.serverUrl)
            assertEquals("keep-my-paired-token", updated.bearerToken)
        }

    @Test
    fun `syncWithServer populates feed items tags and preserves html entities in reader`() =
        runTest {
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val hlc = "001780000001000-0000-server"
            val syncClient = RocheApiClient { request ->
                if (request.url == "https://100.64.0.12:8080/api/v1/sync/peer") {
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"server_peer_id":"server","sync_seq":1,"server_hlc":"$hlc","sync_timestamp":"2026-04-01T10:00:00Z","active_peers":[],""" +
                                """"entries":[{"id":"f-live","kind":"feed","title":"Live RSS","url":"https://example.com","hlc":"$hlc","feed_source":{"feed_url":"https://example.com/rss","source_type":"rss","feed_title":"Live RSS","last_polled_at":"2099-01-01T00:00:00Z","hlc":"$hlc"}},""" +
                                """{"id":"t-live","kind":"collection","title":"rust","hlc":"$hlc","collection_spec":{"mode":"manual","role":"tag","show_in_lists":false}},""" +
                                """{"id":"i-live-1","kind":"article","title":"Synced Post","url":"https://example.com/1","summary":"<p>Summary &amp; intro</p>","created_at":"2026-04-01T10:00:00Z","hlc":"$hlc","content":{"content_type":"article","content_html":"<p>First para &lt;b&gt; &amp; details.</p><p>Second <em>italic</em> para with &amp;lt;tag&amp;gt;.</p>"},"state":{"inbox_state":"unread","hlc":"$hlc"}}],""" +
                                """"collection_entries":[{"collection_id":"f-live","entry_id":"i-live-1","added_at":"2026-04-01T10:00:00Z","hlc":"$hlc"},{"collection_id":"t-live","entry_id":"i-live-1","added_at":"2026-04-01T10:00:00Z","hlc":"$hlc"}],""" +
                                """"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
                    )
                } else {
                    HttpResponse(statusCode = 404, body = "{}")
                }
            }
            val syncedRepo =
                RocheRepository(database = database, apiClient = syncClient, settings = settings)

            val count = syncedRepo.syncWithServer().getOrThrow()
            assertEquals(1, count)

            val snapshot = syncedRepo.snapshot.first()
            assertEquals(1, snapshot.entries.size)
            val entry = snapshot.entries.first()
            assertEquals("i-live-1", entry.id)
            assertEquals(listOf("rust"), entry.tags)
            assertEquals(
                "<p>First para &lt;b&gt; &amp; details.</p><p>Second <em>italic</em> para with &amp;lt;tag&amp;gt;.</p>",
                entry.contentHtml,
            )
        }

    @Test
    fun `saveUrlOrFeed subscribes feed without saving raw xml item and toggleTag records tombstone`() =
        runTest {
            seedTestDatabase()
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val requests = mutableListOf<String>()
            val liveClient = RocheApiClient { request ->
                requests.add("${request.method} ${request.url}")
                if (request.method == "POST" && request.url.endsWith("/api/v1/feeds")) {
                    HttpResponse(
                        statusCode = 201,
                        body =
                            """{"id":"feed-srv-1","title":"Rust Blog","url":"https://blog.rust-lang.org/feed.xml","feed_type":"rss","unread_count":0}""",
                    )
                } else {
                    HttpResponse(statusCode = 404, body = "{}")
                }
            }
            val liveRepo =
                RocheRepository(database = database, apiClient = liveClient, settings = settings)

            val savedFeed =
                liveRepo.saveUrlOrFeed(
                    inputUrl = "https://blog.rust-lang.org/feed.xml",
                    titleOverride = "Rust Blog",
                    tagCsv = "",
                )
            assertTrue(savedFeed.isFeed)
            assertEquals("Rust Blog", savedFeed.title)
            assertFalse(requests.any { it.endsWith("/api/v1/items/save") })

            liveRepo.toggleTagOnItem(itemId = "item-2", tagName = "ebpf")
            assertTrue(database.dao().getTagNamesForItem("item-2").contains("ebpf"))
            liveRepo.toggleTagOnItem(itemId = "item-2", tagName = "ebpf")
            assertFalse(database.dao().getTagNamesForItem("item-2").contains("ebpf"))
            val tagTombstone =
                database
                    .dao()
                    .getTombstone(
                        COLLECTION_ENTRY_ENTITY,
                        collectionEntryNaturalKey(deterministicTagId("ebpf"), "item-2"),
                    )
            assertTrue(tagTombstone?.isLocalDirty == true)
        }

    @Test
    internal fun `updateReadProgress and local tag removal push mutations to server`() = runTest {
        seedTestDatabase()
        settings.update {
            it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
        }
        val syncBodies = mutableListOf<String>()
        var seq = 0L
        val liveClient = RocheApiClient { request ->
            if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
                syncBodies.add(request.body.orEmpty())
                seq += 1L
                HttpResponse(
                    statusCode = 200,
                    body =
                        """{"server_peer_id":"server","sync_seq":$seq,"server_hlc":"00178000000${seq}000-0000-server","sync_timestamp":"2026-06-01T12:00:00Z","active_peers":[],"entries":[],"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
                )
            } else {
                HttpResponse(statusCode = 404, body = "{}")
            }
        }
        val liveRepo =
            RocheRepository(
                database = database,
                apiClient = liveClient,
                settings = settings,
                networkTierProvider = { NetworkTier.OFFLINE },
            )

        liveRepo.updateReadProgress(itemId = "item-1", progress = 0.85)
        val updatedItem = repository.snapshot.first().entries.first { it.id == "item-1" }
        assertEquals(0.85, updatedItem.readProgress)
        liveRepo.syncWithServer().getOrThrow()
        assertTrue(syncBodies.last().contains(""""progress":0.85"""))

        // Marking read records an ISO readAt timestamp and toggling clears it back to null
        assertReadTimestampSync(liveRepo, syncBodies)

        // Removing an existing tag records a collection_entry tombstone and pushes it on sync
        liveRepo.toggleTagOnItem(itemId = "item-2", tagName = "sqlite")
        liveRepo.syncWithServer().getOrThrow()
        assertTrue(syncBodies.last().contains(""""natural_key":"tag-sqlite\u0000item-2""""))
    }

    @Test
    internal fun `offline mutations and saved urls are pushed on next syncWithServer without data loss`() =
        runTest {
            seedTestDatabase()
            // Perform mutations while offline (bearerToken is blank)
            repository.setArchiveState(itemId = "item-1", archived = true)
            repository.toggleStar(itemId = "item-1")
            repository.updateReadProgress(itemId = "item-1", progress = 0.9)
            repository.toggleTagOnItem(itemId = "item-1", tagName = "rust")
            repository.toggleTagOnItem(itemId = "item-1", tagName = "systems")
            repository.saveUrlOrFeed(
                inputUrl = "https://example.com/offline-note",
                titleOverride = "Offline Note",
                tagCsv = "crdt",
            )

            // Come online and sync with server
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val pushedBodies = mutableListOf<String>()
            val syncClient = buildTwoWaySyncMockClient(pushedBodies)
            val syncedRepo =
                RocheRepository(
                    database = database,
                    apiClient = syncClient,
                    settings = settings,
                    networkTierProvider = { NetworkTier.OFFLINE },
                )

            val count = syncedRepo.syncWithServer().getOrThrow()
            assertEquals(7, count)
            assertTwoWaySyncPushedRequests(pushedBodies)

            val entries = syncedRepo.snapshot.first().entries
            val item1 = entries.first { it.id == "item-1" }
            assertTrue(item1.isArchived)
            assertFalse(item1.isFavorite)
            assertEquals(0.9, item1.readProgress)
            assertEquals(listOf("llm-inference", "rust"), item1.tags)

            val savedItem = entries.first { it.url == "https://example.com/offline-note" }
            assertEquals("Offline Note", savedItem.title)
            assertEquals(listOf("crdt"), savedItem.tags)
        }

    @Test
    internal fun `YouTube channel URLs and bare UC channel IDs are classified as youtube feeds`() =
        runTest {
            assertTrue(isLikelyFeedUrl("https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw"))
            assertTrue(isLikelyFeedUrl("HTTPS://WWW.YOUTUBE.COM/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw"))
            assertTrue(isLikelyFeedUrl("UC_x5XG1OV2P6uZZ5FSM9Ttw"))
            assertTrue(isLikelyFeedUrl("https://example.com/feed/"))
            assertTrue(isLikelyFeedUrl("https://arxiv.org/rss/cs.LG"))
            assertFalse(isLikelyFeedUrl("UC_x5XG1OV2P6uZZ5FSM9Ttw/videos"))
            assertFalse(isLikelyFeedUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
            assertEquals(
                ContentType.PDF,
                inferContentType("https://example.com/paper.pdf?download=1#page=2"),
            )

            val savedChannel =
                repository.saveUrlOrFeed(
                    inputUrl = "UC_x5XG1OV2P6uZZ5FSM9Ttw",
                    titleOverride = "Google Developers",
                    tagCsv = "",
                )
            assertTrue(savedChannel.isFeed)
            assertEquals("Google Developers", savedChannel.title)
            val feed = repository.snapshot.first().feeds.first()
            assertEquals("youtube", feed.feedType.wireValue)
        }

    @Test
    internal fun `in-flight tag mutations on local item are preserved and synced when finalized`() =
        runTest {
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val liveClient = RocheApiClient { HttpResponse(statusCode = 404, body = "{}") }
            val dao = database.dao()
            val localEntity =
                createSavedItemEntity(
                    url = "https://example.com/in-flight",
                    title = "In-Flight Entry",
                    hostTitle = "example.com",
                    type = ContentType.ARTICLE,
                )
            dao.upsertItem(localEntity, markDirty = true)
            attachTagsLocally(
                dao = dao,
                itemId = localEntity.id,
                tagNames = listOf("initial"),
                markDirty = true,
            )
            val liveRepo =
                RocheRepository(database = database, apiClient = liveClient, settings = settings)
            liveRepo.toggleTagOnItem(itemId = localEntity.id, tagName = "added-in-flight")

            finalizeOnlineSavedItem(
                dao = dao,
                localEntity = localEntity,
                remoteSaved =
                    com.grayvines.roche.data.remote.parseItemJson(
                        """{"id":"srv-item-77","title":"In-Flight Entry","url":"https://example.com/in-flight","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-04-01T12:00:00Z","tag_names":["initial"]}"""
                    ),
                initialTags = listOf("initial"),
            )

            val entry = liveRepo.snapshot.first().entries.first { it.id == "srv-item-77" }
            assertEquals(listOf("added-in-flight", "initial"), entry.tags)
            assertTrue(dao.getEntryById("srv-item-77")?.isLocalDirty == true)
            val outgoing =
                buildPeerSyncRequest(
                    dao = dao,
                    config =
                        PeerSyncRequestConfig(
                            peerId = "peer-android",
                            deviceName = "Pixel",
                            sinceSeq = 5L,
                        ),
                )
            assertTrue(outgoing.entries.any { it.id == "srv-item-77" })
            assertTrue(
                outgoing.collectionEntries.any {
                    it.entryId == "srv-item-77" &&
                        it.collectionId == deterministicTagId("added-in-flight")
                }
            )
        }

    @Test
    internal fun `toggleOfflineCache hydrates item detail downloads media files and cleans up on evict`() =
        runTest {
            seedTestDatabase()
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val cacheDir = File(tempDir, "offline_media")
            val pdfBytes = "%PDF-1.7-test".toByteArray(Charsets.UTF_8)
            val thumbBytes = byteArrayOf(1, 2, 3, 4)
            val offlineClient = buildOfflineCacheMockClient(pdfBytes, thumbBytes)
            val cachedRepo =
                RocheRepository(
                    database = database,
                    apiClient = offlineClient,
                    settings = settings,
                    cacheDir = cacheDir,
                )

            val enabled = cachedRepo.toggleOfflineCache("item-5")
            assertTrue(enabled)

            val hydrated = cachedRepo.snapshot.first().entries.first { it.id == "item-5" }
            assertTrue(hydrated.isCachedOffline)
            assertEquals("<p>Hydrated full paper HTML</p>", hydrated.contentHtml)
            assertEquals("thumb-item-5", hydrated.thumbnailMediaId)

            val cachedPdf = File(File(File(cacheDir, "items"), "item-5"), "pdf-item-5")
            val cachedThumb = File(File(File(cacheDir, "items"), "item-5"), "thumb-item-5")
            val sharedThumb = File(File(cacheDir, "thumbnails"), "thumb-item-5")
            assertTrue(cachedPdf.isFile)
            assertTrue(cachedThumb.isFile)
            assertTrue(sharedThumb.isFile)
            org.junit.jupiter.api.Assertions.assertArrayEquals(pdfBytes, cachedPdf.readBytes())
            org.junit.jupiter.api.Assertions.assertArrayEquals(thumbBytes, cachedThumb.readBytes())

            // Verify syncWithServer preserves hydrated contentHtml and thumbnailMediaId when list
            // response omits them
            cachedRepo.syncWithServer().getOrThrow()
            val afterSync = cachedRepo.snapshot.first().entries.first { it.id == "item-5" }
            assertEquals("<p>Hydrated full paper HTML</p>", afterSync.contentHtml)
            assertEquals("thumb-item-5", afterSync.thumbnailMediaId)
            assertTrue(afterSync.isCachedOffline)

            val disabled = cachedRepo.toggleOfflineCache("item-5")
            assertFalse(disabled)
            assertFalse(File(File(cacheDir, "items"), "item-5").exists())

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                sanitizeCacheFileName("..")
            }
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                sanitizeCacheFileName(".")
            }
            assertEquals(
                256 to 192,
                com.grayvines.roche.ui.components.computeProportionalTargetSize(4000, 3000, 256),
            )
            assertNull(
                com.grayvines.roche.ui.components.computeProportionalTargetSize(200, 150, 256)
            )
        }

    @Test
    internal fun `refreshFeed deleteFeed and deleteItem sync with server and clean up local state`() =
        runTest {
            seedTestDatabase()
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val cacheDir = File(tempDir, "delete_item_cache")
            val itemCacheDir = File(File(cacheDir, "items"), "item-1").apply { mkdirs() }
            File(itemCacheDir, "media-paper-1").writeBytes(byteArrayOf(1, 2, 3))
            val requests = mutableListOf<String>()
            val syncBodies = mutableListOf<String>()
            val managedRepo =
                RocheRepository(
                    database = database,
                    apiClient = buildFeedAndItemManagementMockClient(requests, syncBodies),
                    settings = settings,
                    cacheDir = cacheDir,
                    networkTierProvider = { NetworkTier.OFFLINE },
                )

            val refreshedTitle = managedRepo.refreshFeed("feed-arxiv-cs-lg").getOrThrow()
            assertEquals("arXiv cs.LG", refreshedTitle)
            assertTrue(managedRepo.snapshot.first().entries.any { it.id == "item-fresh" })

            // Offline deleteItem records tombstone so syncWithServer does not resurrect item-1
            settings.update { it.copy(bearerToken = "") }
            assertTrue(managedRepo.deleteItem("item-1").getOrThrow().isNotBlank())
            assertFalse(itemCacheDir.exists())
            val item1NatKey =
                itemNaturalKey(
                    "https://export.arxiv.org/api/query?search_query=cat:cs.LG",
                    "https://arxiv.org/abs/2603.11842",
                )
            assertTrue(database.dao().getTombstone("item", item1NatKey)?.isLocalDirty == true)
            // Mutating a deleted item is a safe no-op and does not overwrite tombstone
            assertEquals(null, managedRepo.setArchiveState("item-1", archived = true))
            managedRepo.restoreStatus("item-1", ItemStatus.UNREAD)
            managedRepo.updateReadProgress("item-1", 0.5)
            managedRepo.toggleTagOnItem("item-1", "tag-1")
            assertTrue(database.dao().getTombstone("item", item1NatKey)?.isLocalDirty == true)

            // Syncing back online pushes item tombstone via peer sync and does not resurrect item-1
            settings.update { it.copy(bearerToken = "tok-live") }
            managedRepo.syncWithServer().getOrThrow()
            assertTrue(syncBodies.last().contains("https://arxiv.org/abs/2603.11842"))
            assertFalse(managedRepo.snapshot.first().entries.any { it.id == "item-1" })
            assertEquals(false, database.dao().getTombstone("item", item1NatKey)?.isLocalDirty)

            // Delete remote feed purges feed AND its remaining local items (item-fresh)
            assertEquals("arXiv cs.LG", managedRepo.deleteFeed("feed-arxiv-cs-lg").getOrThrow())
            assertFalse(managedRepo.snapshot.first().feeds.any { it.id == "feed-arxiv-cs-lg" })
            assertFalse(managedRepo.snapshot.first().entries.any { it.id == "item-fresh" })
        }

    @Test
    internal fun `remote sync leaves contentText null when server sends summary and long content_html`() =
        runTest {
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val longHtml =
                "<article><p>" + List(2_200) { "essayword$it" }.joinToString(" ") + "</p></article>"
            val syncedRepo =
                RocheRepository(
                    database = database,
                    apiClient = buildLongHtmlSyncMockClient(longHtml),
                    settings = settings,
                )
            syncedRepo.syncWithServer().getOrThrow()

            assertNull(database.dao().getItemById("item-long-essay")?.contentText)
            val entry = syncedRepo.snapshot.first().entries.first { it.id == "item-long-essay" }
            assertNull(entry.contentText)
            assertEquals("12m", entry.readEstimateLabel)
        }

    @Test
    internal fun `local LAN feed items tags and overflow items survive syncWithServer intact`() =
        runTest {
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            seedLanFeedAndOverflowDataset(database)
            val peerSyncBodies = mutableListOf<String>()
            val syncedRepo =
                RocheRepository(
                    database = database,
                    apiClient = buildLanAndOverflowPeerSyncMockClient(peerSyncBodies),
                    settings = settings,
                )

            syncedRepo.syncWithServer().getOrThrow()
            assertEquals(1, peerSyncBodies.size)
            assertFalse(peerSyncBodies.first().contains("192.168.1.50"))
            assertFalse(peerSyncBodies.first().contains("item-pub-2002"))

            val pass1Snapshot = syncedRepo.snapshot.first()
            assertTrue(pass1Snapshot.feeds.any { it.url == "http://192.168.1.50/rss.xml" })
            val lanEntry = pass1Snapshot.entries.first { it.id == "lan-item-1" }
            assertEquals(listOf("homelab"), lanEntry.tags)
            assertTrue(pass1Snapshot.entries.any { it.id == "item-pub-2002" && it.isFavorite })
            assertEquals(true, database.dao().getEntryById("item-pub-2002")?.isLocalDirty)

            // Second pass (delta sync) sends the previously overflowed entry and still keeps LAN
            // data
            syncedRepo.syncWithServer().getOrThrow()
            assertEquals(2, peerSyncBodies.size)
            assertTrue(peerSyncBodies[1].contains("item-pub-2002"))
            assertEquals(false, database.dao().getEntryById("item-pub-2002")?.isLocalDirty)
            assertTrue(
                syncedRepo.snapshot.first().feeds.any { it.url == "http://192.168.1.50/rss.xml" }
            )
            assertTrue(syncedRepo.snapshot.first().entries.any { it.id == "lan-item-1" })
        }

    @Test
    internal fun `renameFeed updates server online and preserves pending rename across offline sync`() =
        runTest {
            seedTestDatabase()
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            var syncAvailable = false
            val syncBodies = mutableListOf<String>()
            val renameRepo =
                RocheRepository(
                    database = database,
                    apiClient =
                        buildRenameFeedSyncMockClient(
                            isSyncAvailable = { syncAvailable },
                            syncBodies = syncBodies,
                        ),
                    settings = settings,
                    networkTierProvider = { NetworkTier.OFFLINE },
                )

            renameRepo.renameFeed("feed-arxiv-cs-lg", "  Custom ML Papers  ")
            assertTrue(database.dao().getFeedSourceById("feed-arxiv-cs-lg")?.isLocalDirty == true)
            assertEquals(
                "Custom ML Papers",
                database.dao().getFeedById("feed-arxiv-cs-lg")?.title,
            )

            // Sync while server returns 503 fails cleanly; stale server HLC must NOT clobber rename
            assertTrue(renameRepo.syncWithServer().isFailure)
            val afterOfflineSync = renameRepo.snapshot.first()
            assertEquals(
                "Custom ML Papers",
                afterOfflineSync.feeds.first { it.id == "feed-arxiv-cs-lg" }.title,
            )
            assertEquals(
                "Custom ML Papers",
                afterOfflineSync.entries.first { it.id == "item-1" }.feedTitle,
            )

            // Server recovers: pending rename pushes via /api/v1/sync/peer, wins HLC over stale
            // server title, and clears dirty flag
            syncAvailable = true
            renameRepo.syncWithServer().getOrThrow()
            assertTrue(syncBodies.single().contains("Custom ML Papers"))
            assertEquals(
                false,
                database.dao().getFeedSourceById("feed-arxiv-cs-lg")?.isLocalDirty,
            )
            assertEquals(
                "Custom ML Papers",
                renameRepo.snapshot.first().feeds.first { it.id == "feed-arxiv-cs-lg" }.title,
            )

            renameRepo.renameFeed("feed-arxiv-cs-lg", "Online Direct Title")
            renameRepo.syncWithServer().getOrThrow()
            assertTrue(syncBodies.last().contains("Online Direct Title"))
            assertEquals(
                false,
                database.dao().getFeedSourceById("feed-arxiv-cs-lg")?.isLocalDirty,
            )
        }

    @Test
    internal fun `toggleOfflineCache leaves isCachedOffline false when audio download fails`() =
        runTest {
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            database
                .dao()
                .upsertItem(
                    InboxItemRow(
                        id = "item-audio-broken",
                        feedId = null,
                        feedTitle = "Podcast",
                        title = "Broken Episode",
                        url = "https://podcasts.example.com/ep-1",
                        author = null,
                        summary = "Episode summary",
                        contentText = null,
                        contentHtml = null,
                        contentType = "podcast",
                        status = "unread",
                        isFavorite = false,
                        isCachedOffline = false,
                        publishedAt = "2026-04-01T10:00:00Z",
                        createdAt = "2026-04-01T10:00:00Z",
                        readProgress = 0.0,
                        thumbnailMediaId = null,
                        duration = "12:00",
                        audioUrl = "https://cdn.example.com/broken.mp3",
                    )
                )
            val cacheDir = File(tempDir, "broken_audio_cache")
            val brokenRepo =
                RocheRepository(
                    database = database,
                    apiClient = buildFailingAudioCacheMockClient(),
                    settings = settings,
                    cacheDir = cacheDir,
                )

            assertFalse(brokenRepo.toggleOfflineCache("item-audio-broken"))
            val entry = brokenRepo.snapshot.first().entries.first { it.id == "item-audio-broken" }
            assertFalse(entry.isCachedOffline)
            assertFalse(File(File(cacheDir, "items"), "item-audio-broken").exists())
        }

    @Test
    internal fun `suspend operations propagate CancellationException without recording bogus mutations`() =
        runTest {
            seedTestDatabase()
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val cancellingRepo =
                RocheRepository(
                    database = database,
                    apiClient = RocheApiClient { throw CancellationException("cancelled") },
                    settings = settings,
                    cacheDir = File(tempDir, "cancel_cache"),
                )

            assertThrows(CancellationException::class.java) {
                runBlocking { cancellingRepo.syncWithServer() }
            }
            assertThrows(CancellationException::class.java) {
                runBlocking { cancellingRepo.refreshFeed("feed-arxiv-cs-lg") }
            }
            assertThrows(CancellationException::class.java) {
                runBlocking { cancellingRepo.toggleOfflineCache("item-5") }
            }
            assertEquals(false, database.dao().getItemStateById("item-5")?.isCachedOffline)
        }

    @Test
    internal fun `reconcilePeerSyncResponse applies targeted HLC updates tombstones and truncates oversized summaries`() =
        runTest {
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-live")
            }
            var syncRound = 1
            val diffRepo =
                RocheRepository(
                    database = database,
                    apiClient = buildTargetedDiffSyncMockClient { syncRound },
                    settings = settings,
                    networkTierProvider = { NetworkTier.OFFLINE },
                )

            // Round 1: initial snapshot with i-keep (oversized summary), i-edit, i-drop, 2 tags
            assertEquals(3, diffRepo.syncWithServer().getOrThrow())
            assertEquals(
                setOf("keep", "drop"),
                diffRepo.snapshot.first().tags.map { it.name }.toSet(),
            )
            assertEquals(
                MAX_SQLITE_SUMMARY_CHARS,
                database.dao().getEntryById("i-keep")?.summary?.length,
            )

            // Round 2: 0-change delta sync preserves all rows identically
            syncRound = 2
            diffRepo.syncWithServer().getOrThrow()
            assertEquals(3, diffRepo.snapshot.first().entries.size)

            // Round 3: insert i-new, update i-edit, tombstone i-drop and drop tag
            syncRound = 3
            assertEquals(3, diffRepo.syncWithServer().getOrThrow())
            val afterDiff = diffRepo.snapshot.first()
            val byId = afterDiff.entries.associateBy { it.id }
            assertEquals(setOf("i-keep", "i-edit", "i-new"), byId.keys)
            assertEquals("Edited Title", byId.getValue("i-edit").title)
            assertTrue(byId.getValue("i-edit").isFavorite)
            assertEquals(listOf("keep"), byId.getValue("i-new").tags)
            assertEquals(listOf("keep"), afterDiff.tags.map { it.name })
        }

    @Test
    internal fun `syncWithServer follows has_more delta continuation pages and advances cursor`() =
        runTest {
            val feedId = deterministicFeedId("https://blog.example.com/rss.xml")
            seedPaginatedDeltaInitialState(database.dao(), feedId)
            settings.update {
                it.copy(
                    serverUrl = "https://100.64.0.12:8080",
                    bearerToken = "tok-paginated",
                    lastPeerSyncCursor = "10",
                )
            }
            val peerSyncBodies = mutableListOf<String>()
            val paginatedRepo =
                RocheRepository(
                    database = database,
                    apiClient = buildPaginatedDeltaSyncMockClient(feedId, peerSyncBodies),
                    settings = settings,
                )

            val totalSynced = paginatedRepo.syncWithServer().getOrThrow()

            assertEquals(EXPECTED_PAGINATED_ITEM_COUNT, totalSynced)
            assertEquals(EXPECTED_CONTINUATION_REQUESTS, peerSyncBodies.size)
            assertTrue(peerSyncBodies[0].contains(""""since_seq":10"""))
            assertTrue(peerSyncBodies[1].contains(""""since_seq":11"""))
            assertFalse(peerSyncBodies[1].contains(""""entries":"""))
            assertTrue(peerSyncBodies[2].contains(""""since_seq":12"""))
            assertFalse(peerSyncBodies[2].contains(""""entries":"""))
            assertEquals(
                "15",
                settings.settings.first().lastPeerSyncCursor,
            )
            assertEquals(
                setOf("item-page-0", "item-page-1", "item-page-2", "item-page-3"),
                paginatedRepo.snapshot.first().entries.map { it.id }.toSet(),
            )
        }

    @Test
    internal fun `toggleStar with backgroundScope updates Room immediately while HTTP push blocks`() =
        runTest {
            seedTestDatabase()
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-bg")
            }
            val syncStarted = CountDownLatch(1)
            val allowSyncFinish = CountDownLatch(1)
            val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val slowApiClient = RocheApiClient { request ->
                    if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
                        syncStarted.countDown()
                        allowSyncFinish.await(5, TimeUnit.SECONDS)
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"server_peer_id":"server","sync_seq":1,"server_hlc":"001780000001000-0000-server","sync_timestamp":"2026-04-01T10:00:00Z","active_peers":[],"entries":[],"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
                        )
                    } else {
                        HttpResponse(statusCode = 200, body = "{}")
                    }
                }
                val asyncRepo =
                    RocheRepository(
                        database = database,
                        apiClient = slowApiClient,
                        settings = settings,
                        networkTierProvider = { NetworkTier.WIFI },
                        backgroundScope = bgScope,
                    )

                // item-2 starts unstarred; toggleStar returns immediately before peer sync finishes
                assertTrue(asyncRepo.toggleStar("item-2"))
                assertEquals(true, database.dao().getItemStateById("item-2")?.isFavorite)
                assertEquals(true, database.dao().getEntryStateById("item-2")?.isLocalDirty)
                assertTrue(syncStarted.await(5, TimeUnit.SECONDS))

                // Release slow peer sync and deterministically join background jobs
                allowSyncFinish.countDown()
                bgScope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
                assertEquals(false, database.dao().getEntryStateById("item-2")?.isLocalDirty)
            } finally {
                bgScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    internal fun `item_contents split stores compact list rows and searchDeepEntries finds deep body text`() =
        runTest {
            val deepBody =
                "Intro paragraph. " +
                    "x".repeat(12_000) +
                    " NeedleInHaystackToken99 appears deep in the article body."
            val deepHtml = "<p>$deepBody</p>"
            database
                .dao()
                .upsertItem(
                    SeedData.items
                        .first()
                        .copy(
                            id = "item-deep",
                            title = "Compact Row Storage Test",
                            summary = "Short summary",
                            contentText = deepBody,
                            contentHtml = deepHtml,
                        )
                )

            val listSnapshot = repository.prewarm()
            val listEntry = listSnapshot.entries.single { it.id == "item-deep" }
            assertEquals(LIST_PREVIEW_TEXT_CHARS, listEntry.contentText?.length)
            assertEquals(LIST_PREVIEW_HTML_CHARS, listEntry.contentHtml?.length)

            val fullEntity = database.dao().getItemById("item-deep")
            assertEquals(deepBody.length, fullEntity?.contentText?.length)
            assertEquals(deepHtml.length, fullEntity?.contentHtml?.length)

            val deepMatches = repository.searchDeepEntries("NeedleInHaystackToken99")
            assertEquals(1, deepMatches.size)
            assertEquals("item-deep", deepMatches.single().id)
            val snippet = extractSearchMatchSnippet(deepMatches.single(), "NeedleInHaystackToken99")
            assertTrue(snippet?.contains("NeedleInHaystackToken99") == true)

            assertEquals(
                """{title creatorsJson url summary contentText contentHtml fallbackFeedTitle} : ("hello"* "world"* "v2"*)""",
                buildFts5MatchQuery("  \"hello\" -world: (v2)* "),
            )
            assertNull(buildFts5MatchQuery("--- () *** \"\""))
            assertTrue(repository.searchDeepEntries("--- () ***").isEmpty())

            database.dao().upsertFeeds(SeedData.feeds)
            repository.renameFeed("feed-arxiv-cs-lg", "RenamedCustomFeed77")
            assertEquals(
                listOf("item-deep"),
                repository.searchDeepEntries("RenamedCustomFeed77").map { it.id },
            )
        }

    @Test
    internal fun `shrinking item content cleans item_contents row and recalculates article duration`() =
        runTest {
            val dao = database.dao()
            val longText = List(1_500) { "word$it" }.joinToString(" ")
            dao.upsertItem(
                SeedData.items
                    .first()
                    .copy(
                        id = "item-shrink",
                        contentType = "article",
                        summary = "Summary",
                        contentText = longText,
                        contentHtml = "<p>$longText</p>",
                        duration = null,
                    )
            )
            val storedLong = dao.getItemById("item-shrink")!!
            assertTrue(storedLong.duration?.endsWith("m") == true)
            val storedLongLength = storedLong.contentText?.length ?: 0
            assertTrue(storedLongLength > LIST_PREVIEW_TEXT_CHARS)
            val longEntry = repository.prewarm().entries.single { it.id == "item-shrink" }
            assertEquals("8m", longEntry.readEstimateLabel)

            // Shrink to short inline content: stale item_contents row and synthetic duration clear
            dao.upsertItem(
                storedLong.copy(
                    summary = "Short summary",
                    contentText = "Short body",
                    contentHtml = "<p>Short body</p>",
                )
            )
            val storedShort = dao.getItemById("item-shrink")!!
            assertEquals("Short body", storedShort.contentText)
            assertNull(storedShort.duration)
            assertTrue(repository.searchDeepEntries("word1450").isEmpty())
            assertEquals(
                listOf("item-shrink"),
                repository.searchDeepEntries("Short body").map { it.id },
            )
            val entry = repository.prewarm().entries.single { it.id == "item-shrink" }
            assertEquals("1m", entry.readEstimateLabel)
        }

    @Test
    internal fun `syncWithServer rejects backwards sync_seq cursor regression`() = runTest {
        settings.update {
            it.copy(
                serverUrl = "https://100.64.0.12:8080",
                bearerToken = "tok-skew",
                lastPeerSyncCursor = "50",
            )
        }
        val skewedClient = RocheApiClient { request ->
            if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
                HttpResponse(
                    statusCode = 200,
                    body =
                        """{"server_peer_id":"server","sync_seq":40,"server_hlc":"001780000001000-0000-server","sync_timestamp":"2026-06-09T00:00:00Z","active_peers":[],"entries":[],"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
                )
            } else {
                HttpResponse(statusCode = 404, body = "{}")
            }
        }
        val skewedRepo =
            RocheRepository(database = database, apiClient = skewedClient, settings = settings)
        skewedRepo.syncWithServer().getOrThrow()

        assertEquals("50", settings.settings.first().lastPeerSyncCursor)
    }

    @Test
    internal fun `catalog mutation with backgroundScope triggers coalesced syncWithServer`() =
        runTest {
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-cat-bg")
            }
            val syncedLatch = CountDownLatch(1)
            val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val catSyncClient = RocheApiClient { request ->
                    if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
                        if (request.body.orEmpty().contains("Neuromancer")) {
                            syncedLatch.countDown()
                        }
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"server_peer_id":"server","sync_seq":1,"server_hlc":"001780000001000-0000-server","sync_timestamp":"2026-07-01T00:00:00Z","active_peers":[],"entries":[],"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
                        )
                    } else {
                        HttpResponse(statusCode = 404, body = "{}")
                    }
                }
                val bgRepo =
                    RocheRepository(
                        database = database,
                        apiClient = catSyncClient,
                        settings = settings,
                        backgroundScope = bgScope,
                    )
                bgRepo.catalog.logEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.BOOK,
                        title = "Neuromancer",
                        creators = listOf("William Gibson"),
                        releaseYear = 1984,
                    ),
                    ConsumptionStatus.CONSUMED,
                    null,
                )
                assertTrue(syncedLatch.await(5, TimeUnit.SECONDS))
            } finally {
                bgScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    internal fun `searchDeepEntries strips NUL bytes caps FTS tokens excludes itemId UUIDs and deduplicates upsertItems`() =
        runTest {
            val dao = database.dao()
            val uuidId = "550e8400-e29b-41d4-a716-446655440000"
            val baseItem =
                SeedData.items
                    .first()
                    .copy(
                        id = uuidId,
                        title = "Morning Coffee",
                        url = "https://example.com/coffee",
                        summary = "DistinctSummaryToken",
                        contentText = "Body text",
                        contentHtml = "<p>Body text</p>",
                    )
            dao.upsertItem(baseItem)
            dao.upsertTag(TagRow(id = "tag-sys", name = "systems"))
            dao.insertItemTag(ItemTagRow(itemId = uuidId, tagId = "tag-sys"))

            assertNull(buildFts5MatchQuery("\u0000"))
            assertNull(buildFts5MatchQuery("  \u0000  "))
            assertEquals("%%", buildSqlContainsPattern("\u0000"))
            assertTrue(repository.searchDeepEntries("\u0000").isEmpty())
            assertTrue(repository.searchDeepEntries("#\u0000").isEmpty())
            assertTrue(repository.searchDeepEntries("550e8400").isEmpty())
            assertTrue(repository.searchDeepEntries("Morning 550e8400").isEmpty())

            val twentyTokens = (1..TEST_OVERFLOW_TOKEN_COUNT).joinToString(" ") { "tok$it" }
            val cappedQuery = buildFts5MatchQuery(twentyTokens).orEmpty()
            assertTrue(cappedQuery.contains("\"tok16\"*"))
            assertFalse(cappedQuery.contains("tok17"))

            val dupFirst = baseItem.copy(id = "dup-1", title = "StaleBatchToken71")
            val dupSecond = baseItem.copy(id = "dup-1", title = "LatestBatchToken72")
            dao.upsertItems(listOf(dupFirst, dupSecond))
            assertTrue(repository.searchDeepEntries("StaleBatchToken71").isEmpty())
            assertEquals(
                listOf("dup-1"),
                repository.searchDeepEntries("LatestBatchToken72").map { it.id },
            )
        }

    @Test
    internal fun `restoring archived local feed with titleOverride synchronizes items feedTitle and FTS`() =
        runTest {
            val dao = database.dao()
            val feedUrl = "https://archived.example.com/rss.xml"
            val feedId = deterministicFeedId(feedUrl)
            dao.upsertFeed(
                FeedRow(
                    id = feedId,
                    title = "OldArchivedTitle33",
                    url = feedUrl,
                    siteUrl = null,
                    feedType = "rss",
                    isActive = false,
                )
            )
            dao.upsertItem(
                SeedData.items
                    .first()
                    .copy(
                        id = "item-archived-feed",
                        feedId = feedId,
                        feedUrl = feedUrl,
                        feedTitle = "OldArchivedTitle33",
                    )
            )

            repository.saveUrlOrFeed(
                inputUrl = feedUrl,
                titleOverride = "RestoredFeedTitle44",
                tagCsv = "",
            )

            assertEquals("RestoredFeedTitle44", dao.getItemById("item-archived-feed")?.feedTitle)
            assertTrue(repository.searchDeepEntries("OldArchivedTitle33").isEmpty())
            assertEquals(
                listOf("item-archived-feed"),
                repository.searchDeepEntries("RestoredFeedTitle44").map { it.id },
            )
        }

    @Test
    internal fun `VPN tunnel fallback active network tier and periodic job upgrade handle split-tunnel Tailscale`() {
        val cgnat = InetAddress.getByName("100.64.0.9")
        val tsUla = InetAddress.getByName("fd7a:115c:a1e0::1")
        val lanIp = InetAddress.getByName("192.168.1.20")
        val loopback = InetAddress.getByName("127.0.0.1")

        assertTrue(isActiveVpnTunnelInterface("tun0", true, false, listOf(lanIp)))
        assertTrue(isActiveVpnTunnelInterface("wg0", true, false, listOf(lanIp)))
        assertTrue(isActiveVpnTunnelInterface("utun3", true, false, listOf(lanIp)))
        assertTrue(isActiveVpnTunnelInterface("wlan0", true, false, listOf(cgnat)))
        assertTrue(isActiveVpnTunnelInterface("eth0", true, false, listOf(tsUla)))
        assertFalse(isActiveVpnTunnelInterface("wlan0", true, false, listOf(lanIp)))
        assertFalse(isActiveVpnTunnelInterface("tun0", false, false, listOf(cgnat)))
        assertFalse(isActiveVpnTunnelInterface("tun0", true, false, listOf(loopback)))

        assertEquals(
            NetworkTier.WIFI,
            resolveActiveNetworkTier(false, true, false, false, true, true),
        )
        assertEquals(
            NetworkTier.WIRED,
            resolveActiveNetworkTier(true, false, true, false, false, true),
        )
        assertEquals(
            NetworkTier.CELLULAR,
            resolveActiveNetworkTier(true, true, false, false, false, false),
        )
        assertEquals(
            NetworkTier.OFFLINE,
            resolveActiveNetworkTier(false, false, false, false, false, false),
        )

        assertFalse(
            PeerSyncScheduler.shouldKeepExistingPeriodicJob(
                isPersisted = true,
                intervalMillis = PeerSyncScheduler.PERIODIC_SYNC_INTERVAL_MS,
                legacyRequiresVpnExtra = true,
                networkAnyConfiguredExtra = false,
            )
        )
        assertTrue(
            PeerSyncScheduler.shouldKeepExistingPeriodicJob(
                isPersisted = true,
                intervalMillis = PeerSyncScheduler.PERIODIC_SYNC_INTERVAL_MS,
                legacyRequiresVpnExtra = false,
                networkAnyConfiguredExtra = true,
            )
        )
    }
}

private const val TEST_OVERFLOW_TOKEN_COUNT = 20

private const val EXPECTED_CONTINUATION_REQUESTS = 3
private const val EXPECTED_PAGINATED_ITEM_COUNT = 4

private suspend fun seedPaginatedDeltaInitialState(dao: RocheDao, feedId: String) {
    dao.upsertFeed(
        FeedRow(
            id = feedId,
            title = "Systems Feed",
            url = "https://blog.example.com/rss.xml",
            siteUrl = "https://blog.example.com",
            feedType = "rss",
            lastPolled = "2099-01-01T00:00:00Z",
        )
    )
    dao.upsertItem(
        InboxItemRow(
            id = "item-page-0",
            feedId = feedId,
            feedUrl = "https://blog.example.com/rss.xml",
            feedTitle = "Systems Feed",
            title = "Existing Post 0",
            url = "https://blog.example.com/post-0",
            author = null,
            summary = "Existing summary",
            contentText = null,
            contentHtml = null,
            contentType = "article",
            status = "unread",
            isFavorite = false,
            isCachedOffline = false,
            publishedAt = "2026-05-31T12:00:00Z",
            createdAt = "2026-05-31T12:00:00Z",
            readProgress = 0.0,
            thumbnailMediaId = null,
            duration = null,
        )
    )
}

private data class DeltaPageFixture(
    val itemId: String,
    val nextSeq: Long,
    val hasMore: Boolean,
    val syncTs: String,
)

private fun buildPaginatedDeltaSyncMockClient(
    feedId: String,
    peerSyncBodies: MutableList<String>,
): RocheApiClient = RocheApiClient { request ->
    if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
        val body = request.body.orEmpty()
        peerSyncBodies.add(body)
        val page =
            when {
                body.contains(""""since_seq":10""") ->
                    DeltaPageFixture("item-page-1", 11L, true, "2026-06-01T01:00:00Z")
                body.contains(""""since_seq":11""") ->
                    DeltaPageFixture("item-page-2", 12L, true, "2026-06-01T02:00:00Z")
                else -> DeltaPageFixture("item-page-3", 15L, false, "2026-06-01T03:00:00Z")
            }
        val hlc = "0017800000${page.nextSeq}000-0000-server"
        HttpResponse(
            statusCode = 200,
            body =
                """{"server_peer_id":"server","sync_seq":${page.nextSeq},"server_hlc":"$hlc","sync_timestamp":"${page.syncTs}","has_more":${page.hasMore},""" +
                    """"active_peers":[{"peer_id":"server","device_name":"Server","network_tier":"wired","weight":1.0,"last_seen_at":"${page.syncTs}"}],""" +
                    """"entries":[{"id":"$feedId","kind":"feed","title":"Systems Feed","url":"https://blog.example.com","hlc":"$hlc","feed_source":{"feed_url":"https://blog.example.com/rss.xml","source_type":"rss","feed_title":"Systems Feed","poll_interval":30,"last_polled_at":"2099-01-01T00:00:00Z","is_active":true,"hlc":"$hlc"}},""" +
                    """{"id":"${page.itemId}","kind":"article","title":"Post ${page.itemId}","url":"https://blog.example.com/${page.itemId}","created_at":"${page.syncTs}","hlc":"$hlc","content":{"content_type":"article"},"state":{"inbox_state":"unread","hlc":"$hlc"}}],""" +
                    """"collection_entries":[{"collection_id":"$feedId","entry_id":"${page.itemId}","added_at":"${page.syncTs}","hlc":"$hlc"}],""" +
                    """"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
        )
    } else {
        HttpResponse(statusCode = 404, body = "{}")
    }
}

internal fun buildFeedAndItemManagementMockClient(
    requests: MutableList<String>,
    syncBodies: MutableList<String>,
): RocheApiClient = RocheApiClient { request ->
    requests.add("${request.method} ${request.url}")
    when {
        request.method == "POST" &&
            request.url.endsWith("/api/v1/feeds/feed-arxiv-cs-lg/refresh") -> {
            HttpResponse(
                statusCode = 200,
                body =
                    """{"id":"feed-arxiv-cs-lg","title":"arXiv cs.LG","url":"https://export.arxiv.org/api/query?search_query=cat:cs.LG","feed_type":"arxiv","unread_count":2}""",
            )
        }
        request.method == "POST" && request.url.endsWith("/api/v1/sync/peer") -> {
            val body = request.body.orEmpty()
            syncBodies.add(body)
            val deletedItem1 =
                body.contains("https://arxiv.org/abs/2603.11842") &&
                    body.contains(""""tombstones":[{"entity_type":"item"""")
            val hlc = "001780000001000-0000-server"
            val entriesJson =
                if (deletedItem1) {
                    """[{"id":"item-fresh","kind":"paper","title":"Fresh Polled Paper","url":"https://arxiv.org/abs/2604.00001","created_at":"2026-04-01T10:00:00Z","hlc":"$hlc","content":{"content_type":"paper"},"state":{"inbox_state":"unread","hlc":"$hlc"}}]"""
                } else {
                    """[{"id":"item-1","kind":"paper","title":"Speculative Decoding","url":"https://arxiv.org/abs/2603.11842","created_at":"2026-03-29T10:00:00Z","hlc":"000000000001000-0000-server","content":{"content_type":"paper"},"state":{"inbox_state":"unread","hlc":"000000000001000-0000-server"}},{"id":"item-fresh","kind":"paper","title":"Fresh Polled Paper","url":"https://arxiv.org/abs/2604.00001","created_at":"2026-04-01T10:00:00Z","hlc":"$hlc","content":{"content_type":"paper"},"state":{"inbox_state":"unread","hlc":"$hlc"}}]"""
                }
            val membershipsJson =
                if (deletedItem1) {
                    """[{"collection_id":"feed-arxiv-cs-lg","entry_id":"item-fresh","added_at":"2026-04-01T10:00:00Z","hlc":"$hlc"}]"""
                } else {
                    """[{"collection_id":"feed-arxiv-cs-lg","entry_id":"item-1","added_at":"2026-03-29T10:00:00Z","hlc":"000000000001000-0000-server"},{"collection_id":"feed-arxiv-cs-lg","entry_id":"item-fresh","added_at":"2026-04-01T10:00:00Z","hlc":"$hlc"}]"""
                }
            HttpResponse(
                statusCode = 200,
                body =
                    """{"server_peer_id":"server","sync_seq":1,"server_hlc":"$hlc","sync_timestamp":"2026-04-01T10:00:00Z","active_peers":[],"entries":$entriesJson,"collection_entries":$membershipsJson,"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
            )
        }
        request.method == "DELETE" && request.url.endsWith("/api/v1/feeds/feed-arxiv-cs-lg") -> {
            HttpResponse(
                statusCode = 404,
                body = """{"title":"Not Found","detail":"missing"}""",
            )
        }
        else -> {
            HttpResponse(statusCode = 404, body = "{}")
        }
    }
}

internal fun buildOfflineCacheMockClient(
    pdfBytes: ByteArray,
    thumbBytes: ByteArray,
): RocheApiClient = RocheApiClient { request ->
    when (request.url) {
        "https://100.64.0.12:8080/api/v1/items/item-5" ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"id":"item-5","title":"Deterministic Simulation Testing","url":"https://arxiv.org/abs/2603.09910","content_html":"<p>Hydrated full paper HTML</p>","content_type":"paper","status":"unread","is_favorite":false,"created_at":"2026-03-27T10:00:00Z","thumbnail_media_id":"thumb-item-5"}""",
            )
        "https://100.64.0.12:8080/api/v1/items/item-5/media" ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"media":[{"id":"pdf-item-5","item_id":"item-5","media_type":"pdf","file_size":13},{"id":"thumb-item-5","item_id":"item-5","media_type":"thumbnail","file_size":4}]}""",
            )
        "https://100.64.0.12:8080/api/v1/media/pdf-item-5" ->
            HttpResponse(statusCode = 200, body = "", bodyBytes = pdfBytes)
        "https://100.64.0.12:8080/api/v1/media/thumb-item-5" ->
            HttpResponse(statusCode = 200, body = "", bodyBytes = thumbBytes)
        "https://100.64.0.12:8080/api/v1/sync/peer" ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"server_peer_id":"server","sync_seq":1,"server_hlc":"001780000001000-0000-server","sync_timestamp":"2026-06-01T12:00:00Z","active_peers":[],"entries":[{"id":"item-5","kind":"paper","title":"Deterministic Simulation Testing","url":"https://arxiv.org/abs/2603.09910","created_at":"2026-03-27T10:00:00Z","hlc":"001780000001000-0000-server","content":{"content_type":"paper"},"state":{"inbox_state":"unread","hlc":"001780000001000-0000-server"}}],"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
            )
        else -> HttpResponse(statusCode = 404, body = "{}")
    }
}

internal fun assertTwoWaySyncPushedRequests(pushedBodies: List<String>) {
    val body = pushedBodies.single()
    assertTrue(body.contains("https://example.com/offline-note"))
    assertTrue(body.contains(""""inbox_state":"dismissed""""))
    assertTrue(body.contains(""""progress":0.9"""))
    assertTrue(body.contains(""""title":"rust""""))
    assertTrue(body.contains(""""title":"crdt""""))
    assertTrue(body.contains(""""natural_key":"$STARRED_COLLECTION_ID\u0000item-1""""))
    assertTrue(body.contains(""""natural_key":"tag-systems\u0000item-1""""))
}

internal fun buildTwoWaySyncMockClient(pushedBodies: MutableList<String>): RocheApiClient =
    RocheApiClient { request ->
        if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
            pushedBodies.add(request.body.orEmpty())
            val staleHlc = "000000000001000-0000-server"
            HttpResponse(
                statusCode = 200,
                body =
                    """{"server_peer_id":"server","sync_seq":1,"server_hlc":"001780000001000-0000-server","sync_timestamp":"2026-06-01T12:00:00Z","active_peers":[],""" +
                        """"entries":[{"id":"item-1","kind":"paper","title":"Speculative Decoding","url":"https://arxiv.org/abs/2603.11842","created_at":"2026-03-29T10:00:00Z","hlc":"$staleHlc","content":{"content_type":"paper"},"state":{"inbox_state":"unread","progress":0.35,"hlc":"$staleHlc"}}],""" +
                        """"collection_entries":[{"collection_id":"$STARRED_COLLECTION_ID","entry_id":"item-1","added_at":"2026-03-29T10:00:00Z","hlc":"$staleHlc"},{"collection_id":"tag-systems","entry_id":"item-1","added_at":"2026-03-29T10:00:00Z","hlc":"$staleHlc"}],""" +
                        """"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
            )
        } else {
            HttpResponse(statusCode = 404, body = "{}")
        }
    }

internal suspend fun assertReadTimestampSync(
    liveRepo: RocheRepository,
    syncBodies: List<String>,
) {
    liveRepo.setReadState(itemId = "item-1", isRead = true)
    val readItem = liveRepo.snapshot.first().entries.first { it.id == "item-1" }
    assertTrue(readItem.isRead)
    assertTrue(!readItem.readAt.isNullOrBlank())
    liveRepo.syncWithServer().getOrThrow()
    assertTrue(syncBodies.last().contains(""""read_at":"${readItem.readAt}""""))

    assertFalse(liveRepo.toggleReadState(itemId = "item-1"))
    val unreadItem = liveRepo.snapshot.first().entries.first { it.id == "item-1" }
    assertFalse(unreadItem.isRead)
    assertEquals(null, unreadItem.readAt)
    liveRepo.syncWithServer().getOrThrow()
    assertFalse(syncBodies.last().contains(""""read_at":"${readItem.readAt}""""))
}

private fun buildLongHtmlSyncMockClient(longHtml: String): RocheApiClient =
    RocheApiClient { request ->
        if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
            val hlc = "001780000001000-0000-server"
            HttpResponse(
                statusCode = 200,
                body =
                    """{"server_peer_id":"server","sync_seq":1,"server_hlc":"$hlc","sync_timestamp":"2026-04-01T10:00:00Z","active_peers":[],"entries":[{"id":"item-long-essay","kind":"article","title":"Ten Thousand Words","url":"https://example.com/long-essay","summary":"Short 320-char excerpt.","created_at":"2026-04-01T10:00:00Z","hlc":"$hlc","content":{"content_type":"article","content_html":"$longHtml"},"state":{"inbox_state":"unread","hlc":"$hlc"}}],"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
            )
        } else {
            HttpResponse(statusCode = 404, body = "{}")
        }
    }

private suspend fun seedLanFeedAndOverflowDataset(database: RocheDatabase) {
    val dao = database.dao()
    val lanFeedId = deterministicFeedId("http://192.168.1.50/rss.xml")
    val pubFeedId = deterministicFeedId("https://blog.example.com/rss.xml")
    dao.upsertFeeds(
        listOf(
            FeedRow(
                id = lanFeedId,
                title = "Homelab LAN RSS",
                url = "http://192.168.1.50/rss.xml",
                siteUrl = "http://192.168.1.50",
                feedType = "rss",
                lastPolled = "2099-01-01T00:00:00Z",
            ),
            FeedRow(
                id = pubFeedId,
                title = "Public Blog",
                url = "https://blog.example.com/rss.xml",
                siteUrl = "https://blog.example.com",
                feedType = "rss",
                lastPolled = "2099-01-01T00:00:00Z",
            ),
        )
    )
    dao.upsertItem(
        InboxItemRow(
            id = "lan-item-1",
            feedId = lanFeedId,
            feedUrl = "http://192.168.1.50/rss.xml",
            feedTitle = "Homelab LAN RSS",
            title = "NAS Status Alert",
            url = "http://192.168.1.50/post-1",
            author = null,
            summary = "All disks healthy",
            contentText = null,
            contentHtml = "<p>All disks healthy</p>",
            contentType = "article",
            status = "unread",
            isFavorite = false,
            isCachedOffline = false,
            publishedAt = "2026-05-01T10:00:00Z",
            createdAt = "2026-05-01T10:00:00Z",
            readProgress = 0.0,
            thumbnailMediaId = null,
            duration = null,
        )
    )
    attachTagsLocally(dao = dao, itemId = "lan-item-1", tagNames = listOf("homelab"))
    seedOverflowPublicItems(dao = dao, pubFeedId = pubFeedId)
}

private suspend fun seedOverflowPublicItems(
    dao: RocheDao,
    pubFeedId: String,
) {
    val bulkItems =
        (1..2_002).map { idx ->
            val padded = idx.toString().padStart(4, '0')
            val isOverflowTarget = idx == 2_002
            val ts = if (isOverflowTarget) "2025-01-01T00:00:00Z" else "2026-05-02T12:00:00Z"
            InboxItemRow(
                id = "item-pub-$padded",
                feedId = pubFeedId,
                feedUrl = "https://blog.example.com/rss.xml",
                feedTitle = "Public Blog",
                title = "Public Post $padded",
                url = "https://blog.example.com/post-$padded",
                author = null,
                summary = "Summary $padded",
                contentText = null,
                contentHtml = null,
                contentType = "article",
                status = "unread",
                isFavorite = isOverflowTarget,
                isCachedOffline = false,
                publishedAt = ts,
                createdAt = ts,
                interactionUpdatedAt = ts,
                readProgress = 0.0,
                thumbnailMediaId = null,
                duration = null,
            )
        }
    dao.upsertItems(bulkItems)
}

private fun buildLanAndOverflowPeerSyncMockClient(
    peerSyncBodies: MutableList<String>
): RocheApiClient = RocheApiClient { request ->
    if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
        val body = request.body.orEmpty()
        peerSyncBodies.add(body)
        val pubFeedId = deterministicFeedId("https://blog.example.com/rss.xml")
        val isDelta = body.contains(""""since_seq":""")
        val seq = if (isDelta) 2 else 1
        val hlc = "00178000000${seq}000-0000-server"
        val entriesJson =
            if (isDelta) {
                """[{"id":"item-pub-2002","kind":"article","title":"Public Post 2002","url":"https://blog.example.com/post-2002","created_at":"2025-01-01T00:00:00Z","hlc":"$hlc","content":{"content_type":"article"},"state":{"inbox_state":"unread","hlc":"$hlc"}}]"""
            } else {
                """[{"id":"$pubFeedId","kind":"feed","title":"Public Blog","url":"https://blog.example.com","hlc":"$hlc","feed_source":{"feed_url":"https://blog.example.com/rss.xml","source_type":"rss","feed_title":"Public Blog","poll_interval":30,"last_polled_at":"2099-01-01T00:00:00Z","is_active":true,"hlc":"$hlc"}},{"id":"item-pub-0001","kind":"article","title":"Public Post 0001","url":"https://blog.example.com/post-0001","created_at":"2026-05-02T12:00:00Z","hlc":"$hlc","content":{"content_type":"article"},"state":{"inbox_state":"unread","hlc":"$hlc"}}]"""
            }
        HttpResponse(
            statusCode = 200,
            body =
                """{"server_peer_id":"server","sync_seq":$seq,"server_hlc":"$hlc","sync_timestamp":"2026-05-03T00:00:00Z",""" +
                    """"active_peers":[{"peer_id":"server","device_name":"Server","network_tier":"wired","weight":1.0,"last_seen_at":"2026-05-03T00:00:00Z"}],""" +
                    """"entries":$entriesJson,"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
        )
    } else {
        HttpResponse(statusCode = 404, body = "{}")
    }
}

private fun buildRenameFeedSyncMockClient(
    isSyncAvailable: () -> Boolean,
    syncBodies: MutableList<String>,
): RocheApiClient = RocheApiClient { request ->
    if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
        if (isSyncAvailable()) {
            val body = request.body.orEmpty()
            syncBodies.add(body)
            val seq = syncBodies.size
            val staleHlc = "000000000001000-0000-server"
            HttpResponse(
                statusCode = 200,
                body =
                    """{"server_peer_id":"server","sync_seq":$seq,"server_hlc":"00178000000${seq}000-0000-server","sync_timestamp":"2026-06-01T12:00:00Z","active_peers":[],""" +
                        """"entries":[{"id":"feed-arxiv-cs-lg","kind":"feed","title":"arXiv cs.LG","url":"https://arxiv.org/list/cs.LG/recent","hlc":"$staleHlc","feed_source":{"feed_url":"https://export.arxiv.org/api/query?search_query=cat:cs.LG","source_type":"arxiv","feed_title":"arXiv cs.LG","hlc":"$staleHlc"}}],""" +
                        """"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
            )
        } else {
            HttpResponse(statusCode = 503, body = """{"title":"Unavailable"}""")
        }
    } else {
        HttpResponse(statusCode = 404, body = "{}")
    }
}

private fun buildFailingAudioCacheMockClient(): RocheApiClient = RocheApiClient { request ->
    when (request.url) {
        "https://100.64.0.12:8080/api/v1/items/item-audio-broken" ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"id":"item-audio-broken","title":"Broken Episode","url":"https://podcasts.example.com/ep-1","content_type":"podcast","status":"unread","is_favorite":false,"created_at":"2026-04-01T10:00:00Z","audio_url":"https://cdn.example.com/broken.mp3"}""",
            )
        "https://100.64.0.12:8080/api/v1/items/item-audio-broken/media" ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"media":[{"id":"audio-broken","item_id":"item-audio-broken","media_type":"audio","file_size":2048}]}""",
            )
        else -> HttpResponse(statusCode = 503, body = """{"title":"Media Unavailable"}""")
    }
}

internal fun buildTargetedDiffSyncMockClient(roundProvider: () -> Int): RocheApiClient {
    val oversizedSummary = "s".repeat(17_000)
    return RocheApiClient { request ->
        if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
            val round = roundProvider()
            HttpResponse(
                statusCode = 200,
                body = buildTargetedDiffPeerSyncJson(round, oversizedSummary),
            )
        } else {
            HttpResponse(statusCode = 404, body = "{}")
        }
    }
}

private fun buildTargetedDiffPeerSyncJson(round: Int, oversizedSummary: String): String {
    val hlc1 = "001780000001000-0000-server"
    val hlc3 = "001780000003000-0000-server"
    return when (round) {
        1 ->
            """{"server_peer_id":"server","sync_seq":1,"server_hlc":"$hlc1","sync_timestamp":"2026-04-01T10:00:00Z","active_peers":[],""" +
                """"entries":[{"id":"f-1","kind":"feed","title":"Feed","url":"https://example.com","hlc":"$hlc1","feed_source":{"feed_url":"https://example.com/rss","source_type":"rss","feed_title":"Feed","last_polled_at":"2099-01-01T00:00:00Z","hlc":"$hlc1"}},""" +
                """{"id":"t-keep","kind":"collection","title":"keep","hlc":"$hlc1","collection_spec":{"mode":"manual","role":"tag","show_in_lists":false}},""" +
                """{"id":"t-drop","kind":"collection","title":"drop","hlc":"$hlc1","collection_spec":{"mode":"manual","role":"tag","show_in_lists":false}},""" +
                """{"id":"i-keep","kind":"article","title":"Keep","url":"https://example.com/keep","summary":"$oversizedSummary","created_at":"2026-04-01T10:00:00Z","hlc":"$hlc1","content":{"content_type":"article"},"state":{"inbox_state":"unread","hlc":"$hlc1"}},""" +
                """{"id":"i-edit","kind":"article","title":"Original","url":"https://example.com/edit","created_at":"2026-04-01T09:00:00Z","hlc":"$hlc1","content":{"content_type":"article"},"state":{"inbox_state":"unread","hlc":"$hlc1"}},""" +
                """{"id":"i-drop","kind":"article","title":"Drop","url":"https://example.com/drop","created_at":"2026-04-01T08:00:00Z","hlc":"$hlc1","content":{"content_type":"article"},"state":{"inbox_state":"unread","hlc":"$hlc1"}}],""" +
                """"collection_entries":[{"collection_id":"f-1","entry_id":"i-keep","added_at":"2026-04-01T10:00:00Z","hlc":"$hlc1"},{"collection_id":"t-keep","entry_id":"i-keep","added_at":"2026-04-01T10:00:00Z","hlc":"$hlc1"},{"collection_id":"f-1","entry_id":"i-edit","added_at":"2026-04-01T09:00:00Z","hlc":"$hlc1"},{"collection_id":"f-1","entry_id":"i-drop","added_at":"2026-04-01T08:00:00Z","hlc":"$hlc1"},{"collection_id":"t-drop","entry_id":"i-drop","added_at":"2026-04-01T08:00:00Z","hlc":"$hlc1"}],""" +
                """"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}"""
        2 ->
            """{"server_peer_id":"server","sync_seq":2,"server_hlc":"001780000002000-0000-server","sync_timestamp":"2026-04-01T10:30:00Z","active_peers":[],"entries":[],"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}"""
        else ->
            """{"server_peer_id":"server","sync_seq":3,"server_hlc":"$hlc3","sync_timestamp":"2026-04-01T11:00:00Z","active_peers":[],""" +
                """"entries":[{"id":"i-edit","kind":"article","title":"Edited Title","url":"https://example.com/edit","created_at":"2026-04-01T09:00:00Z","hlc":"$hlc3","content":{"content_type":"article"},"state":{"inbox_state":"unread","hlc":"$hlc3"}},""" +
                """{"id":"i-new","kind":"article","title":"New","url":"https://example.com/new","created_at":"2026-04-01T11:00:00Z","hlc":"$hlc3","content":{"content_type":"article"},"state":{"inbox_state":"unread","hlc":"$hlc3"}}],""" +
                """"collection_entries":[{"collection_id":"$STARRED_COLLECTION_ID","entry_id":"i-edit","added_at":"2026-04-01T11:00:00Z","hlc":"$hlc3"},{"collection_id":"f-1","entry_id":"i-new","added_at":"2026-04-01T11:00:00Z","hlc":"$hlc3"},{"collection_id":"t-keep","entry_id":"i-new","added_at":"2026-04-01T11:00:00Z","hlc":"$hlc3"}],""" +
                """"collection_generators":[],"consumption_log":[],"comparisons":[],""" +
                """"tombstones":[{"entity_type":"item","natural_key":"https://example.com/rss\u0000https://example.com/drop","deleted_at":"2026-04-01T11:00:00Z","hlc":"$hlc3"},{"entity_type":"tag","natural_key":"drop","deleted_at":"2026-04-01T11:00:00Z","hlc":"$hlc3"}]}"""
    }
}
