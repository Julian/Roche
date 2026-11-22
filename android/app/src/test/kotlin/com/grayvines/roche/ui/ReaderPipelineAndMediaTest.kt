package com.grayvines.roche.ui

import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.grayvines.roche.core.PDF_MAGIC_BYTES
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.HttpTransport
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.RocheServerApi
import com.grayvines.roche.data.remote.VpnTransportCheck
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.media.BitmapLoader
import com.grayvines.roche.media.ITEMS_SUBDIR
import com.grayvines.roche.media.ImageDecoders
import com.grayvines.roche.media.MAX_PDF_PAGES
import com.grayvines.roche.media.MAX_RENDER_WIDTH_PX
import com.grayvines.roche.media.MAX_ZOOM_RENDER_WIDTH_PX
import com.grayvines.roche.media.PdfDocumentSession
import com.grayvines.roche.media.PdfDocumentStore
import com.grayvines.roche.media.PdfPageBackend
import com.grayvines.roche.media.RenderedPage
import com.grayvines.roche.media.SHARED_PDF_FILENAME
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.extractAllImageUrlsFromHtml
import com.grayvines.roche.model.extractFirstImageUrlFromHtml
import com.grayvines.roche.reader.ArticleBlock
import com.grayvines.roche.reader.ArticleBlockCache
import com.grayvines.roche.reader.hasVisibleHtmlText
import com.grayvines.roche.reader.parseHtmlArticleBlocks
import com.grayvines.roche.reader.parseHtmlArticleDocument
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ReaderPipelineAndMediaTest {
    @Test
    fun `single-pass AST normalizes inline tags and precomputes headings and table widths`() {
        val html =
            "<h2>Using <code>cargo</code> &amp; <var>x</var></h2>" +
                "<p>Press <kbd>Ctrl+C</kbd> to see <samp>ok</samp> and <code>done</code>.</p>" +
                "<pre><code>fn keep_raw() {}</code></pre>" +
                "<table><thead><tr><th>Metric</th><th>Description</th></tr></thead>" +
                "<tbody><tr><td><code>p99</code></td>" +
                "<td>Short<br/>Much longer second line value</td></tr></tbody></table>"

        val doc = parseHtmlArticleDocument(rawHtml = html, baseUrl = "https://example.com/post")

        assertEquals(listOf("Using cargo & x"), doc.outlineHeadings)
        assertEquals(listOf(0), doc.orderedHeadingBlockIndices)

        val heading = doc.blocks[0] as ArticleBlock.Heading
        assertEquals("Using cargo & x", heading.plainText)
        assertTrue("<tt>cargo</tt>" in heading.html)
        assertTrue("<em>x</em>" in heading.html)
        assertFalse("<code" in heading.html)
        assertFalse("<var" in heading.html)

        val paragraph = doc.blocks[1] as ArticleBlock.Paragraph
        assertTrue("<tt>Ctrl+C</tt>" in paragraph.html)
        assertTrue("<tt>ok</tt>" in paragraph.html)
        assertTrue("<tt>done</tt>" in paragraph.html)
        assertFalse("<kbd" in paragraph.html)
        assertFalse("<samp" in paragraph.html)

        val codeBlock = doc.blocks[2] as ArticleBlock.CodeBlock
        assertEquals("fn keep_raw() {}", codeBlock.code)

        val table = doc.blocks[3] as ArticleBlock.TableBlock
        assertEquals(listOf(6, 29), table.columnMaxChars)
        assertEquals(listOf(72, 227), computeTableColumnWidthsDp(table))
    }

    @Test
    fun `table parser caps extreme row and column counts and visible html helper handles entities`() {
        val wideRow = (1..40).joinToString("") { "<td>c$it</td>" }
        val manyRows = (1..250).joinToString("") { "<tr>$wideRow</tr>" }
        val blocks = parseHtmlArticleBlocks("<table><tbody>$manyRows</tbody></table>")
        val table = blocks.single() as ArticleBlock.TableBlock

        assertEquals(200, table.rows.size)
        assertEquals(24, table.rows.first().size)
        assertEquals(24, table.columnMaxChars.size)

        assertFalse(hasVisibleHtmlText(""))
        assertFalse(hasVisibleHtmlText("<br/><br/>"))
        assertFalse(hasVisibleHtmlText("<em>&nbsp;&#160;&#xA0;&#0160;&#32;&#x20;&ensp;</em>"))
        assertFalse(hasVisibleHtmlText("""<a title="1 > 0" href="https://example.com"></a>"""))
        assertFalse(hasVisibleHtmlText("<em"))
        assertTrue(hasVisibleHtmlText("< 5 <em></em>"))
        assertTrue(hasVisibleHtmlText("<strong>&amp;</strong>"))
        assertTrue(hasVisibleHtmlText("<tt>a</tt>"))

        // Multi-paragraph & figure-containing cells measure normalized line lengths
        val normalizedTable =
            parseHtmlArticleBlocks(
                    "<table><tbody><tr>" +
                        "<td><p>First</p><p>Second</p></td>" +
                        "<td><figure><figcaption>Very long stripped caption</figcaption></figure>OK</td>" +
                        "</tr></tbody></table>"
                )
                .single() as ArticleBlock.TableBlock
        assertEquals(listOf(6, 2), normalizedTable.columnMaxChars)

        // Inline image captions precompute plain text in the background pass
        val figureBlock =
            parseHtmlArticleBlocks(
                    """<figure><img src="https://example.com/chart.png" alt="Chart">""" +
                        """<figcaption>Figure <em>1</em>: Growth</figcaption></figure>"""
                )
                .single() as ArticleBlock.ImageBlock
        assertEquals("Figure 1: Growth", figureBlock.captionPlainText)
    }

    @Test
    fun `ArticleBlockCache serves identity and content hash tiers cleanly`() = runTest {
        val cache = ArticleBlockCache(maxEntries = 4)
        val html1 = "<p>Hello <code>world</code></p>"
        assertNull(cache.lookupFast(html1, "https://example.com"))

        val parsed1 = cache.getOrParse(html1, "https://example.com")
        assertSame(parsed1, cache.lookupFast(html1, "https://example.com"))
        assertNull(cache.lookupFast(html1, "https://other.example.com"))

        // Distinct String instance with identical content hits SHA-256 tier
        val htmlCopy = String(html1.toCharArray())
        val parsed2 = cache.getOrParse(htmlCopy, "https://example.com")
        assertSame(parsed1, parsed2)

        cache.clear()
        assertNull(cache.lookupFast(html1, "https://example.com"))
    }

    @Test
    fun `IMG_SRC_REGEX extracts quoted sources and stays linear on malformed unclosed tags`() {
        val goodHtml =
            """<p><img alt="Chart" src="https://example.com/a.png"><img src='/b.jpg'></p>"""
        assertEquals(
            "https://example.com/a.png",
            extractFirstImageUrlFromHtml(goodHtml, "https://example.com/post/"),
        )
        assertEquals(
            listOf("https://example.com/a.png", "https://example.com/b.jpg"),
            extractAllImageUrlsFromHtml(goodHtml, "https://example.com/post/"),
        )

        val adversarial = "<img ".repeat(20_000) + "<img alt='x' " + "foo='bar' ".repeat(2_000)
        val startNs = System.nanoTime()
        assertNull(extractFirstImageUrlFromHtml(adversarial, "https://example.com/"))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        assertTrue(elapsedMs < 1_000L, "IMG_SRC_REGEX took ${elapsedMs}ms on malformed HTML")
    }

    @Test
    fun `PdfDocumentSession enforces MAX_PDF_PAGES cap and high-res zoom caching`() = runTest {
        val renderedWidths = mutableListOf<Int>()
        val backend =
            object : PdfPageBackend<String> {
                override val totalPageCount: Int = 1_200

                override fun renderPage(
                    pageIndex: Int,
                    widthPx: Int,
                    maxHeightPx: Int,
                ): RenderedPage<String> {
                    renderedWidths.add(widthPx)
                    return RenderedPage(image = "page-$pageIndex-$widthPx", aspectRatio = 0.77f)
                }

                override fun close() = Unit
            }

        val session = PdfDocumentSession(backend = backend, canonicalPath = "/tmp/big.pdf")
        try {
            assertEquals(1_200, session.totalPageCount)
            assertEquals(MAX_PDF_PAGES, session.pageCount)
            assertTrue(session.isTruncated)
            assertNull(session.renderPageBitmap(pageIndex = MAX_PDF_PAGES, targetWidthPx = 1_000))

            val normal = session.renderPageBitmap(pageIndex = 0, targetWidthPx = 3_000)
            assertEquals("page-0-$MAX_RENDER_WIDTH_PX", normal?.image)
            assertEquals("page-0-$MAX_RENDER_WIDTH_PX", session.getCachedPage(0)?.image)

            val zoomed =
                session.renderPageBitmap(
                    pageIndex = 0,
                    targetWidthPx = 3_000,
                    allowHighResZoom = true,
                )
            assertEquals("page-0-$MAX_ZOOM_RENDER_WIDTH_PX", zoomed?.image)
            assertEquals(listOf(MAX_RENDER_WIDTH_PX, MAX_ZOOM_RENDER_WIDTH_PX), renderedWidths)
        } finally {
            session.closeAsync()
        }
    }

    @Test
    fun `PdfDocumentSession render watchdog times out and still closes backend once unblocked`() =
        runTest {
            val renderStarted = CountDownLatch(1)
            val releaseBlock = CountDownLatch(1)
            val closeCalled = CountDownLatch(1)
            val closeCount = java.util.concurrent.atomic.AtomicInteger(0)
            val backend =
                object : PdfPageBackend<String> {
                    override val totalPageCount: Int = 2

                    override fun renderPage(
                        pageIndex: Int,
                        widthPx: Int,
                        maxHeightPx: Int,
                    ): RenderedPage<String> {
                        renderStarted.countDown()
                        // Simulate native C++ PDFium ignoring Thread.interrupt()
                        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (releaseBlock.count > 0 && System.nanoTime() < deadlineNs) {
                            runCatching { releaseBlock.await(20, TimeUnit.MILLISECONDS) }
                        }
                        return RenderedPage(image = "late", aspectRatio = 0.77f)
                    }

                    override fun close() {
                        closeCount.incrementAndGet()
                        closeCalled.countDown()
                    }
                }

            val session =
                PdfDocumentSession(
                    backend = backend,
                    canonicalPath = "/tmp/hung.pdf",
                    renderTimeoutMs = 100L,
                    closeAwaitTimeoutMs = 80L,
                )
            try {
                val result = session.renderPageBitmap(pageIndex = 0, targetWidthPx = 900)
                assertNull(result)
                assertTrue(renderStarted.await(1, TimeUnit.SECONDS))

                // Close while native render is STILL blocked; must return promptly without leak
                session.closeAsync()
                assertEquals(0, closeCount.get())

                // Once slow native render finally returns, worker thread closes backend once
                releaseBlock.countDown()
                assertTrue(
                    closeCalled.await(2, TimeUnit.SECONDS),
                    "backend.close() did not run after hung render unblocked",
                )
                assertEquals(1, closeCount.get())
            } finally {
                releaseBlock.countDown()
            }
        }

    @Test
    fun `PdfDocumentStore locates valid cached PDF and rejects path traversal`() {
        val tempDir = Files.createTempDirectory("roche-pdf-store-test").toFile()
        try {
            val itemDir = File(File(tempDir, ITEMS_SUBDIR), "paper-42").apply { mkdirs() }
            val pdfFile = File(itemDir, SHARED_PDF_FILENAME)
            pdfFile.writeBytes(PDF_MAGIC_BYTES + "1.7\n%test".toByteArray())

            val store = PdfDocumentStore(cacheRoot = tempDir)
            val candidate = store.findCachedPdf("paper-42")
            assertNotNull(candidate)
            assertEquals(pdfFile.canonicalPath, candidate?.canonicalPath)
            assertNull(store.findCachedPdf("../paper-42"))
            assertNull(store.findCachedPdf("missing-entry"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `BitmapLoader clearFailedKey bypasses cooldown for manual inline image retry`() = runTest {
        var shouldSucceed = false
        var calls = 0
        val transport = HttpTransport {
            calls++
            if (shouldSucceed) {
                HttpResponse(statusCode = 200, body = "ok", bodyBytes = byteArrayOf(1, 2, 3))
            } else {
                HttpResponse(statusCode = 502, body = "")
            }
        }
        val tempDir = Files.createTempDirectory("roche-bitmap-retry-test").toFile()
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settingsRepo =
                SettingsRepository(
                    PreferenceDataStoreFactory.create(
                        scope = dataStoreScope,
                        produceFile = { File(tempDir, "retry_settings.preferences_pb") },
                    )
                )
            val loader =
                BitmapLoader(
                    serverApi = RocheServerApi(VpnTransportCheck { false }, transport),
                    publicWebFetcher = PublicWebFetcher(transport),
                    settings = settingsRepo,
                    decoders = ImageDecoders(decodeBytes = { "decoded-img" }, sizeOfKiB = { 8 }),
                    clockMs = { 10_000L },
                )

            val url = "https://example.com/diagram.png"
            assertNull(loader.loadInlineImage(url))
            assertEquals(1, calls)

            // Cooldown prevents automatic retry
            shouldSucceed = true
            assertNull(loader.loadInlineImage(url))
            assertEquals(1, calls)

            // Tap-to-retry clears failed key and fetches immediately
            loader.clearFailedKey(url)
            assertEquals("decoded-img", loader.loadInlineImage(url))
            assertEquals(2, calls)
        } finally {
            dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `lightbox zoom and double-tap math clamps scale and bounds`() {
        val initial = LightboxZoomTransform()
        val zoomed =
            toggleLightboxDoubleTapZoom(
                current = initial,
                tapX = 100f,
                tapY = 200f,
                containerWidthPx = 400f,
                containerHeightPx = 800f,
            )
        assertEquals(2.5f, zoomed.scale)
        assertTrue(zoomed.isZoomed)
        assertEquals(150f, zoomed.offsetX)
        assertEquals(300f, zoomed.offsetY)

        // Pinching beyond 4x clamps at 4x and clamps pan within bounds
        val overZoomed =
            applyLightboxGesture(
                current = zoomed,
                zoomChange = 3f,
                panX = 5_000f,
                panY = -5_000f,
                containerWidthPx = 400f,
                containerHeightPx = 800f,
            )
        assertEquals(4f, overZoomed.scale)
        assertEquals(600f, overZoomed.offsetX)
        assertEquals(-1_200f, overZoomed.offsetY)

        // Off-center pinch anchors zoom around the pinch centroid
        val centroidPinch =
            applyLightboxGesture(
                current = initial,
                zoomChange = 2f,
                panX = 0f,
                panY = 0f,
                containerWidthPx = 400f,
                containerHeightPx = 800f,
                centroidX = 300f,
                centroidY = 600f,
            )
        assertEquals(2f, centroidPinch.scale)
        assertEquals(-100f, centroidPinch.offsetX)
        assertEquals(-200f, centroidPinch.offsetY)

        // Double-tap while zoomed resets cleanly to 1x
        val reset =
            toggleLightboxDoubleTapZoom(
                current = overZoomed,
                tapX = 200f,
                tapY = 400f,
                containerWidthPx = 400f,
                containerHeightPx = 800f,
            )
        assertEquals(LightboxZoomTransform(), reset)
    }

    @Test
    fun `video thumbnail fallback, PDF detection, margins, and body index offsets are exact`() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            youtubeFallbackThumbnailUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"),
        )
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            youtubeFallbackThumbnailUrl("https://youtu.be/dQw4w9WgXcQ"),
        )
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            youtubeFallbackThumbnailUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ"),
        )
        assertNull(youtubeFallbackThumbnailUrl("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(youtubeFallbackThumbnailUrl("https://youtube.com/watch?v=short"))

        val arxivEntry = sampleEntry(url = "https://arxiv.org/abs/2501.01234")
        val directPdfEntry = sampleEntry(url = "https://example.com/report.PDF?download=1")
        val articleEntry = sampleEntry(url = "https://example.com/blog/post")
        assertTrue(isPdfLikeEntry(arxivEntry))
        assertTrue(isPdfLikeEntry(directPdfEntry))
        assertFalse(isPdfLikeEntry(articleEntry))

        assertEquals(8.dp, resolveReaderHorizontalPadding(maxWidth = 390.dp, isPdf = true))
        assertEquals(18.dp, resolveReaderHorizontalPadding(maxWidth = 390.dp, isPdf = false))
        assertEquals(16.dp, resolveReaderHorizontalPadding(maxWidth = 600.dp, isPdf = true))
        assertEquals(40.dp, resolveReaderHorizontalPadding(maxWidth = 800.dp, isPdf = true))

        assertEquals(
            0,
            computeReaderBodyItemCount(
                blocksCount = 0,
                plainCount = 0,
                hasPdfPages = false,
                showUncachedPdfCard = true,
                isAwaitingBody = false,
            ),
        )
        assertEquals(
            5,
            computeReaderBodyItemCount(
                blocksCount = 5,
                plainCount = 2,
                hasPdfPages = true,
                showUncachedPdfCard = false,
                isAwaitingBody = false,
            ),
        )
    }

    private fun sampleEntry(url: String): InboxEntry =
        InboxEntry(
            id = "e-1",
            title = "Sample Entry",
            url = url,
            feedId = null,
            feedTitle = "Saved",
            author = null,
            summary = null,
            contentText = null,
            contentHtml = null,
            contentType = ContentType.ARTICLE,
            status = ItemStatus.UNREAD,
            isFavorite = false,
            isCachedOffline = false,
            thumbnailMediaId = null,
            duration = null,
            publishedAt = "2026-11-20T00:00:00Z",
            createdAt = "2026-11-20T00:00:00Z",
            readAt = null,
            readProgress = 0.0,
            tags = emptyList(),
        )
}
