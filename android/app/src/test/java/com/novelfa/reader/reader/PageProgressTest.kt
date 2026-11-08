package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Acceptance tests (JVM) for the native viewer's progress rule
 * (PageProgress.kt): progress follows the FURTHEST page on screen.
 *
 * The rule is ComicScreen's behaviour, clean-room: in 2-page mode
 * the spread's second page is the position (ImgActivity.O's
 * last-spread special case), and in scroll mode the current item is
 * the first or the last visible item depending on scroll direction.
 * The viewer wires exactly these functions in, so a green suite
 * here is the contract the viewer keeps — the phone test then only
 * has to confirm the last spread / last strip page saves 100%,
 * not re-derive the rule.
 */
class PageProgressTest {

    @Test
    fun spreadLastPage_pairIsSecondPage() {
        // 36-page book, last spread (34, 35): progress must be 35,
        // the page that makes (35 + 1) / 36 = 100%.
        assertEquals(35, spreadLastPage(firstPage = 34, secondPage = 35))
    }

    @Test
    fun spreadLastPage_firstSpreadIsSecondPage() {
        assertEquals(1, spreadLastPage(firstPage = 0, secondPage = 1))
    }

    @Test
    fun spreadLastPage_singleOrSplitIsItsOnlyPage() {
        // Odd page left over (Single) or a split landscape page:
        // no second page, so the only page is the furthest.
        assertEquals(35, spreadLastPage(firstPage = 35, secondPage = null))
        assertEquals(0, spreadLastPage(firstPage = 0, secondPage = null))
    }

    @Test
    fun stripLeadingPage_scrollingDownIsLastVisible() {
        // Viewport shows pages 34 and 35, scrolling down: the
        // leading edge is 35, the page that completes the book.
        assertEquals(35, stripLeadingPage(firstVisible = 34, lastVisible = 35, scrollingDown = true))
    }

    @Test
    fun stripLeadingPage_scrollingUpIsFirstVisible() {
        // Same viewport, scrolling up: the leading edge is back
        // to the first visible item.
        assertEquals(34, stripLeadingPage(firstVisible = 34, lastVisible = 35, scrollingDown = false))
    }

    @Test
    fun stripLeadingPage_noItemsLaidOutFallsBackToFirst() {
        assertEquals(7, stripLeadingPage(firstVisible = 7, lastVisible = null, scrollingDown = true))
        assertEquals(7, stripLeadingPage(firstVisible = 7, lastVisible = null, scrollingDown = false))
    }

    @Test
    fun stripLeadingPage_singleTallPageIsItself() {
        // One page taller than the viewport: first and last
        // visible are the same page either way.
        assertEquals(5, stripLeadingPage(firstVisible = 5, lastVisible = 5, scrollingDown = true))
        assertEquals(5, stripLeadingPage(firstVisible = 5, lastVisible = 5, scrollingDown = false))
    }
}
