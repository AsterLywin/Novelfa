package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Fb2ParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fallback(n: Int) = "Section $n"

    /** 1x1 transparent PNG, base64. */
    private val tinyPngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private fun sampleFb2(withCoverpage: Boolean = true): String {
        val cover = if (withCoverpage) {
            "<coverpage><image l:href=\"#cover\"/></coverpage>"
        } else ""
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" " +
            "xmlns:l=\"http://www.w3.org/1999/xlink\">\n" +
            "<description><title-info>" +
            "<book-title>کتاب نمونه</book-title>" +
            "<author><first-name>علی</first-name><last-name>رضایی</last-name></author>" +
            cover +
            "</title-info></description>\n" +
            "<body>\n" +
            "<section><title><p>بخش یک</p></title>\n" +
            "<p>سلام <strong>دنیا</strong> و <emphasis>تاکید</emphasis></p>\n" +
            "<p>متن با پاورقی<a l:href=\"#n1\" type=\"note\">[1]</a> در ادامه</p>\n" +
            "<poem><stanza><v>بیت اول</v><v>بیت دوم</v></stanza></poem>\n" +
            "<section><title><p>زیربخش</p></title><p>متن زیربخش</p></section>\n" +
            "</section>\n" +
            "<section><title><p>بخش دو</p></title>\n" +
            "<p>متن بخش دو <image l:href=\"#pic1\"/></p>\n" +
            "</section>\n" +
            "</body>\n" +
            "<body name=\"notes\">\n" +
            "<section id=\"n1\"><title><p>1</p></title>" +
            "<p>متن پاورقی نمونه</p></section>\n" +
            "</body>\n" +
            "<binary id=\"cover\" content-type=\"image/png\">$tinyPngBase64</binary>\n" +
            "<binary id=\"pic1\" content-type=\"image/png\">$tinyPngBase64</binary>\n" +
            "</FictionBook>\n"
    }

    @Test
    fun twoSections_footnote_poem_cover() {
        val out = tempFolder.newFolder("out")
        val book = Fb2Parser.parse(
            sampleFb2().toByteArray(Charsets.UTF_8), "book.fb2", out, ::fallback
        )
        assertEquals("کتاب نمونه", book.title)
        assertEquals(listOf("بخش یک", "بخش دو"), book.chapters.map { it.title })
        val ch1 = book.chapters[0].file.readText()
        assertTrue(ch1.contains("<strong>دنیا</strong>"))
        assertTrue(ch1.contains("<em>تاکید</em>"))
        assertTrue(ch1.contains("data-novelfa-footnote=\"1\""))
        assertTrue(ch1.contains("بیت اول<br/>بیت دوم"))
        // Nested section stays inside its chapter as a heading.
        assertTrue(ch1.contains("<h2>زیربخش</h2>"))
        assertTrue(ch1.contains("متن زیربخش"))
        val ch2 = book.chapters[1].file.readText()
        assertTrue(ch2.contains("<img src=\"media/pic1.png\""))
        // Footnote texts land on the last section, without the note's number.
        assertTrue(ch2.contains("متن پاورقی نمونه"))
        assertTrue(ch2.contains("↩</a> متن پاورقی نمونه</li>"))
        // Cover: the coverpage binary, decoded to media/.
        val cover = book.coverFile
        assertNotNull(cover)
        assertTrue(cover!!.exists())
        assertTrue(cover.length() > 0)
        assertEquals("cover.png", cover.name)
    }

    @Test
    fun noCoverpage_firstBinaryImageIsCover() {
        val out = tempFolder.newFolder("out")
        val book = Fb2Parser.parse(
            sampleFb2(withCoverpage = false).toByteArray(Charsets.UTF_8),
            "book.fb2", out, ::fallback
        )
        // No coverpage: the first binary image still becomes the cover.
        assertNotNull(book.coverFile)
        assertEquals("cover.png", book.coverFile!!.name)
    }

    @Test
    fun sectionlessBody_isOneChapter() {
        val xml = "<?xml version=\"1.0\"?><FictionBook>" +
            "<description><title-info><book-title>داستان کوتاه</book-title>" +
            "</title-info></description>" +
            "<body><p>فقط یک پاراگراف</p></body></FictionBook>"
        val out = tempFolder.newFolder("out")
        val book = Fb2Parser.parse(xml.toByteArray(Charsets.UTF_8), "s.fb2", out, ::fallback)
        assertEquals("داستان کوتاه", book.title)
        assertEquals(1, book.chapters.size)
        assertTrue(book.chapters[0].file.readText().contains("فقط یک پاراگراف"))
    }

    @Test
    fun invalidFb2_throws() {
        val out = tempFolder.newFolder("out")
        try {
            Fb2Parser.parse(
                "<html><body>not fb2</body></html>".toByteArray(Charsets.UTF_8),
                "x.fb2", out, ::fallback
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("FB2"))
        }
    }

    @Test
    fun emptyFb2_hasNoChapters() {
        val xml = "<?xml version=\"1.0\"?><FictionBook>" +
            "<description><title-info><book-title>خالی</book-title>" +
            "</title-info></description><body></body></FictionBook>"
        val out = tempFolder.newFolder("out")
        val book = Fb2Parser.parse(xml.toByteArray(Charsets.UTF_8), "e.fb2", out, ::fallback)
        assertEquals("خالی", book.title)
        assertTrue(book.chapters.isEmpty())
    }

    @Test
    fun fbz_unwrapsFirstFb2Entry() {
        val fbz = tempFolder.newFile("book.fbz")
        ZipOutputStream(fbz.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("not the book".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("folder/book.fb2"))
            zos.write(sampleFb2().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val out = tempFolder.newFolder("out")
        val book = Fb2Parser.parseFile(fbz, out, ::fallback)
        assertEquals("کتاب نمونه", book.title)
        assertEquals(2, book.chapters.size)
        assertNotNull(book.coverFile)
    }

    @Test
    fun metadataScan_countsTopLevelSectionsOnly() {
        val scan = Fb2Parser.scanMetadata(sampleFb2().toByteArray(Charsets.UTF_8))
        assertEquals("کتاب نمونه", scan.title)
        // Two top-level sections; the nested "زیربخش" is NOT counted.
        assertEquals(2, scan.sectionCount)
        assertTrue(scan.hasBodyText)
        assertEquals("cover", scan.coverBinaryId)
        assertTrue(scan.binaries.containsKey("cover"))
        assertTrue(scan.binaries.containsKey("pic1"))
    }

    @Test
    fun metadataScan_sectionlessBody_countsOneChapter() {
        val xml = "<?xml version=\"1.0\"?><FictionBook>" +
            "<description><title-info><book-title>T</book-title>" +
            "</title-info></description>" +
            "<body><p>text</p></body></FictionBook>"
        val scan = Fb2Parser.scanMetadata(xml.toByteArray(Charsets.UTF_8))
        assertEquals(0, scan.sectionCount)
        assertTrue(scan.hasBodyText)
    }

    @Test
    fun metadataScan_noFictionBook_throws() {
        try {
            Fb2Parser.scanMetadata("<html/>".toByteArray(Charsets.UTF_8))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("FB2"))
        }
    }

    @Test
    fun extractCover_writesCoverFile() {
        val scratch = tempFolder.newFolder("scratch")
        val cover = Fb2Parser.extractCover(
            sampleFb2().toByteArray(Charsets.UTF_8), scratch
        )
        assertNotNull(cover)
        assertTrue(cover!!.exists())
        assertTrue(cover.length() > 0)
        // The 1x1 PNG decodes to its real bytes (PNG magic).
        val bytes = cover.readBytes()
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals(0x50.toByte(), bytes[1])
        assertEquals(0x4E.toByte(), bytes[2])
        assertEquals(0x47.toByte(), bytes[3])
    }

    @Test
    fun extractCover_noBinaries_returnsNull() {
        val xml = "<?xml version=\"1.0\"?><FictionBook>" +
            "<description><title-info><book-title>T</book-title>" +
            "</title-info></description>" +
            "<body><section><p>x</p></section></body></FictionBook>"
        val scratch = tempFolder.newFolder("scratch")
        assertNull(
            Fb2Parser.extractCover(xml.toByteArray(Charsets.UTF_8), scratch)
        )
    }

    @Test
    fun fb2Bytes_plainFile_returnsWholeFile() {
        val f = tempFolder.newFile("plain.fb2")
        f.writeText(sampleFb2(), Charsets.UTF_8)
        assertTrue(String(Fb2Parser.fb2Bytes(f), Charsets.UTF_8).contains("FictionBook"))
    }

    @Test
    fun unreferencedFootnote_doesNotBreakParse() {
        // A note reference whose id has no notes-body section keeps
        // its typed label instead of a dead link.
        val xml = "<?xml version=\"1.0\"?><FictionBook>" +
            "<description><title-info><book-title>T</book-title>" +
            "</title-info></description>" +
            "<body><section><title><p>S</p></title>" +
            "<p>text<a l:href=\"#missing\" type=\"note\" " +
            "xmlns:l=\"http://www.w3.org/1999/xlink\">[7]</a> end</p>" +
            "</section></body></FictionBook>"
        val out = tempFolder.newFolder("out")
        val book = Fb2Parser.parse(xml.toByteArray(Charsets.UTF_8), "u.fb2", out, ::fallback)
        assertEquals(1, book.chapters.size)
        val html = book.chapters[0].file.readText()
        assertTrue(html.contains("[7]"))
        assertTrue(!html.contains("novelfa-fn-"))
    }
}
