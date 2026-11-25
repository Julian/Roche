@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.catalog

import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.buildSqlContainsPattern
import com.grayvines.roche.data.local.buildSqlPrefixPattern
import com.grayvines.roche.data.local.extractFtsTokenQueries
import com.grayvines.roche.data.local.isDynamic
import com.grayvines.roche.ranking.EpRankingEngine
import com.grayvines.roche.ranking.RankClusterCandidate
import java.util.Locale
import kotlin.random.Random

internal const val DEFAULT_COLLECTION_PAGE_SIZE = 100
internal const val SMALL_COLLECTION_IN_MEMORY_LIMIT = 500
internal const val LARGE_RANK_UNCOMPARED_SAMPLE_SIZE = 64

@Suppress("DataClassContainsFunctions")
private data class BandedDynamicContext(
    val kind: EntryKind,
    val totalCount: Int,
    val comparedScores: Map<String, EpRankingEngine.Score>,
    val bands: ComparedBands,
    val quantiles: VirtualQuantileResult,
    val hasComparisons: Boolean,
    val minMean: Double,
    val maxMean: Double,
) {
    val nonZeroBandIds: List<String> = bands.bandAbove.map { it.id } + bands.bandBelow.map { it.id }

    val zeroBandTotalCount: Int =
        (totalCount - bands.bandAbove.size - bands.bandBelow.size).coerceAtLeast(0)

    val defaultTier: Char? =
        if (hasComparisons) quantiles.defaultBucket?.let(EpRankingEngine::tierLabel) else null

    fun tierFor(entryId: String): Char? {
        if (!hasComparisons) return null
        val bucket = quantiles.bucketByComparedId[entryId] ?: quantiles.defaultBucket ?: return null
        return EpRankingEngine.tierLabel(bucket)
    }

    fun toRanked(entry: CatalogEntryItem, rank: Int): RankedCatalogEntry {
        val score = comparedScores[entry.id] ?: EpRankingEngine.Score()
        return RankedCatalogEntry(
            entry = entry,
            rank = rank,
            mean = score.mean,
            variance = score.variance,
            scoreOutOfTen = EpRankingEngine.displayScoreOutOfTen(score.mean, minMean, maxMean),
            tier = tierFor(entry.id),
        )
    }
}

@Suppress("LongParameterList")
internal suspend fun loadCollectionPageFromDao(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    epScoreCache: EpScoreCache?,
    tierFilter: Char? = null,
    kindFilter: EntryKind? = null,
    kindFilters: Set<EntryKind> = emptySet(),
    limit: Int = DEFAULT_COLLECTION_PAGE_SIZE,
    offset: Int = 0,
): CatalogCollectionPageResult {
    val safeLimit = limit.coerceAtLeast(1)
    val safeOffset = offset.coerceAtLeast(0)
    val effectiveKinds = kindFilters.ifEmpty { kindFilter?.let(::setOf) ?: emptySet() }
    val largePage =
        loadLargeCollectionPageOrNull(
            dao = dao,
            spec = spec,
            epScoreCache = epScoreCache,
            tierFilter = tierFilter,
            kindFilters = effectiveKinds,
            limit = safeLimit,
            offset = safeOffset,
        )
    return largePage
        ?: loadSmallCollectionPage(
            dao = dao,
            spec = spec,
            epScoreCache = epScoreCache,
            tierFilter = tierFilter,
            kindFilters = effectiveKinds,
            limit = safeLimit,
            offset = safeOffset,
        )
}

@Suppress("LongParameterList")
private suspend fun loadLargeCollectionPageOrNull(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    epScoreCache: EpScoreCache?,
    tierFilter: Char?,
    kindFilters: Set<EntryKind>,
    limit: Int,
    offset: Int,
): CatalogCollectionPageResult? {
    val simpleKind = if (spec.isDynamic) parseSimpleConsumedKindRule(spec.ruleJson) else null
    if (simpleKind != null) {
        return loadLargeDynamicPageOrNull(
            dao = dao,
            collectionId = spec.collectionId,
            kind = simpleKind,
            epScoreCache = epScoreCache,
            tierFilter = tierFilter,
            kindFilters = kindFilters,
            limit = limit,
            offset = offset,
        )
    }
    if (!spec.isDynamic) {
        val hasGenerator =
            isGeneratorEligible(spec) && dao.getCollectionGenerator(spec.collectionId) != null
        val kindCounts = loadManualKindCounts(dao, spec.collectionId, hasGenerator)
        val totalCount = kindCounts.sumOf { it.second }
        if (totalCount > SMALL_COLLECTION_IN_MEMORY_LIMIT) {
            return loadLargeManualCollectionPage(
                dao = dao,
                spec = spec,
                hasGenerator = hasGenerator,
                totalCount = totalCount,
                kindCounts = kindCounts,
                epScoreCache = epScoreCache,
                tierFilter = tierFilter,
                kindFilters = kindFilters,
                limit = limit,
                offset = offset,
            )
        }
    }
    return null
}

@Suppress("LongParameterList")
private suspend fun loadLargeDynamicPageOrNull(
    dao: RocheDao,
    collectionId: String,
    kind: EntryKind,
    epScoreCache: EpScoreCache?,
    tierFilter: Char?,
    kindFilters: Set<EntryKind>,
    limit: Int,
    offset: Int,
): CatalogCollectionPageResult? {
    val totalCount = countConsumedEntriesForKind(dao, kind)
    if (totalCount <= SMALL_COLLECTION_IN_MEMORY_LIMIT) return null
    if (kindFilters.isNotEmpty() && kind !in kindFilters) {
        val ctx = buildBandedDynamicContext(dao, kind, totalCount, epScoreCache)
        return CatalogCollectionPageResult(
            collectionId = collectionId,
            entries = emptyList(),
            totalCount = totalCount,
            filteredTotalCount = 0,
            tierCounts = if (ctx.hasComparisons) ctx.quantiles.tierCounts else emptyMap(),
            kindCounts = listOf(kind to totalCount),
            hasMore = false,
        )
    }
    return loadLargeDynamicKindPage(
        dao = dao,
        collectionId = collectionId,
        kind = kind,
        totalCount = totalCount,
        epScoreCache = epScoreCache,
        tierFilter = tierFilter,
        limit = limit,
        offset = offset,
    )
}

@Suppress("LongParameterList")
private suspend fun loadSmallCollectionPage(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    epScoreCache: EpScoreCache?,
    tierFilter: Char?,
    kindFilters: Set<EntryKind>,
    limit: Int,
    offset: Int,
): CatalogCollectionPageResult {
    val allRanked = loadSmallCollectionRanked(dao, spec, epScoreCache)
    val tierCounts = computeRankedTierCounts(allRanked)
    val kindCounts = computeRankedKindCounts(allRanked)
    val isFiltered = tierFilter != null || kindFilters.isNotEmpty()
    val filtered =
        if (!isFiltered) {
            allRanked
        } else {
            allRanked
                .filter { item ->
                    (tierFilter == null || item.tier == tierFilter) &&
                        (kindFilters.isEmpty() || item.entry.kind in kindFilters)
                }
                .mapIndexed { idx, item -> item.copy(rank = idx + 1) }
        }
    val page =
        if (offset >= filtered.size) {
            emptyList()
        } else {
            val takeCount = minOf(limit, filtered.size - offset)
            filtered.subList(offset, offset + takeCount)
        }
    return CatalogCollectionPageResult(
        collectionId = spec.collectionId,
        entries = page,
        totalCount = allRanked.size,
        filteredTotalCount = filtered.size,
        tierCounts = tierCounts,
        kindCounts = kindCounts,
        hasMore = offset + page.size < filtered.size,
    )
}

internal suspend fun loadSmallCollectionRanked(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    epScoreCache: EpScoreCache?,
): List<RankedCatalogEntry> {
    val role = CollectionRole.fromWireValue(spec.role)
    val isWant = role == CollectionRole.WANT || spec.collectionId == UP_NEXT_COLLECTION_ID
    val hasGenerator =
        isGeneratorEligible(spec) && dao.getCollectionGenerator(spec.collectionId) != null
    val memberEntries = loadCollectionMembersFromDao(dao, spec, hasGenerator)
    if (memberEntries.isEmpty()) return emptyList()
    val mergeRedirects = loadMergeRedirectsMap(dao)
    val ep =
        if (isWant) {
            resolveScopedEpScores(dao, spec.collectionId, mergeRedirects, epScoreCache)
        } else {
            resolveGlobalEpScores(dao, mergeRedirects, epScoreCache)
        }
    return rankEntriesByOrderings(
        entries = memberEntries,
        isPriorityScoped = isWant,
        orderings = ep.orderings,
        precomputedScores = ep.scores,
    )
}

private suspend fun loadCollectionMembersFromDao(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    hasGenerator: Boolean,
): List<CatalogEntryItem> {
    if (!spec.isDynamic) {
        val raw =
            if (hasGenerator) {
                dao.getConsumedCollectionEntries(spec.collectionId)
            } else {
                dao.getActiveEntriesForCollection(spec.collectionId)
            }
        return hydrateDomainEntriesFromDao(dao, raw)
    }
    val simpleKind = parseSimpleConsumedKindRule(spec.ruleJson)
    if (simpleKind != null) {
        return hydrateDomainEntriesFromDao(
            dao,
            dao.getActiveConsumedEntriesByKind(simpleKind.wireValue),
        )
    }
    return loadCustomDynamicEntries(dao, spec)
}

@Suppress("LongParameterList")
private suspend fun loadLargeDynamicKindPage(
    dao: RocheDao,
    collectionId: String,
    kind: EntryKind,
    totalCount: Int,
    epScoreCache: EpScoreCache?,
    tierFilter: Char?,
    limit: Int,
    offset: Int,
): CatalogCollectionPageResult {
    val ctx = buildBandedDynamicContext(dao, kind, totalCount, epScoreCache)
    val tierCounts = if (ctx.hasComparisons) ctx.quantiles.tierCounts else emptyMap()
    if (tierFilter != null && tierFilter != ctx.defaultTier) {
        val matched = collectComparedRankedEntries(ctx).filter { it.tier == tierFilter }
        val slice =
            if (offset >= matched.size) {
                emptyList()
            } else {
                val takeCount = minOf(limit, matched.size - offset)
                matched.subList(offset, offset + takeCount)
            }
        return CatalogCollectionPageResult(
            collectionId = collectionId,
            entries = slice,
            totalCount = totalCount,
            filteredTotalCount = matched.size,
            tierCounts = tierCounts,
            kindCounts = if (totalCount > 0) listOf(kind to totalCount) else emptyList(),
            hasMore = offset + slice.size < matched.size,
        )
    }
    return loadBandedPageWithZeroBand(dao, collectionId, ctx, tierFilter, tierCounts, limit, offset)
}

@Suppress("LongParameterList")
private suspend fun loadBandedPageWithZeroBand(
    dao: RocheDao,
    collectionId: String,
    ctx: BandedDynamicContext,
    tierFilter: Char?,
    tierCounts: Map<Char, Int>,
    limit: Int,
    offset: Int,
): CatalogCollectionPageResult {
    val aboveFiltered =
        ctx.bands.bandAbove
            .mapIndexed { idx, entry -> ctx.toRanked(entry, idx + 1) }
            .filter { tierFilter == null || it.tier == tierFilter }
    val belowBaseRank = ctx.totalCount - ctx.bands.bandBelow.size + 1
    val belowFiltered =
        ctx.bands.bandBelow
            .mapIndexed { idx, entry -> ctx.toRanked(entry, belowBaseRank + idx) }
            .filter { tierFilter == null || it.tier == tierFilter }
    val zeroTotal = ctx.zeroBandTotalCount
    val filteredTotal = aboveFiltered.size + zeroTotal + belowFiltered.size
    val initCap = minOf(limit, (filteredTotal - offset).coerceAtLeast(0), MAX_DYNAMIC_SLICE_CAP)
    val result = ArrayList<RankedCatalogEntry>(initCap)
    var cursor = offset
    var remaining = limit

    if (remaining > 0 && cursor < aboveFiltered.size) {
        val takeCount = minOf(remaining, aboveFiltered.size - cursor)
        result.addAll(aboveFiltered.subList(cursor, cursor + takeCount))
        remaining -= takeCount
        cursor += takeCount
    }
    val zeroStartOffset = (cursor - aboveFiltered.size).coerceAtLeast(0)
    if (remaining > 0 && zeroStartOffset < zeroTotal) {
        val zeroRaw = fetchZeroBandSqlSlice(dao, ctx, zeroStartOffset, remaining)
        val zeroDomain = hydrateDomainEntriesFromDao(dao, zeroRaw)
        val rankBase = ctx.bands.bandAbove.size + zeroStartOffset + 1
        zeroDomain.forEachIndexed { idx, entry ->
            result.add(ctx.toRanked(entry, rankBase + idx))
        }
        remaining -= zeroDomain.size
        cursor += zeroDomain.size
    }
    val belowStartOffset = (cursor - aboveFiltered.size - zeroTotal).coerceAtLeast(0)
    if (remaining > 0 && belowStartOffset < belowFiltered.size) {
        val takeCount = minOf(remaining, belowFiltered.size - belowStartOffset)
        result.addAll(belowFiltered.subList(belowStartOffset, belowStartOffset + takeCount))
    }
    return CatalogCollectionPageResult(
        collectionId = collectionId,
        entries = result,
        totalCount = ctx.totalCount,
        filteredTotalCount = filteredTotal,
        tierCounts = tierCounts,
        kindCounts = if (ctx.totalCount > 0) listOf(ctx.kind to ctx.totalCount) else emptyList(),
        hasMore = offset + result.size < filteredTotal,
    )
}

private suspend fun fetchZeroBandSqlSlice(
    dao: RocheDao,
    ctx: BandedDynamicContext,
    zeroOffset: Int,
    limit: Int,
): List<EntryEntity> {
    val excluded = ctx.nonZeroBandIds
    return when {
        excluded.isEmpty() -> dao.getConsumedKindEntriesPage(ctx.kind.wireValue, limit, zeroOffset)
        excluded.size <= CATALOG_SQL_BIND_CHUNK_SIZE ->
            dao.getConsumedKindPageExcluding(
                kind = ctx.kind.wireValue,
                excludedIds = excluded,
                limit = limit,
                offset = zeroOffset,
            )
        else ->
            collectNonExcludedZeroSlice(
                dao,
                ctx.kind.wireValue,
                excluded.toHashSet(),
                zeroOffset,
                limit,
            )
    }
}

private suspend fun collectNonExcludedZeroSlice(
    dao: RocheDao,
    kindWire: String,
    excludedSet: Set<String>,
    zeroOffset: Int,
    limit: Int,
): List<EntryEntity> {
    val collected = ArrayList<EntryEntity>(limit)
    var skipped = 0
    var sqlOffset = 0
    val batchSize = maxOf(limit * 2, DEFAULT_COLLECTION_PAGE_SIZE)
    while (collected.size < limit) {
        val batch = dao.getConsumedKindEntriesPage(kindWire, batchSize, sqlOffset)
        if (batch.isEmpty()) break
        sqlOffset += batch.size
        val eligible = batch.filterNot { it.id in excludedSet }
        val toSkip = minOf(eligible.size, zeroOffset - skipped)
        skipped += toSkip
        val remaining = limit - collected.size
        if (toSkip < eligible.size && remaining > 0) {
            collected.addAll(eligible.subList(toSkip, minOf(eligible.size, toSkip + remaining)))
        }
    }
    return collected
}

private suspend fun buildBandedDynamicContext(
    dao: RocheDao,
    kind: EntryKind,
    totalCount: Int,
    epScoreCache: EpScoreCache?,
): BandedDynamicContext {
    val mergeRedirects = loadMergeRedirectsMap(dao)
    val globalEp = resolveGlobalEpScores(dao, mergeRedirects, epScoreCache)
    val comparedConsumedIds = loadComparedConsumedIdsForKind(dao, kind, globalEp.scores.keys)
    val comparedRaw = loadEntriesByIdsChunked(dao, comparedConsumedIds)
    val comparedEntries = hydrateDomainEntriesFromDao(dao, comparedRaw)
    val comparedScores = comparedEntries.associate {
        it.id to (globalEp.scores[it.id] ?: EpRankingEngine.Score())
    }
    val uncomparedCount = (totalCount - comparedEntries.size).coerceAtLeast(0)
    val bands = splitComparedBands(comparedEntries, comparedScores, uncomparedCount > 0)
    val orderedIds = globalEp.orderings.asSequence().flatten().flatten().toHashSet()
    val hasComparisons = comparedEntries.any { it.id in orderedIds }
    val quantiles =
        if (hasComparisons) {
            computeQuantilesWithUncompared(comparedScores, uncomparedCount)
        } else {
            VirtualQuantileResult()
        }
    val comparedMin = comparedScores.values.minOfOrNull { it.mean }
    val comparedMax = comparedScores.values.maxOfOrNull { it.mean }
    val minMean =
        when {
            comparedMin == null -> 0.0
            uncomparedCount > 0 -> minOf(comparedMin, 0.0)
            else -> comparedMin
        }
    val maxMean =
        when {
            comparedMax == null -> 0.0
            uncomparedCount > 0 -> maxOf(comparedMax, 0.0)
            else -> comparedMax
        }
    return BandedDynamicContext(
        kind = kind,
        totalCount = totalCount,
        comparedScores = comparedScores,
        bands = bands,
        quantiles = quantiles,
        hasComparisons = hasComparisons,
        minMean = minMean,
        maxMean = maxMean,
    )
}

private fun collectComparedRankedEntries(ctx: BandedDynamicContext): List<RankedCatalogEntry> {
    val above = ctx.bands.bandAbove.mapIndexed { idx, entry -> ctx.toRanked(entry, idx + 1) }
    val belowBase = ctx.totalCount - ctx.bands.bandBelow.size + 1
    val below =
        ctx.bands.bandBelow.mapIndexed { idx, entry -> ctx.toRanked(entry, belowBase + idx) }
    return above + below
}

@Suppress("LongParameterList")
internal suspend fun selectNextRankClusterFromDao(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    epScoreCache: EpScoreCache?,
    focusEntryId: String?,
    roundIndex: Int,
    forceRandomNext: Boolean,
    maxK: Int,
    excludedOpponentIds: Set<String>,
    random: Random,
): List<RankClusterCandidate> {
    val resolvedFocus = focusEntryId?.let { followMergedIdInDao(dao, it) ?: it }
    val simpleKind = if (spec.isDynamic) parseSimpleConsumedKindRule(spec.ruleJson) else null
    val candidates =
        when {
            simpleKind != null &&
                countConsumedEntriesForKind(dao, simpleKind) > SMALL_COLLECTION_IN_MEMORY_LIMIT ->
                loadLargeKindRankCandidates(
                    dao = dao,
                    kind = simpleKind,
                    epScoreCache = epScoreCache,
                    focusEntryId = resolvedFocus,
                    excludedOpponentIds = excludedOpponentIds,
                )
            !spec.isDynamic ->
                loadManualRankCandidates(
                    dao = dao,
                    spec = spec,
                    epScoreCache = epScoreCache,
                    focusEntryId = resolvedFocus,
                    excludedOpponentIds = excludedOpponentIds,
                )
            else -> loadSmallCollectionRanked(dao, spec, epScoreCache)
        }
    if (candidates.size < EpRankingEngine.MIN_K) return emptyList()
    return pickClusterCandidates(
        members = candidates,
        resolvedFocus = resolvedFocus,
        roundIndex = roundIndex,
        forceRandomNext = forceRandomNext,
        maxK = maxK,
        excludedOpponentIds = excludedOpponentIds,
        random = random,
    )
}

private suspend fun loadManualRankCandidates(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    epScoreCache: EpScoreCache?,
    focusEntryId: String?,
    excludedOpponentIds: Set<String>,
): List<RankedCatalogEntry> {
    val hasGenerator =
        isGeneratorEligible(spec) && dao.getCollectionGenerator(spec.collectionId) != null
    val totalCount = loadManualKindCounts(dao, spec.collectionId, hasGenerator).sumOf { it.second }
    return if (totalCount > SMALL_COLLECTION_IN_MEMORY_LIMIT) {
        loadLargeManualRankCandidates(
            dao = dao,
            spec = spec,
            hasGenerator = hasGenerator,
            epScoreCache = epScoreCache,
            focusEntryId = focusEntryId,
            excludedOpponentIds = excludedOpponentIds,
        )
    } else {
        loadSmallCollectionRanked(dao, spec, epScoreCache)
    }
}

private suspend fun loadLargeKindRankCandidates(
    dao: RocheDao,
    kind: EntryKind,
    epScoreCache: EpScoreCache?,
    focusEntryId: String?,
    excludedOpponentIds: Set<String>,
): List<RankedCatalogEntry> {
    val mergeRedirects = loadMergeRedirectsMap(dao)
    val globalEp = resolveGlobalEpScores(dao, mergeRedirects, epScoreCache)
    val comparedIds = loadComparedConsumedIdsForKind(dao, kind, globalEp.scores.keys)
    val rawById =
        LinkedHashMap<String, EntryEntity>(comparedIds.size + LARGE_RANK_UNCOMPARED_SAMPLE_SIZE + 1)
    val sampleRaw =
        dao.getRandomConsumedKindEntries(
            kind = kind.wireValue,
            limit = LARGE_RANK_UNCOMPARED_SAMPLE_SIZE + excludedOpponentIds.size,
        )
    for (row in sampleRaw) {
        if (row.id !in excludedOpponentIds) rawById[row.id] = row
    }
    val missingIds = LinkedHashSet<String>()
    for (id in comparedIds) {
        if (id !in rawById) missingIds.add(id)
    }
    if (
        focusEntryId != null && focusEntryId !in rawById && dao.hasConsumedLogForEntry(focusEntryId)
    ) {
        missingIds.add(focusEntryId)
    }
    for (row in loadEntriesByIdsChunked(dao, missingIds.toList())) {
        if (row.mergedIntoId.isNullOrBlank() && row.kind == kind.wireValue) {
            rawById[row.id] = row
        }
    }
    val domainEntries = hydrateDomainEntriesFromDao(dao, rawById.values.toList())
    return domainEntries.mapIndexed { idx, entry ->
        val score = globalEp.scores[entry.id] ?: EpRankingEngine.Score()
        RankedCatalogEntry(
            entry = entry,
            rank = idx + 1,
            mean = score.mean,
            variance = score.variance,
            scoreOutOfTen = 5.0,
            tier = null,
        )
    }
}

@Suppress("LongParameterList")
private fun pickClusterCandidates(
    members: List<RankedCatalogEntry>,
    resolvedFocus: String?,
    roundIndex: Int,
    forceRandomNext: Boolean,
    maxK: Int,
    excludedOpponentIds: Set<String>,
    random: Random,
): List<RankClusterCandidate> {
    val filteredMembers =
        if (excludedOpponentIds.isEmpty()) {
            members
        } else {
            val reduced = members.filter { ranked ->
                ranked.entry.id == resolvedFocus || ranked.entry.id !in excludedOpponentIds
            }
            if (reduced.size < EpRankingEngine.MIN_K) return emptyList()
            reduced
        }
    val memberIds = filteredMembers.map { it.entry.id }
    val scores = filteredMembers.associate { ranked ->
        ranked.entry.id to EpRankingEngine.Score(ranked.mean, ranked.variance)
    }
    val seedPool = resolvedFocus?.takeIf { it in scores }?.let(::listOf)
    val forceRandom =
        forceRandomNext ||
            seedPool == null &&
                roundIndex > 0 &&
                roundIndex % EpRankingEngine.RANDOM_SEED_EVERY == 0
    val selected =
        EpRankingEngine.selectClusterPrioritized(
            scores = scores,
            itemIds = memberIds,
            maxK = maxK,
            forceRandomSeed = forceRandom,
            seedPool = seedPool,
            random = random,
        )
    if (selected.orderedIds.size < EpRankingEngine.MIN_K) return emptyList()
    val priorityById = selected.priorityIds.withIndex().associate { (idx, id) -> id to idx }
    val byId = filteredMembers.associateBy { it.entry.id }
    return selected.orderedIds.mapIndexedNotNull { idx, id ->
        byId[id]?.toRankCandidate(selectionPriority = priorityById[id] ?: idx)
    }
}

internal suspend fun resolveCollectionMemberCount(
    dao: RocheDao,
    spec: CollectionSpecEntity,
): Int {
    if (!spec.isDynamic) {
        val hasGen =
            isGeneratorEligible(spec) && dao.getCollectionGenerator(spec.collectionId) != null
        val agg =
            dao.getManualCollectionAggregates().firstOrNull { it.collectionId == spec.collectionId }
        return if (hasGen) agg?.consumedCount ?: 0 else agg?.totalCount ?: 0
    }
    val simpleKind = parseSimpleConsumedKindRule(spec.ruleJson)
    if (simpleKind != null) {
        return countConsumedEntriesForKind(dao, simpleKind)
    }
    return loadCustomDynamicEntries(dao, spec).size
}

@Suppress("LongParameterList")
internal suspend fun resolveEntrySettlementFromDao(
    dao: RocheDao,
    entryId: String,
    targetSpec: CollectionSpecEntity,
    specTitle: String,
    epScoreCache: EpScoreCache?,
    includeNeighbors: Boolean = true,
): RankSettlementSummary? {
    val simpleKind =
        if (targetSpec.isDynamic) parseSimpleConsumedKindRule(targetSpec.ruleJson) else null
    val largeTotal = simpleKind?.let { kind ->
        countConsumedEntriesForKind(dao, kind).takeIf { it > SMALL_COLLECTION_IN_MEMORY_LIMIT }
    }
    if (simpleKind != null && largeTotal != null) {
        return resolveLargeDynamicSettlement(
            dao = dao,
            entryId = entryId,
            kind = simpleKind,
            totalCount = largeTotal,
            specTitle = specTitle,
            epScoreCache = epScoreCache,
            includeNeighbors = includeNeighbors,
        )
    }
    if (!targetSpec.isDynamic) {
        val hasGenerator =
            isGeneratorEligible(targetSpec) &&
                dao.getCollectionGenerator(targetSpec.collectionId) != null
        val kindCounts = loadManualKindCounts(dao, targetSpec.collectionId, hasGenerator)
        val manualTotal = kindCounts.sumOf { it.second }
        if (manualTotal > SMALL_COLLECTION_IN_MEMORY_LIMIT) {
            return resolveLargeManualSettlement(
                dao = dao,
                entryId = entryId,
                spec = targetSpec,
                hasGenerator = hasGenerator,
                totalCount = manualTotal,
                kindCounts = kindCounts,
                specTitle = specTitle,
                epScoreCache = epScoreCache,
                includeNeighbors = includeNeighbors,
            )
        }
    }
    return resolveSmallListSettlement(
        dao = dao,
        entryId = entryId,
        targetSpec = targetSpec,
        specTitle = specTitle,
        epScoreCache = epScoreCache,
        includeNeighbors = includeNeighbors,
    )
}

@Suppress("LongParameterList")
private suspend fun resolveSmallListSettlement(
    dao: RocheDao,
    entryId: String,
    targetSpec: CollectionSpecEntity,
    specTitle: String,
    epScoreCache: EpScoreCache?,
    includeNeighbors: Boolean,
): RankSettlementSummary? {
    val rankedList = loadSmallCollectionRanked(dao, targetSpec, epScoreCache)
    val idx = rankedList.indexOfFirst { it.entry.id == entryId }
    if (idx < 0) return null
    val match = rankedList[idx]
    val neighbors =
        if (!includeNeighbors || rankedList.size < 2) {
            emptyList()
        } else {
            val start = (idx - 1).coerceAtLeast(0)
            val end = (idx + 1).coerceAtMost(rankedList.lastIndex)
            rankedList.subList(start, end + 1)
        }
    return RankSettlementSummary(
        entryId = entryId,
        title = match.entry.title,
        collectionTitle = specTitle,
        rank = match.rank,
        totalCount = rankedList.size,
        tier = match.tier,
        subtitle = match.entry.subtitleLine,
        thumbnailUrl = match.entry.thumbnailUrl,
        neighbors = neighbors,
    )
}

@Suppress("LongParameterList")
private suspend fun resolveLargeDynamicSettlement(
    dao: RocheDao,
    entryId: String,
    kind: EntryKind,
    totalCount: Int,
    specTitle: String,
    epScoreCache: EpScoreCache?,
    includeNeighbors: Boolean,
): RankSettlementSummary? {
    val raw = dao.getEntryById(entryId)?.takeIf { it.mergedIntoId.isNullOrBlank() } ?: return null
    if (EntryKind.fromWireValue(raw.kind) != kind || !dao.hasConsumedLogForEntry(entryId)) {
        return null
    }
    val ctx = buildBandedDynamicContext(dao, kind, totalCount, epScoreCache)
    val placement = resolveLargeKindPlacement(dao, raw, ctx, includeNeighbors)
    val entry = placement.ranked.entry
    return RankSettlementSummary(
        entryId = entryId,
        title = entry.title,
        collectionTitle = specTitle,
        rank = placement.ranked.rank,
        totalCount = totalCount,
        tier = placement.ranked.tier,
        subtitle = entry.subtitleLine,
        thumbnailUrl = entry.thumbnailUrl,
        neighbors = placement.neighbors,
    )
}

internal data class EntryPlacementWithNeighbors(
    val ranked: RankedCatalogEntry,
    val neighbors: List<RankedCatalogEntry>,
)

private suspend fun resolveLargeKindPlacement(
    dao: RocheDao,
    raw: EntryEntity,
    ctx: BandedDynamicContext,
    includeNeighbors: Boolean,
): EntryPlacementWithNeighbors {
    val aboveIdx = ctx.bands.bandAbove.indexOfFirst { it.id == raw.id }
    if (aboveIdx >= 0) {
        val ranked = ctx.toRanked(ctx.bands.bandAbove[aboveIdx], aboveIdx + 1)
        val neighbors =
            if (includeNeighbors) {
                buildAboveBandNeighbors(dao, ctx, aboveIdx, ranked)
            } else {
                emptyList()
            }
        return EntryPlacementWithNeighbors(ranked, neighbors)
    }
    val belowIdx = ctx.bands.bandBelow.indexOfFirst { it.id == raw.id }
    if (belowIdx >= 0) {
        val rank = ctx.totalCount - ctx.bands.bandBelow.size + belowIdx + 1
        val ranked = ctx.toRanked(ctx.bands.bandBelow[belowIdx], rank)
        val neighbors =
            if (includeNeighbors) {
                buildBelowBandNeighbors(dao, ctx, belowIdx, ranked)
            } else {
                emptyList()
            }
        return EntryPlacementWithNeighbors(ranked, neighbors)
    }
    val zeroPos = countZeroBandEntriesBefore(dao, ctx, raw)
    val rank = ctx.bands.bandAbove.size + zeroPos + 1
    val domain = hydrateDomainEntriesFromDao(dao, listOf(raw)).single()
    val ranked = ctx.toRanked(domain, rank)
    val neighbors =
        if (includeNeighbors) buildZeroBandNeighbors(dao, ctx, raw, ranked) else emptyList()
    return EntryPlacementWithNeighbors(ranked, neighbors)
}

private suspend fun buildAboveBandNeighbors(
    dao: RocheDao,
    ctx: BandedDynamicContext,
    aboveIdx: Int,
    self: RankedCatalogEntry,
): List<RankedCatalogEntry> = buildList {
    if (aboveIdx > 0) add(ctx.toRanked(ctx.bands.bandAbove[aboveIdx - 1], aboveIdx))
    add(self)
    when {
        aboveIdx + 1 < ctx.bands.bandAbove.size ->
            add(ctx.toRanked(ctx.bands.bandAbove[aboveIdx + 1], aboveIdx + 2))
        ctx.zeroBandTotalCount > 0 ->
            fetchZeroBandSqlSlice(dao, ctx, 0, 1).firstOrNull()?.let { nextRaw ->
                val nextDomain = hydrateDomainEntriesFromDao(dao, listOf(nextRaw)).single()
                add(ctx.toRanked(nextDomain, self.rank + 1))
            }
        ctx.bands.bandBelow.isNotEmpty() ->
            add(ctx.toRanked(ctx.bands.bandBelow.first(), self.rank + 1))
    }
}

private suspend fun buildBelowBandNeighbors(
    dao: RocheDao,
    ctx: BandedDynamicContext,
    belowIdx: Int,
    self: RankedCatalogEntry,
): List<RankedCatalogEntry> = buildList {
    when {
        belowIdx > 0 -> add(ctx.toRanked(ctx.bands.bandBelow[belowIdx - 1], self.rank - 1))
        ctx.zeroBandTotalCount > 0 ->
            fetchZeroBandSqlSlice(dao, ctx, ctx.zeroBandTotalCount - 1, 1).firstOrNull()?.let { prev
                ->
                val prevDomain = hydrateDomainEntriesFromDao(dao, listOf(prev)).single()
                add(ctx.toRanked(prevDomain, self.rank - 1))
            }
        ctx.bands.bandAbove.isNotEmpty() ->
            add(ctx.toRanked(ctx.bands.bandAbove.last(), self.rank - 1))
    }
    add(self)
    if (belowIdx + 1 < ctx.bands.bandBelow.size) {
        add(ctx.toRanked(ctx.bands.bandBelow[belowIdx + 1], self.rank + 1))
    }
}

private suspend fun buildZeroBandNeighbors(
    dao: RocheDao,
    ctx: BandedDynamicContext,
    raw: EntryEntity,
    self: RankedCatalogEntry,
): List<RankedCatalogEntry> = buildList {
    val prevRaw = findZeroBandNeighbor(dao, ctx, raw, forward = false)
    if (prevRaw != null) {
        val prevDomain = hydrateDomainEntriesFromDao(dao, listOf(prevRaw)).single()
        add(ctx.toRanked(prevDomain, self.rank - 1))
    } else if (ctx.bands.bandAbove.isNotEmpty()) {
        add(ctx.toRanked(ctx.bands.bandAbove.last(), self.rank - 1))
    }
    add(self)
    val nextRaw = findZeroBandNeighbor(dao, ctx, raw, forward = true)
    if (nextRaw != null) {
        val nextDomain = hydrateDomainEntriesFromDao(dao, listOf(nextRaw)).single()
        add(ctx.toRanked(nextDomain, self.rank + 1))
    } else if (ctx.bands.bandBelow.isNotEmpty()) {
        add(ctx.toRanked(ctx.bands.bandBelow.first(), self.rank + 1))
    }
}

private suspend fun findZeroBandNeighbor(
    dao: RocheDao,
    ctx: BandedDynamicContext,
    raw: EntryEntity,
    forward: Boolean,
): EntryEntity? {
    val excluded = ctx.nonZeroBandIds
    val kindWire = ctx.kind.wireValue
    return when {
        excluded.isEmpty() -> {
            if (forward) {
                dao.getConsumedKindNext(kindWire, raw.title, raw.id)
            } else {
                dao.getConsumedKindPrev(kindWire, raw.title, raw.id)
            }
        }
        excluded.size <= CATALOG_SQL_BIND_CHUNK_SIZE -> {
            if (forward) {
                dao.getConsumedKindNextExcl(kindWire, raw.title, raw.id, excluded)
            } else {
                dao.getConsumedKindPrevExcl(kindWire, raw.title, raw.id, excluded)
            }
        }
        else -> {
            stepZeroBandNeighborBatch(dao, kindWire, raw, excluded.toHashSet(), forward)
        }
    }
}

private suspend fun stepZeroBandNeighborBatch(
    dao: RocheDao,
    kindWire: String,
    origin: EntryEntity,
    excludedSet: Set<String>,
    forward: Boolean,
): EntryEntity? {
    var cursorTitle = origin.title
    var cursorId = origin.id
    var match: EntryEntity? = null
    while (match == null) {
        val batch =
            if (forward) {
                dao.getConsumedKindNextBatch(
                    kindWire,
                    cursorTitle,
                    cursorId,
                    ZERO_NEIGHBOR_BATCH_SIZE,
                )
            } else {
                dao.getConsumedKindPrevBatch(
                    kindWire,
                    cursorTitle,
                    cursorId,
                    ZERO_NEIGHBOR_BATCH_SIZE,
                )
            }
        match = batch.firstOrNull { it.id !in excludedSet }
        if (match != null || batch.size < ZERO_NEIGHBOR_BATCH_SIZE) break
        val last = batch.last()
        cursorTitle = last.title
        cursorId = last.id
    }
    return match
}

private suspend fun countZeroBandEntriesBefore(
    dao: RocheDao,
    ctx: BandedDynamicContext,
    raw: EntryEntity,
): Int {
    val excluded = ctx.nonZeroBandIds
    return when {
        excluded.isEmpty() -> dao.countConsumedKindBefore(ctx.kind.wireValue, raw.title, raw.id)
        excluded.size <= CATALOG_SQL_BIND_CHUNK_SIZE ->
            dao.countConsumedKindBeforeExcl(ctx.kind.wireValue, raw.title, raw.id, excluded)
        else -> countZeroBandBeforeOverflow(dao, ctx, raw)
    }
}

private suspend fun countZeroBandBeforeOverflow(
    dao: RocheDao,
    ctx: BandedDynamicContext,
    raw: EntryEntity,
): Int {
    val rawCount = dao.countConsumedKindBefore(ctx.kind.wireValue, raw.title, raw.id)
    val asciiTarget = asciiLowercase(raw.title)
    val excludedBefore =
        (ctx.bands.bandAbove + ctx.bands.bandBelow).count { item ->
            val cmp = asciiLowercase(item.title).compareTo(asciiTarget)
            cmp < 0 || cmp == 0 && item.id < raw.id
        }
    return (rawCount - excludedBefore).coerceAtLeast(0)
}

/** Matches SQLite's built-in ASCII-only `LOWER()` semantics for exact cursor parity. */
internal fun asciiLowercase(value: String): String {
    var hasUpper = false
    for (ch in value) {
        if (ch in 'A'..'Z') {
            hasUpper = true
            break
        }
    }
    if (!hasUpper) return value
    return buildString(value.length) {
        for (ch in value) {
            append(if (ch in 'A'..'Z') (ch.code + ASCII_CASE_DELTA).toChar() else ch)
        }
    }
}

internal suspend fun loadCatalogEntryFromDao(
    dao: RocheDao,
    entryId: String,
): CatalogEntryItem? {
    val resolvedId = followMergedIdInDao(dao, entryId) ?: entryId.trim()
    if (resolvedId.isEmpty()) return null
    val raw =
        dao.getEntryById(resolvedId)?.takeIf { it.mergedIntoId.isNullOrBlank() } ?: return null
    val idents = dao.getIdentifiersForEntry(raw.id).map { EntryIdentifierDto(it.scheme, it.value) }
    return toDomainCatalogEntry(raw, idents)
}

@Suppress("LongParameterList")
internal suspend fun loadEntryDetailFromDao(
    dao: RocheDao,
    entryId: String,
    previousEntryId: String?,
    globalTiers: GlobalTierIndex,
    epScoreCache: EpScoreCache?,
): CatalogEntryDetailResult? {
    val entry = loadCatalogEntryFromDao(dao, entryId) ?: return null
    val statuses =
        dao.getStatusesForEntry(entry.id).mapTo(HashSet()) { ConsumptionStatus.fromWireValue(it) }
    val memberships = dao.getCollectionEntriesForEntry(entry.id)
    val memberColIds = memberships.mapTo(HashSet()) { it.collectionId }
    val allSpecs = dao.getAllCollectionSpecs()
    val specsById = allSpecs.associateBy { it.collectionId }
    val titlesById = loadEntryTitlesChunked(dao, allSpecs.map { it.collectionId })
    val isStarred = STARRED_COLLECTION_ID in memberColIds
    val isInWantQueue = memberColIds.any { colId ->
        colId == WANT_TO_READ_COLLECTION_ID ||
            colId == UP_NEXT_COLLECTION_ID ||
            CollectionRole.fromWireValue(specsById[colId]?.role) == CollectionRole.WANT
    }
    val appearances =
        loadAppearancesForEntry(
            dao = dao,
            entry = entry,
            statuses = statuses,
            memberColIds = memberColIds,
            allSpecs = allSpecs,
            titlesById = titlesById,
            epScoreCache = epScoreCache,
        )
    val matchingArtistId =
        if (entry.kind == EntryKind.ALBUM && entry.primaryCreator.isNotBlank()) {
            dao.findActiveArtistByTitle(entry.primaryCreator)?.id
        } else {
            null
        }
    val artistLibraryAlbums =
        if (entry.kind == EntryKind.ARTIST) {
            loadArtistLibraryAlbumsFromDao(dao, entry.title, globalTiers)
        } else {
            emptyList()
        }
    val previousEntryTitle =
        previousEntryId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { prevId ->
                val resolvedPrev = followMergedIdInDao(dao, prevId) ?: prevId
                dao.getEntryById(resolvedPrev)?.title
            }
    return CatalogEntryDetailResult(
        entry = entry,
        appearances = appearances,
        matchingArtistId = matchingArtistId,
        artistLibraryAlbums = artistLibraryAlbums,
        isStarred = isStarred,
        isInWantQueue = isInWantQueue,
        previousEntryTitle = previousEntryTitle,
    )
}

@Suppress("LongParameterList")
private suspend fun loadAppearancesForEntry(
    dao: RocheDao,
    entry: CatalogEntryItem,
    statuses: Set<ConsumptionStatus>,
    memberColIds: Set<String>,
    allSpecs: List<CollectionSpecEntity>,
    titlesById: Map<String, String>,
    epScoreCache: EpScoreCache?,
): List<CatalogListAppearance> =
    allSpecs
        .mapNotNull { spec ->
            resolveAppearanceForSpec(
                dao = dao,
                spec = spec,
                entry = entry,
                statuses = statuses,
                memberColIds = memberColIds,
                titlesById = titlesById,
                epScoreCache = epScoreCache,
            )
        }
        .sortedWith(
            compareByDescending<CatalogListAppearance> { it.isDynamic }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        )

@Suppress("LongParameterList")
private suspend fun resolveAppearanceForSpec(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    entry: CatalogEntryItem,
    statuses: Set<ConsumptionStatus>,
    memberColIds: Set<String>,
    titlesById: Map<String, String>,
    epScoreCache: EpScoreCache?,
): CatalogListAppearance? {
    val rawRole = CollectionRole.fromWireValue(spec.role)
    val role = if (spec.collectionId == STARRED_COLLECTION_ID) CollectionRole.LIST else rawRole
    if (role != CollectionRole.LIST || spec.collectionId == UP_NEXT_COLLECTION_ID) return null
    val matches =
        if (spec.isDynamic) {
            matchesDynamicCollectionRule(
                rule = parseDynamicCollectionRule(spec.ruleJson),
                kind = entry.kind,
                creators = entry.creators,
                releaseYear = entry.releaseYear,
                statuses = statuses,
            )
        } else {
            val isMember = spec.collectionId in memberColIds
            val hasGen =
                isGeneratorEligible(spec) && dao.getCollectionGenerator(spec.collectionId) != null
            isMember && (!hasGen || ConsumptionStatus.CONSUMED in statuses)
        }
    if (!matches) return null
    val specTitle = resolveSpecTitle(spec, titlesById)
    val settlement =
        resolveEntrySettlementFromDao(
            dao = dao,
            entryId = entry.id,
            targetSpec = spec,
            specTitle = specTitle,
            epScoreCache = epScoreCache,
            includeNeighbors = false,
        ) ?: return null
    return CatalogListAppearance(
        collectionId = spec.collectionId,
        title = specTitle,
        rank = settlement.rank,
        totalCount = settlement.totalCount,
        tier = settlement.tier,
        isDynamic = spec.isDynamic,
    )
}

private suspend fun loadArtistLibraryAlbumsFromDao(
    dao: RocheDao,
    artistName: String,
    globalTiers: GlobalTierIndex,
): List<ArtistLibraryAlbum> {
    val normalized = artistName.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return emptyList()
    val rawCandidates = dao.getActiveAlbumsByCreatorLike(buildSqlContainsPattern(artistName.trim()))
    val hydrated =
        hydrateDomainEntriesFromDao(dao, rawCandidates).filter { album ->
            album.creators.any { it.trim().lowercase(Locale.ROOT) == normalized }
        }
    if (hydrated.isEmpty()) return emptyList()
    val consumedIds = loadConsumedIdsChunked(dao, hydrated.map { it.id })
    return hydrated
        .sortedWith(
            compareByDescending<CatalogEntryItem> { it.releaseYear ?: Int.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        )
        .map { album ->
            val tier =
                if (album.id in consumedIds) {
                    globalTiers.tierForConsumed(album.id, EntryKind.ALBUM)
                } else {
                    null
                }
            ArtistLibraryAlbum(entry = album, tier = tier)
        }
}

internal suspend fun searchCatalogEntriesFromDao(
    dao: RocheDao,
    rawQuery: String,
    includeWhenBlank: Boolean,
    limit: Int,
    globalTiers: GlobalTierIndex,
): CatalogSearchPageResult {
    val cleanQuery = rawQuery.replace("\u0000", "").trim().trimStart('#').trim()
    val safeLimit = limit.coerceAtLeast(1)
    return when {
        cleanQuery.isEmpty() && !includeWhenBlank -> CatalogSearchPageResult()
        cleanQuery.isEmpty() -> loadBlankCatalogBrowse(dao, safeLimit, globalTiers)
        else -> executeNonBlankCatalogSearch(dao, cleanQuery, safeLimit, globalTiers)
    }
}

private suspend fun loadBlankCatalogBrowse(
    dao: RocheDao,
    safeLimit: Int,
    globalTiers: GlobalTierIndex,
): CatalogSearchPageResult {
    val total = dao.countBrowseCatalogEntries()
    val rawPage = dao.getBrowseCatalogEntriesPage(safeLimit, 0)
    val matches = hydrateCatalogSearchMatches(dao, rawPage, "", globalTiers, safeLimit)
    return CatalogSearchPageResult(
        matches = matches,
        totalCount = total,
        hasMore = matches.size < total,
    )
}

private suspend fun executeNonBlankCatalogSearch(
    dao: RocheDao,
    cleanQuery: String,
    safeLimit: Int,
    globalTiers: GlobalTierIndex,
): CatalogSearchPageResult {
    val fetchLimit = maxOf(safeLimit * 2, CATALOG_SEARCH_SQL_FETCH_FLOOR)
    val byId = LinkedHashMap<String, EntryEntity>()
    val textTotal = fetchCatalogTextMatches(dao, cleanQuery, fetchLimit, byId)
    val extraTotal = appendCatalogYearKindMatches(dao, cleanQuery, fetchLimit, byId)
    val anyHitLimit = textTotal >= fetchLimit || extraTotal >= fetchLimit
    val finalTotal = if (anyHitLimit) maxOf(textTotal, extraTotal, byId.size) else byId.size
    val matches =
        hydrateCatalogSearchMatches(
            dao = dao,
            rawEntries = byId.values.toList(),
            cleanQuery = cleanQuery,
            globalTiers = globalTiers,
            limit = safeLimit,
        )
    return CatalogSearchPageResult(
        matches = matches,
        totalCount = finalTotal,
        hasMore = matches.size < finalTotal,
    )
}

private suspend fun fetchCatalogTextMatches(
    dao: RocheDao,
    cleanQuery: String,
    fetchLimit: Int,
    outById: MutableMap<String, EntryEntity>,
): Int {
    val likePattern = buildSqlContainsPattern(cleanQuery)
    val prefixPattern = buildSqlPrefixPattern(asciiLowercase(cleanQuery))
    val tokens = extractFtsTokenQueries(cleanQuery)
    if (tokens.isEmpty()) {
        dao.searchCatalogEntriesByLike(likePattern, prefixPattern, fetchLimit).forEach {
            outById[it.id] = it
        }
        return dao.countCatalogEntriesByLike(likePattern)
    }
    val ftsQuery = "{title creatorsJson} : (${tokens.joinToString(" ")})"
    val ftsRows = runSuspendCatching {
        dao.searchCatalogEntriesByFts(ftsQuery, prefixPattern, fetchLimit)
    }
        .getOrElse { dao.searchCatalogEntriesByLike(likePattern, prefixPattern, fetchLimit) }
    ftsRows.forEach { outById[it.id] = it }
    return runSuspendCatching { dao.countCatalogEntriesByFts(ftsQuery) }
        .getOrElse { dao.countCatalogEntriesByLike(likePattern) }
}

private suspend fun appendCatalogYearKindMatches(
    dao: RocheDao,
    cleanQuery: String,
    fetchLimit: Int,
    outById: MutableMap<String, EntryEntity>,
): Int {
    var maxCount = 0
    val yearInt = cleanQuery.toIntOrNull()?.takeIf { it in MIN_CATALOG_YEAR..MAX_CATALOG_YEAR }
    if (yearInt != null) {
        dao.searchCatalogEntriesByYear(yearInt, fetchLimit).forEach {
            outById.putIfAbsent(it.id, it)
        }
        maxCount = maxOf(maxCount, dao.countCatalogEntriesByYear(yearInt))
    }
    val matchedKinds =
        SEARCHABLE_CATALOG_ENTRY_KINDS.filter {
                it.displayLabel.contains(cleanQuery, ignoreCase = true)
            }
            .map { it.wireValue }
    if (matchedKinds.isNotEmpty()) {
        dao.searchCatalogEntriesByKinds(matchedKinds, fetchLimit).forEach {
            outById.putIfAbsent(it.id, it)
        }
        maxCount = maxOf(maxCount, dao.countCatalogEntriesByKinds(matchedKinds))
    }
    return maxCount
}

private suspend fun hydrateCatalogSearchMatches(
    dao: RocheDao,
    rawEntries: List<EntryEntity>,
    cleanQuery: String,
    globalTiers: GlobalTierIndex,
    limit: Int,
): List<CatalogSearchEntryMatch> {
    if (rawEntries.isEmpty()) return emptyList()
    val domainEntries = hydrateDomainEntriesFromDao(dao, rawEntries)
    val consumedIds = loadConsumedIdsChunked(dao, domainEntries.map { it.id })
    val tierById =
        domainEntries
            .mapNotNull { entry ->
                if (entry.id in consumedIds) {
                    globalTiers.tierForConsumed(entry.id, entry.kind)?.let { entry.id to it }
                } else {
                    null
                }
            }
            .toMap()
    val comparator =
        compareByDescending<CatalogEntryItem> {
                cleanQuery.isNotEmpty() && it.title.startsWith(cleanQuery, ignoreCase = true)
            }
            .thenByDescending { it.id in tierById }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    return domainEntries.sortedWith(comparator).take(limit).map { entry ->
        CatalogSearchEntryMatch(entry = entry, tier = tierById[entry.id])
    }
}

private suspend fun countConsumedEntriesForKind(dao: RocheDao, kind: EntryKind): Int =
    dao.countConsumedEntriesForKind(kind.wireValue)

private suspend fun loadComparedConsumedIdsForKind(
    dao: RocheDao,
    kind: EntryKind,
    comparedIds: Set<String>,
): List<String> {
    if (comparedIds.isEmpty()) return emptyList()
    val result = ArrayList<String>()
    for (chunk in comparedIds.chunked(CATALOG_SQL_BIND_CHUNK_SIZE)) {
        for (row in dao.getConsumedEntryKindsByIds(chunk)) {
            if (EntryKind.fromWireValue(row.kind) == kind) {
                result.add(row.id)
            }
        }
    }
    return result
}

private suspend fun loadEntriesByIdsChunked(
    dao: RocheDao,
    ids: List<String>,
): List<EntryEntity> {
    if (ids.isEmpty()) return emptyList()
    val result = ArrayList<EntryEntity>(ids.size)
    for (chunk in ids.chunked(CATALOG_SQL_BIND_CHUNK_SIZE)) {
        result.addAll(dao.getEntriesByIds(chunk))
    }
    return result
}

private suspend fun loadEntryTitlesChunked(
    dao: RocheDao,
    ids: List<String>,
): Map<String, String> {
    if (ids.isEmpty()) return emptyMap()
    val result = HashMap<String, String>(ids.size)
    for (chunk in ids.distinct().chunked(CATALOG_SQL_BIND_CHUNK_SIZE)) {
        for (row in dao.getEntryTitlesByIds(chunk)) {
            result[row.id] = row.title
        }
    }
    return result
}

internal suspend fun loadMergeRedirectsMap(dao: RocheDao): Map<String, String> =
    dao.getMergedEntryRedirects()
        .asSequence()
        .filter { it.mergedIntoId.isNotBlank() && it.mergedIntoId != it.id }
        .associate { it.id to it.mergedIntoId }

internal suspend fun resolveGlobalEpScores(
    dao: RocheDao,
    mergeRedirects: Map<String, String>,
    epScoreCache: EpScoreCache?,
): CachedEpScores {
    val globalComparisons = dao.getGlobalComparisons()
    return epScoreCache?.resolveGlobal(globalComparisons, mergeRedirects)
        ?: computeEpScoresCold(globalComparisons, mergeRedirects)
}

private fun computeRankedTierCounts(entries: List<RankedCatalogEntry>): Map<Char, Int> {
    val counts = LinkedHashMap<Char, Int>()
    for (item in entries) {
        val tier = item.tier ?: continue
        counts[tier] = (counts[tier] ?: 0) + 1
    }
    return counts
}

private fun computeRankedKindCounts(entries: List<RankedCatalogEntry>): List<Pair<EntryKind, Int>> {
    val counts = HashMap<EntryKind, Int>()
    for (item in entries) {
        counts[item.entry.kind] = (counts[item.entry.kind] ?: 0) + 1
    }
    return counts.entries
        .sortedWith(
            compareByDescending<Map.Entry<EntryKind, Int>> { it.value }.thenBy { it.key.wireValue }
        )
        .map { it.key to it.value }
}

private val SEARCHABLE_CATALOG_ENTRY_KINDS =
    listOf(
        EntryKind.ALBUM,
        EntryKind.ARTIST,
        EntryKind.MOVIE,
        EntryKind.TV,
        EntryKind.BOOK,
        EntryKind.GAME,
        EntryKind.SONG,
    )
private const val CATALOG_SEARCH_SQL_FETCH_FLOOR = 64
private const val ZERO_NEIGHBOR_BATCH_SIZE = 32
private const val MAX_DYNAMIC_SLICE_CAP = 256
private const val ASCII_CASE_DELTA = 'a'.code - 'A'.code
private const val MIN_CATALOG_YEAR = 1_000
private const val MAX_CATALOG_YEAR = 2_999
