@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.catalog

import com.grayvines.roche.core.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.DiaryLogRow
import com.grayvines.roche.data.local.DiaryMonthCountRow
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.KindCountRow
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.ranking.EpRankingEngine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Suppress("DataClassContainsFunctions")
private data class BandedManualContext(
    val collectionId: String,
    val hasGenerator: Boolean,
    val isPriorityScoped: Boolean,
    val totalCount: Int,
    val kindCounts: List<Pair<EntryKind, Int>>,
    val comparedScores: Map<String, EpRankingEngine.Score>,
    val bands: ComparedBands,
    val quantiles: VirtualQuantileResult,
    val hasTierComparisons: Boolean,
    val minMean: Double,
    val maxMean: Double,
) {
    val nonZeroBandIds: List<String> = bands.bandAbove.map { it.id } + bands.bandBelow.map { it.id }

    val zeroBandTotalCount: Int =
        (totalCount - bands.bandAbove.size - bands.bandBelow.size).coerceAtLeast(0)

    val defaultTier: Char? =
        if (hasTierComparisons) quantiles.defaultBucket?.let(EpRankingEngine::tierLabel) else null

    fun tierFor(entryId: String): Char? {
        if (!hasTierComparisons) return null
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
internal suspend fun loadLargeManualCollectionPage(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    hasGenerator: Boolean,
    totalCount: Int,
    kindCounts: List<Pair<EntryKind, Int>>,
    epScoreCache: EpScoreCache?,
    tierFilter: Char?,
    kindFilters: Set<EntryKind>,
    limit: Int,
    offset: Int,
): CatalogCollectionPageResult {
    val ctx =
        buildBandedManualContext(
            dao = dao,
            spec = spec,
            hasGenerator = hasGenerator,
            totalCount = totalCount,
            kindCounts = kindCounts,
            epScoreCache = epScoreCache,
        )
    val tierCounts = if (ctx.hasTierComparisons) ctx.quantiles.tierCounts else emptyMap()
    if (tierFilter != null && tierFilter != ctx.defaultTier) {
        return buildTierOnlyManualPage(ctx, tierFilter, kindFilters, tierCounts, limit, offset)
    }
    return loadManualZeroBandPage(
        dao = dao,
        ctx = ctx,
        tierFilter = tierFilter,
        kindFilters = kindFilters,
        tierCounts = tierCounts,
        limit = limit,
        offset = offset,
    )
}

@Suppress("LongParameterList")
private fun buildTierOnlyManualPage(
    ctx: BandedManualContext,
    tierFilter: Char,
    kindFilters: Set<EntryKind>,
    tierCounts: Map<Char, Int>,
    limit: Int,
    offset: Int,
): CatalogCollectionPageResult {
    val matchedEntries =
        (ctx.bands.bandAbove + ctx.bands.bandZeroCompared + ctx.bands.bandBelow).filter { entry ->
            matchesManualFilters(ctx, entry, tierFilter, kindFilters)
        }
    val ranked = matchedEntries.mapIndexed { idx, entry -> ctx.toRanked(entry, idx + 1) }
    val slice =
        if (offset >= ranked.size) {
            emptyList()
        } else {
            val takeCount = minOf(limit, ranked.size - offset)
            ranked.subList(offset, offset + takeCount)
        }
    return CatalogCollectionPageResult(
        collectionId = ctx.collectionId,
        entries = slice,
        totalCount = ctx.totalCount,
        filteredTotalCount = ranked.size,
        tierCounts = tierCounts,
        kindCounts = ctx.kindCounts,
        hasMore = offset + slice.size < ranked.size,
    )
}

private fun matchesManualFilters(
    ctx: BandedManualContext,
    entry: CatalogEntryItem,
    tierFilter: Char?,
    kindFilters: Set<EntryKind>,
): Boolean {
    val kindOk = kindFilters.isEmpty() || entry.kind in kindFilters
    val tierOk = tierFilter == null || ctx.tierFor(entry.id) == tierFilter
    return kindOk && tierOk
}

private fun filterBandWithIndices(
    ctx: BandedManualContext,
    entries: List<CatalogEntryItem>,
    tierFilter: Char?,
    kindFilters: Set<EntryKind>,
): List<Pair<Int, CatalogEntryItem>> = entries.mapIndexedNotNull { idx, entry ->
    if (matchesManualFilters(ctx, entry, tierFilter, kindFilters)) idx to entry else null
}

@Suppress("LongParameterList")
private suspend fun loadManualZeroBandPage(
    dao: RocheDao,
    ctx: BandedManualContext,
    tierFilter: Char?,
    kindFilters: Set<EntryKind>,
    tierCounts: Map<Char, Int>,
    limit: Int,
    offset: Int,
): CatalogCollectionPageResult {
    val isFiltered = tierFilter != null || kindFilters.isNotEmpty()
    val aboveMatched = filterBandWithIndices(ctx, ctx.bands.bandAbove, tierFilter, kindFilters)
    val aboveRanked = aboveMatched.mapIndexed { ordinalIdx, (globalIdx, entry) ->
        ctx.toRanked(entry, if (isFiltered) ordinalIdx + 1 else globalIdx + 1)
    }
    val belowMatched = filterBandWithIndices(ctx, ctx.bands.bandBelow, tierFilter, kindFilters)
    val excludeIds = buildManualZeroBandExclusions(ctx, tierFilter)
    val zeroTotal = countManualZeroBandTotal(dao, ctx, kindFilters, excludeIds)
    val belowBaseGlobal = ctx.totalCount - ctx.bands.bandBelow.size + 1
    val belowRanked = belowMatched.mapIndexed { ordinalIdx, (globalIdx, entry) ->
        val rank =
            if (isFiltered) {
                aboveRanked.size + zeroTotal + ordinalIdx + 1
            } else {
                belowBaseGlobal + globalIdx
            }
        ctx.toRanked(entry, rank)
    }
    val filteredTotal = aboveRanked.size + zeroTotal + belowRanked.size
    val pageEntries =
        assembleBandedManualSlice(
            dao = dao,
            ctx = ctx,
            kindFilters = kindFilters,
            isFiltered = isFiltered,
            aboveRanked = aboveRanked,
            belowRanked = belowRanked,
            excludeIds = excludeIds,
            zeroTotal = zeroTotal,
            limit = limit,
            offset = offset,
        )
    return CatalogCollectionPageResult(
        collectionId = ctx.collectionId,
        entries = pageEntries,
        totalCount = ctx.totalCount,
        filteredTotalCount = filteredTotal,
        tierCounts = tierCounts,
        kindCounts = ctx.kindCounts,
        hasMore = offset + pageEntries.size < filteredTotal,
    )
}

@Suppress("LongParameterList")
private suspend fun assembleBandedManualSlice(
    dao: RocheDao,
    ctx: BandedManualContext,
    kindFilters: Set<EntryKind>,
    isFiltered: Boolean,
    aboveRanked: List<RankedCatalogEntry>,
    belowRanked: List<RankedCatalogEntry>,
    excludeIds: List<String>,
    zeroTotal: Int,
    limit: Int,
    offset: Int,
): List<RankedCatalogEntry> {
    val filteredTotal = aboveRanked.size + zeroTotal + belowRanked.size
    val initCap = minOf(limit, (filteredTotal - offset).coerceAtLeast(0), MAX_SLICE_INIT_CAP)
    val result = ArrayList<RankedCatalogEntry>(initCap)
    var cursor = offset
    var remaining = limit

    if (remaining > 0 && cursor < aboveRanked.size) {
        val takeCount = minOf(remaining, aboveRanked.size - cursor)
        result.addAll(aboveRanked.subList(cursor, cursor + takeCount))
        remaining -= takeCount
        cursor += takeCount
    }
    val zeroStartOffset = (cursor - aboveRanked.size).coerceAtLeast(0)
    if (remaining > 0 && zeroStartOffset < zeroTotal) {
        val zeroRaw =
            fetchManualZeroBandSqlSlice(
                dao = dao,
                ctx = ctx,
                kindFilters = kindFilters,
                excludeIds = excludeIds,
                zeroOffset = zeroStartOffset,
                limit = remaining,
            )
        val zeroDomain = hydrateDomainEntriesFromDao(dao, zeroRaw)
        val rankBase =
            if (isFiltered) {
                aboveRanked.size + zeroStartOffset + 1
            } else {
                ctx.bands.bandAbove.size + zeroStartOffset + 1
            }
        zeroDomain.forEachIndexed { idx, entry -> result.add(ctx.toRanked(entry, rankBase + idx)) }
        remaining -= zeroDomain.size
        cursor += zeroDomain.size
    }
    val belowStartOffset = (cursor - aboveRanked.size - zeroTotal).coerceAtLeast(0)
    if (remaining > 0 && belowStartOffset < belowRanked.size) {
        val takeCount = minOf(remaining, belowRanked.size - belowStartOffset)
        result.addAll(belowRanked.subList(belowStartOffset, belowStartOffset + takeCount))
    }
    return result
}

private fun buildManualZeroBandExclusions(
    ctx: BandedManualContext,
    tierFilter: Char?,
): List<String> {
    if (tierFilter == null) return ctx.nonZeroBandIds
    val extra = ctx.bands.bandZeroCompared.filter { ctx.tierFor(it.id) != tierFilter }.map { it.id }
    return if (extra.isEmpty()) ctx.nonZeroBandIds else ctx.nonZeroBandIds + extra
}

private suspend fun countManualZeroBandTotal(
    dao: RocheDao,
    ctx: BandedManualContext,
    kindFilters: Set<EntryKind>,
    excludeIds: List<String>,
): Int {
    if (kindFilters.isEmpty()) {
        return (ctx.totalCount - excludeIds.size).coerceAtLeast(0)
    }
    val kindsJson = encodeSqlStringJsonArray(kindFilters.map { it.wireValue })
    return when {
        excludeIds.isEmpty() && ctx.hasGenerator ->
            dao.countGenColByKinds(ctx.collectionId, kindsJson)
        excludeIds.isEmpty() -> dao.countManualColByKinds(ctx.collectionId, kindsJson)
        ctx.hasGenerator ->
            dao.countGenColKindsExcl(
                collectionId = ctx.collectionId,
                kindsJson = kindsJson,
                excludedJson = encodeSqlIdJsonArray(excludeIds),
            )
        else ->
            dao.countManualColKindsExcl(
                collectionId = ctx.collectionId,
                kindsJson = kindsJson,
                excludedJson = encodeSqlIdJsonArray(excludeIds),
            )
    }
}

@Suppress("LongParameterList")
private suspend fun fetchManualZeroBandSqlSlice(
    dao: RocheDao,
    ctx: BandedManualContext,
    kindFilters: Set<EntryKind>,
    excludeIds: List<String>,
    zeroOffset: Int,
    limit: Int,
): List<EntryEntity> {
    val colId = ctx.collectionId
    val excludedJson = if (excludeIds.isEmpty()) null else encodeSqlIdJsonArray(excludeIds)
    val kindsJson =
        if (kindFilters.isEmpty()) {
            null
        } else {
            encodeSqlStringJsonArray(kindFilters.map { it.wireValue })
        }
    return if (ctx.hasGenerator) {
        fetchGeneratorZeroSlice(dao, colId, kindsJson, excludedJson, limit, zeroOffset)
    } else {
        fetchPlainManualZeroSlice(dao, colId, kindsJson, excludedJson, limit, zeroOffset)
    }
}

@Suppress("LongParameterList")
private suspend fun fetchPlainManualZeroSlice(
    dao: RocheDao,
    colId: String,
    kindsJson: String?,
    excludedJson: String?,
    limit: Int,
    offset: Int,
): List<EntryEntity> =
    when {
        kindsJson == null && excludedJson == null ->
            dao.getManualCollectionPage(colId, limit, offset)
        kindsJson == null -> dao.getManualColPageExcl(colId, excludedJson!!, limit, offset)
        excludedJson == null -> dao.getManualColKindsPage(colId, kindsJson, limit, offset)
        else -> dao.getManualColKindsPageExcl(colId, kindsJson, excludedJson, limit, offset)
    }

@Suppress("LongParameterList")
private suspend fun fetchGeneratorZeroSlice(
    dao: RocheDao,
    colId: String,
    kindsJson: String?,
    excludedJson: String?,
    limit: Int,
    offset: Int,
): List<EntryEntity> =
    when {
        kindsJson == null && excludedJson == null ->
            dao.getGeneratorCollectionPage(colId, limit, offset)
        kindsJson == null -> dao.getGenColPageExcl(colId, excludedJson!!, limit, offset)
        excludedJson == null -> dao.getGenColKindsPage(colId, kindsJson, limit, offset)
        else -> dao.getGenColKindsPageExcl(colId, kindsJson, excludedJson, limit, offset)
    }

@Suppress("LongParameterList")
private suspend fun buildBandedManualContext(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    hasGenerator: Boolean,
    totalCount: Int,
    kindCounts: List<Pair<EntryKind, Int>>,
    epScoreCache: EpScoreCache?,
): BandedManualContext {
    val role = CollectionRole.fromWireValue(spec.role)
    val isWant = role == CollectionRole.WANT || spec.collectionId == UP_NEXT_COLLECTION_ID
    val mergeRedirects = loadMergeRedirectsMap(dao)
    val ep =
        if (isWant) {
            resolveScopedEpScores(dao, spec.collectionId, mergeRedirects, epScoreCache)
        } else {
            resolveGlobalEpScores(dao, mergeRedirects, epScoreCache)
        }
    val comparedRaw =
        loadManualMembersInIdsChunked(dao, spec.collectionId, hasGenerator, ep.scores.keys)
    val comparedEntries = hydrateDomainEntriesFromDao(dao, comparedRaw)
    val comparedScores = comparedEntries.associate {
        it.id to (ep.scores[it.id] ?: EpRankingEngine.Score())
    }
    val uncomparedCount = (totalCount - comparedEntries.size).coerceAtLeast(0)
    val bands = splitComparedBands(comparedEntries, comparedScores, uncomparedCount > 0)
    val orderedIds = ep.orderings.asSequence().flatten().flatten().toHashSet()
    val hasTierComparisons = !isWant && comparedEntries.any { it.id in orderedIds }
    val quantiles =
        if (hasTierComparisons) {
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
    return BandedManualContext(
        collectionId = spec.collectionId,
        hasGenerator = hasGenerator,
        isPriorityScoped = isWant,
        totalCount = totalCount,
        kindCounts = kindCounts,
        comparedScores = comparedScores,
        bands = bands,
        quantiles = quantiles,
        hasTierComparisons = hasTierComparisons,
        minMean = minMean,
        maxMean = maxMean,
    )
}

@Suppress("LongParameterList")
internal suspend fun resolveLargeManualSettlement(
    dao: RocheDao,
    entryId: String,
    spec: CollectionSpecEntity,
    hasGenerator: Boolean,
    totalCount: Int,
    kindCounts: List<Pair<EntryKind, Int>>,
    specTitle: String,
    epScoreCache: EpScoreCache?,
    includeNeighbors: Boolean,
): RankSettlementSummary? {
    val raw = dao.getEntryById(entryId)?.takeIf { it.mergedIntoId.isNullOrBlank() } ?: return null
    if (dao.hasCollectionMembership(spec.collectionId, entryId) <= 0) return null
    if (hasGenerator && !dao.hasConsumedLogForEntry(entryId)) return null
    val ctx =
        buildBandedManualContext(dao, spec, hasGenerator, totalCount, kindCounts, epScoreCache)
    val placement = resolveLargeManualPlacement(dao, raw, ctx, includeNeighbors)
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

private suspend fun resolveLargeManualPlacement(
    dao: RocheDao,
    raw: EntryEntity,
    ctx: BandedManualContext,
    includeNeighbors: Boolean,
): EntryPlacementWithNeighbors {
    val aboveIdx = ctx.bands.bandAbove.indexOfFirst { it.id == raw.id }
    if (aboveIdx >= 0) {
        val ranked = ctx.toRanked(ctx.bands.bandAbove[aboveIdx], aboveIdx + 1)
        val neighbors =
            if (includeNeighbors) {
                buildManualAboveNeighbors(dao, ctx, aboveIdx, ranked)
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
                buildManualBelowNeighbors(dao, ctx, belowIdx, ranked)
            } else {
                emptyList()
            }
        return EntryPlacementWithNeighbors(ranked, neighbors)
    }
    val zeroPos = countManualZeroBandBefore(dao, ctx, raw)
    val rank = ctx.bands.bandAbove.size + zeroPos + 1
    val domain = hydrateDomainEntriesFromDao(dao, listOf(raw)).single()
    val ranked = ctx.toRanked(domain, rank)
    val neighbors =
        if (includeNeighbors) buildManualZeroNeighbors(dao, ctx, raw, ranked) else emptyList()
    return EntryPlacementWithNeighbors(ranked, neighbors)
}

private suspend fun buildManualAboveNeighbors(
    dao: RocheDao,
    ctx: BandedManualContext,
    aboveIdx: Int,
    self: RankedCatalogEntry,
): List<RankedCatalogEntry> = buildList {
    if (aboveIdx > 0) add(ctx.toRanked(ctx.bands.bandAbove[aboveIdx - 1], aboveIdx))
    add(self)
    when {
        aboveIdx + 1 < ctx.bands.bandAbove.size ->
            add(ctx.toRanked(ctx.bands.bandAbove[aboveIdx + 1], aboveIdx + 2))
        ctx.zeroBandTotalCount > 0 ->
            fetchManualZeroBandSqlSlice(dao, ctx, emptySet(), ctx.nonZeroBandIds, 0, 1)
                .firstOrNull()
                ?.let { nextRaw ->
                    val nextDomain = hydrateDomainEntriesFromDao(dao, listOf(nextRaw)).single()
                    add(ctx.toRanked(nextDomain, self.rank + 1))
                }
        ctx.bands.bandBelow.isNotEmpty() ->
            add(ctx.toRanked(ctx.bands.bandBelow.first(), self.rank + 1))
    }
}

private suspend fun buildManualBelowNeighbors(
    dao: RocheDao,
    ctx: BandedManualContext,
    belowIdx: Int,
    self: RankedCatalogEntry,
): List<RankedCatalogEntry> = buildList {
    when {
        belowIdx > 0 -> add(ctx.toRanked(ctx.bands.bandBelow[belowIdx - 1], self.rank - 1))
        ctx.zeroBandTotalCount > 0 ->
            fetchManualZeroBandSqlSlice(
                    dao,
                    ctx,
                    emptySet(),
                    ctx.nonZeroBandIds,
                    ctx.zeroBandTotalCount - 1,
                    1,
                )
                .firstOrNull()
                ?.let { prev ->
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

private suspend fun buildManualZeroNeighbors(
    dao: RocheDao,
    ctx: BandedManualContext,
    raw: EntryEntity,
    self: RankedCatalogEntry,
): List<RankedCatalogEntry> = buildList {
    val prevRaw = findManualZeroNeighbor(dao, ctx, raw, forward = false)
    if (prevRaw != null) {
        val prevDomain = hydrateDomainEntriesFromDao(dao, listOf(prevRaw)).single()
        add(ctx.toRanked(prevDomain, self.rank - 1))
    } else if (ctx.bands.bandAbove.isNotEmpty()) {
        add(ctx.toRanked(ctx.bands.bandAbove.last(), self.rank - 1))
    }
    add(self)
    val nextRaw = findManualZeroNeighbor(dao, ctx, raw, forward = true)
    if (nextRaw != null) {
        val nextDomain = hydrateDomainEntriesFromDao(dao, listOf(nextRaw)).single()
        add(ctx.toRanked(nextDomain, self.rank + 1))
    } else if (ctx.bands.bandBelow.isNotEmpty()) {
        add(ctx.toRanked(ctx.bands.bandBelow.first(), self.rank + 1))
    }
}

private suspend fun findManualZeroNeighbor(
    dao: RocheDao,
    ctx: BandedManualContext,
    raw: EntryEntity,
    forward: Boolean,
): EntryEntity? {
    val excludedJson =
        if (ctx.nonZeroBandIds.isEmpty()) null else encodeSqlIdJsonArray(ctx.nonZeroBandIds)
    return if (ctx.hasGenerator) {
        findGeneratorZeroNeighbor(dao, ctx.collectionId, raw, forward, excludedJson)
    } else {
        findPlainManualZeroNeighbor(dao, ctx.collectionId, raw, forward, excludedJson)
    }
}

private suspend fun findPlainManualZeroNeighbor(
    dao: RocheDao,
    colId: String,
    raw: EntryEntity,
    forward: Boolean,
    excludedJson: String?,
): EntryEntity? =
    when {
        forward && excludedJson == null -> dao.getManualCollectionNext(colId, raw.title, raw.id)
        forward -> dao.getManualColNextExcl(colId, raw.title, raw.id, excludedJson!!)
        excludedJson == null -> dao.getManualCollectionPrev(colId, raw.title, raw.id)
        else -> dao.getManualColPrevExcl(colId, raw.title, raw.id, excludedJson)
    }

private suspend fun findGeneratorZeroNeighbor(
    dao: RocheDao,
    colId: String,
    raw: EntryEntity,
    forward: Boolean,
    excludedJson: String?,
): EntryEntity? =
    when {
        forward && excludedJson == null -> dao.getGeneratorCollectionNext(colId, raw.title, raw.id)
        forward -> dao.getGenColNextExcl(colId, raw.title, raw.id, excludedJson!!)
        excludedJson == null -> dao.getGeneratorCollectionPrev(colId, raw.title, raw.id)
        else -> dao.getGenColPrevExcl(colId, raw.title, raw.id, excludedJson)
    }

private suspend fun countManualZeroBandBefore(
    dao: RocheDao,
    ctx: BandedManualContext,
    raw: EntryEntity,
): Int {
    val rawBefore =
        if (ctx.hasGenerator) {
            dao.countGeneratorCollectionBefore(ctx.collectionId, raw.title, raw.id)
        } else {
            dao.countManualCollectionBefore(ctx.collectionId, raw.title, raw.id)
        }
    val asciiTarget = asciiLowercase(raw.title)
    val excludedBefore =
        (ctx.bands.bandAbove + ctx.bands.bandBelow).count { item ->
            val cmp = asciiLowercase(item.title).compareTo(asciiTarget)
            cmp < 0 || cmp == 0 && item.id < raw.id
        }
    return (rawBefore - excludedBefore).coerceAtLeast(0)
}

@Suppress("LongParameterList")
internal suspend fun loadLargeManualRankCandidates(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    hasGenerator: Boolean,
    epScoreCache: EpScoreCache?,
    focusEntryId: String?,
    excludedOpponentIds: Set<String>,
): List<RankedCatalogEntry> {
    val role = CollectionRole.fromWireValue(spec.role)
    val isWant = role == CollectionRole.WANT || spec.collectionId == UP_NEXT_COLLECTION_ID
    val mergeRedirects = loadMergeRedirectsMap(dao)
    val ep =
        if (isWant) {
            resolveScopedEpScores(dao, spec.collectionId, mergeRedirects, epScoreCache)
        } else {
            resolveGlobalEpScores(dao, mergeRedirects, epScoreCache)
        }
    val sampleLimit = LARGE_RANK_UNCOMPARED_SAMPLE_SIZE + excludedOpponentIds.size
    val sampleRaw =
        if (hasGenerator) {
            dao.getRandomGenColEntries(spec.collectionId, sampleLimit)
        } else {
            dao.getRandomManualColEntries(spec.collectionId, sampleLimit)
        }
    val rawById = LinkedHashMap<String, EntryEntity>(sampleRaw.size + ep.scores.size + 1)
    for (row in sampleRaw) {
        if (row.id !in excludedOpponentIds) rawById[row.id] = row
    }
    val extraIds = LinkedHashSet<String>()
    for (id in ep.scores.keys) {
        if (id !in rawById) extraIds.add(id)
    }
    if (focusEntryId != null && focusEntryId !in rawById) {
        extraIds.add(focusEntryId)
    }
    for (row in loadManualMembersInIdsChunked(dao, spec.collectionId, hasGenerator, extraIds)) {
        rawById[row.id] = row
    }
    val domainEntries = hydrateDomainEntriesFromDao(dao, rawById.values.toList())
    return domainEntries.mapIndexed { idx, entry ->
        val score = ep.scores[entry.id] ?: EpRankingEngine.Score()
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

private suspend fun loadManualMembersInIdsChunked(
    dao: RocheDao,
    collectionId: String,
    hasGenerator: Boolean,
    candidateIds: Collection<String>,
): List<EntryEntity> {
    if (candidateIds.isEmpty()) return emptyList()
    val result = ArrayList<EntryEntity>()
    for (chunk in candidateIds.chunked(CATALOG_SQL_BIND_CHUNK_SIZE)) {
        val rows =
            if (hasGenerator) {
                dao.getGenColMembersIn(collectionId, chunk)
            } else {
                dao.getManualColMembersIn(collectionId, chunk)
            }
        result.addAll(rows)
    }
    return result
}

internal suspend fun loadManualKindCounts(
    dao: RocheDao,
    collectionId: String,
    hasGenerator: Boolean,
): List<Pair<EntryKind, Int>> {
    val rows =
        if (hasGenerator) {
            dao.getGenColKindCounts(collectionId)
        } else {
            dao.getManualCollectionKindCounts(collectionId)
        }
    return coalesceKindCounts(rows)
}

private fun coalesceKindCounts(rows: List<KindCountRow>): List<Pair<EntryKind, Int>> {
    val sums = LinkedHashMap<EntryKind, Int>(rows.size)
    for (row in rows) {
        if (row.count > 0) {
            val kind = EntryKind.fromWireValue(row.kind)
            sums[kind] = (sums[kind] ?: 0) + row.count
        }
    }
    return sums.entries
        .sortedWith(
            compareByDescending<Map.Entry<EntryKind, Int>> { it.value }.thenBy { it.key.wireValue }
        )
        .map { it.key to it.value }
}

private fun encodeSqlIdJsonArray(ids: List<String>): String = encodeSqlStringJsonArray(ids)

private fun encodeSqlStringJsonArray(values: List<String>): String =
    Json.encodeToString(ListSerializer(String.serializer()), values)

private const val MAX_SLICE_INIT_CAP = 256

@Suppress("LongParameterList")
internal suspend fun loadDiaryLogSliceFromDao(
    dao: RocheDao,
    status: ConsumptionStatus?,
    year: String?,
    kindFilter: DiaryKindFilter,
    limit: Int,
    offset: Int,
    tiers: GlobalTierIndex,
): List<DiaryEntryItem> {
    val (minTs, maxTs) = resolveDiaryYearBounds(year)
    return loadDiaryLogRowsForFilter(dao, status, kindFilter, minTs, maxTs, limit, offset).map {
        toDiaryEntryItem(it, tiers)
    }
}

@Suppress("LongParameterList")
internal suspend fun loadDiaryPageFromDao(
    dao: RocheDao,
    status: ConsumptionStatus?,
    year: String?,
    kindFilter: DiaryKindFilter,
    limit: Int,
    offset: Int,
    tiers: GlobalTierIndex,
): DiaryPageResult {
    val yearCounts = loadDiaryYearCountsForFilter(dao, kindFilter)
    val availableYears = yearCounts.map { it.year }
    val effectiveYear = year?.takeIf { it in availableYears }
    val (minTs, maxTs) = resolveDiaryYearBounds(effectiveYear)

    val monthRows = loadDiaryMonthCountsForFilter(dao, status, kindFilter, minTs, maxTs)
    val totalFiltered = monthRows.sumOf { it.count }
    val rows = loadDiaryLogRowsForFilter(dao, status, kindFilter, minTs, maxTs, limit, offset)

    val nonSongKindRows =
        if (status == null) {
            dao.getDiaryNonSongKindCounts(minTs, maxTs)
        } else {
            dao.getDiaryNonSongKindsStatus(status.wireValue, minTs, maxTs)
        }
    val nonSongKindCounts = coalesceKindCounts(nonSongKindRows)
    val hasSongLogs = dao.hasAnyDiarySongLogs()
    val hasAnyEntries =
        yearCounts.isNotEmpty() ||
            totalFiltered > 0 ||
            nonSongKindCounts.isNotEmpty() ||
            hasSongLogs ||
            dao.hasAnyDiaryLogs()
    return DiaryPageResult(
        entries = rows.map { toDiaryEntryItem(it, tiers) },
        availableYears = availableYears,
        monthCounts = monthRows.associate { it.monthKey to it.count },
        nonSongKindCounts = nonSongKindCounts,
        hasSongLogs = hasSongLogs,
        kindFilter = kindFilter,
        totalFilteredCount = totalFiltered,
        hasAnyEntries = hasAnyEntries,
        effectiveYear = effectiveYear,
        hasMore = offset + rows.size < totalFiltered,
    )
}

private suspend fun loadDiaryYearCountsForFilter(
    dao: RocheDao,
    kindFilter: DiaryKindFilter,
) =
    when (kindFilter) {
        DiaryKindFilter.DefaultNonSong -> dao.getDiaryYearCountsNonSong()
        DiaryKindFilter.All -> dao.getDiaryYearCounts()
        is DiaryKindFilter.Only -> dao.getDiaryYearCountsForKind(kindFilter.kind.wireValue)
    }

private suspend fun loadDiaryMonthCountsForFilter(
    dao: RocheDao,
    status: ConsumptionStatus?,
    kindFilter: DiaryKindFilter,
    minTs: String,
    maxTs: String,
): List<DiaryMonthCountRow> =
    when (kindFilter) {
        DiaryKindFilter.DefaultNonSong ->
            if (status == null) {
                dao.getDiaryMonthCountsNonSong(minTs, maxTs)
            } else {
                dao.getDiaryMonthNonSongStatus(status.wireValue, minTs, maxTs)
            }
        DiaryKindFilter.All ->
            when (status) {
                null -> dao.getDiaryMonthCounts(minTs, maxTs)
                ConsumptionStatus.CONSUMED -> dao.getDiaryMonthCountsConsumed(minTs, maxTs)
                else -> dao.getDiaryMonthCountsByStatus(status.wireValue, minTs, maxTs)
            }
        is DiaryKindFilter.Only ->
            if (
                kindFilter.kind == EntryKind.SONG &&
                    status != null &&
                    status != ConsumptionStatus.CONSUMED
            ) {
                dao.getDiaryMonthSongStatus(status.wireValue, minTs, maxTs)
            } else if (status == null) {
                dao.getDiaryMonthCountsForKind(kindFilter.kind.wireValue, minTs, maxTs)
            } else {
                dao.getDiaryMonthKindStatus(
                    kindFilter.kind.wireValue,
                    status.wireValue,
                    minTs,
                    maxTs,
                )
            }
    }

@Suppress("LongParameterList")
private suspend fun loadDiaryLogRowsForFilter(
    dao: RocheDao,
    status: ConsumptionStatus?,
    kindFilter: DiaryKindFilter,
    minTs: String,
    maxTs: String,
    limit: Int,
    offset: Int,
): List<DiaryLogRow> =
    when (kindFilter) {
        DiaryKindFilter.DefaultNonSong ->
            if (status == null) {
                dao.getDiaryLogPageNonSong(minTs, maxTs, limit, offset)
            } else {
                dao.getDiaryLogPageNonSongByStatus(status.wireValue, minTs, maxTs, limit, offset)
            }
        DiaryKindFilter.All ->
            if (status == null) {
                dao.getDiaryLogPage(minTs, maxTs, limit, offset)
            } else {
                dao.getDiaryLogPageByStatus(status.wireValue, minTs, maxTs, limit, offset)
            }
        is DiaryKindFilter.Only ->
            if (kindFilter.kind == EntryKind.SONG) {
                if (status == null) {
                    dao.getDiaryLogPageForSong(minTs, maxTs, limit, offset)
                } else {
                    dao.getDiaryLogPageForSongByStatus(
                        status.wireValue,
                        minTs,
                        maxTs,
                        limit,
                        offset,
                    )
                }
            } else if (status == null) {
                dao.getDiaryLogPageForKind(kindFilter.kind.wireValue, minTs, maxTs, limit, offset)
            } else {
                dao.getDiaryLogPageForKindByStatus(
                    kindFilter.kind.wireValue,
                    status.wireValue,
                    minTs,
                    maxTs,
                    limit,
                    offset,
                )
            }
    }
