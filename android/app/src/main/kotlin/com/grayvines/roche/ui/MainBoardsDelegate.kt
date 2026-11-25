package com.grayvines.roche.ui

import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.deterministicBoardId
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.boards.BOARD_DEFAULT_PAGE_SIZE
import com.grayvines.roche.data.boards.BoardAdapterType
import com.grayvines.roche.data.boards.BoardItemView
import com.grayvines.roche.data.boards.BoardPageResult
import com.grayvines.roche.data.boards.BoardPromoteAction
import com.grayvines.roche.data.boards.BoardReaderContent
import com.grayvines.roche.data.boards.HackerNewsSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
internal class MainBoardsDelegate(
    private val host: MainDelegateHost,
    private val scope: CoroutineScope,
) {
    private var readerLoadJob: Job? = null

    internal fun loadBoardReader(
        boardId: String,
        externalId: String,
        fallbackItem: BoardItemView?,
        forceRefresh: Boolean = false,
    ) {
        readerLoadJob?.cancel()
        val resolvedFallback =
            fallbackItem ?: resolveCurrentBoardItem(host, boardId, externalId, null)
        host.updateTransient { current ->
            markBoardReaderLoading(current, boardId, externalId, resolvedFallback)
        }
        readerLoadJob = scope.launch {
            val result =
                host.repository.loadBoardItemReader(
                    boardId = boardId,
                    externalId = externalId,
                    fallbackItem = resolvedFallback,
                    forceRefresh = forceRefresh,
                )
            host.updateTransient { current ->
                applyBoardReaderLoadResult(current, boardId, externalId, result)
            }
        }
    }

    internal fun cancelBoardReaderLoad() {
        readerLoadJob?.cancel()
        readerLoadJob = null
    }

    internal suspend fun execute(action: MainAction.BoardMutation) {
        when (action) {
            is MainAction.CreateBoard -> performCreateBoard(action)
            is MainAction.UpdateBoard -> performUpdateBoard(action)
            is MainAction.DeleteBoard -> performDeleteBoard(action.boardId)
            is MainAction.RefreshBoard -> performRefreshBoard(action.boardId)
            MainAction.RefreshAllBoards -> performRefreshAllBoards()
            is MainAction.LoadMoreBoardItems -> performLoadMore(action.boardId)
            else -> executeItemMutation(action)
        }
    }

    private suspend fun executeItemMutation(action: MainAction.BoardMutation) {
        when (action) {
            is MainAction.DismissBoardItem -> performDismissItem(action)
            is MainAction.UndismissBoardItem ->
                performUndismissItem(action.boardId, action.externalId)
            is MainAction.ToggleBoardItemStar -> performToggleStar(action)
            is MainAction.ToggleBoardItemQueue -> performToggleQueue(action)
            is MainAction.FinishBoardItem -> performFinishItem(action)
            is MainAction.UnfinishBoardItem -> performUnfinishItem(action)
            is MainAction.PromoteBoardItemToList -> performPromoteToList(action)
            is MainAction.RetryLoadBoardReader ->
                loadBoardReader(
                    boardId = action.boardId,
                    externalId = action.externalId,
                    fallbackItem = action.item,
                    forceRefresh = true,
                )
            else -> Unit
        }
    }

    private suspend fun performCreateBoard(action: MainAction.CreateBoard) {
        val creationKey = boardCreationKey(action.adapterType, action.sectionOrUrl)
        if (creationKey in host.currentTransientState().creatingBoardKeys) return
        val predictedBoardId = predictBoardId(action.adapterType, action.sectionOrUrl)
        host.updateTransient { current ->
            current.copy(
                isAddBoardSheetOpen = false,
                creatingBoardKeys = current.creatingBoardKeys + creationKey,
                refreshingBoardIds =
                    predictedBoardId?.let { id -> current.refreshingBoardIds + id }
                        ?: current.refreshingBoardIds,
            )
        }
        try {
            host.repository
                .createBoard(
                    adapterType = action.adapterType,
                    sectionOrUrl = action.sectionOrUrl,
                    title = action.title,
                    pollInterval = action.pollInterval,
                )
                .onSuccess { board ->
                    host.updateTransient { current ->
                        current.copy(
                            selectedBoardId = board.id,
                            boardStreamFilter = BoardStreamFilter.ACTIVE,
                            boardFilterQuery = "",
                            boardPaginationByBoardId = current.boardPaginationByBoardId - board.id,
                        )
                    }
                    host.showStatusMessage("Added \"${board.title.take(SNACKBAR_TITLE_LIMIT)}\"")
                }
                .onFailure { err ->
                    host.showStatusMessage(
                        "Could not add board (${err.message ?: "invalid source"})"
                    )
                }
        } finally {
            host.updateTransient { current ->
                current.copy(
                    creatingBoardKeys = current.creatingBoardKeys - creationKey,
                    refreshingBoardIds =
                        predictedBoardId?.let { id -> current.refreshingBoardIds - id }
                            ?: current.refreshingBoardIds,
                )
            }
        }
    }

    private fun predictBoardId(adapterType: BoardAdapterType, sectionOrUrl: String): String? =
        if (adapterType == BoardAdapterType.HACKER_NEWS) {
            HackerNewsSection.fromSectionOrUrl(sectionOrUrl)?.let { section ->
                deterministicBoardId(adapterType.wireValue, section.sourceKey)
            }
        } else {
            null
        }

    private suspend fun performUpdateBoard(action: MainAction.UpdateBoard) {
        host.repository
            .updateBoard(
                boardId = action.boardId,
                title = action.title,
                pollInterval = action.pollInterval,
                isActive = action.isActive,
                orderMode = action.orderMode,
            )
            .onSuccess { updated ->
                host.updateTransient { current ->
                    val nextPag =
                        if (action.orderMode != null) {
                            current.boardPaginationByBoardId - action.boardId
                        } else {
                            current.boardPaginationByBoardId
                        }
                    current.copy(
                        editBoardSheetId =
                            if (current.editBoardSheetId == action.boardId) {
                                null
                            } else {
                                current.editBoardSheetId
                            },
                        boardPaginationByBoardId = nextPag,
                    )
                }
                val message =
                    when {
                        action.isActive == false ->
                            "Paused \"${updated.title.take(SNACKBAR_TITLE_LIMIT)}\""
                        action.isActive == true ->
                            "Resumed \"${updated.title.take(SNACKBAR_TITLE_LIMIT)}\""
                        else -> "Updated \"${updated.title.take(SNACKBAR_TITLE_LIMIT)}\""
                    }
                host.showStatusMessage(message)
            }
            .onFailure { err ->
                host.showStatusMessage("Board update failed (${err.message ?: "error"})")
            }
    }

    private suspend fun performDeleteBoard(boardId: String) {
        host.repository
            .deleteBoard(boardId)
            .onSuccess { deletedTitle ->
                if (host.currentTransientState().selectedBoardReaderBoardId == boardId) {
                    cancelBoardReaderLoad()
                }
                host.updateTransient { current -> applyDeletedBoardState(current, boardId) }
                host.showStatusMessage("Deleted \"${deletedTitle.take(SNACKBAR_TITLE_LIMIT)}\"")
            }
            .onFailure { err ->
                host.showStatusMessage("Delete failed (${err.message ?: "error"})")
            }
    }

    private fun applyDeletedBoardState(
        current: TransientUiState,
        boardId: String,
    ): TransientUiState {
        val closingReader = current.selectedBoardReaderBoardId == boardId
        val nextContextTarget =
            current.boardContextSheetTarget?.takeUnless { it.boardId == boardId }
        return current.copy(
            selectedBoardId = current.selectedBoardId.takeUnless { it == boardId },
            editBoardSheetId = current.editBoardSheetId.takeUnless { it == boardId },
            boardContextSheetTarget = nextContextTarget,
            boardPaginationByBoardId = current.boardPaginationByBoardId - boardId,
            selectedBoardReaderBoardId =
                if (closingReader) null else current.selectedBoardReaderBoardId,
            selectedBoardReaderExternalId =
                if (closingReader) null else current.selectedBoardReaderExternalId,
            boardReaderState = if (closingReader) null else current.boardReaderState,
        )
    }

    private suspend fun performRefreshBoard(boardId: String) {
        if (boardId in host.currentTransientState().refreshingBoardIds) return
        host.updateTransient { it.copy(refreshingBoardIds = it.refreshingBoardIds + boardId) }
        try {
            host.repository
                .refreshBoard(boardId)
                .onSuccess { count ->
                    host.updateTransient { current ->
                        current.copy(
                            boardPaginationByBoardId = current.boardPaginationByBoardId - boardId
                        )
                    }
                    val noun = if (count == 1) "story" else "stories"
                    host.showStatusMessage("Refreshed board · $count $noun")
                }
                .onFailure { err ->
                    host.showStatusMessage("Refresh failed (${err.message ?: "unreachable"})")
                }
        } finally {
            host.updateTransient { it.copy(refreshingBoardIds = it.refreshingBoardIds - boardId) }
        }
    }

    private suspend fun performRefreshAllBoards() {
        host.repository
            .refreshAllBoards()
            .onSuccess { polled ->
                host.updateTransient { current ->
                    current.copy(boardPaginationByBoardId = emptyMap())
                }
                val msg =
                    if (polled == 0) {
                        "Boards are up to date"
                    } else {
                        val noun = if (polled == 1) "board" else "boards"
                        "Refreshed $polled $noun"
                    }
                host.showStatusMessage(msg)
            }
            .onFailure { err ->
                host.showStatusMessage("Refresh failed (${err.message ?: "unreachable"})")
            }
    }

    private suspend fun performLoadMore(boardId: String) {
        val current = host.currentTransientState()
        val pag = current.boardPaginationByBoardId[boardId] ?: BoardPaginationState()
        if (pag.isLoadingMore) return
        val buffered = host.repository.latestSnapshot?.boardItems?.get(boardId).orEmpty()
        val stream =
            computeVisibleBoardStream(
                bufferedItems = buffered,
                pagination = pag,
                filter = BoardStreamFilter.ACTIVE,
                filterQuery = "",
            )
        if (stream.hasMoreLocalBuffer) {
            host.updateTransient { state ->
                val curPag = state.boardPaginationByBoardId[boardId] ?: BoardPaginationState()
                val updated =
                    curPag.copy(
                        displayLimit = curPag.displayLimit + BOARD_DEFAULT_PAGE_SIZE,
                        loadMoreError = null,
                    )
                state.copy(
                    boardPaginationByBoardId = state.boardPaginationByBoardId + (boardId to updated)
                )
            }
            return
        }
        if (pag.liveExhausted) return
        fetchNextLivePage(boardId = boardId, buffered = buffered, pag = pag)
    }

    private suspend fun fetchNextLivePage(
        boardId: String,
        buffered: List<BoardItemView>,
        pag: BoardPaginationState,
    ) {
        val offset = pag.nextLiveOffset ?: buffered.size
        host.updateTransient { state ->
            val curPag = state.boardPaginationByBoardId[boardId] ?: BoardPaginationState()
            state.copy(
                boardPaginationByBoardId =
                    state.boardPaginationByBoardId +
                        (boardId to curPag.copy(isLoadingMore = true, loadMoreError = null))
            )
        }
        host.repository
            .fetchLiveBoardPage(boardId = boardId, offset = offset, limit = BOARD_DEFAULT_PAGE_SIZE)
            .onSuccess { page ->
                host.updateTransient { state ->
                    applyLivePageSuccess(state, boardId, buffered, page)
                }
            }
            .onFailure { err ->
                host.updateTransient { state ->
                    val curPag = state.boardPaginationByBoardId[boardId] ?: BoardPaginationState()
                    val updated =
                        curPag.copy(
                            isLoadingMore = false,
                            loadMoreError = err.message ?: "Could not load more stories",
                        )
                    state.copy(
                        boardPaginationByBoardId =
                            state.boardPaginationByBoardId + (boardId to updated)
                    )
                }
            }
    }

    private fun applyLivePageSuccess(
        state: TransientUiState,
        boardId: String,
        buffered: List<BoardItemView>,
        page: BoardPageResult,
    ): TransientUiState {
        val curPag = state.boardPaginationByBoardId[boardId] ?: BoardPaginationState()
        val existingIds =
            (buffered.map { it.externalId } + curPag.ephemeralItems.map { it.externalId })
                .toHashSet()
        val appended = curPag.ephemeralItems + page.items.filter { it.externalId !in existingIds }
        val updated =
            curPag.copy(
                displayLimit = curPag.displayLimit + BOARD_DEFAULT_PAGE_SIZE,
                ephemeralItems = appended,
                nextLiveOffset = page.nextOffset,
                liveExhausted = !page.hasMore,
                isLoadingMore = false,
                loadMoreError = null,
            )
        return state.copy(
            boardPaginationByBoardId = state.boardPaginationByBoardId + (boardId to updated)
        )
    }

    private suspend fun performDismissItem(action: MainAction.DismissBoardItem) {
        host.repository.dismissBoardItem(action.boardId, action.externalId)
        val dismissedIso = nowUtcIso()
        val closingReader =
            action.closeReaderIfOpen &&
                host.currentTransientState().selectedBoardReaderBoardId == action.boardId &&
                host.currentTransientState().selectedBoardReaderExternalId == action.externalId
        if (closingReader) {
            cancelBoardReaderLoad()
        }
        host.updateTransient { current ->
            val patched =
                patchBoardItemInTransient(current, action.boardId, action.externalId) {
                    it.copy(dismissedAt = dismissedIso)
                }
            val cleared = clearBoardContextIfMatching(patched, action.boardId, action.externalId)
            val next =
                if (closingReader) {
                    cleared.copy(
                        selectedBoardReaderBoardId = null,
                        selectedBoardReaderExternalId = null,
                        boardReaderState = null,
                    )
                } else {
                    cleared
                }
            next.copy(
                statusBanner = null,
                messageSequence = next.messageSequence + 1,
                undoEvent =
                    UndoActionEvent(
                        message = "Dismissed \"${action.title.take(SNACKBAR_TITLE_LIMIT)}\"",
                        kind =
                            UndoActionKind.RestoreBoardDismiss(
                                boardId = action.boardId,
                                externalId = action.externalId,
                            ),
                    ),
            )
        }
    }

    private suspend fun performUndismissItem(boardId: String, externalId: String) {
        host.repository.undismissBoardItem(boardId, externalId)
        host.updateTransient { current ->
            val patched =
                patchBoardItemInTransient(current, boardId, externalId) {
                    it.copy(dismissedAt = null)
                }
            clearBoardContextIfMatching(patched, boardId, externalId)
        }
        host.showStatusMessage("Restored story")
    }

    private suspend fun performToggleStar(action: MainAction.ToggleBoardItemStar) {
        val item = resolveCurrentItem(action.boardId, action.externalId, action.item)
        val linkedId = item?.linkedEntryId
        if (item?.isStarred == true && linkedId != null) {
            runSuspendCatching {
                host.repository.catalog.removeCollectionEntry(STARRED_COLLECTION_ID, linkedId)
            }
                .onSuccess {
                    patchTrackedBoardItem(action.boardId, action.externalId, closeContext = true) {
                        it.copy(isStarred = false)
                    }
                    host.showStatusMessage("Removed star")
                }
                .onFailure { err ->
                    host.showStatusMessage("Could not remove star (${err.message ?: "error"})")
                }
            return
        }
        host.repository
            .promoteBoardItem(
                boardId = action.boardId,
                externalId = action.externalId,
                action = BoardPromoteAction.STAR,
                fallbackItem = item,
                extractedHtml = action.extractedHtml,
            )
            .onSuccess { res ->
                patchTrackedBoardItem(action.boardId, action.externalId, closeContext = true) {
                    it.copy(linkedEntryId = res.entryId, isStarred = true)
                }
                host.showStatusMessage("Starred")
            }
            .onFailure { err ->
                host.showStatusMessage("Could not star story (${err.message ?: "error"})")
            }
    }

    private suspend fun performToggleQueue(action: MainAction.ToggleBoardItemQueue) {
        val item = resolveCurrentItem(action.boardId, action.externalId, action.item)
        val linkedId = item?.linkedEntryId
        if (item?.isInQueue == true && linkedId != null) {
            runSuspendCatching { removeEntryFromWantCollections(linkedId) }
                .onSuccess {
                    patchTrackedBoardItem(action.boardId, action.externalId, closeContext = true) {
                        it.copy(isInQueue = false)
                    }
                    host.showStatusMessage("Removed from Queue")
                }
                .onFailure { err ->
                    host.showStatusMessage(
                        "Could not remove from Queue (${err.message ?: "error"})"
                    )
                }
            return
        }
        host.repository
            .promoteBoardItem(
                boardId = action.boardId,
                externalId = action.externalId,
                action = BoardPromoteAction.QUEUE,
                fallbackItem = item,
                extractedHtml = action.extractedHtml,
            )
            .onSuccess { res ->
                patchTrackedBoardItem(action.boardId, action.externalId, closeContext = true) {
                    it.copy(linkedEntryId = res.entryId, isInQueue = true)
                }
                host.showStatusMessage("Added to Queue")
            }
            .onFailure { err ->
                host.showStatusMessage("Could not queue story (${err.message ?: "error"})")
            }
    }

    private suspend fun removeEntryFromWantCollections(entryId: String) {
        val matchingCols = host.repository.catalog.findWantCollectionsForEntry(entryId)
        val targets = matchingCols.ifEmpty { listOf(WANT_TO_READ_COLLECTION_ID) }
        for (colId in targets) {
            host.repository.catalog.removeCollectionEntry(colId, entryId)
        }
    }

    private suspend fun performFinishItem(action: MainAction.FinishBoardItem) {
        val item = resolveCurrentItem(action.boardId, action.externalId, action.item)
        if (item?.isFinished == true) return
        host.repository
            .promoteBoardItem(
                boardId = action.boardId,
                externalId = action.externalId,
                action = BoardPromoteAction.FINISH,
                fallbackItem = item,
                extractedHtml = action.extractedHtml,
            )
            .onSuccess { res ->
                patchTrackedBoardItem(action.boardId, action.externalId, closeContext = true) {
                    it.copy(linkedEntryId = res.entryId, isFinished = true, isInQueue = false)
                }
                if (!action.silent) {
                    host.showStatusMessage("Marked finished · Saved to Diary")
                }
            }
            .onFailure { err ->
                if (!action.silent) {
                    host.showStatusMessage("Could not finish story (${err.message ?: "error"})")
                }
            }
    }

    private suspend fun performUnfinishItem(action: MainAction.UnfinishBoardItem) {
        host.repository
            .unfinishBoardItem(action.boardId, action.externalId, action.entryId)
            .onSuccess {
                patchTrackedBoardItem(action.boardId, action.externalId, closeContext = true) {
                    it.copy(isFinished = false)
                }
                host.showStatusMessage("Undid Finished")
            }
            .onFailure { err ->
                host.showStatusMessage("Could not undo finish (${err.message ?: "error"})")
            }
    }

    private suspend fun performPromoteToList(action: MainAction.PromoteBoardItemToList) {
        val item = resolveCurrentItem(action.boardId, action.externalId, action.item)
        host.repository
            .promoteBoardItem(
                boardId = action.boardId,
                externalId = action.externalId,
                action = BoardPromoteAction.LIST,
                fallbackItem = item,
                extractedHtml = action.extractedHtml,
                listId = action.listId,
            )
            .onSuccess { res ->
                patchTrackedBoardItem(action.boardId, action.externalId, closeContext = true) {
                    it.copy(linkedEntryId = res.entryId)
                }
                host.showStatusMessage(
                    "Added to \"${action.listTitle.take(SNACKBAR_TITLE_LIMIT)}\""
                )
            }
            .onFailure { err ->
                host.showStatusMessage("Could not add to list (${err.message ?: "error"})")
            }
    }

    private fun resolveCurrentItem(
        boardId: String,
        externalId: String,
        fallback: BoardItemView?,
    ): BoardItemView? = resolveCurrentBoardItem(host, boardId, externalId, fallback)

    private fun patchTrackedBoardItem(
        boardId: String,
        externalId: String,
        closeContext: Boolean = false,
        transform: (BoardItemView) -> BoardItemView,
    ) {
        host.updateTransient { current ->
            val patched = patchBoardItemInTransient(current, boardId, externalId, transform)
            if (closeContext) {
                clearBoardContextIfMatching(patched, boardId, externalId)
            } else {
                patched
            }
        }
    }
}

internal fun markBoardReaderLoading(
    current: TransientUiState,
    boardId: String,
    externalId: String,
    resolvedFallback: BoardItemView?,
): TransientUiState {
    val isActive =
        current.selectedBoardReaderBoardId == boardId &&
            current.selectedBoardReaderExternalId == externalId
    if (!isActive) return current
    val existingContent =
        current.boardReaderState?.content?.takeIf {
            it.boardId == boardId && it.externalId == externalId
        }
    return current.copy(
        boardReaderState =
            BoardReaderUiState(
                boardId = boardId,
                externalId = externalId,
                initialItem = resolvedFallback ?: current.boardReaderState?.initialItem,
                content = existingContent,
                isLoading = true,
                errorMessage = null,
            )
    )
}

internal fun applyBoardReaderLoadResult(
    current: TransientUiState,
    boardId: String,
    externalId: String,
    result: Result<BoardReaderContent>,
): TransientUiState {
    val isActive =
        current.selectedBoardReaderBoardId == boardId &&
            current.selectedBoardReaderExternalId == externalId
    if (!isActive) return current
    val prev =
        current.boardReaderState ?: BoardReaderUiState(boardId = boardId, externalId = externalId)
    val nextState =
        result.fold(
            onSuccess = { loaded ->
                prev.copy(
                    initialItem = loaded.item,
                    content = loaded,
                    isLoading = false,
                    errorMessage = null,
                )
            },
            onFailure = { err ->
                prev.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "Could not load article",
                )
            },
        )
    return current.copy(boardReaderState = nextState)
}

internal fun resolveCurrentBoardItem(
    host: MainDelegateHost,
    boardId: String,
    externalId: String,
    fallback: BoardItemView?,
): BoardItemView? {
    val fromSnap =
        host.repository.latestSnapshot?.boardItems?.get(boardId)?.firstOrNull {
            it.externalId == externalId
        }
    if (fromSnap != null) return fromSnap
    val transient = host.currentTransientState()
    val fromEphemeral =
        transient.boardPaginationByBoardId[boardId]?.ephemeralItems?.firstOrNull {
            it.externalId == externalId
        }
    if (fromEphemeral != null) return fromEphemeral
    val fromReader =
        transient.boardReaderState
            ?.takeIf { it.boardId == boardId && it.externalId == externalId }
            ?.let { it.content?.item ?: it.initialItem }
    return fromReader ?: fallback
}

internal fun clearBoardContextIfMatching(
    current: TransientUiState,
    boardId: String,
    externalId: String,
): TransientUiState {
    val matches =
        current.boardContextSheetTarget?.let {
            it.boardId == boardId && it.externalId == externalId
        } == true
    return if (matches) current.copy(boardContextSheetTarget = null) else current
}

internal fun patchBoardItemInTransient(
    current: TransientUiState,
    boardId: String,
    externalId: String,
    transform: (BoardItemView) -> BoardItemView,
): TransientUiState {
    val nextPagMap =
        current.boardPaginationByBoardId[boardId]?.let { pag ->
            if (pag.ephemeralItems.any { it.externalId == externalId }) {
                val updatedEphemeral =
                    pag.ephemeralItems.map { item ->
                        if (item.externalId == externalId) transform(item) else item
                    }
                current.boardPaginationByBoardId +
                    (boardId to pag.copy(ephemeralItems = updatedEphemeral))
            } else {
                current.boardPaginationByBoardId
            }
        } ?: current.boardPaginationByBoardId
    val nextReader =
        current.boardReaderState?.let { reader ->
            if (reader.boardId == boardId && reader.externalId == externalId) {
                val updatedInitial = reader.initialItem?.let(transform)
                val updatedContent =
                    reader.content?.let { content ->
                        val nextItem = transform(content.item)
                        content.copy(
                            item = nextItem,
                            linkedEntryId = nextItem.linkedEntryId ?: content.linkedEntryId,
                        )
                    }
                reader.copy(initialItem = updatedInitial, content = updatedContent)
            } else {
                reader
            }
        }
    return current.copy(
        boardPaginationByBoardId = nextPagMap,
        boardReaderState = nextReader,
    )
}
