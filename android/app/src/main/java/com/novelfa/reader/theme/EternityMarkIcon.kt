package com.novelfa.reader.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 10 eternity marks, drawn as vector shapes (no image assets).
 *
 * Design (approved by the user 2026-11-09, previewed as
 * ~/workspace/ranks_sheet/marks.png): each mark has its own shape
 * matching its name — ember flame, starry dewdrop, golden leaf,
 * crescent, paper sun, galactic crown, keeper's wing, heart of
 * Pleiades, throne, and the radiant sun of worlds (the most
 * elaborate, with three small worlds orbiting it). All 10 are gold
 * so they stand apart from the violet title stars (StarRow); the
 * gold is fixed, it does NOT follow the app accent.
 *
 * States: earned = gold gradient fill (+ light/dark details);
 * locked = outline only, in onSurfaceVariant. The shapes live in a
 * 120x120 space and are scaled to [size] — the same pattern as
 * StarRow's drawn stars, and the same withTransform+scale recipe
 * CharacterSheet uses for its emblem.
 *
 * The crescent's locked outline is two drawArc calls whose angles
 * come from the exact circle-circle intersection of the outer
 * (56,60,r36) and inner (74,50,r31) circles — outer major arc
 * 271.74° sweep -241.59°, inner arc 64.94° sweep +172.01° — verified
 * by rendering the same arcs (~/workspace/ranks_sheet/crescent_test.png).
 */
@Composable
fun EternityMarkIcon(markIndex: Int, size: Dp, earned: Boolean, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier.size(size)) {
        // this.size is the canvas Size: the Dp parameter named `size`
        // shadows DrawScope.size, and Dp has no minDimension.
        val scale = this.size.minDimension / 120f
        withTransform({ scale(scale, scale) }) {
            drawEternityMark(markIndex.coerceIn(1, 10), earned, outline)
        }
    }
}

private val MarkGoldColors = listOf(
    Color(0xFFFFE9A8),
    Color(0xFFF5C542),
    Color(0xFFE8960C)
)
private val MarkGoldLight = Color(0xFFFFE9A8)
private val MarkGoldDark = Color(0xFF8A5A00)
private val MarkFeatherMid = Color(0xFFE8A90B)
private val MarkFeatherDark = Color(0xFFD9960A)

/** 4-point sparkle, concave diamond, first point at the top. */
private fun star4Path(cx: Float, cy: Float, r: Float): Path {
    val path = Path()
    for (k in 0 until 8) {
        val rad = if (k % 2 == 0) r else r * 0.28f
        val a = Math.toRadians(-90.0 + k * 45.0)
        val x = cx + rad * cos(a).toFloat()
        val y = cy + rad * sin(a).toFloat()
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** One triangular ray from (cx, cy): base at r0, tip at r1. */
private fun rayPath(cx: Float, cy: Float, angleDeg: Float, r0: Float, r1: Float, halfDeg: Float): Path {
    val a = Math.toRadians(angleDeg.toDouble())
    val h = Math.toRadians(halfDeg.toDouble())
    val bx1 = cx + r0 * cos(a - h).toFloat()
    val by1 = cy + r0 * sin(a - h).toFloat()
    val bx2 = cx + r0 * cos(a + h).toFloat()
    val by2 = cy + r0 * sin(a + h).toFloat()
    val tx = cx + r1 * cos(a).toFloat()
    val ty = cy + r1 * sin(a).toFloat()
    return Path().apply {
        moveTo(bx1, by1)
        lineTo(tx, ty)
        lineTo(bx2, by2)
        close()
    }
}

/** Small upright diamond (crown/throne gem). */
private fun diamondPath(cx: Float, cy: Float, r: Float): Path {
    return Path().apply {
        moveTo(cx, cy - r)
        lineTo(cx + r, cy)
        lineTo(cx, cy + r)
        lineTo(cx - r, cy)
        close()
    }
}

private fun DrawScope.drawEternityMark(index: Int, earned: Boolean, outline: Color) {
    val gold = Brush.linearGradient(
        colors = MarkGoldColors,
        start = Offset(60f, 0f),
        end = Offset(60f, 120f)
    )
    val stroke = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (index) {
        1 -> {
            // Ember: flame with a bright core.
            val flame = Path().apply {
                moveTo(60f, 10f)
                cubicTo(69f, 29f, 89f, 37f, 89f, 66f)
                arcTo(Rect(31f, 37f, 89f, 95f), 0f, 180f, false)
                cubicTo(31f, 53f, 39f, 47f, 44f, 39f)
                cubicTo(48f, 47f, 52f, 49f, 54f, 51f)
                cubicTo(52f, 37f, 54f, 22f, 60f, 10f)
                close()
            }
            if (earned) {
                drawPath(flame, brush = gold)
                val core = Path().apply {
                    moveTo(60f, 56f)
                    cubicTo(66f, 64f, 69f, 70f, 69f, 77f)
                    arcTo(Rect(51f, 68f, 69f, 86f), 0f, 180f, false)
                    cubicTo(51f, 70f, 55f, 64f, 60f, 56f)
                    close()
                }
                drawPath(core, color = MarkGoldLight)
            } else {
                drawPath(flame, color = outline, style = stroke)
            }
        }
        2 -> {
            // Stardew: dewdrop with a sparkle and a light highlight.
            val drop = Path().apply {
                moveTo(60f, 12f)
                cubicTo(75f, 35f, 85f, 49f, 85f, 68f)
                arcTo(Rect(35f, 43f, 85f, 93f), 0f, 180f, false)
                cubicTo(35f, 49f, 45f, 35f, 60f, 12f)
                close()
            }
            if (earned) {
                drawPath(drop, brush = gold)
                drawOval(
                    color = MarkGoldLight,
                    topLeft = Offset(44f, 56f),
                    size = Size(12f, 20f),
                    alpha = 0.55f
                )
                drawPath(star4Path(82f, 34f, 11f), color = MarkGoldLight)
            } else {
                drawPath(drop, color = outline, style = stroke)
            }
        }
        3 -> {
            // Golden leaf with veins.
            val leaf = Path().apply {
                moveTo(60f, 8f)
                cubicTo(93f, 31f, 95f, 73f, 60f, 112f)
                cubicTo(25f, 73f, 27f, 31f, 60f, 8f)
                close()
            }
            if (earned) {
                drawPath(leaf, brush = gold)
                drawLine(MarkGoldDark, Offset(60f, 20f), Offset(60f, 100f), strokeWidth = 3f, cap = StrokeCap.Round)
                drawLine(MarkGoldDark, Offset(60f, 44f), Offset(78f, 30f), strokeWidth = 2.4f, cap = StrokeCap.Round)
                drawLine(MarkGoldDark, Offset(60f, 44f), Offset(42f, 30f), strokeWidth = 2.4f, cap = StrokeCap.Round)
                drawLine(MarkGoldDark, Offset(60f, 66f), Offset(80f, 52f), strokeWidth = 2.4f, cap = StrokeCap.Round)
                drawLine(MarkGoldDark, Offset(60f, 66f), Offset(40f, 52f), strokeWidth = 2.4f, cap = StrokeCap.Round)
            } else {
                drawPath(leaf, color = outline, style = stroke)
            }
        }
        4 -> {
            // Silver crescent (gold here): outer circle minus the
            // offset inner circle. Filled = EvenOdd path; locked =
            // the two arcs of its outline (see the file header).
            val outer = Rect(20f, 24f, 92f, 96f)
            val inner = Rect(43f, 19f, 105f, 81f)
            if (earned) {
                val crescent = Path().apply {
                    addOval(outer)
                    addOval(inner)
                    fillType = PathFillType.EvenOdd
                }
                drawPath(crescent, brush = gold)
                drawPath(star4Path(92f, 28f, 10f), color = MarkGoldLight)
                drawPath(star4Path(84f, 88f, 7f), color = MarkGoldLight)
            } else {
                drawArc(
                    color = outline,
                    startAngle = 271.74f,
                    sweepAngle = -241.59f,
                    useCenter = false,
                    topLeft = outer.topLeft,
                    size = outer.size,
                    style = stroke
                )
                drawArc(
                    color = outline,
                    startAngle = 64.94f,
                    sweepAngle = 172.01f,
                    useCenter = false,
                    topLeft = inner.topLeft,
                    size = inner.size,
                    style = stroke
                )
            }
        }
        5 -> {
            // Paper sun: 8 rays + disc + the folded-paper crease.
            val rays = (0 until 8).map { rayPath(60f, 60f, it * 45f, 26f, 46f, 8f) }
            if (earned) {
                rays.forEach { drawPath(it, brush = gold) }
                drawCircle(brush = gold, radius = 24f, center = Offset(60f, 60f))
                drawLine(
                    MarkGoldDark, Offset(42f, 74f), Offset(78f, 46f),
                    strokeWidth = 2.5f, cap = StrokeCap.Round, alpha = 0.45f
                )
            } else {
                rays.forEach { drawPath(it, color = outline, style = stroke) }
                drawCircle(color = outline, radius = 24f, center = Offset(60f, 60f), style = stroke)
            }
        }
        6 -> {
            // Galactic crown with a light gem and a sparkle on top.
            val crown = Path().apply {
                moveTo(28f, 86f)
                lineTo(22f, 44f)
                lineTo(42f, 60f)
                lineTo(60f, 26f)
                lineTo(78f, 60f)
                lineTo(98f, 44f)
                lineTo(92f, 86f)
                close()
            }
            if (earned) {
                drawPath(crown, brush = gold)
                drawRoundRect(
                    brush = gold,
                    topLeft = Offset(28f, 88f),
                    size = Size(64f, 10f),
                    cornerRadius = CornerRadius(3f, 3f)
                )
                drawCircle(brush = gold, radius = 5f, center = Offset(22f, 40f))
                drawCircle(brush = gold, radius = 5f, center = Offset(60f, 22f))
                drawCircle(brush = gold, radius = 5f, center = Offset(98f, 40f))
                drawPath(diamondPath(60f, 60f, 10f), color = MarkGoldLight)
                drawPath(star4Path(60f, 8f, 6f), color = MarkGoldLight)
            } else {
                drawPath(crown, color = outline, style = stroke)
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(28f, 88f),
                    size = Size(64f, 10f),
                    cornerRadius = CornerRadius(3f, 3f),
                    style = stroke
                )
                drawCircle(color = outline, radius = 5f, center = Offset(22f, 40f), style = stroke)
                drawCircle(color = outline, radius = 5f, center = Offset(60f, 22f), style = stroke)
                drawCircle(color = outline, radius = 5f, center = Offset(98f, 40f), style = stroke)
            }
        }
        7 -> {
            // Keeper's wing: three overlapping feathers, fanning up-right.
            val feather1 = Path().apply {
                moveTo(28f, 86f)
                cubicTo(40f, 48f, 66f, 26f, 104f, 22f)
                cubicTo(96f, 42f, 86f, 54f, 70f, 63f)
                cubicTo(56f, 71f, 40f, 79f, 28f, 86f)
                close()
            }
            val feather2 = Path().apply {
                moveTo(28f, 92f)
                cubicTo(44f, 62f, 68f, 46f, 98f, 42f)
                cubicTo(90f, 57f, 79f, 68f, 63f, 75f)
                cubicTo(50f, 81f, 38f, 87f, 28f, 92f)
                close()
            }
            val feather3 = Path().apply {
                moveTo(30f, 98f)
                cubicTo(48f, 76f, 70f, 64f, 94f, 60f)
                cubicTo(86f, 73f, 74f, 83f, 58f, 89f)
                cubicTo(47f, 93f, 38f, 96f, 30f, 98f)
                close()
            }
            if (earned) {
                drawPath(feather1, brush = gold)
                drawPath(feather2, color = MarkFeatherMid)
                drawPath(feather3, color = MarkFeatherDark)
            } else {
                drawPath(feather1, color = outline, style = stroke)
                drawPath(feather2, color = outline, style = stroke)
                drawPath(feather3, color = outline, style = stroke)
            }
        }
        8 -> {
            // Heart of Pleiades with three sparkles above it.
            val heart = Path().apply {
                moveTo(60f, 102f)
                cubicTo(30f, 78f, 16f, 55f, 27f, 38f)
                cubicTo(35f, 26f, 52f, 28f, 60f, 44f)
                cubicTo(68f, 28f, 85f, 26f, 93f, 38f)
                cubicTo(104f, 55f, 90f, 78f, 60f, 102f)
                close()
            }
            if (earned) {
                drawPath(heart, brush = gold)
                drawPath(star4Path(30f, 20f, 8f), color = MarkGoldLight)
                drawPath(star4Path(60f, 12f, 9f), color = MarkGoldLight)
                drawPath(star4Path(90f, 20f, 8f), color = MarkGoldLight)
            } else {
                drawPath(heart, color = outline, style = stroke)
            }
        }
        9 -> {
            // Throne of Eternity, front-facing: arched back, arms,
            // seat and stepped base.
            val back = Path().apply {
                moveTo(46f, 10f)
                cubicTo(46f, 4f, 54f, 2f, 60f, 2f)
                cubicTo(66f, 2f, 74f, 4f, 74f, 10f)
                lineTo(74f, 52f)
                lineTo(46f, 52f)
                close()
            }
            if (earned) {
                drawPath(back, brush = gold)
                drawCircle(brush = gold, radius = 4f, center = Offset(46f, 8f))
                drawCircle(brush = gold, radius = 4f, center = Offset(74f, 8f))
                drawPath(diamondPath(60f, 27f, 9f), color = MarkGoldLight)
                drawRoundRect(
                    brush = gold, topLeft = Offset(28f, 52f), size = Size(18f, 12f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
                drawRoundRect(
                    brush = gold, topLeft = Offset(74f, 52f), size = Size(18f, 12f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
                drawRoundRect(
                    brush = gold, topLeft = Offset(28f, 64f), size = Size(64f, 9f),
                    cornerRadius = CornerRadius(3f, 3f)
                )
                drawRoundRect(
                    brush = gold, topLeft = Offset(26f, 76f), size = Size(68f, 10f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
                drawRoundRect(
                    color = MarkFeatherDark, topLeft = Offset(34f, 88f), size = Size(52f, 7f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            } else {
                drawPath(back, color = outline, style = stroke)
                drawCircle(color = outline, radius = 4f, center = Offset(46f, 8f), style = stroke)
                drawCircle(color = outline, radius = 4f, center = Offset(74f, 8f), style = stroke)
                drawRoundRect(
                    color = outline, topLeft = Offset(28f, 52f), size = Size(18f, 12f),
                    cornerRadius = CornerRadius(2f, 2f), style = stroke
                )
                drawRoundRect(
                    color = outline, topLeft = Offset(74f, 52f), size = Size(18f, 12f),
                    cornerRadius = CornerRadius(2f, 2f), style = stroke
                )
                drawRoundRect(
                    color = outline, topLeft = Offset(28f, 64f), size = Size(64f, 9f),
                    cornerRadius = CornerRadius(3f, 3f), style = stroke
                )
                drawRoundRect(
                    color = outline, topLeft = Offset(26f, 76f), size = Size(68f, 10f),
                    cornerRadius = CornerRadius(2f, 2f), style = stroke
                )
                drawRoundRect(
                    color = outline, topLeft = Offset(34f, 88f), size = Size(52f, 7f),
                    cornerRadius = CornerRadius(2f, 2f), style = stroke
                )
            }
        }
        10 -> {
            // Eternal Sun of Worlds: halo, 12 alternating rays, disc
            // and three small worlds orbiting it.
            val rays = (0 until 12).map {
                rayPath(60f, 60f, it * 30f, 28f, if (it % 2 == 0) 56f else 49f, 6f)
            }
            if (earned) {
                drawCircle(color = Color(0xFFF5C542), radius = 48f, center = Offset(60f, 60f), alpha = 0.16f)
                rays.forEach { drawPath(it, brush = gold) }
                drawCircle(brush = gold, radius = 25f, center = Offset(60f, 60f))
                drawCircle(color = MarkGoldLight, radius = 5.5f, center = Offset(60f, 14f))
                drawCircle(color = MarkGoldLight, radius = 5.5f, center = Offset(100f, 84f))
                drawCircle(color = MarkGoldLight, radius = 5.5f, center = Offset(20f, 84f))
            } else {
                rays.forEach { drawPath(it, color = outline, style = stroke) }
                drawCircle(color = outline, radius = 25f, center = Offset(60f, 60f), style = stroke)
                drawCircle(color = outline, radius = 5.5f, center = Offset(60f, 14f), style = stroke)
                drawCircle(color = outline, radius = 5.5f, center = Offset(100f, 84f), style = stroke)
                drawCircle(color = outline, radius = 5.5f, center = Offset(20f, 84f), style = stroke)
            }
        }
    }
}
