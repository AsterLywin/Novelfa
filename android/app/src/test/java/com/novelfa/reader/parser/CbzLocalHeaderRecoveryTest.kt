package com.novelfa.reader.parser

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Local-header recovery (CbzBook): an archive whose tail is gone
 * (incomplete download — no EOCD, so the platform reader and the
 * central-directory scan both fail with "zip END header not
 * found") still imports with the pages that survived the cut.
 * A file that is not a ZIP at all still fails exactly as before.
 */
class CbzLocalHeaderRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pageBytes(i: Int): ByteArray {
        val n = 1500 + i * 300
        return ByteArray(n) { ((it * 31 + i * 7) and 0xFF).toByte() }
    }

    /** 5 pages; even = STORED, odd = DEFLATED, sizes in the headers (no descriptors). */
    private fun writeCbz(file: File, pages: Int = 5) {
        ZipOutputStream(file.outputStream()).use { zos ->
            for (i in 1..pages) {
                val data = pageBytes(i)
                val entry = ZipEntry("%03d.jpg".format(i))
                if (i % 2 == 0) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                } else {
                    entry.method = ZipEntry.DEFLATED
                    entry.size = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    /** 5 DEFLATED pages with no preset size/CRC: ZipOutputStream writes data descriptors. */
    private fun writeDescriptorCbz(file: File, pages: Int = 5) {
        ZipOutputStream(file.outputStream()).use { zos ->
            for (i in 1..pages) {
                zos.putNextEntry(ZipEntry("%03d.jpg".format(i)))
                zos.write(pageBytes(i))
                zos.closeEntry()
            }
        }
    }

    private fun truncate(file: File, keepBytes: Int): File {
        val bytes = file.readBytes()
        val cut = File(file.parentFile, file.nameWithoutExtension + "-cut.cbz")
        cut.writeBytes(bytes.copyOf(keepBytes))
        return cut
    }

    /** Offset of the last local-header signature (PK\x03\x04) in the file. */
    private fun lastLocalHeaderOffset(bytes: ByteArray): Int {
        var last = -1
        var i = 0
        while (i + 4 <= bytes.size) {
            val v = (bytes[i].toInt() and 0xFF) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                ((bytes[i + 3].toInt() and 0xFF) shl 24)
            if (v == 0x04034b50) last = i
            i++
        }
        return last
    }

    private fun localHeaderDataStart(bytes: ByteArray, headerOff: Int): Int {
        val nameLen = (bytes[headerOff + 26].toInt() and 0xFF) or
            ((bytes[headerOff + 27].toInt() and 0xFF) shl 8)
        val extraLen = (bytes[headerOff + 28].toInt() and 0xFF) or
            ((bytes[headerOff + 29].toInt() and 0xFF) shl 8)
        return headerOff + 30 + nameLen + extraLen
    }

    @Test
    fun truncatedEocd_recoversAllPagesAndBytes() {
        val full = tmp.newFile("full.cbz")
        writeCbz(full)
        val cut = truncate(full, full.readBytes().size - 22) // EOCD only
        val names = CbzBook.imageEntryNames(cut)
        assertEquals(
            listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg", "005.jpg"),
            names
        )
        assertTrue(CbzBook.readEntryBytes(cut, "003.jpg")!!.contentEquals(pageBytes(3)))
        assertTrue(CbzBook.readEntryBytes(cut, "002.jpg")!!.contentEquals(pageBytes(2)))
    }

    @Test
    fun truncatedHalfCentralDirectory_recoversAllPages() {
        val full = tmp.newFile("full.cbz")
        writeCbz(full)
        val cut = truncate(full, full.readBytes().size - 200) // EOCD + half the CD
        assertEquals(
            listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg", "005.jpg"),
            CbzBook.imageEntryNames(cut)
        )
    }

    @Test
    fun cutInsideLastPageData_recoversEarlierPagesOnly() {
        val full = tmp.newFile("full.cbz")
        writeCbz(full)
        val bytes = full.readBytes()
        val lastHeader = lastLocalHeaderOffset(bytes)
        val lastData = localHeaderDataStart(bytes, lastHeader)
        val cut = truncate(full, lastData + 100) // 100 bytes into the last page's data
        assertEquals(
            listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg"),
            CbzBook.imageEntryNames(cut)
        )
        assertTrue(CbzBook.readEntryBytes(cut, "004.jpg")!!.contentEquals(pageBytes(4)))
        assertNull(CbzBook.readEntryBytes(cut, "005.jpg"))
    }

    @Test
    fun cutInsideFirstPageData_throws() {
        val full = tmp.newFile("full.cbz")
        writeCbz(full)
        val bytes = full.readBytes()
        val firstData = localHeaderDataStart(bytes, 0)
        val cut = truncate(full, firstData + 100) // 100 bytes into the first page's data
        try {
            CbzBook.imageEntryNames(cut)
            fail("expected ZipException")
        } catch (e: java.util.zip.ZipException) {
            assertTrue(e.message!!.contains("zip END header not found"))
        }
    }

    @Test
    fun descriptorEntries_truncatedEocd_recoversAllPagesAndBytes() {
        val full = tmp.newFile("desc.cbz")
        writeDescriptorCbz(full)
        val cut = truncate(full, full.readBytes().size - 22) // EOCD only
        assertEquals(
            listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg", "005.jpg"),
            CbzBook.imageEntryNames(cut)
        )
        assertTrue(CbzBook.readEntryBytes(cut, "001.jpg")!!.contentEquals(pageBytes(1)))
        assertTrue(CbzBook.readEntryBytes(cut, "005.jpg")!!.contentEquals(pageBytes(5)))
    }

    @Test
    fun descriptorEntries_cutInsidePageData_recoversEarlierPagesOnly() {
        val full = tmp.newFile("desc.cbz")
        writeDescriptorCbz(full)
        val bytes = full.readBytes()
        val lastHeader = lastLocalHeaderOffset(bytes)
        val lastData = localHeaderDataStart(bytes, lastHeader)
        val cut = truncate(full, lastData + 100)
        assertEquals(
            listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg"),
            CbzBook.imageEntryNames(cut)
        )
    }

    @Test
    fun nonZipFile_stillThrows() {
        val fake = tmp.newFile("fake.cbz")
        fake.writeBytes("Rar!\u001a\u0007\u0000not a zip at all".toByteArray(Charsets.UTF_8))
        try {
            CbzBook.imageEntryNames(fake)
            fail("expected ZipException")
        } catch (e: java.util.zip.ZipException) {
            assertTrue(e.message!!.contains("zip END header not found"))
        }
    }

    @Test
    fun emptyFile_throws() {
        val empty = tmp.newFile("empty.cbz")
        empty.writeBytes(ByteArray(0))
        try {
            CbzBook.imageEntryNames(empty)
            fail("expected ZipException")
        } catch (e: java.util.zip.ZipException) {
            // The platform reader's own wording for a 0-byte file.
            assertTrue(e.message!!.contains("zip file is empty"))
        }
    }
}
