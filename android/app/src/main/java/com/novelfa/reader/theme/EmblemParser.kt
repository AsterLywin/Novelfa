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
    var i = 0
    var cx = 0f
    var cy = 0f
    while (i < tokens.size) {
        when (tokens[i].uppercase()) {
            "M" -> {
                cx = tokens[i + 1].toFloat()
                cy = tokens[i + 2].toFloat()
                path.moveTo(cx, cy)
                i += 3
            }
            "L" -> {
                cx = tokens[i + 1].toFloat()
                cy = tokens[i + 2].toFloat()
                path.lineTo(cx, cy)
                i += 3
            }
            "C" -> {
                val x1 = tokens[i + 1].toFloat()
                val y1 = tokens[i + 2].toFloat()
                val x2 = tokens[i + 3].toFloat()
                val y2 = tokens[i + 4].toFloat()
                cx = tokens[i + 5].toFloat()
                cy = tokens[i + 6].toFloat()
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
