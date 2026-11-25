@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.catalog

import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.local.CollectionAggregateRow
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryThumbnailRow
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.isDynamic
import com.grayvines.roche.ranking.EpRankingEngine
import java.util.Locale

internal const val OVERVIEW_PREVIEW_THUMBNAIL_LIMIT = 4
internal const val HOME_WANT_TO_READ_PREVIEW_LIMIT = 10
internal const val ACTIVE_STARTED_OVERVIEW_LIMIT = 100
internal const val CATALOG_SQL_BIND_CHUNK_SIZE = 450

private data class OverviewLoadContext(
    val dao: RocheDao,
    val specs: List<CollectionSpecEntity>,
    val titlesByEntryId: Map<String, String>,
    val generatorsByCollection: Map<String, CollectionGeneratorEntity>,
    val mergeRedirects: Map<String, String>,
    val globalEp: CachedEpScores,
    val consumedCountByKind: Map<EntryKind, Int>,
    val comparedConsumedByKind: Map<EntryKind, List<String>>,
    val manualAggregatesByColId: Map<String, CollectionAggregateRow>,
    val epScoreCache: EpScoreCache?,
)

private data class OverviewSpecBucketResult(
    val lists: List<CatalogListSummary>,
    val wantQueues: List<CatalogListSummary>,
    val activeWantScopeIds: Set<String>,
)

/**
 * Builds the lightweight [CatalogSnapshot] directly from indexed SQL aggregate queries and cached
 * EP scores, without loading full `entries`, `entry_identifiers`, `collection_entries`, or
 * `consumption_log` tables into memory.
 */
internal suspend fun loadCatalogOverviewSnapshot(
    dao: RocheDao,
    epScoreCache: EpScoreCache? = null,
): CatalogSnapshot {
    val ctx = loadOverviewContext(dao, epScoreCache)
    val globalTiers =
        computeGlobalTierIndex(
            consumedCountByKind = ctx.consumedCountByKind,
            comparedConsumedByKind = ctx.comparedConsumedByKind,
            globalScores = ctx.globalEp.scores,
            globalOrderings = ctx.globalEp.orderings,
        )
    val buckets = buildOverviewSummaries(ctx)
    epScoreCache?.evictUnusedScopes(buckets.activeWantScopeIds)

    val recentDiary =
        dao.getDiaryLogPageNonSongByStatus(
                status = ConsumptionStatus.CONSUMED.wireValue,
                minTs = "",
                maxTs = OVERVIEW_MAX_ISO_TS_BOUND,
                limit = RECENT_DIARY_PREVIEW_LIMIT,
                offset = 0,
            )
            .map { toDiaryEntryItem(it, globalTiers) }
    val activeStartedEntries =
        dao.getActiveStartedEntries(limit = ACTIVE_STARTED_OVERVIEW_LIMIT).map {
            toDiaryEntryItem(it, globalTiers)
        }
    val totalDiaryCount = dao.countDiaryLogsNonSong()
    val diaryConsumedCount =
        ctx.consumedCountByKind.entries.sumOf { (kind, count) ->
            if (kind.isDiaryVisibleKind) count else 0
        }
    val starredEntryIds = dao.getAllStarredEntryIds().toHashSet()
    val wantEntryIds = dao.getAllWantEntryIds().toHashSet()
    val wantToReadEntryIds = dao.getActiveCollectionEntryIds(WANT_TO_READ_COLLECTION_ID).toHashSet()
    val wantToReadPreview = loadWantToReadHomePreview(ctx, wantToReadEntryIds)
    val generatorStates = buildOverviewGeneratorStates(ctx)

    return CatalogSnapshot(
        lists = sortListSummaries(buckets.lists),
        wantQueues = sortWantSummaries(buckets.wantQueues),
        recentDiaryEntries = recentDiary,
        activeStartedEntries = activeStartedEntries,
        totalDiaryCount = totalDiaryCount,
        consumedCount = diaryConsumedCount,
        globalTiers = globalTiers,
        generators = generatorStates,
        comparisonCount = dao.countComparisons(),
        wantToReadPreview = wantToReadPreview,
        wantToReadEntryIds = wantToReadEntryIds,
        starredEntryIds = starredEntryIds,
        wantEntryIds = wantEntryIds,
    )
}

private suspend fun loadOverviewContext(
    dao: RocheDao,
    epScoreCache: EpScoreCache?,
): OverviewLoadContext {
    val specs = dao.getAllCollectionSpecs()
    val generators = dao.getAllCollectionGenerators()
    val mergeRedirects =
        dao.getMergedEntryRedirects()
            .asSequence()
            .filter { it.mergedIntoId.isNotBlank() && it.mergedIntoId != it.id }
            .associate { it.id to it.mergedIntoId }
    val specIds = specs.map { it.collectionId }.distinct()
    val titlesByEntryId = loadTitlesByIdsChunked(dao, specIds)
    val globalComparisons = dao.getGlobalComparisons()
    val globalEp =
        epScoreCache?.resolveGlobal(globalComparisons, mergeRedirects)
            ?: computeEpScoresCold(globalComparisons, mergeRedirects)
    val consumedCountByKind = buildMap {
        for (row in dao.getConsumedEntryCountsByKind()) {
            val kind = EntryKind.fromWireValue(row.kind)
            put(kind, (this[kind] ?: 0) + row.count)
        }
    }
    val comparedConsumedByKind = loadComparedConsumedByKind(dao, globalEp.scores.keys)
    val manualAggregates = dao.getManualCollectionAggregates().associateBy { it.collectionId }
    return OverviewLoadContext(
        dao = dao,
        specs = specs,
        titlesByEntryId = titlesByEntryId,
        generatorsByCollection = generators.associateBy { it.collectionId },
        mergeRedirects = mergeRedirects,
        globalEp = globalEp,
        consumedCountByKind = consumedCountByKind,
        comparedConsumedByKind = comparedConsumedByKind,
        manualAggregatesByColId = manualAggregates,
        epScoreCache = epScoreCache,
    )
}

private suspend fun loadTitlesByIdsChunked(
    dao: RocheDao,
    ids: List<String>,
): Map<String, String> {
    if (ids.isEmpty()) return emptyMap()
    val titles = HashMap<String, String>(ids.size)
    for (chunk in ids.chunked(CATALOG_SQL_BIND_CHUNK_SIZE)) {
        for (row in dao.getEntryTitlesByIds(chunk)) {
            titles[row.id] = row.title
        }
    }
    return titles
}

private suspend fun loadComparedConsumedByKind(
    dao: RocheDao,
    comparedIds: Set<String>,
): Map<EntryKind, List<String>> {
    if (comparedIds.isEmpty()) return emptyMap()
    val byKind = HashMap<EntryKind, MutableList<String>>()
    for (chunk in comparedIds.chunked(CATALOG_SQL_BIND_CHUNK_SIZE)) {
        for (row in dao.getConsumedEntryKindsByIds(chunk)) {
            val kind = EntryKind.fromWireValue(row.kind)
            byKind.getOrPut(kind) { ArrayList() }.add(row.id)
        }
    }
    return byKind
}

private suspend fun buildOverviewSummaries(ctx: OverviewLoadContext): OverviewSpecBucketResult {
    val listSummaries = ArrayList<CatalogListSummary>()
    val wantSummaries = ArrayList<CatalogListSummary>()
    val activeWantScopeIds = HashSet<String>()
    val positiveThumbsById = loadPositiveComparedThumbnails(ctx.dao, ctx.globalEp.scores)

    for (spec in ctx.specs) {
        val rawRole = CollectionRole.fromWireValue(spec.role)
        val role = if (spec.collectionId == STARRED_COLLECTION_ID) CollectionRole.LIST else rawRole
        if (role == CollectionRole.FEED || role == CollectionRole.TAG) continue
        val isWant = role == CollectionRole.WANT
        if (isWant) activeWantScopeIds.add(spec.collectionId)
        val effectiveGen =
            ctx.generatorsByCollection[spec.collectionId]?.takeIf { isGeneratorEligible(spec) }
        val summary =
            buildSingleOverviewSummary(
                ctx = ctx,
                spec = spec,
                role = role,
                isWant = isWant,
                genEntity = effectiveGen,
                positiveThumbsById = positiveThumbsById,
            )
        collectSummaryBucket(
            spec = spec,
            role = role,
            isWant = isWant,
            hasEntries = summary.entryCount > 0,
            summary = summary,
            wantSummaries = wantSummaries,
            listSummaries = listSummaries,
        )
    }
    return OverviewSpecBucketResult(listSummaries, wantSummaries, activeWantScopeIds)
}

private suspend fun loadPositiveComparedThumbnails(
    dao: RocheDao,
    globalScores: Map<String, EpRankingEngine.Score>,
): Map<String, EntryThumbnailRow> {
    val positiveIds =
        globalScores.entries
            .asSequence()
            .filter { it.value.mean > EpRankingEngine.MEAN_TIE_EPSILON }
            .map { it.key }
            .toList()
    if (positiveIds.isEmpty()) return emptyMap()
    val result = HashMap<String, EntryThumbnailRow>(positiveIds.size)
    for (chunk in positiveIds.chunked(CATALOG_SQL_BIND_CHUNK_SIZE)) {
        for (row in dao.getEntryThumbnailsByIds(chunk)) {
            if (!row.thumbnailUrl.isNullOrBlank()) {
                result[row.id] = row
            }
        }
    }
    return result
}

@Suppress("LongParameterList")
private suspend fun buildSingleOverviewSummary(
    ctx: OverviewLoadContext,
    spec: CollectionSpecEntity,
    role: CollectionRole,
    isWant: Boolean,
    genEntity: CollectionGeneratorEntity?,
    positiveThumbsById: Map<String, EntryThumbnailRow>,
): CatalogListSummary {
    val specTitle = resolveSpecTitle(spec, ctx.titlesByEntryId)
    val hasGenerator = genEntity != null
    if (!spec.isDynamic) {
        val agg = ctx.manualAggregatesByColId[spec.collectionId]
        val poolTotal = agg?.totalCount ?: 0
        val poolCompleted = (agg?.consumedCount ?: 0) + (agg?.abandonedCount ?: 0)
        val entryCount = if (hasGenerator) agg?.consumedCount ?: 0 else poolTotal
        val previews =
            if (!isWant && entryCount > 0) {
                loadManualPreviewThumbnails(ctx, spec.collectionId, hasGenerator)
            } else {
                emptyList()
            }
        return CatalogListSummary(
            collectionId = spec.collectionId,
            slug = spec.slug ?: spec.collectionId,
            title = specTitle,
            isDynamic = false,
            role = role,
            entryCount = entryCount,
            isPriorityScoped = isWant,
            hasGenerator = hasGenerator,
            generatorEnabled = genEntity?.enabled == true,
            poolTotalCount = poolTotal,
            poolRemainingCount = (poolTotal - poolCompleted).coerceAtLeast(0),
            previewThumbnails = previews,
        )
    }
    val simpleKind = parseSimpleConsumedKindRule(spec.ruleJson)
    if (simpleKind != null) {
        val count = ctx.consumedCountByKind[simpleKind] ?: 0
        val previews =
            if (count > 0) {
                loadDynamicPreviewThumbnails(ctx, simpleKind, positiveThumbsById)
            } else {
                emptyList()
            }
        return CatalogListSummary(
            collectionId = spec.collectionId,
            slug = spec.slug ?: spec.collectionId,
            title = specTitle,
            isDynamic = true,
            role = role,
            entryCount = count,
            isPriorityScoped = isWant,
            previewThumbnails = previews,
        )
    }
    return buildCustomDynamicSummary(ctx, spec, specTitle, role, isWant)
}

private suspend fun loadDynamicPreviewThumbnails(
    ctx: OverviewLoadContext,
    kind: EntryKind,
    positiveThumbsById: Map<String, EntryThumbnailRow>,
): List<CatalogThumbnailPreview> {
    val chosen = ArrayList<CatalogThumbnailPreview>(OVERVIEW_PREVIEW_THUMBNAIL_LIMIT)
    val chosenIds = HashSet<String>(OVERVIEW_PREVIEW_THUMBNAIL_LIMIT)
    val topRows =
        ctx.comparedConsumedByKind[kind]
            .orEmpty()
            .asSequence()
            .mapNotNull { positiveThumbsById[it] }
            .sortedByDescending { ctx.globalEp.scores[it.id]?.mean ?: 0.0 }
            .take(OVERVIEW_PREVIEW_THUMBNAIL_LIMIT)
    appendUniquePreviewThumbnails(topRows, chosenIds, chosen)
    if (chosen.size < OVERVIEW_PREVIEW_THUMBNAIL_LIMIT) {
        val fallbackRows =
            ctx.dao.getDynamicThumbnailPreviews(
                kind = kind.wireValue,
                limit = OVERVIEW_PREVIEW_THUMBNAIL_LIMIT + chosenIds.size,
            )
        appendUniquePreviewThumbnails(fallbackRows.asSequence(), chosenIds, chosen)
    }
    return chosen
}

private fun appendUniquePreviewThumbnails(
    rows: Sequence<EntryThumbnailRow>,
    chosenIds: MutableSet<String>,
    out: MutableList<CatalogThumbnailPreview>,
) {
    val remaining = (OVERVIEW_PREVIEW_THUMBNAIL_LIMIT - out.size).coerceAtLeast(0)
    if (remaining == 0) return
    rows
        .filter { !it.thumbnailUrl.isNullOrBlank() && chosenIds.add(it.id) }
        .take(remaining)
        .forEach { row ->
            out.add(
                CatalogThumbnailPreview(
                    thumbnailUrl = row.thumbnailUrl.orEmpty(),
                    kind = EntryKind.fromWireValue(row.kind),
                )
            )
        }
}

private suspend fun loadManualPreviewThumbnails(
    ctx: OverviewLoadContext,
    collectionId: String,
    hasGenerator: Boolean,
): List<CatalogThumbnailPreview> {
    val rows =
        if (hasGenerator) {
            ctx.dao.getGeneratorThumbPreviews(
                collectionId = collectionId,
                limit = OVERVIEW_PREVIEW_THUMBNAIL_LIMIT,
            )
        } else {
            ctx.dao.getManualThumbPreviews(
                collectionId = collectionId,
                limit = OVERVIEW_PREVIEW_THUMBNAIL_LIMIT,
            )
        }
    return rows.mapNotNull { row ->
        row.thumbnailUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { thumb ->
                CatalogThumbnailPreview(thumb, EntryKind.fromWireValue(row.kind))
            }
    }
}

private suspend fun buildCustomDynamicSummary(
    ctx: OverviewLoadContext,
    spec: CollectionSpecEntity,
    specTitle: String,
    role: CollectionRole,
    isWant: Boolean,
): CatalogListSummary {
    val members = loadCustomDynamicEntries(ctx.dao, spec)
    val ranked =
        rankEntriesByOrderings(
            entries = members,
            isPriorityScoped = isWant,
            orderings = ctx.globalEp.orderings,
            precomputedScores = ctx.globalEp.scores,
        )
    val previews =
        ranked
            .asSequence()
            .mapNotNull { item ->
                item.entry.thumbnailUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { thumb ->
                        CatalogThumbnailPreview(thumb, item.entry.kind)
                    }
            }
            .take(OVERVIEW_PREVIEW_THUMBNAIL_LIMIT)
            .toList()
    return CatalogListSummary(
        collectionId = spec.collectionId,
        slug = spec.slug ?: spec.collectionId,
        title = specTitle,
        isDynamic = true,
        role = role,
        entryCount = ranked.size,
        isPriorityScoped = isWant,
        previewThumbnails = previews,
    )
}

private suspend fun loadWantToReadHomePreview(
    ctx: OverviewLoadContext,
    wantToReadEntryIds: Set<String>,
): List<RankedCatalogEntry> {
    if (wantToReadEntryIds.isEmpty()) return emptyList()
    val rawEntries = ctx.dao.getActiveEntriesForCollection(WANT_TO_READ_COLLECTION_ID)
    if (rawEntries.isEmpty()) return emptyList()
    val domainEntries = hydrateDomainEntriesFromDao(ctx.dao, rawEntries)
    val localEp =
        resolveScopedEpScores(
            ctx.dao,
            WANT_TO_READ_COLLECTION_ID,
            ctx.mergeRedirects,
            ctx.epScoreCache,
        )
    return rankEntriesByOrderings(
            entries = domainEntries,
            isPriorityScoped = true,
            orderings = localEp.orderings,
            precomputedScores = localEp.scores,
        )
        .take(HOME_WANT_TO_READ_PREVIEW_LIMIT)
}

private suspend fun buildOverviewGeneratorStates(
    ctx: OverviewLoadContext
): List<CollectionGeneratorState> {
    val eligibleSpecs =
        ctx.specs
            .filter { spec ->
                isGeneratorEligible(spec) && spec.collectionId in ctx.generatorsByCollection
            }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) {
                    resolveSpecTitle(it, ctx.titlesByEntryId)
                }
            )
    if (eligibleSpecs.isEmpty()) return emptyList()
    return eligibleSpecs.map { spec ->
        val specTitle = resolveSpecTitle(spec, ctx.titlesByEntryId)
        val gen = checkNotNull(ctx.generatorsByCollection[spec.collectionId])
        val agg = ctx.manualAggregatesByColId[spec.collectionId]
        val totalCount = agg?.totalCount ?: 0
        val consumedCount = agg?.consumedCount ?: 0
        val abandonedCount = agg?.abandonedCount ?: 0
        val completedCount = consumedCount + abandonedCount
        val remainingCount = (totalCount - completedCount).coerceAtLeast(0)
        val drawSnapshot =
            loadActiveDrawSnapshot(
                dao = ctx.dao,
                spec = spec,
                specTitle = specTitle,
                gen = gen,
                mergeRedirects = ctx.mergeRedirects,
                drawNumber = (completedCount + 1).coerceAtLeast(1),
                totalCount = totalCount,
                remainingCount = remainingCount,
            )
        CollectionGeneratorState(
            collectionId = spec.collectionId,
            collectionSlug = spec.slug ?: spec.collectionId,
            collectionTitle = specTitle,
            enabled = gen.enabled,
            currentDraw = drawSnapshot,
            consumedCount = consumedCount,
            abandonedCount = abandonedCount,
            totalCount = totalCount,
        )
    }
}

@Suppress("LongParameterList")
private suspend fun loadActiveDrawSnapshot(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    specTitle: String,
    gen: CollectionGeneratorEntity,
    mergeRedirects: Map<String, String>,
    drawNumber: Int,
    totalCount: Int,
    remainingCount: Int,
): CollectionDrawSnapshot? {
    if (!gen.enabled) return null
    val rawEntryId = gen.currentEntryId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val entryId = followCatalogMergeRedirect(rawEntryId, mergeRedirects)
    if (dao.countTerminalLogsForEntry(entryId) > 0) return null
    val raw: EntryEntity =
        dao.getEntryById(entryId)?.takeIf { it.mergedIntoId.isNullOrBlank() } ?: return null
    val idents = dao.getIdentifiersForEntry(raw.id).map { EntryIdentifierDto(it.scheme, it.value) }
    val domain = toDomainCatalogEntry(raw, idents)
    return toCollectionDrawSnapshot(
        spec = spec,
        specTitle = specTitle,
        entry = domain,
        raw = raw,
        drawNumber = drawNumber,
        totalCount = totalCount,
        remainingCount = remainingCount,
    )
}

internal fun parseSimpleConsumedKindRule(rawJson: String?): EntryKind? {
    val rule = parseDynamicCollectionRule(rawJson) ?: return null
    val isSimple =
        !rule.kind.isNullOrBlank() &&
            rule.kinds.isEmpty() &&
            rule.consumed == true &&
            rule.status.isNullOrBlank() &&
            rule.releaseYear == null &&
            rule.creator.isNullOrBlank() &&
            rule.creatorContains.isNullOrBlank()
    return if (isSimple) EntryKind.fromWireValueOrNull(rule.kind) else null
}

internal suspend fun hydrateDomainEntriesFromDao(
    dao: RocheDao,
    rawEntries: List<EntryEntity>,
): List<CatalogEntryItem> {
    if (rawEntries.isEmpty()) return emptyList()
    val ids = rawEntries.map { it.id }
    val identsByEntryId = HashMap<String, MutableList<EntryIdentifierDto>>(ids.size)
    for (chunk in ids.chunked(CATALOG_SQL_BIND_CHUNK_SIZE)) {
        for (ident in dao.getIdentifiersForEntries(chunk)) {
            identsByEntryId
                .getOrPut(ident.entryId) { ArrayList() }
                .add(EntryIdentifierDto(ident.scheme, ident.value))
        }
    }
    return rawEntries.map { raw -> toDomainCatalogEntry(raw, identsByEntryId[raw.id].orEmpty()) }
}

internal suspend fun resolveScopedEpScores(
    dao: RocheDao,
    collectionId: String,
    mergeRedirects: Map<String, String>,
    epScoreCache: EpScoreCache?,
): CachedEpScores {
    val aliasIds = buildList {
        add(collectionId)
        for ((src, dst) in mergeRedirects) {
            if (followCatalogMergeRedirect(dst, mergeRedirects) == collectionId) {
                add(src)
            }
        }
    }
    val scopedComparisons =
        if (aliasIds.size <= CATALOG_SQL_BIND_CHUNK_SIZE) {
            dao.getScopedComparisonsIn(aliasIds)
        } else {
            aliasIds
                .chunked(CATALOG_SQL_BIND_CHUNK_SIZE)
                .flatMap { dao.getScopedComparisonsIn(it) }
                .sortedBy { it.ts }
        }
    return epScoreCache?.resolveLocal(collectionId, scopedComparisons, mergeRedirects)
        ?: computeEpScoresCold(scopedComparisons, mergeRedirects)
}

internal suspend fun loadCustomDynamicEntries(
    dao: RocheDao,
    spec: CollectionSpecEntity,
): List<CatalogEntryItem> {
    val rule = parseDynamicCollectionRule(spec.ruleJson) ?: return emptyList()
    val targetKinds = extractRuleTargetKinds(rule) ?: return emptyList()
    val candidates =
        if (targetKinds.isNotEmpty()) {
            targetKinds.flatMap { dao.getActiveEntriesByKind(it.wireValue) }
        } else {
            dao.getAllEntries().filter { it.mergedIntoId.isNullOrBlank() }
        }
    if (candidates.isEmpty()) return emptyList()
    val candidateIds = candidates.map { it.id }
    val needsAllStatuses = !rule.status.isNullOrBlank()
    val statusesByEntryId =
        if (needsAllStatuses) loadStatusesByEntryIds(dao, candidateIds) else emptyMap()
    val consumedIds =
        if (needsAllStatuses) emptySet() else loadConsumedIdsChunked(dao, candidateIds)
    val matchedRaw =
        candidates
            .filter { raw ->
                val statuses =
                    if (needsAllStatuses) {
                        statusesByEntryId[raw.id].orEmpty()
                    } else if (raw.id in consumedIds) {
                        setOf(ConsumptionStatus.CONSUMED)
                    } else {
                        emptySet()
                    }
                matchesDynamicCollectionRule(
                    rule = rule,
                    kind = EntryKind.fromWireValue(raw.kind),
                    creators = com.grayvines.roche.core.decodeCreatorsJson(raw.creatorsJson),
                    releaseYear = raw.releaseYear,
                    statuses = statuses,
                )
            }
            .sortedWith(compareBy<EntryEntity> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })
    return hydrateDomainEntriesFromDao(dao, matchedRaw)
}

private suspend fun loadStatusesByEntryIds(
    dao: RocheDao,
    entryIds: List<String>,
): Map<String, Set<ConsumptionStatus>> {
    if (entryIds.isEmpty()) return emptyMap()
    val map = HashMap<String, MutableSet<ConsumptionStatus>>(entryIds.size)
    for (chunk in entryIds.chunked(CATALOG_SQL_BIND_CHUNK_SIZE)) {
        for (row in dao.getEntryStatusesByIds(chunk)) {
            map.getOrPut(row.entryId) { HashSet() }.add(ConsumptionStatus.fromWireValue(row.status))
        }
    }
    return map
}

internal suspend fun loadConsumedIdsChunked(
    dao: RocheDao,
    entryIds: List<String>,
): Set<String> {
    if (entryIds.isEmpty()) return emptySet()
    val result = HashSet<String>()
    for (chunk in entryIds.chunked(CATALOG_SQL_BIND_CHUNK_SIZE)) {
        result.addAll(dao.getConsumedEntryIdsIn(chunk))
    }
    return result
}

private const val OVERVIEW_MAX_ISO_TS_BOUND = "\uFFFF"
