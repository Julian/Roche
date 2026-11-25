package com.grayvines.roche.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QueueDragReorderTest {
    @Test
    fun `applyVisibleQueueReorder moves visible items while preserving hidden and concurrent entries`() {
        assertEquals(
            listOf("c", "a", "b"),
            applyVisibleQueueReorder(listOf("a", "b", "c"), listOf("c", "a", "b")),
        )
        assertEquals(
            listOf("b", "hidden", "a"),
            applyVisibleQueueReorder(listOf("a", "hidden", "b"), listOf("b", "a")),
        )
        assertEquals(
            listOf("b", "a", "added"),
            applyVisibleQueueReorder(listOf("a", "b", "added"), listOf("b", "a")),
        )
        assertEquals(
            listOf("c", "a"),
            applyVisibleQueueReorder(listOf("a", "c"), listOf("c", "removed", "a")),
        )
        assertEquals(
            listOf("a", "b"),
            applyVisibleQueueReorder(listOf("a", "b"), listOf("a", "a")),
        )
    }

    @Test
    fun `moveItemInList moves entries cleanly and clamps out-of-range targets`() {
        assertEquals(listOf("b", "c", "a"), moveItemInList(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), moveItemInList(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "c", "b"), moveItemInList(listOf("a", "b", "c"), 1, 99))
        assertEquals(listOf("a", "b", "c"), moveItemInList(listOf("a", "b", "c"), 1, 1))
        assertEquals(listOf("a", "b", "c"), moveItemInList(listOf("a", "b", "c"), -1, 1))
    }

    @Test
    fun `findQueueSwapTarget resolves adjacent and multi-slot drags with unequal heights`() {
        val slots =
            listOf(
                QueueItemSlot(index = 0, key = "a", offset = 0, size = 100),
                QueueItemSlot(index = 1, key = "b", offset = 100, size = 120),
                QueueItemSlot(index = 2, key = "c", offset = 220, size = 80),
            )

        // Not past b's midpoint (160): dragged center = 0 + 100 + 50 = 150
        assertNull(
            findQueueSwapTarget(
                currentIndex = 0,
                draggedOffset = 0,
                draggedSize = 100,
                dragOffsetY = 100f,
                visibleSlots = slots,
            )
        )

        // Past b's midpoint (160) but not c's (260): dragged center = 0 + 170 + 50 = 220
        assertEquals(
            QueueSwapMatch(targetIndex = 1, layoutOffsetDelta = 120f),
            findQueueSwapTarget(
                currentIndex = 0,
                draggedOffset = 0,
                draggedSize = 100,
                dragOffsetY = 170f,
                visibleSlots = slots,
            ),
        )

        // Fast drag past c's midpoint (260): dragged center = 0 + 230 + 50 = 280 -> moves to offset
        // 200
        assertEquals(
            QueueSwapMatch(targetIndex = 2, layoutOffsetDelta = 200f),
            findQueueSwapTarget(
                currentIndex = 0,
                draggedOffset = 0,
                draggedSize = 100,
                dragOffsetY = 230f,
                visibleSlots = slots,
            ),
        )

        // Dragging c up past a's midpoint (50): dragged center = 220 - 220 + 40 = 40 -> moves to
        // offset 0
        assertEquals(
            QueueSwapMatch(targetIndex = 0, layoutOffsetDelta = -220f),
            findQueueSwapTarget(
                currentIndex = 2,
                draggedOffset = 220,
                draggedSize = 80,
                dragOffsetY = -220f,
                visibleSlots = slots,
            ),
        )
    }
}
