@file:Suppress("MatchingDeclarationName")

package com.grayvines.roche.data

import com.grayvines.roche.core.deterministicItemId
import com.grayvines.roche.core.normalizeConsecutiveFailures
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.ItemTagRow
import com.grayvines.roche.data.local.TagRow
import com.grayvines.roche.data.remote.PeerNodeDto
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem

internal fun groupSortedTagNamesByItemId(
    tags: List<TagRow>,
    itemTags: List<ItemTagRow>,
): Map<String, List<String>> {
    val tagsById = tags.associateBy { it.id }
    return itemTags
        .groupBy(
            keySelector = { it.itemId },
            valueTransform = { tagsById[it.tagId]?.name },
        )
        .mapValues { (_, names) ->
            names
                .filterNotNull()
                .sortedWith(
                    compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it }.thenBy { it }
                )
        }
}

internal class InboxEntryCache {
    private data class CachedEntry(
        val entity: InboxItemRow,
        val tags: List<String>,
        val entry: InboxEntry,
    )

    private var byId: Map<String, CachedEntry> = emptyMap()

    @Synchronized
    fun mapEntries(
        items: List<InboxItemRow>,
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
    items: List<InboxItemRow>,
    feeds: List<FeedRow>,
    tags: List<TagRow>,
    itemTags: List<ItemTagRow>,
    activePeers: List<PeerNodeDto> = emptyList(),
    entryCache: InboxEntryCache? = null,
): RepositorySnapshot {
    val tagNamesByItemId = groupSortedTagNamesByItemId(tags, itemTags)

    val domainEntries =
        if (entryCache != null) {
            entryCache.mapEntries(items = items, tagNamesByItemId = tagNamesByItemId)
        } else {
            items.map { entity -> toInboxEntry(entity, tagNamesByItemId[entity.id].orEmpty()) }
        }

    val unreadByFeedId =
        domainEntries
            .filter { it.isNew && it.feedId != null }
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
                    lastPolled = feed.lastPolled,
                    lastPolledBy = feed.lastPolledBy,
                    lastError = feed.lastError,
                    lastErrorBy = feed.lastErrorBy,
                    consecutiveFailures =
                        normalizeConsecutiveFailures(feed.lastError, feed.consecutiveFailures),
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
            .filter { it.isNew }
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

internal fun toInboxEntry(entity: InboxItemRow, tags: List<String>): InboxEntry =
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
        inFeedInbox = entity.feedId != null && !entity.inboxState.equals("none", ignoreCase = true),
        entryKind = EntryKind.fromWireValue(entity.entryKind),
        isFinished = entity.isFinished,
    )

internal fun createSavedItemEntity(
    url: String,
    title: String,
    hostTitle: String,
    type: ContentType,
    inboxState: String = "unread",
    explicitId: String? = null,
): InboxItemRow {
    val id = explicitId ?: deterministicItemId(null, url)
    return InboxItemRow(
        id = id,
        feedId = null,
        feedTitle =
            when (type) {
                ContentType.PDF -> "arXiv / PDF"
                ContentType.VIDEO -> "YouTube"
                ContentType.AUDIO -> "Audio"
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
        createdAt = nowUtcIso(),
        readProgress = 0.0,
        thumbnailMediaId = null,
        duration = null,
        audioUrl = if (type == ContentType.AUDIO) url else null,
        inboxState = inboxState,
    )
}
