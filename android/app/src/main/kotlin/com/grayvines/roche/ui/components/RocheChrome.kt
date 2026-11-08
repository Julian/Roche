package com.grayvines.roche.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.ui.theme.LocalRocheColors

/** Crisp circular refresh arrow icon for feed rows and sync actions. */
@Composable
fun RefreshIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(modifier = modifier.size(size)) {
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
        val arrowHead =
            Path().apply {
                moveTo(w * REFRESH_ARROW_TIP_X, h * REFRESH_ARROW_TIP_Y)
                lineTo(w * REFRESH_ARROW_LEFT_X, h * REFRESH_ARROW_TIP_Y)
                moveTo(w * REFRESH_ARROW_TIP_X, h * REFRESH_ARROW_TIP_Y)
                lineTo(w * REFRESH_ARROW_TIP_X, h * REFRESH_ARROW_TOP_Y)
            }
        drawPath(path = arrowHead, color = tint, style = stroke)
    }
}

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
    sideTabs: List<AppTab> = AppTab.defaultSideTabs,
) {
    val colors = LocalRocheColors.current
    val normalizedSideTabs = AppTab.normalizeSideTabs(sideTabs)
    val leftTabs = normalizedSideTabs.take(2)
    val rightTabs = normalizedSideTabs.drop(2).take(2)

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
            leftTabs.forEach { tab ->
                BottomBarTabItem(
                    tab = tab,
                    isSelected = tab == activeTab,
                    onClick = { onSelectTab(tab) },
                )
            }
            CenterInboxBarItem(
                isSelected = activeTab == AppTab.INBOX,
                onClick = { onSelectTab(AppTab.INBOX) },
            )
            rightTabs.forEach { tab ->
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
private fun RowScope.CenterInboxBarItem(isSelected: Boolean, onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    val circleBg = if (isSelected) colors.accentBlue else colors.surfaceElevated
    val circleBorder = if (isSelected) colors.accentBlue else colors.accentBlue.copy(alpha = 0.55f)
    val iconTint = if (isSelected) Color.White else colors.accentBlue
    val labelColor = if (isSelected) colors.textPrimary else colors.textSecondary
    val labelWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            Modifier.weight(1.1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    selected = isSelected
                }
                .padding(vertical = 2.dp)
                .testTag("bottom_tab_inbox"),
    ) {
        Box(
            modifier =
                Modifier.size(44.dp)
                    .clip(CircleShape)
                    .background(circleBg)
                    .border(width = 1.5.dp, color = circleBorder, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BottomTabIcon(tab = AppTab.INBOX, tint = iconTint, size = 21.dp)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = AppTab.INBOX.label,
            color = labelColor,
            fontSize = 11.5.sp,
            fontWeight = labelWeight,
            maxLines = 1,
        )
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
    val headlineFamily =
        if (
            com.grayvines.roche.ui.theme.LocalHeadlineFont.current ==
                com.grayvines.roche.model.HeadlineFont.SERIF
        ) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }

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
fun FilterChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
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
            Modifier.height(32.dp)
                .clip(shape)
                .background(bg)
                .border(width = 1.dp, color = border, shape = shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    this.selected = selected
                }
                .padding(horizontal = 12.dp),
    ) {
        Text(text = label, color = textColor, fontSize = 12.sp, fontWeight = weight, maxLines = 1)
    }
}

enum class RocheButtonStyle {
    PRIMARY,
    SECONDARY,
}

@Composable
fun HeaderActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RocheButtonStyle = RocheButtonStyle.SECONDARY,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val bg =
        when (style) {
            RocheButtonStyle.PRIMARY -> colors.accentBlue
            RocheButtonStyle.SECONDARY -> colors.surfaceElevated
        }
    val border =
        when (style) {
            RocheButtonStyle.PRIMARY -> colors.accentBlue
            RocheButtonStyle.SECONDARY -> colors.border
        }
    val textColor =
        when (style) {
            RocheButtonStyle.PRIMARY -> Color.White
            RocheButtonStyle.SECONDARY -> colors.textPrimary
        }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(36.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, border, shape)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 14.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
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
    val baseModifier =
        if (outlined) {
            modifier
                .size(36.dp)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape)
        } else {
            modifier.size(36.dp).clip(shape)
        }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            baseModifier.clickable(onClick = onClick).semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
    ) {
        content()
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = colors.textPrimary, fontSize = 14.sp)
            Text(text = subtitle, color = colors.textMuted, fontSize = 11.5.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("status_snackbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            color = colors.textPrimary,
            fontSize = 12.5.sp,
            maxLines = 1,
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
            TextButton(onClick = onDismiss) {
                Text(text = "✕", color = colors.textMuted, fontSize = 12.sp)
            }
        }
    }
}
