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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated character-theme particle layer (WPF ThemeParticleLayer parity):
 * a handful of small drifting shapes behind the library content, drawn at
 * ~30fps. Types come from [CharacterTheme.particle]: embers, petals,
 * sparkles, butterflies, snow, leaves, ash. Hit-test invisible by
 * construction (pure Canvas draw, no pointer input).
 */
@Composable
fun ParticleLayer(
    type: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (!enabled || type.isBlank()) return
    var tick by remember { mutableLongStateOf(0L) }
    val seeds = remember(type) { List(14) { ParticleSeed(it) } }
    LaunchedEffect(type) {
        while (true) {
            tick++
            delay(33)
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val t = tick * 0.033f
        for (s in seeds) drawSeed(s, type, t)
    }
}

private class ParticleSeed(index: Int) {
    private val rnd = Random(index * 7919 + 13)
    val x0 = rnd.nextFloat()
    val y0 = rnd.nextFloat()
    val size = 6f + rnd.nextFloat() * 14f
    val speed = 14f + rnd.nextFloat() * 30f
    val swayAmp = 10f + rnd.nextFloat() * 30f
    val swayFreq = 0.4f + rnd.nextFloat() * 0.9f
    val phase = rnd.nextFloat() * 6.28f
    val spin = (rnd.nextFloat() - 0.5f) * 120f
    val colorIdx = rnd.nextInt(3)
}

private fun particleColor(type: String, idx: Int): Color = when (type) {
    "embers" -> listOf(Color(0xFFFF6B35), Color(0xFFFF9E4A), Color(0xFFFFD166))[idx % 3]
    "petals" -> listOf(Color(0xFFF9A8D4), Color(0xFFF472B6), Color(0xFFFBCFE8))[idx % 3]
    "sparkles" -> listOf(Color(0xFFFDE68A), Color.White, Color(0xFFFEF3C7))[idx % 3]
    "butterflies" -> listOf(Color(0xFFC4B5FD), Color(0xFFA78BFA), Color(0xFFDDD6FE))[idx % 3]
    "snow" -> Color.White
    "leaves" -> listOf(Color(0xFF86EFAC), Color(0xFF4ADE80), Color(0xFFBBF7D0))[idx % 3]
    // Upstream "ash" (tsugi theme): small gray shards drifting down.
    "ash" -> listOf(Color(0xFF9A8C80), Color(0xFFA39286), Color(0xFF7C6068))[idx % 3]
    else -> Color.White
}

private fun DrawScope.drawSeed(s: ParticleSeed, type: String, t: Float) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val span = h + 80f
    // Embers rise, everything else falls.
    val dir = if (type == "embers") -1f else 1f
    var y = (s.y0 * span + dir * t * s.speed) % span
    if (y < 0) y += span
    y -= 40f
    var x = (s.x0 * w + sin(t * s.swayFreq + s.phase) * s.swayAmp) % w
    if (x < 0) x += w
    val c = particleColor(type, s.colorIdx)
    val alpha = when (type) {
        "sparkles" -> 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 3f + s.phase))
        else -> 0.75f
    }
    when (type) {
        "sparkles" -> {
            val r = s.size * (0.7f + 0.3f * sin(t * 3f + s.phase))
            drawLine(c.copy(alpha = alpha), Offset(x - r, y), Offset(x + r, y), strokeWidth = 3f)
            drawLine(c.copy(alpha = alpha), Offset(x, y - r), Offset(x, y + r), strokeWidth = 3f)
        }
        "butterflies" -> {
            rotate(s.phase * 57f + t * s.spin, Offset(x, y)) {
                val flap = 0.6f + 0.4f * sin(t * 6f + s.phase)
                val wh = s.size * 1.1f * flap
                drawOval(
                    c.copy(alpha = alpha),
                    topLeft = Offset(x - s.size, y - wh / 2f),
                    size = Size(s.size, wh)
                )
                drawOval(
                    c.copy(alpha = alpha),
                    topLeft = Offset(x, y - wh / 2f),
                    size = Size(s.size, wh)
                )
            }
        }
        "petals", "leaves" -> {
            rotate(s.phase * 57f + t * s.spin, Offset(x, y)) {
                drawOval(
                    c.copy(alpha = alpha),
                    topLeft = Offset(x - s.size / 2f, y - s.size / 4f),
                    size = Size(s.size, s.size / 2f)
                )
            }
        }
        "ash" -> {
            // Gray shards: small rotated rectangles drifting down.
            rotate(s.phase * 57f + t * s.spin, Offset(x, y)) {
                val sh = s.size * 0.45f
                drawRect(
                    c.copy(alpha = alpha),
                    topLeft = Offset(x - sh / 2f, y - sh / 4f),
                    size = Size(sh, sh / 2f)
                )
            }
        }
        "embers" -> {
            drawCircle(c.copy(alpha = alpha * 0.25f), radius = s.size * 1.8f, center = Offset(x, y))
            drawCircle(c.copy(alpha = alpha), radius = s.size * 0.6f, center = Offset(x, y))
        }
        else -> {
            // snow
            drawCircle(c.copy(alpha = alpha), radius = s.size * 0.5f, center = Offset(x, y))
        }
    }
}
