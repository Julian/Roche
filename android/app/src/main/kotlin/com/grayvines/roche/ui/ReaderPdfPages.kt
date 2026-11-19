@file:Suppress("MatchingDeclarationName")

package com.grayvines.roche.ui

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
import com.grayvines.roche.media.PdfRendererSession
import com.grayvines.roche.media.findCachedPdfFileForEntry
import com.grayvines.roche.media.openPdfRendererSession
import com.grayvines.roche.media.openSessionCancellably
import com.grayvines.roche.ui.theme.LightRocheColors
import com.grayvines.roche.ui.theme.LocalRocheColors
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalOfflineCacheDir = staticCompositionLocalOf<File?> { null }

internal sealed interface PdfPageRenderState {
    data object Loading : PdfPageRenderState

    data class Success(val bitmap: ImageBitmap, val aspectRatio: Float) : PdfPageRenderState

    data object Error : PdfPageRenderState
}

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
                    PdfPageRenderState.Success(
                        bitmap = rendered.bitmap.asImageBitmap(),
                        aspectRatio = rendered.aspectRatio,
                    )
                } else {
                    PdfPageRenderState.Error
                }
        }
    val aspect = (renderState as? PdfPageRenderState.Success)?.aspectRatio ?: DEFAULT_LETTER_ASPECT

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
                    bitmap = renderState.bitmap,
                    contentDescription = "PDF page ${pageIndex + 1} of $pageCount",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            PdfPageRenderState.Loading -> {
                Text(
                    text = "Rendering page ${pageIndex + 1}…",
                    color = LightRocheColors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            PdfPageRenderState.Error -> {
                Text(
                    text = "Unable to render page ${pageIndex + 1}",
                    color = LightRocheColors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private const val DEFAULT_LETTER_ASPECT = 0.7727f
private const val PDF_SUPERSAMPLE_FACTOR = 1.25f
