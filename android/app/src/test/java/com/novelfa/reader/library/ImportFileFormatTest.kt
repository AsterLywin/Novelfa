package com.novelfa.reader.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM tests for the import dialog's per-file format label. */
class ImportFileFormatTest {

    @Test
    fun knownExtensionsMapToTheirFormats() {
        assertEquals(ImportFileFormat.EPUB, importFormatOfFileName("book.epub"))
        assertEquals(ImportFileFormat.PDF, importFormatOfFileName("book.pdf"))
        assertEquals(ImportFileFormat.TXT, importFormatOfFileName("notes.txt"))
        assertEquals(ImportFileFormat.MARKDOWN, importFormatOfFileName("doc.md"))
        assertEquals(ImportFileFormat.MARKDOWN, importFormatOfFileName("doc.markdown"))
        assertEquals(ImportFileFormat.WORD, importFormatOfFileName("doc.docx"))
        assertEquals(ImportFileFormat.CBZ, importFormatOfFileName("comic.cbz"))
        assertEquals(ImportFileFormat.ZIP, importFormatOfFileName("comic.zip"))
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        assertEquals(ImportFileFormat.PDF, importFormatOfFileName("BOOK.PDF"))
        assertEquals(ImportFileFormat.EPUB, importFormatOfFileName("Book.Epub"))
        assertEquals(ImportFileFormat.CBZ, importFormatOfFileName("comic.CBZ"))
    }

    @Test
    fun onlyTheLastExtensionCounts() {
        assertEquals(ImportFileFormat.EPUB, importFormatOfFileName("my.book.v2.epub"))
        assertEquals(ImportFileFormat.UNKNOWN, importFormatOfFileName("book.pdf.zip.bak"))
    }

    @Test
    fun missingOrUnknownExtensionsAreUnknownNeverGuessed() {
        assertEquals(ImportFileFormat.UNKNOWN, importFormatOfFileName("book"))
        assertEquals(ImportFileFormat.UNKNOWN, importFormatOfFileName("book."))
        assertEquals(ImportFileFormat.UNKNOWN, importFormatOfFileName("book.rar"))
        assertEquals(ImportFileFormat.UNKNOWN, importFormatOfFileName(null))
        assertEquals(ImportFileFormat.UNKNOWN, importFormatOfFileName(""))
    }
}
