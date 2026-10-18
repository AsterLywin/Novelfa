package com.novelfa.reader.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

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
    val hasCharacterTheme: Boolean = false
)

val LocalAppPaletteExtras = compositionLocalOf { AppPaletteExtras() }
