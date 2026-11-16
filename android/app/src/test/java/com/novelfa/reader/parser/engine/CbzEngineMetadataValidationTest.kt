package com.novelfa.reader.parser.engine

import com.novelfa.reader.parser.CbzBook
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import-time page validation: a CBZ whose listing parses but
 * whose page data is cut or corrupt must fail the import loudly
 * instead of registering a book whose first open fails per page
 * ("Cannot open comic image: <name>"). An entry with an
 * unsupported method (bzip2/LZMA/zstd/AES) is a deliberate
 * per-page failure and must still import.
 */
class CbzEngineMetadataValidationTest {

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

    /** Corrupts the 4 signature bytes of the local header for [name]. */
    private fun corruptLocalHeader(file: File, name: String) {
        val data = file.readBytes()
        var i = 0
        while (true) {
            val sig = indexOf(data, byteArrayOf(0x50, 0x4B, 0x03, 0x04), i)
            if (sig < 0) throw IllegalStateException("local header for $name not found")
            val nameLen = (data[sig + 26].toInt() and 0xFF) or
                ((data[sig + 27].toInt() and 0xFF) shl 8)
            val found = String(data, sig + 30, nameLen, Charsets.UTF_8)
            if (found == name) {
                val out = data.copyOf()
                out[sig] = 0x00
                out[sig + 1] = 0x00
                out[sig + 2] = 0x00
                out[sig + 3] = 0x00
                file.writeBytes(out)
                return
            }
            i = sig + 4
        }
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun scratchDir(): File =
        File(System.getProperty("java.io.tmpdir"), "cbz-meta-test-${System.nanoTime()}")
            .apply { mkdirs() }

    @Test
    fun metadata_validArchive_succeeds() {
        val file = File.createTempFile("cbz-valid", ".cbz")
        writeZip(
            file,
            mapOf(
                "001.png" to "PAGE-ONE-BYTES".toByteArray(),
                "002.jpg" to "PAGE-TWO-BYTES".toByteArray(),
                "003.jpg" to "PAGE-THREE-BYTES".toByteArray()
            )
        )
        val meta = CbzEngine.metadata(file, "test.cbz", scratchDir()) {}
        assertEquals(3, meta.chapterCount)
    }

    @Test
    fun metadata_corruptPageData_failsImport() {
        val file = File.createTempFile("cbz-corrupt", ".cbz")
        writeZip(
            file,
            mapOf(
                "001.png" to "PAGE-ONE-BYTES".toByteArray(),
                "002.jpg" to "PAGE-TWO-BYTES".toByteArray(),
                "003.jpg" to "PAGE-THREE-BYTES".toByteArray()
            )
        )
        // The listing still parses (central directory intact), but
        // 002.jpg's local header is destroyed, so its data cannot
        // be opened — the exact shape of the reported failure.
        corruptLocalHeader(file, "002.jpg")
        assertTrue(CbzBook.imageEntryNames(file).contains("002.jpg"))
        assertThrows(IllegalArgumentException::class.java) {
            CbzEngine.metadata(file, "test.cbz", scratchDir()) {}
        }
    }

    @Test
    fun metadata_unsupportedMethodPage_stillImports() {
        val file = File.createTempFile("cbz-bzip2", ".cbz")
        writeZip(
            file,
            mapOf(
                "001.png" to "PAGE-ONE-BYTES".toByteArray(),
                "002.jpg" to "PAGE-TWO-BYTES".toByteArray()
            )
        )
        // Patch 002.jpg's method to bzip2 (12) in local + central
        // headers: the book must still import (per-page failure
        // only), because the method is unsupported, not corrupt.
        val data = file.readBytes()
        val out = data.copyOf()
        fun patch(sig: ByteArray, methodOff: Int) {
            var i = 0
            while (true) {
                val j = indexOf(out, sig, i)
                if (j < 0) break
                // Only patch the entry whose name follows this header.
                // Local header: name length at +26, name at +30.
                // Central header: name length at +28, name at +46.
                val isLocal = sig[2] == 0x03.toByte()
                val nameLenOff = if (isLocal) 26 else 28
                val nameStart = if (isLocal) 30 else 46
                val nameLen = (out[j + nameLenOff].toInt() and 0xFF) or
                    ((out[j + nameLenOff + 1].toInt() and 0xFF) shl 8)
                val name = String(out, j + nameStart, nameLen, Charsets.UTF_8)
                if (name == "002.jpg") {
                    out[j + methodOff] = 12
                    out[j + methodOff + 1] = 0
                }
                i = j + sig.size
            }
        }
        patch(byteArrayOf(0x50, 0x4B, 0x03, 0x04), 8)
        patch(byteArrayOf(0x50, 0x4B, 0x01, 0x02), 10)
        file.writeBytes(out)
        val meta = CbzEngine.metadata(file, "test.cbz", scratchDir()) {}
        assertEquals(2, meta.chapterCount)
    }
}
