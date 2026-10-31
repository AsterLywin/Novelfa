package com.novelfa.reader.reader

import java.io.InputStream

/**
 * EXIF orientation for JPEG pages, without a dependency.
 *
 * ComicScreen reads EXIF orientation (its tile decoder honours
 * 0/90/180/270 and rotates the crop rect to match); Novelfa
 * ignored it, so a camera photo stored sideways in a CBZ showed
 * sideways. The platform ExifInterface would do this, but it is
 * not a dependency of the app, and a JPEG EXIF header is a small
 * format: SOI, then segments until the APP1 ("Exif") segment or
 * the scan data, then a TIFF header with one IFD0 entry (tag
 * 0x0112). This parses exactly that, nothing else — no XMP, no
 * MakerNote, no PNG/WebP EXIF (those are rare in comics and the
 * JPEG path is where a sideways photo comes from).
 *
 * Reading stops at the first APP1 segment, at SOS (scan data —
 * EXIF never follows it), or after [MAX_HEAD_BYTES] of head
 * bytes, so a pathological file cannot make this slurp the
 * whole entry. Anything malformed returns 0 (upright): a bad
 * EXIF header must never fail the page.
 */
internal object ExifRotation {

    private const val MAX_HEAD_BYTES = 65_536

    /** Degrees to rotate clockwise for display: 0, 90, 180 or 270. */
    fun degreesFromStream(stream: InputStream): Int {
        // An APP1 segment, if present, sits in the first segments
        // of the file; 64KB of head is far more than any real
        // header needs, and the scan below treats a truncated
        // tail as "no EXIF" rather than failing.
        val head = ByteArray(MAX_HEAD_BYTES)
        var filled = 0
        while (filled < MAX_HEAD_BYTES) {
            val read = stream.read(head, filled, MAX_HEAD_BYTES - filled)
            if (read <= 0) break
            filled += read
        }
        if (filled < 4) return 0
        return degreesFromHead(head, filled)
    }

    /** Segment scan over a JPEG head buffer (testable without IO). */
    fun degreesFromHead(head: ByteArray, length: Int): Int {
        if (length < 4) return 0
        if (head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return 0 // not JPEG
        var pos = 2
        while (pos + 4 <= length) {
            if (head[pos] != 0xFF.toByte()) return 0
            // Skip fill bytes (0xFF padding between segments).
            var markerPos = pos + 1
            while (markerPos < length && head[markerPos] == 0xFF.toByte()) markerPos++
            if (markerPos >= length) return 0
            val marker = head[markerPos].toInt() and 0xFF
            if (marker == 0xDA) return 0 // SOS: scan data, no EXIF after it
            if (marker == 0xD9) return 0 // EOI
            if (marker in 0xD0..0xD7 || marker == 0x01) {
                // Standalone markers (RSTn, TEM): no length field.
                pos = markerPos + 1
                continue
            }
            if (markerPos + 3 > length) return 0
            val segLen = ((head[markerPos + 1].toInt() and 0xFF) shl 8) or
                (head[markerPos + 2].toInt() and 0xFF)
            if (segLen < 2) return 0
            val dataStart = markerPos + 3
            val dataLen = segLen - 2
            if (dataStart + dataLen > length) return 0 // truncated head
            if (marker == 0xE1 && dataLen >= 6 &&
                head[dataStart] == 'E'.code.toByte() &&
                head[dataStart + 1] == 'x'.code.toByte() &&
                head[dataStart + 2] == 'i'.code.toByte() &&
                head[dataStart + 3] == 'f'.code.toByte() &&
                head[dataStart + 4] == 0.toByte() &&
                head[dataStart + 5] == 0.toByte()
            ) {
                return tiffOrientationDegrees(head, dataStart + 6, dataLen - 6)
            }
            pos = dataStart + dataLen
        }
        return 0
    }

    /**
     * TIFF header + IFD0 scan for tag 0x0112 (Orientation).
     * Both byte orders; type SHORT (3, count 1, value inline) and
     * LONG (4, count 1, value inline) — anything else is ignored.
     */
    private fun tiffOrientationDegrees(buf: ByteArray, off: Int, len: Int): Int {
        if (len < 8) return 0
        val little = when {
            buf[off] == 'I'.code.toByte() && buf[off + 1] == 'I'.code.toByte() -> true
            buf[off] == 'M'.code.toByte() && buf[off + 1] == 'M'.code.toByte() -> false
            else -> return 0
        }
        fun u16(at: Int): Int {
            if (at + 1 >= off + len) return -1
            val b0 = buf[at].toInt() and 0xFF
            val b1 = buf[at + 1].toInt() and 0xFF
            return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1
        }
        fun u32(at: Int): Long {
            if (at + 3 >= off + len) return -1
            val b = (0..3).map { buf[at + it].toInt() and 0xFF }
            return if (little) {
                b[0].toLong() or (b[1].toLong() shl 8) or (b[2].toLong() shl 16) or (b[3].toLong() shl 24)
            } else {
                (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
            }
        }
        if (u16(off + 2) != 42) return 0
        val ifdOff = u32(off + 4).toInt()
        if (ifdOff < 0 || off + ifdOff + 2 > off + len) return 0
        val count = u16(off + ifdOff)
        if (count < 0) return 0
        for (i in 0 until count) {
            val e = off + ifdOff + 2 + i * 12
            if (e + 12 > off + len) return 0
            if (u16(e) != 0x0112) continue
            val type = u16(e + 2)
            val cnt = u32(e + 4)
            if (cnt != 1L) continue
            val raw = when (type) {
                3 -> u16(e + 8)
                4 -> u32(e + 8).toInt()
                else -> continue
            }
            return when (raw) {
                3 -> 180
                6 -> 90
                8 -> 270
                else -> 0 // 1, 2, 4 (mirrors) have no pure rotation; upright
            }
        }
        return 0
    }
}
