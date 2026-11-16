@file:Suppress("MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.CollectionDrawSnapshot
import com.grayvines.roche.data.CollectionGeneratorState
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.TagItem
import com.grayvines.roche.ui.components.BottomTabIcon
import com.grayvines.roche.ui.components.EntryThumbnailPreview
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RefreshIcon
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.components.rememberHighResImageBitmap
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors

@Composable
fun HomeTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    onOpenExternalUrl: (String) -> Unit = {},
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit = { draw ->
        val fallback =
            if (draw.isMusicKind) draw.youtubeMusicUrl else draw.primaryExternalUrl.orEmpty()
        if (fallback.isNotBlank()) {
            onOpenExternalUrl(fallback)
        }
    },
) {
    val colors = LocalRocheColors.current
    val isSettingsUnpinned =
        AppTab.SETTINGS !in AppTab.normalizeSideTabs(state.settings.bottomNavTabs)

    Column(modifier = Modifier.fillMaxSize().testTag("home_screen")) {
        RocheTopHeader(
            title = "Home",
            subtitle = "Overview",
        ) {
            RocheIconActionButton(
                contentDescription = "Sync",
                onClick = { onAction(MainAction.SyncNow) },
                modifier = Modifier.testTag("home_sync_btn"),
            ) {
                RefreshIcon(tint = colors.textSecondary, size = 17.dp)
            }
            if (isSettingsUnpinned) {
                RocheIconActionButton(
                    contentDescription = "Settings",
                    onClick = { onAction(MainAction.SelectTab(AppTab.SETTINGS)) },
                    modifier = Modifier.testTag("home_spare_settings_btn"),
                ) {
                    BottomTabIcon(tab = AppTab.SETTINGS, tint = colors.textSecondary, size = 17.dp)
                }
            }
        }
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideLayout = maxWidth >= WIDE_DASHBOARD_BREAKPOINT
            val isBentoTwoColumn = maxWidth >= BENTO_TWO_COLUMN_BREAKPOINT
            HomeScrollableGrid(
                state = state,
                scrollState = scrollState,
                isWideLayout = isWideLayout,
                isBentoTwoColumn = isBentoTwoColumn,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                onOpenCollectionDraw = onOpenCollectionDraw,
            )
        }
    }
}

@Composable
internal fun HomeScrollableGrid(
    state: MainUiState,
    scrollState: ScrollState,
    isWideLayout: Boolean,
    isBentoTwoColumn: Boolean,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
) {
    val activeDraws = state.activeCollectionDraws
    val hasActiveDraws = activeDraws.isNotEmpty()
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            isWideLayout -> {
                HomeWideTwoColumnDashboard(
                    state = state,
                    activeDraws = activeDraws,
                    onAction = onAction,
                    onOpenExternalUrl = onOpenExternalUrl,
                    onOpenCollectionDraw = onOpenCollectionDraw,
                )
            }
            hasActiveDraws && isBentoTwoColumn -> {
                HomeUpNextWidget(state = state, onAction = onAction, twoColumnCards = false)
                HomeBentoTwoColumnRow(
                    state = state,
                    activeDraws = activeDraws,
                    onAction = onAction,
                    onOpenExternalUrl = onOpenExternalUrl,
                    onOpenCollectionDraw = onOpenCollectionDraw,
                )
                HomeMediaCompanionWidget(state = state, onAction = onAction)
            }
            else -> {
                HomeUpNextWidget(state = state, onAction = onAction, twoColumnCards = false)
                activeDraws.forEach { gen ->
                    HomeCollectionDrawWidget(
                        generator = gen,
                        onAction = onAction,
                        onOpenExternalUrl = onOpenExternalUrl,
                        onOpenCollectionDraw = onOpenCollectionDraw,
                    )
                }
                HomeMediaCompanionWidget(state = state, onAction = onAction)
                HomeActiveSourcesWidget(
                    state = state,
                    onAction = onAction,
                    splitColumnsWhenUnconfigured = !hasActiveDraws && isBentoTwoColumn,
                )
            }
        }
        Spacer(modifier = Modifier.height(76.dp))
    }
}

@Composable
internal fun HomeBentoTwoColumnRow(
    state: MainUiState,
    activeDraws: List<CollectionGeneratorState>,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            activeDraws.forEach { gen ->
                HomeCollectionDrawWidget(
                    generator = gen,
                    onAction = onAction,
                    onOpenExternalUrl = onOpenExternalUrl,
                    onOpenCollectionDraw = onOpenCollectionDraw,
                )
            }
        }
        HomeActiveSourcesWidget(
            state = state,
            onAction = onAction,
            modifier = Modifier.weight(1f),
            splitColumnsWhenUnconfigured = false,
        )
    }
}

@Composable
internal fun HomeWideTwoColumnDashboard(
    state: MainUiState,
    activeDraws: List<CollectionGeneratorState>,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
) {
    val hasActiveDraws = activeDraws.isNotEmpty()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1.12f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HomeUpNextWidget(state = state, onAction = onAction, twoColumnCards = true)
            HomeMediaCompanionWidget(state = state, onAction = onAction)
            if (hasActiveDraws) {
                HomeActiveSourcesWidget(
                    state = state,
                    onAction = onAction,
                    splitColumnsWhenUnconfigured = true,
                )
            }
        }
        Column(
            modifier = Modifier.weight(0.88f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (hasActiveDraws) {
                activeDraws.forEach { gen ->
                    HomeCollectionDrawWidget(
                        generator = gen,
                        onAction = onAction,
                        onOpenExternalUrl = onOpenExternalUrl,
                        onOpenCollectionDraw = onOpenCollectionDraw,
                    )
                }
            } else {
                HomeActiveSourcesWidget(
                    state = state,
                    onAction = onAction,
                    splitColumnsWhenUnconfigured = false,
                )
            }
        }
    }
}

@Composable
internal fun HomeCollectionDrawWidget(
    generator: CollectionGeneratorState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draw = generator.currentDraw
    val openCollectionList = {
        onAction(MainAction.OpenCompanionSection(CompanionSubTab.LISTS, generator.collectionId))
    }
    Column(
        modifier = modifier.fillMaxWidth().testTag("home_widget_collection_draw"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RocheSectionHeader(
            label = generator.collectionTitle,
            actionLabel = "Open List →",
            actionTestTag = "collection_draw_header_open_list_btn",
            onActionClick = openCollectionList,
        )
        RocheSurfaceCard {
            if (draw != null) {
                CollectionDrawSleeveCardBody(
                    draw = draw,
                    onOpenExternalUrl = onOpenExternalUrl,
                    onOpenCollectionDraw = onOpenCollectionDraw,
                )
            } else {
                CollectionDrawPlaceholderBody(
                    collectionTitle = generator.collectionTitle,
                    completedCount = generator.completedCount,
                    totalCount = generator.totalCount,
                    onOpenList = openCollectionList,
                )
            }
        }
    }
}

@Composable
internal fun CollectionDrawSleeveCardBody(
    draw: CollectionDrawSnapshot,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }

    CollectionDrawSquareCover(
        draw = draw,
        onClick = { onOpenCollectionDraw(draw) },
    )
    HorizontalDivider(color = colors.border, thickness = 1.dp)
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clickable { onOpenCollectionDraw(draw) }
                .testTag("collection_draw_meta")
                .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val tagKicker = remember(draw.tags) { buildCollectionDrawTagKicker(draw) }
        if (tagKicker.isNotEmpty()) {
            Text(
                text = tagKicker,
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = draw.title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (draw.creator.isNotBlank()) {
            Text(
                text = draw.creator,
                color = colors.textSecondary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CollectionDrawWikiButton(
            wikiUrl = draw.wikipediaUrl,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

@Composable
private fun CollectionDrawWikiButton(
    wikiUrl: String?,
    onOpenExternalUrl: (String) -> Unit,
) {
    if (wikiUrl.isNullOrBlank()) return
    Spacer(modifier = Modifier.height(4.dp))
    CompactCardActionButton(
        label = "Wiki ↗",
        onClick = { onOpenExternalUrl(wikiUrl) },
        modifier = Modifier.fillMaxWidth().testTag("collection_draw_wiki_btn"),
    )
}

@Composable
internal fun CollectionDrawSquareCover(
    draw: CollectionDrawSnapshot,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val bitmap =
        rememberHighResImageBitmap(
            primaryKey = draw.imageUrl,
            fallbackKey = draw.thumbnailUrl,
        )
    val sleeveBadge = remember(draw) { buildCollectionDrawSleeveBadge(draw) }
    val desc = if (draw.creator.isNotBlank()) "${draw.title} by ${draw.creator}" else draw.title

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .background(colors.surfaceElevated)
                .clickable(onClick = onClick)
                .testTag("collection_draw_cover")
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = desc,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) { drawVinylSleeveArtwork(colors) }
        }

        if (sleeveBadge.isNotEmpty()) {
            AlbumCoverFloatingPill(
                label = sleeveBadge,
                textColor = Color(0xFFE4DFDA),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
        if (draw.remainingCount > 0) {
            AlbumCoverFloatingPill(
                label = "${draw.remainingCount} LEFT",
                textColor = Color(0xFFD4B483),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }
        AlbumCoverPlayBadge(
            accentColor = colors.accentBlue,
            modifier = Modifier.align(Alignment.BottomEnd).padding(9.dp),
        )
    }
}

@Composable
internal fun AlbumCoverFloatingPill(
    label: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(Color(0xCC141413))
                .border(width = 0.75.dp, color = Color(0x38E4DFDA), shape = shape)
                .padding(horizontal = 6.dp, vertical = 2.5.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun AlbumCoverPlayBadge(
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xD9141413))
                .border(width = 1.25.dp, color = accentColor, shape = CircleShape),
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            val w = size.width
            val h = size.height
            val tri =
                Path().apply {
                    moveTo(w * 0.24f, h * 0.14f)
                    lineTo(w * 0.88f, h * 0.50f)
                    lineTo(w * 0.24f, h * 0.86f)
                    close()
                }
            drawPath(path = tri, color = Color.White)
        }
    }
}

internal fun DrawScope.drawVinylSleeveArtwork(colors: RocheColors) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.5f, h * 0.5f)
    val outerRadius = size.minDimension * 0.40f

    drawRect(color = if (colors.isDark) Color(0xFF1E1D1B) else Color(0xFFDCD6CE))
    drawCircle(color = Color(0xFF141413), radius = outerRadius, center = center)
    drawCircle(
        color = Color(0xFF2E2C29),
        radius = outerRadius,
        center = center,
        style = Stroke(width = 2f),
    )

    listOf(0.86f, 0.73f, 0.60f, 0.48f).forEach { ringFrac ->
        drawCircle(
            color = Color(0xFF292825),
            radius = outerRadius * ringFrac,
            center = center,
            style = Stroke(width = 1.2f),
        )
    }
    val sheenDiameter = outerRadius * 1.64f
    drawArc(
        color = Color(0x24E4DFDA),
        startAngle = 212f,
        sweepAngle = 42f,
        useCenter = false,
        topLeft = Offset(center.x - sheenDiameter * 0.5f, center.y - sheenDiameter * 0.5f),
        size = Size(sheenDiameter, sheenDiameter),
        style = Stroke(width = outerRadius * 0.14f, cap = StrokeCap.Round),
    )
    drawCircle(color = colors.accentEcru, radius = outerRadius * 0.30f, center = center)
    drawCircle(color = colors.accentVerdigris, radius = outerRadius * 0.12f, center = center)
    drawCircle(color = Color(0xFF141413), radius = outerRadius * 0.05f, center = center)
}

@Composable
internal fun CompactCardActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RocheButtonStyle = RocheButtonStyle.SECONDARY,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val isPrimary = style == RocheButtonStyle.PRIMARY
    val bg = if (isPrimary) colors.accentBlue else colors.surfaceElevated
    val border = if (isPrimary) colors.accentBlue else colors.border
    val textColor = if (isPrimary) Color.White else colors.textPrimary

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(33.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, border, shape)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 8.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun buildCollectionDrawSleeveBadge(draw: CollectionDrawSnapshot): String = buildList {
    if (draw.drawNumber > 0) add("#${draw.drawNumber}")
    if (draw.releaseDate.isNotBlank()) add(draw.releaseDate)
}
    .joinToString(" · ")

internal fun buildCollectionDrawTagKicker(draw: CollectionDrawSnapshot): String =
    draw.tags.joinToString(" / ") { it.uppercase() }

@Composable
internal fun CollectionDrawPlaceholderBody(
    collectionTitle: String,
    completedCount: Int,
    totalCount: Int,
    onOpenList: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val statusText =
        if (totalCount > 0 && completedCount >= totalCount) {
            "All $totalCount entries in $collectionTitle completed!"
        } else {
            "Add unconsumed entries to $collectionTitle to draw your next item."
        }
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1.35f).background(colors.surfaceElevated)) {
        Canvas(modifier = Modifier.fillMaxSize()) { drawVinylSleeveArtwork(colors) }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = statusText,
            color = colors.textSecondary,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
        )
        CompactCardActionButton(
            label = "View List",
            style = RocheButtonStyle.PRIMARY,
            onClick = onOpenList,
            modifier = Modifier.fillMaxWidth().testTag("collection_draw_view_list_btn"),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeMediaCompanionWidget(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier = modifier.fillMaxWidth().testTag("home_widget_media_companion"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RocheSectionHeader(label = "Lists, Diary & Rankings")
        RocheSurfaceCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            "${state.catalog.consumedCount} logged · " +
                                "${state.catalog.comparisonCount} comparisons · " +
                                "${state.catalog.wantItemCount} want",
                        color = colors.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                    )
                    HeaderActionButton(
                        label = "+ Log",
                        style = RocheButtonStyle.PRIMARY,
                        onClick = { onAction(MainAction.OpenQuickLogSheet(false)) },
                        modifier = Modifier.testTag("home_quick_log_btn"),
                    )
                }
                CompanionShortcutChips(
                    enabledGenerators = state.enabledGenerators,
                    onAction = onAction,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanionShortcutChips(
    enabledGenerators: List<CollectionGeneratorState>,
    onAction: (MainAction) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChipPill(
            label = "Lists",
            selected = false,
            onClick = { onAction(MainAction.OpenCompanionSection(CompanionSubTab.LISTS)) },
            modifier = Modifier.testTag("home_open_lists_btn"),
        )
        enabledGenerators.forEach { gen ->
            FilterChipPill(
                label = gen.collectionTitle,
                selected = false,
                onClick = {
                    onAction(
                        MainAction.OpenCompanionSection(CompanionSubTab.LISTS, gen.collectionId)
                    )
                },
                modifier = Modifier.testTag("home_open_draw_list_${gen.collectionSlug}"),
            )
        }
        FilterChipPill(
            label = "Diary",
            selected = false,
            onClick = { onAction(MainAction.OpenCompanionSection(CompanionSubTab.DIARY)) },
            modifier = Modifier.testTag("home_open_diary_btn"),
        )
        FilterChipPill(
            label = "Want Queue",
            selected = false,
            onClick = { onAction(MainAction.OpenCompanionSection(CompanionSubTab.WANT)) },
            modifier = Modifier.testTag("home_open_want_btn"),
        )
    }
}

@Composable
internal fun HomeUpNextWidget(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
    twoColumnCards: Boolean = false,
) {
    val colors = LocalRocheColors.current
    val spotlightEntries =
        remember(state.activePlayerEntry, state.queueEntries, state.allEntries) {
            buildHomeSpotlightEntries(state)
        }

    Column(
        modifier = modifier.fillMaxWidth().testTag("home_widget_up_next"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RocheSectionHeader(label = "Continue & Up Next")
        when {
            spotlightEntries.isEmpty() -> HomeUpNextEmptyCard(onAction = onAction)
            twoColumnCards && spotlightEntries.size >= 2 ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    spotlightEntries.forEach { entry ->
                        RocheSurfaceCard(modifier = Modifier.weight(1f)) {
                            HomeSpotlightRow(
                                entry = entry,
                                isPlayingAudio = entry.id == state.playerState.currentEntryId,
                                inQueue = state.isEntryInQueue(entry.id),
                                onClick = { onAction(MainAction.OpenReader(entry.id)) },
                            )
                        }
                    }
                }
            else ->
                RocheSurfaceCard {
                    spotlightEntries.forEachIndexed { index, entry ->
                        if (index > 0) {
                            HorizontalDivider(color = colors.border, thickness = 1.dp)
                        }
                        HomeSpotlightRow(
                            entry = entry,
                            isPlayingAudio = entry.id == state.playerState.currentEntryId,
                            inQueue = state.isEntryInQueue(entry.id),
                            onClick = { onAction(MainAction.OpenReader(entry.id)) },
                        )
                    }
                }
        }
    }
}

@Composable
internal fun HomeUpNextEmptyCard(onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    RocheSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Nothing queued right now.",
                color = colors.textSecondary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderActionButton(
                    label = "Open Inbox",
                    onClick = { onAction(MainAction.SelectTab(AppTab.INBOX)) },
                )
                HeaderActionButton(
                    label = "Save URL or Feed",
                    onClick = { onAction(MainAction.OpenAddSheet()) },
                )
            }
        }
    }
}

internal fun buildHomeSpotlightEntries(state: MainUiState): List<InboxEntry> {
    val picked = LinkedHashMap<String, InboxEntry>()
    state.activePlayerEntry?.let { picked[it.id] = it }
    for (queued in state.queueEntries) {
        if (picked.size >= MAX_SPOTLIGHT_ITEMS) break
        picked.putIfAbsent(queued.id, queued)
    }
    if (picked.size < MAX_SPOTLIGHT_ITEMS) {
        state.allEntries
            .filter { !it.isArchived && it.readProgress > 0.0 && it.readProgress < 0.98 }
            .forEach { entry ->
                if (picked.size < MAX_SPOTLIGHT_ITEMS) {
                    picked.putIfAbsent(entry.id, entry)
                }
            }
    }
    if (picked.size < MAX_SPOTLIGHT_ITEMS) {
        state.allEntries
            .filter { !it.isArchived && it.isUnread }
            .forEach { entry ->
                if (picked.size < MAX_SPOTLIGHT_ITEMS) {
                    picked.putIfAbsent(entry.id, entry)
                }
            }
    }
    return picked.values.toList()
}

@Composable
internal fun HomeSpotlightRow(
    entry: InboxEntry,
    isPlayingAudio: Boolean,
    inQueue: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
    val badge =
        when {
            isPlayingAudio -> "NOW PLAYING"
            inQueue -> "QUEUED"
            entry.readProgress > 0.0 -> "${(entry.readProgress * 100).toInt()}% READ"
            else -> "LATEST UNREAD"
        }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 12.dp)
                .testTag("home_spotlight_${entry.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HomeSpotlightSourceHeader(entry = entry, badge = badge)
            Text(
                text = entry.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.showsThumbnailPreview) {
            EntryThumbnailPreview(entry = entry)
        }
    }
}

@Composable
internal fun HomeSpotlightSourceHeader(
    entry: InboxEntry,
    badge: String,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SiteFaviconBadge(
                primaryFaviconUrl = entry.faviconUrl,
                fallbackFaviconUrl = entry.fallbackFaviconUrl,
                domain = entry.displayDomain,
                title = entry.feedTitle,
                size = 15.dp,
            )
            Text(
                text = entry.feedTitle,
                color = colors.textSecondary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = badge,
            color = colors.accentVerdigris,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun HomeActiveSourcesWidget(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
    splitColumnsWhenUnconfigured: Boolean = false,
) {
    val colors = LocalRocheColors.current
    val topUnreadFeeds =
        remember(state.activeFeeds) {
            state.activeFeeds
                .filter { it.unreadCount > 0 }
                .sortedByDescending { it.unreadCount }
                .take(MAX_WIDGET_FEEDS)
        }
    val topTags = remember(state.tags) { state.tags.take(MAX_WIDGET_TAGS) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("home_widget_sources"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RocheSectionHeader(label = "Active Feeds & Topics")
        when {
            topUnreadFeeds.isEmpty() && topTags.isEmpty() ->
                RocheSurfaceCard {
                    Box(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            text = "All subscribed feeds are caught up.",
                            color = colors.textMuted,
                            fontSize = 12.5.sp,
                        )
                    }
                }
            splitColumnsWhenUnconfigured && topUnreadFeeds.isNotEmpty() && topTags.isNotEmpty() ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RocheSurfaceCard(modifier = Modifier.weight(1f)) {
                        HomeUnreadFeedsList(topUnreadFeeds = topUnreadFeeds, onAction = onAction)
                    }
                    RocheSurfaceCard(modifier = Modifier.weight(1f)) {
                        HomeTopicsFlowGrid(topTags = topTags, onAction = onAction)
                    }
                }
            else ->
                RocheSurfaceCard {
                    HomeUnreadFeedsList(topUnreadFeeds = topUnreadFeeds, onAction = onAction)
                    if (topTags.isNotEmpty()) {
                        if (topUnreadFeeds.isNotEmpty()) {
                            HorizontalDivider(color = colors.border, thickness = 1.dp)
                        }
                        HomeTopicsFlowGrid(topTags = topTags, onAction = onAction)
                    }
                }
        }
    }
}

@Composable
internal fun HomeUnreadFeedsList(
    topUnreadFeeds: List<FeedItem>,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    topUnreadFeeds.forEachIndexed { index, feed ->
        if (index > 0) {
            HorizontalDivider(
                color = colors.border,
                thickness = 1.dp,
                modifier = Modifier.padding(start = 42.dp),
            )
        }
        HomeFeedShortcutRow(
            feed = feed,
            onClick = { onAction(MainAction.FilterByFeed(feed.id)) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeTopicsFlowGrid(
    topTags: List<TagItem>,
    onAction: (MainAction) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        topTags.forEach { tag ->
            FilterChipPill(
                label = "#${tag.name} (${tag.unreadCount})",
                selected = false,
                onClick = { onAction(MainAction.FilterByTag(tag.name)) },
            )
        }
    }
}

@Composable
internal fun HomeFeedShortcutRow(
    feed: FeedItem,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 10.dp)
                .testTag("home_feed_${feed.id}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SiteFaviconBadge(
                primaryFaviconUrl = feed.faviconUrl,
                fallbackFaviconUrl = feed.fallbackFaviconUrl,
                domain = feed.displayDomain,
                title = feed.title,
                feedType = feed.feedType,
                size = 22.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = feed.title,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = feed.unreadCount.toString(),
            color = colors.accentBlue,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val BENTO_TWO_COLUMN_BREAKPOINT = 340.dp
private val WIDE_DASHBOARD_BREAKPOINT = 680.dp
private const val MAX_SPOTLIGHT_ITEMS = 2
private const val MAX_WIDGET_FEEDS = 4
private const val MAX_WIDGET_TAGS = 6
