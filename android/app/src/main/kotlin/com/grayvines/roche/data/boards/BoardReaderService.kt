package com.grayvines.roche.data.boards

import com.grayvines.roche.core.DiscussionLink
import com.grayvines.roche.core.SCHEME_HACKER_NEWS
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.externalIdentifierLabel
import com.grayvines.roche.core.extractDiscussionsForEntry
import com.grayvines.roche.core.extractHackerNewsItemId
import com.grayvines.roche.core.isArxivPaperUrl
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.mergeDiscussionLinkLists
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.core.sanitizeHttpUrl
import com.grayvines.roche.data.feeds.ExtractedWebPageSnapshot
import com.grayvines.roche.data.feeds.extractWebPageSnapshot
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.BoardItemStateEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.remote.HttpStatusException
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.inferContentType
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val BOARD_READER_LRU_CAPACITY = 32
private const val LRU_LOAD_FACTOR = 0.75f
private const val MAX_READER_FETCH_ERROR_CHARS = 240

/** Bounded in-memory LRU cache of extracted article snapshots for on-demand board reading. */
internal class BoardReaderHtmlCache(private val maxEntries: Int = BOARD_READER_LRU_CAPACITY) {
    private val map =
        object :
            LinkedHashMap<String, ExtractedWebPageSnapshot>(maxEntries, LRU_LOAD_FACTOR, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ExtractedWebPageSnapshot>?
            ): Boolean = size > maxEntries
        }

    @Synchronized
    fun get(url: String): ExtractedWebPageSnapshot? {
        val key = canonicalizeSyncUrl(url)
        return if (key.isEmpty()) null else map[key]
    }

    @Synchronized
    fun put(url: String, snapshot: ExtractedWebPageSnapshot) {
        val key = canonicalizeSyncUrl(url)
        if (key.isNotEmpty()) {
            map[key] = snapshot
        }
    }

    @Synchronized
    fun remove(url: String) {
        val key = canonicalizeSyncUrl(url)
        if (key.isNotEmpty()) {
            map.remove(key)
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}

internal fun isExtractableBoardHtmlUrl(url: String): Boolean {
    if (!isHttpOrHttpsUrl(url)) return false
    val path = runCatching {
        URI(url.trim().replace(" ", "%20")).rawPath.orEmpty().lowercase(Locale.ROOT)
    }
        .getOrDefault("")
    val isDirectBinaryPdf = path.endsWith(".pdf") || path.startsWith("/pdf/")
    val contentType = inferContentType(url)
    return when {
        contentType == ContentType.VIDEO || contentType == ContentType.AUDIO -> false
        isDirectBinaryPdf -> false
        contentType == ContentType.ARTICLE -> true
        else -> isArxivPaperUrl(url) && path.startsWith("/abs/")
    }
}

internal suspend fun resolveBoardItemForAction(
    dao: RocheDao,
    adapterRegistry: BoardAdapterRegistry,
    board: BoardEntity,
    externalId: String,
    fallbackItem: BoardItemView?,
): BoardItemView? {
    val state = dao.getBoardItemState(board.id, externalId)
    val dbItem = dao.getBoardItem(board.id, externalId)
    if (dbItem != null) {
        val dedup =
            loadScopedBoardDedupLookup(
                dao,
                listOf(BoardDedupKey(board.adapterType, dbItem.url, dbItem.externalId)),
            )
        return projectBoardItemView(board = board, item = dbItem, state = state, dedup = dedup)
    }
    if (fallbackItem != null) {
        val dedup =
            loadScopedBoardDedupLookup(
                dao,
                listOf(BoardDedupKey(board.adapterType, fallbackItem.url, fallbackItem.externalId)),
            )
        return hydrateBoardItemView(board, fallbackItem, state, dedup)
    }
    val adapter = adapterRegistry.resolve(board.adapterType) ?: return null
    val candidate =
        adapter.fetchSingleItem(board.sourceKey, board.sourceConfigJson, externalId).getOrNull()
            ?: return null
    val dedup =
        loadScopedBoardDedupLookup(
            dao,
            listOf(BoardDedupKey(board.adapterType, candidate.url, candidate.externalId)),
        )
    return projectBoardCandidateView(
        board = board,
        candidate = candidate,
        fetchedAtIso = nowUtcIso(),
        state = state,
        dedup = dedup,
        isEphemeralPageItem = true,
    )
}

private fun hydrateBoardItemView(
    board: BoardEntity,
    item: BoardItemView,
    state: BoardItemStateEntity?,
    dedup: BoardDedupLookup,
): BoardItemView {
    val linkedId = dedup.resolveLinkedEntryId(board.adapterType, item.url, item.externalId)
    return item.copy(
        boardId = board.id,
        seenAt = if (state != null) state.seenAt else item.seenAt,
        dismissedAt = if (state != null) state.dismissedAt else item.dismissedAt,
        linkedEntryId = linkedId,
        isInQueue = linkedId != null && linkedId in dedup.inQueueEntryIds,
        isStarred = linkedId != null && linkedId in dedup.starredEntryIds,
        isFinished = linkedId != null && linkedId in dedup.finishedEntryIds,
    )
}

private data class BoardHtmlFetchResult(
    val snapshot: ExtractedWebPageSnapshot? = null,
    val fetchError: String? = null,
)

@Suppress("LongParameterList")
internal suspend fun loadBoardItemReaderContent(
    dao: RocheDao,
    publicWebFetcher: PublicWebFetcher,
    adapterRegistry: BoardAdapterRegistry,
    readerCache: BoardReaderHtmlCache,
    board: BoardEntity,
    externalId: String,
    fallbackItem: BoardItemView?,
    forceRefresh: Boolean = false,
): BoardReaderContent? {
    val item =
        resolveBoardItemForAction(dao, adapterRegistry, board, externalId, fallbackItem)
            ?: return null
    val discussions = resolveBoardItemDiscussions(dao, board, item)

    if (item.isSelfPost) {
        val html = item.selfTextHtml?.takeIf { it.isNotBlank() }
        return BoardReaderContent(
            boardId = board.id,
            externalId = item.externalId,
            item = item,
            contentHtml = html,
            contentText = item.summary,
            isSelfPost = true,
            extractionFailed = html.isNullOrBlank(),
            linkedEntryId = item.linkedEntryId,
            discussions = discussions,
        )
    }

    val linkedContent = item.linkedEntryId?.let { dao.getEntryContentById(it) }
    val linkedHtml = linkedContent?.contentHtml?.takeIf { it.isNotBlank() }
    if (linkedHtml != null) {
        return BoardReaderContent(
            boardId = board.id,
            externalId = item.externalId,
            item = item,
            contentHtml = linkedHtml,
            contentText = linkedContent.contentText,
            isSelfPost = false,
            extractionFailed = false,
            linkedEntryId = item.linkedEntryId,
            discussions = discussions,
        )
    }

    val fetchResult =
        fetchOrGetCachedSnapshot(
            publicWebFetcher = publicWebFetcher,
            readerCache = readerCache,
            url = item.url,
            forceRefresh = forceRefresh,
        )
    val extracted = fetchResult.snapshot
    return BoardReaderContent(
        boardId = board.id,
        externalId = item.externalId,
        item = item,
        contentHtml = extracted?.contentHtml,
        contentText = null,
        extractedTitle = extracted?.title,
        extractedAuthor = extracted?.author,
        extractedSummary = extracted?.summary,
        extractedThumbnailUrl = extracted?.thumbnailUrl,
        isSelfPost = false,
        extractionFailed = extracted?.contentHtml.isNullOrBlank(),
        fetchError = fetchResult.fetchError,
        linkedEntryId = item.linkedEntryId,
        discussions = discussions,
    )
}

private suspend fun fetchOrGetCachedSnapshot(
    publicWebFetcher: PublicWebFetcher,
    readerCache: BoardReaderHtmlCache,
    url: String,
    forceRefresh: Boolean,
): BoardHtmlFetchResult {
    if (forceRefresh) {
        readerCache.remove(url)
    }
    val extractable = isExtractableBoardHtmlUrl(url)
    val cached = if (extractable) readerCache.get(url) else null
    return when {
        !extractable -> BoardHtmlFetchResult()
        cached != null -> BoardHtmlFetchResult(snapshot = cached)
        else -> fetchAndExtractBoardHtml(publicWebFetcher, readerCache, url)
    }
}

private suspend fun fetchAndExtractBoardHtml(
    publicWebFetcher: PublicWebFetcher,
    readerCache: BoardReaderHtmlCache,
    url: String,
): BoardHtmlFetchResult {
    val fetchResult = publicWebFetcher.fetchHtml(url)
    val failure = fetchResult.exceptionOrNull()
    if (failure != null) {
        return BoardHtmlFetchResult(fetchError = formatBoardReaderFetchError(failure))
    }
    val rawHtml = fetchResult.getOrNull().orEmpty()
    val snapshot = withContext(Dispatchers.Default) { extractWebPageSnapshot(rawHtml, url) }
    if (!snapshot.contentHtml.isNullOrBlank()) {
        readerCache.put(url, snapshot)
    }
    return BoardHtmlFetchResult(snapshot = snapshot)
}

private fun formatBoardReaderFetchError(error: Throwable): String =
    when (error) {
        is HttpStatusException -> {
            "HTTP ${error.statusCode}"
        }
        is ProblemDetailsException -> {
            "HTTP ${error.statusCode}"
        }
        else -> {
            val firstLine =
                error.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.trim()
                    ?.take(MAX_READER_FETCH_ERROR_CHARS)
                    ?.takeIf { it.isNotEmpty() }
            firstLine ?: "Network error while loading article"
        }
    }

private suspend fun resolveBoardItemDiscussions(
    dao: RocheDao,
    board: BoardEntity,
    item: BoardItemView,
): List<DiscussionLink> {
    val itemDiscussion =
        item.discussionUrl?.let(::sanitizeHttpUrl)?.let { cleanUrl ->
            val extId =
                if (board.adapterType == SCHEME_HACKER_NEWS) {
                    extractHackerNewsItemId(item.externalId) ?: item.externalId
                } else {
                    item.externalId
                }
            DiscussionLink(
                source = board.adapterType,
                url = cleanUrl,
                externalId = extId,
                label = externalIdentifierLabel(board.adapterType),
            )
        }
    val linkedDiscussions =
        item.linkedEntryId?.let { entryId ->
            val entry = dao.getEntryById(entryId)
            val hnIdent =
                dao.getIdentifiersForEntry(entryId)
                    .firstOrNull { it.scheme == SCHEME_HACKER_NEWS }
                    ?.value
            extractDiscussionsForEntry(entry?.metadataJson, hnIdent)
        } ?: emptyList()
    return mergeDiscussionLinkLists(linkedDiscussions, listOfNotNull(itemDiscussion))
}
