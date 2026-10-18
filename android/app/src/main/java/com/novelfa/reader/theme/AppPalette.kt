package com.novelfa.reader.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Plain (non-character) app palette, ported from ThemeManager.cs:194-281.
 *
 * The Windows version tints the entire UI from the accent color using HSL:
 * backgrounds, cards, text, borders, etc. are all derived from the accent's
 * hue/saturation. This ports that logic so the Android app looks the same.
 */
data class AppPalette(
    val bg: Color,
    val sidebar: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val hover: Color,
    val border: Color,
    val soft: Color,
    val hero1: Color,
    val hero2: Color,
    val accent: Color,
    val accentDark: Color,
    val gradA: Color,
    val gradB: Color,
    val onAccent: Color
)

private fun clamp(v: Double, lo: Double, hi: Double): Double =
    if (v < lo) lo else if (v > hi) hi else v

private fun toByte(v: Double): Int =
    (clamp(v, 0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)

private fun hsl(h: Double, s: Double, l: Double): Color {
    var hh = h % 360.0
    if (hh < 0) hh += 360.0
    val ss = clamp(s, 0.0, 1.0)
    val ll = clamp(l, 0.0, 1.0)
    val c = (1.0 - abs(2.0 * ll - 1.0)) * ss
    val hp = hh / 60.0
    val x = c * (1.0 - abs(hp % 2.0 - 1.0))
    var r1 = 0.0; var g1 = 0.0; var b1 = 0.0
    when {
        hp < 1.0 -> { r1 = c; g1 = x }
        hp < 2.0 -> { r1 = x; g1 = c }
        hp < 3.0 -> { g1 = c; b1 = x }
        hp < 4.0 -> { g1 = x; b1 = c }
        hp < 5.0 -> { r1 = x; b1 = c }
        else -> { r1 = c; b1 = x }
    }
    val m = ll - c / 2.0
    return Color(toByte(r1 + m), toByte(g1 + m), toByte(b1 + m))
}

private fun rgbToHsl(color: Color): Triple<Double, Double, Double> {
    val r = color.red.toDouble()
    val g = color.green.toDouble()
    val b = color.blue.toDouble()
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val d = maxC - minC
    val l = (maxC + minC) / 2.0
    if (d < 0.000001) return Triple(0.0, 0.0, l)
    val s = d / (1.0 - abs(2.0 * l - 1.0))
    var h = when {
        maxC == r -> 60.0 * (((g - b) / d) % 6.0)
        maxC == g -> 60.0 * (((b - r) / d) + 2.0)
        else -> 60.0 * (((r - g) / d) + 4.0)
    }
    if (h < 0) h += 360.0
    return Triple(h, s, l)
}

private fun lin(v: Double): Double =
    if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

private fun luminance(color: Color): Double =
    0.2126 * lin(color.red.toDouble()) +
        0.7152 * lin(color.green.toDouble()) +
        0.0722 * lin(color.blue.toDouble())

private fun pickOn(bg: Color): Color {
    val lum = luminance(bg)
    val darkLum = luminance(Color(0xFF1A1A1A))
    val withWhite = 1.05 / (lum + 0.05)
    val withDark = (lum + 0.05) / (darkLum + 0.05)
    return if (withWhite >= withDark) Color.White else Color(0xFF1A1A1A)
}

fun parseHex(hex: String, fallback: Color = Color.Transparent): Color {
    return try {
        val h = hex.trim().removePrefix("#")
        when (h.length) {
            6 -> Color(
                h.substring(0, 2).toInt(16),
                h.substring(2, 4).toInt(16),
                h.substring(4, 6).toInt(16)
            )
            8 -> Color(
                h.substring(2, 4).toInt(16),
                h.substring(4, 6).toInt(16),
                h.substring(6, 8).toInt(16),
                h.substring(0, 2).toInt(16)
            )
            else -> fallback
        }
    } catch (e: Exception) {
        fallback
    }
}

/**
 * Build the plain-app palette from an accent hex color, exactly like
 * ThemeManager.cs:194-281.
 */
fun buildPlainPalette(light: Boolean, accentHex: String): AppPalette {
    val baseColor = parseHex(accentHex, parseHex("#7C3AED"))
    val (h, s, l) = rgbToHsl(baseColor)

    // Grays/whites/blacks stay untinted; others lean toward the accent hue.
    val tint = if (s < 0.08) 0.0 else min(1.0, 0.45 + s)
    val tintStrong = if (s < 0.08) 0.0 else 1.0

    val accent: Color
    val accentDark: Color
    if (light) {
        val al = clamp(l, 0.38, 0.58)
        accent = hsl(h, s, al)
        accentDark = hsl(h, s, al - 0.08)
    } else {
        val al = clamp(l + 0.16, 0.66, 0.76)
        accent = hsl(h, s, al)
        accentDark = hsl(h, s, al - 0.10)
    }

    val (bg, sidebar, card, text, muted, hover, border, soft, hero1, hero2) =
        if (light) {
            listOf(
                hsl(h, 0.444 * tint, 0.965),  // bgDark
                Color.White,                    // bgSidebar
                Color.White,                    // bgCard
                hsl(h, 0.278 * tint, 0.141),  // text
                hsl(h, 0.108 * tint, 0.471),  // muted
                hsl(h, 0.583 * tint, 0.953),  // hover
                hsl(h, 0.348 * tint, 0.910),  // border
                hsl(h, 0.920 * tintStrong, 0.951), // soft
                hsl(h, 1.000 * tintStrong, 0.959), // hero1
                hsl(h, 0.902 * tintStrong, 0.920)  // hero2
            )
        } else {
            listOf(
                hsl(h, 0.243 * tint, 0.073),  // bgDark
                hsl(h, 0.240 * tint, 0.098),  // bgSidebar
                hsl(h, 0.235 * tint, 0.133),  // bgCard
                hsl(h, 0.333 * tint, 0.912),  // text
                hsl(h, 0.106 * tint, 0.575),  // muted
                hsl(h, 0.231 * tint, 0.178),  // hover
                hsl(h, 0.243 * tint, 0.202),  // border
                hsl(h, 0.416 * tintStrong, 0.222), // soft
                hsl(h, 0.341 * tint, 0.178),  // hero1
                hsl(h, 0.333 * tint, 0.124)   // hero2
            )
        }

    val gradA = hsl(h, s, clamp(l + 0.14, 0.50, 0.80))
    val gradB = hsl(h, s, clamp(l - 0.04, 0.34, 0.60))

    return AppPalette(
        bg = bg,
        sidebar = sidebar,
        card = card,
        text = text,
        muted = muted,
        hover = hover,
        border = border,
        soft = soft,
        hero1 = hero1,
        hero2 = hero2,
        accent = accent,
        accentDark = accentDark,
        gradA = gradA,
        gradB = gradB,
        onAccent = pickOn(accent)
    )
}
