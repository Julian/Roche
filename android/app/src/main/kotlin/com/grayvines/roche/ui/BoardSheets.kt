@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.boards.BoardAdapterType
import com.grayvines.roche.data.boards.BoardItemView
import com.grayvines.roche.data.boards.BoardOrderMode
import com.grayvines.roche.data.boards.BoardSummary
import com.grayvines.roche.data.boards.DEFAULT_BOARD_POLL_INTERVAL_MINUTES
import com.grayvines.roche.data.boards.HackerNewsSection
import com.grayvines.roche.data.catalog.CatalogListSummary
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheAlertDialog
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.SheetActionFooter
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBoardBottomSheet(
    existingBoards: List<BoardSummary>,
    onDismiss: () -> Unit,
    onCreateBoard:
        (
            adapterType: BoardAdapterType,
            sectionOrUrl: String,
            title: String?,
            pollInterval: Int,
        ) -> Unit,
) {
    val colors = LocalRocheColors.current
    var selectedSection by rememberSaveable { mutableStateOf(HackerNewsSection.TOP) }
    var customUrl by rememberSaveable { mutableStateOf("") }
    var customTitle by rememberSaveable { mutableStateOf("") }
    var pollInterval by rememberSaveable { mutableIntStateOf(DEFAULT_BOARD_POLL_INTERVAL_MINUTES) }

    val trimmedUrl = customUrl.trim()
    val parsedUrlSection =
        remember(trimmedUrl) {
            if (trimmedUrl.isEmpty()) null else HackerNewsSection.fromSectionOrUrl(trimmedUrl)
        }
    val hasInvalidUrl = trimmedUrl.isNotEmpty() && parsedUrlSection == null
    val effectiveSection = parsedUrlSection ?: selectedSection
    val alreadyExists =
        remember(existingBoards, effectiveSection) {
            existingBoards.any {
                it.adapterType == BoardAdapterType.HACKER_NEWS &&
                    it.sourceKey.equals(effectiveSection.sourceKey, ignoreCase = true)
            }
        }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        AddBoardSheetForm(
            selectedSection = effectiveSection,
            customUrl = customUrl,
            customTitle = customTitle,
            pollInterval = pollInterval,
            hasInvalidUrl = hasInvalidUrl,
            alreadyExists = alreadyExists,
            onSelectSection = { section ->
                selectedSection = section
                customUrl = ""
            },
            onCustomUrlChange = { next ->
                customUrl = next.replace("\n", "").replace("\r", "")
                HackerNewsSection.fromSectionOrUrl(next)?.let { parsed ->
                    selectedSection = parsed
                }
            },
            onCustomTitleChange = { customTitle = it },
            onSelectPollInterval = { pollInterval = it },
            onDismiss = onDismiss,
            onSubmit = {
                val sectionOrUrl = trimmedUrl.ifEmpty { effectiveSection.sourceKey }
                onCreateBoard(
                    BoardAdapterType.HACKER_NEWS,
                    sectionOrUrl,
                    customTitle.trim().takeIf { it.isNotEmpty() },
                    pollInterval,
                )
            },
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun AddBoardSheetForm(
    selectedSection: HackerNewsSection,
    customUrl: String,
    customTitle: String,
    pollInterval: Int,
    hasInvalidUrl: Boolean,
    alreadyExists: Boolean,
    onSelectSection: (HackerNewsSection) -> Unit,
    onCustomUrlChange: (String) -> Unit,
    onCustomTitleChange: (String) -> Unit,
    onSelectPollInterval: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("add_board_sheet"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AddBoardSheetHeading()
        BoardHackerNewsSectionPicker(
            selectedSection = selectedSection,
            onSelectSection = onSelectSection,
            tagPrefix = "add_board_section",
        )
        AddBoardUrlInputField(
            customUrl = customUrl,
            hasInvalidUrl = hasInvalidUrl,
            alreadyExists = alreadyExists,
            onCustomUrlChange = onCustomUrlChange,
        )
        OutlinedTextField(
            value = customTitle,
            onValueChange = onCustomTitleChange,
            label = { Text("Custom Title (optional)", fontSize = 12.5.sp) },
            placeholder = { Text(selectedSection.defaultTitle, fontSize = 12.5.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("add_board_title_input"),
        )
        BoardPollIntervalPicker(
            selectedMinutes = pollInterval,
            onSelectMinutes = onSelectPollInterval,
            tagPrefix = "add_board_interval",
        )
        SheetActionFooter(
            confirmLabel = if (alreadyExists) "Update & Refresh" else "Add Board",
            confirmEnabled = !hasInvalidUrl,
            confirmTestTag = "add_board_submit_btn",
            onCancel = onDismiss,
            onConfirm = onSubmit,
        )
        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Composable
private fun AddBoardSheetHeading() {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Add Board",
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 19.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Snapshot front pages that stay separate from your Feed Inbox.",
            color = colors.textSecondary,
            fontSize = 13.sp,
        )
    }
    BoardSectionHeaderLabel("SOURCE")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChipPill(
            label = BoardAdapterType.HACKER_NEWS.displayLabel,
            selected = true,
            role = Role.RadioButton,
            onClick = {},
            modifier = Modifier.testTag("add_board_adapter_hn"),
        )
    }
}

@Composable
private fun AddBoardUrlInputField(
    customUrl: String,
    hasInvalidUrl: Boolean,
    alreadyExists: Boolean,
    onCustomUrlChange: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val helperPair =
        when {
            hasInvalidUrl ->
                "Use a news.ycombinator.com URL (/, /best, /ask, /show, /newest)" to
                    colors.accentIndianRed
            alreadyExists ->
                "Already added — saving will reactivate and refresh this board." to colors.textMuted
            else -> null
        }
    OutlinedTextField(
        value = customUrl,
        onValueChange = onCustomUrlChange,
        label = { Text("Or paste Hacker News URL", fontSize = 12.5.sp) },
        placeholder = { Text("https://news.ycombinator.com/best", fontSize = 12.5.sp) },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        isError = hasInvalidUrl,
        supportingText =
            helperPair?.let { (msg, tint) ->
                { Text(text = msg, color = tint, fontSize = 11.5.sp) }
            },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("add_board_url_input"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBoardBottomSheet(
    board: BoardSummary,
    isRefreshing: Boolean,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    var confirmDelete by rememberSaveable(board.id) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseEditBoardSheet) },
        containerColor = colors.surface,
    ) {
        EditBoardSheetBody(
            board = board,
            isRefreshing = isRefreshing,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
            onRequestDelete = { confirmDelete = true },
        )
    }

    if (confirmDelete) {
        BoardDeleteConfirmDialog(
            boardTitle = board.title,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onAction(MainAction.DeleteBoard(board.id))
            },
        )
    }
}

@Composable
private fun EditBoardSheetBody(
    board: BoardSummary,
    isRefreshing: Boolean,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onRequestDelete: () -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("edit_board_sheet"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EditBoardHeaderBlock(board = board)
        BoardHealthCard(board = board, onAction = onAction)
        EditBoardConfigSection(board = board, onAction = onAction)
        EditBoardActionsCard(
            board = board,
            isRefreshing = isRefreshing,
            onRefresh = {
                onAction(MainAction.CloseEditBoardSheet)
                onAction(MainAction.RefreshBoard(board.id))
            },
            onOpenBrowser = {
                onAction(MainAction.CloseEditBoardSheet)
                onOpenExternalUrl(board.sourceUrl)
            },
            onTogglePause = {
                onAction(MainAction.UpdateBoard(boardId = board.id, isActive = board.isPaused))
            },
            onRequestDelete = onRequestDelete,
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun EditBoardConfigSection(
    board: BoardSummary,
    onAction: (MainAction) -> Unit,
) {
    var titleDraft by rememberSaveable(board.id, board.title) { mutableStateOf(board.title) }
    var orderModeDraft by
        rememberSaveable(board.id, board.orderMode) { mutableStateOf(board.orderMode) }
    var pollIntervalDraft by
        rememberSaveable(board.id, board.pollInterval) { mutableIntStateOf(board.pollInterval) }
    val cleanTitle = titleDraft.trim()
    val fieldsChanged =
        cleanTitle != board.title ||
            pollIntervalDraft != board.pollInterval ||
            orderModeDraft != board.orderMode
    val hasChanges = cleanTitle.isNotEmpty() && fieldsChanged

    EditBoardConfigFields(
        titleDraft = titleDraft,
        orderModeDraft = orderModeDraft,
        pollIntervalDraft = pollIntervalDraft,
        hasChanges = hasChanges,
        onTitleChange = { titleDraft = it },
        onOrderModeChange = { orderModeDraft = it },
        onPollIntervalChange = { pollIntervalDraft = it },
        onSaveChanges = {
            onAction(
                MainAction.UpdateBoard(
                    boardId = board.id,
                    title = cleanTitle.takeIf { it != board.title },
                    pollInterval = pollIntervalDraft.takeIf { it != board.pollInterval },
                    orderMode = orderModeDraft.takeIf { it != board.orderMode },
                )
            )
        },
    )
}

@Composable
private fun EditBoardHeaderBlock(board: BoardSummary) {
    val colors = LocalRocheColors.current
    val sectionLabel = board.hnSection?.let(::hnSectionLabel)
    val sourceMeta =
        if (sectionLabel != null) {
            "${board.adapterType.displayLabel} · $sectionLabel"
        } else {
            board.adapterType.displayLabel
        }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = board.title,
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 18.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = sourceMeta,
            color = colors.textSecondary,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = board.sourceUrl,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BoardHealthCard(
    board: BoardSummary,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val freshness =
        remember(board.isPaused, board.snapshotFetchedAt, board.lastPolledAt) {
            formatBoardFreshnessLabel(board)
        }
    val errorText = board.lastError?.trim()?.takeIf { it.isNotEmpty() }
    RocheSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = freshness,
                    color = colors.textSecondary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text =
                        "${board.bufferedItemCount} cached · ${board.dismissedItemCount} dismissed",
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            if (errorText != null) {
                Text(
                    text = errorText,
                    color = colors.accentIndianRed,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                HeaderActionButton(
                    label = "Retry Refresh",
                    onClick = {
                        onAction(MainAction.CloseEditBoardSheet)
                        onAction(MainAction.RefreshBoard(board.id))
                    },
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun EditBoardConfigFields(
    titleDraft: String,
    orderModeDraft: BoardOrderMode,
    pollIntervalDraft: Int,
    hasChanges: Boolean,
    onTitleChange: (String) -> Unit,
    onOrderModeChange: (BoardOrderMode) -> Unit,
    onPollIntervalChange: (Int) -> Unit,
    onSaveChanges: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = titleDraft,
            onValueChange = onTitleChange,
            label = { Text("Board Title", fontSize = 12.5.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("edit_board_title_input"),
        )
        BoardOrderModePicker(
            selectedOrderMode = orderModeDraft,
            onSelectOrderMode = onOrderModeChange,
            tagPrefix = "edit_board_order",
        )
        BoardPollIntervalPicker(
            selectedMinutes = pollIntervalDraft,
            onSelectMinutes = onPollIntervalChange,
            tagPrefix = "edit_board_interval",
        )
        HeaderActionButton(
            label = "Save Changes",
            style = if (hasChanges) RocheButtonStyle.PRIMARY else RocheButtonStyle.SECONDARY,
            enabled = hasChanges,
            onClick = onSaveChanges,
            modifier = Modifier.fillMaxWidth().testTag("edit_board_save_btn"),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardStoryContextBottomSheet(
    item: BoardItemView,
    userLists: List<CatalogListSummary>,
    onAction: (MainAction) -> Unit,
    onShareLink: (title: String, url: String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseBoardStoryContextSheet) },
        containerColor = colors.surface,
    ) {
        BoardStoryContextSheetBody(
            item = item,
            userLists = userLists,
            onAction = onAction,
            onShareLink = onShareLink,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }
}

@Composable
private fun BoardStoryContextSheetBody(
    item: BoardItemView,
    userLists: List<CatalogListSummary>,
    onAction: (MainAction) -> Unit,
    onShareLink: (title: String, url: String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("board_story_context_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoardStoryContextHeader(item = item)
        BoardStoryContextLinksCard(
            item = item,
            onAction = onAction,
            onShareLink = onShareLink,
            onOpenExternalUrl = onOpenExternalUrl,
        )
        BoardStoryContextLibraryCard(item = item, onAction = onAction)
        if (userLists.isNotEmpty()) {
            BoardStoryContextListsBlock(item = item, userLists = userLists, onAction = onAction)
        }
        BoardStoryContextDismissCard(item = item, onAction = onAction)
        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Composable
private fun BoardStoryContextHeader(item: BoardItemView) {
    val colors = LocalRocheColors.current
    val metaLine = remember(item) { formatBoardStoryMetadata(item) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = item.title,
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 17.5.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (metaLine.isNotEmpty()) {
            Text(
                text = "#${item.rank} · $metaLine",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BoardStoryContextLinksCard(
    item: BoardItemView,
    onAction: (MainAction) -> Unit,
    onShareLink: (title: String, url: String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val showOriginal = !item.isSelfPost && item.url.isNotBlank()
    val discussionUrl = item.discussionUrl?.takeIf { it.isNotBlank() }
    val shareUrl = item.url.ifBlank { discussionUrl.orEmpty() }
    RocheSurfaceCard {
        if (showOriginal) {
            BoardSheetActionRow(
                label = "Open Original ↗",
                onClick = {
                    onAction(MainAction.CloseBoardStoryContextSheet)
                    onOpenExternalUrl(item.url)
                },
                modifier = Modifier.testTag("board_ctx_open_original"),
            )
        }
        if (discussionUrl != null) {
            if (showOriginal) {
                HorizontalDivider(color = colors.border, thickness = 1.dp)
            }
            BoardSheetActionRow(
                label = boardDiscussionSheetLabel(item.commentCount),
                onClick = {
                    onAction(MainAction.CloseBoardStoryContextSheet)
                    onOpenExternalUrl(discussionUrl)
                },
                modifier = Modifier.testTag("board_ctx_open_discussion"),
            )
        }
        if (shareUrl.isNotBlank()) {
            if (showOriginal || discussionUrl != null) {
                HorizontalDivider(color = colors.border, thickness = 1.dp)
            }
            BoardSheetActionRow(
                label = "Share link…",
                onClick = {
                    onAction(MainAction.CloseBoardStoryContextSheet)
                    onShareLink(item.title, shareUrl)
                },
                modifier = Modifier.testTag("board_ctx_share"),
            )
        }
    }
}

private fun boardDiscussionSheetLabel(commentCount: Int?): String =
    commentCount?.takeIf { it > 0 }?.let { "Open Discussion ($it comments) ↗" }
        ?: "Open Discussion ↗"

@Composable
private fun BoardStoryContextLibraryCard(
    item: BoardItemView,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheSurfaceCard {
        BoardSheetActionRow(
            label = if (item.isFinished) "Undo Finished" else "✓ Mark Finished",
            textColor = if (item.isFinished) colors.textSecondary else colors.accentVerdigris,
            onClick = { dispatchBoardContextFinish(item, onAction) },
            modifier = Modifier.testTag("board_ctx_finish"),
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        BoardSheetActionRow(
            label = if (item.isInQueue) "Remove from Queue" else "Add to Queue",
            textColor = if (item.isInQueue) colors.textSecondary else colors.accentBlue,
            onClick = {
                onAction(MainAction.ToggleBoardItemQueue(item.boardId, item.externalId, item))
            },
            modifier = Modifier.testTag("board_ctx_queue"),
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        BoardSheetActionRow(
            label = if (item.isStarred) "Remove Star" else "Star Story",
            onClick = {
                onAction(MainAction.ToggleBoardItemStar(item.boardId, item.externalId, item))
            },
            modifier = Modifier.testTag("board_ctx_star"),
        )
    }
}

private fun dispatchBoardContextFinish(item: BoardItemView, onAction: (MainAction) -> Unit) {
    val linkedId = item.linkedEntryId
    val action =
        if (item.isFinished && linkedId != null) {
            MainAction.UnfinishBoardItem(item.boardId, item.externalId, linkedId)
        } else {
            MainAction.FinishBoardItem(item.boardId, item.externalId, item = item, silent = false)
        }
    onAction(action)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardStoryContextListsBlock(
    item: BoardItemView,
    userLists: List<CatalogListSummary>,
    onAction: (MainAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoardSectionHeaderLabel("ADD TO LIST")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            userLists.forEach { list ->
                FilterChipPill(
                    label = "+ ${list.title}",
                    selected = false,
                    onClick = {
                        onAction(
                            MainAction.PromoteBoardItemToList(
                                boardId = item.boardId,
                                externalId = item.externalId,
                                listId = list.collectionId,
                                listTitle = list.title,
                                item = item,
                            )
                        )
                    },
                    modifier = Modifier.testTag("board_ctx_list_${list.collectionId}"),
                )
            }
        }
    }
}

@Composable
private fun BoardStoryContextDismissCard(
    item: BoardItemView,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheSurfaceCard {
        if (item.isDismissed) {
            BoardSheetActionRow(
                label = "Restore Story",
                textColor = colors.accentBlue,
                onClick = {
                    onAction(MainAction.UndismissBoardItem(item.boardId, item.externalId))
                },
                modifier = Modifier.testTag("board_ctx_restore"),
            )
        } else {
            BoardSheetActionRow(
                label = "Dismiss Story",
                textColor = colors.accentIndianRed,
                onClick = {
                    onAction(
                        MainAction.DismissBoardItem(
                            boardId = item.boardId,
                            externalId = item.externalId,
                            title = item.title,
                            closeReaderIfOpen = false,
                        )
                    )
                },
                modifier = Modifier.testTag("board_ctx_dismiss"),
            )
        }
    }
}

@Composable
private fun EditBoardActionsCard(
    board: BoardSummary,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenBrowser: () -> Unit,
    onTogglePause: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RocheSurfaceCard {
            BoardSheetActionRow(
                label = if (isRefreshing) "Refreshing…" else "Refresh Board Now",
                textColor = colors.accentBlue,
                isLoading = isRefreshing,
                onClick = onRefresh,
                modifier = Modifier.testTag("edit_board_refresh_btn"),
            )
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            BoardSheetActionRow(
                label = "Open Board in Browser ↗",
                onClick = onOpenBrowser,
                modifier = Modifier.testTag("edit_board_open_browser_btn"),
            )
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            BoardSheetActionRow(
                label = if (board.isPaused) "Resume Board Polling" else "Pause Board Polling",
                onClick = onTogglePause,
                modifier = Modifier.testTag("edit_board_toggle_pause_btn"),
            )
        }
        RocheSurfaceCard {
            BoardSheetActionRow(
                label = "Delete Board",
                textColor = colors.accentIndianRed,
                onClick = onRequestDelete,
                modifier = Modifier.testTag("edit_board_delete_btn"),
            )
        }
    }
}

@Composable
private fun BoardSheetActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = LocalRocheColors.current.textPrimary,
    isLoading: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = !isLoading, onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = textColor,
            )
        }
        Text(text = label, color = textColor, fontSize = 14.sp)
    }
}

@Composable
private fun BoardDeleteConfirmDialog(
    boardTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalRocheColors.current
    RocheAlertDialog(
        title = "Delete board?",
        onDismissRequest = onDismiss,
        text = {
            Text(
                text =
                    "Remove \"$boardTitle\" and its cached snapshot? Stories you already starred, queued, or finished stay safely in your library.",
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_delete_board_btn"),
            ) {
                Text("Delete", color = colors.accentIndianRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardHackerNewsSectionPicker(
    selectedSection: HackerNewsSection,
    onSelectSection: (HackerNewsSection) -> Unit,
    tagPrefix: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoardSectionHeaderLabel("SECTION")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HN_SECTION_OPTIONS.forEach { (section, label) ->
                FilterChipPill(
                    label = label,
                    selected = selectedSection == section,
                    role = Role.RadioButton,
                    onClick = { onSelectSection(section) },
                    modifier = Modifier.testTag("${tagPrefix}_${section.sourceKey}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardOrderModePicker(
    selectedOrderMode: BoardOrderMode,
    onSelectOrderMode: (BoardOrderMode) -> Unit,
    tagPrefix: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoardSectionHeaderLabel("ORDER")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BOARD_ORDER_MODE_OPTIONS.forEach { (mode, label) ->
                FilterChipPill(
                    label = label,
                    selected = selectedOrderMode == mode,
                    role = Role.RadioButton,
                    onClick = { onSelectOrderMode(mode) },
                    modifier = Modifier.testTag("${tagPrefix}_${mode.wireValue}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardPollIntervalPicker(
    selectedMinutes: Int,
    onSelectMinutes: (Int) -> Unit,
    tagPrefix: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoardSectionHeaderLabel("REFRESH INTERVAL")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BOARD_POLL_INTERVAL_OPTIONS.forEach { (minutes, label) ->
                FilterChipPill(
                    label = label,
                    selected = selectedMinutes == minutes,
                    role = Role.RadioButton,
                    onClick = { onSelectMinutes(minutes) },
                    modifier = Modifier.testTag("${tagPrefix}_$minutes"),
                )
            }
        }
    }
}

@Composable
private fun BoardSectionHeaderLabel(text: String) {
    val colors = LocalRocheColors.current
    Text(
        text = text,
        color = colors.textMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 0.6.sp,
    )
}

private fun hnSectionLabel(section: HackerNewsSection): String =
    HN_SECTION_OPTIONS.firstOrNull { it.first == section }?.second ?: section.defaultTitle

private val HN_SECTION_OPTIONS: List<Pair<HackerNewsSection, String>> =
    listOf(
        HackerNewsSection.TOP to "Front Page",
        HackerNewsSection.BEST to "Best",
        HackerNewsSection.ASK to "Ask HN",
        HackerNewsSection.SHOW to "Show HN",
        HackerNewsSection.NEW to "New",
    )

private val BOARD_ORDER_MODE_OPTIONS: List<Pair<BoardOrderMode, String>> =
    listOf(
        BoardOrderMode.RANKED to "Ranked",
        BoardOrderMode.CHRONOLOGICAL to "Chronological",
    )

private val BOARD_POLL_INTERVAL_OPTIONS: List<Pair<Int, String>> =
    listOf(
        15 to "15 min",
        30 to "30 min",
        60 to "60 min",
        120 to "2 hrs",
    )
