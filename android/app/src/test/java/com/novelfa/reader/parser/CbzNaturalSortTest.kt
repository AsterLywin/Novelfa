package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CbzNaturalSortTest {

    private fun sorted(vararg names: String): List<String> {
        return names.sortedWith { a, b -> CbzBook.compareNatural(a, b) }
    }

    @Test
    fun segmentAware_folderBeforeDash() {
        // Segment-aware: "a/b" (folder a, page b) sorts before
        // "a-b" (a single file) — the whole-path sort used to
        // put them the other way round, scrambling foldered
        // books (ComicScreen's segment-aware natural sort).
        assertEquals(
            listOf("a/b.jpg", "a-b.jpg"),
            sorted("a-b.jpg", "a/b.jpg")
        )
    }

    @Test
    fun numericRunsWithinSegment() {
        assertEquals(
            listOf("ch1/2.jpg", "ch1/10.jpg", "ch2/1.jpg"),
            sorted("ch2/1.jpg", "ch1/10.jpg", "ch1/2.jpg")
        )
    }

    @Test
    fun prefixShorterFirst() {
        assertTrue(CbzBook.compareNatural("ch1", "ch1x") < 0)
        assertEquals(0, CbzBook.compareNatural("a/1.jpg", "a/1.jpg"))
    }
}
