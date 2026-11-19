package com.grayvines.roche.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
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
    val controlOutline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentBlue: Color,
    val accentBlueFilled: Color,
    val accentVerdigris: Color,
    val accentEcru: Color,
    val accentIndianRed: Color,
    val tierSGold: Color,
    val isDark: Boolean,
)

internal val DarkRocheColors =
    RocheColors(
        background = Color(0xFF141413),
        surface = Color(0xFF1B1B19),
        surfaceElevated = Color(0xFF232220),
        border = Color(0xFF272624),
        controlOutline = Color(0xFF8C8780),
        textPrimary = RochePalette.Timberwolf,
        textSecondary = Color(0xFFA8A39D),
        textMuted = Color(0xFF94908A),
        accentBlue = Color(0xFF62A9CF),
        accentBlueFilled = Color(0xFF3A7696),
        accentVerdigris = Color(0xFF54B8B5),
        accentEcru = RochePalette.Ecru,
        accentIndianRed = Color(0xFFDC8387),
        tierSGold = Color(0xFFE0B44C),
        isDark = true,
    )

internal val LightRocheColors =
    RocheColors(
        background = Color(0xFFEFECE8),
        surface = RochePalette.Timberwolf,
        surfaceElevated = Color(0xFFD8D2CC),
        border = Color(0xFFD2CBC3),
        controlOutline = Color(0xFF6B655E),
        textPrimary = Color(0xFF182024),
        textSecondary = Color(0xFF424A4E),
        textMuted = Color(0xFF53595C),
        accentBlue = Color(0xFF235978),
        accentBlueFilled = Color(0xFF2B607E),
        accentVerdigris = Color(0xFF195855),
        accentEcru = Color(0xFF64491D),
        accentIndianRed = Color(0xFF8D3338),
        tierSGold = Color(0xFF704E09),
        isDark = false,
    )

val LocalRocheColors = staticCompositionLocalOf { DarkRocheColors }
val LocalHeadlineFont = staticCompositionLocalOf { HeadlineFont.SERIF }
val LocalReaderFontScale = staticCompositionLocalOf { 1.0f }

fun HeadlineFont.toFontFamily(): FontFamily =
    if (this == HeadlineFont.SERIF) FontFamily.Serif else FontFamily.SansSerif

@Composable fun currentHeadlineFontFamily(): FontFamily = LocalHeadlineFont.current.toFontFamily()

@Composable
fun RocheTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    headlineFont: HeadlineFont = HeadlineFont.SERIF,
    readerFontScale: Float = 1.0f,
    content: @Composable () -> Unit,
) {
    val rocheColors = if (themeMode == ThemeMode.DARK) DarkRocheColors else LightRocheColors
    val darkTheme = rocheColors.isDark
    val headlineFamily = headlineFont.toFontFamily()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity ?: view.context.findActivity())?.window
            if (window != null) {
                WindowInsetsControllerCompat(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalRocheColors provides rocheColors,
        LocalHeadlineFont provides headlineFont,
        LocalReaderFontScale provides readerFontScale,
    ) {
        MaterialTheme(
            colorScheme = buildMaterialScheme(rocheColors),
            typography = buildTypography(headlineFamily),
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

internal fun buildMaterialScheme(rocheColors: RocheColors): ColorScheme =
    if (rocheColors.isDark) {
        darkColorScheme(
            primary = rocheColors.accentBlue,
            onPrimary = rocheColors.background,
            secondary = rocheColors.accentVerdigris,
            onSecondary = rocheColors.background,
            tertiary = rocheColors.accentEcru,
            onTertiary = rocheColors.background,
            error = rocheColors.accentIndianRed,
            onError = rocheColors.background,
            background = rocheColors.background,
            onBackground = rocheColors.textPrimary,
            surface = rocheColors.surface,
            onSurface = rocheColors.textPrimary,
            surfaceVariant = rocheColors.surfaceElevated,
            onSurfaceVariant = rocheColors.textSecondary,
            surfaceContainerLowest = rocheColors.background,
            surfaceContainerLow = rocheColors.background,
            surfaceContainer = rocheColors.surface,
            surfaceContainerHigh = rocheColors.surfaceElevated,
            surfaceContainerHighest = rocheColors.surfaceElevated,
            outline = rocheColors.controlOutline,
            outlineVariant = rocheColors.border,
        )
    } else {
        lightColorScheme(
            primary = rocheColors.accentBlue,
            onPrimary = Color.White,
            secondary = rocheColors.accentVerdigris,
            onSecondary = Color.White,
            tertiary = rocheColors.accentEcru,
            onTertiary = Color.White,
            error = rocheColors.accentIndianRed,
            onError = Color.White,
            background = rocheColors.background,
            onBackground = rocheColors.textPrimary,
            surface = rocheColors.surface,
            onSurface = rocheColors.textPrimary,
            surfaceVariant = rocheColors.surfaceElevated,
            onSurfaceVariant = rocheColors.textSecondary,
            surfaceContainerLowest = rocheColors.background,
            surfaceContainerLow = rocheColors.background,
            surfaceContainer = rocheColors.surface,
            surfaceContainerHigh = rocheColors.surfaceElevated,
            surfaceContainerHighest = rocheColors.surfaceElevated,
            outline = rocheColors.controlOutline,
            outlineVariant = rocheColors.border,
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
