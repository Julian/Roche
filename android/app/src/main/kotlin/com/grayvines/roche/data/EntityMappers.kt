@file:Suppress("MatchingDeclarationName")

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

internal class InboxEntryCache {
    private data class CachedEntry(
        val entity: ItemEntity,
        val tags: List<String>,
        val entry: InboxEntry,
    )

    private var byId: Map<String, CachedEntry> = emptyMap()

    @Synchronized
    fun mapEntries(
        items: List<ItemEntity>,
        tagNamesByItemId: Map<String, List<String>>,
    ): List<InboxEntry> {
        val previous = byId
        val next = HashMap<String, CachedEntry>(items.size)
        val result = ArrayList<InboxEntry>(items.size)
        for (entity in items) {
            val tags = tagNamesByItemId[entity.id].orEmpty()
            val cached = previous[entity.id]
            val resolved =
                if (cached != null && cached.entity == entity && cached.tags == tags) {
                    cached
                } else {
                    CachedEntry(entity = entity, tags = tags, entry = toInboxEntry(entity, tags))
                }
            next[entity.id] = resolved
            result.add(resolved.entry)
        }
        byId = next
        return result
    }
}

@Suppress("LongMethod")
internal fun buildSnapshot(
    items: List<ItemEntity>,
    feeds: List<FeedEntity>,
    tags: List<TagEntity>,
    itemTags: List<ItemTagEntity>,
    activePeers: List<PeerNodeDto> = emptyList(),
    entryCache: InboxEntryCache? = null,
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

    val domainEntries =
        if (entryCache != null) {
            entryCache.mapEntries(items = items, tagNamesByItemId = tagNamesByItemId)
        } else {
            items.map { entity -> toInboxEntry(entity, tagNamesByItemId[entity.id].orEmpty()) }
        }

    val unreadByFeedId =
        domainEntries
            .filter { it.isUnread && it.feedId != null }
            .groupingBy { it.feedId!! }
            .eachCount()

    val podcastFeedIds =
        domainEntries
            .filter { it.contentType == ContentType.AUDIO && it.feedId != null }
            .mapNotNull { it.feedId }
            .toSet()

    val domainFeeds =
        feeds
            .map { feed ->
                val baseType = FeedType.fromWireValue(feed.feedType)
                val effectiveType =
                    if (baseType == FeedType.RSS && feed.id in podcastFeedIds) {
                        FeedType.PODCAST
                    } else {
                        baseType
                    }
                FeedItem(
                    id = feed.id,
                    title = feed.title,
                    url = feed.url,
                    siteUrl = feed.siteUrl,
                    feedType = effectiveType,
                    unreadCount = unreadByFeedId[feed.id] ?: 0,
                    lastPolled = feed.lastPolled,
                    lastPolledBy = feed.lastPolledBy,
                    lastError = feed.lastError,
                    lastErrorBy = feed.lastErrorBy,
                    isActive = feed.isActive,
                    autoDownloadEpisodes = feed.autoDownloadEpisodes,
                    customPlaybackSpeed = feed.customPlaybackSpeed,
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
        activePeers = activePeers,
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
        audioUrl = entity.audioUrl,
    )

@Suppress("CyclomaticComplexMethod")
internal fun toRemoteFeedEntity(
    remote: RemoteFeedDto,
    existing: FeedEntity? = null,
): FeedEntity {
    val useExistingPoll =
        existing != null &&
            existing.lastPolled != null &&
            (remote.lastPolled == null || existing.lastPolled > remote.lastPolled)
    val pollSource = if (useExistingPoll) existing else null
    val resolvedFeedType =
        if (
            existing?.feedType?.equals("podcast", ignoreCase = true) == true &&
                remote.feedType == "rss"
        ) {
            "podcast"
        } else {
            remote.feedType
        }
    return FeedEntity(
        id = remote.id,
        title = remote.title.ifBlank { existing?.title.orEmpty() },
        url = remote.url.ifBlank { existing?.url.orEmpty() },
        siteUrl = remote.siteUrl ?: existing?.siteUrl,
        feedType = resolvedFeedType,
        description = remote.description ?: existing?.description,
        language = remote.language ?: existing?.language,
        pollInterval = remote.pollInterval,
        lastPolled = pollSource?.lastPolled ?: remote.lastPolled,
        lastPolledBy = pollSource?.lastPolledBy ?: remote.lastPolledBy,
        lastError = pollSource?.lastError ?: remote.lastError,
        lastErrorBy = pollSource?.lastErrorBy ?: remote.lastErrorBy,
        isActive = remote.isActive,
        autoDownloadEpisodes = existing?.autoDownloadEpisodes ?: false,
        customPlaybackSpeed = existing?.customPlaybackSpeed,
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
    val richerSummary =
        pickRicherString(existing?.summary, remote.summary)?.take(MAX_SQLITE_SUMMARY_CHARS)
    val richerText =
        remote.contentText?.takeIf { it.isNotBlank() }?.take(MAX_SQLITE_TEXT_CHARS)
            ?: existing
                ?.contentText
                ?.takeIf { it.isNotBlank() && it != existing.summary }
                ?.take(MAX_SQLITE_TEXT_CHARS)
    val richerHtml =
        pickRicherString(existing?.contentHtml, remote.contentHtml)?.take(MAX_SQLITE_HTML_CHARS)
    val resolvedAudioUrl = remote.audioUrl.nonBlankOr(existing?.audioUrl)
    val resolvedContentType =
        if (!resolvedAudioUrl.isNullOrBlank()) {
            ContentType.AUDIO.wireValue
        } else {
            remote.contentType.ifBlank { existing?.contentType ?: "article" }
        }
    return ItemEntity(
        id = remote.id,
        feedId = resolvedFeedId,
        feedTitle = resolveRemoteFeedTitle(remote, feedTitlesById, existing, resolvedFeedId),
        title = remote.title.ifBlank { existing?.title.orEmpty() },
        url = remote.url.ifBlank { existing?.url.orEmpty() },
        author = remote.author.nonBlankOr(existing?.author),
        summary = richerSummary,
        contentText = richerText,
        contentHtml = richerHtml,
        contentType = resolvedContentType,
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
        audioUrl = resolvedAudioUrl,
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
    resolvedFeedId: String?,
): String =
    resolvedFeedId?.let { feedTitlesById[it] }?.takeIf { it.isNotBlank() }
        ?: remote.feedTitle?.takeIf { it.isNotBlank() }
        ?: existing?.feedTitle?.takeIf { it.isNotBlank() }
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
        summary = (remoteSaved.summary ?: localEntity.summary)?.take(MAX_SQLITE_SUMMARY_CHARS),
        contentText =
            (remoteSaved.contentText ?: localEntity.contentText)?.take(MAX_SQLITE_TEXT_CHARS),
        contentHtml =
            (remoteSaved.contentHtml?.takeIf { it.isNotBlank() } ?: localEntity.contentHtml)?.take(
                MAX_SQLITE_HTML_CHARS
            ),
        contentType = remoteSaved.contentType.ifBlank { localEntity.contentType },
        createdAt = remoteSaved.createdAt.ifBlank { localEntity.createdAt },
        thumbnailMediaId = remoteSaved.thumbnailMediaId ?: localEntity.thumbnailMediaId,
        duration = remoteSaved.duration ?: localEntity.duration,
        thumbnailUrl = remoteSaved.thumbnailUrl ?: localEntity.thumbnailUrl,
        audioUrl = remoteSaved.audioUrl ?: localEntity.audioUrl,
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
                ContentType.AUDIO -> "Podcast / Audio"
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
        audioUrl = if (type == ContentType.AUDIO) url else null,
    )
}

internal const val MAX_SQLITE_SUMMARY_CHARS = 16_384
internal const val MAX_SQLITE_TEXT_CHARS = 262_144
internal const val MAX_SQLITE_HTML_CHARS = 512_000
