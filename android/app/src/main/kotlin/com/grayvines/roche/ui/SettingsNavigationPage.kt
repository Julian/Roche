package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.ui.components.BottomTabIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.theme.LocalRocheColors

@Composable
internal fun SettingsNavigationBarPage(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val currentTabs = AppTab.normalizeSideTabs(state.settings.bottomNavTabs)
    var selectedSlotIndex by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(
            title = "Navigation Bar",
            navigationAction = {
                HeaderActionButton(
                    label = "←",
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.testTag("settings_subpage_back_btn"),
                )
            },
        ) {
            HeaderActionButton(
                label = "Reset",
                onClick = { onAction(MainAction.ResetBottomNavTabs) },
                modifier = Modifier.testTag("reset_bottom_nav_btn"),
            )
        }
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RocheSectionHeader(label = "Tap a Slot to Customize")
            VisualDockCustomizerCard(
                currentTabs = currentTabs,
                selectedSlotIndex = selectedSlotIndex,
                onSelectSlot = { selectedSlotIndex = it },
                onAssignTab = { tab ->
                    onAction(MainAction.SetBottomNavSlot(selectedSlotIndex, tab))
                },
            )
        }
    }
}

@Composable
internal fun VisualDockCustomizerCard(
    currentTabs: List<AppTab>,
    selectedSlotIndex: Int,
    onSelectSlot: (Int) -> Unit,
    onAssignTab: (AppTab) -> Unit,
) {
    val colors = LocalRocheColors.current
    val safeSlotIndex = selectedSlotIndex.coerceIn(0, currentTabs.lastIndex)
    val activeSlotTab =
        currentTabs.getOrElse(safeSlotIndex) { AppTab.defaultBottomTabs[safeSlotIndex] }

    RocheSurfaceCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text =
                    "Tap any of the 5 slots below, then choose which destination sits there. Inbox remains accessible anytime from your Home widget.",
                color = colors.textSecondary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )

            DockPreviewTray(
                currentTabs = currentTabs,
                selectedSlotIndex = safeSlotIndex,
                onSelectSlot = onSelectSlot,
            )

            DockSlotAssignmentTray(
                safeSlotIndex = safeSlotIndex,
                activeSlotTab = activeSlotTab,
                currentTabs = currentTabs,
                onAssignTab = onAssignTab,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DockSlotAssignmentTray(
    safeSlotIndex: Int,
    activeSlotTab: AppTab,
    currentTabs: List<AppTab>,
    onAssignTab: (AppTab) -> Unit,
) {
    val colors = LocalRocheColors.current
    val slotLabels =
        listOf(
            "Far Left (Slot 1)",
            "Inner Left (Slot 2)",
            "Center (Slot 3)",
            "Inner Right (Slot 4)",
            "Far Right (Slot 5)",
        )
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Editing ${slotLabels[safeSlotIndex]}",
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Current: ${activeSlotTab.label}",
                color = colors.accentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppTab.sideTabCandidates.forEach { candidate ->
                DockCandidateOption(
                    candidate = candidate,
                    isCurrentInSlot = activeSlotTab == candidate,
                    isUnpinned = candidate !in currentTabs,
                    onClick = { onAssignTab(candidate) },
                )
            }
        }
    }
}

@Composable
@Suppress("MagicNumber")
internal fun DockPreviewTray(
    currentTabs: List<AppTab>,
    selectedSlotIndex: Int,
    onSelectSlot: (Int) -> Unit,
) {
    val colors = LocalRocheColors.current
    val trayShape = RoundedCornerShape(14.dp)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(trayShape)
                .background(colors.background)
                .border(1.dp, colors.border, trayShape)
                .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        currentTabs.forEachIndexed { index, tab ->
            DockPreviewSlot(
                tab = tab,
                badge = "#${index + 1}",
                isSelected = selectedSlotIndex == index,
                onClick = { onSelectSlot(index) },
                modifier = Modifier.testTag("dock_slot_$index"),
            )
        }
    }
}

@Composable
internal fun RowScope.DockPreviewSlot(
    tab: AppTab,
    badge: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(10.dp)
    val bg = if (isSelected) colors.accentBlue.copy(alpha = 0.16f) else colors.surface
    val border = if (isSelected) colors.accentBlue else colors.border
    val iconTint = if (isSelected) colors.accentBlue else colors.textSecondary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .weight(1f)
                .clip(shape)
                .background(bg)
                .border(width = if (isSelected) 1.5.dp else 1.dp, color = border, shape = shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    selected = isSelected
                }
                .padding(vertical = 8.dp, horizontal = 2.dp),
    ) {
        Text(
            text = badge,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
        Spacer(modifier = Modifier.height(2.dp))
        BottomTabIcon(tab = tab, tint = iconTint, size = 18.dp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = tab.label,
            color = colors.textPrimary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun DockCandidateOption(
    candidate: AppTab,
    isCurrentInSlot: Boolean,
    isUnpinned: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val bg = if (isCurrentInSlot) colors.accentBlue.copy(alpha = 0.16f) else colors.surface
    val border =
        when {
            isCurrentInSlot -> colors.accentBlue
            isUnpinned -> colors.accentEcru
            else -> colors.controlOutline
        }
    val suffix =
        when {
            isCurrentInSlot -> ""
            isUnpinned -> " (Spare)"
            else -> " ⇄"
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .clip(shape)
                .background(bg)
                .border(1.dp, border, shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    selected = isCurrentInSlot
                }
                .padding(horizontal = 11.dp, vertical = 7.dp)
                .testTag("dock_candidate_${candidate.name.lowercase()}"),
    ) {
        BottomTabIcon(
            tab = candidate,
            tint = if (isCurrentInSlot) colors.accentBlue else colors.textSecondary,
            size = 15.dp,
        )
        Text(
            text = "${candidate.label}$suffix",
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = if (isCurrentInSlot) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}
