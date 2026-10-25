package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the PDF image fallback ([PdfImageBook]).
 * The platform rendering loop itself (PdfRenderer/Bitmap) is a
 * framework API and cannot run in local unit tests; it is covered by
 * the section-building contract tested here plus device testing.
 */
class PdfImageBookTest {

    @Test
    fun fileNameFor_zeroPadded_usDigits() {
        assertEquals("page-0001.jpg", PdfImageBook.fileNameFor(0))
        assertEquals("page-0010.jpg", PdfImageBook.fileNameFor(9))
        assertEquals("page-0124.jpg", PdfImageBook.fileNameFor(123))
    }

    @Test
    fun buildSections_singleSection_allPagesInOrder() {
        val sections = PdfImageBook.buildSections("My Book", 3)
        assertEquals(1, sections.size)
        assertEquals("My Book", sections[0].title)
        val body = sections[0].bodyHtml
        assertTrue(body.contains("<img src=\"media/page-0001.jpg\">"))
        assertTrue(body.contains("<img src=\"media/page-0002.jpg\">"))
        assertTrue(body.contains("<img src=\"media/page-0003.jpg\">"))
        // Page order is document order, each image in its own <p>
        // (so the pager's image-page rule can claim it).
        assertTrue(body.indexOf("page-0001") < body.indexOf("page-0002"))
        assertTrue(body.indexOf("page-0002") < body.indexOf("page-0003"))
        assertEquals(3, body.split("<p>").size - 1)
    }

    @Test
    fun buildSections_zeroPages_emptyBody() {
        val sections = PdfImageBook.buildSections("Empty", 0)
        assertEquals(1, sections.size)
        assertEquals("", sections[0].bodyHtml)
    }

    @Test
    fun imagePageCap_isBounded() {
        // A cap must exist (unbounded rendering would fill storage on
        // a multi-thousand-page scan); the exact value is a spec
        // choice, documented here so a change is deliberate.
        assertEquals(1000, PdfImageBook.MAX_IMAGE_PAGES)
    }
}
