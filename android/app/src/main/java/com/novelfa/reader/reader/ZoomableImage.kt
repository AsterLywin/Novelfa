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
 * - Taps are never consumed here; the chrome toggle for them
 *   comes from the page item's passive listener (see
 *   NativePageItem), which reports without consuming, so no
 *   detector starves another (the 6c4ee982 regression was an
 *   ancestor detector that consumed; the c78a032 tap inside
 *   this loop never fired on the phone).
 * - Double-tap zoom (ComicScreen's double-tap modes, simplified
 *   to one): a double-tap toggles 1x <-> 2x, zooming around the
 *   tap point — the tapped detail lands under the finger. The
 *   trade-off, stated plainly: the viewer delays its chrome
 *   toggle by 300ms to tell a single tap from the first tap of
 *   a double-tap (the same window PhotoView apps use), so every
 *   chrome toggle now takes 300ms. Without that delay a
 *   double-tap would toggle the chrome twice (a flicker).
 *
 * Limits, stated plainly: the touch area is the page's layout
 * box — a zoomed image draws beyond it (graphicsLayer does not
 * resize layout), and touches on that overflow belong to the
 * neighbouring items/list, not to this page. The box is the
 * full strip width and the page's full height, so in practice
 * the zoomed page stays touchable over most of the screen.
 *
 * Performance, and why the old version felt slow on a phone:
 * two separate costs stacked. (1) Scale/offset used to be
 * passed as graphicsLayer() PARAMETERS, so every pointer move
 * recomposed this container and its Image — a full bitmap
 * re-draw per move, on top of the layer transform. Now they
 * are read only inside the graphicsLayer {} block, which
 * updates the layer's transform without recomposing the
 * content at all: pinch and pan are a GPU transform of a
 * static texture, as they should be. (2) onScaleChange used
 * to fire on every pinch move, so a pinch crossing 1.75x
 * kicked off a full page re-decode + resize + GPU texture
 * upload MID-GESTURE, and every in/out crossing did it
 * again — the hitch the user felt as "zooming is slow".
 * Now the caller is told the scale only once the gesture
 * settles (fingers up, or a double-tap): during the pinch
 * the 1x bitmap is simply upscaled by the GPU (soft for a
 * moment, fast throughout), and the sharper re-render lands
 * once, after the gesture, if the settled scale needs it.
 */
private const val MAX_ZOOM = 4f

@Composable
fun ZoomablePageContainer(
    modifier: Modifier,
    // No tap reporting here anymore: the page item's outer
    // Box reports taps with a passive listener (see
    // NativePageItem), which can neither starve nor be
    // starved by this loop. This loop still tracks tap-ups
    // itself, but only for its double-tap zoom below — and
    // it reports a confirmed double-tap (onDoubleTap) so the
    // viewer drops the pending chrome toggle for exactly
    // that case, never for a second tap on its own.
    // Reports the SETTLED scale (1..4) — once per gesture, when
    // the fingers lift, plus once per double-tap. The caller
    // uses it to re-render the page sharper once the settled
    // scale passes a threshold (ComicScreen's tile refinement,
    // coarse-grained: a full-page re-render at 2x display
    // width instead of per-tile re-decodes — same visible
    // result, far less machinery). It deliberately does NOT
    // fire per pinch move: a mid-gesture re-render stalls the
    // gesture itself (see the performance note above).
    onScaleChange: (Float) -> Unit = {},
    // Confirmed double-tap (same spot, within 300ms): the
    // viewer uses it to suppress the pending chrome toggle.
    onDoubleTap: () -> Unit = {},
    // Single tap, reported here — the standard place for
    // it in a zoomable image (PhotoView pattern): this
    // loop already owns pinch, pan and double-tap zoom, so
    // it owns the single tap too, instead of a separate
    // ancestor listener. The viewer delays the toggle 300ms
    // and a confirmed double-tap suppresses it.
    onTap: () -> Unit = {},
    content: @Composable () -> Unit
) {
    // Plain state, not Animatable: the gesture loop runs in a
    // restricted suspension scope, where Animatable's suspend
    // snapTo/stop are not callable — and nothing here animates
    // (double-tap zoom snaps, it does not animate), so direct
    // assignment is all the loop needs.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // Last tap-up, for double-tap detection: a second tap-up
    // within 300ms and near the first is a double-tap, not two
    // single taps. Reset to 0 after a double-tap, so a third
    // quick tap starts fresh (a triple-tap is not a gesture).
    var lastTapUpMs by remember { mutableStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    // Scale last reported to onScaleChange, so a gesture that
    // ends at the same scale it started at reports nothing.
    var lastReportedScale by remember { mutableFloatStateOf(1f) }

    fun clampOffset(o: Offset, s: Float): Offset {
        val maxX = ((boxSize.width * s - boxSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((boxSize.height * s - boxSize.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            // Block form, not parameters: state read here
            // updates the layer transform without recomposing
            // the Image below (see the performance note above).
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                while (true) {
                    // Plain awaitFirstDown, exactly as before
                    // 343371d7: that build's requireUnconsumed
                    // = false + upConsumed gate broke zoom on
                    // the phone (10s on page 8, no zoom, no
                    // diag message) — the pager/list consumes
                    // the tap up, so any gate on consumption
                    // kills both the single tap and the
                    // double-tap timer. This loop therefore
                    // reports taps unconditionally, and does
                    // NOT check consumption at all.
                    val down = awaitFirstDown()
                    val downPos = down.position
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
                                // No onScaleChange here: the
                                // settled scale is reported once
                                // at gesture end below, so a
                                // mid-pinch re-render never
                                // stalls the gesture.
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
                    // The gesture ended (no pointers pressed).
                    // Settle first: a pinch/drag that moved the
                    // scale reports it exactly once here, so the
                    // caller's re-render runs after the gesture,
                    // never during it. A tap has not moved the
                    // scale, so this reports nothing for taps.
                    if (scale != lastReportedScale) {
                        lastReportedScale = scale
                        onScaleChange(scale)
                    }
                    // A tap is a gesture that never passed slop
                    // and never pinched: it was never consumed
                    // above. Only this container knows the
                    // tap landed on THIS page, so it owns
                    // both the double-tap zoom decision below
                    // and, since 2026-11-02, the single-tap
                    // report itself. Consumption is NOT
                    // checked here: the pager/list consumes
                    // the tap up (proven by 343371d7), so a
                    // consumed-up check would kill the tap
                    // and the double-tap timer with it.
                    if (!pastSlop) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapUpMs < 300 &&
                            (downPos - lastTapPos).getDistance() < slop * 4
                        ) {
                            // Double-tap: 1x <-> 2x around the
                            // tap point (same focal math as the
                            // pinch). onScaleChange fires so the
                            // sharper re-render kicks in at 2x.
                            lastTapUpMs = 0L
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                                lastReportedScale = 1f
                                onScaleChange(1f)
                            } else {
                                val newScale = 2f
                                val center = Offset(
                                    boxSize.width / 2f,
                                    boxSize.height / 2f
                                )
                                offset = clampOffset(
                                    (downPos - center) *
                                        (1f - newScale / scale) +
                                        offset * (newScale / scale),
                                    newScale
                                )
                                scale = newScale
                                lastReportedScale = newScale
                                onScaleChange(newScale)
                            }
                            // Confirmed double-tap: the viewer
                            // drops the pending chrome toggle
                            // for this one (a lone second tap
                            // never suppresses anything).
                            onDoubleTap()
                        } else {
                            // Single page tap: report it for
                            // the chrome toggle (the viewer
                            // waits 300ms, so a second tap
                            // that turns out to be a double
                            // still suppresses it), and arm
                            // the double-tap timer exactly as
                            // before 343371d7 — that build's
                            // consumed-up gate is what broke
                            // double-tap zoom, so it is gone.
                            onTap()
                            lastTapUpMs = now
                            lastTapPos = downPos
                        }
                    }
                }
                }
            }
    ) {
        content()
    }
}
