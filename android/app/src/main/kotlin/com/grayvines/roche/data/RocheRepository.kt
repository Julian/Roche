package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.remote.ProblemDetailsException
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

    suspend fun setArchiveState(itemId: String, archived: Boolean): ItemStatus? {
        val previous = dao.getItemById(itemId)?.let { ItemStatus.fromWireValue(it.status) }
        val newStatus = if (archived) ItemStatus.ARCHIVED else ItemStatus.UNREAD
        dao.updateItemStatus(itemId, newStatus.wireValue)
        syncStatusMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            status = newStatus.wireValue,
        )
        return previous
    }

    suspend fun restoreStatus(itemId: String, status: ItemStatus) {
        dao.updateItemStatus(itemId, status.wireValue)
        syncStatusMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            status = status.wireValue,
        )
    }

    suspend fun toggleStar(itemId: String): Boolean {
        val current = dao.getItemById(itemId) ?: return false
        val updated = !current.isFavorite
        dao.updateItemFavorite(itemId, updated)
        syncFavoriteMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            isFavorite = updated,
        )
        return updated
    }

    suspend fun toggleOfflineCache(itemId: String): Boolean {
        val current = dao.getItemById(itemId) ?: return false
        val updated = !current.isCachedOffline
        dao.updateItemCachedOffline(itemId, updated)
        return updated
    }

    suspend fun updateReadProgress(itemId: String, progress: Double) {
        if (!progress.isFinite()) return
        val clamped = progress.coerceIn(MIN_READ_PROGRESS, MAX_READ_PROGRESS)
        dao.updateReadProgress(itemId, clamped)
        syncReadProgressMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            progress = clamped,
        )
    }

    suspend fun toggleTagOnItem(itemId: String, tagName: String) {
        val normalized = normalizeTagName(tagName)
        if (normalized.isBlank()) return
        val existingTag =
            dao.getTagByName(normalized)
                ?: TagEntity(id = newTagId(), name = normalized).also { dao.upsertTag(it) }
        val alreadyAssigned = dao.hasItemTag(itemId = itemId, tagId = existingTag.id) > 0
        if (alreadyAssigned) {
            dao.deleteItemTag(itemId = itemId, tagId = existingTag.id)
            syncRemoveTagMutation(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                existingTag = existingTag,
                normalizedName = normalized,
            )
        } else {
            dao.insertItemTag(ItemTagEntity(itemId = itemId, tagId = existingTag.id))
            syncAddTagMutation(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                existingTag = existingTag,
                normalizedName = normalized,
            )
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
        val cleanTitleOverride = titleOverride?.trim()?.takeIf { it.isNotBlank() }
        val resolvedTitle = cleanTitleOverride ?: "$hostTitle — Saved Entry"
        val localEntity = createSavedItemEntity(trimmedUrl, resolvedTitle, hostTitle, inferredType)

        dao.upsertItem(localEntity)
        attachTagsLocally(dao = dao, itemId = localEntity.id, tagNames = tags)
        dao.upsertPendingMutation(
            PendingMutationEntity(
                entityId = localEntity.id,
                isLocalItem = true,
                titleOverride = cleanTitleOverride,
            )
        )

        val remoteSaved = pushSavedUrlBestEffort(trimmedUrl, cleanTitleOverride, tags)
        val finalEntity =
            if (remoteSaved != null && remoteSaved.id.isNotBlank()) {
                finalizeOnlineSavedItem(
                    dao = dao,
                    apiClient = apiClient,
                    settings = settings,
                    localEntity = localEntity,
                    remoteSaved = remoteSaved,
                    tags = tags,
                )
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
        val pushOutcome =
            if (currentSettings.bearerToken.isNotBlank()) {
                pushPendingMutations(
                    dao = dao,
                    apiClient = apiClient,
                    serverUrl = currentSettings.serverUrl,
                    token = currentSettings.bearerToken,
                )
            } else {
                PushMutationsOutcome()
            }
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
        val mergedFeeds = mergePushedFeeds(remoteFeeds, pushOutcome.createdFeeds)
        val mergedItems = mergePushedItems(remoteItems, pushOutcome.savedItems)

        reconcileRemoteSnapshot(
            dao = dao,
            remoteFeeds = mergedFeeds,
            remoteItems = mergedItems,
            remoteTags = remoteTags,
            appliedMutations = pushOutcome.appliedMutations,
        )
        mergedItems.size
    }

    internal suspend fun pushSavedUrlBestEffort(
        url: String,
        titleOverride: String?,
        tags: List<String>,
    ): RemoteItemDto? = runCatching {
        val currentSettings = settings.settings.first()
        if (currentSettings.serverUrl.isBlank() || currentSettings.bearerToken.isBlank()) {
            null
        } else {
            apiClient.saveItemUrl(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                url = url,
                title = titleOverride,
                tags = tags,
            )
        }
    }
        .getOrNull()

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
private const val LOCAL_TAG_PREFIX = "tag-"
private const val SERVER_TAG_PREFIX = "tag-srv-"
private const val MIN_READ_PROGRESS = 0.0
private const val MAX_READ_PROGRESS = 1.0
private const val HTTP_NOT_FOUND = 404

internal data class PushMutationsOutcome(
    val createdFeeds: List<RemoteFeedDto> = emptyList(),
    val savedItems: List<RemoteItemDto> = emptyList(),
    val appliedMutations: List<PendingMutationEntity> = emptyList(),
)

internal fun newTagId(): String = "$LOCAL_TAG_PREFIX${UUID.randomUUID()}"

internal fun isLocalTagId(id: String): Boolean =
    id.startsWith(LOCAL_TAG_PREFIX) && !id.startsWith(SERVER_TAG_PREFIX)

internal fun normalizeTagName(raw: String): String =
    raw.replace(",", "").trim().lowercase().trimStart('#')

internal fun isCleanMutation(mutation: PendingMutationEntity): Boolean =
    !mutation.isLocalFeed &&
        !mutation.isLocalItem &&
        mutation.dirtyStatus == null &&
        mutation.dirtyFavorite == null &&
        mutation.dirtyReadProgress == null &&
        mutation.addedTagsCsv.isBlank() &&
        mutation.removedTagsCsv.isBlank()

internal suspend fun upsertOrCleanPendingMutation(
    dao: RocheDao,
    mutation: PendingMutationEntity,
) {
    if (isCleanMutation(mutation)) {
        dao.deletePendingMutation(mutation.entityId)
    } else {
        dao.upsertPendingMutation(mutation)
    }
}

internal suspend fun finalizeOnlineSavedItem(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    localEntity: ItemEntity,
    remoteSaved: RemoteItemDto,
    tags: List<String>,
): ItemEntity {
    val inFlight = dao.getPendingMutation(localEntity.id)
    val currentLocal = dao.getItemById(localEntity.id) ?: localEntity
    dao.deletePendingMutation(localEntity.id)
    val reconciled = reconcileSavedItemEntity(currentLocal, remoteSaved)
    if (remoteSaved.id != localEntity.id) {
        dao.deleteItemTagsForItem(localEntity.id)
        dao.deleteItemById(localEntity.id)
    }
    dao.upsertItem(reconciled)
    attachTagsLocally(dao = dao, itemId = reconciled.id, tagNames = tags)
    if (inFlight != null && hasDirtyItemFields(inFlight)) {
        val pushed =
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = reconciled.id,
                status = inFlight.dirtyStatus,
                isFavorite = inFlight.dirtyFavorite,
                readProgress = inFlight.dirtyReadProgress,
            )
        if (!pushed) {
            upsertOrCleanPendingMutation(
                dao = dao,
                mutation =
                    inFlight.copy(
                        entityId = reconciled.id,
                        isLocalItem = false,
                        titleOverride = null,
                    ),
            )
        }
    }
    return reconciled
}

internal fun hasDirtyItemFields(mutation: PendingMutationEntity): Boolean =
    mutation.dirtyStatus != null ||
        mutation.dirtyFavorite != null ||
        mutation.dirtyReadProgress != null

internal fun reconcileSavedItemEntity(
    localEntity: ItemEntity,
    remoteSaved: RemoteItemDto,
): ItemEntity =
    localEntity.copy(
        id = remoteSaved.id,
        title = remoteSaved.title.ifBlank { localEntity.title },
        summary = remoteSaved.summary ?: localEntity.summary,
        contentText = remoteSaved.contentText ?: localEntity.contentText,
        contentHtml = remoteSaved.contentHtml ?: localEntity.contentHtml,
        contentType = remoteSaved.contentType.ifBlank { localEntity.contentType },
        createdAt = remoteSaved.createdAt.ifBlank { localEntity.createdAt },
        thumbnailMediaId = remoteSaved.thumbnailMediaId ?: localEntity.thumbnailMediaId,
        duration = remoteSaved.duration ?: localEntity.duration,
    )

internal suspend fun pushItemUpdateBestEffort(
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    status: String? = null,
    isFavorite: Boolean? = null,
    readProgress: Double? = null,
): Boolean = runCatching {
    val currentSettings = settings.settings.first()
    if (currentSettings.serverUrl.isBlank() || currentSettings.bearerToken.isBlank()) {
        false
    } else {
        apiClient.updateItem(
            serverUrl = currentSettings.serverUrl,
            token = currentSettings.bearerToken,
            itemId = itemId,
            status = status,
            isFavorite = isFavorite,
            readProgress = readProgress,
        )
        true
    }
}
    .getOrDefault(false)

internal suspend fun syncStatusMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    status: String,
) {
    val existing = dao.getPendingMutation(itemId)
    if (existing?.isLocalItem == true) {
        dao.upsertPendingMutation(existing.copy(dirtyStatus = status))
        return
    }
    val pushed =
        pushItemUpdateBestEffort(
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            status = status,
        )
    val updated =
        (existing ?: PendingMutationEntity(entityId = itemId)).copy(
            dirtyStatus = if (pushed) null else status
        )
    upsertOrCleanPendingMutation(dao = dao, mutation = updated)
}

internal suspend fun syncFavoriteMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    isFavorite: Boolean,
) {
    val existing = dao.getPendingMutation(itemId)
    if (existing?.isLocalItem == true) {
        dao.upsertPendingMutation(existing.copy(dirtyFavorite = isFavorite))
        return
    }
    val pushed =
        pushItemUpdateBestEffort(
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            isFavorite = isFavorite,
        )
    val updated =
        (existing ?: PendingMutationEntity(entityId = itemId)).copy(
            dirtyFavorite = if (pushed) null else isFavorite
        )
    upsertOrCleanPendingMutation(dao = dao, mutation = updated)
}

internal suspend fun syncReadProgressMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    progress: Double,
) {
    val existing = dao.getPendingMutation(itemId)
    if (existing?.isLocalItem == true) {
        dao.upsertPendingMutation(existing.copy(dirtyReadProgress = progress))
        return
    }
    val pushed =
        pushItemUpdateBestEffort(
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            readProgress = progress,
        )
    val updated =
        (existing ?: PendingMutationEntity(entityId = itemId)).copy(
            dirtyReadProgress = if (pushed) null else progress
        )
    upsertOrCleanPendingMutation(dao = dao, mutation = updated)
}

internal suspend fun resolveRemoteTagIdForRemoval(
    dao: RocheDao,
    existingTag: TagEntity,
    normalizedName: String,
    remoteTags: List<RemoteTagDto>,
): String? {
    if (!isLocalTagId(existingTag.id)) return existingTag.id
    val remoteMatch = remoteTags.firstOrNull {
        it.name.trim().equals(normalizedName, ignoreCase = true)
    }
    val resolvedId = remoteMatch?.id?.takeIf { it.isNotBlank() } ?: return null
    if (resolvedId != existingTag.id) {
        dao.migrateItemTagIds(oldTagId = existingTag.id, newTagId = resolvedId)
        dao.deleteTagById(id = existingTag.id)
        dao.upsertTag(TagEntity(id = resolvedId, name = normalizedName))
    }
    return resolvedId
}

internal suspend fun pushAddTagBestEffort(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    existingTag: TagEntity,
    normalizedName: String,
): Boolean = runCatching {
    val currentSettings = settings.settings.first()
    if (currentSettings.serverUrl.isBlank() || currentSettings.bearerToken.isBlank()) {
        false
    } else {
        val remoteTags =
            apiClient.addTagToItem(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                itemId = itemId,
                tagName = normalizedName,
            )
        reconcileAddedRemoteTag(
            dao = dao,
            itemId = itemId,
            localTag = existingTag,
            normalizedName = normalizedName,
            remoteTags = remoteTags,
        )
        true
    }
}
    .getOrDefault(false)

internal suspend fun pushRemoveTagBestEffort(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    existingTag: TagEntity,
    normalizedName: String,
): Boolean = runCatching {
    val currentSettings = settings.settings.first()
    if (currentSettings.serverUrl.isBlank() || currentSettings.bearerToken.isBlank()) {
        false
    } else {
        val remoteTags =
            if (isLocalTagId(existingTag.id)) {
                apiClient.fetchTags(
                    serverUrl = currentSettings.serverUrl,
                    token = currentSettings.bearerToken,
                )
            } else {
                emptyList()
            }
        val resolvedTagId =
            resolveRemoteTagIdForRemoval(
                dao = dao,
                existingTag = existingTag,
                normalizedName = normalizedName,
                remoteTags = remoteTags,
            )
        if (resolvedTagId != null) {
            apiClient.removeTagFromItem(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                itemId = itemId,
                tagId = resolvedTagId,
            )
        }
        true
    }
}
    .getOrDefault(false)

internal suspend fun syncAddTagMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    existingTag: TagEntity,
    normalizedName: String,
) {
    val existing = dao.getPendingMutation(itemId)
    if (existing?.isLocalItem == true) return
    val pushed =
        pushAddTagBestEffort(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            existingTag = existingTag,
            normalizedName = normalizedName,
        )
    val base = existing ?: PendingMutationEntity(entityId = itemId)
    val added = parseTagCsv(base.addedTagsCsv).toMutableSet()
    val removed = parseTagCsv(base.removedTagsCsv).toMutableSet()
    if (pushed) {
        added.remove(normalizedName)
        removed.remove(normalizedName)
    } else {
        added.add(normalizedName)
        removed.remove(normalizedName)
    }
    upsertOrCleanPendingMutation(
        dao = dao,
        mutation =
            base.copy(
                addedTagsCsv = added.joinToString(","),
                removedTagsCsv = removed.joinToString(","),
            ),
    )
}

internal suspend fun syncRemoveTagMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    existingTag: TagEntity,
    normalizedName: String,
) {
    val existing = dao.getPendingMutation(itemId)
    if (existing?.isLocalItem == true) return
    val base = existing ?: PendingMutationEntity(entityId = itemId)
    val added = parseTagCsv(base.addedTagsCsv).toMutableSet()
    val removed = parseTagCsv(base.removedTagsCsv).toMutableSet()
    val wasOfflineLocalAdd = normalizedName in added && isLocalTagId(existingTag.id)
    val pushed =
        if (wasOfflineLocalAdd) {
            true
        } else {
            pushRemoveTagBestEffort(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                existingTag = existingTag,
                normalizedName = normalizedName,
            )
        }
    if (pushed) {
        added.remove(normalizedName)
        removed.remove(normalizedName)
    } else {
        added.remove(normalizedName)
        removed.add(normalizedName)
    }
    upsertOrCleanPendingMutation(
        dao = dao,
        mutation =
            base.copy(
                addedTagsCsv = added.joinToString(","),
                removedTagsCsv = removed.joinToString(","),
            ),
    )
}

internal suspend fun reconcileAddedRemoteTag(
    dao: RocheDao,
    itemId: String,
    localTag: TagEntity,
    normalizedName: String,
    remoteTags: List<RemoteTagDto>,
) {
    val remoteMatch =
        remoteTags.firstOrNull { it.name.trim().equals(normalizedName, ignoreCase = true) }
            ?: return
    if (remoteMatch.id.isNotBlank() && remoteMatch.id != localTag.id) {
        dao.migrateItemTagIds(oldTagId = localTag.id, newTagId = remoteMatch.id)
        dao.deleteItemTag(itemId = itemId, tagId = localTag.id)
        dao.deleteTagById(id = localTag.id)
        dao.upsertTag(TagEntity(id = remoteMatch.id, name = normalizedName))
        dao.insertItemTag(ItemTagEntity(itemId = itemId, tagId = remoteMatch.id))
    }
}

internal suspend fun subscribeFeedBestEffort(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    url: String,
    titleOverride: String?,
): String {
    val cleanTitleOverride = titleOverride?.trim()?.takeIf { it.isNotBlank() }
    val fallbackTitle = cleanTitleOverride ?: extractHostTitle(url)
    val remoteFeed = runCatching {
        val currentSettings = settings.settings.first()
        if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
            apiClient.createFeed(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                url = url,
                title = cleanTitleOverride,
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
            val localFeed =
                FeedEntity(
                    id = "feed-${UUID.randomUUID()}",
                    title = fallbackTitle,
                    url = url,
                    siteUrl = null,
                    feedType = inferFeedTypeWireValue(url),
                )
            dao.upsertPendingMutation(
                PendingMutationEntity(
                    entityId = localFeed.id,
                    isLocalFeed = true,
                    titleOverride = cleanTitleOverride,
                )
            )
            localFeed
        }
    dao.upsertFeed(entity)
    return entity.title
}

internal suspend fun pushPendingMutations(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
): PushMutationsOutcome {
    val mutations = dao.getAllPendingMutations()
    if (mutations.isEmpty()) return PushMutationsOutcome()

    val createdFeeds = mutableListOf<RemoteFeedDto>()
    val savedItems = mutableListOf<RemoteItemDto>()
    val appliedMutations = mutableListOf<PendingMutationEntity>()

    for (mutation in mutations) {
        when {
            mutation.isLocalFeed -> {
                pushPendingLocalFeed(dao, apiClient, serverUrl, token, mutation)?.let {
                    createdFeeds.add(it)
                }
            }
            mutation.isLocalItem -> {
                pushPendingLocalItem(dao, apiClient, serverUrl, token, mutation)?.let {
                    (savedItem, applied) ->
                    savedItems.add(savedItem)
                    appliedMutations.add(applied)
                }
            }
            else -> {
                appliedMutations.add(
                    pushPendingItemMutation(dao, apiClient, serverUrl, token, mutation)
                )
            }
        }
    }
    return PushMutationsOutcome(
        createdFeeds = createdFeeds,
        savedItems = savedItems,
        appliedMutations = appliedMutations,
    )
}

internal suspend fun pushPendingLocalFeed(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    mutation: PendingMutationEntity,
): RemoteFeedDto? {
    val localFeed = dao.getFeedById(mutation.entityId)
    if (localFeed == null) {
        dao.deletePendingMutation(mutation.entityId)
        return null
    }
    val remoteFeed = runCatching {
        apiClient.createFeed(
            serverUrl = serverUrl,
            token = token,
            url = localFeed.url,
            title = mutation.titleOverride,
        )
    }
        .getOrNull()
    return if (remoteFeed != null && remoteFeed.id.isNotBlank()) {
        dao.deleteFeedById(localFeed.id)
        dao.upsertFeed(
            FeedEntity(
                id = remoteFeed.id,
                title = remoteFeed.title.ifBlank { localFeed.title },
                url = remoteFeed.url.ifBlank { localFeed.url },
                siteUrl = remoteFeed.siteUrl,
                feedType = remoteFeed.feedType,
            )
        )
        dao.deletePendingMutation(mutation.entityId)
        remoteFeed
    } else {
        null
    }
}

internal suspend fun pushPendingLocalItem(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    mutation: PendingMutationEntity,
): Pair<RemoteItemDto, PendingMutationEntity>? {
    val localItem = dao.getItemById(mutation.entityId)
    if (localItem == null) {
        dao.deletePendingMutation(mutation.entityId)
        return null
    }
    val localTags = dao.getTagNamesForItem(localItem.id)
    val remoteSaved = runCatching {
        apiClient.saveItemUrl(
            serverUrl = serverUrl,
            token = token,
            url = localItem.url,
            title = mutation.titleOverride,
            tags = localTags,
        )
    }
        .getOrNull()
    return if (remoteSaved != null && remoteSaved.id.isNotBlank()) {
        commitPushedLocalItem(
            dao = dao,
            apiClient = apiClient,
            serverUrl = serverUrl,
            token = token,
            localItem = localItem,
            localTags = localTags,
            remoteSaved = remoteSaved,
            mutation = mutation,
        )
    } else {
        null
    }
}

internal suspend fun pushDirtyItemFields(
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    itemId: String,
    mutation: PendingMutationEntity,
): Result<RemoteItemDto?> {
    val hasDirtyFields =
        mutation.dirtyStatus != null ||
            mutation.dirtyFavorite != null ||
            mutation.dirtyReadProgress != null
    return if (hasDirtyFields) {
        runCatching {
            apiClient.updateItem(
                serverUrl = serverUrl,
                token = token,
                itemId = itemId,
                status = mutation.dirtyStatus,
                isFavorite = mutation.dirtyFavorite,
                readProgress = mutation.dirtyReadProgress,
            )
        }
    } else {
        Result.success(null)
    }
}

internal suspend fun commitPushedLocalItem(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    localItem: ItemEntity,
    localTags: List<String>,
    remoteSaved: RemoteItemDto,
    mutation: PendingMutationEntity,
): Pair<RemoteItemDto, PendingMutationEntity> {
    val fieldsPushed =
        pushDirtyItemFields(apiClient, serverUrl, token, remoteSaved.id, mutation).isSuccess
    val effectiveSaved =
        remoteSaved.copy(
            title = remoteSaved.title.ifBlank { localItem.title },
            status = mutation.dirtyStatus ?: remoteSaved.status,
            isFavorite = mutation.dirtyFavorite ?: remoteSaved.isFavorite,
            readProgress = mutation.dirtyReadProgress ?: remoteSaved.readProgress,
            tagNames = (remoteSaved.tagNames + localTags).distinct(),
        )
    if (remoteSaved.id != localItem.id) {
        dao.deleteItemTagsForItem(localItem.id)
        dao.deleteItemById(localItem.id)
    }
    dao.upsertItem(toRemoteItemEntity(effectiveSaved, emptyMap(), localItem.isCachedOffline))
    attachTagsLocally(dao = dao, itemId = remoteSaved.id, tagNames = effectiveSaved.tagNames)
    dao.deletePendingMutation(mutation.entityId)
    if (!fieldsPushed) {
        dao.upsertPendingMutation(
            PendingMutationEntity(
                entityId = remoteSaved.id,
                isLocalItem = false,
                dirtyStatus = mutation.dirtyStatus,
                dirtyFavorite = mutation.dirtyFavorite,
                dirtyReadProgress = mutation.dirtyReadProgress,
            )
        )
    }
    val applied =
        mutation.copy(
            entityId = remoteSaved.id,
            isLocalItem = false,
            addedTagsCsv = localTags.joinToString(","),
        )
    return effectiveSaved to applied
}

internal suspend fun pushPendingItemMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    mutation: PendingMutationEntity,
): PendingMutationEntity {
    val updateResult = pushDirtyItemFields(apiClient, serverUrl, token, mutation.entityId, mutation)
    val updateError = updateResult.exceptionOrNull() as? ProblemDetailsException
    if (updateError?.statusCode == HTTP_NOT_FOUND) {
        dao.deletePendingMutation(mutation.entityId)
        return mutation
    }
    val fieldsCleaned = updateResult.isSuccess

    val remainingAdded =
        pushPendingTagAdditions(
            dao = dao,
            apiClient = apiClient,
            serverUrl = serverUrl,
            token = token,
            itemId = mutation.entityId,
            addedTags = parseTagCsv(mutation.addedTagsCsv),
        )
    val remainingRemoved =
        pushPendingTagRemovals(
            dao = dao,
            apiClient = apiClient,
            serverUrl = serverUrl,
            token = token,
            itemId = mutation.entityId,
            removedTags = parseTagCsv(mutation.removedTagsCsv),
        )

    val updated =
        mutation.copy(
            dirtyStatus = if (fieldsCleaned) null else mutation.dirtyStatus,
            dirtyFavorite = if (fieldsCleaned) null else mutation.dirtyFavorite,
            dirtyReadProgress = if (fieldsCleaned) null else mutation.dirtyReadProgress,
            addedTagsCsv = remainingAdded.joinToString(","),
            removedTagsCsv = remainingRemoved.joinToString(","),
        )
    upsertOrCleanPendingMutation(dao = dao, mutation = updated)
    return mutation
}

internal suspend fun pushPendingTagAdditions(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    itemId: String,
    addedTags: List<String>,
): List<String> = addedTags.filterNot { tagName ->
    runCatching {
        val remoteTags =
            apiClient.addTagToItem(
                serverUrl = serverUrl,
                token = token,
                itemId = itemId,
                tagName = tagName,
            )
        dao.getTagByName(tagName)?.let { localTag ->
            reconcileAddedRemoteTag(
                dao = dao,
                itemId = itemId,
                localTag = localTag,
                normalizedName = tagName,
                remoteTags = remoteTags,
            )
        }
    }
        .isSuccess
}

internal suspend fun pushPendingTagRemovals(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    itemId: String,
    removedTags: List<String>,
): List<String> {
    if (removedTags.isEmpty()) return emptyList()
    var cachedRemoteTags: List<RemoteTagDto>? = null
    return removedTags.filterNot { tagName ->
        runCatching {
            val existingTag =
                dao.getTagByName(tagName) ?: TagEntity(id = newTagId(), name = tagName)
            val remoteTags =
                if (isLocalTagId(existingTag.id)) {
                    cachedRemoteTags
                        ?: apiClient.fetchTags(serverUrl = serverUrl, token = token).also {
                            cachedRemoteTags = it
                        }
                } else {
                    emptyList()
                }
            val resolvedId =
                resolveRemoteTagIdForRemoval(
                    dao = dao,
                    existingTag = existingTag,
                    normalizedName = tagName,
                    remoteTags = remoteTags,
                )
            if (resolvedId != null) {
                apiClient.removeTagFromItem(
                    serverUrl = serverUrl,
                    token = token,
                    itemId = itemId,
                    tagId = resolvedId,
                )
            }
        }
            .isSuccess
    }
}

internal fun mergePushedFeeds(
    remoteFeeds: List<RemoteFeedDto>,
    createdFeeds: List<RemoteFeedDto>,
): List<RemoteFeedDto> {
    if (createdFeeds.isEmpty()) return remoteFeeds
    val existingIds = remoteFeeds.map { it.id }.toSet()
    return createdFeeds.filter { it.id !in existingIds } + remoteFeeds
}

internal fun mergePushedItems(
    remoteItems: List<RemoteItemDto>,
    savedItems: List<RemoteItemDto>,
): List<RemoteItemDto> {
    if (savedItems.isEmpty()) return remoteItems
    val existingIds = remoteItems.map { it.id }.toSet()
    return savedItems.filter { it.id !in existingIds } + remoteItems
}

internal suspend fun attachTagsLocally(
    dao: RocheDao,
    itemId: String,
    tagNames: List<String>,
) {
    tagNames.forEach { tagName ->
        val normalized = normalizeTagName(tagName)
        if (normalized.isNotBlank()) {
            val tag =
                dao.getTagByName(normalized)
                    ?: TagEntity(id = newTagId(), name = normalized).also { dao.upsertTag(it) }
            dao.insertItemTag(ItemTagEntity(itemId = itemId, tagId = tag.id))
        }
    }
}

internal suspend fun reconcileRemoteSnapshot(
    dao: RocheDao,
    remoteFeeds: List<RemoteFeedDto>,
    remoteItems: List<RemoteItemDto>,
    remoteTags: List<RemoteTagDto>,
    appliedMutations: List<PendingMutationEntity> = emptyList(),
) {
    val existingItemsById = dao.getAllItems().associateBy { it.id }
    val existingFeedsById = dao.getAllFeeds().associateBy { it.id }
    val existingTagsByName = dao.getAllTags().associateBy { it.name.trim().lowercase() }
    val remainingMutations = dao.getAllPendingMutations()
    val effectiveMutations = (appliedMutations + remainingMutations).associateBy { it.entityId }

    val adjustedRemoteItems = remoteItems.map { remote ->
        applyMutationToRemoteItem(remote, effectiveMutations[remote.id])
    }
    val unpushedLocalFeeds =
        collectUnpushedLocalFeeds(remainingMutations, existingFeedsById, remoteFeeds)
    val unpushedLocalItems =
        collectUnpushedLocalItems(remainingMutations, existingItemsById, adjustedRemoteItems)
    val unpushedLocalItemIds = unpushedLocalItems.map { it.id }.toSet()
    val unpushedItemTags = dao.getAllItemTags().filter { it.itemId in unpushedLocalItemIds }

    val feedTitlesById = remoteFeeds.associate { it.id to it.title }
    val tagEntitiesByName =
        buildRemoteTagEntityMap(
            remoteTags = remoteTags,
            remoteItems = adjustedRemoteItems,
            existingTagsByName = existingTagsByName,
            preservedTagIds = unpushedItemTags.map { it.tagId }.toSet(),
        )
    val remoteItemTagEntities = buildRemoteItemTagEntities(adjustedRemoteItems, tagEntitiesByName)

    dao.replaceSnapshotAtomically(
        feeds = unpushedLocalFeeds + remoteFeeds.map { toRemoteFeedEntity(it) },
        tags = tagEntitiesByName.values.toList(),
        items =
            unpushedLocalItems +
                adjustedRemoteItems.map { remote ->
                    toRemoteItemEntity(
                        remote = remote,
                        feedTitlesById = feedTitlesById,
                        isCachedOffline = existingItemsById[remote.id]?.isCachedOffline ?: false,
                    )
                },
        itemTags = (unpushedItemTags + remoteItemTagEntities).distinct(),
    )
    pruneOrphanedPendingMutations(dao, remainingMutations, adjustedRemoteItems)
}

internal fun collectUnpushedLocalFeeds(
    remainingMutations: List<PendingMutationEntity>,
    existingFeedsById: Map<String, FeedEntity>,
    remoteFeeds: List<RemoteFeedDto>,
): List<FeedEntity> =
    remainingMutations
        .filter { it.isLocalFeed }
        .mapNotNull { existingFeedsById[it.entityId] }
        .filter { local -> remoteFeeds.none { it.url.equals(local.url, ignoreCase = true) } }

internal fun collectUnpushedLocalItems(
    remainingMutations: List<PendingMutationEntity>,
    existingItemsById: Map<String, ItemEntity>,
    adjustedRemoteItems: List<RemoteItemDto>,
): List<ItemEntity> =
    remainingMutations
        .filter { it.isLocalItem }
        .mapNotNull { existingItemsById[it.entityId] }
        .filter { local -> adjustedRemoteItems.none { it.id == local.id } }

internal fun buildRemoteItemTagEntities(
    adjustedRemoteItems: List<RemoteItemDto>,
    tagEntitiesByName: Map<String, TagEntity>,
): List<ItemTagEntity> = adjustedRemoteItems.flatMap { remote ->
    remote.tagNames.mapNotNull { rawName ->
        tagEntitiesByName[rawName.trim().lowercase()]?.let { tag ->
            ItemTagEntity(itemId = remote.id, tagId = tag.id)
        }
    }
}

internal fun toRemoteFeedEntity(remote: RemoteFeedDto): FeedEntity =
    FeedEntity(
        id = remote.id,
        title = remote.title,
        url = remote.url,
        siteUrl = remote.siteUrl,
        feedType = remote.feedType,
    )

internal suspend fun pruneOrphanedPendingMutations(
    dao: RocheDao,
    remainingMutations: List<PendingMutationEntity>,
    adjustedRemoteItems: List<RemoteItemDto>,
) {
    val remoteItemIds = adjustedRemoteItems.map { it.id }.toSet()
    remainingMutations
        .filter { !it.isLocalFeed && !it.isLocalItem && it.entityId !in remoteItemIds }
        .forEach { dao.deletePendingMutation(it.entityId) }
}

internal fun applyMutationToRemoteItem(
    remote: RemoteItemDto,
    mutation: PendingMutationEntity?,
): RemoteItemDto {
    if (mutation == null) return remote
    val mergedTags =
        remote.tagNames.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toMutableSet()
    mergedTags.addAll(parseTagCsv(mutation.addedTagsCsv))
    mergedTags.removeAll(parseTagCsv(mutation.removedTagsCsv).toSet())
    return remote.copy(
        status = mutation.dirtyStatus ?: remote.status,
        isFavorite = mutation.dirtyFavorite ?: remote.isFavorite,
        readProgress = mutation.dirtyReadProgress ?: remote.readProgress,
        tagNames = mergedTags.sorted(),
    )
}

internal fun buildRemoteTagEntityMap(
    remoteTags: List<RemoteTagDto>,
    remoteItems: List<RemoteItemDto>,
    existingTagsByName: Map<String, TagEntity> = emptyMap(),
    preservedTagIds: Set<String> = emptySet(),
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
                val preservedId = existingTagsByName[normalized]?.id ?: newTagId()
                map[normalized] = TagEntity(id = preservedId, name = normalized)
            }
        }
    existingTagsByName.values
        .filter { it.id in preservedTagIds && it.name.trim().lowercase() !in map }
        .forEach { tag -> map[tag.name.trim().lowercase()] = tag }
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

internal fun matchesInboxFilter(entry: InboxEntry, filter: InboxFilter): Boolean =
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
