package com.grayvines.roche.core

import androidx.compose.ui.text.font.FontStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InlineMathTest {
    @Test
    fun `stripInlineMathDelimiters formats latex cleanly and preserves currency`() {
        val paperTitle =
            """On the recently proposed ${'$'}o(n \log n)${'$'} integer multiplication algorithm"""
        assertEquals(
            "On the recently proposed o(n log n) integer multiplication algorithm",
            stripInlineMathDelimiters(paperTitle),
        )
        val currency = """Costs ${'$'}5 and ${'$'}10 per seat"""
        assertEquals(currency, stripInlineMathDelimiters(currency))
        val bounds = """Bounds ${'$'}x \in \mathbb{R}${'$'} and ${'$'}p \geq 17${'$'}"""
        assertEquals("Bounds x ∈ ℝ and p ≥ 17", stripInlineMathDelimiters(bounds))
    }

    @Test
    fun `formatInlineMathAnnotated italicizes math variables and strips dollars`() {
        val annotated =
            formatInlineMathAnnotated("""Proposed ${'$'}o(n \log n)${'$'} multiplication""")
        assertEquals("Proposed o(n log n) multiplication", annotated.text)
        assertTrue(annotated.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `inline math handles escaped dollars, fractions, accents, fences, and bare operators`() {
        // Escaped \$ must not pair with a later $x$
        val escaped = formatInlineMathAnnotated("""Price \${'$'}5 and ${'$'}x${'$'}""")
        assertEquals("Price \$5 and x", escaped.text)
        assertTrue(
            escaped.spanStyles.any {
                it.item.fontStyle == FontStyle.Italic &&
                    escaped.text.substring(it.start, it.end) == "x"
            }
        )

        // Fractions, accents, fences
        assertEquals(
            "Ratio a+1/b and ⟨x̃, n̂⟩",
            stripInlineMathDelimiters(
                """Ratio ${'$'}\frac{a+1}{b}${'$'} and ${'$'}\langle\tilde{x}, \hat{n}\rangle${'$'}"""
            ),
        )
        assertEquals(
            "⌊n/2⌋",
            stripInlineMathDelimiters("""${'$'}\lfloor\frac{n}{2}\rfloor${'$'}"""),
        )

        // Bare named operator `log` renders upright while variables stay italic
        val bareOp = formatInlineMathAnnotated("""${'$'}o(n log n)${'$'}""")
        assertEquals("o(n log n)", bareOp.text)
        val italicSlices =
            bareOp.spanStyles
                .filter { it.item.fontStyle == FontStyle.Italic }
                .map { bareOp.text.substring(it.start, it.end) }
        assertTrue("o" in italicSlices)
        assertTrue("n" in italicSlices)
        assertTrue(italicSlices.none { "log" in it })
    }
}
