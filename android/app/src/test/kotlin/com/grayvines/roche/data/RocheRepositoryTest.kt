@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.LIST_PREVIEW_HTML_CHARS
import com.grayvines.roche.data.local.LIST_PREVIEW_TEXT_CHARS
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.SeedData
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import java.io.File
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

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var database: RocheDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: RocheRepository

    @BeforeEach
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<RocheDatabase>().setDriver(BundledSQLiteDriver()).build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(tempDir, "test_settings.preferences_pb") },
            )
        settings = SettingsRepository(dataStore)
        val apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") }
        repository =
            RocheRepository(database = database, apiClient = apiClient, settings = settings)
    }

    @AfterEach
    fun tearDown() {
        runBlocking { dataStoreScope.coroutineContext[Job]?.cancelAndJoin() }
        database.close()
    }

    internal suspend fun seedTestDatabase() {
        val dao = database.dao()
        dao.upsertFeeds(SeedData.feeds)
        dao.upsertTags(SeedData.tags)
        dao.upsertItems(SeedData.items)
        dao.insertItemTags(SeedData.itemTags)
    }

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
            assertEquals("http://100.64.0.12:8080/auth/pair", request.url)
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
        assertEquals("http://100.64.0.12:8080", currentSettings.serverUrl)
        assertEquals("paired-bearer-999", currentSettings.bearerToken)
    }

    @Test
    fun `pairWithCode auto-discovers Roche server on Tailnet when serverUrl is blank`() = runTest {
        val autoDiscoverClient = RocheApiClient { request ->
            when (request.url) {
                "http://roche:8080/health" ->
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"status":"ok","service":"roche","canonical_url":"http://roche.tail9876.ts.net:8080"}""",
                    )
                "http://roche.tail9876.ts.net:8080/auth/pair" ->
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
        assertEquals("http://roche.tail9876.ts.net:8080", currentSettings.serverUrl)
        assertEquals("auto-paired-tok", currentSettings.bearerToken)
    }

    @Test
    fun `discoverServer clears existing bearerToken when switching to a different server host`() =
        runTest {
            settings.update {
                it.copy(
                    serverUrl = "http://100.64.0.12:8080",
                    bearerToken = "secret-existing-token",
                )
            }
            val discoverClient = RocheApiClient { request ->
                if (request.url == "http://roche:8080/health") {
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"status":"ok","service":"roche","canonical_url":"http://roche.tail9876.ts.net:8080"}""",
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
            assertEquals("http://roche.tail9876.ts.net:8080", discoveredUrl)

            val updated = settings.settings.first()
            assertEquals("http://roche.tail9876.ts.net:8080", updated.serverUrl)
            assertEquals("", updated.bearerToken)
        }

    @Test
    fun `syncWithServer replaces demo seed rows populates item tags and strips html in reader`() =
        runTest {
            seedTestDatabase()
            settings.update {
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
            }

            val syncClient = RocheApiClient { request ->
                when (request.url) {
                    "http://100.64.0.12:8080/api/v1/feeds" ->
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"feeds":[{"id":"f-live","title":"Live RSS","url":"https://example.com/rss","feed_type":"rss","unread_count":1}]}""",
                        )
                    "http://100.64.0.12:8080/api/v1/tags" ->
                        HttpResponse(
                            statusCode = 200,
                            body = """{"tags":[{"id":"t-live","name":"rust","unread_count":1}]}""",
                        )
                    "http://100.64.0.12:8080/api/v1/items?per_page=200" ->
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"items":[{"id":"i-live-1","feed_id":"f-live","feed_title":"Live RSS","title":"Synced Post","url":"https://example.com/1","summary":"<p>Summary &amp; intro</p>","content_html":"<p>First para &lt;b&gt; &amp; details.</p><p>Second <em>italic</em> para with &amp;lt;tag&amp;gt;.</p>","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-04-01T10:00:00Z","tag_names":["rust"]}]}""",
                        )
                    else -> HttpResponse(statusCode = 404, body = "{}")
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
    fun `saveUrlOrFeed subscribes feed without saving raw xml item and toggleTag reconciles server tag id`() =
        runTest {
            seedTestDatabase()
            settings.update {
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val requests = mutableListOf<String>()
            val liveClient = RocheApiClient { request ->
                requests.add("${request.method} ${request.url}")
                when {
                    request.method == "POST" && request.url.endsWith("/api/v1/feeds") ->
                        HttpResponse(
                            statusCode = 201,
                            body =
                                """{"id":"feed-srv-1","title":"Rust Blog","url":"https://blog.rust-lang.org/feed.xml","feed_type":"rss","unread_count":0}""",
                        )
                    request.method == "POST" && request.url.endsWith("/api/v1/items/item-2/tags") ->
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"id":"item-2","tags":[{"id":"tag-srv-77","name":"ebpf","unread_count":1}]}""",
                        )
                    request.method == "DELETE" &&
                        request.url.endsWith("/api/v1/items/item-2/tags/tag-srv-77") ->
                        HttpResponse(statusCode = 204, body = "")
                    else -> HttpResponse(statusCode = 404, body = "{}")
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
            liveRepo.toggleTagOnItem(itemId = "item-2", tagName = "ebpf")
            assertTrue(
                requests.contains(
                    "DELETE http://100.64.0.12:8080/api/v1/items/item-2/tags/tag-srv-77"
                )
            )
        }

    @Test
    internal fun `updateReadProgress and local tag removal push mutations to server`() = runTest {
        seedTestDatabase()
        settings.update {
            it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
        }
        val requests = mutableListOf<Pair<String, String?>>()
        val liveClient = RocheApiClient { request ->
            requests.add("${request.method} ${request.url}" to request.body)
            when {
                request.method == "PATCH" && request.url.endsWith("/api/v1/items/item-1") ->
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"id":"item-1","title":"Paper","url":"https://arxiv.org/abs/2603.11842","content_type":"paper","status":"unread","is_favorite":true,"read_progress":0.85,"created_at":"2026-03-29T10:00:00Z"}""",
                    )
                request.method == "GET" && request.url.endsWith("/api/v1/tags") ->
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"tags":[{"id":"srv-sqlite-uuid","name":"sqlite","unread_count":2}]}""",
                    )
                request.method == "DELETE" &&
                    request.url.endsWith("/api/v1/items/item-2/tags/srv-sqlite-uuid") ->
                    HttpResponse(statusCode = 204, body = "")
                else -> HttpResponse(statusCode = 404, body = "{}")
            }
        }
        val liveRepo =
            RocheRepository(database = database, apiClient = liveClient, settings = settings)

        liveRepo.updateReadProgress(itemId = "item-1", progress = 0.85)
        val updatedItem = repository.snapshot.first().entries.first { it.id == "item-1" }
        assertEquals(0.85, updatedItem.readProgress)
        assertTrue(
            requests.any { (endpoint, body) ->
                endpoint == "PATCH http://100.64.0.12:8080/api/v1/items/item-1" &&
                    body == """{"read_progress":0.85}"""
            }
        )

        // Marking read records an ISO readAt timestamp and toggling clears it back to null
        assertReadTimestampSync(liveRepo, requests)

        // Removing a tag whose local ID starts with "tag-" resolves the server UUID first
        liveRepo.toggleTagOnItem(itemId = "item-2", tagName = "sqlite")
        assertTrue(
            requests.any { (endpoint, _) ->
                endpoint ==
                    "DELETE http://100.64.0.12:8080/api/v1/items/item-2/tags/srv-sqlite-uuid"
            }
        )
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
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val pushedRequests = mutableListOf<Pair<String, String?>>()
            val syncClient = buildTwoWaySyncMockClient(pushedRequests)
            val syncedRepo =
                RocheRepository(database = database, apiClient = syncClient, settings = settings)

            val count = syncedRepo.syncWithServer().getOrThrow()
            assertEquals(2, count)
            assertTwoWaySyncPushedRequests(pushedRequests)

            val entries = syncedRepo.snapshot.first().entries.associateBy { it.id }
            val item1 = entries.getValue("item-1")
            assertTrue(item1.isArchived)
            assertFalse(item1.isFavorite)
            assertEquals(0.9, item1.readProgress)
            assertEquals(listOf("llm-inference", "rust"), item1.tags)

            val savedItem = entries.getValue("srv-saved-99")
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
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val addedTagRequests = mutableListOf<String>()
            val liveClient = buildInFlightTagMockClient(addedTagRequests)
            val dao = database.dao()
            val localEntity =
                createSavedItemEntity(
                    url = "https://example.com/in-flight",
                    title = "In-Flight Entry",
                    hostTitle = "example.com",
                    type = ContentType.ARTICLE,
                )
            dao.upsertItem(localEntity)
            attachTagsLocally(dao = dao, itemId = localEntity.id, tagNames = listOf("initial"))
            dao.upsertPendingMutation(
                com.grayvines.roche.data.local.PendingMutationEntity(
                    entityId = localEntity.id,
                    isLocalItem = true,
                )
            )
            val liveRepo =
                RocheRepository(database = database, apiClient = liveClient, settings = settings)
            liveRepo.toggleTagOnItem(itemId = localEntity.id, tagName = "added-in-flight")

            finalizeOnlineSavedItem(
                dao = dao,
                apiClient = liveClient,
                settings = settings,
                localEntity = localEntity,
                remoteSaved =
                    com.grayvines.roche.data.remote.parseItemJson(
                        """{"id":"srv-item-77","title":"In-Flight Entry","url":"https://example.com/in-flight","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-04-01T12:00:00Z","tag_names":["initial"]}"""
                    ),
                tags = listOf("initial"),
            )

            val entry = liveRepo.snapshot.first().entries.first { it.id == "srv-item-77" }
            assertEquals(listOf("added-in-flight", "initial"), entry.tags)
            assertTrue(
                addedTagRequests.any {
                    it.contains("/api/v1/items/srv-item-77/tags") && it.contains("added-in-flight")
                }
            )
        }

    @Test
    internal fun `toggleOfflineCache hydrates item detail downloads media files and cleans up on evict`() =
        runTest {
            seedTestDatabase()
            settings.update {
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
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
                8,
                com.grayvines.roche.ui.components.calculateInSampleSize(4000, 3000, 256, 256),
            )
        }

    @Test
    internal fun `refreshFeed deleteFeed and deleteItem sync with server and clean up local state`() =
        runTest {
            seedTestDatabase()
            settings.update {
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
            }
            val cacheDir = File(tempDir, "delete_item_cache")
            val itemCacheDir = File(File(cacheDir, "items"), "item-1").apply { mkdirs() }
            File(itemCacheDir, "media-paper-1").writeBytes(byteArrayOf(1, 2, 3))
            val requests = mutableListOf<String>()
            val managedRepo =
                RocheRepository(
                    database = database,
                    apiClient = buildFeedAndItemManagementMockClient(requests),
                    settings = settings,
                    cacheDir = cacheDir,
                )

            val refreshedTitle = managedRepo.refreshFeed("feed-arxiv-cs-lg").getOrThrow()
            assertEquals("arXiv cs.LG", refreshedTitle)
            assertTrue(managedRepo.snapshot.first().entries.any { it.id == "item-fresh" })

            // Offline deleteItem records tombstone so syncWithServer does not resurrect item-1
            settings.update { it.copy(bearerToken = "") }
            assertTrue(managedRepo.deleteItem("item-1").getOrThrow().isNotBlank())
            assertFalse(itemCacheDir.exists())
            assertEquals(
                DELETED_STATUS_TOMBSTONE,
                database.dao().getPendingMutation("item-1")?.dirtyStatus,
            )
            // Mutating a deleted item is a safe no-op and does not overwrite tombstone
            assertEquals(null, managedRepo.setArchiveState("item-1", archived = true))
            managedRepo.restoreStatus("item-1", ItemStatus.UNREAD)
            managedRepo.updateReadProgress("item-1", 0.5)
            managedRepo.toggleTagOnItem("item-1", "tag-1")
            assertEquals(
                DELETED_STATUS_TOMBSTONE,
                database.dao().getPendingMutation("item-1")?.dirtyStatus,
            )

            // Syncing back online pushes DELETE /api/v1/items/item-1 and does not resurrect item-1
            settings.update { it.copy(bearerToken = "tok-live") }
            managedRepo.syncWithServer().getOrThrow()
            assertTrue(requests.contains("DELETE http://100.64.0.12:8080/api/v1/items/item-1"))
            assertFalse(managedRepo.snapshot.first().entries.any { it.id == "item-1" })
            assertEquals(null, database.dao().getPendingMutation("item-1"))

            // Delete remote feed purges feed AND its remaining local items (item-fresh)
            assertEquals("arXiv cs.LG", managedRepo.deleteFeed("feed-arxiv-cs-lg").getOrThrow())
            assertFalse(managedRepo.snapshot.first().feeds.any { it.id == "feed-arxiv-cs-lg" })
            assertFalse(managedRepo.snapshot.first().entries.any { it.id == "item-fresh" })
        }

    @Test
    internal fun `remote sync leaves contentText null when server sends summary and long content_html`() =
        runTest {
            settings.update {
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
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
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
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
            assertEquals(true, database.dao().getPendingMutation("item-pub-2002")?.dirtyFavorite)

            // Second pass (delta sync) sends the previously overflowed mutation and still keeps LAN
            // data
            syncedRepo.syncWithServer().getOrThrow()
            assertEquals(2, peerSyncBodies.size)
            assertTrue(peerSyncBodies[1].contains("item-pub-2002"))
            assertNull(database.dao().getPendingMutation("item-pub-2002"))
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
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
            }
            var patchAvailable = false
            val patchBodies = mutableListOf<String>()
            val renameRepo =
                RocheRepository(
                    database = database,
                    apiClient =
                        buildRenameFeedSyncMockClient(
                            isPatchAvailable = { patchAvailable },
                            patchBodies = patchBodies,
                        ),
                    settings = settings,
                )

            renameRepo.renameFeed("feed-arxiv-cs-lg", "  Custom ML Papers  ")
            assertEquals(
                MutationAction.RENAME_FEED,
                database.dao().getPendingMutation("feed-arxiv-cs-lg")?.dirtyStatus,
            )
            assertEquals(
                "Custom ML Papers",
                database.dao().getPendingMutation("feed-arxiv-cs-lg")?.titleOverride,
            )

            // Sync while PATCH still fails: stale server title must NOT clobber uncommitted rename
            renameRepo.syncWithServer().getOrThrow()
            val afterOfflineSync = renameRepo.snapshot.first()
            assertEquals(
                "Custom ML Papers",
                afterOfflineSync.feeds.first { it.id == "feed-arxiv-cs-lg" }.title,
            )
            assertEquals(
                "Custom ML Papers",
                afterOfflineSync.entries.first { it.id == "item-1" }.feedTitle,
            )

            // Server recovers: pending rename replays via PATCH and clears mutation
            patchAvailable = true
            renameRepo.syncWithServer().getOrThrow()
            assertTrue(patchBodies.contains("""{"title":"Custom ML Papers"}"""))
            assertNull(database.dao().getPendingMutation("feed-arxiv-cs-lg"))

            renameRepo.renameFeed("feed-arxiv-cs-lg", "Online Direct Title")
            assertTrue(patchBodies.contains("""{"title":"Online Direct Title"}"""))
            assertNull(database.dao().getPendingMutation("feed-arxiv-cs-lg"))
        }

    @Test
    internal fun `toggleOfflineCache leaves isCachedOffline false when audio download fails`() =
        runTest {
            settings.update {
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
            }
            database
                .dao()
                .upsertItem(
                    ItemEntity(
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
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
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
                runBlocking { cancellingRepo.setArchiveState("item-1", archived = true) }
            }
            assertThrows(CancellationException::class.java) {
                runBlocking { cancellingRepo.toggleOfflineCache("item-5") }
            }
            assertNull(database.dao().getPendingMutation("item-1"))
            assertNull(database.dao().getPendingMutation("item-5"))
        }

    @Test
    internal fun `reconcileSnapshotDiff applies targeted diffs chunks deletes and skips 0-change writes`() =
        runTest {
            settings.update {
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-live")
            }
            var syncRound = 1
            val diffRepo =
                RocheRepository(
                    database = database,
                    apiClient = buildTargetedDiffSyncMockClient { syncRound },
                    settings = settings,
                )

            // Round 1: initial snapshot with i-keep (oversized summary), i-edit, i-drop, 2 tags
            assertEquals(3, diffRepo.syncWithServer().getOrThrow())
            assertEquals(
                setOf("keep", "drop"),
                diffRepo.snapshot.first().tags.map { it.name }.toSet(),
            )

            // Round 2: 0-change sync preserves all rows identically
            diffRepo.syncWithServer().getOrThrow()
            assertEquals(3, diffRepo.snapshot.first().entries.size)

            // Round 3: insert i-new, update i-edit, delete i-drop and drop tag
            syncRound = 2
            assertEquals(3, diffRepo.syncWithServer().getOrThrow())
            val afterDiff = diffRepo.snapshot.first()
            val byId = afterDiff.entries.associateBy { it.id }
            assertEquals(setOf("i-keep", "i-edit", "i-new"), byId.keys)
            assertEquals("Edited Title", byId.getValue("i-edit").title)
            assertTrue(byId.getValue("i-edit").isFavorite)
            assertEquals(listOf("keep"), byId.getValue("i-new").tags)
            assertEquals(listOf("keep"), afterDiff.tags.map { it.name })

            verifyDaoDiffSkipsZeroChangeAndChunksDeletes(database.dao())
        }

    @Test
    internal fun `syncWithServer follows has_more delta continuation pages and advances cursor`() =
        runTest {
            val feedId = deterministicFeedId("https://blog.example.com/rss.xml")
            seedPaginatedDeltaInitialState(database.dao(), feedId)
            settings.update {
                it.copy(
                    serverUrl = "http://100.64.0.12:8080",
                    bearerToken = "tok-paginated",
                    lastPeerSyncCursor = "2026-06-01T00:00:00Z",
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
            assertTrue(peerSyncBodies[0].contains(""""updated_after":"2026-06-01T00:00:00Z""""))
            assertFalse(peerSyncBodies[0].contains("after_item_id"))
            assertTrue(peerSyncBodies[1].contains(""""updated_after":"2026-06-01T01:00:00Z""""))
            assertTrue(peerSyncBodies[1].contains(""""after_item_id":"item-page-1""""))
            assertTrue(peerSyncBodies[1].contains(""""items":[]"""))
            assertTrue(peerSyncBodies[1].contains(""""feeds":[]"""))
            assertTrue(peerSyncBodies[2].contains(""""updated_after":"2026-06-01T01:00:00Z""""))
            assertTrue(peerSyncBodies[2].contains(""""after_item_id":"item-page-2""""))
            assertTrue(peerSyncBodies[2].contains(""""items":[]"""))
            assertEquals(
                "2026-06-01T03:00:00Z",
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
                it.copy(serverUrl = "http://100.64.0.12:8080", bearerToken = "tok-bg")
            }
            val patchStarted = CountDownLatch(1)
            val allowPatchFinish = CountDownLatch(1)
            val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val slowApiClient = RocheApiClient { request ->
                    if (request.method == "PATCH" && request.url.endsWith("/api/v1/items/item-2")) {
                        patchStarted.countDown()
                        allowPatchFinish.await(5, TimeUnit.SECONDS)
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"id":"item-2","title":"Async Star","url":"https://example.com/2","content_type":"article","status":"unread","is_favorite":true,"created_at":"2026-04-01T10:00:00Z"}""",
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
                        backgroundScope = bgScope,
                    )

                // item-2 starts unstarred; toggleStar returns immediately before PATCH completes
                assertTrue(asyncRepo.toggleStar("item-2"))
                assertEquals(true, database.dao().getItemStateById("item-2")?.isFavorite)
                assertEquals(
                    true,
                    database.dao().getPendingMutation("item-2")?.dirtyFavorite,
                )
                assertTrue(patchStarted.await(5, TimeUnit.SECONDS))

                // Release slow HTTP PATCH and verify pending_mutations clears once pushed
                allowPatchFinish.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (
                    database.dao().getPendingMutation("item-2") != null &&
                        System.nanoTime() < deadline
                ) {
                    kotlinx.coroutines.withContext(Dispatchers.Default) {
                        kotlinx.coroutines.delay(15)
                    }
                }
                assertNull(database.dao().getPendingMutation("item-2"))
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
        }
}

private const val EXPECTED_CONTINUATION_REQUESTS = 3
private const val EXPECTED_PAGINATED_ITEM_COUNT = 4

private suspend fun seedPaginatedDeltaInitialState(dao: RocheDao, feedId: String) {
    dao.upsertFeed(
        FeedEntity(
            id = feedId,
            title = "Systems Feed",
            url = "https://blog.example.com/rss.xml",
            siteUrl = "https://blog.example.com",
            feedType = "rss",
            lastPolled = "2099-01-01T00:00:00Z",
        )
    )
    dao.upsertItem(
        ItemEntity(
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
    val nextCursor: String,
    val hasMore: Boolean,
    val nextAfterItemId: String?,
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
                body.contains(""""updated_after":"2026-06-01T00:00:00Z"""") ->
                    DeltaPageFixture("item-page-1", "2026-06-01T01:00:00Z", true, "item-page-1")
                body.contains(""""after_item_id":"item-page-1"""") ->
                    DeltaPageFixture("item-page-2", "2026-06-01T01:00:00Z", true, "item-page-2")
                else -> DeltaPageFixture("item-page-3", "2026-06-01T03:00:00Z", false, null)
            }
        val nextAfterJson =
            page.nextAfterItemId?.let { """"next_after_item_id":"$it",""" }.orEmpty()
        HttpResponse(
            statusCode = 200,
            body =
                """{"server_peer_id":"server","sync_timestamp":"${page.nextCursor}","has_more":${page.hasMore},""" +
                    nextAfterJson +
                    """"active_peers":[{"peer_id":"server","device_name":"Server","network_tier":"wired","weight":1.0,"last_seen_at":"${page.nextCursor}"}],""" +
                    """"feeds":[{"id":"$feedId","title":"Systems Feed","url":"https://blog.example.com/rss.xml","feed_type":"rss","poll_interval":30,"last_polled":"2099-01-01T00:00:00Z","is_active":true,"unread_count":4}],""" +
                    """"items":[{"id":"${page.itemId}","feed_id":"$feedId","feed_url":"https://blog.example.com/rss.xml","feed_title":"Systems Feed","title":"Post ${page.itemId}","url":"https://blog.example.com/${page.itemId}","content_type":"article","status":"unread","is_favorite":false,"created_at":"${page.nextCursor}","tag_names":[]}],""" +
                    """"tags":[],"tombstones":[]}""",
        )
    } else {
        HttpResponse(statusCode = 404, body = "{}")
    }
}

internal fun buildFeedAndItemManagementMockClient(requests: MutableList<String>): RocheApiClient =
    RocheApiClient { request ->
        requests.add("${request.method} ${request.url}")
        when {
            request.method == "POST" &&
                request.url.endsWith("/api/v1/feeds/feed-arxiv-cs-lg/refresh") ->
                HttpResponse(
                    statusCode = 200,
                    body =
                        """{"id":"feed-arxiv-cs-lg","title":"arXiv cs.LG","url":"https://export.arxiv.org/api/query?search_query=cat:cs.LG","feed_type":"arxiv","unread_count":2}""",
                )
            request.method == "GET" && request.url.endsWith("/api/v1/feeds") ->
                HttpResponse(
                    statusCode = 200,
                    body =
                        """{"feeds":[{"id":"feed-arxiv-cs-lg","title":"arXiv cs.LG","url":"https://export.arxiv.org/api/query?search_query=cat:cs.LG","feed_type":"arxiv","unread_count":2}]}""",
                )
            request.method == "GET" && request.url.endsWith("/api/v1/tags") ->
                HttpResponse(statusCode = 200, body = """{"tags":[]}""")
            request.method == "GET" && request.url.contains("/api/v1/items") ->
                HttpResponse(
                    statusCode = 200,
                    body =
                        """{"items":[{"id":"item-1","feed_id":"feed-arxiv-cs-lg","feed_title":"arXiv cs.LG","title":"Speculative Decoding","url":"https://arxiv.org/abs/2603.11842","content_type":"paper","status":"unread","is_favorite":true,"created_at":"2026-03-29T10:00:00Z"},{"id":"item-fresh","feed_id":"feed-arxiv-cs-lg","feed_title":"arXiv cs.LG","title":"Fresh Polled Paper","url":"https://arxiv.org/abs/2604.00001","content_type":"paper","status":"unread","is_favorite":false,"created_at":"2026-04-01T10:00:00Z"}]}""",
                )
            request.method == "DELETE" && request.url.endsWith("/api/v1/feeds/feed-arxiv-cs-lg") ->
                HttpResponse(
                    statusCode = 404,
                    body = """{"title":"Not Found","detail":"missing"}""",
                )
            request.method == "DELETE" && request.url.endsWith("/api/v1/items/item-1") ->
                HttpResponse(statusCode = 204, body = "")
            else -> HttpResponse(statusCode = 404, body = "{}")
        }
    }

internal fun buildOfflineCacheMockClient(
    pdfBytes: ByteArray,
    thumbBytes: ByteArray,
): RocheApiClient = RocheApiClient { request ->
    when (request.url) {
        "http://100.64.0.12:8080/api/v1/items/item-5" ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"id":"item-5","title":"Deterministic Simulation Testing","url":"https://arxiv.org/abs/2603.09910","content_html":"<p>Hydrated full paper HTML</p>","content_type":"paper","status":"unread","is_favorite":false,"created_at":"2026-03-27T10:00:00Z","thumbnail_media_id":"thumb-item-5"}""",
            )
        "http://100.64.0.12:8080/api/v1/items/item-5/media" ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"media":[{"id":"pdf-item-5","item_id":"item-5","media_type":"pdf","file_size":13},{"id":"thumb-item-5","item_id":"item-5","media_type":"thumbnail","file_size":4}]}""",
            )
        "http://100.64.0.12:8080/api/v1/media/pdf-item-5" ->
            HttpResponse(statusCode = 200, body = "", bodyBytes = pdfBytes)
        "http://100.64.0.12:8080/api/v1/media/thumb-item-5" ->
            HttpResponse(statusCode = 200, body = "", bodyBytes = thumbBytes)
        "http://100.64.0.12:8080/api/v1/feeds" ->
            HttpResponse(statusCode = 200, body = """{"feeds":[]}""")
        "http://100.64.0.12:8080/api/v1/tags" ->
            HttpResponse(statusCode = 200, body = """{"tags":[]}""")
        "http://100.64.0.12:8080/api/v1/items?per_page=200" ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"items":[{"id":"item-5","title":"Deterministic Simulation Testing","url":"https://arxiv.org/abs/2603.09910","content_type":"paper","status":"unread","is_favorite":false,"created_at":"2026-03-27T10:00:00Z"}]}""",
            )
        else -> HttpResponse(statusCode = 404, body = "{}")
    }
}

internal fun buildInFlightTagMockClient(addedTagRequests: MutableList<String>): RocheApiClient =
    RocheApiClient { request ->
        if (request.method == "POST" && request.url.endsWith("/tags")) {
            addedTagRequests.add("${request.url}:${request.body}")
            HttpResponse(
                statusCode = 200,
                body =
                    """{"id":"srv-item-77","tags":[{"id":"srv-tag-added","name":"added-in-flight","unread_count":1}]}""",
            )
        } else {
            HttpResponse(statusCode = 404, body = "{}")
        }
    }

internal fun assertTwoWaySyncPushedRequests(pushedRequests: List<Pair<String, String?>>) {
    assertTrue(
        pushedRequests.any { (req, body) ->
            req == "POST http://100.64.0.12:8080/api/v1/items/save" &&
                body?.contains("https://example.com/offline-note") == true
        }
    )
    assertTrue(
        pushedRequests.any { (req, body) ->
            req == "PATCH http://100.64.0.12:8080/api/v1/items/item-1" &&
                body == """{"status":"archived","is_favorite":false,"read_progress":0.9}"""
        }
    )
    assertTrue(
        pushedRequests.any { (req, body) ->
            req == "POST http://100.64.0.12:8080/api/v1/items/item-1/tags" &&
                body == """{"name":"rust"}"""
        }
    )
    assertTrue(
        pushedRequests.any { (req, _) ->
            req == "DELETE http://100.64.0.12:8080/api/v1/items/item-1/tags/srv-tag-systems"
        }
    )
}

internal fun buildTwoWaySyncMockClient(
    pushedRequests: MutableList<Pair<String, String?>>
): RocheApiClient = RocheApiClient { request ->
    pushedRequests.add("${request.method} ${request.url}" to request.body)
    if (request.method == "GET") {
        handleTwoWaySyncReadRequest(request.url)
    } else {
        handleTwoWaySyncWriteRequest(request.method, request.url)
    }
}

internal fun handleTwoWaySyncWriteRequest(method: String, url: String): HttpResponse =
    when {
        method == "POST" && url.endsWith("/api/v1/items/save") ->
            HttpResponse(
                statusCode = 201,
                body =
                    """{"id":"srv-saved-99","title":"Offline Note","url":"https://example.com/offline-note","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-04-01T12:00:00Z","tag_names":["crdt"]}""",
            )
        method == "PATCH" && url.endsWith("/api/v1/items/item-1") ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"id":"item-1","title":"Speculative Decoding","url":"https://arxiv.org/abs/2603.11842","content_type":"paper","status":"archived","is_favorite":false,"read_progress":0.9,"created_at":"2026-03-29T10:00:00Z"}""",
            )
        method == "POST" && url.endsWith("/api/v1/items/item-1/tags") ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"id":"item-1","tags":[{"id":"srv-tag-rust","name":"rust","unread_count":0}]}""",
            )
        method == "DELETE" && url.endsWith("/api/v1/items/item-1/tags/srv-tag-systems") ->
            HttpResponse(statusCode = 204, body = "")
        else -> HttpResponse(statusCode = 404, body = "{}")
    }

internal fun handleTwoWaySyncReadRequest(url: String): HttpResponse =
    when {
        url.endsWith("/api/v1/feeds") ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"feeds":[{"id":"feed-arxiv-cs-lg","title":"arXiv cs.LG","url":"https://export.arxiv.org/api/query?search_query=cat:cs.LG","feed_type":"arxiv","unread_count":0}]}""",
            )
        url.endsWith("/api/v1/tags") ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"tags":[{"id":"srv-tag-llm","name":"llm-inference","unread_count":0},{"id":"srv-tag-systems","name":"systems","unread_count":0},{"id":"srv-tag-rust","name":"rust","unread_count":0},{"id":"srv-tag-crdt","name":"crdt","unread_count":1}]}""",
            )
        url.contains("/api/v1/items") ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"items":[{"id":"item-1","feed_id":"feed-arxiv-cs-lg","feed_title":"arXiv cs.LG","title":"Speculative Decoding","url":"https://arxiv.org/abs/2603.11842","content_type":"paper","status":"unread","is_favorite":true,"read_progress":0.35,"created_at":"2026-03-29T10:00:00Z","tag_names":["llm-inference","systems"]}]}""",
            )
        else -> HttpResponse(statusCode = 404, body = "{}")
    }

internal suspend fun assertReadTimestampSync(
    liveRepo: RocheRepository,
    requests: List<Pair<String, String?>>,
) {
    liveRepo.setReadState(itemId = "item-1", isRead = true)
    val readItem = liveRepo.snapshot.first().entries.first { it.id == "item-1" }
    assertTrue(readItem.isRead)
    assertTrue(!readItem.readAt.isNullOrBlank())
    assertTrue(
        requests.any { (endpoint, body) ->
            endpoint == "PATCH http://100.64.0.12:8080/api/v1/items/item-1" &&
                body == """{"read_at":"${readItem.readAt}"}"""
        }
    )

    assertFalse(liveRepo.toggleReadState(itemId = "item-1"))
    val unreadItem = liveRepo.snapshot.first().entries.first { it.id == "item-1" }
    assertFalse(unreadItem.isRead)
    assertEquals(null, unreadItem.readAt)
    assertTrue(
        requests.any { (endpoint, body) ->
            endpoint == "PATCH http://100.64.0.12:8080/api/v1/items/item-1" &&
                body == """{"read_at":null}"""
        }
    )
}

private fun buildLongHtmlSyncMockClient(longHtml: String): RocheApiClient =
    RocheApiClient { request ->
        when {
            request.method == "GET" && request.url.endsWith("/api/v1/feeds") ->
                HttpResponse(statusCode = 200, body = """{"feeds":[]}""")
            request.method == "GET" && request.url.endsWith("/api/v1/tags") ->
                HttpResponse(statusCode = 200, body = """{"tags":[]}""")
            request.method == "GET" && request.url.contains("/api/v1/items") ->
                HttpResponse(
                    statusCode = 200,
                    body =
                        """{"items":[{"id":"item-long-essay","title":"Ten Thousand Words","url":"https://example.com/long-essay","summary":"Short 320-char excerpt.","content_html":"$longHtml","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-04-01T10:00:00Z"}]}""",
                )
            else -> HttpResponse(statusCode = 404, body = "{}")
        }
    }

private suspend fun seedLanFeedAndOverflowDataset(database: RocheDatabase) {
    val dao = database.dao()
    val lanFeedId = deterministicFeedId("http://192.168.1.50/rss.xml")
    val pubFeedId = deterministicFeedId("https://blog.example.com/rss.xml")
    dao.upsertFeeds(
        listOf(
            FeedEntity(
                id = lanFeedId,
                title = "Homelab LAN RSS",
                url = "http://192.168.1.50/rss.xml",
                siteUrl = "http://192.168.1.50",
                feedType = "rss",
                lastPolled = "2099-01-01T00:00:00Z",
            ),
            FeedEntity(
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
        ItemEntity(
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
            ItemEntity(
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
    dao.upsertPendingMutation(
        PendingMutationEntity(entityId = "item-pub-2002", dirtyFavorite = true)
    )
}

private fun buildLanAndOverflowPeerSyncMockClient(
    peerSyncBodies: MutableList<String>
): RocheApiClient = RocheApiClient { request ->
    if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
        val body = request.body.orEmpty()
        peerSyncBodies.add(body)
        val pubFeedId = deterministicFeedId("https://blog.example.com/rss.xml")
        val isDelta = body.contains(""""updated_after"""")
        val itemsJson =
            if (isDelta) {
                """[{"id":"item-pub-2002","feed_id":"$pubFeedId","feed_url":"https://blog.example.com/rss.xml","feed_title":"Public Blog","title":"Public Post 2002","url":"https://blog.example.com/post-2002","content_type":"article","status":"unread","is_favorite":true,"created_at":"2025-01-01T00:00:00Z","tag_names":[]}]"""
            } else {
                """[{"id":"item-pub-0001","feed_id":"$pubFeedId","feed_url":"https://blog.example.com/rss.xml","feed_title":"Public Blog","title":"Public Post 0001","url":"https://blog.example.com/post-0001","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-05-02T12:00:00Z","tag_names":[]}]"""
            }
        HttpResponse(
            statusCode = 200,
            body =
                """{"server_peer_id":"server","sync_timestamp":"2026-05-03T00:00:00Z",""" +
                    """"active_peers":[{"peer_id":"server","device_name":"Server","network_tier":"wired","weight":1.0,"last_seen_at":"2026-05-03T00:00:00Z"}],""" +
                    """"feeds":[{"id":"$pubFeedId","title":"Public Blog","url":"https://blog.example.com/rss.xml","feed_type":"rss","poll_interval":30,"last_polled":"2099-01-01T00:00:00Z","is_active":true,"unread_count":1}],""" +
                    """"items":$itemsJson,"tags":[],"tombstones":[]}""",
        )
    } else {
        HttpResponse(statusCode = 404, body = "{}")
    }
}

private fun buildRenameFeedSyncMockClient(
    isPatchAvailable: () -> Boolean,
    patchBodies: MutableList<String>,
): RocheApiClient = RocheApiClient { request ->
    when {
        request.method == "PATCH" && request.url.endsWith("/api/v1/feeds/feed-arxiv-cs-lg") ->
            if (isPatchAvailable()) {
                patchBodies.add(request.body.orEmpty())
                HttpResponse(
                    statusCode = 200,
                    body =
                        """{"id":"feed-arxiv-cs-lg","title":"Custom ML Papers","url":"https://export.arxiv.org/api/query?search_query=cat:cs.LG","feed_type":"arxiv","unread_count":1}""",
                )
            } else {
                HttpResponse(statusCode = 503, body = """{"title":"Unavailable"}""")
            }
        request.method == "GET" && request.url.endsWith("/api/v1/feeds") ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"feeds":[{"id":"feed-arxiv-cs-lg","title":"arXiv cs.LG","url":"https://export.arxiv.org/api/query?search_query=cat:cs.LG","feed_type":"arxiv","unread_count":1}]}""",
            )
        request.method == "GET" && request.url.endsWith("/api/v1/tags") ->
            HttpResponse(statusCode = 200, body = """{"tags":[]}""")
        request.method == "GET" && request.url.contains("/api/v1/items") ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"items":[{"id":"item-1","feed_id":"feed-arxiv-cs-lg","feed_title":"arXiv cs.LG","title":"Speculative Decoding","url":"https://arxiv.org/abs/2603.11842","content_type":"paper","status":"unread","is_favorite":true,"created_at":"2026-03-29T10:00:00Z"}]}""",
            )
        else -> HttpResponse(statusCode = 404, body = "{}")
    }
}

private fun buildFailingAudioCacheMockClient(): RocheApiClient = RocheApiClient { request ->
    when (request.url) {
        "http://100.64.0.12:8080/api/v1/items/item-audio-broken" ->
            HttpResponse(
                statusCode = 200,
                body =
                    """{"id":"item-audio-broken","title":"Broken Episode","url":"https://podcasts.example.com/ep-1","content_type":"podcast","status":"unread","is_favorite":false,"created_at":"2026-04-01T10:00:00Z","audio_url":"https://cdn.example.com/broken.mp3"}""",
            )
        "http://100.64.0.12:8080/api/v1/items/item-audio-broken/media" ->
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
        val round = roundProvider()
        when {
            request.url.endsWith("/api/v1/feeds") ->
                HttpResponse(
                    statusCode = 200,
                    body =
                        """{"feeds":[{"id":"f-1","title":"Feed","url":"https://example.com/rss","feed_type":"rss","unread_count":2}]}""",
                )
            request.url.endsWith("/api/v1/tags") ->
                HttpResponse(
                    statusCode = 200,
                    body =
                        if (round == 1) {
                            """{"tags":[{"id":"t-keep","name":"keep","unread_count":1},{"id":"t-drop","name":"drop","unread_count":1}]}"""
                        } else {
                            """{"tags":[{"id":"t-keep","name":"keep","unread_count":2}]}"""
                        },
                )
            request.url.contains("/api/v1/items") ->
                HttpResponse(
                    statusCode = 200,
                    body = buildTargetedDiffItemsJson(round, oversizedSummary),
                )
            else -> HttpResponse(statusCode = 404, body = "{}")
        }
    }
}

internal fun buildTargetedDiffItemsJson(round: Int, oversizedSummary: String): String =
    if (round == 1) {
        """{"items":[""" +
            """{"id":"i-keep","feed_id":"f-1","feed_title":"Feed","title":"Keep","url":"https://example.com/keep","summary":"$oversizedSummary","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-04-01T10:00:00Z","tag_names":["keep"]},""" +
            """{"id":"i-edit","feed_id":"f-1","feed_title":"Feed","title":"Original","url":"https://example.com/edit","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-04-01T09:00:00Z","tag_names":[]},""" +
            """{"id":"i-drop","feed_id":"f-1","feed_title":"Feed","title":"Drop","url":"https://example.com/drop","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-04-01T08:00:00Z","tag_names":["drop"]}]}"""
    } else {
        """{"items":[""" +
            """{"id":"i-keep","feed_id":"f-1","feed_title":"Feed","title":"Keep","url":"https://example.com/keep","summary":"$oversizedSummary","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-04-01T10:00:00Z","tag_names":["keep"]},""" +
            """{"id":"i-edit","feed_id":"f-1","feed_title":"Feed","title":"Edited Title","url":"https://example.com/edit","content_type":"article","status":"unread","is_favorite":true,"created_at":"2026-04-01T09:00:00Z","tag_names":[]},""" +
            """{"id":"i-new","feed_id":"f-1","feed_title":"Feed","title":"New","url":"https://example.com/new","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-04-01T11:00:00Z","tag_names":["keep"]}]}"""
    }

internal suspend fun verifyDaoDiffSkipsZeroChangeAndChunksDeletes(baseDao: RocheDao) {
    val spy = RecordingRocheDao(baseDao)
    val feeds = baseDao.getAllFeeds()
    val tags = baseDao.getAllTags()
    val itemsWithOversizedSummary =
        baseDao.getAllItems().map { item ->
            if (item.id == "i-keep") item.copy(summary = "s".repeat(17_000)) else item
        }
    val itemTags = baseDao.getAllItemTags()

    // 0-change reconcile (even when input summary exceeds 16384 chars) performs 0 writes/deletes
    spy.resetCounts()
    spy.reconcileSnapshotDiff(feeds, tags, itemsWithOversizedSummary, itemTags)
    assertEquals(0, spy.totalWriteAndDeleteCalls)
    assertEquals(0, spy.fullClearCalls)

    // Seed 505 transient items, then reconcile back to the 3 surviving items -> 2 delete chunks
    val transientItems =
        (1..505).map { idx ->
            itemsWithOversizedSummary
                .first()
                .copy(
                    id = "temp-$idx",
                    url = "https://example.com/temp-$idx",
                )
        }
    baseDao.upsertItems(transientItems)
    spy.resetCounts()
    spy.reconcileSnapshotDiff(feeds, tags, itemsWithOversizedSummary, itemTags)
    assertEquals(listOf(500, 5), spy.deletedItemChunks.map { it.size })
    assertTrue(spy.upsertedItemIds.isEmpty())
    assertEquals(0, spy.fullClearCalls)
}

internal class RecordingRocheDao(private val delegate: RocheDao) : RocheDao by delegate {
    val upsertedFeedIds = mutableListOf<String>()
    val upsertedTagIds = mutableListOf<String>()
    val upsertedItemIds = mutableListOf<String>()
    val insertedItemTags = mutableListOf<ItemTagEntity>()
    val deletedItemChunks = mutableListOf<List<String>>()
    val deletedFeedChunks = mutableListOf<List<String>>()
    val deletedTagChunks = mutableListOf<List<String>>()
    val deletedItemTags = mutableListOf<ItemTagEntity>()
    var fullClearCalls = 0

    val totalWriteAndDeleteCalls: Int
        get() =
            upsertedFeedIds.size +
                upsertedTagIds.size +
                upsertedItemIds.size +
                insertedItemTags.size +
                deletedItemChunks.size +
                deletedFeedChunks.size +
                deletedTagChunks.size +
                deletedItemTags.size

    fun resetCounts() {
        upsertedFeedIds.clear()
        upsertedTagIds.clear()
        upsertedItemIds.clear()
        insertedItemTags.clear()
        deletedItemChunks.clear()
        deletedFeedChunks.clear()
        deletedTagChunks.clear()
        deletedItemTags.clear()
        fullClearCalls = 0
    }

    override suspend fun reconcileSnapshotDiff(
        feeds: List<FeedEntity>,
        tags: List<TagEntity>,
        items: List<ItemEntity>,
        itemTags: List<ItemTagEntity>,
    ) {
        super.reconcileSnapshotDiff(feeds, tags, items, itemTags)
    }

    override suspend fun upsertFeeds(feeds: List<FeedEntity>) {
        upsertedFeedIds += feeds.map { it.id }
        delegate.upsertFeeds(feeds)
    }

    override suspend fun upsertTags(tags: List<TagEntity>) {
        upsertedTagIds += tags.map { it.id }
        delegate.upsertTags(tags)
    }

    override suspend fun upsertItems(items: List<ItemEntity>) {
        upsertedItemIds += items.map { it.id }
        delegate.upsertItems(items)
    }

    override suspend fun insertItemTags(itemTags: List<ItemTagEntity>) {
        insertedItemTags += itemTags
        delegate.insertItemTags(itemTags)
    }

    override suspend fun deleteItemsByIds(ids: List<String>) {
        deletedItemChunks += ids
        delegate.deleteItemsByIds(ids)
    }

    override suspend fun deleteFeedsByIds(ids: List<String>) {
        deletedFeedChunks += ids
        delegate.deleteFeedsByIds(ids)
    }

    override suspend fun deleteTagsByIds(ids: List<String>) {
        deletedTagChunks += ids
        delegate.deleteTagsByIds(ids)
    }

    override suspend fun deleteItemTag(itemId: String, tagId: String) {
        deletedItemTags += ItemTagEntity(itemId = itemId, tagId = tagId)
        delegate.deleteItemTag(itemId, tagId)
    }

    override suspend fun clearAllItems() {
        fullClearCalls++
        delegate.clearAllItems()
    }

    override suspend fun clearAllFeeds() {
        fullClearCalls++
        delegate.clearAllFeeds()
    }

    override suspend fun clearAllTags() {
        fullClearCalls++
        delegate.clearAllTags()
    }

    override suspend fun clearAllItemTags() {
        fullClearCalls++
        delegate.clearAllItemTags()
    }
}
