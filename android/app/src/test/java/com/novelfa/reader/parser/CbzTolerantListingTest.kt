package com.novelfa.reader.parser

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tolerant CBZ listing: one entry with an unusual compression
 * method (bzip2, LZMA, zstd, AES) must not fail the whole book.
 * The platform reader ([java.util.zip.ZipFile]) rejects the
 * entire archive at construction for such an entry ("invalid
 * CEN header (bad compression method)"), so [CbzBook] falls back
 * to a direct central-directory scan and only the one entry
 * fails later, per page. The method bytes are patched after
 * writing (java.util.zip cannot write bzip2/LZMA/zstd/AES) —
 * the listing only reads headers, never the entry data, so the
 * patched method is exactly what the platform reader sees.
 */
class CbzTolerantListingTest {

    private fun writeZip(file: File, entries: Map<String, ByteArray>, stored: Boolean = true) {
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                out.putNextEntry(entry)
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    /** Rewrites every entry's compression-method field (local + central headers). */
    private fun patchAllMethods(file: File, method: Int) {
        val data = file.readBytes()
        val out = data.copyOf()
        fun patch(sig: ByteArray, methodOff: Int) {
            var i = 0
            while (true) {
                val j = indexOf(out, sig, i)
                if (j < 0) break
                out[j + methodOff] = (method and 0xFF).toByte()
                out[j + methodOff + 1] = ((method ushr 8) and 0xFF).toByte()
                i = j + sig.size
            }
        }
        patch(byteArrayOf(0x50, 0x4B, 0x03, 0x04), 8)
        patch(byteArrayOf(0x50, 0x4B, 0x01, 0x02), 10)
        file.writeBytes(out)
    }

    private fun indexOf(data: ByteArray, sig: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - sig.size) {
            for (k in sig.indices) {
                if (data[i + k] != sig[k]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun poisonedArchive(method: Int): File {
        val file = File.createTempFile("poisoned", ".cbz")
        writeZip(
            file,
            mapOf("001.jpg" to "page-one-bytes".toByteArray(), "002.jpg" to "page-two-bytes".toByteArray())
        )
        patchAllMethods(file, method)
        return file
    }

    @Test
    fun listingSurvivesBzip2Method() {
        val file = poisonedArchive(12)
        try {
            assertEquals(listOf("001.jpg", "002.jpg"), CbzBook.imageEntryNames(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun listingSurvivesLzmaMethod() {
        val file = poisonedArchive(14)
        try {
            assertEquals(listOf("001.jpg", "002.jpg"), CbzBook.imageEntryNames(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun listingSurvivesZstdMethod() {
        val file = poisonedArchive(93)
        try {
            assertEquals(listOf("001.jpg", "002.jpg"), CbzBook.imageEntryNames(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun listingSurvivesAesMethod() {
        val file = poisonedArchive(99)
        try {
            assertEquals(listOf("001.jpg", "002.jpg"), CbzBook.imageEntryNames(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun allEntryNamesListsNonImagesToo() {
        val file = File.createTempFile("poisoned", ".cbz")
        try {
            writeZip(
                file,
                mapOf(
                    "001.jpg" to "page-one-bytes".toByteArray(),
                    "notes.txt" to "some-notes".toByteArray()
                )
            )
            patchAllMethods(file, 12)
            assertEquals(listOf("001.jpg", "notes.txt"), CbzBook.allEntryNames(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readEntryBytesDeflatedRoundTrip() {
        val file = File.createTempFile("roundtrip", ".cbz")
        try {
            val payload = "hello-page".toByteArray()
            writeZip(file, mapOf("001.jpg" to payload), stored = false)
            val read = CbzBook.readEntryBytes(file, "001.jpg")
            assertTrue(read.contentEquals(payload))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readEntryBytesStoredRoundTrip() {
        val file = File.createTempFile("roundtrip", ".cbz")
        try {
            val payload = "hello-page".toByteArray()
            writeZip(file, mapOf("001.jpg" to payload), stored = true)
            val read = CbzBook.readEntryBytes(file, "001.jpg")
            assertTrue(read.contentEquals(payload))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readEntryBytesBzip2EntryReturnsNull() {
        val file = poisonedArchive(12)
        try {
            assertNull(CbzBook.readEntryBytes(file, "001.jpg"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readEntryBytesMissingEntryReturnsNull() {
        val file = File.createTempFile("roundtrip", ".cbz")
        try {
            writeZip(file, mapOf("001.jpg" to "hello-page".toByteArray()))
            assertNull(CbzBook.readEntryBytes(file, "999.jpg"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun corruptFileThrows() {
        val file = File.createTempFile("corrupt", ".cbz")
        try {
            file.writeBytes("this is not a zip file at all".toByteArray())
            try {
                CbzBook.imageEntryNames(file)
                fail("expected an exception for a non-ZIP file")
            } catch (e: Exception) {
                // The importer's generic catch turns this into the
                // localized bad-structure error, as before.
                assertTrue(e.message?.isNotEmpty() == true)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun emptyArchiveListsNoImages() {
        val file = File.createTempFile("empty", ".cbz")
        try {
            ZipOutputStream(file.outputStream()).use { }
            // The platform reader lists an empty archive fine
            // (no entries); the importer then reports it as
            // unreadable because it holds no images.
            assertTrue(CbzBook.imageEntryNames(file).isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun tiffOnlyListsNoImages() {
        val file = File.createTempFile("tiff", ".cbz")
        try {
            writeZip(
                file,
                mapOf("001.tif" to "tiff-page".toByteArray(), "002.tiff" to "tiff-page".toByteArray())
            )
            // TIFF is not a page in Novelfa (no TIFF decoder):
            // the archive lists fine but holds no images, so the
            // importer reports it as unreadable.
            assertTrue(CbzBook.imageEntryNames(file).isEmpty())
            assertEquals(listOf("001.tif", "002.tiff"), CbzBook.allEntryNames(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun chaptersWorkOnPoisonedArchive() {
        val file = File.createTempFile("poisoned", ".cbz")
        try {
            writeZip(
                file,
                mapOf(
                    "Ch1/001.jpg" to "page-one-bytes".toByteArray(),
                    "Ch2/001.jpg" to "page-two-bytes".toByteArray()
                )
            )
            patchAllMethods(file, 12)
            val chapters = CbzBook.chapters(file)
            assertEquals(2, chapters.size)
            assertEquals("Ch1", chapters[0].title)
            assertEquals(0, chapters[0].firstPageIndex)
            assertEquals("Ch2", chapters[1].title)
            assertEquals(1, chapters[1].firstPageIndex)
        } finally {
            file.delete()
        }
    }
}
