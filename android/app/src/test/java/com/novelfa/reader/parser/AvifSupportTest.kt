package com.novelfa.reader.parser

import com.novelfa.reader.reader.AvifDecoder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AVIF support, JVM-testable parts only: the ftyp brand check
 * (pure Kotlin) and the page-filter extension list (pure
 * java.util.zip). The native decode itself cannot run on the
 * JVM — no .so on the test classpath — so the contract tested
 * here is the graceful one: without the lib, decode calls
 * return null instead of throwing.
 */
class AvifSupportTest {

    private fun ftypBox(major: String, compatible: List<String>): ByteArray {
        val boxSize = 8 + 4 + compatible.size * 4
        val out = ByteArray(boxSize + 8)
        fun put32(off: Int, v: Int) {
            out[off] = (v ushr 24).toByte()
            out[off + 1] = (v ushr 16).toByte()
            out[off + 2] = (v ushr 8).toByte()
            out[off + 3] = v.toByte()
        }
        fun putStr(off: Int, s: String) {
            s.forEachIndexed { i, c -> out[off + i] = c.code.toByte() }
        }
        put32(0, boxSize)
        putStr(4, "ftyp")
        putStr(8, major)
        compatible.forEachIndexed { j, b -> putStr(12 + j * 4, b) }
        return out
    }

    @Test
    fun avifMajorBrandDetected() {
        assertTrue(AvifDecoder.isAvif(ftypBox("avif", emptyList())))
    }

    @Test
    fun avisMajorBrandDetected() {
        assertTrue(AvifDecoder.isAvif(ftypBox("avis", emptyList())))
    }

    @Test
    fun avifCompatibleBrandDetected() {
        assertTrue(AvifDecoder.isAvif(ftypBox("mif1", listOf("mif1", "avif"))))
    }

    @Test
    fun jpegIsNotAvif() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0
        )
        assertFalse(AvifDecoder.isAvif(jpeg))
    }

    @Test
    fun shortInputIsNotAvif() {
        assertFalse(AvifDecoder.isAvif(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun brandAfterBoxEndIsNotAvif() {
        // "avif" past the ftyp box size must not count: the
        // payload here is a fake second box, not a brand.
        val box = ftypBox("mif1", emptyList())
        val payload = "avif".toByteArray(Charsets.US_ASCII)
        payload.copyInto(box, 12)
        assertFalse(AvifDecoder.isAvif(box))
    }

    @Test
    fun avifEntriesArePages() {
        val zip = File.createTempFile("avif_test", ".zip")
        try {
            ZipOutputStream(zip.outputStream()).use { out ->
                for (name in listOf(
                    "a01.avif", "a02.AVIF", "a03.jpg", "a04.png",
                    "notes.txt"
                )) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(byteArrayOf(1))
                    out.closeEntry()
                }
            }
            assertEquals(
                listOf("a01.avif", "a02.AVIF", "a03.jpg", "a04.png"),
                CbzBook.imageEntryNames(zip)
            )
        } finally {
            zip.delete()
        }
    }

    @Test
    fun decodeReturnsNullWithoutNativeLib() {
        // No .so on the JVM test classpath: decodeBounds must
        // return null (graceful degradation), never throw
        // UnsatisfiedLinkError.
        assertFalse(AvifDecoder.isAvailable())
        assertNull(AvifDecoder.decodeBounds(ftypBox("avif", emptyList())))
        assertNull(AvifDecoder.decode(ftypBox("avif", emptyList())))
    }
}
