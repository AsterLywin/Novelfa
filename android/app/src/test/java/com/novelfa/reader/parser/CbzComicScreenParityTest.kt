package com.novelfa.reader.parser

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipException
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ComicScreen parity: the user reports ComicScreen renders a file
 * Novelfa rejects with "zip END header not found". ComicScreen's
 * reader (zi0, a zip4j-like hand reader) differs from Novelfa's in
 * two ways this file proves:
 *  - it finds the EOCD by a backward signature scan (<=3000 bytes)
 *    WITHOUT validating that the EOCD sits exactly at EOF, and it
 *    reads the central directory SEQUENTIALLY from cdOffset,
 *    IGNORING cdSize entirely;
 *  - it never walks local headers at all.
 * Novelfa's readers, by contrast, fail the file when the EOCD's
 * cdSize is wrong (the CD scan requires cdOffset+cdSize <= size
 * and reads exactly cdSize bytes) and when the local walk cannot
 * find a data descriptor (it only accepts the SIGNED PK\x07\x08
 * form — some writers emit the descriptor WITHOUT its signature).
 *
 * These tests build such archives byte by byte and pin the shape
 * that ComicScreen opens while Novelfa rejects, before the fix.
 */
class CbzComicScreenParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private enum class DescriptorStyle { NONE, SIGNED, UNSIGNED }

    private fun pageBytes(i: Int): ByteArray {
        val n = 1500 + i * 300
        return ByteArray(n) { ((it * 31 + i * 7) and 0xFF).toByte() }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        def.setInput(data)
        def.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!def.finished()) {
            val n = def.deflate(buf)
            out.write(buf, 0, n)
        }
        def.end()
        return out.toByteArray()
    }

    private class LE(val out: ByteArrayOutputStream) {
        fun u16(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        }

        fun u32(v: Long) {
            for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        fun bytes(b: ByteArray) = out.write(b)
    }

    /**
     * A CBZ written byte by byte, so the writer's quirks are under
     * test control: [descriptorStyle] for the local entries,
     * [cdSizeOverride] to corrupt the EOCD's cdSize field,
     * [trailingJunk] bytes appended after the EOCD.
     */
    private fun buildCbz(
        file: File,
        pages: Int = 5,
        descriptorStyle: DescriptorStyle = DescriptorStyle.NONE,
        cdSizeOverride: Long? = null,
        trailingJunk: Int = 0
    ) {
        val out = ByteArrayOutputStream()
        val le = LE(out)
        data class Ent(val name: String, val method: Int, val flags: Int, val crc: Long, val comp: Long, val uncomp: Long, val localOff: Long)
        val ents = ArrayList<Ent>()
        for (i in 1..pages) {
            val raw = pageBytes(i)
            val data = if (i % 2 == 0) raw else deflate(raw)
            val method = if (i % 2 == 0) 0 else 8
            val crc = CRC32().apply { update(raw) }.value
            val flags = if (descriptorStyle == DescriptorStyle.NONE) 0 else 0x08
            val name = "%03d.jpg".format(i).toByteArray(Charsets.UTF_8)
            val localOff = out.size().toLong()
            le.u32(0x04034b50L)
            le.u16(20)
            le.u16(flags)
            le.u16(method)
            le.u16(0)
            le.u16(0)
            le.u32(if (descriptorStyle == DescriptorStyle.NONE) crc else 0L)
            le.u32(if (descriptorStyle == DescriptorStyle.NONE) data.size.toLong() else 0L)
            le.u32(if (descriptorStyle == DescriptorStyle.NONE) raw.size.toLong() else 0L)
            le.u16(name.size)
            le.u16(0)
            le.bytes(name)
            le.bytes(data)
            if (descriptorStyle == DescriptorStyle.SIGNED) {
                le.u32(0x08074b50L)
                le.u32(crc)
                le.u32(data.size.toLong())
                le.u32(raw.size.toLong())
            } else if (descriptorStyle == DescriptorStyle.UNSIGNED) {
                le.u32(crc)
                le.u32(data.size.toLong())
                le.u32(raw.size.toLong())
            }
            ents.add(Ent("%03d.jpg".format(i), method, flags, crc, data.size.toLong(), raw.size.toLong(), localOff))
        }
        val cdOffset = out.size().toLong()
        var cdSize = 0L
        for (e in ents) {
            val name = e.name.toByteArray(Charsets.UTF_8)
            val start = out.size()
            le.u32(0x02014b50L)
            le.u16(20)
            le.u16(20)
            le.u16(e.flags)
            le.u16(e.method)
            le.u16(0)
            le.u16(0)
            le.u32(e.crc)
            le.u32(e.comp)
            le.u32(e.uncomp)
            le.u16(name.size)
            le.u16(0)
            le.u16(0)
            le.u16(0)
            le.u16(0)
            le.u32(0)
            le.u32(e.localOff)
            le.bytes(name)
            cdSize += out.size() - start
        }
        le.u32(0x06054b50L)
        le.u16(0)
        le.u16(0)
        le.u16(ents.size)
        le.u16(ents.size)
        le.u32(cdSizeOverride ?: cdSize)
        le.u32(cdOffset)
        le.u16(0)
        if (trailingJunk > 0) {
            le.bytes(ByteArray(trailingJunk) { 0x5A })
        }
        file.writeBytes(out.toByteArray())
    }

    /**
     * A zi0-faithful "would ComicScreen open this" check: EOCD by a
     * backward signature scan (<=3000 bytes, no position validation),
     * then the central directory read SEQUENTIALLY from cdOffset for
     * `count` entries, each starting with PK\x01\x02. cdSize is never
     * consulted, exactly like zi0.
     */
    private fun comicScreenWouldOpen(file: File): Boolean {
        val bytes = file.readBytes()
        val size = bytes.size
        fun i32(off: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = v or ((bytes[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }
        fun i16(off: Int): Int =
            (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
        var eocd = -1
        var p = size - 22
        var steps = 0
        while (p >= 0 && steps <= 3000) {
            if (i32(p) == 0x06054b50L) {
                eocd = p
                break
            }
            p--
            steps++
        }
        if (eocd < 0) return false
        val count = i16(eocd + 10)
        val cdOffset = i32(eocd + 16).toInt()
        var pos = cdOffset
        repeat(count) {
            if (pos + 46 > size) return false
            if (i32(pos) != 0x02014b50L) return false
            val nameLen = i16(pos + 28)
            val extraLen = i16(pos + 30)
            val commentLen = i16(pos + 32)
            pos += 46 + nameLen + extraLen + commentLen
        }
        return count > 0
    }

    private fun zipFileOpens(file: File): String? {
        return try {
            ZipFile(file).use { it.size() }
            null
        } catch (e: ZipException) {
            e.message
        }
    }

    private fun novelfaLists(file: File): List<String>? {
        return try {
            CbzBook.imageEntryNames(file)
        } catch (e: Exception) {
            null
        }
    }

    @Test
    fun shape_unsignedDescriptors_trailingJunk_wrongCdSize() {
        // All three quirks at once: the shape the user's file is
        // believed to have. ComicScreen opens it; Novelfa must not
        // throw "zip END header not found" on it anymore.
        val f = tmp.newFile("shape.cbz")
        buildCbz(
            f,
            descriptorStyle = DescriptorStyle.UNSIGNED,
            cdSizeOverride = 1_000_000L,
            trailingJunk = 500
        )
        assertTrue("ComicScreen (zi0) must open this shape", comicScreenWouldOpen(f))
        // ZipFile fails: the trailing junk breaks its exact-end EOCD validation.
        assertEquals("zip END header not found", zipFileOpens(f))
        val names = novelfaLists(f)
        assertEquals(
            listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg", "005.jpg"),
            names
        )
        assertTrue(CbzBook.readEntryBytes(f, "003.jpg")!!.contentEquals(pageBytes(3)))
    }

    @Test
    fun shape_unsignedDescriptors_wrongCdSize_noJunk() {
        // The minimal repro: unsigned descriptors + a wrong cdSize,
        // no trailing junk. ComicScreen opens it; Novelfa must list it.
        val f = tmp.newFile("minimal.cbz")
        buildCbz(
            f,
            descriptorStyle = DescriptorStyle.UNSIGNED,
            cdSizeOverride = 1_000_000L
        )
        assertTrue("ComicScreen (zi0) must open this shape", comicScreenWouldOpen(f))
        val names = novelfaLists(f)
        assertEquals(
            listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg", "005.jpg"),
            names
        )
        assertTrue(CbzBook.readEntryBytes(f, "004.jpg")!!.contentEquals(pageBytes(4)))
    }

    @Test
    fun shape_truncatedUnsignedDescriptors_recoveredByLocalWalk() {
        // The same unsigned-descriptor file, cut before the central
        // directory: no EOCD and no CD at all, so ONLY the local
        // walk can recover the pages — and it must, by reading the
        // unsigned descriptors.
        val f = tmp.newFile("trunc-unsigned.cbz")
        buildCbz(f, descriptorStyle = DescriptorStyle.UNSIGNED)
        val bytes = f.readBytes()
        val eocd = bytes.size - 22
        var cdOffset = 0L
        for (i in 0 until 4) {
            cdOffset = cdOffset or ((bytes[eocd + 16 + i].toLong() and 0xFF) shl (8 * i))
        }
        f.writeBytes(bytes.copyOfRange(0, cdOffset.toInt()))
        assertEquals(
            listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg", "005.jpg"),
            novelfaLists(f)
        )
        assertTrue(CbzBook.readEntryBytes(f, "005.jpg")!!.contentEquals(pageBytes(5)))
    }

    @Test
    fun shape_unsignedDescriptors_only() {
        // Unsigned descriptors alone (intact EOCD): the platform
        // reader opens it via the CD, so Novelfa must list it.
        val f = tmp.newFile("unsigned.cbz")
        buildCbz(f, descriptorStyle = DescriptorStyle.UNSIGNED)
        assertTrue(comicScreenWouldOpen(f))
        assertEquals(null, zipFileOpens(f))
        assertEquals(
            listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg", "005.jpg"),
            novelfaLists(f)
        )
    }

    @Test
    fun shape_wrongCdSize_only() {
        // A wrong cdSize alone (intact EOCD, normal descriptors):
        // ComicScreen ignores cdSize; Novelfa's CD scan must too.
        // The local-header walk is the backstop, so this must list.
        val f = tmp.newFile("cdsize.cbz")
        buildCbz(f, cdSizeOverride = 1_000_000L)
        assertTrue(comicScreenWouldOpen(f))
        assertEquals(
            listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg", "005.jpg"),
            novelfaLists(f)
        )
    }

    @Test
    fun shape_trailingJunk_only() {
        // Trailing junk after the EOCD alone: the platform reader
        // tolerates it (it still finds the EOCD), so this must list.
        val f = tmp.newFile("junk.cbz")
        buildCbz(f, trailingJunk = 500)
        assertTrue(comicScreenWouldOpen(f))
        assertEquals(null, zipFileOpens(f))
        assertEquals(
            listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg", "005.jpg"),
            novelfaLists(f)
        )
    }
}
