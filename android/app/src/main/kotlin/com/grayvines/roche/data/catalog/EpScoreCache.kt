package com.grayvines.roche.data.catalog

import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.ranking.EpRankingEngine

internal class EpScoreCache {
    private val lock = Any()
    private var cachedGlobal: CachedEpEntry? = null
    private val cachedLocalByScope = HashMap<String, CachedEpEntry>()
    private var globalRecomputes: Int = 0
    private var globalHits: Int = 0
    private var localRecomputes: Int = 0
    private var localHits: Int = 0
    private val localRecomputesByScope = HashMap<String, Int>()

    val stats: EpScoreCacheStats
        get() =
            synchronized(lock) {
                EpScoreCacheStats(
                    globalRecomputes = globalRecomputes,
                    globalHits = globalHits,
                    localRecomputes = localRecomputes,
                    localHits = localHits,
                    cachedScopeCount = cachedLocalByScope.size,
                    localRecomputesByScope = HashMap(localRecomputesByScope),
                )
            }

    fun resolveGlobal(
        comparisons: List<ComparisonEntity>,
        redirects: Map<String, String>,
    ): CachedEpScores =
        synchronized(lock) {
            if (comparisons.isEmpty()) {
                cachedGlobal = null
                EMPTY_CACHED_EP_SCORES
            } else {
                val key = buildEpScoreKey(comparisons, redirects)
                val existing = cachedGlobal
                if (existing != null && existing.key == key) {
                    globalHits += 1
                    existing.result
                } else {
                    val computed = computeEpScoresCold(comparisons, redirects)
                    cachedGlobal = CachedEpEntry(key, computed)
                    globalRecomputes += 1
                    computed
                }
            }
        }

    fun resolveLocal(
        scopeCollectionId: String,
        comparisons: List<ComparisonEntity>,
        redirects: Map<String, String>,
    ): CachedEpScores =
        synchronized(lock) {
            if (comparisons.isEmpty()) {
                cachedLocalByScope.remove(scopeCollectionId)
                EMPTY_CACHED_EP_SCORES
            } else {
                val key = buildEpScoreKey(comparisons, redirects)
                val existing = cachedLocalByScope[scopeCollectionId]
                if (existing != null && existing.key == key) {
                    localHits += 1
                    existing.result
                } else {
                    val computed = computeEpScoresCold(comparisons, redirects)
                    cachedLocalByScope[scopeCollectionId] = CachedEpEntry(key, computed)
                    localRecomputes += 1
                    localRecomputesByScope[scopeCollectionId] =
                        (localRecomputesByScope[scopeCollectionId] ?: 0) + 1
                    computed
                }
            }
        }

    fun evictUnusedScopes(activeScopeIds: Set<String>) {
        synchronized(lock) { cachedLocalByScope.keys.retainAll(activeScopeIds) }
    }

    fun clear() {
        synchronized(lock) {
            cachedGlobal = null
            cachedLocalByScope.clear()
        }
    }
}

internal data class EpScoreCacheStats(
    val globalRecomputes: Int,
    val globalHits: Int,
    val localRecomputes: Int,
    val localHits: Int,
    val cachedScopeCount: Int,
    val localRecomputesByScope: Map<String, Int>,
)

internal data class CachedEpScores(
    val orderings: List<List<List<String>>>,
    val scores: Map<String, EpRankingEngine.Score>,
)

internal data class ComparisonFingerprint(
    val id: String,
    val orderingJson: String,
    val hlc: String,
)

internal data class EpScoreKey(
    val comparisons: List<ComparisonFingerprint>,
    val merges: List<Pair<String, String>>,
)

internal data class CachedEpEntry(
    val key: EpScoreKey,
    val result: CachedEpScores,
)

internal val EMPTY_CACHED_EP_SCORES = CachedEpScores(emptyList(), emptyMap())

private const val REDIRECT_VISITED_CAPACITY = 4

internal fun buildCatalogMergeRedirects(entries: List<EntryEntity>): Map<String, String> {
    var map: HashMap<String, String>? = null
    for (entry in entries) {
        val target = entry.mergedIntoId?.trim()?.takeIf { it.isNotEmpty() && it != entry.id }
        if (target != null) {
            val resolvedMap = map ?: HashMap<String, String>().also { map = it }
            resolvedMap[entry.id] = target
        }
    }
    return map ?: emptyMap()
}

internal fun followCatalogMergeRedirect(id: String, redirects: Map<String, String>): String {
    var current = id.trim()
    if (current.isEmpty() || redirects.isEmpty()) return current
    val visited = HashSet<String>(REDIRECT_VISITED_CAPACITY)
    while (visited.add(current)) {
        val next = redirects[current] ?: break
        current = next
    }
    return current
}

internal fun computeEpScoresCold(
    comparisons: List<ComparisonEntity>,
    redirects: Map<String, String>,
): CachedEpScores {
    if (comparisons.isEmpty()) return EMPTY_CACHED_EP_SCORES
    val orderings = decodeCanonicalOrderings(comparisons, redirects)
    if (orderings.isEmpty()) return EMPTY_CACHED_EP_SCORES
    val comparedIds = orderings.asSequence().flatten().flatten().distinct().toList()
    return CachedEpScores(
        orderings = orderings,
        scores = EpRankingEngine.inferScores(comparedIds, orderings),
    )
}

internal fun decodeCanonicalOrderings(
    comparisons: List<ComparisonEntity>,
    redirects: Map<String, String>,
): List<List<List<String>>> = comparisons.mapNotNull { comp ->
    decodeComparisonOrdering(comp.orderingJson)?.let { decoded ->
        if (redirects.isEmpty()) decoded else remapOrderingWithRedirects(decoded, redirects)
    }
}

internal fun remapOrderingWithRedirects(
    ordering: List<List<String>>,
    redirects: Map<String, String>,
): List<List<String>>? {
    val seen = HashSet<String>()
    val remapped = ordering.mapNotNull { group ->
        group
            .map { followCatalogMergeRedirect(it, redirects) }
            .filter { it.isNotEmpty() && seen.add(it) }
            .takeIf { it.isNotEmpty() }
    }
    return remapped.takeIf { EpRankingEngine.validateOrdering(it).isSuccess }
}

internal fun buildEpScoreKey(
    comparisons: List<ComparisonEntity>,
    redirects: Map<String, String>,
): EpScoreKey {
    val compKeys = comparisons.map { comp ->
        ComparisonFingerprint(
            id = comp.id,
            orderingJson = comp.orderingJson,
            hlc = comp.hlc,
        )
    }
    val mergeKeys =
        if (redirects.isEmpty()) {
            emptyList()
        } else {
            redirects.entries
                .map { it.key to it.value }
                .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        }
    return EpScoreKey(comparisons = compKeys, merges = mergeKeys)
}
