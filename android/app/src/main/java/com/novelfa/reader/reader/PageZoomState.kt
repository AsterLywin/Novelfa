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
