package com.zucham.qbsmarter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Eight hand-rolled color schemes plus the choice of "follow system / light
 * / dark". Static schemes keep the shared module pure-Kotlin.
 */
enum class ThemeSeed(val key: String) {
    BLUE("blue"),
    GREEN("green"),
    PURPLE("purple"),
    ORANGE("orange"),
    RED("red"),
    PINK("pink"),
    YELLOW("yellow"),
    MONO("mono");
    companion object {
        fun fromKey(key: String?): ThemeSeed = entries.firstOrNull { it.key == key } ?: BLUE
    }
}

enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");
    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * Per-seed color palette in raw form. We bind it explicitly to all of
 * Material3's roles below so components like `FilterChip` and
 * `SegmentedButton` (which default to `secondaryContainer`) pick up our
 * seed instead of M3's baseline purple.
 */
private data class SeedPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

object AppColorSchemes {
    fun light(seed: ThemeSeed): ColorScheme = lightSchemeFor(lightPaletteFor(seed))
    fun dark(seed: ThemeSeed): ColorScheme = darkSchemeFor(darkPaletteFor(seed))

    private fun lightPaletteFor(seed: ThemeSeed): SeedPalette = when (seed) {
        ThemeSeed.BLUE -> SeedPalette(
            primary = Color(0xFF1F6FEB), onPrimary = Color.White,
            primaryContainer = Color(0xFFC9E2FD), onPrimaryContainer = Color(0xFF002A66),
        )
        ThemeSeed.GREEN -> SeedPalette(
            primary = Color(0xFF2E7D32), onPrimary = Color.White,
            primaryContainer = Color(0xFFB8E6BC), onPrimaryContainer = Color(0xFF002107),
        )
        ThemeSeed.PURPLE -> SeedPalette(
            primary = Color(0xFF6750A4), onPrimary = Color.White,
            primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
        )
        ThemeSeed.ORANGE -> SeedPalette(
            primary = Color(0xFFD05A0F), onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDBC9), onPrimaryContainer = Color(0xFF381E00),
        )
        ThemeSeed.RED -> SeedPalette(
            primary = Color(0xFFB3261E), onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDAD5), onPrimaryContainer = Color(0xFF410002),
        )
        ThemeSeed.PINK -> SeedPalette(
            primary = Color(0xFFB02A74), onPrimary = Color.White,
            primaryContainer = Color(0xFFFFD8E7), onPrimaryContainer = Color(0xFF3E0023),
        )
        ThemeSeed.YELLOW -> SeedPalette(
            // Dark amber so white text passes contrast; true yellow won't.
            primary = Color(0xFFEEC614), onPrimary = Color.White,
            primaryContainer = Color(0xFFFFF0A2), onPrimaryContainer = Color(0xFF362E00),
        )
        ThemeSeed.MONO -> SeedPalette(
            primary = Color(0xFF222222), onPrimary = Color.White,
            primaryContainer = Color(0xFFE0E0E0), onPrimaryContainer = Color(0xFF111111),
        )
    }

    private fun darkPaletteFor(seed: ThemeSeed): SeedPalette = when (seed) {
        ThemeSeed.BLUE -> SeedPalette(
            primary = Color(0xFFAAC7FF), onPrimary = Color(0xFF002F65),
            primaryContainer = Color(0xFF1E477E), onPrimaryContainer = Color(0xFFD7E6FF),
        )
        ThemeSeed.GREEN -> SeedPalette(
            primary = Color(0xFF9DD49F), onPrimary = Color(0xFF003910),
            primaryContainer = Color(0xFF14531E), onPrimaryContainer = Color(0xFFB8E6BC),
        )
        ThemeSeed.PURPLE -> SeedPalette(
            primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
            primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
        )
        ThemeSeed.ORANGE -> SeedPalette(
            primary = Color(0xFFFFB593), onPrimary = Color(0xFF562000),
            primaryContainer = Color(0xFF7B3300), onPrimaryContainer = Color(0xFFFFDBC9),
        )
        ThemeSeed.RED -> SeedPalette(
            primary = Color(0xFFFFB4AB), onPrimary = Color(0xFF690005),
            primaryContainer = Color(0xFF93000A), onPrimaryContainer = Color(0xFFFFDAD5),
        )
        ThemeSeed.PINK -> SeedPalette(
            primary = Color(0xFFFFAFD1), onPrimary = Color(0xFF5E1141),
            primaryContainer = Color(0xFF7E2A5A), onPrimaryContainer = Color(0xFFFFD8E7),
        )
        ThemeSeed.YELLOW -> SeedPalette(
            primary = Color(0xFFEBC248), onPrimary = Color(0xFF3E2E00),
            primaryContainer = Color(0xFF5C4600), onPrimaryContainer = Color(0xFFFFE08A),
        )
        ThemeSeed.MONO -> SeedPalette(
            primary = Color(0xFFE0E0E0), onPrimary = Color(0xFF111111),
            primaryContainer = Color(0xFF333333), onPrimaryContainer = Color(0xFFE0E0E0),
        )
    }

    /**
     * Bind one [SeedPalette] to all of Material3's accent roles. Why we
     * mirror primary into secondary AND tertiary: Material's roles are
     * meant to be used as a 3-color accent system, but for a non-
     * Material-You app where the user picks one seed (BLUE / GREEN /
     * etc.), having a "complementary" tertiary that's purple/teal for
     * every theme would just look like a bug. Components like
     * `FilterChip` (selected → `secondaryContainer`) and
     * `SegmentedButton` (selected → `secondaryContainer`) automatically
     * pick up the right color without needing per-component overrides.
     */
    private fun lightSchemeFor(p: SeedPalette): ColorScheme = lightColorScheme(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = p.primaryContainer,
        onPrimaryContainer = p.onPrimaryContainer,
        secondary = p.primary,
        onSecondary = p.onPrimary,
        secondaryContainer = p.primaryContainer,
        onSecondaryContainer = p.onPrimaryContainer,
        tertiary = p.primary,
        onTertiary = p.onPrimary,
        tertiaryContainer = p.primaryContainer,
        onTertiaryContainer = p.onPrimaryContainer,

        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF111114),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF111114),
        surfaceVariant = Color(0xFFE4E4E9),
        onSurfaceVariant = Color(0xFF45464F),
        surfaceTint = p.primary,
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFD8D8DE),
        surfaceContainerLowest = Color(0xFFFAFAFC),
        surfaceContainerLow = Color(0xFFF2F2F6),
        surfaceContainer = Color(0xFFEBEBF1),
        surfaceContainerHigh = Color(0xFFDEDEE5),
        surfaceContainerHighest = Color(0xFFD2D2DA),

        outline = Color(0xFF75767F),
        outlineVariant = Color(0xFFC3C4CC),
    )

    private fun darkSchemeFor(p: SeedPalette): ColorScheme = darkColorScheme(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = p.primaryContainer,
        onPrimaryContainer = p.onPrimaryContainer,
        secondary = p.primary,
        onSecondary = p.onPrimary,
        secondaryContainer = p.primaryContainer,
        onSecondaryContainer = p.onPrimaryContainer,
        tertiary = p.primary,
        onTertiary = p.onPrimary,
        tertiaryContainer = p.primaryContainer,
        onTertiaryContainer = p.onPrimaryContainer,

        surface = Color(0xFF141416),
        onSurface = Color(0xFFECECF0),
        background = Color(0xFF0B0B0D),
        onBackground = Color(0xFFECECF0),
        surfaceVariant = Color(0xFF2A2A2F),
        onSurfaceVariant = Color(0xFFC6C6CD),
        surfaceTint = p.primary,
        surfaceBright = Color(0xFF3A3A40),
        surfaceDim = Color(0xFF0F0F11),
        surfaceContainerLowest = Color(0xFF0F0F11),
        surfaceContainerLow = Color(0xFF1A1A1D),
        surfaceContainer = Color(0xFF1F1F22),
        surfaceContainerHigh = Color(0xFF28282C),
        surfaceContainerHighest = Color(0xFF33333A),

        outline = Color(0xFF8E8F97),
        outlineVariant = Color(0xFF44454D),
    )
}