@file:Suppress("TooManyFunctions", "MatchingDeclarationName", "MagicNumber")

package com.grayvines.roche.ui

import androidx.collection.LruCache
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.media.PdfRendererSession
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ThemeMode
import com.grayvines.roche.reader.ArticleBlock
import com.grayvines.roche.reader.extractPlainTextHeadings
import com.grayvines.roche.reader.mapBlockHeadingIndices
import com.grayvines.roche.reader.parseHtmlArticleBlocks
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.MoreDotsIcon
import com.grayvines.roche.ui.components.PredictiveBackDrawerContainer
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ParsedArticleBlocksState(
    val blocks: List<ArticleBlock>,
    val isParsing: Boolean,
)

private data class CachedIdentityBlocks(
    val rawHtml: String,
    val baseUrl: String?,
    val blocks: List<ArticleBlock>,
)

private val parsedArticleBlocksCache = LruCache<String, List<ArticleBlock>>(MAX_CACHED_PARSED_DOCS)
private val recentIdentityBlocksCache = LruCache<Int, CachedIdentityBlocks>(MAX_CACHED_PARSED_DOCS)

private fun articleBlocksCacheKey(cleanHtml: String, baseUrl: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(baseUrl.orEmpty().toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(cleanHtml.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun lookupIdentityCachedBlocks(
    cleanHtml: String,
    baseUrl: String?,
): List<ArticleBlock>? {
    val identityKey = System.identityHashCode(cleanHtml)
    return synchronized(recentIdentityBlocksCache) {
        recentIdentityBlocksCache[identityKey]
            ?.takeIf { it.rawHtml === cleanHtml && it.baseUrl == baseUrl }
            ?.blocks
    }
}

private fun cacheIdentityBlocks(
    cleanHtml: String,
    baseUrl: String?,
    blocks: List<ArticleBlock>,
) {
    val identityKey = System.identityHashCode(cleanHtml)
    synchronized(recentIdentityBlocksCache) {
        recentIdentityBlocksCache.put(
            identityKey,
            CachedIdentityBlocks(rawHtml = cleanHtml, baseUrl = baseUrl, blocks = blocks),
        )
    }
}

private suspend fun loadOrParseArticleBlocks(
    cleanHtml: String,
    baseUrl: String?,
): List<ArticleBlock> =
    withContext(Dispatchers.Default) {
        val shaKey = articleBlocksCacheKey(cleanHtml, baseUrl)
        val cached = synchronized(parsedArticleBlocksCache) { parsedArticleBlocksCache[shaKey] }
        if (cached != null) {
            cached
        } else {
            val parsed = parseHtmlArticleBlocks(rawHtml = cleanHtml, baseUrl = baseUrl)
            synchronized(parsedArticleBlocksCache) { parsedArticleBlocksCache.put(shaKey, parsed) }
            parsed
        }
    }

@Composable
internal fun rememberParsedArticleBlocks(
    htmlBody: String?,
    baseUrl: String?,
): ParsedArticleBlocksState {
    val cleanHtml = htmlBody?.takeIf { it.isNotBlank() }
    val initialState =
        remember(cleanHtml, baseUrl) {
            resolveInitialParsedBlocks(
                cleanHtml = cleanHtml,
                baseUrl = baseUrl,
            )
        }
    val state by
        produceState(initialValue = initialState, cleanHtml, baseUrl) {
            value = initialState
            if (initialState.isParsing && cleanHtml != null) {
                val parsed = loadOrParseArticleBlocks(cleanHtml, baseUrl)
                cacheIdentityBlocks(cleanHtml, baseUrl, parsed)
                value = ParsedArticleBlocksState(parsed, isParsing = false)
            }
        }
    return state
}

private fun resolveInitialParsedBlocks(
    cleanHtml: String?,
    baseUrl: String?,
): ParsedArticleBlocksState {
    if (cleanHtml == null) return ParsedArticleBlocksState(emptyList(), isParsing = false)
    val cached = lookupIdentityCachedBlocks(cleanHtml, baseUrl)
    return if (cached != null) {
        ParsedArticleBlocksState(cached, isParsing = false)
    } else {
        ParsedArticleBlocksState(emptyList(), isParsing = true)
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
    var showTypographyDrawer by rememberSaveable(entry.id) { mutableStateOf(false) }
    val colors = LocalRocheColors.current
    val listState = key(entry.id) { rememberLazyListState() }
    val parsedState =
        rememberParsedArticleBlocks(htmlBody = resolveReaderHtmlBody(entry), baseUrl = entry.url)
    val pdfSession =
        rememberCachedPdfSession(
            entryId = entry.id,
            isCachedOffline = entry.isCachedOffline,
            url = entry.url,
        )
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
        )
        ReaderScrollProgressBar(listState = listState, fallbackProgress = entry.readProgress)
        ReaderTypographySection(
            visible = showTypographyDrawer,
            isPdf = pdfSession != null || entry.contentType == ContentType.PDF,
            onDismiss = { showTypographyDrawer = false },
            blocks = parsedState.blocks,
            settings = settings,
            listState = listState,
            onAction = onAction,
        )
        ReaderArticleBody(
            entry = entry,
            parsedState = parsedState,
            pdfSession = pdfSession,
            isHydrating = awaitingBlankHydration,
            listState = listState,
            onSelectFeed = { fid ->
                onAction(MainAction.CloseReader)
                onAction(MainAction.FilterByFeed(fid))
            },
            onSelectTag = { onAction(MainAction.FilterByTag(it)) },
            onToggleArchive = {
                flushAndToggleReaderArchive(entry, listState, awaitingContent, onAction)
            },
            onShare = { onShareEntry(entry) },
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

internal fun orderedHeadingBlockIndices(blocks: List<ArticleBlock>): List<Int> =
    mapBlockHeadingIndices(blocks).entries.sortedBy { it.value }.map { it.key }

@Suppress("LongParameterList")
@Composable
internal fun ReaderTypographySection(
    visible: Boolean,
    isPdf: Boolean = false,
    onDismiss: () -> Unit,
    blocks: List<ArticleBlock>,
    settings: Settings,
    listState: LazyListState,
    onAction: (MainAction) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    PredictiveBackDrawerContainer(visible = visible, onDismiss = onDismiss) { drawerModifier ->
        val headingBlockIndices = remember(blocks) { orderedHeadingBlockIndices(blocks) }
        ReaderTypographyDrawer(
            blocks = blocks,
            settings = settings,
            isPdf = isPdf,
            onAction = onAction,
            modifier = drawerModifier,
            onJumpToSection = { headingIndex ->
                onDismiss()
                headingBlockIndices.getOrNull(headingIndex)?.let { blockIdx ->
                    coroutineScope.launch {
                        listState.animateScrollToItem(HEADER_ITEM_COUNT + blockIdx)
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
            if (entry.contentType == ContentType.VIDEO || !entry.inFeedInbox) {
                HeaderActionButton(
                    label = "✓ Finished",
                    style = RocheButtonStyle.PRIMARY,
                    horizontalPadding = 9.dp,
                    onClick = { onAction(MainAction.MarkFeedEntryFinished(entry.id, false)) },
                    modifier = Modifier.testTag("reader_video_finished_btn"),
                )
            }
            ReaderTypographyToggleButton(
                active = showTypographyDrawer,
                onClick = onToggleTypography,
            )
            CompactStarAndArchiveButtons(
                entry = entry,
                tagPrefix = "reader",
                outlined = false,
                onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                onToggleArchive = {
                    flushAndToggleReaderArchive(entry, listState, isAwaitingContent, onAction)
                },
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
    blocks: List<ArticleBlock>,
    settings: Settings,
    onAction: (MainAction) -> Unit,
    onJumpToSection: (headingIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    isPdf: Boolean = false,
) {
    val colors = LocalRocheColors.current
    val scale = settings.readerFontScale
    val headings = remember(blocks) { extractPlainTextHeadings(blocks) }

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
internal fun CompactStarAndArchiveButtons(
    entry: InboxEntry,
    tagPrefix: String,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
    outlined: Boolean = true,
) {
    val colors = LocalRocheColors.current
    val starTint = if (entry.isFavorite) colors.accentEcru else colors.textSecondary
    val starDesc = if (entry.isFavorite) "Remove Star" else "Star Entry"
    val archiveDesc = if (entry.isArchived) "Unarchive to Inbox" else "Archive Entry"

    Row(horizontalArrangement = Arrangement.spacedBy(if (outlined) 6.dp else 0.dp)) {
        RocheIconActionButton(
            contentDescription = starDesc,
            outlined = outlined,
            onClick = onToggleStar,
            modifier = Modifier.testTag("${tagPrefix}_star_btn"),
        ) {
            StarIcon(tint = starTint, isStarred = entry.isFavorite, size = 16.dp)
        }
        RocheIconActionButton(
            contentDescription = archiveDesc,
            outlined = outlined,
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
    pdfSession: PdfRendererSession? = null,
    isHydrating: Boolean,
    listState: LazyListState,
    onSelectFeed: (String) -> Unit = {},
    onSelectTag: (String) -> Unit,
    onToggleArchive: () -> Unit = {},
    onShare: () -> Unit = {},
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val linkStyles = rememberReaderLinkStyles()
    val isLocalPdf = isLocalPdfUrl(entry.url)
    val plainParagraphs =
        remember(entry.contentText, entry.summary) {
            (entry.contentText?.takeIf { it.isNotBlank() } ?: entry.summary.orEmpty())
                .split("\n\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontalPadding =
            if (maxWidth > MAX_READER_PROSE_WIDTH) {
                ((maxWidth - MAX_READER_PROSE_WIDTH) / 2f).coerceAtLeast(24.dp)
            } else if (maxWidth >= 500.dp) {
                28.dp
            } else {
                18.dp
            }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("reader_article_list"),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "reader_header") {
                ReaderHeaderItem(
                    entry = entry,
                    isLocalPdf = isLocalPdf,
                    dividerColor = colors.border,
                    onSelectFeed = onSelectFeed,
                    onSelectTag = onSelectTag,
                    onOpenExternalUrl = onOpenExternalUrl,
                )
            }
            readerArticleBodyContentItems(
                entry = entry,
                parsedState = parsedState,
                pdfSession = pdfSession,
                isHydrating = isHydrating,
                plainParagraphs = plainParagraphs,
                mutedColor = colors.textMuted,
                linkStyles = linkStyles,
                onOpenExternalUrl = onOpenExternalUrl,
            )
            item(key = "reader_footer") {
                ReaderEndCard(
                    entry = entry,
                    isLocalPdf = isLocalPdf,
                    onToggleArchive = onToggleArchive,
                    onShare = onShare,
                    onOpenExternalUrl = onOpenExternalUrl,
                )
            }
        }
    }
}

@Suppress("LongParameterList")
private fun LazyListScope.readerArticleBodyContentItems(
    entry: InboxEntry,
    parsedState: ParsedArticleBlocksState,
    pdfSession: PdfRendererSession?,
    isHydrating: Boolean,
    plainParagraphs: List<String>,
    mutedColor: androidx.compose.ui.graphics.Color,
    linkStyles: androidx.compose.ui.text.TextLinkStyles,
    onOpenExternalUrl: (String) -> Unit,
) {
    val hasPdfPages = pdfSession != null && pdfSession.pageCount > 0
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
    } else if (plainParagraphs.isNotEmpty()) {
        itemsIndexed(items = plainParagraphs, key = { idx, _ -> "plain_$idx" }) { _, paragraph ->
            SelectionContainer { ReaderPlainParagraphItem(paragraph = paragraph) }
        }
    } else if (!hasPdfPages && (parsedState.isParsing || isHydrating)) {
        item(key = "reader_loading") {
            Text(text = "Loading full article…", color = mutedColor, fontSize = 14.sp)
        }
    } else if (!hasPdfPages) {
        item(key = "reader_empty") {
            Text(
                text = "Full article text is not cached locally for this item.",
                color = mutedColor,
                fontSize = 14.sp,
            )
        }
    }
    if (pdfSession != null && pdfSession.pageCount > 0) {
        items(count = pdfSession.pageCount, key = { idx -> "pdf_page_$idx" }) { idx ->
            PdfPageCardItem(session = pdfSession, pageIndex = idx)
        }
    }
}

@Composable
private fun ReaderHeaderItem(
    entry: InboxEntry,
    isLocalPdf: Boolean,
    dividerColor: Color,
    onSelectFeed: (String) -> Unit,
    onSelectTag: (String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    Column {
        ReaderArticleHeader(
            entry = entry,
            isLocalPdf = isLocalPdf,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderEndCard(
    entry: InboxEntry,
    isLocalPdf: Boolean,
    onToggleArchive: () -> Unit,
    onShare: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val headingText = if (entry.isArchived) "Archived" else "Finished reading?"
    val archiveLabel = if (entry.isArchived) "Unarchive" else "Archive & Done"
    val archiveStyle =
        if (entry.isArchived) RocheButtonStyle.SECONDARY else RocheButtonStyle.PRIMARY
    val externalUrl = entry.url.takeIf { !isLocalPdf && it.isNotBlank() }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp)) {
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))
        RocheSurfaceCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = headingText,
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HeaderActionButton(
                        label = archiveLabel,
                        style = archiveStyle,
                        onClick = onToggleArchive,
                        modifier = Modifier.testTag("reader_end_archive_btn"),
                    )
                    HeaderActionButton(label = "Share", onClick = onShare)
                    if (externalUrl != null) {
                        HeaderActionButton(
                            label = "Original ↗",
                            onClick = { onOpenExternalUrl(externalUrl) },
                            modifier = Modifier.testTag("reader_end_original_btn"),
                        )
                    }
                }
            }
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
    onSelectFeed: (String) -> Unit = {},
    onOpenExternalUrl: (String) -> Unit = {},
) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val headlineFamily = currentHeadlineFontFamily()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SelectionContainer {
            Text(
                text = entry.title,
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
            ReaderBylineFeedChip(entry = entry, onSelectFeed = onSelectFeed)
            Text(
                text = formatReaderByline(entry),
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
        }
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
    val parsedState = rememberParsedArticleBlocks(htmlBody = htmlBody, baseUrl = entry.url)
    SelectionContainer {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (parsedState.blocks.isNotEmpty()) {
                StructuredHtmlArticleContent(
                    blocks = parsedState.blocks,
                    baseUrl = entry.url,
                    onOpenUrl = onOpenExternalUrl,
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

private fun formatReaderByline(entry: InboxEntry): String = buildList {
    entry.author?.takeIf { it.isNotBlank() }?.let { add(it) }
    add(entry.formattedPublishedLabel)
    add(entry.readEstimateLabel)
    if (entry.isCachedOffline) {
        add("Offline")
    }
}
    .joinToString(" · ", prefix = "· ")

private val MAX_READER_PROSE_WIDTH = 520.dp
private const val HEADER_ITEM_COUNT = 1
private const val MAX_CACHED_PARSED_DOCS = 32
private const val MAX_IN_FLIGHT_SCROLL_FRACTION = 0.999
private const val MIN_PROGRESS = 0.08f
private const val READ_PROGRESS_MIN_DELTA = 0.05
private const val ARCHIVE_COMPLETION_THRESHOLD = 0.90
private const val PERCENT_MULTIPLIER = 100
private const val READER_SCALE_STEP = 0.10f
private const val READER_SCALE_EPSILON = 0.005f
private const val READER_RESTORE_MIN_PROGRESS = 0.02
private const val READER_RESTORE_MAX_PROGRESS = 0.95
