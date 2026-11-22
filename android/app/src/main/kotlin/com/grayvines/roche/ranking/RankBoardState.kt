package com.grayvines.roche.ranking

data class RankClusterCandidate(
    val entryId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val mean: Double,
    val variance: Double,
    val selectionPriority: Int = 0,
)

@Suppress("DataClassContainsFunctions", "TooManyFunctions")
internal data class RankBoardState(
    val buckets: List<List<RankClusterCandidate>>,
    val excluded: List<RankClusterCandidate> = emptyList(),
    val manuallyExcludedIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = buckets.isEmpty()

    val size: Int
        get() = buckets.sumOf { it.size }

    val totalCandidateCount: Int
        get() = size + excluded.size

    val isAllTied: Boolean
        get() = buckets.size == 1 && size >= EpRankingEngine.MIN_K

    fun rows(): List<RankBoardRowState> {
        val result = ArrayList<RankBoardRowState>(size)
        var flatIdx = 0
        val totalBuckets = buckets.size
        buckets.forEachIndexed { bIdx, bucket ->
            val displayRank = flatIdx + 1
            val bSize = bucket.size
            bucket.forEachIndexed { iIdx, candidate ->
                result.add(
                    RankBoardRowState(
                        candidate = candidate,
                        flatIndex = flatIdx,
                        displayRank = displayRank,
                        isTiedWithAbove = iIdx > 0,
                        canMoveUp = bSize > 1 || bIdx > 0,
                        canMoveDown = bSize > 1 || bIdx + 1 < totalBuckets,
                    )
                )
                flatIdx++
            }
        }
        return result
    }

    fun displayRanks(): List<Int> = rows().map { it.displayRank }

    fun toOrderingGroups(): List<List<String>> =
        buckets.filter { it.isNotEmpty() }.map { bucket -> bucket.map { it.entryId } }

    fun canExclude(entryId: String, focusEntryId: String? = null): Boolean =
        size > EpRankingEngine.MIN_K &&
            entryId != focusEntryId &&
            findBucketCoords(buckets, entryId) != null

    fun exclude(entryId: String, focusEntryId: String? = null): RankBoardState {
        if (!canExclude(entryId, focusEntryId)) return this
        val (bIdx, iIdx) = findBucketCoords(buckets, entryId) ?: return this
        val next = buckets.map { it.toMutableList() }.toMutableList()
        val removed = next[bIdx].removeAt(iIdx)
        if (next[bIdx].isEmpty()) {
            next.removeAt(bIdx)
        }
        val nextExcluded =
            if (excluded.any { it.entryId == removed.entryId }) excluded else excluded + removed
        return copy(
            buckets = next,
            excluded = nextExcluded,
            manuallyExcludedIds = manuallyExcludedIds + removed.entryId,
        )
    }

    fun restore(entryId: String): RankBoardState {
        val candidate = excluded.firstOrNull { it.entryId == entryId } ?: return this
        val next = buckets.map { it.toMutableList() }.toMutableList()
        insertCandidateByScore(next, candidate)
        return copy(
            buckets = next,
            excluded = excluded.filterNot { it.entryId == entryId },
            manuallyExcludedIds = manuallyExcludedIds - entryId,
        )
    }

    fun restoreAll(): RankBoardState {
        if (excluded.isEmpty()) return this
        val next = buckets.map { it.toMutableList() }.toMutableList()
        for (candidate in excluded) {
            insertCandidateByScore(next, candidate)
        }
        return copy(
            buckets = next,
            excluded = emptyList(),
            manuallyExcludedIds = emptySet(),
        )
    }

    fun resizeTo(targetK: Int, focusEntryId: String? = null): RankBoardState {
        val clampedTarget = targetK.coerceAtLeast(EpRankingEngine.MIN_K)
        return when {
            clampedTarget < size -> shrinkTo(clampedTarget, focusEntryId)
            clampedTarget > size -> growFromTrimmed(clampedTarget)
            else -> this
        }
    }

    private fun shrinkTo(targetK: Int, focusEntryId: String?): RankBoardState {
        val activeFlat = buckets.flatten()
        val removable =
            activeFlat
                .withIndex()
                .filter { (_, c) -> c.entryId != focusEntryId }
                .sortedWith(
                    compareByDescending<IndexedValue<RankClusterCandidate>> {
                            it.value.selectionPriority
                        }
                        .thenByDescending { it.index }
                )
        val dropCount = (size - targetK).coerceAtMost(removable.size)
        if (dropCount <= 0) return this
        val toDrop = removable.take(dropCount).map { it.value }
        val dropIds = toDrop.mapTo(HashSet()) { it.entryId }
        val nextBuckets =
            buckets.map { b -> b.filterNot { it.entryId in dropIds } }.filter { it.isNotEmpty() }
        val nextExcluded =
            excluded + toDrop.filter { c -> excluded.none { it.entryId == c.entryId } }
        return copy(buckets = nextBuckets, excluded = nextExcluded)
    }

    private fun growFromTrimmed(targetK: Int): RankBoardState {
        val trimmed =
            excluded.filter { it.entryId !in manuallyExcludedIds }.sortedBy { it.selectionPriority }
        val addCount = (targetK - size).coerceAtMost(trimmed.size)
        if (addCount <= 0) return this
        val toRestore = trimmed.take(addCount)
        val restoreIds = toRestore.mapTo(HashSet()) { it.entryId }
        val next = buckets.map { it.toMutableList() }.toMutableList()
        for (candidate in toRestore) {
            insertCandidateByScore(next, candidate)
        }
        return copy(
            buckets = next,
            excluded = excluded.filterNot { it.entryId in restoreIds },
        )
    }

    fun moveUp(entryId: String): RankBoardState {
        val (bIdx, iIdx) = findBucketCoords(buckets, entryId) ?: return this
        val next = buckets.map { it.toMutableList() }.toMutableList()
        if (next[bIdx].size > 1) {
            val item = next[bIdx].removeAt(iIdx)
            next.add(bIdx, mutableListOf(item))
        } else if (bIdx > 0) {
            val prev = next[bIdx - 1]
            next[bIdx - 1] = next[bIdx]
            next[bIdx] = prev
        }
        return copy(buckets = next)
    }

    fun moveDown(entryId: String): RankBoardState {
        val (bIdx, iIdx) = findBucketCoords(buckets, entryId) ?: return this
        val next = buckets.map { it.toMutableList() }.toMutableList()
        if (next[bIdx].size > 1) {
            val item = next[bIdx].removeAt(iIdx)
            next.add(bIdx + 1, mutableListOf(item))
        } else if (bIdx + 1 < next.size) {
            val below = next[bIdx + 1]
            next[bIdx + 1] = next[bIdx]
            next[bIdx] = below
        }
        return copy(buckets = next)
    }

    fun moveToRankSlot(entryId: String, targetZeroBased: Int): RankBoardState {
        val total = size
        if (total <= 1) return this
        val (bIdx, iIdx) = findBucketCoords(buckets, entryId) ?: return this
        val target = targetZeroBased.coerceIn(0, total - 1)
        val oldFlat = flatIndexOf(buckets, entryId)
        val next = buckets.map { it.toMutableList() }.toMutableList()
        val item = next[bIdx].removeAt(iIdx)
        if (next[bIdx].isEmpty()) {
            next.removeAt(bIdx)
        }
        val insertIdx = computeSlotInsertIndex(next, target, oldFlat)
        next.add(insertIdx, mutableListOf(item))
        return copy(buckets = next)
    }

    fun toggleTieWithAbove(entryId: String): RankBoardState {
        val (bIdx, iIdx) = findBucketCoords(buckets, entryId) ?: return this
        val next = buckets.map { it.toMutableList() }.toMutableList()
        if (iIdx > 0) {
            val head = next[bIdx].subList(0, iIdx).toMutableList()
            val tail = next[bIdx].subList(iIdx, next[bIdx].size).toMutableList()
            next[bIdx] = head
            next.add(bIdx + 1, tail)
        } else if (bIdx > 0) {
            val merged = next.removeAt(bIdx)
            next[bIdx - 1].addAll(merged)
        }
        return copy(buckets = next)
    }

    fun tieAll(): RankBoardState {
        val flat = buckets.flatten()
        return if (flat.isEmpty()) copy(buckets = emptyList()) else copy(buckets = listOf(flat))
    }

    fun toggleTieAll(): RankBoardState {
        if (size <= 1) return this
        val flat = buckets.flatten()
        return if (buckets.size == 1) {
            copy(buckets = flat.map(::listOf))
        } else {
            copy(buckets = listOf(flat))
        }
    }

    fun dropOnto(entryId: String, target: RankDropTarget): RankBoardState {
        val coords = findBucketCoords(buckets, entryId)
        return if (coords == null || target.bucketIndex !in buckets.indices) {
            this
        } else {
            applyRankBoardDrop(this, coords.first, coords.second, target)
        }
    }

    internal companion object {
        fun fromCandidates(
            items: List<RankClusterCandidate>,
            tiedWithAboveIds: Set<String> = emptySet(),
        ): RankBoardState {
            if (items.isEmpty()) return RankBoardState(emptyList())
            val buckets = mutableListOf<MutableList<RankClusterCandidate>>()
            items.forEachIndexed { idx, item ->
                if (idx == 0 || item.entryId !in tiedWithAboveIds) {
                    buckets.add(mutableListOf(item))
                } else {
                    buckets.last().add(item)
                }
            }
            return RankBoardState(buckets)
        }
    }
}

internal enum class RankDropZone {
    BEFORE,
    TIE,
    AFTER,
}

internal data class RankDropTarget(
    val bucketIndex: Int,
    val zone: RankDropZone,
)

internal fun applyRankBoardDrop(
    board: RankBoardState,
    srcB: Int,
    srcI: Int,
    target: RankDropTarget,
): RankBoardState {
    if (target.zone == RankDropZone.TIE && srcB == target.bucketIndex) return board
    val next = board.buckets.map { it.toMutableList() }.toMutableList()
    val item = next[srcB].removeAt(srcI)
    if (target.zone == RankDropZone.TIE) {
        next[target.bucketIndex].add(item)
        return board.copy(buckets = next.filter { it.isNotEmpty() })
    }
    val removedSrcBucket = next[srcB].isEmpty()
    if (removedSrcBucket) {
        next.removeAt(srcB)
    }
    val rawInsert =
        if (target.zone == RankDropZone.BEFORE) target.bucketIndex else target.bucketIndex + 1
    val adjusted = if (removedSrcBucket && srcB < rawInsert) rawInsert - 1 else rawInsert
    next.add(adjusted.coerceIn(0, next.size), mutableListOf(item))
    return board.copy(buckets = next)
}

internal fun insertCandidateByScore(
    buckets: MutableList<MutableList<RankClusterCandidate>>,
    candidate: RankClusterCandidate,
) {
    val insertIdx = buckets.indexOfFirst { bucket ->
        val head = bucket.firstOrNull()
        head != null && shouldInsertBefore(candidate, head)
    }
    if (insertIdx >= 0) {
        buckets.add(insertIdx, mutableListOf(candidate))
    } else {
        buckets.add(mutableListOf(candidate))
    }
}

private fun shouldInsertBefore(
    candidate: RankClusterCandidate,
    head: RankClusterCandidate,
): Boolean =
    candidate.mean > head.mean || candidate.mean == head.mean && candidate.entryId < head.entryId

internal fun resolveRankDropTarget(
    pointerY: Float,
    sourceBucketIndex: Int,
    bucketBounds: List<Pair<Float, Float>>,
): RankDropTarget? =
    when {
        bucketBounds.isEmpty() -> null
        pointerY < bucketBounds.first().first -> RankDropTarget(0, RankDropZone.BEFORE)
        pointerY > bucketBounds.last().second ->
            RankDropTarget(bucketBounds.lastIndex, RankDropZone.AFTER)
        else -> resolveInteriorDropTarget(pointerY, sourceBucketIndex, bucketBounds)
    }

private fun resolveInteriorDropTarget(
    pointerY: Float,
    sourceBucketIndex: Int,
    bucketBounds: List<Pair<Float, Float>>,
): RankDropTarget {
    val hitIndex = bucketBounds.indexOfFirst { (top, bottom) -> pointerY in top..bottom }
    if (hitIndex >= 0) {
        return resolveZoneWithinBucket(
            pointerY,
            hitIndex,
            sourceBucketIndex,
            bucketBounds[hitIndex],
        )
    }
    val nextIndex = bucketBounds.indexOfFirst { (top, _) -> pointerY < top }.coerceAtLeast(1)
    val midpoint =
        (bucketBounds[nextIndex - 1].second + bucketBounds[nextIndex].first) * DROP_MIDPOINT
    return if (pointerY < midpoint) {
        RankDropTarget(nextIndex - 1, RankDropZone.AFTER)
    } else {
        RankDropTarget(nextIndex, RankDropZone.BEFORE)
    }
}

private fun resolveZoneWithinBucket(
    pointerY: Float,
    bucketIndex: Int,
    sourceBucketIndex: Int,
    bounds: Pair<Float, Float>,
): RankDropTarget {
    val height = (bounds.second - bounds.first).coerceAtLeast(1f)
    val frac = ((pointerY - bounds.first) / height).coerceIn(0f, 1f)
    val canTie = bucketIndex != sourceBucketIndex
    val zone =
        when {
            frac < DROP_EDGE_THRESHOLD -> RankDropZone.BEFORE
            frac > 1f - DROP_EDGE_THRESHOLD -> RankDropZone.AFTER
            canTie -> RankDropZone.TIE
            frac < DROP_MIDPOINT -> RankDropZone.BEFORE
            else -> RankDropZone.AFTER
        }
    return RankDropTarget(bucketIndex, zone)
}

private const val DROP_EDGE_THRESHOLD = 0.28f
private const val DROP_MIDPOINT = 0.5f

internal data class RankBoardRowState(
    val candidate: RankClusterCandidate,
    val flatIndex: Int,
    val displayRank: Int,
    val isTiedWithAbove: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

internal fun findBucketCoords(
    buckets: List<List<RankClusterCandidate>>,
    entryId: String,
): Pair<Int, Int>? {
    var coords: Pair<Int, Int>? = null
    for ((bIdx, bucket) in buckets.withIndex()) {
        val iIdx = bucket.indexOfFirst { it.entryId == entryId }
        if (iIdx >= 0) {
            coords = bIdx to iIdx
            break
        }
    }
    return coords
}

internal fun flatIndexOf(
    buckets: List<List<RankClusterCandidate>>,
    entryId: String,
): Int = buckets.flatten().indexOfFirst { it.entryId == entryId }.coerceAtLeast(0)

internal fun computeSlotInsertIndex(
    buckets: List<List<RankClusterCandidate>>,
    target: Int,
    oldFlat: Int,
): Int {
    var acc = 0
    var insertIdx = buckets.size
    for ((idx, bucket) in buckets.withIndex()) {
        val nextAcc = acc + bucket.size
        if (target == acc || target < nextAcc) {
            insertIdx = if (target == acc || oldFlat > target) idx else idx + 1
            break
        }
        acc = nextAcc
    }
    return insertIdx
}
