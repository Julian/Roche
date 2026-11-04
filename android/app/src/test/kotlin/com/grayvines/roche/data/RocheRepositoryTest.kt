package com.grayvines.roche.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.data.local.RocheDatabase
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

    @Test
    fun `ensureSeeded populates initial items feeds and tags`() = runTest {
        repository.ensureSeeded()
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
        repository.ensureSeeded()
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
        repository.ensureSeeded()

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
        repository.ensureSeeded()
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
            repository.ensureSeeded()
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
    fun `syncWithServer replaces demo seed rows populates item tags and strips html in reader`() =
        runTest {
            repository.ensureSeeded()
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
            repository.ensureSeeded()
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
}
