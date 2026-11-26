package com.novelfa.reader.parser

import com.novelfa.reader.reader.HtmlText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end pipeline probe (2026-11-26 follow-up): the synthetic
 * HtmlTextFixTest/ParagraphingFixTest probes pass, but Aster reports
 * list spacing AND paragraph direction are still wrong on the phone.
 * This probe drives the REAL pipeline — parseText (fresh import) then
 * HtmlText.prepareChapterBody (display time) — and prints the actual
 * HTML, so a gap between the synthetic probe and the real markup
 * shows up here instead of on his phone.
 */
class PipelineE2EProbeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fallback(n: Int) = "Section $n"

    @Test
    fun md_looseListAndMixedDirection_endToEnd() {
        val md = """
            # کتاب نمونه

            این یک پاراگراف فارسی است که جهت بخش را راست‌چین می‌کند و متن فارسی زیادی دارد تا اکثریت حروف بخش فارسی باشد و جهت کلی بخش راست‌چین حساب شود.

            سلام Hello world this is a mixed paragraph with mostly Latin words after a short Persian greeting at its start.

            - آیتم اول لیست

            - آیتم دوم لیست

            - آیتم سوم لیست

            پاراگراف فارسی پایانی که دوباره جهت بخش را راست‌چین نگه می‌دارد و متن فارسی دارد.
        """.trimIndent()
        val out = tempFolder.newFolder("out-md")
        // NOTE: .md files go through parseMarkdown (BookConverter),
        // not parseText — parseText would treat the markdown as plain
        // text and the list would never become a <ul> at all.
        val book = TextBookParser.parseMarkdown(
            md.toByteArray(Charsets.UTF_8), "sample.md", out, ::fallback
        )
        val stored = book.chapters.joinToString("\n") { it.file.readText() }
        println("=== STORED chapter HTML ===")
        println(stored)
        val prepared = HtmlText.prepareChapterBody(stored, out)
        println("=== PREPARED (display-time) HTML ===")
        println(prepared)
        // D2a: loose list items must be bare <li>, no <p> inside.
        if (prepared.contains("<li><p>") || prepared.contains("<li>\n<p>")) {
            fail("loose list still wrapped in <p> in prepared HTML")
        }
        assertTrue("loose list lost its items", prepared.contains("آیتم اول لیست"))
        // D3: the mixed paragraph starts with a Persian word inside an
        // RTL chapter, so Windows stamps it RTL even though Latin wins
        // the majority.
        assertTrue(
            "mixed paragraph not stamped RTL: $prepared",
            prepared.contains("<p dir=\"rtl\" data-rdr=\"r\">سلام")
        )
    }

    @Test
    fun txt_titleClaimAndDirection_endToEnd() {
        val text = "نام رمان\n\nاین یک پاراگراف فارسی است که جهت بخش را راست‌چین می‌کند و متن فارسی زیادی دارد تا اکثریت بخش فارسی باشد.\n\nHello world this is a Latin paragraph inside a Persian book with many Latin words here.\n"
        val out = tempFolder.newFolder("out-txt")
        val book = TextBookParser.parseText(
            text.toByteArray(Charsets.UTF_8), "novel.txt", out, ::fallback
        )
        println("=== TXT title: ${book.title} ===")
        assertEquals("نام رمان", book.title)
        val stored = book.chapters.joinToString("\n") { it.file.readText() }
        println("=== STORED chapter HTML ===")
        println(stored)
        val prepared = HtmlText.prepareChapterBody(stored, out)
        println("=== PREPARED (display-time) HTML ===")
        println(prepared)
        // Title line must have left the body.
        assertTrue("title line still in body", !prepared.contains("نام رمان"))
        // The Latin paragraph in an RTL chapter: first strong letter is
        // Latin, so Windows stamps it LTR.
        assertTrue(
            "Latin paragraph not stamped LTR: $prepared",
            prepared.contains("<p dir=\"ltr\" data-rdr=\"l\">Hello")
        )
    }
}
