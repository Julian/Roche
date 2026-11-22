@file:Suppress("MatchingDeclarationName")

package com.grayvines.roche.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.collection.LruCache
import androidx.core.graphics.createBitmap
import com.grayvines.roche.core.PDF_MAGIC_BYTES
import com.grayvines.roche.core.hasPdfMagicHeader
import com.grayvines.roche.core.resolveSafeChildFile
import com.grayvines.roche.core.sanitizeCacheFileName
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal const val ITEMS_SUBDIR = "items"
internal const val SHARED_PDF_FILENAME = "shared_document.pdf"
internal const val MAX_PDF_PAGES = 500
internal const val PDF_PAGE_RENDER_TIMEOUT_MS = 10_000L

internal val pdfTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal data class CachedPdfCandidate(
    val file: File,
    val canonicalPath: String,
)

internal data class RenderedPage<T : Any>(
    val image: T,
    val aspectRatio: Float,
)

internal typealias RenderedPdfPage = RenderedPage<Bitmap>

internal data class CachedRenderedPage<T : Any>(
    val widthPx: Int,
    val page: RenderedPage<T>,
)

internal interface PdfPageBackend<T : Any> : AutoCloseable {
    val totalPageCount: Int

    fun renderPage(pageIndex: Int, widthPx: Int, maxHeightPx: Int): RenderedPage<T>?
}

class PdfDocumentStore(val cacheRoot: File?) {
    internal fun findCachedPdf(entryId: String): CachedPdfCandidate? =
        findCachedPdfFileForEntry(cacheRoot, entryId)?.let { file ->
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            CachedPdfCandidate(file = file, canonicalPath = canonical)
        }

    internal fun openSession(candidate: CachedPdfCandidate): PdfRendererSession? =
        openPdfRendererSession(candidate.file, candidate.canonicalPath)
}

internal open class PdfDocumentSession<T : Any>(
    private val backend: PdfPageBackend<T>,
    val canonicalPath: String = "",
    private val maxPages: Int = MAX_PDF_PAGES,
    private val renderTimeoutMs: Long = PDF_PAGE_RENDER_TIMEOUT_MS,
    private val closeAwaitTimeoutMs: Long = CLOSE_AWAIT_TIMEOUT_MS,
) : AutoCloseable {
    private val closeRequested = AtomicBoolean(false)
    private val backendClosed = AtomicBoolean(false)
    @Volatile private var closed = false
    private val pageCache = LruCache<Int, CachedRenderedPage<T>>(MAX_CACHED_PDF_PAGES)
    private val zoomPageCache = LruCache<Int, CachedRenderedPage<T>>(MAX_CACHED_ZOOM_PDF_PAGES)
    private val renderExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "roche-pdf-render").apply { isDaemon = true }
    }

    val totalPageCount: Int = backend.totalPageCount.coerceAtLeast(0)
    val pageCount: Int = totalPageCount.coerceAtMost(maxPages.coerceAtLeast(1))
    val isTruncated: Boolean
        get() = totalPageCount > pageCount

    fun getCachedPage(pageIndex: Int): RenderedPage<T>? {
        if (closed || closeRequested.get() || pageIndex !in 0 until pageCount) return null
        return synchronized(zoomPageCache) { zoomPageCache[pageIndex]?.page }
            ?: synchronized(pageCache) { pageCache[pageIndex]?.page }
    }

    suspend fun renderPageBitmap(
        pageIndex: Int,
        targetWidthPx: Int,
        allowHighResZoom: Boolean = false,
    ): RenderedPage<T>? {
        if (closed || closeRequested.get() || pageIndex !in 0 until pageCount) return null
        val maxWidth = if (allowHighResZoom) MAX_ZOOM_RENDER_WIDTH_PX else MAX_RENDER_WIDTH_PX
        val maxHeight = if (allowHighResZoom) MAX_ZOOM_RENDER_HEIGHT_PX else MAX_RENDER_HEIGHT_PX
        val clampedWidthPx = targetWidthPx.coerceIn(MIN_RENDER_WIDTH_PX, maxWidth)
        val cache = if (allowHighResZoom) zoomPageCache else pageCache
        synchronized(cache) { cache[pageIndex]?.takeIf { it.widthPx == clampedWidthPx }?.page }
            ?.let {
                return it
            }
        val rendered = renderPageWithWatchdog(pageIndex, clampedWidthPx, maxHeight)
        if (rendered != null) {
            synchronized(cache) {
                if (!closed && !closeRequested.get()) {
                    cache.put(pageIndex, CachedRenderedPage(clampedWidthPx, rendered))
                }
            }
        }
        return rendered
    }

    private suspend fun renderPageWithWatchdog(
        pageIndex: Int,
        widthPx: Int,
        maxHeightPx: Int,
    ): RenderedPage<T>? {
        val future = submitRenderTask(pageIndex, widthPx, maxHeightPx) ?: return null
        return awaitRenderFuture(future)
    }

    private fun submitRenderTask(
        pageIndex: Int,
        widthPx: Int,
        maxHeightPx: Int,
    ): Future<RenderedPage<T>?>? = runCatching {
        renderExecutor.submit<RenderedPage<T>?> {
            if (closed || closeRequested.get()) {
                closeBackendOnWorkerThread()
                null
            } else {
                try {
                    runCatching { backend.renderPage(pageIndex, widthPx, maxHeightPx) }.getOrNull()
                } finally {
                    if (closeRequested.get()) {
                        closeBackendOnWorkerThread()
                    }
                }
            }
        }
    }
        .getOrNull()

    private suspend fun awaitRenderFuture(future: Future<RenderedPage<T>?>): RenderedPage<T>? =
        try {
            runInterruptible(Dispatchers.IO) { future.get(renderTimeoutMs, TimeUnit.MILLISECONDS) }
        } catch (ce: CancellationException) {
            future.cancel(true)
            throw ce
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            null
        }

    suspend fun closeAsync() {
        closeRequested.set(true)
        evictCaches()
        val closeFuture = runCatching {
            renderExecutor.submit { closeBackendOnWorkerThread() }
        }
            .getOrNull()
        renderExecutor.shutdown()
        if (closeFuture != null) {
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    closeFuture.get(closeAwaitTimeoutMs, TimeUnit.MILLISECONDS)
                }
            }
        }
        closed = true
        evictCaches()
    }

    private fun closeBackendOnWorkerThread() {
        if (backendClosed.compareAndSet(false, true)) {
            closed = true
            evictCaches()
            runCatching { backend.close() }
        }
    }

    private fun evictCaches() {
        synchronized(pageCache) { pageCache.evictAll() }
        synchronized(zoomPageCache) { zoomPageCache.evictAll() }
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        pdfTeardownScope.launch { closeAsync() }
    }
}

internal class PdfRendererSession(
    descriptor: ParcelFileDescriptor,
    renderer: PdfRenderer,
    canonicalPath: String = "",
    maxPages: Int = MAX_PDF_PAGES,
    renderTimeoutMs: Long = PDF_PAGE_RENDER_TIMEOUT_MS,
) :
    PdfDocumentSession<Bitmap>(
        backend = AndroidPdfPageBackend(descriptor = descriptor, renderer = renderer),
        canonicalPath = canonicalPath,
        maxPages = maxPages,
        renderTimeoutMs = renderTimeoutMs,
    )

private class AndroidPdfPageBackend(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : PdfPageBackend<Bitmap> {
    override val totalPageCount: Int = renderer.pageCount

    override fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        maxHeightPx: Int,
    ): RenderedPage<Bitmap>? = runCatching {
        renderer.openPage(pageIndex).use { page ->
            val rawW = page.width.coerceAtLeast(1)
            val rawH = page.height.coerceAtLeast(1)
            val heightPx =
                (widthPx.toDouble() * rawH.toDouble() / rawW.toDouble())
                    .roundToInt()
                    .coerceIn(1, maxHeightPx)
            val bitmap = createBitmap(widthPx, heightPx)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            RenderedPage(
                image = bitmap,
                aspectRatio = (rawW.toFloat() / rawH.toFloat()).coerceIn(MIN_ASPECT, MAX_ASPECT),
            )
        }
    }
        .getOrNull()

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

internal suspend fun <T : AutoCloseable> openSessionCancellably(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    openOnIo: () -> T?,
    publishOnCaller: (T?) -> Unit,
) {
    val openedRef = AtomicReference<T?>(null)
    try {
        val opened =
            withContext(ioDispatcher) {
                val created = openOnIo()
                if (!coroutineContext.isActive) {
                    created?.close()
                    null
                } else {
                    openedRef.set(created)
                    created
                }
            }
        openedRef.set(null)
        publishOnCaller(opened)
    } catch (ce: CancellationException) {
        openedRef.getAndSet(null)?.close()
        throw ce
    }
}

internal fun findCachedPdfFileForEntry(cacheRoot: File?, entryId: String): File? {
    val trimmedId = entryId.trim()
    if (cacheRoot == null || trimmedId.isEmpty()) return null
    val safeId = runCatching { sanitizeCacheFileName(trimmedId) }.getOrNull() ?: return null
    if (safeId != trimmedId) return null
    val itemsDir = File(cacheRoot, ITEMS_SUBDIR)
    val itemDir = runCatching { resolveSafeChildFile(itemsDir, safeId) }.getOrNull() ?: return null
    if (!itemDir.isDirectory) return null
    val sharedPdf = File(itemDir, SHARED_PDF_FILENAME)
    return if (isValidPdfFile(sharedPdf)) {
        sharedPdf
    } else {
        itemDir.listFiles().orEmpty().firstOrNull { file ->
            file.isFile && !file.name.endsWith(".tmp", ignoreCase = true) && isValidPdfFile(file)
        }
    }
}

internal fun openPdfRendererSession(file: File, canonicalPath: String): PdfRendererSession? =
    runCatching {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = runCatching {
            PdfRenderer(pfd)
        }
            .onFailure { runCatching { pfd.close() } }
            .getOrThrow()
        if (renderer.pageCount <= 0) {
            renderer.close()
            pfd.close()
            null
        } else {
            PdfRendererSession(pfd, renderer, canonicalPath)
        }
    }
    .getOrNull()

private fun isValidPdfFile(file: File): Boolean {
    if (!file.isFile || file.length() < PDF_MAGIC_BYTES.size) return false
    return runCatching {
            file.inputStream().use { stream ->
                val header = ByteArray(PDF_MAGIC_BYTES.size)
                val read = stream.read(header)
                hasPdfMagicHeader(header, read)
            }
        }
        .getOrDefault(false)
}

internal const val MIN_RENDER_WIDTH_PX = 720
internal const val MAX_RENDER_WIDTH_PX = 1_600
internal const val MAX_RENDER_HEIGHT_PX = 2_800
internal const val MAX_ZOOM_RENDER_WIDTH_PX = 2_400
internal const val MAX_ZOOM_RENDER_HEIGHT_PX = 4_200
private const val MIN_ASPECT = 0.35f
private const val MAX_ASPECT = 2.5f
private const val MAX_CACHED_PDF_PAGES = 6
private const val MAX_CACHED_ZOOM_PDF_PAGES = 3
private const val CLOSE_AWAIT_TIMEOUT_MS = 2_000L
