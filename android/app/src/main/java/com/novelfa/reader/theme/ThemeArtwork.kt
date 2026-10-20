package com.novelfa.reader.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform

/**
 * Theme artwork ported from the Windows client (LibraryWindow.xaml):
 * the character emblem used as a giant low-opacity watermark, and the
 * «سوبارو · شکم‌پرستی» (tsugi) stitch decorations. All pieces are
 * hit-test transparent by construction (Canvas draws only).
 */

/**
 * The active theme's emblem, filled with the accent gradient — Windows
 * draws it at 230x230 / 7% in window backgrounds, 420x420 / 6% in the
 * library corner and 190x190 / 14% inside the hero card.
 */
@Composable
fun EmblemWatermark(
    path: Path,
    grad1: Color,
    grad2: Color,
    alpha: Float,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center
) {
    Canvas(modifier = modifier) {
        // The emblem is drawn at the canvas's smaller dimension and placed
        // per contentAlignment, so a wide/short canvas (e.g. the hero card
        // via matchParentSize) still shows it whole, at the wanted side.
        val side = size.minDimension
        val scale = side / 100f
        val topLeft = contentAlignment.align(
            IntSize(side.roundToInt(), side.roundToInt()),
            IntSize(size.width.roundToInt(), size.height.roundToInt())
        )
        withTransform({
            translate(topLeft.x.toFloat(), topLeft.y.toFloat())
            scale(scale, scale, Offset.Zero)
        }) {
            drawPath(
                path = path,
                brush = Brush.linearGradient(colors = listOf(grad1, grad2)),
                alpha = alpha
            )
        }
    }
}

/**
 * Diagonal stitch, 30x30 spec (Windows stat-strip corners): a thin solid
 * line under a thick round-capped dashed line — the dashes read as thread
 * stitches along the seam.
 */
@Composable
fun StitchDiagonal(color: Color, modifier: Modifier = Modifier) {
    StitchDiagonalSpec(
        color = color,
        modifier = modifier,
        inset = 2f / 30f,
        thin = 1.1f / 30f,
        thick = 7f / 30f,
        dash = 1.4f / 30f,
        gap = 9.8f / 30f
    )
}

/** Diagonal stitch, 64x64 spec (Windows hero-card corner). */
@Composable
fun StitchDiagonalLarge(color: Color, modifier: Modifier = Modifier) {
    StitchDiagonalSpec(
        color = color,
        modifier = modifier,
        inset = 4f / 64f,
        thin = 1.2f / 64f,
        thick = 9f / 64f,
        dash = 1.62f / 64f,
        gap = 10.8f / 64f
    )
}

@Composable
private fun StitchDiagonalSpec(
    color: Color,
    modifier: Modifier,
    inset: Float,
    thin: Float,
    thick: Float,
    dash: Float,
    gap: Float
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val start = Offset(w * inset, h * (1f - inset))
        val end = Offset(w * (1f - inset), h * inset)
        drawLine(
            color = color, start = start, end = end,
            strokeWidth = w * thin, cap = StrokeCap.Round, alpha = 0.9f
        )
        drawLine(
            color = color, start = start, end = end,
            strokeWidth = w * thick, cap = StrokeCap.Round, alpha = 0.9f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * dash, w * gap))
        )
    }
}

/**
 * Horizontal stitch strip, 10dp tall (Windows draws it under every book
 * cover in the grid when the tsugi theme is active).
 */
@Composable
fun StitchStrip(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val h = size.height
        val start = Offset(0f, h / 2f)
        val end = Offset(size.width, h / 2f)
        drawLine(
            color = color, start = start, end = end,
            strokeWidth = h * 0.11f, alpha = 0.9f
        )
        drawLine(
            color = color, start = start, end = end,
            strokeWidth = h * 0.9f, cap = StrokeCap.Round, alpha = 0.9f,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(h * 0.162f, h * 1.035f)
            )
        )
    }
}

/**
 * The torn seam beside the hero cover (Windows hero card): a zigzag rip
 * drawn three times — a soft dark-red halo, the red thread itself, and
 * the light cross-stitches over it. Native space is 24x100.
 */
@Composable
fun StitchSeam(textColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 100f
        val pts = listOf(
            18f to 0f, 12f to 9f, 19f to 17f, 9f to 29f, 16f to 38f,
            7f to 52f, 15f to 61f, 10f to 73f, 18f to 84f, 12f to 100f
        )
        val path = Path().apply {
            pts.forEachIndexed { i, (x, y) ->
                if (i == 0) moveTo(x * sx, y * sy) else lineTo(x * sx, y * sy)
            }
        }
        drawPath(
            path = path, color = Color(0xFFB3262E),
            style = Stroke(width = 6f * sx, join = StrokeJoin.Round), alpha = 0.16f
        )
        drawPath(
            path = path, color = Color(0xFFC0392F),
            style = Stroke(width = 1.4f * sx, join = StrokeJoin.Miter), alpha = 0.9f
        )
        drawPath(
            path = path, color = textColor,
            style = Stroke(
                width = 9f * sx, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(1.44f * sy, 11.25f * sy)
                )
            ),
            alpha = 0.75f
        )
    }
}
