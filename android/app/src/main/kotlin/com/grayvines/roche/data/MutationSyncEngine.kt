package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.PendingEntityKind
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RemoteTagDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.remote.UNREAD_READ_AT_TOMBSTONE
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.ItemStatus
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first

private const val HTTP_NOT_FOUND = 404
private const val ARCHIVED_STATUS_WIRE = "archived"
internal const val DELETED_STATUS_TOMBSTONE = "__deleted__"

internal val PendingMutationEntity.isDeletedItem: Boolean
    get() = dirtyStatus == DELETED_STATUS_TOMBSTONE

internal fun PendingMutationEntity.resolveEffectiveReadAt(
    defaultReadAt: String?,
    statusAffectsDefault: Boolean = false,
): String? =
    when (dirtyReadAt) {
        null ->
            if (statusAffectsDefault && dirtyStatus != null) {
                if (dirtyStatus.equals(ARCHIVED_STATUS_WIRE, ignoreCase = true)) {
                    defaultReadAt
                } else {
                    null
                }
            } else {
                defaultReadAt
            }
        UNREAD_READ_AT_TOMBSTONE -> null
        else -> dirtyReadAt
    }

internal data class PushMutationsOutcome(
    val createdFeeds: List<RemoteFeedDto> = emptyList(),
    val savedItems: List<RemoteItemDto> = emptyList(),
    val appliedMutations: List<PendingMutationEntity> = emptyList(),
)

internal fun isCleanMutation(mutation: PendingMutationEntity): Boolean =
    mutation.kind == PendingEntityKind.REMOTE_ITEM &&
        mutation.dirtyStatus == null &&
        mutation.dirtyFavorite == null &&
        mutation.dirtyReadProgress == null &&
        mutation.dirtyReadAt == null &&
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

internal fun hasDirtyItemFields(mutation: PendingMutationEntity): Boolean =
    mutation.dirtyStatus != null ||
        mutation.dirtyFavorite != null ||
        mutation.dirtyReadProgress != null ||
        mutation.dirtyReadAt != null

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
    val currentLocalTags = dao.getTagNamesForItem(localEntity.id)
    dao.deletePendingMutation(localEntity.id)
    val reconciled = reconcileSavedItemEntity(currentLocal, remoteSaved)
    if (remoteSaved.id != localEntity.id) {
        dao.deleteItemTagsForItem(localEntity.id)
        dao.deleteItemById(localEntity.id)
    }
    dao.upsertItem(reconciled)
    val effectiveTags =
        resolveFinalizedLocalTags(
            initialTags = tags,
            currentLocalTags = currentLocalTags,
            remoteTags = remoteSaved.tagNames,
            inFlight = inFlight,
        )
    attachTagsLocally(dao = dao, itemId = reconciled.id, tagNames = effectiveTags)
    if (inFlight != null) {
        val serverKnownTags = (tags + remoteSaved.tagNames).map { normalizeTagName(it) }.distinct()
        syncInFlightSavedItemMutations(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            reconciledItemId = reconciled.id,
            serverKnownTags = serverKnownTags,
            inFlight = inFlight,
        )
    }
    return reconciled
}

internal fun resolveFinalizedLocalTags(
    initialTags: List<String>,
    currentLocalTags: List<String>,
    remoteTags: List<String>,
    inFlight: PendingMutationEntity?,
): List<String> {
    val removedInFlight = parseTagCsv(inFlight?.removedTagsCsv.orEmpty()).toSet()
    val addedInFlight = parseTagCsv(inFlight?.addedTagsCsv.orEmpty())
    val base =
        if (currentLocalTags.isNotEmpty() || inFlight != null) currentLocalTags else initialTags
    return (base + remoteTags + addedInFlight)
        .map { normalizeTagName(it) }
        .distinct()
        .filter { it.isNotBlank() && it !in removedInFlight }
}

internal suspend fun syncInFlightSavedItemMutations(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    reconciledItemId: String,
    serverKnownTags: List<String>,
    inFlight: PendingMutationEntity,
) {
    val fieldsPushed =
        if (hasDirtyItemFields(inFlight)) {
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = reconciledItemId,
                status = inFlight.dirtyStatus,
                isFavorite = inFlight.dirtyFavorite,
                readProgress = inFlight.dirtyReadProgress,
                readAt = inFlight.dirtyReadAt,
            )
        } else {
            true
        }
    val serverTagSet = serverKnownTags.map { normalizeTagName(it) }.toSet()
    val addedDelta = parseTagCsv(inFlight.addedTagsCsv).filter { it !in serverTagSet }
    val removedDelta = parseTagCsv(inFlight.removedTagsCsv).filter { it in serverTagSet }
    val currentSettings = settings.settings.first()
    val canPush = currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()
    val remainingAdded =
        if (canPush && addedDelta.isNotEmpty()) {
            pushPendingTagAdditions(
                dao = dao,
                apiClient = apiClient,
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                itemId = reconciledItemId,
                addedTags = addedDelta,
            )
        } else {
            addedDelta
        }
    val remainingRemoved =
        if (canPush && removedDelta.isNotEmpty()) {
            pushPendingTagRemovals(
                dao = dao,
                apiClient = apiClient,
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                itemId = reconciledItemId,
                removedTags = removedDelta,
            )
        } else {
            removedDelta
        }
    upsertOrCleanPendingMutation(
        dao = dao,
        mutation =
            buildRemainingInFlightMutation(
                reconciledItemId = reconciledItemId,
                inFlight = inFlight,
                fieldsPushed = fieldsPushed,
                remainingAdded = remainingAdded,
                remainingRemoved = remainingRemoved,
            ),
    )
}

private fun buildRemainingInFlightMutation(
    reconciledItemId: String,
    inFlight: PendingMutationEntity,
    fieldsPushed: Boolean,
    remainingAdded: List<String>,
    remainingRemoved: List<String>,
): PendingMutationEntity =
    PendingMutationEntity(
        entityId = reconciledItemId,
        isLocalItem = false,
        dirtyStatus = if (fieldsPushed) null else inFlight.dirtyStatus,
        dirtyFavorite = if (fieldsPushed) null else inFlight.dirtyFavorite,
        dirtyReadProgress = if (fieldsPushed) null else inFlight.dirtyReadProgress,
        dirtyReadAt = if (fieldsPushed) null else inFlight.dirtyReadAt,
        addedTagsCsv = remainingAdded.joinToString(","),
        removedTagsCsv = remainingRemoved.joinToString(","),
    )

internal suspend fun pushItemUpdateBestEffort(
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    status: String? = null,
    isFavorite: Boolean? = null,
    readProgress: Double? = null,
    readAt: String? = null,
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
            readAt = readAt,
        )
        true
    }
}
    .getOrDefault(false)

private suspend fun syncItemFieldMutation(
    dao: RocheDao,
    itemId: String,
    applyDirty: (PendingMutationEntity) -> PendingMutationEntity,
    clearDirty: (PendingMutationEntity) -> PendingMutationEntity,
    pushRemote: suspend () -> Boolean,
) {
    val existing = dao.getPendingMutation(itemId)
    if (existing?.kind == PendingEntityKind.LOCAL_ITEM) {
        dao.upsertPendingMutation(applyDirty(existing))
        return
    }
    val pushed = pushRemote()
    val base = existing ?: PendingMutationEntity(entityId = itemId)
    upsertOrCleanPendingMutation(
        dao = dao,
        mutation = if (pushed) clearDirty(base) else applyDirty(base),
    )
}

internal suspend fun syncStatusMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    status: String,
    explicitReadAt: String? = null,
) {
    syncItemFieldMutation(
        dao = dao,
        itemId = itemId,
        applyDirty = { it.copy(dirtyStatus = status, dirtyReadAt = explicitReadAt) },
        clearDirty = { it.copy(dirtyStatus = null, dirtyReadAt = null) },
        pushRemote = {
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                status = status,
                readAt = explicitReadAt,
            )
        },
    )
}

internal suspend fun syncFavoriteMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    isFavorite: Boolean,
) {
    syncItemFieldMutation(
        dao = dao,
        itemId = itemId,
        applyDirty = { it.copy(dirtyFavorite = isFavorite) },
        clearDirty = { it.copy(dirtyFavorite = null) },
        pushRemote = {
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                isFavorite = isFavorite,
            )
        },
    )
}

internal suspend fun syncReadProgressMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    progress: Double,
) {
    syncItemFieldMutation(
        dao = dao,
        itemId = itemId,
        applyDirty = { it.copy(dirtyReadProgress = progress) },
        clearDirty = { it.copy(dirtyReadProgress = null) },
        pushRemote = {
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                readProgress = progress,
            )
        },
    )
}

internal suspend fun syncReadAtMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    readAt: String?,
) {
    val wireReadAt = readAt ?: UNREAD_READ_AT_TOMBSTONE
    syncItemFieldMutation(
        dao = dao,
        itemId = itemId,
        applyDirty = { it.copy(dirtyReadAt = wireReadAt) },
        clearDirty = { it.copy(dirtyReadAt = null) },
        pushRemote = {
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                readAt = wireReadAt,
            )
        },
    )
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
    val base = existing ?: PendingMutationEntity(entityId = itemId)
    val added = parseTagCsv(base.addedTagsCsv).toMutableSet()
    val removed = parseTagCsv(base.removedTagsCsv).toMutableSet()
    if (existing?.kind == PendingEntityKind.LOCAL_ITEM) {
        added.add(normalizedName)
        removed.remove(normalizedName)
        dao.upsertPendingMutation(
            existing.copy(
                addedTagsCsv = added.joinToString(","),
                removedTagsCsv = removed.joinToString(","),
            )
        )
        return
    }
    val pushed =
        pushAddTagBestEffort(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            existingTag = existingTag,
            normalizedName = normalizedName,
        )
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
    val base = existing ?: PendingMutationEntity(entityId = itemId)
    val added = parseTagCsv(base.addedTagsCsv).toMutableSet()
    val removed = parseTagCsv(base.removedTagsCsv).toMutableSet()
    if (existing?.kind == PendingEntityKind.LOCAL_ITEM) {
        added.remove(normalizedName)
        removed.add(normalizedName)
        dao.upsertPendingMutation(
            existing.copy(
                addedTagsCsv = added.joinToString(","),
                removedTagsCsv = removed.joinToString(","),
            )
        )
        return
    }
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
        dao.upsertTag(TagEntity(id = remoteMatch.id, name = normalizedName))
        dao.migrateItemTagIds(oldTagId = localTag.id, newTagId = remoteMatch.id)
        dao.deleteItemTag(itemId = itemId, tagId = localTag.id)
        dao.deleteTagById(id = localTag.id)
        dao.insertItemTag(ItemTagEntity(itemId = itemId, tagId = remoteMatch.id))
    }
}

internal suspend fun refreshFeedOnlineOrOnDevice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    feedId: String,
    onSyncWithServer: suspend () -> Result<Int>,
): String {
    val currentSettings = settings.settings.first()
    val existingFeed = dao.getFeedById(feedId)
    val existingTitle = existingFeed?.title
    val hasServer =
        currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()
    val remoteRefreshed =
        if (hasServer) {
            runCatching {
                apiClient.refreshFeed(
                    serverUrl = currentSettings.serverUrl,
                    token = currentSettings.bearerToken,
                    feedId = feedId,
                )
            }
                .getOrNull()
        } else {
            null
        }
    return if (remoteRefreshed != null) {
        if (remoteRefreshed.id.isNotBlank()) {
            dao.upsertFeed(toRemoteFeedEntity(remoteRefreshed, existingFeed))
        }
        onSyncWithServer()
        remoteRefreshed.title.ifBlank { existingTitle ?: feedId }
    } else {
        val localFeed =
            existingFeed
                ?: error(
                    if (hasServer) "Feed not found: $feedId" else "Configure Server URL in Settings"
                )
        LocalFeedPoller.pollFeed(
                dao = dao,
                apiClient = apiClient,
                feed = localFeed,
                localPeerId = currentSettings.peerId,
            )
            .getOrThrow()
        if (hasServer) {
            onSyncWithServer()
        }
        dao.getFeedById(localFeed.id)?.title?.ifBlank { existingTitle ?: feedId }
            ?: existingTitle
            ?: feedId
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
    val canonicalUrl = canonicalizeFeedUrl(url)
    val fallbackTitle = cleanTitleOverride ?: extractHostTitle(url)
    val currentSettings = settings.settings.first()
    dao.deleteTombstone("feed", feedNaturalKey(canonicalUrl))
    val remoteFeed = runCatching {
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
            toRemoteFeedEntity(
                remoteFeed.copy(
                    title = remoteFeed.title.ifBlank { fallbackTitle },
                    url = remoteFeed.url.ifBlank { canonicalUrl },
                )
            )
        } else {
            val nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
            val localFeed =
                FeedEntity(
                    id = deterministicFeedId(canonicalUrl),
                    title = fallbackTitle,
                    url = canonicalUrl,
                    siteUrl = null,
                    feedType = inferFeedTypeWireValue(url),
                    lastPolled = nowIso,
                )
            dao.upsertPendingMutation(
                PendingMutationEntity.localFeed(
                    entityId = localFeed.id,
                    titleOverride = cleanTitleOverride,
                )
            )
            localFeed
        }
    dao.upsertFeed(entity)
    if (remoteFeed == null) {
        LocalFeedPoller.pollFeed(
            dao = dao,
            apiClient = apiClient,
            feed = entity,
            localPeerId = currentSettings.peerId,
        )
    }
    return dao.getFeedById(entity.id)?.title ?: entity.title
}

internal suspend fun deleteFeedFromServerOrLocal(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    offlineCacheManager: OfflineCacheManager,
    feedId: String,
): String {
    val existing = dao.getFeedById(feedId)
    val nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    val isLocalFeed = dao.getPendingMutation(feedId)?.kind == PendingEntityKind.LOCAL_FEED
    if (isLocalFeed) {
        if (existing != null) {
            dao.upsertTombstone(
                TombstoneEntity(
                    entityType = "feed",
                    naturalKey = feedNaturalKey(existing.url),
                    deletedAt = nowIso,
                )
            )
        }
        purgeFeedItemsLocally(dao = dao, offlineCacheManager = offlineCacheManager, feedId = feedId)
        dao.deletePendingMutation(feedId)
        dao.deleteFeedById(feedId)
        return existing?.title ?: feedId
    }
    val currentSettings = settings.settings.first()
    if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
        runCatching {
            apiClient.deleteFeed(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                feedId = feedId,
            )
        }
            .onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                val problem = error as? ProblemDetailsException
                if (problem != null && problem.statusCode != HTTP_NOT_FOUND) throw error
            }
    }
    if (existing != null) {
        dao.upsertTombstone(
            TombstoneEntity(
                entityType = "feed",
                naturalKey = feedNaturalKey(existing.url),
                deletedAt = nowIso,
            )
        )
    }
    purgeFeedItemsLocally(dao = dao, offlineCacheManager = offlineCacheManager, feedId = feedId)
    dao.deletePendingMutation(feedId)
    dao.deleteFeedById(feedId)
    return existing?.title ?: feedId
}

internal suspend fun purgeFeedItemsLocally(
    dao: RocheDao,
    offlineCacheManager: OfflineCacheManager,
    feedId: String,
) {
    val feedItems = dao.getAllItems().filter { it.feedId == feedId }
    for (item in feedItems) {
        offlineCacheManager.evictCachedItemFiles(item.id)
        dao.deleteItemTagsForItem(item.id)
        dao.deletePendingMutation(item.id)
        dao.deleteItemById(item.id)
    }
}

internal suspend fun pushDeleteItemBestEffort(
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
): Boolean = runCatching {
    val currentSettings = settings.settings.first()
    if (currentSettings.serverUrl.isBlank() || currentSettings.bearerToken.isBlank()) {
        false
    } else {
        apiClient.deleteItem(
            serverUrl = currentSettings.serverUrl,
            token = currentSettings.bearerToken,
            itemId = itemId,
        )
        true
    }
}
    .recover { error ->
        val problem = error as? ProblemDetailsException
        problem?.statusCode == HTTP_NOT_FOUND
    }
    .getOrDefault(false)

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
        when (mutation.kind) {
            PendingEntityKind.LOCAL_FEED -> {
                pushPendingLocalFeed(dao, apiClient, serverUrl, token, mutation)?.let {
                    createdFeeds.add(it)
                }
            }
            PendingEntityKind.LOCAL_ITEM -> {
                pushPendingLocalItem(dao, apiClient, serverUrl, token, mutation)?.let {
                    (savedItem, applied) ->
                    savedItems.add(savedItem)
                    appliedMutations.add(applied)
                }
            }
            PendingEntityKind.REMOTE_ITEM -> {
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
        val fieldsPushed =
            pushDirtyItemFields(apiClient, serverUrl, token, remoteSaved.id, mutation).isSuccess
        commitPushedLocalItem(
            dao = dao,
            localItem = localItem,
            localTags = localTags,
            remoteSaved = remoteSaved,
            mutation = mutation,
            fieldsPushed = fieldsPushed,
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
): Result<RemoteItemDto?> =
    if (hasDirtyItemFields(mutation)) {
        runCatching {
            apiClient.updateItem(
                serverUrl = serverUrl,
                token = token,
                itemId = itemId,
                status = mutation.dirtyStatus,
                isFavorite = mutation.dirtyFavorite,
                readProgress = mutation.dirtyReadProgress,
                readAt = mutation.dirtyReadAt,
            )
        }
    } else {
        Result.success(null)
    }

internal suspend fun commitPushedLocalItem(
    dao: RocheDao,
    localItem: ItemEntity,
    localTags: List<String>,
    remoteSaved: RemoteItemDto,
    mutation: PendingMutationEntity,
    fieldsPushed: Boolean,
): Pair<RemoteItemDto, PendingMutationEntity> {
    val effectiveReadAt = mutation.resolveEffectiveReadAt(defaultReadAt = remoteSaved.readAt)
    val effectiveSaved =
        remoteSaved.copy(
            title = remoteSaved.title.ifBlank { localItem.title },
            status = mutation.dirtyStatus ?: remoteSaved.status,
            isFavorite = mutation.dirtyFavorite ?: remoteSaved.isFavorite,
            readProgress = mutation.dirtyReadProgress ?: remoteSaved.readProgress,
            readAt = effectiveReadAt,
            tagNames = (remoteSaved.tagNames + localTags).distinct(),
        )
    if (remoteSaved.id != localItem.id) {
        dao.deleteItemTagsForItem(localItem.id)
        dao.deleteItemById(localItem.id)
    }
    dao.upsertItem(
        toRemoteItemEntity(
            remote = effectiveSaved,
            feedTitlesById = emptyMap(),
            isCachedOffline = localItem.isCachedOffline,
            existing = localItem,
        )
    )
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
                dirtyReadAt = mutation.dirtyReadAt,
            )
        )
    }
    val applied =
        mutation.copy(
            entityId = remoteSaved.id,
            isLocalItem = false,
            addedTagsCsv = localTags.joinToString(","),
            removedTagsCsv = "",
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
    if (mutation.isDeletedItem) {
        val deleted = runCatching {
            apiClient.deleteItem(
                serverUrl = serverUrl,
                token = token,
                itemId = mutation.entityId,
            )
            true
        }
            .recover { error ->
                (error as? ProblemDetailsException)?.statusCode == HTTP_NOT_FOUND
            }
            .getOrDefault(false)
        if (deleted) {
            dao.deletePendingMutation(mutation.entityId)
        }
        return mutation
    }
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
            dirtyReadAt = if (fieldsCleaned) null else mutation.dirtyReadAt,
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

internal suspend fun syncArchiveBatchMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemIds: List<String>,
    feedId: String? = null,
    allUnread: Boolean = false,
): List<ArchiveStateSnapshot> {
    val targetIds = itemIds.toSet()
    val unarchivedItems =
        dao.getAllItems().filter { it.id in targetIds && it.status != ARCHIVED_STATUS_WIRE }
    if (unarchivedItems.isEmpty()) return emptyList()

    val nowIso = Instant.now().toString()
    val snapshots = unarchivedItems.map { item ->
        ArchiveStateSnapshot(
            itemId = item.id,
            previousStatus = ItemStatus.fromWireValue(item.status),
            previousReadAt = item.readAt,
        )
    }
    for (item in unarchivedItems) {
        val nextReadAt = item.readAt?.takeIf { it.isNotBlank() } ?: nowIso
        dao.updateItemStatusAndReadAt(item.id, ARCHIVED_STATUS_WIRE, nextReadAt)
    }

    val unarchivedIds = unarchivedItems.map { it.id }
    val mutationsById = unarchivedIds.associateWith { dao.getPendingMutation(it) }
    val remoteIds = unarchivedIds.filter { mutationsById[it]?.kind != PendingEntityKind.LOCAL_ITEM }
    val remoteFeedId = feedId?.takeIf {
        dao.getPendingMutation(it)?.kind != PendingEntityKind.LOCAL_FEED
    }
    val currentSettings = settings.settings.first()
    val pushedToServer =
        if (
            remoteIds.isNotEmpty() &&
                currentSettings.serverUrl.isNotBlank() &&
                currentSettings.bearerToken.isNotBlank()
        ) {
            runCatching {
                    apiClient.archiveBatch(
                        serverUrl = currentSettings.serverUrl,
                        token = currentSettings.bearerToken,
                        itemIds = remoteIds,
                        feedId = remoteFeedId,
                        allUnread = allUnread && remoteIds.size == unarchivedIds.size,
                    )
                    true
                }
                .getOrDefault(false)
        } else {
            false
        }

    for (itemId in unarchivedIds) {
        recordBatchArchiveMutation(
            dao = dao,
            itemId = itemId,
            existing = mutationsById[itemId],
            pushedToServer = pushedToServer,
        )
    }
    return snapshots
}

private suspend fun recordBatchArchiveMutation(
    dao: RocheDao,
    itemId: String,
    existing: PendingMutationEntity?,
    pushedToServer: Boolean,
) {
    if (existing?.kind == PendingEntityKind.LOCAL_ITEM) {
        dao.upsertPendingMutation(
            existing.copy(dirtyStatus = ARCHIVED_STATUS_WIRE, dirtyReadAt = null)
        )
    } else {
        val base = existing ?: PendingMutationEntity(entityId = itemId)
        upsertOrCleanPendingMutation(
            dao = dao,
            mutation =
                base.copy(
                    dirtyStatus = if (pushedToServer) null else ARCHIVED_STATUS_WIRE,
                    dirtyReadAt = null,
                ),
        )
    }
}

internal fun buildLocalOpmlXml(feeds: List<FeedEntity>): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("""<opml version="2.0">""")
    appendLine("  <head>")
    appendLine("    <title>Roche Feeds Export</title>")
    appendLine("  </head>")
    appendLine("  <body>")
    for (feed in feeds) {
        val titleAttr = escapeXmlAttr(feed.title)
        val xmlUrlAttr = escapeXmlAttr(feed.url)
        val htmlUrlPart =
            feed.siteUrl?.takeIf { it.isNotBlank() }?.let { """ htmlUrl="${escapeXmlAttr(it)}"""" }
                ?: ""
        appendLine(
            """    <outline type="rss" text="$titleAttr" title="$titleAttr" xmlUrl="$xmlUrlAttr"$htmlUrlPart/>"""
        )
    }
    appendLine("  </body>")
    appendLine("</opml>")
}

private fun escapeXmlAttr(raw: String): String =
    raw.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
