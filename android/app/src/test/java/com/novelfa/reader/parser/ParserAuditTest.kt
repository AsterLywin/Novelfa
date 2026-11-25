package com.novelfa.reader.parser

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser audit probes (2026-11-25, whole-app test round).
 * Each test demonstrates one finding from the parser audit; each
 * is EXPECTED TO FAIL until its finding is fixed. Plain JVM
 * JUnit — no Robolectric needed (no Android APIs in these paths).
 *
 * Findings (ranked, see the audit report):
 *  1. audit_latin1AccentedText_notMangledAsArabic — decodeText
 *     mis-detects Latin-1 accented text as Windows-1256.
 *  2. audit_rootCoverPlusSeriesFolder_keepsSeparateChapters —
 *     chaptersForEntries collapses chapters when a root-level
 *     page disables the shared-prefix strip.
 *  3. audit_dialogueLineWithAcronym_notAChapter — isHeading
 *     treats any line with an uppercase Latin acronym and no
 *     lowercase as a chapter heading.
 *  4. audit_docxRelsTarget_cannotEscapeExtractDir — a DOCX
 *     rels Target with ../ copies an arbitrary local file into
 *     the book's media/ and makes it the cover.
 *  5. audit_hugeCodeBlockSection_isChunked — chunkSections
 *     never splits a section whose body has no <p> tags (one
 *     huge <pre>/<table>), breaking the 12,000-char invariant.
 */
class ParserAuditTest {

    private fun fallback(n: Int) = "Section $n"

    private fun tempDir(prefix: String): File {
        val dir = File.createTempFile(prefix, "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    /**
     * FINDING 1 (CONFIRMED): a Latin-1 text with accented
     * characters ("café", "très") is not valid UTF-8, so
     * decodeText falls to the Windows-1256 heuristic — and the
     * heuristic fires on ANY single Arabic-script character.
     * But Windows-1256 maps 0xC0–0xFF (é, è, ü, …) to Arabic
     * letters, so one accented Latin letter is enough to make
     * the whole file decode as Arabic gibberish.
     */
    @Test
    fun audit_latin1AccentedText_notMangledAsArabic() {
        val latin1 = "Le café est bon. Très bien!"
        val bytes = latin1.toByteArray(StandardCharsets.ISO_8859_1)
        val decoded = TextBookParser.decodeText(bytes)
        assertEquals(latin1, decoded)
    }

    /**
     * FINDING 2 (CONFIRMED): chaptersForEntries strips the
     * path every foldered page shares ("Series/") only when
     * EVERY page is foldered. One root-level page (the cover)
     * disables the strip, so "Series/Ch 01" and "Series/Ch 02"
     * both collapse into a single chapter called "Series" —
     * the exact outcome the function's own doc says the strip
     * exists to prevent. This is the common manhwa-pack layout
     * (cover + series folder).
     */
    @Test
    fun audit_rootCoverPlusSeriesFolder_keepsSeparateChapters() {
        val chapters = CbzBook.chaptersForEntries(
            listOf(
                "000-cover.jpg",
                "Series/Ch 01/001.jpg",
                "Series/Ch 01/002.jpg",
                "Series/Ch 02/001.jpg"
            )
        )
        assertEquals(2, chapters.size)
        assertEquals("Ch 01", chapters[0].title)
        assertEquals(1, chapters[0].firstPageIndex)
        assertEquals("Ch 02", chapters[1].title)
        assertEquals(3, chapters[1].firstPageIndex)
    }

    /**
     * FINDING 3 (CONFIRMED): isHeading treats a line as a
     * chapter heading when it has no lowercase and any
     * uppercase letter — with no script check. A Persian
     * dialogue line holding a Latin acronym ("او گفت: OK")
     * has no lowercase letters (Persian has no case) and one
     * uppercase acronym, so it becomes a chapter title and the
     * text after it starts a bogus chapter.
     */
    @Test
    fun audit_dialogueLineWithAcronym_notAChapter() {
        val dir = tempDir("audit-txt-")
        try {
            val text = "My Book\n\nفصل ۱\n\nمتن اول.\n\nاو گفت: OK\n\nمتن دوم.\n"
            val book = TextBookParser.parseText(
                text.toByteArray(Charsets.UTF_8),
                "book.txt", dir, ::fallback
            )
            assertEquals(1, book.chapters.size)
            assertEquals("فصل ۱", book.chapters[0].title)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * FINDING 4 (CONFIRMED): DocxParser resolves an image
     * rels Target with File(extractDir, "word/" + target)
     * and never confines it — unlike unzip() in the same file,
     * which rejects entries escaping the dir. A crafted DOCX
     * whose rels Target is "../../secret.txt" makes the parser
     * copy an arbitrary local file into the book's media/ and
     * use it as the cover.
     */
    @Test
    fun audit_docxRelsTarget_cannotEscapeExtractDir() {
        val root = tempDir("audit-docx-")
        try {
            val secret = File(root, "secret.txt")
            secret.writeText("TOP-SECRET", Charsets.UTF_8)
            val docxFile = File(root, "evil.docx")
            ZipOutputStream(docxFile.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("word/document.xml"))
                out.write(
                    ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                        "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                        "<w:body><w:p><w:r><w:drawing><w:inline><a:graphic><a:graphicData " +
                        "uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                        "<pic:pic><pic:blipFill><a:blip r:embed=\"rId1\"/></pic:blipFill>" +
                        "</pic:pic></a:graphicData></a:graphic></w:inline></w:drawing>" +
                        "</w:r></w:p></w:body></w:document>")
                        .toByteArray(Charsets.UTF_8)
                )
                out.closeEntry()
                out.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
                out.write(
                    ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                        "<Relationship Id=\"rId1\" " +
                        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" " +
                        "Target=\"../../secret.txt\"/>" +
                        "</Relationships>")
                        .toByteArray(Charsets.UTF_8)
                )
                out.closeEntry()
            }
            val outDir = File(root, "out")
            val book = DocxParser.parseFile(docxFile, outDir, ::fallback)
            assertFalse(
                "rels Target escaped the extract dir into media/",
                File(outDir, "media/secret.txt").exists()
            )
            assertNull("escaped file became the cover", book.coverFile)
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * FINDING 5 (CONFIRMED): chunkSections promises no section
     * exceeds MAX_SECTION_CHARS (12,000) — the paged reader
     * binary-searches a single over-long leaf for every page
     * break, tens of seconds on a phone. But the split only
     * works on <p> boundaries: a section whose body is one
     * huge <pre> (a long fenced code block) or one huge
     * <table> has no <p> tags, splitLongParagraphs passes it
     * through whole, and the section ships over the cap.
     */
    @Test
    fun audit_hugeCodeBlockSection_isChunked() {
        val dir = tempDir("audit-md-")
        try {
            val fence = "word ".repeat(4000) // 20,000 chars, one block
            val md = "# Title\n\n## Big Code\n\n```text\n$fence\n```\n"
            val book = TextBookParser.parseMarkdown(
                md.toByteArray(Charsets.UTF_8),
                "big.md", dir, ::fallback
            )
            assertTrue(
                "a 20,000-char code-block section must split into 2+ chapters, got ${book.chapters.size}",
                book.chapters.size >= 2
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
