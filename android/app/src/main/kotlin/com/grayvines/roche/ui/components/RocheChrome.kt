@file:Suppress("TooManyFunctions")

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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.semantics.stateDescription
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
import kotlin.math.abs

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
    inboxUnreadCount: Int = 0,
) {
    val colors = LocalRocheColors.current
    val tabs = AppTab.normalizeBottomTabs(sideTabs)
    val inboxBadgeText = formatBottomBarBadgeCount(inboxUnreadCount)

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
                val isInbox = tab == AppTab.INBOX
                BottomBarTabItem(
                    tab = tab,
                    isSelected = tab == activeTab,
                    badgeText = if (isInbox) inboxBadgeText else null,
                    badgeStateDescription =
                        if (isInbox && inboxUnreadCount > 0) "$inboxUnreadCount unread" else null,
                    onClick = { onSelectTab(tab) },
                )
            }
        }
    }
}

private fun formatBottomBarBadgeCount(count: Int): String? =
    when {
        count <= 0 -> null
        count > MAX_BADGE_COUNT -> "$MAX_BADGE_COUNT+"
        else -> count.toString()
    }

private const val MAX_BADGE_COUNT = 99

@Composable
private fun RowScope.BottomBarTabItem(
    tab: AppTab,
    isSelected: Boolean,
    badgeText: String?,
    badgeStateDescription: String?,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val pillBg = if (isSelected) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent
    val iconTint = if (isSelected) colors.accentBlue else colors.textSecondary
    val labelColor = if (isSelected) colors.textPrimary else colors.textSecondary
    val labelWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal

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
                    if (badgeStateDescription != null) {
                        stateDescription = badgeStateDescription
                    }
                }
                .padding(vertical = 6.dp)
                .testTag("bottom_tab_${tab.name.lowercase()}"),
    ) {
        Box(
            modifier = Modifier.widthIn(min = 52.dp).height(28.dp),
            contentAlignment = Alignment.Center,
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
            if (badgeText != null) {
                BottomBarTabBadge(
                    badgeText = badgeText,
                    testTag = "bottom_tab_${tab.name.lowercase()}_badge",
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-3).dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = tab.label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = labelWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun BottomBarTabBadge(
    badgeText: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .widthIn(min = 18.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.accentBlueFilled)
                .padding(horizontal = 4.dp, vertical = 1.dp)
                .testTag(testTag),
    ) {
        Text(
            text = badgeText,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
fun RocheTopHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badgeText: String? = null,
    navigationAction: (@Composable () -> Unit)? = null,
    titleTrailingAction: (@Composable () -> Unit)? = null,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            navigationAction?.invoke()
            TopHeaderTitleCluster(
                title = title,
                subtitle = subtitle,
                badgeText = badgeText,
                modifier = Modifier.weight(1f, fill = false),
            )
            titleTrailingAction?.invoke()
        }
        if (content != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun TopHeaderTitleCluster(
    title: String,
    subtitle: String?,
    badgeText: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Text(
            text = title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        if (!badgeText.isNullOrBlank()) {
            TopHeaderCountPill(text = badgeText)
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun TopHeaderCountPill(text: String) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            color = colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            maxLines = 1,
        )
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
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { SNACKBAR_DISMISS_THRESHOLD_DP.toPx() }
    val dismissFadePx = with(density) { SNACKBAR_DISMISS_FADE_DP.toPx() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val shape = RoundedCornerShape(10.dp)
    var dragOffsetPx by remember(message) { mutableFloatStateOf(0f) }
    val alpha = (1f - abs(dragOffsetPx) / dismissFadePx).coerceIn(0.25f, 1f)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = dragOffsetPx
                    this.alpha = alpha
                }
                .pointerInput(message, dismissThresholdPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(dragOffsetPx) >= dismissThresholdPx) {
                                currentOnDismiss()
                            } else {
                                dragOffsetPx = 0f
                            }
                        },
                        onDragCancel = { dragOffsetPx = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        dragOffsetPx += dragAmount
                    }
                }
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.controlOutline, shape)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
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
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        SnackbarActionButtons(showUndo = showUndo, onUndo = onUndo, onDismiss = onDismiss)
    }
}

@Composable
private fun SnackbarActionButtons(
    showUndo: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRocheColors.current
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
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Dismiss message"
                    },
        ) {
            Text(
                text = "✕",
                color = colors.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

private val SNACKBAR_DISMISS_THRESHOLD_DP = 88.dp
private val SNACKBAR_DISMISS_FADE_DP = 220.dp

@Composable
@Suppress("LongParameterList")
internal fun RocheSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    actionLabel: String? = null,
    actionTestTag: String? = null,
    onActionClick: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    secondaryActionTestTag: String? = null,
    onSecondaryActionClick: (() -> Unit)? = null,
) {
    when {
        onActionClick == null && onSecondaryActionClick == null ->
            Box(modifier = modifier.padding(horizontal = 2.dp)) {
                SectionHeaderLabelGroup(
                    label = label,
                    badgeText = badgeText,
                    singleLineLabel = false,
                )
            }
        onSecondaryActionClick == null && onActionClick != null ->
            SingleActionSectionHeaderRow(
                label = label,
                badgeText = badgeText,
                actionLabel = actionLabel,
                actionTestTag = actionTestTag,
                onActionClick = onActionClick,
                modifier = modifier,
            )
        else ->
            DualActionSectionHeaderRow(
                label = label,
                badgeText = badgeText,
                actionLabel = actionLabel,
                actionTestTag = actionTestTag,
                onActionClick = onActionClick,
                secondaryActionLabel = secondaryActionLabel,
                secondaryActionTestTag = secondaryActionTestTag,
                onSecondaryActionClick = onSecondaryActionClick,
                modifier = modifier,
            )
    }
}

@Composable
@Suppress("LongParameterList")
private fun SingleActionSectionHeaderRow(
    label: String,
    badgeText: String?,
    actionLabel: String?,
    actionTestTag: String?,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val rowMod =
        modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onActionClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .let { base -> if (actionTestTag != null) base.testTag(actionTestTag) else base }
    Row(
        modifier = rowMod,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeaderLabelGroup(
            label = label,
            badgeText = badgeText,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!actionLabel.isNullOrBlank()) {
            Text(
                text = actionLabel,
                color = colors.accentBlue,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun DualActionSectionHeaderRow(
    label: String,
    badgeText: String?,
    actionLabel: String?,
    actionTestTag: String?,
    onActionClick: (() -> Unit)?,
    secondaryActionLabel: String?,
    secondaryActionTestTag: String?,
    onSecondaryActionClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeaderLabelGroup(
            label = label,
            badgeText = badgeText,
            modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!secondaryActionLabel.isNullOrBlank() && onSecondaryActionClick != null) {
                SectionHeaderInlineAction(
                    label = secondaryActionLabel,
                    color = colors.textSecondary,
                    testTag = secondaryActionTestTag,
                    onClick = onSecondaryActionClick,
                )
            }
            if (!actionLabel.isNullOrBlank() && onActionClick != null) {
                SectionHeaderInlineAction(
                    label = actionLabel,
                    color = colors.accentBlue,
                    testTag = actionTestTag,
                    onClick = onActionClick,
                )
            }
        }
    }
}

@Composable
private fun SectionHeaderInlineAction(
    label: String,
    color: Color,
    testTag: String?,
    onClick: () -> Unit,
) {
    val mod =
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .let { base -> if (testTag != null) base.testTag(testTag) else base }
    Box(contentAlignment = Alignment.Center, modifier = mod) {
        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SectionHeaderLabelGroup(
    label: String,
    badgeText: String?,
    modifier: Modifier = Modifier,
    singleLineLabel: Boolean = true,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = colors.textMuted,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
            maxLines = if (singleLineLabel) 1 else Int.MAX_VALUE,
            overflow = if (singleLineLabel) TextOverflow.Ellipsis else TextOverflow.Clip,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        if (!badgeText.isNullOrBlank()) {
            val badgeShape = RoundedCornerShape(999.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.widthIn(max = SECTION_HEADER_BADGE_MAX_WIDTH)
                        .clip(badgeShape)
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.border, badgeShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = badgeText,
                    color = colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val SECTION_HEADER_BADGE_MAX_WIDTH = 150.dp

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
