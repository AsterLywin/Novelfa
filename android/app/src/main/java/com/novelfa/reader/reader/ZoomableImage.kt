package com.novelfa.reader.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeConsumed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * ComicScreen-style zoom + pan for one page in vertical mode.
 *
 * ComicScreen's vertical strip shows each page fit to the strip
 * width, one below the other, with no sideways scroll — and each
 * page is a PhotoView: pinch to zoom, drag to pan while zoomed,
 * and when the pan hits its edge the strip scrolls again. This
 * container is that behaviour, reimplemented for Compose:
 *
 * - Pinch (two fingers) zooms 1x..4x around the pinch focal
 *   point and pans with the centroid, consuming the whole
 *   gesture so the list never scrolls mid-pinch.
 * - One-finger drag pans ONLY while zoomed (scale > 1). At
 *   scale 1 the drag is never consumed, so the list scrolls
 *   vertically as before — and since there is no horizontal
 *   scroll anymore, a sideways drag at scale 1 simply does
 *   nothing, which is the requested "no left-right scroll".
 * - Edge handoff: while zoomed, a drag that cannot move the
 *   image any further in that direction is NOT consumed, so
 *   the list takes over and scrolls from there — the same
 *   hand-over PhotoView does at its bounds.
 * - Taps are never consumed, so the chrome toggle (the outer
 *   tap surface) keeps working on a tap. Deliberately NO
 *   double-tap zoom: the chrome toggle fires on the first tap
 *   up, so a double-tap would toggle the chrome twice (a
 *   flicker) unless single taps were delayed by ~300ms, which
 *   would make every chrome toggle feel laggy. Pinch is the
 *   zoom; double-tap can come back if that trade-off is wanted.
 *
 * Limits, stated plainly: the touch area is the page's layout
 * box — a zoomed image draws beyond it (graphicsLayer does not
 * resize layout), and touches on that overflow belong to the
 * neighbouring items/list, not to this page. The box is the
 * full strip width and the page's full height, so in practice
 * the zoomed page stays touchable over most of the screen.
 */
private const val MAX_ZOOM = 4f

@Composable
fun ZoomablePageContainer(
    modifier: Modifier,
    // Reports the live pinch scale (1..4). The caller uses it to
    // re-render the page sharper once the pinch passes a
    // threshold (ComicScreen's tile refinement, coarse-grained:
    // a full-page re-render at 2x display width instead of
    // per-tile re-decodes — same visible result, far less
    // machinery). Fires on every pinch move; the caller must
    // guard its own threshold so it does not re-render per move.
    onScaleChange: (Float) -> Unit = {},
    content: @Composable () -> Unit
) {
    // Plain state, not Animatable: the gesture loop runs in a
    // restricted suspension scope, where Animatable's suspend
    // snapTo/stop are not callable — and nothing here animates
    // (double-tap zoom was deliberately left out, see above),
    // so direct assignment is all the loop needs.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(o: Offset, s: Float): Offset {
        val maxX = ((boxSize.width * s - boxSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((boxSize.height * s - boxSize.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown()
                    var pinch = false
                    var pastSlop = false
                    var prevCentroid = Offset.Zero
                    var prevDistance = 0f
                    var totalDrag = Offset.Zero
                    val slop = viewConfiguration.touchSlop
                    var gestureActive = true
                    while (gestureActive) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // Pinch: zoom around the focal point,
                            // pan with the centroid, consume all.
                            val c1 = pressed[0].position
                            val c2 = pressed[1].position
                            val centroid = (c1 + c2) / 2f
                            val distance = (c1 - c2).getDistance()
                            if (!pinch) {
                                pinch = true
                                prevCentroid = centroid
                                prevDistance = distance
                            } else if (prevDistance > 0f && distance > 0f) {
                                val oldScale = scale
                                val newScale =
                                    (oldScale * (distance / prevDistance))
                                        .coerceIn(1f, MAX_ZOOM)
                                val center = Offset(
                                    boxSize.width / 2f,
                                    boxSize.height / 2f
                                )
                                // Pan by the centroid move, then
                                // zoom around the new centroid:
                                // the content point under the
                                // fingers stays under them.
                                var newOffset =
                                    offset + (centroid - prevCentroid)
                                newOffset =
                                    (centroid - center) *
                                        (1f - newScale / oldScale) +
                                        newOffset * (newScale / oldScale)
                                newOffset = clampOffset(newOffset, newScale)
                                scale = newScale
                                offset = newOffset
                                onScaleChange(newScale)
                                prevCentroid = centroid
                                prevDistance = distance
                                pressed.forEach { it.consume() }
                            } else {
                                prevCentroid = centroid
                                prevDistance = distance
                            }
                            pastSlop = true
                        } else {
                            val change = pressed[0]
                            if (pinch) {
                                // Pinch ended with one finger left:
                                // keep tracking it as a drag.
                                pinch = false
                                pastSlop = true
                                totalDrag = Offset.Zero
                            }
                            val delta = change.positionChange()
                            totalDrag += delta
                            if (!pastSlop) {
                                if (totalDrag.getDistance() < slop) {
                                    // Not a drag yet — a tap. Never
                                    // consume it: the chrome toggle
                                    // owns taps.
                                    continue
                                }
                                pastSlop = true
                            }
                            if (change.positionChangeConsumed()) {
                                // The list (or another handler)
                                // already claimed this move; don't
                                // fight it.
                                continue
                            }
                            if (scale > 1f) {
                                val newOffset =
                                    clampOffset(offset + delta, scale)
                                if (newOffset != offset) {
                                    offset = newOffset
                                    change.consume()
                                }
                                // Else at the pan edge in this
                                // direction: not consumed, so the
                                // list scrolls from here.
                            }
                            // At scale 1: never consume. The list
                            // scrolls vertically; sideways drags
                            // do nothing (no horizontal scroll).
                        }
                    }
                }
                }
            }
    ) {
        content()
    }
}
