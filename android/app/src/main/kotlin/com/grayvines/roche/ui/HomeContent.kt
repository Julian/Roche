@file:Suppress("MagicNumber", "TooManyFunctions")

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.CollectionGeneratorState
import com.grayvines.roche.data.catalog.DiaryEntryItem
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.BottomTabIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheRefreshableTabScaffold
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.components.SkipDrawIcon
import com.grayvines.roche.ui.components.WikipediaIcon
import com.grayvines.roche.ui.components.drawArticleImagePlaceholder
import com.grayvines.roche.ui.components.rememberEntryThumbnailBitmap
import com.grayvines.roche.ui.components.rememberHighResImageBitmap
import com.grayvines.roche.ui.components.resolveCatalogEntryOpenAction
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import java.text.NumberFormat
import java.util.Locale

@Composable
fun HomeTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    @Suppress("UnusedParameter") activeAudioEntryId: String? = null,
    onOpenExternalUrl: (String) -> Unit = {},
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit = { draw ->
        val fallback =
            if (draw.isMusicKind) draw.youtubeMusicUrl else draw.primaryExternalUrl.orEmpty()
        if (fallback.isNotBlank()) {
            onOpenExternalUrl(fallback)
        }
    },
) {
    RocheRefreshableTabScaffold(
        isRefreshing = state.isSyncing,
        onRefresh = { onAction(MainAction.SyncNow) },
        pullToRefreshTestTag = "home_pull_to_refresh",
        modifier = Modifier.testTag("home_screen"),
        header = { HomeTopHeaderBar(onAction = onAction) },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideLayout = maxWidth >= WIDE_DASHBOARD_BREAKPOINT
            HomeScrollableGrid(
                state = state,
                scrollState = scrollState,
                isWideLayout = isWideLayout,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                onOpenCollectionDraw = onOpenCollectionDraw,
            )
        }
    }
}

@Composable
private fun HomeTopHeaderBar(onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    RocheTopHeader(
        title = "Roche",
        titleTrailingAction = {
            RocheIconActionButton(
                contentDescription = "Settings",
                outlined = false,
                onClick = { onAction(MainAction.SelectTab(AppTab.SETTINGS)) },
                modifier = Modifier.testTag("home_spare_settings_btn"),
            ) {
                BottomTabIcon(tab = AppTab.SETTINGS, tint = colors.textMuted, size = 17.dp)
            }
        },
    ) {
        RocheIconActionButton(
            contentDescription = "Search",
            outlined = true,
            onClick = { onAction(MainAction.OpenSearch) },
            modifier = Modifier.testTag("home_search_btn"),
        ) {
            SearchIcon(tint = colors.textSecondary, size = 17.dp)
        }
    }
}

@Composable
internal fun HomeScrollableGrid(
    state: MainUiState,
    scrollState: ScrollState,
    isWideLayout: Boolean,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
) {
    val activeDraws = state.activeCollectionDraws
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (isWideLayout) {
            HomeWideTwoColumnDashboard(
                state = state,
                activeDraws = activeDraws,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                onOpenCollectionDraw = onOpenCollectionDraw,
            )
        } else {
            HomeSingleColumnDashboard(
                state = state,
                activeDraws = activeDraws,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                onOpenCollectionDraw = onOpenCollectionDraw,
            )
        }
        Spacer(modifier = Modifier.height(76.dp))
    }
}

@Composable
private fun HomeWideTwoColumnDashboard(
    state: MainUiState,
    activeDraws: List<CollectionGeneratorState>,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1.12f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HomeInboxWidget(
                state = state,
                maxItems = WIDE_INBOX_ITEMS,
                onAction = onAction,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            activeDraws.forEach { gen ->
                HomeCollectionDrawWidget(
                    generator = gen,
                    onAction = onAction,
                    onOpenExternalUrl = onOpenExternalUrl,
                    onOpenCollectionDraw = onOpenCollectionDraw,
                )
            }
            HomeRecentDiaryWidget(
                state = state,
                maxItems = WIDE_DIARY_ITEMS,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }
}

@Composable
private fun HomeSingleColumnDashboard(
    state: MainUiState,
    activeDraws: List<CollectionGeneratorState>,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
) {
    activeDraws.forEach { gen ->
        HomeCollectionDrawWidget(
            generator = gen,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
            onOpenCollectionDraw = onOpenCollectionDraw,
        )
    }
    HomeInboxWidget(
        state = state,
        maxItems = COMPACT_INBOX_ITEMS,
        onAction = onAction,
    )
    HomeRecentDiaryWidget(
        state = state,
        maxItems = COMPACT_DIARY_ITEMS,
        onAction = onAction,
        onOpenExternalUrl = onOpenExternalUrl,
    )
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RocheSectionHeader(
            label = "Daily Draw",
            actionLabel = "${generator.collectionTitle} →",
            actionTestTag = "collection_draw_header_open_list_btn",
            onActionClick = openCollectionList,
        )
        RocheSurfaceCard {
            if (draw != null) {
                CollectionDrawSleeveCardBody(
                    generator = generator,
                    draw = draw,
                    onAction = onAction,
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
private fun CollectionDrawSleeveCardBody(
    generator: CollectionGeneratorState,
    draw: CollectionDrawSnapshot,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CollectionDrawTitleRow(
            draw = draw,
            onOpenDraw = { onOpenCollectionDraw(draw) },
            onSkip = {
                onAction(
                    MainAction.OpenDrawNoteDialog(
                        generator.collectionId,
                        DrawNoteDialogMode.ABANDON,
                    )
                )
            },
        )
        CollectionDrawMediaRow(
            draw = draw,
            onOpenDraw = { onOpenCollectionDraw(draw) },
            onOpenExternalUrl = onOpenExternalUrl,
        )
        HorizontalDivider(
            color = colors.border,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        CollectionDrawFooterRow(
            generator = generator,
            draw = draw,
            onRank = {
                onAction(
                    MainAction.OpenDrawNoteDialog(
                        generator.collectionId,
                        DrawNoteDialogMode.FINISH_AND_RANK,
                    )
                )
            },
        )
    }
}

@Composable
private fun CollectionDrawTitleRow(
    draw: CollectionDrawSnapshot,
    onOpenDraw: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val titleText =
        remember(draw.title, draw.releaseDate, colors, headlineFamily) {
            buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = colors.textPrimary,
                        fontFamily = headlineFamily,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                ) {
                    append(draw.title)
                }
                if (draw.releaseDate.isNotBlank()) {
                    append("  ")
                    withStyle(
                        SpanStyle(
                            color = colors.textMuted,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                        )
                    ) {
                        append(draw.releaseDate)
                    }
                }
            }
        }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = titleText,
            lineHeight = 21.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.weight(1f)
                    .clickable(onClick = onOpenDraw)
                    .padding(vertical = 4.dp)
                    .testTag("collection_draw_meta"),
        )
        RocheIconActionButton(
            contentDescription = "Skip ${draw.title}",
            outlined = false,
            onClick = onSkip,
            modifier = Modifier.testTag("collection_draw_skip_btn"),
        ) {
            SkipDrawIcon(tint = colors.textMuted, size = 15.dp)
        }
    }
}

@Composable
private fun CollectionDrawMediaRow(
    draw: CollectionDrawSnapshot,
    onOpenDraw: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val genreText = remember(draw.tags) { buildCollectionDrawGenresLine(draw) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CollectionDrawCompactCover(draw = draw, onClick = onOpenDraw)
        Column(
            modifier = Modifier.weight(1f).heightIn(min = 74.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (draw.creator.isNotBlank()) {
                Text(
                    text = draw.creator,
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.fillMaxWidth().clickable(onClick = onOpenDraw).padding(top = 1.dp),
                )
            } else {
                Spacer(modifier = Modifier.height(1.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = genreText,
                    color = colors.textMuted,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                )
                val wikiUrl = draw.wikipediaUrl
                if (!wikiUrl.isNullOrBlank()) {
                    CollectionDrawWikiIconButton(
                        title = draw.title,
                        onClick = { onOpenExternalUrl(wikiUrl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionDrawWikiIconButton(
    title: String,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Open Wikipedia for $title"
                }
                .testTag("collection_draw_wiki_btn"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(width = 30.dp, height = 28.dp)
                    .clip(shape)
                    .border(1.dp, colors.border, shape)
                    .clearAndSetSemantics {},
        ) {
            WikipediaIcon(tint = colors.textSecondary, size = 14.dp)
        }
    }
}

@Composable
private fun CollectionDrawFooterRow(
    generator: CollectionGeneratorState,
    draw: CollectionDrawSnapshot,
    onRank: () -> Unit,
) {
    val totalCount = generator.totalCount.coerceAtLeast(draw.totalCount)
    val completedCount =
        if (generator.completedCount > 0) {
            generator.completedCount
        } else {
            (draw.drawNumber - 1).coerceAtLeast(0)
        }
    val progressFraction =
        if (totalCount > 0) {
            (completedCount.toFloat() / totalCount.toFloat()).coerceIn(0.02f, 1f)
        } else {
            0.05f
        }
    val progressLabel = formatDrawProgressLabel(draw.drawNumber, completedCount, totalCount)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CollectionDrawProgressMeter(
            collectionTitle = generator.collectionTitle,
            progressLabel = progressLabel,
            progressFraction = progressFraction,
            modifier = Modifier.weight(1f),
        )
        CompactRankButton(
            onClick = onRank,
            modifier = Modifier.testTag("collection_draw_rank_btn"),
        )
    }
}

@Composable
private fun CollectionDrawProgressMeter(
    collectionTitle: String,
    progressLabel: String,
    progressFraction: Float,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val barShape = RoundedCornerShape(999.dp)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = collectionTitle,
                color = colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp),
            )
            Text(
                text = progressLabel,
                color = colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(barShape).background(colors.border)
        ) {
            Box(
                modifier =
                    Modifier.fillMaxWidth(progressFraction)
                        .height(4.dp)
                        .clip(barShape)
                        .background(colors.accentBlue)
            )
        }
    }
}

private fun formatDrawProgressLabel(drawNumber: Int, completedCount: Int, totalCount: Int): String =
    when {
        totalCount > 0 && drawNumber > 0 -> "#$drawNumber / ${formatIntegerCount(totalCount)}"
        totalCount > 0 ->
            "${formatIntegerCount(completedCount)} / ${formatIntegerCount(totalCount)}"
        else -> "#$drawNumber"
    }

@Composable
private fun CompactRankButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 32.dp)
                    .clip(shape)
                    .background(colors.accentBlueFilled)
                    .padding(horizontal = 13.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Rank",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CollectionDrawCompactCover(
    draw: CollectionDrawSnapshot,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(7.dp)
    val bitmap =
        rememberHighResImageBitmap(
            primaryKey = draw.imageUrl,
            fallbackKey = draw.thumbnailUrl,
        )
    val desc = if (draw.creator.isNotBlank()) "${draw.title} by ${draw.creator}" else draw.title

    Box(
        modifier =
            Modifier.size(74.dp)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape)
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
        AlbumCoverPlayBadge(
            accentColor = if (draw.isMusicKind) SPOTIFY_GREEN else colors.accentBlue,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        )
    }
}

@Composable
private fun AlbumCoverPlayBadge(
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color(0xDC141413))
                .border(width = 1.dp, color = accentColor.copy(alpha = 0.85f), shape = CircleShape),
    ) {
        Canvas(modifier = Modifier.size(9.dp)) {
            val w = size.width
            val h = size.height
            val tri =
                Path().apply {
                    moveTo(w * 0.24f, h * 0.14f)
                    lineTo(w * 0.88f, h * 0.50f)
                    lineTo(w * 0.24f, h * 0.86f)
                    close()
                }
            drawPath(path = tri, color = accentColor)
        }
    }
}

internal fun DrawScope.drawVinylSleeveArtwork(colors: RocheColors) {
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val outerRadius = size.minDimension * 0.40f

    drawRect(color = if (colors.isDark) Color(0xFF1E1D1B) else Color(0xFFDCD6CE))
    drawCircle(color = Color(0xFF141413), radius = outerRadius, center = center)
    drawCircle(
        color = Color(0xFF2E2C29),
        radius = outerRadius,
        center = center,
        style = Stroke(width = 2f),
    )
    listOf(0.84f, 0.66f, 0.48f).forEach { ringFrac ->
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
private fun CollectionDrawPlaceholderBody(
    collectionTitle: String,
    completedCount: Int,
    totalCount: Int,
    onOpenList: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val statusText =
        if (totalCount > 0 && completedCount >= totalCount) {
            "All $totalCount entries in $collectionTitle completed."
        } else {
            "Add unconsumed entries to $collectionTitle to draw your next item."
        }
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
        HeaderActionButton(
            label = "View List",
            style = RocheButtonStyle.PRIMARY,
            onClick = onOpenList,
            modifier = Modifier.testTag("collection_draw_view_list_btn"),
        )
    }
}

@Composable
internal fun HomeInboxWidget(
    state: MainUiState,
    maxItems: Int,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedInboxEntries =
        remember(state.allEntries) { state.allEntries.filter { it.inFeedInbox && !it.isArchived } }
    val unreadEntries = remember(feedInboxEntries) { feedInboxEntries.filter { it.isUnread } }
    val unreadCount = unreadEntries.size
    val previewEntries =
        remember(unreadEntries, feedInboxEntries, maxItems) {
            unreadEntries.ifEmpty { feedInboxEntries }.take(maxItems)
        }
    val openInbox = { onAction(MainAction.SelectTab(AppTab.INBOX)) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("home_widget_inbox"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RocheSectionHeader(
            label = "Inbox",
            badgeText = if (unreadCount > 0) formatIntegerCount(unreadCount) else null,
            actionLabel = "All →",
            actionTestTag = "home_open_inbox_btn",
            onActionClick = openInbox,
        )
        RocheSurfaceCard {
            if (previewEntries.isEmpty()) {
                HomeInboxEmptyBody(onOpenInbox = openInbox, onAction = onAction)
            } else {
                HomeInboxPreviewList(
                    previewEntries = previewEntries,
                    onOpenEntry = { onAction(MainAction.OpenReader(it)) },
                )
            }
        }
    }
}

@Composable
private fun HomeInboxPreviewList(
    previewEntries: List<InboxEntry>,
    onOpenEntry: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    previewEntries.forEachIndexed { index, entry ->
        if (index > 0) {
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
        HomeInboxPreviewRow(entry = entry, onClick = { onOpenEntry(entry.id) })
    }
}

@Composable
private fun HomeInboxEmptyBody(
    onOpenInbox: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Your feed inbox is caught up.",
            color = colors.textMuted,
            fontSize = 12.5.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderActionButton(label = "Open Inbox", onClick = onOpenInbox)
            HeaderActionButton(
                label = "Manage Feeds",
                onClick = { onAction(MainAction.SelectTab(AppTab.FEEDS)) },
            )
        }
    }
}

@Composable
private fun HomeInboxPreviewRow(
    entry: InboxEntry,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("home_inbox_item_${entry.id}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (entry.showsThumbnailPreview) {
            HomeCompactEntryThumbnail(entry = entry)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = entry.title,
                color = if (entry.isUnread) colors.textPrimary else colors.textSecondary,
                fontFamily = headlineFamily,
                fontSize = 14.5.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                SiteFaviconBadge(
                    primaryFaviconUrl = entry.faviconUrl,
                    fallbackFaviconUrl = entry.fallbackFaviconUrl,
                    domain = entry.displayDomain,
                    title = entry.feedTitle,
                    size = 14.dp,
                )
                Text(
                    text = entry.feedTitle,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = "· ${entry.formattedPublishedLabel} · ${entry.readEstimateLabel}",
                    color = colors.textMuted,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HomeCompactEntryThumbnail(entry: InboxEntry) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(5.dp)
    val landscapePath = remember { Path() }
    val bitmap =
        rememberEntryThumbnailBitmap(
            primaryKey = entry.thumbnailMediaId,
            fallbackKey = entry.effectiveThumbnailUrl,
        )
    Box(
        modifier =
            Modifier.padding(top = 2.dp)
                .size(width = 56.dp, height = 40.dp)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawArticleImagePlaceholder(colors = colors, landscape = landscapePath)
            }
        }
    }
}

@Composable
internal fun HomeRecentDiaryWidget(
    state: MainUiState,
    maxItems: Int,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val diaryEntries =
        remember(state.catalog.diaryEntries, maxItems) { state.catalog.diaryEntries.take(maxItems) }
    val readableIds = remember(state.allEntries) { state.allEntries.mapTo(HashSet()) { it.id } }
    val catalogUrlsById =
        remember(state.catalog.entriesByCollectionId) {
            buildMap {
                state.catalog.entriesByCollectionId.values.flatten().forEach { item ->
                    item.entry.url?.takeIf { it.isNotBlank() }?.let { put(item.entry.id, it) }
                }
            }
        }
    val openDiary = { onAction(MainAction.OpenCompanionSection(CompanionSubTab.DIARY)) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("home_widget_recent_diary"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RocheSectionHeader(
            label = "Recent Diary",
            actionLabel = "Diary →",
            actionTestTag = "home_open_diary_btn",
            onActionClick = openDiary,
        )
        RocheSurfaceCard {
            if (diaryEntries.isEmpty()) {
                HomeRecentDiaryEmptyBody(onAction = onAction)
            } else {
                HomeRecentDiaryList(
                    diaryEntries = diaryEntries,
                    readableIds = readableIds,
                    catalogUrlsById = catalogUrlsById,
                    onOpenDiary = openDiary,
                    onAction = onAction,
                    onOpenExternalUrl = onOpenExternalUrl,
                )
            }
        }
    }
}

@Composable
private fun HomeRecentDiaryList(
    diaryEntries: List<DiaryEntryItem>,
    readableIds: Set<String>,
    catalogUrlsById: Map<String, String>,
    onOpenDiary: () -> Unit,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    diaryEntries.forEachIndexed { index, diary ->
        if (index > 0) {
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
        val onClick =
            resolveCatalogEntryOpenAction(
                entryId = diary.entryId,
                externalUrl = catalogUrlsById[diary.entryId],
                readableIds = readableIds,
                onOpenReader = { onAction(MainAction.OpenReader(it)) },
                onOpenExternalUrl = onOpenExternalUrl,
                entryKind = diary.kind,
                onOpenCatalogDetail = { onAction(MainAction.OpenCatalogDetailSheet(it)) },
            ) ?: onOpenDiary
        HomeDiaryPreviewRow(diary = diary, onClick = onClick)
    }
}

@Composable
private fun HomeRecentDiaryEmptyBody(onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "No diary entries logged yet.",
            color = colors.textMuted,
            fontSize = 12.5.sp,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        HeaderActionButton(
            label = "+ Log",
            onClick = { onAction(MainAction.OpenQuickLogSheet(false)) },
            modifier = Modifier.testTag("home_quick_log_btn"),
        )
    }
}

@Composable
private fun HomeDiaryPreviewRow(
    diary: DiaryEntryItem,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val subtitle = remember(diary.creators, diary.releaseYear) { buildHomeDiarySubtitle(diary) }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 8.dp)
                .testTag("home_diary_item_${diary.id}"),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = diary.dateLabel,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 38.dp),
        )
        HomeDiaryThumbnail(thumbnailUrl = diary.thumbnailUrl, kind = diary.kind)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = diary.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = colors.textSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (diary.tier != null) {
            HomeTierPill(tier = diary.tier)
        }
    }
}

@Composable
private fun HomeDiaryThumbnail(thumbnailUrl: String?, kind: EntryKind) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(5.dp)
    val bitmap = rememberEntryThumbnailBitmap(primaryKey = null, fallbackKey = thumbnailUrl)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(32.dp)
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
            Text(
                text = if (kind == EntryKind.ALBUM || kind == EntryKind.SONG) "♪" else "▪",
                color = colors.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun HomeTierPill(tier: Char) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val tierColor = resolveTierColor(tier, colors)
    val shape = RoundedCornerShape(4.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(20.dp).clip(shape).border(1.dp, tierColor.copy(alpha = 0.72f), shape),
    ) {
        Text(
            text = tier.toString(),
            color = tierColor,
            fontFamily = headlineFamily,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

internal fun buildCollectionDrawGenresLine(draw: CollectionDrawSnapshot): String =
    draw.tags.take(3).joinToString(", ")

internal fun buildHomeDiarySubtitle(diary: DiaryEntryItem): String {
    val creatorText = diary.creators.joinToString(", ")
    val year = diary.releaseYear?.takeIf { it > 0 }
    return when {
        creatorText.isNotBlank() && year != null -> "$creatorText ($year)"
        creatorText.isNotBlank() -> creatorText
        year != null -> year.toString()
        else -> ""
    }
}

private fun formatIntegerCount(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(value)

private val WIDE_DASHBOARD_BREAKPOINT = 540.dp
private val SPOTIFY_GREEN = Color(0xFF1ED760)
private const val WIDE_INBOX_ITEMS = 7
private const val COMPACT_INBOX_ITEMS = 4
private const val WIDE_DIARY_ITEMS = 6
private const val COMPACT_DIARY_ITEMS = 3
