package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookFormatTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun extensions_mapToFormats() {
        assertEquals(BookFormat.EPUB, BookFormat.fromExtension("book.epub"))
        assertEquals(BookFormat.TXT, BookFormat.fromExtension("notes.TXT"))
        assertEquals(BookFormat.MARKDOWN, BookFormat.fromExtension("doc.markdown"))
        assertEquals(BookFormat.PDF, BookFormat.fromExtension("paper.pdf"))
        assertEquals(BookFormat.DOCX, BookFormat.fromExtension("report.docx"))
        assertEquals(BookFormat.ZIP, BookFormat.fromExtension("archive.zip"))
        assertEquals(BookFormat.FB2, BookFormat.fromExtension("book.fb2"))
        assertEquals(BookFormat.FBZ, BookFormat.fromExtension("book.FBZ"))
        assertEquals(BookFormat.RTF, BookFormat.fromExtension("book.rtf"))
        assertNull(BookFormat.fromExtension("noext"))
        assertNull(BookFormat.fromExtension("book.cbr"))
        assertNull(BookFormat.fromExtension("book.cb7"))
    }

    @Test
    fun sniff_extensionlessPdf() {
        val f = tempFolder.newFile("mystery")
        f.writeBytes("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        assertEquals(BookFormat.PDF, BookFormat.detect(f))
    }

    @Test
    fun sniff_extensionlessText() {
        val f = tempFolder.newFile("mystery")
        f.writeText("سلام دنیا\nHello world\n", Charsets.UTF_8)
        assertEquals(BookFormat.TXT, BookFormat.detect(f))
    }

    @Test
    fun sniff_extensionlessDocx() {
        val f = tempFolder.newFile("mystery.docx")
        java.util.zip.ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zos.write("<w:document/>".toByteArray())
            zos.closeEntry()
        }
        val renamed = File(tempFolder.root, "mystery")
        f.renameTo(renamed)
        assertEquals(BookFormat.DOCX, BookFormat.detect(renamed))
    }

    @Test
    fun sniff_extensionlessFb2() {
        val f = tempFolder.newFile("mystery")
        f.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">" +
                "<description><title-info><book-title>T</book-title>" +
                "</title-info></description><body><section><p>x</p>" +
                "</section></body></FictionBook>",
            Charsets.UTF_8
        )
        assertEquals(BookFormat.FB2, BookFormat.detect(f))
        assertEquals(BookFormat.FB2, BookFormat.detectStrongContent(f))
    }

    @Test
    fun sniff_extensionlessRtf() {
        val f = tempFolder.newFile("mystery")
        f.writeText("{\\rtf1\\ansi\\deff0 Hello world}", Charsets.UTF_8)
        assertEquals(BookFormat.RTF, BookFormat.detect(f))
        assertEquals(BookFormat.RTF, BookFormat.detectStrongContent(f))
    }

    @Test
    fun sniff_extensionlessFbz() {
        val f = tempFolder.newFile("mystery")
        java.util.zip.ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("book.fb2"))
            zos.write("<FictionBook/>".toByteArray())
            zos.closeEntry()
        }
        assertEquals(BookFormat.FBZ, BookFormat.detect(f))
        assertEquals(BookFormat.FBZ, BookFormat.detectStrongContent(f))
    }

    @Test
    fun strongContent_fb2OverrulesWrongExtension() {
        // An FB2 misnamed .txt must sniff as FB2, not TXT.
        val f = tempFolder.newFile("book.txt")
        f.writeText(
            "<?xml version=\"1.0\"?><FictionBook><body/></FictionBook>",
            Charsets.UTF_8
        )
        assertEquals(BookFormat.FB2, BookFormat.detectStrongContent(f))
        // An RTF misnamed .txt must sniff as RTF.
        val g = tempFolder.newFile("notes.txt")
        g.writeText("{\\rtf1 Hello}", Charsets.UTF_8)
        assertEquals(BookFormat.RTF, BookFormat.detectStrongContent(g))
    }

    @Test
    fun sniff_unknown_returnsNull() {
        val f = tempFolder.newFile("mystery")
        f.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x00, 0x01))
        assertNull(BookFormat.detect(f))
    }

    @Test
    fun strongContent_overrulesWrongExtension() {
        // A DOCX misnamed .pdf must sniff as DOCX, not PDF.
        val f = tempFolder.newFile("book.pdf")
        java.util.zip.ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zos.write("<w:document/>".toByteArray())
            zos.closeEntry()
        }
        assertEquals(BookFormat.DOCX, BookFormat.detectStrongContent(f))
        // And a PDF magic header misnamed .epub must sniff as PDF.
        val g = tempFolder.newFile("book.epub")
        g.writeBytes("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        assertEquals(BookFormat.PDF, BookFormat.detectStrongContent(g))
        // Plain text has no strong signature: never overrules.
        val h = tempFolder.newFile("notes.md")
        h.writeText("just text\n", Charsets.UTF_8)
        assertNull(BookFormat.detectStrongContent(h))
    }

    @Test
    fun sniff_extensionlessWindows1256Persian() {
        val f = tempFolder.newFile("mystery")
        f.writeBytes("سلام دنیا".toByteArray(java.nio.charset.Charset.forName("windows-1256")))
        assertEquals(BookFormat.TXT, BookFormat.detect(f))
    }

    @Test
    fun sniff_binaryNotMistakenForWindows1256() {
        val f = tempFolder.newFile("mystery")
        // High bytes but no Arabic letters and many controls: binary.
        f.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0xC0.toByte(), 0x00, 0xFF.toByte(), 0x03))
        assertNull(BookFormat.detect(f))
    }
}
