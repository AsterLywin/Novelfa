package com.novelfa.reader.reader

/**
 * One page's finger-zoom state, session-only.
 *
 * This is the per-page zoom model: each page (more precisely, each
 * page box — a dual-page split's two halves are two boxes of one
 * page) owns one of these, and nothing is ever shared between
 * pages. The state is deliberately NOT persisted: it never goes
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
 * One pinch frame's scale: the span ratio, unless either span
 * is below [MIN_PINCH_SPAN_PX] (or non-positive / non-finite),
 * in which case the scale is held. The result is always
 * coerced into [MIN_PAGE_ZOOM, MAX_PAGE_ZOOM].
 */
fun pinchScale(oldScale: Float, prevSpan: Float, span: Float): Float {
    val old = coerceZoomScale(oldScale)
    if (!prevSpan.isFinite() || !span.isFinite() ||
        prevSpan < MIN_PINCH_SPAN_PX || span < MIN_PINCH_SPAN_PX
    ) return old
    return coerceZoomScale(old * (span / prevSpan))
}

/**
 * Pinch smoothing factor: each gesture frame moves the
 * displayed scale this fraction of the way from the previous
 * displayed scale toward the ratio-implied target (see
 * [smoothPinchScale]). Three-quarters per frame: the 0.5 value
 * fixed the tremor but made zoom feel very slow on device
 * (2026-11-04 report), so the factor was raised — a steady
 * pinch now trails by ~a third of a frame of motion, while
 * per-frame noise is still damped (std x0.78) and the 20px
 * span floor still kills the degenerate small-span spikes.
 */
const val PINCH_SMOOTHING_ALPHA = 0.75f

/**
 * One smoothed pinch frame: three-quarters of the way from [prevSmoothed]
 * toward [targetScale] (the [pinchScale] result for this
 * frame). Why smoothing is needed on top of the span floor:
 * the floor only kills the degenerate case (span < 20px,
 * 20-50% jumps). At normal spans the raw ratio still carries
 * 1-3% touch noise per frame, and [focalPan] multiplies the
 * existing offset by new/old every frame — so at 3x zoom a
 * 2% scale wobble is a multi-pixel offset jump per frame,
 * which is the shimmer that survives the floor. Smoothing
 * the scale damps the offset jumps by the same factor, since
 * the pan math runs on the smoothed scale. The result always
 * lies between prev and target (never overshoots) and is
 * coerced into [MIN_PAGE_ZOOM, MAX_PAGE_ZOOM]; a non-finite
 * target holds the previous scale.
 */
fun smoothPinchScale(prevSmoothed: Float, targetScale: Float): Float {
    val prev = coerceZoomScale(prevSmoothed)
    if (!targetScale.isFinite()) return prev
    val target = coerceZoomScale(targetScale)
    return coerceZoomScale(prev + PINCH_SMOOTHING_ALPHA * (target - prev))
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
