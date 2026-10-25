package com.novelfa.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the generated cover ([GeneratedCover]).
 * The bitmap rendering itself (Canvas/StaticLayout) is a framework API
 * and cannot run in local unit tests; it is covered by the palette
 * contract tested here plus device testing.
 */
class GeneratedCoverTest {

    @Test
    fun paletteFor_deterministic_sameTitleSamePalette() {
        assertEquals(
            GeneratedCover.paletteFor("صد سال تنهایی"),
            GeneratedCover.paletteFor("صد سال تنهایی")
        )
    }

    @Test
    fun paletteFor_alwaysWithinPalettes() {
        val titles = listOf(
            "صد سال تنهایی", "War and Peace", "1984", "کلیدر",
            "The Name of the Wind", "بوف کور", "Dune", "مثنوی معنوی"
        )
        for (title in titles) {
            assertTrue(GeneratedCover.PALETTES.contains(GeneratedCover.paletteFor(title)))
        }
    }

    @Test
    fun paletteFor_spreadsAcrossPalettes() {
        // A hash pick that collapsed every title onto one palette
        // would defeat the point (distinct books, distinct covers).
        val titles = listOf(
            "صد سال تنهایی", "War and Peace", "1984", "کلیدر",
            "The Name of the Wind", "بوف کور", "Dune", "مثنوی معنوی"
        )
        val distinct = titles.map { GeneratedCover.paletteFor(it) }.toSet()
        assertTrue(distinct.size > 1)
    }

    @Test
    fun coverDimensions_areTwoToThree() {
        assertEquals(600, GeneratedCover.COVER_WIDTH)
        assertEquals(900, GeneratedCover.COVER_HEIGHT)
    }
}
