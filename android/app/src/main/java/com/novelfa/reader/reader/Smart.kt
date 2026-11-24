package com.novelfa.reader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.ceil
import kotlin.math.max

/**
 * Smart reading (ported from upstream SmartEngine.cs / SmartWebBridge,
 * Ali 2026-11-23): auto-scroll, eye care and reading-time math.
 * The engine itself lives in ReaderScreen; this file holds only the
 * pure, unit-testable math plus the eye-care overlay composable.
 */

/**
 * Eye-care overlay alphas, the same math as upstream
 * `__smartEyeCare(warmth, brightness)`: warmth 0-60 tints the page
 * warm orange, brightness 45-100 dims it with black. Returns
 * (warmAlpha, dimAlpha); (0, 0) means no overlay is needed.
 */
internal fun eyeCareLayers(warmth: Int, brightness: Int): Pair<Float, Float> {
    val w = warmth.coerceIn(0, 60)
    val b = brightness.coerceIn(45, 100)
    if (w == 0 && b >= 100) return 0f to 0f
    val dim = (100 - b) / 100f * 0.65f
    val warmAlpha = (w / 60f) * 0.26f
    return warmAlpha to dim
}

/**
 * Remaining reading minutes for a chapter: [words] words in the
 * chapter, [fractionDone] already read (0..1), at [wpm] words per
 * minute. Rounds up, so a non-empty remainder is at least 1 minute.
 */
internal fun remainingMinutes(words: Int, fractionDone: Float, wpm: Int): Int {
    if (words <= 0 || wpm <= 0) return 0
    val left = words * (1f - fractionDone.coerceIn(0f, 1f))
    if (left <= 0f) return 0
    return max(1, ceil(left / wpm).toInt())
}

/** Step-mode scroll distance: [stepPercent]% of the viewport height. */
internal fun autoScrollStepDelta(clientHeight: Int, stepPercent: Int): Int =
    max(40, clientHeight * stepPercent.coerceIn(10, 100) / 100)

/**
 * Smooth-mode page-turn interval in paged mode (upstream
 * `__smartAutoScroll.startContinuous`): a page every
 * max(1 s, 60/speed) seconds, so 45 px/s turns about every 1.3 s.
 */
internal fun smoothPageIntervalSec(speedPxPerSec: Int): Double =
    max(1.0, 60.0 / speedPxPerSec.coerceIn(10, 240))

/**
 * Auto-scroll engine runtime (upstream `__smartAutoScroll` state):
 * a plain holder, not Compose state — the scroll reporter fires
 * every 200 ms and must not recompose the reader. [pauseUntilMs] is
 * the user-scroll pause deadline; [programmaticUntilMs] marks the
 * window after an engine-driven scroll, so the reporter's next tick
 * does not read it as the user grabbing the page.
 */
internal class AutoScrollRuntime {
    var pauseUntilMs: Long = 0L
    var programmaticUntilMs: Long = 0L
    var prevTop: Int = -1
    var lastTop: Int = 0
    var lastHeight: Int = 0
    var lastClient: Int = 0
    var smoothCarryPx: Double = 0.0
    var smoothCarrySec: Double = 0.0
}

/**
 * Eye-care overlay over the reader WebView (upstream
 * `__smartEyeCare`): a warm tint over a dim, stacked like the CSS
 * layers — warm first, black under it. A plain Box with no click
 * handling, so touches pass through to the page below; it sits
 * under the footnote bubble and the page pill.
 */
@Composable
internal fun EyeCareOverlay(warmth: Int, brightness: Int, modifier: Modifier = Modifier) {
    val (warmAlpha, dimAlpha) = eyeCareLayers(warmth, brightness)
    if (warmAlpha <= 0f && dimAlpha <= 0f) return
    Box(modifier = modifier.fillMaxSize()) {
        if (dimAlpha > 0f) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = dimAlpha))
            )
        }
        if (warmAlpha > 0f) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color(255, 165, 55).copy(alpha = warmAlpha))
            )
        }
    }
}
