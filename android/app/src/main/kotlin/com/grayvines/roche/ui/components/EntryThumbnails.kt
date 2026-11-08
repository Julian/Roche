package com.grayvines.roche.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors

/**
 * Renders row image previews for entries:
 * - `ContentType.ARTICLE`: Rounded image link preview (`60×60 dp`) when an article has a thumbnail.
 * - `ContentType.PDF` (arXiv papers): Portrait first-page & figure preview (`54×66 dp`).
 * - `ContentType.VIDEO` (YouTube): 16:9 frame preview (`78×50 dp`) with play badge (`#C1666B`) and
 *   duration overlay.
 */
@Composable
fun EntryThumbnailPreview(entry: InboxEntry, modifier: Modifier = Modifier) {
    when (entry.contentType) {
        ContentType.ARTICLE -> ArticleImageThumbnail(entry = entry, modifier = modifier)
        ContentType.PDF -> PaperFirstPageThumbnail(entry = entry, modifier = modifier)
        ContentType.VIDEO -> VideoFrameThumbnail(entry = entry, modifier = modifier)
    }
}

@Composable
private fun ArticleImageThumbnail(entry: InboxEntry, modifier: Modifier = Modifier) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val bitmap =
        rememberEntryThumbnailBitmap(
            primaryKey = entry.thumbnailMediaId,
            fallbackKey = entry.effectiveThumbnailUrl,
        )
    Box(
        modifier =
            modifier
                .size(60.dp)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(width = 1.dp, color = colors.border, shape = shape)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(modifier = Modifier.matchParentSize()) { drawArticleImagePlaceholder(colors) }
        }
    }
}

private fun DrawScope.drawArticleImagePlaceholder(colors: RocheColors) {
    val w = size.width
    val h = size.height
    drawCircle(
        color = colors.accentVerdigris.copy(alpha = ARTICLE_SUN_ALPHA),
        radius = w * ARTICLE_SUN_RADIUS,
        center = Offset(w * ARTICLE_SUN_CX, h * ARTICLE_SUN_CY),
    )
    val landscape =
        Path().apply {
            moveTo(w * ARTICLE_MNT_X0, h * ARTICLE_MNT_BASE_Y)
            lineTo(w * ARTICLE_MNT_X1, h * ARTICLE_MNT_PEAK1_Y)
            lineTo(w * ARTICLE_MNT_X2, h * ARTICLE_MNT_VALLEY_Y)
            lineTo(w * ARTICLE_MNT_X3, h * ARTICLE_MNT_PEAK2_Y)
            lineTo(w * ARTICLE_MNT_X4, h * ARTICLE_MNT_BASE_Y)
            close()
        }
    drawPath(
        path = landscape,
        color = colors.textMuted.copy(alpha = ARTICLE_MNT_ALPHA),
    )
}

@Composable
private fun PaperFirstPageThumbnail(entry: InboxEntry, modifier: Modifier = Modifier) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(5.dp)
    val isAlternateChart = entry.id.hashCode() % 2 == 0
    val bitmap =
        rememberEntryThumbnailBitmap(
            primaryKey = entry.thumbnailMediaId,
            fallbackKey = entry.effectiveThumbnailUrl,
        )
    Box(
        modifier =
            modifier
                .size(width = 54.dp, height = 66.dp)
                .clip(shape)
                .background(Color(0xFFEBE7E0))
                .border(width = 1.dp, color = colors.border, shape = shape)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawPaperPage(colors = colors, isAlternateChart = isAlternateChart)
            }
        }
    }
}

private fun DrawScope.drawPaperPage(colors: RocheColors, isAlternateChart: Boolean) {
    val w = size.width
    val h = size.height
    val inkDark = Color(0xFF2C2A27)
    val inkMuted = Color(0xFF7A756E)
    val chartBoxBg = Color(0xFFDED8CE)

    drawRoundRect(
        color = inkDark,
        topLeft = Offset(w * PAPER_MARGIN_X, h * PAPER_TITLE_Y),
        size = Size(w * PAPER_CONTENT_W, h * PAPER_TITLE_H),
        cornerRadius = CornerRadius(PAPER_CORNER, PAPER_CORNER),
    )
    drawRoundRect(
        color = inkMuted,
        topLeft = Offset(w * PAPER_SUBTITLE_X, h * PAPER_SUBTITLE_Y),
        size = Size(w * PAPER_SUBTITLE_W, h * PAPER_LINE_H),
        cornerRadius = CornerRadius(PAPER_CORNER, PAPER_CORNER),
    )

    val figTop = h * PAPER_FIG_TOP
    val figHeight = h * PAPER_FIG_H
    drawRoundRect(
        color = chartBoxBg,
        topLeft = Offset(w * PAPER_MARGIN_X, figTop),
        size = Size(w * PAPER_CONTENT_W, figHeight),
        cornerRadius = CornerRadius(PAPER_FIG_CORNER, PAPER_FIG_CORNER),
    )

    drawPaperFigure(
        colors = colors,
        isAlternateChart = isAlternateChart,
        figTop = figTop,
        figHeight = figHeight,
    )

    PAPER_BODY_ROWS.forEach { rowFrac ->
        drawRect(
            color = inkMuted,
            topLeft = Offset(w * PAPER_MARGIN_X, h * rowFrac),
            size = Size(w * PAPER_COL_W, h * PAPER_LINE_H),
        )
        drawRect(
            color = inkMuted,
            topLeft = Offset(w * PAPER_RIGHT_COL_X, h * rowFrac),
            size = Size(w * PAPER_COL_W, h * PAPER_LINE_H),
        )
    }
}

private fun DrawScope.drawPaperFigure(
    colors: RocheColors,
    isAlternateChart: Boolean,
    figTop: Float,
    figHeight: Float,
) {
    val w = size.width
    if (isAlternateChart) {
        val curve =
            Path().apply {
                moveTo(w * CURVE_X0, figTop + figHeight * CURVE_Y0)
                lineTo(w * CURVE_X1, figTop + figHeight * CURVE_Y1)
                lineTo(w * CURVE_X2, figTop + figHeight * CURVE_Y2)
                lineTo(w * CURVE_X3, figTop + figHeight * CURVE_Y3)
                lineTo(w * CURVE_X4, figTop + figHeight * CURVE_Y4)
            }
        drawPath(
            path = curve,
            color = colors.accentBlue,
            style = Stroke(width = CURVE_STROKE_W, cap = StrokeCap.Round),
        )
    } else {
        drawRect(
            color = colors.accentBlue,
            topLeft = Offset(w * BAR_1_X, figTop + figHeight * BAR_1_TOP),
            size = Size(w * BAR_W, figHeight * BAR_1_H),
        )
        drawRect(
            color = colors.accentVerdigris,
            topLeft = Offset(w * BAR_2_X, figTop + figHeight * BAR_2_TOP),
            size = Size(w * BAR_W, figHeight * BAR_2_H),
        )
        drawRect(
            color = colors.accentIndianRed,
            topLeft = Offset(w * BAR_3_X, figTop + figHeight * BAR_3_TOP),
            size = Size(w * BAR_W, figHeight * BAR_3_H),
        )
    }
}

@Composable
private fun VideoFrameThumbnail(entry: InboxEntry, modifier: Modifier = Modifier) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(6.dp)
    val bitmap =
        rememberEntryThumbnailBitmap(
            primaryKey = entry.thumbnailMediaId,
            fallbackKey = entry.effectiveThumbnailUrl,
        )
    Box(
        modifier =
            modifier
                .size(width = 78.dp, height = 50.dp)
                .clip(shape)
                .background(Color(0xFF1A2226))
                .border(width = 1.dp, color = colors.border, shape = shape)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(modifier = Modifier.matchParentSize()) { drawVideoCanvas(colors) }
        }

        Text(
            text = entry.duration ?: "Video",
            color = Color(0xFFE4DFDA),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xCC10100F))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
internal fun rememberEntryThumbnailBitmap(
    primaryKey: String?,
    fallbackKey: String? = null,
): ImageBitmap? {
    val cleanPrimary = primaryKey?.trim()?.takeIf { it.isNotBlank() }
    val cleanFallback = fallbackKey?.trim()?.takeIf { it.isNotBlank() && it != cleanPrimary }
    val effectivePrimary = cleanPrimary ?: cleanFallback ?: return null
    val loader = LocalThumbnailLoader.current ?: return null
    val initialCached =
        loader.getCached(effectivePrimary) ?: cleanFallback?.let { loader.getCached(it) }
    val bitmap by
        produceState<ImageBitmap?>(
            initialValue = initialCached,
            key1 = effectivePrimary,
            key2 = cleanFallback,
            key3 = loader,
        ) {
            if (value == null) {
                val loadedPrimary = loader.loadThumbnail(effectivePrimary)
                value =
                    loadedPrimary
                        ?: if (cleanFallback != null && cleanFallback != effectivePrimary) {
                            loader.loadThumbnail(cleanFallback)
                        } else {
                            null
                        }
            }
        }
    return bitmap
}

private fun DrawScope.drawVideoCanvas(colors: RocheColors) {
    val w = size.width
    val h = size.height
    val wave =
        Path().apply {
            moveTo(0f, h * WAVE_Y0)
            lineTo(w * WAVE_X1, h * WAVE_Y1)
            lineTo(w * WAVE_X2, h * WAVE_Y2)
            lineTo(w * WAVE_X3, h * WAVE_Y3)
            lineTo(w * WAVE_X4, h * WAVE_Y4)
            lineTo(w, h * WAVE_Y5)
        }
    drawPath(
        path = wave,
        color = colors.accentVerdigris.copy(alpha = WAVE_ALPHA),
        style = Stroke(width = WAVE_STROKE_W, cap = StrokeCap.Round),
    )

    val center = Offset(w * 0.5f, h * PLAY_CENTER_Y)
    val radius = h * PLAY_RADIUS_FRAC
    drawCircle(color = PLAY_DISC_BG, radius = radius, center = center)
    drawCircle(
        color = colors.accentIndianRed,
        radius = radius,
        center = center,
        style = Stroke(width = PLAY_RING_W),
    )

    val playTriangle =
        Path().apply {
            moveTo(center.x - radius * PLAY_LEFT_OFFSET, center.y - radius * PLAY_HALF_H)
            lineTo(center.x - radius * PLAY_LEFT_OFFSET, center.y + radius * PLAY_HALF_H)
            lineTo(center.x + radius * PLAY_RIGHT_OFFSET, center.y)
            close()
        }
    drawPath(path = playTriangle, color = PLAY_TRIANGLE_FG)
}

private val PLAY_DISC_BG = Color(0xBF141413)
private val PLAY_TRIANGLE_FG = Color(0xFFE4DFDA)

private val PAPER_BODY_ROWS = listOf(0.70f, 0.77f, 0.84f)
private const val PAPER_MARGIN_X = 0.13f
private const val PAPER_CONTENT_W = 0.74f
private const val PAPER_TITLE_Y = 0.11f
private const val PAPER_TITLE_H = 0.04f
private const val PAPER_SUBTITLE_X = 0.22f
private const val PAPER_SUBTITLE_Y = 0.18f
private const val PAPER_SUBTITLE_W = 0.56f
private const val PAPER_LINE_H = 0.025f
private const val PAPER_CORNER = 2f
private const val PAPER_FIG_TOP = 0.26f
private const val PAPER_FIG_H = 0.36f
private const val PAPER_FIG_CORNER = 3f
private const val PAPER_COL_W = 0.33f
private const val PAPER_RIGHT_COL_X = 0.54f

private const val CURVE_X0 = 0.18f
private const val CURVE_Y0 = 0.80f
private const val CURVE_X1 = 0.34f
private const val CURVE_Y1 = 0.58f
private const val CURVE_X2 = 0.50f
private const val CURVE_Y2 = 0.65f
private const val CURVE_X3 = 0.66f
private const val CURVE_Y3 = 0.30f
private const val CURVE_X4 = 0.82f
private const val CURVE_Y4 = 0.18f
private const val CURVE_STROKE_W = 3.2f

private const val BAR_W = 0.13f
private const val BAR_1_X = 0.22f
private const val BAR_1_TOP = 0.45f
private const val BAR_1_H = 0.42f
private const val BAR_2_X = 0.42f
private const val BAR_2_TOP = 0.22f
private const val BAR_2_H = 0.65f
private const val BAR_3_X = 0.62f
private const val BAR_3_TOP = 0.34f
private const val BAR_3_H = 0.53f

private const val WAVE_Y0 = 0.72f
private const val WAVE_X1 = 0.20f
private const val WAVE_Y1 = 0.56f
private const val WAVE_X2 = 0.38f
private const val WAVE_Y2 = 0.66f
private const val WAVE_X3 = 0.56f
private const val WAVE_Y3 = 0.34f
private const val WAVE_X4 = 0.76f
private const val WAVE_Y4 = 0.50f
private const val WAVE_Y5 = 0.26f
private const val WAVE_ALPHA = 0.55f
private const val WAVE_STROKE_W = 2.6f

private const val PLAY_CENTER_Y = 0.46f
private const val PLAY_RADIUS_FRAC = 0.22f
private const val PLAY_RING_W = 2.4f
private const val PLAY_LEFT_OFFSET = 0.28f
private const val PLAY_RIGHT_OFFSET = 0.46f
private const val PLAY_HALF_H = 0.42f

private const val ARTICLE_SUN_ALPHA = 0.35f
private const val ARTICLE_SUN_RADIUS = 0.11f
private const val ARTICLE_SUN_CX = 0.32f
private const val ARTICLE_SUN_CY = 0.34f
private const val ARTICLE_MNT_X0 = 0.14f
private const val ARTICLE_MNT_X1 = 0.42f
private const val ARTICLE_MNT_X2 = 0.58f
private const val ARTICLE_MNT_X3 = 0.72f
private const val ARTICLE_MNT_X4 = 0.88f
private const val ARTICLE_MNT_BASE_Y = 0.76f
private const val ARTICLE_MNT_PEAK1_Y = 0.48f
private const val ARTICLE_MNT_VALLEY_Y = 0.64f
private const val ARTICLE_MNT_PEAK2_Y = 0.50f
private const val ARTICLE_MNT_ALPHA = 0.25f
