package com.novelfa.reader.parser.engine

import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.data.IMPORT_MODE_COPY
import com.novelfa.reader.data.IMPORT_MODE_IN_PLACE
import com.novelfa.reader.data.InPlaceSource
import com.novelfa.reader.data.isInPlace
import com.novelfa.reader.parser.BookFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the in-place import mode. The Android-bound
 * parts (resolve, permissions) are deliberately NOT covered here:
 * they need a Context and are verified by the three-level report
 * (compile + chain trace + device checks) instead.
 */
class InPlaceTest {

    private fun book(importMode: String, sourceUri: String?, filePath: String) =
        BookEntity(
            id = "b1", title = "T", filePath = filePath,
            totalChapters = 0, dateAdded = "",
            sourceUri = sourceUri, importMode = importMode
        )

    @Test
    fun `only a file-scheme in-place book is an external file`() {
        assertTrue(
            InPlaceSource.isExternalFile(
                book(IMPORT_MODE_IN_PLACE, "file:///sdcard/book.epub", "/sdcard/book.epub")
            )
        )
        // content:// in-place: filePath is a transient working copy,
        // app-owned — deleting the book may delete it.
        assertFalse(
            InPlaceSource.isExternalFile(
                book(IMPORT_MODE_IN_PLACE, "content://com.example/book", "/cache/inplace/b1/source.epub")
            )
        )
        // Copy books: filePath is always the stored copy.
        assertFalse(
            InPlaceSource.isExternalFile(
                book(IMPORT_MODE_COPY, null, "/files/books/b1.epub")
            )
        )
        // A copy book that happens to carry a file:// sourceUri
        // (never written by the importer) must not be treated as
        // external: the mode, not the scheme alone, decides.
        assertFalse(
            InPlaceSource.isExternalFile(
                book(IMPORT_MODE_COPY, "file:///sdcard/book.epub", "/files/books/b1.epub")
            )
        )
    }

    @Test
    fun `source scheme parsing is case-insensitive and null-safe`() {
        assertEquals("content", InPlaceSource.sourceScheme("content://x/y"))
        assertEquals("file", InPlaceSource.sourceScheme("FILE:///sdcard/x.epub"))
        assertNull(InPlaceSource.sourceScheme(null))
        assertNull(InPlaceSource.sourceScheme(""))
    }

    @Test
    fun `isInPlace follows the stored mode string`() {
        assertTrue(book(IMPORT_MODE_IN_PLACE, null, "/x").isInPlace())
        assertFalse(book(IMPORT_MODE_COPY, null, "/x").isInPlace())
        assertFalse(book("UNKNOWN", null, "/x").isInPlace())
    }

    @Test
    fun `working copy paths are stable per book`() {
        val cacheDir = File("/cache")
        assertEquals(
            File("/cache/inplace/b1/source.epub"),
            InPlaceSource.workingFile(cacheDir, "b1", "epub")
        )
        assertEquals(
            File("/cache/inplace/b1"),
            InPlaceSource.workingDir(cacheDir, "b1")
        )
    }

    @Test
    fun `meta text changes when size or mtime changes`() {
        assertEquals("100|200", InPlaceSource.metaText(100, 200))
        assertEquals("-1|-1", InPlaceSource.metaText(null, null))
        assertTrue(InPlaceSource.metaText(100, 200) != InPlaceSource.metaText(101, 200))
        assertTrue(InPlaceSource.metaText(100, 200) != InPlaceSource.metaText(100, 201))
    }

    @Test
    fun `sniff claims PDF by header regardless of name`() {
        val head = "%PDF-1.7".toByteArray()
        assertEquals(BookFormat.PDF, InPlaceSource.sniffFormat(head, head.size, "book.txt"))
    }

    @Test
    fun `sniff claims zip containers by extension`() {
        val head = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
        assertEquals(BookFormat.EPUB, InPlaceSource.sniffFormat(head, head.size, "book.epub"))
        assertEquals(BookFormat.DOCX, InPlaceSource.sniffFormat(head, head.size, "book.docx"))
        assertEquals(BookFormat.CBZ, InPlaceSource.sniffFormat(head, head.size, "book.cbz"))
        // Extension-less zip: a plain archive, classified at open.
        assertEquals(BookFormat.ZIP, InPlaceSource.sniffFormat(head, head.size, "archive"))
    }

    @Test
    fun `sniff claims plain text and rejects binary`() {
        val text = "This is a plain text book, chapter one.".toByteArray()
        assertEquals(BookFormat.TXT, InPlaceSource.sniffFormat(text, text.size, "notes"))
        val binary = byteArrayOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1)
        assertNull(InPlaceSource.sniffFormat(binary, binary.size, "notes"))
    }
}
