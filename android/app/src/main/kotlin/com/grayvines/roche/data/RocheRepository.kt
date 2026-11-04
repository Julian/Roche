package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.SeedData
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RemoteTagDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.remote.inferFeedTypeWireValue
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.settings.normalizeServerUrl
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

data class SaveUrlResult(val title: String, val isFeed: Boolean)

data class RepositorySnapshot(
    val entries: List<InboxEntry>,
    val feeds: List<FeedItem>,
    val tags: List<TagItem>,
)

class RocheRepository(
    private val database: RocheDatabase,
    private val apiClient: RocheApiClient,
    private val settings: SettingsRepository,
) {
    private val dao = database.dao()

    val snapshot: Flow<RepositorySnapshot> =
        combine(
            dao.observeItems(),
            dao.observeFeeds(),
            dao.observeTags(),
            dao.observeItemTags(),
        ) { items, feeds, tags, itemTags ->
            buildSnapshot(items = items, feeds = feeds, tags = tags, itemTags = itemTags)
        }

    internal suspend fun ensureSeeded() {
        if (dao.countItems() == 0 && settings.settings.first().bearerToken.isBlank()) {
            dao.upsertFeeds(SeedData.feeds)
            dao.upsertTags(SeedData.tags)
            dao.upsertItems(SeedData.items)
            dao.insertItemTags(SeedData.itemTags)
        }
    }

    suspend fun setArchiveState(itemId: String, archived: Boolean): ItemStatus? {
        val previous = dao.getItemById(itemId)?.let { ItemStatus.fromWireValue(it.status) }
        val newStatus = if (archived) ItemStatus.ARCHIVED else ItemStatus.UNREAD
        dao.updateItemStatus(itemId, newStatus.wireValue)
        pushStatusBestEffort(itemId = itemId, status = newStatus.wireValue)
        return previous
    }

    suspend fun restoreStatus(itemId: String, status: ItemStatus) {
        dao.updateItemStatus(itemId, status.wireValue)
        pushStatusBestEffort(itemId = itemId, status = status.wireValue)
    }

    suspend fun toggleStar(itemId: String): Boolean {
        val current = dao.getItemById(itemId) ?: return false
        val updated = !current.isFavorite
        dao.updateItemFavorite(itemId, updated)
        runCatching {
            val currentSettings = settings.settings.first()
            if (currentSettings.bearerToken.isNotBlank()) {
                apiClient.updateItem(
                    serverUrl = currentSettings.serverUrl,
                    token = currentSettings.bearerToken,
                    itemId = itemId,
                    isFavorite = updated,
                )
            }
        }
        return updated
    }

    suspend fun toggleOfflineCache(itemId: String): Boolean {
        val current = dao.getItemById(itemId) ?: return false
        val updated = !current.isCachedOffline
        dao.updateItemCachedOffline(itemId, updated)
        return updated
    }

    suspend fun toggleTagOnItem(itemId: String, tagName: String) {
        val normalized = tagName.trim().lowercase()
        if (normalized.isBlank()) return
        val existingTag =
            dao.getTagByName(normalized)
                ?: TagEntity(id = newTagId(), name = normalized).also { dao.upsertTag(it) }
        val alreadyAssigned = dao.hasItemTag(itemId = itemId, tagId = existingTag.id) > 0
        if (alreadyAssigned) {
            dao.deleteItemTag(itemId = itemId, tagId = existingTag.id)
            runCatching {
                val currentSettings = settings.settings.first()
                if (currentSettings.bearerToken.isNotBlank()) {
                    apiClient.removeTagFromItem(
                        serverUrl = currentSettings.serverUrl,
                        token = currentSettings.bearerToken,
                        itemId = itemId,
                        tagId = existingTag.id,
                    )
                }
            }
        } else {
            dao.insertItemTag(ItemTagEntity(itemId = itemId, tagId = existingTag.id))
            runCatching {
                val currentSettings = settings.settings.first()
                if (currentSettings.bearerToken.isNotBlank()) {
                    val remoteTags =
                        apiClient.addTagToItem(
                            serverUrl = currentSettings.serverUrl,
                            token = currentSettings.bearerToken,
                            itemId = itemId,
                            tagName = normalized,
                        )
                    reconcileAddedRemoteTag(
                        dao = dao,
                        itemId = itemId,
                        localTag = existingTag,
                        normalizedName = normalized,
                        remoteTags = remoteTags,
                    )
                }
            }
        }
    }

    suspend fun saveUrlOrFeed(
        inputUrl: String,
        titleOverride: String?,
        tagCsv: String,
    ): SaveUrlResult {
        val trimmedUrl = inputUrl.trim()
        if (isLikelyFeedUrl(trimmedUrl)) {
            val feedTitle =
                subscribeFeedBestEffort(
                    dao = dao,
                    apiClient = apiClient,
                    settings = settings,
                    url = trimmedUrl,
                    titleOverride = titleOverride,
                )
            return SaveUrlResult(title = feedTitle, isFeed = true)
        }
        val tags = parseTagCsv(tagCsv)
        val inferredType = inferContentType(trimmedUrl)
        val hostTitle = extractHostTitle(trimmedUrl)
        val resolvedTitle =
            titleOverride?.trim()?.takeIf { it.isNotBlank() } ?: "$hostTitle — Saved Entry"
        val localEntity = createSavedItemEntity(trimmedUrl, resolvedTitle, hostTitle, inferredType)

        dao.upsertItem(localEntity)
        attachTagsLocally(dao = dao, itemId = localEntity.id, tagNames = tags)

        val remoteSaved = pushSavedUrlBestEffort(trimmedUrl, titleOverride, tags)
        val finalEntity =
            if (
                remoteSaved != null &&
                    remoteSaved.id.isNotBlank() &&
                    remoteSaved.id != localEntity.id
            ) {
                val reconciled =
                    localEntity.copy(
                        id = remoteSaved.id,
                        createdAt = remoteSaved.createdAt.ifBlank { localEntity.createdAt },
                    )
                dao.deleteItemTagsForItem(localEntity.id)
                dao.deleteItemById(localEntity.id)
                dao.upsertItem(reconciled)
                attachTagsLocally(dao = dao, itemId = reconciled.id, tagNames = tags)
                reconciled
            } else {
                localEntity
            }
        return SaveUrlResult(title = finalEntity.title, isFeed = false)
    }

    suspend fun pairWithCode(
        serverUrl: String,
        pairingCode: String,
        deviceName: String,
    ): Result<String> = runCatching {
        val normalizedUrl = normalizeServerUrl(serverUrl)
        check(normalizedUrl.isNotBlank()) { "Enter your Roche Server URL" }
        val token =
            apiClient.pairDevice(
                serverUrl = normalizedUrl,
                code = pairingCode.trim(),
                deviceName = deviceName.trim().ifBlank { "Roche Android" },
            )
        settings.update { it.copy(serverUrl = normalizedUrl, bearerToken = token) }
        token
    }

    suspend fun syncWithServer(): Result<Int> = runCatching {
        val currentSettings = settings.settings.first()
        check(currentSettings.serverUrl.isNotBlank()) { "Configure Server URL in Settings" }
        val remoteFeeds =
            apiClient.fetchFeeds(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
            )
        val remoteItems =
            apiClient.fetchItems(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
            )
        val remoteTags =
            apiClient.fetchTags(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
            )

        reconcileRemoteSnapshot(
            dao = dao,
            remoteFeeds = remoteFeeds,
            remoteItems = remoteItems,
            remoteTags = remoteTags,
        )
        remoteItems.size
    }

    private suspend fun pushSavedUrlBestEffort(
        url: String,
        titleOverride: String?,
        tags: List<String>,
    ): RemoteItemDto? = runCatching {
        val currentSettings = settings.settings.first()
        if (currentSettings.bearerToken.isBlank()) return null
        apiClient.saveItemUrl(
            serverUrl = currentSettings.serverUrl,
            token = currentSettings.bearerToken,
            url = url,
            title = titleOverride,
            tags = tags,
        )
    }
        .getOrNull()

    private suspend fun pushStatusBestEffort(itemId: String, status: String) {
        runCatching {
            val currentSettings = settings.settings.first()
            if (currentSettings.bearerToken.isNotBlank()) {
                apiClient.updateItem(
                    serverUrl = currentSettings.serverUrl,
                    token = currentSettings.bearerToken,
                    itemId = itemId,
                    status = status,
                )
            }
        }
    }

    companion object {
        fun filterEntries(
            entries: List<InboxEntry>,
            filter: InboxFilter,
            searchQuery: String = "",
            selectedTag: String? = null,
            selectedFeedId: String? = null,
        ): List<InboxEntry> {
            val normalizedQuery = searchQuery.trim().lowercase()
            return entries.filter { entry ->
                matchesScope(entry, filter, selectedTag, selectedFeedId) &&
                    matchesSearchQuery(entry, normalizedQuery)
            }
        }
    }
}

private const val DEFAULT_SAVED_HOST = "Saved Link"

internal fun newTagId(): String = "tag-${UUID.randomUUID()}"

internal suspend fun reconcileAddedRemoteTag(
    dao: com.grayvines.roche.data.local.RocheDao,
    itemId: String,
    localTag: TagEntity,
    normalizedName: String,
    remoteTags: List<RemoteTagDto>,
) {
    val remoteMatch =
        remoteTags.firstOrNull { it.name.trim().equals(normalizedName, ignoreCase = true) }
            ?: return
    if (remoteMatch.id.isNotBlank() && remoteMatch.id != localTag.id) {
        dao.deleteItemTag(itemId = itemId, tagId = localTag.id)
        dao.deleteTagById(id = localTag.id)
        dao.upsertTag(TagEntity(id = remoteMatch.id, name = normalizedName))
        dao.insertItemTag(ItemTagEntity(itemId = itemId, tagId = remoteMatch.id))
    }
}

internal suspend fun subscribeFeedBestEffort(
    dao: com.grayvines.roche.data.local.RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    url: String,
    titleOverride: String?,
): String {
    val fallbackTitle = titleOverride?.trim()?.takeIf { it.isNotBlank() } ?: extractHostTitle(url)
    val remoteFeed = runCatching {
        val currentSettings = settings.settings.first()
        if (currentSettings.bearerToken.isNotBlank()) {
            apiClient.createFeed(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                url = url,
                title = titleOverride?.trim()?.takeIf { it.isNotBlank() },
            )
        } else {
            null
        }
    }
        .getOrNull()
    val entity =
        if (remoteFeed != null && remoteFeed.id.isNotBlank()) {
            FeedEntity(
                id = remoteFeed.id,
                title = remoteFeed.title.ifBlank { fallbackTitle },
                url = remoteFeed.url.ifBlank { url },
                siteUrl = remoteFeed.siteUrl,
                feedType = remoteFeed.feedType,
            )
        } else {
            FeedEntity(
                id = "feed-${UUID.randomUUID()}",
                title = fallbackTitle,
                url = url,
                siteUrl = null,
                feedType = inferFeedTypeWireValue(url),
            )
        }
    dao.upsertFeed(entity)
    return entity.title
}

internal suspend fun attachTagsLocally(
    dao: com.grayvines.roche.data.local.RocheDao,
    itemId: String,
    tagNames: List<String>,
) {
    tagNames.forEach { tagName ->
        val normalized = tagName.trim().lowercase()
        if (normalized.isNotBlank()) {
            val tag =
                dao.getTagByName(normalized)
                    ?: TagEntity(id = newTagId(), name = normalized).also { dao.upsertTag(it) }
            dao.insertItemTag(ItemTagEntity(itemId = itemId, tagId = tag.id))
        }
    }
}

internal suspend fun reconcileRemoteSnapshot(
    dao: com.grayvines.roche.data.local.RocheDao,
    remoteFeeds: List<RemoteFeedDto>,
    remoteItems: List<RemoteItemDto>,
    remoteTags: List<RemoteTagDto>,
) {
    val existingItemsById = dao.getAllItems().associateBy { it.id }
    val feedTitlesById = remoteFeeds.associate { it.id to it.title }
    val tagEntitiesByName = buildRemoteTagEntityMap(remoteTags, remoteItems)
    val itemTagEntities = remoteItems.flatMap { remote ->
        remote.tagNames.mapNotNull { rawName ->
            tagEntitiesByName[rawName.trim().lowercase()]?.let { tag ->
                ItemTagEntity(itemId = remote.id, tagId = tag.id)
            }
        }
    }

    dao.replaceSnapshotAtomically(
        feeds =
            remoteFeeds.map {
                FeedEntity(
                    id = it.id,
                    title = it.title,
                    url = it.url,
                    siteUrl = it.siteUrl,
                    feedType = it.feedType,
                )
            },
        tags = tagEntitiesByName.values.toList(),
        items =
            remoteItems.map { remote ->
                toRemoteItemEntity(
                    remote = remote,
                    feedTitlesById = feedTitlesById,
                    isCachedOffline = existingItemsById[remote.id]?.isCachedOffline ?: false,
                )
            },
        itemTags = itemTagEntities,
    )
}

private fun buildRemoteTagEntityMap(
    remoteTags: List<RemoteTagDto>,
    remoteItems: List<RemoteItemDto>,
): Map<String, TagEntity> {
    val map = mutableMapOf<String, TagEntity>()
    remoteTags.forEach { remoteTag ->
        val normalized = remoteTag.name.trim().lowercase()
        if (normalized.isNotBlank()) {
            map[normalized] = TagEntity(id = remoteTag.id, name = normalized)
        }
    }
    remoteItems
        .flatMap { it.tagNames }
        .forEach { rawName ->
            val normalized = rawName.trim().lowercase()
            if (normalized.isNotBlank() && normalized !in map) {
                map[normalized] = TagEntity(id = newTagId(), name = normalized)
            }
        }
    return map
}

internal fun isLikelyFeedUrl(url: String): Boolean {
    val lower = url.lowercase().substringBefore('?')
    return lower.endsWith(".xml") ||
        lower.endsWith(".rss") ||
        lower.endsWith(".atom") ||
        lower.endsWith("/rss") ||
        lower.endsWith("/feed") ||
        lower.endsWith("/atom") ||
        lower.contains("rss.arxiv.org") ||
        lower.contains("export.arxiv.org/api/query") ||
        lower.contains("feeds/videos.xml")
}

internal fun toRemoteItemEntity(
    remote: RemoteItemDto,
    feedTitlesById: Map<String, String>,
    isCachedOffline: Boolean,
): ItemEntity =
    ItemEntity(
        id = remote.id,
        feedId = remote.feedId,
        feedTitle =
            remote.feedTitle ?: remote.feedId?.let { feedTitlesById[it] } ?: DEFAULT_SAVED_HOST,
        title = remote.title,
        url = remote.url,
        author = remote.author,
        summary = remote.summary,
        contentText = remote.contentText,
        contentHtml = remote.contentHtml,
        contentType = remote.contentType,
        status = remote.status,
        isFavorite = remote.isFavorite,
        isCachedOffline = isCachedOffline,
        publishedAt = remote.publishedAt,
        createdAt = remote.createdAt,
        readProgress = remote.readProgress,
        thumbnailMediaId = remote.thumbnailMediaId,
        duration = remote.duration,
    )

internal fun matchesScope(
    entry: InboxEntry,
    filter: InboxFilter,
    selectedTag: String?,
    selectedFeedId: String?,
): Boolean =
    when {
        selectedTag != null ->
            !entry.isArchived && entry.tags.any { it.equals(selectedTag, ignoreCase = true) }
        selectedFeedId != null -> !entry.isArchived && entry.feedId == selectedFeedId
        else -> matchesInboxFilter(entry, filter)
    }

private fun matchesInboxFilter(entry: InboxEntry, filter: InboxFilter): Boolean =
    when (filter) {
        InboxFilter.UNREAD -> entry.status == ItemStatus.UNREAD
        InboxFilter.ARTICLES -> !entry.isArchived && entry.contentType == ContentType.ARTICLE
        InboxFilter.PAPERS -> !entry.isArchived && entry.contentType == ContentType.PDF
        InboxFilter.VIDEOS -> !entry.isArchived && entry.contentType == ContentType.VIDEO
        InboxFilter.STARRED -> entry.isFavorite
        InboxFilter.ARCHIVED -> entry.isArchived
    }

internal fun matchesSearchQuery(entry: InboxEntry, normalizedQuery: String): Boolean {
    if (normalizedQuery.isBlank()) return true
    val cleanQuery = normalizedQuery.trimStart('#')
    if (cleanQuery.isBlank()) return true
    return entry.title.lowercase().contains(cleanQuery) ||
        entry.feedTitle.lowercase().contains(cleanQuery) ||
        entry.author.orEmpty().lowercase().contains(cleanQuery) ||
        entry.url.lowercase().contains(cleanQuery) ||
        entry.summary.orEmpty().lowercase().contains(cleanQuery) ||
        entry.contentText.orEmpty().lowercase().contains(cleanQuery) ||
        entry.contentHtml.orEmpty().lowercase().contains(cleanQuery) ||
        entry.tags.any { it.lowercase().contains(cleanQuery) }
}

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

internal fun parseTagCsv(tagCsv: String): List<String> =
    tagCsv
        .split(',')
        .map { it.trim().lowercase().trimStart('#') }
        .filter { it.isNotBlank() }
        .distinct()

internal fun inferContentType(url: String): ContentType =
    when {
        url.contains("arxiv.org", ignoreCase = true) || url.endsWith(".pdf", ignoreCase = true) ->
            ContentType.PDF
        url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true) -> ContentType.VIDEO
        else -> ContentType.ARTICLE
    }

internal fun extractHostTitle(url: String): String {
    val parsedHost = runCatching { URI.create(url).host?.removePrefix("www.") }.getOrNull()
    return if (!parsedHost.isNullOrBlank()) parsedHost else DEFAULT_SAVED_HOST
}

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
        summary = "Saved from Android client ($url).",
        contentText = "Saved entry from $url.",
        contentHtml = null,
        contentType = type.wireValue,
        status = ItemStatus.UNREAD.wireValue,
        isFavorite = false,
        isCachedOffline = true,
        publishedAt = "now",
        createdAt = Instant.now().toString(),
        readProgress = 0.0,
        thumbnailMediaId = if (type == ContentType.ARTICLE) null else "media-saved-$id",
        duration =
            when (type) {
                ContentType.VIDEO -> "12:00"
                ContentType.PDF -> "10p"
                ContentType.ARTICLE -> null
            },
    )
}
