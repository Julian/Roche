@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.CatalogEntryEntity
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.SQLITE_DELETE_CHUNK_SIZE
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.rocheJson
import com.grayvines.roche.ranking.EpRankingEngine
import java.time.Instant
import kotlinx.serialization.json.JsonObject

internal data class OutgoingCatalogSyncBatch(
    val collections: List<PeerCollectionSpecPayload> = emptyList(),
    val catalogEntries: List<PeerCatalogEntryPayload> = emptyList(),
    val collectionEntries: List<PeerCollectionEntryPayload> = emptyList(),
    val collectionGenerators: List<PeerCollectionGeneratorPayload> = emptyList(),
    val consumptionLog: List<PeerConsumptionLogPayload> = emptyList(),
    val comparisons: List<PeerComparisonPayload> = emptyList(),
    val sentDirtyCollectionUpdatedAt: Map<String, String> = emptyMap(),
    val sentDirtyEntryUpdatedAt: Map<String, String> = emptyMap(),
    val sentDirtyMembershipAddedAt: Map<Pair<String, String>, String> = emptyMap(),
    val sentDirtyGeneratorUpdatedAt: Map<String, String> = emptyMap(),
    val sentDirtyLogIds: Set<String> = emptySet(),
    val sentDirtyComparisonIds: Set<String> = emptySet(),
    val overflowCollectionIds: List<String> = emptyList(),
    val overflowEntryIds: List<String> = emptyList(),
    val overflowMembershipKeys: List<Pair<String, String>> = emptyList(),
    val overflowLogIds: List<String> = emptyList(),
    val overflowComparisonIds: List<String> = emptyList(),
    val hasAnyLocalCatalogData: Boolean = false,
)

internal suspend fun hasLocalCatalogSyncData(dao: RocheDao): Boolean {
    if (dao.getAllConsumptionLogs().isNotEmpty() || dao.getAllComparisons().isNotEmpty()) {
        return true
    }
    if (dao.getAllCollectionGenerators().any { it.enabled || !it.currentEntryId.isNullOrBlank() }) {
        return true
    }
    val specs = dao.getAllCollectionSpecs()
    if (
        specs.any {
            CollectionMode.fromWireValue(it.mode) == CollectionMode.MANUAL &&
                (CollectionRole.fromWireValue(it.role) == CollectionRole.LIST ||
                    CollectionRole.fromWireValue(it.role) == CollectionRole.WANT)
        }
    ) {
        return true
    }
    val specIds = specs.map { it.collectionId }.toHashSet()
    val pool1001Ids =
        dao.getCollectionMemberships(ALBUMS_1001_COLLECTION_ID).map { it.entryId }.toHashSet()
    return dao.getAllCatalogEntries().any { it.id !in specIds && it.id !in pool1001Ids }
}

private const val TOMBSTONE_COLLECTION_ENTRY = "collection_entry"
private const val MAX_SYNC_COLLECTIONS = 1_000
private const val MAX_SYNC_CATALOG_ENTRIES = 4_000
private const val MAX_SYNC_COLLECTION_ENTRIES = 8_000
private const val MAX_SYNC_CONSUMPTION_LOG = 8_000
private const val MAX_SYNC_COMPARISONS = 8_000

internal suspend fun buildOutgoingCatalogSyncBatch(
    dao: RocheDao,
    inclusiveSinceInstant: Instant?,
    syncedFeedIds: Set<String> = emptySet(),
    syncedItemIds: Set<String> = emptySet(),
): OutgoingCatalogSyncBatch {
    val tables = loadOutgoingCatalogTables(dao)
    val customSpecs = selectCustomSpecs(tables.specs)
    val (sentSpecs, overflowSpecs) = selectOutgoingSpecs(customSpecs, inclusiveSinceInstant)
    val specIds = tables.specs.map { it.collectionId }.toHashSet()
    val pool1001Ids = collectPool1001Ids(tables.memberships)
    val slices = selectOutgoingChildSlices(tables, syncedFeedIds, inclusiveSinceInstant)
    val entryFilterCtx =
        buildOutgoingEntryFilterCtx(
            tables = tables,
            slices = slices,
            specIds = specIds,
            pool1001Ids = pool1001Ids,
            syncedFeedIds = syncedFeedIds,
            syncedItemIds = syncedItemIds,
            inclusiveSince = inclusiveSinceInstant,
        )
    val (sentEntries, overflowEntries) =
        splitWithCap(
            tables.entries
                .filter { shouldSyncCatalogEntry(it, entryFilterCtx) }
                .sortedWith(
                    compareByDescending<CatalogEntryEntity> { it.isLocalDirty }
                        .thenBy { it.updatedAt }
                ),
            MAX_SYNC_CATALOG_ENTRIES,
        )
    val hasAnyLocalData =
        hasNonSeedCatalogRows(
            customSpecs,
            tables.logs,
            tables.comparisons,
            tables.generators,
            tables.entries,
            pool1001Ids,
            specIds,
        )
    return assembleOutgoingCatalogBatch(
        sentSpecs = sentSpecs,
        sentEntries = sentEntries,
        identsByEntryId = loadEntryIdentsByEntryId(dao),
        sentMemberships = slices.sentMemberships,
        sentGenerators = slices.sentGenerators,
        sentLogs = slices.sentLogs,
        sentComparisons = slices.sentComparisons,
        overflowIds =
            OverflowCatalogIds(
                collectionIds = overflowSpecs.map { it.collectionId },
                entryIds = overflowEntries.map { it.id },
                membershipKeys = slices.overflowMemberships.map { it.collectionId to it.entryId },
                logIds = slices.overflowLogs.map { it.id },
                comparisonIds = slices.overflowComparisons.map { it.id },
            ),
        hasAnyLocalCatalogData = hasAnyLocalData,
    )
}

private data class OutgoingCatalogTables(
    val specs: List<CollectionSpecEntity>,
    val entries: List<CatalogEntryEntity>,
    val memberships: List<CollectionEntryEntity>,
    val generators: List<CollectionGeneratorEntity>,
    val logs: List<ConsumptionLogEntity>,
    val comparisons: List<ComparisonEntity>,
)

private data class OutgoingChildSlices(
    val sentMemberships: List<CollectionEntryEntity>,
    val overflowMemberships: List<CollectionEntryEntity>,
    val sentGenerators: List<CollectionGeneratorEntity>,
    val sentLogs: List<ConsumptionLogEntity>,
    val overflowLogs: List<ConsumptionLogEntity>,
    val sentComparisons: List<ComparisonEntity>,
    val overflowComparisons: List<ComparisonEntity>,
)

private suspend fun loadOutgoingCatalogTables(dao: RocheDao): OutgoingCatalogTables =
    OutgoingCatalogTables(
        specs = dao.getAllCollectionSpecs(),
        entries = dao.getAllCatalogEntries(),
        memberships = dao.getAllCollectionEntries(),
        generators = dao.getAllCollectionGenerators(),
        logs = dao.getAllConsumptionLogs(),
        comparisons = dao.getAllComparisons(),
    )

private fun selectOutgoingChildSlices(
    tables: OutgoingCatalogTables,
    syncedFeedIds: Set<String>,
    inclusiveSince: Instant?,
): OutgoingChildSlices {
    val specsById = tables.specs.associateBy { it.collectionId }
    val (sentMem, overMem) =
        splitWithCap(
            selectOutgoingMemberships(tables.memberships, specsById, syncedFeedIds, inclusiveSince)
                .sortedWith(
                    compareByDescending<CollectionEntryEntity> { it.isLocalDirty }
                        .thenBy { it.addedAt }
                ),
            MAX_SYNC_COLLECTION_ENTRIES,
        )
    val (sentLogs, overLogs) =
        splitWithCap(
            selectOutgoingLogs(tables.logs, inclusiveSince)
                .sortedWith(
                    compareByDescending<ConsumptionLogEntity> { it.isLocalDirty }
                        .thenBy { it.createdAt }
                ),
            MAX_SYNC_CONSUMPTION_LOG,
        )
    val (sentComps, overComps) =
        splitWithCap(
            selectOutgoingComparisons(tables.comparisons, inclusiveSince)
                .sortedWith(
                    compareByDescending<ComparisonEntity> { it.isLocalDirty }
                        .thenBy { it.createdAt }
                ),
            MAX_SYNC_COMPARISONS,
        )
    return OutgoingChildSlices(
        sentMemberships = sentMem,
        overflowMemberships = overMem,
        sentGenerators = selectOutgoingGenerators(tables.generators, inclusiveSince),
        sentLogs = sentLogs,
        overflowLogs = overLogs,
        sentComparisons = sentComps,
        overflowComparisons = overComps,
    )
}

@Suppress("LongParameterList")
private fun buildOutgoingEntryFilterCtx(
    tables: OutgoingCatalogTables,
    slices: OutgoingChildSlices,
    specIds: Set<String>,
    pool1001Ids: Set<String>,
    syncedFeedIds: Set<String>,
    syncedItemIds: Set<String>,
    inclusiveSince: Instant?,
): OutgoingEntryFilterContext {
    val outgoingRefs =
        collectReferencedNonPoolIds(
            allEntries = emptyList(),
            allLogs = slices.sentLogs,
            allGenerators = slices.sentGenerators,
            allMemberships = slices.sentMemberships,
            allComparisons = slices.sentComparisons,
        ) - pool1001Ids - specIds
    val allRefs =
        collectReferencedNonPoolIds(
            tables.entries,
            tables.logs,
            tables.generators,
            tables.memberships,
            tables.comparisons,
        )
    return OutgoingEntryFilterContext(
        specIds = specIds,
        syncedFeedIds = syncedFeedIds,
        syncedItemIds = syncedItemIds,
        pool1001Ids = pool1001Ids,
        referencedEntryIds = allRefs,
        outgoingReferencedIds = outgoingRefs,
        inclusiveSince = inclusiveSince,
    )
}

private fun <T> splitWithCap(items: List<T>, cap: Int): Pair<List<T>, List<T>> =
    if (items.size <= cap) items to emptyList() else items.take(cap) to items.drop(cap)

private fun selectOutgoingSpecs(
    customSpecs: List<CollectionSpecEntity>,
    inclusiveSinceInstant: Instant?,
): Pair<List<CollectionSpecEntity>, List<CollectionSpecEntity>> =
    splitWithCap(
        customSpecs
            .filter {
                it.isLocalDirty || isIncludedInSyncWindow(it.updatedAt, inclusiveSinceInstant)
            }
            .sortedWith(
                compareByDescending<CollectionSpecEntity> { it.isLocalDirty }
                    .thenBy { it.updatedAt }
            ),
        MAX_SYNC_COLLECTIONS,
    )

private fun collectPool1001Ids(allMemberships: List<CollectionEntryEntity>): Set<String> =
    allMemberships
        .filter { it.collectionId == ALBUMS_1001_COLLECTION_ID }
        .map { it.entryId }
        .toHashSet()

private suspend fun loadEntryIdentsByEntryId(dao: RocheDao): Map<String, List<EntryIdentifierDto>> =
    dao.getAllEntryIdentifiers()
        .groupBy { it.entryId }
        .mapValues { (_, list) -> list.map { EntryIdentifierDto(it.scheme, it.value) } }

@Suppress("LongParameterList")
private fun hasNonSeedCatalogRows(
    customSpecs: List<CollectionSpecEntity>,
    allLogs: List<ConsumptionLogEntity>,
    allComparisons: List<ComparisonEntity>,
    allGenerators: List<CollectionGeneratorEntity>,
    allEntries: List<CatalogEntryEntity>,
    pool1001Ids: Set<String>,
    specIds: Set<String>,
): Boolean =
    customSpecs.isNotEmpty() ||
        allLogs.isNotEmpty() ||
        allComparisons.isNotEmpty() ||
        allGenerators.any { it.enabled || !it.currentEntryId.isNullOrBlank() } ||
        allEntries.any { it.id !in pool1001Ids && it.id !in specIds }

private data class OutgoingEntryFilterContext(
    val specIds: Set<String>,
    val syncedFeedIds: Set<String>,
    val syncedItemIds: Set<String>,
    val pool1001Ids: Set<String>,
    val referencedEntryIds: Set<String>,
    val outgoingReferencedIds: Set<String>,
    val inclusiveSince: Instant?,
)

private data class OverflowCatalogIds(
    val collectionIds: List<String> = emptyList(),
    val entryIds: List<String> = emptyList(),
    val membershipKeys: List<Pair<String, String>> = emptyList(),
    val logIds: List<String> = emptyList(),
    val comparisonIds: List<String> = emptyList(),
)

private fun selectCustomSpecs(allSpecs: List<CollectionSpecEntity>): List<CollectionSpecEntity> =
    allSpecs.filter { spec ->
        val role = CollectionRole.fromWireValue(spec.role)
        CollectionMode.fromWireValue(spec.mode) == CollectionMode.MANUAL &&
            (role == CollectionRole.LIST || role == CollectionRole.WANT)
    }

private fun collectReferencedNonPoolIds(
    allEntries: List<CatalogEntryEntity>,
    allLogs: List<ConsumptionLogEntity>,
    allGenerators: List<CollectionGeneratorEntity>,
    allMemberships: List<CollectionEntryEntity>,
    allComparisons: List<ComparisonEntity>,
): Set<String> = buildSet {
    allEntries.forEach { entry ->
        entry.mergedIntoId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
    }
    allLogs.forEach { add(it.entryId) }
    allGenerators.mapNotNullTo(this) { it.currentEntryId }
    allMemberships
        .filter { it.collectionId != ALBUMS_1001_COLLECTION_ID }
        .forEach { add(it.entryId) }
    allComparisons.forEach { comp ->
        comp.scopeCollectionId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        decodeComparisonOrdering(comp.orderingJson)?.flatten()?.forEach { id ->
            id.trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}

private fun shouldSyncCatalogEntry(
    entry: CatalogEntryEntity,
    ctx: OutgoingEntryFilterContext,
): Boolean {
    if (entry.id in ctx.specIds) return false
    if (entry.isLocalDirty || !entry.mergedIntoId.isNullOrBlank()) return true
    if (entry.id in ctx.syncedFeedIds || entry.id in ctx.syncedItemIds) return false
    if (entry.id in ctx.outgoingReferencedIds) return true
    return if (ctx.inclusiveSince != null) {
        isIsoTimestampAtOrAfter(entry.updatedAt, ctx.inclusiveSince)
    } else {
        entry.id !in ctx.pool1001Ids || entry.id in ctx.referencedEntryIds
    }
}

private fun selectOutgoingMemberships(
    allMemberships: List<CollectionEntryEntity>,
    specsById: Map<String, CollectionSpecEntity>,
    syncedFeedIds: Set<String>,
    inclusiveSinceInstant: Instant?,
): List<CollectionEntryEntity> = allMemberships.filter { mem ->
    val role = specsById[mem.collectionId]?.let { CollectionRole.fromWireValue(it.role) }
    val eligibleCollection =
        mem.collectionId != ALBUMS_1001_COLLECTION_ID &&
            mem.collectionId != STARRED_COLLECTION_ID &&
            mem.collectionId !in syncedFeedIds &&
            role != CollectionRole.FEED &&
            role != CollectionRole.TAG
    eligibleCollection &&
        (mem.isLocalDirty || isIncludedInSyncWindow(mem.addedAt, inclusiveSinceInstant))
}

private fun selectOutgoingGenerators(
    allGenerators: List<CollectionGeneratorEntity>,
    inclusiveSinceInstant: Instant?,
): List<CollectionGeneratorEntity> = allGenerators.filter { gen ->
    val matchesWindow =
        if (inclusiveSinceInstant == null) {
            gen.enabled
        } else {
            isIsoTimestampAtOrAfter(gen.updatedAt, inclusiveSinceInstant)
        }
    gen.collectionId == ALBUMS_1001_COLLECTION_ID && (gen.isLocalDirty || matchesWindow)
}

private fun selectOutgoingLogs(
    allLogs: List<ConsumptionLogEntity>,
    inclusiveSinceInstant: Instant?,
): List<ConsumptionLogEntity> = allLogs.filter { log ->
    log.isLocalDirty ||
        isIncludedInSyncWindow(log.createdAt, inclusiveSinceInstant) ||
        inclusiveSinceInstant != null && isIsoTimestampAtOrAfter(log.ts, inclusiveSinceInstant)
}

private fun selectOutgoingComparisons(
    allComparisons: List<ComparisonEntity>,
    inclusiveSinceInstant: Instant?,
): List<ComparisonEntity> = allComparisons.filter { comp ->
    comp.isLocalDirty ||
        isIncludedInSyncWindow(comp.createdAt, inclusiveSinceInstant) ||
        inclusiveSinceInstant != null && isIsoTimestampAtOrAfter(comp.ts, inclusiveSinceInstant)
}

private fun isIncludedInSyncWindow(iso: String, inclusiveSinceInstant: Instant?): Boolean =
    inclusiveSinceInstant == null || isIsoTimestampAtOrAfter(iso, inclusiveSinceInstant)

@Suppress("LongParameterList")
private fun assembleOutgoingCatalogBatch(
    sentSpecs: List<CollectionSpecEntity>,
    sentEntries: List<CatalogEntryEntity>,
    identsByEntryId: Map<String, List<EntryIdentifierDto>>,
    sentMemberships: List<CollectionEntryEntity>,
    sentGenerators: List<CollectionGeneratorEntity>,
    sentLogs: List<ConsumptionLogEntity>,
    sentComparisons: List<ComparisonEntity>,
    overflowIds: OverflowCatalogIds,
    hasAnyLocalCatalogData: Boolean,
): OutgoingCatalogSyncBatch =
    OutgoingCatalogSyncBatch(
        collections = sentSpecs.map(::toPeerCollectionSpecPayload),
        catalogEntries =
            sentEntries.map { toPeerCatalogEntryPayload(it, identsByEntryId[it.id].orEmpty()) },
        collectionEntries = sentMemberships.map(::toPeerCollectionEntryPayload),
        collectionGenerators = sentGenerators.map(::toPeerGeneratorPayload),
        consumptionLog = sentLogs.map(::toPeerConsumptionLogPayload),
        comparisons = sentComparisons.mapNotNull(::toPeerComparisonPayload),
        sentDirtyCollectionUpdatedAt =
            sentSpecs.filter { it.isLocalDirty }.associate { it.collectionId to it.updatedAt },
        sentDirtyEntryUpdatedAt =
            sentEntries.filter { it.isLocalDirty }.associate { it.id to it.updatedAt },
        sentDirtyMembershipAddedAt =
            sentMemberships
                .filter { it.isLocalDirty }
                .associate { Pair(it.collectionId, it.entryId) to it.addedAt },
        sentDirtyGeneratorUpdatedAt =
            sentGenerators.filter { it.isLocalDirty }.associate { it.collectionId to it.updatedAt },
        sentDirtyLogIds = sentLogs.filter { it.isLocalDirty }.map { it.id }.toSet(),
        sentDirtyComparisonIds = sentComparisons.filter { it.isLocalDirty }.map { it.id }.toSet(),
        overflowCollectionIds = overflowIds.collectionIds,
        overflowEntryIds = overflowIds.entryIds,
        overflowMembershipKeys = overflowIds.membershipKeys,
        overflowLogIds = overflowIds.logIds,
        overflowComparisonIds = overflowIds.comparisonIds,
        hasAnyLocalCatalogData = hasAnyLocalCatalogData,
    )

internal suspend fun reconcileIncomingCatalogSync(
    dao: RocheDao,
    catalogStore: CatalogStore?,
    response: PeerSyncResponseDto,
    sentBatch: OutgoingCatalogSyncBatch,
) {
    if (catalogStore != null) {
        catalogStore.ensureSystemSeeded()
        val needsPool =
            response.collectionGenerators.any {
                it.collectionId == ALBUMS_1001_COLLECTION_ID &&
                    (it.enabled || !it.currentEntryId.isNullOrBlank())
            }
        if (needsPool) {
            catalogStore.ensure1001PoolSeeded()
        }
    }

    val nowIso = response.syncTimestamp.ifBlank { nowUtcIso() }
    dao.executeInTransaction {
        clearSentDirtyCatalogRows(dao, sentBatch)
        markOverflowCatalogRowsDirty(dao, sentBatch)
        applyCollectionEntryTombstones(dao, response)
        val specRedirects = mergeIncomingCollections(dao, response.collections, nowIso)
        val entryRedirects = mergeIncomingCatalogEntries(dao, response.catalogEntries, nowIso)
        val redirectMap = specRedirects + entryRedirects
        mergeIncomingCollectionEntries(dao, response.collectionEntries, redirectMap, nowIso)
        mergeIncomingConsumptionLogs(dao, response.consumptionLog, redirectMap, nowIso)
        mergeIncomingComparisons(dao, response.comparisons, redirectMap, nowIso)
        mergeIncomingGenerators(
            dao,
            catalogStore,
            response.collectionGenerators,
            redirectMap,
            nowIso,
        )
        if (catalogStore != null) {
            val referencedIds = collectReferencedSyncIds(dao, response, redirectMap)
            catalogStore.materializeReferencedFeeds(referencedIds, nowIso)
        }
    }
}

private suspend fun markOverflowCatalogRowsDirty(
    dao: RocheDao,
    sentBatch: OutgoingCatalogSyncBatch,
) {
    sentBatch.overflowCollectionIds.chunked(SQLITE_DELETE_CHUNK_SIZE).forEach {
        dao.markCollectionSpecsDirty(it)
    }
    sentBatch.overflowEntryIds.chunked(SQLITE_DELETE_CHUNK_SIZE).forEach {
        dao.markCatalogEntriesDirty(it)
    }
    for ((colId, entryId) in sentBatch.overflowMembershipKeys) {
        dao.markCollectionEntryDirty(colId, entryId)
    }
    sentBatch.overflowLogIds.chunked(SQLITE_DELETE_CHUNK_SIZE).forEach {
        dao.markConsumptionLogsDirty(it)
    }
    sentBatch.overflowComparisonIds.chunked(SQLITE_DELETE_CHUNK_SIZE).forEach {
        dao.markComparisonsDirty(it)
    }
}

private suspend fun collectReferencedSyncIds(
    dao: RocheDao,
    response: PeerSyncResponseDto,
    redirectMap: Map<String, String>,
): Set<String> = buildSet {
    response.consumptionLog.forEach { add(followMergedInMap(it.entryId.trim(), redirectMap)) }
    response.collectionEntries.forEach { add(followMergedInMap(it.entryId.trim(), redirectMap)) }
    response.comparisons.forEach { comp ->
        comp.ordering.flatten().forEach { add(followMergedInMap(it.trim(), redirectMap)) }
    }
    val incomingIds =
        response.items.mapTo(HashSet()) { it.id } + response.feeds.mapTo(HashSet()) { it.id }
    if (incomingIds.isNotEmpty()) {
        addAll(collectLocalRefsForIncomingIds(dao, incomingIds))
    }
}

private suspend fun collectLocalRefsForIncomingIds(
    dao: RocheDao,
    incomingIds: Set<String>,
): Set<String> = buildSet {
    dao.getAllConsumptionLogs().forEach { if (it.entryId in incomingIds) add(it.entryId) }
    dao.getAllCollectionEntries().forEach { if (it.entryId in incomingIds) add(it.entryId) }
    dao.getAllComparisons().forEach { comp ->
        decodeComparisonOrdering(comp.orderingJson)?.flatten()?.forEach { id ->
            if (id in incomingIds) add(id)
        }
    }
}

private suspend fun clearSentDirtyCatalogRows(
    dao: RocheDao,
    sentBatch: OutgoingCatalogSyncBatch,
) {
    for ((colId, expectedUpdatedAt) in sentBatch.sentDirtyCollectionUpdatedAt) {
        dao.clearSpecDirtyIfSameTs(colId, expectedUpdatedAt)
    }
    for ((entryId, expectedUpdatedAt) in sentBatch.sentDirtyEntryUpdatedAt) {
        dao.clearEntryDirtyIfSameTs(entryId, expectedUpdatedAt)
    }
    for ((membershipKey, expectedAddedAt) in sentBatch.sentDirtyMembershipAddedAt) {
        dao.clearMemberDirtyIfSameTs(
            membershipKey.first,
            membershipKey.second,
            expectedAddedAt,
        )
    }
    for ((genId, expectedUpdatedAt) in sentBatch.sentDirtyGeneratorUpdatedAt) {
        dao.clearGeneratorDirtyIfSameTs(genId, expectedUpdatedAt)
    }
    if (sentBatch.sentDirtyLogIds.isNotEmpty()) {
        dao.clearConsumptionLogsDirty(sentBatch.sentDirtyLogIds.toList())
    }
    if (sentBatch.sentDirtyComparisonIds.isNotEmpty()) {
        dao.clearComparisonsDirty(sentBatch.sentDirtyComparisonIds.toList())
    }
}

private suspend fun applyCollectionEntryTombstones(
    dao: RocheDao,
    response: PeerSyncResponseDto,
) {
    val currentMemberships =
        dao.getAllCollectionEntries().associateBy { it.collectionId to it.entryId }
    for (tomb in response.tombstones) {
        val target = resolveTombstonedMembership(tomb, currentMemberships) ?: continue
        dao.deleteCollectionEntry(target.first, target.second)
    }
}

private fun resolveTombstonedMembership(
    tomb: SyncTombstoneDto,
    currentMemberships: Map<Pair<String, String>, CollectionEntryEntity>,
): Pair<String, String>? {
    if (!tomb.entityType.equals(TOMBSTONE_COLLECTION_ENTRY, ignoreCase = true)) return null
    val parts = tomb.naturalKey.split('\u0000')
    val key = if (parts.size == 2) parts[0] to parts[1] else null
    val existing = key?.let { currentMemberships[it] } ?: return null
    val tombInstant = runCatching { Instant.parse(tomb.deletedAt.trim()) }.getOrNull()
    val addedInstant = runCatching { Instant.parse(existing.addedAt.trim()) }.getOrNull()
    val shouldDelete =
        tombInstant == null || addedInstant == null || !addedInstant.isAfter(tombInstant)
    return if (shouldDelete) key else null
}

private suspend fun mergeIncomingCollections(
    dao: RocheDao,
    incoming: List<PeerCollectionSpecPayload>,
    nowIso: String,
): Map<String, String> {
    if (incoming.isEmpty()) return emptyMap()
    val existingSpecs = dao.getAllCollectionSpecs()
    val existingById = existingSpecs.associateBy { it.collectionId }.toMutableMap()
    val existingBySlug =
        existingSpecs
            .mapNotNull { s ->
                s.slug?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { it to s }
            }
            .toMap()
            .toMutableMap()
    val existingEntriesById = dao.getAllCatalogEntries().associateBy { it.id }
    val upserts = ArrayList<CollectionSpecEntity>()
    val entryUpserts = ArrayList<CatalogEntryEntity>()
    val idents = ArrayList<EntryIdentifierEntity>()
    val specRedirects = HashMap<String, String>()
    for (col in incoming) {
        val merged =
            resolveIncomingCollectionSpec(
                col = col,
                existingById = existingById,
                existingBySlug = existingBySlug,
                specRedirects = specRedirects,
                nowIso = nowIso,
            ) ?: continue
        upserts.add(merged.first)
        existingById[merged.first.collectionId] = merged.first
        merged.first.slug?.let { existingBySlug[it] = merged.first }
        entryUpserts.add(
            mergeCollectionCatalogEntry(
                merged.first,
                existingEntriesById[merged.first.collectionId],
            )
        )
        merged.second?.let(idents::add)
    }
    if (upserts.isNotEmpty()) dao.upsertCollectionSpecs(upserts)
    if (entryUpserts.isNotEmpty()) dao.upsertCatalogEntries(entryUpserts)
    if (idents.isNotEmpty()) dao.insertEntryIdentifiersIgnore(idents)
    return specRedirects
}

private fun mergeCollectionCatalogEntry(
    spec: CollectionSpecEntity,
    existing: CatalogEntryEntity?,
): CatalogEntryEntity =
    existing?.copy(title = spec.title, summary = spec.summary ?: existing.summary)
        ?: buildCollectionCatalogEntry(spec)

private fun resolveIncomingCollectionSpec(
    col: PeerCollectionSpecPayload,
    existingById: Map<String, CollectionSpecEntity>,
    existingBySlug: Map<String, CollectionSpecEntity>,
    specRedirects: MutableMap<String, String>,
    nowIso: String,
): Pair<CollectionSpecEntity, EntryIdentifierEntity?>? {
    val cleanTitle = col.title.trim()
    val role = CollectionRole.fromWireValue(col.role)
    if (cleanTitle.isEmpty() || role == CollectionRole.FEED || role == CollectionRole.TAG) {
        return null
    }
    val slug = col.slug?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val candidateId = resolveIncomingCollectionId(col.collectionId, slug) ?: return null
    val slugOwner = slug?.let { existingBySlug[it] }
    val colId =
        if (slugOwner != null && slugOwner.collectionId != candidateId) {
            if (isProtectedCollectionSpec(slugOwner)) return null
            recordCollectionSpecRedirect(specRedirects, col.collectionId, candidateId, slugOwner)
            slugOwner.collectionId
        } else {
            candidateId
        }
    val existing = existingById[colId]
    if (existing != null && CollectionRole.fromWireValue(existing.role) == CollectionRole.SYSTEM) {
        return null
    }
    val specEntity = buildIncomingSpecEntity(col, colId, cleanTitle, slug, role, existing, nowIso)
    val slugIdent = slug?.let { EntryIdentifierEntity("slug", it, colId) }
    return specEntity to slugIdent
}

private fun isProtectedCollectionSpec(spec: CollectionSpecEntity): Boolean =
    CollectionRole.fromWireValue(spec.role) == CollectionRole.SYSTEM ||
        CollectionMode.fromWireValue(spec.mode) == CollectionMode.DYNAMIC

private fun recordCollectionSpecRedirect(
    specRedirects: MutableMap<String, String>,
    rawId: String,
    candidateId: String,
    slugOwner: CollectionSpecEntity,
) {
    specRedirects[candidateId] = slugOwner.collectionId
    rawId
        .trim()
        .takeIf { it.isNotEmpty() && it != slugOwner.collectionId }
        ?.let {
            specRedirects[it] = slugOwner.collectionId
        }
}

@Suppress("LongParameterList")
private fun buildIncomingSpecEntity(
    col: PeerCollectionSpecPayload,
    colId: String,
    cleanTitle: String,
    slug: String?,
    role: CollectionRole,
    existing: CollectionSpecEntity?,
    nowIso: String,
): CollectionSpecEntity {
    val incomingWins =
        shouldAcceptIncomingLwwUpdate(
            incomingUpdatedAt = col.updatedAt,
            localUpdatedAt = existing?.updatedAt,
            localDirty = existing?.isLocalDirty == true,
        )
    if (existing != null && !incomingWins) {
        return existing.copy(
            summary = existing.summary ?: nonBlankOrFallback(col.summary, null),
            slug = existing.slug ?: slug,
            ruleJson = existing.ruleJson ?: col.ruleJson,
            color = existing.color ?: nonBlankOrFallback(col.color, null),
        )
    }
    return CollectionSpecEntity(
        collectionId = colId,
        title = cleanTitle,
        summary = nonBlankOrFallback(col.summary, existing?.summary),
        slug = slug ?: existing?.slug,
        mode = CollectionMode.fromWireValue(col.mode).wireValue,
        role = role.wireValue,
        ruleJson = col.ruleJson ?: existing?.ruleJson,
        showInLists = col.showInLists,
        color = nonBlankOrFallback(col.color, existing?.color),
        createdAt = col.createdAt ?: existing?.createdAt ?: nowIso,
        updatedAt = col.updatedAt?.takeIf { it.isNotBlank() } ?: existing?.updatedAt ?: nowIso,
        isLocalDirty = existing?.isLocalDirty == true && !incomingWins,
    )
}

private fun shouldAcceptIncomingLwwUpdate(
    incomingUpdatedAt: String?,
    localUpdatedAt: String?,
    localDirty: Boolean,
): Boolean {
    if (localUpdatedAt.isNullOrBlank()) return true
    val localInstant =
        runCatching { Instant.parse(localUpdatedAt.trim()) }.getOrNull() ?: return true
    val incomingInstant =
        incomingUpdatedAt
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            }
    return if (incomingInstant == null) {
        !localDirty
    } else if (localDirty) {
        incomingInstant.isAfter(localInstant)
    } else {
        !incomingInstant.isBefore(localInstant)
    }
}

private fun nonBlankOrFallback(raw: String?, fallback: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

private fun resolveIncomingCollectionId(rawId: String, slug: String?): String? =
    rawId.trim().takeIf { it.isNotEmpty() }
        ?: slug?.let {
            if (it.startsWith("want:")) {
                systemCollectionUuid(it)
            } else {
                systemCollectionUuid("custom:$it")
            }
        }

private suspend fun mergeIncomingCatalogEntries(
    dao: RocheDao,
    incoming: List<PeerCatalogEntryPayload>,
    nowIso: String,
): Map<String, String> {
    val existingById = dao.getAllCatalogEntries().associateBy { it.id }.toMutableMap()
    if (incoming.isNotEmpty()) {
        val upserts = ArrayList<CatalogEntryEntity>(incoming.size)
        val idents = ArrayList<EntryIdentifierEntity>()
        val merges = ArrayList<Pair<String, String>>()

        for (item in incoming) {
            val merged =
                buildMergedCatalogEntry(item, existingById[item.id.trim()], nowIso) ?: continue
            upserts.add(merged.entity)
            existingById[merged.entity.id] = merged.entity
            idents.addAll(merged.identifiers)
            merged.mergeRedirect?.let(merges::add)
        }
        if (upserts.isNotEmpty()) dao.upsertCatalogEntries(upserts)
        if (idents.isNotEmpty()) dao.insertEntryIdentifiersIgnore(idents)
        val redirectMap = buildMergeRedirectMap(existingById.values)
        for ((dupId, rawCanonId) in merges) {
            val canonId = followMergedInMap(rawCanonId, redirectMap)
            if (canonId != dupId) {
                foldMergedDuplicateInDao(dao, dupId, canonId, nowIso)
            }
        }
        return redirectMap
    }
    return buildMergeRedirectMap(existingById.values)
}

private fun buildMergeRedirectMap(entries: Collection<CatalogEntryEntity>): Map<String, String> =
    buildMap {
        for (entry in entries) {
            val target = entry.mergedIntoId?.trim()?.takeIf { it.isNotEmpty() && it != entry.id }
            if (target != null) {
                put(entry.id, target)
            }
        }
    }

internal fun followMergedInMap(id: String, redirects: Map<String, String>): String {
    var current = id.trim()
    if (current.isEmpty() || redirects.isEmpty()) return current
    val visited = HashSet<String>(4)
    while (visited.add(current)) {
        val next = redirects[current] ?: break
        current = next
    }
    return current
}

private suspend fun foldMergedDuplicateInDao(
    dao: RocheDao,
    dupId: String,
    canonId: String,
    nowIso: String,
) {
    dao.repointIdentifiersToEntry(dupId, canonId)
    repointMergedMembershipsInDao(dao, dupId, canonId, nowIso)
    dao.repointConsumptionLogsToEntry(dupId, canonId)
    repointMergedComparisonsInDao(dao, dupId, canonId)
    val gen = dao.getCollectionGenerator(ALBUMS_1001_COLLECTION_ID)
    if (gen?.currentEntryId == dupId) {
        dao.upsertCollectionGenerator(gen.copy(currentEntryId = canonId, updatedAt = nowIso))
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
        rewriteSingleMergedComparison(dao, comp, dupId, canonId)
    }
}

private suspend fun rewriteSingleMergedComparison(
    dao: RocheDao,
    comp: ComparisonEntity,
    dupId: String,
    canonId: String,
) {
    val parsed = decodeComparisonOrdering(comp.orderingJson) ?: return
    val newScope = if (comp.scopeCollectionId == dupId) canonId else comp.scopeCollectionId
    val rewritten = rewriteOrderingAfterMerge(parsed, dupId, canonId)
    dao.deleteComparisonById(comp.id)
    if (rewritten.sumOf { it.size } < EpRankingEngine.MIN_K) return
    val tsMillis = runCatching { Instant.parse(comp.ts.trim()).toEpochMilli() }.getOrDefault(0L)
    val newId = deterministicComparisonId(newScope, tsMillis, rewritten)
    dao.insertComparisonIgnore(
        comp.copy(
            id = newId,
            scopeCollectionId = newScope,
            orderingJson = canonicalOrderingJson(rewritten),
        )
    )
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

private data class MergedCatalogEntryResult(
    val entity: CatalogEntryEntity,
    val identifiers: List<EntryIdentifierEntity>,
    val mergeRedirect: Pair<String, String>?,
)

private fun buildMergedCatalogEntry(
    item: PeerCatalogEntryPayload,
    existing: CatalogEntryEntity?,
    nowIso: String,
): MergedCatalogEntryResult? {
    val id = item.id.trim()
    val title = item.title.trim()
    if (id.isEmpty() || title.isEmpty()) return null
    val resolvedKind = EntryKind.fromWireValue(item.kind)
    val mergedInto = item.mergedIntoId?.trim()?.takeIf { it.isNotEmpty() }
    val entity =
        buildMergedCatalogEntity(item, id, title, resolvedKind, mergedInto, existing, nowIso)
    val targetIdentOwner = mergedInto ?: id
    val identifiers =
        item.identifiers.mapNotNull { ident ->
            val s = normalizeIdentifierScheme(ident.scheme)
            val v = normalizeIdentifierValue(resolvedKind, s, ident.value)
            if (s.isNotEmpty() && v.isNotEmpty()) {
                EntryIdentifierEntity(s, v, targetIdentOwner)
            } else {
                null
            }
        }
    val redirect = if (mergedInto != null && mergedInto != id) id to mergedInto else null
    return MergedCatalogEntryResult(entity, identifiers, redirect)
}

@Suppress("LongParameterList")
private fun buildMergedCatalogEntity(
    item: PeerCatalogEntryPayload,
    id: String,
    title: String,
    resolvedKind: EntryKind,
    mergedInto: String?,
    existing: CatalogEntryEntity?,
    nowIso: String,
): CatalogEntryEntity {
    val incomingWins =
        shouldAcceptIncomingLwwUpdate(
            incomingUpdatedAt = item.updatedAt,
            localUpdatedAt = existing?.updatedAt,
            localDirty = existing?.isLocalDirty == true,
        )
    val metadataJson = item.metadata?.toString()?.takeIf { it.isNotBlank() && it != "{}" }
    return if (existing != null && !incomingWins) {
        preserveLocalCatalogEntity(existing, item, metadataJson, mergedInto)
    } else {
        overwriteCatalogEntity(
            item = item,
            id = id,
            title = title,
            resolvedKind = resolvedKind,
            metadataJson = metadataJson,
            mergedInto = mergedInto,
            existing = existing,
            incomingWins = incomingWins,
            nowIso = nowIso,
        )
    }
}

private fun preserveLocalCatalogEntity(
    existing: CatalogEntryEntity,
    item: PeerCatalogEntryPayload,
    metadataJson: String?,
    mergedInto: String?,
): CatalogEntryEntity =
    existing.copy(
        creatorsJson =
            when {
                existing.creatorsJson != "[]" -> existing.creatorsJson
                item.creators.isNotEmpty() -> encodeCreatorsJson(item.creators)
                else -> "[]"
            },
        url = existing.url ?: item.url.nullIfBlankTrimmed(),
        publishedAt = existing.publishedAt ?: item.publishedAt.nullIfBlankTrimmed(),
        releaseYear = existing.releaseYear ?: item.releaseYear,
        summary = existing.summary ?: item.summary.nullIfBlankTrimmed(),
        thumbnailUrl = existing.thumbnailUrl ?: sanitizeHttpUrl(item.thumbnailUrl),
        metadataJson =
            if (existing.metadataJson != "{}") existing.metadataJson else metadataJson ?: "{}",
        mergedIntoId = mergedInto ?: existing.mergedIntoId,
    )

@Suppress("LongParameterList")
private fun overwriteCatalogEntity(
    item: PeerCatalogEntryPayload,
    id: String,
    title: String,
    resolvedKind: EntryKind,
    metadataJson: String?,
    mergedInto: String?,
    existing: CatalogEntryEntity?,
    incomingWins: Boolean,
    nowIso: String,
): CatalogEntryEntity =
    CatalogEntryEntity(
        id = id,
        kind = resolvedKind.wireValue,
        title = title,
        creatorsJson = resolveOverwrittenCreatorsJson(item, existing),
        url = preferNonBlank(item.url, existing?.url),
        publishedAt = preferNonBlank(item.publishedAt, existing?.publishedAt),
        releaseYear = item.releaseYear ?: existing?.releaseYear,
        summary = preferNonBlank(item.summary, existing?.summary),
        thumbnailUrl = sanitizeHttpUrl(item.thumbnailUrl) ?: existing?.thumbnailUrl,
        metadataJson = metadataJson ?: existing?.metadataJson ?: "{}",
        mergedIntoId = mergedInto ?: existing?.mergedIntoId,
        createdAt = preferNonBlank(item.createdAt, existing?.createdAt) ?: nowIso,
        updatedAt = preferNonBlank(item.updatedAt, existing?.updatedAt) ?: nowIso,
        isLocalDirty = existing?.isLocalDirty == true && !incomingWins,
    )

private fun resolveOverwrittenCreatorsJson(
    item: PeerCatalogEntryPayload,
    existing: CatalogEntryEntity?,
): String =
    if (item.creators.isNotEmpty()) {
        encodeCreatorsJson(item.creators)
    } else {
        existing?.creatorsJson ?: "[]"
    }

private fun preferNonBlank(primary: String?, fallback: String?): String? =
    primary.nullIfBlankTrimmed() ?: fallback

private fun String?.nullIfBlankTrimmed(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private suspend fun mergeIncomingCollectionEntries(
    dao: RocheDao,
    incoming: List<PeerCollectionEntryPayload>,
    redirects: Map<String, String>,
    nowIso: String,
) {
    if (incoming.isEmpty()) return
    val existingMemberships =
        dao.getAllCollectionEntries().associateBy { it.collectionId to it.entryId }
    val childrenByParent = HashMap<String, MutableSet<String>>()
    for ((colId, entryId) in existingMemberships.keys) {
        childrenByParent.getOrPut(colId) { HashSet() }.add(entryId)
    }
    val tombstones =
        dao.getAllTombstones()
            .filter { it.entityType.equals(TOMBSTONE_COLLECTION_ENTRY, ignoreCase = true) }
            .associateBy { it.naturalKey }
    val ctx =
        IncomingMembershipContext(redirects, existingMemberships, tombstones, childrenByParent)
    val upserts = ArrayList<CollectionEntryEntity>()
    for (ce in incoming) {
        val resolved = resolveIncomingMembership(ce, ctx, nowIso) ?: continue
        resolved.second?.let { natKey -> dao.deleteTombstone(TOMBSTONE_COLLECTION_ENTRY, natKey) }
        childrenByParent
            .getOrPut(resolved.first.collectionId) { HashSet() }
            .add(resolved.first.entryId)
        upserts.add(resolved.first)
    }
    if (upserts.isNotEmpty()) dao.upsertCollectionEntries(upserts)
}

private data class IncomingMembershipContext(
    val redirects: Map<String, String>,
    val existingMemberships: Map<Pair<String, String>, CollectionEntryEntity>,
    val tombstones: Map<String, com.grayvines.roche.data.local.TombstoneEntity>,
    val childrenByParent: Map<String, Set<String>>,
)

private fun resolveIncomingMembership(
    ce: PeerCollectionEntryPayload,
    ctx: IncomingMembershipContext,
    nowIso: String,
): Pair<CollectionEntryEntity, String?>? {
    val colId = followMergedInMap(ce.collectionId, ctx.redirects)
    val entryId = followMergedInMap(ce.entryId, ctx.redirects)
    if (colId.isEmpty() || entryId.isEmpty() || colId == entryId) return null
    val natKey = collectionEntryNaturalKey(colId, entryId)
    val tomb = ctx.tombstones[natKey]
    if (isTombstoneSupersedingAdd(tomb, ce.addedAt)) return null
    val existing = ctx.existingMemberships[colId to entryId]
    val keepLocalDirty =
        existing?.isLocalDirty == true &&
            !shouldAcceptIncomingLwwUpdate(ce.addedAt, existing.addedAt, localDirty = true)
    if (keepLocalDirty || wouldIntroduceCycleInGraph(ctx.childrenByParent, colId, entryId)) {
        return null
    }
    val entity =
        CollectionEntryEntity(
            collectionId = colId,
            entryId = entryId,
            position = ce.position,
            addedAt = ce.addedAt.ifBlank { nowIso },
            isLocalDirty = false,
        )
    return entity to tomb?.let { natKey }
}

private fun isTombstoneSupersedingAdd(
    tomb: com.grayvines.roche.data.local.TombstoneEntity?,
    addedAtRaw: String,
): Boolean {
    if (tomb == null) return false
    val tombTs = runCatching { Instant.parse(tomb.deletedAt.trim()) }.getOrNull() ?: return false
    val addedTs = runCatching { Instant.parse(addedAtRaw.trim()) }.getOrNull() ?: return false
    return !addedTs.isAfter(tombTs)
}

private data class IncomingLogsPlan(
    val toInsert: List<ConsumptionLogEntity>,
    val maxConsumedTsByEntry: Map<String, String>,
    val replacedAutoLogIds: Set<String>,
)

private suspend fun mergeIncomingConsumptionLogs(
    dao: RocheDao,
    incoming: List<PeerConsumptionLogPayload>,
    redirects: Map<String, String>,
    nowIso: String,
) {
    if (incoming.isEmpty()) return
    val existingConsumedByEntry =
        dao.getAllConsumptionLogs()
            .filter { ConsumptionStatus.fromWireValue(it.status) == ConsumptionStatus.CONSUMED }
            .groupBy { it.entryId }
    val plan = planIncomingConsumptionLogs(incoming, redirects, existingConsumedByEntry, nowIso)
    plan.replacedAutoLogIds.forEach { dao.deleteConsumptionLogById(it) }
    if (plan.toInsert.isNotEmpty()) {
        dao.insertConsumptionLogsIgnore(plan.toInsert)
    }
    if (plan.maxConsumedTsByEntry.isNotEmpty()) {
        removePromotedWantEntries(dao, plan.maxConsumedTsByEntry)
    }
}

private fun planIncomingConsumptionLogs(
    incoming: List<PeerConsumptionLogPayload>,
    redirects: Map<String, String>,
    existingConsumedByEntry: Map<String, List<ConsumptionLogEntity>>,
    nowIso: String,
): IncomingLogsPlan {
    val acc = IncomingLogsAccumulator(existingConsumedByEntry)
    for (log in incoming) {
        val entryId = followMergedInMap(log.entryId, redirects)
        if (entryId.isNotEmpty()) {
            acc.accept(log, entryId, nowIso)
        }
    }
    return acc.toPlan()
}

private class IncomingLogsAccumulator(
    private val existingConsumedByEntry: Map<String, List<ConsumptionLogEntity>>
) {
    private val consumedInBatch = HashSet<String>(existingConsumedByEntry.keys)
    private val toInsert = ArrayList<ConsumptionLogEntity>()
    private val maxConsumedTsByEntry = HashMap<String, String>()
    private val replacedAutoIds = HashSet<String>()

    fun accept(log: PeerConsumptionLogPayload, entryId: String, nowIso: String) {
        val status = ConsumptionStatus.fromWireValue(log.status)
        val id = resolveIncomingLogId(log, entryId, status)
        val autoLogId = deterministicAutoLogId(entryId)
        val isConsumed = status == ConsumptionStatus.CONSUMED
        val effectiveTs = log.ts.ifBlank { nowIso }
        if (isConsumed && id == autoLogId && entryId in consumedInBatch) {
            recordConsumedTs(entryId, effectiveTs)
            return
        }
        if (isConsumed && id != autoLogId) {
            findReplaceableAutoLogId(existingConsumedByEntry[entryId], autoLogId)?.let {
                replacedAutoIds.add(it)
            }
        }
        toInsert.add(toConsumptionLogEntity(log, id, entryId, status, nowIso))
        if (isConsumed) {
            consumedInBatch.add(entryId)
            recordConsumedTs(entryId, effectiveTs)
        }
    }

    private fun recordConsumedTs(entryId: String, incomingTs: String) {
        var best = maxConsumedTsByEntry[entryId] ?: incomingTs
        best = selectNewerIso(best, incomingTs)
        for (existing in existingConsumedByEntry[entryId].orEmpty()) {
            best = selectNewerIso(best, existing.ts)
        }
        maxConsumedTsByEntry[entryId] = best
    }

    fun toPlan(): IncomingLogsPlan =
        IncomingLogsPlan(toInsert, maxConsumedTsByEntry, replacedAutoIds)
}

internal fun selectNewerIso(first: String, second: String): String {
    val firstInst = runCatching { Instant.parse(first.trim()) }.getOrNull() ?: return second
    val secondInst = runCatching { Instant.parse(second.trim()) }.getOrNull() ?: return first
    return if (secondInst.isAfter(firstInst)) second else first
}

internal fun resolveIncomingLogId(
    log: PeerConsumptionLogPayload,
    entryId: String,
    status: ConsumptionStatus,
): String {
    val tsMillis = runCatching { Instant.parse(log.ts.trim()).toEpochMilli() }.getOrDefault(0L)
    return log.id.trim().takeIf { it.isNotEmpty() }
        ?: deterministicConsumptionLogId(entryId, tsMillis, status.wireValue)
}

internal fun findReplaceableAutoLogId(
    existingForEntry: List<ConsumptionLogEntity>?,
    autoLogId: String,
): String? =
    existingForEntry?.firstOrNull { it.id == autoLogId && it.notes == null && !it.isLocalDirty }?.id

internal fun toConsumptionLogEntity(
    log: PeerConsumptionLogPayload,
    id: String,
    entryId: String,
    status: ConsumptionStatus,
    nowIso: String,
): ConsumptionLogEntity =
    ConsumptionLogEntity(
        id = id,
        entryId = entryId,
        ts = log.ts.ifBlank { nowIso },
        status = status.wireValue,
        notes = log.notes.nullIfBlankTrimmed(),
        createdAt = log.createdAt ?: nowIso,
        isLocalDirty = false,
    )

private suspend fun removePromotedWantEntries(
    dao: RocheDao,
    maxConsumedTsByEntry: Map<String, String>,
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
        val consumedTs =
            if (mem.collectionId in wantIds) maxConsumedTsByEntry[mem.entryId] else null
        if (consumedTs != null && !isIsoStrictlyAfter(mem.addedAt, consumedTs)) {
            dao.deleteCollectionEntry(mem.collectionId, mem.entryId)
        }
    }
}

private fun isIsoStrictlyAfter(candidateIso: String, referenceIso: String): Boolean {
    val cand = runCatching { Instant.parse(candidateIso.trim()) }.getOrNull() ?: return false
    val ref = runCatching { Instant.parse(referenceIso.trim()) }.getOrNull() ?: return false
    return cand.isAfter(ref)
}

private suspend fun mergeIncomingComparisons(
    dao: RocheDao,
    incoming: List<PeerComparisonPayload>,
    redirects: Map<String, String>,
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
        toInsert.add(
            ComparisonEntity(
                id = id,
                ts = comp.ts.ifBlank { nowIso },
                scopeCollectionId = scope,
                orderingJson = canonicalOrderingJson(canonicalOrdering),
                createdAt = comp.createdAt ?: nowIso,
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

private suspend fun mergeIncomingGenerators(
    dao: RocheDao,
    catalogStore: CatalogStore?,
    incoming: List<PeerCollectionGeneratorPayload>,
    redirects: Map<String, String>,
    nowIso: String,
) {
    val remoteGen = incoming.firstOrNull { it.collectionId == ALBUMS_1001_COLLECTION_ID }
    val localGen = dao.getCollectionGenerator(ALBUMS_1001_COLLECTION_ID)
    if (remoteGen == null && localGen == null) return

    var base = selectBaseGeneratorEntity(localGen, remoteGen, nowIso)
    val activeId =
        base.currentEntryId?.takeIf { it.isNotBlank() }?.let { followMergedInMap(it, redirects) }
    if (activeId != base.currentEntryId) {
        base = base.copy(currentEntryId = activeId)
    }
    if (!activeId.isNullOrBlank() && dao.countTerminalLogsForEntry(activeId) > 0) {
        base =
            base.copy(
                currentEntryId = null,
                updatedAt = nowIso,
                isLocalDirty = true,
            )
    }
    if (base.enabled && base.currentEntryId.isNullOrBlank() && catalogStore != null) {
        catalogStore.ensure1001PoolSeeded()
        val drawn = catalogStore.drawRandomRemaining1001Id()
        base =
            base.copy(
                currentEntryId = drawn,
                drawnAt = drawn?.let { nowIso },
                updatedAt = nowIso,
                isLocalDirty = true,
            )
    }
    dao.upsertCollectionGenerator(base)
}

private fun selectBaseGeneratorEntity(
    localGen: CollectionGeneratorEntity?,
    remoteGen: PeerCollectionGeneratorPayload?,
    nowIso: String,
): CollectionGeneratorEntity {
    if (remoteGen != null && shouldPreferRemoteGenerator(localGen, remoteGen)) {
        return CollectionGeneratorEntity(
            collectionId = ALBUMS_1001_COLLECTION_ID,
            enabled = remoteGen.enabled,
            currentEntryId = remoteGen.currentEntryId?.trim()?.takeIf { it.isNotEmpty() },
            drawnAt = remoteGen.drawnAt,
            updatedAt = remoteGen.updatedAt.ifBlank { nowIso },
            isLocalDirty = false,
        )
    }
    return localGen
        ?: CollectionGeneratorEntity(
            collectionId = ALBUMS_1001_COLLECTION_ID,
            enabled = false,
            currentEntryId = null,
            drawnAt = null,
            updatedAt = nowIso,
            isLocalDirty = false,
        )
}

private fun shouldPreferRemoteGenerator(
    localGen: CollectionGeneratorEntity?,
    remoteGen: PeerCollectionGeneratorPayload,
): Boolean {
    if (localGen == null) return true
    if (isRemoteGeneratorNewer(localGen.updatedAt, remoteGen.updatedAt)) return true
    return localGen.updatedAt == remoteGen.updatedAt &&
        localGen.currentEntryId.isNullOrBlank() &&
        !remoteGen.currentEntryId.isNullOrBlank()
}

private fun isRemoteGeneratorNewer(localIso: String, remoteIso: String): Boolean {
    val loc = runCatching { Instant.parse(localIso.trim()) }.getOrNull() ?: return true
    val rem = runCatching { Instant.parse(remoteIso.trim()) }.getOrNull() ?: return false
    return rem.isAfter(loc)
}

private fun toPeerCollectionSpecPayload(spec: CollectionSpecEntity): PeerCollectionSpecPayload =
    PeerCollectionSpecPayload(
        collectionId = spec.collectionId,
        title = spec.title,
        summary = spec.summary,
        slug = spec.slug,
        mode = spec.mode,
        role = spec.role,
        ruleJson = spec.ruleJson,
        showInLists = spec.showInLists,
        color = spec.color,
        createdAt = spec.createdAt,
        updatedAt = spec.updatedAt,
    )

private fun toPeerCatalogEntryPayload(
    entry: CatalogEntryEntity,
    identifiers: List<EntryIdentifierDto>,
): PeerCatalogEntryPayload {
    val metadataElement =
        entry.metadataJson
            .takeIf { it.isNotBlank() && it != "{}" }
            ?.let { raw ->
                runCatching { rocheJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            }
            ?.takeIf { it.isNotEmpty() }
    val safeIdentifiers = identifiers.filter { ident ->
        val scheme = ident.scheme.lowercase()
        if (scheme == "url" || scheme == "feed_url") {
            isSyncablePublicUrl(ident.value)
        } else {
            true
        }
    }
    return PeerCatalogEntryPayload(
        id = entry.id,
        kind = entry.kind,
        title = entry.title,
        creators = decodeCreatorsJson(entry.creatorsJson),
        url = entry.url?.takeIf { isSyncablePublicUrl(it) },
        publishedAt = entry.publishedAt,
        releaseYear = entry.releaseYear,
        summary = entry.summary,
        thumbnailUrl = entry.thumbnailUrl?.takeIf { isSyncablePublicUrl(it) },
        metadata = metadataElement,
        identifiers = safeIdentifiers,
        mergedIntoId = entry.mergedIntoId,
        createdAt = entry.createdAt,
        updatedAt = entry.updatedAt,
    )
}

private fun toPeerCollectionEntryPayload(entry: CollectionEntryEntity): PeerCollectionEntryPayload =
    PeerCollectionEntryPayload(
        collectionId = entry.collectionId,
        entryId = entry.entryId,
        position = entry.position,
        addedAt = entry.addedAt,
    )

private fun toPeerGeneratorPayload(gen: CollectionGeneratorEntity): PeerCollectionGeneratorPayload =
    PeerCollectionGeneratorPayload(
        collectionId = gen.collectionId,
        enabled = gen.enabled,
        currentEntryId = gen.currentEntryId,
        drawnAt = gen.drawnAt,
        updatedAt = gen.updatedAt,
    )

private fun toPeerConsumptionLogPayload(log: ConsumptionLogEntity): PeerConsumptionLogPayload =
    PeerConsumptionLogPayload(
        id = log.id,
        entryId = log.entryId,
        ts = log.ts,
        status = log.status,
        notes = log.notes,
        createdAt = log.createdAt,
    )

private fun toPeerComparisonPayload(comp: ComparisonEntity): PeerComparisonPayload? {
    val ordering = decodeComparisonOrdering(comp.orderingJson) ?: return null
    return PeerComparisonPayload(
        id = comp.id,
        ts = comp.ts,
        scopeCollectionId = comp.scopeCollectionId,
        ordering = ordering,
        createdAt = comp.createdAt,
    )
}
