@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.data.boards.BoardItemView
import com.grayvines.roche.data.boards.BoardReaderContent
import com.grayvines.roche.data.boards.BoardSummary
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.reader.ArticleBlock
import com.grayvines.roche.reader.stripInlineHtmlToPlainText
import com.grayvines.roche.ui.components.CommentBubbleIcon
import com.grayvines.roche.ui.components.DismissIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheMetaPill
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class BoardReaderBundle(
    val boardId: String,
    val externalId: String,
    val itemKey: String,
    val content: BoardReaderContent?,
    val htmlBody: String?,
    val parsedState: ParsedArticleBlocksState,
    val plainParagraphs: List<String>,
    val isLoading: Boolean,
    val hasReadableBody: Boolean,
    val errorMessage: String?,
)

private data class BoardReaderCallbacks(
    val onJumpToAnchor: (String) -> Unit,
    val onZoomImage: (ImageBitmap, ArticleBlock.ImageBlock) -> Unit,
    val onAutoFinish: () -> Unit,
    val onMarkFinished: () -> Unit,
    val onUnfinish: () -> Unit,
    val onToggleQueue: () -> Unit,
    val onToggleStar: () -> Unit,
    val onDismiss: () -> Unit,
    val onRetry: () -> Unit,
    val onShare: () -> Unit,
    val onOpenExternalUrl: (String) -> Unit,
)

@Composable
private fun rememberBoardReaderBundle(
    board: BoardSummary?,
    item: BoardItemView?,
    readerState: BoardReaderUiState?,
): BoardReaderBundle {
    val boardId = readerState?.boardId ?: item?.boardId ?: board?.id.orEmpty()
    val externalId = readerState?.externalId ?: item?.externalId.orEmpty()
    val content = readerState?.content
    val isLoading = readerState?.isLoading == true
    val htmlBody =
        content?.contentHtml?.takeIf { it.isNotBlank() }
            ?: item?.selfTextHtml?.takeIf { it.isNotBlank() }
    val parsedState =
        rememberParsedArticleBlocks(
            htmlBody = htmlBody,
            baseUrl = item?.url,
            articleTitle = item?.title,
        )
    val plainParagraphs =
        remember(content?.contentText) {
            content
                ?.contentText
                ?.takeIf { it.isNotBlank() }
                ?.split("\n\n")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }
    val effectiveFetchError =
        readerState?.errorMessage?.takeIf { it.isNotBlank() }
            ?: content?.fetchError?.takeIf { it.isNotBlank() }
    val hasReadable =
        hasReadableBoardReaderBody(
            isLoading = isLoading,
            isParsing = parsedState.isParsing,
            extractionFailed = content?.extractionFailed == true,
            blocksCount = parsedState.blocks.size,
            plainParagraphsCount = plainParagraphs.size,
            errorMessage = effectiveFetchError,
        )
    return BoardReaderBundle(
        boardId = boardId,
        externalId = externalId,
        itemKey = "$boardId:$externalId",
        content = content,
        htmlBody = htmlBody,
        parsedState = parsedState,
        plainParagraphs = plainParagraphs,
        isLoading = isLoading,
        hasReadableBody = hasReadable,
        errorMessage = effectiveFetchError,
    )
}

@Composable
fun BoardReaderScreen(
    board: BoardSummary?,
    item: BoardItemView?,
    readerState: BoardReaderUiState?,
    settings: Settings,
    onAction: (MainAction) -> Unit,
    onShareLink: (title: String, url: String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val bundle = rememberBoardReaderBundle(board, item, readerState)
    var showTypographyDrawer by rememberSaveable(bundle.itemKey) { mutableStateOf(false) }
    var activeLightboxTarget by
        remember(bundle.itemKey) { mutableStateOf<ReaderLightboxTarget?>(null) }
    var suppressAutoFinish by remember(bundle.itemKey) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val listState = key(bundle.itemKey) { rememberLazyListState() }
    val callbacks =
        buildBoardReaderCallbacks(
            bundle = bundle,
            item = item,
            listState = listState,
            coroutineScope = coroutineScope,
            onSuppressAutoFinish = { suppressAutoFinish = true },
            onOpenLightbox = { activeLightboxTarget = it },
            onAction = onAction,
            onShareLink = onShareLink,
            onOpenExternalUrl = onOpenExternalUrl,
        )

    BoardReaderAutoFinishEffect(
        itemKey = bundle.itemKey,
        canAutoFinish = canAutoFinishBoardReader(item, bundle.hasReadableBody, suppressAutoFinish),
        listState = listState,
        onAutoFinish = callbacks.onAutoFinish,
    )

    BoardReaderScaffold(
        board = board,
        item = item,
        bundle = bundle,
        settings = settings,
        showTypographyDrawer = showTypographyDrawer,
        listState = listState,
        callbacks = callbacks,
        onToggleTypography = { showTypographyDrawer = !showTypographyDrawer },
        onDismissTypography = { showTypographyDrawer = false },
        onAction = onAction,
    )

    activeLightboxTarget?.let { target ->
        ReaderMediaLightboxDialog(target = target, onDismiss = { activeLightboxTarget = null })
    }
}

@Suppress("LongParameterList")
private fun buildBoardReaderCallbacks(
    bundle: BoardReaderBundle,
    item: BoardItemView?,
    listState: LazyListState,
    coroutineScope: CoroutineScope,
    onSuppressAutoFinish: () -> Unit,
    onOpenLightbox: (ReaderLightboxTarget) -> Unit,
    onAction: (MainAction) -> Unit,
    onShareLink: (String, String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
): BoardReaderCallbacks =
    BoardReaderCallbacks(
        onJumpToAnchor = { anchorId ->
            jumpToBoardReaderAnchor(anchorId, bundle.parsedState, listState, coroutineScope)
        },
        onZoomImage = { bmp, blk -> onOpenLightbox(buildBoardInlineImageTarget(bmp, blk)) },
        onAutoFinish = {
            dispatchBoardReaderPromote(bundle, item, BoardPromoteKind.AUTO_FINISH, onAction)
        },
        onMarkFinished = {
            dispatchBoardReaderPromote(bundle, item, BoardPromoteKind.FINISH, onAction)
        },
        onUnfinish = {
            val linkedId = item?.linkedEntryId ?: bundle.content?.linkedEntryId
            if (linkedId != null) {
                onSuppressAutoFinish()
                onAction(MainAction.UnfinishBoardItem(bundle.boardId, bundle.externalId, linkedId))
            }
        },
        onToggleQueue = {
            dispatchBoardReaderPromote(bundle, item, BoardPromoteKind.QUEUE, onAction)
        },
        onToggleStar = {
            dispatchBoardReaderPromote(bundle, item, BoardPromoteKind.STAR, onAction)
        },
        onDismiss = {
            dispatchBoardReaderPromote(bundle, item, BoardPromoteKind.DISMISS, onAction)
        },
        onRetry = {
            onAction(MainAction.RetryLoadBoardReader(bundle.boardId, bundle.externalId, item))
        },
        onShare = {
            item?.let { target ->
                val shareUrl = target.url.ifBlank { target.discussionUrl.orEmpty() }
                if (shareUrl.isNotBlank()) {
                    onShareLink(target.title, shareUrl)
                }
            }
        },
        onOpenExternalUrl = onOpenExternalUrl,
    )

private enum class BoardPromoteKind {
    AUTO_FINISH,
    FINISH,
    QUEUE,
    STAR,
    DISMISS,
}

private fun dispatchBoardReaderPromote(
    bundle: BoardReaderBundle,
    item: BoardItemView?,
    kind: BoardPromoteKind,
    onAction: (MainAction) -> Unit,
) {
    val target = item ?: return
    val action =
        when (kind) {
            BoardPromoteKind.AUTO_FINISH ->
                MainAction.FinishBoardItem(
                    boardId = bundle.boardId,
                    externalId = bundle.externalId,
                    item = target,
                    extractedHtml = bundle.htmlBody,
                    silent = true,
                )
            BoardPromoteKind.FINISH ->
                MainAction.FinishBoardItem(
                    boardId = bundle.boardId,
                    externalId = bundle.externalId,
                    item = target,
                    extractedHtml = bundle.htmlBody,
                    silent = false,
                )
            BoardPromoteKind.QUEUE ->
                MainAction.ToggleBoardItemQueue(
                    bundle.boardId,
                    bundle.externalId,
                    target,
                    bundle.htmlBody,
                )
            BoardPromoteKind.STAR ->
                MainAction.ToggleBoardItemStar(
                    bundle.boardId,
                    bundle.externalId,
                    target,
                    bundle.htmlBody,
                )
            BoardPromoteKind.DISMISS ->
                if (target.isDismissed) {
                    MainAction.UndismissBoardItem(bundle.boardId, bundle.externalId)
                } else {
                    MainAction.DismissBoardItem(
                        boardId = bundle.boardId,
                        externalId = bundle.externalId,
                        title = target.title,
                        closeReaderIfOpen = true,
                    )
                }
        }
    onAction(action)
}

private fun jumpToBoardReaderAnchor(
    anchorId: String,
    parsedState: ParsedArticleBlocksState,
    listState: LazyListState,
    coroutineScope: CoroutineScope,
) {
    val blockIdx =
        parsedState.anchorBlockIndices[anchorId]
            ?: parsedState.anchorBlockIndices[anchorId.removePrefix("prose-")]
    if (blockIdx != null) {
        coroutineScope.launch {
            listState.animateScrollToItem(BOARD_READER_HEADER_ITEMS + blockIdx)
        }
    }
}

private fun buildBoardInlineImageTarget(
    bitmap: ImageBitmap,
    block: ArticleBlock.ImageBlock,
): ReaderLightboxTarget.InlineImage =
    ReaderLightboxTarget.InlineImage(
        bitmap = bitmap,
        alt = block.alt,
        captionText =
            block.captionPlainText ?: block.captionHtml?.let(::stripInlineHtmlToPlainText),
    )

@Composable
private fun BoardReaderScaffold(
    board: BoardSummary?,
    item: BoardItemView?,
    bundle: BoardReaderBundle,
    settings: Settings,
    showTypographyDrawer: Boolean,
    listState: LazyListState,
    callbacks: BoardReaderCallbacks,
    onToggleTypography: () -> Unit,
    onDismissTypography: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier =
            Modifier.fillMaxSize().background(colors.background).testTag("board_reader_screen")
    ) {
        BoardReaderTopToolbar(
            backLabel = "← ${board?.title ?: "Boards"}",
            item = item,
            showTypographyDrawer = showTypographyDrawer,
            onBack = { onAction(MainAction.CloseBoardReader) },
            onToggleTypography = onToggleTypography,
            onToggleQueue = callbacks.onToggleQueue,
            onToggleStar = callbacks.onToggleStar,
            onDismiss = callbacks.onDismiss,
        )
        ReaderScrollProgressBar(
            listState = listState,
            fallbackProgress = if (item?.isFinished == true) 1.0 else 0.0,
        )
        ReaderTypographySection(
            visible = showTypographyDrawer,
            isPdf = false,
            onDismiss = onDismissTypography,
            parsedState = bundle.parsedState,
            sectionBlockOffset = BOARD_READER_HEADER_ITEMS,
            settings = settings,
            listState = listState,
            onAction = onAction,
        )
        BoardReaderBodyList(
            item = item,
            bundle = bundle,
            listState = listState,
            callbacks = callbacks,
            modifier = Modifier.weight(1f),
        )
    }
}

@Suppress("LongParameterList")
internal fun hasReadableBoardReaderBody(
    isLoading: Boolean,
    isParsing: Boolean,
    extractionFailed: Boolean,
    blocksCount: Int,
    plainParagraphsCount: Int,
    errorMessage: String?,
): Boolean =
    !isLoading &&
        !isParsing &&
        !extractionFailed &&
        errorMessage.isNullOrBlank() &&
        (blocksCount > 0 || plainParagraphsCount > 0)

internal fun canAutoFinishBoardReader(
    item: BoardItemView?,
    hasReadableBody: Boolean,
    suppressAutoFinish: Boolean,
): Boolean = hasReadableBody && item != null && !item.isFinished && !suppressAutoFinish

@Composable
private fun BoardReaderAutoFinishEffect(
    itemKey: String,
    canAutoFinish: Boolean,
    listState: LazyListState,
    onAutoFinish: () -> Unit,
) {
    var hasUserScrolled by remember(itemKey) { mutableStateOf(false) }
    var autoFinishFired by remember(itemKey) { mutableStateOf(false) }
    val canScroll by
        remember(listState) {
            derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
        }
    val totalItems by
        remember(listState) { derivedStateOf { listState.layoutInfo.totalItemsCount } }

    BoardShortPostDwellEffect(
        itemKey = itemKey,
        eligible = canAutoFinish && !autoFinishFired && !canScroll && totalItems > 0,
        listState = listState,
        onDwellComplete = {
            autoFinishFired = true
            onAutoFinish()
        },
    )

    LaunchedEffect(itemKey, listState.isScrollInProgress, canScroll, canAutoFinish) {
        if (listState.isScrollInProgress) {
            hasUserScrolled = true
        } else {
            val scrollEligible = hasUserScrolled && canScroll
            if (scrollEligible && canAutoFinish && !autoFinishFired) {
                val fraction = computeLazyListScrollFraction(listState)
                if (fraction != null && fraction >= BOARD_AUTO_FINISH_SCROLL_THRESHOLD) {
                    autoFinishFired = true
                    onAutoFinish()
                }
            }
        }
    }
}

@Composable
private fun BoardShortPostDwellEffect(
    itemKey: String,
    eligible: Boolean,
    listState: LazyListState,
    onDwellComplete: () -> Unit,
) {
    LaunchedEffect(itemKey, eligible) {
        if (eligible) {
            withFrameNanos {}
            delay(BOARD_SHORT_POST_DWELL_MS)
            val stillFits =
                listState.layoutInfo.totalItemsCount > 0 &&
                    !listState.canScrollForward &&
                    !listState.canScrollBackward
            if (stillFits) {
                onDwellComplete()
            }
        }
    }
}

@Composable
private fun BoardReaderTopToolbar(
    backLabel: String,
    item: BoardItemView?,
    showTypographyDrawer: Boolean,
    onBack: () -> Unit,
    onToggleTypography: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleStar: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier =
                Modifier.weight(1f, fill = false)
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack)
                    .semantics { role = Role.Button }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("board_reader_back_btn"),
        ) {
            Text(
                text = backLabel,
                color = colors.textSecondary,
                fontSize = 13.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BoardReaderToolbarActions(
            item = item,
            showTypographyDrawer = showTypographyDrawer,
            onToggleTypography = onToggleTypography,
            onToggleQueue = onToggleQueue,
            onToggleStar = onToggleStar,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun BoardReaderToolbarActions(
    item: BoardItemView?,
    showTypographyDrawer: Boolean,
    onToggleTypography: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleStar: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BoardReaderTypographyToggle(
            active = showTypographyDrawer,
            onClick = onToggleTypography,
        )
        if (item != null) {
            BoardReaderItemToolbarActions(
                item = item,
                onToggleQueue = onToggleQueue,
                onToggleStar = onToggleStar,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun BoardReaderItemToolbarActions(
    item: BoardItemView,
    onToggleQueue: () -> Unit,
    onToggleStar: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRocheColors.current
    BoardReaderTopQueuePill(isInQueue = item.isInQueue, onClick = onToggleQueue)
    RocheIconActionButton(
        contentDescription = if (item.isStarred) "Remove star" else "Star story",
        outlined = false,
        onClick = onToggleStar,
        modifier = Modifier.testTag("board_reader_star_btn"),
    ) {
        StarIcon(
            tint = if (item.isStarred) colors.accentEcru else colors.textSecondary,
            isStarred = item.isStarred,
            size = 16.dp,
        )
    }
    RocheIconActionButton(
        contentDescription = if (item.isDismissed) "Restore story" else "Dismiss story",
        outlined = false,
        onClick = onDismiss,
        modifier = Modifier.testTag("board_reader_dismiss_btn"),
    ) {
        DismissIcon(
            tint = if (item.isDismissed) colors.accentBlue else colors.textSecondary,
            isDismissed = item.isDismissed,
            size = 16.dp,
        )
    }
}

@Composable
private fun BoardReaderTopQueuePill(
    isInQueue: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(7.dp)
    val bg = if (isInQueue) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent
    val border = if (isInQueue) colors.accentBlue.copy(alpha = 0.5f) else colors.border
    val fg = if (isInQueue) colors.accentBlue else colors.textSecondary
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = if (isInQueue) "Remove from Queue" else "Add to Queue"
                }
                .testTag("board_reader_top_queue_btn"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 28.dp)
                    .clip(shape)
                    .background(bg)
                    .border(1.dp, border, shape)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clearAndSetSemantics {},
        ) {
            Text(
                text = if (isInQueue) "✓ Queue" else "+ Queue",
                color = fg,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BoardReaderTypographyToggle(
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val bg = if (active) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent
    val fg = if (active) colors.accentBlue else colors.textSecondary
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
                .testTag("board_reader_typography_btn"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 34.dp).widthIn(min = 34.dp).clip(shape).background(bg),
        ) {
            Text(
                text = "Aa",
                color = fg,
                fontFamily = FontFamily.Serif,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun BoardReaderBodyList(
    item: BoardItemView?,
    bundle: BoardReaderBundle,
    listState: LazyListState,
    callbacks: BoardReaderCallbacks,
    modifier: Modifier = Modifier,
) {
    val linkStyles = rememberReaderLinkStyles()
    CompositionLocalProvider(LocalReaderAnchorNavigator provides callbacks.onJumpToAnchor) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val horizontalPadding =
                resolveReaderHorizontalPadding(maxWidth = maxWidth, isPdf = false)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("board_reader_list"),
                contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (item != null) {
                    item(key = "board_reader_header_${bundle.itemKey}") {
                        BoardReaderArticleHeader(
                            item = item,
                            content = bundle.content,
                            onOpenExternalUrl = callbacks.onOpenExternalUrl,
                        )
                    }
                }
                boardReaderMainBodyItems(
                    item = item,
                    bundle = bundle,
                    linkStyles = linkStyles,
                    callbacks = callbacks,
                )
            }
        }
    }
}

private fun LazyListScope.boardReaderMainBodyItems(
    item: BoardItemView?,
    bundle: BoardReaderBundle,
    linkStyles: androidx.compose.ui.text.TextLinkStyles,
    callbacks: BoardReaderCallbacks,
) {
    when {
        bundle.isLoading || bundle.parsedState.isParsing -> {
            item(key = "board_reader_loading") { BoardReaderLoadingCard() }
        }
        bundle.hasReadableBody && bundle.parsedState.blocks.isNotEmpty() -> {
            boardReaderHtmlItems(item, bundle.parsedState.blocks, linkStyles, callbacks)
        }
        bundle.hasReadableBody && bundle.plainParagraphs.isNotEmpty() -> {
            boardReaderPlainItems(item, bundle.plainParagraphs, callbacks)
        }
        else -> {
            item(key = "board_reader_fallback") {
                BoardReaderFallbackCard(
                    item = item,
                    errorMessage = bundle.errorMessage,
                    callbacks = callbacks,
                )
            }
        }
    }
}

private fun LazyListScope.boardReaderHtmlItems(
    item: BoardItemView?,
    blocks: List<ArticleBlock>,
    linkStyles: androidx.compose.ui.text.TextLinkStyles,
    callbacks: BoardReaderCallbacks,
) {
    itemsIndexed(items = blocks, key = { idx, _ -> "board_block_$idx" }) { _, block ->
        SelectionContainer {
            RenderArticleBlock(
                block = block,
                linkStyles = linkStyles,
                baseUrl = item?.url,
                onOpenUrl = callbacks.onOpenExternalUrl,
                onZoomImage = callbacks.onZoomImage,
            )
        }
    }
    if (item != null) {
        item(key = "board_reader_end_card") {
            BoardReaderEndCard(item = item, callbacks = callbacks)
        }
    }
}

private fun LazyListScope.boardReaderPlainItems(
    item: BoardItemView?,
    paragraphs: List<String>,
    callbacks: BoardReaderCallbacks,
) {
    itemsIndexed(items = paragraphs, key = { idx, _ -> "board_plain_$idx" }) { _, paragraph ->
        SelectionContainer { BoardReaderPlainParagraph(paragraph) }
    }
    if (item != null) {
        item(key = "board_reader_end_card") {
            BoardReaderEndCard(item = item, callbacks = callbacks)
        }
    }
}

@Composable
private fun BoardReaderArticleHeader(
    item: BoardItemView,
    content: BoardReaderContent?,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val metadataText = remember(item) { formatBoardStoryMetadata(item) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SelectionContainer {
            Text(
                text = item.title,
                color = colors.textPrimary,
                fontFamily = currentHeadlineFontFamily(),
                fontSize = (22.5f * scale).sp,
                lineHeight = (29f * scale).sp,
                fontWeight = FontWeight.Medium,
            )
        }
        BoardReaderHeaderBadges(item = item)
        if (metadataText.isNotBlank()) {
            Text(
                text = metadataText,
                color = colors.textMuted,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
            )
        }
        BoardReaderExternalLinksRow(
            item = item,
            content = content,
            onOpenExternalUrl = onOpenExternalUrl,
        )
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = colors.border, thickness = 1.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardReaderHeaderBadges(item: BoardItemView) {
    val colors = LocalRocheColors.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        RocheMetaPill(
            text = "#${item.rank}",
            textColor = colors.textSecondary,
            backgroundColor = colors.surfaceElevated,
            borderColor = colors.border,
        )
        if (item.isFinished) {
            RocheMetaPill(
                text = "✓ Finished",
                textColor = colors.accentVerdigris,
                modifier = Modifier.testTag("board_reader_finished_badge"),
            )
        }
        if (item.isInQueue) {
            RocheMetaPill(
                text = "In Queue",
                textColor = colors.accentBlue,
                modifier = Modifier.testTag("board_reader_queue_badge"),
            )
        }
        if (item.isStarred) {
            RocheMetaPill(
                text = "★ Starred",
                textColor = colors.accentEcru,
                modifier = Modifier.testTag("board_reader_star_badge"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardReaderExternalLinksRow(
    item: BoardItemView,
    content: BoardReaderContent?,
    onOpenExternalUrl: (String) -> Unit,
) {
    val discussionUrl = item.discussionUrl?.takeIf { it.isNotBlank() }
    val canonDiscussion = remember(discussionUrl) { discussionUrl?.let(::canonicalizeSyncUrl) }
    val extraDiscussions =
        remember(content?.discussions, canonDiscussion) {
            content?.discussions.orEmpty().filter {
                canonDiscussion == null || canonicalizeSyncUrl(it.url) != canonDiscussion
            }
        }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (!item.isSelfPost && item.url.isNotBlank()) {
            HeaderActionButton(
                label = "Original ↗",
                onClick = { onOpenExternalUrl(item.url) },
                modifier = Modifier.testTag("board_reader_original_btn"),
            )
        }
        if (discussionUrl != null) {
            BoardDiscussionLinkButton(
                label = formatBoardCommentsLabel(item.commentCount),
                onClick = { onOpenExternalUrl(discussionUrl) },
                modifier = Modifier.testTag("board_reader_comments_btn"),
            )
        }
        extraDiscussions.forEachIndexed { idx, link ->
            BoardDiscussionLinkButton(
                label = "${link.displayLabel} ↗",
                onClick = { onOpenExternalUrl(link.url) },
                modifier = Modifier.testTag("board_reader_extra_discussion_$idx"),
            )
        }
    }
}

@Composable
private fun BoardDiscussionLinkButton(
    label: String,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier.heightIn(min = 36.dp)
                    .clip(shape)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.controlOutline, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            CommentBubbleIcon(
                tint = colors.accentEcru,
                size = 14.dp,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Text(
                text = label,
                color = colors.textPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BoardReaderLoadingCard() {
    val colors = LocalRocheColors.current
    RocheSurfaceCard(modifier = Modifier.testTag("board_reader_loading_card")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = colors.accentBlue,
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "Loading article…",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "You can open Original or Comments anytime above.",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun BoardReaderPlainParagraph(paragraph: String) {
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

internal enum class BoardFallbackKind {
    FETCH_ERROR,
    SELF_POST,
    UNEXTRACTABLE,
}

internal data class BoardFallbackModel(
    val kind: BoardFallbackKind,
    val heading: String,
    val explanation: String,
    val showRetryPrimary: Boolean,
    val showOriginalLink: Boolean,
    val showDiscussionPrimary: Boolean,
)

internal fun resolveBoardFallbackModel(
    item: BoardItemView?,
    fetchError: String?,
): BoardFallbackModel {
    val cleanError = fetchError?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        cleanError != null ->
            BoardFallbackModel(
                kind = BoardFallbackKind.FETCH_ERROR,
                heading = "Couldn't load article",
                explanation = cleanError,
                showRetryPrimary = true,
                showOriginalLink = item != null && !item.isSelfPost && item.url.isNotBlank(),
                showDiscussionPrimary = false,
            )
        item?.isSelfPost == true ->
            BoardFallbackModel(
                kind = BoardFallbackKind.SELF_POST,
                heading = "Discussion post",
                explanation = "No body text — jump into the discussion on Hacker News.",
                showRetryPrimary = false,
                showOriginalLink = false,
                showDiscussionPrimary = true,
            )
        else ->
            BoardFallbackModel(
                kind = BoardFallbackKind.UNEXTRACTABLE,
                heading = "Readable text unavailable",
                explanation =
                    "This page couldn't be rendered in reader view — it may be an interactive app, repository, PDF, or behind a paywall.",
                showRetryPrimary = false,
                showOriginalLink = item != null && item.url.isNotBlank(),
                showDiscussionPrimary = false,
            )
    }
}

@Composable
private fun BoardReaderFallbackCard(
    item: BoardItemView?,
    errorMessage: String?,
    callbacks: BoardReaderCallbacks,
) {
    val colors = LocalRocheColors.current
    val fallback = remember(item, errorMessage) { resolveBoardFallbackModel(item, errorMessage) }

    RocheSurfaceCard(modifier = Modifier.testTag("board_reader_fallback_card")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = fallback.heading,
                color = colors.textPrimary,
                fontFamily = currentHeadlineFontFamily(),
                fontSize = 17.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = fallback.explanation,
                color = colors.textSecondary,
                fontSize = 13.5.sp,
                lineHeight = 19.5.sp,
            )
            BoardFallbackPrimaryActions(
                item = item,
                fallback = fallback,
                onRetry = callbacks.onRetry,
                onOpenExternalUrl = callbacks.onOpenExternalUrl,
            )
            if (item != null) {
                HorizontalDivider(color = colors.border, thickness = 1.dp)
                BoardReaderPromotionButtons(item = item, callbacks = callbacks)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardFallbackPrimaryActions(
    item: BoardItemView?,
    fallback: BoardFallbackModel,
    onRetry: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val discussionUrl = item?.discussionUrl?.takeIf { it.isNotBlank() }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (fallback.showRetryPrimary) {
            HeaderActionButton(
                label = "Retry",
                style = RocheButtonStyle.PRIMARY,
                onClick = onRetry,
                modifier = Modifier.testTag("board_reader_retry_btn"),
            )
        }
        if (fallback.showOriginalLink && item != null) {
            BoardFallbackOriginalButton(
                url = item.url,
                isSecondary = fallback.showRetryPrimary,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
        if (discussionUrl != null) {
            BoardFallbackDiscussionButton(
                commentCount = item.commentCount,
                discussionUrl = discussionUrl,
                isPrimary = fallback.showDiscussionPrimary,
                onOpenExternalUrl = onOpenExternalUrl,
            )
        }
    }
}

@Composable
private fun BoardFallbackOriginalButton(
    url: String,
    isSecondary: Boolean,
    onOpenExternalUrl: (String) -> Unit,
) {
    HeaderActionButton(
        label = "Open Original ↗",
        style = if (isSecondary) RocheButtonStyle.SECONDARY else RocheButtonStyle.PRIMARY,
        onClick = { onOpenExternalUrl(url) },
        modifier = Modifier.testTag("board_reader_fallback_original_btn"),
    )
}

@Composable
private fun BoardFallbackDiscussionButton(
    commentCount: Int?,
    discussionUrl: String,
    isPrimary: Boolean,
    onOpenExternalUrl: (String) -> Unit,
) {
    val label =
        if (commentCount != null && commentCount > 0) {
            "Read Discussion ($commentCount comments) ↗"
        } else {
            "Read Discussion ↗"
        }
    HeaderActionButton(
        label = label,
        style = if (isPrimary) RocheButtonStyle.PRIMARY else RocheButtonStyle.SECONDARY,
        onClick = { onOpenExternalUrl(discussionUrl) },
        modifier = Modifier.testTag("board_reader_fallback_comments_btn"),
    )
}

@Composable
private fun BoardReaderEndCard(
    item: BoardItemView,
    callbacks: BoardReaderCallbacks,
) {
    val colors = LocalRocheColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp)) {
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))
        RocheSurfaceCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text =
                        if (item.isFinished) "✓ Finished · Saved to Diary" else "Finished reading?",
                    color = if (item.isFinished) colors.accentVerdigris else colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (item.isFinished) FontWeight.Medium else FontWeight.Normal,
                )
                BoardReaderPromotionButtons(
                    item = item,
                    callbacks = callbacks,
                ) {
                    BoardEndCardTrailingLinks(
                        item = item,
                        onOpenExternalUrl = callbacks.onOpenExternalUrl,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardEndCardTrailingLinks(
    item: BoardItemView,
    onOpenExternalUrl: (String) -> Unit,
) {
    val discussionUrl = item.discussionUrl?.takeIf { it.isNotBlank() }
    if (!item.isSelfPost && item.url.isNotBlank()) {
        HeaderActionButton(
            label = "Original ↗",
            onClick = { onOpenExternalUrl(item.url) },
        )
    }
    if (discussionUrl != null) {
        HeaderActionButton(
            label = formatBoardCommentsLabel(item.commentCount),
            onClick = { onOpenExternalUrl(discussionUrl) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardReaderPromotionButtons(
    item: BoardItemView,
    callbacks: BoardReaderCallbacks,
    content: (@Composable () -> Unit)? = null,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!item.isFinished) {
            HeaderActionButton(
                label = "✓ Mark Finished",
                style = RocheButtonStyle.PRIMARY,
                onClick = callbacks.onMarkFinished,
                modifier = Modifier.testTag("board_reader_finish_btn"),
            )
        } else if (item.linkedEntryId != null) {
            HeaderActionButton(
                label = "Undo Finished",
                onClick = callbacks.onUnfinish,
                modifier = Modifier.testTag("board_reader_unfinish_btn"),
            )
        }
        HeaderActionButton(
            label = if (item.isInQueue) "✓ In Queue" else "+ Queue",
            onClick = callbacks.onToggleQueue,
            modifier = Modifier.testTag("board_reader_queue_btn"),
        )
        HeaderActionButton(
            label = if (item.isStarred) "★ Starred" else "☆ Star",
            onClick = callbacks.onToggleStar,
            modifier = Modifier.testTag("board_reader_end_star_btn"),
        )
        HeaderActionButton(
            label = if (item.isDismissed) "Restore" else "Dismiss",
            onClick = callbacks.onDismiss,
            modifier = Modifier.testTag("board_reader_end_dismiss_btn"),
        )
        HeaderActionButton(
            label = "Share",
            onClick = callbacks.onShare,
            modifier = Modifier.testTag("board_reader_share_btn"),
        )
        content?.invoke()
    }
}

private const val BOARD_READER_HEADER_ITEMS = 1
private const val BOARD_AUTO_FINISH_SCROLL_THRESHOLD = 0.98
private const val BOARD_SHORT_POST_DWELL_MS = 4_000L
