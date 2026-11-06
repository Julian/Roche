package com.grayvines.roche.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.ui.theme.LocalRocheColors

@Composable
fun RocheBottomBar(activeTab: AppTab, onSelectTab: (AppTab) -> Unit) {
    val colors = LocalRocheColors.current
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
            AppTab.entries.forEach { tab ->
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
        )
    }
}

@Composable
fun RocheTopHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
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
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 23.sp,
                fontWeight = FontWeight.Medium,
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
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = trailing,
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
