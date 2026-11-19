@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.deleteItemPreservingHistory
import com.grayvines.roche.data.local.entryHasCatalogHistory
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.ranking.EpRankingEngine
import java.time.Instant

@Suppress("LongParameterList")
internal suspend fun mergeIncomingGraphEdgesInTx(
    dao: RocheDao,
    catalogStore: CatalogStore?,
    response: PeerSyncResponseDto,
    redirects: Map<String, String>,
    serverNode: String,
    localPeerId: String = DEFAULT_LOCAL_HLC_NODE,
    nowIso: String,
) {
    mergeCollectionEntriesInDao(
        dao = dao,
        incoming = response.collectionEntries,
        redirects = redirects,
        serverNode = serverNode,
        nowIso = nowIso,
    )
    enforceLinkedItemTombstones(dao)
    mergeConsumptionLogsInDao(
        dao = dao,
        incoming = response.consumptionLog,
        redirects = redirects,
        serverNode = serverNode,
        nowIso = nowIso,
    )
    mergeIncomingComparisonsInDao(
        dao = dao,
        incoming = response.comparisons,
        redirects = redirects,
        serverNode = serverNode,
        nowIso = nowIso,
    )
    mergeIncomingGeneratorsInDao(
        dao = dao,
        catalogStore = catalogStore,
        incoming = response.collectionGenerators,
        redirects = redirects,
        serverNode = serverNode,
        localPeerId = localPeerId,
        nowIso = nowIso,
        allowLocalAutoAdvance = !response.hasMore,
    )
}

private suspend fun enforceLinkedItemTombstones(dao: RocheDao) {
    val itemTombstones =
        dao.getAllTombstones()
            .filter { it.entityType.equals("item", ignoreCase = true) }
            .associateBy { it.naturalKey }
    if (itemTombstones.isEmpty()) return

    val feedUrlsByColId =
        dao.getAllFeedSources().associate { it.collectionId to canonicalizeSyncUrl(it.feedUrl) }
    val membershipsByEntry =
        dao.getAllCollectionEntries().groupBy({ it.entryId }, { it.collectionId })
    val statesById = dao.getAllEntryStates().associateBy { it.entryId }

    for (entry in dao.getAllEntries()) {
        reconcileLinkedInboxTombstone(
            dao = dao,
            entry = entry,
            state = statesById[entry.id],
            entryColIds = membershipsByEntry[entry.id].orEmpty(),
            feedUrlsByColId = feedUrlsByColId,
            itemTombstones = itemTombstones,
        )
    }
}

@Suppress("LongParameterList")
private suspend fun reconcileLinkedInboxTombstone(
    dao: RocheDao,
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
        feedUrls.isEmpty() &&
            (state.inboxState == "unread" ||
                state.inboxState == "dismissed" ||
                (state.inboxState == "none" &&
                    (WANT_TO_READ_COLLECTION_ID in entryColIds ||
                        !dao.entryHasCatalogHistory(entry.id))))
    val isFeedActive =
        feedUrls.isNotEmpty() && (state.inboxState == "unread" || state.inboxState == "dismissed")
    if (!isManualActive && !isFeedActive) return
    val keys =
        if (feedUrls.isEmpty()) {
            listOf(itemNaturalKey(null, itemUrl))
        } else {
            feedUrls.map { itemNaturalKey(it, itemUrl) }
        }
    val activeTomb = keys.mapNotNull { itemTombstones[it] }.maxByOrNull { it.hlc } ?: return
    if (state.hlc > activeTomb.hlc) {
        dao.deleteTombstone("item", activeTomb.naturalKey)
    } else {
        dao.deleteItemPreservingHistory(entry.id, recordTombstones = false)
    }
}

private suspend fun mergeCollectionEntriesInDao(
    dao: RocheDao,
    incoming: List<SyncCollectionEntryDto>,
    redirects: Map<String, String>,
    serverNode: String,
    nowIso: String,
) {
    if (incoming.isEmpty()) return
    val existingMemberships =
        dao.getAllCollectionEntries().associateBy { it.collectionId to it.entryId }
    val feedIds = dao.getAllFeedSources().mapTo(HashSet()) { it.collectionId }
    val childrenByParent = HashMap<String, MutableSet<String>>()
    for ((colId, entryId) in existingMemberships.keys) {
        childrenByParent.getOrPut(colId) { HashSet() }.add(entryId)
    }
    val tombstones =
        dao.getAllTombstones()
            .filter { it.entityType.equals(COLLECTION_ENTRY_ENTITY, ignoreCase = true) }
            .associateBy { it.naturalKey }
    val ctx = IncomingMembershipCtx(redirects, existingMemberships, tombstones, childrenByParent)
    val upserts = ArrayList<CollectionEntryEntity>()
    val linkedFeedIds = HashSet<String>()

    for (ce in incoming) {
        val resolved = resolveIncomingMembershipDto(ce, ctx, serverNode, nowIso) ?: continue
        resolved.second?.let { natKey -> dao.deleteTombstone(COLLECTION_ENTRY_ENTITY, natKey) }
        val row = resolved.first
        childrenByParent.getOrPut(row.collectionId) { HashSet() }.add(row.entryId)
        upserts.add(row)
        if (row.collectionId in feedIds) {
            linkedFeedIds.add(row.collectionId)
        }
    }
    if (upserts.isNotEmpty()) {
        dao.upsertCollectionEntries(upserts)
    }
    for (fid in linkedFeedIds) {
        dao.clearFallbackFeedTitleForFeed(fid)
    }
}

private data class IncomingMembershipCtx(
    val redirects: Map<String, String>,
    val existingMemberships: Map<Pair<String, String>, CollectionEntryEntity>,
    val tombstones: Map<String, TombstoneEntity>,
    val childrenByParent: Map<String, Set<String>>,
)

private fun resolveIncomingMembershipDto(
    ce: SyncCollectionEntryDto,
    ctx: IncomingMembershipCtx,
    serverNode: String,
    nowIso: String,
): Pair<CollectionEntryEntity, String?>? {
    val colId = followMergedInMap(ce.collectionId, ctx.redirects)
    val entryId = followMergedInMap(ce.entryId, ctx.redirects)
    if (colId.isEmpty() || entryId.isEmpty() || colId == entryId) return null
    val memHlc = resolveOrSynthesizeHlc(ce.hlc, ce.addedAt, nowIso, serverNode)
    val natKey = collectionEntryNaturalKey(colId, entryId)
    val tomb = ctx.tombstones[natKey]
    if (tomb != null && tomb.hlc >= memHlc) return null

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
            hlc = maxOf(memHlc, existing?.hlc.orEmpty()),
            isLocalDirty = false,
        )
    return entity to tomb?.let { natKey }
}

private data class IncomingLogsMergePlan(
    val toInsert: List<ConsumptionLogEntity>,
    val maxConsumedHlcByEntry: Map<String, String>,
    val replacedAutoLogIds: Set<String>,
)

private suspend fun mergeConsumptionLogsInDao(
    dao: RocheDao,
    incoming: List<SyncConsumptionLogDto>,
    redirects: Map<String, String>,
    serverNode: String,
    nowIso: String,
) {
    if (incoming.isEmpty()) return
    val existingConsumedByEntry =
        dao.getAllConsumptionLogs()
            .filter { ConsumptionStatus.fromWireValue(it.status) == ConsumptionStatus.CONSUMED }
            .groupBy { it.entryId }
    val acc = IncomingLogsAcc(existingConsumedByEntry, serverNode, nowIso)
    for (log in incoming) {
        val entryId = followMergedInMap(log.entryId, redirects)
        if (entryId.isNotEmpty()) {
            acc.accept(log, entryId)
        }
    }
    val plan = acc.toPlan()
    plan.replacedAutoLogIds.forEach { dao.deleteConsumptionLogById(it) }
    if (plan.toInsert.isNotEmpty()) {
        dao.insertConsumptionLogsIgnore(plan.toInsert)
    }
    if (plan.maxConsumedHlcByEntry.isNotEmpty()) {
        prunePromotedWantMemberships(dao, plan.maxConsumedHlcByEntry)
    }
}

private class IncomingLogsAcc(
    private val existingConsumedByEntry: Map<String, List<ConsumptionLogEntity>>,
    private val serverNode: String,
    private val nowIso: String,
) {
    private val consumedInBatch = HashSet<String>(existingConsumedByEntry.keys)
    private val toInsert = ArrayList<ConsumptionLogEntity>()
    private val maxConsumedHlcByEntry = HashMap<String, String>()
    private val replacedAutoIds = HashSet<String>()

    fun accept(log: SyncConsumptionLogDto, entryId: String) {
        val status = ConsumptionStatus.fromWireValue(log.status)
        val tsMillis = runCatching { Instant.parse(log.ts.trim()).toEpochMilli() }.getOrDefault(0L)
        val id =
            log.id.trim().takeIf { it.isNotEmpty() }
                ?: deterministicConsumptionLogId(entryId, tsMillis, status.wireValue)
        val autoLogId = deterministicAutoLogId(entryId)
        val isConsumed = status == ConsumptionStatus.CONSUMED
        val logHlc = resolveOrSynthesizeHlc(log.hlc, log.ts, nowIso, serverNode)

        if (isConsumed && id == autoLogId && entryId in consumedInBatch) {
            recordConsumedHlc(entryId, logHlc)
            return
        }
        if (isConsumed && id != autoLogId) {
            existingConsumedByEntry[entryId]
                ?.firstOrNull { it.id == autoLogId && it.notes == null && !it.isLocalDirty }
                ?.let { replacedAutoIds.add(it.id) }
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

    private fun recordConsumedHlc(entryId: String, logHlc: String) {
        var best = maxOf(maxConsumedHlcByEntry[entryId].orEmpty(), logHlc)
        for (existing in existingConsumedByEntry[entryId].orEmpty()) {
            best = maxOf(best, existing.hlc)
        }
        maxConsumedHlcByEntry[entryId] = best
    }

    fun toPlan(): IncomingLogsMergePlan =
        IncomingLogsMergePlan(toInsert, maxConsumedHlcByEntry, replacedAutoIds)
}

private suspend fun prunePromotedWantMemberships(
    dao: RocheDao,
    maxConsumedHlcByEntry: Map<String, String>,
) {
    val wantIds =
        dao.getAllCollectionSpecs()
            .filter {
                CollectionRole.fromWireValue(it.role) == CollectionRole.WANT ||
                    it.collectionId == UP_NEXT_COLLECTION_ID
            }
            .mapTo(HashSet()) { it.collectionId }
    if (wantIds.isEmpty()) return
    for (mem in dao.getAllCollectionEntries()) {
        val consumedHlc =
            if (mem.collectionId in wantIds) maxConsumedHlcByEntry[mem.entryId] else null
        if (consumedHlc != null && mem.hlc <= consumedHlc) {
            dao.deleteCollectionEntry(mem.collectionId, mem.entryId)
        }
    }
}

private suspend fun mergeIncomingComparisonsInDao(
    dao: RocheDao,
    incoming: List<SyncComparisonDto>,
    redirects: Map<String, String>,
    serverNode: String,
    nowIso: String,
) {
    if (incoming.isEmpty()) return
    val toInsert = ArrayList<ComparisonEntity>()
    for (comp in incoming) {
        val canonicalOrdering = canonicalizeIncomingOrdering(comp.ordering, redirects)
        if (EpRankingEngine.validateOrdering(canonicalOrdering).isFailure) continue
        val rawScope = comp.scopeCollectionId?.trim()?.takeIf { it.isNotEmpty() }
        val scope = rawScope?.let { followMergedInMap(it, redirects) }?.takeIf { it.isNotEmpty() }
        val tsMillis = runCatching { Instant.parse(comp.ts.trim()).toEpochMilli() }.getOrDefault(0L)
        val wasRewritten = canonicalOrdering != comp.ordering || scope != rawScope
        val id =
            comp.id.trim().takeIf { it.isNotEmpty() && !wasRewritten }
                ?: deterministicComparisonId(scope, tsMillis, canonicalOrdering)
        val compHlc = resolveOrSynthesizeHlc(comp.hlc, comp.ts, nowIso, serverNode)
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
    catalogStore: CatalogStore?,
    incoming: List<SyncCollectionGeneratorDto>,
    redirects: Map<String, String>,
    serverNode: String,
    localPeerId: String,
    nowIso: String,
    allowLocalAutoAdvance: Boolean,
) {
    val specsById = dao.getAllCollectionSpecs().associateBy { it.collectionId }
    val localByColId = dao.getAllCollectionGenerators().associateBy { it.collectionId }
    val remoteByColId =
        filterTombstonedRemoteGenerators(
            dao = dao,
            remoteByColId = indexEligibleRemoteGenerators(incoming, specsById, redirects),
            serverNode = serverNode,
            nowIso = nowIso,
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
                allowLocalAutoAdvance = allowLocalAutoAdvance,
            )
        dao.upsertCollectionGenerator(merged)
    }
}

private suspend fun filterTombstonedRemoteGenerators(
    dao: RocheDao,
    remoteByColId: Map<String, SyncCollectionGeneratorDto>,
    serverNode: String,
    nowIso: String,
): Map<String, SyncCollectionGeneratorDto> {
    if (remoteByColId.isEmpty()) return remoteByColId
    val filtered = LinkedHashMap<String, SyncCollectionGeneratorDto>(remoteByColId.size)
    for ((colId, remote) in remoteByColId) {
        val tomb = dao.getTombstone(TOMBSTONE_ENTITY_COLLECTION_GENERATOR, colId)
        if (tomb != null) {
            val incHlc = resolveOrSynthesizeHlc(remote.hlc, remote.updatedAt, nowIso, serverNode)
            if (tomb.hlc >= incHlc) {
                continue
            }
            dao.deleteTombstone(TOMBSTONE_ENTITY_COLLECTION_GENERATOR, colId)
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
            val isManualList =
                resolvedColId != STARRED_COLLECTION_ID &&
                    spec != null &&
                    CollectionMode.fromWireValue(spec.mode) == CollectionMode.MANUAL &&
                    CollectionRole.fromWireValue(spec.role) == CollectionRole.LIST
            if (isManualList) {
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
    allowLocalAutoAdvance: Boolean,
): CollectionGeneratorEntity {
    var base = selectBaseGeneratorEntity(colId, localGen, remoteGen, serverNode, nowIso)
    val activeId =
        base.currentEntryId?.takeIf { it.isNotBlank() }?.let { followMergedInMap(it, redirects) }
    if (activeId != base.currentEntryId) {
        base = base.copy(currentEntryId = activeId)
    }
    if (allowLocalAutoAdvance) {
        if (!activeId.isNullOrBlank() && dao.countTerminalLogsForEntry(activeId) > 0) {
            val hlc = nextLocalHlc(dao, localPeerId)
            base =
                base.copy(currentEntryId = null, updatedAt = nowIso, hlc = hlc, isLocalDirty = true)
        }
        if (base.enabled && base.currentEntryId.isNullOrBlank() && catalogStore != null) {
            val drawn = catalogStore.drawRandomRemainingId(colId)
            val hlc = nextLocalHlc(dao, localPeerId)
            base =
                base.copy(
                    currentEntryId = drawn,
                    drawnAt = drawn?.let { nowIso },
                    updatedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = true,
                )
        }
    }
    return base
}

private fun selectBaseGeneratorEntity(
    collectionId: String,
    localGen: CollectionGeneratorEntity?,
    remoteGen: SyncCollectionGeneratorDto?,
    serverNode: String,
    nowIso: String,
): CollectionGeneratorEntity {
    val selected =
        if (remoteGen != null) {
            val remHlc =
                resolveOrSynthesizeHlc(remoteGen.hlc, remoteGen.updatedAt, nowIso, serverNode)
            val remoteEntryId = remoteGen.currentEntryId?.trim()?.takeIf { it.isNotEmpty() }
            val localEntryId = localGen?.currentEntryId?.trim()?.takeIf { it.isNotEmpty() }
            val preferRemote =
                localGen == null ||
                    remHlc > localGen.hlc ||
                    remHlc == localGen.hlc &&
                        localEntryId.isNullOrBlank() &&
                        !remoteEntryId.isNullOrBlank()
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
                remHlc < localGen.hlc &&
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
            hlc = HlcTimestamp.fromIsoOrEpoch(nowIso, DEFAULT_LOCAL_HLC_NODE).encode(),
            isLocalDirty = false,
        )
}

internal suspend fun foldMergedDuplicateInDao(
    dao: RocheDao,
    dupId: String,
    canonId: String,
    nowIso: String,
) {
    dao.repointIdentifiersToEntry(dupId, canonId)
    repointMergedMembershipsInDao(dao, dupId, canonId, nowIso)
    dao.repointConsumptionLogsToEntry(dupId, canonId)
    repointMergedComparisonsInDao(dao, dupId, canonId)
    for (gen in dao.getAllCollectionGenerators()) {
        if (gen.currentEntryId == dupId) {
            dao.upsertCollectionGenerator(gen.copy(currentEntryId = canonId, updatedAt = nowIso))
        }
    }
}

private suspend fun repointMergedMembershipsInDao(
    dao: RocheDao,
    dupId: String,
    canonId: String,
    nowIso: String,
) {
    val allMemberships = dao.getAllCollectionEntries()
    val existingPairs = allMemberships.map { it.collectionId to it.entryId }.toHashSet()
    val migrated = ArrayList<CollectionEntryEntity>()
    for (mem in allMemberships) {
        if (mem.entryId == dupId && mem.collectionId != canonId) {
            if (mem.collectionId to canonId !in existingPairs) {
                migrated.add(mem.copy(entryId = canonId, addedAt = nowIso))
            }
        } else if (mem.collectionId == dupId && mem.entryId != canonId) {
            if (canonId to mem.entryId !in existingPairs) {
                migrated.add(mem.copy(collectionId = canonId, addedAt = nowIso))
            }
        }
    }
    dao.deleteMergedDupMemberships(dupId)
    if (migrated.isNotEmpty()) {
        dao.upsertCollectionEntries(migrated)
    }
}

private suspend fun repointMergedComparisonsInDao(
    dao: RocheDao,
    dupId: String,
    canonId: String,
) {
    val affected =
        dao.getAllComparisons().filter {
            it.scopeCollectionId == dupId || it.orderingJson.contains(dupId)
        }
    for (comp in affected) {
        val parsed = decodeComparisonOrdering(comp.orderingJson) ?: continue
        val newScope = if (comp.scopeCollectionId == dupId) canonId else comp.scopeCollectionId
        val rewritten = rewriteOrderingAfterMerge(parsed, dupId, canonId)
        dao.deleteComparisonById(comp.id)
        if (rewritten.sumOf { it.size } >= EpRankingEngine.MIN_K) {
            val tsMillis = runCatching {
                Instant.parse(comp.ts.trim()).toEpochMilli()
            }
                .getOrDefault(0L)
            val newId = deterministicComparisonId(newScope, tsMillis, rewritten)
            dao.insertComparisonIgnore(
                comp.copy(
                    id = newId,
                    scopeCollectionId = newScope,
                    orderingJson = canonicalOrderingJson(rewritten),
                )
            )
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
