@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.local

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.ENTRY_KIND_COLLECTION
import com.grayvines.roche.core.ENTRY_KIND_PODCAST_EPISODE
import com.grayvines.roche.core.FLAG_LOCAL_HLC
import com.grayvines.roche.core.HlcClock
import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.SCHEME_ARXIV
import com.grayvines.roche.core.SCHEME_DOI
import com.grayvines.roche.core.SCHEME_ISBN
import com.grayvines.roche.core.SEED_EPOCH_ISO
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.STARRED_SLUG
import com.grayvines.roche.core.STARRED_TITLE
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_ENTRY
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_GENERATOR
import com.grayvines.roche.core.TOMBSTONE_ENTITY_FEED
import com.grayvines.roche.core.TOMBSTONE_ENTITY_ITEM
import com.grayvines.roche.core.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_SLUG
import com.grayvines.roche.core.WANT_TO_READ_TITLE
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.collectionEntryNaturalKey
import com.grayvines.roche.core.deterministicTagId
import com.grayvines.roche.core.encodeCreatorsJson
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.extractHostTitle
import com.grayvines.roche.core.extractYearPrefix
import com.grayvines.roche.core.feedNaturalKey
import com.grayvines.roche.core.formatArticleMinutes
import com.grayvines.roche.core.inferContentTypeWireValue
import com.grayvines.roche.core.isEntryUserEdited
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.core.isPlaceholderEntryTitle
import com.grayvines.roche.core.isSyncableItemUrl
import com.grayvines.roche.core.normalizeConsecutiveFailures
import com.grayvines.roche.core.normalizeDoi
import com.grayvines.roche.core.normalizeIsbn
import com.grayvines.roche.core.normalizeTagName
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.core.resolveArticleDuration
import com.grayvines.roche.core.stampEntryEditedHlc
import com.grayvines.roche.data.sync.compareHlc
import com.grayvines.roche.model.ContentType

internal const val SQLITE_DELETE_CHUNK_SIZE = 500
internal const val MAX_SQLITE_SUMMARY_CHARS = 16_384
internal const val MAX_SQLITE_TEXT_CHARS = 262_144
internal const val MAX_SQLITE_HTML_CHARS = 512_000
internal const val LIST_PREVIEW_SUMMARY_CHARS = 1_024
internal const val LIST_PREVIEW_TEXT_CHARS = 2_048
internal const val LIST_PREVIEW_HTML_CHARS = 2_048
internal const val MAX_FTS_QUERY_TOKENS = 16

internal suspend fun nextLocalHlc(
    dao: RocheDao,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
    nowMs: Long = System.currentTimeMillis(),
): String {
    var next = ""
    dao.executeInTransaction {
        val dbPrev = dao.getLocalSyncFlag(FLAG_LOCAL_HLC)
        next = HlcClock(nodeId, dbPrev).tick(nowMs).encode()
        dao.upsertLocalSyncFlagIfGreater(FLAG_LOCAL_HLC, next)
    }
    return next
}

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
): String? =
    resolveArticleDuration(
        existingDuration = item.duration,
        isArticle = ContentType.fromWireValue(item.contentType) == ContentType.ARTICLE,
        hasExtendedContent = hasExtendedItemContent(item),
        fallbackTextSource = boundedText ?: boundedHtml ?: boundedSummary.orEmpty(),
    )

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
    limit: Int = 150,
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
        getSearchItemsByIdsChunk(chunks.single(), limit)
    } else {
        chunks
            .flatMap { chunk -> getSearchItemsByIdsChunk(chunk, limit) }
            .sortedByDescending { it.publishedAt ?: it.createdAt }
            .take(limit)
    }
}

internal fun buildSeededCollectionEntry(
    id: String,
    title: String,
    seedHlc: String,
    timestampIso: String = SEED_EPOCH_ISO,
    isLocalDirty: Boolean = false,
): EntryEntity =
    EntryEntity(
        id = id,
        kind = ENTRY_KIND_COLLECTION,
        title = title,
        createdAt = timestampIso,
        updatedAt = timestampIso,
        hlc = seedHlc,
        isLocalDirty = isLocalDirty,
    )

internal fun buildStarredCollectionSpec(existingColor: String? = null): CollectionSpecEntity =
    CollectionSpecEntity(
        collectionId = STARRED_COLLECTION_ID,
        slug = STARRED_SLUG,
        role = "list",
        ruleJson = null,
        color = existingColor,
    )

internal fun buildWantToReadCollectionSpec(): CollectionSpecEntity =
    CollectionSpecEntity(
        collectionId = WANT_TO_READ_COLLECTION_ID,
        slug = WANT_TO_READ_SLUG,
        role = "want",
        ruleJson = null,
        color = null,
    )

internal suspend fun RocheDao.ensureStarredSpecExists() {
    val existingSpec = getCollectionSpecById(STARRED_COLLECTION_ID)
    if (existingSpec != null && existingSpec.role == "list" && !existingSpec.isDynamic) return
    val seedHlc = HlcTimestamp.fromIsoOrEpoch(SEED_EPOCH_ISO, DEFAULT_LOCAL_HLC_NODE).encode()
    if (getEntryById(STARRED_COLLECTION_ID) == null) {
        upsertEntry(buildSeededCollectionEntry(STARRED_COLLECTION_ID, STARRED_TITLE, seedHlc))
    }
    upsertCollectionSpec(buildStarredCollectionSpec(existingSpec?.color))
    insertEntryIdentifierIgnore(EntryIdentifierEntity("slug", STARRED_SLUG, STARRED_COLLECTION_ID))
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
            buildSeededCollectionEntry(
                id = WANT_TO_READ_COLLECTION_ID,
                title = WANT_TO_READ_TITLE,
                seedHlc = hlc,
                timestampIso = nowIso,
                isLocalDirty = markDirty,
            )
        )
    }
    upsertCollectionSpec(buildWantToReadCollectionSpec())
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
                role = "feed",
                ruleJson = null,
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
        consecutiveFailures =
            normalizeConsecutiveFailures(feed.lastError, feed.consecutiveFailures),
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
    val feedSource = bounded.feedId?.let { getFeedSourceById(it) }
    val hasKnownFeed = feedSource != null
    val isPodcastFeed = feedSource?.sourceType.equals(FEED_TYPE_PODCAST, ignoreCase = true) == true
    upsertEntry(
        buildUpsertInboxEntry(
            bounded = bounded,
            existingEntry = existingEntry,
            effectiveTs = effectiveTs,
            hlc = hlc,
            markDirty = markDirty,
            isPodcastFeed = isPodcastFeed,
        )
    )
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

@Suppress("LongParameterList")
private fun buildUpsertInboxEntry(
    bounded: InboxItemRow,
    existingEntry: EntryEntity?,
    effectiveTs: String,
    hlc: String,
    markDirty: Boolean,
    isPodcastFeed: Boolean = false,
): EntryEntity {
    val creatorsJson =
        bounded.author?.trim()?.takeIf { it.isNotEmpty() }?.let { encodeCreatorsJson(listOf(it)) }
            ?: existingEntry?.creatorsJson
            ?: "[]"
    return EntryEntity(
        id = bounded.id,
        kind = resolveUpsertInboxEntryKind(bounded, existingEntry, isPodcastFeed),
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

private fun resolveUpsertInboxEntryKind(
    bounded: InboxItemRow,
    existingEntry: EntryEntity?,
    isPodcastFeed: Boolean,
): String {
    val hasAudio =
        !bounded.audioUrl.isNullOrBlank() ||
            bounded.contentType.equals(CONTENT_TYPE_AUDIO, ignoreCase = true)
    return when {
        existingEntry == null ->
            entryKindForItemWire(
                contentType = bounded.contentType,
                audioUrl = bounded.audioUrl,
                isPodcastFeed = isPodcastFeed,
            )
        existingEntry.kind == DEFAULT_ENTRY_KIND && isPodcastFeed && hasAudio ->
            ENTRY_KIND_PODCAST_EPISODE
        else -> existingEntry.kind
    }
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
    if (
        feedId != null &&
            getEntryById(feedId) != null &&
            hasCollectionMembership(feedId, bounded.id) == 0
    ) {
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
                kind = ENTRY_KIND_COLLECTION,
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
                role = "tag",
                ruleJson = null,
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
            TOMBSTONE_ENTITY_COLLECTION_ENTRY,
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
                    entityType = TOMBSTONE_ENTITY_COLLECTION_ENTRY,
                    naturalKey = collectionEntryNaturalKey(tagId, itemId),
                    deletedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = true,
                )
            )
        }
    }
}

internal data class DocumentEnrichmentPatch(
    val entryId: String,
    val title: String? = null,
    val hasUserTitleOverride: Boolean = false,
    val authors: List<String> = emptyList(),
    val summary: String? = null,
    val publishedAt: String? = null,
    val releaseYear: Int? = null,
    val thumbnailUrl: String? = null,
    val contentHtml: String? = null,
    val doi: String? = null,
    val arxivId: String? = null,
    val isbn: String? = null,
    val forceRefresh: Boolean = false,
    val nodeId: String = DEFAULT_LOCAL_HLC_NODE,
)

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
    applyDocumentEnrichment(
        DocumentEnrichmentPatch(
            entryId = itemId,
            title = title,
            hasUserTitleOverride = hasUserTitleOverride,
            authors = listOfNotNull(author?.trim()?.takeIf { it.isNotEmpty() }),
            summary = summary,
            publishedAt = publishedAt,
            thumbnailUrl = thumbnailUrl,
            contentHtml = contentHtml,
            nodeId = nodeId,
        )
    )
}

internal suspend fun RocheDao.applyDocumentEnrichment(patch: DocumentEnrichmentPatch): Boolean {
    var changed = false
    executeInTransaction {
        val existingEntry = getEntryById(patch.entryId)
        val isEditableKind =
            existingEntry != null &&
                !existingEntry.kind.equals(ENTRY_KIND_COLLECTION, ignoreCase = true)
        val userEdited = existingEntry != null && isEntryUserEdited(existingEntry.metadataJson)
        if (isEditableKind && (!userEdited || patch.forceRefresh)) {
            changed = applyDocumentEnrichmentInTx(existingEntry, patch, userEdited)
        }
    }
    return changed
}

private suspend fun RocheDao.applyDocumentEnrichmentInTx(
    existingEntry: EntryEntity,
    patch: DocumentEnrichmentPatch,
    userEdited: Boolean,
): Boolean {
    val nowIso = nowUtcIso()
    val markDirty = existingEntry.url?.let(::isSyncableItemUrl) != false
    val candidate = buildEnrichedEntryRow(existingEntry, patch, nowIso)
    val entryChanged = hasEnrichedEntryChanges(existingEntry, candidate)
    val attachedIdents = attachEnrichedIdentsInTx(existingEntry.id, patch)
    val contentChanged = applyEnrichedContentInTx(existingEntry.id, patch, candidate.summary)
    if (!entryChanged && !attachedIdents) return contentChanged

    val hlc = if (markDirty) nextLocalHlc(this, patch.nodeId) else existingEntry.hlc
    val nextMeta =
        if (userEdited && patch.forceRefresh && markDirty) {
            stampEntryEditedHlc(existingEntry.metadataJson, hlc)
        } else {
            existingEntry.metadataJson
        }
    upsertEntry(
        candidate.copy(
            metadataJson = nextMeta,
            updatedAt = if (markDirty) nowIso else existingEntry.updatedAt,
            hlc = hlc,
            isLocalDirty = markDirty || existingEntry.isLocalDirty,
        )
    )
    return true
}

private fun hasEnrichedEntryChanges(existing: EntryEntity, candidate: EntryEntity): Boolean =
    candidate.title != existing.title ||
        candidate.creatorsJson != existing.creatorsJson ||
        candidate.publishedAt != existing.publishedAt ||
        candidate.releaseYear != existing.releaseYear ||
        candidate.summary != existing.summary ||
        candidate.thumbnailUrl != existing.thumbnailUrl

private suspend fun RocheDao.attachEnrichedIdentsInTx(
    entryId: String,
    patch: DocumentEnrichmentPatch,
): Boolean {
    val existingPairs = getIdentifiersForEntry(entryId).map { it.scheme to it.value }.toHashSet()
    val toInsert = buildList {
        patch.doi?.let(::normalizeDoi)?.let { add(EntryIdentifierEntity(SCHEME_DOI, it, entryId)) }
        patch.arxivId?.let(::extractArxivIdentifier)?.let {
            add(EntryIdentifierEntity(SCHEME_ARXIV, it, entryId))
        }
        patch.isbn?.let(::normalizeIsbn)?.let {
            add(EntryIdentifierEntity(SCHEME_ISBN, it, entryId))
        }
    }
        .distinctBy { it.scheme to it.value }
        .filter { it.scheme to it.value !in existingPairs }
    if (toInsert.isEmpty()) return false
    insertEntryIdentifiersIgnore(toInsert)
    return getIdentifiersForEntry(entryId).size > existingPairs.size
}

private suspend fun RocheDao.applyEnrichedContentInTx(
    entryId: String,
    patch: DocumentEnrichmentPatch,
    nextSummary: String?,
): Boolean {
    val existingContent = getEntryContentById(entryId) ?: return false
    val nextHtml =
        existingContent.contentHtml?.takeIf { it.isNotBlank() }
            ?: patch.contentHtml?.take(MAX_SQLITE_HTML_CHARS)
    val nextDuration = resolveEnrichedDuration(existingContent, nextHtml, nextSummary)
    if (nextHtml == existingContent.contentHtml && nextDuration == existingContent.duration) {
        return false
    }
    upsertEntryContent(existingContent.copy(contentHtml = nextHtml, duration = nextDuration))
    return true
}

private fun buildEnrichedEntryRow(
    existing: EntryEntity,
    patch: DocumentEnrichmentPatch,
    nowIso: String,
): EntryEntity {
    val cleanNewTitle = patch.title?.trim()?.takeIf { it.isNotEmpty() }
    val canUpgradeTitle =
        !patch.hasUserTitleOverride || isPlaceholderEntryTitle(existing.title, existing.url)
    val nextTitle =
        if (
            canUpgradeTitle &&
                cleanNewTitle != null &&
                !isPlaceholderEntryTitle(cleanNewTitle, existing.url)
        ) {
            cleanNewTitle
        } else {
            existing.title
        }
    val cleanAuthors = patch.authors.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val nextCreators =
        if (existing.creatorsJson != "[]" && existing.creatorsJson.isNotBlank()) {
            existing.creatorsJson
        } else if (cleanAuthors.isNotEmpty()) {
            encodeCreatorsJson(cleanAuthors)
        } else {
            existing.creatorsJson
        }
    val nextPub = existing.publishedAt ?: patch.publishedAt?.trim()?.takeIf { it.isNotEmpty() }
    val nextYear = existing.releaseYear ?: patch.releaseYear ?: extractYearPrefix(nextPub)
    val nextSummary =
        existing.summary?.takeIf { it.isNotBlank() }
            ?: patch.summary?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_SQLITE_SUMMARY_CHARS)
    val nextThumb = existing.thumbnailUrl?.takeIf { it.isNotBlank() } ?: patch.thumbnailUrl
    return existing.copy(
        title = nextTitle,
        creatorsJson = nextCreators,
        publishedAt = nextPub,
        releaseYear = nextYear,
        summary = nextSummary,
        thumbnailUrl = nextThumb,
        updatedAt = nowIso,
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

private suspend fun RocheDao.resolveOrInitEntryState(
    itemId: String,
    nowIso: String,
): EntryStateEntity? =
    getEntryStateById(itemId)
        ?: getEntryById(itemId)?.let {
            EntryStateEntity(
                entryId = itemId,
                inboxState = "none",
                updatedAt = nowIso,
            )
        }

internal suspend fun RocheDao.setItemStatusAndReadAt(
    itemId: String,
    status: String,
    readAt: String?,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
    restoreToWantToRead: Boolean = true,
) {
    executeInTransaction {
        val nowIso = nowUtcIso()
        val existing = resolveOrInitEntryState(itemId, nowIso)
        if (existing != null) {
            applyItemStatusAndReadAtInTx(
                itemId = itemId,
                existing = existing,
                status = status,
                readAt = readAt,
                nowIso = nowIso,
                nodeId = nodeId,
                restoreToWantToRead = restoreToWantToRead,
            )
        }
    }
}

@Suppress("LongParameterList")
private suspend fun RocheDao.applyItemStatusAndReadAtInTx(
    itemId: String,
    existing: EntryStateEntity,
    status: String,
    readAt: String?,
    nowIso: String,
    nodeId: String,
    restoreToWantToRead: Boolean,
) {
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
    val isSyncable = getEntryById(itemId)?.url?.let(::isSyncableItemUrl) != false
    upsertEntryState(
        existing.copy(
            inboxState = inboxState,
            readAt = effectiveReadAt,
            interactionUpdatedAt = nowIso,
            updatedAt = nowIso,
            hlc = hlc,
            isLocalDirty = isSyncable || existing.isLocalDirty,
        )
    )
    if (isNonFeed && (isArchived || restoreToWantToRead)) {
        syncNonFeedWantArchive(itemId, isArchived, nowIso, hlc, nodeId)
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
                        entityType = TOMBSTONE_ENTITY_COLLECTION_ENTRY,
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
        deleteTombstone(TOMBSTONE_ENTITY_COLLECTION_ENTRY, natKey)
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
        val nowIso = nowUtcIso()
        val existing = resolveOrInitEntryState(itemId, nowIso)
        if (existing != null) {
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
        val nowIso = nowUtcIso()
        val existing = resolveOrInitEntryState(itemId, nowIso)
        if (existing != null) {
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
            val isSyncable = !isLocalPdfUrl(entry.url)
            val nowIso = nowUtcIso()
            val hlc = nextLocalHlc(this, nodeId)
            val natKey = collectionEntryNaturalKey(STARRED_COLLECTION_ID, itemId)
            if (isFavorite) {
                deleteTombstone(TOMBSTONE_ENTITY_COLLECTION_ENTRY, natKey)
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
                            entityType = TOMBSTONE_ENTITY_COLLECTION_ENTRY,
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

internal suspend fun RocheDao.replaceEntryIdentifier(
    entryId: String,
    scheme: String,
    value: String,
) {
    deleteEntryIdentifiersByScheme(scheme, entryId)
    if (scheme.isNotEmpty() && value.isNotEmpty()) {
        upsertEntryIdentifier(EntryIdentifierEntity(scheme, value, entryId))
    }
}

internal suspend fun RocheDao.copyItemTombstonesForFeedUrl(
    oldFeedUrl: String,
    newFeedUrl: String,
    markDirty: Boolean,
): List<TombstoneEntity> {
    val oldCanon = canonicalizeSyncUrl(oldFeedUrl)
    val newCanon = canonicalizeSyncUrl(newFeedUrl)
    if (oldCanon.isEmpty() || newCanon.isEmpty() || oldCanon == newCanon) return emptyList()
    val oldPrefix = "$oldCanon\u0000"
    val allTombstones = getAllTombstones()
    val existingItemKeys =
        allTombstones
            .filter { it.entityType == TOMBSTONE_ENTITY_ITEM }
            .associateBy { it.naturalKey }
    val migrated = ArrayList<TombstoneEntity>()
    for (tomb in existingItemKeys.values) {
        if (!tomb.naturalKey.startsWith(oldPrefix)) continue
        val itemPart = tomb.naturalKey.substring(oldPrefix.length)
        val migratedKey = "$newCanon\u0000$itemPart"
        val existingTarget = existingItemKeys[migratedKey]
        if (existingTarget == null || compareHlc(tomb.hlc, existingTarget.hlc) > 0) {
            val entity =
                TombstoneEntity(
                    entityType = TOMBSTONE_ENTITY_ITEM,
                    naturalKey = migratedKey,
                    deletedAt = tomb.deletedAt,
                    hlc = tomb.hlc,
                    isLocalDirty = markDirty || tomb.isLocalDirty,
                )
            upsertTombstone(entity)
            migrated.add(entity)
        }
    }
    return migrated
}

internal suspend fun RocheDao.updateFeedUrlGraph(
    feedId: String,
    newFeedUrl: String,
    markDirty: Boolean = true,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
): Boolean {
    var updated = false
    executeInTransaction {
        updated = applyFeedUrlUpdateInTx(feedId, newFeedUrl, markDirty, nodeId)
    }
    return updated
}

private suspend fun RocheDao.applyFeedUrlUpdateInTx(
    feedId: String,
    newFeedUrl: String,
    markDirty: Boolean,
    nodeId: String,
): Boolean {
    val source = getFeedSourceById(feedId) ?: return false
    if (source.feedUrl == newFeedUrl) return false
    checkNoFeedUrlConflict(feedId, newFeedUrl)

    val nowIso = nowUtcIso()
    val hlc = nextLocalHlc(this, nodeId)
    deleteTombstone(TOMBSTONE_ENTITY_FEED, feedNaturalKey(newFeedUrl))
    deleteTombstone(TOMBSTONE_ENTITY_FEED, feedId)

    val wasHostFallbackTitle =
        source.feedTitle != null &&
            source.feedTitle.equals(extractHostTitle(source.feedUrl), ignoreCase = true)
    val nextFeedTitle = if (wasHostFallbackTitle) extractHostTitle(newFeedUrl) else source.feedTitle
    upsertFeedSource(
        source.copy(
            feedUrl = newFeedUrl,
            siteUrl = null,
            feedTitle = nextFeedTitle,
            lastPolledAt = null,
            lastPolledBy = null,
            lastError = null,
            lastErrorBy = null,
            consecutiveFailures = 0,
            hlc = hlc,
            isLocalDirty = markDirty || source.isLocalDirty,
        )
    )
    updateFeedEntryAfterUrlChange(feedId, source, newFeedUrl, nextFeedTitle, nowIso, hlc, markDirty)
    replaceEntryIdentifier(feedId, "feed_url", feedNaturalKey(newFeedUrl))
    copyItemTombstonesForFeedUrl(source.feedUrl, newFeedUrl, markDirty)
    return true
}

private suspend fun RocheDao.checkNoFeedUrlConflict(feedId: String, newFeedUrl: String) {
    val newCanon = canonicalizeSyncUrl(newFeedUrl)
    val hasConflict =
        getAllFeedSources().any { other ->
            other.collectionId != feedId && canonicalizeSyncUrl(other.feedUrl) == newCanon
        }
    check(!hasConflict) { "Another feed already uses that URL" }
}

@Suppress("LongParameterList")
private suspend fun RocheDao.updateFeedEntryAfterUrlChange(
    feedId: String,
    source: FeedSourceEntity,
    newFeedUrl: String,
    nextFeedTitle: String?,
    nowIso: String,
    hlc: String,
    markDirty: Boolean,
) {
    val entry = getEntryById(feedId) ?: return
    val useFallbackTitle =
        source.feedTitle.isNullOrBlank() && (entry.title.isBlank() || entry.title == source.feedUrl)
    val nextEntryTitle =
        when {
            nextFeedTitle != source.feedTitle && !nextFeedTitle.isNullOrBlank() -> nextFeedTitle
            useFallbackTitle -> newFeedUrl
            else -> entry.title
        }
    val replaceEntryUrl =
        entry.url == null || entry.url == source.feedUrl || entry.url == source.siteUrl
    upsertEntry(
        entry.copy(
            title = nextEntryTitle,
            url = if (replaceEntryUrl) newFeedUrl else entry.url,
            updatedAt = nowIso,
            hlc = hlc,
            isLocalDirty = markDirty || entry.isLocalDirty,
        )
    )
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
            val updated =
                if (isActive) {
                    source.copy(
                        isActive = true,
                        lastError = null,
                        lastErrorBy = null,
                        consecutiveFailures = 0,
                        hlc = hlc,
                        isLocalDirty = markDirty || source.isLocalDirty,
                    )
                } else {
                    source.copy(
                        isActive = false,
                        hlc = hlc,
                        isLocalDirty = markDirty || source.isLocalDirty,
                    )
                }
            upsertFeedSource(updated)
        }
    }
}

internal suspend fun RocheDao.updateFeedTypeGraph(
    feedId: String,
    targetFeedType: String,
    markDirty: Boolean = true,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    val normalizedTarget = targetFeedType.trim().lowercase()
    if (normalizedTarget != FEED_TYPE_RSS && normalizedTarget != FEED_TYPE_PODCAST) return
    executeInTransaction {
        val source = getFeedSourceById(feedId)
        val feedEntry = getEntryById(feedId)
        val currentType = source?.sourceType?.trim()?.lowercase()
        val canToggle =
            source != null &&
                feedEntry != null &&
                (currentType == FEED_TYPE_RSS || currentType == FEED_TYPE_PODCAST)
        if (canToggle) {
            val nowIso = nowUtcIso()
            val expectedFeedKind = entryKindForFeedTypeWire(normalizedTarget)
            if (currentType != normalizedTarget) {
                val fsHlc = nextLocalHlc(this, nodeId)
                upsertFeedSource(
                    source.copy(
                        sourceType = normalizedTarget,
                        hlc = fsHlc,
                        isLocalDirty = markDirty || source.isLocalDirty,
                    )
                )
            }
            if (feedEntry.kind != expectedFeedKind) {
                val entryHlc = nextLocalHlc(this, nodeId)
                upsertEntry(
                    feedEntry.copy(
                        kind = expectedFeedKind,
                        updatedAt = nowIso,
                        hlc = entryHlc,
                        isLocalDirty = markDirty || feedEntry.isLocalDirty,
                    )
                )
            }
            reclassifyFeedItemsForType(
                feedId = feedId,
                targetFeedType = normalizedTarget,
                markDirty = markDirty,
                nowIso = nowIso,
                nodeId = nodeId,
            )
        }
    }
}

private suspend fun RocheDao.reclassifyFeedItemsForType(
    feedId: String,
    targetFeedType: String,
    markDirty: Boolean,
    nowIso: String,
    nodeId: String,
) {
    val memberIds = getCollectionMemberships(feedId).map { it.entryId }.distinct()
    if (memberIds.isEmpty()) return
    val entriesById =
        memberIds
            .chunked(SQLITE_DELETE_CHUNK_SIZE)
            .flatMap { getEntriesByIds(it) }
            .associateBy {
                it.id
            }
    val contents = memberIds.chunked(SQLITE_DELETE_CHUNK_SIZE).flatMap { getEntryContentsByIds(it) }
    val otherPodcastIds =
        if (targetFeedType == FEED_TYPE_RSS) {
            getAllFeedSources()
                .filter {
                    it.collectionId != feedId &&
                        it.sourceType.equals(FEED_TYPE_PODCAST, ignoreCase = true)
                }
                .mapTo(HashSet()) { it.collectionId }
        } else {
            emptySet()
        }
    for (content in contents) {
        val entry = entriesById[content.entryId]
        if (entry != null && entry.mergedIntoId.isNullOrEmpty()) {
            reclassifyItemForFeedType(
                entry = entry,
                content = content,
                targetFeedType = targetFeedType,
                otherPodcastIds = otherPodcastIds,
                markDirty = markDirty,
                nowIso = nowIso,
                nodeId = nodeId,
            )
        }
    }
}

@Suppress("LongParameterList")
private suspend fun RocheDao.reclassifyItemForFeedType(
    entry: EntryEntity,
    content: EntryContentEntity,
    targetFeedType: String,
    otherPodcastIds: Set<String>,
    markDirty: Boolean,
    nowIso: String,
    nodeId: String,
) {
    val hasAudio =
        !content.audioUrl.isNullOrBlank() ||
            content.contentType.equals(CONTENT_TYPE_AUDIO, ignoreCase = true) ||
            content.contentType.equals(FEED_TYPE_PODCAST, ignoreCase = true)
    val targetPair =
        resolveFeedItemTypeTarget(
            entry = entry,
            content = content,
            hasAudio = hasAudio,
            targetFeedType = targetFeedType,
            otherPodcastIds = otherPodcastIds,
        ) ?: return
    val (targetCt, targetKind) = targetPair
    if (content.contentType != targetCt) {
        upsertEntryContent(content.copy(contentType = targetCt))
    }
    if (entry.kind != targetKind || content.contentType != targetCt) {
        val itemHlc = nextLocalHlc(this, nodeId)
        upsertEntry(
            entry.copy(
                kind = targetKind,
                updatedAt = nowIso,
                hlc = itemHlc,
                isLocalDirty = markDirty || entry.isLocalDirty,
            )
        )
    }
}

private suspend fun RocheDao.resolveFeedItemTypeTarget(
    entry: EntryEntity,
    content: EntryContentEntity,
    hasAudio: Boolean,
    targetFeedType: String,
    otherPodcastIds: Set<String>,
): Pair<String, String>? =
    if (targetFeedType == FEED_TYPE_PODCAST) {
        val promotableKind =
            entry.kind == DEFAULT_ENTRY_KIND || entry.kind == ENTRY_KIND_PODCAST_EPISODE
        if (hasAudio && promotableKind) CONTENT_TYPE_AUDIO to ENTRY_KIND_PODCAST_EPISODE else null
    } else {
        resolveDemotedFeedItemTarget(entry, content, hasAudio, otherPodcastIds)
    }

private suspend fun RocheDao.resolveDemotedFeedItemTarget(
    entry: EntryEntity,
    content: EntryContentEntity,
    hasAudio: Boolean,
    otherPodcastIds: Set<String>,
): Pair<String, String>? {
    val needsCheck = hasAudio || entry.kind == ENTRY_KIND_PODCAST_EPISODE
    val sharedWithOtherPodcast =
        needsCheck &&
            otherPodcastIds.isNotEmpty() &&
            getCollectionEntriesForEntry(entry.id).any { it.collectionId in otherPodcastIds }
    if (!needsCheck || sharedWithOtherPodcast) return null
    val inferred =
        entry.url?.trim()?.takeIf { it.isNotEmpty() }?.let(::inferContentTypeWireValue)
            ?: DEFAULT_ENTRY_KIND
    val nextKind =
        if (entry.kind == ENTRY_KIND_PODCAST_EPISODE) {
            entryKindForItemWire(inferred, content.audioUrl, isPodcastFeed = false)
        } else {
            entry.kind
        }
    return inferred to nextKind
}

internal suspend fun RocheDao.entryHasCatalogHistory(entryId: String): Boolean =
    hasCatalogHistoryRaw(entryId, buildSqlContainsPattern("\"$entryId\"")) != 0

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
                    entityType = TOMBSTONE_ENTITY_COLLECTION_GENERATOR,
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

@Suppress("LongParameterList")
internal suspend fun RocheDao.deleteItemPreservingHistory(
    itemId: String,
    recordTombstones: Boolean = true,
    detachWantQueues: Boolean = true,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
    hlcOverride: String? = null,
    preloadedSpecsById: Map<String, CollectionSpecEntity>? = null,
): Boolean {
    val entry = getEntryById(itemId) ?: return false
    val isSyncable = !isLocalPdfUrl(entry.url)
    val emitTombstones = recordTombstones && isSyncable
    val nowIso = nowUtcIso()
    val hlc = hlcOverride ?: nextLocalHlc(this, nodeId)
    val specsById = preloadedSpecsById ?: getAllCollectionSpecs().associateBy { it.collectionId }
    if (detachWantQueues) {
        detachWantMembershipsForItem(itemId, nowIso, hlc, emitTombstones, specsById)
    }
    if (entryHasCatalogHistory(itemId)) {
        upsertEntry(entry.copy(updatedAt = nowIso, hlc = hlc, isLocalDirty = emitTombstones))
        val prevState = getEntryStateById(itemId)
        val keepReadable = hasReadableListMembership(itemId, specsById)
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
        detachInboxOnlyMemberships(
            itemId = itemId,
            nowIso = nowIso,
            hlc = hlc,
            recordTombstones = emitTombstones,
            detachTags = !keepReadable,
            specsById = specsById,
        )
    } else {
        detachInboxOnlyMemberships(
            itemId = itemId,
            nowIso = nowIso,
            hlc = hlc,
            recordTombstones = emitTombstones,
            detachTags = true,
            specsById = specsById,
        )
        deleteEntryCascade(itemId)
    }
    return true
}

@Suppress("LongParameterList")
private suspend fun RocheDao.detachWantMembershipsForItem(
    itemId: String,
    nowIso: String,
    hlc: String,
    recordTombstones: Boolean,
    specsById: Map<String, CollectionSpecEntity>,
) {
    for (mem in getCollectionEntriesForEntry(itemId)) {
        val shouldDetach =
            mem.collectionId == STARRED_COLLECTION_ID || specsById[mem.collectionId]?.role == "want"
        if (shouldDetach) {
            deleteCollectionEntry(mem.collectionId, itemId)
            if (recordTombstones) {
                upsertTombstone(
                    TombstoneEntity(
                        entityType = TOMBSTONE_ENTITY_COLLECTION_ENTRY,
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

private suspend fun RocheDao.hasReadableListMembership(
    itemId: String,
    specsById: Map<String, CollectionSpecEntity>,
): Boolean =
    getCollectionEntriesForEntry(itemId).any { mem ->
        val role = specsById[mem.collectionId]?.role
        role == "list" || role == "want"
    }

@Suppress("LongParameterList")
private suspend fun RocheDao.detachInboxOnlyMemberships(
    itemId: String,
    nowIso: String,
    hlc: String,
    recordTombstones: Boolean,
    detachTags: Boolean,
    specsById: Map<String, CollectionSpecEntity>,
) {
    for (mem in getCollectionEntriesForEntry(itemId)) {
        val role = specsById[mem.collectionId]?.role
        val isInboxScoped = mem.collectionId == UP_NEXT_COLLECTION_ID || detachTags && role == "tag"
        if (isInboxScoped) {
            deleteCollectionEntry(mem.collectionId, itemId)
            if (recordTombstones) {
                upsertTombstone(
                    TombstoneEntity(
                        entityType = TOMBSTONE_ENTITY_COLLECTION_ENTRY,
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
    val specsById = getAllCollectionSpecs().associateBy { it.collectionId }
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
                preloadedSpecsById = specsById,
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

private const val CONTENT_TYPE_AUDIO = "audio"
private const val FEED_TYPE_RSS = "rss"
private const val FEED_TYPE_PODCAST = "podcast"

internal suspend fun attachTagsLocally(
    dao: RocheDao,
    itemId: String,
    tagNames: List<String>,
    markDirty: Boolean = false,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    for (tagName in tagNames) {
        val normalized = normalizeTagName(tagName)
        if (normalized.isNotBlank()) {
            val tag =
                dao.getTagByName(normalized)
                    ?: TagRow(id = deterministicTagId(normalized), name = normalized).also {
                        dao.upsertTag(it, markDirty = markDirty, nodeId = nodeId)
                    }
            dao.insertItemTag(
                ItemTagRow(itemId = itemId, tagId = tag.id),
                markDirty = markDirty,
                nodeId = nodeId,
            )
        }
    }
}
