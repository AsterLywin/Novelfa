package com.novelfa.reader.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests (JVM) for PDF search (task 4b): the pure
 * next/prev navigation over a hit list, and the degraded
 * behavior of [PdfTextSearcher] when the native library is
 * absent — unit tests run without libnovelfa_pdftext.so, so
 * the searcher must report unavailable and return empty
 * results instead of ever touching JNI. The live path (a real
 * PDF with the library present) is a phone test, not a JVM one.
 */
class PdfSearchTest {

    private val threeHits = listOf(
        PdfSearchHit(0, 10, 5),
        PdfSearchHit(0, 40, 5),
        PdfSearchHit(2, 3, 5)
    )

    @Test
    fun state_emptyStaysPut() {
        val state = PdfSearchState(emptyList())
        assertEquals(0, state.count)
        assertNull(state.current)
        assertEquals(state, state.next())
        assertEquals(state, state.prev())
    }

    @Test
    fun state_nextWalksInOrderAndClamps() {
        val state = PdfSearchState(threeHits)
        assertNull(state.current)
        val first = state.next()
        assertEquals(threeHits[0], first.current)
        val second = first.next()
        assertEquals(threeHits[1], second.current)
        val third = second.next()
        assertEquals(threeHits[2], third.current)
        // Past the last hit: no wrap, no crash — stays.
        assertEquals(third, third.next())
    }

    @Test
    fun state_prevWalksBackAndClamps() {
        val state = PdfSearchState(threeHits).next().next().next()
        assertEquals(threeHits[2], state.current)
        val second = state.prev()
        assertEquals(threeHits[1], second.current)
        val first = second.prev()
        assertEquals(threeHits[0], first.current)
        // Before the first hit: stays, never -1.
        assertEquals(first, first.prev())
    }

    @Test
    fun state_prevBeforeAnyNextStaysUnset() {
        val state = PdfSearchState(threeHits)
        assertEquals(state, state.prev())
        assertNull(state.current)
    }

    @Test
    fun state_countIsHitCount() {
        assertEquals(3, PdfSearchState(threeHits).count)
        assertEquals(1, PdfSearchState(listOf(PdfSearchHit(4, 0, 2))).count)
    }

    @Test
    fun searcher_degradedWithoutNativeLibrary() {
        // No .so on the JVM test classpath: available is false,
        // and every call degrades to empty instead of crashing.
        assertFalse(PdfiumText.available)
        val searcher = PdfTextSearcher(File("/nonexistent/book.pdf"))
        assertFalse(searcher.available)
        assertEquals(0, searcher.pageCount)
        assertTrue(searcher.searchAll("word").isEmpty())
        assertTrue(
            searcher.hitRects(PdfSearchHit(0, 0, 4), 800, 1000).isEmpty()
        )
        // Closing a never-opened searcher is a no-op, not a crash.
        searcher.close()
    }

    @Test
    fun searcher_blankQueryIsEmptyEvenDegraded() {
        val searcher = PdfTextSearcher(File("/nonexistent/book.pdf"))
        assertTrue(searcher.searchAll("").isEmpty())
        assertTrue(searcher.searchAll("   ").isEmpty())
        searcher.close()
    }
}
