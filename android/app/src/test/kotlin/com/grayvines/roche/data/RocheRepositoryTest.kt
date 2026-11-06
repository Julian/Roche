package com.grayvines.roche.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.SeedData
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RocheRepositoryTest {
    @TempDir lateinit var tempDir: File

    private lateinit var database: RocheDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: RocheRepository

    @BeforeEach
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<RocheDatabase>().setDriver(BundledSQLiteDriver()).build()
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "test_settings.preferences_pb") }
            )
        settings = SettingsRepository(dataStore)
        val apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") }
        repository =
            RocheRepository(database = database, apiClient = apiClient, settings = settings)
    }

    @AfterEach
    fun tearDown() {
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

        repository.restoreStatus(itemId = "item-1", status = previousStatus!!)
        val afterUndo = repository.snapshot.first().entries.first { it.id == "item-1" }
        assertFalse(afterUndo.isArchived)
        assertEquals(ItemStatus.UNREAD, afterUndo.status)
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
}
