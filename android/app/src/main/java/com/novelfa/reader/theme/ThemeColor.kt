package com.novelfa.reader.theme

import androidx.compose.ui.graphics.Color

/**
 * Parse "#RRGGBB" (or "RRGGBB", or 3-digit "#RGB") to a Compose Color.
 * Falls back to magenta on invalid input, mirroring WPF ThemeManager.Col.
 */
fun hexToColor(hex: String): Color {
    var t = hex.trim().trimStart('#')
    if (t.length == 3) {
        t = t.map { "$it$it" }.joinToString("")
    }
    val v = t.toLongOrNull(16)
    if (t.length != 6 || v == null) return Color.Magenta
    return Color(
        red = ((v shr 16) and 0xFF).toInt(),
        green = ((v shr 8) and 0xFF).toInt(),
        blue = (v and 0xFF).toInt()
    )
}
