@file:Suppress("MagicNumber", "TooManyFunctions")

package com.grayvines.roche.ranking

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.serialization.Serializable

object EpRankingEngine {
    const val PRIOR_ALPHA: Double = 1.0
    const val COLD_START_VARIANCE: Double = 1.0 / PRIOR_ALPHA
    const val MIN_K: Int = 2
    const val MAX_K_DEFAULT: Int = 5
    const val MIN_CONFUSABILITY: Double = 0.15
    const val RANDOM_SEED_EVERY: Int = 5
    const val N_QUANTILES: Int = 5
    val TIER_LETTERS: CharArray = charArrayOf('D', 'C', 'B', 'A', 'S')

    private const val MEAN_TIE_EPSILON: Double = 1e-5
    private const val ZERO_MEAN_SNAP: Double = 1e-12
    private const val DEFAULT_MAX_ITER: Int = 100

    @Serializable
    data class Score(
        val mean: Double = 0.0,
        val variance: Double = COLD_START_VARIANCE,
    )

    fun tierLabel(quantile: Int): Char =
        if (quantile in 0 until N_QUANTILES) TIER_LETTERS[quantile] else 'S'

    fun validateOrdering(ordering: List<List<String>>): Result<Unit> {
        val errorMsg = findOrderingValidationError(ordering)
        return if (errorMsg != null) {
            Result.failure(IllegalArgumentException(errorMsg))
        } else {
            Result.success(Unit)
        }
    }

    private fun findOrderingValidationError(ordering: List<List<String>>): String? {
        if (ordering.any { it.isEmpty() }) return "tie groups must be non-empty"
        val seen = HashSet<String>()
        var duplicateOrBlank: String? = null
        var total = 0
        for (rawId in ordering.flatten()) {
            val trimmed = rawId.trim()
            val entryError =
                when {
                    trimmed.isEmpty() -> "entry IDs in ordering cannot be empty"
                    !seen.add(trimmed) -> "ordering must not repeat entry ID '$trimmed'"
                    else -> null
                }
            if (entryError != null) {
                duplicateOrBlank = entryError
                break
            }
            total += 1
        }
        return duplicateOrBlank
            ?: if (total < MIN_K) "a comparison needs at least $MIN_K entries" else null
    }

    fun toPairs(ordering: List<List<String>>): List<Pair<String, String>> {
        val pairs = ArrayList<Pair<String, String>>()
        appendStrictPairs(ordering, pairs)
        appendTiePairs(ordering, pairs)
        return pairs
    }

    private fun appendStrictPairs(
        ordering: List<List<String>>,
        pairs: MutableList<Pair<String, String>>,
    ) {
        for (gi in ordering.indices) {
            val winners = ordering[gi]
            for (lj in gi + 1 until ordering.size) {
                appendCrossGroupPairs(winners, ordering[lj], pairs)
            }
        }
    }

    private fun appendCrossGroupPairs(
        winners: List<String>,
        losers: List<String>,
        pairs: MutableList<Pair<String, String>>,
    ) {
        for (w in winners) {
            for (l in losers) {
                if (w != l) pairs.add(w to l)
            }
        }
    }

    private fun appendTiePairs(
        ordering: List<List<String>>,
        pairs: MutableList<Pair<String, String>>,
    ) {
        for (group in ordering) {
            appendIntraGroupTiePairs(group, pairs)
        }
    }

    private fun appendIntraGroupTiePairs(
        group: List<String>,
        pairs: MutableList<Pair<String, String>>,
    ) {
        for (i in group.indices) {
            val a = group[i]
            for (j in i + 1 until group.size) {
                val b = group[j]
                if (a != b) {
                    pairs.add(a to b)
                    pairs.add(b to a)
                }
            }
        }
    }

    fun inferScores(
        itemIds: List<String>,
        orderings: List<List<List<String>>>,
    ): Map<String, Score> {
        val scores = LinkedHashMap<String, Score>(itemIds.size)
        for (id in itemIds) {
            scores[id] = Score()
        }
        val rawPairs =
            if (itemIds.isEmpty() || orderings.isEmpty()) {
                emptyList()
            } else {
                orderings.flatMap(::toPairs)
            }
        if (rawPairs.isNotEmpty()) {
            solveAllConnectedComponents(itemIds.toHashSet(), rawPairs, scores)
        }
        return scores
    }

    private fun solveAllConnectedComponents(
        targetSet: Set<String>,
        rawPairs: List<Pair<String, String>>,
        scores: MutableMap<String, Score>,
    ) {
        val adj = HashMap<String, MutableList<String>>()
        for ((w, l) in rawPairs) {
            adj.getOrPut(w) { ArrayList() }.add(l)
            adj.getOrPut(l) { ArrayList() }.add(w)
        }
        val visited = HashSet<String>()
        for (seed in targetSet.sorted()) {
            if (seed !in visited && seed in adj) {
                val comp = collectConnectedComponent(seed, adj, visited)
                solveSingleComponent(comp, rawPairs, targetSet, scores)
            }
        }
    }

    private fun solveSingleComponent(
        comp: List<String>,
        rawPairs: List<Pair<String, String>>,
        targetSet: Set<String>,
        scores: MutableMap<String, Score>,
    ) {
        val idxMap = HashMap<String, Int>(comp.size)
        comp.forEachIndexed { idx, id -> idxMap[id] = idx }
        val compPairs = rawPairs.mapNotNull { (w, l) ->
            val wi = idxMap[w]
            val li = idxMap[l]
            if (wi != null && li != null && wi != li) wi to li else null
        }
        if (compPairs.isEmpty()) return
        val solved =
            EpGaussianMath.epPairwiseComponent(comp.size, compPairs, PRIOR_ALPHA, DEFAULT_MAX_ITER)
                ?: return
        val (means, variances) = solved
        for (i in comp.indices) {
            val id = comp[i]
            if (id in targetSet) {
                val cleanMean = if (abs(means[i]) < ZERO_MEAN_SNAP) 0.0 else means[i]
                scores[id] = Score(mean = cleanMean, variance = variances[i])
            }
        }
    }

    private fun collectConnectedComponent(
        seed: String,
        adj: Map<String, List<String>>,
        visited: MutableSet<String>,
    ): List<String> {
        val comp = ArrayList<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(seed)
        visited.add(seed)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            comp.add(cur)
            for (nb in adj[cur].orEmpty()) {
                if (visited.add(nb)) {
                    stack.addLast(nb)
                }
            }
        }
        comp.sort()
        return comp
    }

    fun confusability(a: Score, b: Score): Double {
        val diffVar = a.variance + b.variance
        if (diffVar <= 0.0) {
            return if (abs(a.mean - b.mean) <= Double.MIN_VALUE) 0.5 else 0.0
        }
        val z = abs(a.mean - b.mean) / sqrt(diffVar)
        return 0.5 * EpGaussianMath.codyErfc(z / EpGaussianMath.SQRT2)
    }

    fun codyErfc(x: Double): Double = EpGaussianMath.codyErfc(x)

    @Suppress("LongParameterList")
    fun selectCluster(
        scores: Map<String, Score>,
        itemIds: List<String>,
        maxK: Int = MAX_K_DEFAULT,
        forceRandomSeed: Boolean = false,
        seedPool: Collection<String>? = null,
        random: Random = Random.Default,
    ): List<String> {
        val cap = max(maxK, MIN_K)
        val candidates = resolveSeedCandidates(itemIds, seedPool)
        if (candidates.isEmpty()) return emptyList()

        val seedId = pickSeedCandidate(candidates, scores, cap, forceRandomSeed, random)
        val seedScore = scores[seedId] ?: Score()
        val neighbors =
            itemIds
                .filter { it != seedId }
                .map { id ->
                    val candScore = scores[id] ?: Score()
                    Triple(confusability(seedScore, candScore), random.nextDouble(), id)
                }
                .sortedWith { a, b ->
                    val c0 = b.first.compareTo(a.first)
                    if (c0 != 0) c0 else b.second.compareTo(a.second)
                }

        val cluster = ArrayList<String>(cap)
        cluster.add(seedId)
        for ((conf, _, candId) in neighbors) {
            if (cluster.size >= cap || conf < MIN_CONFUSABILITY) break
            cluster.add(candId)
        }
        cluster.sortWith { a, b ->
            val ma = (scores[a] ?: Score()).mean
            val mb = (scores[b] ?: Score()).mean
            val cmp = mb.compareTo(ma)
            if (cmp != 0) cmp else a.compareTo(b)
        }
        return cluster
    }

    private fun resolveSeedCandidates(
        itemIds: List<String>,
        seedPool: Collection<String>?,
    ): List<String> {
        if (itemIds.isEmpty()) return emptyList()
        val pool = seedPool ?: return itemIds
        val poolSet = pool.toHashSet()
        return itemIds.filter { it in poolSet }
    }

    private fun pickSeedCandidate(
        candidates: List<String>,
        scores: Map<String, Score>,
        cap: Int,
        forceRandomSeed: Boolean,
        random: Random,
    ): String {
        if (forceRandomSeed) {
            val ranked =
                candidates
                    .map { id ->
                        val variance = (scores[id] ?: Score()).variance
                        Triple(variance, random.nextDouble(), id)
                    }
                    .sortedWith { a, b ->
                        val c0 = b.first.compareTo(a.first)
                        if (c0 != 0) c0 else b.second.compareTo(a.second)
                    }
                    .take(cap)
            return ranked[random.nextInt(ranked.size)].third
        }
        var bestVar = Double.NEGATIVE_INFINITY
        var bestTie = Double.NEGATIVE_INFINITY
        var bestId = candidates.first()
        for (id in candidates) {
            val variance = (scores[id] ?: Score()).variance
            val tie = random.nextDouble()
            val better = variance > bestVar || variance == bestVar && tie > bestTie
            if (better) {
                bestVar = variance
                bestTie = tie
                bestId = id
            }
        }
        return bestId
    }

    fun rescaleToQuantiles(
        scores: Map<String, Score>,
        nQuantiles: Int = N_QUANTILES,
    ): Map<String, Int> {
        if (scores.isEmpty() || nQuantiles <= 0) return emptyMap()
        val sortedIds =
            scores.keys.sortedWith { a, b ->
                val ma = scores.getValue(a).mean
                val mb = scores.getValue(b).mean
                val cmp = ma.compareTo(mb)
                if (cmp != 0) cmp else a.compareTo(b)
            }
        val n = sortedIds.size
        if (n == 1) {
            return mapOf(sortedIds[0] to nQuantiles - 1)
        }
        val buckets = LinkedHashMap<String, Int>(n)
        var i = 0
        while (i < n) {
            val baseMean = scores.getValue(sortedIds[i]).mean
            var j = i + 1
            while (
                j < n && abs(scores.getValue(sortedIds[j]).mean - baseMean) <= MEAN_TIE_EPSILON
            ) {
                j += 1
            }
            val midpoint = (i + j - 1).toDouble() / 2.0
            val rawBucket = floor(midpoint / (n - 1).toDouble() * nQuantiles.toDouble()).toInt()
            val bucket = min(rawBucket, nQuantiles - 1).coerceAtLeast(0)
            for (k in i until j) {
                buckets[sortedIds[k]] = bucket
            }
            i = j
        }
        return buckets
    }

    fun rankIdsByScore(
        itemIds: List<String>,
        scores: Map<String, Score>,
    ): List<String> = itemIds.sortedWith { a, b ->
        val ma = (scores[a] ?: Score()).mean
        val mb = (scores[b] ?: Score()).mean
        val cmp = mb.compareTo(ma)
        if (cmp != 0) cmp else a.compareTo(b)
    }

    fun displayScoreOutOfTen(mean: Double, minMean: Double, maxMean: Double): Double {
        val span = maxMean - minMean
        return if (span <= MEAN_TIE_EPSILON) {
            5.0
        } else {
            ((mean - minMean) / span * 10.0).coerceIn(0.0, 10.0)
        }
    }
}
