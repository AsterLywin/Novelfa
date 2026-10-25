package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextBookParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fallback(n: Int) = "Section $n"

    @Test
    fun txt_headingLines_splitSections() {
        val text = "My Book\n\nفصل ۱\n\nمتن فصل اول اینجاست.\n\nفصل ۲\n\nمتن فصل دوم اینجاست.\n"
        val out = tempFolder.newFolder("out")
        val book = TextBookParser.parseText(
            text.toByteArray(Charsets.UTF_8), "book.txt", out, ::fallback
        )
        assertEquals("My Book", book.title)
        assertEquals(listOf("فصل ۱", "فصل ۲"), book.chapters.map { it.title })
        assertTrue(book.chapters[0].file.readText().contains("متن فصل اول"))
    }

    @Test
    fun txt_noHeadings_chunksBySizeWithFallbackTitles() {
        val para = "این یک پاراگراف طولانی است. ".repeat(200)
        val text = (para + "\n\n").repeat(10)
        val out = tempFolder.newFolder("out")
        val book = TextBookParser.parseText(
            text.toByteArray(Charsets.UTF_8), "long.txt", out, ::fallback
        )
        assertTrue(book.chapters.size > 1)
        assertTrue(book.chapters.all { it.title.startsWith("Section") })
        // Nothing lost: every chapter file is non-empty.
        assertTrue(book.chapters.all { it.file.length() > 0 })
    }

    @Test
    fun txt_windows1256Persian_decodes() {
        // Windows-1256 bytes for "سلام" are not valid UTF-8, so the
        // strict-UTF-8 attempt must fail and the 1256 fallback must win.
        val bytes = "سلام".toByteArray(java.nio.charset.Charset.forName("windows-1256"))
        val out = tempFolder.newFolder("out")
        val book = TextBookParser.parseText(bytes, "fa.txt", out, ::fallback)
        val body = book.chapters.single().file.readText()
        assertTrue(body.contains("سلام"))
    }

    @Test
    fun txt_bomAndCrlf_normalized() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "خط اول\r\n\r\nخط دوم\r\n".toByteArray(Charsets.UTF_8)
        val out = tempFolder.newFolder("out")
        val book = TextBookParser.parseText(bytes, "bom.txt", out, ::fallback)
        val body = book.chapters.joinToString("") { it.file.readText() }
        assertTrue(body.contains("خط اول"))
        assertTrue(body.contains("خط دوم"))
        assertTrue(!body.contains("\uFEFF"))
    }

    @Test
    fun markdown_h1Title_h2Sections_inlineFormatting() {
        val md = """
            # رمان من

            ## فصل اول

            این **متن پررنگ** و *کج* است و `کد` دارد.

            - مورد یک
            - مورد دو

            ## فصل دوم

            ادامه‌ی داستان [پیوند](https://example.com) است.
        """.trimIndent()
        val out = tempFolder.newFolder("out")
        val book = TextBookParser.parseMarkdown(
            md.toByteArray(Charsets.UTF_8), "novel.md", out, ::fallback
        )
        assertEquals("رمان من", book.title)
        assertEquals(listOf("فصل اول", "فصل دوم"), book.chapters.map { it.title })
        val first = book.chapters[0].file.readText()
        assertTrue(first.contains("<strong>متن پررنگ</strong>"))
        assertTrue(first.contains("<em>کج</em>"))
        assertTrue(first.contains("<code>کد</code>"))
        assertTrue(first.contains("<ul>") && first.contains("<li>مورد یک</li>"))
        assertTrue(book.chapters[1].file.readText().contains("<a href=\"https://example.com\">پیوند</a>"))
    }

    @Test
    fun markdown_tableAndCodeBlock_render() {
        val md = """
            # T

            | A | B |
            |---|---|
            | 1 | 2 |

            ```
            val x = 1 < 2
            ```
        """.trimIndent()
        val out = tempFolder.newFolder("out")
        val book = TextBookParser.parseMarkdown(
            md.toByteArray(Charsets.UTF_8), "t.md", out, ::fallback
        )
        val body = book.chapters.joinToString("") { it.file.readText() }
        assertTrue(body.contains("<table>") && body.contains("<th>A</th>"))
        assertTrue(body.contains("<pre><code>val x = 1 &lt; 2</code></pre>"))
    }

    @Test
    fun convertedBook_writeRead_roundTripsTitles() {
        val out = tempFolder.newFolder("out")
        val written = ConvertedBook.write(
            out, "My Book",
            listOf(
                ConvertedBook.Section("One", "<p>first</p>"),
                ConvertedBook.Section("Two", "<p>second</p>")
            )
        )
        assertEquals(listOf("One", "Two"), written.chapters.map { it.title })
        val read = ConvertedBook.read(out, "My Book.txt", ::fallback)!!
        assertEquals(listOf("One", "Two"), read.chapters.map { it.title })
        assertEquals("My Book", read.title)
    }

    @Test
    fun convertedBook_chunking_keepsTitleSuffixNeutral() {
        // Six 3000-char paragraphs: splitting happens at paragraph
        // boundaries (a single huge paragraph cannot split).
        val big = (1..6).joinToString("") { "<p>" + "x".repeat(3000) + "</p>" }
        val chunked = ConvertedBook.chunkSections(
            listOf(ConvertedBook.Section("Big", big))
        )
        assertTrue(chunked.size >= 2)
        assertEquals("Big", chunked[0].title)
        assertEquals("Big (2)", chunked[1].title)
    }
}
