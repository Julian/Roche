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
import kotlin.math.cos
import kotlin.math.sin

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
        if (!isArchived) {
            drawFilingCabinet(tint = tint, stroke = stroke, strokeWidth = strokeWidth)
        } else {
            drawUnarchiveTray(tint = tint, stroke = stroke, strokeWidth = strokeWidth)
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

private fun DrawScope.drawUnarchiveTray(tint: Color, stroke: Stroke, strokeWidth: Float) {
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
            moveTo(width * CHEVRON_LEFT_X, height * CHEVRON_WING_Y)
            lineTo(centerX, height * ARROW_TOP_Y)
            lineTo(width * CHEVRON_RIGHT_X, height * CHEVRON_WING_Y)
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
