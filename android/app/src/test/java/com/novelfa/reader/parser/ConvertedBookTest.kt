package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ConvertedBook.chunkSections]' giant-paragraph split:
 * a section made of one over-long <p> must come out as several
 * bounded sections with the text preserved exactly (no glued words,
 * no lost tags) — otherwise the paged reader binary-searches a single
 * giant leaf for every page break (tens of seconds on a phone).
 */
class ConvertedBookTest {

    private fun plain(html: String): String =
        Regex("<[^>]*>").replace(html, "")

    @Test
    fun chunkSections_splitsGiantSingleParagraph() {
        val text = (1..4000).joinToString(" ") { "کلمه$it" }
        val body = "<p>$text</p>"
        assertTrue(body.length > ConvertedBook.MAX_SECTION_CHARS)
        val out = ConvertedBook.chunkSections(
            listOf(ConvertedBook.Section("بخش", body))
        )
        assertTrue(out.size > 1)
        assertEquals(text, out.joinToString("") { plain(it.bodyHtml) })
        for (s in out) {
            assertTrue(plain(s.bodyHtml).length <= ConvertedBook.MAX_SECTION_CHARS)
        }
    }

    @Test
    fun chunkSections_keepsInlineTagsBalancedAcrossSplits() {
        val inner = "<strong>" + (1..4000).joinToString(" ") { "واژه$it" } + "</strong>"
        val body = "<p>$inner</p>"
        val out = ConvertedBook.chunkSections(
            listOf(ConvertedBook.Section("بخش", body))
        )
        assertTrue(out.size > 1)
        for (s in out) {
            val opens = "<strong>".toRegex().findAll(s.bodyHtml).count()
            val closes = "</strong>".toRegex().findAll(s.bodyHtml).count()
            assertEquals(opens, closes)
        }
        assertEquals(plain(inner), out.joinToString("") { plain(it.bodyHtml) })
    }

    @Test
    fun chunkSections_shortSectionsUnchanged() {
        val body = "<p>متن کوتاه</p><p>پاراگراف دوم</p>"
        val out = ConvertedBook.chunkSections(
            listOf(ConvertedBook.Section("بخش", body))
        )
        assertEquals(1, out.size)
        assertEquals(body, out[0].bodyHtml)
    }

    @Test
    fun chunkSections_imageInsideGiantParagraphPreservedOnce() {
        val text = (1..4000).joinToString(" ") { "کلمه$it" }
        val body = "<p>$text<img src=\"media/a.jpg\" /></p>"
        val out = ConvertedBook.chunkSections(
            listOf(ConvertedBook.Section("بخش", body))
        )
        assertTrue(out.size > 1)
        val joined = out.joinToString("") { it.bodyHtml }
        assertEquals(1, "<img".toRegex().findAll(joined).count())
        assertEquals(text, out.joinToString("") { plain(it.bodyHtml) })
    }

    @Test
    fun chunkSections_blockMarkupInsideParagraphLeftWhole() {
        // Defensive: a <div> inside a <p> is not splittable safely —
        // the paragraph stays whole, but no text is lost.
        val text = (1..4000).joinToString(" ") { "کلمه$it" }
        val body = "<p>$text<div>inner</div></p>"
        val out = ConvertedBook.chunkSections(
            listOf(ConvertedBook.Section("بخش", body))
        )
        assertEquals(1, out.size)
        assertEquals(text + "inner", out.joinToString("") { plain(it.bodyHtml) })
    }
}
