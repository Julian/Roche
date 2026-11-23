@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grayvines.roche.model.AppTab
import kotlin.math.cos
import kotlin.math.sin

/** Renders the custom vector icon corresponding to each bottom navigation [AppTab]. */
@Composable
fun BottomTabIcon(
    tab: AppTab,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val pathA = remember { Path() }
    val pathB = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawBottomTabGlyph(
            tab = tab,
            pathA = pathA,
            pathB = pathB,
            tint = tint,
            stroke = stroke,
            strokeWidth = strokeWidth,
        )
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawBottomTabGlyph(
    tab: AppTab,
    pathA: Path,
    pathB: Path,
    tint: Color,
    stroke: Stroke,
    strokeWidth: Float,
) {
    when (tab) {
        AppTab.INBOX -> drawInboxTabIcon(pathA, tint, stroke, strokeWidth)
        AppTab.QUEUE -> drawQueueTabIcon(pathA, tint, stroke, strokeWidth)
        AppTab.STARRED -> drawStarTabIcon(pathA, tint, stroke)
        AppTab.HOME -> drawHomeTabIcon(pathA, pathB, tint, stroke)
        AppTab.FEEDS -> drawFeedsTabIcon(tint, stroke)
        AppTab.BOARDS -> drawBoardsTabIcon(pathA, tint, stroke, strokeWidth)
        AppTab.LISTS -> drawListsTabIcon(tint, stroke, strokeWidth)
        AppTab.DIARY -> drawDiaryTabIcon(tint, stroke, strokeWidth)
        AppTab.OFFLINE ->
            drawArrowTray(
                trayPath = pathA,
                chevronPath = pathB,
                tint = tint,
                stroke = stroke,
                strokeWidth = strokeWidth,
                arrowTipY = ARROW_BOTTOM_Y,
                chevronWingY = OFFLINE_CHEVRON_WING_Y,
            )
        AppTab.SETTINGS -> drawSettingsCogIcon(pathA, tint, stroke)
    }
}

private fun DrawScope.drawStarTabIcon(path: Path, tint: Color, stroke: Stroke) {
    populateStarPath(path = path, width = size.width, height = size.height)
    drawPath(path = path, color = tint, style = stroke)
}

private fun DrawScope.drawInboxTabIcon(
    path: Path,
    tint: Color,
    stroke: Stroke,
    strokeWidth: Float,
) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * INBOX_BOX_LEFT, h * INBOX_BOX_TOP),
        size = Size(w * INBOX_BOX_W, h * INBOX_BOX_H),
        cornerRadius = CornerRadius(w * CORNER_FRACTION, w * CORNER_FRACTION),
        style = stroke,
    )
    path.rewind()
    path.moveTo(w * INBOX_BOX_LEFT, h * INBOX_SHELF_Y)
    path.lineTo(w * INBOX_NOTCH_LEFT, h * INBOX_SHELF_Y)
    path.lineTo(w * INBOX_WELL_LEFT, h * INBOX_WELL_Y)
    path.lineTo(w * INBOX_WELL_RIGHT, h * INBOX_WELL_Y)
    path.lineTo(w * INBOX_NOTCH_RIGHT, h * INBOX_SHELF_Y)
    path.lineTo(w * INBOX_BOX_RIGHT, h * INBOX_SHELF_Y)
    drawPath(path = path, color = tint, style = stroke)
    drawLine(
        color = tint,
        start = Offset(w * INBOX_LINE_LEFT, h * INBOX_TOP_LINE_Y),
        end = Offset(w * INBOX_LINE_RIGHT, h * INBOX_TOP_LINE_Y),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawQueueTabIcon(
    path: Path,
    tint: Color,
    stroke: Stroke,
    strokeWidth: Float,
) {
    val w = size.width
    val h = size.height
    drawLine(
        color = tint,
        start = Offset(w * 0.14f, h * 0.24f),
        end = Offset(w * 0.84f, h * 0.24f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.14f, h * 0.50f),
        end = Offset(w * 0.54f, h * 0.50f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.14f, h * 0.76f),
        end = Offset(w * 0.54f, h * 0.76f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    path.rewind()
    path.moveTo(w * 0.66f, h * 0.46f)
    path.lineTo(w * 0.88f, h * 0.63f)
    path.lineTo(w * 0.66f, h * 0.80f)
    path.close()
    drawPath(path = path, color = tint, style = Fill)
    drawPath(path = path, color = tint, style = stroke)
}

private fun DrawScope.drawHomeTabIcon(
    housePath: Path,
    doorPath: Path,
    tint: Color,
    stroke: Stroke,
) {
    val w = size.width
    val h = size.height
    housePath.rewind()
    housePath.moveTo(w * 0.14f, h * 0.48f)
    housePath.lineTo(w * 0.50f, h * 0.16f)
    housePath.lineTo(w * 0.86f, h * 0.48f)
    housePath.lineTo(w * 0.78f, h * 0.48f)
    housePath.lineTo(w * 0.78f, h * 0.84f)
    housePath.lineTo(w * 0.22f, h * 0.84f)
    housePath.lineTo(w * 0.22f, h * 0.48f)
    housePath.close()
    drawPath(path = housePath, color = tint, style = stroke)
    doorPath.rewind()
    doorPath.moveTo(w * 0.41f, h * 0.84f)
    doorPath.lineTo(w * 0.41f, h * 0.58f)
    doorPath.lineTo(w * 0.59f, h * 0.58f)
    doorPath.lineTo(w * 0.59f, h * 0.84f)
    drawPath(path = doorPath, color = tint, style = stroke)
}

private fun DrawScope.drawBoardsTabIcon(
    flapPath: Path,
    tint: Color,
    stroke: Stroke,
    strokeWidth: Float,
) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.14f, h * 0.16f),
        size = Size(w * 0.58f, h * 0.68f),
        cornerRadius = CornerRadius(w * CORNER_FRACTION, w * CORNER_FRACTION),
        style = stroke,
    )
    flapPath.rewind()
    flapPath.moveTo(w * 0.72f, h * 0.34f)
    flapPath.lineTo(w * 0.86f, h * 0.34f)
    flapPath.lineTo(w * 0.86f, h * 0.76f)
    flapPath.lineTo(w * 0.78f, h * 0.84f)
    flapPath.lineTo(w * 0.72f, h * 0.84f)
    drawPath(path = flapPath, color = tint, style = stroke)
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.26f, h * 0.28f),
        size = Size(w * 0.34f, h * 0.17f),
        cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        style = stroke,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.26f, h * 0.58f),
        end = Offset(w * 0.60f, h * 0.58f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.26f, h * 0.71f),
        end = Offset(w * 0.50f, h * 0.71f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

/** Crisp speech-bubble icon for external discussion / Hacker News comment links. */
@Composable
fun CommentBubbleIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
) {
    val bubblePath = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = this.size.width
        val h = this.size.height
        bubblePath.rewind()
        bubblePath.moveTo(w * 0.22f, h * 0.18f)
        bubblePath.lineTo(w * 0.78f, h * 0.18f)
        bubblePath.quadraticTo(w * 0.86f, h * 0.18f, w * 0.86f, h * 0.26f)
        bubblePath.lineTo(w * 0.86f, h * 0.62f)
        bubblePath.quadraticTo(w * 0.86f, h * 0.70f, w * 0.78f, h * 0.70f)
        bubblePath.lineTo(w * 0.44f, h * 0.70f)
        bubblePath.lineTo(w * 0.24f, h * 0.85f)
        bubblePath.lineTo(w * 0.24f, h * 0.70f)
        bubblePath.lineTo(w * 0.22f, h * 0.70f)
        bubblePath.quadraticTo(w * 0.14f, h * 0.70f, w * 0.14f, h * 0.62f)
        bubblePath.lineTo(w * 0.14f, h * 0.26f)
        bubblePath.quadraticTo(w * 0.14f, h * 0.18f, w * 0.22f, h * 0.18f)
        bubblePath.close()
        drawPath(path = bubblePath, color = tint, style = stroke)
    }
}

private fun DrawScope.drawListsTabIcon(tint: Color, stroke: Stroke, strokeWidth: Float) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.16f, h * 0.16f),
        size = Size(w * 0.68f, h * 0.68f),
        cornerRadius = CornerRadius(w * CORNER_FRACTION, w * CORNER_FRACTION),
        style = stroke,
    )
    for (rowY in listOf(0.36f, 0.52f, 0.68f)) {
        drawCircle(
            color = tint,
            radius = strokeWidth * 0.72f,
            center = Offset(w * 0.30f, h * rowY),
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.42f, h * rowY),
            end = Offset(w * 0.72f, h * rowY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawDiaryTabIcon(tint: Color, stroke: Stroke, strokeWidth: Float) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.16f, h * 0.22f),
        size = Size(w * 0.68f, h * 0.62f),
        cornerRadius = CornerRadius(w * CORNER_FRACTION, w * CORNER_FRACTION),
        style = stroke,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.34f, h * 0.14f),
        end = Offset(w * 0.34f, h * 0.28f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.66f, h * 0.14f),
        end = Offset(w * 0.66f, h * 0.28f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.16f, h * 0.42f),
        end = Offset(w * 0.84f, h * 0.42f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.30f, h * 0.58f),
        end = Offset(w * 0.70f, h * 0.58f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(w * 0.30f, h * 0.72f),
        end = Offset(w * 0.56f, h * 0.72f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

/** Crisp magnifying glass icon for the floating Search overlay button and search bar. */
@Composable
fun SearchIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawSearchTabIcon(tint = tint, stroke = stroke, strokeWidth = strokeWidth)
    }
}

private fun DrawScope.drawSearchTabIcon(tint: Color, stroke: Stroke, strokeWidth: Float) {
    val w = size.width
    val h = size.height
    drawCircle(
        color = tint,
        radius = w * SEARCH_LENS_RADIUS,
        center = Offset(w * SEARCH_LENS_CX, h * SEARCH_LENS_CY),
        style = stroke,
    )
    drawLine(
        color = tint,
        start = Offset(w * SEARCH_HANDLE_START, h * SEARCH_HANDLE_START),
        end = Offset(w * SEARCH_HANDLE_END, h * SEARCH_HANDLE_END),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawFeedsTabIcon(tint: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawCircle(
        color = tint,
        radius = w * FEEDS_DOT_RADIUS,
        center = Offset(w * FEEDS_DOT_CX, h * FEEDS_DOT_CY),
        style = Fill,
    )
    drawArc(
        color = tint,
        startAngle = FEEDS_ARC_START_DEG,
        sweepAngle = FEEDS_ARC_SWEEP_DEG,
        useCenter = false,
        topLeft = Offset(w * FEEDS_INNER_OFFSET, h * FEEDS_INNER_TOP),
        size = Size(w * FEEDS_INNER_DIAMETER, h * FEEDS_INNER_DIAMETER),
        style = stroke,
    )
    drawArc(
        color = tint,
        startAngle = FEEDS_ARC_START_DEG,
        sweepAngle = FEEDS_ARC_SWEEP_DEG,
        useCenter = false,
        topLeft = Offset(w * FEEDS_OUTER_OFFSET, h * FEEDS_OUTER_TOP),
        size = Size(w * FEEDS_OUTER_DIAMETER, h * FEEDS_OUTER_DIAMETER),
        style = stroke,
    )
}

/**
 * Draws a clean continuous 6-tooth mechanical gear/cog outline with a centered hub circle
 * (Lucide-style Settings icon, distinct from sliders or sun rays).
 */
private fun DrawScope.drawSettingsCogIcon(path: Path, tint: Color, stroke: Stroke) {
    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    val outerR = size.minDimension * 0.42f
    val valleyR = size.minDimension * 0.295f
    val teeth = 6
    val stepDeg = 360.0 / teeth
    val toothOuterHalfDeg = 10.5
    val toothBaseHalfDeg = 16.5

    path.rewind()
    for (i in 0 until teeth) {
        val centerDeg = -90.0 + i * stepDeg
        val points =
            listOf(
                valleyR to centerDeg - toothBaseHalfDeg,
                outerR to centerDeg - toothOuterHalfDeg,
                outerR to centerDeg + toothOuterHalfDeg,
                valleyR to centerDeg + toothBaseHalfDeg,
            )
        points.forEachIndexed { idx, (r, deg) ->
            val rad = Math.toRadians(deg)
            val x = cx + (r * cos(rad)).toFloat()
            val y = cy + (r * sin(rad)).toFloat()
            if (i == 0 && idx == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
    }
    path.close()
    drawPath(path = path, color = tint, style = stroke)
    drawCircle(
        color = tint,
        radius = size.minDimension * 0.135f,
        center = Offset(cx, cy),
        style = stroke,
    )
}

/**
 * Custom 5-pointed star icon for primary row actions, reader toolbar, and context sheet header.
 * Renders filled when `isStarred = true` and stroked when `isStarred = false`.
 */
@Composable
fun StarIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    isStarred: Boolean = false,
    size: Dp = 18.dp,
) {
    val starPath = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        populateStarPath(path = starPath, width = this.size.width, height = this.size.height)
        if (isStarred) {
            drawPath(path = starPath, color = tint, style = Fill)
        }
        drawPath(path = starPath, color = tint, style = stroke)
    }
}

/** Crisp plus (`+`) icon for the floating action button. */
@Composable
fun PlusIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * PLUS_STROKE_FRACTION
        val center = this.size.width * 0.5f
        val start = this.size.width * PLUS_ARM_START
        val end = this.size.width * PLUS_ARM_END
        drawLine(
            color = tint,
            start = Offset(center, start),
            end = Offset(center, end),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(start, center),
            end = Offset(end, center),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

private fun populateStarPath(path: Path, width: Float, height: Float) {
    val cx = width * 0.5f
    val cy = height * STAR_CENTER_Y
    val outerRadius = width * STAR_OUTER_RADIUS
    val innerRadius = width * STAR_INNER_RADIUS
    path.rewind()
    for (i in 0 until STAR_VERTEX_COUNT) {
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        val angleRad = Math.toRadians(STAR_START_DEG + i * STAR_STEP_DEG)
        val x = cx + (radius * cos(angleRad)).toFloat()
        val y = cy + (radius * sin(angleRad)).toFloat()
        if (i == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()
}

/**
 * Crisp dismiss (`×`) icon for clearing entries without logging Finished, plus an upward-tray
 * restore icon when viewing Dismissed entries.
 */
@Composable
fun DismissIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    isDismissed: Boolean = false,
    size: Dp = 18.dp,
) {
    val pathA = remember { Path() }
    val pathB = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (!isDismissed) {
            val start = this.size.minDimension * 0.26f
            val end = this.size.minDimension * 0.74f
            drawLine(
                color = tint,
                start = Offset(start, start),
                end = Offset(end, end),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(end, start),
                end = Offset(start, end),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        } else {
            drawArrowTray(
                trayPath = pathA,
                chevronPath = pathB,
                tint = tint,
                stroke = stroke,
                strokeWidth = strokeWidth,
                arrowTipY = ARROW_TOP_Y,
                chevronWingY = CHEVRON_WING_Y,
            )
        }
    }
}

/**
 * Custom two-drawer filing cabinet icon for archiving entries, plus an upward-tray unarchive icon
 * when viewing the Archived filter.
 */
@Composable
fun FilingCabinetIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    isArchived: Boolean = false,
    size: Dp = 18.dp,
) {
    val pathA = remember { Path() }
    val pathB = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val trayWingY = if (isArchived) CHEVRON_WING_Y else OFFLINE_CHEVRON_WING_Y
        if (!isArchived) {
            drawFilingCabinet(tint = tint, stroke = stroke, strokeWidth = strokeWidth)
        } else {
            drawArrowTray(
                trayPath = pathA,
                chevronPath = pathB,
                tint = tint,
                stroke = stroke,
                strokeWidth = strokeWidth,
                arrowTipY = ARROW_TOP_Y,
                chevronWingY = trayWingY,
            )
        }
    }
}

private fun DrawScope.drawFilingCabinet(tint: Color, stroke: Stroke, strokeWidth: Float) {
    val width = size.width
    val height = size.height
    val left = width * CABINET_LEFT
    val top = height * CABINET_TOP
    val boxWidth = width * CABINET_WIDTH
    val boxHeight = height * CABINET_HEIGHT

    drawRoundRect(
        color = tint,
        topLeft = Offset(left, top),
        size = Size(boxWidth, boxHeight),
        cornerRadius = CornerRadius(width * CORNER_FRACTION, width * CORNER_FRACTION),
        style = stroke,
    )
    val midY = height * 0.5f
    drawLine(
        color = tint,
        start = Offset(left, midY),
        end = Offset(left + boxWidth, midY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    val handleLeft = width * HANDLE_START
    val handleRight = width * HANDLE_END
    drawLine(
        color = tint,
        start = Offset(handleLeft, height * TOP_HANDLE_Y),
        end = Offset(handleRight, height * TOP_HANDLE_Y),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(handleLeft, height * BOTTOM_HANDLE_Y),
        end = Offset(handleRight, height * BOTTOM_HANDLE_Y),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

@Suppress("LongParameterList")
private fun DrawScope.drawArrowTray(
    trayPath: Path,
    chevronPath: Path,
    tint: Color,
    stroke: Stroke,
    strokeWidth: Float,
    arrowTipY: Float,
    chevronWingY: Float,
) {
    val width = size.width
    val height = size.height
    trayPath.rewind()
    trayPath.moveTo(width * CABINET_LEFT, height * TRAY_TOP_Y)
    trayPath.lineTo(width * CABINET_LEFT, height * TRAY_BOTTOM_Y)
    trayPath.lineTo(width * CABINET_RIGHT, height * TRAY_BOTTOM_Y)
    trayPath.lineTo(width * CABINET_RIGHT, height * TRAY_TOP_Y)
    drawPath(path = trayPath, color = tint, style = stroke)

    val centerX = width * 0.5f
    drawLine(
        color = tint,
        start = Offset(centerX, height * ARROW_TOP_Y),
        end = Offset(centerX, height * ARROW_BOTTOM_Y),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    chevronPath.rewind()
    chevronPath.moveTo(width * CHEVRON_LEFT_X, height * chevronWingY)
    chevronPath.lineTo(centerX, height * arrowTipY)
    chevronPath.lineTo(width * CHEVRON_RIGHT_X, height * chevronWingY)
    drawPath(path = chevronPath, color = tint, style = stroke)
}

/** Crisp funnel icon for toggling the filter & tag drawer in Feeds and Inbox headers. */
@Composable
internal fun FilterFunnelIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val funnelPath = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = this.size.width
        val h = this.size.height
        funnelPath.rewind()
        funnelPath.moveTo(w * 0.14f, h * 0.18f)
        funnelPath.lineTo(w * 0.86f, h * 0.18f)
        funnelPath.lineTo(w * 0.58f, h * 0.52f)
        funnelPath.lineTo(w * 0.58f, h * 0.84f)
        funnelPath.lineTo(w * 0.42f, h * 0.76f)
        funnelPath.lineTo(w * 0.42f, h * 0.52f)
        funnelPath.close()
        drawPath(path = funnelPath, color = tint, style = stroke)
    }
}

/** Dual-blade server rack icon for the Server & Sync settings card. */
@Composable
internal fun ServerRackIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = this.size.width
        val h = this.size.height
        val corner = CornerRadius(w * 0.09f, w * 0.09f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.12f, h * 0.16f),
            size = Size(w * 0.76f, h * 0.28f),
            cornerRadius = corner,
            style = stroke,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.12f, h * 0.56f),
            size = Size(w * 0.76f, h * 0.28f),
            cornerRadius = corner,
            style = stroke,
        )
        drawCircle(color = tint, radius = w * 0.055f, center = Offset(w * 0.28f, h * 0.30f))
        drawCircle(color = tint, radius = w * 0.055f, center = Offset(w * 0.28f, h * 0.70f))
    }
}

/** Document outline icon for OPML import/export actions in Settings. */
@Composable
internal fun DocumentOpmlIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val docPath = remember { Path() }
    val foldPath = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = this.size.width
        val h = this.size.height
        docPath.rewind()
        docPath.moveTo(w * 0.22f, h * 0.12f)
        docPath.lineTo(w * 0.58f, h * 0.12f)
        docPath.lineTo(w * 0.78f, h * 0.32f)
        docPath.lineTo(w * 0.78f, h * 0.88f)
        docPath.lineTo(w * 0.22f, h * 0.88f)
        docPath.close()
        drawPath(path = docPath, color = tint, style = stroke)
        foldPath.rewind()
        foldPath.moveTo(w * 0.56f, h * 0.12f)
        foldPath.lineTo(w * 0.56f, h * 0.34f)
        foldPath.lineTo(w * 0.78f, h * 0.34f)
        drawPath(path = foldPath, color = tint, style = stroke)
    }
}

/** Downward arrow into tray icon for 1-tap offline download on podcast episode rows. */
@Composable
fun DownloadTrayIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val pathA = remember { Path() }
    val pathB = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawArrowTray(
            trayPath = pathA,
            chevronPath = pathB,
            tint = tint,
            stroke = stroke,
            strokeWidth = strokeWidth,
            arrowTipY = ARROW_BOTTOM_Y,
            chevronWingY = OFFLINE_CHEVRON_WING_Y,
        )
    }
}

/** Standard double-triangle Rewind (`◀◀`) icon without any embedded numbers. */
@Composable
fun RewindIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val leftTri = remember { Path() }
    val rightTri = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        leftTri.rewind()
        leftTri.moveTo(w * 0.10f, h * 0.50f)
        leftTri.lineTo(w * 0.48f, h * 0.22f)
        leftTri.lineTo(w * 0.48f, h * 0.78f)
        leftTri.close()
        rightTri.rewind()
        rightTri.moveTo(w * 0.48f, h * 0.50f)
        rightTri.lineTo(w * 0.88f, h * 0.22f)
        rightTri.lineTo(w * 0.88f, h * 0.78f)
        rightTri.close()
        drawPath(path = leftTri, color = tint, style = Fill)
        drawPath(path = rightTri, color = tint, style = Fill)
    }
}

/** Standard double-triangle Fast-Forward (`▶▶`) icon without any embedded numbers. */
@Composable
fun FastForwardIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val leftTri = remember { Path() }
    val rightTri = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        leftTri.rewind()
        leftTri.moveTo(w * 0.52f, h * 0.50f)
        leftTri.lineTo(w * 0.12f, h * 0.22f)
        leftTri.lineTo(w * 0.12f, h * 0.78f)
        leftTri.close()
        rightTri.rewind()
        rightTri.moveTo(w * 0.90f, h * 0.50f)
        rightTri.lineTo(w * 0.52f, h * 0.22f)
        rightTri.lineTo(w * 0.52f, h * 0.78f)
        rightTri.close()
        drawPath(path = leftTri, color = tint, style = Fill)
        drawPath(path = rightTri, color = tint, style = Fill)
    }
}

/** Play (`▶`) or Pause (`⏸`) transport icon. */
@Composable
fun PlayPauseIcon(
    isPlaying: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val playTri = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        if (isPlaying) {
            val barWidth = w * 0.20f
            val corner = CornerRadius(w * 0.05f, w * 0.05f)
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.22f, h * 0.18f),
                size = Size(barWidth, h * 0.64f),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.58f, h * 0.18f),
                size = Size(barWidth, h * 0.64f),
                cornerRadius = corner,
            )
        } else {
            playTri.rewind()
            playTri.moveTo(w * 0.24f, h * 0.16f)
            playTri.lineTo(w * 0.84f, h * 0.50f)
            playTri.lineTo(w * 0.24f, h * 0.84f)
            playTri.close()
            drawPath(path = playTri, color = tint, style = Fill)
        }
    }
}

/** Skip to Next Episode (`⏭`) icon with a forward triangle and vertical bar. */
@Composable
fun SkipNextIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val tri = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        tri.rewind()
        tri.moveTo(w * 0.16f, h * 0.20f)
        tri.lineTo(w * 0.68f, h * 0.50f)
        tri.lineTo(w * 0.16f, h * 0.80f)
        tri.close()
        drawPath(path = tri, color = tint, style = Fill)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.72f, h * 0.20f),
            size = Size(w * 0.12f, h * 0.60f),
            cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        )
    }
}

/** Three-dot horizontal overflow (`•••`) icon for quiet toolbar menus. */
@Composable
fun MoreDotsIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension * 0.085f
        val cy = this.size.height * 0.5f
        drawCircle(color = tint, radius = r, center = Offset(this.size.width * 0.22f, cy))
        drawCircle(color = tint, radius = r, center = Offset(this.size.width * 0.50f, cy))
        drawCircle(color = tint, radius = r, center = Offset(this.size.width * 0.78f, cy))
    }
}

/** Six-dot grip handle (`⋮⋮`) indicating a row can be dragged to reorder. */
@Composable
fun DragHandleIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension * 0.075f
        val xs = listOf(this.size.width * 0.34f, this.size.width * 0.66f)
        val ys =
            listOf(this.size.height * 0.24f, this.size.height * 0.50f, this.size.height * 0.76f)
        for (y in ys) {
            for (x in xs) {
                drawCircle(color = tint, radius = r, center = Offset(x, y))
            }
        }
    }
}

/** Downward chevron (`⌄`) icon for collapsing fullscreen overlays and players. */
@Composable
fun ChevronDownIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val path = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = this.size.width
        val h = this.size.height
        path.rewind()
        path.moveTo(w * 0.22f, h * 0.36f)
        path.lineTo(w * 0.50f, h * 0.66f)
        path.lineTo(w * 0.78f, h * 0.36f)
        drawPath(path = path, color = tint, style = stroke)
    }
}

/** Opposing vertical arrows (`⇅`) icon for list sort menus. */
@Composable
fun SortArrowsIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val arrows = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = this.size.width
        val h = this.size.height
        arrows.rewind()
        arrows.moveTo(w * 0.34f, h * 0.78f)
        arrows.lineTo(w * 0.34f, h * 0.22f)
        arrows.moveTo(w * 0.19f, h * 0.37f)
        arrows.lineTo(w * 0.34f, h * 0.22f)
        arrows.lineTo(w * 0.49f, h * 0.37f)
        arrows.moveTo(w * 0.66f, h * 0.22f)
        arrows.lineTo(w * 0.66f, h * 0.78f)
        arrows.moveTo(w * 0.51f, h * 0.63f)
        arrows.lineTo(w * 0.66f, h * 0.78f)
        arrows.lineTo(w * 0.81f, h * 0.63f)
        drawPath(path = arrows, color = tint, style = stroke)
    }
}

/** Crescent moon icon for the podcast player sleep timer. */
@Composable
fun MoonSleepIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val path = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = this.size.width
        val h = this.size.height
        path.rewind()
        path.moveTo(w * 0.78f, h * 0.60f)
        path.cubicTo(w * 0.70f, h * 0.78f, w * 0.46f, h * 0.84f, w * 0.30f, h * 0.70f)
        path.cubicTo(w * 0.16f, h * 0.56f, w * 0.20f, h * 0.30f, w * 0.40f, h * 0.22f)
        path.cubicTo(w * 0.34f, h * 0.40f, w * 0.46f, h * 0.62f, w * 0.78f, h * 0.60f)
        path.close()
        drawPath(path = path, color = tint, style = stroke)
    }
}

/** Quiet stroked Skip (`⏭`) icon for skipping a daily collection draw. */
@Composable
fun SkipDrawIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
) {
    val tri = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = this.size.width
        val h = this.size.height
        tri.rewind()
        tri.moveTo(w * 0.22f, h * 0.24f)
        tri.lineTo(w * 0.58f, h * 0.50f)
        tri.lineTo(w * 0.22f, h * 0.76f)
        tri.close()
        drawPath(path = tri, color = tint, style = stroke)
        drawLine(
            color = tint,
            start = Offset(w * 0.76f, h * 0.24f),
            end = Offset(w * 0.76f, h * 0.76f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/** Crisp crossed-serif Wikipedia `W` outline icon for album/entry lookup buttons. */
@Composable
fun WikipediaIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
) {
    val glyph = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = this.size.width
        val h = this.size.height
        val topY = h * 0.22f
        val botY = h * 0.78f
        drawLine(
            tint,
            Offset(w * 0.11f, topY),
            Offset(w * 0.26f, topY),
            strokeWidth,
            StrokeCap.Round,
        )
        drawLine(
            tint,
            Offset(w * 0.43f, topY),
            Offset(w * 0.57f, topY),
            strokeWidth,
            StrokeCap.Round,
        )
        drawLine(
            tint,
            Offset(w * 0.74f, topY),
            Offset(w * 0.89f, topY),
            strokeWidth,
            StrokeCap.Round,
        )
        glyph.rewind()
        glyph.moveTo(w * 0.18f, topY)
        glyph.lineTo(w * 0.33f, botY)
        glyph.lineTo(w * 0.47f, h * 0.37f)
        glyph.lineTo(w * 0.62f, botY)
        glyph.lineTo(w * 0.77f, topY)
        drawPath(path = glyph, color = tint, style = stroke)
    }
}

private const val STROKE_FRACTION = 0.085f
private const val CABINET_LEFT = 0.16f
private const val CABINET_RIGHT = 0.84f
private const val CABINET_TOP = 0.12f
private const val CABINET_WIDTH = 0.68f
private const val CABINET_HEIGHT = 0.76f
private const val CORNER_FRACTION = 0.08f
private const val HANDLE_START = 0.38f
private const val HANDLE_END = 0.62f
private const val TOP_HANDLE_Y = 0.31f
private const val BOTTOM_HANDLE_Y = 0.69f
private const val TRAY_TOP_Y = 0.56f
private const val TRAY_BOTTOM_Y = 0.84f
private const val ARROW_TOP_Y = 0.16f
private const val ARROW_BOTTOM_Y = 0.64f
private const val CHEVRON_LEFT_X = 0.32f
private const val CHEVRON_RIGHT_X = 0.68f
private const val CHEVRON_WING_Y = 0.34f
private const val STAR_VERTEX_COUNT = 10
private const val STAR_START_DEG = -90.0
private const val STAR_STEP_DEG = 36.0
private const val STAR_CENTER_Y = 0.52f
private const val STAR_OUTER_RADIUS = 0.42f
private const val STAR_INNER_RADIUS = 0.18f
private const val PLUS_STROKE_FRACTION = 0.11f
private const val PLUS_ARM_START = 0.22f
private const val PLUS_ARM_END = 0.78f

private const val INBOX_BOX_LEFT = 0.14f
private const val INBOX_BOX_RIGHT = 0.86f
private const val INBOX_BOX_TOP = 0.16f
private const val INBOX_BOX_W = 0.72f
private const val INBOX_BOX_H = 0.68f
private const val INBOX_SHELF_Y = 0.56f
private const val INBOX_NOTCH_LEFT = 0.34f
private const val INBOX_WELL_LEFT = 0.41f
private const val INBOX_WELL_RIGHT = 0.59f
private const val INBOX_NOTCH_RIGHT = 0.66f
private const val INBOX_WELL_Y = 0.68f
private const val INBOX_LINE_LEFT = 0.30f
private const val INBOX_LINE_RIGHT = 0.70f
private const val INBOX_TOP_LINE_Y = 0.36f

private const val SEARCH_LENS_CX = 0.44f
private const val SEARCH_LENS_CY = 0.44f
private const val SEARCH_LENS_RADIUS = 0.28f
private const val SEARCH_HANDLE_START = 0.64f
private const val SEARCH_HANDLE_END = 0.86f

private const val FEEDS_DOT_CX = 0.24f
private const val FEEDS_DOT_CY = 0.76f
private const val FEEDS_DOT_RADIUS = 0.08f
private const val FEEDS_ARC_START_DEG = -90f
private const val FEEDS_ARC_SWEEP_DEG = 90f
private const val FEEDS_INNER_OFFSET = -0.18f
private const val FEEDS_INNER_TOP = 0.42f
private const val FEEDS_INNER_DIAMETER = 0.76f
private const val FEEDS_OUTER_OFFSET = -0.48f
private const val FEEDS_OUTER_TOP = 0.12f
private const val FEEDS_OUTER_DIAMETER = 1.36f

private const val OFFLINE_CHEVRON_WING_Y = 0.46f
