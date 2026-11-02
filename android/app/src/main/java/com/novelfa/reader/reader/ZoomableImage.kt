package com.novelfa.reader.reader

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 *   tap point — the tapped detail lands under the finger — and
 *   it animates there over ~120ms (ComicScreen's transition
 *   duration) instead of snapping. The trade-off, stated
 *   plainly: the viewer delays its chrome toggle by 300ms to
 *   tell a single tap from the first tap of a double-tap (the
 *   same window PhotoView apps use), so every chrome toggle
 *   now takes 300ms. Without that delay a double-tap would
 *   toggle the chrome twice (a flicker).
 * - Global zoom: the scale is shared by every page in the
 *   book (see zoomScale), not kept per page — a zoom on one
 *   page applies to all pages, and turning the page no longer
 *   snaps back to 1x.
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

/**
 * One double-tap zoom animation: from the scale/offset the
 * page had when the double-tap landed, to the target scale,
 * around the tap point. Held in state (not in the gesture
 * loop) so the animation itself can run in a composable
 * LaunchedEffect, where Animatable is allowed — the loop
 * runs in a restricted scope where it is not.
 */
private data class ZoomAnimTarget(
    val fromScale: Float,
    val fromOffset: Offset,
    val toScale: Float,
    val tapPos: Offset
)

@Composable
fun ZoomablePageContainer(
    modifier: Modifier,
    // The viewer's global zoom (1..4), shared by every page
    // in this book: when one page is pinched or double-tapped
    // to a settled scale, the viewer writes it here, and
    // every page — the current one, its neighbours, and any
    // page composed later — shows that same scale. Before
    // this, each page kept its own scale, so turning the
    // page snapped back to 1x and zoom felt per-page only.
    // The sync below is guarded by equality, so a page
    // reporting its own settled scale does not reset itself.
    zoomScale: Float = 1f,
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
    // Plain state, not Animatable, for the gesture loop: the
    // loop runs in a restricted suspension scope, where
    // Animatable's suspend snapTo/stop are not callable, so
    // direct assignment is all the loop needs. The double-tap
    // animation below is the exception, and it lives in a
    // composable LaunchedEffect (where Animatable IS allowed),
    // driven by zoomAnimTarget state the loop only sets.
    // Initial scale is the viewer's global zoom, so a page
    // composed after a zoom (a turned-to page, a strip page
    // below) starts at that zoom instead of snapping to 1x.
    var scale by remember { mutableFloatStateOf(zoomScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoomAnimTarget by remember { mutableStateOf<ZoomAnimTarget?>(null) }
    // Last tap-up, for double-tap detection: a second tap-up
    // within 300ms and near the first is a double-tap, not two
    // single taps. Reset to 0 after a double-tap, so a third
    // quick tap starts fresh (a triple-tap is not a gesture).
    var lastTapUpMs by remember { mutableStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    // Scale last reported to onScaleChange, so a gesture that
    // ends at the same scale it started at reports nothing.
    var lastReportedScale by remember { mutableFloatStateOf(zoomScale) }

    fun clampOffset(o: Offset, s: Float): Offset {
        val maxX = ((boxSize.width * s - boxSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((boxSize.height * s - boxSize.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    // Global-zoom sync: when the viewer writes a new global
    // zoom (another page settled there), this page follows.
    // Guarded by equality, so a page reporting its own
    // settled scale does not reset its own offset mid-gesture.
    // A zoom back to 1x resets the pan offset: at 1x the
    // clamp is zero anyway, so a stale offset would only
    // shift the page for one frame.
    LaunchedEffect(zoomScale) {
        if (scale != zoomScale) {
            scale = zoomScale
            offset = if (zoomScale <= 1f) {
                Offset.Zero
            } else {
                clampOffset(offset, zoomScale)
            }
            lastReportedScale = zoomScale
        }
    }

    // Double-tap zoom animation, ComicScreen's transition
    // duration (~120ms): the scale animates from the scale
    // the page had to the double-tap target, around the tap
    // point, instead of snapping — the snap was the visible
    // "zoom is not smooth" hitch. The settled scale is
    // reported only when the animation completes, never per
    // frame, so the sharper re-render still runs once, after
    // the gesture, never during it. Cancelling (a new down
    // sets the target to null) leaves the scale at its
    // intermediate value, and the next gesture starts from
    // there — the animation never snaps back.
    LaunchedEffect(zoomAnimTarget) {
        val target = zoomAnimTarget ?: return@LaunchedEffect
        val center = Offset(
            boxSize.width / 2f,
            boxSize.height / 2f
        )
        // Plain animate(), not Animatable: Animatable's
        // constructor needs an explicit type converter in
        // this Compose version, while animate() handles
        // Float directly — same 120ms tween, same per-frame
        // focal math, no converter boilerplate.
        animate(
            initialValue = target.fromScale,
            targetValue = target.toScale,
            animationSpec = tween(durationMillis = 120)
        ) { value, _ ->
            scale = value
            offset = clampOffset(
                (target.tapPos - center) *
                    (1f - value / target.fromScale) +
                    target.fromOffset * (value / target.fromScale),
                value
            )
        }
        lastReportedScale = target.toScale
        onScaleChange(target.toScale)
        zoomAnimTarget = null
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
                    // A new gesture cancels any double-tap
                    // animation still running: the scale stays
                    // at its intermediate value, and this
                    // gesture starts from there.
                    zoomAnimTarget = null
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
                            // pinch). The zoom itself is NOT set
                            // here: the animation effect above
                            // animates it over 120ms and reports
                            // the settled scale only when it
                            // completes, so the sharper re-render
                            // kicks in once, after the zoom.
                            lastTapUpMs = 0L
                            zoomAnimTarget = ZoomAnimTarget(
                                fromScale = scale,
                                fromOffset = offset,
                                toScale = if (scale > 1.05f) 1f else 2f,
                                tapPos = downPos
                            )
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
