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
