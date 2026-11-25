@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package com.grayvines.roche.data.catalog

import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.buildYoutubeMusicAlbumUrl
import com.grayvines.roche.core.coreJson
import com.grayvines.roche.core.decodeCreatorsJson
import com.grayvines.roche.core.externalIdentifierUrl
import com.grayvines.roche.core.parseIsoInstant
import com.grayvines.roche.core.sanitizeHttpUrl
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.DiaryLogRow
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.ranking.EpRankingEngine
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun resolveSpecTitle(
    spec: CollectionSpecEntity,
    titlesByEntryId: Map<String, String>,
): String =
    titlesByEntryId[spec.collectionId]?.takeIf { it.isNotBlank() } ?: spec.slug ?: spec.collectionId

internal fun sortListSummaries(summaries: List<CatalogListSummary>): List<CatalogListSummary> =
    summaries.sortedWith(
        compareByDescending<CatalogListSummary> { it.entryCount > 0 || it.generatorEnabled }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    )

internal fun sortWantSummaries(summaries: List<CatalogListSummary>): List<CatalogListSummary> =
    summaries.sortedWith(
        compareByDescending<CatalogListSummary> {
                it.collectionId == WANT_TO_READ_COLLECTION_ID && it.entryCount > 0
            }
            .thenByDescending { it.entryCount }
            .thenByDescending { it.collectionId == WANT_TO_READ_COLLECTION_ID }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    )

@Suppress("LongParameterList")
internal fun collectSummaryBucket(
    spec: CollectionSpecEntity,
    role: CollectionRole,
    isWant: Boolean,
    hasEntries: Boolean,
    summary: CatalogListSummary,
    wantSummaries: MutableList<CatalogListSummary>,
    listSummaries: MutableList<CatalogListSummary>,
) {
    if (isWant) {
        val isPinnedWant = spec.collectionId == WANT_TO_READ_COLLECTION_ID
        if (hasEntries || isPinnedWant) {
            wantSummaries.add(summary)
        }
    } else if (role == CollectionRole.LIST) {
        listSummaries.add(summary)
    }
}

internal fun parseDynamicCollectionRule(rawJson: String?): DynamicCollectionRuleDto? =
    rawJson
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
            runCatching { coreJson.decodeFromString(DynamicCollectionRuleDto.serializer(), raw) }
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
    val allowedKinds = extractRuleTargetKinds(rule) ?: return false
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

internal fun extractRuleTargetKinds(rule: DynamicCollectionRuleDto): Set<EntryKind>? {
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

internal fun rankEntriesByOrderings(
    entries: List<CatalogEntryItem>,
    isPriorityScoped: Boolean,
    orderings: List<List<List<String>>>,
    precomputedScores: Map<String, EpRankingEngine.Score>? = null,
): List<RankedCatalogEntry> {
    if (entries.isEmpty()) return emptyList()
    val memberIds = entries.map { it.id }
    val memberSet = memberIds.toHashSet()
    val relevantComparisonCount = orderings.count { ord ->
        ord.any { group -> group.any { it in memberSet } }
    }
    val localScores =
        if (precomputedScores != null) {
            memberIds.associateWith { precomputedScores[it] ?: EpRankingEngine.Score() }
        } else {
            EpRankingEngine.inferScores(memberIds, orderings)
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
        RankedCatalogEntry(
            entry = domain,
            rank = idx + 1,
            mean = score.mean,
            variance = score.variance,
            scoreOutOfTen = EpRankingEngine.displayScoreOutOfTen(score.mean, minMean, maxMean),
            tier = quantiles[domain.id]?.let(EpRankingEngine::tierLabel),
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

internal fun toDiaryEntryItem(
    row: DiaryLogRow,
    globalTiers: GlobalTierIndex = GlobalTierIndex(),
): DiaryEntryItem {
    val (monthKey, dateLabel) = formatDiaryTimestamp(row.ts)
    val status = ConsumptionStatus.fromWireValue(row.status)
    val kind = EntryKind.fromWireValue(row.kind)
    return DiaryEntryItem(
        id = row.id,
        entryId = row.entryId,
        kind = kind,
        title = row.title,
        creators = decodeCreatorsJson(row.creatorsJson),
        releaseYear = row.releaseYear,
        ts = row.ts,
        monthKey = monthKey,
        dateLabel = dateLabel,
        status = status,
        notes = row.notes?.takeIf { it.isNotBlank() },
        tier =
            if (status == ConsumptionStatus.CONSUMED) {
                globalTiers.tierForConsumed(row.entryId, kind)
            } else {
                null
            },
        thumbnailUrl = row.thumbnailUrl?.takeIf { it.isNotBlank() },
        url = row.url?.takeIf { it.isNotBlank() },
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
            identsByScheme[SCHEME_WIKIPEDIA]
                ?.let { externalIdentifierUrl(SCHEME_WIKIPEDIA, it) }
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
        runCatching { coreJson.parseToJsonElement(metadataJson) as? JsonObject }.getOrNull()
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
        metadataJson = raw.metadataJson,
    )

internal fun CatalogEntryItem.toMusicLaunchSnapshot(): CollectionDrawSnapshot {
    val identsByScheme = identifiers.associate { it.scheme.lowercase() to it.value }
    return CollectionDrawSnapshot(
        entryId = id,
        kind = kind,
        title = title,
        creator = primaryCreator,
        releaseDate = releaseYear?.takeIf { it > 0 }?.toString().orEmpty(),
        url = url,
        imageUrl = sanitizeHttpUrl(thumbnailUrl),
        thumbnailUrl = sanitizeHttpUrl(thumbnailUrl),
        tags = extractMetadataGenres(metadataJson),
        youtubeMusicId = pickPreferredYoutubeMusicId(identifiers),
        spotifyId = identsByScheme["spotify"],
        appleMusicId = identsByScheme["apple_music"],
        tidalId = identsByScheme["tidal"],
        qobuzId = identsByScheme["qobuz"],
        deezerId = identsByScheme["deezer"],
        amazonMusicId = identsByScheme["amazon_music"],
        wikipediaUrl = resolveWikipediaUrl(),
    )
}

internal fun CatalogEntryItem.resolveWikipediaUrl(): String? =
    identifiers
        .firstOrNull { it.scheme.equals(SCHEME_WIKIPEDIA, ignoreCase = true) }
        ?.value
        ?.let { externalIdentifierUrl(SCHEME_WIKIPEDIA, it) }
        ?.let(::sanitizeHttpUrl)

internal fun extractAlbumTracks(metadataJson: String): List<AlbumTrackItem> {
    val obj =
        runCatching { coreJson.parseToJsonElement(metadataJson) as? JsonObject }.getOrNull()
            ?: return emptyList()
    val arr = obj["tracks"] as? JsonArray ?: return emptyList()
    return arr.take(MAX_PARSED_TRACKS).mapIndexedNotNull { idx, elem ->
        parseAlbumTrackItem(elem as? JsonObject, idx + 1)
    }
}

private fun parseAlbumTrackItem(item: JsonObject?, fallbackNumber: Int): AlbumTrackItem? {
    val title =
        (item?.get("title") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
    val disc =
        (item["disc"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 } ?: 1
    val number =
        (item["number"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }
            ?: fallbackNumber
    val durationMs =
        (item["duration_ms"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it > 0L }
    val artists =
        (item["artists"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    val spotifyUri =
        (item["spotify_uri"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    return AlbumTrackItem(disc, number, title, durationMs, artists, spotifyUri)
}

internal fun extractArtistDiscography(metadataJson: String): List<DiscographyAlbumItem> {
    val obj =
        runCatching { coreJson.parseToJsonElement(metadataJson) as? JsonObject }.getOrNull()
            ?: return emptyList()
    val arr = obj["discography"] as? JsonArray ?: return emptyList()
    return arr.take(MAX_PARSED_DISCOGRAPHY).mapNotNull { elem ->
        parseDiscographyAlbumItem(elem as? JsonObject)
    }
}

private fun parseDiscographyAlbumItem(item: JsonObject?): DiscographyAlbumItem? {
    val title =
        (item?.get("title") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
    val year =
        (item["release_year"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf {
            it in MIN_RELEASE_YEAR..MAX_RELEASE_YEAR
        }
    val thumb = (item["thumbnail_url"] as? JsonPrimitive)?.contentOrNull?.let(::sanitizeHttpUrl)
    val appleId =
        (item["apple_music_id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf {
            it.isNotEmpty() && it.all(Char::isDigit)
        }
    return DiscographyAlbumItem(title, year, thumb, appleId)
}

internal fun decodeComparisonOrdering(rawJson: String): List<List<String>>? = runCatching {
    coreJson.decodeFromString(
        ListSerializer(ListSerializer(kotlinx.serialization.serializer<String>())),
        rawJson,
    )
}
    .getOrNull()
    ?.takeIf { EpRankingEngine.validateOrdering(it).isSuccess }

private fun formatDiaryTimestamp(rawIso: String): Pair<String, String> {
    val parsed = parseIsoInstant(rawIso)
    return if (parsed != null) {
        val zdt = parsed.atZone(ZoneOffset.UTC)
        DIARY_MONTH_FMT.format(zdt) to DIARY_DAY_FMT.format(zdt)
    } else {
        val prefix = rawIso.take(MONTH_PREFIX_LEN).ifBlank { "Recent" }
        prefix to rawIso.take(DATE_PREFIX_LEN)
    }
}

private const val SCHEME_WIKIPEDIA = "wikipedia"
private const val MAX_GENRE_CHIPS = 3
private const val MAX_PARSED_TRACKS = 120
private const val MAX_PARSED_DISCOGRAPHY = 80
private const val MIN_RELEASE_YEAR = 1900
private const val MAX_RELEASE_YEAR = 2100
private const val MONTH_PREFIX_LEN = 7
private const val DATE_PREFIX_LEN = 10
private val DIARY_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)
private val DIARY_DAY_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.US)
