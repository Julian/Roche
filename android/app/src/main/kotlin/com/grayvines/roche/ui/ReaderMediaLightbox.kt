@file:Suppress("MatchingDeclarationName", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.grayvines.roche.media.PdfRendererSession
import kotlin.math.roundToInt

internal sealed interface ReaderLightboxTarget {
    data class InlineImage(
        val bitmap: ImageBitmap,
        val alt: String? = null,
        val captionText: String? = null,
    ) : ReaderLightboxTarget

    data class PdfPage(
        val session: PdfRendererSession,
        val initialPageIndex: Int,
    ) : ReaderLightboxTarget
}

internal data class LightboxZoomTransform(
    val scale: Float = MIN_LIGHTBOX_SCALE,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val isZoomed: Boolean
        get() = scale > DOUBLE_TAP_RESET_THRESHOLD
}

@Suppress("LongParameterList")
internal fun applyLightboxGesture(
    current: LightboxZoomTransform,
    zoomChange: Float,
    panX: Float,
    panY: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
    centroidX: Float = containerWidthPx / 2f,
    centroidY: Float = containerHeightPx / 2f,
): LightboxZoomTransform {
    val oldScale = current.scale
    val nextScale = (oldScale * zoomChange).coerceIn(MIN_LIGHTBOX_SCALE, MAX_LIGHTBOX_SCALE)
    if (nextScale <= MIN_LIGHTBOX_SCALE + ZOOM_EPSILON) {
        return LightboxZoomTransform()
    }
    val scaleFactor = nextScale / oldScale
    val relCentroidX = centroidX - containerWidthPx / 2f
    val relCentroidY = centroidY - containerHeightPx / 2f
    val rawOffsetX = (current.offsetX - relCentroidX) * scaleFactor + relCentroidX + panX
    val rawOffsetY = (current.offsetY - relCentroidY) * scaleFactor + relCentroidY + panY
    return clampLightboxTransform(
        scale = nextScale,
        rawOffsetX = rawOffsetX,
        rawOffsetY = rawOffsetY,
        containerWidthPx = containerWidthPx,
        containerHeightPx = containerHeightPx,
    )
}

internal fun toggleLightboxDoubleTapZoom(
    current: LightboxZoomTransform,
    tapX: Float,
    tapY: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
): LightboxZoomTransform {
    if (current.isZoomed) {
        return LightboxZoomTransform()
    }
    val targetScale = DOUBLE_TAP_ZOOM_SCALE
    val centerX = containerWidthPx / 2f
    val centerY = containerHeightPx / 2f
    val rawOffsetX = (centerX - tapX) * (targetScale - 1f)
    val rawOffsetY = (centerY - tapY) * (targetScale - 1f)
    return clampLightboxTransform(
        scale = targetScale,
        rawOffsetX = rawOffsetX,
        rawOffsetY = rawOffsetY,
        containerWidthPx = containerWidthPx,
        containerHeightPx = containerHeightPx,
    )
}

private fun clampLightboxTransform(
    scale: Float,
    rawOffsetX: Float,
    rawOffsetY: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
): LightboxZoomTransform {
    val maxX = ((scale - 1f) * containerWidthPx / 2f).coerceAtLeast(0f)
    val maxY = ((scale - 1f) * containerHeightPx / 2f).coerceAtLeast(0f)
    return LightboxZoomTransform(
        scale = scale,
        offsetX = rawOffsetX.coerceIn(-maxX, maxX),
        offsetY = rawOffsetY.coerceIn(-maxY, maxY),
    )
}

@Composable
internal fun ReaderMediaLightboxDialog(
    target: ReaderLightboxTarget,
    onDismiss: () -> Unit,
    onPdfPageChanged: (Int) -> Unit = {},
) {
    val initialPage =
        when (target) {
            is ReaderLightboxTarget.PdfPage ->
                target.initialPageIndex.coerceIn(0, (target.session.pageCount - 1).coerceAtLeast(0))
            is ReaderLightboxTarget.InlineImage -> 0
        }
    var currentPage by remember(target) { mutableIntStateOf(initialPage) }
    var transform by remember(target, currentPage) { mutableStateOf(LightboxZoomTransform()) }

    LaunchedEffect(target, currentPage) {
        if (target is ReaderLightboxTarget.PdfPage) {
            onPdfPageChanged(currentPage)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .background(LIGHTBOX_SCRIM)
                    .safeDrawingPadding()
                    .testTag("reader_media_lightbox")
        ) {
            LightboxTopBar(
                target = target,
                currentPage = currentPage,
                transform = transform,
                onResetZoom = { transform = LightboxZoomTransform() },
                onDismiss = onDismiss,
            )
            LightboxZoomViewport(
                target = target,
                currentPage = currentPage,
                transform = transform,
                onTransformChange = { transform = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            LightboxBottomBar(
                target = target,
                currentPage = currentPage,
                onSelectPage = { nextPage -> currentPage = nextPage },
            )
        }
    }
}

@Composable
private fun LightboxTopBar(
    target: ReaderLightboxTarget,
    currentPage: Int,
    transform: LightboxZoomTransform,
    onResetZoom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title =
        when (target) {
            is ReaderLightboxTarget.PdfPage ->
                "Page ${currentPage + 1} of ${target.session.pageCount}"
            is ReaderLightboxTarget.InlineImage -> target.alt?.takeIf { it.isNotBlank() } ?: "Image"
        }
    val zoomLabel = if (transform.isZoomed) "${(transform.scale * 100f).roundToInt()}%" else null

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(LIGHTBOX_BAR_BG)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                color = LIGHTBOX_INK,
                fontFamily =
                    if (target is ReaderLightboxTarget.PdfPage) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.SansSerif
                    },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (zoomLabel != null) {
                Text(
                    text = zoomLabel,
                    color = LIGHTBOX_MUTED_INK,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (transform.isZoomed) {
                LightboxActionPill(
                    label = "1×",
                    contentDescription = "Reset zoom",
                    enabled = true,
                    onClick = onResetZoom,
                    testTag = "reader_lightbox_reset_zoom_btn",
                )
            }
            LightboxActionPill(
                label = "✕",
                contentDescription = "Close fullscreen viewer",
                enabled = true,
                onClick = onDismiss,
                testTag = "reader_lightbox_close_btn",
            )
        }
    }
}

@Composable
private fun LightboxZoomViewport(
    target: ReaderLightboxTarget,
    currentPage: Int,
    transform: LightboxZoomTransform,
    onTransformChange: (LightboxZoomTransform) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var currentTransform by remember(target, currentPage) { mutableStateOf(transform) }
    currentTransform = transform

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .clipToBounds()
                .onSizeChanged { viewportSize = it }
                .lightboxZoomGestures(
                    key1 = target,
                    key2 = currentPage,
                    viewportSize = { viewportSize },
                    readTransform = { currentTransform },
                    onUpdate = { next ->
                        currentTransform = next
                        onTransformChange(next)
                    },
                ),
    ) {
        val graphicsModifier =
            Modifier.graphicsLayer(
                scaleX = transform.scale,
                scaleY = transform.scale,
                translationX = transform.offsetX,
                translationY = transform.offsetY,
            )
        when (target) {
            is ReaderLightboxTarget.InlineImage -> {
                Image(
                    bitmap = target.bitmap,
                    contentDescription = target.alt ?: "Zoomed article image",
                    contentScale = ContentScale.Fit,
                    modifier = graphicsModifier.fillMaxSize().padding(8.dp),
                )
            }
            is ReaderLightboxTarget.PdfPage -> {
                LightboxPdfPageCanvas(
                    session = target.session,
                    pageIndex = currentPage,
                    modifier = graphicsModifier,
                )
            }
        }
    }
}

private fun Modifier.lightboxZoomGestures(
    key1: Any,
    key2: Any,
    viewportSize: () -> IntSize,
    readTransform: () -> LightboxZoomTransform,
    onUpdate: (LightboxZoomTransform) -> Unit,
): Modifier =
    this.pointerInput(key1, key2) {
            detectTapGestures(
                onDoubleTap = { tapOffset ->
                    val size = viewportSize()
                    onUpdate(
                        toggleLightboxDoubleTapZoom(
                            current = readTransform(),
                            tapX = tapOffset.x,
                            tapY = tapOffset.y,
                            containerWidthPx = size.width.toFloat(),
                            containerHeightPx = size.height.toFloat(),
                        )
                    )
                }
            )
        }
        .pointerInput(key1, key2) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val size = viewportSize()
                onUpdate(
                    applyLightboxGesture(
                        current = readTransform(),
                        zoomChange = zoom,
                        panX = pan.x,
                        panY = pan.y,
                        containerWidthPx = size.width.toFloat(),
                        containerHeightPx = size.height.toFloat(),
                        centroidX = centroid.x,
                        centroidY = centroid.y,
                    )
                )
            }
        }

@Composable
private fun rememberLightboxPdfRenderState(
    session: PdfRendererSession,
    pageIndex: Int,
): PdfPageRenderState {
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val targetHighResWidthPx =
        remember(containerWidthPx) { (containerWidthPx * PDF_ZOOM_SUPERSAMPLE_FACTOR).roundToInt() }
    val cachedPreview =
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
            initialValue = cachedPreview,
            session,
            pageIndex,
            targetHighResWidthPx,
        ) {
            value = cachedPreview
            val highRes =
                session.renderPageBitmap(
                    pageIndex = pageIndex,
                    targetWidthPx = targetHighResWidthPx,
                    allowHighResZoom = true,
                )
            value =
                when {
                    highRes != null ->
                        PdfPageRenderState.Success(
                            bitmap = highRes.image.asImageBitmap(),
                            aspectRatio = highRes.aspectRatio,
                        )
                    cachedPreview is PdfPageRenderState.Success -> cachedPreview
                    else -> PdfPageRenderState.Error
                }
        }
    return renderState
}

@Composable
private fun LightboxPdfPageCanvas(
    session: PdfRendererSession,
    pageIndex: Int,
    modifier: Modifier = Modifier,
) {
    when (val state = rememberLightboxPdfRenderState(session = session, pageIndex = pageIndex)) {
        is PdfPageRenderState.Success -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    modifier
                        .padding(6.dp)
                        .aspectRatio(state.aspectRatio)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White),
            ) {
                Image(
                    bitmap = state.bitmap,
                    contentDescription = "PDF page ${pageIndex + 1} of ${session.pageCount}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        PdfPageRenderState.Loading -> {
            Text(
                text = "Rendering page ${pageIndex + 1}…",
                color = LIGHTBOX_MUTED_INK,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        PdfPageRenderState.Error -> {
            Text(
                text = "Unable to render page ${pageIndex + 1}",
                color = LIGHTBOX_MUTED_INK,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun LightboxBottomBar(
    target: ReaderLightboxTarget,
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(LIGHTBOX_BAR_BG)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (target) {
            is ReaderLightboxTarget.PdfPage ->
                LightboxPdfBottomControls(
                    pageCount = target.session.pageCount,
                    currentPage = currentPage,
                    onSelectPage = onSelectPage,
                )
            is ReaderLightboxTarget.InlineImage ->
                LightboxImageBottomCaption(captionText = target.captionText)
        }
    }
}

@Composable
private fun LightboxPdfBottomControls(
    pageCount: Int,
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
) {
    if (pageCount > 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightboxActionPill(
                label = "← Prev",
                contentDescription = "Previous page",
                enabled = currentPage > 0,
                onClick = { onSelectPage((currentPage - 1).coerceAtLeast(0)) },
                testTag = "reader_lightbox_prev_page_btn",
            )
            Text(
                text = ZOOM_GESTURE_HINT,
                color = LIGHTBOX_MUTED_INK,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            LightboxActionPill(
                label = "Next →",
                contentDescription = "Next page",
                enabled = currentPage < pageCount - 1,
                onClick = { onSelectPage((currentPage + 1).coerceAtMost(pageCount - 1)) },
                testTag = "reader_lightbox_next_page_btn",
            )
        }
    } else {
        Text(
            text = ZOOM_GESTURE_HINT,
            color = LIGHTBOX_MUTED_INK,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun LightboxImageBottomCaption(captionText: String?) {
    val caption = captionText?.takeIf { it.isNotBlank() }
    if (caption != null) {
        Text(
            text = caption,
            color = LIGHTBOX_INK,
            fontSize = 12.5.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        )
    }
    Text(
        text = ZOOM_GESTURE_HINT,
        color = LIGHTBOX_MUTED_INK,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun LightboxActionPill(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val fg = if (enabled) LIGHTBOX_INK else LIGHTBOX_MUTED_INK.copy(alpha = 0.4f)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                    if (!enabled) disabled()
                }
                .padding(horizontal = 10.dp)
                .testTag(testTag),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private val LIGHTBOX_SCRIM = Color(0xF510100F)
private val LIGHTBOX_BAR_BG = Color(0xCC181817)
private val LIGHTBOX_INK = Color(0xFFE4DFDA)
private val LIGHTBOX_MUTED_INK = Color(0xFFA7A29C)
private const val MIN_LIGHTBOX_SCALE = 1f
private const val MAX_LIGHTBOX_SCALE = 4f
private const val DOUBLE_TAP_ZOOM_SCALE = 2.5f
private const val DOUBLE_TAP_RESET_THRESHOLD = 1.05f
private const val ZOOM_EPSILON = 0.01f
private const val PDF_ZOOM_SUPERSAMPLE_FACTOR = 2.2f
private const val ZOOM_GESTURE_HINT = "Pinch or double-tap to zoom"
