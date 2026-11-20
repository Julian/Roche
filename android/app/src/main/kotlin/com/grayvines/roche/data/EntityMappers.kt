@file:Suppress("MatchingDeclarationName")

package com.grayvines.roche.data

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.deterministicItemId
import com.grayvines.roche.core.deterministicTagId
import com.grayvines.roche.core.isLocalPollNewer
import com.grayvines.roche.core.normalizeConsecutiveFailures
import com.grayvines.roche.core.normalizeTagName
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.ItemTagRow
import com.grayvines.roche.data.local.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.local.MAX_SQLITE_SUMMARY_CHARS
import com.grayvines.roche.data.local.MAX_SQLITE_TEXT_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TagRow
import com.grayvines.roche.data.local.insertItemTag
import com.grayvines.roche.data.local.upsertTag
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.sync.PeerNodeDto
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
    )

@Suppress("CyclomaticComplexMethod")
internal fun toRemoteFeedEntity(
    remote: RemoteFeedDto,
    existing: FeedRow? = null,
): FeedRow {
    val useExistingPoll =
        existing != null && isLocalPollNewer(existing.lastPolled, remote.lastPolled)
    val pollSource = if (useExistingPoll) existing else null
    val resolvedLastError =
        (if (pollSource != null) pollSource.lastError else remote.lastError)?.takeIf {
            it.isNotBlank()
        }
    val resolvedLastErrorBy =
        when {
            resolvedLastError == null -> null
            pollSource != null -> pollSource.lastErrorBy
            else -> remote.lastErrorBy
        }
    val resolvedConsecutiveFailures =
        normalizeConsecutiveFailures(
            resolvedLastError,
            if (pollSource != null) pollSource.consecutiveFailures else remote.consecutiveFailures,
        )
    val resolvedFeedType =
        if (
            existing?.feedType?.equals("podcast", ignoreCase = true) == true &&
                remote.feedType == "rss"
        ) {
            "podcast"
        } else {
            remote.feedType
        }
    return FeedRow(
        id = remote.id,
        title = remote.title.ifBlank { existing?.title.orEmpty() },
        url = remote.url.ifBlank { existing?.url.orEmpty() },
        siteUrl = remote.siteUrl ?: existing?.siteUrl,
        feedType = resolvedFeedType,
        description = remote.description ?: existing?.description,
        language = remote.language ?: existing?.language,
        pollInterval = remote.pollInterval,
        lastPolled = if (pollSource != null) pollSource.lastPolled else remote.lastPolled,
        lastPolledBy = if (pollSource != null) pollSource.lastPolledBy else remote.lastPolledBy,
        lastError = resolvedLastError,
        lastErrorBy = resolvedLastErrorBy,
        consecutiveFailures = resolvedConsecutiveFailures,
        isActive = remote.isActive,
        autoDownloadEpisodes = existing?.autoDownloadEpisodes ?: false,
        customPlaybackSpeed = existing?.customPlaybackSpeed,
        hlc = existing?.hlc.orEmpty(),
        isLocalDirty = false,
    )
}

private fun String?.nonBlankOr(fallback: String?): String? =
    this?.takeIf { it.isNotBlank() } ?: fallback

internal fun mergeRemoteItemIntoLocal(
    local: InboxItemRow,
    remote: RemoteItemDto,
): InboxItemRow =
    local.copy(
        title = remote.title.ifBlank { local.title },
        author = remote.author.nonBlankOr(local.author),
        summary = remote.summary.nonBlankOr(local.summary)?.take(MAX_SQLITE_SUMMARY_CHARS),
        contentText =
            remote.contentText
                .nonBlankOr(local.contentText?.takeIf { it.isNotBlank() && it != local.summary })
                ?.take(MAX_SQLITE_TEXT_CHARS),
        contentHtml = remote.contentHtml.nonBlankOr(local.contentHtml)?.take(MAX_SQLITE_HTML_CHARS),
        thumbnailMediaId = remote.thumbnailMediaId.nonBlankOr(local.thumbnailMediaId),
        duration = remote.duration.nonBlankOr(local.duration),
        thumbnailUrl = remote.thumbnailUrl.nonBlankOr(local.thumbnailUrl),
        audioUrl = remote.audioUrl.nonBlankOr(local.audioUrl),
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
        createdAt = nowUtcIso(),
        readProgress = 0.0,
        thumbnailMediaId = null,
        duration = null,
        audioUrl = if (type == ContentType.AUDIO) url else null,
        inboxState = inboxState,
    )
}

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
