package com.novelfa.reader.data

import java.io.File
import java.io.FileInputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The direct in-place route guard: an archive whose image entries
 * use a method SeekableZip cannot serve (bzip2, LZMA, zstd) lists
 * fine but can never open those pages — and Retry cannot help,
 * because the store is fixed at open time. The route must skip
 * the direct path for such archives and fall back to the working
 * copy instead. A bad method on a NON-image entry must not trip
 * the guard: the pages themselves still read fine.
 *
 * Runs on the JVM through SeekableZip.open(channel): the exercised
 * paths touch no Android APIs (the descriptor is null here).
 */
class SeekableZipGuardTest {

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
                out.putNextEntry(entry)
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun readShortLE(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)

    /** Rewrites the compression-method field of ONE named entry (local + central headers). */
    private fun patchMethodFor(file: File, entryName: String, method: Int) {
        val data = file.readBytes()
        val out = data.copyOf()
        fun setMethod(off: Int) {
            out[off] = (method and 0xFF).toByte()
            out[off + 1] = ((method ushr 8) and 0xFF).toByte()
        }
        // Local headers, walked in order.
        var p = 0
        while (p + 30 <= out.size) {
            if (out[p] != 0x50.toByte() || out[p + 1] != 0x4B.toByte() ||
                out[p + 2] != 0x03.toByte() || out[p + 3] != 0x04.toByte()
            ) break
            val nameLen = readShortLE(out, p + 26)
            val extraLen = readShortLE(out, p + 28)
            val compSize = (readShortLE(out, p + 18).toLong() and 0xFFFF) or
                ((readShortLE(out, p + 20).toLong() and 0xFFFF) shl 16)
            val name = String(out, p + 30, nameLen, Charsets.UTF_8)
            if (name == entryName) setMethod(p + 8)
            p += 30 + nameLen + extraLen + compSize.toInt()
        }
        // Central headers, scanned by signature.
        var i = 0
        while (i + 46 <= out.size) {
            if (out[i] == 0x50.toByte() && out[i + 1] == 0x4B.toByte() &&
                out[i + 2] == 0x01.toByte() && out[i + 3] == 0x02.toByte()
            ) {
                val nameLen = readShortLE(out, i + 28)
                val extraLen = readShortLE(out, i + 30)
                val commentLen = readShortLE(out, i + 32)
                val name = String(out, i + 46, nameLen, Charsets.UTF_8)
                if (name == entryName) setMethod(i + 10)
                i += 46 + nameLen + extraLen + commentLen
            } else {
                i++
            }
        }
        file.writeBytes(out)
    }

    /** Opens the archive and runs [block] with the channel still open (entry reads need it). */
    private fun withSeekable(file: File, block: (SeekableZip) -> Unit) {
        FileInputStream(file).use { fis ->
            val seekable = SeekableZip.open(fis.channel) ?: error("archive should parse")
            block(seekable)
        }
    }

    @Test
    fun deflatedArchive_hasNoUnservablePages() {
        val file = File.createTempFile("guard", ".cbz")
        try {
            writeZip(
                file,
                mapOf("001.jpg" to "page-one".toByteArray(), "002.jpg" to "page-two".toByteArray())
            )
            withSeekable(file) { seekable ->
                assertFalse(seekable.hasUnservableImageEntries())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun bzip2Page_tripsGuard() {
        val file = File.createTempFile("guard", ".cbz")
        try {
            writeZip(
                file,
                mapOf("001.jpg" to "page-one".toByteArray(), "002.jpg" to "page-two".toByteArray())
            )
            patchMethodFor(file, "002.jpg", 12)
            withSeekable(file) { seekable ->
                assertTrue(seekable.hasUnservableImageEntries())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun bzip2NonImageEntry_doesNotTripGuard() {
        val file = File.createTempFile("guard", ".cbz")
        try {
            writeZip(
                file,
                mapOf("001.jpg" to "page-one".toByteArray(), "notes.txt" to "notes".toByteArray())
            )
            patchMethodFor(file, "notes.txt", 12)
            withSeekable(file) { seekable ->
                assertFalse(seekable.hasUnservableImageEntries())
            }
        } finally {
            file.delete()
        }
    }
}
