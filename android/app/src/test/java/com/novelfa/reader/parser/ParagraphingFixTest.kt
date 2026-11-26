package com.novelfa.reader.parser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-26 bug round: TXT/MD paragraphing must match the Windows
 * NovelFaReader. Each test is EXPECTED TO FAIL until its fix lands.
 * Plain JVM JUnit — no Robolectric needed (no Android APIs in these paths).
 *
 *  D1 — TXT first-line title claim (Windows claims it unconditionally).
 *  D2a — MD loose lists: Windows emits bare <li>, flexmark wraps in <p>.
 *  D2b — MD blockquotes: Windows makes one <blockquote> per blank-line run.
 */
class ParagraphingFixTest {

    private fun fallback(n: Int) = "Section $n"

    private fun tempDir(prefix: String): File {
        val dir = File.createTempFile(prefix, "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    @Test
    fun txtFirstLine_claimedAsTitleLikeWindows() {
        val dir = tempDir("para-d1-")
        try {
            val text = "به نام خدا\n\nاین پاراگراف اول است\nکه در دو خط نوشته شده\n\nاین پاراگراف دوم است\n"
            val book = TextBookParser.parseText(
                text.toByteArray(Charsets.UTF_8), "book.txt", dir, ::fallback
            )
            assertEquals("به نام خدا", book.title)
            val body = book.chapters.joinToString("") { it.file.readText() }
            assertFalse(
                "first line must leave the body when it becomes the title",
                body.contains("به نام خدا")
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mdLooseList_itemsHaveNoParagraphWrapper() {
        val dir = tempDir("para-d2a-")
        try {
            val md = "- آیتم یک\n\n- آیتم دو\n"
            val book = TextBookParser.parseMarkdown(
                md.toByteArray(Charsets.UTF_8), "list.md", dir, ::fallback
            )
            val html = book.chapters.joinToString("") { it.file.readText() }
            assertFalse(
                "loose list items must not carry <p> wrappers (Windows emits bare <li>)",
                Regex("<li>\\s*<p>").containsMatchIn(html)
            )
            assertTrue(html.contains("آیتم یک"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mdBlockquote_splitPerBlankLineLikeWindows() {
        val dir = tempDir("para-d2b-")
        try {
            val md = "> نقل اول\n\n> نقل دوم\n"
            val book = TextBookParser.parseMarkdown(
                md.toByteArray(Charsets.UTF_8), "quote.md", dir, ::fallback
            )
            val html = book.chapters.joinToString("") { it.file.readText() }
            assertEquals(
                "each blank-line-separated quote run is its own <blockquote> on Windows",
                2, "<blockquote>".toRegex().findAll(html).count()
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
