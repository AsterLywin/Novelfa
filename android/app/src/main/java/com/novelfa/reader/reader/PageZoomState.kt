package com.novelfa.reader.reader

/**
 * One page's finger-zoom state, session-only.
 *
 * This is the per-page zoom model: each page (more precisely, each
 * page box — a dual-page split's two halves are two boxes of one
 * page) owns one of these in the pager; strip boxes each own one
 * too, held in the viewer's per-page registry (see
 * ZoomablePageContainer) — ComicScreen's method, so zooming one
 * strip page never moves another. The state is deliberately NOT
 * into the database, DataStore, backup or reading progress.
 * [pageId] is a session-scoped label only ("pager-3-half-0",
 * "strip-12") — it identifies the box inside one reader session
 * so a stale state can never be mistaken for another page's, and
 * it is never stored anywhere.
 *
 * Pure Kotlin, no Compose dependency, so the whole zoom math is
 * unit-testable on the JVM (see PageZoomStateTest).
 *
 * - [scale]: the user zoom multiplier on top of the page's own
 *   fit size (1 = the fit size itself, never smaller).
 * - [offsetX]/[offsetY]: the pan of the zoomed content inside its
 *   own box, in pixels, clamped by [clampPan] so the content can
 *   never leave its allowed range.
 */
data class PageZoomState(
    val pageId: String,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    /** True once the page is zoomed past its fit size. */
    val isZoomed: Boolean get() = scale > 1f

    /** The fit-size identity: scale 1, no pan. */
    fun reset(): PageZoomState = copy(scale = 1f, offsetX = 0f, offsetY = 0f)
}

/** Zoom never goes below the page's fit size. */
const val MIN_PAGE_ZOOM = 1f

/** Zoom never goes above 4x the fit size (the project's existing cap). */
const val MAX_PAGE_ZOOM = 4f

/**
 * Double-tap toggles back to 1x only once the page is clearly
 * zoomed; a scale barely above 1 still counts as "at fit size"
 * for the toggle, so a rounding wobble never traps the user.
 */
const val PAGE_ZOOM_DOUBLE_TAP_THRESHOLD = 1.05f

/** Coerces a scale into [MIN_PAGE_ZOOM, MAX_PAGE_ZOOM]; non-finite input becomes 1x. */
fun coerceZoomScale(scale: Float): Float =
    if (scale.isFinite()) scale.coerceIn(MIN_PAGE_ZOOM, MAX_PAGE_ZOOM) else MIN_PAGE_ZOOM

/**
 * Pinch-span noise floor, in pixels: below it the finger
 * distance carries no usable zoom signal. A pinch is a ratio
 * (new span / old span), so when the span is a few pixels a
 * 1-2px touch wobble is a 20-50% scale jump per frame — the
 * image trembles while the fingers hover nearly touching
 * (pinch-in starts and pinch-out ends, where the span is
 * small by nature). At or above the floor the ratio is a real
 * signal and the scale follows it exactly; below it the scale
 * is held and the gesture pans only (focalPan with an
 * unchanged scale is a pure centroid pan), so close-finger
 * dragging still tracks the fingers. Normal pinches run at
 * 50-400px spans and never notice the floor.
 */
const val MIN_PINCH_SPAN_PX = 20f

/**
 * Span-smoothing factor: each gesture frame moves the smoothed
 * span this fraction of the way toward the raw span (see
 * [smoothPinchSpan]). Half per frame: the 0.75 scale smoothing
 * it replaces made zoom feel very slow on device (2026-11-05
 * report) — a per-frame ratio target smoothed that way trails
 * the fingers by 16-29% on a 2x pinch, because the lag feeds
 * back into the next frame's target and compounds. Smoothing
 * the span instead bounds the lag to a few percent (it shrinks
 * as the span grows) while damping the same 1-3% touch noise
 * by the same factor the old scale smoothing did.
 */
const val SPAN_SMOOTHING_ALPHA = 0.5f

/**
 * One smoothed span frame: halfway from [prevSmoothedSpan]
 * toward [span], never below [MIN_PINCH_SPAN_PX]. Why the span
 * is smoothed instead of the scale: the scale must track the
 * fingers 1:1 or the zoom feels slow, and any smoothing on a
 * per-frame ratio target compounds (see above). The span is a
 * plain value, not a ratio product, so its smoothing lag is
 * bounded — a few pixels for a fast pinch, shrinking in
 * relative terms as the span grows — and it damps the same
 * per-frame touch noise that used to shimmer the offset. A
 * non-finite or non-positive span holds the previous smoothed
 * span; a non-finite previous span restarts from the raw span.
 */
fun smoothPinchSpan(prevSmoothedSpan: Float, span: Float): Float {
    if (!span.isFinite() || span <= 0f) return prevSmoothedSpan
    if (!prevSmoothedSpan.isFinite() || prevSmoothedSpan <= 0f) {
        return span.coerceAtLeast(MIN_PINCH_SPAN_PX)
    }
    val smoothed = prevSmoothedSpan + SPAN_SMOOTHING_ALPHA * (span - prevSmoothedSpan)
    return smoothed.coerceAtLeast(MIN_PINCH_SPAN_PX)
}

/**
 * One pinch frame's scale, referenced to the gesture start:
 * [startScale] at [startSpan], now at [span]. The scale is the
 * absolute ratio from the start, not a per-frame ratio chained
 * onto the previous frame — so it tracks the fingers 1:1 and
 * cannot lag behind them the way a smoothed per-frame target
 * did. The result is always coerced into [MIN_PAGE_ZOOM,
 * MAX_PAGE_ZOOM]. The span floor lives in the caller, not
 * here: below the floor the caller holds the current scale
 * (returning [startScale] here would snap the zoom back to
 * the gesture-start scale on a pinch-out). Bad inputs hold
 * the start scale.
 */
fun pinchTargetScale(startScale: Float, startSpan: Float, span: Float): Float {
    val start = coerceZoomScale(startScale)
    if (!startSpan.isFinite() || !span.isFinite() ||
        startSpan <= 0f || span <= 0f
    ) return start
    return coerceZoomScale(start * (span / startSpan))
}

/**
 * Pan clamp for a box of [boxWidth] x [boxHeight] pixels at [scale].
 * The content is centred, so each direction can move at most half
 * the overflow: (box * scale - box) / 2. At 1x the clamp is zero,
 * so a stale offset can never shift an unzoomed page. Each axis
 * is clamped independently, so a page narrower than the viewport
 * in one direction simply has no pan in that direction.
 */
fun clampPan(
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    boxWidth: Float,
    boxHeight: Float
): Pair<Float, Float> {
    val s = coerceZoomScale(scale)
    val maxX = ((boxWidth * s - boxWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((boxHeight * s - boxHeight) / 2f).coerceAtLeast(0f)
    return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
}

/**
 * Pinch focal math: the new pan after zooming from [oldScale] to
 * [newScale] around ([focalX], [focalY]) in box coordinates, with
 * the centroid moving by ([centroidDeltaX], [centroidDeltaY]).
 * The content point under the fingers stays under them: first
 * pan by the centroid move, then zoom around the new focal
 * point. The result is clamped to the box at the new scale.
 * This is the exact math the gesture loop applies per move.
 */
fun focalPan(
    oldScale: Float,
    oldOffsetX: Float,
    oldOffsetY: Float,
    centroidDeltaX: Float,
    centroidDeltaY: Float,
    newScale: Float,
    focalX: Float,
    focalY: Float,
    centerX: Float,
    centerY: Float,
    boxWidth: Float,
    boxHeight: Float
): Pair<Float, Float> {
    val old = coerceZoomScale(oldScale)
    val new = coerceZoomScale(newScale)
    var x = oldOffsetX + centroidDeltaX
    var y = oldOffsetY + centroidDeltaY
    x = (focalX - centerX) * (1f - new / old) + x * (new / old)
    y = (focalY - centerY) * (1f - new / old) + y * (new / old)
    return clampPan(x, y, new, boxWidth, boxHeight)
}

/**
 * The double-tap animation's offset at an intermediate [value]
 * between [fromScale] and the target, around ([tapX], [tapY]).
 * At [value] == [fromScale] this is exactly the starting offset;
 * at the target it is the focal-preserving end offset (see
 * [doubleTapEndOffset]), so the animation never snaps at either
 * end. The result is clamped to the box at [value].
 */
fun doubleTapAnimOffset(
    fromScale: Float,
    fromOffsetX: Float,
    fromOffsetY: Float,
    value: Float,
    tapX: Float,
    tapY: Float,
    centerX: Float,
    centerY: Float,
    boxWidth: Float,
    boxHeight: Float
): Pair<Float, Float> {
    val from = coerceZoomScale(fromScale)
    val v = coerceZoomScale(value)
    val x = (tapX - centerX) * (1f - v / from) + fromOffsetX * (v / from)
    val y = (tapY - centerY) * (1f - v / from) + fromOffsetY * (v / from)
    return clampPan(x, y, v, boxWidth, boxHeight)
}

/**
 * The settled offset of a double-tap zoom from [fromScale] to
 * [toScale] around ([tapX], [tapY]): the tapped detail lands
 * under the finger. Equivalent to [focalPan] with no centroid
 * move; kept separate so the animation's end state is testable
 * on its own.
 */
fun doubleTapEndOffset(
    fromScale: Float,
    fromOffsetX: Float,
    fromOffsetY: Float,
    toScale: Float,
    tapX: Float,
    tapY: Float,
    centerX: Float,
    centerY: Float,
    boxWidth: Float,
    boxHeight: Float
): Pair<Float, Float> =
    doubleTapAnimOffset(
        fromScale, fromOffsetX, fromOffsetY, toScale,
        tapX, tapY, centerX, centerY, boxWidth, boxHeight
    )

/** Double-tap target: clearly zoomed -> back to 1x, otherwise -> 2x. */
fun doubleTapTargetScale(scale: Float): Float =
    if (scale > PAGE_ZOOM_DOUBLE_TAP_THRESHOLD) MIN_PAGE_ZOOM else 2f

/**
 * Paged reset decision. A page box belongs to [slotKey] (its pager
 * item index); the viewer reports the currently displayed item as
 * [activeSlotKey]. The box must reset exactly when both are known
 * and differ — i.e. this box is no longer the displayed page.
 * Null on either side means "no paged reset applies" (a strip
 * item, or an unknown slot), so the answer is false and the box
 * keeps its own zoom. This is deliberately NOT a scroll-offset
 * test: a partial swipe that settles back must not reset anything.
 */
fun shouldResetZoom(slotKey: Int?, activeSlotKey: Int?): Boolean =
    slotKey != null && activeSlotKey != null && slotKey != activeSlotKey
