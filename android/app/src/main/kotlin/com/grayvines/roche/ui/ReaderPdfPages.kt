@file:Suppress("MatchingDeclarationName", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.media.PdfDocumentStore
import com.grayvines.roche.media.PdfRendererSession
import com.grayvines.roche.media.openSessionCancellably
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.theme.LightRocheColors
import com.grayvines.roche.ui.theme.LocalRocheColors
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalOfflineCacheDir = staticCompositionLocalOf<File?> { null }
val LocalPdfDocumentStore = staticCompositionLocalOf<PdfDocumentStore?> { null }

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
    isDownloadingOffline: Boolean = false,
): PdfRendererSession? {
    val injectedStore = LocalPdfDocumentStore.current
    val fallbackCacheDir = LocalOfflineCacheDir.current
    val store =
        remember(injectedStore, fallbackCacheDir) {
            injectedStore ?: fallbackCacheDir?.let { PdfDocumentStore(it) }
        }
    var session by remember(store, entryId) { mutableStateOf<PdfRendererSession?>(null) }

    LaunchedEffect(store, entryId, isCachedOffline, url, isDownloadingOffline) {
        syncPdfSession(
            store = store,
            entryId = entryId,
            currentSession = session,
            publishSession = { next ->
                val previous = session
                session = next
                if (previous !== next) {
                    previous?.close()
                }
            },
        )
    }
    DisposableEffect(store, entryId) {
        onDispose {
            session?.close()
            session = null
        }
    }
    return session
}

private suspend fun syncPdfSession(
    store: PdfDocumentStore?,
    entryId: String,
    currentSession: PdfRendererSession?,
    publishSession: (PdfRendererSession?) -> Unit,
) {
    if (store == null) {
        publishSession(null)
        return
    }
    val candidate = withContext(Dispatchers.IO) { store.findCachedPdf(entryId) }
    val isSameSession =
        candidate != null &&
            currentSession != null &&
            currentSession.canonicalPath == candidate.canonicalPath
    if (isSameSession) return
    if (candidate != null) {
        openSessionCancellably(
            openOnIo = { store.openSession(candidate) },
            publishOnCaller = publishSession,
        )
    } else {
        publishSession(null)
    }
}

@Composable
private fun rememberPdfPageRenderState(
    session: PdfRendererSession,
    pageIndex: Int,
): PdfPageRenderState {
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val targetWidthPx =
        remember(containerWidthPx) { (containerWidthPx * PDF_SUPERSAMPLE_FACTOR).roundToInt() }
    val initialRenderState =
        remember(session, pageIndex) {
            session.getCachedPage(pageIndex)?.let { page ->
                PdfPageRenderState.Success(
                    bitmap = page.image.asImageBitmap(),
                    aspectRatio = page.aspectRatio,
                )
            } ?: PdfPageRenderState.Loading
        }
    val renderState by
        produceState<PdfPageRenderState>(
            initialValue = initialRenderState,
            session,
            pageIndex,
            targetWidthPx,
        ) {
            value = initialRenderState
            val rendered = session.renderPageBitmap(pageIndex, targetWidthPx)
            value =
                when {
                    rendered != null ->
                        PdfPageRenderState.Success(
                            bitmap = rendered.image.asImageBitmap(),
                            aspectRatio = rendered.aspectRatio,
                        )
                    initialRenderState is PdfPageRenderState.Success -> initialRenderState
                    else -> PdfPageRenderState.Error
                }
        }
    return renderState
}

@Composable
internal fun PdfPageCardItem(
    session: PdfRendererSession,
    pageIndex: Int,
    onZoomPage: ((Int) -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    val renderState = rememberPdfPageRenderState(session = session, pageIndex = pageIndex)
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
            onZoomPage = onZoomPage,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (pageIndex == 0 && onZoomPage != null) "Tap page to zoom" else "",
                color = colors.textMuted,
                fontSize = 10.5.sp,
            )
            Text(
                text = "Page ${pageIndex + 1} of ${session.pageCount}",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
            )
        }
    }
}

@Composable
private fun PdfPageSurface(
    renderState: PdfPageRenderState,
    pageIndex: Int,
    pageCount: Int,
    aspect: Float,
    onZoomPage: ((Int) -> Unit)?,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(6.dp)
    val canZoom = renderState is PdfPageRenderState.Success && onZoomPage != null
    val clickModifier =
        if (canZoom) {
            Modifier.clickable { onZoomPage.invoke(pageIndex) }
                .semantics {
                    role = Role.Button
                    contentDescription =
                        "PDF page ${pageIndex + 1} of $pageCount. Tap to zoom fullscreen."
                }
        } else {
            Modifier
        }
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .aspectRatio(aspect)
                .clip(shape)
                .background(Color.White)
                .border(1.dp, colors.border, shape)
                .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        when (renderState) {
            is PdfPageRenderState.Success -> {
                Image(
                    bitmap = renderState.bitmap,
                    contentDescription =
                        if (canZoom) {
                            null
                        } else {
                            "PDF page ${pageIndex + 1} of $pageCount"
                        },
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

@Composable
internal fun PdfPageJumpBar(
    currentPageIndex: Int,
    pageCount: Int,
    onJumpToPage: (Int) -> Unit,
    onZoomCurrentPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    val colors = LocalRocheColors.current
    val lastIndex = pageCount - 1
    val clampedCurrent = currentPageIndex.coerceIn(0, lastIndex)
    var previewFraction by remember(pageCount) { mutableStateOf<Float?>(null) }
    val activePage =
        previewFraction?.let { (it * lastIndex).roundToInt().coerceIn(0, lastIndex) }
            ?: clampedCurrent
    val currentFraction = clampedCurrent.toFloat() / lastIndex.toFloat()

    Column(
        modifier = modifier.fillMaxWidth().background(colors.surface).testTag("reader_pdf_jump_bar")
    ) {
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${activePage + 1} / $pageCount",
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            )
            RocheInteractiveSlider(
                fraction = currentFraction,
                onDragPreviewChange = { previewFraction = it },
                onFractionChangeFinished = { frac ->
                    previewFraction = null
                    onJumpToPage((frac * lastIndex).roundToInt().coerceIn(0, lastIndex))
                },
                contentDescription = "PDF page scrubber",
                stateDescription = "Page ${activePage + 1} of $pageCount",
                modifier = Modifier.weight(1f).testTag("reader_pdf_page_slider"),
            )
            PdfZoomActionButton(
                activePage = activePage,
                onClick = { onZoomCurrentPage(activePage) },
            )
        }
    }
}

@Composable
private fun PdfZoomActionButton(
    activePage: Int,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Zoom page ${activePage + 1}"
                }
                .testTag("reader_pdf_zoom_btn"),
    ) {
        Text(
            text = "⤢",
            color = colors.accentBlue,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun PdfTruncationNoticeCard(
    renderedPages: Int,
    totalPages: Int,
    externalUrl: String?,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Showing the first $renderedPages of $totalPages pages in-app.",
                color = colors.textSecondary,
                fontSize = 13.sp,
            )
            if (!externalUrl.isNullOrBlank()) {
                HeaderActionButton(
                    label = "Open Original PDF ↗",
                    onClick = { onOpenExternalUrl(externalUrl) },
                )
            }
        }
    }
}

private const val DEFAULT_LETTER_ASPECT = 0.7727f
private const val PDF_SUPERSAMPLE_FACTOR = 1.25f
