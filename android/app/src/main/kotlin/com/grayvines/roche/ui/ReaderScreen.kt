@file:Suppress("TooManyFunctions", "MatchingDeclarationName", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grayvines.roche.core.formatInlineMathAnnotated
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.media.PdfRendererSession
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ThemeMode
import com.grayvines.roche.playback.PodcastPlayerState
import com.grayvines.roche.reader.ArticleBlock
import com.grayvines.roche.reader.ArticleBlockCache
import com.grayvines.roche.reader.mapBlockHeadingIndices
import com.grayvines.roche.reader.stripInlineHtmlToPlainText
import com.grayvines.roche.ui.components.CommentBubbleIcon
import com.grayvines.roche.ui.components.CompactStarAndArchiveButtons
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.MoreDotsIcon
import com.grayvines.roche.ui.components.PredictiveBackDrawerContainer
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheMetaPill
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

val LocalArticleBlockCache = staticCompositionLocalOf { ArticleBlockCache() }

internal data class ParsedArticleBlocksState(
    val blocks: List<ArticleBlock>,
    val isParsing: Boolean,
    val outlineHeadings: List<String> = emptyList(),
    val orderedHeadingBlockIndices: List<Int> = emptyList(),
    val anchorBlockIndices: Map<String, Int> = emptyMap(),
)

@Composable
internal fun rememberParsedArticleBlocks(
    htmlBody: String?,
    baseUrl: String?,
    articleTitle: String? = null,
): ParsedArticleBlocksState {
    val cache = LocalArticleBlockCache.current
    val cleanHtml = htmlBody?.takeIf { it.isNotBlank() }
    val initialState =
        remember(cache, cleanHtml, baseUrl, articleTitle) {
            resolveInitialParsedBlocks(
                cache = cache,
                cleanHtml = cleanHtml,
                baseUrl = baseUrl,
                articleTitle = articleTitle,
            )
        }
    val state by
        produceState(initialValue = initialState, cache, cleanHtml, baseUrl, articleTitle) {
            value = initialState
            if (initialState.isParsing && cleanHtml != null) {
                val parsed =
                    cache.getOrParse(
                        cleanHtml = cleanHtml,
                        baseUrl = baseUrl,
                        articleTitle = articleTitle,
                    )
                value =
                    ParsedArticleBlocksState(
                        blocks = parsed.blocks,
                        isParsing = false,
                        outlineHeadings = parsed.outlineHeadings,
                        orderedHeadingBlockIndices = parsed.orderedHeadingBlockIndices,
                        anchorBlockIndices = parsed.anchorBlockIndices,
                    )
            }
        }
    return state
}

private fun resolveInitialParsedBlocks(
    cache: ArticleBlockCache,
    cleanHtml: String?,
    baseUrl: String?,
    articleTitle: String?,
): ParsedArticleBlocksState {
    if (cleanHtml == null) return ParsedArticleBlocksState(emptyList(), isParsing = false)
    val cached = cache.lookupFast(cleanHtml, baseUrl, articleTitle)
    return if (cached != null) {
        ParsedArticleBlocksState(
            blocks = cached.blocks,
            isParsing = false,
            outlineHeadings = cached.outlineHeadings,
            orderedHeadingBlockIndices = cached.orderedHeadingBlockIndices,
            anchorBlockIndices = cached.anchorBlockIndices,
        )
    } else {
        ParsedArticleBlocksState(emptyList(), isParsing = true)
    }
}

private data class ReaderLayoutMetrics(
    val isLocalPdf: Boolean,
    val hasPdfPages: Boolean,
    val showAudioBar: Boolean,
    val showVideoHero: Boolean,
    val showUncachedPdfCard: Boolean,
    val sectionBlockOffset: Int,
    val pdfFirstItemIndex: Int,
)

private fun resolveReaderLayoutMetrics(
    entry: InboxEntry,
    parsedState: ParsedArticleBlocksState,
    plainParagraphsCount: Int,
    pdfSession: PdfRendererSession?,
    awaitingBlankHydration: Boolean,
): ReaderLayoutMetrics {
    val isLocalPdf = isLocalPdfUrl(entry.url)
    val hasPdfPages = pdfSession != null && pdfSession.pageCount > 0
    val showAudioBar = entry.isPlayableAudio
    val showVideoHero = entry.contentType == ContentType.VIDEO
    val showUncachedPdfCard = !hasPdfPages && isPdfLikeEntry(entry) && !isLocalPdf
    val preBodyCount =
        (if (showAudioBar) 1 else 0) +
            (if (showVideoHero) 1 else 0) +
            if (showUncachedPdfCard) 1 else 0
    val sectionBlockOffset = HEADER_ITEM_COUNT + preBodyCount
    val bodyItemCount =
        computeReaderBodyItemCount(
            blocksCount = parsedState.blocks.size,
            plainCount = plainParagraphsCount,
            hasPdfPages = hasPdfPages,
            showUncachedPdfCard = showUncachedPdfCard,
            isAwaitingBody = parsedState.isParsing || awaitingBlankHydration,
        )
    return ReaderLayoutMetrics(
        isLocalPdf = isLocalPdf,
        hasPdfPages = hasPdfPages,
        showAudioBar = showAudioBar,
        showVideoHero = showVideoHero,
        showUncachedPdfCard = showUncachedPdfCard,
        sectionBlockOffset = sectionBlockOffset,
        pdfFirstItemIndex = sectionBlockOffset + bodyItemCount,
    )
}

private data class ReaderArticleCallbacks(
    val onSelectFeed: (String) -> Unit,
    val onSelectTag: (String) -> Unit,
    val onToggleArchive: () -> Unit,
    val onMarkFinished: () -> Unit,
    val onUnfinish: () -> Unit,
    val onDownloadPdf: () -> Unit,
    val onToggleAudio: () -> Unit,
    val onOpenAudioPlayer: () -> Unit,
    val onToggleOfflineAudio: () -> Unit,
    val onShare: () -> Unit,
    val onEditDetails: () -> Unit,
    val onOpenExternalUrl: (String) -> Unit,
    val onJumpToAnchor: (String) -> Unit,
    val onZoomPdfPage: (Int) -> Unit,
    val onZoomInlineImage: (ImageBitmap, ArticleBlock.ImageBlock) -> Unit,
)

private data class ReaderContentBundle(
    val parsedState: ParsedArticleBlocksState,
    val plainParagraphs: List<String>,
    val pdfSession: PdfRendererSession?,
    val awaitingBlankHydration: Boolean,
    val awaitingContent: Boolean,
    val metrics: ReaderLayoutMetrics,
)

@Composable
private fun rememberReaderContentBundle(
    entry: InboxEntry,
    isHydrating: Boolean,
    isDownloadingOffline: Boolean,
): ReaderContentBundle {
    val parsedState =
        rememberParsedArticleBlocks(
            htmlBody = resolveReaderHtmlBody(entry),
            baseUrl = entry.url,
            articleTitle = entry.title,
        )
    val pdfSession =
        rememberCachedPdfSession(
            entryId = entry.id,
            isCachedOffline = entry.isCachedOffline,
            url = entry.url,
            isDownloadingOffline = isDownloadingOffline,
        )
    val plainParagraphs = rememberReaderPlainParagraphs(entry)
    val awaitingBlankHydration = isHydrating && entry.contentHtml.isNullOrBlank()
    val awaitingContent = isHydrating || parsedState.isParsing
    val metrics =
        resolveReaderLayoutMetrics(
            entry = entry,
            parsedState = parsedState,
            plainParagraphsCount = plainParagraphs.size,
            pdfSession = pdfSession,
            awaitingBlankHydration = awaitingBlankHydration,
        )
    return ReaderContentBundle(
        parsedState = parsedState,
        plainParagraphs = plainParagraphs,
        pdfSession = pdfSession,
        awaitingBlankHydration = awaitingBlankHydration,
        awaitingContent = awaitingContent,
        metrics = metrics,
    )
}

@Composable
@Suppress("LongParameterList")
fun ReaderView(
    entry: InboxEntry,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    backLabel: String = "← Inbox",
    settings: Settings = Settings(),
    isHydrating: Boolean = false,
    isDownloadingOffline: Boolean = false,
    catalogReleaseYear: Int? = null,
    playerStateFlow: StateFlow<PodcastPlayerState>? = null,
) {
    var showTypographyDrawer by rememberSaveable(entry.id) { mutableStateOf(false) }
    var activeLightboxTarget by remember(entry.id) { mutableStateOf<ReaderLightboxTarget?>(null) }
    var suppressAutoFinish by remember(entry.id) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val listState = key(entry.id) { rememberLazyListState() }
    val content =
        rememberReaderContentBundle(
            entry = entry,
            isHydrating = isHydrating,
            isDownloadingOffline = isDownloadingOffline,
        )

    ReaderScrollProgressEffect(
        entry = entry,
        listState = listState,
        isAwaitingContent = content.awaitingContent,
        pdfPageCount = content.pdfSession?.pageCount ?: 0,
        suppressAutoFinish = suppressAutoFinish,
        onAction = onAction,
    )

    ReaderViewScaffold(
        entry = entry,
        backLabel = backLabel,
        settings = settings,
        content = content,
        isDownloadingOffline = isDownloadingOffline,
        catalogReleaseYear = catalogReleaseYear,
        playerStateFlow = playerStateFlow,
        showTypographyDrawer = showTypographyDrawer,
        listState = listState,
        onToggleTypography = { showTypographyDrawer = !showTypographyDrawer },
        onDismissTypography = { showTypographyDrawer = false },
        onUnfinishEntry = {
            suppressAutoFinish = true
            onAction(MainAction.UnfinishFeedEntry(entry.id))
        },
        onAction = onAction,
        onShareEntry = onShareEntry,
        onOpenExternalUrl = onOpenExternalUrl,
        onOpenLightbox = { activeLightboxTarget = it },
    )

    activeLightboxTarget?.let { target ->
        ReaderMediaLightboxDialog(
            target = target,
            onDismiss = { activeLightboxTarget = null },
            onPdfPageChanged = { pageIdx ->
                if (content.pdfSession != null) {
                    coroutineScope.launch {
                        listState.scrollToItem(content.metrics.pdfFirstItemIndex + pageIdx)
                    }
                }
            },
        )
    }
}

@Suppress("LongParameterList")
private fun buildReaderArticleCallbacks(
    entry: InboxEntry,
    listState: LazyListState,
    content: ReaderContentBundle,
    coroutineScope: CoroutineScope,
    onUnfinishEntry: () -> Unit,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenLightbox: (ReaderLightboxTarget) -> Unit,
): ReaderArticleCallbacks =
    ReaderArticleCallbacks(
        onSelectFeed = { fid ->
            onAction(MainAction.CloseReader)
            onAction(MainAction.FilterByFeed(fid))
        },
        onSelectTag = { onAction(MainAction.FilterByTag(it)) },
        onToggleArchive = { onAction(MainAction.ToggleArchive(entry = entry)) },
        onMarkFinished = { onAction(MainAction.MarkFeedEntryFinished(entry.id, false)) },
        onUnfinish = onUnfinishEntry,
        onDownloadPdf = { onAction(MainAction.CacheEntryOffline(entry.id)) },
        onToggleAudio = { onAction(MainAction.PlayAudioEntry(entry.id, openFullscreen = false)) },
        onOpenAudioPlayer = {
            onAction(MainAction.PlayAudioEntry(entry.id, openFullscreen = true))
        },
        onToggleOfflineAudio = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
        onShare = { onShareEntry(entry) },
        onEditDetails = { onAction(MainAction.OpenEditEntrySheet(entry.id)) },
        onOpenExternalUrl = onOpenExternalUrl,
        onJumpToAnchor = { anchorId ->
            jumpToReaderAnchor(anchorId, content, listState, coroutineScope)
        },
        onZoomPdfPage = { pageIdx ->
            content.pdfSession?.let { onOpenLightbox(ReaderLightboxTarget.PdfPage(it, pageIdx)) }
        },
        onZoomInlineImage = { bitmap, block ->
            onOpenLightbox(
                ReaderLightboxTarget.InlineImage(
                    bitmap = bitmap,
                    alt = block.alt,
                    captionText =
                        block.captionPlainText
                            ?: block.captionHtml?.let(::stripInlineHtmlToPlainText),
                )
            )
        },
    )

@Composable
@Suppress("LongParameterList")
private fun ReaderViewScaffold(
    entry: InboxEntry,
    backLabel: String,
    settings: Settings,
    content: ReaderContentBundle,
    isDownloadingOffline: Boolean,
    catalogReleaseYear: Int?,
    playerStateFlow: StateFlow<PodcastPlayerState>?,
    showTypographyDrawer: Boolean,
    listState: LazyListState,
    onToggleTypography: () -> Unit,
    onDismissTypography: () -> Unit,
    onUnfinishEntry: () -> Unit,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenLightbox: (ReaderLightboxTarget) -> Unit,
) {
    val colors = LocalRocheColors.current
    val coroutineScope = rememberCoroutineScope()
    val callbacks =
        buildReaderArticleCallbacks(
            entry = entry,
            listState = listState,
            content = content,
            coroutineScope = coroutineScope,
            onUnfinishEntry = onUnfinishEntry,
            onAction = onAction,
            onShareEntry = onShareEntry,
            onOpenExternalUrl = onOpenExternalUrl,
            onOpenLightbox = onOpenLightbox,
        )
    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        ReaderTopToolbar(
            entry = entry,
            backLabel = backLabel,
            showTypographyDrawer = showTypographyDrawer,
            onToggleTypography = onToggleTypography,
            onAction = onAction,
        )
        ReaderScrollProgressBar(listState = listState, fallbackProgress = entry.readProgress)
        ReaderTypographySection(
            visible = showTypographyDrawer,
            isPdf = content.metrics.hasPdfPages,
            onDismiss = onDismissTypography,
            parsedState = content.parsedState,
            sectionBlockOffset = content.metrics.sectionBlockOffset,
            settings = settings,
            listState = listState,
            onAction = onAction,
        )
        ReaderArticleBody(
            entry = entry,
            parsedState = content.parsedState,
            plainParagraphs = content.plainParagraphs,
            pdfSession = content.pdfSession,
            isHydrating = content.awaitingBlankHydration,
            isDownloadingOffline = isDownloadingOffline,
            catalogReleaseYear = catalogReleaseYear,
            playerStateFlow = playerStateFlow,
            listState = listState,
            callbacks = callbacks,
            modifier = Modifier.weight(1f),
        )
        val pdfSession = content.pdfSession
        if (pdfSession != null && pdfSession.pageCount > 1) {
            ReaderPdfJumpFooter(
                pdfSession = pdfSession,
                listState = listState,
                pdfFirstItemIndex = content.metrics.pdfFirstItemIndex,
                onZoomCurrentPage = { pageIdx ->
                    onOpenLightbox(ReaderLightboxTarget.PdfPage(pdfSession, pageIdx))
                },
            )
        }
    }
}

private fun jumpToReaderAnchor(
    anchorId: String,
    content: ReaderContentBundle,
    listState: LazyListState,
    coroutineScope: CoroutineScope,
) {
    val blockIdx =
        content.parsedState.anchorBlockIndices[anchorId]
            ?: content.parsedState.anchorBlockIndices[anchorId.removePrefix("prose-")]
    if (blockIdx != null) {
        coroutineScope.launch {
            listState.animateScrollToItem(content.metrics.sectionBlockOffset + blockIdx)
        }
    }
}

@Composable
private fun rememberReaderPlainParagraphs(entry: InboxEntry): List<String> =
    remember(entry.contentText, entry.summary) {
        (entry.contentText?.takeIf { it.isNotBlank() } ?: entry.summary.orEmpty())
            .split("\n\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

@Composable
private fun ReaderPdfJumpFooter(
    pdfSession: PdfRendererSession,
    listState: LazyListState,
    pdfFirstItemIndex: Int,
    onZoomCurrentPage: (Int) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val currentVisiblePdfPage by
        remember(listState, pdfFirstItemIndex, pdfSession.pageCount) {
            derivedStateOf {
                (listState.firstVisibleItemIndex - pdfFirstItemIndex).coerceIn(
                    0,
                    pdfSession.pageCount - 1,
                )
            }
        }
    PdfPageJumpBar(
        currentPageIndex = currentVisiblePdfPage,
        pageCount = pdfSession.pageCount,
        onJumpToPage = { pageIdx ->
            coroutineScope.launch { listState.scrollToItem(pdfFirstItemIndex + pageIdx) }
        },
        onZoomCurrentPage = onZoomCurrentPage,
    )
}

internal fun computeReaderBodyItemCount(
    blocksCount: Int,
    plainCount: Int,
    hasPdfPages: Boolean,
    showUncachedPdfCard: Boolean,
    isAwaitingBody: Boolean,
): Int =
    when {
        blocksCount > 0 -> blocksCount
        plainCount > 0 -> plainCount
        !hasPdfPages && isAwaitingBody -> 1
        !hasPdfPages && !showUncachedPdfCard -> 1
        else -> 0
    }

internal fun orderedHeadingBlockIndices(blocks: List<ArticleBlock>): List<Int> =
    mapBlockHeadingIndices(blocks).entries.sortedBy { it.value }.map { it.key }

@Suppress("LongParameterList")
@Composable
internal fun ReaderTypographySection(
    visible: Boolean,
    isPdf: Boolean = false,
    onDismiss: () -> Unit,
    parsedState: ParsedArticleBlocksState,
    sectionBlockOffset: Int = HEADER_ITEM_COUNT,
    settings: Settings,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    PredictiveBackDrawerContainer(visible = visible, onDismiss = onDismiss) { drawerModifier ->
        ReaderTypographyDrawer(
            headings = parsedState.outlineHeadings,
            settings = settings,
            isPdf = isPdf,
            onAction = onAction,
            modifier = drawerModifier,
            onJumpToSection = { headingIndex ->
                onDismiss()
                parsedState.orderedHeadingBlockIndices.getOrNull(headingIndex)?.let { blockIdx ->
                    coroutineScope.launch {
                        listState.animateScrollToItem(sectionBlockOffset + blockIdx)
                    }
                }
            },
        )
    }
}

@Composable
internal fun ReaderScrollProgressBar(
    listState: LazyListState,
    fallbackProgress: Double,
) {
    val colors = LocalRocheColors.current
    val scrollFraction by
        remember(listState, fallbackProgress) {
            derivedStateOf {
                val live = computeLazyListScrollFraction(listState)
                (live ?: fallbackProgress).toFloat().coerceIn(MIN_PROGRESS, 1f)
            }
        }

    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(colors.border)) {
        Box(
            modifier =
                Modifier.fillMaxWidth(scrollFraction).height(2.dp).background(colors.accentBlue)
        )
    }
}

internal fun computeLazyListScrollFraction(listState: LazyListState): Double? {
    val layout = listState.layoutInfo
    val total = layout.totalItemsCount
    val visible = layout.visibleItemsInfo
    if (total <= 0 || visible.isEmpty()) return null
    val canScroll = listState.canScrollForward || listState.canScrollBackward
    if (!canScroll) return null
    if (!listState.canScrollForward) return 1.0
    if (!listState.canScrollBackward) return 0.0
    val avgItemPx = visible.sumOf { it.size }.toDouble() / visible.size.toDouble()
    val scrolledPx =
        listState.firstVisibleItemIndex * avgItemPx + listState.firstVisibleItemScrollOffset
    val viewportPx = (layout.viewportEndOffset - layout.viewportStartOffset).toDouble()
    val maxScrollPx = (total * avgItemPx - viewportPx).coerceAtLeast(1.0)
    return (scrolledPx / maxScrollPx).coerceIn(0.0, MAX_IN_FLIGHT_SCROLL_FRACTION)
}

@Composable
@Suppress("LongParameterList")
internal fun ReaderScrollProgressEffect(
    entry: InboxEntry,
    listState: LazyListState,
    isAwaitingContent: Boolean,
    pdfPageCount: Int = 0,
    suppressAutoFinish: Boolean = false,
    onAction: (MainAction) -> Unit,
) {
    val bodyLengthBucket = readerBodyCharCount(entry) + pdfPageCount
    var restoredBodyBucket by remember(entry.id) { mutableIntStateOf(-1) }
    var hasUserScrolled by remember(entry.id) { mutableStateOf(false) }
    val totalItems by
        remember(listState) { derivedStateOf { listState.layoutInfo.totalItemsCount } }
    val canScroll by
        remember(listState) {
            derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
        }

    LaunchedEffect(
        entry.id,
        entry.status,
        entry.isFinished,
        bodyLengthBucket,
        totalItems,
        canScroll,
        isAwaitingContent,
        suppressAutoFinish,
    ) {
        if (!isAwaitingContent && totalItems > 0) {
            restoredBodyBucket =
                syncReaderInitialOrFitScroll(
                    entry = entry,
                    listState = listState,
                    bodyLengthBucket = bodyLengthBucket,
                    restoredBodyBucket = restoredBodyBucket,
                    totalItems = totalItems,
                    canScroll = canScroll,
                    hasUserScrolled = hasUserScrolled,
                    suppressAutoFinish = suppressAutoFinish,
                    onAction = onAction,
                )
        }
    }

    LaunchedEffect(entry.id, listState.isScrollInProgress, canScroll, suppressAutoFinish) {
        if (listState.isScrollInProgress) {
            hasUserScrolled = true
        } else if (hasUserScrolled && canScroll) {
            maybeCommitScrolledProgress(entry, listState, suppressAutoFinish, onAction)
        }
    }
}

internal fun shouldRestoreReaderScroll(entry: InboxEntry): Boolean =
    !entry.isArchived &&
        entry.readProgress in READER_RESTORE_MIN_PROGRESS..READER_RESTORE_MAX_PROGRESS

@Suppress("LongParameterList")
private suspend fun syncReaderInitialOrFitScroll(
    entry: InboxEntry,
    listState: LazyListState,
    bodyLengthBucket: Int,
    restoredBodyBucket: Int,
    totalItems: Int,
    canScroll: Boolean,
    hasUserScrolled: Boolean,
    suppressAutoFinish: Boolean,
    onAction: (MainAction) -> Unit,
): Int {
    if (canScroll && !hasUserScrolled && restoredBodyBucket != bodyLengthBucket) {
        if (shouldRestoreReaderScroll(entry)) {
            val targetIndex =
                (entry.readProgress.coerceIn(0.0, 1.0) * (totalItems - 1))
                    .roundToInt()
                    .coerceIn(0, totalItems - 1)
            listState.scrollToItem(targetIndex)
        }
        return bodyLengthBucket
    }
    if (shouldAutoCompleteViewportFit(entry, bodyLengthBucket, canScroll, suppressAutoFinish)) {
        withFrameNanos {}
        delay(SHORT_ARTICLE_DWELL_MS)
        val stillFitsViewport =
            listState.layoutInfo.totalItemsCount > 0 &&
                !listState.canScrollForward &&
                !listState.canScrollBackward
        if (stillFitsViewport) {
            onAction(MainAction.UpdateReadProgress(entry.id, 1.0))
        }
    }
    return restoredBodyBucket
}

private fun readerBodyCharCount(entry: InboxEntry): Int =
    (entry.contentHtml?.length ?: 0) +
        (entry.contentText?.length ?: 0) +
        (entry.summary?.length ?: 0)

private fun shouldAutoCompleteViewportFit(
    entry: InboxEntry,
    bodyLengthBucket: Int,
    canScroll: Boolean,
    suppressAutoFinish: Boolean,
): Boolean {
    val isReadableText =
        entry.contentType != ContentType.VIDEO &&
            entry.contentType != ContentType.AUDIO &&
            !entry.isPodcastEpisode
    return entry.isNew &&
        !suppressAutoFinish &&
        isReadableText &&
        bodyLengthBucket > 0 &&
        !canScroll &&
        entry.readProgress < AUTO_FINISH_SCROLL_THRESHOLD
}

private fun maybeCommitScrolledProgress(
    entry: InboxEntry,
    listState: LazyListState,
    suppressAutoFinish: Boolean,
    onAction: (MainAction) -> Unit,
) {
    if (
        entry.contentType == ContentType.VIDEO ||
            entry.contentType == ContentType.AUDIO ||
            entry.isPodcastEpisode
    ) {
        return
    }
    val rawProgress = computeLazyListScrollFraction(listState) ?: return
    val effectiveProgress =
        if (suppressAutoFinish && rawProgress >= AUTO_FINISH_SCROLL_THRESHOLD) {
            SUPPRESSED_MAX_PROGRESS
        } else {
            rawProgress
        }
    val crossedCompletion =
        effectiveProgress >= AUTO_FINISH_SCROLL_THRESHOLD &&
            entry.readProgress < AUTO_FINISH_SCROLL_THRESHOLD
    if (
        crossedCompletion || abs(effectiveProgress - entry.readProgress) >= READ_PROGRESS_MIN_DELTA
    ) {
        onAction(MainAction.UpdateReadProgress(entry.id, effectiveProgress))
    }
}

internal fun computeLiveReaderProgress(
    entry: InboxEntry,
    listState: LazyListState,
    isAwaitingContent: Boolean,
): Double? {
    if (entry.contentType == ContentType.VIDEO || entry.contentType == ContentType.AUDIO) {
        return null
    }
    val scrollFraction = computeLazyListScrollFraction(listState)
    return when {
        scrollFraction != null -> scrollFraction
        !entry.isPlayableAudio &&
            !isAwaitingContent &&
            listState.layoutInfo.totalItemsCount > 0 &&
            readerBodyCharCount(entry) > 0 -> 1.0
        else -> null
    }
}

@Composable
private fun ReaderTopToolbar(
    entry: InboxEntry,
    backLabel: String,
    showTypographyDrawer: Boolean,
    onToggleTypography: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ReaderBackLinkButton(
            backLabel = backLabel,
            onClick = { onAction(MainAction.CloseReader) },
            modifier = Modifier.weight(1f, fill = false),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            ReaderTypographyToggleButton(
                active = showTypographyDrawer,
                onClick = onToggleTypography,
            )
            CompactStarAndArchiveButtons(
                entry = entry,
                tagPrefix = "reader",
                outlined = false,
                onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                onToggleArchive = { onAction(MainAction.ToggleArchive(entry = entry)) },
            )
            RocheIconActionButton(
                contentDescription = "More entry actions",
                outlined = false,
                onClick = { onAction(MainAction.OpenContextSheet(entry.id)) },
                modifier = Modifier.testTag("reader_more_btn"),
            ) {
                MoreDotsIcon(tint = colors.textSecondary, size = 17.dp)
            }
        }
    }
}

@Composable
private fun ReaderBackLinkButton(
    backLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("reader_back_btn"),
    ) {
        Text(
            text = backLabel,
            color = colors.textSecondary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReaderTypographyToggleButton(active: Boolean, onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val bg = if (active) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent
    val textColor = if (active) colors.accentBlue else colors.textSecondary
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Typography settings"
                }
                .testTag("reader_typography_btn"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 34.dp).widthIn(min = 34.dp).clip(shape).background(bg),
        ) {
            Text(
                text = "Aa",
                color = textColor,
                fontFamily = FontFamily.Serif,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
internal fun ReaderTypographyDrawer(
    headings: List<String>,
    settings: Settings,
    onAction: (MainAction) -> Unit,
    onJumpToSection: (headingIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    isPdf: Boolean = false,
) {
    val colors = LocalRocheColors.current
    val scale = settings.readerFontScale

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("reader_typography_drawer"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!isPdf) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ReaderFontSizeStepper(currentScale = scale, onAction = onAction)
                ReaderFontFamilySwitch(currentFont = settings.headlineFont, onAction = onAction)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "THEME",
                color = colors.textMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            ReaderThemeSwitch(currentTheme = settings.themeMode, onAction = onAction)
        }
        if (headings.isNotEmpty()) {
            ReaderSectionsOutlineList(
                headings = headings,
                onSelectHeading = onJumpToSection,
            )
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
private fun ReaderThemeSwitch(
    currentTheme: ThemeMode,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier =
            Modifier.clip(shape)
                .background(colors.background)
                .border(1.dp, colors.border, shape)
                .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderFontOptionTab(
            label = "Dark",
            selected = currentTheme == ThemeMode.DARK,
            onClick = { onAction(MainAction.SetThemeMode(ThemeMode.DARK)) },
            role = Role.RadioButton,
            testTag = "reader_theme_dark_btn",
        )
        ReaderFontOptionTab(
            label = "Light",
            selected = currentTheme == ThemeMode.LIGHT,
            onClick = { onAction(MainAction.SetThemeMode(ThemeMode.LIGHT)) },
            role = Role.RadioButton,
            testTag = "reader_theme_light_btn",
        )
    }
}

@Composable
private fun ReaderFontSizeStepper(
    currentScale: Float,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val pct = (currentScale * PERCENT_MULTIPLIER).roundToInt()
    val canDecrease = currentScale > Settings.MIN_READER_FONT_SCALE + READER_SCALE_EPSILON
    val canIncrease = currentScale < Settings.MAX_READER_FONT_SCALE - READER_SCALE_EPSILON
    val shape = RoundedCornerShape(8.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier.clip(shape)
                .background(colors.background)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 2.dp),
    ) {
        ReaderStepperButton(
            label = "A−",
            contentDescription = "Decrease font size",
            testTag = "reader_font_smaller_btn",
            enabled = canDecrease,
            onClick = {
                onAction(
                    MainAction.SetReaderFontScale(
                        (currentScale - READER_SCALE_STEP).coerceAtLeast(
                            Settings.MIN_READER_FONT_SCALE
                        )
                    )
                )
            },
        )
        Text(
            text = "$pct%",
            color = colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.widthIn(min = 38.dp),
        )
        ReaderStepperButton(
            label = "A+",
            contentDescription = "Increase font size",
            testTag = "reader_font_larger_btn",
            enabled = canIncrease,
            onClick = {
                onAction(
                    MainAction.SetReaderFontScale(
                        (currentScale + READER_SCALE_STEP).coerceAtMost(
                            Settings.MAX_READER_FONT_SCALE
                        )
                    )
                )
            },
        )
    }
}

@Composable
private fun ReaderStepperButton(
    label: String,
    contentDescription: String,
    testTag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val labelColor = if (enabled) colors.textPrimary else colors.textMuted.copy(alpha = 0.45f)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                    if (!enabled) {
                        disabled()
                    }
                }
                .testTag(testTag),
    ) {
        Text(
            text = label,
            color = labelColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun ReaderFontFamilySwitch(
    currentFont: HeadlineFont,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier =
            Modifier.clip(shape)
                .background(colors.background)
                .border(1.dp, colors.border, shape)
                .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderFontOptionTab(
            label = "Serif",
            selected = currentFont == HeadlineFont.SERIF,
            onClick = { onAction(MainAction.SetHeadlineFont(HeadlineFont.SERIF)) },
            role = Role.RadioButton,
        )
        ReaderFontOptionTab(
            label = "Sans",
            selected = currentFont == HeadlineFont.SANS,
            onClick = { onAction(MainAction.SetHeadlineFont(HeadlineFont.SANS)) },
            role = Role.RadioButton,
        )
    }
}

@Composable
private fun ReaderFontOptionTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    role: Role = Role.Tab,
    testTag: String? = null,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(6.dp)
    val bg = if (selected) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent
    val fg = if (selected) colors.accentBlue else colors.textSecondary
    val baseModifier =
        Modifier.minimumInteractiveComponentSize()
            .clip(shape)
            .clickable(onClick = onClick)
            .semantics {
                this.role = role
                this.selected = selected
            }
            .let { mod -> if (testTag != null) mod.testTag(testTag) else mod }
    Box(
        contentAlignment = Alignment.Center,
        modifier = baseModifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 32.dp)
                    .clip(shape)
                    .background(bg)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                color = fg,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun ReaderSectionsOutlineList(
    headings: List<String>,
    onSelectHeading: (headingIndex: Int) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "SECTIONS (${headings.size})",
            color = colors.textMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
        )
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = 192.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            headings.forEachIndexed { index, heading ->
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelectHeading(index) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}.",
                        color = colors.textMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = heading,
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun ReaderArticleBody(
    entry: InboxEntry,
    parsedState: ParsedArticleBlocksState,
    plainParagraphs: List<String>,
    pdfSession: PdfRendererSession?,
    isHydrating: Boolean,
    isDownloadingOffline: Boolean,
    catalogReleaseYear: Int?,
    playerStateFlow: StateFlow<PodcastPlayerState>?,
    listState: LazyListState,
    callbacks: ReaderArticleCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val linkStyles = rememberReaderLinkStyles()
    val isLocalPdf = isLocalPdfUrl(entry.url)
    val hasPdfPages = pdfSession != null && pdfSession.pageCount > 0
    val showUncachedPdfCard = !hasPdfPages && isPdfLikeEntry(entry) && !isLocalPdf

    CompositionLocalProvider(LocalReaderAnchorNavigator provides callbacks.onJumpToAnchor) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val horizontalPadding =
                resolveReaderHorizontalPadding(maxWidth = maxWidth, isPdf = hasPdfPages)
            val proseModifier = Modifier.readerProsePadding(hasPdfPages, maxWidth)

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("reader_article_list"),
                contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                readerPreBodyItems(
                    entry = entry,
                    isLocalPdf = isLocalPdf,
                    catalogReleaseYear = catalogReleaseYear,
                    showUncachedPdfCard = showUncachedPdfCard,
                    isDownloadingOffline = isDownloadingOffline,
                    playerStateFlow = playerStateFlow,
                    dividerColor = colors.border,
                    proseModifier = proseModifier,
                    callbacks = callbacks,
                )
                readerArticleBodyContentItems(
                    entry = entry,
                    parsedState = parsedState,
                    hasPdfPages = hasPdfPages,
                    isHydrating = isHydrating,
                    showUncachedPdfCard = showUncachedPdfCard,
                    plainParagraphs = plainParagraphs,
                    proseModifier = proseModifier,
                    mutedColor = colors.textMuted,
                    linkStyles = linkStyles,
                    callbacks = callbacks,
                )
                readerPdfPageItems(
                    pdfSession = pdfSession,
                    entry = entry,
                    isLocalPdf = isLocalPdf,
                    proseModifier = proseModifier,
                    callbacks = callbacks,
                )
                readerFooterItem(
                    entry = entry,
                    isLocalPdf = isLocalPdf,
                    proseModifier = proseModifier,
                    callbacks = callbacks,
                )
            }
        }
    }
}

private fun LazyListScope.readerFooterItem(
    entry: InboxEntry,
    isLocalPdf: Boolean,
    proseModifier: Modifier,
    callbacks: ReaderArticleCallbacks,
) {
    item(key = "reader_footer") {
        Box(modifier = proseModifier) {
            ReaderEndCard(
                entry = entry,
                isLocalPdf = isLocalPdf,
                onToggleArchive = callbacks.onToggleArchive,
                onMarkFinished = callbacks.onMarkFinished,
                onUnfinish = callbacks.onUnfinish,
                onShare = callbacks.onShare,
                onEditDetails = callbacks.onEditDetails,
                onOpenExternalUrl = callbacks.onOpenExternalUrl,
            )
        }
    }
}

@Suppress("LongParameterList")
private fun LazyListScope.readerPreBodyItems(
    entry: InboxEntry,
    isLocalPdf: Boolean,
    catalogReleaseYear: Int?,
    showUncachedPdfCard: Boolean,
    isDownloadingOffline: Boolean,
    playerStateFlow: StateFlow<PodcastPlayerState>?,
    dividerColor: Color,
    proseModifier: Modifier,
    callbacks: ReaderArticleCallbacks,
) {
    item(key = "reader_header") {
        Box(modifier = proseModifier) {
            ReaderHeaderItem(
                entry = entry,
                isLocalPdf = isLocalPdf,
                catalogReleaseYear = catalogReleaseYear,
                dividerColor = dividerColor,
                onSelectFeed = callbacks.onSelectFeed,
                onSelectTag = callbacks.onSelectTag,
                onOpenExternalUrl = callbacks.onOpenExternalUrl,
            )
        }
    }
    if (entry.isPlayableAudio) {
        item(key = "reader_audio_bar") {
            ReaderAudioBarSlot(
                entry = entry,
                playerStateFlow = playerStateFlow,
                isDownloadingOffline = isDownloadingOffline,
                callbacks = callbacks,
                modifier = proseModifier,
            )
        }
    }
    if (entry.contentType == ContentType.VIDEO) {
        item(key = "reader_video_hero") {
            ReaderVideoHeroCard(
                entry = entry,
                onWatchExternal = callbacks.onOpenExternalUrl,
                onMarkFinished = callbacks.onMarkFinished,
                onUnfinish = callbacks.onUnfinish,
                onToggleDismiss = callbacks.onToggleArchive,
                modifier = proseModifier,
            )
        }
    }
    if (showUncachedPdfCard) {
        item(key = "reader_uncached_pdf") {
            ReaderUncachedPdfCard(
                externalUrl = entry.url.takeIf { it.isNotBlank() },
                isDownloading = isDownloadingOffline,
                onDownloadPdf = callbacks.onDownloadPdf,
                onOpenExternalUrl = callbacks.onOpenExternalUrl,
                modifier = proseModifier,
            )
        }
    }
}

@Composable
private fun ReaderAudioBarSlot(
    entry: InboxEntry,
    playerStateFlow: StateFlow<PodcastPlayerState>?,
    isDownloadingOffline: Boolean,
    callbacks: ReaderArticleCallbacks,
    modifier: Modifier = Modifier,
) {
    val playerState = playerStateFlow?.collectAsStateWithLifecycle()?.value
    ReaderAudioPlayerCard(
        entry = entry,
        playerState = playerState,
        isDownloadingOffline = isDownloadingOffline,
        onTogglePlay = callbacks.onToggleAudio,
        onOpenPlayer = callbacks.onOpenAudioPlayer,
        onToggleOffline = callbacks.onToggleOfflineAudio,
        modifier = modifier,
    )
}

private fun Modifier.readerProsePadding(hasPdfPages: Boolean, maxWidth: Dp): Modifier =
    if (hasPdfPages && maxWidth < 500.dp) this.padding(horizontal = 10.dp) else this

internal fun resolveReaderHorizontalPadding(maxWidth: Dp, isPdf: Boolean): Dp =
    if (isPdf) {
        when {
            maxWidth > MAX_READER_PDF_WIDTH ->
                ((maxWidth - MAX_READER_PDF_WIDTH) / 2f).coerceAtLeast(24.dp)
            maxWidth >= 500.dp -> 16.dp
            else -> 8.dp
        }
    } else {
        when {
            maxWidth > MAX_READER_PROSE_WIDTH ->
                ((maxWidth - MAX_READER_PROSE_WIDTH) / 2f).coerceAtLeast(24.dp)
            maxWidth >= 500.dp -> 28.dp
            else -> 18.dp
        }
    }

@Suppress("LongParameterList")
private fun LazyListScope.readerArticleBodyContentItems(
    entry: InboxEntry,
    parsedState: ParsedArticleBlocksState,
    hasPdfPages: Boolean,
    isHydrating: Boolean,
    showUncachedPdfCard: Boolean,
    plainParagraphs: List<String>,
    proseModifier: Modifier,
    mutedColor: Color,
    linkStyles: androidx.compose.ui.text.TextLinkStyles,
    callbacks: ReaderArticleCallbacks,
) {
    if (parsedState.blocks.isNotEmpty()) {
        itemsIndexed(items = parsedState.blocks, key = { idx, _ -> "block_$idx" }) { _, block ->
            Box(modifier = proseModifier) {
                SelectionContainer {
                    RenderArticleBlock(
                        block = block,
                        linkStyles = linkStyles,
                        baseUrl = entry.url,
                        onOpenUrl = callbacks.onOpenExternalUrl,
                        onZoomImage = callbacks.onZoomInlineImage,
                    )
                }
            }
        }
    } else if (plainParagraphs.isNotEmpty()) {
        itemsIndexed(items = plainParagraphs, key = { idx, _ -> "plain_$idx" }) { _, paragraph ->
            Box(modifier = proseModifier) {
                SelectionContainer { ReaderPlainParagraphItem(paragraph = paragraph) }
            }
        }
    } else if (!hasPdfPages && (parsedState.isParsing || isHydrating)) {
        item(key = "reader_loading") {
            Text(
                text = "Loading full article…",
                color = mutedColor,
                fontSize = 14.sp,
                modifier = proseModifier,
            )
        }
    } else if (!hasPdfPages && !showUncachedPdfCard) {
        item(key = "reader_empty") {
            Text(
                text = "Full article text is not cached locally for this item.",
                color = mutedColor,
                fontSize = 14.sp,
                modifier = proseModifier,
            )
        }
    }
}

private fun LazyListScope.readerPdfPageItems(
    pdfSession: PdfRendererSession?,
    entry: InboxEntry,
    isLocalPdf: Boolean,
    proseModifier: Modifier,
    callbacks: ReaderArticleCallbacks,
) {
    if (pdfSession == null || pdfSession.pageCount <= 0) return
    items(count = pdfSession.pageCount, key = { idx -> "pdf_page_$idx" }) { idx ->
        PdfPageCardItem(
            session = pdfSession,
            pageIndex = idx,
            onZoomPage = callbacks.onZoomPdfPage,
        )
    }
    if (pdfSession.isTruncated) {
        item(key = "reader_pdf_truncated") {
            Box(modifier = proseModifier) {
                PdfTruncationNoticeCard(
                    renderedPages = pdfSession.pageCount,
                    totalPages = pdfSession.totalPageCount,
                    externalUrl = entry.url.takeIf { !isLocalPdf && it.isNotBlank() },
                    onOpenExternalUrl = callbacks.onOpenExternalUrl,
                )
            }
        }
    }
}

@Composable
private fun ReaderHeaderItem(
    entry: InboxEntry,
    isLocalPdf: Boolean,
    catalogReleaseYear: Int?,
    dividerColor: Color,
    onSelectFeed: (String) -> Unit,
    onSelectTag: (String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    Column {
        ReaderArticleHeader(
            entry = entry,
            isLocalPdf = isLocalPdf,
            catalogReleaseYear = catalogReleaseYear,
            onSelectFeed = onSelectFeed,
            onOpenExternalUrl = onOpenExternalUrl,
        )
        if (entry.tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                entry.tags.forEach { tag ->
                    FilterChipPill(
                        label = "#$tag",
                        selected = false,
                        onClick = { onSelectTag(tag) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = dividerColor, thickness = 1.dp)
        Spacer(modifier = Modifier.height(2.dp))
    }
}

private data class ReaderEndCardPresentation(
    val showFinishCta: Boolean,
    val showUnfinishCta: Boolean,
    val headingText: String,
    val dismissLabel: String?,
    val externalUrl: String?,
    val discussions: List<com.grayvines.roche.core.DiscussionLink> = emptyList(),
)

private fun resolveReaderEndCardModel(
    entry: InboxEntry,
    isLocalPdf: Boolean,
): ReaderEndCardPresentation {
    val isWantEntry = !entry.inFeedInbox
    val headingText =
        when {
            entry.isFinished -> LABEL_FINISHED
            entry.isDismissed -> "Dismissed"
            else -> "Finished reading?"
        }
    val dismissLabel =
        when {
            entry.isDismissed && isWantEntry -> "Restore to Want"
            entry.isDismissed -> "Mark New"
            entry.isFinished && isWantEntry -> null
            isWantEntry -> "Remove from Want"
            else -> "Dismiss"
        }
    return ReaderEndCardPresentation(
        showFinishCta = !entry.isFinished,
        showUnfinishCta = entry.isFinished,
        headingText = headingText,
        dismissLabel = dismissLabel,
        externalUrl = entry.url.takeIf { !isLocalPdf && it.isNotBlank() },
        discussions = visibleExternalDiscussions(entry),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList")
@Composable
private fun ReaderEndCard(
    entry: InboxEntry,
    isLocalPdf: Boolean,
    onToggleArchive: () -> Unit,
    onMarkFinished: () -> Unit,
    onUnfinish: () -> Unit,
    onShare: () -> Unit,
    onEditDetails: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val presentation = resolveReaderEndCardModel(entry = entry, isLocalPdf = isLocalPdf)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp)) {
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))
        RocheSurfaceCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = presentation.headingText,
                    color = if (entry.isFinished) colors.accentVerdigris else colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (entry.isFinished) FontWeight.Medium else FontWeight.Normal,
                )
                ReaderEndCardActionsRow(
                    presentation = presentation,
                    onToggleArchive = onToggleArchive,
                    onMarkFinished = onMarkFinished,
                    onUnfinish = onUnfinish,
                    onShare = onShare,
                    onEditDetails = onEditDetails,
                    onOpenExternalUrl = onOpenExternalUrl,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList")
@Composable
private fun ReaderEndCardActionsRow(
    presentation: ReaderEndCardPresentation,
    onToggleArchive: () -> Unit,
    onMarkFinished: () -> Unit,
    onUnfinish: () -> Unit,
    onShare: () -> Unit,
    onEditDetails: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (presentation.showFinishCta) {
            HeaderActionButton(
                label = LABEL_FINISHED,
                style = RocheButtonStyle.PRIMARY,
                onClick = onMarkFinished,
                modifier = Modifier.testTag("reader_end_finished_btn"),
            )
        }
        if (presentation.showUnfinishCta) {
            HeaderActionButton(
                label = "Undo Finished",
                style = RocheButtonStyle.SECONDARY,
                onClick = onUnfinish,
                modifier = Modifier.testTag("reader_end_unfinish_btn"),
            )
        }
        presentation.dismissLabel?.let { label ->
            HeaderActionButton(
                label = label,
                style = RocheButtonStyle.SECONDARY,
                onClick = onToggleArchive,
                modifier = Modifier.testTag("reader_end_archive_btn"),
            )
        }
        HeaderActionButton(
            label = "Edit Details",
            onClick = onEditDetails,
            modifier = Modifier.testTag("reader_end_edit_btn"),
        )
        HeaderActionButton(label = "Share", onClick = onShare)
        presentation.externalUrl?.let { url ->
            HeaderActionButton(
                label = "Original ↗",
                onClick = { onOpenExternalUrl(url) },
                modifier = Modifier.testTag("reader_end_original_btn"),
            )
        }
        presentation.discussions.forEachIndexed { idx, link ->
            HeaderActionButton(
                label = "${link.displayLabel} ↗",
                onClick = { onOpenExternalUrl(link.url) },
                modifier = Modifier.testTag("reader_end_discussion_btn_$idx"),
            )
        }
    }
}

@Composable
private fun ReaderPlainParagraphItem(paragraph: String) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    Text(
        text = paragraph,
        color = colors.textPrimary,
        fontFamily = currentHeadlineFontFamily(),
        fontSize = (16f * scale).sp,
        lineHeight = (26f * scale).sp,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderArticleHeader(
    entry: InboxEntry,
    isLocalPdf: Boolean = isLocalPdfUrl(entry.url),
    catalogReleaseYear: Int? = null,
    onSelectFeed: (String) -> Unit = {},
    onOpenExternalUrl: (String) -> Unit = {},
) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val headlineFamily = currentHeadlineFontFamily()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SelectionContainer {
            Text(
                text = remember(entry.title) { formatInlineMathAnnotated(entry.title) },
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = (23f * scale).sp,
                lineHeight = (29.5f * scale).sp,
                fontWeight = FontWeight.Medium,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ReaderStateBadgePill(entry = entry)
            ReaderBylineFeedChip(entry = entry, onSelectFeed = onSelectFeed)
            Text(
                text = formatReaderByline(entry, catalogReleaseYear),
                color = colors.textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
            )
            if (!isLocalPdf && entry.url.isNotBlank()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenExternalUrl(entry.url) }
                            .semantics { role = Role.Button }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .testTag("reader_byline_original_btn"),
                ) {
                    Text(
                        text = "Original ↗",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
            visibleExternalDiscussions(entry).forEachIndexed { idx, link ->
                ReaderDiscussionLinkPill(
                    label = link.displayLabel,
                    onClick = { onOpenExternalUrl(link.url) },
                    modifier = Modifier.testTag("reader_byline_discussion_$idx"),
                )
            }
        }
    }
}

@Composable
private fun ReaderDiscussionLinkPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier.clip(shape)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.border, shape)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            CommentBubbleIcon(tint = colors.accentEcru, size = 12.dp)
            Text(
                text = "$label ↗",
                color = colors.accentEcru,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ReaderStateBadgePill(entry: InboxEntry) {
    val colors = LocalRocheColors.current
    if (entry.isFinished) {
        RocheMetaPill(
            text = LABEL_FINISHED,
            textColor = colors.accentVerdigris,
            modifier = Modifier.testTag("reader_state_badge"),
        )
    } else if (entry.isDismissed) {
        RocheMetaPill(
            text = "✕ Dismissed",
            textColor = colors.textMuted,
            modifier = Modifier.testTag("reader_state_badge"),
        )
    }
}

@Composable
private fun ReaderBylineFeedChip(
    entry: InboxEntry,
    onSelectFeed: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val feedId = entry.feedId
    val baseMod =
        if (feedId != null) {
            Modifier.minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(6.dp))
                .clickable { onSelectFeed(feedId) }
                .semantics { role = Role.Button }
                .padding(end = 4.dp, top = 2.dp, bottom = 2.dp)
        } else {
            Modifier
        }
    Row(
        modifier = baseMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
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
            color = colors.accentBlue,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ReaderBodyContent(
    entry: InboxEntry,
    onOpenExternalUrl: (String) -> Unit = {},
) {
    val htmlBody = resolveReaderHtmlBody(entry)
    val parsedState =
        rememberParsedArticleBlocks(
            htmlBody = htmlBody,
            baseUrl = entry.url,
            articleTitle = entry.title,
        )
    var activeImageTarget by
        remember(entry.id) { mutableStateOf<ReaderLightboxTarget.InlineImage?>(null) }
    SelectionContainer {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (parsedState.blocks.isNotEmpty()) {
                StructuredHtmlArticleContent(
                    blocks = parsedState.blocks,
                    baseUrl = entry.url,
                    onOpenUrl = onOpenExternalUrl,
                    onZoomImage = { bitmap, block ->
                        activeImageTarget =
                            ReaderLightboxTarget.InlineImage(
                                bitmap = bitmap,
                                alt = block.alt,
                                captionText =
                                    block.captionPlainText
                                        ?: block.captionHtml?.let(::stripInlineHtmlToPlainText),
                            )
                    },
                )
            } else if (parsedState.isParsing) {
                Text(
                    text = "Loading notes…",
                    color = LocalRocheColors.current.textMuted,
                    fontSize = 14.sp,
                )
            } else {
                ReaderPlainTextParagraphs(entry = entry)
            }
        }
    }
    if (!isLocalPdfUrl(entry.url) && entry.url.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        HeaderActionButton(
            label = "Open Original Link ↗",
            onClick = { onOpenExternalUrl(entry.url) },
        )
    }
    activeImageTarget?.let { target ->
        ReaderMediaLightboxDialog(target = target, onDismiss = { activeImageTarget = null })
    }
}

@Composable
internal fun ReaderPlainTextParagraphs(entry: InboxEntry) {
    val colors = LocalRocheColors.current
    val bodyParagraphs =
        (entry.contentText?.takeIf { it.isNotBlank() } ?: entry.summary.orEmpty())
            .split("\n\n")
            .filter { it.isNotBlank() }

    if (bodyParagraphs.isEmpty()) {
        Text(
            text = "Full article text is not cached locally for this item.",
            color = colors.textMuted,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(12.dp))
    } else {
        bodyParagraphs.forEach { paragraph ->
            ReaderPlainParagraphItem(paragraph = paragraph.trim())
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

internal fun resolveReaderHtmlBody(entry: InboxEntry): String? =
    entry.contentHtml?.takeIf { it.isNotBlank() }
        ?: entry.summary?.takeIf { "<" in it || "&" in it }

private fun formatReaderByline(entry: InboxEntry, catalogReleaseYear: Int? = null): String =
    buildList {
        entry.author?.takeIf { it.isNotBlank() }?.let { add(it) }
        val dateLabel =
            if (!entry.publishedAt.isNullOrBlank()) {
                entry.formattedPublishedLabel
            } else if (catalogReleaseYear != null && catalogReleaseYear > 0) {
                catalogReleaseYear.toString()
            } else {
                entry.formattedPublishedLabel
            }
        add(dateLabel)
        add(entry.readEstimateLabel)
        if (entry.isCachedOffline) {
            add("Offline")
        }
    }
    .joinToString(" · ", prefix = "· ")

private val MAX_READER_PROSE_WIDTH = 520.dp
private val MAX_READER_PDF_WIDTH = 720.dp
private const val HEADER_ITEM_COUNT = 1
private const val MAX_IN_FLIGHT_SCROLL_FRACTION = 0.999
private const val MIN_PROGRESS = 0.08f
private const val READ_PROGRESS_MIN_DELTA = 0.05
private const val AUTO_FINISH_SCROLL_THRESHOLD = 0.98
private const val SUPPRESSED_MAX_PROGRESS = 0.95
private const val SHORT_ARTICLE_DWELL_MS = 4_000L
private const val LABEL_FINISHED = "✓ Finished"
private const val PERCENT_MULTIPLIER = 100
private const val READER_SCALE_STEP = 0.10f
private const val READER_SCALE_EPSILON = 0.005f
private const val READER_RESTORE_MIN_PROGRESS = 0.02
private const val READER_RESTORE_MAX_PROGRESS = 0.95
