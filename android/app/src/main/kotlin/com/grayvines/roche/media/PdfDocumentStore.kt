package com.grayvines.roche.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.createBitmap
import com.grayvines.roche.core.PDF_MAGIC_BYTES
import com.grayvines.roche.core.hasPdfMagicHeader
import com.grayvines.roche.core.resolveSafeChildFile
import com.grayvines.roche.core.sanitizeCacheFileName
import java.io.File
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val ITEMS_SUBDIR = "items"
internal const val SHARED_PDF_FILENAME = "shared_document.pdf"

internal val pdfTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal data class RenderedPdfPage(
    val bitmap: Bitmap,
    val aspectRatio: Float,
)

internal data class CachedRenderedPdfPage(
    val widthPx: Int,
    val page: RenderedPdfPage,
)

internal class PdfRendererSession(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val canonicalPath: String = "",
) : AutoCloseable {
    private val renderMutex = Mutex()
    private val closeRequested = AtomicBoolean(false)
    @Volatile private var closed = false
    private val pageCache = LruCache<Int, CachedRenderedPdfPage>(MAX_CACHED_PDF_PAGES)

    val pageCount: Int = renderer.pageCount

    suspend fun renderPageBitmap(pageIndex: Int, targetWidthPx: Int): RenderedPdfPage? =
        withContext(Dispatchers.IO) {
            if (closed || closeRequested.get()) {
                null
            } else {
                val clampedWidthPx =
                    targetWidthPx.coerceIn(MIN_RENDER_WIDTH_PX, MAX_RENDER_WIDTH_PX)
                renderMutex.withLock { getOrRenderPageLocked(pageIndex, clampedWidthPx) }
            }
        }

    private fun getOrRenderPageLocked(pageIndex: Int, clampedWidthPx: Int): RenderedPdfPage? {
        if (closed || closeRequested.get() || pageIndex !in 0 until pageCount) return null
        val cached = pageCache.get(pageIndex)
        if (cached != null && cached.widthPx == clampedWidthPx) return cached.page
        return renderSinglePageLocked(pageIndex, clampedWidthPx)?.also { rendered ->
            pageCache.put(pageIndex, CachedRenderedPdfPage(clampedWidthPx, rendered))
        }
    }

    private fun renderSinglePageLocked(pageIndex: Int, widthPx: Int): RenderedPdfPage? =
        runCatching {
            renderer.openPage(pageIndex).use { page ->
                val rawW = page.width.coerceAtLeast(1)
                val rawH = page.height.coerceAtLeast(1)
                val heightPx =
                    (widthPx.toDouble() * rawH.toDouble() / rawW.toDouble())
                        .roundToInt()
                        .coerceIn(1, MAX_RENDER_HEIGHT_PX)
                val bitmap = createBitmap(widthPx, heightPx)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                RenderedPdfPage(
                    bitmap = bitmap,
                    aspectRatio =
                        (rawW.toFloat() / rawH.toFloat()).coerceIn(MIN_ASPECT, MAX_ASPECT),
                )
            }
        }
        .getOrNull()

    suspend fun closeAsync() {
        closeRequested.set(true)
        withContext(Dispatchers.IO) {
            renderMutex.withLock {
                if (!closed) {
                    closed = true
                    pageCache.evictAll()
                    runCatching { renderer.close() }
                    runCatching { descriptor.close() }
                }
            }
        }
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        pdfTeardownScope.launch { closeAsync() }
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

private const val MIN_ASPECT = 0.35f
private const val MAX_ASPECT = 2.5f
private const val MIN_RENDER_WIDTH_PX = 720
private const val MAX_RENDER_WIDTH_PX = 1_600
private const val MAX_RENDER_HEIGHT_PX = 2_800
private const val MAX_CACHED_PDF_PAGES = 6
