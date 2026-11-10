package com.grayvines.roche.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.PeerNodeDto
import com.grayvines.roche.data.settings.normalizeServerUrl
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode
import com.grayvines.roche.model.extractCleanDomain
import com.grayvines.roche.model.isCrossPeerRescueError
import com.grayvines.roche.ui.components.BottomTabIcon
import com.grayvines.roche.ui.components.DocumentOpmlIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.ServerRackIcon
import com.grayvines.roche.ui.components.SettingToggleRow
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlinx.coroutines.delay

private enum class SettingsPage {
    HUB,
    SERVER,
    NAVIGATION,
}

@Composable
fun SettingsTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
) {
    var currentPage by rememberSaveable { mutableStateOf(SettingsPage.HUB) }

    BackHandler(enabled = currentPage != SettingsPage.HUB) { currentPage = SettingsPage.HUB }

    when (currentPage) {
        SettingsPage.HUB ->
            SettingsHubPage(
                state = state,
                onAction = onAction,
                onOpenServerPage = { currentPage = SettingsPage.SERVER },
                onOpenNavPage = { currentPage = SettingsPage.NAVIGATION },
                onImportOpml = onImportOpml,
                onExportOpml = onExportOpml,
            )
        SettingsPage.SERVER ->
            SettingsServerPage(
                state = state,
                onAction = onAction,
                onBack = { currentPage = SettingsPage.HUB },
            )
        SettingsPage.NAVIGATION ->
            SettingsNavigationBarPage(
                state = state,
                onAction = onAction,
                onBack = { currentPage = SettingsPage.HUB },
            )
    }
}

@Composable
internal fun SettingsHubPage(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onOpenServerPage: () -> Unit,
    onOpenNavPage: () -> Unit,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(title = "Settings")
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ServerSummaryCardSection(
                state = state,
                onAction = onAction,
                onOpenServerPage = onOpenServerPage,
            )
            AppearanceCardSection(state = state, onAction = onAction)
            NavigationSummaryCardSection(
                state = state,
                onAction = onAction,
                onOpenNavPage = onOpenNavPage,
            )
            SubscriptionsCardSection(
                feedCount = state.feeds.size,
                onImportOpml = onImportOpml,
                onExportOpml = onExportOpml,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun ServerSummaryCardSection(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onOpenServerPage: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val isConfigured =
        state.settings.serverUrl.isNotBlank() && state.settings.bearerToken.isNotBlank()
    val hostLabel =
        extractCleanDomain(state.settings.serverUrl).ifBlank { "Configure Self-Hosted Server" }
    val subtitle = resolveServerSummarySubtitle(state = state, isConfigured = isConfigured)
    val dotColor = if (isConfigured) colors.accentVerdigris else colors.accentEcru

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Server & Sync")
        RocheSurfaceCard {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable(onClick = onOpenServerPage)
                        .padding(horizontal = 14.dp, vertical = 13.dp)
                        .testTag("settings_server_row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsIconContainer {
                    ServerRackIcon(tint = colors.accentVerdigris, size = 18.dp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                        Text(
                            text = hostLabel,
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = subtitle,
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HeaderActionButton(
                    label = "Sync",
                    onClick = { onAction(MainAction.SyncNow) },
                )
                Text(text = "›", color = colors.textMuted, fontSize = 18.sp)
            }
        }
    }
}

private fun resolveServerSummarySubtitle(
    state: MainUiState,
    isConfigured: Boolean,
): String {
    if (!isConfigured) return "Tap to pair device or set Server URL"
    if (state.activePeers.isEmpty()) return "Paired via Tailscale · Tap to configure"
    val peerNoun = if (state.activePeers.size == 1) "peer" else "peers"
    return "${state.activePeers.size} active $peerNoun · Cooperative sync"
}

@Composable
internal fun AppearanceCardSection(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Appearance & Reading")
        RocheSurfaceCard {
            ThemeAndFontPickers(state = state, onAction = onAction)
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            SegmentedSettingBlock(
                title = "Inbox Row Density",
                currentLabel =
                    if (state.settings.ultraDenseRows) "1-line compact" else "2-line + snippet",
            ) {
                SegmentedOptionButton(
                    label = "Comfortable",
                    selected = !state.settings.ultraDenseRows,
                    onClick = { onAction(MainAction.SetUltraDenseRows(false)) },
                    modifier = Modifier.testTag("density_comfortable_btn"),
                )
                SegmentedOptionButton(
                    label = "Compact 1-Line",
                    selected = state.settings.ultraDenseRows,
                    onClick = { onAction(MainAction.SetUltraDenseRows(true)) },
                    modifier = Modifier.testTag("density_compact_btn"),
                )
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                SettingToggleRow(
                    title = "Swipe-Left to Archive",
                    subtitle = "Swipe rows in Inbox alongside the archive icon",
                    checked = state.settings.swipeToArchive,
                    onCheckedChange = { onAction(MainAction.SetSwipeToArchive(it)) },
                )
            }
        }
    }
}

@Composable
internal fun ThemeAndFontPickers(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val isLight = state.settings.themeMode == ThemeMode.LIGHT
    val isSerif = state.settings.headlineFont == HeadlineFont.SERIF

    SegmentedSettingBlock(
        title = "Color Theme",
        currentLabel = if (isLight) "Timberwolf Paper" else "Warm Charcoal Ink",
    ) {
        SegmentedOptionButton(
            label = "Dark Ink",
            selected = !isLight,
            swatchColor = Color(0xFF141413),
            onClick = { onAction(MainAction.SetThemeMode(ThemeMode.DARK)) },
            modifier = Modifier.testTag("theme_dark_btn"),
        )
        SegmentedOptionButton(
            label = "Paper Light",
            selected = isLight,
            swatchColor = Color(0xFFE4DFDA),
            onClick = { onAction(MainAction.SetThemeMode(ThemeMode.LIGHT)) },
            modifier = Modifier.testTag("theme_paper_btn"),
        )
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
    SegmentedSettingBlock(
        title = "Headline Font",
        currentLabel = if (isSerif) "Editorial Serif" else "Clean Sans",
    ) {
        SegmentedOptionButton(
            label = "Aa Editorial Serif",
            selected = isSerif,
            fontFamily = FontFamily.Serif,
            onClick = { onAction(MainAction.SetHeadlineFont(HeadlineFont.SERIF)) },
            modifier = Modifier.testTag("font_serif_btn"),
        )
        SegmentedOptionButton(
            label = "Aa Sans-Serif",
            selected = !isSerif,
            fontFamily = FontFamily.SansSerif,
            onClick = { onAction(MainAction.SetHeadlineFont(HeadlineFont.SANS)) },
            modifier = Modifier.testTag("font_sans_btn"),
        )
    }
}

@Composable
internal fun NavigationSummaryCardSection(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onOpenNavPage: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val currentTabs = AppTab.normalizeSideTabs(state.settings.bottomNavTabs)
    val leftSummary = currentTabs.take(2).joinToString(" · ") { it.label }
    val rightSummary = currentTabs.drop(2).take(2).joinToString(" · ") { it.label }
    val unpinnedTab = AppTab.sideTabCandidates.firstOrNull { it !in currentTabs } ?: AppTab.OFFLINE
    val unpinnedSubtitle =
        if (unpinnedTab == AppTab.OFFLINE) {
            "Quick access to your unpinned 5th tab (${state.offlineEntries.size} cached)"
        } else {
            "Quick access to your unpinned 5th tab"
        }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Navigation")
        RocheSurfaceCard {
            SettingsDrilldownRow(
                title = "Bottom Navigation Bar",
                subtitle = "$leftSummary · [Inbox] · $rightSummary",
                onClick = onOpenNavPage,
                modifier = Modifier.testTag("settings_nav_row"),
            ) {
                BottomTabIcon(tab = AppTab.INBOX, tint = colors.accentBlue, size = 18.dp)
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            SettingsDrilldownRow(
                title = "Open ${unpinnedTab.label}",
                subtitle = unpinnedSubtitle,
                onClick = { onAction(MainAction.SelectTab(unpinnedTab)) },
                modifier = Modifier.testTag("settings_unpinned_tab_row"),
            ) {
                BottomTabIcon(tab = unpinnedTab, tint = colors.accentEcru, size = 18.dp)
            }
        }
    }
}

@Composable
internal fun SubscriptionsCardSection(
    feedCount: Int,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Subscriptions & Data")
        RocheSurfaceCard {
            SettingsDrilldownRow(
                title = "Import OPML Subscriptions…",
                subtitle = "Import RSS, YouTube & arXiv feeds from an .opml file",
                onClick = onImportOpml,
                modifier = Modifier.testTag("import_opml_btn"),
            ) {
                DocumentOpmlIcon(tint = colors.textSecondary, size = 18.dp)
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            SettingsDrilldownRow(
                title = "Export OPML Backup…",
                subtitle = "Save all $feedCount subscribed feeds as XML",
                onClick = onExportOpml,
                modifier = Modifier.testTag("export_opml_btn"),
            ) {
                DocumentOpmlIcon(tint = colors.textSecondary, size = 18.dp)
            }
        }
    }
}

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
        currentTabs.getOrElse(safeSlotIndex) { AppTab.defaultSideTabs[safeSlotIndex] }

    RocheSurfaceCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text =
                    "Tap any of the 4 outer slots below, then choose which destination sits there. Inbox stays anchored in the center.",
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
        DockPreviewSlot(
            tab = currentTabs[0],
            badge = "#1",
            isSelected = selectedSlotIndex == 0,
            onClick = { onSelectSlot(0) },
            modifier = Modifier.testTag("dock_slot_0"),
        )
        DockPreviewSlot(
            tab = currentTabs[1],
            badge = "#2",
            isSelected = selectedSlotIndex == 1,
            onClick = { onSelectSlot(1) },
            modifier = Modifier.testTag("dock_slot_1"),
        )
        LockedCenterInboxDockSlot()
        DockPreviewSlot(
            tab = currentTabs[2],
            badge = "#4",
            isSelected = selectedSlotIndex == 2,
            onClick = { onSelectSlot(2) },
            modifier = Modifier.testTag("dock_slot_2"),
        )
        DockPreviewSlot(
            tab = currentTabs[3],
            badge = "#5",
            isSelected = selectedSlotIndex == 3,
            onClick = { onSelectSlot(3) },
            modifier = Modifier.testTag("dock_slot_3"),
        )
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
        )
    }
}

@Composable
internal fun RowScope.LockedCenterInboxDockSlot() {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier.weight(1f)
                .alpha(0.75f)
                .clip(shape)
                .border(width = 1.dp, color = colors.border, shape = shape)
                .padding(vertical = 8.dp, horizontal = 2.dp),
    ) {
        Text(
            text = "Center",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
        Spacer(modifier = Modifier.height(2.dp))
        BottomTabIcon(tab = AppTab.INBOX, tint = colors.accentBlue, size = 18.dp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = AppTab.INBOX.label,
            color = colors.textPrimary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
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
            else -> colors.border
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
            Modifier.clip(shape)
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

@Composable
internal fun SettingsServerPage(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalRocheColors.current
    var serverUrl by rememberSaveable { mutableStateOf(state.settings.serverUrl) }
    var bearerToken by rememberSaveable { mutableStateOf(state.settings.bearerToken) }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var revealToken by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.settings.serverUrl) {
        if (normalizeServerUrl(serverUrl) != state.settings.serverUrl) {
            serverUrl = state.settings.serverUrl
        }
    }
    LaunchedEffect(state.settings.bearerToken) {
        if (bearerToken.trim() != state.settings.bearerToken) {
            bearerToken = state.settings.bearerToken
        }
    }
    ServerConfigAutoSaveEffect(
        serverUrl = serverUrl,
        bearerToken = bearerToken,
        savedServerUrl = state.settings.serverUrl,
        savedBearerToken = state.settings.bearerToken,
        onAction = onAction,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(
            title = "Server & Pairing",
            navigationAction = {
                HeaderActionButton(
                    label = "←",
                    onClick = onBack,
                    modifier = Modifier.testTag("settings_subpage_back_btn"),
                )
            },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        ServerPageScrollBody(
            state = state,
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            pairingCode = pairingCode,
            revealToken = revealToken,
            onServerUrlChange = { serverUrl = it },
            onBearerTokenChange = { bearerToken = it },
            onPairingCodeChange = { pairingCode = it },
            onToggleRevealToken = { revealToken = !revealToken },
            onAction = onAction,
        )
    }
}

@Composable
internal fun ServerPageScrollBody(
    state: MainUiState,
    serverUrl: String,
    bearerToken: String,
    pairingCode: String,
    revealToken: Boolean,
    onServerUrlChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onPairingCodeChange: (String) -> Unit,
    onToggleRevealToken: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ServerConnectionStatusCard(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            onAction = onAction,
            onHideKeyboard = { keyboardController?.hide() },
        )
        ServerPairingCard(
            serverUrl = serverUrl,
            pairingCode = pairingCode,
            onPairingCodeChange = onPairingCodeChange,
            onAction = onAction,
            onHideKeyboard = { keyboardController?.hide() },
        )
        ServerManualCredentialsCard(
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            revealToken = revealToken,
            onServerUrlChange = onServerUrlChange,
            onBearerTokenChange = onBearerTokenChange,
            onToggleRevealToken = onToggleRevealToken,
            onSaveClick = {
                keyboardController?.hide()
                onAction(MainAction.UpdateServerConfig(serverUrl, bearerToken))
            },
        )
        PeerMeshStatusCard(
            state = state,
            onAction = onAction,
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
internal fun PeerMeshStatusCard(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val localPeerId = state.settings.peerId
    val activeFeeds = state.activeFeeds
    val onDevicePolledCount = activeFeeds.count { it.lastPolledBy == localPeerId }
    val rescueReadyCount = activeFeeds.count { it.isCrossPeerRescueError(localPeerId) }
    val tierLabel =
        if (state.localNetworkTier == NetworkTier.OFFLINE) {
            "Offline"
        } else {
            "Online · Dynamic cooperative polling"
        }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Cooperative Peer Mesh")
        RocheSurfaceCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PeerMeshLocalHeaderRow(
                    deviceName = state.settings.deviceName,
                    tierLabel = tierLabel,
                    onPollOnDevice = { onAction(MainAction.PollFeedsOnDeviceNow) },
                )
                Text(
                    text =
                        buildPeerMeshHealthSummary(
                            totalFeeds = activeFeeds.size,
                            onDevicePolledCount = onDevicePolledCount,
                            rescueReadyCount = rescueReadyCount,
                        ),
                    color = if (rescueReadyCount > 0) colors.accentEcru else colors.textSecondary,
                    fontSize = 12.sp,
                )
                if (state.activePeers.isNotEmpty()) {
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                    state.activePeers.forEach { peer ->
                        val polledByPeer = state.feeds.count { it.lastPolledBy == peer.peerId }
                        PeerMeshNodeRow(
                            peer = peer,
                            isLocalPeer = peer.peerId == localPeerId,
                            polledFeedCount = polledByPeer,
                            totalFeeds = state.feeds.size,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PeerMeshLocalHeaderRow(
    deviceName: String,
    tierLabel: String,
    onPollOnDevice: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$deviceName (This Device)",
                color = colors.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tierLabel,
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        HeaderActionButton(
            label = "Poll On-Device",
            onClick = onPollOnDevice,
            modifier = Modifier.testTag("poll_feeds_on_device_btn"),
        )
    }
}

@Composable
internal fun PeerMeshNodeRow(
    peer: PeerNodeDto,
    isLocalPeer: Boolean,
    polledFeedCount: Int = 0,
    totalFeeds: Int = 0,
) {
    val colors = LocalRocheColors.current
    val tier = NetworkTier.fromWireValue(peer.networkTier)
    val dotColor =
        if (tier == NetworkTier.OFFLINE) colors.accentIndianRed else colors.accentVerdigris
    val nameLabel = if (isLocalPeer) "${peer.deviceName} · local" else peer.deviceName
    val statusLabel =
        when {
            tier == NetworkTier.OFFLINE -> "Offline"
            totalFeeds > 0 -> "$polledFeedCount/$totalFeeds feeds"
            else -> "Online"
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            Text(
                text = nameLabel,
                color = colors.textPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = statusLabel,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

internal fun buildPeerMeshHealthSummary(
    totalFeeds: Int,
    onDevicePolledCount: Int,
    rescueReadyCount: Int,
): String {
    if (totalFeeds == 0) {
        return "Equal-peer CRDT sync & dynamic cooperative polling active."
    }
    val base = "$onDevicePolledCount of $totalFeeds feeds last polled on-device"
    return if (rescueReadyCount > 0) {
        "$base · $rescueReadyCount peer error rescue ready"
    } else {
        "$base · Background sync every 30m"
    }
}

@Composable
internal fun ServerConnectionStatusCard(
    serverUrl: String,
    bearerToken: String,
    onAction: (MainAction) -> Unit,
    onHideKeyboard: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val isConfigured = serverUrl.isNotBlank() && bearerToken.isNotBlank()
    val dotColor = if (isConfigured) colors.accentVerdigris else colors.accentEcru
    val statusText = if (isConfigured) "Connected to Tailnet Server" else "Server Not Yet Paired"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Connection Status")
        RocheSurfaceCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                    Text(
                        text = statusText,
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = serverUrl.ifBlank { "No server URL configured" },
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HeaderActionButton(
                        label = "Sync Now",
                        style = RocheButtonStyle.PRIMARY,
                        onClick = {
                            onHideKeyboard()
                            onAction(MainAction.SyncWithServerConfig(serverUrl, bearerToken))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    HeaderActionButton(
                        label = "Discover on Tailnet",
                        onClick = {
                            onHideKeyboard()
                            onAction(MainAction.DiscoverServer)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ServerPairingCard(
    serverUrl: String,
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit,
    onAction: (MainAction) -> Unit,
    onHideKeyboard: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Pair New Device (roche pair)")
        RocheSurfaceCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text =
                        "Run roche pair on your server and paste the one-time code (or host#code) below:",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = onPairingCodeChange,
                        label = { Text("Pairing Code (from roche pair)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("pairing_code_input"),
                    )
                    HeaderActionButton(
                        label = "Pair Device",
                        style = RocheButtonStyle.PRIMARY,
                        onClick = {
                            onHideKeyboard()
                            onAction(MainAction.PairWithServer(serverUrl, pairingCode))
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ServerManualCredentialsCard(
    serverUrl: String,
    bearerToken: String,
    revealToken: Boolean,
    onServerUrlChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onToggleRevealToken: () -> Unit,
    onSaveClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Manual Endpoint & Credentials")
        RocheSurfaceCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text("Roche Server URL (Tailscale / LAN)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
                )
                OutlinedTextField(
                    value = bearerToken,
                    onValueChange = onBearerTokenChange,
                    label = { Text("Bearer Token") },
                    singleLine = true,
                    visualTransformation =
                        if (revealToken) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    modifier = Modifier.fillMaxWidth().testTag("bearer_token_input"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeaderActionButton(
                        label = if (revealToken) "Hide Token" else "Reveal Token",
                        onClick = onToggleRevealToken,
                    )
                    HeaderActionButton(
                        label = "Save Settings",
                        style = RocheButtonStyle.PRIMARY,
                        onClick = onSaveClick,
                    )
                }
                Text(
                    text = "Changes auto-save after 350ms.",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
internal fun ServerConfigAutoSaveEffect(
    serverUrl: String,
    bearerToken: String,
    savedServerUrl: String,
    savedBearerToken: String,
    onAction: (MainAction) -> Unit,
) {
    LaunchedEffect(serverUrl, bearerToken) {
        val normalizedUrl = normalizeServerUrl(serverUrl)
        val trimmedToken = bearerToken.trim()
        if (normalizedUrl != savedServerUrl || trimmedToken != savedBearerToken) {
            delay(SERVER_CONFIG_AUTOSAVE_DELAY_MS)
            onAction(
                MainAction.UpdateServerConfig(
                    serverUrl = serverUrl,
                    bearerToken = bearerToken,
                    feedback = ConfigSaveFeedback.SILENT,
                )
            )
        }
    }
}

@Composable
internal fun SegmentedSettingBlock(
    title: String,
    currentLabel: String,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalRocheColors.current
    val pickerShape = RoundedCornerShape(9.dp)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(text = currentLabel, color = colors.textMuted, fontSize = 11.5.sp)
        }
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(pickerShape)
                    .background(colors.background)
                    .border(1.dp, colors.border, pickerShape)
                    .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
internal fun RowScope.SegmentedOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    swatchColor: Color? = null,
    fontFamily: FontFamily = FontFamily.SansSerif,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(6.dp)
    val bg = if (selected) colors.surfaceElevated else Color.Transparent
    val border = if (selected) colors.accentBlue else Color.Transparent
    val textColor = if (selected) colors.textPrimary else colors.textSecondary
    val weight = if (selected) FontWeight.SemiBold else FontWeight.Medium

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier =
            modifier
                .weight(1f)
                .height(34.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, border, shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    this.selected = selected
                }
                .padding(horizontal = 8.dp),
    ) {
        if (swatchColor != null) {
            Box(
                modifier =
                    Modifier.size(10.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .border(0.75.dp, colors.border, CircleShape)
            )
            Spacer(modifier = Modifier.size(6.dp))
        }
        Text(
            text = label,
            color = textColor,
            fontFamily = fontFamily,
            fontSize = 12.sp,
            fontWeight = weight,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SettingsDrilldownRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconContainer(content = content)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                color = colors.textMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = "›", color = colors.textMuted, fontSize = 18.sp)
    }
}

@Composable
internal fun SettingsIconContainer(content: @Composable () -> Unit) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(34.dp)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape),
    ) {
        content()
    }
}

private const val SERVER_CONFIG_AUTOSAVE_DELAY_MS = 350L
