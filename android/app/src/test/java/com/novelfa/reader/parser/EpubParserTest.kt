package com.novelfa.reader.parser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
class EpubParserTest {
    @get:Rule
    val tempFolder = TemporaryFolder()
    @Test
    fun resolveHref_decodesPersianAndStripsHash() {
        val folder = tempFolder.newFolder("opf")
        File(folder, "فصل ۱.xhtml").writeText("x")
        val got = HrefResolver.resolve(folder, "%D9%81%D8%B5%D9%84%20%DB%B1.xhtml#ch1")
        assertTrue(got.exists())
    }
    @Test
    fun spineOrder_isPreserved() {
        val dir = File("src/test/resources/book1")
        val book = EpubParser.parse(dir, "mybook.epub")
        assertEquals(listOf("فصل 1", "فصل 2"), book.chapters.map { it.title })
        assertEquals(listOf("ch1.xhtml", "ch2.xhtml"), book.chapters.map { it.file.name })
        assertEquals("cover.jpg", book.coverFile?.name)
        assertEquals("My Title", book.title)
    }

    // --- Parser hardening (ported from AI Studio defect pass) ---

    @Test
    fun parse_missingContainer_fallsBackToOpfScan() {
        // Same book as book1 but WITHOUT META-INF/container.xml.
        val dir = tempFolder.newFolder("nocontainer")
        val oebps = File(dir, "OEBPS").apply { mkdirs() }
        val src = File("src/test/resources/book1/OEBPS")
        File(oebps, "content.opf").writeText(File(src, "content.opf").readText())
        File(oebps, "ch1.xhtml").writeText(File(src, "ch1.xhtml").readText())
        File(oebps, "ch2.xhtml").writeText(File(src, "ch2.xhtml").readText())
        File(oebps, "cover.jpg").writeBytes(File(src, "cover.jpg").readBytes())
        val book = EpubParser.parse(dir, "nocontainer.epub")
        assertEquals(listOf("فصل 1", "فصل 2"), book.chapters.map { it.title })
        assertEquals("My Title", book.title)
        assertEquals("cover.jpg", book.coverFile?.name)
    }

    @Test
    fun parse_doctypeOpf_rejectedBeforeParsing() {
        // Portable XXE guard: a literal DOCTYPE scan rejects the file before
        // any parser is involved (works on every Android JAXP provider,
        // unlike setFeature flags).
        val dir = tempFolder.newFolder("doctype")
        val oebps = File(dir, "OEBPS").apply { mkdirs() }
        val src = File("src/test/resources/book1/OEBPS")
        File(oebps, "content.opf").writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE package [\n<!ENTITY xxe SYSTEM \"file:///etc/passwd\">\n]>\n" +
                File(src, "content.opf").readText()
        )
        File(oebps, "ch1.xhtml").writeText(File(src, "ch1.xhtml").readText())
        File(oebps, "ch2.xhtml").writeText(File(src, "ch2.xhtml").readText())
        try {
            EpubParser.parse(dir, "doctype.epub")
            fail("expected DOCTYPE rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("DOCTYPE"))
        }
    }

    @Test
    fun parse_namespaceLessOpf_stillParses() {
        val dir = tempFolder.newFolder("nons")
        val oebps = File(dir, "OEBPS").apply { mkdirs() }
        val src = File("src/test/resources/book1/OEBPS")
        File(oebps, "content.opf").writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><package version=\"3.0\">" +
                "<metadata><title>NS Title</title></metadata>" +
                "<manifest><item id=\"c1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>" +
                "<spine><itemref idref=\"c1\"/></spine></package>"
        )
        File(oebps, "ch1.xhtml").writeText(File(src, "ch1.xhtml").readText())
        val book = EpubParser.parse(dir, "nons.epub")
        assertEquals(listOf("فصل 1"), book.chapters.map { it.title })
        assertEquals("NS Title", book.title)
    }
}
