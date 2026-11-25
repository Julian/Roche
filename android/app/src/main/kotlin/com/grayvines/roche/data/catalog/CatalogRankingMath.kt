package com.grayvines.roche.data.catalog

import com.grayvines.roche.ranking.EpRankingEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

internal data class VirtualQuantileResult(
    val bucketByComparedId: Map<String, Int> = emptyMap(),
    val defaultBucket: Int? = null,
    val tierCounts: Map<Char, Int> = emptyMap(),
)

internal data class ComparedBands(
    val bandAbove: List<CatalogEntryItem> = emptyList(),
    val bandZeroCompared: List<CatalogEntryItem> = emptyList(),
    val bandBelow: List<CatalogEntryItem> = emptyList(),
)

private data class WeightedScoreNode(
    val id: String?,
    val mean: Double,
    val weight: Int,
)

/**
 * Exact O(K log K) equivalent of [EpRankingEngine.rescaleToQuantiles] when a population has
 * `comparedScores.size` compared items plus [uncomparedCount] uncompared items (all with prior
 * `mean = 0.0`).
 */
internal fun computeQuantilesWithUncompared(
    comparedScores: Map<String, EpRankingEngine.Score>,
    uncomparedCount: Int,
    nQuantiles: Int = EpRankingEngine.N_QUANTILES,
): VirtualQuantileResult {
    val safeUncompared = uncomparedCount.coerceAtLeast(0)
    val totalN = comparedScores.size + safeUncompared
    if (totalN == 0 || nQuantiles <= 0) return VirtualQuantileResult()

    val nodes = buildSortedWeightedNodes(comparedScores, safeUncompared)
    val buckets = LinkedHashMap<String, Int>(comparedScores.size)
    val tierCounts = LinkedHashMap<Char, Int>()
    var defaultBucket: Int? = null
    var startIdx = 0
    var i = 0
    while (i < nodes.size) {
        val baseMean = nodes[i].mean
        var j = i + 1
        while (
            j < nodes.size && abs(nodes[j].mean - baseMean) <= EpRankingEngine.MEAN_TIE_EPSILON
        ) {
            j += 1
        }
        var groupWeight = 0
        for (k in i until j) groupWeight += nodes[k].weight
        val bucket = resolveQuantileBucket(startIdx, groupWeight, totalN, nQuantiles)
        val tier = EpRankingEngine.tierLabel(bucket)
        tierCounts[tier] = (tierCounts[tier] ?: 0) + groupWeight
        for (k in i until j) {
            val nodeId = nodes[k].id
            if (nodeId != null) {
                buckets[nodeId] = bucket
            } else {
                defaultBucket = bucket
            }
        }
        startIdx += groupWeight
        i = j
    }
    return VirtualQuantileResult(
        bucketByComparedId = buckets,
        defaultBucket = defaultBucket,
        tierCounts = tierCounts,
    )
}

private fun buildSortedWeightedNodes(
    comparedScores: Map<String, EpRankingEngine.Score>,
    uncomparedCount: Int,
): List<WeightedScoreNode> {
    val nodes =
        ArrayList<WeightedScoreNode>(comparedScores.size + if (uncomparedCount > 0) 1 else 0)
    for ((id, score) in comparedScores) {
        nodes.add(WeightedScoreNode(id = id, mean = score.mean, weight = 1))
    }
    if (uncomparedCount > 0) {
        nodes.add(WeightedScoreNode(id = null, mean = 0.0, weight = uncomparedCount))
    }
    nodes.sortWith { a, b ->
        val cmp = a.mean.compareTo(b.mean)
        if (cmp != 0) cmp else (a.id ?: "").compareTo(b.id ?: "")
    }
    return nodes
}

private fun resolveQuantileBucket(
    startIdx: Int,
    groupWeight: Int,
    totalN: Int,
    nQuantiles: Int,
): Int {
    if (totalN == 1) return nQuantiles - 1
    val endIdx = startIdx + groupWeight - 1
    val midpoint = (startIdx + endIdx).toDouble() / 2.0
    val rawBucket = floor(midpoint / (totalN - 1).toDouble() * nQuantiles.toDouble()).toInt()
    return min(rawBucket, nQuantiles - 1).coerceAtLeast(0)
}

internal fun computeGlobalTierIndex(
    consumedCountByKind: Map<EntryKind, Int>,
    comparedConsumedByKind: Map<EntryKind, List<String>>,
    globalScores: Map<String, EpRankingEngine.Score>,
    globalOrderings: List<List<List<String>>>,
): GlobalTierIndex {
    if (globalOrderings.isEmpty() || comparedConsumedByKind.isEmpty()) {
        return GlobalTierIndex()
    }
    val orderedIds = HashSet<String>(globalScores.size)
    for (ord in globalOrderings) {
        for (group in ord) {
            orderedIds.addAll(group)
        }
    }
    val tierByCompared = HashMap<String, Char>()
    val defaultTierByKind = HashMap<EntryKind, Char>()
    for ((kind, totalCount) in consumedCountByKind) {
        val comparedIds = comparedConsumedByKind[kind].orEmpty().distinct()
        if (comparedIds.none { it in orderedIds }) continue
        val comparedScores = comparedIds.associateWith { id ->
            globalScores[id] ?: EpRankingEngine.Score()
        }
        val uncomparedCount = (totalCount - comparedScores.size).coerceAtLeast(0)
        val result = computeQuantilesWithUncompared(comparedScores, uncomparedCount)
        for ((id, bucket) in result.bucketByComparedId) {
            tierByCompared[id] = EpRankingEngine.tierLabel(bucket)
        }
        result.defaultBucket?.let { bucket ->
            defaultTierByKind[kind] = EpRankingEngine.tierLabel(bucket)
        }
    }
    return GlobalTierIndex(
        tierByComparedEntryId = tierByCompared,
        defaultTierByKind = defaultTierByKind,
    )
}

/**
 * Splits a collection's compared entries into three ordered bands around the uncompared `mean =
 * 0.0` tie-group (`bandAbove`, `bandZeroCompared`, `bandBelow`) matching
 * [sortEntriesByScoreAndTitle].
 */
internal fun splitComparedBands(
    comparedEntries: List<CatalogEntryItem>,
    scores: Map<String, EpRankingEngine.Score>,
    hasUncomparedEntries: Boolean,
): ComparedBands {
    if (comparedEntries.isEmpty()) return ComparedBands()
    if (!hasUncomparedEntries) {
        return ComparedBands(bandAbove = sortEntriesByScoreAndTitle(comparedEntries, scores))
    }
    val sentinel = buildZeroSentinelEntry()
    // Walk ascending using the exact same comparator and tie-grouping direction as
    // computeQuantilesWithUncompared so every entry in bandZeroCompared is guaranteed to share
    // defaultBucket with uncompared items.
    val sortedAsc =
        (comparedEntries + sentinel).sortedWith { a, b ->
            val cmp = entryBandMean(a, scores).compareTo(entryBandMean(b, scores))
            if (cmp != 0) cmp else bandSortKey(a).compareTo(bandSortKey(b))
        }
    val above = ArrayList<CatalogEntryItem>()
    val zero = ArrayList<CatalogEntryItem>()
    val below = ArrayList<CatalogEntryItem>()
    partitionSortedAscIntoBands(sortedAsc, scores, above, zero, below)
    return ComparedBands(
        bandAbove = sortEntriesByScoreAndTitle(above, scores),
        bandZeroCompared =
            zero.sortedWith(
                compareBy<CatalogEntryItem> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
            ),
        bandBelow = sortEntriesByScoreAndTitle(below, scores),
    )
}

private fun bandSortKey(entry: CatalogEntryItem): String =
    if (entry.id == ZERO_SENTINEL_ID) "" else entry.id

private fun entryBandMean(
    entry: CatalogEntryItem,
    scores: Map<String, EpRankingEngine.Score>,
): Double =
    if (entry.id == ZERO_SENTINEL_ID) 0.0 else (scores[entry.id] ?: EpRankingEngine.Score()).mean

private fun partitionSortedAscIntoBands(
    sortedAsc: List<CatalogEntryItem>,
    scores: Map<String, EpRankingEngine.Score>,
    above: MutableList<CatalogEntryItem>,
    zero: MutableList<CatalogEntryItem>,
    below: MutableList<CatalogEntryItem>,
) {
    var seenZeroGroup = false
    var tieStart = 0
    while (tieStart < sortedAsc.size) {
        val baseMean = entryBandMean(sortedAsc[tieStart], scores)
        var tieEnd = tieStart + 1
        while (
            tieEnd < sortedAsc.size &&
                abs(entryBandMean(sortedAsc[tieEnd], scores) - baseMean) <=
                    EpRankingEngine.MEAN_TIE_EPSILON
        ) {
            tieEnd += 1
        }
        val slice = sortedAsc.subList(tieStart, tieEnd)
        val isZeroGroup = slice.any { it.id == ZERO_SENTINEL_ID }
        val realItems = slice.filter { it.id != ZERO_SENTINEL_ID }
        when {
            isZeroGroup -> {
                seenZeroGroup = true
                zero.addAll(realItems)
            }
            !seenZeroGroup -> {
                below.addAll(realItems)
            }
            else -> {
                above.addAll(realItems)
            }
        }
        tieStart = tieEnd
    }
}

private fun buildZeroSentinelEntry(): CatalogEntryItem =
    CatalogEntryItem(
        id = ZERO_SENTINEL_ID,
        kind = EntryKind.ARTICLE,
        title = "",
        creators = emptyList(),
        url = null,
        publishedAt = null,
        releaseYear = null,
        summary = null,
        thumbnailUrl = null,
        identifiers = emptyList(),
    )

private const val ZERO_SENTINEL_ID = "\u0000__zero_prior_sentinel__"
