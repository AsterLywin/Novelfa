package com.novelfa.reader

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.theme.Accent
import com.novelfa.reader.theme.CharacterThemes
import com.novelfa.reader.theme.hexToColor

/**
 * Root: the Material3 color scheme is fully driven by the user's settings.
 *
 * - Settings → dark/light picks the base scheme (this used to be a bare
 *   MaterialTheme — always light — so the switch did nothing; fixed earlier).
 * - Settings → accent ("رنگ برنامه") recolors the scheme's primary roles.
 *   The picker saved the pref but nothing read it, so picking a color had
 *   no visible effect until now.
 * - Settings → special themes ("تم‌های خاص") applies the selected character
 *   theme's dark/light palette to the whole app UI (picking "بدون تم شخصیت"
 *   goes back to plain app colors). Also saved-but-never-read until now.
 */
@Composable
fun NovelfaApp() {
    val context = LocalContext.current
    val prefs = remember { PrefsStore(context) }
    val themeKey by prefs.theme.collectAsState(initial = "dark")
    val accentHex by prefs.accent.collectAsState(initial = Accent.DEFAULT)
    val charThemeId by prefs.charTheme.collectAsState(initial = "")
    val isLight = themeKey == "light"
    val colorScheme = remember(isLight, accentHex, charThemeId) {
        buildAppColorScheme(isLight, accentHex, charThemeId)
    }
    MaterialTheme(colorScheme = colorScheme) {
        NavGraph()
    }
}

/** Builds the app [ColorScheme] from the stored theme/accent/character-theme. */
private fun buildAppColorScheme(
    isLight: Boolean,
    accentHex: String,
    charThemeId: String
): ColorScheme {
    val base = if (isLight) lightColorScheme() else darkColorScheme()

    // A selected character theme wins: recolor the app with its dark/light
    // palette (background, cards, text, accent, ...).
    charThemeId.takeIf { it.isNotBlank() }?.let { id ->
        CharacterThemes.All.firstOrNull { it.id == id }?.let { theme ->
            val p = theme.palette(isLight)
            val accent = parseHex(p.accent, Accent.DEFAULT)
            return base.copy(
                primary = accent,
                onPrimary = accent.contrastOn(),
                primaryContainer = parseHex(p.accentDark, accent),
                onPrimaryContainer = accent.contrastOn(),
                secondary = parseHex(p.hero1, base.secondary),
                tertiary = parseHex(p.hero2, base.tertiary),
                background = parseHex(p.bg, base.background),
                onBackground = parseHex(p.text, base.onBackground),
                surface = parseHex(p.card, base.surface),
                onSurface = parseHex(p.text, base.onSurface),
                surfaceVariant = parseHex(p.soft, base.surfaceVariant),
                onSurfaceVariant = parseHex(p.muted, base.onSurfaceVariant),
                outline = parseHex(p.border, base.outline)
            )
        }
    }

    // Otherwise the accent preset recolors the primary roles of the scheme.
    val accent = parseHex(accentHex, Accent.DEFAULT)
    return base.copy(
        primary = accent,
        onPrimary = accent.contrastOn(),
        primaryContainer = accent.copy(alpha = 0.25f),
        onPrimaryContainer = accent.contrastOn()
    )
}

/** Parses "#RRGGBB" safely; falls back instead of returning magenta. */
private fun parseHex(hex: String, fallbackHex: String): Color =
    parseHex(hex, hexToColor(fallbackHex))

private fun parseHex(hex: String, fallback: Color): Color {
    val t = hex.trim().trimStart('#')
    val expanded = if (t.length == 3) t.map { "$it$it" }.joinToString("") else t
    val ok = expanded.length == 6 &&
        expanded.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return if (ok) hexToColor(hex) else fallback
}

/** Black or white text — whichever reads better on this background color. */
private fun Color.contrastOn(): Color {
    val lum = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return if (lum > 0.55f) Color.Black else Color.White
}
