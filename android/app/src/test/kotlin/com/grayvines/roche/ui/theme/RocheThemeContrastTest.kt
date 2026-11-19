package com.grayvines.roche.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies WCAG 2.1 AA contrast ratios across [DarkRocheColors], [LightRocheColors], their
 * alpha-composited badge/pill surfaces, filled primary button surfaces, non-text control outlines,
 * and the full Material 3 [androidx.compose.material3.ColorScheme].
 */
class RocheThemeContrastTest {

    @Test
    fun darkThemeMeetsWcagAaContrast() {
        assertPaletteContrast("dark", DarkRocheColors)
    }

    @Test
    fun lightThemeMeetsWcagAaContrast() {
        assertPaletteContrast("light", LightRocheColors)
    }

    @Test
    fun pdfFallbackInkMeetsWcagAaOnWhitePaper() {
        assertContrastAtLeast(
            label = "PDF placeholder text on white page",
            foreground = LightRocheColors.textMuted,
            background = Color.White,
            minimumRatio = WCAG_AA_NORMAL_TEXT,
        )
    }

    private fun assertPaletteContrast(themeName: String, colors: RocheColors) {
        val surfaces =
            listOf(
                "background" to colors.background,
                "surface" to colors.surface,
                "surfaceElevated" to colors.surfaceElevated,
            )

        // All text and accent-text tokens must meet WCAG AA (4.5:1) on every surface tier.
        val textTokens =
            listOf(
                "textPrimary" to colors.textPrimary,
                "textSecondary" to colors.textSecondary,
                "textMuted" to colors.textMuted,
                "accentBlue" to colors.accentBlue,
                "accentVerdigris" to colors.accentVerdigris,
                "accentEcru" to colors.accentEcru,
                "accentIndianRed" to colors.accentIndianRed,
                "tierSGold" to colors.tierSGold,
            )

        for ((fgName, fg) in textTokens) {
            for ((bgName, bg) in surfaces) {
                assertContrastAtLeast(
                    label = "$themeName $fgName on $bgName",
                    foreground = fg,
                    background = bg,
                    minimumRatio = WCAG_AA_NORMAL_TEXT,
                )
            }
        }

        // Alpha-composited tier/status pill backgrounds (16% over surface) and tie pill (20% ecru).
        assertBadgePillContrast(themeName, colors)

        // Filled custom primary buttons (HeaderActionButton / CompactCardActionButton / Save FAB).
        assertContrastAtLeast(
            label = "$themeName white text on accentBlueFilled",
            foreground = Color.White,
            background = colors.accentBlueFilled,
            minimumRatio = WCAG_AA_NORMAL_TEXT,
        )

        // Non-text control outlines (Switch, OutlinedTextField, slider track) >= 3.0:1 (SC 1.4.11).
        for ((bgName, bg) in surfaces) {
            assertContrastAtLeast(
                label = "$themeName controlOutline on $bgName",
                foreground = colors.controlOutline,
                background = bg,
                minimumRatio = WCAG_AA_UI_COMPONENT,
            )
        }

        assertMaterialSchemeContrast(themeName, colors)
    }

    private fun assertBadgePillContrast(themeName: String, colors: RocheColors) {
        val badgeTokens =
            listOf(
                "accentBlue" to colors.accentBlue,
                "accentVerdigris" to colors.accentVerdigris,
                "accentEcru" to colors.accentEcru,
                "accentIndianRed" to colors.accentIndianRed,
                "tierSGold" to colors.tierSGold,
            )
        for ((name, token) in badgeTokens) {
            val pillSurface = token.copy(alpha = PILL_TINT_ALPHA).compositeOver(colors.surface)
            assertContrastAtLeast(
                label = "$themeName $name on 16%-tinted surface pill",
                foreground = token,
                background = pillSurface,
                minimumRatio = WCAG_AA_NORMAL_TEXT,
            )
        }
        val tiePillSurface =
            colors.accentEcru.copy(alpha = TIE_PILL_TINT_ALPHA).compositeOver(colors.surface)
        assertContrastAtLeast(
            label = "$themeName accentEcru on 20%-tinted tie pill",
            foreground = colors.accentEcru,
            background = tiePillSurface,
            minimumRatio = WCAG_AA_NORMAL_TEXT,
        )
    }

    private fun assertMaterialSchemeContrast(themeName: String, colors: RocheColors) {
        val scheme = buildMaterialScheme(colors)
        val textPairs =
            listOf(
                "onPrimary on primary" to (scheme.onPrimary to scheme.primary),
                "onSecondary on secondary" to (scheme.onSecondary to scheme.secondary),
                "onTertiary on tertiary" to (scheme.onTertiary to scheme.tertiary),
                "onError on error" to (scheme.onError to scheme.error),
                "onBackground on background" to (scheme.onBackground to scheme.background),
                "onSurface on surface" to (scheme.onSurface to scheme.surface),
                "onSurfaceVariant on surface" to (scheme.onSurfaceVariant to scheme.surface),
                "primary on surface (focused field label)" to (scheme.primary to scheme.surface),
                "primary on surfaceContainerHigh (dialog TextButton)" to
                    (scheme.primary to scheme.surfaceContainerHigh),
            )
        for ((label, pair) in textPairs) {
            assertContrastAtLeast(
                label = "$themeName Material $label",
                foreground = pair.first,
                background = pair.second,
                minimumRatio = WCAG_AA_NORMAL_TEXT,
            )
        }
        val outlinePairs =
            listOf(
                "outline on surface" to (scheme.outline to scheme.surface),
                "outline on surfaceContainerHighest (unchecked Switch)" to
                    (scheme.outline to scheme.surfaceContainerHighest),
            )
        for ((label, pair) in outlinePairs) {
            assertContrastAtLeast(
                label = "$themeName Material $label",
                foreground = pair.first,
                background = pair.second,
                minimumRatio = WCAG_AA_UI_COMPONENT,
            )
        }
    }

    private fun assertContrastAtLeast(
        label: String,
        foreground: Color,
        background: Color,
        minimumRatio: Double,
    ) {
        val ratio = contrastRatio(foreground, background)
        val formattedRatio = String.format(Locale.ROOT, "%.2f", ratio)
        val formattedMin = String.format(Locale.ROOT, "%.1f", minimumRatio)
        assertTrue(
            ratio >= minimumRatio,
            "$label contrast ratio is $formattedRatio:1, expected >= $formattedMin:1",
        )
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + LUMINANCE_OFFSET) / (darker + LUMINANCE_OFFSET)
    }

    private fun relativeLuminance(color: Color): Double {
        val r = linearize(color.red.toDouble())
        val g = linearize(color.green.toDouble())
        val b = linearize(color.blue.toDouble())
        return RED_WEIGHT * r + GREEN_WEIGHT * g + BLUE_WEIGHT * b
    }

    private fun linearize(channel: Double): Double =
        if (channel <= SRGB_LINEAR_THRESHOLD) {
            channel / SRGB_LINEAR_DIVISOR
        } else {
            ((channel + SRGB_GAMMA_OFFSET) / SRGB_GAMMA_DIVISOR).pow(SRGB_GAMMA_EXPONENT)
        }

    companion object {
        private const val WCAG_AA_NORMAL_TEXT = 4.5
        private const val WCAG_AA_UI_COMPONENT = 3.0
        private const val PILL_TINT_ALPHA = 0.16f
        private const val TIE_PILL_TINT_ALPHA = 0.20f
        private const val LUMINANCE_OFFSET = 0.05
        private const val RED_WEIGHT = 0.2126
        private const val GREEN_WEIGHT = 0.7152
        private const val BLUE_WEIGHT = 0.0722
        private const val SRGB_LINEAR_THRESHOLD = 0.04045
        private const val SRGB_LINEAR_DIVISOR = 12.92
        private const val SRGB_GAMMA_OFFSET = 0.055
        private const val SRGB_GAMMA_DIVISOR = 1.055
        private const val SRGB_GAMMA_EXPONENT = 2.4
    }
}
