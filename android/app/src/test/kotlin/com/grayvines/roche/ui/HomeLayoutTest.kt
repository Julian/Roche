package com.grayvines.roche.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeLayoutTest {
    @Test
    fun `computeHomeInboxMaxItems sizes full-width inbox to roughly two-thirds of viewport height`() {
        // Standard phone portrait (~412x740 content area): 6 rows (~420dp + 78dp chrome ≈ 67%).
        assertEquals(
            6,
            computeHomeInboxMaxItems(viewportWidth = 412.dp, viewportHeight = 740.dp),
        )

        // Unfolded foldable (~700x620 content area): 5 full-width rows (~320dp + 78dp ≈ 64%).
        assertEquals(
            5,
            computeHomeInboxMaxItems(viewportWidth = 700.dp, viewportHeight = 620.dp),
        )

        // Tall tablet portrait (~800x1080 content area): 10 full-width rows (~640dp + 78dp ≈ 66%).
        assertEquals(
            10,
            computeHomeInboxMaxItems(viewportWidth = 800.dp, viewportHeight = 1080.dp),
        )

        // Short landscape viewport clamps gracefully to minimum preview count.
        assertEquals(
            3,
            computeHomeInboxMaxItems(viewportWidth = 915.dp, viewportHeight = 260.dp),
        )

        // Unspecified constraints fall back safely without throwing.
        assertEquals(
            6,
            computeHomeInboxMaxItems(
                viewportWidth = Dp.Unspecified,
                viewportHeight = Dp.Unspecified,
            ),
        )
    }

    @Test
    fun `computeHomeInboxMaxItems scales item count down for larger accessibility font scales`() {
        val defaultItems =
            computeHomeInboxMaxItems(
                viewportWidth = 412.dp,
                viewportHeight = 740.dp,
                fontScale = 1.0f,
            )
        val largeFontItems =
            computeHomeInboxMaxItems(
                viewportWidth = 412.dp,
                viewportHeight = 740.dp,
                fontScale = 1.5f,
            )
        val maxFontItems =
            computeHomeInboxMaxItems(
                viewportWidth = 412.dp,
                viewportHeight = 740.dp,
                fontScale = 2.0f,
            )
        assertTrue(largeFontItems < defaultItems)
        assertTrue(maxFontItems <= largeFontItems)
        assertTrue(maxFontItems >= 3)
    }

    @Test
    fun `resolveHomeBottomLayout splits Queue and Draw into two columns on phones and tablets`() {
        val belowTwoCol = resolveHomeBottomLayout(viewportWidth = 339.dp, hasActiveDraws = true)
        assertFalse(belowTwoCol.useTwoColumns)
        assertFalse(belowTwoCol.isCompactColumn)

        val atTwoCol = resolveHomeBottomLayout(viewportWidth = 340.dp, hasActiveDraws = true)
        assertTrue(atTwoCol.useTwoColumns)
        assertTrue(atTwoCol.isCompactColumn)
        assertEquals(12.dp, atTwoCol.columnGap)

        val phoneWithDraw = resolveHomeBottomLayout(viewportWidth = 412.dp, hasActiveDraws = true)
        assertTrue(phoneWithDraw.useTwoColumns)
        assertTrue(phoneWithDraw.isCompactColumn)
        assertEquals(12.dp, phoneWithDraw.columnGap)

        // 540dp => (540 - 28 - 14) / 2 = 249dp < 250dp (still compact); 542dp => 250dp (roomy).
        val boundaryCompact = resolveHomeBottomLayout(viewportWidth = 540.dp, hasActiveDraws = true)
        assertTrue(boundaryCompact.useTwoColumns)
        assertTrue(boundaryCompact.isCompactColumn)

        val boundaryRoomy = resolveHomeBottomLayout(viewportWidth = 542.dp, hasActiveDraws = true)
        assertTrue(boundaryRoomy.useTwoColumns)
        assertFalse(boundaryRoomy.isCompactColumn)
        assertEquals(14.dp, boundaryRoomy.columnGap)

        val foldableWithDraw =
            resolveHomeBottomLayout(viewportWidth = 700.dp, hasActiveDraws = true)
        assertTrue(foldableWithDraw.useTwoColumns)
        assertFalse(foldableWithDraw.isCompactColumn)
        assertEquals(14.dp, foldableWithDraw.columnGap)

        val noActiveDraws = resolveHomeBottomLayout(viewportWidth = 700.dp, hasActiveDraws = false)
        assertFalse(noActiveDraws.useTwoColumns)
        assertFalse(noActiveDraws.isCompactColumn)
    }

    @Test
    fun `resolveHomeBottomLayout adapts columns and compactness for large accessibility font scales`() {
        // Phone at 1.5x font scale stacks to single column so cards aren't squeezed into ~124sp.
        val phoneLargeFont =
            resolveHomeBottomLayout(
                viewportWidth = 412.dp,
                hasActiveDraws = true,
                fontScale = 1.5f,
            )
        assertFalse(phoneLargeFont.useTwoColumns)
        assertFalse(phoneLargeFont.isCompactColumn)

        // Phone at 2.0x font scale stacks AND uses compact card layout so headers never crush.
        val phoneMaxFont =
            resolveHomeBottomLayout(
                viewportWidth = 412.dp,
                hasActiveDraws = true,
                fontScale = 2.0f,
            )
        assertFalse(phoneMaxFont.useTwoColumns)
        assertTrue(phoneMaxFont.isCompactColumn)

        // Medium foldable at 2.0x font scale keeps two columns only when wide enough, and uses
        // compact column headers/cards so DualActionSectionHeaderRow cannot starve the title.
        val mediumFoldableMaxFont =
            resolveHomeBottomLayout(
                viewportWidth = 600.dp,
                hasActiveDraws = true,
                fontScale = 2.0f,
            )
        assertFalse(mediumFoldableMaxFont.useTwoColumns)
        assertFalse(mediumFoldableMaxFont.isCompactColumn)

        val wideFoldableMaxFont =
            resolveHomeBottomLayout(
                viewportWidth = 700.dp,
                hasActiveDraws = true,
                fontScale = 2.0f,
            )
        assertTrue(wideFoldableMaxFont.useTwoColumns)
        assertTrue(wideFoldableMaxFont.isCompactColumn)
    }
}
