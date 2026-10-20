package com.novelfa.reader.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/**
 * App-wide palette extras not covered by Material3 ColorScheme:
 * character-theme aura colors and accent gradient.
 * Port of ThemeManager.cs:284-301 (ThemeAuraA/B, AccentGradient).
 */
data class AppPaletteExtras(
    val aura1: Color = Color.Transparent,
    val aura2: Color = Color.Transparent,
    val accentGrad1: Color = Color.Transparent,
    val accentGrad2: Color = Color.Transparent,
    val hasCharacterTheme: Boolean = false,
    /** Active character theme id ("" when none) — gates tsugi stitch art. */
    val charThemeId: String = "",
    /** Active theme's emblem path (drawn in a 100x100 space), null when none. */
    val emblemPath: Path? = null
)

val LocalAppPaletteExtras = compositionLocalOf { AppPaletteExtras() }
