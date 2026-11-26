package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun txt_shortFirstLineBeforeBody_isTitleLikeWindows() {
        val text = "به مادرم\n\nداستان از اینجا شروع می‌شود.\n\nادامه‌ی داستان.\n"
        val out = tempFolder.newFolder("out-ded")
        val book = TextBookParser.parseText(
            text.toByteArray(Charsets.UTF_8), "story.txt", out, ::fallback
        )
        // Windows parity (2026-11-26): the Windows reader claims a
        // short first line as the title unconditionally — the
        // dedication line becomes the title and leaves the body,
        // exactly as on Windows. (This supersedes the old guard that
        // kept such a line in the body.)
        assertEquals("به مادرم", book.title)
        val body = book.chapters.joinToString("") { it.file.readText() }
        assertFalse(body.contains("به مادرم"))
    }

    @Test
    fun txt_firstLineMatchingFileName_isTitle() {
        // Windows parity: the first line repeats the file's own name,
        // so it IS the title page even with no heading after it — the
        // line becomes the title and leaves the body.
        val text = "نام رمان\n\nمتن شروع کتاب که پاراگراف اول است.\n\nادامه‌ی داستان.\n"
        val out = tempFolder.newFolder("out-title")
        val book = TextBookParser.parseText(
            text.toByteArray(Charsets.UTF_8), "نام رمان.txt", out, ::fallback
        )
        assertEquals("نام رمان", book.title)
        val body = book.chapters.joinToString("") { it.file.readText() }
        assertTrue(!body.contains("نام رمان"))
        assertTrue(body.contains("متن شروع کتاب"))
    }

    @Test
    fun txt_firstLineMatchingFileName_arabicLetters_isTitle() {
        // File named with Arabic ك still matches a Persian title line.
        // (The title must not start with فصل/بخش/قسمت/کتاب: that word
        // makes isHeading claim it as a section heading instead.)
        val text = "رمان کوهستان\n\nمتن شروع کتاب.\n\nادامه.\n"
        val out = tempFolder.newFolder("out-title-ar")
        val fileName = "رمان کوهستان.txt".replace('ک', 'ك')
        val book = TextBookParser.parseText(
            text.toByteArray(Charsets.UTF_8), fileName, out, ::fallback
        )
        assertEquals("رمان کوهستان", book.title)
        val body = book.chapters.joinToString("") { it.file.readText() }
        assertTrue(!body.contains("رمان کوهستان"))
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

    private fun renderMd(md: String, name: String = "t.md"): String {
        val out = tempFolder.newFolder("out-" + name.hashCode().toString().replace("-", "n"))
        val book = TextBookParser.parseMarkdown(
            md.toByteArray(Charsets.UTF_8), name, out, ::fallback
        )
        return book.chapters.joinToString("") { it.file.readText() }
    }

    @Test
    fun markdown_taskList_rendersCheckboxes() {
        val body = renderMd(
            """
            # T

            - [x] کار انجام‌شده
            - [ ] کار انجام‌نشده
            """.trimIndent()
        )
        assertTrue("body was:\n$body", body.contains("task-list-item"))
        assertTrue("body was:\n$body", body.contains("type=\"checkbox\""))
        assertTrue("body was:\n$body", body.contains("checked"))
        assertTrue(body.contains("کار انجام‌شده"))
        assertTrue(body.contains("کار انجام‌نشده"))
    }

    @Test
    fun markdown_footnotes_renderWithBackrefs() {
        val body = renderMd(
            """
            # T

            این جمله یک پانویس دارد.[^1]

            [^1]: متن پانویس اول است.
            """.trimIndent()
        )
        assertTrue("body was:\n$body", body.contains("class=\"footnotes\""))
        assertTrue("body was:\n$body", body.contains("fn-1"))
        assertTrue("body was:\n$body", body.contains("fnref-1"))
        assertTrue(body.contains("متن پانویس اول است."))
    }

    @Test
    fun markdown_referenceLinksAndAutolinks() {
        val body = renderMd(
            """
            # T

            لینک مرجع: [گیت‌هاب][gh]

            لینک خودکار: <https://example.com>

            [gh]: https://github.com
            """.trimIndent()
        )
        assertTrue("body was:\n$body", body.contains("<a href=\"https://github.com\">گیت‌هاب</a>"))
        assertTrue("body was:\n$body", body.contains("<a href=\"https://example.com\">https://example.com</a>"))
        assertTrue("body was:\n$body", !body.contains("[gh]:"))
    }

    @Test
    fun markdown_images_remoteKept_relativeAltOnly() {
        val body = renderMd(
            """
            # T

            ![دوردست](https://example.com/pic.png)

            ![لوگو](images/logo.png)
            """.trimIndent()
        )
        assertTrue("body was:\n$body", body.contains("<img src=\"https://example.com/pic.png\""))
        assertTrue("body was:\n$body", body.contains("<em>لوگو</em>"))
        assertTrue("body was:\n$body", !body.contains("images/logo.png"))
    }

    @Test
    fun markdown_tableAlignment() {
        val body = renderMd(
            """
            # T

            | راست | وسط | چپ |
            |---:|:---:|---|
            | 1 | 2 | 3 |
            """.trimIndent()
        )
        assertTrue("body was:\n$body", body.contains("<table>"))
        assertTrue(
            "body was:\n$body",
            body.contains("align=\"center\"") || body.contains("text-align: center")
        )
        assertTrue(
            "body was:\n$body",
            body.contains("align=\"right\"") || body.contains("text-align: right")
        )
    }

    @Test
    fun markdown_subSuperscriptAndDefinitionList() {
        val body = renderMd(
            """
            # T

            آب H~2~O است و توان X^2^ است.

            اصطلاح اول
            : تعریف اصطلاح اول
            """.trimIndent()
        )
        assertTrue("body was:\n$body", body.contains("H<sub>2</sub>O"))
        assertTrue("body was:\n$body", body.contains("X<sup>2</sup>"))
        assertTrue("body was:\n$body", body.contains("<dl>"))
        assertTrue("body was:\n$body", body.contains("<dt>اصطلاح اول</dt>"))
        assertTrue("body was:\n$body", body.contains("<dd>تعریف اصطلاح اول</dd>"))
    }

    @Test
    fun markdown_nestedBlockquoteAndBoldWithItalic() {
        val body = renderMd(
            """
            # T

            > بیرونی
            >
            > > درونی

            این **متن *کج* وسط** است.
            """.trimIndent()
        )
        assertTrue(
            "body was:\n$body",
            body.split("<blockquote>").size - 1 >= 2
        )
        assertTrue(body.contains("درونی"))
        assertTrue("body was:\n$body", body.contains("<strong>متن <em>کج</em> وسط</strong>"))
    }

    @Test
    fun markdown_rawHtml_passthrough() {
        val body = renderMd(
            """
            # T

            متن با <b>پررنگ اچ‌تی‌ام‌ال</b> و خط جدید<br>بعدش.
            """.trimIndent()
        )
        assertTrue("body was:\n$body", body.contains("<b>پررنگ اچ‌تی‌ام‌ال</b>"))
        assertTrue("body was:\n$body", body.contains("<br"))
    }

    @Test
    fun markdown_setextH1_isTitle() {
        val md = """
            رمان من
            ========

            متن داستان.
        """.trimIndent()
        val out = tempFolder.newFolder("out-setext")
        val book = TextBookParser.parseMarkdown(
            md.toByteArray(Charsets.UTF_8), "novel.md", out, ::fallback
        )
        assertEquals("رمان من", book.title)
        assertTrue(book.chapters.joinToString("") { it.file.readText() }.contains("متن داستان."))
    }

    @Test
    fun markdown_titleOnly_rendersOneChapter() {
        // A lone `# Title` used to parse to ZERO chapters: the title
        // was consumed as the book title and no section remained, so
        // the copy import failed it as "no readable chapters" and the
        // reader opened a blank book. The rendered title itself must
        // become the one section.
        val out = tempFolder.newFolder("out-title-only")
        val book = TextBookParser.parseMarkdown(
            "# Title\n".toByteArray(Charsets.UTF_8), "t.md", out, ::fallback
        )
        assertEquals("Title", book.title)
        assertEquals(1, book.chapters.size)
        assertTrue(book.chapters[0].file.readText().contains("Title"))
    }

    @Test
    fun markdown_empty_stillZeroChapters() {
        // A truly empty document honestly has nothing to render.
        val out = tempFolder.newFolder("out-empty")
        val book = TextBookParser.parseMarkdown(
            ByteArray(0), "t.md", out, ::fallback
        )
        assertEquals(0, book.chapters.size)
    }

    @Test
    fun markdown_deeplyNested_noStackOverflowEscape() {
        // ~2000 nested list levels overflow a 1 MB IO-thread stack
        // inside flexmark (probe, real 0.64.8 jars). StackOverflowError
        // is an Error, so it used to escape every `catch (Exception)`
        // upstream and crash the app on import. The parse must turn it
        // into a plain failure instead — never let the Error out.
        val deep = buildString {
            repeat(2000) { i -> append("  ".repeat(i)).append("- item\n") }
        }
        val out = tempFolder.newFolder("out-deep")
        try {
            TextBookParser.parseMarkdown(
                deep.toByteArray(Charsets.UTF_8), "t.md", out, ::fallback
            )
        } catch (e: StackOverflowError) {
            throw AssertionError("StackOverflowError escaped parseMarkdown", e)
        } catch (e: IllegalStateException) {
            // Expected on a small stack: the wrapped "too deeply nested" failure.
            assertTrue(e.message?.contains("deeply nested") == true)
        }
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
