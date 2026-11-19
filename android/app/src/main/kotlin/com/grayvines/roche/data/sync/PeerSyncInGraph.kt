@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.sync

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_ENTRY
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_GENERATOR
import com.grayvines.roche.core.TOMBSTONE_ENTITY_CONSUMPTION_LOG
import com.grayvines.roche.core.TOMBSTONE_ENTITY_ITEM
import com.grayvines.roche.core.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.canonicalOrderingJson
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.collectionEntryNaturalKey
import com.grayvines.roche.core.deterministicAutoLogId
import com.grayvines.roche.core.deterministicComparisonId
import com.grayvines.roche.core.deterministicConsumptionLogId
import com.grayvines.roche.core.itemNaturalKey
import com.grayvines.roche.core.parseIsoInstant
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.CollectionRole
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.SyncCollectionEntryDto
import com.grayvines.roche.data.catalog.SyncCollectionGeneratorDto
import com.grayvines.roche.data.catalog.SyncComparisonDto
import com.grayvines.roche.data.catalog.SyncConsumptionLogDto
import com.grayvines.roche.data.catalog.decodeComparisonOrdering
import com.grayvines.roche.data.catalog.isGeneratorEligible
import com.grayvines.roche.data.catalog.wouldIntroduceCycleInGraph
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.FeedSourceEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.deleteItemPreservingHistory
import com.grayvines.roche.data.local.entryHasCatalogHistory
import com.grayvines.roche.data.local.nextLocalHlc
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.ranking.EpRankingEngine

private class GraphEdgesBatchCache(private val dao: RocheDao) {
    private var tombstonesByType: MutableMap<String, MutableMap<String, TombstoneEntity>>? = null
    private var feedSources: List<FeedSourceEntity>? = null
    private var specsByIdCache: Map<String, CollectionSpecEntity>? = null
    private var membershipsByKeyCache: MutableMap<Pair<String, String>, CollectionEntryEntity>? =
        null

    suspend fun tombstonesFor(entityType: String): MutableMap<String, TombstoneEntity> {
        val grouped =
            tombstonesByType
                ?: HashMap<String, MutableMap<String, TombstoneEntity>>().also { map ->
                    for (tomb in dao.getAllTombstones()) {
                        map.getOrPut(tomb.entityType.lowercase()) { HashMap() }[tomb.naturalKey] =
                            tomb
                    }
                    tombstonesByType = map
                }
        return grouped.getOrPut(entityType.lowercase()) { HashMap() }
    }

    fun removeTombstone(entityType: String, naturalKey: String) {
        tombstonesByType?.get(entityType.lowercase())?.remove(naturalKey)
    }

    suspend fun feedSources(): List<FeedSourceEntity> =
        feedSources ?: dao.getAllFeedSources().also { feedSources = it }

    suspend fun specsById(): Map<String, CollectionSpecEntity> =
        specsByIdCache
            ?: dao.getAllCollectionSpecs()
                .associateBy { it.collectionId }
                .also {
                    specsByIdCache = it
                }

    suspend fun membershipsByKey(): MutableMap<Pair<String, String>, CollectionEntryEntity> =
        membershipsByKeyCache
            ?: dao.getAllCollectionEntries()
                .associateByTo(HashMap()) { it.collectionId to it.entryId }
                .also { membershipsByKeyCache = it }

    fun putMemberships(rows: List<CollectionEntryEntity>) {
        val map = membershipsByKeyCache ?: return
        for (row in rows) {
            map[row.collectionId to row.entryId] = row
        }
    }

    fun removeMembership(collectionId: String, entryId: String) {
        membershipsByKeyCache?.remove(collectionId to entryId)
    }

    suspend fun refreshMembershipsForEntry(entryId: String) {
        val map = membershipsByKeyCache ?: return
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().key.second == entryId) {
                iter.remove()
            }
        }
        for (row in dao.getCollectionEntriesForEntry(entryId)) {
            map[row.collectionId to row.entryId] = row
        }
    }
}

@Suppress("LongParameterList")
internal suspend fun mergeIncomingGraphEdgesInTx(
    dao: RocheDao,
    catalogStore: CatalogStore?,
    response: PeerSyncResponseDto,
    redirects: Map<String, String>,
    suppressedEntryIds: Set<String> = emptySet(),
    serverNode: String,
    localPeerId: String = DEFAULT_LOCAL_HLC_NODE,
    nowIso: String,
    nowMs: Long = System.currentTimeMillis(),
) {
    val cache = GraphEdgesBatchCache(dao)
    mergeCollectionEntriesInDao(
        dao = dao,
        cache = cache,
        incoming = response.collectionEntries,
        redirects = redirects,
        suppressedEntryIds = suppressedEntryIds,
        serverNode = serverNode,
        nowIso = nowIso,
        nowMs = nowMs,
    )
    enforceLinkedItemTombstones(dao, cache)
    mergeConsumptionLogsInDao(
        dao = dao,
        cache = cache,
        incoming = response.consumptionLog,
        redirects = redirects,
        serverNode = serverNode,
        nowIso = nowIso,
        nowMs = nowMs,
    )
    mergeIncomingComparisonsInDao(
        dao = dao,
        incoming = response.comparisons,
        redirects = redirects,
        serverNode = serverNode,
        nowIso = nowIso,
        nowMs = nowMs,
    )
    mergeIncomingGeneratorsInDao(
        dao = dao,
        cache = cache,
        catalogStore = catalogStore,
        incoming = response.collectionGenerators,
        redirects = redirects,
        serverNode = serverNode,
        localPeerId = localPeerId,
        nowIso = nowIso,
        nowMs = nowMs,
        allowLocalAutoAdvance = !response.hasMore,
    )
}

private suspend fun enforceLinkedItemTombstones(dao: RocheDao, cache: GraphEdgesBatchCache) {
    val itemTombstones = cache.tombstonesFor(TOMBSTONE_ENTITY_ITEM)
    if (itemTombstones.isEmpty()) return
    val tombstonedItemUrls = extractTombstonedItemUrls(itemTombstones.keys)
    val feedUrlsByColId =
        cache.feedSources().associate { it.collectionId to canonicalizeSyncUrl(it.feedUrl) }
    val membershipsByEntry =
        cache.membershipsByKey().values.groupBy({ it.entryId }, { it.collectionId })
    val statesById = dao.getAllEntryStates().associateBy { it.entryId }

    for (entry in dao.getAllEntries()) {
        val rawUrl = entry.url?.takeIf { it.isNotBlank() }
        if (rawUrl == null || canonicalizeSyncUrl(rawUrl) !in tombstonedItemUrls) continue
        reconcileLinkedInboxTombstone(
            dao = dao,
            cache = cache,
            entry = entry,
            state = statesById[entry.id],
            entryColIds = membershipsByEntry[entry.id].orEmpty(),
            feedUrlsByColId = feedUrlsByColId,
            itemTombstones = itemTombstones,
        )
    }
}

private fun extractTombstonedItemUrls(keys: Set<String>): Set<String> = buildSet {
    for (natKey in keys) {
        val sep = natKey.indexOf('\u0000')
        if (sep >= 0 && sep + 1 < natKey.length) {
            add(canonicalizeSyncUrl(natKey.substring(sep + 1)))
        }
    }
}

@Suppress("LongParameterList")
private suspend fun reconcileLinkedInboxTombstone(
    dao: RocheDao,
    cache: GraphEdgesBatchCache,
    entry: EntryEntity,
    state: EntryStateEntity?,
    entryColIds: List<String>,
    feedUrlsByColId: Map<String, String>,
    itemTombstones: Map<String, TombstoneEntity>,
) {
    if (state == null) return
    val itemUrl = entry.url?.takeIf { it.isNotBlank() } ?: return
    val feedUrls = entryColIds.mapNotNull { feedUrlsByColId[it] }
    val isManualActive =
        feedUrls.isEmpty() && isManualInboxItemActive(dao, entry.id, state, entryColIds)
    val isFeedActive =
        feedUrls.isNotEmpty() && (state.inboxState == "unread" || state.inboxState == "dismissed")
    if (!isManualActive && !isFeedActive) return
    val keys =
        if (feedUrls.isEmpty()) {
            listOf(itemNaturalKey(null, itemUrl))
        } else {
            feedUrls.map { itemNaturalKey(it, itemUrl) }
        }
    val activeTomb =
        keys.mapNotNull { itemTombstones[it] }.maxWithOrNull { a, b -> compareHlc(a.hlc, b.hlc) }
            ?: return
    if (compareHlc(state.hlc, activeTomb.hlc) > 0) {
        dao.deleteTombstone(TOMBSTONE_ENTITY_ITEM, activeTomb.naturalKey)
        cache.removeTombstone(TOMBSTONE_ENTITY_ITEM, activeTomb.naturalKey)
    } else {
        dao.deleteItemPreservingHistory(
            itemId = entry.id,
            recordTombstones = false,
            hlcOverride = activeTomb.hlc,
        )
        cache.refreshMembershipsForEntry(entry.id)
    }
}

private suspend fun isManualInboxItemActive(
    dao: RocheDao,
    entryId: String,
    state: EntryStateEntity,
    entryColIds: List<String>,
): Boolean =
    when (state.inboxState) {
        "unread",
        "dismissed" -> true
        "none" ->
            WANT_TO_READ_COLLECTION_ID in entryColIds ||
                dao.getEntryContentById(entryId) != null ||
                !dao.entryHasCatalogHistory(entryId)
        else -> false
    }

@Suppress("LongParameterList")
private suspend fun mergeCollectionEntriesInDao(
    dao: RocheDao,
    cache: GraphEdgesBatchCache,
    incoming: List<SyncCollectionEntryDto>,
    redirects: Map<String, String>,
    suppressedEntryIds: Set<String>,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
) {
    if (incoming.isEmpty()) return
    val existingMemberships = cache.membershipsByKey()
    val feedIds = cache.feedSources().mapTo(HashSet()) { it.collectionId }
    val childrenByParent = HashMap<String, MutableSet<String>>()
    for ((colId, entryId) in existingMemberships.keys) {
        childrenByParent.getOrPut(colId) { HashSet() }.add(entryId)
    }
    val tombstones = cache.tombstonesFor(TOMBSTONE_ENTITY_COLLECTION_ENTRY)
    val ctx =
        IncomingMembershipCtx(
            redirects = redirects,
            suppressedEntryIds = suppressedEntryIds,
            existingMemberships = existingMemberships,
            tombstones = tombstones,
            childrenByParent = childrenByParent,
            nowMs = nowMs,
        )
    val upserts = ArrayList<CollectionEntryEntity>()
    val linkedFeedIds = HashSet<String>()

    for (ce in incoming) {
        val resolved = resolveIncomingMembershipDto(ce, ctx, serverNode, nowIso) ?: continue
        resolved.second?.let { natKey ->
            dao.deleteTombstone(TOMBSTONE_ENTITY_COLLECTION_ENTRY, natKey)
            cache.removeTombstone(TOMBSTONE_ENTITY_COLLECTION_ENTRY, natKey)
        }
        val row = resolved.first
        childrenByParent.getOrPut(row.collectionId) { HashSet() }.add(row.entryId)
        upserts.add(row)
        if (row.collectionId in feedIds) {
            linkedFeedIds.add(row.collectionId)
        }
    }
    if (upserts.isNotEmpty()) {
        dao.upsertCollectionEntries(upserts)
        cache.putMemberships(upserts)
    }
    for (fid in linkedFeedIds) {
        dao.clearFallbackFeedTitleForFeed(fid)
    }
}

@Suppress("LongParameterList")
private data class IncomingMembershipCtx(
    val redirects: Map<String, String>,
    val suppressedEntryIds: Set<String>,
    val existingMemberships: Map<Pair<String, String>, CollectionEntryEntity>,
    val tombstones: Map<String, TombstoneEntity>,
    val childrenByParent: Map<String, Set<String>>,
    val nowMs: Long,
)

private fun resolveIncomingMembershipDto(
    ce: SyncCollectionEntryDto,
    ctx: IncomingMembershipCtx,
    serverNode: String,
    nowIso: String,
): Pair<CollectionEntryEntity, String?>? {
    val rawColId = ce.collectionId.trim()
    val rawEntryId = ce.entryId.trim()
    val colId = followMergedInMap(rawColId, ctx.redirects)
    val entryId = followMergedInMap(rawEntryId, ctx.redirects)
    if (isInvalidOrSuppressedEdge(rawColId, rawEntryId, colId, entryId, ctx.suppressedEntryIds)) {
        return null
    }
    val memHlc = resolveOrSynthesizeHlc(ce.hlc, ce.addedAt, nowIso, serverNode, ctx.nowMs)
    val natKey = collectionEntryNaturalKey(colId, entryId)
    val tomb = ctx.tombstones[natKey]
    if (tomb != null && compareHlc(tomb.hlc, memHlc) >= 0) return null

    val existing = ctx.existingMemberships[colId to entryId]
    val incomingWins = shouldIncomingWinHlc(memHlc, existing?.hlc, existing?.isLocalDirty == true)
    if (!incomingWins || wouldIntroduceCycleInGraph(ctx.childrenByParent, colId, entryId)) {
        return null
    }
    val entity =
        CollectionEntryEntity(
            collectionId = colId,
            entryId = entryId,
            position = ce.position,
            addedAt = ce.addedAt.ifBlank { nowIso },
            hlc = maxHlc(memHlc, existing?.hlc),
            isLocalDirty = false,
        )
    return entity to tomb?.let { natKey }
}

private fun isInvalidOrSuppressedEdge(
    rawColId: String,
    rawEntryId: String,
    colId: String,
    entryId: String,
    suppressedEntryIds: Set<String>,
): Boolean {
    if (colId.isEmpty() || entryId.isEmpty() || colId == entryId) return true
    return listOf(rawColId, rawEntryId, colId, entryId).any { it in suppressedEntryIds }
}

private data class IncomingLogsMergePlan(
    val toInsert: List<ConsumptionLogEntity>,
    val maxConsumedHlcByEntry: Map<String, String>,
    val replacedAutoLogIds: Set<String>,
    val clearedTombstoneIds: Set<String>,
)

@Suppress("LongParameterList")
private suspend fun mergeConsumptionLogsInDao(
    dao: RocheDao,
    cache: GraphEdgesBatchCache,
    incoming: List<SyncConsumptionLogDto>,
    redirects: Map<String, String>,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
) {
    if (incoming.isEmpty()) return
    val tombstonesById = cache.tombstonesFor(TOMBSTONE_ENTITY_CONSUMPTION_LOG)
    val existingConsumedByEntry =
        dao.getAllConsumptionLogs()
            .filter { ConsumptionStatus.fromWireValue(it.status) == ConsumptionStatus.CONSUMED }
            .groupBy { it.entryId }
    val acc = IncomingLogsAcc(existingConsumedByEntry, tombstonesById, serverNode, nowIso, nowMs)
    for (log in incoming) {
        val entryId = followMergedInMap(log.entryId, redirects)
        if (entryId.isNotEmpty()) {
            acc.accept(log, entryId)
        }
    }
    val plan = acc.toPlan()
    plan.clearedTombstoneIds.forEach {
        dao.deleteTombstone(TOMBSTONE_ENTITY_CONSUMPTION_LOG, it)
        cache.removeTombstone(TOMBSTONE_ENTITY_CONSUMPTION_LOG, it)
    }
    plan.replacedAutoLogIds.forEach { dao.deleteConsumptionLogById(it) }
    if (plan.toInsert.isNotEmpty()) {
        dao.insertConsumptionLogsIgnore(plan.toInsert)
    }
    if (plan.maxConsumedHlcByEntry.isNotEmpty()) {
        prunePromotedWantMemberships(dao, cache, plan.maxConsumedHlcByEntry)
    }
}

private class IncomingLogsAcc(
    private val existingConsumedByEntry: Map<String, List<ConsumptionLogEntity>>,
    private val tombstonesById: Map<String, TombstoneEntity>,
    private val serverNode: String,
    private val nowIso: String,
    private val nowMs: Long,
) {
    private val consumedInBatch = HashSet<String>(existingConsumedByEntry.keys)
    private val toInsert = ArrayList<ConsumptionLogEntity>()
    private val maxConsumedHlcByEntry = HashMap<String, String>()
    private val replacedAutoIds = HashSet<String>()
    private val clearedTombstoneIds = HashSet<String>()

    fun accept(log: SyncConsumptionLogDto, entryId: String) {
        val status = ConsumptionStatus.fromWireValue(log.status)
        val id = resolveIncomingLogId(log, entryId, status)
        val autoLogId = deterministicAutoLogId(entryId)
        val isConsumed = status == ConsumptionStatus.CONSUMED
        val logHlc = resolveOrSynthesizeHlc(log.hlc, log.ts, nowIso, serverNode, nowMs)
        if (!checkAndClearLogTombstone(id, logHlc)) return

        if (isConsumed && id == autoLogId && entryId in consumedInBatch) {
            recordConsumedHlc(entryId, logHlc)
            return
        }
        if (isConsumed && id != autoLogId) {
            markReplacedAutoLogIfNeeded(entryId, autoLogId)
        }
        toInsert.add(
            ConsumptionLogEntity(
                id = id,
                entryId = entryId,
                ts = log.ts.ifBlank { nowIso },
                status = status.wireValue,
                notes = log.notes?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = log.createdAt ?: nowIso,
                hlc = logHlc,
                isLocalDirty = false,
            )
        )
        if (isConsumed) {
            consumedInBatch.add(entryId)
            recordConsumedHlc(entryId, logHlc)
        }
    }

    private fun resolveIncomingLogId(
        log: SyncConsumptionLogDto,
        entryId: String,
        status: ConsumptionStatus,
    ): String {
        val explicit = log.id.trim()
        if (explicit.isNotEmpty()) return explicit
        val tsMillis = parseIsoInstant(log.ts)?.toEpochMilli() ?: 0L
        return deterministicConsumptionLogId(entryId, tsMillis, status.wireValue)
    }

    private fun checkAndClearLogTombstone(id: String, logHlc: String): Boolean {
        val tomb = tombstonesById[id] ?: return true
        if (compareHlc(tomb.hlc, logHlc) >= 0) return false
        clearedTombstoneIds.add(id)
        return true
    }

    private fun markReplacedAutoLogIfNeeded(entryId: String, autoLogId: String) {
        existingConsumedByEntry[entryId]
            ?.firstOrNull { it.id == autoLogId && it.notes == null && !it.isLocalDirty }
            ?.let { replacedAutoIds.add(it.id) }
    }

    private fun recordConsumedHlc(entryId: String, logHlc: String) {
        var best = maxHlc(maxConsumedHlcByEntry[entryId], logHlc)
        for (existing in existingConsumedByEntry[entryId].orEmpty()) {
            best = maxHlc(best, existing.hlc)
        }
        maxConsumedHlcByEntry[entryId] = best
    }

    fun toPlan(): IncomingLogsMergePlan =
        IncomingLogsMergePlan(
            toInsert = toInsert,
            maxConsumedHlcByEntry = maxConsumedHlcByEntry,
            replacedAutoLogIds = replacedAutoIds,
            clearedTombstoneIds = clearedTombstoneIds,
        )
}

private suspend fun prunePromotedWantMemberships(
    dao: RocheDao,
    cache: GraphEdgesBatchCache,
    maxConsumedHlcByEntry: Map<String, String>,
) {
    val wantIds =
        cache
            .specsById()
            .values
            .filter {
                CollectionRole.fromWireValue(it.role) == CollectionRole.WANT ||
                    it.collectionId == UP_NEXT_COLLECTION_ID
            }
            .mapTo(HashSet()) { it.collectionId }
    if (wantIds.isEmpty()) return
    val toRemove = ArrayList<Pair<String, String>>()
    for (mem in cache.membershipsByKey().values) {
        val consumedHlc =
            if (mem.collectionId in wantIds) maxConsumedHlcByEntry[mem.entryId] else null
        if (consumedHlc != null && compareHlc(mem.hlc, consumedHlc) <= 0) {
            toRemove.add(mem.collectionId to mem.entryId)
        }
    }
    for ((colId, entryId) in toRemove) {
        dao.deleteCollectionEntry(colId, entryId)
        cache.removeMembership(colId, entryId)
    }
}

@Suppress("LongParameterList")
private suspend fun mergeIncomingComparisonsInDao(
    dao: RocheDao,
    incoming: List<SyncComparisonDto>,
    redirects: Map<String, String>,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
) {
    if (incoming.isEmpty()) return
    val toInsert = ArrayList<ComparisonEntity>()
    for (comp in incoming) {
        val canonicalOrdering = canonicalizeIncomingOrdering(comp.ordering, redirects)
        if (EpRankingEngine.validateOrdering(canonicalOrdering).isFailure) continue
        val rawScope = comp.scopeCollectionId?.trim()?.takeIf { it.isNotEmpty() }
        val scope = rawScope?.let { followMergedInMap(it, redirects) }?.takeIf { it.isNotEmpty() }
        val tsMillis = parseIsoInstant(comp.ts)?.toEpochMilli() ?: 0L
        val wasRewritten = canonicalOrdering != comp.ordering || scope != rawScope
        val id =
            comp.id.trim().takeIf { it.isNotEmpty() && !wasRewritten }
                ?: deterministicComparisonId(scope, tsMillis, canonicalOrdering)
        val compHlc = resolveOrSynthesizeHlc(comp.hlc, comp.ts, nowIso, serverNode, nowMs)
        toInsert.add(
            ComparisonEntity(
                id = id,
                ts = comp.ts.ifBlank { nowIso },
                scopeCollectionId = scope,
                orderingJson = canonicalOrderingJson(canonicalOrdering),
                createdAt = comp.createdAt ?: nowIso,
                hlc = compHlc,
                isLocalDirty = false,
            )
        )
    }
    if (toInsert.isNotEmpty()) {
        dao.insertComparisonsIgnore(toInsert)
    }
}

private fun canonicalizeIncomingOrdering(
    ordering: List<List<String>>,
    redirects: Map<String, String>,
): List<List<String>> {
    if (redirects.isEmpty()) return ordering
    val seen = HashSet<String>()
    return ordering.mapNotNull { group ->
        group
            .map { followMergedInMap(it, redirects) }
            .filter { it.isNotEmpty() && seen.add(it) }
            .takeIf { it.isNotEmpty() }
    }
}

@Suppress("LongParameterList")
private suspend fun mergeIncomingGeneratorsInDao(
    dao: RocheDao,
    cache: GraphEdgesBatchCache,
    catalogStore: CatalogStore?,
    incoming: List<SyncCollectionGeneratorDto>,
    redirects: Map<String, String>,
    serverNode: String,
    localPeerId: String,
    nowIso: String,
    nowMs: Long,
    allowLocalAutoAdvance: Boolean,
) {
    val specsById = cache.specsById()
    val localByColId = dao.getAllCollectionGenerators().associateBy { it.collectionId }
    val remoteByColId =
        filterTombstonedGenerators(
            dao = dao,
            cache = cache,
            remoteByColId = indexEligibleRemoteGenerators(incoming, specsById, redirects),
            serverNode = serverNode,
            nowIso = nowIso,
            nowMs = nowMs,
        )
    val candidateIds =
        (localByColId.keys + remoteByColId.keys).distinct().filter { it != STARRED_COLLECTION_ID }
    for (colId in candidateIds) {
        val merged =
            reconcileSingleGeneratorInDao(
                dao = dao,
                catalogStore = catalogStore,
                colId = colId,
                localGen = localByColId[colId],
                remoteGen = remoteByColId[colId],
                redirects = redirects,
                serverNode = serverNode,
                localPeerId = localPeerId,
                nowIso = nowIso,
                nowMs = nowMs,
                allowLocalAutoAdvance = allowLocalAutoAdvance,
            )
        dao.upsertCollectionGenerator(merged)
    }
}

private suspend fun filterTombstonedGenerators(
    dao: RocheDao,
    cache: GraphEdgesBatchCache,
    remoteByColId: Map<String, SyncCollectionGeneratorDto>,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
): Map<String, SyncCollectionGeneratorDto> {
    if (remoteByColId.isEmpty()) return remoteByColId
    val genTombstones = cache.tombstonesFor(TOMBSTONE_ENTITY_COLLECTION_GENERATOR)
    val filtered = LinkedHashMap<String, SyncCollectionGeneratorDto>(remoteByColId.size)
    for ((colId, remote) in remoteByColId) {
        val tomb = genTombstones[colId]
        if (tomb != null) {
            val incHlc =
                resolveOrSynthesizeHlc(remote.hlc, remote.updatedAt, nowIso, serverNode, nowMs)
            if (compareHlc(tomb.hlc, incHlc) >= 0) {
                continue
            }
            dao.deleteTombstone(TOMBSTONE_ENTITY_COLLECTION_GENERATOR, colId)
            cache.removeTombstone(TOMBSTONE_ENTITY_COLLECTION_GENERATOR, colId)
        }
        filtered[colId] = remote
    }
    return filtered
}

private fun indexEligibleRemoteGenerators(
    incoming: List<SyncCollectionGeneratorDto>,
    specsById: Map<String, CollectionSpecEntity>,
    redirects: Map<String, String>,
): Map<String, SyncCollectionGeneratorDto> {
    val remoteByColId = LinkedHashMap<String, SyncCollectionGeneratorDto>()
    for (remote in incoming) {
        val rawColId = remote.collectionId.trim()
        if (rawColId.isNotEmpty()) {
            val resolvedColId = followMergedInMap(rawColId, redirects)
            val spec = specsById[resolvedColId]
            if (spec != null && isGeneratorEligible(spec)) {
                remoteByColId[resolvedColId] = remote.copy(collectionId = resolvedColId)
            }
        }
    }
    return remoteByColId
}

@Suppress("LongParameterList")
private suspend fun reconcileSingleGeneratorInDao(
    dao: RocheDao,
    catalogStore: CatalogStore?,
    colId: String,
    localGen: CollectionGeneratorEntity?,
    remoteGen: SyncCollectionGeneratorDto?,
    redirects: Map<String, String>,
    serverNode: String,
    localPeerId: String,
    nowIso: String,
    nowMs: Long,
    allowLocalAutoAdvance: Boolean,
): CollectionGeneratorEntity {
    var base =
        selectBaseGeneratorEntity(
            colId,
            localGen,
            remoteGen,
            serverNode,
            localPeerId,
            nowIso,
            nowMs,
        )
    val activeId =
        base.currentEntryId?.takeIf { it.isNotBlank() }?.let { followMergedInMap(it, redirects) }
    if (activeId != base.currentEntryId) {
        base = base.copy(currentEntryId = activeId)
    }
    if (allowLocalAutoAdvance) {
        if (!activeId.isNullOrBlank() && dao.countTerminalLogsForEntry(activeId) > 0) {
            val hlc = nextLocalHlc(dao, localPeerId)
            base =
                base.copy(
                    currentEntryId = null,
                    drawnAt = null,
                    updatedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = true,
                )
        }
        if (base.enabled && base.currentEntryId.isNullOrBlank() && catalogStore != null) {
            val drawn = catalogStore.drawRandomRemainingId(colId)
            if (drawn != null) {
                val hlc = nextLocalHlc(dao, localPeerId)
                base =
                    base.copy(
                        currentEntryId = drawn,
                        drawnAt = nowIso,
                        updatedAt = nowIso,
                        hlc = hlc,
                        isLocalDirty = true,
                    )
            }
        }
    }
    return base
}

@Suppress("LongParameterList")
private fun selectBaseGeneratorEntity(
    collectionId: String,
    localGen: CollectionGeneratorEntity?,
    remoteGen: SyncCollectionGeneratorDto?,
    serverNode: String,
    localPeerId: String,
    nowIso: String,
    nowMs: Long,
): CollectionGeneratorEntity {
    val selected =
        if (remoteGen != null) {
            val remHlc =
                resolveOrSynthesizeHlc(
                    remoteGen.hlc,
                    remoteGen.updatedAt,
                    nowIso,
                    serverNode,
                    nowMs,
                )
            val remoteEntryId = remoteGen.currentEntryId?.trim()?.takeIf { it.isNotEmpty() }
            val localEntryId = localGen?.currentEntryId?.trim()?.takeIf { it.isNotEmpty() }
            val hlcCmp = if (localGen == null) 1 else compareHlc(remHlc, localGen.hlc)
            val preferRemote =
                localGen == null ||
                    hlcCmp > 0 ||
                    hlcCmp == 0 && localEntryId.isNullOrBlank() && !remoteEntryId.isNullOrBlank()
            when {
                preferRemote ->
                    CollectionGeneratorEntity(
                        collectionId = collectionId,
                        enabled = remoteGen.enabled,
                        currentEntryId = remoteEntryId,
                        drawnAt = remoteGen.drawnAt,
                        updatedAt = remoteGen.updatedAt.ifBlank { nowIso },
                        hlc = remHlc,
                        isLocalDirty = false,
                    )
                hlcCmp < 0 &&
                    (localGen.enabled != remoteGen.enabled || localEntryId != remoteEntryId) ->
                    localGen.copy(isLocalDirty = true)
                else -> localGen
            }
        } else {
            localGen
        }
    return selected
        ?: CollectionGeneratorEntity(
            collectionId = collectionId,
            enabled = false,
            currentEntryId = null,
            drawnAt = null,
            updatedAt = nowIso,
            hlc = HlcTimestamp.fromIsoOrEpoch(nowIso, localPeerId).encode(),
            isLocalDirty = false,
        )
}

internal class DuplicateFoldBatchCache(private val dao: RocheDao) {
    private var membershipsByKey: MutableMap<Pair<String, String>, CollectionEntryEntity>? = null
    private var comparisonsById: MutableMap<String, ComparisonEntity>? = null
    private var generatorsByColId: MutableMap<String, CollectionGeneratorEntity>? = null

    suspend fun membershipsByKey(): MutableMap<Pair<String, String>, CollectionEntryEntity> =
        membershipsByKey
            ?: dao.getAllCollectionEntries()
                .associateByTo(HashMap()) { it.collectionId to it.entryId }
                .also { membershipsByKey = it }

    suspend fun comparisonsById(): MutableMap<String, ComparisonEntity> =
        comparisonsById
            ?: dao.getAllComparisons()
                .associateByTo(HashMap()) { it.id }
                .also {
                    comparisonsById = it
                }

    suspend fun generatorsByColId(): MutableMap<String, CollectionGeneratorEntity> =
        generatorsByColId
            ?: dao.getAllCollectionGenerators()
                .associateByTo(HashMap()) { it.collectionId }
                .also { generatorsByColId = it }
}

internal suspend fun foldMergedDuplicateInDao(
    dao: RocheDao,
    dupId: String,
    canonId: String,
    nowIso: String,
    cache: DuplicateFoldBatchCache? = null,
) {
    val batch = cache ?: DuplicateFoldBatchCache(dao)
    dao.repointIdentifiersToEntry(dupId, canonId)
    repointMergedMembershipsInDao(dao, batch, dupId, canonId, nowIso)
    dao.repointConsumptionLogsToEntry(dupId, canonId)
    repointMergedComparisonsInDao(dao, batch, dupId, canonId)
    val generators = batch.generatorsByColId()
    val updatedGenerators = ArrayList<CollectionGeneratorEntity>()
    for (gen in generators.values) {
        if (gen.currentEntryId == dupId) {
            val updated = gen.copy(currentEntryId = canonId, updatedAt = nowIso)
            dao.upsertCollectionGenerator(updated)
            updatedGenerators.add(updated)
        }
    }
    for (updated in updatedGenerators) {
        generators[updated.collectionId] = updated
    }
}

private suspend fun repointMergedMembershipsInDao(
    dao: RocheDao,
    batch: DuplicateFoldBatchCache,
    dupId: String,
    canonId: String,
    nowIso: String,
) {
    val memberships = batch.membershipsByKey()
    val migrated = ArrayList<CollectionEntryEntity>()
    val removedKeys = ArrayList<Pair<String, String>>()
    for ((key, mem) in memberships) {
        if (mem.entryId != dupId && mem.collectionId != dupId) continue
        removedKeys.add(key)
        buildMigratedMembership(mem, memberships, dupId, canonId, nowIso)?.let(migrated::add)
    }
    dao.deleteMergedDupMemberships(dupId)
    removedKeys.forEach(memberships::remove)
    if (migrated.isNotEmpty()) {
        dao.upsertCollectionEntries(migrated)
        migrated.forEach { memberships[it.collectionId to it.entryId] = it }
    }
}

private fun buildMigratedMembership(
    mem: CollectionEntryEntity,
    existing: Map<Pair<String, String>, CollectionEntryEntity>,
    dupId: String,
    canonId: String,
    nowIso: String,
): CollectionEntryEntity? {
    val targetColId = if (mem.collectionId == dupId) canonId else mem.collectionId
    val targetEntryId = if (mem.entryId == dupId) canonId else mem.entryId
    if (targetColId == targetEntryId) return null
    if (targetColId to targetEntryId in existing) return null
    return mem.copy(collectionId = targetColId, entryId = targetEntryId, addedAt = nowIso)
}

private suspend fun repointMergedComparisonsInDao(
    dao: RocheDao,
    batch: DuplicateFoldBatchCache,
    dupId: String,
    canonId: String,
) {
    val comparisons = batch.comparisonsById()
    val affected =
        comparisons.values.filter {
            it.scopeCollectionId == dupId || it.orderingJson.contains(dupId)
        }
    for (comp in affected) {
        val parsed = decodeComparisonOrdering(comp.orderingJson) ?: continue
        val newScope = if (comp.scopeCollectionId == dupId) canonId else comp.scopeCollectionId
        val rewritten = rewriteOrderingAfterMerge(parsed, dupId, canonId)
        dao.deleteComparisonById(comp.id)
        comparisons.remove(comp.id)
        if (rewritten.sumOf { it.size } >= EpRankingEngine.MIN_K) {
            val tsMillis = parseIsoInstant(comp.ts)?.toEpochMilli() ?: 0L
            val newId = deterministicComparisonId(newScope, tsMillis, rewritten)
            val updated =
                comp.copy(
                    id = newId,
                    scopeCollectionId = newScope,
                    orderingJson = canonicalOrderingJson(rewritten),
                )
            dao.insertComparisonIgnore(updated)
            comparisons.putIfAbsent(newId, updated)
        }
    }
}

internal fun rewriteOrderingAfterMerge(
    ordering: List<List<String>>,
    dupId: String,
    canonId: String,
): List<List<String>> {
    val seen = HashSet<String>()
    return ordering.mapNotNull { group ->
        val dedupGroup =
            group.map { id -> if (id == dupId) canonId else id }.filter { seen.add(it) }
        dedupGroup.takeIf { it.isNotEmpty() }
    }
}
