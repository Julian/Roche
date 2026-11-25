package com.grayvines.roche.data.boards

import com.grayvines.roche.core.SCHEME_HACKER_NEWS
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.extractHackerNewsItemId
import com.grayvines.roche.core.normalizeConsecutiveFailures
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.BoardItemEntity
import com.grayvines.roche.data.local.BoardItemStateEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.RocheDao

private val BOARD_DEDUP_CONTAINER_KINDS =
    setOf("publication", "podcast", "channel", "collection", "artist")
private const val BOARD_DEDUP_CHUNK_SIZE = 450

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

internal data class BoardDedupKey(
    val adapterType: String,
    val url: String,
    val externalId: String,
)

internal data class BoardDedupLookup(
    val entryIdByUrl: Map<String, String> = emptyMap(),
    val entryIdByArxiv: Map<String, String> = emptyMap(),
    val entryIdByHn: Map<String, String> = emptyMap(),
    val inQueueEntryIds: Set<String> = emptySet(),
    val starredEntryIds: Set<String> = emptySet(),
    val finishedEntryIds: Set<String> = emptySet(),
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

private data class ResolvedScopedEntries(
    val redirects: Map<String, String>,
    val kindById: Map<String, String>,
)

/**
 * Loads deduplication links and badges ONLY for the provided active board [keys], using PK
 * `(scheme, value)` lookups on `entry_identifiers` and targeted `IN` badge queries instead of
 * scanning full catalog tables.
 */
internal suspend fun loadScopedBoardDedupLookup(
    dao: RocheDao,
    keys: List<BoardDedupKey>,
): BoardDedupLookup {
    if (keys.isEmpty()) return BoardDedupLookup()
    val matchedIdents = queryScopedIdentifiers(dao, keys)
    if (matchedIdents.isEmpty()) return BoardDedupLookup()

    val rawEntryIds = matchedIdents.mapTo(LinkedHashSet()) { it.entryId }
    val resolved = resolveScopedEntryRedirects(dao, rawEntryIds)
    val entryIdByUrl = HashMap<String, String>()
    val entryIdByArxiv = HashMap<String, String>()
    val entryIdByHn = HashMap<String, String>()
    val canonicalOwnerIds = HashSet<String>(rawEntryIds.size)

    for (ident in matchedIdents) {
        val owner = followRedirect(ident.entryId, resolved.redirects)
        val kind = resolved.kindById[owner]
        if (kind != null && kind !in BOARD_DEDUP_CONTAINER_KINDS) {
            canonicalOwnerIds.add(owner)
            when (ident.scheme) {
                "url" -> entryIdByUrl.putIfAbsent(ident.value, owner)
                "arxiv" -> entryIdByArxiv.putIfAbsent(ident.value, owner)
                SCHEME_HACKER_NEWS -> entryIdByHn.putIfAbsent(ident.value, owner)
            }
        }
    }
    if (canonicalOwnerIds.isEmpty()) return BoardDedupLookup()

    val allRedirects = mergeAllEntryRedirects(dao, resolved.redirects)
    val badgeQueryIds = collectBadgeQueryIds(rawEntryIds, canonicalOwnerIds, allRedirects)
    val inQueueIds = loadBadgeEntryIds(badgeQueryIds, allRedirects) { dao.getWantEntryIdsIn(it) }
    val starredIds = loadBadgeEntryIds(badgeQueryIds, allRedirects) { dao.getStarredEntryIdsIn(it) }
    val finishedIds =
        loadBadgeEntryIds(badgeQueryIds, allRedirects) { dao.getConsumedEntryIdsIn(it) }

    return BoardDedupLookup(
        entryIdByUrl = entryIdByUrl,
        entryIdByArxiv = entryIdByArxiv,
        entryIdByHn = entryIdByHn,
        inQueueEntryIds = inQueueIds,
        starredEntryIds = starredIds,
        finishedEntryIds = finishedIds,
    )
}

private suspend fun queryScopedIdentifiers(
    dao: RocheDao,
    keys: List<BoardDedupKey>,
): List<EntryIdentifierEntity> {
    val arxivValues = keys.mapNotNull { extractArxivIdentifier(it.url) }.distinct()
    val urlValues =
        keys.mapNotNull { canonicalizeSyncUrl(it.url).takeIf(String::isNotEmpty) }.distinct()
    val hnValues =
        keys
            .asSequence()
            .filter { it.adapterType == SCHEME_HACKER_NEWS }
            .mapNotNull { extractHackerNewsItemId(it.externalId) }
            .distinct()
            .toList()
    val matched = ArrayList<EntryIdentifierEntity>()
    querySchemeValuesChunked(dao, "arxiv", arxivValues, matched)
    querySchemeValuesChunked(dao, "url", urlValues, matched)
    querySchemeValuesChunked(dao, SCHEME_HACKER_NEWS, hnValues, matched)
    return matched
}

private suspend fun querySchemeValuesChunked(
    dao: RocheDao,
    scheme: String,
    values: List<String>,
    out: MutableList<EntryIdentifierEntity>,
) {
    if (values.isEmpty()) return
    for (chunk in values.chunked(BOARD_DEDUP_CHUNK_SIZE)) {
        out.addAll(dao.getIdentifiersBySchemeValues(scheme, chunk))
    }
}

private suspend fun resolveScopedEntryRedirects(
    dao: RocheDao,
    initialIds: Set<String>,
): ResolvedScopedEntries {
    val redirects = HashMap<String, String>()
    val kindById = HashMap<String, String>(initialIds.size)
    val visited = HashSet<String>(initialIds.size)
    var frontier: List<String> = initialIds.toList()
    while (frontier.isNotEmpty()) {
        val toFetch = frontier.filter { visited.add(it) }
        if (toFetch.isEmpty()) break
        frontier =
            fetchRedirectFrontier(
                dao = dao,
                toFetch = toFetch,
                kindById = kindById,
                redirects = redirects,
                visited = visited,
            )
    }
    return ResolvedScopedEntries(redirects = redirects, kindById = kindById)
}

private suspend fun fetchRedirectFrontier(
    dao: RocheDao,
    toFetch: List<String>,
    kindById: MutableMap<String, String>,
    redirects: MutableMap<String, String>,
    visited: Set<String>,
): List<String> {
    val nextFrontier = ArrayList<String>()
    for (chunk in toFetch.chunked(BOARD_DEDUP_CHUNK_SIZE)) {
        for (entry in dao.getEntriesByIds(chunk)) {
            kindById[entry.id] = entry.kind
            val target = entry.mergedIntoId?.trim()?.takeIf { it.isNotEmpty() && it != entry.id }
            if (target != null) {
                redirects[entry.id] = target
                if (target !in visited) nextFrontier.add(target)
            }
        }
    }
    return nextFrontier
}

private suspend fun mergeAllEntryRedirects(
    dao: RocheDao,
    scopedRedirects: Map<String, String>,
): Map<String, String> {
    val mergedRows = dao.getMergedEntryRedirects()
    if (mergedRows.isEmpty()) return scopedRedirects
    val combined = HashMap<String, String>(scopedRedirects.size + mergedRows.size)
    combined.putAll(scopedRedirects)
    for (row in mergedRows) {
        val target = row.mergedIntoId.trim()
        if (target.isNotEmpty() && target != row.id) {
            combined[row.id] = target
        }
    }
    return combined
}

private fun collectBadgeQueryIds(
    rawEntryIds: Set<String>,
    canonicalOwnerIds: Set<String>,
    allRedirects: Map<String, String>,
): List<String> {
    val ids = LinkedHashSet<String>(rawEntryIds.size + canonicalOwnerIds.size)
    ids.addAll(rawEntryIds)
    ids.addAll(canonicalOwnerIds)
    for (src in allRedirects.keys) {
        if (followRedirect(src, allRedirects) in canonicalOwnerIds) {
            ids.add(src)
        }
    }
    return ids.toList()
}

private suspend fun loadBadgeEntryIds(
    entryIds: List<String>,
    redirects: Map<String, String>,
    query: suspend (List<String>) -> List<String>,
): Set<String> {
    val result = HashSet<String>()
    for (chunk in entryIds.chunked(BOARD_DEDUP_CHUNK_SIZE)) {
        for (id in query(chunk)) {
            result.add(followRedirect(id, redirects))
        }
    }
    return result
}

internal suspend fun projectBoardsSnapshot(
    dao: RocheDao,
    boardTables: RawBoardTables,
): ProjectedBoardsSnapshot {
    if (boardTables.boards.isEmpty()) {
        return ProjectedBoardsSnapshot()
    }
    val boardsById = boardTables.boards.associateBy { it.id }
    val keys =
        boardTables.boardItems.mapNotNull { item ->
            boardsById[item.boardId]?.let { board ->
                BoardDedupKey(
                    adapterType = board.adapterType,
                    url = item.url,
                    externalId = item.externalId,
                )
            }
        }
    val dedup = loadScopedBoardDedupLookup(dao, keys)
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
