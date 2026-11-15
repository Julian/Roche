@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.CatalogEntryEntity
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.ranking.EpRankingEngine
import java.io.InputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CatalogStore(
    private val dao: RocheDao,
    poolStreamProvider: (() -> InputStream?)? = null,
    private val random: Random = Random.Default,
) {
    private val seeder = CatalogSeeder(dao = dao, poolStreamProvider = poolStreamProvider)
    private val mutationMutex = Mutex()

    val snapshot: Flow<CatalogSnapshot> =
        combine(
                combine(
                    dao.observeCatalogEntries(),
                    dao.observeEntryIdentifiers(),
                    dao.observeCollectionSpecs(),
                    dao.observeCollectionEntries(),
                ) { entries, identifiers, specs, memberships ->
                    PartialCatalogTables(entries, identifiers, specs, memberships)
                },
                dao.observeCollectionGenerators(),
                dao.observeConsumptionLogs(),
                dao.observeComparisons(),
            ) { partial, generators, logs, comparisons ->
                buildCatalogSnapshot(
                    RawCatalogTables(
                        entries = partial.entries,
                        identifiers = partial.identifiers,
                        specs = partial.specs,
                        memberships = partial.memberships,
                        generators = generators,
                        logs = logs,
                        comparisons = comparisons,
                    )
                )
            }
            .flowOn(Dispatchers.Default)

    suspend fun ensureSystemSeeded() = seeder.ensureSystemSeeded()

    suspend fun ensure1001PoolSeeded() = seeder.ensure1001PoolSeeded()

    suspend fun setAlbumGeneratorEnabled(enabled: Boolean): DailyAlbumSnapshot? =
        mutationMutex.withLock {
            ensure1001PoolSeeded()
            val nowIso = nowUtcIso()
            val current = dao.getCollectionGenerator(ALBUMS_1001_COLLECTION_ID)
            var activeId =
                current?.currentEntryId?.takeIf { it.isNotBlank() }?.let { followMergedId(it) }
            if (activeId != null && dao.countTerminalLogsForEntry(activeId) > 0) {
                activeId = null
            }
            val drawnAt =
                if (enabled && activeId == null) {
                    activeId = drawRandomRemaining1001Id()
                    activeId?.let { nowIso }
                } else {
                    current?.drawnAt
                }
            dao.upsertCollectionGenerator(
                CollectionGeneratorEntity(
                    collectionId = ALBUMS_1001_COLLECTION_ID,
                    enabled = enabled,
                    currentEntryId = activeId,
                    drawnAt = drawnAt,
                    updatedAt = nowIso,
                    isLocalDirty = true,
                )
            )
            activeId?.takeIf { enabled }?.let { resolveAlbumSnapshotById(it) }
        }

    suspend fun finishCurrent1001Album(notes: String? = null): Album1001StepResult =
        completeActive1001Album(status = ConsumptionStatus.CONSUMED, notes = notes)

    suspend fun abandonCurrent1001Album(notes: String? = null): Album1001StepResult =
        completeActive1001Album(status = ConsumptionStatus.ABANDONED, notes = notes)

    private suspend fun completeActive1001Album(
        status: ConsumptionStatus,
        notes: String?,
    ): Album1001StepResult = mutationMutex.withLock {
        ensure1001PoolSeeded()
        val gen =
            dao.getCollectionGenerator(ALBUMS_1001_COLLECTION_ID)
                ?: error("1001 Albums generator not initialized")
        val verb = if (status == ConsumptionStatus.CONSUMED) "finish" else "abandon"
        val currentId =
            gen.currentEntryId?.takeIf { it.isNotBlank() }?.let { followMergedId(it) }
                ?: error("No active 1001 album to $verb")
        val nowIso = nowUtcIso()
        appendConsumptionInDao(
            dao = dao,
            entryId = currentId,
            status = status,
            notes = notes,
            tsIso = nowIso,
            nowIso = nowIso,
            markDirty = true,
        )
        val nextId =
            advance1001GeneratorLocked(enabled = gen.enabled, nowIso = nowIso, markDirty = true)
        Album1001StepResult(
            entryId = currentId,
            nextAlbum = nextId?.let { resolveAlbumSnapshotById(it) },
        )
    }

    suspend fun upsertEntry(
        input: UpsertCatalogEntryInput,
        markDirty: Boolean = true,
    ): String = mutationMutex.withLock {
        ensureSystemSeeded()
        upsertEntryInDao(dao = dao, input = input, nowIso = nowUtcIso(), markDirty = markDirty)
    }

    suspend fun logEntry(
        input: UpsertCatalogEntryInput,
        status: ConsumptionStatus,
        notes: String? = null,
    ): String = mutationMutex.withLock {
        ensureSystemSeeded()
        val nowIso = nowUtcIso()
        val entryId = upsertEntryInDao(dao = dao, input = input, nowIso = nowIso, markDirty = true)
        appendConsumptionInDao(
            dao = dao,
            entryId = entryId,
            status = status,
            notes = notes,
            tsIso = nowIso,
            nowIso = nowIso,
            markDirty = true,
        )
        advance1001IfActiveLocked(entryId, status, nowIso)
        entryId
    }

    suspend fun logExistingEntry(
        entryId: String,
        status: ConsumptionStatus,
        notes: String? = null,
    ): String? = mutationMutex.withLock {
        ensureSystemSeeded()
        followMergedId(entryId)?.also { resolved ->
            val nowIso = nowUtcIso()
            appendConsumptionInDao(
                dao = dao,
                entryId = resolved,
                status = status,
                notes = notes,
                tsIso = nowIso,
                nowIso = nowIso,
                markDirty = true,
            )
            advance1001IfActiveLocked(resolved, status, nowIso)
        }
    }

    suspend fun wantEntry(
        input: UpsertCatalogEntryInput,
        targetQueueIdOrSlug: String? = null,
    ): String = mutationMutex.withLock {
        ensureSystemSeeded()
        val nowIso = nowUtcIso()
        val entryId = upsertEntryInDao(dao = dao, input = input, nowIso = nowIso, markDirty = true)
        val queueSpec = ensureWantCollectionInDao(dao, input.kind, targetQueueIdOrSlug, nowIso)
        addCollectionMemberInDao(
            dao = dao,
            spec = queueSpec,
            entryId = entryId,
            position = null,
            nowIso = nowIso,
            markDirty = true,
        )
        entryId
    }

    suspend fun createCustomList(
        title: String,
        role: CollectionRole = CollectionRole.LIST,
    ): String = mutationMutex.withLock {
        ensureSystemSeeded()
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "Collection title cannot be empty" }
        val rawSlug = slugifyTitle(cleanTitle)
        val slug = if (role == CollectionRole.WANT) "want:$rawSlug" else rawSlug
        val existing = dao.getCollectionSpecBySlug(slug)
        if (existing != null) {
            existing.collectionId
        } else {
            val id =
                if (role == CollectionRole.WANT) {
                    systemCollectionUuid(slug)
                } else {
                    systemCollectionUuid("custom:$slug")
                }
            val nowIso = nowUtcIso()
            dao.upsertCollectionSpec(
                CollectionSpecEntity(
                    collectionId = id,
                    title = cleanTitle,
                    slug = slug,
                    mode = CollectionMode.MANUAL.wireValue,
                    role = role.wireValue,
                    showInLists = role == CollectionRole.LIST,
                    createdAt = nowIso,
                    updatedAt = nowIso,
                    isLocalDirty = true,
                )
            )
            dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("slug", slug, id))
            id
        }
    }

    suspend fun addCollectionEntry(
        collectionIdOrSlug: String,
        entryId: String,
        position: Double? = null,
    ) = mutationMutex.withLock {
        ensureSystemSeeded()
        val spec =
            resolveCollectionSpec(collectionIdOrSlug)
                ?: error("Collection not found: $collectionIdOrSlug")
        val resolvedEntry = followMergedId(entryId) ?: error("Catalog entry not found: $entryId")
        addCollectionMemberInDao(
            dao = dao,
            spec = spec,
            entryId = resolvedEntry,
            position = position,
            nowIso = nowUtcIso(),
            markDirty = true,
        )
    }

    suspend fun removeCollectionEntry(
        collectionIdOrSlug: String,
        entryId: String,
    ): Boolean = mutationMutex.withLock {
        ensureSystemSeeded()
        val spec =
            resolveCollectionSpec(collectionIdOrSlug)
                ?: error("Collection not found: $collectionIdOrSlug")
        val mode = CollectionMode.fromWireValue(spec.mode)
        require(mode == CollectionMode.MANUAL) {
            "Cannot manually modify entries of ${mode.wireValue} collection '${spec.title}'"
        }
        val resolvedEntry = followMergedId(entryId) ?: entryId.trim()
        val existing =
            dao.getCollectionMemberships(spec.collectionId).any { it.entryId == resolvedEntry }
        if (existing) {
            val nowIso = nowUtcIso()
            dao.deleteCollectionEntry(spec.collectionId, resolvedEntry)
            dao.upsertTombstone(
                TombstoneEntity(
                    entityType = COLLECTION_ENTRY_ENTITY,
                    naturalKey = collectionEntryNaturalKey(spec.collectionId, resolvedEntry),
                    deletedAt = nowIso,
                )
            )
        }
        existing
    }

    suspend fun recordComparison(
        ordering: List<List<String>>,
        scopeCollectionId: String? = null,
    ): String = mutationMutex.withLock {
        ensureSystemSeeded()
        EpRankingEngine.validateOrdering(ordering).getOrThrow()
        val resolvedGroups = ordering.map { group ->
            group.map { id -> followMergedId(id) ?: id.trim() }.distinct()
        }
        EpRankingEngine.validateOrdering(resolvedGroups).getOrThrow()
        val cleanScope = scopeCollectionId?.trim()?.takeIf { it.isNotEmpty() }
        val now = Instant.now()
        val nowIso = now.toString()
        val compId =
            deterministicComparisonId(
                scopeCollectionId = cleanScope,
                tsMillis = now.toEpochMilli(),
                ordering = resolvedGroups,
            )
        dao.insertComparisonIgnore(
            ComparisonEntity(
                id = compId,
                ts = nowIso,
                scopeCollectionId = cleanScope,
                orderingJson = canonicalOrderingJson(resolvedGroups),
                createdAt = nowIso,
                isLocalDirty = true,
            )
        )
        compId
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
                    title = spec.title,
                    scopeCollectionId = spec.collectionId.takeIf { isPriorityScoped },
                )
            }
        }

    suspend fun resolveEntrySystemListSlug(entryId: String): String? = mutationMutex.withLock {
        ensureSystemSeeded()
        val resolved = followMergedId(entryId) ?: entryId.trim()
        dao.getCatalogEntryById(resolved)?.let { EntryKind.fromWireValue(it.kind).systemListSlug }
    }

    suspend fun resolveEntrySettlement(
        entryId: String,
        preferredCollectionIdOrSlug: String? = null,
    ): RankSettlementSummary? = mutationMutex.withLock {
        ensureSystemSeeded()
        val resolved = followMergedId(entryId) ?: entryId
        val snap = buildCatalogSnapshot(loadRawCatalogTables())
        val preferredSpec = preferredCollectionIdOrSlug?.let { resolveCollectionSpec(it) }
        val rawEntry = dao.getCatalogEntryById(resolved)
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
                collectionTitle = targetSpec.title,
                rank = match.rank,
                totalCount = rankedList.size,
                tier = match.tier,
            )
        }
    }

    suspend fun materializeFeedItemInCatalog(
        item: ItemEntity,
        feed: FeedEntity?,
        logConsumed: Boolean,
    ): String = mutationMutex.withLock {
        ensureSystemSeeded()
        materializeFeedItemInDao(
            dao = dao,
            item = item,
            feed = feed,
            logConsumed = logConsumed,
            markDirty = false,
            nowIso = nowUtcIso(),
        )
    }

    internal suspend fun materializeReferencedFeeds(referencedIds: Set<String>, nowIso: String) {
        materializeFeedsForIds(dao = dao, referencedIds = referencedIds, nowIso = nowIso)
    }

    internal suspend fun withMutationLock(block: suspend () -> Unit) {
        mutationMutex.withLock {
            ensureSystemSeeded()
            block()
        }
    }

    private suspend fun advance1001IfActiveLocked(
        entryId: String,
        status: ConsumptionStatus,
        nowIso: String,
    ) {
        if (status != ConsumptionStatus.CONSUMED && status != ConsumptionStatus.ABANDONED) return
        val gen = dao.getCollectionGenerator(ALBUMS_1001_COLLECTION_ID) ?: return
        if (gen.currentEntryId == entryId) {
            advance1001GeneratorLocked(enabled = gen.enabled, nowIso = nowIso, markDirty = true)
        }
    }

    private suspend fun advance1001GeneratorLocked(
        enabled: Boolean,
        nowIso: String,
        markDirty: Boolean,
    ): String? {
        val nextId = if (enabled) drawRandomRemaining1001Id() else null
        dao.upsertCollectionGenerator(
            CollectionGeneratorEntity(
                collectionId = ALBUMS_1001_COLLECTION_ID,
                enabled = enabled,
                currentEntryId = nextId,
                drawnAt = nextId?.let { nowIso },
                updatedAt = nowIso,
                isLocalDirty = markDirty,
            )
        )
        return nextId
    }

    internal suspend fun drawRandomRemaining1001Id(): String? {
        val poolMembers = dao.getCollectionMemberships(ALBUMS_1001_COLLECTION_ID).map { it.entryId }
        if (poolMembers.isEmpty()) return null
        val activeIds =
            dao.getAllCatalogEntries()
                .filter { it.mergedIntoId.isNullOrBlank() }
                .map { it.id }
                .toHashSet()
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

    private suspend fun resolveAlbumSnapshotById(entryId: String): DailyAlbumSnapshot? {
        val snap = buildCatalogSnapshot(loadRawCatalogTables())
        return snap.albumGenerator.currentAlbum?.takeIf { it.entryId == entryId }
    }

    internal suspend fun loadRawCatalogTables(): RawCatalogTables =
        RawCatalogTables(
            entries = dao.getAllCatalogEntries(),
            identifiers = dao.getAllEntryIdentifiers(),
            specs = dao.getAllCollectionSpecs(),
            memberships = dao.getAllCollectionEntries(),
            generators = dao.getAllCollectionGenerators(),
            logs = dao.getAllConsumptionLogs(),
            comparisons = dao.getAllComparisons(),
        )
}

private data class PartialCatalogTables(
    val entries: List<CatalogEntryEntity>,
    val identifiers: List<EntryIdentifierEntity>,
    val specs: List<CollectionSpecEntity>,
    val memberships: List<CollectionEntryEntity>,
)

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
