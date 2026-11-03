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
 * ComicScreen-style zoom + pan for one page — with one page box
 * as the whole world. This container is the per-page viewport:
 *
 * - Layout and transform are separate. The page's layout size is
 *   its own fit size (width- or height-driven, from that page's
 *   own aspect) and NEVER changes with zoom. Zoom is a GPU
 *   transform of the decoded content inside that box only, and
 *   the layer is clipped to the box (graphicsLayer clip = true),
 *   so a zoomed page can never paint over its neighbours, and
 *   its touch area is exactly its visible area. The old version
 *   had no clip: a zoomed page drew beyond its box and its
 *   overflow belonged, for touch, to the neighbouring items.
 * - The zoom is per page box, never shared. Each container owns
 *   its own PageZoomState (scale + pan), keyed by sessionKey +
 *   zoomPageId, so zooming one page leaves every other page
 *   exactly as it was — including pages of different sizes in
 *   the same strip, where one shared multiplier was always
 *   wrong for at least one of them. There is no global zoom
 *   anymore: the 32bb0313 decision (one scale for the whole
 *   book, kept across page turns) was reversed by the
 *   2026-11-03 zoom-engine order, which requires per-page
 *   independence in strips and a hard reset on page change in
 *   the pager. A strip item's state dies with its composition
 *   (Lazy disposal), so unloaded pages never hold state.
 * - Paged reset. The viewer passes the displayed pager item as
 *   activeSlotKey and each box its own slotKey. When they differ
 *   the box is no longer displayed, so it resets to 1x with zero
 *   pan and reports 1x (so the item's sharp re-render drops back
 *   to 1x too). The reset is tied to the real page change, not
 *   to scroll offsets: a partial swipe that settles back resets
 *   nothing. In strips both keys are null and no auto-reset
 *   happens — vertical keeps its per-page independence instead.
 * - Gesture arbitration, paged vs strip. At 1x a drag is never
 *   consumed, so the pager turns / the list scrolls as before.
 *   While zoomed, a drag pans. In strips (edgeHandoff = true)
 *   a drag at the pan edge is handed back, so the list scrolls
 *   from there — PhotoView's edge handover. In the pager
 *   (edgeHandoff = false) a drag at the pan edge is CONSUMED,
 *   so the pager can never turn the page from a pan: while
 *   zoomed the user pans only, and must zoom back out (pinch
 *   to 1x, or double-tap) before a swipe turns the page. That
 *   lock is what stops the next page from entering a zoomed
 *   page's view. No userScrollEnabled switch is involved: the
 *   container's consumption is the only gate, as before.
 * - Pinch (two fingers) zooms 1x..4x around the pinch focal
 *   point and pans with the centroid, consuming the whole
 *   gesture so the list/pager never moves mid-pinch. The focal
 *   math lives in PageZoomState.focalPan and is unit-tested.
 * - One-finger drag pans ONLY while zoomed (scale > 1). At
 *   scale 1 the drag is never consumed, so the list scrolls
 *   vertically as before — and since there is no horizontal
 *   scroll anymore, a sideways drag at scale 1 simply does
 *   nothing, which is the requested "no left-right scroll".
 * - Double-tap zoom: a double-tap toggles 1x <-> 2x, zooming
 *   around the tap point — the tapped detail lands under the
 *   finger — and it animates there over ~120ms (ComicScreen's
 *   transition duration) instead of snapping.
 * - Taps are never consumed here; the chrome toggle for them
 *   comes from the page item's passive listener (see
 *   NativePageItem), which reports without consuming, so no
 *   detector starves another (the 6c4ee982 regression was an
 *   ancestor detector that consumed; the c78a032 tap inside
 *   this loop never fired on the phone).
 * - The viewer delays its chrome toggle by 300ms to tell a
 *   single tap from the first tap of a double-tap (the same
 *   window PhotoView apps use), so every chrome toggle
 *   now takes 300ms. Without that delay a double-tap would
 *   toggle the chrome twice (a flicker).
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
    // Session key (the book id): the zoom state is remembered
    // under it, so swapping the reader's content in the same
    // composition drops every page's old zoom instead of
    // handing a new book the previous book's scale and pan.
    sessionKey: Any? = null,
    // Paged reset keys (see the header note): this box's pager
    // item index, and the currently displayed pager item index.
    // Both null in strips, where no auto-reset applies.
    slotKey: Int? = null,
    activeSlotKey: Int? = null,
    // Session-scoped page-box label for the state (see
    // PageZoomState): "pager-3-half-0", "strip-12". Never
    // persisted anywhere.
    zoomPageId: String = "",
    // Edge handover: true in strips (a drag at the pan edge
    // scrolls the list), false in the pager (a drag at the
    // pan edge is consumed, so the pager never turns from
    // a pan while zoomed).
    edgeHandoff: Boolean = true,
    // No tap reporting here anymore: the page item's outer
    // Box reports taps with a passive listener (see
    // NativePageItem), which can neither starve nor be
    // starved by this loop. This loop still tracks tap-ups
    // itself, but only for its double-tap zoom below — and
    // it reports a confirmed double-tap (onDoubleTap) so the
    // viewer drops the pending chrome toggle for exactly
    // that case, never for a second tap on its own.
    // Reports the SETTLED scale (1..4) — once per gesture, when
    // the fingers lift, plus once per double-tap, plus once on
    // a paged reset back to 1x. The caller uses it to re-render
    // the page sharper once the settled scale passes a
    // threshold (ComicScreen's tile refinement, coarse-grained:
    // a full-page re-render at 2x display width instead of
    // per-tile re-decodes — same visible result, far less
    // machinery). It deliberately does NOT fire per pinch move:
    // a mid-gesture re-render stalls the gesture itself (see
    // the performance note above).
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
    // One state per page box: nothing here is shared with any
    // other page, and the remember keys (session + page box)
    // drop the state when the content or the box changes.
    var zoomState by remember(sessionKey, zoomPageId) {
        mutableStateOf(PageZoomState(pageId = zoomPageId))
    }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoomAnimTarget by remember(sessionKey, zoomPageId) {
        mutableStateOf<ZoomAnimTarget?>(null)
    }
    // Last tap-up, for double-tap detection: a second tap-up
    // within 300ms and near the first is a double-tap, not two
    // single taps. Reset to 0 after a double-tap, so a third
    // quick tap starts fresh (a triple-tap is not a gesture).
    var lastTapUpMs by remember(sessionKey, zoomPageId) { mutableStateOf(0L) }
    var lastTapPos by remember(sessionKey, zoomPageId) {
        mutableStateOf(Offset.Zero)
    }
    // Scale last reported to onScaleChange, so a gesture that
    // ends at the same scale it started at reports nothing.
    var lastReportedScale by remember(sessionKey, zoomPageId) {
        mutableFloatStateOf(1f)
    }

    fun clampCurrent(oX: Float, oY: Float, s: Float): Pair<Float, Float> =
        clampPan(oX, oY, s, boxSize.width.toFloat(), boxSize.height.toFloat())

    // Paged reset: when the displayed pager item moves away
    // from this box's item, this box is no longer displayed,
    // so its zoom and pan return to the fit-size identity and
    // the settled scale is reported once as 1x. Guarded, so a
    // box that was never zoomed reports nothing, and a
    // double-tap animation still running on the outgoing box
    // is cancelled instead of overwriting the reset after it.
    // A partial swipe that settles back never changes
    // activeSlotKey, so it resets nothing.
    LaunchedEffect(activeSlotKey) {
        if (shouldResetZoom(slotKey, activeSlotKey) &&
            (zoomState.isZoomed || zoomState.offsetX != 0f || zoomState.offsetY != 0f)
        ) {
            zoomAnimTarget = null
            zoomState = zoomState.reset()
            if (lastReportedScale != 1f) {
                lastReportedScale = 1f
                onScaleChange(1f)
            }
        }
    }

    // Viewport change while zoomed (rotation, window resize):
    // the box changed size, so the old pan may now exceed the
    // new clamp. Re-clamp instead of jumping: at 1x the clamp
    // is zero anyway, so an unzoomed page is untouched, and
    // the first layout (box Zero -> real) is a no-op because
    // the offset starts at zero.
    LaunchedEffect(boxSize) {
        if (boxSize.width > 0 && boxSize.height > 0) {
            val (x, y) = clampCurrent(zoomState.offsetX, zoomState.offsetY, zoomState.scale)
            if (x != zoomState.offsetX || y != zoomState.offsetY) {
                zoomState = zoomState.copy(offsetX = x, offsetY = y)
            }
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
            val (x, y) = doubleTapAnimOffset(
                target.fromScale, target.fromOffset.x, target.fromOffset.y,
                value, target.tapPos.x, target.tapPos.y,
                center.x, center.y,
                boxSize.width.toFloat(), boxSize.height.toFloat()
            )
            zoomState = zoomState.copy(scale = value, offsetX = x, offsetY = y)
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
            // clip = true is the per-page viewport: the zoomed
            // content is cut at this box's own bounds and can
            // never paint over a neighbouring page.
            .graphicsLayer {
                clip = true
                scaleX = zoomState.scale
                scaleY = zoomState.scale
                translationX = zoomState.offsetX
                translationY = zoomState.offsetY
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
                                val oldScale = zoomState.scale
                                val newScale =
                                    coerceZoomScale(oldScale * (distance / prevDistance))
                                val center = Offset(
                                    boxSize.width / 2f,
                                    boxSize.height / 2f
                                )
                                // Pan by the centroid move, then
                                // zoom around the new centroid:
                                // the content point under the
                                // fingers stays under them.
                                // (Pure math: PageZoomState.focalPan.)
                                val (nx, ny) = focalPan(
                                    oldScale, zoomState.offsetX, zoomState.offsetY,
                                    centroid.x - prevCentroid.x,
                                    centroid.y - prevCentroid.y,
                                    newScale, centroid.x, centroid.y,
                                    center.x, center.y,
                                    boxSize.width.toFloat(), boxSize.height.toFloat()
                                )
                                zoomState = zoomState.copy(
                                    scale = newScale, offsetX = nx, offsetY = ny
                                )
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
                            if (zoomState.scale > 1f) {
                                val (nx, ny) = clampCurrent(
                                    zoomState.offsetX + delta.x,
                                    zoomState.offsetY + delta.y,
                                    zoomState.scale
                                )
                                if (nx != zoomState.offsetX || ny != zoomState.offsetY) {
                                    zoomState = zoomState.copy(offsetX = nx, offsetY = ny)
                                    change.consume()
                                } else if (!edgeHandoff) {
                                    // Paged, at the pan edge: consume
                                    // anyway, so the drag can never
                                    // reach the pager and turn the
                                    // page from a pan. Strips keep
                                    // the old behaviour below: the
                                    // unconsumed drag scrolls the
                                    // list from the edge.
                                    change.consume()
                                }
                                // Else (strips) at the pan edge in
                                // this direction: not consumed, so
                                // the list scrolls from here.
                            }
                            // At scale 1: never consume. The pager
                            // turns / the list scrolls vertically;
                            // sideways drags do nothing (no
                            // horizontal scroll).
                        }
                    }
                    // The gesture ended (no pointers pressed).
                    // Settle first: a pinch/drag that moved the
                    // scale reports it exactly once here, so the
                    // caller's re-render runs after the gesture,
                    // never during it. A tap has not moved the
                    // scale, so this reports nothing for taps.
                    if (zoomState.scale != lastReportedScale) {
                        lastReportedScale = zoomState.scale
                        onScaleChange(zoomState.scale)
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
                                fromScale = zoomState.scale,
                                fromOffset = Offset(zoomState.offsetX, zoomState.offsetY),
                                toScale = doubleTapTargetScale(zoomState.scale),
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
