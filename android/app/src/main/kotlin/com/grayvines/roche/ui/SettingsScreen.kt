package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode
import com.grayvines.roche.model.extractCleanDomain
import com.grayvines.roche.ui.components.BottomTabIcon
import com.grayvines.roche.ui.components.DocumentOpmlIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.ServerRackIcon
import com.grayvines.roche.ui.components.SettingToggleRow
import com.grayvines.roche.ui.theme.LocalRocheColors

@Composable
fun SettingsTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
) {
    val settingsBackStack = rememberNavBackStack(SettingsNavKey.Hub)
    val popSubpage: () -> Unit = {
        if (settingsBackStack.size > 1) {
            settingsBackStack.removeLastOrNull()
        }
    }

    NavDisplay(
        backStack = settingsBackStack,
        onBack = popSubpage,
        entryProvider =
            entryProvider {
                entry<SettingsNavKey.Hub> {
                    SettingsHubPage(
                        state = state,
                        onAction = onAction,
                        onOpenServerPage = {
                            if (settingsBackStack.lastOrNull() != SettingsNavKey.Server) {
                                settingsBackStack.add(SettingsNavKey.Server)
                            }
                        },
                        onOpenNavPage = {
                            if (settingsBackStack.lastOrNull() != SettingsNavKey.Navigation) {
                                settingsBackStack.add(SettingsNavKey.Navigation)
                            }
                        },
                        onImportOpml = onImportOpml,
                        onExportOpml = onExportOpml,
                    )
                }
                entry<SettingsNavKey.Server> {
                    SettingsServerPage(
                        state = state,
                        onAction = onAction,
                        onBack = popSubpage,
                    )
                }
                entry<SettingsNavKey.Navigation> {
                    SettingsNavigationBarPage(
                        state = state,
                        onAction = onAction,
                        onBack = popSubpage,
                    )
                }
            },
    )
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
            HomeWidgetsCardSection(
                state = state,
                onAction = onAction,
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
                        .semantics { role = Role.Button }
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
            swatchColor = Color(DARK_INK_SWATCH),
            onClick = { onAction(MainAction.SetThemeMode(ThemeMode.DARK)) },
            modifier = Modifier.testTag("theme_dark_btn"),
        )
        SegmentedOptionButton(
            label = "Paper Light",
            selected = isLight,
            swatchColor = Color(PAPER_LIGHT_SWATCH),
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
    val currentTabs = AppTab.normalizeBottomTabs(state.settings.bottomNavTabs)
    val summary = currentTabs.joinToString(" · ") { it.label }
    val unpinnedTabs =
        AppTab.bottomTabCandidates.filter { it !in currentTabs && it != AppTab.SETTINGS }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Navigation")
        RocheSurfaceCard {
            SettingsDrilldownRow(
                title = "Bottom Navigation Bar",
                subtitle = summary,
                onClick = onOpenNavPage,
                modifier = Modifier.testTag("settings_nav_row"),
            ) {
                BottomTabIcon(tab = AppTab.HOME, tint = colors.accentBlue, size = 18.dp)
            }
            val totalStarred =
                remember(state.starredEntries, state.catalog.entriesByCollectionId) {
                    (state.starredEntries.map { it.id } +
                            state.catalog.entriesByCollectionId[STARRED_COLLECTION_ID]
                                .orEmpty()
                                .map { it.entry.id })
                        .distinct()
                        .size
                }
            unpinnedTabs.forEach { unpinnedTab ->
                val unpinnedSubtitle =
                    when (unpinnedTab) {
                        AppTab.HOME -> "Quick access to your unpinned Home widgets"
                        AppTab.INBOX -> "Read-only chronological stream from subscribed feeds"
                        AppTab.DIARY ->
                            "Chronological consumption log (${state.catalog.consumedCount} logged)"
                        AppTab.QUEUE ->
                            "${state.queueEntries.size} up next · ${state.catalog.wantItemCount} want"
                        AppTab.OFFLINE ->
                            "Quick access to your unpinned tab (${state.offlineEntries.size} cached)"
                        AppTab.STARRED ->
                            "Quick access to your unpinned tab ($totalStarred starred)"
                        AppTab.LISTS -> "Ranked collections & custom lists"
                        else -> "Quick access to your unpinned tab"
                    }
                HorizontalDivider(color = colors.border, thickness = 1.dp)
                SettingsDrilldownRow(
                    title = "Open ${unpinnedTab.label}",
                    subtitle = unpinnedSubtitle,
                    onClick = { onAction(MainAction.SelectTab(unpinnedTab)) },
                    modifier =
                        Modifier.testTag("settings_unpinned_tab_${unpinnedTab.name.lowercase()}"),
                ) {
                    BottomTabIcon(tab = unpinnedTab, tint = colors.accentEcru, size = 18.dp)
                }
            }
        }
    }
}

@Composable
internal fun HomeWidgetsCardSection(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheSectionHeader(label = "Home Widgets")
        RocheSurfaceCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingToggleRow(
                    title = "Active List Draws",
                    subtitle = "Show active random draws from generator-enabled lists on Home.",
                    checked = state.settings.showCollectionDrawsOnHome,
                    onCheckedChange = { onAction(MainAction.SetShowCollectionDrawsOnHome(it)) },
                    modifier = Modifier.testTag("collection_draws_home_toggle"),
                )
                HomeDrawsStatusLines(state = state)
            }
        }
    }
}

@Composable
internal fun HomeDrawsStatusLines(state: MainUiState) {
    val enabled = state.enabledGenerators
    if (!state.settings.showCollectionDrawsOnHome || enabled.isEmpty()) return
    val colors = LocalRocheColors.current
    enabled.forEach { gen ->
        val draw = gen.currentDraw
        val summary =
            if (draw != null) {
                val byPart = draw.creator.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                "${gen.collectionTitle} (${gen.completedCount}/${gen.totalCount}): " +
                    "${draw.title}$byPart"
            } else {
                "${gen.collectionTitle}: pool complete (${gen.completedCount}/${gen.totalCount})"
            }
        Text(
            text = summary,
            color = colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, border, shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    this.selected = selected
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (swatchColor != null) {
            Box(
                modifier =
                    Modifier.size(10.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .border(0.75.dp, colors.controlOutline, CircleShape)
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
            overflow = TextOverflow.Ellipsis,
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
                .semantics { role = Role.Button }
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

private const val DARK_INK_SWATCH = 0xFF141413
private const val PAPER_LIGHT_SWATCH = 0xFFE4DFDA
