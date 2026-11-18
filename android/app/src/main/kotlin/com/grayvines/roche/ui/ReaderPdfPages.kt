package com.grayvines.roche.ui

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

val LocalOfflineCacheDir = staticCompositionLocalOf<File?> { null }

internal class PdfRendererSession(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : AutoCloseable {
    private val renderMutex = Mutex()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    suspend fun renderPageBitmap(pageIndex: Int, targetWidthPx: Int): RenderedPdfPage? =
        withContext(Dispatchers.IO) {
            renderMutex.withLock {
                if (closed || pageIndex !in 0 until pageCount) {
                    null
                } else {
                    renderSinglePageLocked(pageIndex, targetWidthPx)
                }
            }
        }

    private fun renderSinglePageLocked(pageIndex: Int, targetWidthPx: Int): RenderedPdfPage? =
        runCatching {
            renderer.openPage(pageIndex).use { page ->
                val rawW = page.width.coerceAtLeast(1)
                val rawH = page.height.coerceAtLeast(1)
                val widthPx = targetWidthPx.coerceIn(MIN_RENDER_WIDTH_PX, MAX_RENDER_WIDTH_PX)
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

    override fun close() {
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

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
        val opened =
            withContext(Dispatchers.IO) {
                findCachedPdfFileForEntry(cacheRoot, entryId)?.let(::openPdfRendererSession)
            }
        val previous = session
        session = opened
        if (previous !== opened) {
            previous?.close()
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
    val rendered by
        produceState<RenderedPdfPage?>(initialValue = null, session, pageIndex, targetWidthPx) {
            value = session.renderPageBitmap(pageIndex, targetWidthPx)
        }
    val shape = RoundedCornerShape(6.dp)
    val aspect = rendered?.aspectRatio ?: DEFAULT_LETTER_ASPECT

    Column(
        modifier = Modifier.fillMaxWidth().testTag("reader_pdf_page_${pageIndex + 1}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(aspect)
                    .clip(shape)
                    .background(Color.White)
                    .border(1.dp, colors.border, shape),
            contentAlignment = Alignment.Center,
        ) {
            val pageImg = rendered
            if (pageImg != null) {
                Image(
                    bitmap = pageImg.bitmap,
                    contentDescription = "PDF page ${pageIndex + 1} of ${session.pageCount}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "Rendering page ${pageIndex + 1}…",
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            text = "Page ${pageIndex + 1} of ${session.pageCount}",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            modifier = Modifier.align(Alignment.End).padding(end = 2.dp),
        )
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

private fun openPdfRendererSession(file: File): PdfRendererSession? = runCatching {
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
        PdfRendererSession(pfd, renderer)
    }
}
    .getOrNull()

private val PDF_MAGIC_BYTES = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // "%PDF-"
private const val DEFAULT_LETTER_ASPECT = 0.7727f
private const val MIN_ASPECT = 0.35f
private const val MAX_ASPECT = 2.5f
private const val PDF_SUPERSAMPLE_FACTOR = 1.6f
private const val MIN_RENDER_WIDTH_PX = 720
private const val MAX_RENDER_WIDTH_PX = 2_400
private const val MAX_RENDER_HEIGHT_PX = 4_096
