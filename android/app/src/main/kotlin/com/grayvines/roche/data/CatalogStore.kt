@file:Suppress("TooManyFunctions", "LargeClass")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.ranking.EpRankingEngine
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CatalogStore(
    internal val dao: RocheDao,
    private val random: Random = Random.Default,
    private val onLocalMutation: () -> Unit = {},
) {
    private val seeder = CatalogSeeder(dao = dao)
    private val mutationMutex = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    val snapshot: Flow<CatalogSnapshot> =
        combine(
                combine(
                    dao.observeEntriesStamp(),
                    dao.observeIdentsStamp(),
                    dao.observeSpecsStamp(),
                    dao.observeMembersStamp(),
                ) { _, _, _, _ ->
                    Unit
                },
                dao.observeGeneratorsStamp(),
                dao.observeLogsStamp(),
                dao.observeComparisonsStamp(),
            ) { _, _, _, _ ->
                Unit
            }
            .conflate()
            .mapLatest { loadConsistentRawTables() }
            .distinctUntilChanged()
            .map(::buildCatalogSnapshot)
            .flowOn(Dispatchers.Default)

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
                val hlc = nextLocalHlc(dao)
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
        onLocalMutation()
        return result
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
            dao.executeInTransaction {
                appendConsumptionInDao(
                    dao = dao,
                    entryId = currentId,
                    status = status,
                    notes = notes,
                    tsIso = nowIso,
                    nowIso = nowIso,
                    markDirty = true,
                )
                advanceActiveDrawsLocked(currentId, status, nowIso)
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
        onLocalMutation()
        return result
    }

    suspend fun upsertEntry(
        input: UpsertCatalogEntryInput,
        markDirty: Boolean = true,
    ): String {
        val entryId = mutationMutex.withLock {
            ensureSystemSeeded()
            var resolved = ""
            dao.executeInTransaction {
                resolved =
                    upsertEntryInDao(
                        dao = dao,
                        input = input,
                        nowIso = nowUtcIso(),
                        markDirty = markDirty,
                    )
            }
            resolved
        }
        if (markDirty) {
            onLocalMutation()
        }
        return entryId
    }

    suspend fun logEntry(
        input: UpsertCatalogEntryInput,
        status: ConsumptionStatus,
        notes: String? = null,
    ): String {
        val entryId = mutationMutex.withLock {
            ensureSystemSeeded()
            val nowIso = nowUtcIso()
            var resolved = ""
            dao.executeInTransaction {
                resolved =
                    upsertEntryInDao(
                        dao = dao,
                        input = input,
                        nowIso = nowIso,
                        markDirty = true,
                    )
                appendConsumptionInDao(
                    dao = dao,
                    entryId = resolved,
                    status = status,
                    notes = notes,
                    tsIso = nowIso,
                    nowIso = nowIso,
                    markDirty = true,
                )
                advanceActiveDrawsLocked(resolved, status, nowIso)
            }
            resolved
        }
        onLocalMutation()
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
                dao.executeInTransaction {
                    appendConsumptionInDao(
                        dao = dao,
                        entryId = resolved,
                        status = status,
                        notes = notes,
                        tsIso = nowIso,
                        nowIso = nowIso,
                        markDirty = true,
                    )
                    advanceActiveDrawsLocked(resolved, status, nowIso)
                }
            }
        }
        if (loggedId != null) {
            onLocalMutation()
        }
        return loggedId
    }

    suspend fun wantEntry(
        input: UpsertCatalogEntryInput,
        targetQueueIdOrSlug: String? = null,
    ): String {
        val entryId = mutationMutex.withLock {
            ensureSystemSeeded()
            val nowIso = nowUtcIso()
            var resolved = ""
            dao.executeInTransaction {
                resolved =
                    upsertEntryInDao(
                        dao = dao,
                        input = input,
                        nowIso = nowIso,
                        markDirty = true,
                    )
                val queueSpec =
                    ensureWantCollectionInDao(dao, input.kind, targetQueueIdOrSlug, nowIso)
                addCollectionMemberInDao(
                    dao = dao,
                    spec = queueSpec,
                    entryId = resolved,
                    position = null,
                    nowIso = nowIso,
                    markDirty = true,
                )
            }
            resolved
        }
        onLocalMutation()
        return entryId
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
            val rawSlug = slugifyTitle(cleanTitle)
            val slug = if (role == CollectionRole.WANT) "want:$rawSlug" else rawSlug
            val existing = dao.getCollectionSpecBySlug(slug)
            if (existing != null) {
                ensureCollectionEntryRow(existing.collectionId, cleanTitle)
                existing.collectionId
            } else {
                val newId =
                    if (role == CollectionRole.WANT) {
                        systemCollectionUuid(slug)
                    } else {
                        systemCollectionUuid("custom:$slug")
                    }
                insertNewCustomCollectionInTx(newId, cleanTitle, slug, role)
                created = true
                newId
            }
        }
        if (created) {
            onLocalMutation()
        }
        return id
    }

    private suspend fun ensureCollectionEntryRow(collectionId: String, cleanTitle: String) {
        if (dao.getEntryById(collectionId) != null) return
        val nowIso = nowUtcIso()
        val hlc = nextLocalHlc(dao)
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
        val newSpec =
            CollectionSpecEntity(
                collectionId = newId,
                slug = slug,
                mode = CollectionMode.MANUAL.wireValue,
                role = role.wireValue,
                showInLists = role == CollectionRole.LIST,
            )
        dao.executeInTransaction {
            val hlc = nextLocalHlc(dao)
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
            dao.executeInTransaction {
                addCollectionMemberInDao(
                    dao = dao,
                    spec = spec,
                    entryId = resolvedEntry,
                    position = position,
                    nowIso = nowUtcIso(),
                    markDirty = true,
                )
            }
        }
        onLocalMutation()
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
            val mode = CollectionMode.fromWireValue(spec.mode)
            require(mode == CollectionMode.MANUAL) {
                "Cannot manually modify entries of ${mode.wireValue} collection '$specTitle'"
            }
            val resolvedEntry = followMergedId(entryId) ?: entryId.trim()
            val existing =
                dao.getCollectionMemberships(spec.collectionId).any {
                    it.entryId == resolvedEntry
                }
            if (existing) {
                val nowIso = nowUtcIso()
                dao.executeInTransaction {
                    val hlc = nextLocalHlc(dao)
                    dao.deleteCollectionEntry(spec.collectionId, resolvedEntry)
                    dao.upsertTombstone(
                        TombstoneEntity(
                            entityType = COLLECTION_ENTRY_ENTITY,
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
            onLocalMutation()
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
            val nowIso = now.toString()
            val id =
                deterministicComparisonId(
                    scopeCollectionId = cleanScope,
                    tsMillis = now.toEpochMilli(),
                    ordering = resolvedGroups,
                )
            dao.executeInTransaction {
                val hlc = nextLocalHlc(dao)
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
        onLocalMutation()
        return compId
    }

    suspend fun selectNextRankCluster(
        collectionIdOrSlug: String,
        focusEntryId: String? = null,
        roundIndex: Int = 0,
        forceRandomNext: Boolean = false,
        maxK: Int = EpRankingEngine.MAX_K_DEFAULT,
    ): List<RankClusterCandidate> = mutationMutex.withLock {
        ensureSystemSeeded()
        val spec = resolveCollectionSpec(collectionIdOrSlug)
        val snap = buildCatalogSnapshot(loadRawCatalogTables())
        val members = spec?.let { snap.entriesByCollectionId[it.collectionId] }.orEmpty()
        if (members.size < EpRankingEngine.MIN_K) {
            emptyList()
        } else {
            pickClusterForMembers(members, focusEntryId, roundIndex, forceRandomNext, maxK)
        }
    }

    private suspend fun pickClusterForMembers(
        members: List<RankedCatalogEntry>,
        focusEntryId: String?,
        roundIndex: Int,
        forceRandomNext: Boolean,
        maxK: Int,
    ): List<RankClusterCandidate> {
        val memberIds = members.map { it.entry.id }
        val scores = members.associate { ranked ->
            ranked.entry.id to EpRankingEngine.Score(ranked.mean, ranked.variance)
        }
        val resolvedFocus = focusEntryId?.let { followMergedId(it) ?: it }
        val seedPool = resolvedFocus?.takeIf { it in scores }?.let(::listOf)
        val forceRandom =
            forceRandomNext ||
                seedPool == null &&
                    roundIndex > 0 &&
                    roundIndex % EpRankingEngine.RANDOM_SEED_EVERY == 0
        val clusterIds =
            EpRankingEngine.selectCluster(
                scores = scores,
                itemIds = memberIds,
                maxK = maxK,
                forceRandomSeed = forceRandom,
                seedPool = seedPool,
                random = random,
            )
        val byId = members.associateBy { it.entry.id }
        return if (clusterIds.size < EpRankingEngine.MIN_K) {
            emptyList()
        } else {
            clusterIds.mapNotNull { id -> byId[id]?.toRankCandidate() }
        }
    }

    suspend fun resolveCollectionRankMeta(collectionIdOrSlug: String): CollectionRankMeta? =
        mutationMutex.withLock {
            ensureSystemSeeded()
            resolveCollectionSpec(collectionIdOrSlug)?.let { spec ->
                val role = CollectionRole.fromWireValue(spec.role)
                val isPriorityScoped =
                    role == CollectionRole.WANT || spec.collectionId == UP_NEXT_COLLECTION_ID
                CollectionRankMeta(
                    collectionId = spec.collectionId,
                    slug = spec.slug ?: spec.collectionId,
                    title = lookupSpecTitle(spec),
                    scopeCollectionId = spec.collectionId.takeIf { isPriorityScoped },
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
    ): RankSettlementSummary? = mutationMutex.withLock {
        ensureSystemSeeded()
        val resolved = followMergedId(entryId) ?: entryId
        val snap = buildCatalogSnapshot(loadRawCatalogTables())
        val preferredSpec = preferredCollectionIdOrSlug?.let { resolveCollectionSpec(it) }
        val rawEntry = dao.getEntryById(resolved)
        val fallbackSpec = rawEntry?.let {
            dao.getCollectionSpecBySlug(EntryKind.fromWireValue(it.kind).systemListSlug)
        }
        val targetSpec = preferredSpec ?: fallbackSpec
        val rankedList = targetSpec?.let { snap.entriesByCollectionId[it.collectionId] }.orEmpty()
        val match = rankedList.firstOrNull { it.entry.id == resolved }
        if (targetSpec == null || match == null) {
            null
        } else {
            RankSettlementSummary(
                entryId = resolved,
                title = match.entry.title,
                collectionTitle = lookupSpecTitle(targetSpec),
                rank = match.rank,
                totalCount = rankedList.size,
                tier = match.tier,
            )
        }
    }

    internal suspend fun logFeedItemCompletion(itemId: String): String {
        val resolvedId = mutationMutex.withLock {
            ensureSystemSeeded()
            val nowIso = nowUtcIso()
            var id = itemId
            dao.executeInTransaction {
                id = logFeedItemConsumedInDao(dao = dao, itemId = itemId, nowIso = nowIso)
                advanceActiveDrawsLocked(id, ConsumptionStatus.CONSUMED, nowIso)
            }
            id
        }
        onLocalMutation()
        return resolvedId
    }

    internal fun notifyLocalMutation() {
        onLocalMutation()
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
        val isCustomManualList =
            spec.collectionId != STARRED_COLLECTION_ID &&
                CollectionMode.fromWireValue(spec.mode) == CollectionMode.MANUAL &&
                CollectionRole.fromWireValue(spec.role) == CollectionRole.LIST
        return spec.takeIf { isCustomManualList }
    }

    private suspend fun advanceActiveDrawsLocked(
        entryId: String,
        status: ConsumptionStatus,
        nowIso: String,
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
                )
            }
        }
    }

    private suspend fun advanceSingleGeneratorLocked(
        collectionId: String,
        enabled: Boolean,
        nowIso: String,
        markDirty: Boolean,
    ): String? {
        val nextId = if (enabled) drawRandomRemainingId(collectionId) else null
        val hlc =
            if (markDirty) {
                nextLocalHlc(dao)
            } else {
                HlcTimestamp.fromIsoOrEpoch(nowIso, DEFAULT_LOCAL_HLC_NODE).encode()
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
        val poolMembers = dao.getCollectionMemberships(collectionId).map { it.entryId }
        if (poolMembers.isEmpty()) return null
        val activeIds =
            dao.getAllEntries().filter { it.mergedIntoId.isNullOrBlank() }.map { it.id }.toHashSet()
        val finishedIds =
            dao.getAllConsumptionLogs()
                .filter {
                    val s = ConsumptionStatus.fromWireValue(it.status)
                    s == ConsumptionStatus.CONSUMED || s == ConsumptionStatus.ABANDONED
                }
                .map { it.entryId }
                .toHashSet()
        val remaining = poolMembers.filter { it in activeIds && it !in finishedIds }.distinct()
        return if (remaining.isEmpty()) null else remaining[random.nextInt(remaining.size)]
    }

    internal suspend fun resolveCollectionSpec(idOrSlug: String): CollectionSpecEntity? {
        val clean = idOrSlug.trim()
        if (clean.isEmpty()) return null
        val direct = dao.getCollectionSpecById(clean) ?: dao.getCollectionSpecBySlug(clean)
        if (direct != null) return direct
        val normalizedSlug =
            if (clean.startsWith("want:", ignoreCase = true)) {
                val base = clean.substringAfter(':')
                EntryKind.fromFlexibleAlias(base)?.let { "want:${it.systemListSlug}" }
            } else {
                EntryKind.fromFlexibleAlias(clean)?.systemListSlug
            }
        return normalizedSlug?.let { dao.getCollectionSpecBySlug(it) }
    }

    internal suspend fun followMergedId(entryId: String): String? =
        followMergedIdInDao(dao, entryId)

    private suspend fun resolveDrawSnapshot(
        collectionId: String,
        entryId: String,
    ): CollectionDrawSnapshot? {
        val snap = buildCatalogSnapshot(loadRawCatalogTables())
        return snap.generatorFor(collectionId)?.currentDraw?.takeIf { it.entryId == entryId }
    }

    private suspend fun loadConsistentRawTables(): RawCatalogTables {
        var tables: RawCatalogTables? = null
        dao.executeInTransaction { tables = loadRawCatalogTables() }
        return checkNotNull(tables)
    }

    internal suspend fun loadRawCatalogTables(): RawCatalogTables =
        RawCatalogTables(
            entries = dao.getAllEntries(),
            identifiers = dao.getAllEntryIdentifiers(),
            specs = dao.getAllCollectionSpecs(),
            memberships = dao.getAllCollectionEntries(),
            generators = dao.getAllCollectionGenerators(),
            logs = dao.getAllConsumptionLogs(),
            comparisons = dao.getAllComparisons(),
        )
}

internal fun nowUtcIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

internal fun slugifyTitle(raw: String): String =
    raw.trim().lowercase().replace(NON_SLUG_REGEX, "_").trim('_').ifEmpty { "list" }

internal fun extractYearPrefix(rawIso: String?): Int? {
    val clean = rawIso?.trim().orEmpty()
    return if (clean.length >= YEAR_CHARS && clean.take(YEAR_CHARS).all(Char::isDigit)) {
        clean.take(YEAR_CHARS).toIntOrNull()
    } else {
        null
    }
}

internal fun RankedCatalogEntry.toRankCandidate(): RankClusterCandidate =
    RankClusterCandidate(
        entryId = entry.id,
        title = entry.title,
        subtitle = entry.subtitleLine,
        thumbnailUrl = entry.thumbnailUrl,
        mean = mean,
        variance = variance,
    )

private const val YEAR_CHARS = 4
private val NON_SLUG_REGEX = Regex("[^a-z0-9]+")
