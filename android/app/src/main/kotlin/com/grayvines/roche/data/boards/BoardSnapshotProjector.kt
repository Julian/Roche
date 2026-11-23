package com.grayvines.roche.data.boards

import com.grayvines.roche.core.SCHEME_HACKER_NEWS
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.extractHackerNewsItemId
import com.grayvines.roche.core.normalizeConsecutiveFailures
import com.grayvines.roche.data.catalog.RawCatalogTables
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.BoardItemEntity
import com.grayvines.roche.data.local.BoardItemStateEntity
import com.grayvines.roche.data.local.RocheDao

private val BOARD_DEDUP_CONTAINER_KINDS =
    setOf("publication", "podcast", "channel", "collection", "artist")
private const val STATUS_CONSUMED = "consumed"
private const val ROLE_WANT = "want"

internal data class RawBoardTables(
    val boards: List<BoardEntity> = emptyList(),
    val boardItems: List<BoardItemEntity> = emptyList(),
    val boardItemStates: List<BoardItemStateEntity> = emptyList(),
)

internal data class ProjectedBoardsSnapshot(
    val boards: List<BoardSummary> = emptyList(),
    val boardItems: Map<String, List<BoardItemView>> = emptyMap(),
    val boardItemStates: Map<Pair<String, String>, BoardItemStateEntity> = emptyMap(),
)

internal data class BoardDedupLookup(
    val entryIdByUrl: Map<String, String>,
    val entryIdByArxiv: Map<String, String>,
    val entryIdByHn: Map<String, String>,
    val inQueueEntryIds: Set<String>,
    val starredEntryIds: Set<String>,
    val finishedEntryIds: Set<String>,
)

internal fun BoardDedupLookup.resolveLinkedEntryId(
    adapterType: String,
    url: String,
    externalId: String,
): String? {
    val byArxiv = extractArxivIdentifier(url)?.let { entryIdByArxiv[it] }
    val byUrl = canonicalizeSyncUrl(url).takeIf { it.isNotEmpty() }?.let { entryIdByUrl[it] }
    val byHn =
        if (adapterType == SCHEME_HACKER_NEWS) {
            extractHackerNewsItemId(externalId)?.let { entryIdByHn[it] }
        } else {
            null
        }
    return byArxiv ?: byUrl ?: byHn
}

private data class IndexedEntryIdentMaps(
    val byUrl: Map<String, String>,
    val byArxiv: Map<String, String>,
    val byHn: Map<String, String>,
)

internal fun buildBoardDedupLookup(catalogTables: RawCatalogTables): BoardDedupLookup {
    val redirects = HashMap<String, String>()
    val entryKindById = HashMap<String, String>(catalogTables.entries.size)
    for (entry in catalogTables.entries) {
        entryKindById[entry.id] = entry.kind
        val target = entry.mergedIntoId?.trim()?.takeIf { it.isNotEmpty() && it != entry.id }
        if (target != null) {
            redirects[entry.id] = target
        }
    }
    val idents = indexIdentifiersForBoardDedup(catalogTables, redirects, entryKindById)
    val (inQueueIds, starredIds) = collectMembershipBadges(catalogTables, redirects)
    val finishedIds =
        catalogTables.logs
            .asSequence()
            .filter { it.status.equals(STATUS_CONSUMED, ignoreCase = true) }
            .mapTo(HashSet()) { followRedirect(it.entryId, redirects) }
    return BoardDedupLookup(
        entryIdByUrl = idents.byUrl,
        entryIdByArxiv = idents.byArxiv,
        entryIdByHn = idents.byHn,
        inQueueEntryIds = inQueueIds,
        starredEntryIds = starredIds,
        finishedEntryIds = finishedIds,
    )
}

private fun indexIdentifiersForBoardDedup(
    catalogTables: RawCatalogTables,
    redirects: Map<String, String>,
    entryKindById: Map<String, String>,
): IndexedEntryIdentMaps {
    val entryIdByUrl = HashMap<String, String>()
    val entryIdByArxiv = HashMap<String, String>()
    val entryIdByHn = HashMap<String, String>()
    for (ident in catalogTables.identifiers) {
        val owner = followRedirect(ident.entryId, redirects)
        val kind = entryKindById[owner]
        if (kind != null && kind !in BOARD_DEDUP_CONTAINER_KINDS) {
            when (ident.scheme) {
                "url" -> entryIdByUrl.putIfAbsent(ident.value, owner)
                "arxiv" -> entryIdByArxiv.putIfAbsent(ident.value, owner)
                SCHEME_HACKER_NEWS -> entryIdByHn.putIfAbsent(ident.value, owner)
            }
        }
    }
    return IndexedEntryIdentMaps(byUrl = entryIdByUrl, byArxiv = entryIdByArxiv, byHn = entryIdByHn)
}

private fun collectMembershipBadges(
    catalogTables: RawCatalogTables,
    redirects: Map<String, String>,
): Pair<Set<String>, Set<String>> {
    val wantColIds =
        catalogTables.specs
            .asSequence()
            .filter { it.role.equals(ROLE_WANT, ignoreCase = true) }
            .mapTo(HashSet()) { it.collectionId }
            .apply { add(WANT_TO_READ_COLLECTION_ID) }
    val inQueueIds = HashSet<String>()
    val starredIds = HashSet<String>()
    for (mem in catalogTables.memberships) {
        val resolvedEntryId = followRedirect(mem.entryId, redirects)
        if (mem.collectionId in wantColIds) {
            inQueueIds.add(resolvedEntryId)
        }
        if (mem.collectionId == STARRED_COLLECTION_ID) {
            starredIds.add(resolvedEntryId)
        }
    }
    return inQueueIds to starredIds
}

internal suspend fun loadBoardDedupLookupFromDao(dao: RocheDao): BoardDedupLookup {
    val rawCatalog =
        RawCatalogTables(
            entries = dao.getAllEntries(),
            identifiers = dao.getAllEntryIdentifiers(),
            specs = dao.getAllCollectionSpecs(),
            memberships = dao.getAllCollectionEntries(),
            generators = emptyList(),
            logs = dao.getAllConsumptionLogs(),
            comparisons = emptyList(),
        )
    return buildBoardDedupLookup(rawCatalog)
}

internal fun projectBoardsSnapshot(
    boardTables: RawBoardTables,
    catalogTables: RawCatalogTables,
): ProjectedBoardsSnapshot {
    if (boardTables.boards.isEmpty()) {
        return ProjectedBoardsSnapshot()
    }
    val dedup = buildBoardDedupLookup(catalogTables)
    val statesByKey =
        boardTables.boardItemStates.associateBy { state -> state.boardId to state.externalId }
    val itemsByBoardId = boardTables.boardItems.groupBy { it.boardId }
    val projectedItemsByBoard = LinkedHashMap<String, List<BoardItemView>>(boardTables.boards.size)
    val summaries = ArrayList<BoardSummary>(boardTables.boards.size)
    val sortedBoards =
        boardTables.boards.sortedWith(
            compareBy<BoardEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                .thenBy { it.id }
        )
    for (board in sortedBoards) {
        val rawItems = itemsByBoardId[board.id].orEmpty().sortedBy { it.rank }
        val projectedItems = rawItems.map { item ->
            projectBoardItemView(
                board = board,
                item = item,
                state = statesByKey[board.id to item.externalId],
                dedup = dedup,
            )
        }
        projectedItemsByBoard[board.id] = projectedItems
        summaries.add(buildBoardSummary(board, projectedItems))
    }
    return ProjectedBoardsSnapshot(
        boards = summaries,
        boardItems = projectedItemsByBoard,
        boardItemStates = statesByKey,
    )
}

internal fun projectBoardItemView(
    board: BoardEntity,
    item: BoardItemEntity,
    state: BoardItemStateEntity?,
    dedup: BoardDedupLookup,
): BoardItemView {
    val linkedId = dedup.resolveLinkedEntryId(board.adapterType, item.url, item.externalId)
    return BoardItemView(
        boardId = item.boardId,
        externalId = item.externalId,
        rank = item.rank,
        title = item.title,
        url = item.url,
        discussionUrl = item.discussionUrl,
        author = item.author,
        domain = item.domain,
        publishedAt = item.publishedAt,
        score = item.score,
        commentCount = item.commentCount,
        summary = item.summary,
        selfTextHtml = item.selfTextHtml,
        metadataJson = item.metadataJson,
        fetchedAt = item.fetchedAt,
        seenAt = state?.seenAt,
        dismissedAt = state?.dismissedAt,
        linkedEntryId = linkedId,
        isInQueue = linkedId != null && linkedId in dedup.inQueueEntryIds,
        isStarred = linkedId != null && linkedId in dedup.starredEntryIds,
        isFinished = linkedId != null && linkedId in dedup.finishedEntryIds,
        isEphemeralPageItem = false,
    )
}

internal fun projectBoardCandidateView(
    board: BoardEntity,
    candidate: BoardPollCandidate,
    fetchedAtIso: String,
    state: BoardItemStateEntity?,
    dedup: BoardDedupLookup,
    isEphemeralPageItem: Boolean = true,
): BoardItemView {
    val linkedId =
        dedup.resolveLinkedEntryId(board.adapterType, candidate.url, candidate.externalId)
    return BoardItemView(
        boardId = board.id,
        externalId = candidate.externalId,
        rank = candidate.rank,
        title = candidate.title,
        url = candidate.url,
        discussionUrl = candidate.discussionUrl,
        author = candidate.author,
        domain = candidate.domain,
        publishedAt = candidate.publishedAt,
        score = candidate.score,
        commentCount = candidate.commentCount,
        summary = candidate.summary,
        selfTextHtml = candidate.selfTextHtml,
        metadataJson = candidate.metadataJson,
        fetchedAt = fetchedAtIso,
        seenAt = state?.seenAt,
        dismissedAt = state?.dismissedAt,
        linkedEntryId = linkedId,
        isInQueue = linkedId != null && linkedId in dedup.inQueueEntryIds,
        isStarred = linkedId != null && linkedId in dedup.starredEntryIds,
        isFinished = linkedId != null && linkedId in dedup.finishedEntryIds,
        isEphemeralPageItem = isEphemeralPageItem,
    )
}

internal fun buildBoardSummary(
    board: BoardEntity,
    projectedItems: List<BoardItemView>,
): BoardSummary {
    var visibleCount = 0
    var unseenCount = 0
    var dismissedCount = 0
    for (item in projectedItems) {
        if (item.isDismissed) {
            dismissedCount++
        } else {
            visibleCount++
            if (!item.isSeen) {
                unseenCount++
            }
        }
    }
    return BoardSummary(
        id = board.id,
        adapterType =
            BoardAdapterType.fromWireValue(board.adapterType) ?: BoardAdapterType.HACKER_NEWS,
        sourceKey = board.sourceKey,
        sourceUrl = board.sourceUrl,
        sourceConfigJson = board.sourceConfigJson,
        title = board.title,
        orderMode = BoardOrderMode.fromWireValue(board.orderMode),
        pollInterval = board.pollInterval,
        isActive = board.isActive,
        lastPolledAt = board.lastPolledAt,
        lastPolledBy = board.lastPolledBy,
        lastError = board.lastError,
        lastErrorBy = board.lastErrorBy,
        consecutiveFailures =
            normalizeConsecutiveFailures(board.lastError, board.consecutiveFailures),
        snapshotFetchedAt = board.snapshotFetchedAt,
        createdAt = board.createdAt,
        updatedAt = board.updatedAt,
        visibleItemCount = visibleCount,
        unseenItemCount = unseenCount,
        dismissedItemCount = dismissedCount,
        bufferedItemCount = projectedItems.size,
    )
}

private fun followRedirect(entryId: String, redirects: Map<String, String>): String {
    var cur = entryId
    if (redirects.isEmpty()) return cur
    val visited = HashSet<String>(4)
    while (visited.add(cur)) {
        val next = redirects[cur] ?: break
        cur = next
    }
    return cur
}
