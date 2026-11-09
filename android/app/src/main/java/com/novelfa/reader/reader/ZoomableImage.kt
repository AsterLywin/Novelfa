package com.novelfa.reader.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
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
 * - Layout and transform are separate — in paged mode. The
 *   page's layout size is its own fit size (width- or
 *   height-driven, from that page's own aspect) and NEVER
 *   changes with zoom. Zoom is a GPU transform of the decoded
 *   content inside that box only, and the layer is clipped to
 *   the box (graphicsLayer clip = true), so a zoomed page can
 *   never paint over its neighbours, and its touch area is
 *   exactly its visible area. The old version had no clip: a
 *   zoomed page drew beyond its box and its overflow belonged,
 *   for touch, to the neighbouring items. In strips the rule
 *   is the opposite (see the one-canvas note below): the
 *   LAYOUT scales — each item is its fit size x the shared
 *   strip scale — and the layer only carries the shared
 *   cross-axis pan, so the pages stay stuck together with no
 *   gap and no overlap by construction.
 * - One-canvas strip zoom (2026-11-05). A paged container
 *   owns its own PageZoomState (scale + pan), keyed by
 *   sessionKey + zoomPageId, so zooming one page leaves every
 *   other page exactly as it was, and the zoom resets when the
 *   displayed page changes. A strip is ONE box instead: every
 *   strip item renders the ONE shared state the viewer owns
 *   for the whole strip (passed in as externalZoomState), and
 *   a pinch zooms ALL pages together, uniformly, around the
 *   pinch focal point in strip coordinates — the main-axis
 *   pan IS the list scroll, the cross-axis pan is one shared
 *   offset for all pages. Two earlier strip models failed on
 *   the phone and are gone: a shared GPU scale per box
 *   (cfa4beaf + 1a143f8c) made every page pivot around its own
 *   centre while the focal math ran in viewport geometry, so
 *   the content on both sides of every page boundary crowded
 *   toward that boundary — the "pulled into each other" the
 *   phone showed on vc7; per-page zoom (13ad461d) zoomed each
 *   page on its own, which is the original complaint all over
 *   again (vc8). The one-canvas model fixes both by
 *   construction: the zoom changes the layout, so pages can
 *   neither gap nor overlap. There is still no global zoom
 *   for the whole book: the 32bb0313 decision (one scale kept
 *   across page turns) stays reversed.
 * - Paged reset. The viewer passes the displayed pager item as
 *   activeSlotKey and each box its own slotKey. When they differ
 *   the box is no longer displayed, so it resets to 1x with zero
 *   pan. The reset is tied to the real page change, not
 *   to scroll offsets: a partial swipe that settles back resets
 *   nothing. In strips both keys are null and no auto-reset
 *   happens — the strip keeps each page's own zoom instead.
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
 *   In strips this box does NOT pinch: the strip-level
 *   detector on the viewer owns every strip pinch (see
 *   NativeImageBookViewer.stripPinchDetector), because a box
 *   only ever sees the pointers inside its own bounds — a
 *   pinch straddling two pages reached neither box as a
 *   pinch — and it writes the ONE shared strip state (scale +
 *   shared cross pan) and scrolls the list, so the whole
 *   strip zooms as one box. In the pager the pinch still
 *   lives here.
 * - One-finger drag pans ONLY while zoomed (scale > 1). At
 *   scale 1 the drag is never consumed, so the list scrolls
 *   vertically as before — and since there is no horizontal
 *   scroll anymore, a sideways drag at scale 1 simply does
 *   nothing, which is the requested "no left-right scroll".
 *   In strips the main-axis drag is NEVER consumed even while
 *   zoomed — the list scroll through the taller strip IS the
 *   pan (which is also why panning while zoomed is fast now:
 *   it is a native list scroll, not a per-box pan) — and only
 *   the cross-axis drag pans the shared offset, while zoomed.
 * - Double-tap zoom: a double-tap toggles 1x <-> 2x, zooming
 *   around the tap point — the tapped detail lands under the
 *   finger — and it animates there over ~120ms (ComicScreen's
 *   transition duration) instead of snapping. In strips the
 *   toggle moves the WHOLE strip (shared scale, list scroll,
 *   shared cross offset), not one page.
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
 * static texture, as they should be. (2) The settled scale
 * used to trigger a full page re-render (zip stream + decode
 * + Sobel crop + resize + sharpen + filters) — in strips for
 * every visible page at once — each time a pinch settled past
 * 1.75x or fell back under 1.25x, so zooming in AND out both
 * froze the reader after the gesture. That is gone since
 * 2026-11-09 (ComicScreen's rule, decoded from the
 * ComicScreen 2309 source: decode once, zoom with the matrix
 * only — see NativePageItem): the container reports nothing
 * on settle, the bitmap is simply upscaled by the GPU while
 * zoomed, and no re-render ever lands. The trade-off is
 * ComicScreen's too: at 2-4x the page goes soft, and the
 * sharp path is the sheet's zoom steps, which re-render the
 * book at a higher decode width on a deliberate tap.
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

/**
 * One-canvas strip host (2026-11-05): everything a strip
 * container needs to zoom the WHOLE strip as one box. The
 * viewer builds one per strip (not per page) and hands it to
 * every strip item through NativePageItem. Null in paged mode,
 * where the container owns its own state and none of the
 * strip math below runs.
 *
 * - [listState] is the strip's LazyColumn/LazyRow state: the
 *   main-axis pan IS its scroll, so the pinch detector and the
 *   double-tap animation compensate the focal point by
 *   scrolling it (see PageZoomState.stripScrollDelta).
 * - [horizontal] is the strip direction (LazyRow vs LazyColumn).
 * - [viewportWidthPx]/[viewportHeightPx] are the strip viewport.
 * - [fitFraction] is the page's fit size as a fraction of the
 *   viewport on its fit axis (the padding shrink: 1 without
 *   padding) — the cross-axis anchor math needs the item's
 *   real width, which the list layout does not report.
 */
data class StripZoomHost(
    val listState: LazyListState,
    val horizontal: Boolean,
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val fitFraction: Float
)

/**
 * One-canvas double-tap zoom animation target: from the shared
 * scale the strip had when the double-tap landed, to the
 * double-tap target, around the tap point (in this box's
 * coordinates). The effect below animates the shared scale and
 * moves the whole strip around that point (list scroll +
 * shared cross offset) instead of animating a per-box offset.
 */
private data class StripZoomAnimTarget(
    val fromScale: Float,
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
    // Externally-owned zoom state, passed in by the viewer
    // (paged: null, the box owns its own; strips: the ONE
    // shared strip state — see the header note). With an
    // external state this box never pinches on its own: the
    // strip-level detector owns every strip pinch.
    externalZoomState: MutableState<PageZoomState>? = null,
    // One-canvas strip host (see StripZoomHost): non-null
    // exactly in strips, where the zoom is the shared strip
    // scale and this box's layout is its fit size x that
    // scale. Null in paged mode.
    stripHost: StripZoomHost? = null,
    // This box's page index inside the strip (its
    // NativePageItem page): the double-tap animation looks
    // the item up in the strip layout by it. Null in paged
    // mode, where no strip animation runs.
    stripPageIndex: Int? = null,
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
    // The box's own state (paged mode). Strips pass a shared
    // state instead (see externalZoomState above): the box's own
    // state is still remembered unconditionally — a trivial
    // object — so the remember call order never depends on
    // which branch is taken. (A local var with custom
    // accessors is not valid Kotlin, so the selection is a
    // plain holder read instead.)
    val ownZoomState = remember(sessionKey, zoomPageId) {
        mutableStateOf(PageZoomState(pageId = zoomPageId))
    }
    // One state per page box in the pager; one shared state
    // for the whole strip when the viewer passes it in. Every
    // read and write below goes through here, so the gesture
    // loop, the double-tap animation and the reset all work
    // on the shared state in strips without knowing it.
    val zoomStateHolder = externalZoomState ?: ownZoomState
    var zoomState by zoomStateHolder
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoomAnimTarget by remember(sessionKey, zoomPageId) {
        mutableStateOf<ZoomAnimTarget?>(null)
    }
    // One-canvas mode: a non-null stripHost means this box is
    // a strip item, its layout is fit size x the shared scale,
    // and the zoom/pan below run on the shared strip state.
    val layoutZoom = stripHost != null
    // Latest host for the gesture loop: pointerInput(Unit)
    // captures its values once, so the loop reads the host
    // through this state instead (rotation / padding changes
    // hand it a new host, and the loop must see the new one).
    val hostState = rememberUpdatedState(stripHost)
    var stripZoomAnimTarget by remember(sessionKey, zoomPageId) {
        mutableStateOf<StripZoomAnimTarget?>(null)
    }
    // Last tap-up, for double-tap detection: a second tap-up
    // within 300ms and near the first is a double-tap, not two
    // single taps. Reset to 0 after a double-tap, so a third
    // quick tap starts fresh (a triple-tap is not a gesture).
    var lastTapUpMs by remember(sessionKey, zoomPageId) { mutableStateOf(0L) }
    var lastTapPos by remember(sessionKey, zoomPageId) {
        mutableStateOf(Offset.Zero)
    }

    fun clampCurrent(oX: Float, oY: Float, s: Float): Pair<Float, Float> =
        clampPan(oX, oY, s, boxSize.width.toFloat(), boxSize.height.toFloat())

    // Paged reset: when the displayed pager item moves away
    // from this box's item, this box is no longer displayed,
    // so its zoom and pan return to the fit-size identity.
    // Guarded, so a box that was never zoomed resets nothing,
    // and a double-tap animation still running on the outgoing
    // box is cancelled instead of overwriting the reset after
    // it. A partial swipe that settles back never changes
    // activeSlotKey, so it resets nothing.
    LaunchedEffect(activeSlotKey) {
        if (shouldResetZoom(slotKey, activeSlotKey) &&
            (zoomState.isZoomed || zoomState.offsetX != 0f || zoomState.offsetY != 0f)
        ) {
            zoomAnimTarget = null
            zoomState = zoomState.reset()
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
            if (layoutZoom) {
                // One-canvas: only the shared cross offset can
                // exceed its bound after a viewport change;
                // the scale lives in the layout and the main
                // axis is the list scroll.
                val host = stripHost!!
                val (boxCross, viewportCross) = if (host.horizontal) {
                    boxSize.height.toFloat() to host.viewportHeightPx
                } else {
                    boxSize.width.toFloat() to host.viewportWidthPx
                }
                val maxCross = stripMaxCrossOffset(boxCross, viewportCross)
                if (host.horizontal) {
                    val y = zoomState.offsetY.coerceIn(-maxCross, maxCross)
                    if (y != zoomState.offsetY) zoomState = zoomState.copy(offsetY = y)
                } else {
                    val x = zoomState.offsetX.coerceIn(-maxCross, maxCross)
                    if (x != zoomState.offsetX) zoomState = zoomState.copy(offsetX = x)
                }
            } else {
                val (x, y) = clampCurrent(zoomState.offsetX, zoomState.offsetY, zoomState.scale)
                if (x != zoomState.offsetX || y != zoomState.offsetY) {
                    zoomState = zoomState.copy(offsetX = x, offsetY = y)
                }
            }
        }
    }

    // Double-tap zoom animation, ComicScreen's transition
    // duration (~120ms): the scale animates from the scale
    // the page had to the double-tap target, around the tap
    // point, instead of snapping — the snap was the visible
    // "zoom is not smooth" hitch. Nothing is reported when the
    // animation completes: there is no re-render anymore
    // (ComicScreen's rule — see the performance note above).
    // Cancelling (a new down sets the target to null) leaves
    // the scale at its intermediate value, and the next gesture
    // starts from there — the animation never snaps back.
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
        zoomAnimTarget = null
    }

    // One-canvas double-tap animation, same ~120ms as the
    // per-page one: the scale animates from the shared scale
    // the strip had to the double-tap target, around the tap
    // point — and the WHOLE strip moves with it, so the tapped
    // detail stays under the finger throughout: the main axis
    // by scrolling the list, the cross axis by the shared
    // offset (see PageZoomState.stripScrollDelta /
    // stripCrossOffset). A manual frame loop, not animate():
    // the loop must scroll the list per frame, and scrollBy
    // is suspend — animate()'s per-frame block is not. If the
    // tapped page has scrolled out of the layout by the time
    // the effect runs, the scale is set directly instead.
    LaunchedEffect(stripZoomAnimTarget) {
        val target = stripZoomAnimTarget ?: return@LaunchedEffect
        val host = stripHost ?: return@LaunchedEffect
        val page = stripPageIndex ?: return@LaunchedEffect
        // Focal point in viewport coordinates, resolved once
        // at animation start: the item's list offset plus the
        // tap inside its box. The viewport point itself never
        // moves during the animation — the scroll and the cross
        // offset below keep the content under it.
        val item = host.listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == page }
        if (item == null) {
            zoomState = zoomState.copy(scale = target.toScale)
            stripZoomAnimTarget = null
            return@LaunchedEffect
        }
        val focalMain = item.offset.toFloat() +
            if (host.horizontal) target.tapPos.x else target.tapPos.y
        val focalCross = if (host.horizontal) {
            host.viewportHeightPx / 2f + (target.tapPos.y - boxSize.height / 2f)
        } else {
            host.viewportWidthPx / 2f + (target.tapPos.x - boxSize.width / 2f)
        }
        val startNanos = withFrameNanos { it }
        var prevScale = target.fromScale
        while (true) {
            val nowNanos = withFrameNanos { it }
            val elapsedMs = (nowNanos - startNanos) / 1_000_000f
            val t = (elapsedMs / 120f).coerceIn(0f, 1f)
            val eased = FastOutSlowInEasing.transform(t)
            val value = target.fromScale +
                (target.toScale - target.fromScale) * eased
            // Main axis: scroll so the focal content point
            // stays under the finger.
            val currentItem = host.listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == page }
            if (currentItem != null) {
                val delta = stripScrollDelta(
                    currentItem.offset.toFloat(), focalMain, prevScale, value
                )
                if (delta != 0f) host.listState.scroll { scrollBy(delta) }
            }
            // Cross axis: the shared offset around the same
            // focal point.
            val cross = if (host.horizontal) zoomState.offsetY else zoomState.offsetX
            val newCross = stripCrossOffset(
                prevScale, cross, focalCross,
                if (host.horizontal) host.viewportHeightPx else host.viewportWidthPx,
                host.fitFraction, value
            )
            zoomState = if (host.horizontal) {
                zoomState.copy(scale = value, offsetY = newCross)
            } else {
                zoomState.copy(scale = value, offsetX = newCross)
            }
            prevScale = value
            if (t >= 1f) break
        }
        stripZoomAnimTarget = null
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
                if (layoutZoom) {
                    // One-canvas strip: the LAYOUT already
                    // scales (this box is its fit size x the
                    // shared scale, see NativePageItem), so
                    // this layer must NOT scale again — it
                    // only carries the shared cross-axis pan.
                    // The main-axis pan is the list scroll.
                    // The cross offset is clamped to this
                    // box's own overflow over the viewport, so
                    // no edge of the strip can enter the
                    // viewport (the same half-overflow bound
                    // the per-page clamp uses, against the
                    // viewport instead of the box).
                    val host = stripHost!!
                    val (boxCross, viewportCross) = if (host.horizontal) {
                        boxSize.height.toFloat() to host.viewportHeightPx
                    } else {
                        boxSize.width.toFloat() to host.viewportWidthPx
                    }
                    val maxCross = stripMaxCrossOffset(boxCross, viewportCross)
                    scaleX = 1f
                    scaleY = 1f
                    if (host.horizontal) {
                        translationX = 0f
                        translationY =
                            zoomState.offsetY.coerceIn(-maxCross, maxCross)
                    } else {
                        translationX =
                            zoomState.offsetX.coerceIn(-maxCross, maxCross)
                        translationY = 0f
                    }
                } else {
                    // Display clamp: with a strip-shared state the
                    // offset was clamped against the page it was
                    // panned on, which may exceed a shorter page's
                    // own range — clamp what is actually drawn to
                    // this box, so no page can show blank space at
                    // its edge. For per-page boxes this is the same
                    // clamp the gesture loop already applies on
                    // every write, so it is a no-op there.
                    val (dx, dy) = clampPan(
                        zoomState.offsetX, zoomState.offsetY, zoomState.scale,
                        boxSize.width.toFloat(), boxSize.height.toFloat()
                    )
                    scaleX = zoomState.scale
                    scaleY = zoomState.scale
                    translationX = dx
                    translationY = dy
                }
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
                    stripZoomAnimTarget = null
                    var pinch = false
                    var pastSlop = false
                    var prevCentroid = Offset.Zero
                    // Gesture-start reference for the pinch
                    // scale (see PageZoomState.pinchTargetScale)
                    // and the smoothed span it is computed from
                    // (see smoothPinchSpan): both reset to the
                    // live values whenever a pinch starts, so a
                    // new gesture never jumps from a stale
                    // reference.
                    var pinchStartScale = 1f
                    var pinchStartSpan = 0f
                    var pinchSmoothSpan = 0f
                    var totalDrag = Offset.Zero
                    val slop = viewConfiguration.touchSlop
                    var gestureActive = true
                    while (gestureActive) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // Strips: this box never pinches on
                            // its own — the strip-level detector
                            // (see the viewer) owns EVERY strip
                            // pinch, including one whose fingers
                            // straddle two pages, and writes the
                            // ONE shared strip state — so this
                            // page's scale would be zoomed twice
                            // per frame if this branch ran too.
                            // Just mark the gesture as a drag, so
                            // no tap is reported for it when the
                            // fingers lift, and let the detector
                            // handle the moves. Paged mode has no
                            // external state and pinches here.
                            if (externalZoomState != null) {
                                pastSlop = true
                                continue
                            }
                            // Pinch: zoom around the focal point,
                            // pan with the centroid, consume all.
                            val c1 = pressed[0].position
                            val c2 = pressed[1].position
                            val centroid = (c1 + c2) / 2f
                            val distance = (c1 - c2).getDistance()
                            if (!pinch) {
                                pinch = true
                                prevCentroid = centroid
                                pinchStartScale = zoomState.scale
                                pinchStartSpan = distance
                                pinchSmoothSpan = distance
                            } else if (distance < MIN_PINCH_SPAN_PX) {
                                // Span-floor guard: near-touching
                                // fingers give a noise ratio, not a
                                // zoom signal — without it the scale
                                // spikes frame to frame while the
                                // fingers hover close together, which
                                // is the trembling users see on
                                // pinch-in starts and pinch-out ends.
                                // Hold the scale, and re-base the
                                // gesture reference at the floor, so
                                // crossing it again resumes
                                // continuously instead of jumping.
                                pinchStartScale = zoomState.scale
                                pinchStartSpan = MIN_PINCH_SPAN_PX
                                pinchSmoothSpan = distance
                                prevCentroid = centroid
                                pressed.forEach { it.consume() }
                            } else {
                                // The scale is the absolute ratio
                                // from the gesture start, on a
                                // smoothed span (see PageZoomState):
                                // it tracks the fingers 1:1 instead
                                // of trailing them the way a smoothed
                                // per-frame ratio did, so zooming in
                                // and out no longer feels slow. The
                                // pan math runs on the same scale,
                                // so the content point under the
                                // fingers stays under them.
                                pinchSmoothSpan =
                                    smoothPinchSpan(pinchSmoothSpan, distance)
                                val newScale = pinchTargetScale(
                                    pinchStartScale, pinchStartSpan, pinchSmoothSpan
                                )
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
                                    zoomState.scale, zoomState.offsetX, zoomState.offsetY,
                                    centroid.x - prevCentroid.x,
                                    centroid.y - prevCentroid.y,
                                    newScale, centroid.x, centroid.y,
                                    center.x, center.y,
                                    boxSize.width.toFloat(), boxSize.height.toFloat()
                                )
                                zoomState = zoomState.copy(
                                    scale = newScale, offsetX = nx, offsetY = ny
                                )
                                prevCentroid = centroid
                                pressed.forEach { it.consume() }
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
                            if (layoutZoom) {
                                // One-canvas: the main-axis drag is
                                // never consumed — the list scrolls
                                // through the taller strip, and that
                                // IS the pan. Only the cross-axis
                                // drag pans the shared offset, while
                                // zoomed, and it is not consumed
                                // either: the list has no handler on
                                // that axis (a LazyColumn never
                                // scrolls sideways, a LazyRow never
                                // vertically), so there is nothing
                                // to fight over.
                                val host = hostState.value
                                if (host != null && zoomState.scale > 1f) {
                                    val (boxCross, viewportCross) =
                                        if (host.horizontal) {
                                            boxSize.height.toFloat() to
                                                host.viewportHeightPx
                                        } else {
                                            boxSize.width.toFloat() to
                                                host.viewportWidthPx
                                        }
                                    val maxCross = stripMaxCrossOffset(
                                        boxCross, viewportCross
                                    )
                                    if (host.horizontal) {
                                        val ny = (zoomState.offsetY + delta.y)
                                            .coerceIn(-maxCross, maxCross)
                                        if (ny != zoomState.offsetY) {
                                            zoomState = zoomState.copy(offsetY = ny)
                                        }
                                    } else {
                                        val nx = (zoomState.offsetX + delta.x)
                                            .coerceIn(-maxCross, maxCross)
                                        if (nx != zoomState.offsetX) {
                                            zoomState = zoomState.copy(offsetX = nx)
                                        }
                                    }
                                }
                            } else if (zoomState.scale > 1f) {
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
                                    // page from a pan.
                                    change.consume()
                                }
                                // Else (edge handover on): not
                                // consumed, so the list scrolls
                                // from here.
                            }
                            // At scale 1: never consume. The pager
                            // turns / the list scrolls vertically;
                            // sideways drags do nothing (no
                            // horizontal scroll).
                        }
                    }
                    // The gesture ended (no pointers pressed).
                    // Nothing to settle: the zoom is a GPU
                    // transform of the once-decoded bitmap, and
                    // no page re-renders on gesture end.
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
                            // animates it over 120ms instead of
                            // snapping.
                            lastTapUpMs = 0L
                            if (layoutZoom) {
                                // One-canvas: the animation (see
                                // the strip effect above) moves the
                                // whole strip — shared scale, list
                                // scroll, shared cross offset —
                                // around the tap point, so the
                                // target carries no per-box offset.
                                stripZoomAnimTarget = StripZoomAnimTarget(
                                    fromScale = zoomState.scale,
                                    toScale = doubleTapTargetScale(zoomState.scale),
                                    tapPos = downPos
                                )
                            } else {
                                zoomAnimTarget = ZoomAnimTarget(
                                    fromScale = zoomState.scale,
                                    fromOffset = Offset(zoomState.offsetX, zoomState.offsetY),
                                    toScale = doubleTapTargetScale(zoomState.scale),
                                    tapPos = downPos
                                )
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
