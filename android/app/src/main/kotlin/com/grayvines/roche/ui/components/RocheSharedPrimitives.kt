@file:Suppress("MatchingDeclarationName", "MagicNumber")

package com.grayvines.roche.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors

@Composable
internal fun RocheStripTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val accent = if (warning) colors.accentIndianRed else colors.accentBlue
    val bg = if (selected) accent.copy(alpha = 0.16f) else Color.Transparent
    val textColor =
        when {
            selected -> accent
            warning -> colors.accentIndianRed
            else -> colors.textSecondary
        }
    val weight = if (selected) FontWeight.Medium else FontWeight.Normal

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    this.selected = selected
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 30.dp)
                    .clip(shape)
                    .background(bg)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = weight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun RocheMetaPill(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    backgroundColor: Color = textColor.copy(alpha = 0.14f),
    borderColor: Color = textColor.copy(alpha = 0.45f),
    cornerRadius: Dp = 5.dp,
    horizontalPadding: Dp = 6.dp,
    verticalPadding: Dp = 2.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .clip(shape)
                .background(backgroundColor)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        Text(
            text = text,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun TierBadgePill(
    tier: Char,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val badgeColor = resolveTierColor(tier, colors)
    val shape = RoundedCornerShape(5.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .clip(shape)
                .background(badgeColor.copy(alpha = 0.16f))
                .border(1.dp, badgeColor.copy(alpha = 0.55f), shape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = tier.toString(),
            color = badgeColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

internal fun resolveTierColor(tier: Char, colors: RocheColors): Color =
    when (tier.uppercaseChar()) {
        'S' -> colors.tierSGold
        'A' -> colors.accentVerdigris
        'B' -> colors.accentBlue
        'C' -> colors.accentEcru
        else -> colors.accentIndianRed
    }

@Composable
internal fun CatalogCoverThumbnail(
    thumbnailUrl: String?,
    kind: EntryKind,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val bitmap = rememberEntryThumbnailBitmap(primaryKey = null, fallbackKey = thumbnailUrl)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(size)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val glyph =
                when (kind) {
                    EntryKind.ALBUM,
                    EntryKind.SONG,
                    EntryKind.ARTIST -> "♪"
                    EntryKind.MOVIE,
                    EntryKind.TV,
                    EntryKind.VIDEO -> "▶"
                    else -> "▪"
                }
            Text(
                text = glyph,
                color = colors.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
internal fun UnpinnedTabBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Back to Home",
) {
    val colors = LocalRocheColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onBack)
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                }
                .testTag("unpinned_tab_back_btn"),
    ) {
        Text(
            text = "←",
            color = colors.textSecondary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

internal fun Modifier.lazyGroupedCardRow(
    isFirst: Boolean,
    isLast: Boolean,
    surfaceColor: Color,
    borderColor: Color,
    cornerRadius: Dp = 12.dp,
): Modifier {
    val topR = if (isFirst) cornerRadius else 0.dp
    val bottomR = if (isLast) cornerRadius else 0.dp
    val shape = RoundedCornerShape(topR, topR, bottomR, bottomR)
    val borderMod =
        if (isFirst && isLast) {
            Modifier.border(1.dp, borderColor, shape)
        } else {
            Modifier.drawGroupedCardBorders(isFirst, isLast, borderColor, cornerRadius)
        }
    return clip(shape).background(surfaceColor).then(borderMod)
}

private fun Modifier.drawGroupedCardBorders(
    isFirst: Boolean,
    isLast: Boolean,
    borderColor: Color,
    cornerRadius: Dp,
): Modifier = drawWithContent {
    drawContent()
    val stroke = 1.dp.toPx()
    val half = stroke * 0.5f
    val left = half
    val right = size.width - half
    val maxR = minOf(size.width, size.height) * 0.5f
    val r = (cornerRadius.toPx() - half).coerceIn(0f, maxR)
    val style = Stroke(width = stroke)
    when {
        isFirst -> {
            val top = half
            val path =
                Path().apply {
                    moveTo(left, size.height)
                    lineTo(left, top + r)
                    arcTo(Rect(left, top, left + 2f * r, top + 2f * r), 180f, 90f, false)
                    lineTo(right - r, top)
                    arcTo(Rect(right - 2f * r, top, right, top + 2f * r), 270f, 90f, false)
                    lineTo(right, size.height)
                }
            drawPath(path = path, color = borderColor, style = style)
        }
        isLast -> {
            val bottom = size.height - half
            val path =
                Path().apply {
                    moveTo(left, 0f)
                    lineTo(left, bottom - r)
                    arcTo(Rect(left, bottom - 2f * r, left + 2f * r, bottom), 180f, -90f, false)
                    lineTo(right - r, bottom)
                    arcTo(Rect(right - 2f * r, bottom - 2f * r, right, bottom), 90f, -90f, false)
                    lineTo(right, 0f)
                }
            drawPath(path = path, color = borderColor, style = style)
        }
        else -> {
            drawLine(borderColor, Offset(left, 0f), Offset(left, size.height), stroke)
            drawLine(borderColor, Offset(right, 0f), Offset(right, size.height), stroke)
        }
    }
}

internal fun formatHumanByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1_024.0
    val mb = kb * 1_024.0
    val gb = mb * 1_024.0
    return when {
        bytes >= gb -> "%.1f GB".format(java.util.Locale.US, bytes / gb)
        bytes >= mb -> "%.1f MB".format(java.util.Locale.US, bytes / mb)
        bytes >= kb -> "%.0f KB".format(java.util.Locale.US, bytes / kb)
        else -> "$bytes B"
    }
}
