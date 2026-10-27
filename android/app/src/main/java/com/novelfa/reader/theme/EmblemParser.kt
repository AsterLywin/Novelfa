package com.novelfa.reader.theme

import androidx.compose.ui.graphics.Path

/**
 * Minimal WPF PathGeometry parser for the character emblems.
 * Supports M (move), L (line), C (cubic bezier), Z (close) commands
 * with comma/space-separated coordinates. The F0/F1 fill-rule prefix
 * is ignored (we always use the non-zero winding).
 */
fun parseEmblemPath(data: String): Path? {
    if (data.isBlank()) return null
    val path = Path()
    // Tokenize: commands and numbers
    val tokens = Regex("[MLCZmlcz]|-?\\d+\\.?\\d*").findAll(data).map { it.value }.toList()
    // Truncated data used to throw IndexOutOfBoundsException from the
    // callers' composition (CharacterThemes.emblemPath, ParticleLayer),
    // crashing the screen on a single typo'd emblem string. A malformed
    // path is now invalid (null) instead of a crash.
    fun numAt(index: Int): Float? =
        tokens.getOrNull(index)?.toFloatOrNull()
    var i = 0
    var cx = 0f
    var cy = 0f
    while (i < tokens.size) {
        when (tokens[i].uppercase()) {
            "M" -> {
                cx = numAt(i + 1) ?: return null
                cy = numAt(i + 2) ?: return null
                path.moveTo(cx, cy)
                i += 3
            }
            "L" -> {
                cx = numAt(i + 1) ?: return null
                cy = numAt(i + 2) ?: return null
                path.lineTo(cx, cy)
                i += 3
            }
            "C" -> {
                val x1 = numAt(i + 1) ?: return null
                val y1 = numAt(i + 2) ?: return null
                val x2 = numAt(i + 3) ?: return null
                val y2 = numAt(i + 4) ?: return null
                cx = numAt(i + 5) ?: return null
                cy = numAt(i + 6) ?: return null
                path.cubicTo(x1, y1, x2, y2, cx, cy)
                i += 7
            }
            "Z" -> {
                path.close()
                i += 1
            }
            else -> i += 1 // Skip F0/F1 and unknown tokens
        }
    }
    return path
}
