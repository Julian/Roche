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
import java.time.Instant
import java.time.temporal.ChronoUnit

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

@Suppress("LongMethod")
internal suspend fun reconcileRemoteSnapshot(
    dao: RocheDao,
    remoteFeeds: List<RemoteFeedDto>,
    remoteItems: List<RemoteItemDto>,
    remoteTags: List<RemoteTagDto>,
    appliedMutations: List<PendingMutationEntity> = emptyList(),
    preFetchItemsById: Map<String, ItemEntity> = emptyMap(),
) {
    val allExistingItems = dao.getAllItems()
    val existingItemsById = allExistingItems.associateBy { it.id }
    val allExistingFeeds = dao.getAllFeeds()
    val existingFeedsById = allExistingFeeds.associateBy { it.id }
    val existingFeedsByUrl = allExistingFeeds.associateBy { it.url.trim().lowercase() }
    val existingTagsByName = dao.getAllTags().associateBy { it.name.trim().lowercase() }
    val remainingMutations = dao.getAllPendingMutations()
    val effectiveMutations = (appliedMutations + remainingMutations).associateBy { it.entityId }
    val tombstones = dao.getAllTombstones()
    val deletedFeedKeys = collectTombstoneKeys(tombstones, "feed")
    val deletedItemKeys = collectTombstoneKeys(tombstones, "item")

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
    val preservedLocalItems =
        collectPreservedLocalItems(
            remainingMutations = remainingMutations,
            existingItemsById = existingItemsById,
            adjustedRemoteItems = adjustedRemoteItems,
            feedUrlsById = feedUrlsById,
            effectiveMutations = effectiveMutations,
        )
    val preservedLocalItemIds = preservedLocalItems.map { it.id }.toSet()
    val preservedItemTags = dao.getAllItemTags().filter { it.itemId in preservedLocalItemIds }
    val feedTitlesById = survivingRemoteFeeds.associate { it.id to it.title }
    val tagEntitiesByName =
        buildRemoteTagEntityMap(
            remoteTags = remoteTags,
            remoteItems = adjustedRemoteItems,
            existingTagsByName = existingTagsByName,
            preservedTagIds = preservedItemTags.map { it.tagId }.toSet(),
        )
    val remoteItemTagEntities = buildRemoteItemTagEntities(adjustedRemoteItems, tagEntitiesByName)

    dao.replaceSnapshotAtomically(
        feeds =
            buildReplacementFeedEntities(
                unpushedLocalFeeds = unpushedLocalFeeds,
                survivingRemoteFeeds = survivingRemoteFeeds,
                existingFeedsById = existingFeedsById,
                existingFeedsByUrl = existingFeedsByUrl,
            ),
        tags = tagEntitiesByName.values.toList(),
        items =
            buildReplacementItemEntities(
                preservedLocalItems = preservedLocalItems,
                adjustedRemoteItems = adjustedRemoteItems,
                existingItemsById = existingItemsById,
                existingItemsByNatKey = existingItemsByNatKey,
                feedTitlesById = feedTitlesById,
                feedUrlsById = feedUrlsById,
            ),
        itemTags = (preservedItemTags + remoteItemTagEntities).distinct(),
    )
    pruneOrphanedPendingMutations(dao, remainingMutations, remoteItems)
}

private fun collectPreservedLocalItems(
    remainingMutations: List<PendingMutationEntity>,
    existingItemsById: Map<String, ItemEntity>,
    adjustedRemoteItems: List<RemoteItemDto>,
    feedUrlsById: Map<String, String>,
    effectiveMutations: Map<String, PendingMutationEntity>,
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
    return unpushedLocalItems + preservedOfflineItems
}

private fun buildReplacementFeedEntities(
    unpushedLocalFeeds: List<FeedEntity>,
    survivingRemoteFeeds: List<RemoteFeedDto>,
    existingFeedsById: Map<String, FeedEntity>,
    existingFeedsByUrl: Map<String, FeedEntity>,
): List<FeedEntity> =
    unpushedLocalFeeds +
        survivingRemoteFeeds.map { remoteFeed ->
            val existingFeed =
                existingFeedsById[remoteFeed.id]
                    ?: existingFeedsByUrl[remoteFeed.url.trim().lowercase()]
            toRemoteFeedEntity(remoteFeed, existingFeed)
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
    adjustedRemoteItems: List<RemoteItemDto>,
) {
    val remoteItemIds = adjustedRemoteItems.map { it.id }.toSet()
    remainingMutations
        .filter { it.kind == PendingEntityKind.REMOTE_ITEM && it.entityId !in remoteItemIds }
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

internal suspend fun buildPeerSyncRequest(
    dao: RocheDao,
    peerId: String,
    deviceName: String,
    networkTier: NetworkTier = NetworkTier.WIFI,
): PeerSyncRequestDto {
    val feeds = dao.getAllFeeds()
    val feedUrlsById = feeds.associate { it.id to it.url }
    val tags = dao.getAllTags()
    val tagsById = tags.associateBy { it.id }
    val itemTagsByItemId =
        dao.getAllItemTags()
            .groupBy(
                keySelector = { it.itemId },
                valueTransform = { tagsById[it.tagId]?.name },
            )
            .mapValues { (_, names) -> names.filterNotNull().sorted() }
    val mutationsById = dao.getAllPendingMutations().associateBy { it.entityId }
    val nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    val syncableFeeds = feeds.filter { isSyncablePublicUrl(it.url) }
    val itemPayloads =
        dao.getAllItems()
            .filter {
                mutationsById[it.id]?.isDeletedItem != true && isSyncablePublicUrl(it.url)
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

    val tombstones =
        dao.getAllTombstones().map {
            SyncTombstoneDto(
                entityType = it.entityType,
                naturalKey = it.naturalKey,
                deletedAt = it.deletedAt,
            )
        }

    return PeerSyncRequestDto(
        peerId = peerId,
        deviceName = deviceName,
        networkTier = networkTier.wireValue,
        weight = networkTier.defaultWeight,
        feeds = syncableFeeds,
        items = itemPayloads,
        tags = tags,
        tombstones = tombstones,
    )
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
    preSyncMutations: List<PendingMutationEntity> = emptyList(),
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
        if (currentMutationsById[preMut.entityId] == preMut) {
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
    )
}

internal suspend fun tryPeerSyncAndCooperativePoll(
    dao: RocheDao,
    apiClient: RocheApiClient,
    currentSettings: Settings,
): Int? {
    val preFetchItemsById = dao.getAllItems().associateBy { it.id }
    val preSyncMutations = dao.getAllPendingMutations()
    val request =
        buildPeerSyncRequest(
            dao = dao,
            peerId = currentSettings.peerId,
            deviceName = currentSettings.deviceName,
        )
    val response =
        apiClient.syncPeer(
            serverUrl = currentSettings.serverUrl,
            token = currentSettings.bearerToken,
            request = request,
        ) ?: return null
    reconcilePeerSyncResponse(
        dao = dao,
        response = response,
        preFetchItemsById = preFetchItemsById,
        preSyncMutations = preSyncMutations,
    )
    val polledFeedsCount =
        pollCooperativeFeedsOnDevice(
            dao = dao,
            apiClient = apiClient,
            localPeerId = currentSettings.peerId,
            activePeers = response.activePeers,
        )
    if (polledFeedsCount > 0) {
        val followUpPreFetch = dao.getAllItems().associateBy { it.id }
        val followUpPreSyncMutations = dao.getAllPendingMutations()
        val followUpReq =
            buildPeerSyncRequest(
                dao = dao,
                peerId = currentSettings.peerId,
                deviceName = currentSettings.deviceName,
            )
        val followUpResp =
            apiClient.syncPeer(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                request = followUpReq,
            )
        if (followUpResp != null) {
            reconcilePeerSyncResponse(
                dao = dao,
                response = followUpResp,
                preFetchItemsById = followUpPreFetch,
                preSyncMutations = followUpPreSyncMutations,
            )
        }
    }
    return dao.getAllItems().size
}

internal suspend fun pollCooperativeFeedsOnDevice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    localPeerId: String,
    activePeers: List<PeerNodeDto>,
    nowInstant: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
): Int {
    val feeds = dao.getAllFeeds()
    var polledFeedsCount = 0
    for (feed in feeds) {
        if (shouldPeerPollFeed(localPeerId, activePeers, feed, nowInstant)) {
            val result =
                LocalFeedPoller.pollFeed(
                    dao = dao,
                    apiClient = apiClient,
                    feed = feed,
                    localPeerId = localPeerId,
                    nowInstant = nowInstant,
                )
            if (result.isSuccess) {
                polledFeedsCount++
            }
        }
    }
    return polledFeedsCount
}
