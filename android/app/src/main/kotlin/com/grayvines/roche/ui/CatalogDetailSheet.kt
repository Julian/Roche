@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.formatInlineMathAnnotated
import com.grayvines.roche.core.parseIsoInstant
import com.grayvines.roche.data.catalog.AlbumTrackItem
import com.grayvines.roche.data.catalog.ArtistLibraryAlbum
import com.grayvines.roche.data.catalog.CatalogEntryItem
import com.grayvines.roche.data.catalog.CatalogListAppearance
import com.grayvines.roche.data.catalog.CatalogListSummary
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.DiaryEntryItem
import com.grayvines.roche.data.catalog.DiscographyAlbumItem
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.MusicTrackLaunchRequest
import com.grayvines.roche.data.catalog.extractAlbumTracks
import com.grayvines.roche.data.catalog.extractArtistDiscography
import com.grayvines.roche.data.catalog.extractMetadataGenres
import com.grayvines.roche.data.catalog.resolveWikipediaUrl
import com.grayvines.roche.data.catalog.toMusicLaunchSnapshot
import com.grayvines.roche.ui.components.CatalogCoverThumbnail
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.TierBadgePill
import com.grayvines.roche.ui.components.WikipediaIcon
import com.grayvines.roche.ui.components.rememberEntryThumbnailBitmap
import com.grayvines.roche.ui.components.rememberHighResImageBitmap
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RochePalette
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class CatalogDetailModel(
    val entry: CatalogEntryItem,
    val appearances: List<CatalogListAppearance>,
    val primaryAppearance: CatalogListAppearance?,
    val matchingArtistId: String?,
    val diaryLogs: List<DiaryEntryItem>,
    val diaryLogsTotalCount: Int,
    val hasMoreDiaryLogs: Boolean,
    val isLoadingDiaryLogs: Boolean,
    val genres: List<String>,
    val tracks: List<AlbumTrackItem>,
    val wikiUrl: String?,
    val artistLibraryAlbums: List<ArtistLibraryAlbum>,
    val artistExternalDiscography: List<DiscographyAlbumItem>,
    val isStarred: Boolean,
    val isInWantQueue: Boolean,
    val addableManualLists: List<CatalogListSummary>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogDetailBottomSheet(
    entryId: String,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    onPlayMusicTrack: (MusicTrackLaunchRequest) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val model = rememberCatalogDetailModel(entryId, state) ?: return
    val colors = LocalRocheColors.current
    val scrollState = rememberScrollState()
    val previousEntryId = state.catalogDetailStack.dropLast(1).lastOrNull()
    val previousTitle = rememberPreviousDetailTitle(entryId, previousEntryId, state)
    val isEnriching = entryId in state.enrichingCatalogEntryIds
    var editingLogId by rememberSaveable(entryId) { mutableStateOf<String?>(null) }
    val editingLog =
        remember(editingLogId, model.diaryLogs) {
            editingLogId?.let { id -> model.diaryLogs.firstOrNull { it.id == id } }
        }

    LaunchedEffect(entryId) { scrollState.scrollTo(0) }

    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseCatalogDetailSheet) },
        containerColor = colors.surface,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        CatalogDetailModalBody(
            model = model,
            state = state,
            previousTitle = previousTitle,
            isEnriching = isEnriching,
            scrollState = scrollState,
            onAction = onAction,
            onEditDiaryLog = { editingLogId = it.id },
            onOpenCollectionDraw = onOpenCollectionDraw,
            onPlayMusicTrack = onPlayMusicTrack,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
    if (editingLog != null) {
        DiaryEditLogDialogHost(
            log = editingLog,
            onDismiss = { editingLogId = null },
            onAction = onAction,
        )
    }
}

@Composable
private fun rememberCatalogDetailModel(entryId: String, state: MainUiState): CatalogDetailModel? =
    remember(
        entryId,
        state.catalog,
        state.catalogDetail,
        state.catalogDetailLogs,
        state.diary.entries,
        state.activeCollection.entries,
        state.activeWantQueue.entries,
        state.starredCollection.entries,
        state.workspaceSearch.matchedCatalogEntries,
        state.catalogBrowseSearch.matches,
    ) {
        buildCatalogDetailModel(entryId, state)
    }

@Composable
private fun rememberPreviousDetailTitle(
    entryId: String,
    previousEntryId: String?,
    state: MainUiState,
): String? =
    remember(
        previousEntryId,
        state.catalogDetail,
        state.activeCollection.entries,
        state.activeWantQueue.entries,
        state.starredCollection.entries,
        state.workspaceSearch.matchedCatalogEntries,
        state.catalogBrowseSearch.matches,
    ) {
        previousEntryId?.let { prevId ->
            state.catalogDetail?.takeIf { it.entry.id == entryId }?.previousEntryTitle
                ?: resolveCatalogEntryById(prevId, state)?.title
        }
    }

@Suppress("LongParameterList")
@Composable
private fun CatalogDetailModalBody(
    model: CatalogDetailModel,
    state: MainUiState,
    previousTitle: String?,
    isEnriching: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    onAction: (MainAction) -> Unit,
    onEditDiaryLog: (DiaryEntryItem) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    onPlayMusicTrack: (MusicTrackLaunchRequest) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    BackHandler(enabled = true) {
        if (state.catalogDetailStack.size > 1) {
            onAction(MainAction.PopCatalogDetailSheet)
        } else {
            onAction(MainAction.CloseCatalogDetailSheet)
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val isWide = maxWidth >= CATALOG_DETAIL_WIDE_BREAKPOINT
        val horizontalPad = if (isWide) 20.dp else 16.dp
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = horizontalPad, vertical = 6.dp)
                    .testTag("catalog_detail_sheet"),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.catalogDetailStack.size > 1) {
                CatalogDetailBackChip(
                    previousTitle = previousTitle,
                    onBack = { onAction(MainAction.PopCatalogDetailSheet) },
                )
            }
            CatalogDetailSheetContent(
                model = model,
                isWide = isWide,
                canRefreshOnline = state.hasBearerToken,
                isEnriching = isEnriching,
                onAction = onAction,
                onEditDiaryLog = onEditDiaryLog,
                onOpenCollectionDraw = onOpenCollectionDraw,
                onPlayMusicTrack = onPlayMusicTrack,
                onOpenExternalUrl = onOpenExternalUrl,
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun CatalogDetailBackChip(
    previousTitle: String?,
    onBack: () -> Unit,
) {
    val label = if (!previousTitle.isNullOrBlank()) "← $previousTitle" else "← Back"
    FilterChipPill(
        label = label,
        selected = false,
        onClick = onBack,
        modifier = Modifier.testTag("catalog_detail_back_btn"),
    )
}

private data class CatalogMetaRefreshState(
    val canRefreshOnline: Boolean,
    val isEnriching: Boolean,
)

@Suppress("LongParameterList")
@Composable
private fun CatalogDetailSheetContent(
    model: CatalogDetailModel,
    isWide: Boolean,
    canRefreshOnline: Boolean,
    isEnriching: Boolean,
    onAction: (MainAction) -> Unit,
    onEditDiaryLog: (DiaryEntryItem) -> Unit,
    onOpenCollectionDraw: (CollectionDrawSnapshot) -> Unit,
    onPlayMusicTrack: (MusicTrackLaunchRequest) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val refreshState = CatalogMetaRefreshState(canRefreshOnline, isEnriching)
    when (model.entry.kind) {
        EntryKind.ALBUM ->
            AlbumDetailSheetBody(
                model = model,
                isWide = isWide,
                refreshState = refreshState,
                onAction = onAction,
                onEditDiaryLog = onEditDiaryLog,
                onPlayAlbum = { onOpenCollectionDraw(model.entry.toMusicLaunchSnapshot()) },
                onPlayMusicTrack = onPlayMusicTrack,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        EntryKind.ARTIST ->
            ArtistDetailSheetBody(
                model = model,
                isWide = isWide,
                refreshState = refreshState,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        else ->
            GenericCatalogDetailBody(
                model = model,
                isWide = isWide,
                refreshState = refreshState,
                onAction = onAction,
                onEditDiaryLog = onEditDiaryLog,
                onPlayMusicTrack = onPlayMusicTrack,
                onOpenExternalUrl = onOpenExternalUrl,
            )
    }
}

@Composable
private fun CatalogDetailActionStrip(
    model: CatalogDetailModel,
    refreshState: CatalogMetaRefreshState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val entry = model.entry
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChipPill(
            label = if (model.isStarred) "★ Starred" else "☆ Star",
            selected = model.isStarred,
            onClick = { onAction(MainAction.ToggleCatalogStar(entry.id)) },
            modifier = Modifier.testTag("catalog_detail_star_btn"),
        )
        if (entry.kind != EntryKind.ARTIST) {
            FilterChipPill(
                label = if (model.isInWantQueue) "✓ In Queue" else "+ Queue",
                selected = model.isInWantQueue,
                onClick = { onAction(MainAction.ToggleCatalogWant(entry.id)) },
                modifier = Modifier.testTag("catalog_detail_queue_btn"),
            )
        }
        if (model.addableManualLists.isNotEmpty()) {
            AddToManualListChip(
                entryId = entry.id,
                lists = model.addableManualLists,
                onSelectList = { colId ->
                    onAction(MainAction.AddCatalogEntryToList(colId, entry.id))
                },
            )
        }
        DetailEditAndRefreshChips(
            entry = entry,
            refreshState = refreshState,
            accentColor = colors.accentBlue,
            onAction = onAction,
        )
    }
}

@Composable
private fun DetailEditAndRefreshChips(
    entry: CatalogEntryItem,
    refreshState: CatalogMetaRefreshState,
    accentColor: Color,
    onAction: (MainAction) -> Unit,
) {
    if (entry.kind != EntryKind.COLLECTION) {
        FilterChipPill(
            label = "✎ Edit",
            selected = false,
            onClick = { onAction(MainAction.OpenEditEntrySheet(entry.id)) },
            modifier = Modifier.testTag("catalog_detail_edit_btn"),
        )
    }
    val supportsDeviceRefresh =
        entry.kind == EntryKind.PAPER ||
            entry.kind == EntryKind.ARTICLE ||
            entry.kind == EntryKind.BOOK
    if (refreshState.canRefreshOnline || supportsDeviceRefresh) {
        RefreshMetadataChip(
            isEnriching = refreshState.isEnriching,
            accentColor = accentColor,
            onClick = { onAction(MainAction.RefreshCatalogEntryMetadata(entry.id)) },
        )
    }
}

@Composable
private fun AddToManualListChip(
    entryId: String,
    lists: List<CatalogListSummary>,
    onSelectList: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    var expanded by rememberSaveable(entryId) { mutableStateOf(false) }
    Box {
        FilterChipPill(
            label = "+ List",
            selected = expanded,
            onClick = { expanded = true },
            modifier = Modifier.testTag("catalog_detail_add_list_btn"),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceElevated,
        ) {
            lists.forEach { list ->
                DropdownMenuItem(
                    text = { Text(list.title, color = colors.textPrimary) },
                    onClick = {
                        expanded = false
                        onSelectList(list.collectionId)
                    },
                    modifier = Modifier.testTag("catalog_add_to_list_${list.collectionId}"),
                )
            }
        }
    }
}

@Composable
private fun RefreshMetadataChip(
    isEnriching: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .clip(shape)
                .background(
                    if (isEnriching) accentColor.copy(alpha = 0.14f) else colors.surfaceElevated
                )
                .border(1.dp, if (isEnriching) accentColor else colors.border, shape)
                .clickable(enabled = !isEnriching, onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .testTag("catalog_detail_refresh_meta_btn"),
    ) {
        if (isEnriching) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.6.dp,
                color = accentColor,
            )
        }
        Text(
            text = if (isEnriching) "Refreshing…" else "↻ Refresh Metadata",
            color = if (isEnriching) accentColor else colors.textSecondary,
            fontSize = 12.sp,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun AlbumDetailSheetBody(
    model: CatalogDetailModel,
    isWide: Boolean,
    refreshState: CatalogMetaRefreshState,
    onAction: (MainAction) -> Unit,
    onEditDiaryLog: (DiaryEntryItem) -> Unit,
    onPlayAlbum: () -> Unit,
    onPlayMusicTrack: (MusicTrackLaunchRequest) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val openList: (String) -> Unit = { colId ->
        onAction(MainAction.OpenCompanionSection(CompanionSubTab.LISTS, colId))
    }
    AlbumDetailHero(
        model = model,
        onPlayAlbum = onPlayAlbum,
        onOpenArtist =
            model.matchingArtistId?.let { id ->
                { onAction(MainAction.OpenCatalogDetailSheet(id)) }
            },
        onRank = { startFocusedRank(model.primaryAppearance, model.entry.id, onAction) },
    )
    CatalogDetailActionStrip(model, refreshState, onAction)
    if (isWide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.weight(ALBUM_TRACKLIST_WEIGHT)) {
                AlbumTracklistSection(model.entry, model.tracks, onPlayAlbum, onPlayMusicTrack)
            }
            Column(
                modifier = Modifier.weight(ALBUM_SIDE_WEIGHT),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CatalogListsSection(
                    model.appearances,
                    model.wikiUrl,
                    null,
                    openList,
                    onOpenExternalUrl,
                )
                CatalogDiarySection(
                    entry = model.entry,
                    logs = model.diaryLogs,
                    totalCount = model.diaryLogsTotalCount,
                    hasMore = model.hasMoreDiaryLogs,
                    isLoading = model.isLoadingDiaryLogs,
                    onAction = onAction,
                    onEditDiaryLog = onEditDiaryLog,
                )
            }
        }
    } else {
        AlbumTracklistSection(model.entry, model.tracks, onPlayAlbum, onPlayMusicTrack)
        CatalogListsSection(model.appearances, model.wikiUrl, null, openList, onOpenExternalUrl)
        CatalogDiarySection(
            entry = model.entry,
            logs = model.diaryLogs,
            totalCount = model.diaryLogsTotalCount,
            hasMore = model.hasMoreDiaryLogs,
            isLoading = model.isLoadingDiaryLogs,
            onAction = onAction,
            onEditDiaryLog = onEditDiaryLog,
        )
    }
}

@Composable
private fun AlbumDetailHero(
    model: CatalogDetailModel,
    onPlayAlbum: () -> Unit,
    onOpenArtist: (() -> Unit)?,
    onRank: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val entry = model.entry
    val genresLine = remember(model.genres) { model.genres.take(3).joinToString(" · ") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumHeroCoverButton(entry = entry, onClick = onPlayAlbum)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HeroTitleWithYear(
                title = entry.title,
                releaseYear = entry.releaseYear,
                onClick = onPlayAlbum,
                testTag = "catalog_detail_play_title",
            )
            if (entry.primaryCreator.isNotBlank()) {
                AlbumHeroArtistLine(
                    artistName = entry.creators.joinToString(", "),
                    onOpenArtist = onOpenArtist,
                )
            }
            if (genresLine.isNotBlank()) {
                Text(
                    text = genresLine,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HeroCompactRankBadge(
            appearance = model.primaryAppearance,
            onClick = onRank,
        )
    }
}

@Composable
private fun AlbumHeroCoverButton(
    entry: CatalogEntryItem,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(10.dp)
    val bitmap = rememberHighResImageBitmap(primaryKey = entry.thumbnailUrl, fallbackKey = null)
    Box(
        modifier =
            Modifier.size(78.dp)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Play ${entry.title} in music app"
                }
                .testTag("catalog_detail_album_cover")
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
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xD6141413))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
        ) {
            Canvas(modifier = Modifier.size(9.dp)) {
                val w = size.width
                val h = size.height
                val tri =
                    Path().apply {
                        moveTo(w * 0.26f, h * 0.14f)
                        lineTo(w * 0.88f, h * 0.50f)
                        lineTo(w * 0.26f, h * 0.86f)
                        close()
                    }
                drawPath(path = tri, color = RochePalette.Timberwolf)
            }
        }
    }
}

@Composable
private fun AlbumHeroArtistLine(
    artistName: String,
    onOpenArtist: (() -> Unit)?,
) {
    val colors = LocalRocheColors.current
    if (onOpenArtist != null) {
        Text(
            text = "$artistName →",
            color = colors.accentBlue,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenArtist)
                    .semantics { role = Role.Button }
                    .padding(vertical = 1.dp)
                    .testTag("catalog_detail_artist_link"),
        )
    } else {
        Text(
            text = artistName,
            color = colors.textSecondary,
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroTitleWithYear(
    title: String,
    releaseYear: Int?,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val annotated =
        remember(title, releaseYear, colors, headlineFamily) {
            buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = colors.textPrimary,
                        fontFamily = headlineFamily,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Normal,
                    )
                ) {
                    append(formatInlineMathAnnotated(title))
                }
                if (releaseYear != null && releaseYear > 0) {
                    append("  ")
                    withStyle(
                        SpanStyle(
                            color = colors.textMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                        )
                    ) {
                        append(releaseYear.toString())
                    }
                }
            }
        }
    var mod: Modifier = Modifier
    if (onClick != null) {
        mod = mod.clickable(onClick = onClick).semantics { role = Role.Button }
    }
    if (testTag != null) {
        mod = mod.testTag(testTag)
    }
    Text(
        text = annotated,
        lineHeight = 25.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = mod,
    )
}

@Composable
private fun HeroCompactRankBadge(
    appearance: CatalogListAppearance?,
    onClick: () -> Unit,
) {
    if (appearance == null) return
    val colors = LocalRocheColors.current
    val canRank = appearance.totalCount >= 2
    val clickMod =
        if (canRank) {
            Modifier.minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Rank in ${appearance.title}"
                }
        } else {
            Modifier
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            clickMod.padding(horizontal = 6.dp, vertical = 4.dp).testTag("catalog_detail_rank_btn"),
    ) {
        if (appearance.tier != null) {
            TierBadgePill(tier = appearance.tier)
        }
        Text(
            text = "#${appearance.rank}",
            color = colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun AlbumTracklistSection(
    entry: CatalogEntryItem,
    tracks: List<AlbumTrackItem>,
    onPlayAlbum: () -> Unit,
    onPlayMusicTrack: (MusicTrackLaunchRequest) -> Unit,
) {
    val summaryNote = remember(tracks) { formatTracklistSummary(tracks) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetSectionHeaderRow(title = "Tracklist", trailingNote = summaryNote)
        RocheSurfaceCard {
            if (tracks.isEmpty()) {
                AlbumTracklistEmptyFallback(onPlayAlbum = onPlayAlbum)
            } else {
                AlbumTracklistRows(
                    entry = entry,
                    tracks = tracks,
                    onPlayMusicTrack = onPlayMusicTrack,
                )
            }
        }
    }
}

@Composable
private fun AlbumTracklistEmptyFallback(onPlayAlbum: () -> Unit) {
    val colors = LocalRocheColors.current
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onPlayAlbum)
                .semantics { role = Role.Button }
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .testTag("catalog_detail_play_album_fallback"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Open album in your music app",
            color = colors.accentBlue,
            fontSize = 13.5.sp,
        )
        Text(
            text = "Tracklist appears automatically once album metadata is enriched.",
            color = colors.textMuted,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun AlbumTracklistRows(
    entry: CatalogEntryItem,
    tracks: List<AlbumTrackItem>,
    onPlayMusicTrack: (MusicTrackLaunchRequest) -> Unit,
) {
    val colors = LocalRocheColors.current
    val multiDisc = remember(tracks) { tracks.any { it.disc > 1 } }
    tracks.forEachIndexed { idx, track ->
        val isNewDisc = multiDisc && (idx == 0 || tracks[idx - 1].disc != track.disc)
        if (idx > 0) {
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
        if (isNewDisc) {
            Text(
                text = "DISC ${track.disc}",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        AlbumTrackRow(
            track = track,
            onClick = {
                onPlayMusicTrack(
                    MusicTrackLaunchRequest(
                        trackTitle = track.title,
                        albumTitle = entry.title,
                        artist = track.artists.firstOrNull() ?: entry.primaryCreator,
                        spotifyUri = track.spotifyUri,
                    )
                )
            },
        )
    }
}

@Composable
private fun AlbumTrackRow(
    track: AlbumTrackItem,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val duration = remember(track.durationMs) { formatTrackDuration(track.durationMs) }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Play ${track.title}"
                }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("catalog_track_${track.disc}_${track.number}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = track.number.toString(),
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = track.title,
            color = colors.textPrimary,
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (duration.isNotEmpty()) {
            Text(
                text = duration,
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun CatalogListsSection(
    appearances: List<CatalogListAppearance>,
    wikiUrl: String?,
    externalUrl: String?,
    onOpenList: (String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    if (appearances.isEmpty() && wikiUrl == null && externalUrl == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "LISTS",
                color = colors.textMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                modifier = Modifier.semantics { heading() },
            )
            if (wikiUrl != null) {
                QuietWikiButton(onClick = { onOpenExternalUrl(wikiUrl) })
            } else if (externalUrl != null) {
                Text(
                    text = "Open ↗",
                    color = colors.accentBlue,
                    fontSize = 12.sp,
                    modifier =
                        Modifier.clickable { onOpenExternalUrl(externalUrl) }
                            .semantics { role = Role.Button },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            appearances.forEach { app ->
                CatalogListLinkButton(appearance = app, onClick = { onOpenList(app.collectionId) })
            }
        }
    }
}

@Composable
private fun CatalogListLinkButton(
    appearance: CatalogListAppearance,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(shape)
                .background(colors.background)
                .border(1.dp, colors.border, shape)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("catalog_detail_list_btn_${appearance.collectionId}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = appearance.title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Text(
            text = "#${appearance.rank}",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun QuietWikiButton(onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Open Wikipedia"
                }
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .testTag("catalog_detail_wiki_btn"),
    ) {
        WikipediaIcon(tint = colors.textSecondary, size = 14.dp)
        Text(text = "Wiki", color = colors.textSecondary, fontSize = 11.5.sp)
    }
}

@Composable
private fun CatalogDiaryAddLogButton(entry: CatalogEntryItem, onAction: (MainAction) -> Unit) {
    val colors = LocalRocheColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    onAction(
                        MainAction.OpenQuickLogSheet(
                            defaultToWant = false,
                            prefillKind = entry.kind,
                            prefillTitle = entry.title,
                            prefillCreators = entry.creators.joinToString(", "),
                            prefillReleaseYear = entry.releaseYear,
                            existingEntryId = entry.id,
                        )
                    )
                }
                .semantics { role = Role.Button }
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .testTag("catalog_detail_log_btn"),
    ) {
        Text(text = "+ Log", color = colors.accentBlue, fontSize = 12.sp)
    }
}

@Suppress("LongParameterList")
@Composable
private fun CatalogDiarySection(
    entry: CatalogEntryItem,
    logs: List<DiaryEntryItem>,
    totalCount: Int,
    hasMore: Boolean,
    isLoading: Boolean = false,
    onAction: (MainAction) -> Unit,
    onEditDiaryLog: (DiaryEntryItem) -> Unit,
) {
    val colors = LocalRocheColors.current
    val headerLabel = if (totalCount > logs.size) "DIARY ($totalCount)" else "DIARY"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = headerLabel,
                color = colors.textMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                modifier = Modifier.semantics { heading() },
            )
            CatalogDiaryAddLogButton(entry = entry, onAction = onAction)
        }
        RocheSurfaceCard {
            CatalogDiaryCardBody(
                logs = logs,
                totalCount = totalCount,
                hasMore = hasMore,
                isLoading = isLoading,
                onAction = onAction,
                onEditDiaryLog = onEditDiaryLog,
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun CatalogDiaryCardBody(
    logs: List<DiaryEntryItem>,
    totalCount: Int,
    hasMore: Boolean,
    isLoading: Boolean,
    onAction: (MainAction) -> Unit,
    onEditDiaryLog: (DiaryEntryItem) -> Unit,
) {
    val colors = LocalRocheColors.current
    if (logs.isEmpty()) {
        Text(
            text = if (isLoading) "Loading diary…" else "No plays logged yet.",
            color = colors.textMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
        return
    }
    logs.forEachIndexed { idx, log ->
        if (idx > 0) {
            HorizontalDivider(color = colors.border, thickness = 1.dp)
        }
        CatalogDiaryHistoryRow(log = log, onEdit = { onEditDiaryLog(log) })
    }
    if (hasMore) {
        CatalogDiaryLoadMoreRow(
            remaining = (totalCount - logs.size).coerceAtLeast(0),
            onLoadMore = { onAction(MainAction.LoadMoreCatalogDetailLogs) },
        )
    }
}

@Composable
private fun CatalogDiaryLoadMoreRow(remaining: Int, onLoadMore: () -> Unit) {
    val colors = LocalRocheColors.current
    HorizontalDivider(color = colors.border, thickness = 1.dp)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(
            onClick = onLoadMore,
            modifier = Modifier.testTag("catalog_detail_logs_load_more_btn"),
        ) {
            Text(
                text = "Show more ($remaining remaining)",
                color = colors.accentBlue,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun CatalogDiaryHistoryRow(
    log: DiaryEntryItem,
    onEdit: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val dateText = remember(log.ts, log.dateLabel) { formatDiaryFullDate(log.ts, log.dateLabel) }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onEdit)
                .semantics { role = Role.Button }
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CatalogDiaryHistoryHeader(log = log, dateText = dateText, onEdit = onEdit)
        if (!log.notes.isNullOrBlank()) {
            Text(
                text = log.notes,
                color = colors.accentEcru,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun CatalogDiaryHistoryHeader(
    log: DiaryEntryItem,
    dateText: String,
    onEdit: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val statusTint =
        when (log.status) {
            ConsumptionStatus.CONSUMED -> colors.accentVerdigris
            ConsumptionStatus.STARTED -> colors.accentBlue
            ConsumptionStatus.ABANDONED -> colors.accentIndianRed
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dateText,
            color = colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier =
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(statusTint.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = log.status.displayLabel,
                    color = statusTint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                )
            }
            RocheIconActionButton(
                contentDescription = "Edit diary log",
                outlined = false,
                onClick = onEdit,
                modifier = Modifier.testTag("catalog_diary_edit_${log.id}"),
            ) {
                Text(text = "⋯", color = colors.textMuted, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ArtistDetailSheetBody(
    model: CatalogDetailModel,
    isWide: Boolean,
    refreshState: CatalogMetaRefreshState,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val portraitUrl =
        model.entry.thumbnailUrl
            ?: model.artistLibraryAlbums
                .firstOrNull { !it.entry.thumbnailUrl.isNullOrBlank() }
                ?.entry
                ?.thumbnailUrl
    ArtistDetailHero(
        entry = model.entry,
        portraitUrl = portraitUrl,
        inLibraryCount = model.artistLibraryAlbums.size,
        primaryAppearance = model.primaryAppearance,
        onRank = { startFocusedRank(model.primaryAppearance, model.entry.id, onAction) },
    )
    CatalogDetailActionStrip(model, refreshState, onAction)
    val bio = model.entry.summary?.trim()
    if (!bio.isNullOrEmpty()) {
        ArtistBioCard(entryId = model.entry.id, bio = bio)
    }
    ArtistDiscographySection(
        artistName = model.entry.title,
        inLibraryAlbums = model.artistLibraryAlbums,
        externalAlbums = model.artistExternalDiscography,
        columns = if (isWide) 3 else 2,
        wikiUrl = model.wikiUrl,
        onOpenAlbum = { albumId -> onAction(MainAction.OpenCatalogDetailSheet(albumId)) },
        onAddExternalAlbum = { ext ->
            onAction(
                MainAction.OpenQuickLogSheet(
                    defaultToWant = false,
                    prefillKind = EntryKind.ALBUM,
                    prefillTitle = ext.title,
                    prefillCreators = model.entry.title,
                    prefillReleaseYear = ext.releaseYear,
                )
            )
        },
        onOpenExternalUrl = onOpenExternalUrl,
    )
}

@Composable
private fun ArtistDetailHero(
    entry: CatalogEntryItem,
    portraitUrl: String?,
    inLibraryCount: Int,
    primaryAppearance: CatalogListAppearance?,
    onRank: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    val bitmap = rememberHighResImageBitmap(primaryKey = portraitUrl, fallbackKey = null)
    val countLabel =
        if (inLibraryCount == 1) "1 album in library" else "$inLibraryCount albums in library"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(70.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.border, CircleShape),
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
                    text = entry.title.take(1).uppercase(),
                    color = colors.textMuted,
                    fontFamily = headlineFamily,
                    fontSize = 24.sp,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "ARTIST",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = entry.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 21.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = countLabel, color = colors.textSecondary, fontSize = 12.sp)
        }
        HeroCompactRankBadge(appearance = primaryAppearance, onClick = onRank)
    }
}

private sealed interface DiscographyTile {
    data class InLibrary(val item: ArtistLibraryAlbum) : DiscographyTile

    data class External(val item: DiscographyAlbumItem) : DiscographyTile
}

@Suppress("LongParameterList")
@Composable
private fun ArtistDiscographySection(
    artistName: String,
    inLibraryAlbums: List<ArtistLibraryAlbum>,
    externalAlbums: List<DiscographyAlbumItem>,
    columns: Int,
    wikiUrl: String?,
    onOpenAlbum: (String) -> Unit,
    onAddExternalAlbum: (DiscographyAlbumItem) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val tiles =
        remember(inLibraryAlbums, externalAlbums) {
            inLibraryAlbums.map(DiscographyTile::InLibrary) +
                externalAlbums.map(DiscographyTile::External)
        }
    val note =
        if (externalAlbums.isNotEmpty()) {
            "${inLibraryAlbums.size} of ${tiles.size} in library"
        } else {
            "${inLibraryAlbums.size} in library"
        }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ArtistDiscographyHeader(
            note = note,
            wikiUrl = wikiUrl,
            onOpenExternalUrl = onOpenExternalUrl,
        )
        if (tiles.isEmpty()) {
            RocheSurfaceCard {
                Text(
                    text = "No albums for $artistName in your library yet.",
                    color = colors.textMuted,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else {
            ArtistDiscographyGrid(
                tiles = tiles,
                columns = columns,
                onOpenAlbum = onOpenAlbum,
                onAddExternalAlbum = onAddExternalAlbum,
            )
        }
    }
}

@Composable
private fun ArtistDiscographyHeader(
    note: String,
    wikiUrl: String?,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "DISCOGRAPHY",
            color = colors.textMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = note,
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            if (wikiUrl != null) {
                QuietWikiButton(onClick = { onOpenExternalUrl(wikiUrl) })
            }
        }
    }
}

@Composable
private fun ArtistDiscographyGrid(
    tiles: List<DiscographyTile>,
    columns: Int,
    onOpenAlbum: (String) -> Unit,
    onAddExternalAlbum: (DiscographyAlbumItem) -> Unit,
) {
    val rows = remember(tiles, columns) { tiles.chunked(columns) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { rowTiles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowTiles.forEach { tile ->
                    Box(modifier = Modifier.weight(1f)) {
                        when (tile) {
                            is DiscographyTile.InLibrary ->
                                DiscographyLibraryCard(
                                    album = tile.item,
                                    onClick = { onOpenAlbum(tile.item.entry.id) },
                                )
                            is DiscographyTile.External ->
                                DiscographyExternalCard(
                                    album = tile.item,
                                    onClick = { onAddExternalAlbum(tile.item) },
                                )
                        }
                    }
                }
                repeat(columns - rowTiles.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DiscographyLibraryCard(album: ArtistLibraryAlbum, onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(9.dp)
    val bitmap = rememberEntryThumbnailBitmap(null, album.entry.thumbnailUrl)
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .testTag("artist_discog_album_${album.entry.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(shape)
                    .background(colors.background)
                    .border(1.dp, colors.border, shape),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap,
                    null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(text = "♪", color = colors.textMuted, fontSize = 18.sp)
            }
        }
        Text(
            text = album.entry.title,
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = album.entry.releaseYear?.takeIf { it > 0 }?.toString().orEmpty(),
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            if (album.tier != null) {
                TierBadgePill(tier = album.tier)
            }
        }
    }
}

@Composable
private fun DiscographyExternalCard(album: DiscographyAlbumItem, onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(9.dp)
    val pillShape = RoundedCornerShape(999.dp)
    val bitmap = rememberEntryThumbnailBitmap(null, album.thumbnailUrl)
    Column(
        modifier =
            Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick).semantics {
                role = Role.Button
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(shape)
                    .background(colors.background)
                    .border(1.dp, colors.border, shape),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap,
                    null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.45f),
                )
            }
            Box(
                modifier =
                    Modifier.clip(pillShape)
                        .background(colors.surface.copy(alpha = 0.88f))
                        .border(1.dp, colors.border, pillShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    "+ Add",
                    color = colors.accentBlue,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Text(
            text = album.title,
            color = colors.textSecondary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album.releaseYear?.toString() ?: "Album",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ArtistBioCard(entryId: String, bio: String) {
    val colors = LocalRocheColors.current
    var expanded by rememberSaveable(entryId) { mutableStateOf(false) }
    val isLong = bio.length > ARTIST_BIO_COLLAPSE_CHARS
    RocheSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp).testTag("catalog_artist_bio"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = bio,
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = if (expanded || !isLong) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (isLong) {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier =
                        Modifier.minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { expanded = !expanded }
                            .semantics { role = Role.Button }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (expanded) "Show less" else "Read more",
                        color = colors.accentBlue,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun GenericCatalogDetailBody(
    model: CatalogDetailModel,
    isWide: Boolean,
    refreshState: CatalogMetaRefreshState,
    onAction: (MainAction) -> Unit,
    onEditDiaryLog: (DiaryEntryItem) -> Unit,
    onPlayMusicTrack: (MusicTrackLaunchRequest) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val entry = model.entry
    val openList: (String) -> Unit = { colId ->
        onAction(MainAction.OpenCompanionSection(CompanionSubTab.LISTS, colId))
    }
    val onPlaySong = buildSongPlayCallback(entry, onPlayMusicTrack)
    GenericCatalogDetailHero(model = model, onAction = onAction, onPlaySong = onPlaySong)
    CatalogDetailActionStrip(model, refreshState, onAction)
    if (!entry.summary.isNullOrBlank()) {
        RocheSurfaceCard {
            Text(
                text = remember(entry.summary) { formatInlineMathAnnotated(entry.summary) },
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
    GenericCatalogDetailSections(
        model = model,
        isWide = isWide,
        openList = openList,
        onAction = onAction,
        onEditDiaryLog = onEditDiaryLog,
        onOpenExternalUrl = onOpenExternalUrl,
    )
}

@Suppress("LongParameterList")
@Composable
private fun GenericCatalogDetailSections(
    model: CatalogDetailModel,
    isWide: Boolean,
    openList: (String) -> Unit,
    onAction: (MainAction) -> Unit,
    onEditDiaryLog: (DiaryEntryItem) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val entry = model.entry
    val listsSection: @Composable () -> Unit = {
        CatalogListsSection(
            model.appearances,
            model.wikiUrl,
            entry.url,
            openList,
            onOpenExternalUrl,
        )
    }
    val diarySection: @Composable () -> Unit = {
        CatalogDiarySection(
            entry = entry,
            logs = model.diaryLogs,
            totalCount = model.diaryLogsTotalCount,
            hasMore = model.hasMoreDiaryLogs,
            isLoading = model.isLoadingDiaryLogs,
            onAction = onAction,
            onEditDiaryLog = onEditDiaryLog,
        )
    }
    if (isWide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.weight(1f)) { listsSection() }
            Box(modifier = Modifier.weight(1f)) { diarySection() }
        }
    } else {
        listsSection()
        diarySection()
    }
}

private fun buildSongPlayCallback(
    entry: CatalogEntryItem,
    onPlayMusicTrack: (MusicTrackLaunchRequest) -> Unit,
): (() -> Unit)? {
    if (entry.kind != EntryKind.SONG) return null
    val spotifyId =
        entry.identifiers.firstOrNull { it.scheme.equals("spotify", ignoreCase = true) }?.value
    return {
        onPlayMusicTrack(
            MusicTrackLaunchRequest(
                trackTitle = entry.title,
                albumTitle = "",
                artist = entry.primaryCreator,
                spotifyUri = spotifyId,
            )
        )
    }
}

@Composable
private fun GenericCatalogDetailHero(
    model: CatalogDetailModel,
    onAction: (MainAction) -> Unit,
    onPlaySong: (() -> Unit)? = null,
) {
    val entry = model.entry
    val genresLine = remember(model.genres) { model.genres.take(3).joinToString(" · ") }
    val formattedPubDate =
        remember(entry.publishedAt) {
            entry.publishedAt?.let {
                parseIsoInstant(it)?.let { inst ->
                    DIARY_FULL_DATE_FMT.format(inst.atZone(ZoneOffset.UTC))
                }
            }
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onPlaySong != null) {
            AlbumHeroCoverButton(entry = entry, onClick = onPlaySong)
        } else {
            CatalogCoverThumbnail(
                thumbnailUrl = entry.thumbnailUrl,
                kind = entry.kind,
                size = 72.dp,
            )
        }
        GenericCatalogHeroMetaColumn(
            entry = entry,
            genresLine = genresLine,
            formattedPubDate = formattedPubDate,
            onPlaySong = onPlaySong,
            modifier = Modifier.weight(1f),
        )
        HeroCompactRankBadge(
            appearance = model.primaryAppearance,
            onClick = { startFocusedRank(model.primaryAppearance, entry.id, onAction) },
        )
    }
}

@Composable
private fun GenericCatalogHeroMetaColumn(
    entry: CatalogEntryItem,
    genresLine: String,
    formattedPubDate: String?,
    onPlaySong: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val metaSubLine =
        remember(formattedPubDate, genresLine) {
            listOfNotNull(
                    formattedPubDate?.takeIf { it.isNotBlank() },
                    genresLine.takeIf { it.isNotBlank() },
                )
                .joinToString(" · ")
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = entry.kind.displayLabel.uppercase(),
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 0.8.sp,
        )
        HeroTitleWithYear(
            title = entry.title,
            releaseYear = if (formattedPubDate == null) entry.releaseYear else null,
            onClick = onPlaySong,
            testTag = onPlaySong?.let { "catalog_detail_play_title" },
        )
        if (entry.creators.isNotEmpty()) {
            Text(
                text = entry.creators.joinToString(", "),
                color = colors.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (metaSubLine.isNotBlank()) {
            Text(
                text = metaSubLine,
                color = colors.textMuted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SheetSectionHeaderRow(title: String, trailingNote: String = "") {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            color = colors.textMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.semantics { heading() },
        )
        if (trailingNote.isNotBlank()) {
            Text(
                text = trailingNote,
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

private fun startFocusedRank(
    appearance: CatalogListAppearance?,
    entryId: String,
    onAction: (MainAction) -> Unit,
) {
    if (appearance == null || appearance.totalCount < 2) return
    onAction(MainAction.StartRankingSession(appearance.collectionId, entryId))
}

private fun buildCatalogDetailModel(entryId: String, state: MainUiState): CatalogDetailModel? {
    val catalog = state.catalog
    val loadedDetail = state.catalogDetail?.takeIf { it.entry.id == entryId }
    val entry = loadedDetail?.entry ?: resolveCatalogEntryById(entryId, state) ?: return null
    val appearances = loadedDetail?.appearances.orEmpty()
    val matchingArtistId = loadedDetail?.matchingArtistId
    val artistAlbums = loadedDetail?.artistLibraryAlbums.orEmpty()
    val inLibTitleKeys = artistAlbums.mapTo(HashSet()) { it.entry.title.trim().lowercase() }
    val externalDiscog =
        if (entry.kind == EntryKind.ARTIST) {
            extractArtistDiscography(entry.metadataJson).filter {
                it.title.trim().lowercase() !in inLibTitleKeys
            }
        } else {
            emptyList()
        }
    val isStarred = loadedDetail?.isStarred ?: (entry.id in catalog.starredEntryIds)
    val isInWantQueue = loadedDetail?.isInWantQueue ?: (entry.id in catalog.wantEntryIds)
    val appearanceIds = appearances.mapTo(HashSet()) { it.collectionId }
    val addableManualLists =
        catalog.lists.filter { list ->
            isEditableManualUserList(list) &&
                list.collectionId != STARRED_COLLECTION_ID &&
                list.collectionId !in appearanceIds
        }
    val loadedDetailLogs = state.catalogDetailLogs?.takeIf { it.entryId == entry.id }
    val diaryLogs =
        loadedDetailLogs?.logs ?: catalog.recentDiaryEntries.filter { it.entryId == entry.id }
    val isLoadingDiaryLogs =
        loadedDetailLogs == null &&
            (entry.kind == EntryKind.SONG ||
                catalog.totalDiaryCount > catalog.recentDiaryEntries.size) &&
            state.catalogDetailStack.lastOrNull() == entry.id
    return CatalogDetailModel(
        entry = entry,
        appearances = appearances,
        primaryAppearance = appearances.firstOrNull(),
        matchingArtistId = matchingArtistId,
        diaryLogs = diaryLogs,
        diaryLogsTotalCount = loadedDetailLogs?.totalCount ?: diaryLogs.size,
        hasMoreDiaryLogs = loadedDetailLogs?.hasMore == true,
        isLoadingDiaryLogs = isLoadingDiaryLogs,
        genres = extractMetadataGenres(entry.metadataJson),
        tracks =
            if (entry.kind == EntryKind.ALBUM) {
                extractAlbumTracks(entry.metadataJson)
            } else {
                emptyList()
            },
        wikiUrl = entry.resolveWikipediaUrl(),
        artistLibraryAlbums = artistAlbums,
        artistExternalDiscography = externalDiscog,
        isStarred = isStarred,
        isInWantQueue = isInWantQueue,
        addableManualLists = addableManualLists,
    )
}

private fun resolveCatalogEntryById(entryId: String, state: MainUiState): CatalogEntryItem? =
    state.catalogDetail?.entry?.takeIf { it.id == entryId }
        ?: state.activeCollection.entries.firstOrNull { it.entry.id == entryId }?.entry
        ?: state.activeWantQueue.entries.firstOrNull { it.entry.id == entryId }?.entry
        ?: state.starredCollection.entries.firstOrNull { it.entry.id == entryId }?.entry
        ?: state.catalog.wantToReadPreview.firstOrNull { it.entry.id == entryId }?.entry
        ?: state.workspaceSearch.matchedCatalogEntries.firstOrNull { it.entry.id == entryId }?.entry
        ?: state.catalogBrowseSearch.matches.firstOrNull { it.entry.id == entryId }?.entry
        ?: (state.catalog.recentDiaryEntries + state.diary.entries)
            .firstOrNull { it.entryId == entryId }
            ?.let { diary ->
                CatalogEntryItem(
                    id = diary.entryId,
                    kind = diary.kind,
                    title = diary.title,
                    creators = diary.creators,
                    url = diary.url,
                    publishedAt = null,
                    releaseYear = diary.releaseYear,
                    summary = null,
                    thumbnailUrl = diary.thumbnailUrl,
                    identifiers = emptyList(),
                )
            }

private fun formatTracklistSummary(tracks: List<AlbumTrackItem>): String {
    if (tracks.isEmpty()) return ""
    val songWord = if (tracks.size == 1) "song" else "songs"
    val totalMs = tracks.mapNotNull { it.durationMs }.sum()
    val totalMinutes = (totalMs / MS_PER_MINUTE).toInt()
    return if (totalMinutes > 0) {
        "${tracks.size} $songWord · $totalMinutes min"
    } else {
        "${tracks.size} $songWord"
    }
}

private fun formatTrackDuration(durationMs: Long?): String {
    if (durationMs == null || durationMs <= 0L) return ""
    val totalSec = durationMs / MS_PER_SECOND
    val min = totalSec / SECONDS_PER_MINUTE
    val sec = totalSec % SECONDS_PER_MINUTE
    return "%d:%02d".format(Locale.US, min, sec)
}

private fun formatDiaryFullDate(rawIso: String, fallback: String): String {
    val parsed = parseIsoInstant(rawIso) ?: return fallback
    return DIARY_FULL_DATE_FMT.format(parsed.atZone(ZoneOffset.UTC))
}

private val CATALOG_DETAIL_WIDE_BREAKPOINT = 480.dp
private const val ARTIST_BIO_COLLAPSE_CHARS = 220
private const val ALBUM_TRACKLIST_WEIGHT = 1.55f
private const val ALBUM_SIDE_WEIGHT = 1f
private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MS_PER_MINUTE = 60_000L
private val DIARY_FULL_DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
