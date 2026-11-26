package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-26 bug round, plain JVM (HtmlText is pure Kotlin — no Android
 * APIs). Each test is EXPECTED TO FAIL until its fix lands.
 *
 *  D3 — per-paragraph direction: Windows (d2850b8b) uses first-strong-
 *       letter unless the other script outnumbers it 4:1, biased
 *       toward the chapter direction; Android used plain majority.
 *  F4 — footnote-id heuristic: real EPUBs also use ftn1 / f1 / n1.
 */
class HtmlTextFixTest {

    @Test
    fun mixedScriptParagraph_firstStrongLetterWinsInRtlChapter() {
        // An RTL chapter (the first paragraph makes the whole body
        // RTL-majority) containing a paragraph that starts Persian
        // but is mostly English — Windows keeps it RTL; plain
        // majority flips it LTR.
        val body = "<p>این یک پاراگراف فارسی است که جهت بخش را راست‌چین می‌کند</p>" +
            "<p>سلام Hello world this is English text here</p>"
        val out = HtmlText.applyTextDirection(body)
        assertTrue(
            "expected the mixed paragraph to stay dir=\"rtl\" in [$out]",
            out.contains("<p dir=\"rtl\" data-rdr=\"r\">سلام Hello world")
        )
    }

    @Test
    fun mixedScriptParagraph_latinFirstInRtlChapter_needsFourToOneToFlip() {
        // Starts Latin, Latin majority but far below 4:1 over the
        // RTL words — Windows keeps it RTL.
        val body = "<p>Hello سلام دنیا</p>"
        val out = HtmlText.applyTextDirection(body)
        assertTrue("expected dir=\"rtl\" in [$out]", out.contains("dir=\"rtl\""))
    }

    @Test
    fun footnoteMark_wideIdShapes() {
        val html = "<p><a href=\"#ftn1\">1</a> <a href=\"#f1\">1</a> " +
            "<a href=\"#n1\">1</a> <a href=\"#fn1\">1</a></p>"
        val out = HtmlText.markFootnoteLinks(html)
        assertEquals(
            4, "data-novelfa-footnote".toRegex().findAll(out).count()
        )
    }
}
