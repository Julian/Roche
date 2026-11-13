package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.PendingEntityKind
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RemoteTagDto

internal data class RemoteSnapshotData(
    val feeds: List<RemoteFeedDto>,
    val items: List<RemoteItemDto>,
    val tags: List<RemoteTagDto>,
)

internal data class ReconcileOptions(
    val appliedMutations: List<PendingMutationEntity> = emptyList(),
    val preFetchItemsById: Map<String, ItemEntity> = emptyMap(),
    val preFetchFeedsById: Map<String, FeedEntity> = emptyMap(),
    val isDeltaItems: Boolean = false,
    val overflowItemIds: Set<String> = emptySet(),
)

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
    snapshot: RemoteSnapshotData,
    options: ReconcileOptions = ReconcileOptions(),
) {
    val localState =
        LocalReconcileState(
            items = dao.getAllItems(),
            feeds = dao.getAllFeeds(),
            tags = dao.getAllTags(),
            remainingMutations = dao.getAllPendingMutations(),
            tombstones = dao.getAllTombstones(),
        )
    val context = ReconcileContext(localState, snapshot, options)
    val adjustedRemoteItems = context.adjustRemoteItems()
    val replacementFeeds = context.buildReplacementFeeds()
    val feedTitlesById = replacementFeeds.associate { it.id to it.title }
    val preservedLocalItems =
        context.collectPreservedLocalItems(
            adjustedRemoteItems = adjustedRemoteItems,
            survivingFeeds = replacementFeeds,
        )
    val (replacementTags, replacementItemTags) =
        context.buildTagAndItemTagLists(
            adjustedRemoteItems = adjustedRemoteItems,
            preservedLocalItems = preservedLocalItems,
            allItemTags = dao.getAllItemTags(),
        )
    val replacementItems =
        context.buildReplacementItems(
            preservedLocalItems = preservedLocalItems,
            adjustedRemoteItems = adjustedRemoteItems,
            feedTitlesById = feedTitlesById,
        )

    dao.reconcileSnapshotDiff(
        feeds = replacementFeeds,
        tags = replacementTags,
        items = replacementItems,
        itemTags = replacementItemTags,
    )
    pruneOrphanedPendingMutations(
        dao = dao,
        remainingMutations = context.remainingMutations,
        survivingItemIds = replacementItems.map { it.id }.toSet(),
        survivingFeedIds = replacementFeeds.map { it.id }.toSet(),
    )
}

internal data class LocalReconcileState(
    val items: List<ItemEntity>,
    val feeds: List<FeedEntity>,
    val tags: List<TagEntity>,
    val remainingMutations: List<PendingMutationEntity>,
    val tombstones: List<TombstoneEntity>,
)

internal class ReconcileContext(
    local: LocalReconcileState,
    private val snapshot: RemoteSnapshotData,
    private val options: ReconcileOptions,
) {
    private val existingItemsById = local.items.associateBy { it.id }
    private val existingFeedsById = local.feeds.associateBy { it.id }
    private val existingFeedsByUrl = local.feeds.associateBy { feedNaturalKey(it.url) }
    private val existingTagsById = local.tags.associateBy { it.id }
    private val existingTagsByName = local.tags.associateBy { it.name.trim().lowercase() }
    internal val remainingMutations = local.remainingMutations
    private val effectiveMutations =
        (options.appliedMutations + remainingMutations).associateBy { it.entityId }
    private val deletedFeedKeys = collectTombstoneKeys(local.tombstones, "feed")
    private val deletedItemKeys = collectTombstoneKeys(local.tombstones, "item")
    private val deletedTagKeys = collectTombstoneKeys(local.tombstones, "tag")
    private val survivingRemoteFeeds =
        snapshot.feeds.filterNot { feedNaturalKey(it.url) in deletedFeedKeys }
    private val feedUrlsById =
        (local.feeds.map { it.id to it.url } + survivingRemoteFeeds.map { it.id to it.url }).toMap()
    private val existingItemsByNatKey =
        local.items.associateBy { item ->
            itemNaturalKey(item.feedUrl ?: item.feedId?.let { feedUrlsById[it] }, item.url)
        }

    internal fun adjustRemoteItems(): List<RemoteItemDto> =
        snapshot.items
            .filterNot { remote ->
                val fUrl = remote.feedUrl ?: remote.feedId?.let { feedUrlsById[it] }
                val natKey = itemNaturalKey(fUrl, remote.url)
                val matchedLocal = existingItemsById[remote.id] ?: existingItemsByNatKey[natKey]
                val deletedByMutation =
                    effectiveMutations[remote.id]?.isDeletedItem == true ||
                        matchedLocal != null &&
                            effectiveMutations[matchedLocal.id]?.isDeletedItem == true
                deletedByMutation || natKey in deletedItemKeys
            }
            .map { remote ->
                val fUrl = remote.feedUrl ?: remote.feedId?.let { feedUrlsById[it] }
                val natKey = itemNaturalKey(fUrl, remote.url)
                val matchedLocal = existingItemsById[remote.id] ?: existingItemsByNatKey[natKey]
                val mutation =
                    effectiveMutations[remote.id] ?: matchedLocal?.let { effectiveMutations[it.id] }
                val mutated = applyMutationToRemoteItem(remote, mutation)
                val preFetch =
                    options.preFetchItemsById[remote.id]
                        ?: matchedLocal?.let { options.preFetchItemsById[it.id] }
                overlayInFlightLocalChanges(
                    remote = mutated,
                    preFetch = preFetch,
                    currentLocal = matchedLocal,
                )
            }

    internal fun buildReplacementFeeds(): List<FeedEntity> {
        val unpushedLocalFeeds =
            collectUnpushedLocalFeeds(
                remainingMutations = remainingMutations,
                existingFeedsById = existingFeedsById,
                remoteFeeds = survivingRemoteFeeds,
                deletedFeedKeys = deletedFeedKeys,
                effectiveMutations = effectiveMutations,
            )
        return unpushedLocalFeeds +
            survivingRemoteFeeds.map { remoteFeed ->
                val existingFeed =
                    existingFeedsById[remoteFeed.id]
                        ?: existingFeedsByUrl[feedNaturalKey(remoteFeed.url)]
                val preFetchFeed =
                    options.preFetchFeedsById[remoteFeed.id]
                        ?: existingFeed?.let { options.preFetchFeedsById[it.id] }
                val mutation =
                    effectiveMutations[remoteFeed.id]
                        ?: existingFeed?.let { effectiveMutations[it.id] }
                val baseEntity = toRemoteFeedEntity(remoteFeed, existingFeed)
                baseEntity.copy(
                    title =
                        resolveReplacementFeedTitle(
                            baseEntity,
                            existingFeed,
                            preFetchFeed,
                            mutation,
                        ),
                    isActive =
                        resolveReplacementFeedActive(
                            baseEntity,
                            existingFeed,
                            preFetchFeed,
                            mutation,
                        ),
                )
            }
    }

    internal fun collectPreservedLocalItems(
        adjustedRemoteItems: List<RemoteItemDto>,
        survivingFeeds: List<FeedEntity>,
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
        val priorPreserved = unpushedLocalItems + preservedOfflineItems
        val feedIndex = SurvivingFeedIndex(survivingFeeds, deletedFeedKeys)
        val unsyncedAndOverflowItems =
            collectSurvivingLocalItems(
                adjustedRemoteItems = adjustedRemoteItems,
                alreadyPreservedIds = priorPreserved.map { it.id }.toSet(),
                feedIndex = feedIndex,
                requireUnsyncedOrOverflow = true,
            )
        val basePreserved = priorPreserved + unsyncedAndOverflowItems
        if (!options.isDeltaItems) return basePreserved
        val deltaUnchangedItems =
            collectSurvivingLocalItems(
                adjustedRemoteItems = adjustedRemoteItems,
                alreadyPreservedIds = basePreserved.map { it.id }.toSet(),
                feedIndex = feedIndex,
                requireUnsyncedOrOverflow = false,
            )
        return basePreserved + deltaUnchangedItems
    }

    private fun collectSurvivingLocalItems(
        adjustedRemoteItems: List<RemoteItemDto>,
        alreadyPreservedIds: Set<String>,
        feedIndex: SurvivingFeedIndex,
        requireUnsyncedOrOverflow: Boolean,
    ): List<ItemEntity> {
        val excludedIds = adjustedRemoteItems.map { it.id }.toSet() + alreadyPreservedIds
        val excludedNatKeys =
            adjustedRemoteItems
                .map { remote ->
                    val fUrl = remote.feedUrl ?: remote.feedId?.let { feedUrlsById[it] }
                    itemNaturalKey(fUrl, remote.url)
                }
                .toSet() + deletedItemKeys
        return existingItemsById.values.mapNotNull { local ->
            val localFeedUrl = local.feedUrl ?: local.feedId?.let { feedUrlsById[it] }
            val localNatKey = itemNaturalKey(localFeedUrl, local.url)
            val isDeleted = effectiveMutations[local.id]?.isDeletedItem == true
            if (local.id in excludedIds || localNatKey in excludedNatKeys || isDeleted) {
                null
            } else if (requireUnsyncedOrOverflow) {
                feedIndex.resolveLocalOrOverflow(
                    local = local,
                    localFeedUrl = localFeedUrl,
                    isOverflow = local.id in options.overflowItemIds,
                )
            } else {
                feedIndex.resolveItem(local = local, localFeedUrl = localFeedUrl)
            }
        }
    }

    internal fun buildTagAndItemTagLists(
        adjustedRemoteItems: List<RemoteItemDto>,
        preservedLocalItems: List<ItemEntity>,
        allItemTags: List<ItemTagEntity>,
    ): Pair<List<TagEntity>, List<ItemTagEntity>> {
        val preservedLocalItemIds = preservedLocalItems.map { it.id }.toSet()
        val rawPreservedItemTags = allItemTags.filter { it.itemId in preservedLocalItemIds }
        val tagEntitiesByName =
            buildRemoteTagEntityMap(
                remoteTags = snapshot.tags.filterNot { tagNaturalKey(it.name) in deletedTagKeys },
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
        val remoteItemTagEntities =
            buildRemoteItemTagEntities(adjustedRemoteItems, tagEntitiesByName)
        return tagEntitiesByName.values.toList() to
            (preservedItemTags + remoteItemTagEntities).distinct()
    }

    internal fun buildReplacementItems(
        preservedLocalItems: List<ItemEntity>,
        adjustedRemoteItems: List<RemoteItemDto>,
        feedTitlesById: Map<String, String>,
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
}

internal class SurvivingFeedIndex(
    survivingFeeds: List<FeedEntity>,
    private val deletedFeedKeys: Set<String>,
) {
    private val feedsById = survivingFeeds.associateBy { it.id }
    private val feedsByUrl = survivingFeeds.associateBy { feedNaturalKey(it.url) }

    internal fun resolveItem(local: ItemEntity, localFeedUrl: String?): ItemEntity? {
        if (local.feedId == null && localFeedUrl == null) return local
        val feedTombstoned = localFeedUrl != null && feedNaturalKey(localFeedUrl) in deletedFeedKeys
        val matchedFeed =
            local.feedId?.let { feedsById[it] }
                ?: localFeedUrl?.let { feedsByUrl[feedNaturalKey(it)] }
        if (feedTombstoned || matchedFeed == null) return null
        return local.copy(
            feedId = matchedFeed.id,
            feedUrl = matchedFeed.url,
            feedTitle = matchedFeed.title.ifBlank { local.feedTitle },
        )
    }

    internal fun resolveLocalOrOverflow(
        local: ItemEntity,
        localFeedUrl: String?,
        isOverflow: Boolean,
    ): ItemEntity? {
        val resolved = resolveItem(local, localFeedUrl) ?: return null
        val effectiveFeedUrl = resolved.feedUrl ?: localFeedUrl
        val hasUnsyncedFeed = effectiveFeedUrl != null && !isSyncablePublicUrl(effectiveFeedUrl)
        val shouldPreserve = hasUnsyncedFeed || !isSyncableItemUrl(resolved.url) || isOverflow
        return resolved.takeIf { shouldPreserve }
    }
}

internal fun remapPreservedItemTags(
    rawPreservedItemTags: List<ItemTagEntity>,
    existingTagsById: Map<String, TagEntity>,
    tagEntitiesByName: Map<String, TagEntity>,
): List<ItemTagEntity> = rawPreservedItemTags.mapNotNull { itemTag ->
    val normalizedName = existingTagsById[itemTag.tagId]?.name?.trim()?.lowercase()
    val canonicalTag = normalizedName?.let { tagEntitiesByName[it] }
    canonicalTag?.let { ItemTagEntity(itemId = itemTag.itemId, tagId = it.id) }
}

internal fun resolveReplacementFeedActive(
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

internal fun resolveReplacementFeedTitle(
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

internal fun collectTombstoneKeys(
    tombstones: List<TombstoneEntity>,
    entityType: String,
): Set<String> =
    tombstones
        .filter { it.entityType.equals(entityType, ignoreCase = true) }
        .map { it.naturalKey }
        .toSet()

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
    deletedFeedKeys: Set<String> = emptySet(),
    effectiveMutations: Map<String, PendingMutationEntity> = emptyMap(),
): List<FeedEntity> {
    val remoteIds = remoteFeeds.map { it.id }.toSet()
    val remoteNatKeys = remoteFeeds.map { feedNaturalKey(it.url) }.toSet()
    val unpushedIds =
        remainingMutations
            .filter { it.kind == PendingEntityKind.LOCAL_FEED }
            .map { it.entityId }
            .toSet()
    return existingFeedsById.values
        .filter { local ->
            val natKey = feedNaturalKey(local.url)
            val shouldKeepLocal = local.id in unpushedIds || !isSyncablePublicUrl(local.url)
            shouldKeepLocal &&
                local.id !in remoteIds &&
                natKey !in remoteNatKeys &&
                natKey !in deletedFeedKeys
        }
        .map { local ->
            val mut = effectiveMutations[local.id]
            local.copy(
                title = mut?.titleOverride?.takeIf { it.isNotBlank() } ?: local.title,
                isActive = mut?.dirtyFeedIsActive ?: local.isActive,
            )
        }
}

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
