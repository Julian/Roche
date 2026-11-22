package com.grayvines.roche.ranking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    private fun prioritizedCandidates(): List<RankClusterCandidate> =
        listOf(
            RankClusterCandidate(
                "hi",
                "High",
                "",
                null,
                mean = 1.2,
                variance = 0.2,
                selectionPriority = 4,
            ),
            RankClusterCandidate(
                "mid",
                "Mid",
                "",
                null,
                mean = 0.5,
                variance = 0.3,
                selectionPriority = 3,
            ),
            RankClusterCandidate(
                "near2",
                "Near 2",
                "",
                null,
                mean = 0.1,
                variance = 0.4,
                selectionPriority = 2,
            ),
            RankClusterCandidate(
                "near1",
                "Near 1",
                "",
                null,
                mean = -0.1,
                variance = 0.4,
                selectionPriority = 1,
            ),
            RankClusterCandidate(
                "focus",
                "Focus",
                "",
                null,
                mean = -0.3,
                variance = 0.8,
                selectionPriority = 0,
            ),
        )

    @Test
    fun `exclude enforces focus guard and MIN_K floor while restore puts items back`() {
        var board = RankBoardState.fromCandidates(prioritizedCandidates())
        assertFalse(board.canExclude("focus", focusEntryId = "focus"))
        assertEquals(board, board.exclude("focus", focusEntryId = "focus"))

        board = board.exclude("hi", "focus").exclude("mid", "focus").exclude("near2", "focus")
        assertEquals(2, board.size)
        assertEquals(setOf("hi", "mid", "near2"), board.manuallyExcludedIds)
        assertFalse(board.canExclude("near1", focusEntryId = "focus"))

        board = board.restore("near2")
        assertEquals(3, board.size)
        assertEquals(setOf("hi", "mid"), board.manuallyExcludedIds)

        board = board.restoreAll()
        assertEquals(5, board.size)
        assertTrue(board.excluded.isEmpty())
        assertTrue(board.manuallyExcludedIds.isEmpty())
    }

    @Test
    fun `resizeTo drops least confusable items and preserves manual exclusions`() {
        var board = RankBoardState.fromCandidates(prioritizedCandidates())

        // Resizing 5 -> 3 drops least-confusable ("hi" p=4, "mid" p=3), NEVER "focus" (p=0)
        board = board.resizeTo(3, focusEntryId = "focus")
        assertEquals(
            listOf(listOf("near2"), listOf("near1"), listOf("focus")),
            board.toOrderingGroups(),
        )
        assertEquals(setOf("hi", "mid"), board.excluded.map { it.entryId }.toSet())
        assertTrue(board.manuallyExcludedIds.isEmpty())

        // Reordering/tying preserves excluded items
        board = board.moveUp("focus").toggleTieWithAbove("near1")
        assertEquals(setOf("hi", "mid"), board.excluded.map { it.entryId }.toSet())

        // Manually excluding "near2" then growing to 5 restores size-trimmed items only
        board = board.exclude("near2", focusEntryId = "focus").resizeTo(5, focusEntryId = "focus")
        assertEquals(4, board.size)
        assertEquals(listOf("near2"), board.excluded.map { it.entryId })
        assertEquals(setOf("near2"), board.manuallyExcludedIds)
    }
}
