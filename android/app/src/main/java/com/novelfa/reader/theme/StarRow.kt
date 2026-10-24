package com.novelfa.reader.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Five real (drawn, not glyph) stars with fractional fill. [fill] is
 * 0..5 across the row: 2.6 fills two stars and 60% of the third, so a
 * title at 12% progress shows the first star ~60% full instead of one
 * whole ★. Glyph stars (★/☆ text) can only ever be full or empty, which
 * is why the old display jumped a whole star every 20%.
 *
 * Fill follows reading direction: in RTL the first star is the
 * rightmost one (Row order) and each star fills from its start side,
 * so the row reads the same way the progress bar does.
 */
@Composable
fun StarRow(
    fill: Float,
    modifier: Modifier = Modifier,
    starSize: Dp = 16.dp,
    gap: Dp = 2.dp
) {
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurfaceVariant
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(modifier = modifier) {
        for (i in 0 until 5) {
            if (i > 0) Spacer(Modifier.width(gap))
            val f = (fill - i).coerceIn(0f, 1f)
            Canvas(Modifier.size(starSize)) {
                val path = starPath(size)
                // Outline first (the "empty" star), then the filled
                // portion clipped to the fraction — a half star is
                // really half a star, not a smaller glyph.
                drawPath(path, empty, style = Stroke(width = 1.2.dp.toPx()))
                if (f > 0f) {
                    if (isRtl) {
                        clipRect(
                            left = size.width * (1f - f),
                            top = 0f,
                            right = size.width,
                            bottom = size.height
                        ) { drawPath(path, filled) }
                    } else {
                        clipRect(
                            left = 0f,
                            top = 0f,
                            right = size.width * f,
                            bottom = size.height
                        ) { drawPath(path, filled) }
                    }
                }
            }
        }
    }
}

/** Classic 5-point star path in [size]: outer points up, inner radius 0.382. */
private fun starPath(size: Size): Path {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outer = size.width / 2f
    val inner = outer * 0.381966f
    val path = Path()
    for (k in 0 until 10) {
        val r = if (k % 2 == 0) outer else inner
        // Start at the top point (-90°) and step 36° per vertex.
        val a = Math.toRadians(-90.0 + k * 36.0)
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat()
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
