package com.grayvines.roche.ranking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RankDragBoardTest {
    private fun sampleBoard(): RankBoardState {
        val candidates =
            listOf("a", "b", "c", "d").map { id ->
                RankClusterCandidate(
                    entryId = id,
                    title = id.uppercase(),
                    subtitle = "",
                    thumbnailUrl = null,
                    mean = 0.0,
                    variance = 1.0,
                )
            }
        return RankBoardState.fromCandidates(candidates)
    }

    @Test
    fun `dropOnto reorders before and after buckets and merges or extracts ties`() {
        var board = sampleBoard()

        // Drag "a" down onto the middle of "c" -> tie [c, a]
        board = board.dropOnto("a", RankDropTarget(2, RankDropZone.TIE))
        assertEquals(listOf(listOf("b"), listOf("c", "a"), listOf("d")), board.toOrderingGroups())

        // Drag "a" out of the tied bucket onto top edge of its own bucket -> unties above "c"
        board = board.dropOnto("a", RankDropTarget(1, RankDropZone.BEFORE))
        assertEquals(
            listOf(listOf("b"), listOf("a"), listOf("c"), listOf("d")),
            board.toOrderingGroups(),
        )

        // Drag "d" to BEFORE bucket 0 -> moves to #1
        board = board.dropOnto("d", RankDropTarget(0, RankDropZone.BEFORE))
        assertEquals(
            listOf(listOf("d"), listOf("b"), listOf("a"), listOf("c")),
            board.toOrderingGroups(),
        )

        // Drag "d" to AFTER bucket 2 ("a") -> moves right after "a"
        board = board.dropOnto("d", RankDropTarget(2, RankDropZone.AFTER))
        assertEquals(
            listOf(listOf("b"), listOf("a"), listOf("d"), listOf("c")),
            board.toOrderingGroups(),
        )

        // Dropping onto same bucket TIE is a no-op
        val unchanged = board.dropOnto("a", RankDropTarget(1, RankDropZone.TIE))
        assertEquals(board.toOrderingGroups(), unchanged.toOrderingGroups())
    }

    @Test
    fun `resolveRankDropTarget maps top edge middle tie zone bottom edge and inter-bucket gaps`() {
        assertNull(resolveRankDropTarget(50f, 0, emptyList()))

        val bounds = listOf(100f to 200f, 220f to 320f, 340f to 440f)

        // Above first bucket -> BEFORE 0
        assertEquals(
            RankDropTarget(0, RankDropZone.BEFORE),
            resolveRankDropTarget(80f, 2, bounds),
        )
        // Below last bucket -> AFTER 2
        assertEquals(
            RankDropTarget(2, RankDropZone.AFTER),
            resolveRankDropTarget(500f, 0, bounds),
        )
        // Top 28% of bucket 1 -> BEFORE 1
        assertEquals(
            RankDropTarget(1, RankDropZone.BEFORE),
            resolveRankDropTarget(235f, 0, bounds),
        )
        // Middle of bucket 1 when dragging from bucket 0 -> TIE 1
        assertEquals(
            RankDropTarget(1, RankDropZone.TIE),
            resolveRankDropTarget(270f, 0, bounds),
        )
        // Middle of own bucket 1 -> does NOT offer self-TIE
        assertEquals(
            RankDropTarget(1, RankDropZone.BEFORE),
            resolveRankDropTarget(260f, 1, bounds),
        )
        // Bottom 28% of bucket 1 -> AFTER 1
        assertEquals(
            RankDropTarget(1, RankDropZone.AFTER),
            resolveRankDropTarget(305f, 0, bounds),
        )
        // Inter-bucket gap between 0 (ends 200) and 1 (starts 220): 205 -> AFTER 0, 215 -> BEFORE 1
        assertEquals(
            RankDropTarget(0, RankDropZone.AFTER),
            resolveRankDropTarget(205f, 2, bounds),
        )
        assertEquals(
            RankDropTarget(1, RankDropZone.BEFORE),
            resolveRankDropTarget(215f, 2, bounds),
        )
    }
}
