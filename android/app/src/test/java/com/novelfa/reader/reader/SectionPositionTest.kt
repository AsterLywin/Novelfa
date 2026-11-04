package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Acceptance tests (JVM) for the vertical in-section position
 * (SectionPosition): the fraction math the scroll reporter and the
 * restore JS both use, and the stored "chapter|fraction" form.
 * The WebView side (report + restore JS) cannot run on the JVM, so
 * a green suite here is the contract that side keeps — the phone
 * test then only has to confirm the report reaches the store and
 * the restore scrolls, not re-derive the math.
 */
class SectionPositionTest {

    private val delta = 0.001f

    @Test
    fun fraction_topOfChapter_isZero() {
        assertEquals(0f, sectionFraction(scrollTop = 1000, chapterTop = 1000, chapterHeight = 5000), delta)
    }

    @Test
    fun fraction_middle_isHalf() {
        assertEquals(0.5f, sectionFraction(scrollTop = 3500, chapterTop = 1000, chapterHeight = 5000), delta)
    }

    @Test
    fun fraction_aboveChapter_clampsToZero() {
        assertEquals(0f, sectionFraction(scrollTop = 500, chapterTop = 1000, chapterHeight = 5000), delta)
    }

    @Test
    fun fraction_pastChapterBottom_clampsBelowOne() {
        // Never names the next chapter: 0.999, not 1.0.
        assertEquals(0.999f, sectionFraction(scrollTop = 9000, chapterTop = 1000, chapterHeight = 5000), delta)
    }

    @Test
    fun fraction_zeroHeight_isZero() {
        assertEquals(0f, sectionFraction(scrollTop = 1000, chapterTop = 1000, chapterHeight = 0), delta)
    }

    @Test
    fun restoreScrollTop_roundTrip_matchesFraction() {
        // The restore JS computes top + fraction * height; the Kotlin
        // mirror must agree, or the settle re-restore would drift.
        val top = sectionRestoreScrollTop(chapterTop = 1000, chapterHeight = 5000, fraction = 0.5f)
        assertEquals(3500, top)
        assertEquals(0.5f, sectionFraction(scrollTop = top, chapterTop = 1000, chapterHeight = 5000), delta)
    }

    @Test
    fun restoreScrollTop_zeroHeight_isChapterTop() {
        assertEquals(1000, sectionRestoreScrollTop(chapterTop = 1000, chapterHeight = 0, fraction = 0.5f))
    }

    @Test
    fun serialize_parse_roundTrip() {
        val pos = SectionPosition(chapter = 3, fraction = 0.625f)
        assertEquals(pos, parseSectionPosition(serializeSectionPosition(pos)))
    }

    @Test
    fun serialize_keepsFourDecimals() {
        assertEquals("2|0.1235", serializeSectionPosition(SectionPosition(2, 0.12345f)))
    }

    @Test
    fun parse_rejectsMalformed() {
        assertNull(parseSectionPosition(null))
        assertNull(parseSectionPosition(""))
        assertNull(parseSectionPosition("3"))
        assertNull(parseSectionPosition("3|0.5|extra"))
        assertNull(parseSectionPosition("x|0.5"))
        assertNull(parseSectionPosition("3|x"))
        assertNull(parseSectionPosition("-1|0.5"))
        assertNull(parseSectionPosition("3|-0.1"))
        assertNull(parseSectionPosition("3|1.0"))
        assertNull(parseSectionPosition("3|1.5"))
    }

    @Test
    fun parse_acceptsEdges() {
        assertEquals(SectionPosition(0, 0f), parseSectionPosition("0|0.0000"))
        assertEquals(SectionPosition(7, 0.999f), parseSectionPosition("7|0.9990"))
    }
}
