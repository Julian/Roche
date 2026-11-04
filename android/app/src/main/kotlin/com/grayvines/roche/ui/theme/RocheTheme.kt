package com.grayvines.roche.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode

/**
 * Roche signature 5-color palette (`https://coolors.co/4281a4-48a9a6-e4dfda-d4b483-c1666b`) paired
 * with neutral warm charcoal dark mode and Timberwolf editorial paper light mode so blue/teal stays
 * a sparse highlight rather than saturating surfaces.
 */
object RochePalette {
    val SteelBlue = Color(0xFF4281A4)
    val Verdigris = Color(0xFF48A9A6)
    val Timberwolf = Color(0xFFE4DFDA)
    val Ecru = Color(0xFFD4B483)
    val IndianRed = Color(0xFFC1666B)
}

@Immutable
data class RocheColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentBlue: Color,
    val accentVerdigris: Color,
    val accentEcru: Color,
    val accentIndianRed: Color,
    val isDark: Boolean,
)

private val DarkRocheColors =
    RocheColors(
        background = Color(0xFF141413),
        surface = Color(0xFF1B1B19),
        surfaceElevated = Color(0xFF232220),
        border = Color(0xFF272624),
        textPrimary = RochePalette.Timberwolf,
        textSecondary = Color(0xFFA8A39D),
        textMuted = Color(0xFF6E6A65),
        accentBlue = RochePalette.SteelBlue,
        accentVerdigris = RochePalette.Verdigris,
        accentEcru = RochePalette.Ecru,
        accentIndianRed = RochePalette.IndianRed,
        isDark = true,
    )

private val LightRocheColors =
    RocheColors(
        background = Color(0xFFEFECE8),
        surface = RochePalette.Timberwolf,
        surfaceElevated = Color(0xFFD8D2CC),
        border = Color(0xFFD2CBC3),
        textPrimary = Color(0xFF182024),
        textSecondary = Color(0xFF525A5E),
        textMuted = Color(0xFF7E8487),
        accentBlue = Color(0xFF336B8A),
        accentVerdigris = Color(0xFF358784),
        accentEcru = Color(0xFF9E7B46),
        accentIndianRed = Color(0xFFA84C51),
        isDark = false,
    )

val LocalRocheColors = staticCompositionLocalOf { DarkRocheColors }
val LocalHeadlineFont = staticCompositionLocalOf { HeadlineFont.SERIF }

@Composable
fun RocheTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    headlineFont: HeadlineFont = HeadlineFont.SERIF,
    content: @Composable () -> Unit,
) {
    val rocheColors = if (themeMode == ThemeMode.DARK) DarkRocheColors else LightRocheColors
    val headlineFamily =
        if (headlineFont == HeadlineFont.SERIF) FontFamily.Serif else FontFamily.SansSerif

    CompositionLocalProvider(
        LocalRocheColors provides rocheColors,
        LocalHeadlineFont provides headlineFont,
    ) {
        MaterialTheme(
            colorScheme = buildMaterialScheme(rocheColors),
            typography = buildTypography(headlineFamily),
            content = content,
        )
    }
}

private fun buildMaterialScheme(rocheColors: RocheColors): ColorScheme =
    if (rocheColors.isDark) {
        darkColorScheme(
            primary = rocheColors.accentBlue,
            secondary = rocheColors.accentVerdigris,
            tertiary = rocheColors.accentEcru,
            error = rocheColors.accentIndianRed,
            background = rocheColors.background,
            surface = rocheColors.surface,
            surfaceVariant = rocheColors.surfaceElevated,
            onPrimary = Color.White,
            onBackground = rocheColors.textPrimary,
            onSurface = rocheColors.textPrimary,
            onSurfaceVariant = rocheColors.textSecondary,
            outline = rocheColors.border,
        )
    } else {
        lightColorScheme(
            primary = rocheColors.accentBlue,
            secondary = rocheColors.accentVerdigris,
            tertiary = rocheColors.accentEcru,
            error = rocheColors.accentIndianRed,
            background = rocheColors.background,
            surface = rocheColors.surface,
            surfaceVariant = rocheColors.surfaceElevated,
            onPrimary = Color.White,
            onBackground = rocheColors.textPrimary,
            onSurface = rocheColors.textPrimary,
            onSurfaceVariant = rocheColors.textSecondary,
            outline = rocheColors.border,
        )
    }

private fun buildTypography(headlineFamily: FontFamily): Typography =
    Typography(
        headlineSmall =
            TextStyle(
                fontFamily = headlineFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = headlineFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 21.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            ),
    )
