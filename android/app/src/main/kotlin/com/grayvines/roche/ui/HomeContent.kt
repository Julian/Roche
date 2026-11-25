@file:Suppress("MagicNumber", "TooManyFunctions")

package com.grayvines.roche.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.formatInlineMathAnnotated
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.CollectionGeneratorState
import com.grayvines.roche.data.catalog.RankedCatalogEntry
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.extractCleanDomain
import com.grayvines.roche.ui.components.BottomTabIcon
import com.grayvines.roche.ui.components.EntrySourceMetadataRow
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheRefreshableTabScaffold
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.SkipDrawIcon
import com.grayvines.roche.ui.components.WikipediaIcon
import com.grayvines.roche.ui.components.rememberEntryThumbnailBitmap
import com.grayvines.roche.ui.components.rememberHighResImageBitmap
import com.grayvines.roche.ui.components.resolveCatalogEntryOpenAction
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeTabContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    activeAudioEntryId: String? = null,
    onOpenExternalUrl: (String) -> Unit = {},
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit = { draw ->
        onAction(MainAction.LaunchCollectionDraw(draw))
    },
) {
    val fontScale = LocalDensity.current.fontScale
    RocheRefreshableTabScaffold(
        isRefreshing = state.isManualRefreshing,
        onRefresh = { onAction(MainAction.ManualRefresh) },
        pullToRefreshTestTag = "home_pull_to_refresh",
        modifier = Modifier.testTag("home_screen"),
        header = { HomeTopHeaderBar(onAction = onAction) },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val inboxMaxItems =
                computeHomeInboxMaxItems(
                    viewportWidth = maxWidth,
                    viewportHeight = maxHeight,
                    fontScale = fontScale,
                )
            HomeScrollableGrid(
                state = state,
                scrollState = scrollState,
                viewportWidth = maxWidth,
                fontScale = fontScale,
                inboxMaxItems = inboxMaxItems,
                activeAudioEntryId = activeAudioEntryId,
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
@Suppress("LongParameterList")
internal fun HomeScrollableGrid(
    state: MainUiState,
    scrollState: ScrollState,
    viewportWidth: Dp,
    inboxMaxItems: Int,
    activeAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    fontScale: Float = 1f,
) {
    val activeDraws = state.activeCollectionDraws
    val bottomLayout =
        resolveHomeBottomLayout(
            viewportWidth = viewportWidth,
            hasActiveDraws = activeDraws.isNotEmpty(),
            fontScale = fontScale,
        )
    val homeContinueItems =
        remember(state.inProgressItems) { state.inProgressItems.take(HOME_CONTINUE_ITEMS) }
    val shownContinueIds =
        remember(homeContinueItems) { homeContinueItems.mapTo(HashSet()) { it.entryId } }
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = HOME_HORIZONTAL_PADDING, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (homeContinueItems.isNotEmpty()) {
            HomeContinueWidget(
                items = homeContinueItems,
                totalInProgressCount = state.inProgressItems.size,
                readableIds = state.readableEntryIds,
                activeAudioEntryId = activeAudioEntryId,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
        HomeInboxWidget(
            state = state,
            maxItems = inboxMaxItems,
            excludeEntryIds = shownContinueIds,
            activeAudioEntryId = activeAudioEntryId,
            onAction = onAction,
        )
        HomeBottomWidgetsSection(
            state = state,
            activeDraws = activeDraws,
            bottomLayout = bottomLayout,
            shownContinueIds = shownContinueIds,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
            onOpenCollectionDraw = onOpenCollectionDraw,
        )
        Spacer(modifier = Modifier.fillMaxWidth().height(76.dp).testTag("home_bottom_spacer"))
    }
}

@Composable
@Suppress("LongParameterList")
private fun HomeBottomWidgetsSection(
    state: MainUiState,
    activeDraws: List<CollectionGeneratorState>,
    bottomLayout: HomeBottomLayout,
    shownContinueIds: Set<String>,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
) {
    val renderQueue: @Composable (Modifier) -> Unit = { queueModifier ->
        HomeWantQueueWidget(
            state = state,
            maxItems = HOME_QUEUE_PREVIEW_ITEMS,
            excludeEntryIds = shownContinueIds,
            isCompactColumn = bottomLayout.isCompactColumn,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
            modifier = queueModifier,
        )
    }
    val renderDraws: @Composable () -> Unit = {
        activeDraws.forEach { gen ->
            HomeCollectionDrawWidget(
                generator = gen,
                resolvingMusicEntryId = state.resolvingMusicEntryId,
                isCompactColumn = bottomLayout.isCompactColumn,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
                onOpenCollectionDraw = onOpenCollectionDraw,
            )
        }
    }
    if (bottomLayout.useTwoColumns) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag("home_bottom_two_column_row"),
            horizontalArrangement = Arrangement.spacedBy(bottomLayout.columnGap),
            verticalAlignment = Alignment.Top,
        ) {
            renderQueue(Modifier.weight(1f))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                renderDraws()
            }
        }
    } else {
        renderQueue(Modifier)
        renderDraws()
    }
}

@Composable
internal fun HomeCollectionDrawWidget(
    generator: CollectionGeneratorState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    modifier: Modifier = Modifier,
    resolvingMusicEntryId: String? = null,
    isCompactColumn: Boolean = false,
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
            label = generator.collectionTitle,
            actionLabel = "List →",
            actionTestTag = "collection_draw_header_open_list_btn",
            onActionClick = openCollectionList,
        )
        RocheSurfaceCard {
            if (draw != null) {
                CollectionDrawCardBody(
                    generator = generator,
                    draw = draw,
                    isResolvingMusic =
                        resolvingMusicEntryId != null &&
                            resolvingMusicEntryId == draw.musicResolveKey(),
                    isCompactColumn = isCompactColumn,
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
private fun CollectionDrawCardBody(
    generator: CollectionGeneratorState,
    draw: CollectionDrawSnapshot,
    isResolvingMusic: Boolean,
    isCompactColumn: Boolean,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
) {
    val colors = LocalRocheColors.current
    val openDrawDetail = {
        if (draw.entryId.isNotBlank()) {
            onAction(MainAction.OpenCatalogDetailSheet(draw.entryId))
        } else {
            onOpenCollectionDraw(draw)
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = if (isCompactColumn) 8.dp else 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CollectionDrawUpperBody(
            draw = draw,
            isResolvingMusic = isResolvingMusic,
            isCompactColumn = isCompactColumn,
            onOpenCover = { onOpenCollectionDraw(draw) },
            onOpenDetail = openDrawDetail,
            onSkip = {
                onAction(
                    MainAction.OpenDrawNoteDialog(
                        generator.collectionId,
                        DrawNoteDialogMode.ABANDON,
                    )
                )
            },
            onOpenExternalUrl = onOpenExternalUrl,
        )
        HorizontalDivider(
            color = colors.border,
            thickness = 1.dp,
            modifier =
                Modifier.padding(
                    horizontal = if (isCompactColumn) 10.dp else 12.dp,
                    vertical = if (isCompactColumn) 0.dp else 2.dp,
                ),
        )
        CollectionDrawFooterRow(
            generator = generator,
            draw = draw,
            isCompactColumn = isCompactColumn,
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
private fun CollectionDrawUpperBody(
    draw: CollectionDrawSnapshot,
    isResolvingMusic: Boolean,
    isCompactColumn: Boolean,
    onOpenCover: () -> Unit,
    onOpenDetail: () -> Unit,
    onSkip: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    if (isCompactColumn) {
        CollectionDrawCompactHeader(
            draw = draw,
            isResolvingMusic = isResolvingMusic,
            onOpenCover = onOpenCover,
            onSkip = onSkip,
            onOpenExternalUrl = onOpenExternalUrl,
        )
        CollectionDrawCompactMeta(draw = draw, onOpenDetail = onOpenDetail)
    } else {
        CollectionDrawTitleRow(
            draw = draw,
            onOpenDetail = onOpenDetail,
            onSkip = onSkip,
        )
        CollectionDrawMediaRow(
            draw = draw,
            isResolvingMusic = isResolvingMusic,
            onOpenCover = onOpenCover,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

@Composable
private fun CollectionDrawCompactHeader(
    draw: CollectionDrawSnapshot,
    isResolvingMusic: Boolean,
    onOpenCover: () -> Unit,
    onSkip: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        CollectionDrawCompactCover(
            draw = draw,
            isResolvingMusic = isResolvingMusic,
            coverSize = 84.dp,
            onClick = onOpenCover,
        )
        Column(horizontalAlignment = Alignment.End) {
            CollectionDrawSkipButton(title = draw.title, onClick = onSkip)
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

@Composable
private fun CollectionDrawCompactMeta(
    draw: CollectionDrawSnapshot,
    onOpenDetail: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val titleText =
        rememberDrawTitleText(
            title = draw.title,
            releaseDate = draw.releaseDate,
            titleFontSize = 15.sp,
        )
    val genreText = remember(draw.tags) { buildCollectionDrawGenresLine(draw) }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onOpenDetail)
                .padding(horizontal = 10.dp, vertical = 2.dp)
                .testTag("collection_draw_meta"),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = titleText,
            lineHeight = 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (draw.creator.isNotBlank()) {
            Text(
                text = draw.creator,
                color = colors.textSecondary,
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (genreText.isNotBlank()) {
            Text(
                text = genreText,
                color = colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CollectionDrawSkipButton(
    title: String,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheIconActionButton(
        contentDescription = "Skip $title",
        outlined = false,
        onClick = onClick,
        modifier = Modifier.testTag("collection_draw_skip_btn"),
    ) {
        SkipDrawIcon(tint = colors.textMuted, size = 15.dp)
    }
}

@Composable
private fun rememberDrawTitleText(
    title: String,
    releaseDate: String,
    titleFontSize: TextUnit,
): AnnotatedString {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    return remember(title, releaseDate, titleFontSize, colors, headlineFamily) {
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = colors.textPrimary,
                    fontFamily = headlineFamily,
                    fontSize = titleFontSize,
                    fontWeight = FontWeight.Medium,
                )
            ) {
                append(title)
            }
            if (releaseDate.isNotBlank()) {
                append("  ")
                withStyle(
                    SpanStyle(
                        color = colors.textMuted,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                    )
                ) {
                    append(releaseDate)
                }
            }
        }
    }
}

@Composable
private fun CollectionDrawTitleRow(
    draw: CollectionDrawSnapshot,
    onOpenDetail: () -> Unit,
    onSkip: () -> Unit,
) {
    val titleText =
        rememberDrawTitleText(
            title = draw.title,
            releaseDate = draw.releaseDate,
            titleFontSize = 16.5.sp,
        )

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier =
                Modifier.weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onOpenDetail)
                    .padding(vertical = 4.dp)
                    .testTag("collection_draw_meta"),
        ) {
            Text(
                text = titleText,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CollectionDrawSkipButton(title = draw.title, onClick = onSkip)
    }
}

@Composable
private fun CollectionDrawMediaRow(
    draw: CollectionDrawSnapshot,
    isResolvingMusic: Boolean,
    onOpenCover: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val genreText = remember(draw.tags) { buildCollectionDrawGenresLine(draw) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CollectionDrawCompactCover(
            draw = draw,
            isResolvingMusic = isResolvingMusic,
            onClick = onOpenCover,
        )
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
                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
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
    isCompactColumn: Boolean,
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

    if (isCompactColumn) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CollectionDrawProgressMeter(
                leadingLabel = null,
                progressLabel = progressLabel,
                progressFraction = progressFraction,
                modifier = Modifier.fillMaxWidth(),
            )
            CompactRankButton(
                onClick = onRank,
                modifier = Modifier.testTag("collection_draw_rank_btn"),
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactRankButton(
                onClick = onRank,
                modifier = Modifier.testTag("collection_draw_rank_btn"),
            )
            CollectionDrawProgressMeter(
                leadingLabel = "Daily Draw",
                progressLabel = progressLabel,
                progressFraction = progressFraction,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CollectionDrawProgressMeter(
    leadingLabel: String?,
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
            horizontalArrangement =
                if (leadingLabel != null) Arrangement.SpaceBetween else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingLabel != null) {
                Text(
                    text = leadingLabel,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp),
                )
            }
            Text(
                text = progressLabel,
                color = colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
    isResolvingMusic: Boolean,
    onClick: () -> Unit,
    coverSize: Dp = 74.dp,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(7.dp)
    val bitmap =
        rememberHighResImageBitmap(
            primaryKey = draw.imageUrl,
            fallbackKey = draw.thumbnailUrl,
        )
    val desc =
        (if (draw.creator.isNotBlank()) "${draw.title} by ${draw.creator}" else draw.title)
            .ifBlank { "Open draw" }

    Box(
        modifier =
            Modifier.size(coverSize)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = desc
                }
                .testTag("collection_draw_cover")
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) { drawVinylSleeveArtwork(colors) }
        }
        if (isResolvingMusic) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.fillMaxSize()
                        .background(Color(0x99141413))
                        .testTag("collection_draw_resolving_spinner"),
            ) {
                CircularProgressIndicator(
                    color = colors.accentVerdigris,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else if (draw.isMusicKind) {
            AlbumCoverPlayBadge(
                accentColor = colors.accentVerdigris,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
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
@Suppress("LongParameterList")
internal fun HomeContinueWidget(
    items: List<InProgressItem>,
    totalInProgressCount: Int,
    readableIds: Set<String>,
    activeAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier = modifier.fillMaxWidth().testTag("home_widget_continue"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RocheSectionHeader(
            label = "Continue Reading & Listening",
            badgeText =
                if (totalInProgressCount > 0) formatIntegerCount(totalInProgressCount) else null,
            actionLabel = "Queue →",
            actionTestTag = "home_continue_open_queue_btn",
            onActionClick = { onAction(MainAction.SelectTab(AppTab.QUEUE)) },
        )
        RocheSurfaceCard {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
                InProgressRowDispatcher(
                    item = item,
                    readableIds = readableIds,
                    activeAudioEntryId = activeAudioEntryId,
                    onAction = onAction,
                    onOpenExternalUrl = onOpenExternalUrl,
                    testTagPrefix = "home_continue_item",
                )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun HomeInboxWidget(
    state: MainUiState,
    maxItems: Int,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
    excludeEntryIds: Set<String> = emptySet(),
    activeAudioEntryId: String? = null,
) {
    val newFeedEntries =
        remember(state.allEntries) { state.allEntries.filter { it.inFeedInbox && it.isNew } }
    val recentFeedEntries =
        remember(state.allEntries) { state.allEntries.filter { it.inFeedInbox } }
    val newCount = newFeedEntries.size
    val filteredNew =
        remember(newFeedEntries, excludeEntryIds) {
            newFeedEntries.filter { it.id !in excludeEntryIds }
        }
    val allNewInContinue = newCount > 0 && filteredNew.isEmpty()
    val previewEntries =
        remember(filteredNew, recentFeedEntries, excludeEntryIds, allNewInContinue, maxItems) {
            if (allNewInContinue) {
                emptyList()
            } else {
                val filteredRecent = recentFeedEntries.filter { it.id !in excludeEntryIds }
                filteredNew.ifEmpty { filteredRecent }.take(maxItems)
            }
        }
    val headerBadge =
        when {
            newCount > 0 -> formatIntegerCount(newCount)
            previewEntries.isNotEmpty() -> "Caught up · Recent"
            else -> null
        }
    val openInbox = { onAction(MainAction.SelectTab(AppTab.INBOX)) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("home_widget_inbox"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RocheSectionHeader(
            label = "Inbox",
            badgeText = headerBadge,
            actionLabel = "All →",
            actionTestTag = "home_open_inbox_btn",
            onActionClick = openInbox,
        )
        RocheSurfaceCard {
            when {
                allNewInContinue ->
                    HomeActionNoteCardBody(
                        message = "All unread items are currently in Continue above.",
                        actionLabel = "Open Inbox",
                        onActionClick = openInbox,
                    )
                previewEntries.isEmpty() ->
                    HomeInboxEmptyBody(onOpenInbox = openInbox, onAction = onAction)
                else ->
                    HomeInboxPreviewList(
                        previewEntries = previewEntries,
                        activeAudioEntryId = activeAudioEntryId,
                        onOpenEntry = { onAction(MainAction.OpenReader(it)) },
                        onLongPressEntry = { onAction(MainAction.OpenContextSheet(it)) },
                    )
            }
        }
    }
}

@Composable
private fun HomeActionNoteCardBody(
    message: String,
    actionLabel: String,
    onActionClick: () -> Unit,
    stackVertically: Boolean = false,
    actionTestTag: String? = null,
) {
    val colors = LocalRocheColors.current
    val buttonModifier = if (actionTestTag != null) Modifier.testTag(actionTestTag) else Modifier
    if (stackVertically) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                color = colors.textMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            HeaderActionButton(
                label = actionLabel,
                onClick = onActionClick,
                modifier = buttonModifier,
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = colors.textMuted,
                fontSize = 12.5.sp,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            HeaderActionButton(
                label = actionLabel,
                onClick = onActionClick,
                modifier = buttonModifier,
            )
        }
    }
}

@Composable
private fun HomeInboxPreviewList(
    previewEntries: List<InboxEntry>,
    activeAudioEntryId: String?,
    onOpenEntry: (String) -> Unit,
    onLongPressEntry: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    previewEntries.forEachIndexed { index, entry ->
        if (index > 0) {
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
        HomeInboxPreviewRow(
            entry = entry,
            isPlayingAudio = entry.id == activeAudioEntryId,
            onClick = { onOpenEntry(entry.id) },
            onLongClick = { onLongPressEntry(entry.id) },
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeInboxPreviewRow(
    entry: InboxEntry,
    isPlayingAudio: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    testTagPrefix: String = "home_inbox_item",
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val hasThumbSource = entry.hasThumbnailSource()
    val rowBg = if (isPlayingAudio) colors.accentBlue.copy(alpha = 0.12f) else Color.Transparent

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(rowBg)
                .drawLeftAccentBar(if (isPlayingAudio) colors.accentBlue else null)
                .heightIn(min = 48.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("${testTagPrefix}_${entry.id}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (hasThumbSource) {
            HomeCompactEntryThumbnail(entry = entry)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = remember(entry.title) { formatInlineMathAnnotated(entry.title) },
                color = if (entry.isNew) colors.textPrimary else colors.textSecondary,
                fontFamily = headlineFamily,
                fontSize = 14.5.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            EntrySourceMetadataRow(
                entry = entry,
                trailingMetaText = "· ${entry.formattedPublishedLabel}",
                showOfflineIndicator = true,
                showStarDot = entry.isFavorite,
            )
        }
    }
}

@Composable
private fun HomeCompactEntryThumbnail(entry: InboxEntry) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(5.dp)
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
        }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun HomeWantQueueWidget(
    state: MainUiState,
    maxItems: Int,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    excludeEntryIds: Set<String> = emptySet(),
    isCompactColumn: Boolean = false,
) {
    val previewItems =
        remember(state.catalog.wantToReadPreview, excludeEntryIds, maxItems) {
            state.catalog.wantToReadPreview
                .filter { it.entry.id !in excludeEntryIds }
                .take(maxItems)
        }
    val totalCount = state.catalog.wantItemCount
    val otherWantCount =
        remember(state.catalog.wantQueues) {
            state.catalog.wantQueues
                .filter { it.collectionId != WANT_TO_READ_COLLECTION_ID }
                .sumOf { it.entryCount }
        }
    val offlineCount = state.offlineEntries.size
    val offlineLabel = if (offlineCount > 0) "Offline ($offlineCount)" else "Offline"

    Column(
        modifier = modifier.fillMaxWidth().testTag("home_widget_queue_want"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RocheSectionHeader(
            label = "Queue",
            badgeText = if (totalCount > 0) formatIntegerCount(totalCount) else null,
            secondaryActionLabel = if (isCompactColumn) null else offlineLabel,
            secondaryActionTestTag = if (isCompactColumn) null else "home_open_offline_btn",
            onSecondaryActionClick =
                if (isCompactColumn) {
                    null
                } else {
                    { onAction(MainAction.SelectTab(AppTab.OFFLINE)) }
                },
            actionLabel = "All →",
            actionTestTag = "home_open_queue_btn",
            onActionClick = { onAction(MainAction.SelectTab(AppTab.QUEUE)) },
        )
        RocheSurfaceCard {
            HomeWantQueueCardBody(
                previewItems = previewItems,
                totalCount = totalCount,
                otherWantCount = otherWantCount,
                offlineLabel = offlineLabel,
                isCompactColumn = isCompactColumn,
                state = state,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun HomeWantQueueCardBody(
    previewItems: List<RankedCatalogEntry>,
    totalCount: Int,
    otherWantCount: Int,
    offlineLabel: String,
    isCompactColumn: Boolean,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val horizontalPadding = if (isCompactColumn) 10.dp else 12.dp
    if (previewItems.isEmpty() && otherWantCount == 0) {
        if (totalCount > 0) {
            HomeActionNoteCardBody(
                message = "All saved items are currently in Continue above.",
                actionLabel = "Open Queue",
                onActionClick = { onAction(MainAction.SelectTab(AppTab.QUEUE)) },
                stackVertically = isCompactColumn,
            )
        } else {
            HomeActionNoteCardBody(
                message =
                    "Save URLs or add works to your synced Queue to read, watch, or listen next.",
                actionLabel = "+ Save URL",
                onActionClick = { onAction(MainAction.OpenAddSheet()) },
                stackVertically = isCompactColumn,
                actionTestTag = "home_queue_save_url_btn",
            )
        }
    } else {
        HomeWantQueuePreviewList(
            items = previewItems,
            isCompactColumn = isCompactColumn,
            state = state,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
        )
        if (otherWantCount > 0) {
            val summaryLabel =
                if (isCompactColumn) {
                    "+${formatIntegerCount(otherWantCount)} more"
                } else {
                    "+${formatIntegerCount(otherWantCount)} in other Want queues"
                }
            HomeQueueFooterRow(
                label = summaryLabel,
                showTopDivider = previewItems.isNotEmpty(),
                horizontalPadding = horizontalPadding,
                testTag = "home_other_want_queues_row",
                onClick = { onAction(MainAction.SelectTab(AppTab.QUEUE)) },
            )
        }
    }
    if (isCompactColumn) {
        HomeQueueFooterRow(
            label = offlineLabel,
            showTopDivider = true,
            horizontalPadding = horizontalPadding,
            testTag = "home_open_offline_btn",
            onClick = { onAction(MainAction.SelectTab(AppTab.OFFLINE)) },
        )
    }
}

@Composable
private fun HomeQueueFooterRow(
    label: String,
    showTopDivider: Boolean,
    horizontalPadding: Dp,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    if (showTopDivider) {
        HorizontalDivider(color = colors.border, thickness = 1.dp)
    }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = horizontalPadding, vertical = 10.dp)
                .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 6.dp),
        )
        Text(text = "Open →", color = colors.accentBlue, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun HomeWantQueuePreviewList(
    items: List<RankedCatalogEntry>,
    isCompactColumn: Boolean,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    items.forEachIndexed { index, item ->
        if (index > 0) {
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
        HomeWantCatalogPreviewRow(
            item = item,
            isCompactColumn = isCompactColumn,
            onClick = resolveHomeWantItemClick(item, state, onAction, onOpenExternalUrl),
        )
    }
}

private fun resolveHomeWantItemClick(
    want: RankedCatalogEntry,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
): () -> Unit =
    resolveCatalogEntryOpenAction(
        entryId = want.entry.id,
        externalUrl = want.entry.url,
        readableIds = state.readableEntryIds,
        onOpenReader = { onAction(MainAction.OpenReader(it)) },
        onOpenExternalUrl = onOpenExternalUrl,
        entryKind = want.entry.kind,
        onOpenCatalogDetail = { onAction(MainAction.OpenCatalogDetailSheet(it)) },
    ) ?: { onAction(MainAction.OpenCompanionSection(CompanionSubTab.WANT)) }

@Composable
private fun HomeWantCatalogPreviewRow(
    item: RankedCatalogEntry,
    isCompactColumn: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val horizontalPadding = if (isCompactColumn) 10.dp else 12.dp
    val titleFontSize = if (isCompactColumn) 13.5.sp else 14.sp
    val titleLineHeight = if (isCompactColumn) 18.sp else 19.sp
    val meta =
        remember(item.entry) {
            buildList {
                    item.entry.url
                        ?.let(::extractCleanDomain)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::add)
                    add(item.entry.kind.displayLabel)
                }
                .joinToString(" · ")
        }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = horizontalPadding, vertical = 9.dp)
                .testTag("home_want_item_${item.entry.id}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = remember(item.entry.title) { formatInlineMathAnnotated(item.entry.title) },
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = titleFontSize,
                lineHeight = titleLineHeight,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun buildCollectionDrawGenresLine(draw: CollectionDrawSnapshot): String =
    draw.tags.take(3).joinToString(", ")

internal data class HomeBottomLayout(
    val useTwoColumns: Boolean,
    val isCompactColumn: Boolean,
    val columnGap: Dp,
)

internal fun resolveHomeBottomLayout(
    viewportWidth: Dp,
    hasActiveDraws: Boolean,
    fontScale: Float = 1f,
): HomeBottomLayout {
    val safeFontScale = fontScale.coerceAtLeast(1f)
    val safeWidth =
        if (viewportWidth.isSpecified && viewportWidth.value.isFinite()) viewportWidth else 412.dp
    val useTwoColumns = hasActiveDraws && safeWidth / safeFontScale >= TWO_COLUMN_BOTTOM_BREAKPOINT
    val columnGap = if (safeWidth >= WIDE_HOME_BREAKPOINT) 14.dp else 12.dp
    val columnWidth =
        if (useTwoColumns) {
            (safeWidth - HOME_HORIZONTAL_PADDING * 2 - columnGap) / 2
        } else {
            safeWidth - HOME_HORIZONTAL_PADDING * 2
        }
    return HomeBottomLayout(
        useTwoColumns = useTwoColumns,
        isCompactColumn = columnWidth / safeFontScale < COMPACT_COLUMN_BREAKPOINT,
        columnGap = columnGap,
    )
}

internal fun computeHomeInboxMaxItems(
    viewportWidth: Dp,
    viewportHeight: Dp,
    fontScale: Float = 1f,
): Int {
    if (!viewportHeight.isSpecified || !viewportHeight.value.isFinite()) {
        return DEFAULT_HOME_INBOX_ITEMS
    }
    val safeFontScale = fontScale.coerceAtLeast(1f)
    val isWideWidth =
        viewportWidth.isSpecified &&
            viewportWidth.value.isFinite() &&
            viewportWidth >= WIDE_HOME_BREAKPOINT
    val baseRowHeightDp =
        if (isWideWidth) {
            WIDE_INBOX_ROW_HEIGHT_DP
        } else {
            COMPACT_INBOX_ROW_HEIGHT_DP
        }
    val estimatedRowHeightDp = baseRowHeightDp * (1f + (safeFontScale - 1f) * 0.7f)
    val targetCardHeightDp =
        (viewportHeight.value * HOME_INBOX_TARGET_HEIGHT_FRACTION - HOME_INBOX_CHROME_HEIGHT_DP)
            .coerceAtLeast(MIN_INBOX_CARD_HEIGHT_DP)
    return (targetCardHeightDp / estimatedRowHeightDp)
        .roundToInt()
        .coerceIn(MIN_HOME_INBOX_ITEMS, MAX_HOME_INBOX_ITEMS)
}

private fun formatIntegerCount(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(value)

private val HOME_HORIZONTAL_PADDING = 14.dp
private val TWO_COLUMN_BOTTOM_BREAKPOINT = 340.dp
private val COMPACT_COLUMN_BREAKPOINT = 250.dp
private val WIDE_HOME_BREAKPOINT = 540.dp
private const val HOME_INBOX_TARGET_HEIGHT_FRACTION = 2f / 3f
private const val HOME_INBOX_CHROME_HEIGHT_DP = 78f
private const val MIN_INBOX_CARD_HEIGHT_DP = 180f
private const val WIDE_INBOX_ROW_HEIGHT_DP = 64f
private const val COMPACT_INBOX_ROW_HEIGHT_DP = 70f
private const val DEFAULT_HOME_INBOX_ITEMS = 6
private const val MIN_HOME_INBOX_ITEMS = 3
private const val MAX_HOME_INBOX_ITEMS = 12
private const val HOME_CONTINUE_ITEMS = 4
private const val HOME_QUEUE_PREVIEW_ITEMS = 4
