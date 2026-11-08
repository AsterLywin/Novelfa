package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Acceptance tests (JVM) for the book-wide page numbers
 * (BookPageCount.kt): ReadEra-style "page N of M" for the whole
 * book, exact once every chapter is counted, estimated from file
 * size while chapters are still uncounted.
 */
class BookPageCountTest {

    private val bytes = listOf(10_000L, 20_000L, 5_000L)

    @Test
    fun exact_whenAllChaptersKnown() {
        val counts = mapOf(0 to 10, 1 to 20, 2 to 5)
        val nums = bookPageNumbers(counts, bytes, 1, 7)
        assertEquals(BookPageNumbers(page = 17, total = 35, exact = true), nums)
    }

    @Test
    fun estimatedTotal_whileChaptersUncounted() {
        // Only chapter 0 counted: ratio 10 pages / 10_000 bytes.
        val counts = mapOf(0 to 10)
        val nums = bookPageNumbers(counts, bytes, 0, 3)
        // ch1 estimated 20, ch2 estimated 5 -> total 35, not exact.
        assertEquals(BookPageNumbers(page = 3, total = 35, exact = false), nums)
        val later = bookPageNumbers(counts, bytes, 1, 7)
        assertEquals(BookPageNumbers(page = 17, total = 35, exact = false), later)
    }

    @Test
    fun noCalibration_noNumbers() {
        assertNull(bookPageNumbers(emptyMap(), bytes, 0, 1))
        // Only a tiny chapter known: below the calibration floor.
        assertNull(bookPageNumbers(mapOf(0 to 1), listOf(100L, 20_000L), 0, 1))
        assertNull(bookPageNumbers(mapOf(0 to 10), emptyList(), 0, 1))
    }

    @Test
    fun currentPage_clampedIntoItsChapter() {
        // Live page 12 in a 10-page chapter (repagination racing a
        // stale cached count) must not push the book past its end.
        val counts = mapOf(0 to 10, 1 to 20, 2 to 5)
        val nums = bookPageNumbers(counts, bytes, 0, 12)
        assertEquals(BookPageNumbers(page = 10, total = 35, exact = true), nums)
    }

    @Test
    fun unknownCurrentChapter_usesEstimate() {
        val counts = mapOf(0 to 10)
        // Chapter 1 uncounted: estimated 20 pages, live page 25 clamps to 20.
        val nums = bookPageNumbers(counts, bytes, 1, 25)
        assertEquals(BookPageNumbers(page = 30, total = 35, exact = false), nums)
    }

    @Test
    fun emptyChapterFile_isOnePage() {
        val counts = mapOf(1 to 20)
        val nums = bookPageNumbers(counts, listOf(0L, 20_000L), 0, 1)
        assertEquals(BookPageNumbers(page = 1, total = 21, exact = false), nums)
    }

    @Test
    fun singleChapter_bookWideEqualsChapterLocal() {
        val counts = mapOf(0 to 12)
        assertEquals(
            BookPageNumbers(page = 5, total = 12, exact = true),
            bookPageNumbers(counts, listOf(12_000L), 0, 5)
        )
    }

    @Test
    fun pageWithinChapter_fromFraction() {
        assertEquals(1, pageWithinChapter(0f, 10))
        assertEquals(6, pageWithinChapter(0.5f, 10))
        assertEquals(10, pageWithinChapter(0.999f, 10))
        assertEquals(10, pageWithinChapter(1f, 10))
        assertEquals(1, pageWithinChapter(0.5f, 1))
        assertEquals(1, pageWithinChapter(-0.2f, 10))
    }

    @Test
    fun ratio_ignoresTinyChapters() {
        // Chapter 0 is tiny (100 bytes, 1 page): it must not drag
        // the ratio down; only chapter 1 calibrates (20/20_000).
        val counts = mapOf(0 to 1, 1 to 20)
        assertEquals(0.001, pagesPerByte(counts, listOf(100L, 20_000L, 5_000L))!!, 0.000001)
        val nums = bookPageNumbers(counts, listOf(100L, 20_000L, 5_000L), 2, 1)
        assertEquals(BookPageNumbers(page = 22, total = 26, exact = false), nums)
    }
}
