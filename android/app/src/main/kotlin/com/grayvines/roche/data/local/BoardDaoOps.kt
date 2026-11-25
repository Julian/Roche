@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.local

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.DiscussionLink
import com.grayvines.roche.core.SCHEME_HACKER_NEWS
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_ENTRY
import com.grayvines.roche.core.TOMBSTONE_ENTITY_ENTRY
import com.grayvines.roche.core.TOMBSTONE_ENTITY_ITEM
import com.grayvines.roche.core.TOMBSTONE_TYPE_BOARD
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.boardNaturalKey
import com.grayvines.roche.core.canonicalArxivAbstractUrl
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.collectionEntryNaturalKey
import com.grayvines.roche.core.deterministicBoardId
import com.grayvines.roche.core.deterministicItemId
import com.grayvines.roche.core.encodeCreatorsJson
import com.grayvines.roche.core.externalIdentifierLabel
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.extractHackerNewsItemId
import com.grayvines.roche.core.extractYearPrefix
import com.grayvines.roche.core.extractYoutubeVideoId
import com.grayvines.roche.core.inferContentTypeWireValue
import com.grayvines.roche.core.isArxivPaperUrl
import com.grayvines.roche.core.isSyncableItemUrl
import com.grayvines.roche.core.itemNaturalKey
import com.grayvines.roche.core.mergeDiscussionIntoMetadata
import com.grayvines.roche.core.newerIsoTimestamp
import com.grayvines.roche.core.normalizeTagName
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.core.resolveArticleDuration
import com.grayvines.roche.core.sanitizeHttpUrl
import com.grayvines.roche.data.boards.BOARD_STATE_GRACE_HOURS
import com.grayvines.roche.data.boards.BoardAdapterType
import com.grayvines.roche.data.boards.BoardItemView
import com.grayvines.roche.data.boards.BoardOrderMode
import com.grayvines.roche.data.boards.BoardPromoteAction
import com.grayvines.roche.data.boards.BoardPromoteResult
import com.grayvines.roche.data.boards.isExtractableBoardHtmlUrl
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.addCollectionMemberInDao
import com.grayvines.roche.data.catalog.appendConsumptionInDao
import com.grayvines.roche.data.catalog.followMergedIdInDao
import com.grayvines.roche.data.catalog.logFeedItemConsumedInDao
import com.grayvines.roche.model.ContentType
import java.time.Instant
import java.time.temporal.ChronoUnit

private val CONTAINER_ENTRY_KINDS =
    setOf("publication", "podcast", "channel", "collection", "artist")
private const val SCHEME_URL = "url"
private const val SCHEME_ARXIV = "arxiv"
private const val INBOX_STATE_NONE = "none"
private const val INBOX_STATE_UNREAD = "unread"
private const val INBOX_STATE_DISMISSED = "dismissed"

internal data class BoardPromoteCommitResult(
    val result: BoardPromoteResult,
    val needsHtmlEnrichment: Boolean,
)

@Suppress("LongParameterList")
internal data class BoardPromoteParams(
    val board: BoardEntity,
    val item: BoardItemView,
    val action: BoardPromoteAction,
    val extractedHtml: String? = null,
    val extractedSummary: String? = null,
    val extractedAuthor: String? = null,
    val extractedThumbnailUrl: String? = null,
    val notes: String? = null,
    val tagName: String? = null,
    val listId: String? = null,
    val nodeId: String = DEFAULT_LOCAL_HLC_NODE,
)

@Suppress("LongParameterList")
internal suspend fun RocheDao.createOrReactivateBoardInTx(
    adapterType: BoardAdapterType,
    sourceKey: String,
    sourceUrl: String,
    sourceConfigJson: String,
    title: String?,
    defaultTitle: String,
    orderMode: String,
    pollInterval: Int,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
): BoardEntity {
    require(pollInterval > 0) { "Poll interval must be positive" }
    val explicitTitle = title?.trim()?.takeIf { it.isNotEmpty() }
    val normAdapter = adapterType.wireValue
    val normSourceKey = sourceKey.trim().lowercase()
    val natKey = boardNaturalKey(normAdapter, normSourceKey)
    val detId = deterministicBoardId(normAdapter, normSourceKey)
    val nowIso = nowUtcIso()
    val hlc = nextLocalHlc(this, nodeId)
    val existing = getBoardBySourceKey(normAdapter, normSourceKey) ?: getBoardById(detId)
    val resolvedTitle =
        explicitTitle ?: existing?.title?.takeIf { it.isNotBlank() } ?: defaultTitle.trim()
    require(resolvedTitle.isNotEmpty()) { "Board title cannot be empty" }
    val targetId = existing?.id ?: detId
    deleteTombstone(TOMBSTONE_TYPE_BOARD, natKey)
    deleteTombstone(TOMBSTONE_TYPE_BOARD, targetId)
    val entity =
        buildBoardEntityForCreate(
            existing = existing,
            targetId = targetId,
            adapterType = normAdapter,
            sourceKey = normSourceKey,
            sourceUrl = sourceUrl,
            sourceConfigJson = sourceConfigJson,
            title = resolvedTitle,
            orderMode = orderMode,
            pollInterval = pollInterval,
            nowIso = nowIso,
            hlc = hlc,
        )
    upsertBoard(entity)
    return entity
}

@Suppress("LongParameterList")
private fun buildBoardEntityForCreate(
    existing: BoardEntity?,
    targetId: String,
    adapterType: String,
    sourceKey: String,
    sourceUrl: String,
    sourceConfigJson: String,
    title: String,
    orderMode: String,
    pollInterval: Int,
    nowIso: String,
    hlc: String,
): BoardEntity =
    if (existing != null) {
        existing.copy(
            sourceUrl = sourceUrl,
            sourceConfigJson = sourceConfigJson,
            title = title,
            orderMode = orderMode,
            pollInterval = pollInterval,
            isActive = true,
            lastError = null,
            lastErrorBy = null,
            consecutiveFailures = 0,
            updatedAt = nowIso,
            hlc = hlc,
            isLocalDirty = true,
        )
    } else {
        BoardEntity(
            id = targetId,
            adapterType = adapterType,
            sourceKey = sourceKey,
            sourceUrl = sourceUrl,
            sourceConfigJson = sourceConfigJson,
            title = title,
            orderMode = orderMode,
            pollInterval = pollInterval,
            isActive = true,
            createdAt = nowIso,
            updatedAt = nowIso,
            hlc = hlc,
            isLocalDirty = true,
        )
    }

@Suppress("LongParameterList")
internal suspend fun RocheDao.updateBoardConfigInTx(
    boardId: String,
    title: String? = null,
    pollInterval: Int? = null,
    isActive: Boolean? = null,
    orderMode: BoardOrderMode? = null,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
): BoardEntity {
    val existing = getBoardById(boardId) ?: error("Board not found: $boardId")
    val nextTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: existing.title
    val nextInterval = pollInterval ?: existing.pollInterval
    require(nextInterval > 0) { "Poll interval must be positive" }
    val nextActive = isActive ?: existing.isActive
    val reactivating = !existing.isActive && nextActive
    val nowIso = nowUtcIso()
    val hlc = nextLocalHlc(this, nodeId)
    val updated =
        existing.copy(
            title = nextTitle,
            orderMode = orderMode?.wireValue ?: existing.orderMode,
            pollInterval = nextInterval,
            isActive = nextActive,
            lastError = if (reactivating) null else existing.lastError,
            lastErrorBy = if (reactivating) null else existing.lastErrorBy,
            consecutiveFailures = if (reactivating) 0 else existing.consecutiveFailures,
            updatedAt = nowIso,
            hlc = hlc,
            isLocalDirty = true,
        )
    upsertBoard(updated)
    return updated
}

internal suspend fun RocheDao.deleteBoardWithTombstonesInTx(
    boardId: String,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
): String? {
    val existing = getBoardById(boardId) ?: return null
    val nowIso = nowUtcIso()
    val hlc = nextLocalHlc(this, nodeId)
    val natKey = boardNaturalKey(existing.adapterType, existing.sourceKey)
    upsertTombstone(
        TombstoneEntity(
            entityType = TOMBSTONE_TYPE_BOARD,
            naturalKey = natKey,
            deletedAt = nowIso,
            hlc = hlc,
            isLocalDirty = true,
        )
    )
    upsertTombstone(
        TombstoneEntity(
            entityType = TOMBSTONE_TYPE_BOARD,
            naturalKey = existing.id,
            deletedAt = nowIso,
            hlc = hlc,
            isLocalDirty = true,
        )
    )
    deleteBoardById(existing.id)
    return existing.title
}

internal suspend fun RocheDao.markBoardItemSeenInTx(
    boardId: String,
    externalId: String,
    itemFetchedAt: String? = null,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
): Boolean {
    if (getBoardById(boardId) == null) return false
    val existing = getBoardItemState(boardId, externalId)
    if (!existing?.seenAt.isNullOrBlank()) return false
    val nowIso = nowUtcIso()
    val hlc = nextLocalHlc(this, nodeId)
    val dbItemFetchedAt = itemFetchedAt ?: getBoardItem(boardId, externalId)?.fetchedAt
    val lastSeen =
        newerIsoTimestamp(existing?.lastSeenInSnapshotAt, dbItemFetchedAt)
            ?: existing?.lastSeenInSnapshotAt
            ?: nowIso
    upsertBoardItemState(
        BoardItemStateEntity(
            boardId = boardId,
            externalId = externalId,
            seenAt = nowIso,
            dismissedAt = existing?.dismissedAt,
            lastSeenInSnapshotAt = lastSeen,
            updatedAt = nowIso,
            hlc = hlc,
            isLocalDirty = true,
        )
    )
    return true
}

internal suspend fun RocheDao.setBoardItemDismissedInTx(
    boardId: String,
    externalId: String,
    dismissed: Boolean,
    itemFetchedAt: String? = null,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
): Boolean {
    if (getBoardById(boardId) == null) return false
    val existing = getBoardItemState(boardId, externalId)
    val currentlyDismissed = !existing?.dismissedAt.isNullOrBlank()
    if (currentlyDismissed == dismissed) return false
    val nowIso = nowUtcIso()
    val hlc = nextLocalHlc(this, nodeId)
    val dbItemFetchedAt = itemFetchedAt ?: getBoardItem(boardId, externalId)?.fetchedAt
    val lastSeen =
        newerIsoTimestamp(existing?.lastSeenInSnapshotAt, dbItemFetchedAt)
            ?: existing?.lastSeenInSnapshotAt
            ?: nowIso
    upsertBoardItemState(
        BoardItemStateEntity(
            boardId = boardId,
            externalId = externalId,
            seenAt = if (dismissed) existing?.seenAt ?: nowIso else existing?.seenAt,
            dismissedAt = if (dismissed) nowIso else null,
            lastSeenInSnapshotAt = lastSeen,
            updatedAt = nowIso,
            hlc = hlc,
            isLocalDirty = true,
        )
    )
    return true
}

internal suspend fun pruneExpiredBoardStatesInDao(
    dao: RocheDao,
    nowInstant: Instant = Instant.now(),
): Int {
    val cutoffIso = nowInstant.minus(BOARD_STATE_GRACE_HOURS, ChronoUnit.HOURS).toString()
    return dao.pruneExpiredBoardItemStates(cutoffIso)
}

internal suspend fun resolveDedupEntryIdInDao(
    dao: RocheDao,
    url: String,
    hackerNewsExternalId: String? = null,
): String? {
    val cleanUrl = url.trim()
    val targetUrl = canonicalArxivAbstractUrl(cleanUrl) ?: cleanUrl
    val arxivId = targetUrl.takeIf { it.isNotEmpty() }?.let(::extractArxivIdentifier)
    val canonUrl = canonicalizeSyncUrl(targetUrl)
    val hnDigits = hackerNewsExternalId?.let(::extractHackerNewsItemId)
    val byArxiv = lookupActiveByIdent(dao, SCHEME_ARXIV, arxivId)
    val byUrl = byArxiv ?: lookupActiveByIdent(dao, SCHEME_URL, canonUrl)
    val byHn = byUrl ?: lookupActiveByIdent(dao, SCHEME_HACKER_NEWS, hnDigits)
    return byHn
        ?: canonUrl
            .takeIf { it.isNotEmpty() }
            ?.let { resolveActiveEntryId(dao, deterministicItemId(null, targetUrl)) }
}

private suspend fun lookupActiveByIdent(
    dao: RocheDao,
    scheme: String,
    value: String?,
): String? =
    value
        ?.takeIf { it.isNotEmpty() }
        ?.let { resolveActiveEntryId(dao, dao.getEntryIdByIdentifier(scheme, it)) }

private suspend fun resolveActiveEntryId(
    dao: RocheDao,
    candidateId: String?,
): String? {
    val raw = candidateId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val resolvedId = followMergedIdInDao(dao, raw) ?: return null
    val entry = dao.getEntryById(resolvedId) ?: return null
    return resolvedId.takeIf { entry.kind !in CONTAINER_ENTRY_KINDS }
}

internal suspend fun RocheDao.promoteBoardItemInTx(
    params: BoardPromoteParams
): BoardPromoteCommitResult {
    val item = params.item
    val board = params.board
    val nowIso = nowUtcIso()
    val hlc = nextLocalHlc(this, params.nodeId)
    val isSyncable = isSyncableItemUrl(item.url)
    val hnDigits =
        if (board.adapterType == SCHEME_HACKER_NEWS) {
            extractHackerNewsItemId(item.externalId)
        } else {
            null
        }
    val discussionLink = buildBoardDiscussionLink(board.adapterType, item, hnDigits)
    val existingId = resolveDedupEntryIdInDao(this, item.url, hnDigits)
    val existingEntry = existingId?.let { getEntryById(it) }

    val (entryId, entryTitle, needsEnrichment) =
        if (existingEntry != null) {
            updatePromotedEntryInTx(
                existing = existingEntry,
                params = params,
                discussionLink = discussionLink,
                hnDigits = hnDigits,
                nowIso = nowIso,
                hlc = hlc,
                isSyncable = isSyncable,
            )
        } else {
            createNewPromotedEntryInTx(
                params = params,
                discussionLink = discussionLink,
                hnDigits = hnDigits,
                nowIso = nowIso,
                hlc = hlc,
                isSyncable = isSyncable,
            )
        }

    applyBoardPromoteActionInTx(
        entryId = entryId,
        params = params,
        nowIso = nowIso,
        hlc = hlc,
        isSyncable = isSyncable,
    )
    markBoardItemSeenInTx(
        boardId = board.id,
        externalId = item.externalId,
        itemFetchedAt = item.fetchedAt.takeIf { it.isNotBlank() },
        nodeId = params.nodeId,
    )
    return BoardPromoteCommitResult(
        result =
            BoardPromoteResult(
                entryId = entryId,
                action = params.action,
                title = entryTitle,
                wasExistingEntry = existingEntry != null,
            ),
        needsHtmlEnrichment = needsEnrichment,
    )
}

private fun buildBoardDiscussionLink(
    adapterType: String,
    item: BoardItemView,
    hnDigits: String?,
): DiscussionLink? {
    val discussionUrl = item.discussionUrl?.trim()?.let(::sanitizeHttpUrl) ?: return null
    return DiscussionLink(
        source = adapterType,
        url = discussionUrl,
        externalId = hnDigits ?: item.externalId.trim().takeIf { it.isNotEmpty() },
        label = externalIdentifierLabel(adapterType),
    )
}

@Suppress("LongParameterList")
private suspend fun RocheDao.updatePromotedEntryInTx(
    existing: EntryEntity,
    params: BoardPromoteParams,
    discussionLink: DiscussionLink?,
    hnDigits: String?,
    nowIso: String,
    hlc: String,
    isSyncable: Boolean,
): Triple<String, String, Boolean> {
    val targetUrl = canonicalArxivAbstractUrl(params.item.url) ?: params.item.url
    val mergedMetadata =
        if (discussionLink != null) {
            mergeDiscussionIntoMetadata(existing.metadataJson, discussionLink)
        } else {
            existing.metadataJson
        }
    val nextSummary =
        existing.summary?.takeIf { it.isNotBlank() }
            ?: (params.extractedSummary ?: params.item.summary)?.take(MAX_SQLITE_SUMMARY_CHARS)
    val nextThumb =
        existing.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: sanitizeHttpUrl(params.extractedThumbnailUrl)
    val updated =
        existing.copy(
            summary = nextSummary,
            thumbnailUrl = nextThumb,
            metadataJson = mergedMetadata,
            updatedAt = nowIso,
            hlc = hlc,
            isLocalDirty = isSyncable || existing.isLocalDirty,
        )
    deleteTombstone(TOMBSTONE_ENTITY_ENTRY, existing.id)
    upsertEntry(updated)
    attachPromotedIdentsInTx(existing.id, targetUrl, hnDigits)
    val htmlCandidate =
        (params.extractedHtml ?: params.item.selfTextHtml)
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_SQLITE_HTML_CHARS)
    val existingContent = getEntryContentById(existing.id)
    val needsEnrichment =
        upsertPromotedContentInTx(
            entryId = existing.id,
            existingContent = existingContent,
            params = params,
            targetUrl = targetUrl,
            htmlCandidate = htmlCandidate,
            summary = nextSummary,
        )
    return Triple(existing.id, updated.title, needsEnrichment)
}

@Suppress("LongParameterList")
private suspend fun RocheDao.createNewPromotedEntryInTx(
    params: BoardPromoteParams,
    discussionLink: DiscussionLink?,
    hnDigits: String?,
    nowIso: String,
    hlc: String,
    isSyncable: Boolean,
): Triple<String, String, Boolean> {
    val item = params.item
    val targetUrl = canonicalArxivAbstractUrl(item.url) ?: item.url
    val entry = buildNewPromotedEntry(params, targetUrl, discussionLink, nowIso, hlc, isSyncable)
    deleteTombstone(TOMBSTONE_ENTITY_ITEM, itemNaturalKey(null, targetUrl))
    if (targetUrl != item.url) {
        deleteTombstone(TOMBSTONE_ENTITY_ITEM, itemNaturalKey(null, item.url))
    }
    deleteTombstone(TOMBSTONE_ENTITY_ENTRY, entry.id)
    upsertEntry(entry)
    attachPromotedIdentsInTx(entry.id, targetUrl, hnDigits)
    val htmlCandidate =
        (params.extractedHtml ?: item.selfTextHtml)
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_SQLITE_HTML_CHARS)
    val needsEnrichment =
        upsertPromotedContentInTx(
            entryId = entry.id,
            existingContent = null,
            params = params,
            targetUrl = targetUrl,
            htmlCandidate = htmlCandidate,
            summary = entry.summary,
        )
    // Promoted board items NEVER enter Feed Inbox (`inboxState = "none"`).
    val isFinish = params.action == BoardPromoteAction.FINISH
    upsertEntryState(
        EntryStateEntity(
            entryId = entry.id,
            inboxState = INBOX_STATE_NONE,
            readAt = if (isFinish) nowIso else null,
            progress = if (isFinish) 1.0 else 0.0,
            interactionUpdatedAt = nowIso,
            updatedAt = nowIso,
            isCachedOffline = false,
            hlc = hlc,
            isLocalDirty = isSyncable,
        )
    )
    return Triple(entry.id, entry.title, needsEnrichment)
}

@Suppress("LongParameterList")
private fun buildNewPromotedEntry(
    params: BoardPromoteParams,
    targetUrl: String,
    discussionLink: DiscussionLink?,
    nowIso: String,
    hlc: String,
    isSyncable: Boolean,
): EntryEntity {
    val item = params.item
    val entryKind = classifyBoardItemEntryKind(targetUrl)
    val creators =
        listOfNotNull(params.extractedAuthor?.trim(), item.author?.trim())
            .firstOrNull { it.isNotEmpty() }
            ?.let(::listOf)
            .orEmpty()
    val entryId = deterministicItemId(null, targetUrl)
    val metadataJson =
        if (discussionLink != null) mergeDiscussionIntoMetadata("{}", discussionLink) else "{}"
    val summary =
        (params.extractedSummary ?: item.summary)
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_SQLITE_SUMMARY_CHARS)
    return EntryEntity(
        id = entryId,
        kind = entryKind.wireValue,
        title = item.title.trim().ifEmpty { targetUrl },
        creatorsJson = encodeCreatorsJson(creators),
        url = targetUrl,
        publishedAt = item.publishedAt,
        releaseYear = extractYearPrefix(item.publishedAt),
        summary = summary,
        thumbnailUrl = sanitizeHttpUrl(params.extractedThumbnailUrl),
        metadataJson = metadataJson,
        mergedIntoId = null,
        createdAt = nowIso,
        updatedAt = nowIso,
        hlc = hlc,
        isLocalDirty = isSyncable,
    )
}

private suspend fun RocheDao.attachPromotedIdentsInTx(
    entryId: String,
    url: String,
    hnDigits: String?,
) {
    val rows = buildList {
        val canonUrl = canonicalizeSyncUrl(url)
        if (canonUrl.isNotEmpty()) {
            add(EntryIdentifierEntity(SCHEME_URL, canonUrl, entryId))
        }
        extractArxivIdentifier(url)?.let { arxiv ->
            add(EntryIdentifierEntity(SCHEME_ARXIV, arxiv, entryId))
        }
        if (hnDigits != null) {
            add(EntryIdentifierEntity(SCHEME_HACKER_NEWS, hnDigits, entryId))
        }
    }
    if (rows.isNotEmpty()) {
        insertEntryIdentifiersIgnore(rows)
    }
}

@Suppress("LongParameterList")
private suspend fun RocheDao.upsertPromotedContentInTx(
    entryId: String,
    existingContent: EntryContentEntity?,
    params: BoardPromoteParams,
    targetUrl: String,
    htmlCandidate: String?,
    summary: String?,
): Boolean {
    val contentType = existingContent?.contentType ?: inferContentTypeWireValue(targetUrl)
    val effectiveHtml = existingContent?.contentHtml?.takeIf { it.isNotBlank() } ?: htmlCandidate
    val duration =
        resolveArticleDuration(
            existingDuration = existingContent?.duration,
            isArticle = ContentType.fromWireValue(contentType) == ContentType.ARTICLE,
            hasExtendedContent =
                effectiveHtml.orEmpty().length > LIST_PREVIEW_HTML_CHARS ||
                    summary.orEmpty().length > LIST_PREVIEW_SUMMARY_CHARS,
            fallbackTextSource = existingContent?.contentText ?: effectiveHtml ?: summary.orEmpty(),
        )
    val fallbackTitle =
        existingContent?.fallbackFeedTitle
            ?: params.item.domain?.takeIf { it.isNotBlank() }
            ?: params.board.title
    if (existingContent == null || effectiveHtml != existingContent.contentHtml) {
        upsertEntryContent(
            EntryContentEntity(
                entryId = entryId,
                contentType = contentType,
                contentText = existingContent?.contentText,
                contentHtml = effectiveHtml,
                audioUrl = existingContent?.audioUrl,
                duration = duration,
                thumbnailMediaId = existingContent?.thumbnailMediaId,
                fallbackFeedTitle = fallbackTitle,
            )
        )
    }
    return effectiveHtml.isNullOrBlank() &&
        !params.item.isSelfPost &&
        isExtractableBoardHtmlUrl(targetUrl)
}

@Suppress("LongParameterList")
private suspend fun RocheDao.applyBoardPromoteActionInTx(
    entryId: String,
    params: BoardPromoteParams,
    nowIso: String,
    hlc: String,
    isSyncable: Boolean,
) {
    when (params.action) {
        BoardPromoteAction.QUEUE -> {
            ensureWantToReadSpecExists(markDirty = isSyncable, nodeId = params.nodeId)
            addBoardSystemCollectionInTx(
                WANT_TO_READ_COLLECTION_ID,
                entryId,
                nowIso,
                hlc,
                isSyncable,
            )
        }
        BoardPromoteAction.STAR -> {
            ensureStarredSpecExists()
            addBoardSystemCollectionInTx(STARRED_COLLECTION_ID, entryId, nowIso, hlc, isSyncable)
        }
        BoardPromoteAction.FINISH -> {
            finishBoardPromotedEntryInTx(entryId, params, nowIso, hlc, isSyncable)
        }
        BoardPromoteAction.TAG -> {
            val cleanTag = normalizeTagName(params.tagName.orEmpty())
            require(cleanTag.isNotEmpty()) { "Tag name cannot be empty" }
            attachTagsLocally(this, entryId, listOf(cleanTag), isSyncable, params.nodeId)
        }
        BoardPromoteAction.LIST -> {
            addBoardPromotedToListInTx(entryId, params, nowIso, isSyncable)
        }
    }
}

private suspend fun RocheDao.addBoardSystemCollectionInTx(
    collectionId: String,
    entryId: String,
    nowIso: String,
    hlc: String,
    isSyncable: Boolean,
) {
    deleteTombstone(
        TOMBSTONE_ENTITY_COLLECTION_ENTRY,
        collectionEntryNaturalKey(collectionId, entryId),
    )
    upsertCollectionEntry(
        CollectionEntryEntity(
            collectionId = collectionId,
            entryId = entryId,
            position = null,
            addedAt = nowIso,
            hlc = hlc,
            isLocalDirty = isSyncable,
        )
    )
}

@Suppress("LongParameterList")
private suspend fun RocheDao.finishBoardPromotedEntryInTx(
    entryId: String,
    params: BoardPromoteParams,
    nowIso: String,
    hlc: String,
    isSyncable: Boolean,
) {
    val existingState = getEntryStateById(entryId)
    val nextInboxState =
        if (existingState?.inboxState == INBOX_STATE_UNREAD) {
            INBOX_STATE_DISMISSED
        } else {
            existingState?.inboxState ?: INBOX_STATE_NONE
        }
    upsertEntryState(
        EntryStateEntity(
            entryId = entryId,
            inboxState = nextInboxState,
            readAt = existingState?.readAt?.takeIf { it.isNotBlank() } ?: nowIso,
            progress = 1.0,
            interactionUpdatedAt = nowIso,
            updatedAt = nowIso,
            isCachedOffline = existingState?.isCachedOffline ?: false,
            hlc = hlc,
            isLocalDirty = isSyncable || existingState?.isLocalDirty == true,
        )
    )
    removeWantMembershipInTx(entryId, nowIso, hlc, isSyncable)
    val cleanNotes = params.notes?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanNotes != null) {
        appendConsumptionInDao(
            dao = this,
            entryId = entryId,
            status = ConsumptionStatus.CONSUMED,
            notes = cleanNotes,
            tsIso = nowIso,
            nowIso = nowIso,
            markDirty = isSyncable,
            nodeId = params.nodeId,
        )
    } else {
        logFeedItemConsumedInDao(this, entryId, nowIso, params.nodeId)
    }
}

private suspend fun RocheDao.removeWantMembershipInTx(
    entryId: String,
    nowIso: String,
    hlc: String,
    isSyncable: Boolean,
) {
    if (hasCollectionMembership(WANT_TO_READ_COLLECTION_ID, entryId) <= 0) return
    deleteCollectionEntry(WANT_TO_READ_COLLECTION_ID, entryId)
    if (isSyncable) {
        upsertTombstone(
            TombstoneEntity(
                entityType = TOMBSTONE_ENTITY_COLLECTION_ENTRY,
                naturalKey = collectionEntryNaturalKey(WANT_TO_READ_COLLECTION_ID, entryId),
                deletedAt = nowIso,
                hlc = hlc,
                isLocalDirty = true,
            )
        )
    }
}

private suspend fun RocheDao.addBoardPromotedToListInTx(
    entryId: String,
    params: BoardPromoteParams,
    nowIso: String,
    isSyncable: Boolean,
) {
    val targetListId = params.listId?.trim().orEmpty()
    require(targetListId.isNotEmpty()) { "List ID cannot be empty" }
    val spec =
        getCollectionSpecById(targetListId)
            ?: throw IllegalArgumentException("List not found: $targetListId")
    addCollectionMemberInDao(
        dao = this,
        spec = spec,
        entryId = entryId,
        position = null,
        nowIso = nowIso,
        markDirty = isSyncable,
        nodeId = params.nodeId,
    )
}

internal fun classifyBoardItemEntryKind(url: String): EntryKind =
    when {
        isArxivPaperUrl(url) -> EntryKind.PAPER
        extractYoutubeVideoId(url) != null -> EntryKind.VIDEO
        else -> EntryKind.ARTICLE
    }
