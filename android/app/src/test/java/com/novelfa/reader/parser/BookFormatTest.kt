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
        assertNull(BookFormat.fromExtension("archive.zip"))
        assertNull(BookFormat.fromExtension("noext"))
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
    fun sniff_unknown_returnsNull() {
        val f = tempFolder.newFile("mystery")
        f.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x00, 0x01))
        assertNull(BookFormat.detect(f))
    }
}
