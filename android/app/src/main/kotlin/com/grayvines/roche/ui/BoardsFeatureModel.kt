package com.grayvines.roche.ui

import com.grayvines.roche.core.DiscussionLink
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.isArxivPaperUrl
import com.grayvines.roche.core.parseIsoInstant
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.boards.BOARD_DEFAULT_PAGE_SIZE
import com.grayvines.roche.data.boards.BoardAdapterType
import com.grayvines.roche.data.boards.BoardItemView
import com.grayvines.roche.data.boards.BoardReaderContent
import com.grayvines.roche.data.boards.BoardSummary
import com.grayvines.roche.data.boards.HackerNewsSection
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.inferContentType
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class BoardStreamFilter(val label: String) {
    ACTIVE("Active"),
    DISMISSED("Dismissed"),
}

data class BoardPaginationState(
    val displayLimit: Int = BOARD_DEFAULT_PAGE_SIZE,
    val ephemeralItems: List<BoardItemView> = emptyList(),
    val nextLiveOffset: Int? = null,
    val liveExhausted: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
)

data class BoardReaderUiState(
    val boardId: String,
    val externalId: String,
    val initialItem: BoardItemView? = null,
    val content: BoardReaderContent? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isPromoting: Boolean = false,
)

data class BoardStoryContextTarget(
    val boardId: String,
    val externalId: String,
    val item: BoardItemView,
)

internal data class VisibleBoardStream(
    val items: List<BoardItemView>,
    val totalMatchingCount: Int,
    val activeCount: Int,
    val dismissedCount: Int,
    val hasMoreLocalBuffer: Boolean,
    val canLoadMoreLive: Boolean,
    val isLoadingMore: Boolean,
    val loadMoreError: String?,
)

private data class EphemeralDedupSets(
    val entryIdByCanonUrl: Map<String, String>,
    val entryIdByArxiv: Map<String, String>,
    val starredIds: Set<String>,
    val inQueueIds: Set<String>,
    val finishedIds: Set<String>,
)

internal fun boardCreationKey(adapterType: BoardAdapterType, sectionOrUrl: String): String {
    val resolvedKey =
        if (adapterType == BoardAdapterType.HACKER_NEWS) {
            HackerNewsSection.fromSectionOrUrl(sectionOrUrl)?.sourceKey
        } else {
            null
        }
    return "${adapterType.wireValue}:${resolvedKey ?: sectionOrUrl.trim().lowercase()}"
}

internal fun computeVisibleBoardStream(
    bufferedItems: List<BoardItemView>,
    pagination: BoardPaginationState,
    filter: BoardStreamFilter,
    filterQuery: String,
): VisibleBoardStream {
    val bufferedIds = bufferedItems.mapTo(HashSet(bufferedItems.size)) { it.externalId }
    val mergedAll =
        if (pagination.ephemeralItems.isEmpty()) {
            bufferedItems
        } else {
            bufferedItems + pagination.ephemeralItems.filter { it.externalId !in bufferedIds }
        }
    val activeAll = mergedAll.filter { !it.isDismissed }
    val dismissedAll = mergedAll.filter { it.isDismissed }
    val pool = if (filter == BoardStreamFilter.ACTIVE) activeAll else dismissedAll
    val cleanQuery = filterQuery.trim().lowercase()
    val filtered =
        if (cleanQuery.isEmpty()) {
            pool
        } else {
            pool.filter { item -> matchesBoardFilterQuery(item, cleanQuery) }
        }
    val isWindowedActive = filter == BoardStreamFilter.ACTIVE && cleanQuery.isEmpty()
    val visible =
        if (isWindowedActive) {
            filtered.take(pagination.displayLimit.coerceAtLeast(BOARD_DEFAULT_PAGE_SIZE))
        } else {
            filtered
        }
    val hasMoreLocal = isWindowedActive && filtered.size > visible.size
    val hasLiveCandidates = !pagination.liveExhausted && bufferedItems.isNotEmpty()
    val canLoadLive = isWindowedActive && !hasMoreLocal && hasLiveCandidates
    return VisibleBoardStream(
        items = visible,
        totalMatchingCount = filtered.size,
        activeCount = activeAll.size,
        dismissedCount = dismissedAll.size,
        hasMoreLocalBuffer = hasMoreLocal,
        canLoadMoreLive = canLoadLive,
        isLoadingMore = pagination.isLoadingMore,
        loadMoreError = pagination.loadMoreError,
    )
}

internal fun matchesBoardFilterQuery(item: BoardItemView, normalizedQuery: String): Boolean {
    if (normalizedQuery.isEmpty()) return true
    return item.title.contains(normalizedQuery, ignoreCase = true) ||
        item.displayDomain.contains(normalizedQuery, ignoreCase = true) ||
        item.author?.contains(normalizedQuery, ignoreCase = true) == true ||
        item.summary?.contains(normalizedQuery, ignoreCase = true) == true
}

internal fun reconcileBoardPaginationMap(
    paginationByBoardId: Map<String, BoardPaginationState>,
    snapshot: RepositorySnapshot,
): Map<String, BoardPaginationState> {
    if (paginationByBoardId.isEmpty()) return paginationByBoardId
    if (paginationByBoardId.values.all { it.ephemeralItems.isEmpty() }) {
        return paginationByBoardId
    }
    val dedup = buildEphemeralDedupSets(snapshot)
    var anyBoardChanged = false
    val mapped = paginationByBoardId.mapValues { (boardId, pag) ->
        if (pag.ephemeralItems.isEmpty()) {
            pag
        } else {
            val updatedItems = reconcileEphemeralList(boardId, pag.ephemeralItems, snapshot, dedup)
            if (updatedItems !== pag.ephemeralItems) {
                anyBoardChanged = true
                pag.copy(ephemeralItems = updatedItems)
            } else {
                pag
            }
        }
    }
    return if (anyBoardChanged) mapped else paginationByBoardId
}

private fun buildEphemeralDedupSets(snapshot: RepositorySnapshot): EphemeralDedupSets {
    val byCanonUrl = HashMap<String, String>(snapshot.entries.size)
    val byArxiv = HashMap<String, String>()
    val finishedIds = HashSet<String>()
    val starredIds = HashSet<String>(snapshot.catalog.starredEntryIds)
    for (entry in snapshot.entries) {
        val canon = canonicalizeSyncUrl(entry.url)
        if (canon.isNotEmpty()) {
            byCanonUrl.putIfAbsent(canon, entry.id)
        }
        extractArxivIdentifier(entry.url)?.let { arxiv -> byArxiv.putIfAbsent(arxiv, entry.id) }
        if (entry.isFinished) finishedIds.add(entry.id)
        if (entry.isFavorite) starredIds.add(entry.id)
    }
    for (diary in snapshot.catalog.recentDiaryEntries) {
        if (diary.status == ConsumptionStatus.CONSUMED) {
            finishedIds.add(diary.entryId)
        }
    }
    val inQueueIds = snapshot.catalog.wantEntryIds
    return EphemeralDedupSets(
        entryIdByCanonUrl = byCanonUrl,
        entryIdByArxiv = byArxiv,
        starredIds = starredIds,
        inQueueIds = inQueueIds,
        finishedIds = finishedIds,
    )
}

private fun reconcileEphemeralList(
    boardId: String,
    items: List<BoardItemView>,
    snapshot: RepositorySnapshot,
    dedup: EphemeralDedupSets,
): List<BoardItemView> {
    var anyItemChanged = false
    val mapped = items.map { item ->
        val updated = reconcileSingleEphemeralItem(boardId, item, snapshot, dedup)
        if (updated != item) {
            anyItemChanged = true
            updated
        } else {
            item
        }
    }
    return if (anyItemChanged) mapped else items
}

private fun reconcileSingleEphemeralItem(
    boardId: String,
    item: BoardItemView,
    snapshot: RepositorySnapshot,
    dedup: EphemeralDedupSets,
): BoardItemView {
    val state = snapshot.boardItemStates[boardId to item.externalId]
    val resolvedLinkedId =
        item.linkedEntryId
            ?: extractArxivIdentifier(item.url)?.let { dedup.entryIdByArxiv[it] }
            ?: canonicalizeSyncUrl(item.url)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    dedup.entryIdByCanonUrl[it]
                }
    return item.copy(
        seenAt = state?.seenAt ?: item.seenAt,
        dismissedAt = if (state != null) state.dismissedAt else item.dismissedAt,
        linkedEntryId = resolvedLinkedId,
        isStarred = resolvedLinkedId?.let { it in dedup.starredIds } ?: item.isStarred,
        isInQueue = resolvedLinkedId?.let { it in dedup.inQueueIds } ?: item.isInQueue,
        isFinished = item.isFinished || resolvedLinkedId?.let { it in dedup.finishedIds } == true,
    )
}

internal fun resolveLiveBoardReaderItem(
    state: MainUiState,
    boardId: String,
    externalId: String,
): BoardItemView? {
    val fromSnapshot =
        state.boardItemsByBoardId[boardId]?.firstOrNull { it.externalId == externalId }
    if (fromSnapshot != null) return fromSnapshot
    val fromEphemeral =
        state.boardPaginationByBoardId[boardId]?.ephemeralItems?.firstOrNull {
            it.externalId == externalId
        }
    if (fromEphemeral != null) return fromEphemeral
    val reader =
        state.boardReaderState?.takeIf { it.boardId == boardId && it.externalId == externalId }
    return reader?.content?.item ?: reader?.initialItem
}

internal fun resolveBoardStoryKindBadge(item: BoardItemView): String? =
    when {
        item.isSelfPost && item.title.startsWith("Ask HN", ignoreCase = true) -> "Ask HN"
        item.isSelfPost -> "Self"
        isArxivPaperUrl(item.url) -> "Paper"
        inferContentType(item.url) == ContentType.PDF -> "PDF"
        inferContentType(item.url) == ContentType.VIDEO -> "Video"
        else -> null
    }

internal fun formatBoardFreshnessLabel(
    board: BoardSummary,
    now: Instant = Instant.now(),
): String {
    if (board.isPaused) return "Paused"
    val raw = board.snapshotFetchedAt ?: board.lastPolledAt ?: return "Not polled yet"
    val instant = parseIsoInstant(raw) ?: return "Updated recently"
    val elapsed = Duration.between(instant, now).seconds.coerceAtLeast(0L)
    return when {
        elapsed < BOARD_SECONDS_PER_MIN -> "Updated just now"
        elapsed < BOARD_SECONDS_PER_HOUR -> "Updated ${elapsed / BOARD_SECONDS_PER_MIN}m ago"
        elapsed < BOARD_SECONDS_PER_DAY -> "Updated ${elapsed / BOARD_SECONDS_PER_HOUR}h ago"
        else -> "Updated ${elapsed / BOARD_SECONDS_PER_DAY}d ago"
    }
}

internal fun formatBoardStoryMetadata(item: BoardItemView): String = buildList {
    resolveBoardStoryKindBadge(item)?.let(::add)
    if (!item.isSelfPost) {
        val rawDomain = item.domain?.takeIf { it.isNotBlank() } ?: item.displayDomain
        rawDomain.takeIf { it.isNotBlank() && !it.equals(HN_HOST, ignoreCase = true) }?.let(::add)
    }
    item.score?.let { pts -> add(if (pts == 1) "1 pt" else "$pts pts") }
    item.author?.trim()?.takeIf { it.isNotEmpty() }?.let { add("by $it") }
    item.formattedPublishedLabel.takeIf { it.isNotBlank() }?.let(::add)
}
    .joinToString(" · ")

internal fun formatBoardCommentsLabel(commentCount: Int?): String =
    when {
        commentCount == null || commentCount <= 0 -> "Discuss ↗"
        commentCount == 1 -> "1 comment ↗"
        else -> "$commentCount comments ↗"
    }

internal fun formatBoardCompactComments(commentCount: Int?): String? =
    commentCount?.takeIf { it > 0 }?.toString()

internal fun visibleExternalDiscussions(entry: InboxEntry): List<DiscussionLink> {
    if (entry.discussions.isEmpty()) return emptyList()
    val entryCanon = canonicalizeSyncUrl(entry.url)
    if (entryCanon.isEmpty()) return entry.discussions
    return entry.discussions.filter { link -> canonicalizeSyncUrl(link.url) != entryCanon }
}

internal fun reduceBoardUiAction(
    state: TransientUiState,
    action: MainAction.BoardUi,
): TransientUiState =
    when (action) {
        is MainAction.SelectBoard ->
            state.copy(
                selectedBoardId = action.boardId,
                boardStreamFilter = BoardStreamFilter.ACTIVE,
                boardFilterQuery = "",
                boardContextSheetTarget = null,
            )
        is MainAction.SelectBoardStreamFilter -> state.copy(boardStreamFilter = action.filter)
        is MainAction.UpdateBoardFilterQuery -> state.copy(boardFilterQuery = action.query)
        MainAction.OpenAddBoardSheet ->
            state.copy(
                isAddBoardSheetOpen = true,
                editBoardSheetId = null,
                boardContextSheetTarget = null,
                contextSheetEntryId = null,
                feedContextSheetId = null,
            )
        MainAction.CloseAddBoardSheet -> state.copy(isAddBoardSheetOpen = false)
        is MainAction.OpenEditBoardSheet ->
            state.copy(
                editBoardSheetId = action.boardId,
                isAddBoardSheetOpen = false,
                boardContextSheetTarget = null,
            )
        MainAction.CloseEditBoardSheet -> state.copy(editBoardSheetId = null)
        is MainAction.OpenBoardStoryContextSheet ->
            state.copy(
                boardContextSheetTarget =
                    BoardStoryContextTarget(action.boardId, action.externalId, action.item),
                isAddBoardSheetOpen = false,
                editBoardSheetId = null,
            )
        MainAction.CloseBoardStoryContextSheet -> state.copy(boardContextSheetTarget = null)
        is MainAction.OpenBoardReader -> reduceOpenBoardReader(state, action)
        MainAction.CloseBoardReader ->
            state.copy(
                selectedBoardReaderBoardId = null,
                selectedBoardReaderExternalId = null,
                boardReaderState = null,
            )
    }

private fun reduceOpenBoardReader(
    state: TransientUiState,
    action: MainAction.OpenBoardReader,
): TransientUiState =
    state.copy(
        selectedBoardId = action.boardId,
        selectedReaderEntryId = null,
        readerContent = null,
        selectedBoardReaderBoardId = action.boardId,
        selectedBoardReaderExternalId = action.externalId,
        boardReaderState =
            BoardReaderUiState(
                boardId = action.boardId,
                externalId = action.externalId,
                initialItem = action.item,
                isLoading = true,
            ),
        contextSheetEntryId = null,
        feedContextSheetId = null,
        isAddBoardSheetOpen = false,
        editBoardSheetId = null,
        boardContextSheetTarget = null,
    )

private const val HN_HOST = "news.ycombinator.com"
private const val BOARD_SECONDS_PER_MIN = 60L
private const val BOARD_SECONDS_PER_HOUR = 3_600L
private const val BOARD_SECONDS_PER_DAY = 86_400L
