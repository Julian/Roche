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
import java.util.UUID

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
            .mapValues { (_, names) -> names.filterNotNull().sorted() }

    val domainEntries = items.map { entity ->
        toInboxEntry(entity, tagNamesByItemId[entity.id].orEmpty())
    }

    val unreadByFeedId =
        domainEntries
            .filter { it.status == ItemStatus.UNREAD && it.feedId != null }
            .groupingBy { it.feedId!! }
            .eachCount()

    val domainFeeds = feeds.map { feed ->
        FeedItem(
            id = feed.id,
            title = feed.title,
            url = feed.url,
            siteUrl = feed.siteUrl,
            feedType = FeedType.fromWireValue(feed.feedType),
            unreadCount = unreadByFeedId[feed.id] ?: 0,
        )
    }

    val unreadByTagName =
        domainEntries
            .filter { it.status == ItemStatus.UNREAD }
            .flatMap { entry -> entry.tags }
            .groupingBy { it }
            .eachCount()

    val domainTags = tags.map { tag ->
        TagItem(
            id = tag.id,
            name = tag.name,
            unreadCount = unreadByTagName[tag.name] ?: 0,
        )
    }

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
    )

internal fun toRemoteFeedEntity(remote: RemoteFeedDto): FeedEntity =
    FeedEntity(
        id = remote.id,
        title = remote.title,
        url = remote.url,
        siteUrl = remote.siteUrl,
        feedType = remote.feedType,
    )

internal fun toRemoteItemEntity(
    remote: RemoteItemDto,
    feedTitlesById: Map<String, String>,
    isCachedOffline: Boolean,
    existing: ItemEntity? = null,
): ItemEntity =
    ItemEntity(
        id = remote.id,
        feedId = remote.feedId ?: existing?.feedId,
        feedTitle =
            remote.feedTitle
                ?: remote.feedId?.let { feedTitlesById[it] }
                ?: existing?.feedTitle
                ?: DEFAULT_SAVED_HOST,
        title = remote.title.ifBlank { existing?.title.orEmpty() },
        url = remote.url.ifBlank { existing?.url.orEmpty() },
        author = remote.author?.takeIf { it.isNotBlank() } ?: existing?.author,
        summary = remote.summary?.takeIf { it.isNotBlank() } ?: existing?.summary,
        contentText = remote.contentText?.takeIf { it.isNotBlank() } ?: existing?.contentText,
        contentHtml =
            (remote.contentHtml?.takeIf { it.isNotBlank() } ?: existing?.contentHtml)?.take(
                MAX_SQLITE_HTML_CHARS
            ),
        contentType = remote.contentType.ifBlank { existing?.contentType ?: "article" },
        status = remote.status,
        isFavorite = remote.isFavorite,
        isCachedOffline = isCachedOffline,
        publishedAt = remote.publishedAt ?: existing?.publishedAt,
        createdAt = remote.createdAt.ifBlank { existing?.createdAt.orEmpty() },
        readProgress = remote.readProgress,
        thumbnailMediaId =
            remote.thumbnailMediaId?.takeIf { it.isNotBlank() } ?: existing?.thumbnailMediaId,
        duration = remote.duration?.takeIf { it.isNotBlank() } ?: existing?.duration,
    )

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
    )

internal fun createSavedItemEntity(
    url: String,
    title: String,
    hostTitle: String,
    type: ContentType,
): ItemEntity {
    val id = "item-${UUID.randomUUID()}"
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
