package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests (JVM) for the per-page zoom engine
 * (PageZoomState): pan clamps, focal-point math, double-tap
 * targets, and the paged reset rule. The Compose container
 * (ZoomablePageContainer) applies exactly these functions, so
 * a green suite here is the contract the gesture loop keeps —
 * the phone test then only has to confirm the gestures reach
 * the loop, not re-derive the math.
 */
class PageZoomStateTest {

    private val delta = 0.001f

    @Test
    fun defaultsAreFitSizeIdentity() {
        val state = PageZoomState(pageId = "pager-3-half-0")
        assertEquals(1f, state.scale, delta)
        assertEquals(0f, state.offsetX, delta)
        assertEquals(0f, state.offsetY, delta)
        assertFalse(state.isZoomed)
    }

    @Test
    fun resetReturnsToFitSizeIdentity() {
        val zoomed = PageZoomState("pager-3-half-0", scale = 2f, offsetX = 120f, offsetY = -40f)
        assertTrue(zoomed.isZoomed)
        val reset = zoomed.reset()
        assertEquals(1f, reset.scale, delta)
        assertEquals(0f, reset.offsetX, delta)
        assertEquals(0f, reset.offsetY, delta)
        assertFalse(reset.isZoomed)
        assertEquals("pager-3-half-0", reset.pageId)
    }

    @Test
    fun perPageStatesAreIndependent() {
        // The structural guarantee behind Z-01/Z-02/Z-04: two
        // page boxes hold two separate states; zooming one
        // (a copy) leaves the other untouched.
        val page1 = PageZoomState("strip-0", scale = 2f, offsetX = 100f, offsetY = 50f)
        val page2 = PageZoomState("strip-1")
        val page1ZoomedMore = page1.copy(scale = 3f, offsetX = 200f)
        assertEquals(2f, page1.scale, delta)
        assertEquals(1f, page2.scale, delta)
        assertEquals(0f, page2.offsetX, delta)
        assertEquals(3f, page1ZoomedMore.scale, delta)
    }

    @Test
    fun resetOfOnePageLeavesOtherPagesAlone() {
        // Simulates the paged reset: the outgoing page resets,
        // the incoming page (never zoomed) stays at identity.
        val states = mapOf(
            "pager-3-half-0" to PageZoomState("pager-3-half-0", scale = 2f, offsetX = 80f),
            "pager-4-half-0" to PageZoomState("pager-4-half-0")
        )
        val after = states.mapValues { (id, state) ->
            if (shouldResetZoom(slotKey = 3, activeSlotKey = 4) && id == "pager-3-half-0") {
                state.reset()
            } else {
                state
            }
        }
        assertEquals(1f, after.getValue("pager-3-half-0").scale, delta)
        assertEquals(0f, after.getValue("pager-3-half-0").offsetX, delta)
        assertEquals(1f, after.getValue("pager-4-half-0").scale, delta)
    }

    @Test
    fun scaleIsCoercedToOneToFour() {
        assertEquals(1f, coerceZoomScale(0.5f), delta)
        assertEquals(1f, coerceZoomScale(0f), delta)
        assertEquals(1f, coerceZoomScale(-3f), delta)
        assertEquals(2.5f, coerceZoomScale(2.5f), delta)
        assertEquals(4f, coerceZoomScale(9f), delta)
    }

    @Test
    fun nonFiniteScaleBecomesFitSize() {
        // A zero pinch distance (or a broken event stream) must
        // never produce NaN/Infinity state — Z-10 stability.
        assertEquals(1f, coerceZoomScale(Float.NaN), delta)
        assertEquals(1f, coerceZoomScale(Float.POSITIVE_INFINITY), delta)
        assertEquals(1f, coerceZoomScale(Float.NEGATIVE_INFINITY), delta)
    }

    @Test
    fun zoomOutNeverGoesBelowFitSize() {
        // Z-11: zooming out stops at the fit size, never below.
        assertEquals(1f, coerceZoomScale(0.1f), delta)
        val (x, y) = clampPan(500f, 500f, 0.5f, 1000f, 1000f)
        assertEquals(0f, x, delta)
        assertEquals(0f, y, delta)
    }

    @Test
    fun panClampAtFitSizeIsZero() {
        val (x, y) = clampPan(300f, -200f, 1f, 1000f, 800f)
        assertEquals(0f, x, delta)
        assertEquals(0f, y, delta)
    }

    @Test
    fun panClampIsHalfTheOverflowPerAxis() {
        // Box 1000x800 at 2x: overflow 1000x800, half = 500x400.
        val (x, y) = clampPan(600f, -500f, 2f, 1000f, 800f)
        assertEquals(500f, x, delta)
        assertEquals(-400f, y, delta)
        val (ix, iy) = clampPan(100f, 200f, 2f, 1000f, 800f)
        assertEquals(100f, ix, delta)
        assertEquals(200f, iy, delta)
    }

    @Test
    fun panClampAxesAreIndependent() {
        // A wide, short page: plenty of horizontal pan, almost
        // none vertical — one axis must not borrow the other's.
        val (x, y) = clampPan(600f, 80f, 2f, 1000f, 100f)
        assertEquals(500f, x, delta)
        assertEquals(50f, y, delta)
    }

    @Test
    fun differentAspectPagesGetDifferentPanBounds() {
        // The order's example, at fit-width 1000px: page
        // 800x1000 displays 1000x1250, page 1200x1000 displays
        // 1000x833. At 1.5x their vertical pan limits differ —
        // the clamp is per page, never shared (Z-02).
        val (_, y1) = clampPan(0f, 9999f, 1.5f, 1000f, 1250f)
        val (_, y2) = clampPan(0f, 9999f, 1.5f, 1000f, 833.333f)
        assertEquals(312.5f, y1, delta)
        assertEquals(208.333f, y2, 0.01f)
        assertTrue(y1 > y2)
    }

    @Test
    fun pinchKeepsTheFocalPointUnderTheFingers() {
        // Box 1000x1000, centre (500, 500). Zoom 1x -> 2x around
        // (750, 500) with no centroid move: the offset must be
        // -250, so the point at 750 still displays at 750:
        // 500 + (750 - 500) * 2 + (-250) = 750.
        val (x, y) = focalPan(
            oldScale = 1f, oldOffsetX = 0f, oldOffsetY = 0f,
            centroidDeltaX = 0f, centroidDeltaY = 0f,
            newScale = 2f, focalX = 750f, focalY = 500f,
            centerX = 500f, centerY = 500f,
            boxWidth = 1000f, boxHeight = 1000f
        )
        assertEquals(-250f, x, delta)
        assertEquals(0f, y, delta)
        val displayed = 500f + (750f - 500f) * 2f + x
        assertEquals(750f, displayed, delta)
    }

    @Test
    fun pinchPansWithTheCentroidThenZoomsAroundIt() {
        // Old offset (100, 0) at 1x, centroid moves +20, focal
        // (600, 500), zoom to 2x: (100 + 20) panned, then
        // (600 - 500) * (1 - 2) + 120 * 2 = -100 + 240 = 140.
        val (x, y) = focalPan(
            oldScale = 1f, oldOffsetX = 100f, oldOffsetY = 0f,
            centroidDeltaX = 20f, centroidDeltaY = 0f,
            newScale = 2f, focalX = 600f, focalY = 500f,
            centerX = 500f, centerY = 500f,
            boxWidth = 1000f, boxHeight = 1000f
        )
        assertEquals(140f, x, delta)
        assertEquals(0f, y, delta)
    }

    @Test
    fun focalPanClampsToTheBox() {
        // Zooming around a far corner cannot push the content
        // past the clamp — Z-10 stability under extreme input.
        val (x, y) = focalPan(
            oldScale = 1f, oldOffsetX = 0f, oldOffsetY = 0f,
            centroidDeltaX = 0f, centroidDeltaY = 0f,
            newScale = 4f, focalX = 0f, focalY = 0f,
            centerX = 500f, centerY = 500f,
            boxWidth = 1000f, boxHeight = 1000f
        )
        // Unclamped: (0 - 500) * (1 - 4) = 1500; clamp at 4x is 1500.
        assertEquals(1500f, x, delta)
        assertEquals(1500f, y, delta)
    }

    @Test
    fun doubleTapTargetTogglesOneToTwo() {
        assertEquals(2f, doubleTapTargetScale(1f), delta)
        assertEquals(2f, doubleTapTargetScale(1.05f), delta)
        assertEquals(1f, doubleTapTargetScale(1.06f), delta)
        assertEquals(1f, doubleTapTargetScale(2f), delta)
        assertEquals(1f, doubleTapTargetScale(4f), delta)
    }

    @Test
    fun doubleTapEndOffsetKeepsTheTappedDetail() {
        // 1x -> 2x around (750, 500): same -250 as the pinch.
        val (x, y) = doubleTapEndOffset(
            fromScale = 1f, fromOffsetX = 0f, fromOffsetY = 0f,
            toScale = 2f, tapX = 750f, tapY = 500f,
            centerX = 500f, centerY = 500f,
            boxWidth = 1000f, boxHeight = 1000f
        )
        assertEquals(-250f, x, delta)
        assertEquals(0f, y, delta)
        // Tapping the centre never pans.
        val (cx, cy) = doubleTapEndOffset(
            fromScale = 1f, fromOffsetX = 0f, fromOffsetY = 0f,
            toScale = 2f, tapX = 500f, tapY = 500f,
            centerX = 500f, centerY = 500f,
            boxWidth = 1000f, boxHeight = 1000f
        )
        assertEquals(0f, cx, delta)
        assertEquals(0f, cy, delta)
    }

    @Test
    fun doubleTapBackToOneRestoresIdentityExactly() {
        // 2x at offset -250, tap the same detail back to 1x:
        // (750 - 500) * (1 - 1/2) + (-250) * (1/2) = 125 - 125 = 0.
        val (x, y) = doubleTapEndOffset(
            fromScale = 2f, fromOffsetX = -250f, fromOffsetY = 0f,
            toScale = 1f, tapX = 750f, tapY = 500f,
            centerX = 500f, centerY = 500f,
            boxWidth = 1000f, boxHeight = 1000f
        )
        assertEquals(0f, x, delta)
        assertEquals(0f, y, delta)
    }

    @Test
    fun doubleTapAnimationEndpointsMatch() {
        // At value == fromScale the animation is at the start
        // offset; at value == toScale it is at the end offset —
        // no snap at either end of the 120ms animation.
        // Realistic from-state: 2x with offset (40, -30) is
        // inside the 2x clamp; a 1x offset could never be
        // nonzero, because the 1x clamp is zero.
        val (sx, sy) = doubleTapAnimOffset(
            fromScale = 2f, fromOffsetX = 40f, fromOffsetY = -30f,
            value = 2f, tapX = 750f, tapY = 500f,
            centerX = 500f, centerY = 500f,
            boxWidth = 1000f, boxHeight = 1000f
        )
        assertEquals(40f, sx, delta)
        assertEquals(-30f, sy, delta)
        val (ex, ey) = doubleTapAnimOffset(
            fromScale = 2f, fromOffsetX = 40f, fromOffsetY = -30f,
            value = 1f, tapX = 750f, tapY = 500f,
            centerX = 500f, centerY = 500f,
            boxWidth = 1000f, boxHeight = 1000f
        )
        // Unclamped the end would be (145, -15) — (750 - 500) *
        // (1 - 1/2) + 40 * (1/2) = 145 — but at exactly 1x the
        // clamp is zero, so the animation lands on identity,
        // as it must: an unzoomed page can never sit shifted.
        assertEquals(0f, ex, delta)
        assertEquals(0f, ey, delta)
        val (dx, dy) = doubleTapEndOffset(
            fromScale = 2f, fromOffsetX = 40f, fromOffsetY = -30f,
            toScale = 1f, tapX = 750f, tapY = 500f,
            centerX = 500f, centerY = 500f,
            boxWidth = 1000f, boxHeight = 1000f
        )
        assertEquals(dx, ex, delta)
        assertEquals(dy, ey, delta)
    }

    @Test
    fun resetOnlyWhenTheDisplayedSlotChanges() {
        // Z-08/Z-09/Z-19: same slot -> keep; different slot ->
        // reset; unknown slot (strips) -> keep.
        assertFalse(shouldResetZoom(slotKey = 3, activeSlotKey = 3))
        assertTrue(shouldResetZoom(slotKey = 3, activeSlotKey = 4))
        assertFalse(shouldResetZoom(slotKey = null, activeSlotKey = 4))
        assertFalse(shouldResetZoom(slotKey = 3, activeSlotKey = null))
        assertFalse(shouldResetZoom(slotKey = null, activeSlotKey = null))
    }
}
