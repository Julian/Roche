@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.local

import com.grayvines.roche.data.COLLECTION_ENTRY_ENTITY
import com.grayvines.roche.data.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.data.EntryKind
import com.grayvines.roche.data.HlcTimestamp
import com.grayvines.roche.data.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.MAX_SQLITE_SUMMARY_CHARS
import com.grayvines.roche.data.MAX_SQLITE_TEXT_CHARS
import com.grayvines.roche.data.STARRED_COLLECTION_ID
import com.grayvines.roche.data.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.data.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.WANT_TO_READ_SLUG
import com.grayvines.roche.data.WANT_TO_READ_TITLE
import com.grayvines.roche.data.canonicalizeSyncUrl
import com.grayvines.roche.data.collectionEntryNaturalKey
import com.grayvines.roche.data.encodeCreatorsJson
import com.grayvines.roche.data.extractArxivIdentifier
import com.grayvines.roche.data.extractYearPrefix
import com.grayvines.roche.data.isSyncableItemUrl
import com.grayvines.roche.data.nextLocalHlc
import com.grayvines.roche.data.normalizeTagName
import com.grayvines.roche.data.nowUtcIso
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.formatArticleMinutes

internal const val SQLITE_DELETE_CHUNK_SIZE = 500
internal const val LIST_PREVIEW_SUMMARY_CHARS = 1_024
internal const val LIST_PREVIEW_TEXT_CHARS = 2_048
internal const val LIST_PREVIEW_HTML_CHARS = 2_048
internal const val MAX_FTS_QUERY_TOKENS = 16

private const val SEED_EPOCH_ISO = "1970-01-01T00:00:00Z"
private const val FTS_SEARCHABLE_COLUMNS_PREFIX =
    "{title creatorsJson url summary contentText contentHtml fallbackFeedTitle} : "
private val SYNTHETIC_ARTICLE_DURATION_REGEX = Regex("""^\d+m$""")

internal fun truncateInboxItemForStorage(item: InboxItemRow): InboxItemRow {
    val boundedSummary = item.summary?.take(MAX_SQLITE_SUMMARY_CHARS)
    val boundedText = item.contentText?.take(MAX_SQLITE_TEXT_CHARS)
    val boundedHtml = item.contentHtml?.take(MAX_SQLITE_HTML_CHARS)
    val resolvedDuration =
        resolveStoredArticleDuration(item, boundedSummary, boundedText, boundedHtml)
    val unchanged =
        boundedSummary == item.summary &&
            boundedText == item.contentText &&
            boundedHtml == item.contentHtml &&
            resolvedDuration == item.duration
    return if (unchanged) {
        item
    } else {
        item.copy(
            summary = boundedSummary,
            contentText = boundedText,
            contentHtml = boundedHtml,
            duration = resolvedDuration,
        )
    }
}

private fun resolveStoredArticleDuration(
    item: InboxItemRow,
    boundedSummary: String?,
    boundedText: String?,
    boundedHtml: String?,
): String? {
    if (ContentType.fromWireValue(item.contentType) != ContentType.ARTICLE) {
        return item.duration
    }
    val existing = item.duration?.trim()?.takeIf { it.isNotEmpty() }
    val hasExplicitUpstreamDuration =
        existing != null && !SYNTHETIC_ARTICLE_DURATION_REGEX.matches(existing)
    if (hasExplicitUpstreamDuration) {
        return item.duration
    }
    if (!hasExtendedItemContent(item)) {
        return null
    }
    val textSource = boundedText ?: boundedHtml ?: boundedSummary.orEmpty()
    return if (textSource.isNotBlank()) formatArticleMinutes(textSource) else null
}

internal fun hasExtendedItemContent(item: InboxItemRow): Boolean =
    item.summary.orEmpty().length > LIST_PREVIEW_SUMMARY_CHARS ||
        item.contentText.orEmpty().length > LIST_PREVIEW_TEXT_CHARS ||
        item.contentHtml.orEmpty().length > LIST_PREVIEW_HTML_CHARS

internal fun extractFtsTokenQueries(rawQuery: String): List<String> {
    val sanitized = rawQuery.replace("\u0000", "").trim()
    if (sanitized.isBlank()) return emptyList()
    val tokens = ArrayList<String>()
    val current = StringBuilder()
    var hasCoreAlphaNum = false
    var index = 0
    while (index < sanitized.length) {
        val cp = sanitized.codePointAt(index)
        if (isFtsTokenCodePoint(cp)) {
            current.appendCodePoint(cp)
            hasCoreAlphaNum = hasCoreAlphaNum || isCoreFtsAlphaNum(cp)
        } else {
            flushFtsToken(current, hasCoreAlphaNum, tokens)
            hasCoreAlphaNum = false
        }
        index += Character.charCount(cp)
    }
    flushFtsToken(current, hasCoreAlphaNum, tokens)
    return tokens.take(MAX_FTS_QUERY_TOKENS).map { token ->
        val escaped = token.replace("\"", "\"\"")
        "\"$escaped\"*"
    }
}

internal fun buildFts5MatchQuery(rawQuery: String): String? {
    val tokenQueries = extractFtsTokenQueries(rawQuery)
    return if (tokenQueries.isEmpty()) {
        null
    } else {
        "$FTS_SEARCHABLE_COLUMNS_PREFIX(${tokenQueries.joinToString(separator = " ")})"
    }
}

private fun flushFtsToken(
    current: StringBuilder,
    hasCoreAlphaNum: Boolean,
    tokens: MutableList<String>,
) {
    if (current.isNotEmpty()) {
        if (hasCoreAlphaNum) {
            tokens.add(current.toString())
        }
        current.setLength(0)
    }
}

private fun isCoreFtsAlphaNum(cp: Int): Boolean =
    Character.isLetterOrDigit(cp) ||
        when (Character.getType(cp).toByte()) {
            Character.LETTER_NUMBER,
            Character.OTHER_NUMBER -> true
            else -> false
        }

private fun isFtsTokenCodePoint(cp: Int): Boolean =
    isCoreFtsAlphaNum(cp) ||
        when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK,
            Character.COMBINING_SPACING_MARK,
            Character.ENCLOSING_MARK -> true
            else -> false
        }

internal fun buildSqlContainsPattern(rawQuery: String): String {
    val sanitized = rawQuery.replace("\u0000", "").trim()
    return buildString(sanitized.length + 2) {
        append('%')
        for (ch in sanitized) {
            if (ch == '\\' || ch == '%' || ch == '_') {
                append('\\')
            }
            append(ch)
        }
        append('%')
    }
}

internal suspend fun RocheDao.executeMultiTokenSearch(
    tokenQueries: List<String>,
    likePattern: String,
): List<InboxItemRow> {
    val orderedTokens = tokenQueries.distinct().sortedByDescending { it.length }
    val matchedIds = matchItemIdsForFtsToken(orderedTokens.first()).toMutableSet()
    for (index in 1 until orderedTokens.size) {
        if (matchedIds.isEmpty()) break
        matchedIds.retainAll(matchItemIdsForFtsToken(orderedTokens[index]).toHashSet())
    }
    matchedIds.addAll(matchItemIdsByTagLike(likePattern))
    if (matchedIds.isEmpty()) return emptyList()
    val chunks = matchedIds.chunked(SQLITE_DELETE_CHUNK_SIZE)
    return if (chunks.size == 1) {
        getSearchItemsByIdsChunk(chunks.single())
    } else {
        chunks
            .flatMap { chunk -> getSearchItemsByIdsChunk(chunk) }
            .sortedByDescending { it.publishedAt ?: it.createdAt }
    }
}

internal suspend fun RocheDao.ensureStarredSpecExists() {
    val existingSpec = getCollectionSpecById(STARRED_COLLECTION_ID)
    if (existingSpec != null && existingSpec.showInLists && existingSpec.role == "list") return
    val seedHlc = HlcTimestamp.fromIsoOrEpoch(SEED_EPOCH_ISO, DEFAULT_LOCAL_HLC_NODE).encode()
    if (getEntryById(STARRED_COLLECTION_ID) == null) {
        upsertEntry(
            EntryEntity(
                id = STARRED_COLLECTION_ID,
                kind = EntryKind.COLLECTION.wireValue,
                title = "Starred",
                createdAt = SEED_EPOCH_ISO,
                updatedAt = SEED_EPOCH_ISO,
                hlc = seedHlc,
                isLocalDirty = false,
            )
        )
    }
    upsertCollectionSpec(
        CollectionSpecEntity(
            collectionId = STARRED_COLLECTION_ID,
            slug = "starred",
            mode = MODE_MANUAL,
            role = "list",
            ruleJson = null,
            showInLists = true,
            color = existingSpec?.color,
        )
    )
    insertEntryIdentifierIgnore(EntryIdentifierEntity("slug", "starred", STARRED_COLLECTION_ID))
}

internal suspend fun RocheDao.ensureWantToReadSpecExists(
    markDirty: Boolean = true,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    if (
        getCollectionSpecById(WANT_TO_READ_COLLECTION_ID) != null &&
            getEntryById(WANT_TO_READ_COLLECTION_ID) != null
    ) {
        return
    }
    val nowIso = nowUtcIso()
    val hlc =
        if (markDirty) {
            nextLocalHlc(this, nodeId)
        } else {
            HlcTimestamp.fromIsoOrEpoch(SEED_EPOCH_ISO, DEFAULT_LOCAL_HLC_NODE).encode()
        }
    if (getEntryById(WANT_TO_READ_COLLECTION_ID) == null) {
        upsertEntry(
            EntryEntity(
                id = WANT_TO_READ_COLLECTION_ID,
                kind = EntryKind.COLLECTION.wireValue,
                title = WANT_TO_READ_TITLE,
                createdAt = nowIso,
                updatedAt = nowIso,
                hlc = hlc,
                isLocalDirty = markDirty,
            )
        )
    }
    upsertCollectionSpec(
        CollectionSpecEntity(
            collectionId = WANT_TO_READ_COLLECTION_ID,
            slug = WANT_TO_READ_SLUG,
            mode = MODE_MANUAL,
            role = "want",
            ruleJson = null,
            showInLists = false,
            color = null,
        )
    )
    insertEntryIdentifierIgnore(
        EntryIdentifierEntity("slug", WANT_TO_READ_SLUG, WANT_TO_READ_COLLECTION_ID)
    )
}

internal suspend fun RocheDao.upsertFeed(
    feed: FeedRow,
    markDirty: Boolean = false,
    hlcOverride: String? = null,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    executeInTransaction {
        val nowIso = feed.lastPolled ?: nowUtcIso()
        val existingEntry = getEntryById(feed.id)
        val existingSource = getFeedSourceById(feed.id)
        val effectiveHlc =
            resolveFeedEffectiveHlc(feed, existingSource, nowIso, markDirty, hlcOverride, nodeId)
        val cleanTitle = feed.title.ifBlank { feed.url }
        upsertEntry(
            buildUpsertFeedEntry(feed, existingEntry, cleanTitle, nowIso, effectiveHlc, markDirty)
        )
        upsertCollectionSpec(
            CollectionSpecEntity(
                collectionId = feed.id,
                slug = null,
                mode = "feed",
                role = "feed",
                ruleJson = null,
                showInLists = false,
                color = null,
            )
        )
        upsertFeedSource(
            buildUpsertFeedSource(feed, existingSource, cleanTitle, effectiveHlc, markDirty)
        )
        val canonUrl = canonicalizeSyncUrl(feed.url)
        if (canonUrl.isNotEmpty()) {
            insertEntryIdentifierIgnore(EntryIdentifierEntity("feed_url", canonUrl, feed.id))
        }
        clearFallbackFeedTitleForFeed(feed.id)
    }
}

@Suppress("LongParameterList")
private suspend fun RocheDao.resolveFeedEffectiveHlc(
    feed: FeedRow,
    existingSource: FeedSourceEntity?,
    nowIso: String,
    markDirty: Boolean,
    hlcOverride: String?,
    nodeId: String,
): String =
    hlcOverride
        ?: if (markDirty) {
            nextLocalHlc(this, nodeId)
        } else {
            feed.hlc.takeIf { it.isNotBlank() }
                ?: existingSource?.hlc?.takeIf { it.isNotBlank() }
                ?: HlcTimestamp.fromIsoOrEpoch(nowIso, nodeId).encode()
        }

@Suppress("LongParameterList")
private fun buildUpsertFeedEntry(
    feed: FeedRow,
    existingEntry: EntryEntity?,
    cleanTitle: String,
    nowIso: String,
    effectiveHlc: String,
    markDirty: Boolean,
): EntryEntity =
    EntryEntity(
        id = feed.id,
        kind = existingEntry?.kind ?: entryKindForFeedTypeWire(feed.feedType),
        title = cleanTitle,
        creatorsJson = existingEntry?.creatorsJson ?: "[]",
        url = feed.siteUrl ?: existingEntry?.url ?: feed.url,
        publishedAt = existingEntry?.publishedAt,
        releaseYear = existingEntry?.releaseYear,
        summary = feed.description ?: existingEntry?.summary,
        thumbnailUrl = existingEntry?.thumbnailUrl,
        metadataJson = existingEntry?.metadataJson ?: "{}",
        mergedIntoId = existingEntry?.mergedIntoId,
        createdAt = existingEntry?.createdAt ?: nowIso,
        updatedAt = if (markDirty) nowUtcIso() else existingEntry?.updatedAt ?: nowIso,
        hlc = effectiveHlc,
        isLocalDirty = markDirty || feed.isLocalDirty || existingEntry?.isLocalDirty == true,
    )

private fun buildUpsertFeedSource(
    feed: FeedRow,
    existingSource: FeedSourceEntity?,
    cleanTitle: String,
    effectiveHlc: String,
    markDirty: Boolean,
): FeedSourceEntity =
    FeedSourceEntity(
        collectionId = feed.id,
        feedUrl = feed.url,
        siteUrl = feed.siteUrl ?: existingSource?.siteUrl,
        sourceType = feed.feedType,
        feedTitle = cleanTitle,
        language = feed.language ?: existingSource?.language,
        pollInterval = feed.pollInterval,
        isActive = feed.isActive,
        lastPolledAt = feed.lastPolled,
        lastPolledBy = feed.lastPolledBy,
        lastError = feed.lastError,
        lastErrorBy = feed.lastErrorBy,
        autoDownloadEpisodes = feed.autoDownloadEpisodes,
        customPlaybackSpeed = feed.customPlaybackSpeed,
        hlc = effectiveHlc,
        isLocalDirty = markDirty || feed.isLocalDirty || existingSource?.isLocalDirty == true,
    )

internal suspend fun RocheDao.upsertFeeds(
    feeds: List<FeedRow>,
    markDirty: Boolean = false,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    if (feeds.isEmpty()) return
    executeInTransaction {
        for (feed in feeds) {
            upsertFeed(feed, markDirty = markDirty, nodeId = nodeId)
        }
    }
}

internal suspend fun RocheDao.upsertItem(
    item: InboxItemRow,
    markDirty: Boolean = false,
    hlcOverride: String? = null,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    val bounded = truncateInboxItemForStorage(item)
    executeInTransaction { writeSingleInboxItemInTx(bounded, markDirty, hlcOverride, nodeId) }
}

internal suspend fun RocheDao.upsertItems(
    items: List<InboxItemRow>,
    markDirty: Boolean = false,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    if (items.isEmpty()) return
    val deduped = items.associateBy { it.id }.values.map(::truncateInboxItemForStorage)
    executeInTransaction {
        for (item in deduped) {
            writeSingleInboxItemInTx(
                item,
                markDirty = markDirty,
                hlcOverride = null,
                nodeId = nodeId,
            )
        }
    }
}

private suspend fun RocheDao.writeSingleInboxItemInTx(
    bounded: InboxItemRow,
    markDirty: Boolean,
    hlcOverride: String?,
    nodeId: String,
) {
    val existingEntry = getEntryById(bounded.id)
    val existingState = getEntryStateById(bounded.id)
    val effectiveTs = bounded.interactionUpdatedAt ?: bounded.createdAt
    val hlc =
        resolveInboxItemEffectiveHlc(
            existingEntry,
            existingState,
            effectiveTs,
            markDirty,
            hlcOverride,
            nodeId,
        )
    upsertEntry(buildUpsertInboxEntry(bounded, existingEntry, effectiveTs, hlc, markDirty))
    val hasKnownFeed = bounded.feedId?.let { getFeedSourceById(it) != null } == true
    upsertEntryContent(
        EntryContentEntity(
            entryId = bounded.id,
            contentType = bounded.contentType,
            contentText = bounded.contentText,
            contentHtml = bounded.contentHtml,
            audioUrl = bounded.audioUrl,
            duration = bounded.duration,
            thumbnailMediaId = bounded.thumbnailMediaId,
            fallbackFeedTitle = if (hasKnownFeed) null else bounded.feedTitle,
        )
    )
    val inboxState =
        when {
            bounded.inboxState.equals("none", ignoreCase = true) -> "none"
            bounded.status.equals("archived", ignoreCase = true) -> "dismissed"
            else -> "unread"
        }
    upsertEntryState(
        EntryStateEntity(
            entryId = bounded.id,
            inboxState = inboxState,
            readAt = bounded.readAt,
            progress = bounded.readProgress,
            interactionUpdatedAt = bounded.interactionUpdatedAt,
            updatedAt = effectiveTs,
            isCachedOffline = bounded.isCachedOffline,
            hlc = hlc,
            isLocalDirty = markDirty || existingState?.isLocalDirty == true,
        )
    )
    syncInboxItemLinksInTx(bounded, effectiveTs, hlc, markDirty)
}

@Suppress("LongParameterList")
private suspend fun RocheDao.resolveInboxItemEffectiveHlc(
    existingEntry: EntryEntity?,
    existingState: EntryStateEntity?,
    effectiveTs: String,
    markDirty: Boolean,
    hlcOverride: String?,
    nodeId: String,
): String =
    hlcOverride
        ?: if (markDirty) {
            nextLocalHlc(this, nodeId)
        } else {
            existingState?.hlc?.takeIf { it.isNotBlank() }
                ?: existingEntry?.hlc?.takeIf { it.isNotBlank() }
                ?: HlcTimestamp.fromIsoOrEpoch(effectiveTs, nodeId).encode()
        }

private fun buildUpsertInboxEntry(
    bounded: InboxItemRow,
    existingEntry: EntryEntity?,
    effectiveTs: String,
    hlc: String,
    markDirty: Boolean,
): EntryEntity {
    val creatorsJson =
        bounded.author?.trim()?.takeIf { it.isNotEmpty() }?.let { encodeCreatorsJson(listOf(it)) }
            ?: existingEntry?.creatorsJson
            ?: "[]"
    return EntryEntity(
        id = bounded.id,
        kind = existingEntry?.kind ?: entryKindForItemWire(bounded.contentType, bounded.audioUrl),
        title = bounded.title,
        creatorsJson = creatorsJson,
        url = bounded.url.ifBlank { existingEntry?.url },
        publishedAt = bounded.publishedAt ?: existingEntry?.publishedAt,
        releaseYear = existingEntry?.releaseYear ?: extractYearPrefix(bounded.publishedAt),
        summary = bounded.summary,
        thumbnailUrl = bounded.thumbnailUrl ?: existingEntry?.thumbnailUrl,
        metadataJson = existingEntry?.metadataJson ?: "{}",
        mergedIntoId = existingEntry?.mergedIntoId,
        createdAt = existingEntry?.createdAt ?: bounded.createdAt,
        updatedAt = if (markDirty) nowUtcIso() else existingEntry?.updatedAt ?: effectiveTs,
        hlc = hlc,
        isLocalDirty = markDirty || existingEntry?.isLocalDirty == true,
    )
}

private suspend fun RocheDao.syncInboxItemLinksInTx(
    bounded: InboxItemRow,
    effectiveTs: String,
    hlc: String,
    markDirty: Boolean,
) {
    val canonUrl = canonicalizeSyncUrl(bounded.url)
    if (canonUrl.isNotEmpty()) {
        insertEntryIdentifierIgnore(EntryIdentifierEntity("url", canonUrl, bounded.id))
    }
    extractArxivIdentifier(bounded.url)?.let { arxiv ->
        insertEntryIdentifierIgnore(EntryIdentifierEntity("arxiv", arxiv, bounded.id))
    }
    val feedId = bounded.feedId?.trim()?.takeIf { it.isNotEmpty() }
    if (feedId != null && hasCollectionMembership(feedId, bounded.id) == 0) {
        upsertCollectionEntry(
            CollectionEntryEntity(
                collectionId = feedId,
                entryId = bounded.id,
                position = null,
                addedAt = bounded.createdAt,
                hlc = hlc,
                isLocalDirty = markDirty,
            )
        )
    }
    ensureStarredSpecExists()
    if (bounded.isFavorite) {
        if (hasCollectionMembership(STARRED_COLLECTION_ID, bounded.id) == 0) {
            upsertCollectionEntry(
                CollectionEntryEntity(
                    collectionId = STARRED_COLLECTION_ID,
                    entryId = bounded.id,
                    position = null,
                    addedAt = effectiveTs,
                    hlc = hlc,
                    isLocalDirty = markDirty,
                )
            )
        }
    } else {
        deleteCollectionEntry(STARRED_COLLECTION_ID, bounded.id)
    }
}

internal suspend fun RocheDao.upsertTag(
    tag: TagRow,
    markDirty: Boolean = false,
    hlcOverride: String? = null,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    val normName = normalizeTagName(tag.name)
    if (normName.isEmpty()) return
    val displayTitle = tag.name.trim().ifEmpty { normName }
    executeInTransaction {
        val nowIso = nowUtcIso()
        val existingEntry = getEntryById(tag.id)
        val hlc =
            hlcOverride
                ?: if (markDirty) {
                    nextLocalHlc(this, nodeId)
                } else {
                    existingEntry?.hlc?.takeIf { it.isNotBlank() }
                        ?: HlcTimestamp.fromIsoOrEpoch(SEED_EPOCH_ISO, nodeId).encode()
                }
        upsertEntry(
            EntryEntity(
                id = tag.id,
                kind = EntryKind.COLLECTION.wireValue,
                title = displayTitle,
                createdAt = existingEntry?.createdAt ?: nowIso,
                updatedAt = if (markDirty) nowIso else existingEntry?.updatedAt ?: nowIso,
                hlc = hlc,
                isLocalDirty = markDirty || existingEntry?.isLocalDirty == true,
            )
        )
        upsertCollectionSpec(
            CollectionSpecEntity(
                collectionId = tag.id,
                slug = "tag:$normName",
                mode = MODE_MANUAL,
                role = "tag",
                ruleJson = null,
                showInLists = false,
                color = tag.color,
            )
        )
        insertEntryIdentifierIgnore(EntryIdentifierEntity("tag", normName, tag.id))
    }
}

internal suspend fun RocheDao.upsertTags(
    tags: List<TagRow>,
    markDirty: Boolean = false,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    if (tags.isEmpty()) return
    executeInTransaction {
        for (tag in tags) {
            upsertTag(tag, markDirty = markDirty, nodeId = nodeId)
        }
    }
}

internal suspend fun RocheDao.insertItemTag(
    itemTag: ItemTagRow,
    markDirty: Boolean = false,
    hlcOverride: String? = null,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    val nowIso = nowUtcIso()
    val hlc =
        hlcOverride
            ?: if (markDirty) {
                nextLocalHlc(this, nodeId)
            } else {
                HlcTimestamp.fromIsoOrEpoch(nowIso, nodeId).encode()
            }
    if (markDirty) {
        deleteTombstone(
            COLLECTION_ENTRY_ENTITY,
            collectionEntryNaturalKey(itemTag.tagId, itemTag.itemId),
        )
    }
    upsertCollectionEntry(
        CollectionEntryEntity(
            collectionId = itemTag.tagId,
            entryId = itemTag.itemId,
            position = null,
            addedAt = nowIso,
            hlc = hlc,
            isLocalDirty = markDirty,
        )
    )
}

internal suspend fun RocheDao.insertItemTags(
    itemTags: List<ItemTagRow>,
    markDirty: Boolean = false,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    if (itemTags.isEmpty()) return
    executeInTransaction {
        for (it in itemTags) {
            insertItemTag(it, markDirty = markDirty, nodeId = nodeId)
        }
    }
}

internal suspend fun RocheDao.removeItemTag(
    itemId: String,
    tagId: String,
    markDirty: Boolean = true,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    executeInTransaction {
        val nowIso = nowUtcIso()
        val hlc = nextLocalHlc(this, nodeId)
        deleteCollectionEntry(tagId, itemId)
        if (markDirty) {
            upsertTombstone(
                TombstoneEntity(
                    entityType = COLLECTION_ENTRY_ENTITY,
                    naturalKey = collectionEntryNaturalKey(tagId, itemId),
                    deletedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = true,
                )
            )
        }
    }
}

@Suppress("LongParameterList")
internal suspend fun RocheDao.enrichEntryMetadataIfPresent(
    itemId: String,
    title: String?,
    hasUserTitleOverride: Boolean,
    author: String? = null,
    summary: String? = null,
    publishedAt: String? = null,
    thumbnailUrl: String? = null,
    contentHtml: String? = null,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    executeInTransaction {
        val existingEntry = getEntryById(itemId)
        val existingContent = getEntryContentById(itemId)
        if (existingEntry != null && existingContent != null) {
            val nowIso = nowUtcIso()
            val markDirty = existingEntry.url?.let(::isSyncableItemUrl) == true
            val hlc = if (markDirty) nextLocalHlc(this, nodeId) else existingEntry.hlc
            val nextSummary =
                existingEntry.summary?.takeIf { it.isNotBlank() }
                    ?: summary?.take(MAX_SQLITE_SUMMARY_CHARS)
            upsertEntry(
                buildEnrichedEntryRow(
                    existing = existingEntry,
                    title = title,
                    hasUserTitleOverride = hasUserTitleOverride,
                    author = author,
                    summary = nextSummary,
                    publishedAt = publishedAt,
                    thumbnailUrl = thumbnailUrl,
                    nowIso = nowIso,
                    hlc = hlc,
                    markDirty = markDirty,
                )
            )
            val nextHtml =
                existingContent.contentHtml?.takeIf { it.isNotBlank() }
                    ?: contentHtml?.take(MAX_SQLITE_HTML_CHARS)
            val nextDuration = resolveEnrichedDuration(existingContent, nextHtml, nextSummary)
            if (
                nextHtml != existingContent.contentHtml || nextDuration != existingContent.duration
            ) {
                upsertEntryContent(
                    existingContent.copy(contentHtml = nextHtml, duration = nextDuration)
                )
            }
        }
    }
}

@Suppress("LongParameterList")
private fun buildEnrichedEntryRow(
    existing: EntryEntity,
    title: String?,
    hasUserTitleOverride: Boolean,
    author: String?,
    summary: String?,
    publishedAt: String?,
    thumbnailUrl: String?,
    nowIso: String,
    hlc: String,
    markDirty: Boolean,
): EntryEntity {
    val nextTitle =
        if (hasUserTitleOverride) {
            existing.title
        } else {
            title?.trim()?.takeIf { it.isNotEmpty() } ?: existing.title
        }
    val nextCreators =
        if (existing.creatorsJson != "[]" && existing.creatorsJson.isNotBlank()) {
            existing.creatorsJson
        } else {
            author?.trim()?.takeIf { it.isNotEmpty() }?.let { encodeCreatorsJson(listOf(it)) }
                ?: existing.creatorsJson
        }
    val nextPub = existing.publishedAt ?: publishedAt
    val nextThumb = existing.thumbnailUrl?.takeIf { it.isNotBlank() } ?: thumbnailUrl
    return existing.copy(
        title = nextTitle,
        creatorsJson = nextCreators,
        publishedAt = nextPub,
        releaseYear = existing.releaseYear ?: extractYearPrefix(nextPub),
        summary = summary,
        thumbnailUrl = nextThumb,
        updatedAt = if (markDirty) nowIso else existing.updatedAt,
        hlc = hlc,
        isLocalDirty = markDirty || existing.isLocalDirty,
    )
}

private fun resolveEnrichedDuration(
    existingContent: EntryContentEntity,
    nextHtml: String?,
    nextSummary: String?,
): String? {
    if (existingContent.duration != null) return existingContent.duration
    val isArticle = ContentType.fromWireValue(existingContent.contentType) == ContentType.ARTICLE
    val hasExtended =
        nextHtml.orEmpty().length > LIST_PREVIEW_HTML_CHARS ||
            nextSummary.orEmpty().length > LIST_PREVIEW_SUMMARY_CHARS
    if (!isArticle || !hasExtended) return null
    val src = existingContent.contentText ?: nextHtml ?: nextSummary.orEmpty()
    return src.takeIf { it.isNotBlank() }?.let(::formatArticleMinutes)
}

internal suspend fun RocheDao.setItemStatusAndReadAt(
    itemId: String,
    status: String,
    readAt: String?,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    executeInTransaction {
        val existing = getEntryStateById(itemId)
        if (existing != null) {
            val nowIso = nowUtcIso()
            val hlc = nextLocalHlc(this, nodeId)
            val isNonFeed = existing.inboxState.equals("none", ignoreCase = true)
            val isArchived = status.equals("archived", ignoreCase = true)
            val inboxState =
                when {
                    isNonFeed -> "none"
                    isArchived -> "dismissed"
                    else -> "unread"
                }
            val effectiveReadAt =
                if (isNonFeed && isArchived) readAt ?: existing.readAt ?: nowIso else readAt
            upsertEntryState(
                existing.copy(
                    inboxState = inboxState,
                    readAt = effectiveReadAt,
                    interactionUpdatedAt = nowIso,
                    updatedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty =
                        getEntryById(itemId)?.url?.let(::isSyncableItemUrl) != false ||
                            existing.isLocalDirty,
                )
            )
            if (isNonFeed) {
                syncNonFeedWantArchive(itemId, isArchived, nowIso, hlc, nodeId)
            }
        }
    }
}

private suspend fun RocheDao.syncNonFeedWantArchive(
    itemId: String,
    isArchived: Boolean,
    nowIso: String,
    hlc: String,
    nodeId: String,
) {
    val isSyncable = getEntryById(itemId)?.url?.let(::isSyncableItemUrl) != false
    val natKey = collectionEntryNaturalKey(WANT_TO_READ_COLLECTION_ID, itemId)
    if (isArchived) {
        if (hasCollectionMembership(WANT_TO_READ_COLLECTION_ID, itemId) > 0) {
            deleteCollectionEntry(WANT_TO_READ_COLLECTION_ID, itemId)
            if (isSyncable) {
                upsertTombstone(
                    TombstoneEntity(
                        entityType = COLLECTION_ENTRY_ENTITY,
                        naturalKey = natKey,
                        deletedAt = nowIso,
                        hlc = hlc,
                        isLocalDirty = true,
                    )
                )
            }
        }
    } else {
        ensureWantToReadSpecExists(markDirty = isSyncable, nodeId = nodeId)
        deleteTombstone(COLLECTION_ENTRY_ENTITY, natKey)
        upsertCollectionEntry(
            CollectionEntryEntity(
                collectionId = WANT_TO_READ_COLLECTION_ID,
                entryId = itemId,
                position = null,
                addedAt = nowIso,
                hlc = hlc,
                isLocalDirty = isSyncable,
            )
        )
    }
}

internal suspend fun RocheDao.setItemReadAt(
    itemId: String,
    readAt: String?,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    executeInTransaction {
        val existing = getEntryStateById(itemId)
        if (existing != null) {
            val nowIso = nowUtcIso()
            val hlc = nextLocalHlc(this, nodeId)
            upsertEntryState(
                existing.copy(
                    readAt = readAt,
                    interactionUpdatedAt = nowIso,
                    updatedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = true,
                )
            )
        }
    }
}

internal suspend fun RocheDao.setItemReadProgress(
    itemId: String,
    progress: Double,
    explicitReadAt: String? = null,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    executeInTransaction {
        val existing = getEntryStateById(itemId)
        if (existing != null) {
            val nowIso = nowUtcIso()
            val hlc = nextLocalHlc(this, nodeId)
            upsertEntryState(
                existing.copy(
                    progress = progress,
                    readAt = explicitReadAt ?: existing.readAt,
                    interactionUpdatedAt = nowIso,
                    updatedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = true,
                )
            )
        }
    }
}

internal suspend fun RocheDao.setItemFavorite(
    itemId: String,
    isFavorite: Boolean,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    executeInTransaction {
        ensureStarredSpecExists()
        val entry = getEntryById(itemId)
        if (entry != null) {
            val isSyncable = entry.url?.startsWith("localpdf://", ignoreCase = true) != true
            val nowIso = nowUtcIso()
            val hlc = nextLocalHlc(this, nodeId)
            val natKey = collectionEntryNaturalKey(STARRED_COLLECTION_ID, itemId)
            if (isFavorite) {
                deleteTombstone(COLLECTION_ENTRY_ENTITY, natKey)
                upsertCollectionEntry(
                    CollectionEntryEntity(
                        collectionId = STARRED_COLLECTION_ID,
                        entryId = itemId,
                        position = null,
                        addedAt = nowIso,
                        hlc = hlc,
                        isLocalDirty = isSyncable,
                    )
                )
            } else {
                deleteCollectionEntry(STARRED_COLLECTION_ID, itemId)
                if (isSyncable) {
                    upsertTombstone(
                        TombstoneEntity(
                            entityType = COLLECTION_ENTRY_ENTITY,
                            naturalKey = natKey,
                            deletedAt = nowIso,
                            hlc = hlc,
                            isLocalDirty = true,
                        )
                    )
                }
            }
            getEntryStateById(itemId)?.let { existingState ->
                upsertEntryState(
                    existingState.copy(
                        interactionUpdatedAt = nowIso,
                        updatedAt = nowIso,
                        hlc = hlc,
                        isLocalDirty = isSyncable || existingState.isLocalDirty,
                    )
                )
            }
        }
    }
}

internal suspend fun RocheDao.updateFeedTitleGraph(
    feedId: String,
    cleanTitle: String,
    markDirty: Boolean = true,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    executeInTransaction {
        val nowIso = nowUtcIso()
        val hlc = nextLocalHlc(this, nodeId)
        getEntryById(feedId)?.let { entry ->
            upsertEntry(
                entry.copy(
                    title = cleanTitle,
                    updatedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = markDirty || entry.isLocalDirty,
                )
            )
        }
        getFeedSourceById(feedId)?.let { source ->
            upsertFeedSource(
                source.copy(
                    feedTitle = cleanTitle,
                    hlc = hlc,
                    isLocalDirty = markDirty || source.isLocalDirty,
                )
            )
        }
        clearFallbackFeedTitleForFeed(feedId)
    }
}

internal suspend fun RocheDao.updateFeedActiveGraph(
    feedId: String,
    isActive: Boolean,
    markDirty: Boolean = true,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    executeInTransaction {
        val hlc = nextLocalHlc(this, nodeId)
        getFeedSourceById(feedId)?.let { source ->
            upsertFeedSource(
                source.copy(
                    isActive = isActive,
                    hlc = hlc,
                    isLocalDirty = markDirty || source.isLocalDirty,
                )
            )
        }
    }
}

internal suspend fun RocheDao.entryHasCatalogHistory(entryId: String): Boolean =
    hasCatalogHistoryRaw(entryId, "%\"$entryId\"%") != 0

@Suppress("FunctionNameMaxLength")
internal suspend fun RocheDao.deleteCollectionGeneratorWithTombstone(
    collectionId: String,
    recordTombstone: Boolean = true,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    val cleanId = collectionId.trim()
    if (cleanId.isEmpty()) return
    executeInTransaction {
        deleteCollectionGenerator(cleanId)
        if (recordTombstone) {
            val nowIso = nowUtcIso()
            val hlc = nextLocalHlc(this, nodeId)
            upsertTombstone(
                TombstoneEntity(
                    entityType = com.grayvines.roche.data.TOMBSTONE_ENTITY_COLLECTION_GENERATOR,
                    naturalKey = cleanId,
                    deletedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = true,
                )
            )
        }
    }
}

internal suspend fun RocheDao.deleteEntryCascade(entryId: String) {
    deleteEntryContentById(entryId)
    deleteEntryStateById(entryId)
    deleteIdentifiersForEntry(entryId)
    deleteFeedSourceById(entryId)
    deleteCollectionGenerator(entryId)
    deleteCollectionSpec(entryId)
    deleteMergedDupMemberships(entryId)
    deleteRawEntryById(entryId)
}

internal suspend fun RocheDao.deleteItemPreservingHistory(
    itemId: String,
    recordTombstones: Boolean = true,
    detachWantQueues: Boolean = true,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
    hlcOverride: String? = null,
): Boolean {
    val entry = getEntryById(itemId) ?: return false
    val isSyncable = entry.url?.startsWith("localpdf://", ignoreCase = true) != true
    val emitTombstones = recordTombstones && isSyncable
    val nowIso = nowUtcIso()
    val hlc = hlcOverride ?: nextLocalHlc(this, nodeId)
    if (detachWantQueues) {
        detachWantMembershipsForItem(itemId, nowIso, hlc, emitTombstones)
    }
    if (entryHasCatalogHistory(itemId)) {
        upsertEntry(entry.copy(updatedAt = nowIso, hlc = hlc, isLocalDirty = emitTombstones))
        val prevState = getEntryStateById(itemId)
        val keepReadable = hasReadableListMembership(itemId)
        upsertEntryState(
            EntryStateEntity(
                entryId = itemId,
                inboxState = "none",
                readAt = null,
                progress = if (keepReadable) prevState?.progress ?: 0.0 else 0.0,
                interactionUpdatedAt = nowIso,
                updatedAt = nowIso,
                isCachedOffline = keepReadable && prevState?.isCachedOffline == true,
                hlc = hlc,
                isLocalDirty =
                    emitTombstones || recordTombstones && prevState?.isLocalDirty == true,
            )
        )
        if (!keepReadable) {
            deleteEntryContentById(itemId)
        }
        detachInboxOnlyMemberships(itemId, nowIso, hlc, emitTombstones, detachTags = !keepReadable)
    } else {
        detachInboxOnlyMemberships(itemId, nowIso, hlc, emitTombstones, detachTags = true)
        deleteEntryCascade(itemId)
    }
    return true
}

private suspend fun RocheDao.detachWantMembershipsForItem(
    itemId: String,
    nowIso: String,
    hlc: String,
    recordTombstones: Boolean,
) {
    val specsById = getAllCollectionSpecs().associateBy { it.collectionId }
    for (mem in getCollectionEntriesForEntry(itemId)) {
        val shouldDetach =
            mem.collectionId == STARRED_COLLECTION_ID || specsById[mem.collectionId]?.role == "want"
        if (shouldDetach) {
            deleteCollectionEntry(mem.collectionId, itemId)
            if (recordTombstones) {
                upsertTombstone(
                    TombstoneEntity(
                        entityType = COLLECTION_ENTRY_ENTITY,
                        naturalKey = collectionEntryNaturalKey(mem.collectionId, itemId),
                        deletedAt = nowIso,
                        hlc = hlc,
                        isLocalDirty = true,
                    )
                )
            }
        }
    }
}

private suspend fun RocheDao.hasReadableListMembership(itemId: String): Boolean {
    val specsById = getAllCollectionSpecs().associateBy { it.collectionId }
    return getCollectionEntriesForEntry(itemId).any { mem ->
        val role = specsById[mem.collectionId]?.role
        role == "list" || role == "want"
    }
}

private suspend fun RocheDao.detachInboxOnlyMemberships(
    itemId: String,
    nowIso: String,
    hlc: String,
    recordTombstones: Boolean,
    detachTags: Boolean,
) {
    val specsById = getAllCollectionSpecs().associateBy { it.collectionId }
    for (mem in getCollectionEntriesForEntry(itemId)) {
        val role = specsById[mem.collectionId]?.role
        val isInboxScoped = mem.collectionId == UP_NEXT_COLLECTION_ID || detachTags && role == "tag"
        if (isInboxScoped) {
            deleteCollectionEntry(mem.collectionId, itemId)
            if (recordTombstones) {
                upsertTombstone(
                    TombstoneEntity(
                        entityType = COLLECTION_ENTRY_ENTITY,
                        naturalKey = collectionEntryNaturalKey(mem.collectionId, itemId),
                        deletedAt = nowIso,
                        hlc = hlc,
                        isLocalDirty = true,
                    )
                )
            }
        }
    }
}

internal suspend fun RocheDao.deleteFeedPreservingHistory(
    feedId: String,
    recordTombstones: Boolean = true,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
    hlcOverride: String? = null,
): List<String> {
    val evictedItemIds = ArrayList<String>()
    val allSources = getAllFeedSources().map { it.collectionId }.toHashSet()
    val feedMembers = getCollectionMemberships(feedId).map { it.entryId }
    for (entryId in feedMembers) {
        val otherMemberships = getCollectionEntriesForEntry(entryId)
        val keepInAnotherFeed = otherMemberships.any { other ->
            other.collectionId != feedId && other.collectionId in allSources
        }
        deleteCollectionEntry(feedId, entryId)
        if (!keepInAnotherFeed) {
            evictedItemIds.add(entryId)
            deleteItemPreservingHistory(
                itemId = entryId,
                recordTombstones = recordTombstones,
                detachWantQueues = false,
                nodeId = nodeId,
                hlcOverride = hlcOverride,
            )
        }
    }
    deleteFeedSourceById(feedId)
    val hasRemainingChildren = countCollectionEntries(feedId) > 0
    if (hasRemainingChildren || entryHasCatalogHistory(feedId)) {
        deleteCollectionSpec(feedId)
        getEntryById(feedId)?.let { entry ->
            val nowIso = nowUtcIso()
            val hlc = hlcOverride ?: nextLocalHlc(this, nodeId)
            upsertEntry(entry.copy(updatedAt = nowIso, hlc = hlc))
        }
    } else {
        deleteEntryCascade(feedId)
    }
    return evictedItemIds
}

private const val MODE_MANUAL = "manual"
