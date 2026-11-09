package com.novelfa.reader.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp

/**
 * One drawn icon per XP rank title (12), same mechanism as
 * [EternityMarkIcon]: vector shapes in a 120x120 space, no image
 * assets, following the app's effective accent (primary /
 * primaryContainer, lightened for the bright details) — a character
 * theme's accent when one is active, otherwise the user's own accent
 * color. States: earned = accent gradient fill (+ light/dark
 * details); locked = outline only, in onSurfaceVariant. The shapes
 * live in a 120x120 space and are scaled to [size].
 *
 * Design (approved by the user 2026-11-09, previewed as
 * ~/workspace/ranks_sheet/rank_icons.png): sprout, lantern, open
 * book, bitten book, magnifier, key, world sinking into waves,
 * shield, crown, Pleiades cluster, horned beast, devouring vortex.
 * Each shape matches its title; the bitten book's bite is part of
 * its outline (an arc notch), not a mask — Compose has no mask, so
 * the notch is traced with arcTo (see rank 4).
 *
 * [rankIndex] is the 0-based [com.novelfa.reader.domain.XpTitle.index]
 * (0 = تازه‌وارد … 11 = بلعنده‌ی جهان‌ها).
 */
@Composable
fun RankIcon(rankIndex: Int, size: Dp, earned: Boolean, modifier: Modifier = Modifier) {
    // Same accent derivation as EternityMarkIcon: the icon changes
    // with the theme/accent automatically — no fixed color anywhere.
    val scheme = MaterialTheme.colorScheme
    val colors = MarkColors(
        light = lighten(scheme.primary, 0.6f),
        mid = scheme.primary,
        dark = scheme.primaryContainer
    )
    val outline = scheme.onSurfaceVariant
    Canvas(modifier = modifier.size(size)) {
        // this.size is the canvas Size: the Dp parameter named `size`
        // shadows DrawScope.size, and Dp has no minDimension.
        val scale = this.size.minDimension / 120f
        withTransform({ scale(scale, scale) }) {
            drawRankIcon(rankIndex.coerceIn(0, 11), earned, outline, colors)
        }
    }
}

/** 4-point sparkle, concave diamond, first point at the top. */
private fun star4Path(cx: Float, cy: Float, r: Float): Path {
    val path = Path()
    for (k in 0 until 8) {
        val rad = if (k % 2 == 0) r else r * 0.28f
        val a = Math.toRadians(-90.0 + k * 45.0)
        val x = cx + rad * Math.cos(a).toFloat()
        val y = cy + rad * Math.sin(a).toFloat()
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun DrawScope.drawRankIcon(index: Int, earned: Boolean, outline: Color, colors: MarkColors) {
    val accent = Brush.linearGradient(
        colors = listOf(colors.light, colors.mid, colors.dark),
        start = Offset(60f, 0f),
        end = Offset(60f, 120f)
    )
    val stroke = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thinStroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (index) {
        0 -> {
            // Newcomer: a sprout — two leaves and a stem.
            val leafLeft = Path().apply {
                moveTo(60f, 62f)
                cubicTo(45f, 55f, 35f, 45f, 32f, 30f)
                cubicTo(48f, 32f, 58f, 45f, 60f, 62f)
                close()
            }
            val leafRight = Path().apply {
                moveTo(60f, 62f)
                cubicTo(75f, 55f, 85f, 45f, 88f, 30f)
                cubicTo(72f, 32f, 62f, 45f, 60f, 62f)
                close()
            }
            if (earned) {
                drawPath(leafLeft, brush = accent)
                drawPath(leafRight, brush = accent)
                drawLine(colors.mid, Offset(60f, 96f), Offset(60f, 62f), strokeWidth = 5f, cap = StrokeCap.Round)
            } else {
                drawPath(leafLeft, color = outline, style = stroke)
                drawPath(leafRight, color = outline, style = stroke)
                drawLine(outline, Offset(60f, 96f), Offset(60f, 62f), strokeWidth = 5f, cap = StrokeCap.Round)
            }
        }
        1 -> {
            // Library passerby: a lantern — handle arc, cap, body with
            // two dark bars and a bright glow.
            val body = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(42f, 37f, 78f, 88f),
                        CornerRadius(0f), CornerRadius(0f),
                        CornerRadius(9f), CornerRadius(9f)
                    )
                )
            }
            if (earned) {
                drawArc(
                    brush = accent, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(48f, 18f), size = Size(24f, 24f), style = stroke
                )
                drawRoundRect(
                    brush = accent, topLeft = Offset(46f, 28f), size = Size(28f, 9f),
                    cornerRadius = CornerRadius(3f, 3f)
                )
                drawPath(body, brush = accent)
                drawRect(color = colors.dark, topLeft = Offset(50f, 42f), size = Size(4f, 42f))
                drawRect(color = colors.dark, topLeft = Offset(66f, 42f), size = Size(4f, 42f))
                drawCircle(color = colors.light, radius = 8f, center = Offset(60f, 60f))
            } else {
                drawArc(
                    color = outline, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(48f, 18f), size = Size(24f, 24f), style = stroke
                )
                drawRoundRect(
                    color = outline, topLeft = Offset(46f, 28f), size = Size(28f, 9f),
                    cornerRadius = CornerRadius(3f, 3f), style = stroke
                )
                drawPath(body, color = outline, style = stroke)
            }
        }
        2 -> {
            // Book lover: an open book — two curved pages, spine and
            // light text lines.
            val pageLeft = Path().apply {
                moveTo(60f, 38f)
                cubicTo(48f, 28f, 34f, 26f, 22f, 30f)
                lineTo(22f, 88f)
                cubicTo(34f, 84f, 48f, 86f, 60f, 96f)
                close()
            }
            val pageRight = Path().apply {
                moveTo(60f, 38f)
                cubicTo(72f, 28f, 86f, 26f, 98f, 30f)
                lineTo(98f, 88f)
                cubicTo(86f, 84f, 72f, 86f, 60f, 96f)
                close()
            }
            if (earned) {
                drawPath(pageLeft, brush = accent)
                drawPath(pageRight, brush = accent)
                drawLine(colors.dark, Offset(60f, 38f), Offset(60f, 96f), strokeWidth = 2.5f, cap = StrokeCap.Round)
                drawLine(colors.light, Offset(30f, 50f), Offset(48f, 50f), strokeWidth = 2.5f, cap = StrokeCap.Round)
                drawLine(colors.light, Offset(30f, 60f), Offset(48f, 60f), strokeWidth = 2.5f, cap = StrokeCap.Round)
                drawLine(colors.light, Offset(30f, 70f), Offset(43f, 70f), strokeWidth = 2.5f, cap = StrokeCap.Round)
                drawLine(colors.light, Offset(72f, 50f), Offset(90f, 50f), strokeWidth = 2.5f, cap = StrokeCap.Round)
                drawLine(colors.light, Offset(72f, 60f), Offset(90f, 60f), strokeWidth = 2.5f, cap = StrokeCap.Round)
                drawLine(colors.light, Offset(72f, 70f), Offset(85f, 70f), strokeWidth = 2.5f, cap = StrokeCap.Round)
            } else {
                drawPath(pageLeft, color = outline, style = stroke)
                drawPath(pageRight, color = outline, style = stroke)
            }
        }
        3 -> {
            // Bookworm: a book with a bite taken out of its top-right
            // corner. The bite is a concave arc notch in the outline
            // (circle center (92,28) r18: from (74,28) at 180° to
            // (92,46) at 90°, sweep -90°), so it shows in the locked
            // outline too — no mask involved.
            val book = Path().apply {
                moveTo(38f, 28f)
                lineTo(74f, 28f)
                arcTo(Rect(74f, 10f, 110f, 46f), 180f, -90f, false)
                lineTo(92f, 82f)
                arcTo(Rect(82f, 82f, 92f, 92f), 0f, 90f, false)
                lineTo(38f, 92f)
                arcTo(Rect(28f, 82f, 38f, 92f), 90f, 90f, false)
                lineTo(28f, 38f)
                arcTo(Rect(28f, 28f, 38f, 38f), 180f, 90f, false)
                close()
            }
            if (earned) {
                drawPath(book, brush = accent)
                drawLine(colors.dark, Offset(42f, 28f), Offset(42f, 92f), strokeWidth = 4f, cap = StrokeCap.Round)
                drawLine(colors.light, Offset(54f, 50f), Offset(80f, 50f), strokeWidth = 2.5f, cap = StrokeCap.Round)
                drawLine(colors.light, Offset(54f, 62f), Offset(80f, 62f), strokeWidth = 2.5f, cap = StrokeCap.Round)
                drawLine(colors.light, Offset(54f, 74f), Offset(72f, 74f), strokeWidth = 2.5f, cap = StrokeCap.Round)
            } else {
                drawPath(book, color = outline, style = stroke)
            }
        }
        4 -> {
            // Story hunter: a magnifier with a sparkle in the lens.
            if (earned) {
                drawCircle(brush = accent, radius = 26f, center = Offset(52f, 52f), style = Stroke(width = 9f, cap = StrokeCap.Round))
                drawLine(brush = accent, start = Offset(70f, 70f), end = Offset(92f, 92f), strokeWidth = 10f, cap = StrokeCap.Round)
                drawPath(star4Path(52f, 52f, 12f), color = colors.light)
            } else {
                drawCircle(color = outline, radius = 26f, center = Offset(52f, 52f), style = Stroke(width = 9f, cap = StrokeCap.Round))
                drawLine(outline, Offset(70f, 70f), Offset(92f, 92f), strokeWidth = 10f, cap = StrokeCap.Round)
            }
        }
        5 -> {
            // Librarian: a key — bow, shaft and two teeth.
            if (earned) {
                drawCircle(brush = accent, radius = 16f, center = Offset(42f, 44f), style = Stroke(width = 8f, cap = StrokeCap.Round))
                drawLine(brush = accent, start = Offset(54f, 56f), end = Offset(88f, 90f), strokeWidth = 8f, cap = StrokeCap.Round)
                drawLine(brush = accent, start = Offset(76f, 76f), end = Offset(68f, 84f), strokeWidth = 7f, cap = StrokeCap.Round)
                drawLine(brush = accent, start = Offset(86f, 86f), end = Offset(78f, 94f), strokeWidth = 7f, cap = StrokeCap.Round)
            } else {
                drawCircle(color = outline, radius = 16f, center = Offset(42f, 44f), style = Stroke(width = 8f, cap = StrokeCap.Round))
                drawLine(outline, Offset(54f, 56f), Offset(88f, 90f), strokeWidth = 8f, cap = StrokeCap.Round)
                drawLine(outline, Offset(76f, 76f), Offset(68f, 84f), strokeWidth = 7f, cap = StrokeCap.Round)
                drawLine(outline, Offset(86f, 86f), Offset(78f, 94f), strokeWidth = 7f, cap = StrokeCap.Round)
            }
        }
        6 -> {
            // Lost in worlds: a world sinking into two waves.
            val wave1 = Path().apply {
                moveTo(28f, 66f)
                quadraticTo(36f, 56f, 44f, 66f)
                quadraticTo(52f, 76f, 60f, 66f)
                quadraticTo(68f, 56f, 76f, 66f)
                quadraticTo(84f, 76f, 92f, 66f)
            }
            val wave2 = Path().apply {
                moveTo(36f, 82f)
                quadraticTo(44f, 72f, 52f, 82f)
                quadraticTo(60f, 92f, 68f, 82f)
                quadraticTo(76f, 72f, 84f, 82f)
            }
            if (earned) {
                drawCircle(brush = accent, radius = 14f, center = Offset(60f, 40f))
                drawPath(wave1, brush = accent, style = Stroke(width = 6f, cap = StrokeCap.Round))
                drawPath(wave2, color = colors.light, style = Stroke(width = 6f, cap = StrokeCap.Round))
            } else {
                drawCircle(color = outline, radius = 14f, center = Offset(60f, 40f), style = stroke)
                drawPath(wave1, color = outline, style = Stroke(width = 6f, cap = StrokeCap.Round))
                drawPath(wave2, color = outline, style = Stroke(width = 6f, cap = StrokeCap.Round))
            }
        }
        7 -> {
            // Keeper of the forbidden library: a shield with a keyhole.
            val shield = Path().apply {
                moveTo(60f, 22f)
                lineTo(92f, 34f)
                lineTo(92f, 62f)
                cubicTo(92f, 84f, 78f, 98f, 60f, 106f)
                cubicTo(42f, 98f, 28f, 84f, 28f, 62f)
                lineTo(28f, 34f)
                close()
            }
            if (earned) {
                drawPath(shield, brush = accent)
                drawCircle(color = colors.light, radius = 7f, center = Offset(60f, 58f))
                drawPath(
                    Path().apply {
                        moveTo(57f, 62f)
                        lineTo(63f, 62f)
                        lineTo(65f, 75f)
                        lineTo(55f, 75f)
                        close()
                    },
                    color = colors.light
                )
            } else {
                drawPath(shield, color = outline, style = stroke)
            }
        }
        8 -> {
            // Library legend: a crown with a dark band and light jewels.
            val crown = Path().apply {
                moveTo(28f, 78f)
                lineTo(24f, 44f)
                lineTo(42f, 58f)
                lineTo(60f, 36f)
                lineTo(78f, 58f)
                lineTo(96f, 44f)
                lineTo(92f, 78f)
                close()
            }
            if (earned) {
                drawPath(crown, brush = accent)
                drawRoundRect(
                    color = colors.dark, topLeft = Offset(28f, 78f), size = Size(64f, 10f),
                    cornerRadius = CornerRadius(4f, 4f)
                )
                drawCircle(color = colors.light, radius = 4f, center = Offset(24f, 42f))
                drawCircle(color = colors.light, radius = 4.5f, center = Offset(60f, 34f))
                drawCircle(color = colors.light, radius = 4f, center = Offset(96f, 42f))
            } else {
                drawPath(crown, color = outline, style = stroke)
                drawRoundRect(
                    color = outline, topLeft = Offset(28f, 78f), size = Size(64f, 10f),
                    cornerRadius = CornerRadius(4f, 4f), style = stroke
                )
                drawCircle(color = outline, radius = 4f, center = Offset(24f, 42f), style = stroke)
                drawCircle(color = outline, radius = 4.5f, center = Offset(60f, 34f), style = stroke)
                drawCircle(color = outline, radius = 4f, center = Offset(96f, 42f), style = stroke)
            }
        }
        9 -> {
            // Sage of Pleiades: the seven-star cluster, with a faint
            // glow behind it.
            val stars = listOf(
                Triple(48f, 40f, 7f), Triple(62f, 30f, 9f), Triple(76f, 40f, 7f),
                Triple(56f, 54f, 6f), Triple(70f, 56f, 6f), Triple(44f, 56f, 5f),
                Triple(84f, 54f, 5f)
            )
            if (earned) {
                drawCircle(color = colors.mid, radius = 36f, center = Offset(62f, 46f), alpha = 0.16f)
                stars.forEach { (x, y, r) -> drawPath(star4Path(x, y, r), color = colors.light) }
            } else {
                stars.forEach { (x, y, r) -> drawPath(star4Path(x, y, r), color = outline, style = thinStroke) }
            }
        }
        10 -> {
            // Monster of Pleiades: a horned beast — light horns, accent
            // head, light eyes and fangs.
            val hornLeft = Path().apply {
                moveTo(38f, 44f)
                cubicTo(28f, 30f, 30f, 18f, 40f, 12f)
                cubicTo(42f, 24f, 46f, 32f, 52f, 38f)
                close()
            }
            val hornRight = Path().apply {
                moveTo(82f, 44f)
                cubicTo(92f, 30f, 90f, 18f, 80f, 12f)
                cubicTo(78f, 24f, 74f, 32f, 68f, 38f)
                close()
            }
            val head = Path().apply {
                moveTo(32f, 62f)
                cubicTo(32f, 44f, 44f, 34f, 60f, 34f)
                cubicTo(76f, 34f, 88f, 44f, 88f, 62f)
                cubicTo(88f, 80f, 76f, 92f, 60f, 92f)
                cubicTo(44f, 92f, 32f, 80f, 32f, 62f)
                close()
            }
            if (earned) {
                drawPath(hornLeft, color = colors.light)
                drawPath(hornRight, color = colors.light)
                drawPath(head, brush = accent)
                drawPath(
                    Path().apply {
                        moveTo(42f, 56f)
                        lineTo(56f, 60f)
                        lineTo(42f, 64f)
                        close()
                    },
                    color = colors.light
                )
                drawPath(
                    Path().apply {
                        moveTo(78f, 56f)
                        lineTo(64f, 60f)
                        lineTo(78f, 64f)
                        close()
                    },
                    color = colors.light
                )
                drawPath(
                    Path().apply {
                        moveTo(50f, 82f)
                        lineTo(54f, 89f)
                        lineTo(58f, 82f)
                        close()
                    },
                    color = colors.light
                )
                drawPath(
                    Path().apply {
                        moveTo(62f, 82f)
                        lineTo(66f, 89f)
                        lineTo(70f, 82f)
                        close()
                    },
                    color = colors.light
                )
            } else {
                drawPath(hornLeft, color = outline, style = stroke)
                drawPath(hornRight, color = outline, style = stroke)
                drawPath(head, color = outline, style = stroke)
            }
        }
        11 -> {
            // Devourer of worlds: a vortex of three arcs around a small
            // world at its center. Each arc spans 270°: outer from the
            // top, middle from the right, inner from the bottom.
            if (earned) {
                drawArc(
                    brush = accent, startAngle = 270f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(26f, 26f), size = Size(68f, 68f),
                    style = Stroke(width = 6f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = colors.light, startAngle = 0f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(36f, 36f), size = Size(48f, 48f),
                    style = Stroke(width = 6f, cap = StrokeCap.Round)
                )
                drawArc(
                    brush = accent, startAngle = 90f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(46f, 46f), size = Size(28f, 28f),
                    style = Stroke(width = 6f, cap = StrokeCap.Round)
                )
                drawCircle(color = colors.light, radius = 9f, center = Offset(60f, 60f))
            } else {
                drawArc(
                    color = outline, startAngle = 270f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(26f, 26f), size = Size(68f, 68f),
                    style = Stroke(width = 6f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = outline, startAngle = 0f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(36f, 36f), size = Size(48f, 48f),
                    style = Stroke(width = 6f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = outline, startAngle = 90f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(46f, 46f), size = Size(28f, 28f),
                    style = Stroke(width = 6f, cap = StrokeCap.Round)
                )
            }
        }
    }
}
