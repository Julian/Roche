@file:Suppress("TooManyFunctions", "MagicNumber")

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.grayvines.roche.data.settings.Settings
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
import com.grayvines.roche.ui.components.UnpinnedTabBackButton
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
    onRevealSavedBearerToken: suspend () -> String = { "" },
) {
    val settingsBackStack = rememberNavBackStack(SettingsNavKey.Hub)
    SettingsHubResetEffect(state.settingsHubResetTick) {
        while (settingsBackStack.size > 1) settingsBackStack.removeLastOrNull()
    }
    val popSubpage: () -> Unit = {
        if (settingsBackStack.size > 1) settingsBackStack.removeLastOrNull()
    }
    val openSubpage: (SettingsNavKey) -> Unit = { key ->
        if (settingsBackStack.lastOrNull() != key) settingsBackStack.add(key)
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
                        onOpenSubpage = openSubpage,
                    )
                }
                entry<SettingsNavKey.Server> {
                    SettingsServerPage(
                        state = state,
                        onAction = onAction,
                        onBack = popSubpage,
                        onRevealSavedBearerToken = onRevealSavedBearerToken,
                    )
                }
                entry<SettingsNavKey.Appearance> {
                    SettingsAppearancePage(state = state, onAction = onAction, onBack = popSubpage)
                }
                entry<SettingsNavKey.Navigation> {
                    SettingsNavigationBarPage(
                        state = state,
                        onAction = onAction,
                        onBack = popSubpage,
                    )
                }
                entry<SettingsNavKey.Data> {
                    SettingsDataPage(
                        feedCount = state.feeds.size,
                        onImportOpml = onImportOpml,
                        onExportOpml = onExportOpml,
                        onBack = popSubpage,
                    )
                }
            },
    )
}

@Composable
private fun SettingsHubResetEffect(resetTick: Int, onResetToHub: () -> Unit) {
    var lastHandledResetTick by rememberSaveable { mutableIntStateOf(resetTick) }
    LaunchedEffect(resetTick) {
        if (resetTick > lastHandledResetTick) {
            lastHandledResetTick = resetTick
            onResetToHub()
        }
    }
}

@Composable
internal fun SettingsHubPage(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onOpenSubpage: (SettingsNavKey) -> Unit,
) {
    val colors = LocalRocheColors.current
    val showBackToHome = state.isUnpinnedDestination(AppTab.SETTINGS)
    Column(modifier = Modifier.fillMaxSize()) {
        RocheTopHeader(
            title = "Settings",
            navigationAction =
                if (showBackToHome) {
                    {
                        UnpinnedTabBackButton(
                            onBack = { onAction(MainAction.SelectTab(AppTab.HOME)) }
                        )
                    }
                } else {
                    null
                },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsPreferencesIndexCard(
                state = state,
                onAction = onAction,
                onOpenSubpage = onOpenSubpage,
            )
            SettingsMoreViewsCard(state = state, onAction = onAction)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsPreferencesIndexCard(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onOpenSubpage: (SettingsNavKey) -> Unit,
) {
    val colors = LocalRocheColors.current
    val navSummary =
        remember(state.settings.bottomNavTabs) {
            AppTab.normalizeBottomTabs(state.settings.bottomNavTabs).joinToString(" · ") {
                it.label
            }
        }
    val appearanceSummary = buildAppearanceSummary(state)
    val dataSummary = "${state.feeds.size} feeds · OPML import & export"

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        RocheSectionHeader(label = "Preferences")
        RocheSurfaceCard {
            SettingsServerHubRow(
                state = state,
                onSync = { onAction(MainAction.SyncNow) },
                onClick = { onOpenSubpage(SettingsNavKey.Server) },
            )
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            SettingsDrilldownRow(
                title = "Appearance & Reading",
                subtitle = appearanceSummary,
                onClick = { onOpenSubpage(SettingsNavKey.Appearance) },
                modifier = Modifier.testTag("settings_appearance_row"),
            ) {
                Text(
                    text = "Aa",
                    color = colors.accentBlue,
                    fontFamily = FontFamily.Serif,
                    fontSize = 15.sp,
                )
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            SettingsDrilldownRow(
                title = "Navigation Bar",
                subtitle = navSummary,
                onClick = { onOpenSubpage(SettingsNavKey.Navigation) },
                modifier = Modifier.testTag("settings_nav_row"),
            ) {
                BottomTabIcon(tab = AppTab.HOME, tint = colors.accentBlue, size = 17.dp)
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            SettingsDrilldownRow(
                title = "Subscriptions & Data",
                subtitle = dataSummary,
                onClick = { onOpenSubpage(SettingsNavKey.Data) },
                modifier = Modifier.testTag("settings_data_row"),
            ) {
                DocumentOpmlIcon(tint = colors.textSecondary, size = 17.dp)
            }
        }
    }
}

@Composable
private fun SettingsServerHubRow(
    state: MainUiState,
    onSync: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val isConfigured = state.settings.serverUrl.isNotBlank() && state.hasBearerToken
    val hostLabel =
        extractCleanDomain(state.settings.serverUrl).ifBlank { "Configure Self-Hosted Server" }
    val health = resolveSyncHealthPresentation(state, isConfigured, colors)
    val dotColor = health.dotColor
    val subtitle = health.hubSubtitle

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 14.dp, vertical = 13.dp)
                .testTag("settings_server_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconContainer {
            ServerRackIcon(tint = colors.accentVerdigris, size = 17.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                Text(
                    text = hostLabel,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
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
        HeaderActionButton(label = "Sync", onClick = onSync)
        Text(text = "›", color = colors.textMuted, fontSize = 18.sp)
    }
}

@Composable
private fun SettingsMoreViewsCard(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val currentTabs = AppTab.normalizeBottomTabs(state.settings.bottomNavTabs)
    val unpinnedTabs =
        AppTab.bottomTabCandidates.filter { it !in currentTabs && it != AppTab.SETTINGS }
    if (unpinnedTabs.isEmpty()) return

    val totalStarred =
        remember(state.starredEntries, state.catalog.starredEntryIds) {
            (state.starredEntries.map { it.id } + state.catalog.starredEntryIds).distinct().size
        }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        RocheSectionHeader(label = "More Views")
        RocheSurfaceCard {
            unpinnedTabs.forEachIndexed { idx, tab ->
                if (idx > 0) {
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
                SettingsDrilldownRow(
                    title = "Open ${tab.label}",
                    subtitle = resolveUnpinnedTabSubtitle(tab, state, totalStarred),
                    onClick = { onAction(MainAction.SelectTab(tab)) },
                    modifier = Modifier.testTag("settings_unpinned_tab_${tab.name.lowercase()}"),
                ) {
                    BottomTabIcon(tab = tab, tint = colors.accentEcru, size = 17.dp)
                }
            }
        }
    }
}

@Composable
internal fun SettingsAppearancePage(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsSubPageHeader(title = "Appearance & Reading", onBack = onBack)
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                RocheSectionHeader(label = "Theme & Typography")
                RocheSurfaceCard {
                    ThemeAndFontPickers(state = state, onAction = onAction)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                RocheSectionHeader(label = "Inbox & Home")
                RocheSurfaceCard {
                    InboxAndHomeSettingsContent(state = state, onAction = onAction)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                RocheSectionHeader(label = "Podcast Playback")
                RocheSurfaceCard {
                    PodcastPlaybackSettingsContent(state = state, onAction = onAction)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InboxAndHomeSettingsContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    SegmentedSettingBlock(
        title = "Inbox Row Density",
        currentLabel = if (state.settings.ultraDenseRows) "1-line compact" else "2-line + snippet",
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
            title = "Swipe-Left to Dismiss",
            subtitle = "Swipe rows in Inbox alongside the dismiss icon",
            checked = state.settings.swipeToArchive,
            onCheckedChange = { onAction(MainAction.SetSwipeToArchive(it)) },
        )
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingToggleRow(
            title = "Active List Draws on Home",
            subtitle = "Show random daily draws from generator-enabled lists on Home.",
            checked = state.settings.showCollectionDrawsOnHome,
            onCheckedChange = { onAction(MainAction.SetShowCollectionDrawsOnHome(it)) },
            modifier = Modifier.testTag("collection_draws_home_toggle"),
        )
        HomeDrawsStatusLines(state = state)
    }
}

@Composable
internal fun SettingsDataPage(
    feedCount: Int,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsSubPageHeader(title = "Subscriptions & Data", onBack = onBack)
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            RocheSectionHeader(label = "OPML Feeds")
            RocheSurfaceCard {
                SettingsDrilldownRow(
                    title = "Import OPML Subscriptions…",
                    subtitle = "Import RSS, Podcasts, YouTube & arXiv feeds from an .opml file",
                    onClick = onImportOpml,
                    modifier = Modifier.testTag("import_opml_btn"),
                ) {
                    DocumentOpmlIcon(tint = colors.textSecondary, size = 17.dp)
                }
                HorizontalDivider(color = colors.border, thickness = 1.dp)
                SettingsDrilldownRow(
                    title = "Export OPML Backup…",
                    subtitle = "Save all $feedCount subscribed feeds as XML",
                    onClick = onExportOpml,
                    modifier = Modifier.testTag("export_opml_btn"),
                ) {
                    DocumentOpmlIcon(tint = colors.textSecondary, size = 17.dp)
                }
            }
        }
    }
}

@Composable
internal fun SettingsSubPageHeader(
    title: String,
    onBack: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack)
                    .semantics { role = Role.Button }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .testTag("settings_subpage_back_btn"),
        ) {
            Text(text = "← Settings", color = colors.accentBlue, fontSize = 13.sp)
        }
        Text(
            text = title,
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 17.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (content != null) {
            content()
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
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
    HorizontalDivider(color = colors.border, thickness = 1.dp)
    ReaderFontScaleSettingRow(scale = state.settings.readerFontScale, onAction = onAction)
}

@Composable
private fun ReaderFontScaleSettingRow(
    scale: Float,
    onAction: (MainAction) -> Unit,
) {
    val pct = (scale * 100f).roundToInt()
    val canDecrease = scale > Settings.MIN_READER_FONT_SCALE + 0.001f
    val canIncrease = scale < Settings.MAX_READER_FONT_SCALE - 0.001f
    SegmentedSettingBlock(title = "Default Reader Text Size", currentLabel = "$pct%") {
        SegmentedOptionButton(
            label = "A−",
            selected = false,
            onClick = {
                if (canDecrease) {
                    onAction(
                        MainAction.SetReaderFontScale(
                            (scale - 0.10f).coerceAtLeast(Settings.MIN_READER_FONT_SCALE)
                        )
                    )
                }
            },
            modifier = Modifier.testTag("settings_reader_scale_dec"),
        )
        SegmentedOptionButton(
            label = "100%",
            selected = pct == 100,
            onClick = {
                onAction(MainAction.SetReaderFontScale(Settings.DEFAULT_READER_FONT_SCALE))
            },
            modifier = Modifier.testTag("settings_reader_scale_reset"),
        )
        SegmentedOptionButton(
            label = "A+",
            selected = false,
            onClick = {
                if (canIncrease) {
                    onAction(
                        MainAction.SetReaderFontScale(
                            (scale + 0.10f).coerceAtMost(Settings.MAX_READER_FONT_SCALE)
                        )
                    )
                }
            },
            modifier = Modifier.testTag("settings_reader_scale_inc"),
        )
    }
}

@Composable
private fun PodcastPlaybackSettingsContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val currentSpeed = state.settings.defaultPlaybackSpeed
    val speedLabel = formatPlaybackSpeedLabel(currentSpeed)
    SegmentedSettingBlock(title = "Default Playback Speed", currentLabel = speedLabel) {
        SETTINGS_SPEED_PRESETS.forEach { preset ->
            SegmentedOptionButton(
                label = formatPlaybackSpeedLabel(preset),
                selected = kotlin.math.abs(currentSpeed - preset) < 0.01f,
                onClick = {
                    onAction(MainAction.SetPlaybackSpeed(preset, applyToCurrentPodcast = false))
                },
            )
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        SettingToggleRow(
            title = "Smart Rewind",
            subtitle = "Rewind a few seconds automatically after pausing",
            checked = state.settings.smartRewindEnabled,
            onCheckedChange = { onAction(MainAction.SetSmartRewind(it)) },
            modifier = Modifier.testTag("settings_smart_rewind_toggle"),
        )
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        SettingToggleRow(
            title = "Skip Silence",
            subtitle = "Shorten long silent pauses during podcast playback",
            checked = state.settings.skipSilenceEnabled,
            onCheckedChange = { onAction(MainAction.SetSkipSilence(it)) },
            modifier = Modifier.testTag("settings_skip_silence_toggle"),
        )
    }
}

private val SETTINGS_SPEED_PRESETS: List<Float> = listOf(1.0f, 1.25f, 1.5f, 2.0f)

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
                "${gen.collectionTitle} (${gen.completedCount}/${gen.totalCount}): ${draw.title}$byPart"
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
internal fun SegmentedSettingBlock(
    title: String,
    currentLabel: String,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalRocheColors.current
    val pickerShape = RoundedCornerShape(9.dp)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, color = colors.textPrimary, fontSize = 14.sp)
            Text(text = currentLabel, color = colors.textMuted, fontSize = 11.5.sp)
        }
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(pickerShape)
                    .background(colors.background)
                    .border(1.dp, colors.border, pickerShape)
                    .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
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
    val bg = if (selected) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent
    val textColor = if (selected) colors.accentBlue else colors.textSecondary
    val weight = if (selected) FontWeight.Medium else FontWeight.Normal

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier =
            modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(bg)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.RadioButton
                    this.selected = selected
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (swatchColor != null) {
            Box(
                modifier =
                    Modifier.size(9.dp)
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
            fontSize = 12.5.sp,
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
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

private fun buildAppearanceSummary(state: MainUiState): String {
    val theme = if (state.settings.themeMode == ThemeMode.LIGHT) "Paper Light" else "Dark Ink"
    val font = if (state.settings.headlineFont == HeadlineFont.SERIF) "Serif" else "Sans"
    val density = if (state.settings.ultraDenseRows) "Compact" else "Comfortable"
    return "$theme · $font · $density"
}

private fun resolveUnpinnedTabSubtitle(
    tab: AppTab,
    state: MainUiState,
    totalStarred: Int,
): String =
    when (tab) {
        AppTab.HOME -> "Quick access to Home widgets"
        AppTab.INBOX -> "Chronological stream from subscribed feeds"
        AppTab.BOARDS -> formatBoardsUnpinnedSubtitle(state)
        AppTab.DIARY -> "Chronological consumption log (${state.catalog.consumedCount} logged)"
        AppTab.QUEUE ->
            "${state.inProgressItems.size} in progress · ${state.catalog.wantItemCount} want"
        AppTab.OFFLINE -> "${state.offlineEntries.size} cached articles & episodes"
        AppTab.STARRED -> "$totalStarred starred entries"
        AppTab.LISTS -> "Ranked collections & custom lists"
        else -> "Open ${tab.label}"
    }

internal fun formatBoardsUnpinnedSubtitle(state: MainUiState): String {
    val totalCount = state.boards.size
    val activeCount = state.boards.count { it.isActive }
    return when {
        totalCount == 0 -> "Live front pages & Hacker News"
        activeCount == 0 && totalCount == 1 -> "1 paused board"
        activeCount == 0 -> "$totalCount paused boards"
        activeCount == 1 -> "1 active board"
        else -> "$activeCount active boards"
    }
}

internal fun formatPlaybackSpeedLabel(speed: Float): String =
    String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "x"

private const val DARK_INK_SWATCH = 0xFF141413
private const val PAPER_LIGHT_SWATCH = 0xFFE4DFDA
