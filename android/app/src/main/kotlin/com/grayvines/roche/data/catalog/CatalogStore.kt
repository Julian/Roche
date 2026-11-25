@file:Suppress("TooManyFunctions", "LargeClass")

package com.grayvines.roche.data.catalog

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_ENTRY
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_GENERATOR
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COMPARISON
import com.grayvines.roche.core.TOMBSTONE_ENTITY_CONSUMPTION_LOG
import com.grayvines.roche.core.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.canonicalOrderingJson
import com.grayvines.roche.core.collectionEntryNaturalKey
import com.grayvines.roche.core.deriveCollectionIdentity
import com.grayvines.roche.core.deterministicComparisonId
import com.grayvines.roche.core.formatUtcMillisIso
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.data.local.CatalogStampRow
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.DbWriteLock
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.deleteCollectionGeneratorWithTombstone
import com.grayvines.roche.data.local.immutableReason
import com.grayvines.roche.data.local.nextLocalHlc
import com.grayvines.roche.ranking.EpRankingEngine
import com.grayvines.roche.ranking.RankClusterCandidate
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CatalogStore(
    private val dao: RocheDao,
    private val random: Random = Random.Default,
    onLocalMutation: () -> Unit = {},
    private val localPeerIdProvider: suspend () -> String = { DEFAULT_LOCAL_HLC_NODE },
    internal val writeLock: DbWriteLock = DbWriteLock(),
) {
    private val localMutationCallback = AtomicReference(onLocalMutation)
    private val seeder =
        CatalogSeeder(
            dao = dao,
            writeLock = writeLock,
            localPeerIdProvider = ::resolveLocalPeerId,
        )
    private val mutationMutex: DbWriteLock = writeLock
    private val snapshotCacheMutex = Mutex()
    internal val epScoreCache = EpScoreCache()
    private var cachedState: CachedCatalogState? = null

    internal val epCacheStats: EpScoreCacheStats
        get() = epScoreCache.stats

    @Volatile
    internal var catalogSnapshotBuilds: Int = 0
        private set

    @Volatile
    internal var catalogTableReloads: Int = 0
        private set

    @Volatile
    internal var catalogSnapshotCacheHits: Int = 0
        private set

    suspend fun getSnapshot(): CatalogSnapshot = getOrBuildCatalogSnapshot()

    suspend fun loadDiaryPage(
        status: ConsumptionStatus? = null,
        year: String? = null,
        limit: Int = DEFAULT_DIARY_PAGE_SIZE,
        offset: Int = 0,
        globalTiers: GlobalTierIndex? = null,
    ): DiaryPageResult {
        ensureSystemSeeded()
        val tiers = globalTiers ?: getOrBuildCatalogSnapshot().globalTiers
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)
        var result = DiaryPageResult()
        dao.executeInTransaction {
            val yearCounts = dao.getDiaryYearCounts()
            val availableYears = yearCounts.map { it.year }
            val effectiveYear = year?.takeIf { it in availableYears }
            val (minTs, maxTs) = resolveDiaryYearBounds(effectiveYear)
            val monthRows =
                if (status == null) {
                    dao.getDiaryMonthCounts(minTs, maxTs)
                } else {
                    dao.getDiaryMonthCountsByStatus(status.wireValue, minTs, maxTs)
                }
            val totalFiltered = monthRows.sumOf { it.count }
            val rows =
                if (status == null) {
                    dao.getDiaryLogPage(minTs, maxTs, safeLimit, safeOffset)
                } else {
                    dao.getDiaryLogPageByStatus(
                        status.wireValue,
                        minTs,
                        maxTs,
                        safeLimit,
                        safeOffset,
                    )
                }
            result =
                DiaryPageResult(
                    entries = rows.map { toDiaryEntryItem(it, tiers) },
                    availableYears = availableYears,
                    monthCounts = monthRows.associate { it.monthKey to it.count },
                    totalFilteredCount = totalFiltered,
                    hasAnyEntries = yearCounts.isNotEmpty() || totalFiltered > 0,
                    effectiveYear = effectiveYear,
                    hasMore = safeOffset + rows.size < totalFiltered,
                )
        }
        return result
    }

    suspend fun loadEntryDiaryLogs(
        entryId: String,
        limit: Int = DEFAULT_ENTRY_DETAIL_LOGS_LIMIT,
        offset: Int = 0,
        globalTiers: GlobalTierIndex? = null,
    ): EntryDiaryLogsResult {
        ensureSystemSeeded()
        val resolvedId = followMergedId(entryId) ?: entryId.trim()
        if (resolvedId.isEmpty()) return EntryDiaryLogsResult(entryId = resolvedId)
        val tiers = globalTiers ?: getOrBuildCatalogSnapshot().globalTiers
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)
        var result = EntryDiaryLogsResult(entryId = resolvedId)
        dao.executeInTransaction {
            val totalCount = dao.countDiaryLogsForEntry(resolvedId)
            val rows = dao.getDiaryLogsForEntry(resolvedId, safeLimit, safeOffset)
            result =
                EntryDiaryLogsResult(
                    entryId = resolvedId,
                    logs = rows.map { toDiaryEntryItem(it, tiers) },
                    totalCount = totalCount,
                    hasMore = safeOffset + rows.size < totalCount,
                )
        }
        return result
    }

    suspend fun loadCollectionPage(
        collectionIdOrSlug: String,
        tierFilter: Char? = null,
        limit: Int = DEFAULT_COLLECTION_PAGE_SIZE,
        offset: Int = 0,
    ): CatalogCollectionPageResult {
        ensureSystemSeeded()
        val spec =
            resolveCollectionSpec(collectionIdOrSlug)
                ?: return CatalogCollectionPageResult(collectionId = collectionIdOrSlug)
        var result = CatalogCollectionPageResult(collectionId = spec.collectionId)
        dao.executeInTransaction {
            result =
                loadCollectionPageFromDao(
                    dao = dao,
                    spec = spec,
                    epScoreCache = epScoreCache,
                    tierFilter = tierFilter,
                    limit = limit,
                    offset = offset,
                )
        }
        return result
    }

    suspend fun loadCollectionEntries(collectionIdOrSlug: String): List<RankedCatalogEntry> {
        ensureSystemSeeded()
        val spec = resolveCollectionSpec(collectionIdOrSlug) ?: return emptyList()
        var entries: List<RankedCatalogEntry> = emptyList()
        dao.executeInTransaction {
            entries = loadSmallCollectionRanked(dao, spec, epScoreCache)
        }
        return entries
    }

    suspend fun loadEntryDetail(
        entryId: String,
        previousEntryId: String? = null,
        globalTiers: GlobalTierIndex? = null,
    ): CatalogEntryDetailResult? {
        ensureSystemSeeded()
        val tiers = globalTiers ?: getOrBuildCatalogSnapshot().globalTiers
        var result: CatalogEntryDetailResult? = null
        dao.executeInTransaction {
            result =
                loadEntryDetailFromDao(
                    dao = dao,
                    entryId = entryId,
                    previousEntryId = previousEntryId,
                    globalTiers = tiers,
                    epScoreCache = epScoreCache,
                )
        }
        return result
    }

    suspend fun loadCatalogEntry(entryId: String): CatalogEntryItem? {
        ensureSystemSeeded()
        return loadCatalogEntryFromDao(dao, entryId)
    }

    suspend fun searchCatalogEntries(
        rawQuery: String,
        includeWhenBlank: Boolean = false,
        limit: Int = DEFAULT_COLLECTION_PAGE_SIZE,
        globalTiers: GlobalTierIndex? = null,
    ): CatalogSearchPageResult {
        ensureSystemSeeded()
        val tiers = globalTiers ?: getOrBuildCatalogSnapshot().globalTiers
        return searchCatalogEntriesFromDao(
            dao = dao,
            rawQuery = rawQuery,
            includeWhenBlank = includeWhenBlank,
            limit = limit,
            globalTiers = tiers,
        )
    }

    suspend fun isEntryStarred(entryId: String): Boolean {
        ensureSystemSeeded()
        val resolved = followMergedId(entryId) ?: entryId.trim()
        return resolved.isNotEmpty() &&
            dao.hasCollectionMembership(STARRED_COLLECTION_ID, resolved) > 0
    }

    suspend fun findWantCollectionForEntry(entryId: String): String? =
        findWantCollectionsForEntry(entryId).firstOrNull()

    suspend fun findWantCollectionsForEntry(entryId: String): List<String> {
        ensureSystemSeeded()
        val resolved = followMergedId(entryId) ?: entryId.trim()
        val memberCols =
            if (resolved.isEmpty()) {
                emptyList()
            } else {
                dao.getCollectionEntriesForEntry(resolved).map { it.collectionId }.distinct()
            }
        if (memberCols.isEmpty()) return emptyList()
        val specsById = dao.getAllCollectionSpecs().associateBy { it.collectionId }
        return memberCols.filter { colId ->
            colId == WANT_TO_READ_COLLECTION_ID ||
                colId == UP_NEXT_COLLECTION_ID ||
                CollectionRole.fromWireValue(specsById[colId]?.role) == CollectionRole.WANT
        }
    }

    suspend fun resolveProvisionalFocusRank(
        collectionIdOrSlug: String,
        focusEntryId: String,
    ): ProvisionalFocusRank? {
        val settlement =
            resolveEntrySettlement(
                entryId = focusEntryId,
                preferredCollectionIdOrSlug = collectionIdOrSlug,
                includeNeighbors = false,
            ) ?: return null
        return ProvisionalFocusRank(
            rank = settlement.rank,
            totalCount = settlement.totalCount,
            tier = settlement.tier,
        )
    }

    internal suspend fun resolveLocalPeerId(): String =
        localPeerIdProvider().ifBlank { DEFAULT_LOCAL_HLC_NODE }

    suspend fun ensureSystemSeeded() = seeder.ensureSystemSeeded()

    suspend fun setCollectionGeneratorEnabled(
        collectionIdOrSlug: String,
        enabled: Boolean,
    ): CollectionDrawSnapshot? {
        val result = mutationMutex.withLock {
            ensureSystemSeeded()
            val spec =
                resolveEligibleGeneratorSpec(collectionIdOrSlug)
                    ?: error("Collection '$collectionIdOrSlug' cannot use a generator")
            val nowIso = nowUtcIso()
            var drawnActiveId: String? = null
            dao.executeInTransaction {
                val current = dao.getCollectionGenerator(spec.collectionId)
                var activeId =
                    current?.currentEntryId?.takeIf { it.isNotBlank() }?.let { followMergedId(it) }
                if (activeId != null && dao.countTerminalLogsForEntry(activeId) > 0) {
                    activeId = null
                }
                val drawnAt =
                    if (enabled && activeId == null) {
                        activeId = drawRandomRemainingId(spec.collectionId)
                        activeId?.let { nowIso }
                    } else {
                        current?.drawnAt
                    }
                drawnActiveId = activeId
                val nodeId = resolveLocalPeerId()
                val hlc = nextLocalHlc(dao, nodeId)
                dao.deleteTombstone(TOMBSTONE_ENTITY_COLLECTION_GENERATOR, spec.collectionId)
                dao.upsertCollectionGenerator(
                    CollectionGeneratorEntity(
                        collectionId = spec.collectionId,
                        enabled = enabled,
                        currentEntryId = activeId,
                        drawnAt = drawnAt,
                        updatedAt = nowIso,
                        hlc = hlc,
                        isLocalDirty = true,
                    )
                )
            }
            drawnActiveId?.takeIf { enabled }?.let { resolveDrawSnapshot(spec.collectionId, it) }
        }
        notifyLocalMutation()
        return result
    }

    suspend fun removeCollectionGenerator(collectionIdOrSlug: String): Boolean {
        val removed = mutationMutex.withLock {
            ensureSystemSeeded()
            val existing =
                resolveCollectionSpec(collectionIdOrSlug)?.let { spec ->
                    dao.getCollectionGenerator(spec.collectionId)
                }
            if (existing == null) {
                false
            } else {
                val nodeId = resolveLocalPeerId()
                dao.deleteCollectionGeneratorWithTombstone(
                    collectionId = existing.collectionId,
                    recordTombstone = true,
                    nodeId = nodeId,
                )
                true
            }
        }
        if (removed) {
            notifyLocalMutation()
        }
        return removed
    }

    suspend fun finishActiveDraw(
        collectionIdOrSlug: String,
        notes: String? = null,
    ): CollectionDrawStepResult =
        completeActiveDraw(collectionIdOrSlug, status = ConsumptionStatus.CONSUMED, notes = notes)

    suspend fun abandonActiveDraw(
        collectionIdOrSlug: String,
        notes: String? = null,
    ): CollectionDrawStepResult =
        completeActiveDraw(collectionIdOrSlug, status = ConsumptionStatus.ABANDONED, notes = notes)

    private suspend fun completeActiveDraw(
        collectionIdOrSlug: String,
        status: ConsumptionStatus,
        notes: String?,
    ): CollectionDrawStepResult {
        val result = mutationMutex.withLock {
            ensureSystemSeeded()
            val spec =
                resolveEligibleGeneratorSpec(collectionIdOrSlug)
                    ?: error("Collection '$collectionIdOrSlug' not found")
            val specTitle = lookupSpecTitle(spec)
            val gen =
                dao.getCollectionGenerator(spec.collectionId)
                    ?: error("Generator not initialized for '$specTitle'")
            val verb = if (status == ConsumptionStatus.CONSUMED) "finish" else "abandon"
            val currentId =
                gen.currentEntryId?.takeIf { it.isNotBlank() }?.let { followMergedId(it) }
                    ?: error("No active item to $verb in '$specTitle'")
            val nowIso = nowUtcIso()
            val nodeId = resolveLocalPeerId()
            dao.executeInTransaction {
                appendConsumptionInDao(
                    dao = dao,
                    entryId = currentId,
                    status = status,
                    notes = notes,
                    tsIso = nowIso,
                    nowIso = nowIso,
                    markDirty = true,
                    nodeId = nodeId,
                )
                advanceActiveDrawsLocked(currentId, status, nowIso, nodeId)
            }
            val updatedGen = dao.getCollectionGenerator(spec.collectionId)
            val nextId = updatedGen?.currentEntryId?.takeIf { it.isNotBlank() }
            CollectionDrawStepResult(
                entryId = currentId,
                collectionId = spec.collectionId,
                collectionTitle = specTitle,
                nextDraw = nextId?.let { resolveDrawSnapshot(spec.collectionId, it) },
            )
        }
        notifyLocalMutation()
        return result
    }

    suspend fun upsertEntry(
        input: UpsertCatalogEntryInput,
        markDirty: Boolean = true,
    ): String {
        val entryId = mutationMutex.withLock {
            ensureSystemSeeded()
            val nodeId = resolveLocalPeerId()
            var resolved = ""
            dao.executeInTransaction {
                resolved =
                    upsertEntryInDao(
                        dao = dao,
                        input = input,
                        nowIso = nowUtcIso(),
                        markDirty = markDirty,
                        nodeId = nodeId,
                    )
            }
            resolved
        }
        if (markDirty) {
            notifyLocalMutation()
        }
        return entryId
    }

    suspend fun updateEntry(
        entryId: String,
        input: UpdateCatalogEntryInput,
    ): CatalogEntryItem {
        val updated = mutationMutex.withLock {
            ensureSystemSeeded()
            val nodeId = resolveLocalPeerId()
            var result: CatalogEntryItem? = null
            dao.executeInTransaction {
                result =
                    updateEntryInDao(
                        dao = dao,
                        entryId = entryId,
                        input = input,
                        nowIso = nowUtcIso(),
                        nodeId = nodeId,
                    )
            }
            checkNotNull(result)
        }
        notifyLocalMutation()
        return updated
    }

    suspend fun logEntry(
        input: UpsertCatalogEntryInput,
        status: ConsumptionStatus,
        notes: String? = null,
    ): String {
        val entryId = mutationMutex.withLock {
            ensureSystemSeeded()
            val nowIso = nowUtcIso()
            val nodeId = resolveLocalPeerId()
            var resolved = ""
            dao.executeInTransaction {
                resolved =
                    upsertEntryInDao(
                        dao = dao,
                        input = input,
                        nowIso = nowIso,
                        markDirty = true,
                        nodeId = nodeId,
                    )
                appendConsumptionInDao(
                    dao = dao,
                    entryId = resolved,
                    status = status,
                    notes = notes,
                    tsIso = nowIso,
                    nowIso = nowIso,
                    markDirty = true,
                    nodeId = nodeId,
                )
                advanceActiveDrawsLocked(resolved, status, nowIso, nodeId)
            }
            resolved
        }
        notifyLocalMutation()
        return entryId
    }

    suspend fun logExistingEntry(
        entryId: String,
        status: ConsumptionStatus,
        notes: String? = null,
    ): String? {
        val loggedId = mutationMutex.withLock {
            ensureSystemSeeded()
            followMergedId(entryId)?.also { resolved ->
                val nowIso = nowUtcIso()
                val nodeId = resolveLocalPeerId()
                dao.executeInTransaction {
                    appendConsumptionInDao(
                        dao = dao,
                        entryId = resolved,
                        status = status,
                        notes = notes,
                        tsIso = nowIso,
                        nowIso = nowIso,
                        markDirty = true,
                        nodeId = nodeId,
                    )
                    advanceActiveDrawsLocked(resolved, status, nowIso, nodeId)
                }
            }
        }
        if (loggedId != null) {
            notifyLocalMutation()
        }
        return loggedId
    }

    suspend fun updateConsumptionLog(
        logId: String,
        status: ConsumptionStatus? = null,
        notes: String? = null,
        clearNotes: Boolean = false,
        tsIso: String? = null,
    ): Boolean {
        val cleanId = logId.trim()
        if (cleanId.isEmpty()) return false
        val updated = mutationMutex.withLock {
            ensureSystemSeeded()
            val existing = dao.getConsumptionLogById(cleanId)
            if (existing == null) {
                false
            } else {
                val nextStatus = status ?: ConsumptionStatus.fromWireValue(existing.status)
                val nextNotes =
                    when {
                        clearNotes -> null
                        notes != null -> notes.trim().takeIf { it.isNotEmpty() }
                        else -> existing.notes
                    }
                val nextTs = tsIso?.trim()?.takeIf { it.isNotEmpty() } ?: existing.ts
                applyConsumptionLogUpdateInTx(
                    existing = existing,
                    nextStatus = nextStatus,
                    nextNotes = nextNotes,
                    nextTs = nextTs,
                )
                true
            }
        }
        if (updated) {
            notifyLocalMutation()
        }
        return updated
    }

    private suspend fun applyConsumptionLogUpdateInTx(
        existing: ConsumptionLogEntity,
        nextStatus: ConsumptionStatus,
        nextNotes: String?,
        nextTs: String,
    ) {
        val nowIso = nowUtcIso()
        val nodeId = resolveLocalPeerId()
        dao.executeInTransaction {
            val hlc = nextLocalHlc(dao, nodeId)
            dao.deleteTombstone(TOMBSTONE_ENTITY_CONSUMPTION_LOG, existing.id)
            dao.upsertConsumptionLog(
                existing.copy(
                    ts = nextTs,
                    status = nextStatus.wireValue,
                    notes = nextNotes,
                    hlc = hlc,
                    isLocalDirty = true,
                )
            )
            if (nextStatus == ConsumptionStatus.CONSUMED) {
                removeFromWantQueuesInDao(dao, existing.entryId, nowIso, nodeId)
                dao.getEntryStateById(existing.entryId)?.let { st ->
                    if (st.readAt == null) {
                        dao.upsertEntryState(
                            st.copy(
                                readAt = nextTs,
                                interactionUpdatedAt = nowIso,
                                updatedAt = nowIso,
                                hlc = hlc,
                                isLocalDirty = true,
                            )
                        )
                    }
                }
                engageParentArtistsInDao(
                    dao = dao,
                    entryId = existing.entryId,
                    tsIso = nextTs,
                    nowIso = nowIso,
                    markDirty = true,
                    nodeId = nodeId,
                )
            }
            if (
                nextStatus == ConsumptionStatus.CONSUMED ||
                    nextStatus == ConsumptionStatus.ABANDONED
            ) {
                advanceActiveDrawsLocked(existing.entryId, nextStatus, nowIso, nodeId)
            }
        }
    }

    suspend fun deleteConsumptionLog(logId: String): Boolean {
        val cleanId = logId.trim()
        if (cleanId.isEmpty()) return false
        val deleted = mutationMutex.withLock {
            ensureSystemSeeded()
            if (dao.getConsumptionLogById(cleanId) == null) {
                false
            } else {
                val nowIso = nowUtcIso()
                val nodeId = resolveLocalPeerId()
                dao.executeInTransaction {
                    val hlc = nextLocalHlc(dao, nodeId)
                    dao.deleteConsumptionLogById(cleanId)
                    dao.upsertTombstone(
                        TombstoneEntity(
                            entityType = TOMBSTONE_ENTITY_CONSUMPTION_LOG,
                            naturalKey = cleanId,
                            deletedAt = nowIso,
                            hlc = hlc,
                            isLocalDirty = true,
                        )
                    )
                }
                true
            }
        }
        if (deleted) {
            notifyLocalMutation()
        }
        return deleted
    }

    suspend fun wantEntry(
        input: UpsertCatalogEntryInput,
        targetQueueIdOrSlug: String? = null,
    ): String {
        val entryId = mutationMutex.withLock {
            ensureSystemSeeded()
            val nowIso = nowUtcIso()
            val nodeId = resolveLocalPeerId()
            var resolved = ""
            dao.executeInTransaction {
                resolved =
                    upsertEntryInDao(
                        dao = dao,
                        input = input,
                        nowIso = nowIso,
                        markDirty = true,
                        nodeId = nodeId,
                    )
                val queueSpec =
                    ensureWantCollectionInDao(dao, input.kind, targetQueueIdOrSlug, nowIso, nodeId)
                addCollectionMemberInDao(
                    dao = dao,
                    spec = queueSpec,
                    entryId = resolved,
                    position = null,
                    nowIso = nowIso,
                    markDirty = true,
                    nodeId = nodeId,
                )
            }
            resolved
        }
        notifyLocalMutation()
        return entryId
    }

    suspend fun wantExistingEntry(entryId: String): Pair<String, String>? {
        val result = mutationMutex.withLock {
            ensureSystemSeeded()
            val resolved = followMergedId(entryId) ?: entryId.trim()
            dao.getEntryById(resolved)?.let { existing ->
                val nowIso = nowUtcIso()
                val nodeId = resolveLocalPeerId()
                val kind = EntryKind.fromWireValue(existing.kind)
                var queuePair: Pair<String, String>? = null
                dao.executeInTransaction {
                    val queueSpec = ensureWantCollectionInDao(dao, kind, null, nowIso, nodeId)
                    addCollectionMemberInDao(
                        dao = dao,
                        spec = queueSpec,
                        entryId = resolved,
                        position = null,
                        nowIso = nowIso,
                        markDirty = true,
                        nodeId = nodeId,
                    )
                    queuePair = queueSpec.collectionId to lookupSpecTitle(queueSpec)
                }
                queuePair
            }
        }
        if (result != null) {
            notifyLocalMutation()
        }
        return result
    }

    suspend fun createCustomList(
        title: String,
        role: CollectionRole = CollectionRole.LIST,
    ): String {
        var created = false
        val id = mutationMutex.withLock {
            ensureSystemSeeded()
            val cleanTitle = title.trim()
            require(cleanTitle.isNotEmpty()) { "Collection title cannot be empty" }
            val derived =
                requireNotNull(deriveCollectionIdentity(cleanTitle, role.wireValue)) {
                    "Collection slug cannot be empty"
                }
            val existing = dao.getCollectionSpecBySlug(derived.slug)
            if (existing != null) {
                ensureCollectionEntryRow(existing.collectionId, cleanTitle)
                existing.collectionId
            } else {
                insertNewCustomCollectionInTx(derived.collectionId, cleanTitle, derived.slug, role)
                created = true
                derived.collectionId
            }
        }
        if (created) {
            notifyLocalMutation()
        }
        return id
    }

    private suspend fun ensureCollectionEntryRow(collectionId: String, cleanTitle: String) {
        if (dao.getEntryById(collectionId) != null) return
        val nowIso = nowUtcIso()
        val hlc = nextLocalHlc(dao, resolveLocalPeerId())
        dao.upsertEntry(
            EntryEntity(
                id = collectionId,
                kind = EntryKind.COLLECTION.wireValue,
                title = cleanTitle,
                createdAt = nowIso,
                updatedAt = nowIso,
                hlc = hlc,
                isLocalDirty = true,
            )
        )
    }

    private suspend fun insertNewCustomCollectionInTx(
        newId: String,
        cleanTitle: String,
        slug: String,
        role: CollectionRole,
    ) {
        val nowIso = nowUtcIso()
        val nodeId = resolveLocalPeerId()
        val newSpec =
            CollectionSpecEntity(
                collectionId = newId,
                slug = slug,
                role = role.wireValue,
            )
        dao.executeInTransaction {
            val hlc = nextLocalHlc(dao, nodeId)
            dao.upsertEntry(
                EntryEntity(
                    id = newId,
                    kind = EntryKind.COLLECTION.wireValue,
                    title = cleanTitle,
                    createdAt = nowIso,
                    updatedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = true,
                )
            )
            dao.upsertCollectionSpec(newSpec)
            dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("slug", slug, newId))
        }
    }

    suspend fun addCollectionEntry(
        collectionIdOrSlug: String,
        entryId: String,
        position: Double? = null,
    ) {
        mutationMutex.withLock {
            ensureSystemSeeded()
            val spec =
                resolveCollectionSpec(collectionIdOrSlug)
                    ?: error("Collection not found: $collectionIdOrSlug")
            val resolvedEntry =
                followMergedId(entryId) ?: error("Catalog entry not found: $entryId")
            val nodeId = resolveLocalPeerId()
            dao.executeInTransaction {
                addCollectionMemberInDao(
                    dao = dao,
                    spec = spec,
                    entryId = resolvedEntry,
                    position = position,
                    nowIso = nowUtcIso(),
                    markDirty = true,
                    nodeId = nodeId,
                )
            }
        }
        notifyLocalMutation()
    }

    suspend fun removeCollectionEntry(
        collectionIdOrSlug: String,
        entryId: String,
    ): Boolean {
        val removed = mutationMutex.withLock {
            ensureSystemSeeded()
            val spec =
                resolveCollectionSpec(collectionIdOrSlug)
                    ?: error("Collection not found: $collectionIdOrSlug")
            val specTitle = lookupSpecTitle(spec)
            val reason = spec.immutableReason()
            require(reason == null) {
                "Cannot manually modify entries of $reason collection '$specTitle'"
            }
            val resolvedEntry = followMergedId(entryId) ?: entryId.trim()
            val existing =
                dao.getCollectionMemberships(spec.collectionId).any {
                    it.entryId == resolvedEntry
                }
            if (existing) {
                val nowIso = nowUtcIso()
                val nodeId = resolveLocalPeerId()
                dao.executeInTransaction {
                    val hlc = nextLocalHlc(dao, nodeId)
                    dao.deleteCollectionEntry(spec.collectionId, resolvedEntry)
                    dao.upsertTombstone(
                        TombstoneEntity(
                            entityType = TOMBSTONE_ENTITY_COLLECTION_ENTRY,
                            naturalKey =
                                collectionEntryNaturalKey(spec.collectionId, resolvedEntry),
                            deletedAt = nowIso,
                            hlc = hlc,
                            isLocalDirty = true,
                        )
                    )
                }
            }
            existing
        }
        if (removed) {
            notifyLocalMutation()
        }
        return removed
    }

    suspend fun recordComparison(
        ordering: List<List<String>>,
        scopeCollectionId: String? = null,
    ): String {
        val compId = mutationMutex.withLock {
            ensureSystemSeeded()
            EpRankingEngine.validateOrdering(ordering).getOrThrow()
            val resolvedGroups = ordering.map { group ->
                group.map { id -> followMergedId(id) ?: id.trim() }.distinct()
            }
            EpRankingEngine.validateOrdering(resolvedGroups).getOrThrow()
            val cleanScope = scopeCollectionId?.trim()?.takeIf { it.isNotEmpty() }
            val now = Instant.now()
            val nowIso = formatUtcMillisIso(now)
            val nodeId = resolveLocalPeerId()
            val id =
                deterministicComparisonId(
                    scopeCollectionId = cleanScope,
                    tsMillis = now.toEpochMilli(),
                    ordering = resolvedGroups,
                )
            dao.executeInTransaction {
                val hlc = nextLocalHlc(dao, nodeId)
                dao.insertComparisonIgnore(
                    ComparisonEntity(
                        id = id,
                        ts = nowIso,
                        scopeCollectionId = cleanScope,
                        orderingJson = canonicalOrderingJson(resolvedGroups),
                        createdAt = nowIso,
                        hlc = hlc,
                        isLocalDirty = true,
                    )
                )
            }
            id
        }
        notifyLocalMutation()
        return compId
    }

    suspend fun deleteComparison(comparisonId: String): Boolean {
        val cleanId = comparisonId.trim()
        if (cleanId.isEmpty()) return false
        val deleted = mutationMutex.withLock {
            ensureSystemSeeded()
            if (dao.getComparisonById(cleanId) == null) {
                false
            } else {
                val nowIso = nowUtcIso()
                val nodeId = resolveLocalPeerId()
                dao.executeInTransaction {
                    val hlc = nextLocalHlc(dao, nodeId)
                    dao.deleteComparisonById(cleanId)
                    dao.upsertTombstone(
                        TombstoneEntity(
                            entityType = TOMBSTONE_ENTITY_COMPARISON,
                            naturalKey = cleanId,
                            deletedAt = nowIso,
                            hlc = hlc,
                            isLocalDirty = true,
                        )
                    )
                }
                true
            }
        }
        if (deleted) {
            notifyLocalMutation()
        }
        return deleted
    }

    @Suppress("LongParameterList")
    suspend fun selectNextRankCluster(
        collectionIdOrSlug: String,
        focusEntryId: String? = null,
        roundIndex: Int = 0,
        forceRandomNext: Boolean = false,
        maxK: Int = EpRankingEngine.MAX_K_DEFAULT,
        excludedOpponentIds: Set<String> = emptySet(),
    ): List<RankClusterCandidate> = mutationMutex.withLock {
        ensureSystemSeeded()
        val spec = resolveCollectionSpec(collectionIdOrSlug)
        if (spec == null) {
            emptyList()
        } else {
            var cluster: List<RankClusterCandidate> = emptyList()
            dao.executeInTransaction {
                cluster =
                    selectNextRankClusterFromDao(
                        dao = dao,
                        spec = spec,
                        epScoreCache = epScoreCache,
                        focusEntryId = focusEntryId,
                        roundIndex = roundIndex,
                        forceRandomNext = forceRandomNext,
                        maxK = maxK,
                        excludedOpponentIds = excludedOpponentIds,
                        random = random,
                    )
            }
            cluster
        }
    }

    suspend fun resolveCollectionRankMeta(
        collectionIdOrSlug: String,
        includeMemberCount: Boolean = true,
    ): CollectionRankMeta? = mutationMutex.withLock {
        ensureSystemSeeded()
        resolveCollectionSpec(collectionIdOrSlug)?.let { spec ->
            val role = CollectionRole.fromWireValue(spec.role)
            val isPriorityScoped =
                role == CollectionRole.WANT || spec.collectionId == UP_NEXT_COLLECTION_ID
            val memberCount = if (includeMemberCount) resolveCollectionMemberCount(dao, spec) else 0
            CollectionRankMeta(
                collectionId = spec.collectionId,
                slug = spec.slug ?: spec.collectionId,
                title = lookupSpecTitle(spec),
                scopeCollectionId = spec.collectionId.takeIf { isPriorityScoped },
                memberCount = memberCount,
            )
        }
    }

    suspend fun resolveEntrySystemListSlug(entryId: String): String? = mutationMutex.withLock {
        ensureSystemSeeded()
        val resolved = followMergedId(entryId) ?: entryId.trim()
        dao.getEntryById(resolved)?.let { EntryKind.fromWireValue(it.kind).systemListSlug }
    }

    suspend fun resolveEntrySettlement(
        entryId: String,
        preferredCollectionIdOrSlug: String? = null,
        includeNeighbors: Boolean = true,
    ): RankSettlementSummary? = mutationMutex.withLock {
        ensureSystemSeeded()
        val resolved = followMergedId(entryId) ?: entryId
        val preferredSpec = preferredCollectionIdOrSlug?.let { resolveCollectionSpec(it) }
        val rawEntry = dao.getEntryById(resolved)
        val fallbackSpec = rawEntry?.let {
            dao.getCollectionSpecBySlug(EntryKind.fromWireValue(it.kind).systemListSlug)
        }
        val targetSpec = preferredSpec ?: fallbackSpec
        targetSpec?.let { spec ->
            val specTitle = lookupSpecTitle(spec)
            var summary: RankSettlementSummary? = null
            dao.executeInTransaction {
                summary =
                    resolveEntrySettlementFromDao(
                        dao = dao,
                        entryId = resolved,
                        targetSpec = spec,
                        specTitle = specTitle,
                        epScoreCache = epScoreCache,
                        includeNeighbors = includeNeighbors,
                    )
            }
            summary
        }
    }

    internal suspend fun deleteConsumedLogsForEntry(entryId: String): Int {
        val deleted = mutationMutex.withLock {
            ensureSystemSeeded()
            val nowIso = nowUtcIso()
            val nodeId = resolveLocalPeerId()
            var count = 0
            dao.executeInTransaction {
                count = deleteConsumedLogsInDao(dao, entryId, nowIso, nodeId)
            }
            count
        }
        if (deleted > 0) {
            notifyLocalMutation()
        }
        return deleted
    }

    internal suspend fun logFeedItemCompletion(itemId: String): String {
        val resolvedId = mutationMutex.withLock {
            ensureSystemSeeded()
            logFeedItemCompletionLocked(itemId)
        }
        notifyLocalMutation()
        return resolvedId
    }

    internal suspend fun logItemCompletionIfUnlogged(itemId: String): String? {
        val resolvedId = mutationMutex.withLock {
            ensureSystemSeeded()
            val targetId = followMergedId(itemId) ?: itemId
            if (dao.countTerminalLogsForEntry(targetId) > 0) {
                null
            } else {
                logFeedItemCompletionLocked(itemId)
            }
        }
        if (resolvedId != null) {
            notifyLocalMutation()
        }
        return resolvedId
    }

    private suspend fun logFeedItemCompletionLocked(itemId: String): String {
        val nowIso = nowUtcIso()
        val nodeId = resolveLocalPeerId()
        var id = itemId
        dao.executeInTransaction {
            id =
                logFeedItemConsumedInDao(
                    dao = dao,
                    itemId = itemId,
                    nowIso = nowIso,
                    nodeId = nodeId,
                )
            advanceActiveDrawsLocked(id, ConsumptionStatus.CONSUMED, nowIso, nodeId)
        }
        return id
    }

    internal fun setOnLocalMutation(callback: () -> Unit) {
        localMutationCallback.set(callback)
    }

    internal fun notifyLocalMutation() {
        localMutationCallback.get().invoke()
    }

    internal suspend fun getEntryIdentifierSchemes(entryId: String): Set<String> =
        dao.getIdentifiersForEntry(entryId).map { it.scheme.lowercase() }.toSet()

    internal suspend fun <T> withLockedDao(block: suspend (RocheDao) -> T): T =
        mutationMutex.withLock {
            ensureSystemSeeded()
            block(dao)
        }

    internal suspend fun withMutationLock(block: suspend () -> Unit) {
        mutationMutex.withLock {
            ensureSystemSeeded()
            block()
        }
    }

    private suspend fun lookupSpecTitle(spec: CollectionSpecEntity): String =
        dao.getEntryById(spec.collectionId)?.title?.takeIf { it.isNotBlank() }
            ?: spec.slug
            ?: spec.collectionId

    private suspend fun resolveEligibleGeneratorSpec(idOrSlug: String): CollectionSpecEntity? {
        val spec = resolveCollectionSpec(idOrSlug) ?: return null
        return spec.takeIf(::isGeneratorEligible)
    }

    private suspend fun advanceActiveDrawsLocked(
        entryId: String,
        status: ConsumptionStatus,
        nowIso: String,
        nodeId: String,
    ) {
        if (status != ConsumptionStatus.CONSUMED && status != ConsumptionStatus.ABANDONED) return
        for (gen in dao.getAllCollectionGenerators()) {
            val canonCur =
                gen.currentEntryId?.takeIf { it.isNotBlank() }?.let { followMergedId(it) }
            if (canonCur == entryId) {
                advanceSingleGeneratorLocked(
                    collectionId = gen.collectionId,
                    enabled = gen.enabled,
                    nowIso = nowIso,
                    markDirty = true,
                    nodeId = nodeId,
                )
            }
        }
    }

    private suspend fun advanceSingleGeneratorLocked(
        collectionId: String,
        enabled: Boolean,
        nowIso: String,
        markDirty: Boolean,
        nodeId: String,
    ): String? {
        val nextId = if (enabled) drawRandomRemainingId(collectionId) else null
        val hlc =
            if (markDirty) {
                nextLocalHlc(dao, nodeId)
            } else {
                HlcTimestamp.fromIsoOrEpoch(nowIso, nodeId).encode()
            }
        if (markDirty) {
            dao.deleteTombstone(TOMBSTONE_ENTITY_COLLECTION_GENERATOR, collectionId)
        }
        dao.upsertCollectionGenerator(
            CollectionGeneratorEntity(
                collectionId = collectionId,
                enabled = enabled,
                currentEntryId = nextId,
                drawnAt = nextId?.let { nowIso },
                updatedAt = nowIso,
                hlc = hlc,
                isLocalDirty = markDirty,
            )
        )
        return nextId
    }

    internal suspend fun drawRandomRemainingId(collectionId: String): String? {
        val remaining = dao.getEligibleDrawEntryIds(collectionId)
        return if (remaining.isEmpty()) null else remaining[random.nextInt(remaining.size)]
    }

    internal suspend fun resolveCollectionSpec(idOrSlug: String): CollectionSpecEntity? {
        val clean = idOrSlug.trim()
        if (clean.isEmpty()) return null
        val direct = dao.getCollectionSpecById(clean) ?: dao.getCollectionSpecBySlug(clean)
        if (direct != null) return direct
        val normalizedSlug =
            if (clean.startsWith(WANT_SLUG_PREFIX, ignoreCase = true)) {
                val base = clean.substringAfter(':')
                EntryKind.fromFlexibleAlias(base)?.let { "$WANT_SLUG_PREFIX${it.systemListSlug}" }
            } else {
                EntryKind.fromFlexibleAlias(clean)?.systemListSlug
            }
        return normalizedSlug?.let { dao.getCollectionSpecBySlug(it) }
    }

    internal suspend fun followMergedId(entryId: String): String? =
        followMergedIdInDao(dao, entryId)

    internal suspend fun resolveDrawSnapshot(
        collectionId: String,
        entryId: String,
    ): CollectionDrawSnapshot? {
        val snap = getOrBuildCatalogSnapshot()
        return snap.generatorFor(collectionId)?.currentDraw?.takeIf { it.entryId == entryId }
    }

    internal suspend fun resolveOverviewSnapshot(
        observedStamp: CatalogStampRow? = null
    ): CatalogSnapshot {
        ensureSystemSeeded()
        return snapshotCacheMutex.withLock {
            val existing = cachedState
            if (observedStamp != null && existing != null && existing.stamp == observedStamp) {
                catalogSnapshotCacheHits += 1
                existing.snapshot
            } else {
                var resolvedStamp: CatalogStampRow? = null
                var built: CatalogSnapshot? = null
                dao.executeInTransaction {
                    val currentStamp = dao.getCatalogStamp()
                    resolvedStamp = currentStamp
                    if (existing == null || existing.stamp != currentStamp) {
                        catalogTableReloads += 1
                        built = loadCatalogOverviewSnapshot(dao, epScoreCache)
                    }
                }
                val finalStamp = checkNotNull(resolvedStamp)
                val fresh = built
                if (fresh == null && existing != null && existing.stamp == finalStamp) {
                    catalogSnapshotCacheHits += 1
                    existing.snapshot
                } else {
                    val snapshot = checkNotNull(fresh)
                    catalogSnapshotBuilds += 1
                    cachedState = CachedCatalogState(finalStamp, snapshot)
                    snapshot
                }
            }
        }
    }

    internal suspend fun getOrBuildCatalogSnapshot(): CatalogSnapshot = resolveOverviewSnapshot()
}

internal fun resolveDiaryYearBounds(year: String?): Pair<String, String> {
    val yr = year?.trim()?.toIntOrNull()?.takeIf { it in MIN_DIARY_YEAR..MAX_DIARY_YEAR }
    return if (yr != null) {
        val next = yr + 1
        "${"%04d".format(java.util.Locale.US, yr)}-01-01" to
            "${"%04d".format(java.util.Locale.US, next)}-01-01"
    } else {
        "" to MAX_ISO_TS_BOUND
    }
}

internal data class CachedCatalogState(
    val stamp: CatalogStampRow,
    val snapshot: CatalogSnapshot,
)

internal fun RankedCatalogEntry.toRankCandidate(selectionPriority: Int = 0): RankClusterCandidate =
    RankClusterCandidate(
        entryId = entry.id,
        title = entry.title,
        subtitle = entry.subtitleLine,
        thumbnailUrl = entry.thumbnailUrl,
        mean = mean,
        variance = variance,
        selectionPriority = selectionPriority,
    )

private const val WANT_SLUG_PREFIX = "want:"
internal const val DEFAULT_DIARY_PAGE_SIZE = 100
internal const val DEFAULT_ENTRY_DETAIL_LOGS_LIMIT = 50
internal const val RECENT_DIARY_PREVIEW_LIMIT = 12
private const val MAX_ISO_TS_BOUND = "\uFFFF"
private const val MIN_DIARY_YEAR = 1
private const val MAX_DIARY_YEAR = 9_998
