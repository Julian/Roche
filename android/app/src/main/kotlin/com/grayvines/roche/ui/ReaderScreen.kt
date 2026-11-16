@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package com.grayvines.roche.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.collection.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ThemeMode
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ParsedArticleBlocksState(
    val blocks: List<ArticleBlock>,
    val isParsing: Boolean,
)

private val parsedArticleBlocksCache = LruCache<String, List<ArticleBlock>>(MAX_CACHED_PARSED_DOCS)

@Composable
internal fun rememberParsedArticleBlocks(
    htmlBody: String?,
    baseUrl: String?,
): ParsedArticleBlocksState {
    val cleanHtml = htmlBody?.takeIf { it.isNotBlank() }
    val cacheKey =
        remember(cleanHtml, baseUrl) {
            if (cleanHtml == null) "" else "${baseUrl.orEmpty()}\n$cleanHtml"
        }
    val initialState =
        remember(cacheKey) {
            resolveInitialParsedBlocks(
                cacheKey = cacheKey,
                cleanHtml = cleanHtml,
                baseUrl = baseUrl,
            )
        }
    val state by
        produceState(initialValue = initialState, cacheKey) {
            value = initialState
            if (initialState.isParsing && cleanHtml != null) {
                val parsed =
                    withContext(Dispatchers.Default) {
                        parseHtmlArticleBlocks(rawHtml = cleanHtml, baseUrl = baseUrl)
                    }
                synchronized(parsedArticleBlocksCache) {
                    parsedArticleBlocksCache.put(cacheKey, parsed)
                }
                value = ParsedArticleBlocksState(parsed, isParsing = false)
            }
        }
    return state
}

private fun resolveInitialParsedBlocks(
    cacheKey: String,
    cleanHtml: String?,
    baseUrl: String?,
): ParsedArticleBlocksState {
    if (cleanHtml == null) return ParsedArticleBlocksState(emptyList(), isParsing = false)
    val cached = synchronized(parsedArticleBlocksCache) { parsedArticleBlocksCache[cacheKey] }
    return when {
        cached != null -> {
            ParsedArticleBlocksState(cached, isParsing = false)
        }
        cleanHtml.length <= INLINE_PARSE_CHAR_LIMIT -> {
            val parsed = parseHtmlArticleBlocks(rawHtml = cleanHtml, baseUrl = baseUrl)
            synchronized(parsedArticleBlocksCache) {
                parsedArticleBlocksCache.put(cacheKey, parsed)
            }
            ParsedArticleBlocksState(parsed, isParsing = false)
        }
        else -> {
            ParsedArticleBlocksState(emptyList(), isParsing = true)
        }
    }
}

@Composable
fun ReaderView(
    entry: InboxEntry,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    backLabel: String = "← Inbox",
    settings: Settings = Settings(),
    isHydrating: Boolean = false,
) {
    var showTypographyDrawer by rememberSaveable { mutableStateOf(false) }
    val colors = LocalRocheColors.current
    val listState = rememberLazyListState()
    val parsedState =
        rememberParsedArticleBlocks(htmlBody = resolveReaderHtmlBody(entry), baseUrl = entry.url)
    val awaitingBlankHydration = isHydrating && entry.contentHtml.isNullOrBlank()
    val awaitingContent = isHydrating || parsedState.isParsing

    ReaderScrollProgressEffect(entry, listState, awaitingContent, onAction)

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        ReaderTopToolbar(
            entry = entry,
            listState = listState,
            isAwaitingContent = awaitingContent,
            backLabel = backLabel,
            showTypographyDrawer = showTypographyDrawer,
            onToggleTypography = { showTypographyDrawer = !showTypographyDrawer },
            onAction = onAction,
            onShare = { onShareEntry(entry) },
        )
        ReaderScrollProgressBar(listState = listState, fallbackProgress = entry.readProgress)
        ReaderTypographySection(
            visible = showTypographyDrawer,
            onDismiss = { showTypographyDrawer = false },
            blocks = parsedState.blocks,
            settings = settings,
            listState = listState,
            onAction = onAction,
        )
        ReaderArticleBody(
            entry = entry,
            parsedState = parsedState,
            isHydrating = awaitingBlankHydration,
            listState = listState,
            onSelectTag = { onAction(MainAction.FilterByTag(it)) },
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

internal fun orderedHeadingBlockIndices(blocks: List<ArticleBlock>): List<Int> =
    mapBlockHeadingIndices(blocks).entries.sortedBy { it.value }.map { it.key }

@Composable
internal fun ReaderTypographySection(
    visible: Boolean,
    onDismiss: () -> Unit,
    blocks: List<ArticleBlock>,
    settings: Settings,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
) {
    var backProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) {
        if (!visible) {
            backProgress = 0f
        }
    }
    PredictiveBackHandler(enabled = visible) { progress ->
        try {
            progress.collect { backEvent -> backProgress = backEvent.progress }
            backProgress = 0f
            onDismiss()
        } catch (cancelled: CancellationException) {
            backProgress = 0f
            throw cancelled
        }
    }
    if (!visible) return
    val coroutineScope = rememberCoroutineScope()
    val headingBlockIndices = remember(blocks) { orderedHeadingBlockIndices(blocks) }
    ReaderTypographyDrawer(
        blocks = blocks,
        settings = settings,
        onAction = onAction,
        modifier = Modifier.predictiveBackDrawerGraphics(backProgress),
        onJumpToSection = { headingIndex ->
            headingBlockIndices.getOrNull(headingIndex)?.let { blockIdx ->
                coroutineScope.launch {
                    listState.animateScrollToItem(HEADER_ITEM_COUNT + blockIdx)
                }
            }
        },
    )
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
internal fun ReaderScrollProgressEffect(
    entry: InboxEntry,
    listState: LazyListState,
    isAwaitingContent: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val bodyLengthBucket = readerBodyCharCount(entry)
    var restoredBodyBucket by remember(entry.id) { mutableIntStateOf(-1) }
    var hasUserScrolled by remember(entry.id) { mutableStateOf(false) }
    val totalItems by
        remember(listState) { derivedStateOf { listState.layoutInfo.totalItemsCount } }
    val canScroll by
        remember(listState) {
            derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
        }

    LaunchedEffect(entry.id, bodyLengthBucket, totalItems, canScroll, isAwaitingContent) {
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
                    onAction = onAction,
                )
        }
    }

    LaunchedEffect(entry.id, listState.isScrollInProgress, canScroll) {
        if (listState.isScrollInProgress) {
            hasUserScrolled = true
        } else if (hasUserScrolled && canScroll) {
            maybeCommitScrolledProgress(entry, listState, onAction)
        }
    }
}

@Suppress("LongParameterList")
private suspend fun syncReaderInitialOrFitScroll(
    entry: InboxEntry,
    listState: LazyListState,
    bodyLengthBucket: Int,
    restoredBodyBucket: Int,
    totalItems: Int,
    canScroll: Boolean,
    hasUserScrolled: Boolean,
    onAction: (MainAction) -> Unit,
): Int {
    if (canScroll && !hasUserScrolled && restoredBodyBucket != bodyLengthBucket) {
        if (entry.readProgress > 0.0) {
            val targetIndex =
                (entry.readProgress.coerceIn(0.0, 1.0) * (totalItems - 1))
                    .roundToInt()
                    .coerceIn(0, totalItems - 1)
            listState.scrollToItem(targetIndex)
        }
        return bodyLengthBucket
    }
    if (shouldAutoCompleteViewportFit(entry, bodyLengthBucket, canScroll)) {
        withFrameNanos {}
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
): Boolean {
    val isReadableText =
        entry.contentType != ContentType.VIDEO && entry.contentType != ContentType.AUDIO
    return isReadableText && bodyLengthBucket > 0 && !canScroll && entry.readProgress < 1.0
}

private fun maybeCommitScrolledProgress(
    entry: InboxEntry,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
) {
    val rawProgress = computeLazyListScrollFraction(listState) ?: return
    val crossedCompletion =
        rawProgress >= ARCHIVE_COMPLETION_THRESHOLD &&
            entry.readProgress < ARCHIVE_COMPLETION_THRESHOLD
    if (crossedCompletion || abs(rawProgress - entry.readProgress) >= READ_PROGRESS_MIN_DELTA) {
        onAction(MainAction.UpdateReadProgress(entry.id, rawProgress))
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
        !isAwaitingContent &&
            listState.layoutInfo.totalItemsCount > 0 &&
            readerBodyCharCount(entry) > 0 -> 1.0
        else -> null
    }
}

private fun flushAndToggleReaderArchive(
    entry: InboxEntry,
    listState: LazyListState,
    isAwaitingContent: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val live = computeLiveReaderProgress(entry, listState, isAwaitingContent)
    onAction(MainAction.ToggleArchive(entry = entry, liveReadProgress = live))
}

@Composable
@Suppress("LongParameterList")
private fun ReaderTopToolbar(
    entry: InboxEntry,
    listState: LazyListState,
    isAwaitingContent: Boolean,
    backLabel: String,
    showTypographyDrawer: Boolean,
    onToggleTypography: () -> Unit,
    onAction: (MainAction) -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            HeaderActionButton(
                label = backLabel,
                horizontalPadding = 10.dp,
                onClick = { onAction(MainAction.CloseReader) },
                modifier = Modifier.testTag("reader_back_btn"),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (entry.contentType == ContentType.VIDEO) {
                HeaderActionButton(
                    label = "✓ Finished",
                    style = RocheButtonStyle.PRIMARY,
                    horizontalPadding = 9.dp,
                    onClick = { onAction(MainAction.MarkFeedEntryFinished(entry.id, false)) },
                    modifier = Modifier.testTag("reader_video_finished_btn"),
                )
            }
            val aaStyle =
                if (showTypographyDrawer) RocheButtonStyle.PRIMARY else RocheButtonStyle.SECONDARY
            HeaderActionButton(
                label = "Aa",
                style = aaStyle,
                horizontalPadding = 9.dp,
                onClick = onToggleTypography,
                modifier = Modifier.testTag("reader_typography_btn"),
            )
            CompactStarAndArchiveButtons(
                entry = entry,
                tagPrefix = "reader",
                onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                onToggleArchive = {
                    flushAndToggleReaderArchive(entry, listState, isAwaitingContent, onAction)
                },
            )
            HeaderActionButton(label = "Share", horizontalPadding = 9.dp, onClick = onShare)
            HeaderActionButton(
                label = "•••",
                horizontalPadding = 9.dp,
                onClick = { onAction(MainAction.OpenContextSheet(entry.id)) },
            )
        }
    }
}

@Composable
internal fun ReaderTypographyDrawer(
    blocks: List<ArticleBlock>,
    settings: Settings,
    onAction: (MainAction) -> Unit,
    onJumpToSection: (headingIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val scale = settings.readerFontScale
    val headings = remember(blocks) { extractPlainTextHeadings(blocks) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("reader_typography_drawer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "TEXT SIZE (${(scale * PERCENT_MULTIPLIER).roundToInt()}%)",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
        )
        ReaderFontScaleControlsRow(currentScale = scale, onAction = onAction)
        ReaderAppearanceQuickToggleRow(settings = settings, onAction = onAction)

        if (headings.isNotEmpty()) {
            ReaderSectionsOutlineStrip(
                headings = headings,
                onSelectHeading = onJumpToSection,
            )
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
internal fun ReaderAppearanceQuickToggleRow(
    settings: Settings,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChipPill(
            label = if (settings.themeMode == ThemeMode.LIGHT) "☀ Paper" else "☾ Dark Ink",
            selected = false,
            onClick = {
                val next =
                    if (settings.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
                onAction(MainAction.SetThemeMode(next))
            },
        )
        FilterChipPill(
            label = if (settings.headlineFont == HeadlineFont.SERIF) "Aa Serif" else "Aa Sans",
            selected = false,
            onClick = {
                val next =
                    if (settings.headlineFont == HeadlineFont.SERIF) {
                        HeadlineFont.SANS
                    } else {
                        HeadlineFont.SERIF
                    }
                onAction(MainAction.SetHeadlineFont(next))
            },
        )
    }
}

@Composable
internal fun ReaderFontScaleControlsRow(
    currentScale: Float,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderActionButton(
            label = "A−",
            onClick = {
                onAction(
                    MainAction.SetReaderFontScale(
                        (currentScale - READER_SCALE_STEP).coerceAtLeast(
                            Settings.MIN_READER_FONT_SCALE
                        )
                    )
                )
            },
            modifier = Modifier.testTag("reader_font_smaller_btn"),
        )
        READER_FONT_PRESETS.forEach { preset ->
            val pct = (preset * PERCENT_MULTIPLIER).roundToInt()
            FilterChipPill(
                label = "$pct%",
                selected = abs(currentScale - preset) < READER_SCALE_EPSILON,
                onClick = { onAction(MainAction.SetReaderFontScale(preset)) },
            )
        }
        HeaderActionButton(
            label = "A+",
            onClick = {
                onAction(
                    MainAction.SetReaderFontScale(
                        (currentScale + READER_SCALE_STEP).coerceAtMost(
                            Settings.MAX_READER_FONT_SCALE
                        )
                    )
                )
            },
            modifier = Modifier.testTag("reader_font_larger_btn"),
        )
    }
}

@Composable
internal fun ReaderSectionsOutlineStrip(
    headings: List<String>,
    onSelectHeading: (headingIndex: Int) -> Unit,
) {
    val colors = LocalRocheColors.current
    Text(
        text = "ARTICLE SECTIONS (${headings.size})",
        color = colors.textMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.5.sp,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        headings.forEachIndexed { index, heading ->
            FilterChipPill(
                label = "§ ${heading.take(MAX_SECTION_LABEL_CHARS)}",
                selected = false,
                onClick = { onSelectHeading(index) },
            )
        }
    }
}

@Composable
internal fun CompactStarAndArchiveButtons(
    entry: InboxEntry,
    tagPrefix: String,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val starTint = if (entry.isFavorite) colors.accentEcru else colors.textSecondary
    val starDesc = if (entry.isFavorite) "Remove Star" else "Star Entry"
    val archiveDesc = if (entry.isArchived) "Unarchive to Inbox" else "Archive Entry"

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheIconActionButton(
            contentDescription = starDesc,
            onClick = onToggleStar,
            modifier = Modifier.testTag("${tagPrefix}_star_btn"),
        ) {
            StarIcon(tint = starTint, isStarred = entry.isFavorite, size = 16.dp)
        }
        RocheIconActionButton(
            contentDescription = archiveDesc,
            onClick = onToggleArchive,
            modifier = Modifier.testTag("${tagPrefix}_archive_btn"),
        ) {
            FilingCabinetIcon(
                tint = colors.textSecondary,
                isArchived = entry.isArchived,
                size = 16.dp,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun ReaderArticleBody(
    entry: InboxEntry,
    parsedState: ParsedArticleBlocksState,
    isHydrating: Boolean,
    listState: LazyListState,
    onSelectTag: (String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val linkStyles = rememberReaderLinkStyles()
    val plainParagraphs =
        remember(entry.contentText, entry.summary) {
            (entry.contentText?.takeIf { it.isNotBlank() } ?: entry.summary.orEmpty())
                .split("\n\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("reader_article_list"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "reader_header") {
            ReaderHeaderItem(
                entry = entry,
                dividerColor = colors.border,
                onSelectTag = onSelectTag,
            )
        }
        readerArticleBodyContentItems(
            entry = entry,
            parsedState = parsedState,
            isHydrating = isHydrating,
            plainParagraphs = plainParagraphs,
            mutedColor = colors.textMuted,
            linkStyles = linkStyles,
            onOpenExternalUrl = onOpenExternalUrl,
        )
        item(key = "reader_footer") {
            Column {
                Spacer(modifier = Modifier.height(6.dp))
                HeaderActionButton(
                    label = "Open Original Link ↗",
                    onClick = { onOpenExternalUrl(entry.url) },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Suppress("LongParameterList")
private fun LazyListScope.readerArticleBodyContentItems(
    entry: InboxEntry,
    parsedState: ParsedArticleBlocksState,
    isHydrating: Boolean,
    plainParagraphs: List<String>,
    mutedColor: androidx.compose.ui.graphics.Color,
    linkStyles: androidx.compose.ui.text.TextLinkStyles,
    onOpenExternalUrl: (String) -> Unit,
) {
    if (parsedState.blocks.isNotEmpty()) {
        itemsIndexed(items = parsedState.blocks, key = { idx, _ -> "block_$idx" }) { _, block ->
            SelectionContainer {
                RenderArticleBlock(
                    block = block,
                    linkStyles = linkStyles,
                    baseUrl = entry.url,
                    onOpenUrl = onOpenExternalUrl,
                )
            }
        }
    } else if (parsedState.isParsing || isHydrating && plainParagraphs.isEmpty()) {
        item(key = "reader_loading") {
            Text(text = "Loading full article…", color = mutedColor, fontSize = 14.sp)
        }
    } else if (plainParagraphs.isNotEmpty()) {
        itemsIndexed(items = plainParagraphs, key = { idx, _ -> "plain_$idx" }) { _, paragraph ->
            SelectionContainer { ReaderPlainParagraphItem(paragraph = paragraph) }
        }
    } else {
        item(key = "reader_empty") {
            Text(
                text = "Full article text is not cached locally for this item.",
                color = mutedColor,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun ReaderHeaderItem(
    entry: InboxEntry,
    dividerColor: androidx.compose.ui.graphics.Color,
    onSelectTag: (String) -> Unit,
) {
    Column {
        ReaderArticleHeader(entry = entry)
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
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = dividerColor, thickness = 1.dp)
        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun ReaderPlainParagraphItem(paragraph: String) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    Text(
        text = paragraph,
        color = colors.textPrimary,
        fontFamily = FontFamily.Serif,
        fontSize = (16f * scale).sp,
        lineHeight = (26f * scale).sp,
    )
}

@Composable
internal fun ReaderArticleHeader(entry: InboxEntry) {
    val colors = LocalRocheColors.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
    SelectionContainer {
        Column {
            Text(
                text =
                    "${entry.feedTitle} · ${entry.contentType.wireValue.uppercase()} · ${entry.readEstimateLabel}",
                color = colors.accentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = entry.title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 23.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatReaderByline(entry),
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
internal fun ReaderBodyContent(
    entry: InboxEntry,
    onOpenExternalUrl: (String) -> Unit = {},
    scrollRootCoords: (() -> LayoutCoordinates?)? = null,
    onHeadingMeasured: ((headingIndex: Int, yOffsetPx: Int) -> Unit)? = null,
) {
    val htmlBody = resolveReaderHtmlBody(entry)
    val parsedState = rememberParsedArticleBlocks(htmlBody = htmlBody, baseUrl = entry.url)
    SelectionContainer {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (parsedState.blocks.isNotEmpty()) {
                StructuredHtmlArticleContent(
                    blocks = parsedState.blocks,
                    baseUrl = entry.url,
                    onOpenUrl = onOpenExternalUrl,
                    scrollRootCoords = scrollRootCoords,
                    onHeadingMeasured = onHeadingMeasured,
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
    Spacer(modifier = Modifier.height(8.dp))
    HeaderActionButton(
        label = "Open Original Link ↗",
        onClick = { onOpenExternalUrl(entry.url) },
    )
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

internal fun resolveHeadingScrollPx(
    headingIndex: Int,
    totalHeadings: Int,
    maxScrollPx: Int,
    measuredOffsetsPx: Map<Int, Int>,
): Int {
    val safeMax = maxScrollPx.coerceAtLeast(0)
    val measured = measuredOffsetsPx[headingIndex]
    return if (measured != null) {
        measured.coerceIn(0, safeMax)
    } else {
        val safeCount = totalHeadings.coerceAtLeast(1)
        val fraction = if (totalHeadings <= 1) 0f else headingIndex.toFloat() / safeCount.toFloat()
        (fraction * safeMax).roundToInt().coerceIn(0, safeMax)
    }
}

private fun formatReaderByline(entry: InboxEntry): String = buildString {
    entry.author?.let { append("$it · ") }
    append(entry.formattedPublishedLabel)
    if (entry.isCachedOffline) {
        append(" · Cached offline")
    }
}

private const val HEADER_ITEM_COUNT = 1
private const val INLINE_PARSE_CHAR_LIMIT = 2_048
private const val MAX_CACHED_PARSED_DOCS = 32
private const val MAX_IN_FLIGHT_SCROLL_FRACTION = 0.999
private const val MIN_PROGRESS = 0.08f
private const val READ_PROGRESS_MIN_DELTA = 0.05
private const val ARCHIVE_COMPLETION_THRESHOLD = 0.90
private const val PERCENT_MULTIPLIER = 100
private const val READER_SCALE_STEP = 0.10f
private const val READER_SCALE_EPSILON = 0.03f
private const val MAX_SECTION_LABEL_CHARS = 32
private val READER_FONT_PRESETS = listOf(0.88f, 1.0f, 1.15f, 1.30f)
