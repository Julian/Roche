package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RemoteTagDto
import com.grayvines.roche.data.remote.UNREAD_READ_AT_TOMBSTONE

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
    preFetchItemsById: Map<String, ItemEntity> = emptyMap(),
) {
    val existingItemsById = dao.getAllItems().associateBy { it.id }
    val existingFeedsById = dao.getAllFeeds().associateBy { it.id }
    val existingTagsByName = dao.getAllTags().associateBy { it.name.trim().lowercase() }
    val remainingMutations = dao.getAllPendingMutations()
    val effectiveMutations = (appliedMutations + remainingMutations).associateBy { it.entityId }

    val adjustedRemoteItems =
        remoteItems
            .filterNot { remote ->
                effectiveMutations[remote.id]?.dirtyStatus == DELETED_STATUS_TOMBSTONE
            }
            .map { remote ->
                val mutated = applyMutationToRemoteItem(remote, effectiveMutations[remote.id])
                overlayInFlightLocalChanges(
                    remote = mutated,
                    preFetch = preFetchItemsById[remote.id],
                    currentLocal = existingItemsById[remote.id],
                )
            }
    val unpushedLocalFeeds =
        collectUnpushedLocalFeeds(remainingMutations, existingFeedsById, remoteFeeds)
    val unpushedLocalItems =
        collectUnpushedLocalItems(remainingMutations, existingItemsById, adjustedRemoteItems)
    val preservedOfflineItems =
        collectPreservedOfflineItems(
            existingItemsById = existingItemsById,
            adjustedRemoteItems = adjustedRemoteItems,
            unpushedLocalItems = unpushedLocalItems,
            effectiveMutations = effectiveMutations,
        )
    val preservedLocalItems = unpushedLocalItems + preservedOfflineItems
    val preservedLocalItemIds = preservedLocalItems.map { it.id }.toSet()
    val preservedItemTags = dao.getAllItemTags().filter { it.itemId in preservedLocalItemIds }

    val feedTitlesById = remoteFeeds.associate { it.id to it.title }
    val tagEntitiesByName =
        buildRemoteTagEntityMap(
            remoteTags = remoteTags,
            remoteItems = adjustedRemoteItems,
            existingTagsByName = existingTagsByName,
            preservedTagIds = preservedItemTags.map { it.tagId }.toSet(),
        )
    val remoteItemTagEntities = buildRemoteItemTagEntities(adjustedRemoteItems, tagEntitiesByName)

    dao.replaceSnapshotAtomically(
        feeds = unpushedLocalFeeds + remoteFeeds.map { toRemoteFeedEntity(it) },
        tags = tagEntitiesByName.values.toList(),
        items =
            preservedLocalItems +
                adjustedRemoteItems.map { remote ->
                    val existing = existingItemsById[remote.id]
                    toRemoteItemEntity(
                        remote = remote,
                        feedTitlesById = feedTitlesById,
                        isCachedOffline = existing?.isCachedOffline ?: false,
                        existing = existing,
                    )
                },
        itemTags = (preservedItemTags + remoteItemTagEntities).distinct(),
    )
    pruneOrphanedPendingMutations(dao, remainingMutations, remoteItems)
}

internal fun collectPreservedOfflineItems(
    existingItemsById: Map<String, ItemEntity>,
    adjustedRemoteItems: List<RemoteItemDto>,
    unpushedLocalItems: List<ItemEntity>,
    effectiveMutations: Map<String, PendingMutationEntity>,
): List<ItemEntity> {
    if (adjustedRemoteItems.size < MAX_REMOTE_SYNC_WINDOW_ITEMS) return emptyList()
    val adjustedRemoteItemIds = adjustedRemoteItems.map { it.id }.toSet()
    val unpushedIds = unpushedLocalItems.map { it.id }.toSet()
    return existingItemsById.values.filter { local ->
        val isDeleted = effectiveMutations[local.id]?.dirtyStatus == DELETED_STATUS_TOMBSTONE
        local.isCachedOffline &&
            local.id !in adjustedRemoteItemIds &&
            local.id !in unpushedIds &&
            !isDeleted
    }
}

private const val MAX_REMOTE_SYNC_WINDOW_ITEMS = 5_000

internal fun overlayInFlightLocalChanges(
    remote: RemoteItemDto,
    preFetch: ItemEntity?,
    currentLocal: ItemEntity?,
): RemoteItemDto {
    if (preFetch == null || currentLocal == null) return remote
    val statusChanged = currentLocal.status != preFetch.status
    val favoriteChanged = currentLocal.isFavorite != preFetch.isFavorite
    val progressChanged = currentLocal.readProgress != preFetch.readProgress
    val readAtChanged = currentLocal.readAt != preFetch.readAt
    val coreChanged = statusChanged || favoriteChanged || progressChanged
    if (!coreChanged && !readAtChanged) return remote
    return remote.copy(
        status = if (statusChanged) currentLocal.status else remote.status,
        isFavorite = if (favoriteChanged) currentLocal.isFavorite else remote.isFavorite,
        readProgress = if (progressChanged) currentLocal.readProgress else remote.readProgress,
        readAt = if (readAtChanged) currentLocal.readAt else remote.readAt,
    )
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
    val effectiveReadAt =
        when (mutation.dirtyReadAt) {
            null ->
                if (mutation.dirtyStatus != null) {
                    if (mutation.dirtyStatus.equals("archived", ignoreCase = true)) {
                        remote.readAt
                    } else {
                        null
                    }
                } else {
                    remote.readAt
                }
            UNREAD_READ_AT_TOMBSTONE -> null
            else -> mutation.dirtyReadAt
        }
    return remote.copy(
        status = mutation.dirtyStatus ?: remote.status,
        isFavorite = mutation.dirtyFavorite ?: remote.isFavorite,
        readProgress = mutation.dirtyReadProgress ?: remote.readProgress,
        readAt = effectiveReadAt,
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
