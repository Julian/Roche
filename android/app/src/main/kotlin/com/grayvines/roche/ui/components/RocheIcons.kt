package com.grayvines.roche.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tab) {
            AppTab.INBOX ->
                drawInboxTabIcon(tint = tint, stroke = stroke, strokeWidth = strokeWidth)
            AppTab.SEARCH ->
                drawSearchTabIcon(tint = tint, stroke = stroke, strokeWidth = strokeWidth)
            AppTab.FEEDS -> drawFeedsTabIcon(tint = tint, stroke = stroke)
            AppTab.OFFLINE ->
                drawArrowTray(
                    tint = tint,
                    stroke = stroke,
                    strokeWidth = strokeWidth,
                    arrowTipY = ARROW_BOTTOM_Y,
                    chevronWingY = OFFLINE_CHEVRON_WING_Y,
                )
            AppTab.SETTINGS ->
                drawSettingsTabIcon(tint = tint, stroke = stroke, strokeWidth = strokeWidth)
        }
    }
}

private fun DrawScope.drawInboxTabIcon(tint: Color, stroke: Stroke, strokeWidth: Float) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * INBOX_BOX_LEFT, h * INBOX_BOX_TOP),
        size = Size(w * INBOX_BOX_W, h * INBOX_BOX_H),
        cornerRadius = CornerRadius(w * CORNER_FRACTION, w * CORNER_FRACTION),
        style = stroke,
    )
    val shelfPath =
        Path().apply {
            moveTo(w * INBOX_BOX_LEFT, h * INBOX_SHELF_Y)
            lineTo(w * INBOX_NOTCH_LEFT, h * INBOX_SHELF_Y)
            lineTo(w * INBOX_WELL_LEFT, h * INBOX_WELL_Y)
            lineTo(w * INBOX_WELL_RIGHT, h * INBOX_WELL_Y)
            lineTo(w * INBOX_NOTCH_RIGHT, h * INBOX_SHELF_Y)
            lineTo(w * INBOX_BOX_RIGHT, h * INBOX_SHELF_Y)
        }
    drawPath(path = shelfPath, color = tint, style = stroke)
    drawLine(
        color = tint,
        start = Offset(w * INBOX_LINE_LEFT, h * INBOX_TOP_LINE_Y),
        end = Offset(w * INBOX_LINE_RIGHT, h * INBOX_TOP_LINE_Y),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
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

private fun DrawScope.drawSettingsTabIcon(tint: Color, stroke: Stroke, strokeWidth: Float) {
    val w = size.width
    val h = size.height
    val xStart = w * SLIDER_LEFT
    val xEnd = w * SLIDER_RIGHT
    val r = w * SLIDER_KNOB_RADIUS

    val y1 = h * SLIDER_ROW_1_Y
    val k1 = w * SLIDER_KNOB_1_X
    drawLine(tint, Offset(xStart, y1), Offset(k1 - r, y1), strokeWidth, StrokeCap.Round)
    drawLine(tint, Offset(k1 + r, y1), Offset(xEnd, y1), strokeWidth, StrokeCap.Round)
    drawCircle(color = tint, radius = r, center = Offset(k1, y1), style = stroke)

    val y2 = h * SLIDER_ROW_2_Y
    val k2 = w * SLIDER_KNOB_2_X
    drawLine(tint, Offset(xStart, y2), Offset(k2 - r, y2), strokeWidth, StrokeCap.Round)
    drawLine(tint, Offset(k2 + r, y2), Offset(xEnd, y2), strokeWidth, StrokeCap.Round)
    drawCircle(color = tint, radius = r, center = Offset(k2, y2), style = stroke)

    val y3 = h * SLIDER_ROW_3_Y
    val k3 = w * SLIDER_KNOB_3_X
    drawLine(tint, Offset(xStart, y3), Offset(k3 - r, y3), strokeWidth, StrokeCap.Round)
    drawLine(tint, Offset(k3 + r, y3), Offset(xEnd, y3), strokeWidth, StrokeCap.Round)
    drawCircle(color = tint, radius = r, center = Offset(k3, y3), style = stroke)
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
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val starPath = buildStarPath(width = this.size.width, height = this.size.height)
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

private fun buildStarPath(width: Float, height: Float): Path {
    val cx = width * 0.5f
    val cy = height * STAR_CENTER_Y
    val outerRadius = width * STAR_OUTER_RADIUS
    val innerRadius = width * STAR_INNER_RADIUS
    return Path().apply {
        for (i in 0 until STAR_VERTEX_COUNT) {
            val radius = if (i % 2 == 0) outerRadius else innerRadius
            val angleRad = Math.toRadians(STAR_START_DEG + i * STAR_STEP_DEG)
            val x = cx + (radius * cos(angleRad)).toFloat()
            val y = cy + (radius * sin(angleRad)).toFloat()
            if (i == 0) {
                moveTo(x, y)
            } else {
                lineTo(x, y)
            }
        }
        close()
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
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val trayWingY = if (isArchived) CHEVRON_WING_Y else OFFLINE_CHEVRON_WING_Y
        if (!isArchived) {
            drawFilingCabinet(tint = tint, stroke = stroke, strokeWidth = strokeWidth)
        } else {
            drawArrowTray(
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

private fun DrawScope.drawArrowTray(
    tint: Color,
    stroke: Stroke,
    strokeWidth: Float,
    arrowTipY: Float,
    chevronWingY: Float,
) {
    val width = size.width
    val height = size.height
    val trayPath =
        Path().apply {
            moveTo(width * CABINET_LEFT, height * TRAY_TOP_Y)
            lineTo(width * CABINET_LEFT, height * TRAY_BOTTOM_Y)
            lineTo(width * CABINET_RIGHT, height * TRAY_BOTTOM_Y)
            lineTo(width * CABINET_RIGHT, height * TRAY_TOP_Y)
        }
    drawPath(path = trayPath, color = tint, style = stroke)

    val centerX = width * 0.5f
    drawLine(
        color = tint,
        start = Offset(centerX, height * ARROW_TOP_Y),
        end = Offset(centerX, height * ARROW_BOTTOM_Y),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    val chevronPath =
        Path().apply {
            moveTo(width * CHEVRON_LEFT_X, height * chevronWingY)
            lineTo(centerX, height * arrowTipY)
            lineTo(width * CHEVRON_RIGHT_X, height * chevronWingY)
        }
    drawPath(path = chevronPath, color = tint, style = stroke)
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

private const val SLIDER_LEFT = 0.16f
private const val SLIDER_RIGHT = 0.84f
private const val SLIDER_KNOB_RADIUS = 0.09f
private const val SLIDER_ROW_1_Y = 0.24f
private const val SLIDER_ROW_2_Y = 0.50f
private const val SLIDER_ROW_3_Y = 0.76f
private const val SLIDER_KNOB_1_X = 0.38f
private const val SLIDER_KNOB_2_X = 0.66f
private const val SLIDER_KNOB_3_X = 0.46f
