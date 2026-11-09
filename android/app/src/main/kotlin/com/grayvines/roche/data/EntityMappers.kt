package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import java.time.Instant

@Suppress("LongMethod")
internal fun buildSnapshot(
    items: List<ItemEntity>,
    feeds: List<FeedEntity>,
    tags: List<TagEntity>,
    itemTags: List<ItemTagEntity>,
): RepositorySnapshot {
    val tagsById = tags.associateBy { it.id }
    val tagNamesByItemId =
        itemTags
            .groupBy(
                keySelector = { it.itemId },
                valueTransform = { tagsById[it.tagId]?.name },
            )
            .mapValues { (_, names) ->
                names
                    .filterNotNull()
                    .sortedWith(
                        compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it }
                            .thenBy { it }
                    )
            }

    val domainEntries = items.map { entity ->
        toInboxEntry(entity, tagNamesByItemId[entity.id].orEmpty())
    }

    val unreadByFeedId =
        domainEntries
            .filter { it.isUnread && it.feedId != null }
            .groupingBy { it.feedId!! }
            .eachCount()

    val domainFeeds =
        feeds
            .map { feed ->
                FeedItem(
                    id = feed.id,
                    title = feed.title,
                    url = feed.url,
                    siteUrl = feed.siteUrl,
                    feedType = FeedType.fromWireValue(feed.feedType),
                    unreadCount = unreadByFeedId[feed.id] ?: 0,
                )
            }
            .sortedWith(
                compareBy<FeedItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                    .thenBy { it.title }
            )

    val unreadByTagName =
        domainEntries
            .filter { it.isUnread }
            .flatMap { entry -> entry.tags }
            .groupingBy { it }
            .eachCount()

    val domainTags =
        tags
            .map { tag ->
                TagItem(
                    id = tag.id,
                    name = tag.name,
                    unreadCount = unreadByTagName[tag.name] ?: 0,
                )
            }
            .sortedWith(
                compareBy<TagItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.name }
            )

    return RepositorySnapshot(
        entries = domainEntries,
        feeds = domainFeeds,
        tags = domainTags,
    )
}

internal fun toInboxEntry(entity: ItemEntity, tags: List<String>): InboxEntry =
    InboxEntry(
        id = entity.id,
        feedId = entity.feedId,
        feedTitle = entity.feedTitle,
        title = entity.title,
        url = entity.url,
        author = entity.author,
        summary = entity.summary,
        contentText = entity.contentText,
        contentHtml = entity.contentHtml,
        contentType = ContentType.fromWireValue(entity.contentType),
        status = ItemStatus.fromWireValue(entity.status),
        isFavorite = entity.isFavorite,
        isCachedOffline = entity.isCachedOffline,
        publishedAt = entity.publishedAt,
        createdAt = entity.createdAt,
        readProgress = entity.readProgress,
        thumbnailMediaId = entity.thumbnailMediaId,
        duration = entity.duration,
        tags = tags,
        readAt = entity.readAt,
        thumbnailUrl = entity.thumbnailUrl,
    )

internal fun toRemoteFeedEntity(
    remote: RemoteFeedDto,
    existing: FeedEntity? = null,
): FeedEntity {
    val useExistingPoll =
        existing != null &&
            existing.lastPolled != null &&
            (remote.lastPolled == null || existing.lastPolled > remote.lastPolled)
    val pollSource = if (useExistingPoll) existing else null
    return FeedEntity(
        id = remote.id,
        title = remote.title.ifBlank { existing?.title.orEmpty() },
        url = remote.url.ifBlank { existing?.url.orEmpty() },
        siteUrl = remote.siteUrl ?: existing?.siteUrl,
        feedType = remote.feedType,
        description = remote.description ?: existing?.description,
        language = remote.language ?: existing?.language,
        pollInterval = remote.pollInterval,
        lastPolled = pollSource?.lastPolled ?: remote.lastPolled,
        lastPolledBy = pollSource?.lastPolledBy ?: remote.lastPolledBy,
        lastError = pollSource?.lastError ?: remote.lastError,
        lastErrorBy = pollSource?.lastErrorBy ?: remote.lastErrorBy,
        isActive = remote.isActive,
    )
}

internal fun toRemoteItemEntity(
    remote: RemoteItemDto,
    feedTitlesById: Map<String, String>,
    isCachedOffline: Boolean,
    existing: ItemEntity? = null,
    feedUrlsById: Map<String, String> = emptyMap(),
): ItemEntity {
    val resolvedFeedId = remote.feedId ?: existing?.feedId
    val resolvedFeedUrl =
        remote.feedUrl ?: resolvedFeedId?.let { feedUrlsById[it] } ?: existing?.feedUrl
    val richerSummary = pickRicherString(existing?.summary, remote.summary)
    val richerHtml =
        pickRicherString(existing?.contentHtml, remote.contentHtml)?.take(MAX_SQLITE_HTML_CHARS)
    return ItemEntity(
        id = remote.id,
        feedId = resolvedFeedId,
        feedTitle = resolveRemoteFeedTitle(remote, feedTitlesById, existing),
        title = remote.title.ifBlank { existing?.title.orEmpty() },
        url = remote.url.ifBlank { existing?.url.orEmpty() },
        author = remote.author.nonBlankOr(existing?.author),
        summary = richerSummary,
        contentText = remote.contentText.nonBlankOr(existing?.contentText) ?: richerSummary,
        contentHtml = richerHtml,
        contentType = remote.contentType.ifBlank { existing?.contentType ?: "article" },
        status = remote.status,
        isFavorite = remote.isFavorite,
        isCachedOffline = isCachedOffline,
        publishedAt = remote.publishedAt ?: existing?.publishedAt,
        createdAt = remote.createdAt.ifBlank { existing?.createdAt.orEmpty() },
        readProgress = remote.readProgress,
        thumbnailMediaId = remote.thumbnailMediaId.nonBlankOr(existing?.thumbnailMediaId),
        duration = remote.duration.nonBlankOr(existing?.duration),
        readAt =
            if (remote.status.equals("archived", ignoreCase = true)) {
                remote.readAt.nonBlankOr(existing?.readAt)
                    ?: remote.createdAt.ifBlank { Instant.now().toString() }
            } else {
                remote.readAt
            },
        thumbnailUrl = remote.thumbnailUrl.nonBlankOr(existing?.thumbnailUrl),
        feedUrl = resolvedFeedUrl,
        interactionUpdatedAt =
            newerIsoTimestamp(remote.interactionUpdatedAt, existing?.interactionUpdatedAt),
    )
}

internal fun newerIsoTimestamp(first: String?, second: String?): String? {
    val a = first?.trim()?.takeIf { it.isNotEmpty() }
    val b = second?.trim()?.takeIf { it.isNotEmpty() }
    if (a == null) return b
    if (b == null) return a
    val instA = runCatching { Instant.parse(a) }.getOrNull()
    val instB = runCatching { Instant.parse(b) }.getOrNull()
    return if (instA != null && instB != null) {
        if (instB.isAfter(instA)) b else a
    } else {
        if (b > a) b else a
    }
}

private fun resolveRemoteFeedTitle(
    remote: RemoteItemDto,
    feedTitlesById: Map<String, String>,
    existing: ItemEntity?,
): String =
    remote.feedTitle
        ?: remote.feedId?.let { feedTitlesById[it] }
        ?: existing?.feedTitle
        ?: DEFAULT_SAVED_HOST

private fun String?.nonBlankOr(fallback: String?): String? =
    this?.takeIf { it.isNotBlank() } ?: fallback

internal fun reconcileSavedItemEntity(
    localEntity: ItemEntity,
    remoteSaved: RemoteItemDto,
): ItemEntity =
    localEntity.copy(
        id = remoteSaved.id,
        title = remoteSaved.title.ifBlank { localEntity.title },
        summary = remoteSaved.summary ?: localEntity.summary,
        contentText = remoteSaved.contentText ?: localEntity.contentText,
        contentHtml =
            (remoteSaved.contentHtml?.takeIf { it.isNotBlank() } ?: localEntity.contentHtml)?.take(
                MAX_SQLITE_HTML_CHARS
            ),
        contentType = remoteSaved.contentType.ifBlank { localEntity.contentType },
        createdAt = remoteSaved.createdAt.ifBlank { localEntity.createdAt },
        thumbnailMediaId = remoteSaved.thumbnailMediaId ?: localEntity.thumbnailMediaId,
        duration = remoteSaved.duration ?: localEntity.duration,
        thumbnailUrl = remoteSaved.thumbnailUrl ?: localEntity.thumbnailUrl,
    )

internal fun createSavedItemEntity(
    url: String,
    title: String,
    hostTitle: String,
    type: ContentType,
): ItemEntity {
    val id = deterministicItemId(null, url)
    return ItemEntity(
        id = id,
        feedId = null,
        feedTitle =
            when (type) {
                ContentType.PDF -> "arXiv / PDF"
                ContentType.VIDEO -> "YouTube"
                ContentType.ARTICLE -> hostTitle
            },
        title = title,
        url = url,
        author = null,
        summary = null,
        contentText = null,
        contentHtml = null,
        contentType = type.wireValue,
        status = ItemStatus.UNREAD.wireValue,
        isFavorite = false,
        isCachedOffline = false,
        publishedAt = null,
        createdAt = Instant.now().toString(),
        readProgress = 0.0,
        thumbnailMediaId = null,
        duration = null,
    )
}

internal const val MAX_SQLITE_HTML_CHARS = 512_000
