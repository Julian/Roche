package com.grayvines.roche.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

/** Crisp circular refresh arrow icon for feed rows and sync actions. */
@Composable
fun RefreshIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    isSpinning: Boolean = false,
) {
    val arrowHead = remember { Path() }
    val spinModifier =
        if (isSpinning) {
            val transition = rememberInfiniteTransition(label = "refresh_spin")
            val angle by
                transition.animateFloat(
                    initialValue = 0f,
                    targetValue = FULL_ROTATION_DEG,
                    animationSpec =
                        infiniteRepeatable(
                            animation =
                                tween(durationMillis = SPIN_DURATION_MS, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                    label = "refresh_angle",
                )
            Modifier.graphicsLayer { rotationZ = angle }
        } else {
            Modifier
        }
    Canvas(modifier = modifier.size(size).then(spinModifier)) {
        val strokeWidth = this.size.minDimension * REFRESH_STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = this.size.width
        val h = this.size.height
        val arcPadding = w * REFRESH_ARC_PADDING
        val arcDiameter = w - arcPadding * 2f
        drawArc(
            color = tint,
            startAngle = REFRESH_START_ANGLE_DEG,
            sweepAngle = REFRESH_SWEEP_ANGLE_DEG,
            useCenter = false,
            topLeft = Offset(arcPadding, arcPadding),
            size = Size(arcDiameter, arcDiameter),
            style = stroke,
        )
        arrowHead.rewind()
        arrowHead.moveTo(w * REFRESH_ARROW_TIP_X, h * REFRESH_ARROW_TIP_Y)
        arrowHead.lineTo(w * REFRESH_ARROW_LEFT_X, h * REFRESH_ARROW_TIP_Y)
        arrowHead.moveTo(w * REFRESH_ARROW_TIP_X, h * REFRESH_ARROW_TIP_Y)
        arrowHead.lineTo(w * REFRESH_ARROW_TIP_X, h * REFRESH_ARROW_TOP_Y)
        drawPath(path = arrowHead, color = tint, style = stroke)
    }
}

private const val SPIN_DURATION_MS = 900
private const val FULL_ROTATION_DEG = 360f

private const val REFRESH_STROKE_FRACTION = 0.085f
private const val REFRESH_ARC_PADDING = 0.18f
private const val REFRESH_START_ANGLE_DEG = 35f
private const val REFRESH_SWEEP_ANGLE_DEG = 290f
private const val REFRESH_ARROW_TIP_X = 0.80f
private const val REFRESH_ARROW_TIP_Y = 0.36f
private const val REFRESH_ARROW_LEFT_X = 0.56f
private const val REFRESH_ARROW_TOP_Y = 0.14f

@Composable
fun RocheBottomBar(
    activeTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    sideTabs: List<AppTab> = AppTab.defaultBottomTabs,
) {
    val colors = LocalRocheColors.current
    val tabs = AppTab.normalizeBottomTabs(sideTabs)

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surface)
                .navigationBarsPadding()
                .testTag("bottom_nav_bar")
    ) {
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                BottomBarTabItem(
                    tab = tab,
                    isSelected = tab == activeTab,
                    onClick = { onSelectTab(tab) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.BottomBarTabItem(tab: AppTab, isSelected: Boolean, onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    val pillBg = if (isSelected) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent
    val iconTint = if (isSelected) colors.accentBlue else colors.textSecondary
    val labelColor = if (isSelected) colors.textPrimary else colors.textSecondary
    val labelWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            Modifier.weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    selected = isSelected
                }
                .padding(vertical = 6.dp)
                .testTag("bottom_tab_${tab.name.lowercase()}"),
    ) {
        Box(
            modifier =
                Modifier.width(48.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(pillBg),
            contentAlignment = Alignment.Center,
        ) {
            BottomTabIcon(tab = tab, tint = iconTint, size = 19.dp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = tab.label,
            color = labelColor,
            fontSize = 11.5.sp,
            fontWeight = labelWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RocheTopHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationAction: (@Composable () -> Unit)? = null,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            if (navigationAction != null) {
                navigationAction()
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontFamily = headlineFamily,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = colors.textMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
        if (content != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
fun FilterChipPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Tab,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val bg =
        if (selected) {
            colors.accentBlue.copy(alpha = 0.16f)
        } else {
            colors.surface
        }
    val border = if (selected) colors.accentBlue else colors.border
    val textColor = if (selected) colors.textPrimary else colors.textSecondary
    val weight = if (selected) FontWeight.SemiBold else FontWeight.Medium

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    this.role = role
                    this.selected = selected
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 32.dp)
                    .clip(shape)
                    .background(bg)
                    .border(width = 1.dp, color = border, shape = shape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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

enum class RocheButtonStyle {
    PRIMARY,
    SECONDARY,
}

private const val DISABLED_PRIMARY_BG_ALPHA = 0.4f
private const val DISABLED_SECONDARY_BG_ALPHA = 0.5f
private const val DISABLED_PRIMARY_TEXT_ALPHA = 0.6f

private data class HeaderActionColors(
    val background: Color,
    val border: Color,
    val text: Color,
)

private fun resolveHeaderActionColors(
    colors: RocheColors,
    style: RocheButtonStyle,
    enabled: Boolean,
): HeaderActionColors =
    when (style) {
        RocheButtonStyle.PRIMARY -> {
            val accent =
                if (enabled) {
                    colors.accentBlueFilled
                } else {
                    colors.accentBlueFilled.copy(alpha = DISABLED_PRIMARY_BG_ALPHA)
                }
            HeaderActionColors(
                background = accent,
                border = accent,
                text =
                    if (enabled) {
                        Color.White
                    } else {
                        Color.White.copy(alpha = DISABLED_PRIMARY_TEXT_ALPHA)
                    },
            )
        }
        RocheButtonStyle.SECONDARY -> {
            HeaderActionColors(
                background =
                    if (enabled) {
                        colors.surfaceElevated
                    } else {
                        colors.surfaceElevated.copy(alpha = DISABLED_SECONDARY_BG_ALPHA)
                    },
                border = colors.controlOutline,
                text = if (enabled) colors.textPrimary else colors.textMuted,
            )
        }
    }

@Composable
fun HeaderActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RocheButtonStyle = RocheButtonStyle.SECONDARY,
    horizontalPadding: Dp = 14.dp,
    contentDescription: String? = null,
    enabled: Boolean = true,
) {
    val palette = resolveHeaderActionColors(LocalRocheColors.current, style, enabled)
    val shape = RoundedCornerShape(8.dp)
    val labelModifier =
        if (contentDescription != null) Modifier.clearAndSetSemantics {} else Modifier

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics {
                    role = Role.Button
                    if (!enabled) {
                        disabled()
                    }
                    if (contentDescription != null) {
                        this.contentDescription = contentDescription
                    }
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 36.dp)
                    .clip(shape)
                    .background(palette.background)
                    .border(1.dp, palette.border, shape)
                    .padding(horizontal = horizontalPadding, vertical = 8.dp),
        ) {
            Text(
                text = label,
                color = palette.text,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = labelModifier,
            )
        }
    }
}

@Composable
fun RocheIconActionButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val visualModifier =
        if (outlined) {
            Modifier.size(36.dp)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape)
        } else {
            Modifier.size(36.dp).clip(shape)
        }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = visualModifier.clearAndSetSemantics {},
        ) {
            content()
        }
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = colors.textPrimary, fontSize = 14.sp)
            Text(text = subtitle, color = colors.textMuted, fontSize = 11.5.sp)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun UndoOrStatusSnackbar(
    message: String,
    showUndo: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.controlOutline, shape)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("status_snackbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            color = colors.textPrimary,
            fontSize = 12.5.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showUndo) {
                TextButton(onClick = onUndo) {
                    Text(
                        text = "UNDO",
                        color = colors.accentEcru,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Dismiss message" },
            ) {
                Text(
                    text = "✕",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
internal fun RocheSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionTestTag: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    if (actionLabel == null || onActionClick == null) {
        Text(
            text = label.uppercase(),
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.5.sp,
            letterSpacing = 0.7.sp,
            modifier = modifier.padding(horizontal = 4.dp).semantics { heading() },
        )
        return
    }
    val actionMod =
        Modifier.minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onActionClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .let { base -> if (actionTestTag != null) base.testTag(actionTestTag) else base }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.5.sp,
            letterSpacing = 0.7.sp,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = actionLabel.uppercase(),
            color = colors.accentVerdigris,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.5.sp,
            letterSpacing = 0.5.sp,
            modifier = actionMod,
        )
    }
}

@Composable
internal fun RocheSurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface)
                .border(width = 1.dp, color = colors.border, shape = shape)
    ) {
        content()
    }
}
