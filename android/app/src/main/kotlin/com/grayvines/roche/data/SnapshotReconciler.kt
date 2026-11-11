@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.PendingEntityKind
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.remote.PeerSyncItemPayload
import com.grayvines.roche.data.remote.PeerSyncRequestDto
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RemoteTagDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.Settings
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

@Suppress("LongMethod", "LongParameterList")
internal suspend fun reconcileRemoteSnapshot(
    dao: RocheDao,
    remoteFeeds: List<RemoteFeedDto>,
    remoteItems: List<RemoteItemDto>,
    remoteTags: List<RemoteTagDto>,
    appliedMutations: List<PendingMutationEntity> = emptyList(),
    preFetchItemsById: Map<String, ItemEntity> = emptyMap(),
    preFetchFeedsById: Map<String, FeedEntity> = emptyMap(),
    isDeltaItems: Boolean = false,
) {
    val allExistingItems = dao.getAllItems()
    val existingItemsById = allExistingItems.associateBy { it.id }
    val allExistingFeeds = dao.getAllFeeds()
    val existingFeedsById = allExistingFeeds.associateBy { it.id }
    val existingFeedsByUrl = allExistingFeeds.associateBy { it.url.trim().lowercase() }
    val allExistingTags = dao.getAllTags()
    val existingTagsById = allExistingTags.associateBy { it.id }
    val existingTagsByName = allExistingTags.associateBy { it.name.trim().lowercase() }
    val remainingMutations = dao.getAllPendingMutations()
    val effectiveMutations = (appliedMutations + remainingMutations).associateBy { it.entityId }
    val tombstones = dao.getAllTombstones()
    val deletedFeedKeys = collectTombstoneKeys(tombstones, "feed")
    val deletedItemKeys = collectTombstoneKeys(tombstones, "item")
    val deletedTagKeys = collectTombstoneKeys(tombstones, "tag")

    val survivingRemoteFeeds = remoteFeeds.filterNot { feedNaturalKey(it.url) in deletedFeedKeys }
    val feedUrlsById =
        (allExistingFeeds.map { it.id to it.url } + survivingRemoteFeeds.map { it.id to it.url })
            .toMap()
    val existingItemsByNatKey = allExistingItems.associateBy { item ->
        itemNaturalKey(item.feedUrl ?: item.feedId?.let { feedUrlsById[it] }, item.url)
    }

    val adjustedRemoteItems =
        adjustRemoteItemsForReconcile(
            remoteItems = remoteItems,
            feedUrlsById = feedUrlsById,
            existingItemsById = existingItemsById,
            existingItemsByNatKey = existingItemsByNatKey,
            effectiveMutations = effectiveMutations,
            deletedItemKeys = deletedItemKeys,
            preFetchItemsById = preFetchItemsById,
        )
    val unpushedLocalFeeds =
        collectUnpushedLocalFeeds(remainingMutations, existingFeedsById, survivingRemoteFeeds)
    val replacementFeeds =
        buildReplacementFeedEntities(
            unpushedLocalFeeds = unpushedLocalFeeds,
            survivingRemoteFeeds = survivingRemoteFeeds,
            existingFeedsById = existingFeedsById,
            existingFeedsByUrl = existingFeedsByUrl,
            effectiveMutations = effectiveMutations,
            preFetchFeedsById = preFetchFeedsById,
        )
    val feedTitlesById = replacementFeeds.associate { it.id to it.title }
    val preservedLocalItems =
        collectPreservedLocalItems(
            remainingMutations = remainingMutations,
            existingItemsById = existingItemsById,
            adjustedRemoteItems = adjustedRemoteItems,
            feedUrlsById = feedUrlsById,
            effectiveMutations = effectiveMutations,
            isDeltaItems = isDeltaItems,
            deletedFeedKeys = deletedFeedKeys,
            deletedItemKeys = deletedItemKeys,
            survivingFeeds = replacementFeeds,
        )
    val preservedLocalItemIds = preservedLocalItems.map { it.id }.toSet()
    val rawPreservedItemTags = dao.getAllItemTags().filter { it.itemId in preservedLocalItemIds }
    val tagEntitiesByName =
        buildRemoteTagEntityMap(
            remoteTags = remoteTags.filterNot { tagNaturalKey(it.name) in deletedTagKeys },
            remoteItems = adjustedRemoteItems,
            existingTagsByName =
                existingTagsByName.filterValues { tagNaturalKey(it.name) !in deletedTagKeys },
            preservedTagIds = rawPreservedItemTags.map { it.tagId }.toSet(),
        )
    val preservedItemTags =
        remapPreservedItemTags(
            rawPreservedItemTags = rawPreservedItemTags,
            existingTagsById = existingTagsById,
            tagEntitiesByName = tagEntitiesByName,
        )
    val remoteItemTagEntities = buildRemoteItemTagEntities(adjustedRemoteItems, tagEntitiesByName)
    val replacementItems =
        buildReplacementItemEntities(
            preservedLocalItems = preservedLocalItems,
            adjustedRemoteItems = adjustedRemoteItems,
            existingItemsById = existingItemsById,
            existingItemsByNatKey = existingItemsByNatKey,
            feedTitlesById = feedTitlesById,
            feedUrlsById = feedUrlsById,
        )

    dao.replaceSnapshotAtomically(
        feeds = replacementFeeds,
        tags = tagEntitiesByName.values.toList(),
        items = replacementItems,
        itemTags = (preservedItemTags + remoteItemTagEntities).distinct(),
    )
    pruneOrphanedPendingMutations(
        dao = dao,
        remainingMutations = remainingMutations,
        survivingItemIds = replacementItems.map { it.id }.toSet(),
        survivingFeedIds = replacementFeeds.map { it.id }.toSet(),
    )
}

@Suppress("LongParameterList")
private fun collectPreservedLocalItems(
    remainingMutations: List<PendingMutationEntity>,
    existingItemsById: Map<String, ItemEntity>,
    adjustedRemoteItems: List<RemoteItemDto>,
    feedUrlsById: Map<String, String>,
    effectiveMutations: Map<String, PendingMutationEntity>,
    isDeltaItems: Boolean = false,
    deletedFeedKeys: Set<String> = emptySet(),
    deletedItemKeys: Set<String> = emptySet(),
    survivingFeeds: List<FeedEntity> = emptyList(),
): List<ItemEntity> {
    val unpushedLocalItems =
        collectUnpushedLocalItems(
            remainingMutations = remainingMutations,
            existingItemsById = existingItemsById,
            adjustedRemoteItems = adjustedRemoteItems,
            feedUrlsById = feedUrlsById,
        )
    val preservedOfflineItems =
        collectPreservedOfflineItems(
            existingItemsById = existingItemsById,
            adjustedRemoteItems = adjustedRemoteItems,
            unpushedLocalItems = unpushedLocalItems,
            effectiveMutations = effectiveMutations,
        )
    val basePreserved = unpushedLocalItems + preservedOfflineItems
    if (!isDeltaItems) return basePreserved
    val deltaUnchangedItems =
        collectDeltaUnchangedItems(
            existingItemsById = existingItemsById,
            adjustedRemoteItems = adjustedRemoteItems,
            alreadyPreservedIds = basePreserved.map { it.id }.toSet(),
            feedUrlsById = feedUrlsById,
            effectiveMutations = effectiveMutations,
            deletedFeedKeys = deletedFeedKeys,
            deletedItemKeys = deletedItemKeys,
            survivingFeeds = survivingFeeds,
        )
    return basePreserved + deltaUnchangedItems
}

@Suppress("LongParameterList")
private fun collectDeltaUnchangedItems(
    existingItemsById: Map<String, ItemEntity>,
    adjustedRemoteItems: List<RemoteItemDto>,
    alreadyPreservedIds: Set<String>,
    feedUrlsById: Map<String, String>,
    effectiveMutations: Map<String, PendingMutationEntity>,
    deletedFeedKeys: Set<String>,
    deletedItemKeys: Set<String>,
    survivingFeeds: List<FeedEntity>,
): List<ItemEntity> {
    val excludedIds = adjustedRemoteItems.map { it.id }.toSet() + alreadyPreservedIds
    val excludedNatKeys =
        adjustedRemoteItems
            .map { remote ->
                val fUrl = remote.feedUrl ?: remote.feedId?.let { feedUrlsById[it] }
                itemNaturalKey(fUrl, remote.url)
            }
            .toSet() + deletedItemKeys
    val survivingFeedsById = survivingFeeds.associateBy { it.id }
    val survivingFeedsByUrl = survivingFeeds.associateBy { it.url.trim().lowercase() }
    return existingItemsById.values.mapNotNull { local ->
        val localFeedUrl = local.feedUrl ?: local.feedId?.let { feedUrlsById[it] }
        val localNatKey = itemNaturalKey(localFeedUrl, local.url)
        val isDeleted = effectiveMutations[local.id]?.isDeletedItem == true
        if (local.id in excludedIds || localNatKey in excludedNatKeys || isDeleted) {
            null
        } else {
            resolveSurvivingDeltaItem(
                local = local,
                localFeedUrl = localFeedUrl,
                deletedFeedKeys = deletedFeedKeys,
                survivingFeedsById = survivingFeedsById,
                survivingFeedsByUrl = survivingFeedsByUrl,
            )
        }
    }
}

private fun resolveSurvivingDeltaItem(
    local: ItemEntity,
    localFeedUrl: String?,
    deletedFeedKeys: Set<String>,
    survivingFeedsById: Map<String, FeedEntity>,
    survivingFeedsByUrl: Map<String, FeedEntity>,
): ItemEntity? {
    if (local.feedId == null && localFeedUrl == null) return local
    val feedTombstoned = localFeedUrl != null && feedNaturalKey(localFeedUrl) in deletedFeedKeys
    val matchedFeed =
        local.feedId?.let { survivingFeedsById[it] }
            ?: localFeedUrl?.trim()?.lowercase()?.let { survivingFeedsByUrl[it] }
    if (feedTombstoned || matchedFeed == null) return null
    return local.copy(
        feedId = matchedFeed.id,
        feedUrl = matchedFeed.url,
        feedTitle = matchedFeed.title.ifBlank { local.feedTitle },
    )
}

private fun remapPreservedItemTags(
    rawPreservedItemTags: List<ItemTagEntity>,
    existingTagsById: Map<String, TagEntity>,
    tagEntitiesByName: Map<String, TagEntity>,
): List<ItemTagEntity> = rawPreservedItemTags.mapNotNull { itemTag ->
    val normalizedName = existingTagsById[itemTag.tagId]?.name?.trim()?.lowercase()
    val canonicalTag = normalizedName?.let { tagEntitiesByName[it] }
    canonicalTag?.let { ItemTagEntity(itemId = itemTag.itemId, tagId = it.id) }
}

@Suppress("LongParameterList")
private fun buildReplacementFeedEntities(
    unpushedLocalFeeds: List<FeedEntity>,
    survivingRemoteFeeds: List<RemoteFeedDto>,
    existingFeedsById: Map<String, FeedEntity>,
    existingFeedsByUrl: Map<String, FeedEntity>,
    effectiveMutations: Map<String, PendingMutationEntity> = emptyMap(),
    preFetchFeedsById: Map<String, FeedEntity> = emptyMap(),
): List<FeedEntity> =
    unpushedLocalFeeds +
        survivingRemoteFeeds.map { remoteFeed ->
            val existingFeed =
                existingFeedsById[remoteFeed.id]
                    ?: existingFeedsByUrl[remoteFeed.url.trim().lowercase()]
            val preFetchFeed =
                preFetchFeedsById[remoteFeed.id] ?: existingFeed?.let { preFetchFeedsById[it.id] }
            val mutation =
                effectiveMutations[remoteFeed.id] ?: existingFeed?.let { effectiveMutations[it.id] }
            val baseEntity = toRemoteFeedEntity(remoteFeed, existingFeed)
            baseEntity.copy(
                title =
                    resolveReplacementFeedTitle(baseEntity, existingFeed, preFetchFeed, mutation),
                isActive =
                    resolveReplacementFeedActive(baseEntity, existingFeed, preFetchFeed, mutation),
            )
        }

private fun resolveReplacementFeedActive(
    baseEntity: FeedEntity,
    existingFeed: FeedEntity?,
    preFetchFeed: FeedEntity?,
    mutation: PendingMutationEntity?,
): Boolean =
    if (
        preFetchFeed != null &&
            existingFeed != null &&
            existingFeed.isActive != preFetchFeed.isActive
    ) {
        existingFeed.isActive
    } else {
        mutation?.dirtyFeedIsActive ?: baseEntity.isActive
    }

private fun resolveReplacementFeedTitle(
    baseEntity: FeedEntity,
    existingFeed: FeedEntity?,
    preFetchFeed: FeedEntity?,
    mutation: PendingMutationEntity?,
): String =
    if (preFetchFeed != null && existingFeed != null && existingFeed.title != preFetchFeed.title) {
        existingFeed.title
    } else {
        mutation?.titleOverride?.takeIf { it.isNotBlank() } ?: baseEntity.title
    }

private fun buildReplacementItemEntities(
    preservedLocalItems: List<ItemEntity>,
    adjustedRemoteItems: List<RemoteItemDto>,
    existingItemsById: Map<String, ItemEntity>,
    existingItemsByNatKey: Map<String, ItemEntity>,
    feedTitlesById: Map<String, String>,
    feedUrlsById: Map<String, String>,
): List<ItemEntity> =
    preservedLocalItems +
        adjustedRemoteItems.map { remote ->
            val fUrl = remote.feedUrl ?: remote.feedId?.let { feedUrlsById[it] }
            val existing =
                existingItemsById[remote.id]
                    ?: existingItemsByNatKey[itemNaturalKey(fUrl, remote.url)]
            toRemoteItemEntity(
                remote = remote,
                feedTitlesById = feedTitlesById,
                isCachedOffline = existing?.isCachedOffline ?: false,
                existing = existing,
                feedUrlsById = feedUrlsById,
            )
        }

private fun collectTombstoneKeys(
    tombstones: List<TombstoneEntity>,
    entityType: String,
): Set<String> =
    tombstones
        .filter { it.entityType.equals(entityType, ignoreCase = true) }
        .map { it.naturalKey }
        .toSet()

private fun adjustRemoteItemsForReconcile(
    remoteItems: List<RemoteItemDto>,
    feedUrlsById: Map<String, String>,
    existingItemsById: Map<String, ItemEntity>,
    existingItemsByNatKey: Map<String, ItemEntity>,
    effectiveMutations: Map<String, PendingMutationEntity>,
    deletedItemKeys: Set<String>,
    preFetchItemsById: Map<String, ItemEntity>,
): List<RemoteItemDto> =
    remoteItems
        .filterNot { remote ->
            val fUrl = remote.feedUrl ?: remote.feedId?.let { feedUrlsById[it] }
            val natKey = itemNaturalKey(fUrl, remote.url)
            val matchedLocal = existingItemsById[remote.id] ?: existingItemsByNatKey[natKey]
            effectiveMutations[remote.id]?.isDeletedItem == true ||
                matchedLocal != null &&
                    effectiveMutations[matchedLocal.id]?.isDeletedItem == true ||
                natKey in deletedItemKeys
        }
        .map { remote ->
            val fUrl = remote.feedUrl ?: remote.feedId?.let { feedUrlsById[it] }
            val natKey = itemNaturalKey(fUrl, remote.url)
            val matchedLocal = existingItemsById[remote.id] ?: existingItemsByNatKey[natKey]
            val mutation =
                effectiveMutations[remote.id] ?: matchedLocal?.let { effectiveMutations[it.id] }
            val mutated = applyMutationToRemoteItem(remote, mutation)
            overlayInFlightLocalChanges(
                remote = mutated,
                preFetch =
                    preFetchItemsById[remote.id] ?: matchedLocal?.let { preFetchItemsById[it.id] },
                currentLocal = matchedLocal,
            )
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
        val isDeleted = effectiveMutations[local.id]?.isDeletedItem == true
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
    val interactionTsChanged = currentLocal.interactionUpdatedAt != preFetch.interactionUpdatedAt
    val coreChanged = statusChanged || favoriteChanged || progressChanged
    if (!coreChanged && !readAtChanged && !interactionTsChanged) return remote
    return remote.copy(
        status = if (statusChanged) currentLocal.status else remote.status,
        isFavorite = if (favoriteChanged) currentLocal.isFavorite else remote.isFavorite,
        readProgress = if (progressChanged) currentLocal.readProgress else remote.readProgress,
        readAt = if (readAtChanged) currentLocal.readAt else remote.readAt,
        interactionUpdatedAt =
            newerIsoTimestamp(currentLocal.interactionUpdatedAt, remote.interactionUpdatedAt),
    )
}

internal fun collectUnpushedLocalFeeds(
    remainingMutations: List<PendingMutationEntity>,
    existingFeedsById: Map<String, FeedEntity>,
    remoteFeeds: List<RemoteFeedDto>,
): List<FeedEntity> =
    remainingMutations
        .filter { it.kind == PendingEntityKind.LOCAL_FEED }
        .mapNotNull { existingFeedsById[it.entityId] }
        .filter { local -> remoteFeeds.none { it.url.equals(local.url, ignoreCase = true) } }

internal fun collectUnpushedLocalItems(
    remainingMutations: List<PendingMutationEntity>,
    existingItemsById: Map<String, ItemEntity>,
    adjustedRemoteItems: List<RemoteItemDto>,
    feedUrlsById: Map<String, String> = emptyMap(),
): List<ItemEntity> {
    val remoteNatKeys =
        adjustedRemoteItems
            .map { remote ->
                val fUrl = remote.feedUrl ?: remote.feedId?.let { feedUrlsById[it] }
                itemNaturalKey(fUrl, remote.url)
            }
            .toSet()
    return remainingMutations
        .filter { it.kind == PendingEntityKind.LOCAL_ITEM }
        .mapNotNull { existingItemsById[it.entityId] }
        .filter { local ->
            val localFeedUrl = local.feedUrl ?: local.feedId?.let { feedUrlsById[it] }
            val localNatKey = itemNaturalKey(localFeedUrl, local.url)
            adjustedRemoteItems.none { it.id == local.id } && localNatKey !in remoteNatKeys
        }
}

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
    survivingItemIds: Set<String>,
    survivingFeedIds: Set<String> = emptySet(),
) {
    remainingMutations
        .filter { mutation ->
            mutation.kind == PendingEntityKind.REMOTE_ITEM &&
                if (mutation.isRemoteFeedMutation) {
                    mutation.entityId !in survivingFeedIds
                } else {
                    mutation.entityId !in survivingItemIds
                }
        }
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
        mutation.resolveEffectiveReadAt(
            defaultReadAt = remote.readAt,
            statusAffectsDefault = true,
        )
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
            val color = remoteTag.color ?: existingTagsByName[normalized]?.color
            map[normalized] = TagEntity(id = remoteTag.id, name = normalized, color = color)
        }
    }
    remoteItems
        .flatMap { it.tagNames }
        .forEach { rawName ->
            val normalized = rawName.trim().lowercase()
            if (normalized.isNotBlank() && normalized !in map) {
                val preserved = existingTagsByName[normalized]
                val preservedId = preserved?.id ?: deterministicTagId(normalized)
                map[normalized] =
                    TagEntity(id = preservedId, name = normalized, color = preserved?.color)
            }
        }
    existingTagsByName.values
        .filter { it.id in preservedTagIds && it.name.trim().lowercase() !in map }
        .forEach { tag -> map[tag.name.trim().lowercase()] = tag }
    return map
}

@Suppress("LongParameterList")
internal suspend fun buildPeerSyncRequest(
    dao: RocheDao,
    peerId: String,
    deviceName: String,
    networkTier: NetworkTier = NetworkTier.WIFI,
    updatedAfter: String? = null,
    weightOverride: Double? = null,
    preloadedFeeds: List<FeedEntity>? = null,
    preloadedItems: List<ItemEntity>? = null,
    preloadedMutations: List<PendingMutationEntity>? = null,
): PeerSyncRequestDto {
    val feeds = preloadedFeeds ?: dao.getAllFeeds()
    val allItems = preloadedItems ?: dao.getAllItems()
    val feedUrlsById = feeds.associate { it.id to it.url }
    val tags = dao.getAllTags()
    val itemTagsByItemId = buildItemTagNamesByItemId(dao, tags)
    val mutationsById =
        (preloadedMutations ?: dao.getAllPendingMutations()).associateBy { it.entityId }
    val nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    val sinceInstant =
        resolveDeltaSinceInstant(updatedAfter, feeds.isNotEmpty() || allItems.isNotEmpty())
    val inclusiveSinceInstant = sinceInstant?.minusSeconds(1)
    val itemPayloads =
        allItems
            .filter { item ->
                mutationsById[item.id]?.isDeletedItem != true &&
                    isSyncablePublicUrl(item.url) &&
                    isDeltaPeerSyncItem(
                        item = item,
                        hasPendingMutation = item.id in mutationsById,
                        inclusiveSinceInstant = inclusiveSinceInstant,
                    )
            }
            .sortedByDescending { it.interactionUpdatedAt ?: it.publishedAt ?: it.createdAt }
            .take(MAX_PEER_SYNC_ITEMS)
            .map { item ->
                toPeerSyncItemPayload(
                    item = item,
                    mut = mutationsById[item.id],
                    itemTagNames = itemTagsByItemId[item.id].orEmpty(),
                    feedUrlsById = feedUrlsById,
                    nowIso = nowIso,
                )
            }
    val tombstones = collectPeerSyncTombstones(dao.getAllTombstones(), inclusiveSinceInstant)
    val effectiveWeight =
        if (networkTier == NetworkTier.OFFLINE) {
            0.0
        } else {
            weightOverride
        }
    return PeerSyncRequestDto(
        peerId = peerId,
        deviceName = deviceName,
        networkTier = networkTier.wireValue,
        weight = effectiveWeight,
        updatedAfter = sinceInstant?.toString(),
        feeds = feeds.filter { isSyncablePublicUrl(it.url) },
        items = itemPayloads,
        tags = tags,
        tombstones = tombstones,
    )
}

private suspend fun buildItemTagNamesByItemId(
    dao: RocheDao,
    tags: List<TagEntity>,
): Map<String, List<String>> {
    val tagsById = tags.associateBy { it.id }
    return dao.getAllItemTags()
        .groupBy(keySelector = { it.itemId }, valueTransform = { tagsById[it.tagId]?.name })
        .mapValues { (_, names) -> names.filterNotNull().sorted() }
}

private fun resolveDeltaSinceInstant(updatedAfter: String?, hasLocalData: Boolean): Instant? =
    updatedAfter
        ?.trim()
        ?.takeIf { it.isNotBlank() && hasLocalData }
        ?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }

private fun collectPeerSyncTombstones(
    tombstones: List<TombstoneEntity>,
    inclusiveSinceInstant: Instant?,
): List<SyncTombstoneDto> =
    tombstones
        .filter { tomb ->
            inclusiveSinceInstant == null ||
                isIsoTimestampAtOrAfter(tomb.deletedAt, inclusiveSinceInstant)
        }
        .map {
            SyncTombstoneDto(
                entityType = it.entityType,
                naturalKey = it.naturalKey,
                deletedAt = it.deletedAt,
            )
        }

private fun isDeltaPeerSyncItem(
    item: ItemEntity,
    hasPendingMutation: Boolean,
    inclusiveSinceInstant: Instant?,
): Boolean {
    if (inclusiveSinceInstant == null) return true
    return hasPendingMutation ||
        isIsoTimestampAtOrAfter(item.interactionUpdatedAt, inclusiveSinceInstant) ||
        isIsoTimestampAtOrAfter(item.createdAt, inclusiveSinceInstant)
}

private fun isIsoTimestampAtOrAfter(rawIso: String?, threshold: Instant): Boolean {
    if (rawIso.isNullOrBlank()) return false
    val parsed = runCatching { Instant.parse(rawIso.trim()) }.getOrNull() ?: return false
    return !parsed.isBefore(threshold)
}

private fun toPeerSyncItemPayload(
    item: ItemEntity,
    mut: PendingMutationEntity?,
    itemTagNames: List<String>,
    feedUrlsById: Map<String, String>,
    nowIso: String,
): PeerSyncItemPayload {
    val baseTags = itemTagNames.toMutableSet()
    if (mut != null) {
        baseTags.addAll(parseTagCsv(mut.addedTagsCsv))
        baseTags.removeAll(parseTagCsv(mut.removedTagsCsv).toSet())
    }
    val hasPendingUserChanges =
        mut != null &&
            (hasDirtyItemFields(mut) ||
                mut.addedTagsCsv.isNotBlank() ||
                mut.removedTagsCsv.isNotBlank())
    val effectiveInterTs = item.interactionUpdatedAt ?: if (hasPendingUserChanges) nowIso else null
    val effectiveReadAt =
        mut?.resolveEffectiveReadAt(defaultReadAt = item.readAt, statusAffectsDefault = true)
            ?: item.readAt
    val rawFeedUrl = item.feedUrl ?: item.feedId?.let { feedUrlsById[it] }
    return PeerSyncItemPayload(
        id = item.id,
        feedId = item.feedId,
        feedUrl = rawFeedUrl?.takeIf { isSyncablePublicUrl(it) },
        title = mut?.titleOverride?.takeIf { it.isNotBlank() } ?: item.title,
        url = item.url,
        author = item.author,
        publishedAt = item.publishedAt,
        createdAt = item.createdAt,
        summary = item.summary,
        contentHtml = item.contentHtml,
        thumbnailUrl = item.thumbnailUrl,
        contentType = item.contentType,
        status = mut?.dirtyStatus ?: item.status,
        isFavorite = mut?.dirtyFavorite ?: item.isFavorite,
        readAt = effectiveReadAt,
        readProgress = mut?.dirtyReadProgress ?: item.readProgress,
        interactionUpdatedAt = effectiveInterTs,
        tagNames = baseTags.sorted(),
    )
}

private const val MAX_PEER_SYNC_ITEMS = 2_000

internal suspend fun reconcilePeerSyncResponse(
    dao: RocheDao,
    response: PeerSyncResponseDto,
    preFetchItemsById: Map<String, ItemEntity>,
    preFetchFeedsById: Map<String, FeedEntity> = emptyMap(),
    preSyncMutations: List<PendingMutationEntity> = emptyList(),
    isDeltaItems: Boolean = false,
) {
    val remoteTombstoneKeys =
        response.tombstones.map { it.entityType.lowercase() to it.naturalKey }.toSet()
    val liveFeedUrlsById = response.feeds.associate { it.id to it.url }
    val liveFeedKeys = response.feeds.map { feedNaturalKey(it.url) }.toSet()
    val liveItemKeys =
        response.items
            .map { item ->
                val fUrl = item.feedUrl ?: item.feedId?.let { liveFeedUrlsById[it] }
                itemNaturalKey(fUrl, item.url)
            }
            .toSet()
    val liveTagKeys = response.tags.map { tagNaturalKey(it.name) }.toSet()

    for (localTomb in dao.getAllTombstones()) {
        val typeLower = localTomb.entityType.lowercase()
        val keyPair = typeLower to localTomb.naturalKey
        val isResurrectedOnPeer =
            keyPair !in remoteTombstoneKeys &&
                when (typeLower) {
                    "feed" -> localTomb.naturalKey in liveFeedKeys
                    "item" -> localTomb.naturalKey in liveItemKeys
                    "tag" -> localTomb.naturalKey in liveTagKeys
                    else -> false
                }
        if (isResurrectedOnPeer) {
            dao.deleteTombstone(localTomb.entityType, localTomb.naturalKey)
        }
    }

    if (response.tombstones.isNotEmpty()) {
        dao.upsertTombstones(
            response.tombstones.map {
                TombstoneEntity(
                    entityType = it.entityType,
                    naturalKey = it.naturalKey,
                    deletedAt = it.deletedAt,
                )
            }
        )
    }
    val currentMutationsById = dao.getAllPendingMutations().associateBy { it.entityId }
    for (preMut in preSyncMutations) {
        if (!preMut.isRemoteFeedMutation && currentMutationsById[preMut.entityId] == preMut) {
            dao.deletePendingMutation(preMut.entityId)
        }
    }
    reconcileRemoteSnapshot(
        dao = dao,
        remoteFeeds = response.feeds,
        remoteItems = response.items,
        remoteTags = response.tags,
        appliedMutations = emptyList(),
        preFetchItemsById = preFetchItemsById,
        preFetchFeedsById = preFetchFeedsById,
        isDeltaItems = isDeltaItems,
    )
}

@Suppress("LongParameterList")
internal suspend fun tryPeerSyncAndCooperativePoll(
    dao: RocheDao,
    apiClient: RocheApiClient,
    currentSettings: Settings,
    networkTier: NetworkTier = NetworkTier.WIFI,
    onPeersUpdated: (List<PeerNodeDto>) -> Unit = {},
    onSyncCursorUpdated: suspend (String) -> Unit = {},
    initialWeightOverride: Double? = null,
): Int? {
    val response =
        executeSinglePeerSyncRound(
            dao = dao,
            apiClient = apiClient,
            currentSettings = currentSettings,
            networkTier = networkTier,
            weightOverride = initialWeightOverride,
        ) ?: return null
    onPeersUpdated(response.activePeers)
    val latestCursor = response.syncTimestamp.trim()
    val updatedSettings =
        if (latestCursor.isNotBlank()) {
            onSyncCursorUpdated(latestCursor)
            currentSettings.copy(lastPeerSyncCursor = latestCursor)
        } else {
            currentSettings
        }
    var measuredWeight: Double? = null
    var attemptedAnyFeed = false
    if (networkTier != NetworkTier.OFFLINE) {
        pollCooperativeFeedsOnDevice(
            dao = dao,
            apiClient = apiClient,
            localPeerId = updatedSettings.peerId,
            activePeers = response.activePeers,
            onMeasuredWeight = { measuredWeight = it },
            onAttemptedFeeds = { attemptedAnyFeed = it > 0 },
        )
    }
    if (attemptedAnyFeed) {
        val followUpResp =
            executeSinglePeerSyncRound(
                dao = dao,
                apiClient = apiClient,
                currentSettings = updatedSettings,
                networkTier = networkTier,
                weightOverride = measuredWeight,
            )
        if (followUpResp != null) {
            onPeersUpdated(followUpResp.activePeers)
            val followUpCursor = followUpResp.syncTimestamp.trim()
            if (followUpCursor.isNotBlank()) {
                onSyncCursorUpdated(followUpCursor)
            }
        }
    }
    return dao.countItems()
}

private suspend fun executeSinglePeerSyncRound(
    dao: RocheDao,
    apiClient: RocheApiClient,
    currentSettings: Settings,
    networkTier: NetworkTier,
    weightOverride: Double? = null,
): PeerSyncResponseDto? {
    for (mutation in dao.getAllPendingMutations().filter { it.isRemoteFeedMutation }) {
        pushPendingFeedMutation(
            dao = dao,
            apiClient = apiClient,
            serverUrl = currentSettings.serverUrl,
            token = currentSettings.bearerToken,
            mutation = mutation,
        )
    }
    val preFetchItems = dao.getAllItems()
    val preFetchFeeds = dao.getAllFeeds()
    val preSyncMutations = dao.getAllPendingMutations()
    val request =
        buildPeerSyncRequest(
            dao = dao,
            peerId = currentSettings.peerId,
            deviceName = currentSettings.deviceName,
            networkTier = networkTier,
            updatedAfter = currentSettings.lastPeerSyncCursor,
            weightOverride = weightOverride,
            preloadedFeeds = preFetchFeeds,
            preloadedItems = preFetchItems,
            preloadedMutations = preSyncMutations,
        )
    val syncedResponse = runCatching {
        apiClient.syncPeer(
            serverUrl = currentSettings.serverUrl,
            token = currentSettings.bearerToken,
            request = request,
        )
    }
        .onFailure { error ->
            if (error is CancellationException) throw error
            if (request.updatedAfter != null || error !is IOException) throw error
        }
        .getOrNull()
    val response = syncedResponse ?: return null
    reconcilePeerSyncResponse(
        dao = dao,
        response = response,
        preFetchItemsById = preFetchItems.associateBy { it.id },
        preFetchFeedsById = preFetchFeeds.associateBy { it.id },
        preSyncMutations = preSyncMutations,
        isDeltaItems = request.updatedAfter != null,
    )
    return response
}

@Suppress("LongParameterList")
internal suspend fun pollCooperativeFeedsOnDevice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    localPeerId: String,
    activePeers: List<PeerNodeDto>,
    nowInstant: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
    onMeasuredWeight: (Double) -> Unit = {},
    onAttemptedFeeds: (Int) -> Unit = {},
): Int {
    val attemptedIds = mutableSetOf<String>()
    var currentPeers = activePeers
    var polledFeedsCount = 0
    var totalItemsProcessed = 0
    val startedNanos = System.nanoTime()

    var keepPolling = true
    while (keepPolling) {
        val slice =
            dao.getAllFeeds()
                .filter {
                    it.id !in attemptedIds &&
                        shouldPeerPollFeed(localPeerId, currentPeers, it, nowInstant)
                }
                .take(COOPERATIVE_POLL_SLICE_SIZE)
        if (slice.isEmpty()) {
            keepPolling = false
        } else {
            slice.forEach { attemptedIds.add(it.id) }
            val results = coroutineScope {
                slice
                    .map { feed ->
                        async {
                            LocalFeedPoller.pollFeed(
                                dao = dao,
                                apiClient = apiClient,
                                feed = feed,
                                localPeerId = localPeerId,
                                nowInstant = nowInstant,
                            )
                        }
                    }
                    .awaitAll()
            }

            val succeededCounts = results.mapNotNull { it.getOrNull() }
            if (succeededCounts.isNotEmpty()) {
                polledFeedsCount += succeededCounts.size
                totalItemsProcessed += succeededCounts.sum()
                val elapsedSeconds =
                    (System.nanoTime() - startedNanos).toDouble() / NANOS_PER_SECOND
                val updatedWeight =
                    computeThroughputWeight(
                        feedsCompleted = polledFeedsCount,
                        itemsProcessed = totalItemsProcessed,
                        elapsedSeconds = elapsedSeconds,
                    )
                onMeasuredWeight(updatedWeight)
                currentPeers = currentPeers.map { peer ->
                    if (peer.peerId == localPeerId) peer.copy(weight = updatedWeight) else peer
                }
            }
        }
    }
    onAttemptedFeeds(attemptedIds.size)
    return polledFeedsCount
}

private const val COOPERATIVE_POLL_SLICE_SIZE = 4
private const val NANOS_PER_SECOND = 1_000_000_000.0
