package com.novelfa.reader.reader

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EXIF orientation parsing on crafted JPEG heads: no real photos
 * needed — the parser only ever sees SOI + segments + one TIFF
 * header, so synthetic bytes cover every branch.
 */
class ExifRotationTest {

    private fun jpegWithExif(littleEndian: Boolean, orientation: Int): ByteArray {
        val tiff = mutableListOf<Byte>()
        fun u16(v: Int) {
            if (littleEndian) {
                tiff += (v and 0xFF).toByte()
                tiff += ((v shr 8) and 0xFF).toByte()
            } else {
                tiff += ((v shr 8) and 0xFF).toByte()
                tiff += (v and 0xFF).toByte()
            }
        }
        fun u32(v: Long) {
            if (littleEndian) {
                for (s in 0..24 step 8) tiff += ((v shr s) and 0xFF).toByte()
            } else {
                for (s in 24 downTo 0 step 8) tiff += ((v shr s) and 0xFF).toByte()
            }
        }
        tiff += if (littleEndian) 'I'.code.toByte() else 'M'.code.toByte()
        tiff += if (littleEndian) 'I'.code.toByte() else 'M'.code.toByte()
        u16(42)
        u32(8)
        u16(1) // one IFD0 entry
        u16(0x0112) // Orientation tag
        u16(3) // SHORT
        u32(1) // count 1
        if (littleEndian) {
            tiff += (orientation and 0xFF).toByte()
            tiff += ((orientation shr 8) and 0xFF).toByte()
            tiff += 0
            tiff += 0
        } else {
            tiff += ((orientation shr 8) and 0xFF).toByte()
            tiff += (orientation and 0xFF).toByte()
            tiff += 0
            tiff += 0
        }
        u32(0) // next IFD: none
        val app1Data = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff.toByteArray()
        val out = mutableListOf<Byte>()
        out += 0xFF.toByte()
        out += 0xD8.toByte() // SOI
        out += 0xFF.toByte()
        out += 0xE1.toByte() // APP1
        val segLen = app1Data.size + 2
        out += ((segLen shr 8) and 0xFF).toByte()
        out += (segLen and 0xFF).toByte()
        out += app1Data.toList()
        out += 0xFF.toByte()
        out += 0xDA.toByte() // SOS: scan data follows, parser must stop
        out += 0x11.toByte()
        return out.toByteArray()
    }

    @Test
    fun littleEndianOrientation6Is90() {
        val bytes = jpegWithExif(littleEndian = true, orientation = 6)
        assertEquals(90, ExifRotation.degreesFromHead(bytes, bytes.size))
        assertEquals(90, ExifRotation.degreesFromStream(ByteArrayInputStream(bytes)))
    }

    @Test
    fun bigEndianOrientation3Is180() {
        val bytes = jpegWithExif(littleEndian = false, orientation = 3)
        assertEquals(180, ExifRotation.degreesFromHead(bytes, bytes.size))
    }

    @Test
    fun orientation8Is270() {
        val bytes = jpegWithExif(littleEndian = true, orientation = 8)
        assertEquals(270, ExifRotation.degreesFromHead(bytes, bytes.size))
    }

    @Test
    fun orientation1IsUpright() {
        val bytes = jpegWithExif(littleEndian = true, orientation = 1)
        assertEquals(0, ExifRotation.degreesFromHead(bytes, bytes.size))
    }

    @Test
    fun jpegWithoutExifIsUpright() {
        // SOI + APP0 (JFIF) + SOS, no APP1 at all.
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xDA.toByte(), 0x11
        )
        assertEquals(0, ExifRotation.degreesFromHead(bytes, bytes.size))
    }

    @Test
    fun nonJpegAndTruncatedAreUpright() {
        assertEquals(0, ExifRotation.degreesFromHead(byteArrayOf(1, 2, 3, 4), 4))
        val bytes = jpegWithExif(littleEndian = true, orientation = 6)
        // Cut inside the APP1 segment: the parser must not crash
        // and must not claim a rotation from a partial header.
        assertEquals(0, ExifRotation.degreesFromHead(bytes, 12))
    }
}
