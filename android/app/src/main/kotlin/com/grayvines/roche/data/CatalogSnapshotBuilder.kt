@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.remote.rocheJson
import com.grayvines.roche.ranking.EpRankingEngine
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class RawCatalogTables(
    val entries: List<EntryEntity>,
    val identifiers: List<EntryIdentifierEntity>,
    val specs: List<CollectionSpecEntity>,
    val memberships: List<CollectionEntryEntity>,
    val generators: List<CollectionGeneratorEntity>,
    val logs: List<ConsumptionLogEntity>,
    val comparisons: List<ComparisonEntity>,
)

internal fun buildCatalogSnapshot(tables: RawCatalogTables): CatalogSnapshot {
    val activeEntries =
        tables.entries.filter { it.mergedIntoId.isNullOrBlank() }.associateBy { it.id }
    val titlesByEntryId = tables.entries.associate { it.id to it.title }
    val domainEntries = buildDomainEntries(activeEntries, tables.identifiers)
    val logsByEntryId = tables.logs.groupBy { it.entryId }
    val statusesByEntryId = logsByEntryId.mapValues { (_, list) ->
        list.map { ConsumptionStatus.fromWireValue(it.status) }.toHashSet()
    }
    val consumedIds = extractEntryIdsWithStatus(tables.logs, ConsumptionStatus.CONSUMED)
    val abandonedIds = extractEntryIdsWithStatus(tables.logs, ConsumptionStatus.ABANDONED)
    val globalOrderings =
        tables.comparisons
            .filter { it.scopeCollectionId.isNullOrBlank() }
            .mapNotNull { decodeComparisonOrdering(it.orderingJson) }
    val globalScores = EpRankingEngine.inferScores(domainEntries.keys.toList(), globalOrderings)
    val membershipsByCollection = groupSortedMemberships(tables.memberships)
    val generatorsByCollection = tables.generators.associateBy { it.collectionId }

    val collectionBundle =
        buildRankedCollections(
            RankedCollectionsInput(
                specs = tables.specs,
                titlesByEntryId = titlesByEntryId,
                membershipsByCollection = membershipsByCollection,
                generatorsByCollection = generatorsByCollection,
                domainEntries = domainEntries,
                consumedIds = consumedIds,
                abandonedIds = abandonedIds,
                statusesByEntryId = statusesByEntryId,
                logsByEntryId = logsByEntryId,
                globalOrderings = globalOrderings,
                globalScores = globalScores,
                allComparisons = tables.comparisons,
            )
        )
    val globalTiers =
        computePerKindGlobalTiers(domainEntries, consumedIds, globalScores, globalOrderings)
    val diaryItems = buildDiaryItems(tables.logs, domainEntries, globalTiers)
    val generatorStates =
        buildCollectionGeneratorStates(
            specs = tables.specs,
            titlesByEntryId = titlesByEntryId,
            generatorsByCollection = generatorsByCollection,
            membershipsByCollection = membershipsByCollection,
            rawEntries = activeEntries,
            domainEntries = domainEntries,
            consumedIds = consumedIds,
            abandonedIds = abandonedIds,
        )

    return CatalogSnapshot(
        lists = collectionBundle.sortedLists,
        wantQueues = collectionBundle.sortedWants,
        entriesByCollectionId = collectionBundle.entriesByCollectionId,
        diaryEntries = diaryItems,
        generators = generatorStates,
        comparisonCount = tables.comparisons.size,
    )
}

internal fun resolveSpecTitle(
    spec: CollectionSpecEntity,
    titlesByEntryId: Map<String, String>,
): String =
    titlesByEntryId[spec.collectionId]?.takeIf { it.isNotBlank() } ?: spec.slug ?: spec.collectionId

private fun buildDomainEntries(
    activeEntries: Map<String, EntryEntity>,
    identifiers: List<EntryIdentifierEntity>,
): Map<String, CatalogEntryItem> {
    val identsByEntryId =
        identifiers
            .groupBy { it.entryId }
            .mapValues { (_, list) -> list.map { EntryIdentifierDto(it.scheme, it.value) } }
    return activeEntries.mapValues { (_, raw) ->
        toDomainCatalogEntry(raw, identsByEntryId[raw.id].orEmpty())
    }
}

private fun extractEntryIdsWithStatus(
    logs: List<ConsumptionLogEntity>,
    status: ConsumptionStatus,
): Set<String> =
    logs
        .filter { ConsumptionStatus.fromWireValue(it.status) == status }
        .map { it.entryId }
        .toHashSet()

private fun groupSortedMemberships(
    memberships: List<CollectionEntryEntity>
): Map<String, List<CollectionEntryEntity>> =
    memberships
        .sortedWith(
            compareBy<CollectionEntryEntity> { it.position ?: Double.MAX_VALUE }
                .thenBy { it.addedAt }
        )
        .groupBy { it.collectionId }

private data class RankedCollectionsBundle(
    val entriesByCollectionId: Map<String, List<RankedCatalogEntry>>,
    val sortedLists: List<CatalogListSummary>,
    val sortedWants: List<CatalogListSummary>,
)

@Suppress("LongParameterList")
private data class RankedCollectionsInput(
    val specs: List<CollectionSpecEntity>,
    val titlesByEntryId: Map<String, String>,
    val membershipsByCollection: Map<String, List<CollectionEntryEntity>>,
    val generatorsByCollection: Map<String, CollectionGeneratorEntity>,
    val domainEntries: Map<String, CatalogEntryItem>,
    val consumedIds: Set<String>,
    val abandonedIds: Set<String>,
    val statusesByEntryId: Map<String, Set<ConsumptionStatus>>,
    val logsByEntryId: Map<String, List<ConsumptionLogEntity>>,
    val globalOrderings: List<List<List<String>>>,
    val globalScores: Map<String, EpRankingEngine.Score>,
    val allComparisons: List<ComparisonEntity>,
)

private fun buildRankedCollections(input: RankedCollectionsInput): RankedCollectionsBundle {
    val entriesByCollectionId = LinkedHashMap<String, List<RankedCatalogEntry>>()
    val listSummaries = ArrayList<CatalogListSummary>()
    val wantSummaries = ArrayList<CatalogListSummary>()

    for (spec in input.specs) {
        val role = CollectionRole.fromWireValue(spec.role)
        if (role == CollectionRole.FEED || role == CollectionRole.TAG) continue
        val mode = CollectionMode.fromWireValue(spec.mode)
        val isWant = role == CollectionRole.WANT || spec.collectionId == UP_NEXT_COLLECTION_ID
        val genEntity = input.generatorsByCollection[spec.collectionId]
        val rawMemberships = input.membershipsByCollection[spec.collectionId].orEmpty()
        val ranked =
            resolveAndRankSpec(input, spec, mode, isWant, genEntity != null, rawMemberships)
        entriesByCollectionId[spec.collectionId] = ranked
        val summary =
            toCatalogListSummary(
                spec = spec,
                specTitle = resolveSpecTitle(spec, input.titlesByEntryId),
                mode = mode,
                role = role,
                isWant = isWant,
                rankedSize = ranked.size,
                genEntity = genEntity,
                rawMemberships = rawMemberships,
                domainEntries = input.domainEntries,
                consumedIds = input.consumedIds,
                abandonedIds = input.abandonedIds,
            )
        collectSummaryBucket(
            spec,
            isWant,
            ranked.isNotEmpty(),
            summary,
            wantSummaries,
            listSummaries,
        )
    }

    return RankedCollectionsBundle(
        entriesByCollectionId = entriesByCollectionId,
        sortedLists = sortListSummaries(listSummaries),
        sortedWants = sortWantSummaries(wantSummaries),
    )
}

@Suppress("LongParameterList")
private fun resolveAndRankSpec(
    input: RankedCollectionsInput,
    spec: CollectionSpecEntity,
    mode: CollectionMode,
    isWant: Boolean,
    hasGenerator: Boolean,
    rawMemberships: List<CollectionEntryEntity>,
): List<RankedCatalogEntry> {
    val memberIds =
        resolveCollectionMemberIds(
            spec = spec,
            mode = mode,
            hasGenerator = hasGenerator,
            domainEntries = input.domainEntries,
            consumedIds = input.consumedIds,
            statusesByEntryId = input.statusesByEntryId,
            memberships = rawMemberships,
        )
    return rankSpecEntries(
        specId = spec.collectionId,
        isWant = isWant,
        memberEntries = memberIds.mapNotNull { input.domainEntries[it] },
        allComparisons = input.allComparisons,
        globalOrderings = input.globalOrderings,
        globalScores = input.globalScores,
        logsByEntryId = input.logsByEntryId,
    )
}

@Suppress("LongParameterList")
private fun toCatalogListSummary(
    spec: CollectionSpecEntity,
    specTitle: String,
    mode: CollectionMode,
    role: CollectionRole,
    isWant: Boolean,
    rankedSize: Int,
    genEntity: CollectionGeneratorEntity?,
    rawMemberships: List<CollectionEntryEntity>,
    domainEntries: Map<String, CatalogEntryItem>,
    consumedIds: Set<String>,
    abandonedIds: Set<String>,
): CatalogListSummary {
    val poolIds = rawMemberships.map { it.entryId }.filter { it in domainEntries }.distinct()
    val poolCompleted = poolIds.count { it in consumedIds || it in abandonedIds }
    return CatalogListSummary(
        collectionId = spec.collectionId,
        slug = spec.slug ?: spec.collectionId,
        title = specTitle,
        mode = mode,
        role = role,
        entryCount = rankedSize,
        isPriorityScoped = isWant,
        hasGenerator = genEntity != null,
        generatorEnabled = genEntity?.enabled == true,
        poolTotalCount = poolIds.size,
        poolRemainingCount = (poolIds.size - poolCompleted).coerceAtLeast(0),
    )
}

@Suppress("LongParameterList")
private fun rankSpecEntries(
    specId: String,
    isWant: Boolean,
    memberEntries: List<CatalogEntryItem>,
    allComparisons: List<ComparisonEntity>,
    globalOrderings: List<List<List<String>>>,
    globalScores: Map<String, EpRankingEngine.Score>,
    logsByEntryId: Map<String, List<ConsumptionLogEntity>>,
): List<RankedCatalogEntry> {
    val orderings =
        if (isWant) {
            allComparisons
                .filter { it.scopeCollectionId == specId }
                .mapNotNull { decodeComparisonOrdering(it.orderingJson) }
        } else {
            globalOrderings
        }
    return rankEntriesByOrderings(
        entries = memberEntries,
        isPriorityScoped = isWant,
        orderings = orderings,
        precomputedGlobalScores = if (isWant) null else globalScores,
        logsByEntryId = logsByEntryId,
    )
}

private fun sortListSummaries(summaries: List<CatalogListSummary>): List<CatalogListSummary> =
    summaries.sortedWith(
        compareByDescending<CatalogListSummary> { it.entryCount > 0 || it.generatorEnabled }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    )

private fun sortWantSummaries(summaries: List<CatalogListSummary>): List<CatalogListSummary> =
    summaries.sortedWith(
        compareByDescending<CatalogListSummary> { it.entryCount }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    )

@Suppress("LongParameterList")
private fun collectSummaryBucket(
    spec: CollectionSpecEntity,
    isWant: Boolean,
    hasEntries: Boolean,
    summary: CatalogListSummary,
    wantSummaries: MutableList<CatalogListSummary>,
    listSummaries: MutableList<CatalogListSummary>,
) {
    if (isWant) {
        if (hasEntries || spec.collectionId == UP_NEXT_COLLECTION_ID) {
            wantSummaries.add(summary)
        }
    } else if (spec.showInLists) {
        listSummaries.add(summary)
    }
}

@Suppress("LongParameterList")
internal fun resolveCollectionMemberIds(
    spec: CollectionSpecEntity,
    mode: CollectionMode,
    hasGenerator: Boolean,
    domainEntries: Map<String, CatalogEntryItem>,
    consumedIds: Set<String>,
    memberships: List<CollectionEntryEntity>,
    statusesByEntryId: Map<String, Set<ConsumptionStatus>> = emptyMap(),
): List<String> =
    when (mode) {
        CollectionMode.DYNAMIC -> {
            val rule = parseDynamicCollectionRule(spec.ruleJson)
            domainEntries.values
                .filter { entry ->
                    val statuses =
                        statusesByEntryId[entry.id]
                            ?: if (entry.id in consumedIds) {
                                setOf(ConsumptionStatus.CONSUMED)
                            } else {
                                emptySet()
                            }
                    matchesDynamicCollectionRule(
                        rule = rule,
                        kind = entry.kind,
                        creators = entry.creators,
                        releaseYear = entry.releaseYear,
                        statuses = statuses,
                    )
                }
                .sortedWith(compareBy<CatalogEntryItem> { it.title.lowercase() }.thenBy { it.id })
                .map { it.id }
        }
        CollectionMode.MANUAL,
        CollectionMode.FEED -> {
            val rawIds = memberships.map { it.entryId }.filter { it in domainEntries }.distinct()
            if (hasGenerator) rawIds.filter { it in consumedIds } else rawIds
        }
    }

internal fun parseDynamicCollectionRule(rawJson: String?): DynamicCollectionRuleDto? =
    rawJson
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
            runCatching { rocheJson.decodeFromString(DynamicCollectionRuleDto.serializer(), raw) }
                .getOrNull()
        }

internal fun matchesDynamicCollectionRule(
    rule: DynamicCollectionRuleDto?,
    kind: EntryKind,
    creators: List<String>,
    releaseYear: Int?,
    statuses: Set<ConsumptionStatus>,
): Boolean {
    if (rule == null) return false
    val allowedKinds = parseRuleTargetKinds(rule) ?: return false
    val requiredStatus = parseRuleTargetStatus(rule) ?: return false
    val kindMatches = allowedKinds.isEmpty() || kind in allowedKinds
    val isConsumed = ConsumptionStatus.CONSUMED in statuses
    val consumedMatches = rule.consumed == null || isConsumed == rule.consumed
    val statusMatches = requiredStatus.isEmpty() || requiredStatus.all { it in statuses }
    val yearMatches = rule.releaseYear == null || releaseYear == rule.releaseYear
    val creatorNeedle = (rule.creator ?: rule.creatorContains)?.trim()?.takeIf { it.isNotEmpty() }
    val creatorMatches =
        creatorNeedle == null || creators.any { it.contains(creatorNeedle, ignoreCase = true) }
    return kindMatches && consumedMatches && statusMatches && yearMatches && creatorMatches
}

private fun parseRuleTargetKinds(rule: DynamicCollectionRuleDto): Set<EntryKind>? {
    val result = LinkedHashSet<EntryKind>()
    if (!rule.kind.isNullOrBlank()) {
        val parsed = EntryKind.fromWireValueOrNull(rule.kind) ?: return null
        result.add(parsed)
    }
    for (rawKind in rule.kinds) {
        if (rawKind.isBlank()) continue
        val parsed = EntryKind.fromWireValueOrNull(rawKind) ?: return null
        result.add(parsed)
    }
    return result
}

private fun parseRuleTargetStatus(rule: DynamicCollectionRuleDto): Set<ConsumptionStatus>? {
    val rawStatus = rule.status?.trim()?.takeIf { it.isNotEmpty() } ?: return emptySet()
    val parsed = ConsumptionStatus.fromWireValueOrNull(rawStatus) ?: return null
    return setOf(parsed)
}

@Suppress("LongParameterList")
internal fun rankEntriesByOrderings(
    entries: List<CatalogEntryItem>,
    isPriorityScoped: Boolean,
    orderings: List<List<List<String>>>,
    precomputedGlobalScores: Map<String, EpRankingEngine.Score>? = null,
    logsByEntryId: Map<String, List<ConsumptionLogEntity>> = emptyMap(),
): List<RankedCatalogEntry> {
    if (entries.isEmpty()) return emptyList()
    val memberIds = entries.map { it.id }
    val memberSet = memberIds.toHashSet()
    val relevantComparisonCount = orderings.count { ord ->
        ord.any { group -> group.any { it in memberSet } }
    }
    val localScores =
        if (isPriorityScoped || precomputedGlobalScores == null) {
            EpRankingEngine.inferScores(memberIds, orderings)
        } else {
            memberIds.associateWith { precomputedGlobalScores[it] ?: EpRankingEngine.Score() }
        }
    val sortedEntries = sortEntriesByScoreAndTitle(entries, localScores)
    val quantiles =
        if (!isPriorityScoped && relevantComparisonCount > 0) {
            EpRankingEngine.rescaleToQuantiles(localScores)
        } else {
            emptyMap()
        }
    val minMean = localScores.values.minOfOrNull { it.mean } ?: 0.0
    val maxMean = localScores.values.maxOfOrNull { it.mean } ?: 0.0
    return sortedEntries.mapIndexed { idx, domain ->
        val score = localScores[domain.id] ?: EpRankingEngine.Score()
        val latestLog = logsByEntryId[domain.id]?.firstOrNull()
        RankedCatalogEntry(
            entry = domain,
            rank = idx + 1,
            mean = score.mean,
            variance = score.variance,
            scoreOutOfTen = EpRankingEngine.displayScoreOutOfTen(score.mean, minMean, maxMean),
            tier = quantiles[domain.id]?.let(EpRankingEngine::tierLabel),
            latestStatus = latestLog?.let { ConsumptionStatus.fromWireValue(it.status) },
            latestNotes = latestLog?.notes?.takeIf { it.isNotBlank() },
        )
    }
}

internal fun sortEntriesByScoreAndTitle(
    entries: List<CatalogEntryItem>,
    scores: Map<String, EpRankingEngine.Score>,
): List<CatalogEntryItem> {
    val sorted = entries.sortedWith(scoreAndTitleComparator(scores)).toMutableList()
    normalizeTieGroupsInPlace(sorted, scores)
    return sorted
}

private fun scoreAndTitleComparator(
    scores: Map<String, EpRankingEngine.Score>
): Comparator<CatalogEntryItem> = Comparator { a, b ->
    val ma = (scores[a.id] ?: EpRankingEngine.Score()).mean
    val mb = (scores[b.id] ?: EpRankingEngine.Score()).mean
    val meanCmp = mb.compareTo(ma)
    if (meanCmp != 0) {
        meanCmp
    } else {
        val titleCmp = a.title.lowercase().compareTo(b.title.lowercase())
        if (titleCmp != 0) titleCmp else a.id.compareTo(b.id)
    }
}

private fun normalizeTieGroupsInPlace(
    sorted: MutableList<CatalogEntryItem>,
    scores: Map<String, EpRankingEngine.Score>,
) {
    val tieComparator = compareBy<CatalogEntryItem> { it.title.lowercase() }.thenBy { it.id }
    var tieStart = 0
    while (tieStart < sorted.size) {
        val baseMean = (scores[sorted[tieStart].id] ?: EpRankingEngine.Score()).mean
        var tieEnd = tieStart + 1
        while (
            tieEnd < sorted.size &&
                abs((scores[sorted[tieEnd].id] ?: EpRankingEngine.Score()).mean - baseMean) <=
                    EpRankingEngine.MEAN_TIE_EPSILON
        ) {
            tieEnd += 1
        }
        if (tieEnd - tieStart > 1) {
            sorted.subList(tieStart, tieEnd).sortWith(tieComparator)
        }
        tieStart = tieEnd
    }
}

private fun computePerKindGlobalTiers(
    domainEntries: Map<String, CatalogEntryItem>,
    consumedIds: Set<String>,
    globalScores: Map<String, EpRankingEngine.Score>,
    globalOrderings: List<List<List<String>>>,
): Map<String, Char> {
    val tiers = HashMap<String, Char>()
    val byKind = consumedIds.mapNotNull { domainEntries[it] }.groupBy { it.kind }
    for ((_, entries) in byKind) {
        val idSet = entries.map { it.id }.toHashSet()
        val hasComparisons = globalOrderings.any { ord ->
            ord.any { group -> group.any { it in idSet } }
        }
        if (!hasComparisons) continue
        val kindScores = entries.associate { entry ->
            entry.id to (globalScores[entry.id] ?: EpRankingEngine.Score())
        }
        val quantiles = EpRankingEngine.rescaleToQuantiles(kindScores)
        for ((id, q) in quantiles) {
            tiers[id] = EpRankingEngine.tierLabel(q)
        }
    }
    return tiers
}

private fun buildDiaryItems(
    logs: List<ConsumptionLogEntity>,
    domainEntries: Map<String, CatalogEntryItem>,
    globalTiers: Map<String, Char>,
): List<DiaryEntryItem> = logs.mapNotNull { log ->
    domainEntries[log.entryId]
        ?.takeIf { it.kind.isDiaryVisibleKind }
        ?.let { entry ->
            val (monthKey, dateLabel) = formatDiaryTimestamp(log.ts)
            val status = ConsumptionStatus.fromWireValue(log.status)
            DiaryEntryItem(
                id = log.id,
                entryId = entry.id,
                kind = entry.kind,
                title = entry.title,
                creators = entry.creators,
                releaseYear = entry.releaseYear,
                ts = log.ts,
                monthKey = monthKey,
                dateLabel = dateLabel,
                status = status,
                notes = log.notes?.takeIf { it.isNotBlank() },
                tier = if (status == ConsumptionStatus.CONSUMED) globalTiers[entry.id] else null,
            )
        }
}

@Suppress("LongParameterList")
private fun buildCollectionGeneratorStates(
    specs: List<CollectionSpecEntity>,
    titlesByEntryId: Map<String, String>,
    generatorsByCollection: Map<String, CollectionGeneratorEntity>,
    membershipsByCollection: Map<String, List<CollectionEntryEntity>>,
    rawEntries: Map<String, EntryEntity>,
    domainEntries: Map<String, CatalogEntryItem>,
    consumedIds: Set<String>,
    abandonedIds: Set<String>,
): List<CollectionGeneratorState> =
    specs
        .filter { spec ->
            CollectionMode.fromWireValue(spec.mode) == CollectionMode.MANUAL &&
                CollectionRole.fromWireValue(spec.role) == CollectionRole.LIST &&
                spec.collectionId in generatorsByCollection
        }
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { resolveSpecTitle(it, titlesByEntryId) }
        )
        .map { spec ->
            buildSingleGeneratorState(
                spec = spec,
                specTitle = resolveSpecTitle(spec, titlesByEntryId),
                gen = checkNotNull(generatorsByCollection[spec.collectionId]),
                memberships = membershipsByCollection[spec.collectionId].orEmpty(),
                rawEntries = rawEntries,
                domainEntries = domainEntries,
                consumedIds = consumedIds,
                abandonedIds = abandonedIds,
            )
        }

@Suppress("LongParameterList")
private fun buildSingleGeneratorState(
    spec: CollectionSpecEntity,
    specTitle: String,
    gen: CollectionGeneratorEntity,
    memberships: List<CollectionEntryEntity>,
    rawEntries: Map<String, EntryEntity>,
    domainEntries: Map<String, CatalogEntryItem>,
    consumedIds: Set<String>,
    abandonedIds: Set<String>,
): CollectionGeneratorState {
    val memberIds = memberships.map { it.entryId }.filter { it in domainEntries }.distinct()
    val totalCount = memberIds.size
    val consumedCount = memberIds.count { it in consumedIds }
    val abandonedCount = memberIds.count { it in abandonedIds && it !in consumedIds }
    val completedCount = consumedCount + abandonedCount
    val remainingCount = (totalCount - completedCount).coerceAtLeast(0)
    val enabled = gen.enabled
    val activeEntryId =
        gen.currentEntryId?.takeIf {
            enabled && it.isNotBlank() && it !in consumedIds && it !in abandonedIds
        }
    val activeDomain = activeEntryId?.let { domainEntries[it] }
    val activeRaw = activeEntryId?.let { rawEntries[it] }
    val snapshot =
        if (activeDomain != null && activeRaw != null) {
            toCollectionDrawSnapshot(
                spec = spec,
                specTitle = specTitle,
                entry = activeDomain,
                raw = activeRaw,
                drawNumber = (completedCount + 1).coerceAtLeast(1),
                totalCount = totalCount,
                remainingCount = remainingCount,
            )
        } else {
            null
        }
    return CollectionGeneratorState(
        collectionId = spec.collectionId,
        collectionSlug = spec.slug ?: spec.collectionId,
        collectionTitle = specTitle,
        enabled = enabled,
        currentDraw = snapshot,
        consumedCount = consumedCount,
        abandonedCount = abandonedCount,
        totalCount = totalCount,
    )
}

@Suppress("LongParameterList")
internal fun toCollectionDrawSnapshot(
    spec: CollectionSpecEntity,
    specTitle: String,
    entry: CatalogEntryItem,
    raw: EntryEntity,
    drawNumber: Int,
    totalCount: Int,
    remainingCount: Int,
): CollectionDrawSnapshot {
    val identsByScheme = entry.identifiers.associate { it.scheme.lowercase() to it.value }
    val ytMusicId = pickPreferredYoutubeMusicId(entry.identifiers)
    val tags = extractMetadataGenres(raw.metadataJson)
    return CollectionDrawSnapshot(
        collectionId = spec.collectionId,
        collectionSlug = spec.slug ?: spec.collectionId,
        collectionTitle = specTitle,
        entryId = entry.id,
        kind = entry.kind,
        title = entry.title,
        creator = entry.primaryCreator,
        releaseDate = entry.releaseYear?.takeIf { it > 0 }?.toString().orEmpty(),
        url = entry.url,
        imageUrl = sanitizeHttpUrl(entry.thumbnailUrl),
        thumbnailUrl = sanitizeHttpUrl(entry.thumbnailUrl),
        tags = tags,
        drawNumber = drawNumber,
        totalCount = totalCount,
        remainingCount = remainingCount,
        youtubeMusicId = ytMusicId,
        spotifyId = identsByScheme["spotify"],
        appleMusicId = identsByScheme["apple_music"],
        tidalId = identsByScheme["tidal"],
        qobuzId = identsByScheme["qobuz"],
        deezerId = identsByScheme["deezer"],
        amazonMusicId = identsByScheme["amazon_music"],
        wikipediaUrl =
            identsByScheme["wikipedia"]
                ?.let { externalIdentifierUrl("wikipedia", it) }
                ?.let(::sanitizeHttpUrl),
    )
}

private fun pickPreferredYoutubeMusicId(identifiers: List<EntryIdentifierDto>): String? {
    val values =
        identifiers
            .filter { it.scheme.equals("youtube_music", ignoreCase = true) }
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
    return values.firstOrNull {
        buildYoutubeMusicAlbumUrl(it).startsWith("https://music.youtube.com/browse/MPREb")
    }
        ?: values.firstOrNull {
            buildYoutubeMusicAlbumUrl(it).startsWith("https://music.youtube.com/playlist?list=")
        }
        ?: values.firstOrNull()
}

internal fun extractMetadataGenres(metadataJson: String): List<String> {
    val obj =
        runCatching { rocheJson.parseToJsonElement(metadataJson) as? JsonObject }.getOrNull()
            ?: return emptyList()
    val primary =
        (obj["genres"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val fallback =
        (obj["styles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    return (primary?.takeIf { it.isNotEmpty() } ?: fallback.orEmpty())
        .map { it.replace('-', ' ').trim() }
        .filter { it.isNotEmpty() }
        .take(MAX_GENRE_CHIPS)
}

internal fun toDomainCatalogEntry(
    raw: EntryEntity,
    identifiers: List<EntryIdentifierDto>,
): CatalogEntryItem =
    CatalogEntryItem(
        id = raw.id,
        kind = EntryKind.fromWireValue(raw.kind),
        title = raw.title,
        creators = decodeCreatorsJson(raw.creatorsJson),
        url = raw.url?.takeIf { it.isNotBlank() },
        publishedAt = raw.publishedAt?.takeIf { it.isNotBlank() },
        releaseYear = raw.releaseYear,
        summary = raw.summary?.takeIf { it.isNotBlank() },
        thumbnailUrl = raw.thumbnailUrl?.takeIf { it.isNotBlank() },
        identifiers = identifiers,
    )

internal fun decodeCreatorsJson(rawJson: String): List<String> = runCatching {
    val arr = rocheJson.parseToJsonElement(rawJson) as? JsonArray
    arr?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
}
    .getOrNull()
    .orEmpty()

internal fun encodeCreatorsJson(creators: List<String>): String =
    rocheJson.encodeToString(
        ListSerializer(kotlinx.serialization.serializer<String>()),
        creators.map { it.trim() }.filter { it.isNotEmpty() },
    )

internal fun decodeComparisonOrdering(rawJson: String): List<List<String>>? = runCatching {
    rocheJson.decodeFromString(
        ListSerializer(ListSerializer(kotlinx.serialization.serializer<String>())),
        rawJson,
    )
}
    .getOrNull()
    ?.takeIf { EpRankingEngine.validateOrdering(it).isSuccess }

private fun formatDiaryTimestamp(rawIso: String): Pair<String, String> {
    val parsed = runCatching { Instant.parse(rawIso.trim()) }.getOrNull()
    return if (parsed != null) {
        val zdt = parsed.atZone(ZoneOffset.UTC)
        DIARY_MONTH_FMT.format(zdt) to DIARY_DAY_FMT.format(zdt)
    } else {
        val prefix = rawIso.take(MONTH_PREFIX_LEN).ifBlank { "Recent" }
        prefix to rawIso.take(DATE_PREFIX_LEN)
    }
}

private const val MAX_GENRE_CHIPS = 2
private const val MONTH_PREFIX_LEN = 7
private const val DATE_PREFIX_LEN = 10
private val DIARY_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)
private val DIARY_DAY_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.US)
