package com.novelfa.reader.library

import com.novelfa.reader.domain.Book
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The duplicate screen's size index: a picked file only needs
 * hashing against stored books of the same declared size. Without
 * it, every new file scanned the whole library and re-read
 * same-size files — O(n·m) full-file hash reads on a bulk import.
 */
class BooksBySizeTest {

    private fun book(id: String, file: File): Book = Book(
        id = id,
        title = id,
        filePath = file.absolutePath,
        totalChapters = 1,
        dateAdded = "2026-11-06"
    )

    private fun sizedFile(bytes: Int): File {
        val file = File.createTempFile("book", ".epub")
        file.writeBytes(ByteArray(bytes) { 0x41 })
        return file
    }

    @Test
    fun groupsByFileSize() {
        val a = sizedFile(100)
        val b = sizedFile(100)
        val c = sizedFile(200)
        try {
            val index = booksBySize(listOf(book("a", a), book("b", b), book("c", c)))
            assertEquals(2, index.size)
            assertEquals(listOf("a", "b"), index[100L]!!.map { it.id })
            assertEquals(listOf("c"), index[200L]!!.map { it.id })
        } finally {
            a.delete(); b.delete(); c.delete()
        }
    }

    @Test
    fun missingFileExcluded() {
        val present = sizedFile(100)
        val missing = File("/tmp/does-not-exist-novelfa-test.epub")
        try {
            val index = booksBySize(listOf(book("a", present), book("gone", missing)))
            assertEquals(1, index.size)
            assertEquals(listOf("a"), index[100L]!!.map { it.id })
        } finally {
            present.delete()
        }
    }

    @Test
    fun emptyLibraryEmptyIndex() {
        assertTrue(booksBySize(emptyList()).isEmpty())
    }

    @Test
    fun lookupMissesOnUnknownSize() {
        val a = sizedFile(100)
        try {
            val index = booksBySize(listOf(book("a", a)))
            assertTrue(index[999L].orEmpty().isEmpty())
        } finally {
            a.delete()
        }
    }
}
