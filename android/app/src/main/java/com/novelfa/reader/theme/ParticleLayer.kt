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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated character-theme particle layer — direct port of WPF
 * ThemeParticles.cs: per-kind count/size/duration/peak/rising/sway,
 * fade envelope (0 → peak → 0), rotation for petals/snow/leaves/ash,
 * wing-flap for butterflies (drawn from the character emblem path),
 * twinkle for sparkles, colors from the character palette
 * (accent / aura2 / text-or-accent), peak dimmed in light mode.
 * ~30fps, hit-test invisible (pure Canvas draw).
 */
@Composable
fun ParticleLayer(
    type: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    theme: CharacterTheme? = null,
    light: Boolean = false
) {
    if (!enabled || type.isBlank()) return
    val p = paramsFor(type)
    val pal = theme?.palette(light)
    // Windows: b1 = accent, b2 = aura2, b3 = light ? accent : text.
    val b1 = pal?.let { parseHex(it.accent, Color(0xFFA78BFA)) } ?: fallbackColors(type)[0]
    val b2 = pal?.let { parseHex(it.aura2, Color(0xFFDDD6FE)) } ?: fallbackColors(type)[1]
    val b3 = (pal?.let { parseHex(if (light) it.accent else it.text, Color.White) }
        ?: fallbackColors(type)[2])
    val peak = if (light) p.peak * 0.8f else p.peak
    val emblem = remember(theme, type) {
        if (type == "butterflies") theme?.emblemPath else null
    }
    val glyphs = remember(type) {
        p.glyph?.let { g -> GLYPH_DATA[g]?.let { parseEmblemPath(it) } }
    }
    val seeds = remember(type) { List(p.count) { ParticleSeed(it, p) } }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(type) {
        while (true) {
            tick++
            delay(33)
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val t = tick * 0.033f
        for (s in seeds) {
            drawSeed(s, p, t, emblem, glyphs, b1, b2, b3, peak, type)
        }
    }
}

/** Per-kind parameters, verbatim from ThemeParticles.cs Build(). */
private data class KindParams(
    val count: Int,
    val sizeMin: Float,
    val sizeMax: Float,
    val durMin: Float,
    val durMax: Float,
    val peak: Float,
    val rising: Boolean,
    val swayAmp: Float,
    val rotates: Boolean,
    /** glyph name, "emblem" for butterflies, null for plain dots (embers). */
    val glyph: String?
)

private fun paramsFor(type: String) = when (type) {
    "embers" -> KindParams(12, 3f, 7f, 8f, 16f, 0.7f, true, 18f, false, null)
    "petals" -> KindParams(10, 11f, 19f, 13f, 24f, 0.75f, false, 46f, true, "petal")
    "sparkles" -> KindParams(10, 7f, 15f, 11f, 22f, 0.8f, true, 14f, false, "spark")
    "butterflies" -> KindParams(5, 17f, 27f, 16f, 28f, 0.75f, true, 60f, false, "emblem")
    "leaves" -> KindParams(16, 10f, 20f, 10f, 22f, 0.9f, false, 48f, true, "leaf")
    "ash" -> KindParams(22, 3f, 9f, 14f, 28f, 0.6f, false, 22f, true, "shard")
    "snow" -> KindParams(18, 5f, 11f, 12f, 24f, 0.85f, false, 28f, true, "snow")
    else -> KindParams(14, 6f, 12f, 12f, 20f, 0.75f, false, 24f, false, null)
}

/** Glyph path data (20x20 box), verbatim from CharacterThemes.glyphData. */
private val GLYPH_DATA = mapOf(
    "petal" to "F0 M10,1 C15,1 19,6 17,11 C15.5,15 12,17 10,19.5 C8,17 4.5,15 3,11 C1,6 5,1 10,1 Z",
    "leaf" to "F0 M10,1 C16,4 18,12 10,19 C2,12 4,4 10,1 Z M9.4,6 L10.6,6 L10.6,17 L9.4,17 Z",
    "spark" to "F0 M10,0 L12.2,7.8 L20,10 L12.2,12.2 L10,20 L7.8,12.2 L0,10 L7.8,7.8 Z",
    "shard" to "F0 M3,2 L14,1 L18,8 L15,18 L6,19 L1,10 Z",
    "snow" to "F1 M12.2,10 C12.2,11.22 11.22,12.2 10,12.2 C8.78,12.2 7.8,11.22 7.8,10 C7.8,8.78 8.78,7.8 10,7.8 C11.22,7.8 12.2,8.78 12.2,10 Z M9.15,19 L9.15,1 L10.85,1 L10.85,19 Z M1.78,13.76 L17.37,4.76 L18.22,6.24 L2.63,15.24 Z M2.63,4.76 L18.22,13.76 L17.37,15.24 L1.78,6.24 Z"
)

/** Fallback colors when no character palette is available. */
private fun fallbackColors(type: String): List<Color> = when (type) {
    "embers" -> listOf(Color(0xFFFF6B35), Color(0xFFFF9E4A), Color(0xFFFFD166))
    "petals" -> listOf(Color(0xFFF9A8D4), Color(0xFFF472B6), Color(0xFFFBCFE8))
    "sparkles" -> listOf(Color(0xFFFDE68A), Color.White, Color(0xFFFEF3C7))
    "butterflies" -> listOf(Color(0xFFC4B5FD), Color(0xFFA78BFA), Color(0xFFDDD6FE))
    "snow" -> listOf(Color.White, Color.White, Color.White)
    "leaves" -> listOf(Color(0xFF86EFAC), Color(0xFF4ADE80), Color(0xFFBBF7D0))
    "ash" -> listOf(Color(0xFF9A8C80), Color(0xFFA39286), Color(0xFF7C6068))
    else -> listOf(Color.White, Color.White, Color.White)
}

private class ParticleSeed(index: Int, p: KindParams) {
    private val rnd = Random(index * 7919 + 13)
    val x0 = rnd.nextFloat()
    /** Duration of one fall/rise cycle, seconds. */
    val dur = p.durMin + rnd.nextFloat() * (p.durMax - p.durMin)
    /** Negative start offset so particles begin mid-cycle (WPF delay). */
    val t0 = -rnd.nextFloat() * dur
    val size = p.sizeMin + rnd.nextFloat() * (p.sizeMax - p.sizeMin)
    val swayAmp = p.swayAmp * (0.4f + rnd.nextFloat() * 0.6f)
    val swayPeriod = 3f + rnd.nextFloat() * 3.5f
    val swayPhase = rnd.nextFloat() * 2f * PI.toFloat()
    val spinDir = if (rnd.nextBoolean()) 1f else -1f
    val spinDur = 7f + rnd.nextFloat() * 8f
    val flapDur = 0.28f + rnd.nextFloat() * 0.17f
    val twinkleDur = 1f + rnd.nextFloat() * 1.2f
    val colorIdx = index
}

/** Windows fill pattern per kind and particle index. */
private fun fillFor(type: String, i: Int, b1: Color, b2: Color, b3: Color): Color =
    when (type) {
        "embers" -> if (i % 4 == 0) b2 else b1
        "butterflies", "petals", "leaves" -> if (i % 2 == 0) b1 else b2
        "ash" -> if (i % 9 == 0) b2 else if (i % 2 == 0) b1 else b3
        else -> if (i % 3 == 0) b2 else if (i % 3 == 1) b1 else b3
    }

private fun DrawScope.drawSeed(
    s: ParticleSeed,
    p: KindParams,
    t: Float,
    emblem: Path?,
    glyph: Path?,
    b1: Color,
    b2: Color,
    b3: Color,
    peak: Float,
    type: String
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    // Cycle position 0..1 (WPF: fall animation with negative BeginTime).
    var cyc = ((t + s.t0) % s.dur) / s.dur
    if (cyc < 0) cyc += 1f
    val span = h + 70f
    val y = if (p.rising) (h + 30f) - cyc * span else -40f + cyc * span
    // Sway: sine ease auto-reverse equivalent.
    val x = s.x0 * w + s.swayAmp * sin(2f * PI.toFloat() * (t + s.t0) / s.swayPeriod + s.swayPhase)
    // Fade envelope: 0 -> peak by 12%, hold to 88%, -> 0.
    val alpha = peak * when {
        cyc < 0.12f -> cyc / 0.12f
        cyc > 0.88f -> (1f - cyc) / 0.12f
        else -> 1f
    }
    if (alpha <= 0.01f) return
    val c = fillFor(type, s.colorIdx, b1, b2, b3).copy(alpha = alpha)

    when {
        // Butterflies: the character emblem with a wing flap (ScaleX 1 -> 0.3).
        type == "butterflies" && emblem != null -> {
            val bounds = emblem.getBounds()
            val bw = bounds.width.takeIf { it > 0f } ?: 100f
            val k = s.size / bw
            val flap = 1f - 0.7f * (0.5f + 0.5f * sin(2f * PI.toFloat() * t / s.flapDur + s.swayPhase))
            withTransform({
                translate(x, y)
                scale(scaleX = k * flap, scaleY = k, pivot = Offset.Zero)
                translate(-bounds.left, -bounds.top)
            }) {
                drawPath(emblem, c)
            }
        }
        glyph != null -> {
            // Glyphs live in a 20x20 box.
            val k = s.size / 20f
            val rot = if (p.rotates) {
                s.spinDir * 360f * (((t + s.t0) / s.spinDur) % 1f)
            } else 0f
            withTransform({
                translate(x, y)
                rotate(rot, Offset.Zero)
                scale(k, k, Offset.Zero)
                translate(-10f, -10f)
            }) {
                if (type == "sparkles") {
                    // Twinkle: scale pulsation 0.45 -> 1.1.
                    val tw = 0.45f + 0.65f * (0.5f + 0.5f * sin(2f * PI.toFloat() * t / s.twinkleDur + s.swayPhase))
                    withTransform({ scale(tw, tw, Offset(10f, 10f)) }) {
                        drawPath(glyph, c)
                    }
                } else {
                    drawPath(glyph, c)
                }
            }
        }
        else -> {
            // Embers: soft dot (no glyph).
            drawCircle(c.copy(alpha = alpha * 0.25f), radius = s.size * 1.8f, center = Offset(x, y))
            drawCircle(c, radius = s.size * 0.6f, center = Offset(x, y))
        }
    }
}
