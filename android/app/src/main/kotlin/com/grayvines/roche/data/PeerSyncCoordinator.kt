package com.grayvines.roche.data

import com.grayvines.roche.data.local.FLAG_CATALOG_BACKFILL_NEEDED
import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.PendingEntityKind
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.SQLITE_DELETE_CHUNK_SIZE
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.remote.PeerSyncItemPayload
import com.grayvines.roche.data.remote.PeerSyncRequestDto
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.Settings
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal data class BuiltPeerSyncRequest(
    val request: PeerSyncRequestDto,
    val sentFeedIds: Set<String>,
    val sentItemIds: Set<String>,
    val overflowItemIds: Set<String>,
    val byteOverflowItemIds: Set<String> = emptySet(),
    val sentTombstoneKeys: Set<Pair<String, String>> = emptySet(),
    val sentCatalogBatch: OutgoingCatalogSyncBatch = OutgoingCatalogSyncBatch(),
)

internal data class ItemBudgetSplit(
    val sentItems: List<ItemEntity>,
    val byteOverflowIds: Set<String>,
    val countOverflowIds: Set<String>,
) {
    val allOverflowIds: Set<String>
        get() = byteOverflowIds + countOverflowIds
}

internal data class PeerSyncPreloadedState(
    val feeds: List<FeedEntity>,
    val items: List<ItemEntity>,
    val mutations: List<PendingMutationEntity> = emptyList(),
)

internal data class PeerSyncRequestConfig(
    val peerId: String,
    val deviceName: String,
    val networkTier: NetworkTier = NetworkTier.WIFI,
    val updatedAfter: String? = null,
    val weightOverride: Double? = null,
    val forcedItemIds: Set<String> = emptySet(),
    val excludeItemIds: Set<String> = emptySet(),
    val maxItemBytes: Int = MAX_PEER_SYNC_ITEM_BYTES,
)

internal data class PeerSyncReconcileInput(
    val preloaded: PeerSyncPreloadedState,
    val isDeltaItems: Boolean = false,
    val appliedMutations: List<PendingMutationEntity> = emptyList(),
    val sentFeedIds: Set<String>? = null,
    val sentItemIds: Set<String>? = null,
    val sentTombstoneKeys: Set<Pair<String, String>>? = null,
    val overflowItemIds: Set<String> = emptySet(),
    val sentCatalogBatch: OutgoingCatalogSyncBatch = OutgoingCatalogSyncBatch(),
    val catalogStore: CatalogStore? = null,
)

internal data class CooperativePollOutcome(
    val polledFeedsCount: Int,
    val attemptedFeedsCount: Int,
    val measuredWeight: Double?,
)

internal suspend fun buildPeerSyncRequest(
    dao: RocheDao,
    config: PeerSyncRequestConfig,
    preloaded: PeerSyncPreloadedState? = null,
): PeerSyncRequestDto = buildTrackedPeerSyncRequest(dao, config, preloaded).request

internal suspend fun buildTrackedPeerSyncRequest(
    dao: RocheDao,
    config: PeerSyncRequestConfig,
    preloaded: PeerSyncPreloadedState? = null,
): BuiltPeerSyncRequest {
    val feeds = preloaded?.feeds ?: dao.getAllFeeds()
    val allItems = preloaded?.items ?: dao.getAllItems()
    val syncableFeeds = feeds.filter { isSyncablePublicUrl(it.url) }
    val unsyncedFeedIds = feeds.filterNot { isSyncablePublicUrl(it.url) }.map { it.id }.toSet()
    val feedUrlsById = feeds.associate { it.id to it.url }
    val tags = dao.getAllTags()
    val mutationsById =
        (preloaded?.mutations ?: dao.getAllPendingMutations()).associateBy { it.entityId }
    val nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    val hasSyncableData =
        syncableFeeds.isNotEmpty() ||
            allItems.any { isSyncablePeerItem(it, unsyncedFeedIds, feedUrlsById) } ||
            hasLocalCatalogSyncData(dao)
    val sinceInstant = resolveDeltaSinceInstant(config.updatedAfter, hasSyncableData)
    val inclusiveSince = sinceInstant?.minusSeconds(1)
    val split =
        selectPeerSyncItems(
            allItems,
            mutationsById,
            unsyncedFeedIds,
            feedUrlsById,
            inclusiveSince,
            config,
        )
    val sentFeedIds = syncableFeeds.map { it.id }.toSet()
    val sentItemIds = split.sentItems.map { it.id }.toSet()
    val catalogBatch = buildOutgoingCatalogSyncBatch(dao, inclusiveSince, sentFeedIds, sentItemIds)
    val itemPayloads =
        mapPeerSyncItemPayloads(
            split.sentItems,
            mutationsById,
            buildItemTagNamesByItemId(dao, tags),
            feedUrlsById,
            nowIso,
        )
    val tombstones = collectPeerSyncTombstones(dao.getAllTombstones(), inclusiveSince)
    val requestDto =
        assemblePeerSyncRequestDto(
            config,
            sinceInstant,
            syncableFeeds,
            itemPayloads,
            tags,
            tombstones,
            catalogBatch,
        )
    return BuiltPeerSyncRequest(
        request = requestDto,
        sentFeedIds = sentFeedIds,
        sentItemIds = sentItemIds,
        overflowItemIds = split.allOverflowIds,
        byteOverflowItemIds = split.byteOverflowIds,
        sentTombstoneKeys = tombstones.map { it.entityType.lowercase() to it.naturalKey }.toSet(),
        sentCatalogBatch = catalogBatch,
    )
}

@Suppress("LongParameterList")
private fun mapPeerSyncItemPayloads(
    sentItems: List<ItemEntity>,
    mutationsById: Map<String, PendingMutationEntity>,
    itemTagsByItemId: Map<String, List<String>>,
    feedUrlsById: Map<String, String>,
    nowIso: String,
): List<PeerSyncItemPayload> = sentItems.map { item ->
    toPeerSyncItemPayload(
        item = item,
        mut = mutationsById[item.id],
        itemTagNames = itemTagsByItemId[item.id].orEmpty(),
        feedUrlsById = feedUrlsById,
        nowIso = nowIso,
    )
}

@Suppress("LongParameterList")
private fun assemblePeerSyncRequestDto(
    config: PeerSyncRequestConfig,
    sinceInstant: Instant?,
    syncableFeeds: List<FeedEntity>,
    itemPayloads: List<PeerSyncItemPayload>,
    tags: List<TagEntity>,
    tombstones: List<SyncTombstoneDto>,
    catalogBatch: OutgoingCatalogSyncBatch,
): PeerSyncRequestDto {
    val effectiveWeight =
        if (config.networkTier == NetworkTier.OFFLINE) 0.0 else config.weightOverride
    return PeerSyncRequestDto(
        peerId = config.peerId,
        deviceName = config.deviceName,
        networkTier = config.networkTier.wireValue,
        weight = effectiveWeight,
        updatedAfter = sinceInstant?.toString(),
        feeds = syncableFeeds,
        items = itemPayloads,
        tags = tags,
        tombstones = tombstones,
        collections = catalogBatch.collections,
        catalogEntries = catalogBatch.catalogEntries,
        collectionEntries = catalogBatch.collectionEntries,
        collectionGenerators = catalogBatch.collectionGenerators,
        consumptionLog = catalogBatch.consumptionLog,
        comparisons = catalogBatch.comparisons,
    )
}

@Suppress("LongParameterList")
internal fun selectPeerSyncItems(
    allItems: List<ItemEntity>,
    mutationsById: Map<String, PendingMutationEntity>,
    unsyncedFeedIds: Set<String>,
    feedUrlsById: Map<String, String>,
    inclusiveSinceInstant: Instant?,
    config: PeerSyncRequestConfig = PeerSyncRequestConfig(peerId = "", deviceName = ""),
): ItemBudgetSplit {
    val eligibleItems =
        allItems
            .filter { item ->
                item.id !in config.excludeItemIds &&
                    mutationsById[item.id]?.isDeletedItem != true &&
                    isSyncablePeerItem(item, unsyncedFeedIds, feedUrlsById) &&
                    (item.id in config.forcedItemIds ||
                        isDeltaPeerSyncItem(
                            item = item,
                            hasPendingMutation = item.id in mutationsById,
                            inclusiveSinceInstant = inclusiveSinceInstant,
                        ))
            }
            .sortedByDescending { it.interactionUpdatedAt ?: it.publishedAt ?: it.createdAt }
    val capped = eligibleItems.take(MAX_PEER_SYNC_ITEMS)
    val countOverflow = eligibleItems.drop(MAX_PEER_SYNC_ITEMS).map { it.id }.toSet()
    val (sent, byteOverflow) = splitItemsByBudget(capped, config.maxItemBytes)
    return ItemBudgetSplit(sent, byteOverflow, countOverflow)
}

internal fun splitItemsByBudget(
    cappedItems: List<ItemEntity>,
    maxBytes: Int = MAX_PEER_SYNC_ITEM_BYTES,
): Pair<List<ItemEntity>, Set<String>> {
    val sent = ArrayList<ItemEntity>()
    val overflow = LinkedHashSet<String>()
    var usedBytes = 0
    for (item in cappedItems) {
        val estBytes = estimateSyncItemBytes(item)
        val fits = sent.isEmpty() || usedBytes + estBytes <= maxBytes
        if (fits) {
            sent.add(item)
            usedBytes += estBytes
        } else {
            overflow.add(item.id)
        }
    }
    return sent to overflow
}

internal fun estimateSyncItemBytes(item: ItemEntity): Int {
    val chars =
        item.title.length +
            item.url.length +
            (item.author?.length ?: 0) +
            (item.summary?.length ?: 0) +
            (item.contentHtml?.length ?: 0) +
            (item.thumbnailUrl?.length ?: 0) +
            (item.audioUrl?.length ?: 0)
    return ITEM_JSON_BASE_BYTES + chars * UTF8_JSON_ESCAPE_FACTOR
}

internal fun isSyncablePeerItem(
    item: ItemEntity,
    unsyncedFeedIds: Set<String>,
    feedUrlsById: Map<String, String>,
): Boolean {
    val rawFeedUrl = item.feedUrl ?: item.feedId?.let { feedUrlsById[it] }
    val belongsToUnsyncedFeed =
        item.feedId != null && item.feedId in unsyncedFeedIds ||
            rawFeedUrl != null && !isSyncablePublicUrl(rawFeedUrl)
    return !belongsToUnsyncedFeed && isSyncableItemUrl(item.url)
}

internal suspend fun buildItemTagNamesByItemId(
    dao: RocheDao,
    tags: List<TagEntity>,
): Map<String, List<String>> {
    val tagsById = tags.associateBy { it.id }
    return dao.getAllItemTags()
        .groupBy(keySelector = { it.itemId }, valueTransform = { tagsById[it.tagId]?.name })
        .mapValues { (_, names) -> names.filterNotNull().sorted() }
}

internal fun resolveDeltaSinceInstant(updatedAfter: String?, hasLocalData: Boolean): Instant? =
    updatedAfter
        ?.trim()
        ?.takeIf { it.isNotBlank() && hasLocalData }
        ?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }

internal fun collectPeerSyncTombstones(
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

internal fun isDeltaPeerSyncItem(
    item: ItemEntity,
    hasPendingMutation: Boolean,
    inclusiveSinceInstant: Instant?,
): Boolean {
    if (inclusiveSinceInstant == null) return true
    return hasPendingMutation ||
        isIsoTimestampAtOrAfter(item.interactionUpdatedAt, inclusiveSinceInstant) ||
        isIsoTimestampAtOrAfter(item.createdAt, inclusiveSinceInstant)
}

internal fun isIsoTimestampAtOrAfter(rawIso: String?, threshold: Instant): Boolean {
    if (rawIso.isNullOrBlank()) return false
    val parsed = runCatching { Instant.parse(rawIso.trim()) }.getOrNull() ?: return false
    return !parsed.isBefore(threshold)
}

internal fun toPeerSyncItemPayload(
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
        audioUrl = item.audioUrl,
        duration = parseDurationToSecondsLong(item.duration),
    )
}

internal fun parseDurationToSecondsLong(raw: String?): Long? {
    val clean = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val seconds =
        clean.toLongOrNull()
            ?: run {
                val parts = clean.split(':').map { it.trim().toLongOrNull() ?: INVALID_DURATION }
                when {
                    parts.any { it < 0L } -> INVALID_DURATION
                    parts.size == MM_SS_PART_COUNT -> parts[0] * SECONDS_PER_MINUTE + parts[1]
                    parts.size == HH_MM_SS_PART_COUNT ->
                        parts[0] * SECONDS_PER_HOUR + parts[1] * SECONDS_PER_MINUTE + parts[2]
                    else -> INVALID_DURATION
                }
            }
    return seconds.takeIf { it > 0L }
}

private const val INVALID_DURATION = -1L
private const val MM_SS_PART_COUNT = 2
private const val HH_MM_SS_PART_COUNT = 3
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val MAX_PEER_SYNC_ITEMS = 2_000
internal const val MAX_PEER_SYNC_ITEM_BYTES = 4 * 1024 * 1024
private const val ITEM_JSON_BASE_BYTES = 512
private const val UTF8_JSON_ESCAPE_FACTOR = 2
private const val MAX_OVERFLOW_PUSH_ROUNDS = 25
private const val HTTP_PAYLOAD_TOO_LARGE = 413
internal const val MAX_DELTA_CONTINUATION_PAGES = 20
private const val COOPERATIVE_POLL_SLICE_SIZE = 4
private const val NANOS_PER_SECOND = 1_000_000_000.0

internal suspend fun reconcilePeerSyncResponse(
    dao: RocheDao,
    response: PeerSyncResponseDto,
    input: PeerSyncReconcileInput,
) {
    PeerResponseReconciler(dao, response, input).reconcile()
}

internal class PeerResponseReconciler(
    private val dao: RocheDao,
    private val response: PeerSyncResponseDto,
    private val input: PeerSyncReconcileInput,
) {
    internal suspend fun reconcile() {
        syncTombstones()
        val preFetchFeedsById = input.preloaded.feeds.associateBy { it.id }
        val preFetchItemsById = input.preloaded.items.associateBy { it.id }
        clearSyncedMutations(preFetchFeedsById, preFetchItemsById)
        reconcileRemoteSnapshot(
            dao = dao,
            snapshot =
                RemoteSnapshotData(
                    feeds = response.feeds,
                    items = response.items,
                    tags = response.tags,
                ),
            options =
                ReconcileOptions(
                    appliedMutations = input.appliedMutations,
                    preFetchItemsById = preFetchItemsById,
                    preFetchFeedsById = preFetchFeedsById,
                    isDeltaItems = input.isDeltaItems,
                    overflowItemIds = input.overflowItemIds,
                ),
        )
        val cleanSyncTimestamp = response.syncTimestamp.trim()
        if (input.overflowItemIds.isNotEmpty() && cleanSyncTimestamp.isNotBlank()) {
            input.overflowItemIds.chunked(SQLITE_DELETE_CHUNK_SIZE).forEach { chunk ->
                dao.updateItemsInteractionAt(chunk, cleanSyncTimestamp)
            }
        }
        val store = input.catalogStore
        if (store != null) {
            store.withMutationLock {
                reconcileIncomingCatalogSync(
                    dao = dao,
                    catalogStore = store,
                    response = response,
                    sentBatch = input.sentCatalogBatch,
                )
            }
        } else {
            reconcileIncomingCatalogSync(
                dao = dao,
                catalogStore = null,
                response = response,
                sentBatch = input.sentCatalogBatch,
            )
        }
    }

    private suspend fun syncTombstones() {
        val remoteTombstoneKeys =
            response.tombstones.map { it.entityType.lowercase() to it.naturalKey }.toSet()
        val liveFeedUrlsById = response.feeds.associate { it.id to it.url }
        val liveFeedTsByKey = response.feeds.associate { feedNaturalKey(it.url) to it.lastPolled }
        val liveItemTsByKey =
            response.items.associate { item ->
                val fUrl = item.feedUrl ?: item.feedId?.let { liveFeedUrlsById[it] }
                itemNaturalKey(fUrl, item.url) to (item.interactionUpdatedAt ?: item.createdAt)
            }
        val liveTagKeys = response.tags.map { tagNaturalKey(it.name) }.toSet()

        for (localTomb in dao.getAllTombstones()) {
            val isResurrected =
                isTombstoneResurrectedOnPeer(
                    localTomb = localTomb,
                    remoteTombstoneKeys = remoteTombstoneKeys,
                    liveFeedTsByKey = liveFeedTsByKey,
                    liveItemTsByKey = liveItemTsByKey,
                    liveTagKeys = liveTagKeys,
                )
            if (isResurrected) {
                dao.deleteTombstone(localTomb.entityType, localTomb.naturalKey)
            }
        }

        if (response.tombstones.isNotEmpty()) {
            val existingByKey =
                dao.getAllTombstones().associateBy { it.entityType.lowercase() to it.naturalKey }
            dao.upsertTombstones(
                response.tombstones.map { incoming ->
                    val current =
                        existingByKey[incoming.entityType.lowercase() to incoming.naturalKey]
                    TombstoneEntity(
                        entityType = incoming.entityType,
                        naturalKey = incoming.naturalKey,
                        deletedAt = selectNewerTombstoneTs(incoming.deletedAt, current?.deletedAt),
                    )
                }
            )
        }
    }

    private fun isTombstoneResurrectedOnPeer(
        localTomb: TombstoneEntity,
        remoteTombstoneKeys: Set<Pair<String, String>>,
        liveFeedTsByKey: Map<String, String?>,
        liveItemTsByKey: Map<String, String?>,
        liveTagKeys: Set<String>,
    ): Boolean {
        val typeLower = localTomb.entityType.lowercase()
        val keyPair = typeLower to localTomb.naturalKey
        if (keyPair in remoteTombstoneKeys) return false
        val wasSent = input.sentTombstoneKeys == null || keyPair in input.sentTombstoneKeys
        return when (typeLower) {
            "feed" -> isFeedTombstoneResurrected(localTomb, liveFeedTsByKey, wasSent)
            "item" ->
                localTomb.naturalKey in liveItemTsByKey &&
                    isPeerTsStrictlyAfter(
                        liveItemTsByKey[localTomb.naturalKey],
                        localTomb.deletedAt,
                    )
            "tag" -> wasSent && localTomb.naturalKey in liveTagKeys
            else -> false
        }
    }

    private fun isFeedTombstoneResurrected(
        localTomb: TombstoneEntity,
        liveFeedTsByKey: Map<String, String?>,
        wasSent: Boolean,
    ): Boolean {
        if (localTomb.naturalKey !in liveFeedTsByKey) return false
        val feedTs = liveFeedTsByKey[localTomb.naturalKey]
        return if (feedTs != null) isPeerTsStrictlyAfter(feedTs, localTomb.deletedAt) else wasSent
    }

    private suspend fun clearSyncedMutations(
        preFetchFeedsById: Map<String, FeedEntity>,
        preFetchItemsById: Map<String, ItemEntity>,
    ) {
        val preservedEntityIds =
            collectUnsyncedEntityIds(preFetchFeedsById, preFetchItemsById) + input.overflowItemIds
        val currentMutationsById = dao.getAllPendingMutations().associateBy { it.entityId }
        for (preMut in input.preloaded.mutations) {
            val shouldClear =
                shouldClearMutation(
                    preMut = preMut,
                    currentMut = currentMutationsById[preMut.entityId],
                    preservedEntityIds = preservedEntityIds,
                    preFetchItemsById = preFetchItemsById,
                )
            if (shouldClear) {
                dao.deletePendingMutation(preMut.entityId)
            }
        }
    }

    private fun collectUnsyncedEntityIds(
        preFetchFeedsById: Map<String, FeedEntity>,
        preFetchItemsById: Map<String, ItemEntity>,
    ): Set<String> {
        val feedUrlsById = preFetchFeedsById.mapValues { it.value.url }
        val unsyncedFeedIds =
            preFetchFeedsById.values.filterNot { isSyncablePublicUrl(it.url) }.map { it.id }.toSet()
        val unsyncedItemIds =
            preFetchItemsById.values
                .filterNot { isSyncablePeerItem(it, unsyncedFeedIds, feedUrlsById) }
                .map { it.id }
                .toSet()
        return unsyncedFeedIds + unsyncedItemIds
    }

    private fun shouldClearMutation(
        preMut: PendingMutationEntity,
        currentMut: PendingMutationEntity?,
        preservedEntityIds: Set<String>,
        preFetchItemsById: Map<String, ItemEntity>,
    ): Boolean {
        if (
            preMut.isRemoteFeedMutation ||
                currentMut != preMut ||
                preMut.entityId in preservedEntityIds
        ) {
            return false
        }
        return when {
            preMut.isDeletedItem -> true
            preMut.kind == PendingEntityKind.LOCAL_FEED ->
                input.sentFeedIds == null || preMut.entityId in input.sentFeedIds
            else ->
                input.sentItemIds == null ||
                    preMut.entityId in input.sentItemIds ||
                    preMut.entityId !in preFetchItemsById
        }
    }
}

internal data class SinglePeerSyncResult(
    val response: PeerSyncResponseDto,
    val cursorUsed: String?,
    val sentItemIds: Set<String> = emptySet(),
    val byteOverflowItemIds: Set<String> = emptySet(),
)

internal class PeerSyncCoordinator(
    private val dao: RocheDao,
    private val apiClient: RocheApiClient,
    private val networkTier: NetworkTier = NetworkTier.WIFI,
    private val catalogStore: CatalogStore? = null,
    private val onPeersUpdated: (List<PeerNodeDto>) -> Unit = {},
    private val onSyncCursorUpdated: suspend (String) -> Unit = {},
) {
    internal suspend fun syncAndCooperativePoll(
        currentSettings: Settings,
        initialWeightOverride: Double? = null,
        requestCursorOverride: String? = null,
    ): Int? {
        val needsCatalogBackfill = dao.getLocalSyncFlag(FLAG_CATALOG_BACKFILL_NEEDED) != null
        val initialRound =
            executeSinglePeerSyncRound(
                currentSettings = currentSettings,
                weightOverride = initialWeightOverride,
                ignoreSyncCursor = needsCatalogBackfill,
                requestCursorOverride = requestCursorOverride,
            ) ?: return null
        var latestPeers = initialRound.response.activePeers
        onPeersUpdated(latestPeers)
        var updatedSettings =
            applyCursorAndContinueDelta(
                baseSettings = currentSettings,
                round = initialRound,
                weightOverride = initialWeightOverride,
                onPagePeers = { peers ->
                    latestPeers = peers
                    onPeersUpdated(peers)
                },
            )
        updatedSettings =
            drainOverflowItemPushes(
                initialSettings = updatedSettings,
                initialSentIds = initialRound.sentItemIds,
                initialOverflowIds = initialRound.byteOverflowItemIds,
                weightOverride = initialWeightOverride,
            ) { peers ->
                latestPeers = peers
                onPeersUpdated(peers)
            }
        if (needsCatalogBackfill) {
            dao.deleteLocalSyncFlag(FLAG_CATALOG_BACKFILL_NEEDED)
        }
        val pollOutcome =
            if (networkTier != NetworkTier.OFFLINE) {
                pollCooperativeFeedsOnDevice(
                    dao = dao,
                    apiClient = apiClient,
                    localPeerId = updatedSettings.peerId,
                    activePeers = latestPeers,
                )
            } else {
                null
            }
        if (pollOutcome != null && pollOutcome.attemptedFeedsCount > 0) {
            runPostPollPeerSync(updatedSettings, pollOutcome.measuredWeight)
        }
        return dao.countItems()
    }

    private suspend fun runPostPollPeerSync(
        currentSettings: Settings,
        measuredWeight: Double?,
    ) {
        val followUpRound =
            executeSinglePeerSyncRound(
                currentSettings = currentSettings,
                weightOverride = measuredWeight,
            ) ?: return
        onPeersUpdated(followUpRound.response.activePeers)
        applyCursorAndContinueDelta(
            baseSettings = currentSettings,
            round = followUpRound,
            weightOverride = measuredWeight,
        )
    }

    private suspend fun applyCursorAndContinueDelta(
        baseSettings: Settings,
        round: SinglePeerSyncResult,
        weightOverride: Double?,
        onPagePeers: (List<PeerNodeDto>) -> Unit = onPeersUpdated,
    ): Settings {
        val response = round.response
        var updatedSettings =
            advancePersistedSyncCursor(
                settings = baseSettings,
                candidateCursor = response.syncTimestamp,
                isIntermediatePage = response.hasMore,
            )
        var pageCursor = response.syncTimestamp.trim()
        var afterItemId = response.nextAfterItemId?.trim()?.takeIf { it.isNotBlank() }
        val seenKeys = mutableSetOf<Pair<String, String?>>()
        if (!round.cursorUsed.isNullOrBlank()) {
            seenKeys.add(round.cursorUsed.trim() to null)
        }
        var hasMore =
            response.hasMore && pageCursor.isNotBlank() && seenKeys.add(pageCursor to afterItemId)
        var page = 0
        while (hasMore && page < MAX_DELTA_CONTINUATION_PAGES) {
            val pageResp =
                executePullOnlyDeltaPage(
                    currentSettings = updatedSettings,
                    cursor = pageCursor,
                    afterItemId = afterItemId,
                    weightOverride = weightOverride,
                )
            val nextCursor = pageResp?.syncTimestamp?.trim().orEmpty()
            if (pageResp != null && nextCursor.isNotBlank()) {
                onPagePeers(pageResp.activePeers)
                updatedSettings =
                    advancePersistedSyncCursor(
                        settings = updatedSettings,
                        candidateCursor = nextCursor,
                        isIntermediatePage = pageResp.hasMore,
                    )
                pageCursor = nextCursor
                afterItemId = pageResp.nextAfterItemId?.trim()?.takeIf { it.isNotBlank() }
                hasMore = pageResp.hasMore && seenKeys.add(pageCursor to afterItemId)
            } else {
                hasMore = false
            }
            page++
        }
        return updatedSettings
    }

    private suspend fun advancePersistedSyncCursor(
        settings: Settings,
        candidateCursor: String,
        isIntermediatePage: Boolean,
    ): Settings {
        val cleanCandidate = candidateCursor.trim()
        val shouldPersist =
            !isIntermediatePage &&
                cleanCandidate.isNotBlank() &&
                shouldAdvanceSyncCursor(settings.lastPeerSyncCursor, cleanCandidate)
        return if (shouldPersist) {
            onSyncCursorUpdated(cleanCandidate)
            settings.copy(lastPeerSyncCursor = cleanCandidate)
        } else {
            settings
        }
    }

    private fun shouldAdvanceSyncCursor(
        previousCursor: String?,
        candidateCursor: String,
    ): Boolean {
        if (candidateCursor.isBlank()) return false
        if (previousCursor.isNullOrBlank()) return true
        val prevInstant =
            runCatching { Instant.parse(previousCursor.trim()) }.getOrNull() ?: return true
        val candInstant = runCatching { Instant.parse(candidateCursor.trim()) }.getOrNull()
        return candInstant != null && !candInstant.isBefore(prevInstant)
    }

    private suspend fun executePullOnlyDeltaPage(
        currentSettings: Settings,
        cursor: String,
        afterItemId: String?,
        weightOverride: Double?,
    ): PeerSyncResponseDto? {
        val preloaded =
            PeerSyncPreloadedState(
                feeds = dao.getAllFeeds(),
                items = dao.getAllItems(),
            )
        val effectiveWeight = if (networkTier == NetworkTier.OFFLINE) 0.0 else weightOverride
        val request =
            PeerSyncRequestDto(
                peerId = currentSettings.peerId,
                deviceName = currentSettings.deviceName,
                networkTier = networkTier.wireValue,
                weight = effectiveWeight,
                updatedAfter = cursor,
                afterItemId = afterItemId,
                feeds = emptyList(),
                items = emptyList(),
                tags = emptyList(),
                tombstones = emptyList(),
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
            input =
                PeerSyncReconcileInput(
                    preloaded = preloaded,
                    isDeltaItems = true,
                    sentFeedIds = emptySet(),
                    sentItemIds = emptySet(),
                    sentTombstoneKeys = emptySet(),
                    catalogStore = catalogStore,
                ),
        )
        return response
    }

    private suspend fun drainOverflowItemPushes(
        initialSettings: Settings,
        initialSentIds: Set<String>,
        initialOverflowIds: Set<String>,
        weightOverride: Double?,
        onPagePeers: (List<PeerNodeDto>) -> Unit,
    ): Settings {
        var settings = initialSettings
        var remainingIds = initialOverflowIds
        val alreadySentIds = initialSentIds.toMutableSet()
        var roundIdx = 0
        var keepDraining = remainingIds.isNotEmpty()
        while (keepDraining && roundIdx < MAX_OVERFLOW_PUSH_ROUNDS) {
            val prevCount = remainingIds.size
            val nextRound =
                executeSinglePeerSyncRound(
                    currentSettings = settings,
                    weightOverride = weightOverride,
                    forcedItemIds = remainingIds,
                    excludeItemIds = alreadySentIds,
                )
            if (nextRound == null) {
                keepDraining = false
            } else {
                alreadySentIds.addAll(nextRound.sentItemIds)
                onPagePeers(nextRound.response.activePeers)
                settings =
                    applyCursorAndContinueDelta(
                        baseSettings = settings,
                        round = nextRound,
                        weightOverride = weightOverride,
                        onPagePeers = onPagePeers,
                    )
                remainingIds = nextRound.byteOverflowItemIds - alreadySentIds
                keepDraining = remainingIds.isNotEmpty() && remainingIds.size < prevCount
                roundIdx++
            }
        }
        return settings
    }

    private suspend fun executeSinglePeerSyncRound(
        currentSettings: Settings,
        weightOverride: Double? = null,
        ignoreSyncCursor: Boolean = false,
        requestCursorOverride: String? = null,
        forcedItemIds: Set<String> = emptySet(),
        excludeItemIds: Set<String> = emptySet(),
    ): SinglePeerSyncResult? {
        val appliedFeedMutations = pushRemoteFeedMutations(currentSettings)
        val preloaded =
            PeerSyncPreloadedState(
                feeds = dao.getAllFeeds(),
                items = dao.getAllItems(),
                mutations = dao.getAllPendingMutations(),
            )
        val effectiveCursor =
            requestCursorOverride
                ?: currentSettings.lastPeerSyncCursor.takeUnless { ignoreSyncCursor }
        val tracked =
            buildTrackedPeerSyncRequest(
                dao = dao,
                config =
                    PeerSyncRequestConfig(
                        peerId = currentSettings.peerId,
                        deviceName = currentSettings.deviceName,
                        networkTier = networkTier,
                        updatedAfter = effectiveCursor,
                        weightOverride = weightOverride,
                        forcedItemIds = forcedItemIds,
                        excludeItemIds = excludeItemIds,
                    ),
                preloaded = preloaded,
            )
        val (response, droppedItemsOn413) =
            callSyncPeerRound(currentSettings, tracked.request) ?: return null
        val (sentIds, overflowIds, byteOverflowIds) =
            resolveRoundItemSets(tracked, droppedItemsOn413)
        reconcilePeerSyncResponse(
            dao = dao,
            response = response,
            input =
                PeerSyncReconcileInput(
                    preloaded = preloaded,
                    isDeltaItems = tracked.request.updatedAfter != null,
                    appliedMutations = appliedFeedMutations,
                    sentFeedIds = tracked.sentFeedIds,
                    sentItemIds = sentIds,
                    sentTombstoneKeys = tracked.sentTombstoneKeys,
                    overflowItemIds = overflowIds,
                    sentCatalogBatch = tracked.sentCatalogBatch,
                    catalogStore = catalogStore,
                ),
        )
        return SinglePeerSyncResult(
            response = response,
            cursorUsed = tracked.request.updatedAfter,
            sentItemIds = sentIds,
            byteOverflowItemIds = byteOverflowIds,
        )
    }

    private fun resolveRoundItemSets(
        tracked: BuiltPeerSyncRequest,
        droppedItemsOn413: Boolean,
    ): Triple<Set<String>, Set<String>, Set<String>> =
        if (droppedItemsOn413) {
            Triple(
                emptySet(),
                tracked.sentItemIds + tracked.overflowItemIds,
                tracked.sentItemIds + tracked.byteOverflowItemIds,
            )
        } else {
            Triple(tracked.sentItemIds, tracked.overflowItemIds, tracked.byteOverflowItemIds)
        }

    private suspend fun callSyncPeerRound(
        currentSettings: Settings,
        request: PeerSyncRequestDto,
    ): Pair<PeerSyncResponseDto, Boolean>? {
        val primary = runSuspendCatching {
            apiClient.syncPeer(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                request = request,
            )
        }
        val error = primary.exceptionOrNull()
        return when {
            error == null -> primary.getOrNull()?.let { it to false }
            (error as? ProblemDetailsException)?.statusCode == HTTP_PAYLOAD_TOO_LARGE &&
                request.items.isNotEmpty() ->
                apiClient
                    .syncPeer(
                        serverUrl = currentSettings.serverUrl,
                        token = currentSettings.bearerToken,
                        request = request.copy(items = emptyList()),
                    )
                    ?.let { it to true }
            request.updatedAfter == null && isOversizedResponseError(error) -> null
            else -> throw error
        }
    }

    private suspend fun pushRemoteFeedMutations(
        currentSettings: Settings
    ): List<PendingMutationEntity> {
        val applied = mutableListOf<PendingMutationEntity>()
        for (mutation in dao.getAllPendingMutations().filter { it.isRemoteFeedMutation }) {
            val pushed =
                pushPendingFeedMutation(
                    dao = dao,
                    apiClient = apiClient,
                    serverUrl = currentSettings.serverUrl,
                    token = currentSettings.bearerToken,
                    mutation = mutation,
                )
            if (pushed) {
                applied += mutation
            }
        }
        return applied
    }
}

internal suspend fun pollCooperativeFeedsOnDevice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    localPeerId: String,
    activePeers: List<PeerNodeDto>,
    nowInstant: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
): CooperativePollOutcome {
    val attemptedIds = mutableSetOf<String>()
    var currentPeers = activePeers
    var polledFeedsCount = 0
    var totalItemsProcessed = 0
    var measuredWeight: Double? = null
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
            val succeededCounts =
                pollCooperativeSlice(dao, apiClient, slice, localPeerId, nowInstant)
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
                measuredWeight = updatedWeight
                currentPeers = currentPeers.map { peer ->
                    if (peer.peerId == localPeerId) peer.copy(weight = updatedWeight) else peer
                }
            }
        }
    }
    return CooperativePollOutcome(
        polledFeedsCount = polledFeedsCount,
        attemptedFeedsCount = attemptedIds.size,
        measuredWeight = measuredWeight,
    )
}

internal suspend fun pollCooperativeSlice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    slice: List<FeedEntity>,
    localPeerId: String,
    nowInstant: Instant,
): List<Int> = coroutineScope {
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
        .mapNotNull { it.getOrNull() }
}

internal fun isOversizedResponseError(error: Throwable): Boolean =
    error is IOException &&
        error.message?.contains("exceeds maximum allowed size", ignoreCase = true) == true

internal fun isPeerTsStrictlyAfter(peerIso: String?, tombDeletedAtIso: String): Boolean {
    val peerInst = peerIso?.trim()?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val tombInst = runCatching { Instant.parse(tombDeletedAtIso.trim()) }.getOrNull()
    return peerInst != null && tombInst != null && peerInst.isAfter(tombInst)
}

internal fun selectNewerTombstoneTs(incomingIso: String, localIso: String?): String {
    if (localIso.isNullOrBlank()) return incomingIso
    val incInst = runCatching { Instant.parse(incomingIso.trim()) }.getOrNull() ?: return localIso
    val locInst = runCatching { Instant.parse(localIso.trim()) }.getOrNull() ?: return incomingIso
    return if (locInst.isAfter(incInst)) localIso else incomingIso
}
