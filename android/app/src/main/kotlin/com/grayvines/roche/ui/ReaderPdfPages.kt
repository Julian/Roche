package com.grayvines.roche.ui

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import com.grayvines.roche.ui.theme.LocalRocheColors
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

val LocalOfflineCacheDir = staticCompositionLocalOf<File?> { null }

private val pdfTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal sealed interface PdfPageRenderState {
    data object Loading : PdfPageRenderState

    data class Success(val page: RenderedPdfPage) : PdfPageRenderState

    data object Error : PdfPageRenderState
}

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
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                RenderedPdfPage(
                    bitmap = bitmap.asImageBitmap(),
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

private data class CachedRenderedPdfPage(
    val widthPx: Int,
    val page: RenderedPdfPage,
)

internal data class RenderedPdfPage(
    val bitmap: ImageBitmap,
    val aspectRatio: Float,
)

@Composable
internal fun rememberCachedPdfSession(
    entryId: String,
    isCachedOffline: Boolean,
    url: String,
): PdfRendererSession? {
    val cacheRoot = LocalOfflineCacheDir.current
    var session by remember(cacheRoot, entryId) { mutableStateOf<PdfRendererSession?>(null) }

    LaunchedEffect(cacheRoot, entryId, isCachedOffline, url) {
        val candidate =
            withContext(Dispatchers.IO) {
                findCachedPdfFileForEntry(cacheRoot, entryId)?.let { file ->
                    val canonical = runCatching {
                        file.canonicalPath
                    }
                        .getOrDefault(file.absolutePath)
                    file to canonical
                }
            }
        val currentSession = session
        val isSameSession =
            candidate != null &&
                currentSession != null &&
                currentSession.canonicalPath == candidate.second
        if (!isSameSession) {
            if (candidate != null) {
                openSessionCancellably(
                    openOnIo = { openPdfRendererSession(candidate.first, candidate.second) },
                    publishOnCaller = { opened ->
                        val previous = session
                        session = opened
                        if (previous !== opened) {
                            previous?.close()
                        }
                    },
                )
            } else {
                val previous = session
                session = null
                previous?.close()
            }
        }
    }
    DisposableEffect(cacheRoot, entryId) {
        onDispose {
            session?.close()
            session = null
        }
    }
    return session
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

@Composable
internal fun PdfPageCardItem(
    session: PdfRendererSession,
    pageIndex: Int,
) {
    val colors = LocalRocheColors.current
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val targetWidthPx =
        remember(containerWidthPx) {
            (containerWidthPx * PDF_SUPERSAMPLE_FACTOR).roundToInt()
        }
    val renderState by
        produceState<PdfPageRenderState>(
            initialValue = PdfPageRenderState.Loading,
            session,
            pageIndex,
            targetWidthPx,
        ) {
            value = PdfPageRenderState.Loading
            val rendered = session.renderPageBitmap(pageIndex, targetWidthPx)
            value =
                if (rendered != null) {
                    PdfPageRenderState.Success(rendered)
                } else {
                    PdfPageRenderState.Error
                }
        }
    val aspect =
        (renderState as? PdfPageRenderState.Success)?.page?.aspectRatio ?: DEFAULT_LETTER_ASPECT

    Column(
        modifier = Modifier.fillMaxWidth().testTag("reader_pdf_page_${pageIndex + 1}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PdfPageSurface(
            renderState = renderState,
            pageIndex = pageIndex,
            pageCount = session.pageCount,
            aspect = aspect,
        )
        Text(
            text = "Page ${pageIndex + 1} of ${session.pageCount}",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            modifier = Modifier.align(Alignment.End).padding(end = 2.dp),
        )
    }
}

@Composable
private fun PdfPageSurface(
    renderState: PdfPageRenderState,
    pageIndex: Int,
    pageCount: Int,
    aspect: Float,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .aspectRatio(aspect)
                .clip(shape)
                .background(Color.White)
                .border(1.dp, colors.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        when (renderState) {
            is PdfPageRenderState.Success -> {
                Image(
                    bitmap = renderState.page.bitmap,
                    contentDescription = "PDF page ${pageIndex + 1} of $pageCount",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            PdfPageRenderState.Loading -> {
                Text(
                    text = "Rendering page ${pageIndex + 1}…",
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            PdfPageRenderState.Error -> {
                Text(
                    text = "Unable to render page ${pageIndex + 1}",
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

internal fun findCachedPdfFileForEntry(cacheRoot: File?, entryId: String): File? {
    if (cacheRoot == null || entryId.isBlank()) return null
    val itemsDir = File(cacheRoot, "items")
    val itemDir = File(itemsDir, entryId)
    val safeCanonical = runCatching {
        itemDir.canonicalPath.startsWith(itemsDir.canonicalPath + File.separator)
    }
        .getOrDefault(false)
    if (!safeCanonical || !itemDir.isDirectory) return null
    val sharedPdf = File(itemDir, "shared_document.pdf")
    if (isValidPdfFile(sharedPdf)) return sharedPdf
    return itemDir.listFiles().orEmpty().firstOrNull { file ->
        file.isFile && !file.name.endsWith(".tmp", ignoreCase = true) && isValidPdfFile(file)
    }
}

private fun isValidPdfFile(file: File): Boolean {
    if (!file.isFile || file.length() < PDF_MAGIC_BYTES.size) return false
    return runCatching {
            file.inputStream().use { stream ->
                val header = ByteArray(PDF_MAGIC_BYTES.size)
                val read = stream.read(header)
                read == PDF_MAGIC_BYTES.size && header.contentEquals(PDF_MAGIC_BYTES)
            }
        }
        .getOrDefault(false)
}

private fun openPdfRendererSession(file: File, canonicalPath: String): PdfRendererSession? =
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

private val PDF_MAGIC_BYTES = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // "%PDF-"
private const val DEFAULT_LETTER_ASPECT = 0.7727f
private const val MIN_ASPECT = 0.35f
private const val MAX_ASPECT = 2.5f
private const val PDF_SUPERSAMPLE_FACTOR = 1.25f
private const val MIN_RENDER_WIDTH_PX = 720
private const val MAX_RENDER_WIDTH_PX = 1_600
private const val MAX_RENDER_HEIGHT_PX = 2_800
private const val MAX_CACHED_PDF_PAGES = 6
