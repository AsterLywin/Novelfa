package com.novelfa.reader.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Celebration burst for the home-screen XP dialog (spec §6).
 *
 * Particle count is the spec's intensity, by the highest new step:
 * star = ~30, title = ~120, eternity mark = ~300 (+ halo),
 * 10th mark (Eternal Sun) = ~600 (+ rainbow halo).
 * The burst is finite: particles fall for about 4 seconds and then
 * freeze on the last frame, so a dialog left open does not burn CPU
 * forever. Seeds are deterministic per tier, so the same tier always
 * looks the same. The canvas is hit-test invisible (pure draw), so
 * dialog buttons above it stay tappable.
 *
 * This is deliberately NOT [ParticleLayer]: that layer is the
 * looping character-theme ambience (5–22 particles, tied to a theme
 * type). A celebration is a one-shot burst whose count IS the tier.
 */
@Composable
fun CelebrationParticles(
    tier: CelebrationTier,
    modifier: Modifier = Modifier
) {
    val count = when (tier) {
        CelebrationTier.STAR -> 30
        CelebrationTier.TITLE -> 120
        CelebrationTier.ETERNITY -> 300
        CelebrationTier.ETERNITY_FINAL -> 600
    }
    val seeds = remember(tier) { List(count) { CelebrationSeed(it) } }
    var tick by remember(tier) { mutableLongStateOf(0L) }
    // ~4 seconds at ~30fps, then stop ticking: the last frame stays
    // as a static confetti field instead of looping forever.
    LaunchedEffect(tier) {
        while (tick < 120) {
            tick++
            delay(33)
        }
    }
    // The caller owns the size: this used to append fillMaxSize
    // here, and inside the XP dialog that stretched the whole
    // dialog to nearly full screen height — a tall empty box
    // with the text stranded at the top. The dialog now passes
    // matchParentSize, so the canvas covers exactly the content
    // area and the dialog wraps the content height.
    Canvas(modifier = modifier) {
        val t = tick * 0.033f
        if (tier == CelebrationTier.ETERNITY) {
            drawHalo(Color(0xFFFFD166))
        } else if (tier == CelebrationTier.ETERNITY_FINAL) {
            drawRainbowHalo()
        }
        for (s in seeds) {
            drawSeed(s, t)
        }
    }
}

/** One falling particle: deterministic position, speed, size, colour. */
private class CelebrationSeed(index: Int) {
    private val rnd = Random(index * 7919 + 13)
    val x0 = rnd.nextFloat()
    val startY = rnd.nextFloat() * 0.35f
    val fallSpeed = 0.10f + rnd.nextFloat() * 0.22f
    val swayAmp = 6f + rnd.nextFloat() * 22f
    val swaySpeed = 1f + rnd.nextFloat() * 2.5f
    val phase = rnd.nextFloat() * (2f * PI.toFloat())
    val size = 2.5f + rnd.nextFloat() * 5.5f
    val isStar = index % 4 == 0
    val rotation = rnd.nextFloat() * 360f
    val color = when (index % 6) {
        0 -> Color(0xFFFFD166)
        1 -> Color(0xFFF472B6)
        2 -> Color(0xFFA78BFA)
        3 -> Color(0xFF7DD3FC)
        4 -> Color(0xFF86EFAC)
        else -> Color.White
    }
}

private fun DrawScope.drawSeed(s: CelebrationSeed, t: Float) {
    val w = size.width
    val h = size.height
    // Fall, wrapping once past the bottom so the field stays full
    // for the whole burst instead of emptying from the top.
    val yFrac = (s.startY + t * s.fallSpeed) % 1.15f
    val x = s.x0 * w + sin(t * s.swaySpeed + s.phase) * s.swayAmp
    val y = yFrac * h
    // Fade in at the top, fade out near the bottom.
    val alpha = when {
        yFrac < 0.05f -> (yFrac / 0.05f).coerceIn(0f, 1f)
        yFrac > 0.95f -> ((1.15f - yFrac) / 0.20f).coerceIn(0f, 1f)
        else -> 1f
    }
    if (alpha <= 0f) return
    if (s.isStar) {
        val path = starPath(Offset(x, y), s.size * 1.6f, s.rotation + t * 40f)
        drawPath(path, s.color.copy(alpha = alpha * 0.95f))
    } else {
        drawCircle(s.color.copy(alpha = alpha * 0.9f), radius = s.size, center = Offset(x, y))
    }
}

private fun starPath(center: Offset, radius: Float, rotationDeg: Float): Path {
    val path = Path()
    val rot = rotationDeg * PI.toFloat() / 180f
    for (i in 0 until 8) {
        val angle = rot + i * PI.toFloat() / 4f
        val r = if (i % 2 == 0) radius else radius * 0.45f
        val x = center.x + cos(angle) * r
        val y = center.y + sin(angle) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun DrawScope.drawHalo(color: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = maxOf(size.width, size.height) * 0.55f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)),
            center = Offset(cx, cy),
            radius = maxR
        ),
        radius = maxR,
        center = Offset(cx, cy)
    )
}

private fun DrawScope.drawRainbowHalo() {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = maxOf(size.width, size.height) * 0.55f
    val colors = listOf(
        Color(0xFFF472B6), Color(0xFFFFD166), Color(0xFF86EFAC),
        Color(0xFF7DD3FC), Color(0xFFA78BFA)
    )
    for ((i, c) in colors.withIndex()) {
        val r = maxR * (1f - i * 0.16f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(c.copy(alpha = 0.20f), c.copy(alpha = 0f)),
                center = Offset(cx, cy),
                radius = r
            ),
            radius = r,
            center = Offset(cx, cy)
        )
    }
}
