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
import com.grayvines.roche.data.canonicalizeSyncUrl
import com.grayvines.roche.data.collectionEntryNaturalKey
import com.grayvines.roche.data.encodeCreatorsJson
import com.grayvines.roche.data.extractArxivIdentifier
import com.grayvines.roche.data.extractYearPrefix
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
    if (getCollectionSpecById(STARRED_COLLECTION_ID) != null) return
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
            mode = "manual",
            role = "system",
            ruleJson = null,
            showInLists = false,
            color = null,
        )
    )
    insertEntryIdentifierIgnore(EntryIdentifierEntity("slug", "starred", STARRED_COLLECTION_ID))
}

internal suspend fun RocheDao.upsertFeed(
    feed: FeedRow,
    markDirty: Boolean = false,
    hlcOverride: String? = null,
) {
    executeInTransaction {
        val nowIso = feed.lastPolled ?: nowUtcIso()
        val existingEntry = getEntryById(feed.id)
        val existingSource = getFeedSourceById(feed.id)
        val effectiveHlc =
            resolveFeedEffectiveHlc(feed, existingSource, nowIso, markDirty, hlcOverride)
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

private suspend fun RocheDao.resolveFeedEffectiveHlc(
    feed: FeedRow,
    existingSource: FeedSourceEntity?,
    nowIso: String,
    markDirty: Boolean,
    hlcOverride: String?,
): String =
    hlcOverride
        ?: feed.hlc.takeIf { it.isNotBlank() }
        ?: if (markDirty) {
            nextLocalHlc(this)
        } else {
            existingSource?.hlc?.takeIf { it.isNotBlank() }
                ?: HlcTimestamp.fromIsoOrEpoch(nowIso, DEFAULT_LOCAL_HLC_NODE).encode()
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

internal suspend fun RocheDao.upsertFeeds(feeds: List<FeedRow>, markDirty: Boolean = false) {
    if (feeds.isEmpty()) return
    executeInTransaction {
        for (feed in feeds) {
            upsertFeed(feed, markDirty = markDirty)
        }
    }
}

internal suspend fun RocheDao.upsertItem(
    item: InboxItemRow,
    markDirty: Boolean = false,
    hlcOverride: String? = null,
) {
    val bounded = truncateInboxItemForStorage(item)
    executeInTransaction { writeSingleInboxItemInTx(bounded, markDirty, hlcOverride) }
}

internal suspend fun RocheDao.upsertItems(items: List<InboxItemRow>, markDirty: Boolean = false) {
    if (items.isEmpty()) return
    val deduped = items.associateBy { it.id }.values.map(::truncateInboxItemForStorage)
    executeInTransaction {
        for (item in deduped) {
            writeSingleInboxItemInTx(item, markDirty = markDirty, hlcOverride = null)
        }
    }
}

private suspend fun RocheDao.writeSingleInboxItemInTx(
    bounded: InboxItemRow,
    markDirty: Boolean,
    hlcOverride: String?,
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
        if (bounded.status.equals("archived", ignoreCase = true)) "dismissed" else "unread"
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

private suspend fun RocheDao.resolveInboxItemEffectiveHlc(
    existingEntry: EntryEntity?,
    existingState: EntryStateEntity?,
    effectiveTs: String,
    markDirty: Boolean,
    hlcOverride: String?,
): String =
    hlcOverride
        ?: if (markDirty) {
            nextLocalHlc(this)
        } else {
            existingState?.hlc?.takeIf { it.isNotBlank() }
                ?: existingEntry?.hlc?.takeIf { it.isNotBlank() }
                ?: HlcTimestamp.fromIsoOrEpoch(effectiveTs, DEFAULT_LOCAL_HLC_NODE).encode()
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
                    nextLocalHlc(this)
                } else {
                    existingEntry?.hlc?.takeIf { it.isNotBlank() }
                        ?: HlcTimestamp.fromIsoOrEpoch(SEED_EPOCH_ISO, DEFAULT_LOCAL_HLC_NODE)
                            .encode()
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
                mode = "manual",
                role = "tag",
                ruleJson = null,
                showInLists = false,
                color = tag.color,
            )
        )
        insertEntryIdentifierIgnore(EntryIdentifierEntity("tag", normName, tag.id))
    }
}

internal suspend fun RocheDao.upsertTags(tags: List<TagRow>, markDirty: Boolean = false) {
    if (tags.isEmpty()) return
    executeInTransaction {
        for (tag in tags) {
            upsertTag(tag, markDirty = markDirty)
        }
    }
}

internal suspend fun RocheDao.insertItemTag(
    itemTag: ItemTagRow,
    markDirty: Boolean = false,
    hlcOverride: String? = null,
) {
    val nowIso = nowUtcIso()
    val hlc =
        hlcOverride
            ?: if (markDirty) {
                nextLocalHlc(this)
            } else {
                HlcTimestamp.fromIsoOrEpoch(nowIso, DEFAULT_LOCAL_HLC_NODE).encode()
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
) {
    if (itemTags.isEmpty()) return
    executeInTransaction {
        for (it in itemTags) {
            insertItemTag(it, markDirty = markDirty)
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
            val inboxState =
                if (status.equals("archived", ignoreCase = true)) "dismissed" else "unread"
            upsertEntryState(
                existing.copy(
                    inboxState = inboxState,
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
        val existingState = getEntryStateById(itemId)
        if (existingState != null) {
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
                        isLocalDirty = true,
                    )
                )
            } else {
                deleteCollectionEntry(STARRED_COLLECTION_ID, itemId)
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
            upsertEntryState(
                existingState.copy(
                    interactionUpdatedAt = nowIso,
                    updatedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = true,
                )
            )
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
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
): Boolean {
    val entry = getEntryById(itemId) ?: return false
    val nowIso = nowUtcIso()
    val hlc = nextLocalHlc(this, nodeId)
    if (entryHasCatalogHistory(itemId)) {
        upsertEntry(entry.copy(updatedAt = nowIso, hlc = hlc, isLocalDirty = recordTombstones))
        val prevState = getEntryStateById(itemId)
        upsertEntryState(
            EntryStateEntity(
                entryId = itemId,
                inboxState = "none",
                readAt = null,
                progress = 0.0,
                interactionUpdatedAt = nowIso,
                updatedAt = nowIso,
                isCachedOffline = false,
                hlc = hlc,
                isLocalDirty = recordTombstones || prevState?.isLocalDirty == true,
            )
        )
        deleteEntryContentById(itemId)
        detachInboxOnlyMemberships(itemId, nowIso, hlc, recordTombstones)
    } else {
        detachInboxOnlyMemberships(itemId, nowIso, hlc, recordTombstones)
        deleteEntryCascade(itemId)
    }
    return true
}

private suspend fun RocheDao.detachInboxOnlyMemberships(
    itemId: String,
    nowIso: String,
    hlc: String,
    recordTombstones: Boolean,
) {
    val specsById = getAllCollectionSpecs().associateBy { it.collectionId }
    for (mem in getCollectionEntriesForEntry(itemId)) {
        val role = specsById[mem.collectionId]?.role
        val isInboxScoped =
            mem.collectionId == STARRED_COLLECTION_ID ||
                mem.collectionId == UP_NEXT_COLLECTION_ID ||
                role == "tag"
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
): List<String> {
    val evictedItemIds = ArrayList<String>()
    val allSources = getAllFeedSources().map { it.collectionId }.toHashSet()
    val feedMembers = getCollectionMemberships(feedId).map { it.entryId }
    for (entryId in feedMembers) {
        val otherMemberships = getCollectionEntriesForEntry(entryId)
        val keepInInbox = otherMemberships.any { other ->
            other.collectionId != feedId &&
                (other.collectionId == STARRED_COLLECTION_ID || other.collectionId in allSources)
        }
        if (keepInInbox) {
            deleteCollectionEntry(feedId, entryId)
        } else {
            evictedItemIds.add(entryId)
            deleteItemPreservingHistory(
                itemId = entryId,
                recordTombstones = recordTombstones,
                nodeId = nodeId,
            )
        }
    }
    deleteFeedSourceById(feedId)
    val hasRemainingChildren = countCollectionEntries(feedId) > 0
    if (hasRemainingChildren || entryHasCatalogHistory(feedId)) {
        deleteCollectionSpec(feedId)
        getEntryById(feedId)?.let { entry ->
            val nowIso = nowUtcIso()
            val hlc = nextLocalHlc(this, nodeId)
            upsertEntry(entry.copy(updatedAt = nowIso, hlc = hlc))
        }
    } else {
        deleteEntryCascade(feedId)
    }
    return evictedItemIds
}
