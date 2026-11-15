@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.CatalogEntryEntity
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.remote.rocheJson
import com.grayvines.roche.ranking.EpRankingEngine
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class RawCatalogTables(
    val entries: List<CatalogEntryEntity>,
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
    val identsByEntryId =
        tables.identifiers
            .groupBy { it.entryId }
            .mapValues { (_, list) -> list.map { EntryIdentifierDto(it.scheme, it.value) } }
    val domainEntries = activeEntries.mapValues { (_, raw) ->
        toDomainCatalogEntry(raw, identsByEntryId[raw.id].orEmpty())
    }
    val logsByEntryId = tables.logs.groupBy { it.entryId }
    val consumedIds = extractEntryIdsWithStatus(tables.logs, ConsumptionStatus.CONSUMED)
    val abandonedIds = extractEntryIdsWithStatus(tables.logs, ConsumptionStatus.ABANDONED)
    val globalScores = computeGlobalScores(domainEntries.keys, tables.comparisons)
    val membershipsByCollection = groupSortedMemberships(tables.memberships)
    val generatorCollectionIds = tables.generators.map { it.collectionId }.toHashSet()

    val collectionBundle =
        buildRankedCollections(
            specs = tables.specs,
            membershipsByCollection = membershipsByCollection,
            generatorCollectionIds = generatorCollectionIds,
            domainEntries = domainEntries,
            consumedIds = consumedIds,
            logsByEntryId = logsByEntryId,
            globalScores = globalScores,
            allComparisons = tables.comparisons,
        )
    val globalTiers = computePerKindGlobalTiers(domainEntries, consumedIds, globalScores)
    val diaryItems = buildDiaryItems(tables.logs, domainEntries, globalTiers)
    val albumGen =
        buildAlbumGeneratorState(
            generators = tables.generators,
            memberships = membershipsByCollection[ALBUMS_1001_COLLECTION_ID].orEmpty(),
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
        albumGenerator = albumGen,
        comparisonCount = tables.comparisons.size,
    )
}

private fun extractEntryIdsWithStatus(
    logs: List<ConsumptionLogEntity>,
    status: ConsumptionStatus,
): Set<String> =
    logs
        .filter { ConsumptionStatus.fromWireValue(it.status) == status }
        .map { it.entryId }
        .toHashSet()

private fun computeGlobalScores(
    entryIds: Set<String>,
    comparisons: List<ComparisonEntity>,
): Map<String, EpRankingEngine.Score> {
    val globalOrderings =
        comparisons
            .filter { it.scopeCollectionId.isNullOrBlank() }
            .mapNotNull { decodeComparisonOrdering(it.orderingJson) }
    return EpRankingEngine.inferScores(entryIds.toList(), globalOrderings)
}

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
private fun buildRankedCollections(
    specs: List<CollectionSpecEntity>,
    membershipsByCollection: Map<String, List<CollectionEntryEntity>>,
    generatorCollectionIds: Set<String>,
    domainEntries: Map<String, CatalogEntryItem>,
    consumedIds: Set<String>,
    logsByEntryId: Map<String, List<ConsumptionLogEntity>>,
    globalScores: Map<String, EpRankingEngine.Score>,
    allComparisons: List<ComparisonEntity>,
): RankedCollectionsBundle {
    val entriesByCollectionId = LinkedHashMap<String, List<RankedCatalogEntry>>()
    val listSummaries = ArrayList<CatalogListSummary>()
    val wantSummaries = ArrayList<CatalogListSummary>()

    for (spec in specs) {
        val mode = CollectionMode.fromWireValue(spec.mode)
        val role = CollectionRole.fromWireValue(spec.role)
        if (role == CollectionRole.FEED || role == CollectionRole.TAG) continue
        val isWant = role == CollectionRole.WANT || spec.collectionId == UP_NEXT_COLLECTION_ID
        val hasGenerator =
            spec.collectionId in generatorCollectionIds ||
                spec.collectionId == ALBUMS_1001_COLLECTION_ID
        val memberIds =
            resolveCollectionMemberIds(
                spec = spec,
                mode = mode,
                hasGenerator = hasGenerator,
                domainEntries = domainEntries,
                consumedIds = consumedIds,
                memberships = membershipsByCollection[spec.collectionId].orEmpty(),
            )
        val ranked =
            rankCollectionMembers(
                collectionId = spec.collectionId,
                isPriorityScoped = isWant,
                memberIds = memberIds,
                domainEntries = domainEntries,
                logsByEntryId = logsByEntryId,
                globalScores = globalScores,
                allComparisons = allComparisons,
            )
        entriesByCollectionId[spec.collectionId] = ranked
        val summary =
            CatalogListSummary(
                collectionId = spec.collectionId,
                slug = spec.slug ?: spec.collectionId,
                title = spec.title,
                mode = mode,
                role = role,
                entryCount = ranked.size,
                isPriorityScoped = isWant,
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

private fun sortListSummaries(summaries: List<CatalogListSummary>): List<CatalogListSummary> =
    summaries.sortedWith(
        compareByDescending<CatalogListSummary> { it.entryCount > 0 }
            .thenBy { it.collectionId == ALBUMS_1001_COLLECTION_ID }
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
    } else if (spec.showInLists || spec.collectionId == ALBUMS_1001_COLLECTION_ID) {
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
): List<String> =
    when (mode) {
        CollectionMode.DYNAMIC -> {
            val rule =
                spec.ruleJson
                    ?.takeIf { it.isNotBlank() }
                    ?.let { raw ->
                        runCatching {
                            rocheJson.decodeFromString(
                                DynamicCollectionRuleDto.serializer(),
                                raw,
                            )
                        }
                            .getOrNull()
                    }
            domainEntries.values
                .filter { entry ->
                    matchesDynamicRule(entry, rule, consumedIds)
                }
                .map { it.id }
        }
        CollectionMode.MANUAL,
        CollectionMode.FEED -> {
            val rawIds = memberships.map { it.entryId }.filter { it in domainEntries }.distinct()
            if (hasGenerator) rawIds.filter { it in consumedIds } else rawIds
        }
    }

private fun matchesDynamicRule(
    entry: CatalogEntryItem,
    rule: DynamicCollectionRuleDto?,
    consumedIds: Set<String>,
): Boolean {
    if (rule == null) return false
    val kindMatches =
        rule.kind.isNullOrBlank() || entry.kind.wireValue.equals(rule.kind, ignoreCase = true)
    val consumedMatches =
        when (rule.consumed) {
            null -> true
            true -> entry.id in consumedIds
            false -> entry.id !in consumedIds
        }
    val creatorMatches =
        rule.creator.isNullOrBlank() ||
            entry.creators.any { it.contains(rule.creator, ignoreCase = true) }
    return kindMatches && consumedMatches && creatorMatches
}

@Suppress("LongParameterList")
private fun rankCollectionMembers(
    collectionId: String,
    isPriorityScoped: Boolean,
    memberIds: List<String>,
    domainEntries: Map<String, CatalogEntryItem>,
    logsByEntryId: Map<String, List<ConsumptionLogEntity>>,
    globalScores: Map<String, EpRankingEngine.Score>,
    allComparisons: List<ComparisonEntity>,
): List<RankedCatalogEntry> {
    if (memberIds.isEmpty()) return emptyList()
    val localScores =
        if (isPriorityScoped) {
            val scopedOrderings =
                allComparisons
                    .filter { it.scopeCollectionId == collectionId }
                    .mapNotNull { decodeComparisonOrdering(it.orderingJson) }
            EpRankingEngine.inferScores(memberIds, scopedOrderings)
        } else {
            memberIds.associateWith { globalScores[it] ?: EpRankingEngine.Score() }
        }
    val orderedIds = EpRankingEngine.rankIdsByScore(memberIds, localScores)
    val quantiles =
        if (isPriorityScoped) {
            emptyMap()
        } else {
            EpRankingEngine.rescaleToQuantiles(localScores)
        }
    val minMean = localScores.values.minOfOrNull { it.mean } ?: 0.0
    val maxMean = localScores.values.maxOfOrNull { it.mean } ?: 0.0
    return orderedIds.mapIndexedNotNull { idx, id ->
        domainEntries[id]?.let { domain ->
            val score = localScores[id] ?: EpRankingEngine.Score()
            val latestLog = logsByEntryId[id]?.firstOrNull()
            val tier =
                if (isPriorityScoped) {
                    null
                } else {
                    quantiles[id]?.let(EpRankingEngine::tierLabel)
                }
            RankedCatalogEntry(
                entry = domain,
                rank = idx + 1,
                mean = score.mean,
                variance = score.variance,
                scoreOutOfTen = EpRankingEngine.displayScoreOutOfTen(score.mean, minMean, maxMean),
                tier = tier,
                latestStatus = latestLog?.let { ConsumptionStatus.fromWireValue(it.status) },
                latestNotes = latestLog?.notes?.takeIf { it.isNotBlank() },
            )
        }
    }
}

private fun computePerKindGlobalTiers(
    domainEntries: Map<String, CatalogEntryItem>,
    consumedIds: Set<String>,
    globalScores: Map<String, EpRankingEngine.Score>,
): Map<String, Char> {
    val tiers = HashMap<String, Char>()
    val byKind = consumedIds.mapNotNull { domainEntries[it] }.groupBy { it.kind }
    for ((_, entries) in byKind) {
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
private fun buildAlbumGeneratorState(
    generators: List<CollectionGeneratorEntity>,
    memberships: List<CollectionEntryEntity>,
    rawEntries: Map<String, CatalogEntryEntity>,
    domainEntries: Map<String, CatalogEntryItem>,
    consumedIds: Set<String>,
    abandonedIds: Set<String>,
): AlbumGeneratorState {
    val gen = generators.firstOrNull { it.collectionId == ALBUMS_1001_COLLECTION_ID }
    val memberIds = memberships.map { it.entryId }.distinct()
    val totalCount = memberIds.size
    val consumedCount = memberIds.count { it in consumedIds }
    val abandonedCount = memberIds.count { it in abandonedIds }
    val completedCount = consumedCount + abandonedCount
    val remainingCount = (totalCount - completedCount).coerceAtLeast(0)
    val enabled = gen?.enabled == true
    val activeEntryId =
        gen?.currentEntryId?.takeIf {
            enabled && it.isNotBlank() && it !in consumedIds && it !in abandonedIds
        }
    val activeDomain = activeEntryId?.let { domainEntries[it] }
    val activeRaw = activeEntryId?.let { rawEntries[it] }
    val snapshot =
        if (activeDomain != null && activeRaw != null) {
            toDailyAlbumSnapshot(
                entry = activeDomain,
                raw = activeRaw,
                albumNumber = (completedCount + 1).coerceAtLeast(1),
                totalCount = totalCount,
                remainingCount = remainingCount,
            )
        } else {
            null
        }
    return AlbumGeneratorState(
        enabled = enabled,
        currentAlbum = snapshot,
        consumedCount = consumedCount,
        abandonedCount = abandonedCount,
        totalCount = totalCount,
    )
}

internal fun toDailyAlbumSnapshot(
    entry: CatalogEntryItem,
    raw: CatalogEntryEntity,
    albumNumber: Int,
    totalCount: Int,
    remainingCount: Int,
): DailyAlbumSnapshot {
    val identsByScheme = entry.identifiers.associate { it.scheme.lowercase() to it.value }
    val genres = extractMetadataGenres(raw.metadataJson)
    return DailyAlbumSnapshot(
        entryId = entry.id,
        name = entry.title,
        artist = entry.primaryCreator.ifBlank { "Unknown Artist" },
        releaseDate = entry.releaseYear?.takeIf { it > 0 }?.toString().orEmpty(),
        imageUrl = sanitizeHttpUrl(entry.thumbnailUrl),
        thumbnailUrl = sanitizeHttpUrl(entry.thumbnailUrl),
        genres = genres,
        albumNumber = albumNumber,
        totalCount = totalCount,
        remainingCount = remainingCount,
        youtubeMusicId = identsByScheme["youtube_music"],
        spotifyId = identsByScheme["spotify"],
        appleMusicId = identsByScheme["apple_music"],
        tidalId = identsByScheme["tidal"],
        qobuzId = identsByScheme["qobuz"],
        deezerId = identsByScheme["deezer"],
        amazonMusicId = identsByScheme["amazon_music"],
        wikipediaUrl = sanitizeHttpUrl(identsByScheme["wikipedia"]),
    )
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
    raw: CatalogEntryEntity,
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

internal fun stripAlbumEditionSuffix(rawTitle: String): String {
    var current = rawTitle.trim()
    while (true) {
        val next = stripBracketEditionOnce(current) ?: stripDashEditionOnce(current) ?: break
        current = next
    }
    return current.ifEmpty { rawTitle.trim() }
}

private fun stripBracketEditionOnce(current: String): String? {
    for ((openCh, closeCh) in EDITION_BRACKET_PAIRS) {
        if (!current.endsWith(closeCh)) continue
        val idx = current.lastIndexOf(openCh)
        if (idx > 0 && hasEditionKeyword(current.substring(idx + 1, current.length - 1))) {
            return current.substring(0, idx).trim()
        }
    }
    return null
}

private fun stripDashEditionOnce(current: String): String? {
    val dashIdx = current.lastIndexOf(" - ")
    if (dashIdx <= 0) return null
    val suffix = current.substring(dashIdx + 3)
    return if (hasEditionKeyword(suffix)) current.substring(0, dashIdx).trim() else null
}

private fun hasEditionKeyword(fragment: String): Boolean {
    val lower = fragment.lowercase()
    return EDITION_KEYWORDS.any { lower.contains(it) }
}

internal fun loadBundled1001Albums(
    poolStreamProvider: (() -> InputStream?)? = null
): List<BundledAlbumPoolItem> {
    val rawText =
        poolStreamProvider?.invoke()?.use { it.bufferedReader(Charsets.UTF_8).readText() }
            ?: readBundledPoolFallback()
            ?: return emptyList()
    return runCatching {
            rocheJson.decodeFromString(
                ListSerializer(BundledAlbumPoolItem.serializer()),
                rawText,
            )
        }
        .getOrDefault(emptyList())
}

private fun readBundledPoolFallback(): String? {
    val candidates =
        listOf(
            File("src/main/assets/albums_1001_pool.json"),
            File("app/src/main/assets/albums_1001_pool.json"),
            File("android/app/src/main/assets/albums_1001_pool.json"),
            File("../server/src/data/albums_1001_pool.json"),
            File("../../server/src/data/albums_1001_pool.json"),
        )
    return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
}

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
private val EDITION_BRACKET_PAIRS = listOf('(' to ')', '[' to ']')
private val EDITION_KEYWORDS =
    listOf(
        "deluxe",
        "standard",
        "remaster",
        "remastered",
        "expanded",
        "special",
        "limited",
        "anniversary",
        "bonus",
        "edition",
        "version",
        "reissue",
        "explicit",
        "clean",
        "remasters",
    )
