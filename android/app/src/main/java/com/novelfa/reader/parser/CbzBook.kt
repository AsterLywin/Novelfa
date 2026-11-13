package com.novelfa.reader.parser

import com.novelfa.reader.data.DiagLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipFile

/**
 * CBZ / plain-ZIP image books (manhwa, manga, comics) — the
 * ComicScreen model, implemented from the open CBZ format, not from
 * any other reader's code: a CBZ is just a ZIP whose entries are
 * page images, in reading order.
 *
 * Rules, shared by the importer (validation + page count) and the
 * native viewer (on-demand decode):
 * - Only image entries count: jpg/jpeg, png, webp, gif, bmp,
 *   avif. Anything else (ComicInfo.xml, covers in other
 *   formats, text files) is ignored, never a page. AVIF joined
 *   the list with the native libavif engine: without it an
 *   .avif entry would be listed but fail every decode, and a
 *   dropped page beats a failed page.
 * - TIFF is still NOT a page: there is no platform decoder and
 *   no native TIFF engine in the app (same reason AVIF waited
 *   for its engine).
 * - Directories, macOS `__MACOSX/` junk and dot-files are ignored.
 * - Order is NATURAL, not lexicographic: `page-2.jpg` before
 *   `page-10.jpg`, and folder paths sort before their contents, so
 *   a zip with chapter folders still reads in order when flattened.
 * - Chapter folders ARE split into sections ([chaptersForEntries]):
 *   each folder (after stripping a path every folder shares, e.g.
 *   the series folder) is one chapter starting at its first page,
 *   and the native viewer offers that list as a one-tap jump. A
 *   flat archive (no folders) has no chapters and reads exactly
 *   as before; root-level pages belong to no chapter.
 * - Nothing is extracted at import: the original archive is stored
 *   as-is and pages are decoded on demand at open time, exactly
 *   like the native PDF viewer. A 500-page manhwa therefore imports
 *   in seconds and costs no extracted-JPEG storage.
 * - CBR (RAR) is served by the same rules through the native
 *   unrar bridge ([RarNative], [RarEngine]): a RAR is a CBZ
 *   with a different container, so its page list, natural order
 *   and chapter split all come from this object, and only the
 *   entry bytes come from JNI instead of the ZIP reader.
 */
object CbzBook {

    private val IMAGE_EXTENSIONS =
        setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

    fun isImageEntry(name: String): Boolean {
        if (name.endsWith('/')) return false
        val base = name.substringAfterLast('/')
        if (base.startsWith('.')) return false
        if (name.startsWith("__MACOSX/")) return false
        val ext = base.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    /**
     * The file's real kind from its first bytes — the decisive
     * fact when the archive will not list at all. A RAR, 7z or
     * PDF wearing a .cbz name fails every ZIP reader with
     * "zip END header not found", exactly like a truncated ZIP —
     * but ComicScreen opens RARs natively, 7z through Commons
     * Compress, and PDFs through its own engine, so it renders
     * the same file easily. Without this check the user is told
     * to re-download a file that is not broken, only misnamed.
     * Returns "ZIP" for a real ZIP (any "PK" header signature),
     * or the detected type ("RAR", "7Z", "PDF", "GZIP", "BZIP2",
     * "UNKNOWN").
     */
    fun archiveKind(zipFile: File): String {
        return try {
            FileInputStream(zipFile).use { fis ->
                val head = ByteArray(8)
                var read = 0
                while (read < head.size) {
                    val n = fis.read(head, read, head.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                    "ZIP"
                } else if (read >= 4 && head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() &&
                    head[2] == 'r'.code.toByte() && head[3] == '!'.code.toByte()
                ) {
                    "RAR"
                } else if (read >= 6 && head[0] == '7'.code.toByte() && head[1] == 'z'.code.toByte() &&
                    head[2] == 0xBC.toByte() && head[3] == 0xAF.toByte() &&
                    head[4] == 0x27.toByte() && head[5] == 0x1C.toByte()
                ) {
                    "7Z"
                } else if (read >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
                    head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()
                ) {
                    "PDF"
                } else if (read >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) {
                    "GZIP"
                } else if (read >= 3 && head[0] == 'B'.code.toByte() && head[1] == 'Z'.code.toByte() &&
                    head[2] == 'h'.code.toByte()
                ) {
                    "BZIP2"
                } else {
                    "UNKNOWN"
                }
            }
        } catch (e: Exception) {
            DiagLog.w("NovelfaCbzBook", "archiveKind probe failed, reporting UNKNOWN", e)
            "UNKNOWN"
        }
    }

    /**
     * Sorted image entry names for [zipFile]. Throws on a corrupt
     * or non-ZIP file (the importer's generic catch turns that into
     * a localized import error); returns an empty list only when
     * the archive is valid but holds no images.
     *
     * The listing is tolerant: the platform reader ([ZipFile])
     * rejects a whole archive when a single entry uses an unusual
     * compression method (bzip2, LZMA, zstd, AES — "invalid CEN
     * header (bad compression method)"), even when that entry is
     * not a page at all. ComicScreen's reader does not validate
     * the method while listing, so such books open there and only
     * the one page fails. When the platform reader refuses the
     * archive, the central directory is scanned directly and
     * only the truly unreadable entries fail later, one page at
     * a time ([openEntryStream], [readEntryBytes]).
     *
     * Nested archives ([NestedArchive]): when the archive holds
     * `.zip`/`.cbz` entries, their images are listed too, under
     * virtual paths (`ch01.zip/ch01/001.jpg`), in the same
     * natural order — a chapter-per-ZIP pack reads as one flat
     * book. [nestedCacheDir] is where nested archives are
     * materialized; null uses the shared nested cache.
     */
    fun imageEntryNames(
        zipFile: File,
        nestedCacheDir: File? = null
    ): List<String> {
        val top = allEntryNames(zipFile)
        if (top.none { NestedArchive.isArchiveName(it) }) {
            return top.filter { isImageEntry(it) }.sortedWith(::compareNatural)
        }
        return NestedArchive.imageEntries(
            zipFile, nestedCacheDir ?: NestedArchive.defaultCacheDir()
        )
    }

    /**
     * Every entry name in the archive, in central-directory order.
     * Same tolerance as [imageEntryNames]: the platform listing
     * first, the direct central-directory scan when it refuses
     * the archive, and the local-header scan when the tail itself
     * is gone (see [localHeaderEntries]). Throws the platform
     * reader's original exception only when all three readers
     * fail — a file that is not a readable ZIP at all.
     */
    fun allEntryNames(zipFile: File): List<String> {
        try {
            ZipFile(zipFile).use { zip ->
                return zip.entries().toList().map { it.name }
            }
        } catch (first: Exception) {
            return tolerantEntryNames(zipFile) ?: throw first
        }
    }

    /**
     * One entry's bytes, read tolerantly: STORED verbatim,
     * DEFLATED through an inflater, anything else (bzip2, LZMA,
     * zstd, encrypted) returns null instead of failing the
     * whole archive. The bytes are capped ([maxBytes]) so one
     * hostile entry cannot exhaust memory. Null also covers a
     * missing entry, a bad local header, or a file that is not
     * a readable ZIP at all.
     */
    fun readEntryBytes(
        zipFile: File,
        entryName: String,
        maxBytes: Long = 64L * 1024 * 1024,
        nestedCacheDir: File? = null
    ): ByteArray? {
        return try {
            openEntryStream(zipFile, entryName, nestedCacheDir)?.use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            DiagLog.w("NovelfaCbzBook", "readEntryBytes failed", e)
            null
        }
    }

    /**
     * A stream over one entry's data, or null when the entry
     * cannot be served (unknown method, encrypted, bad local
     * header, missing entry). The stream owns its file channel:
     * closing it closes the channel; the caller owns the stream.
     * STORED and DEFLATED only — the same rule as SeekableZip's
     * in-place reader, with the same per-entry failure shape.
     *
     * Virtual paths ([NestedArchive], e.g.
     * `ch01.zip/ch01/001.jpg`) resolve through the materialized
     * nested archives; [nestedCacheDir] is where they are
     * materialized, null uses the shared nested cache.
     */
    fun openEntryStream(
        zipFile: File,
        entryName: String,
        nestedCacheDir: File? = null
    ): InputStream? {
        if (NestedArchive.isVirtualPath(entryName)) {
            return NestedArchive.openPage(
                zipFile, entryName,
                nestedCacheDir ?: NestedArchive.defaultCacheDir()
            )
        }
        val entry = recoveredEntries(zipFile)
            ?.firstOrNull { it.name == entryName } ?: return null
        if (entry.isEncrypted || (entry.method != 0 && entry.method != 8)) return null
        val fis = try {
            FileInputStream(zipFile)
        } catch (e: Exception) {
            DiagLog.w("NovelfaCbzBook", "openEntryStream: cannot open zip file", e)
            return null
        }
        try {
            val channel = fis.channel
            val header = ByteArray(30)
            if (!readFullyAt(channel, entry.localHeaderOffset, header)) {
                runCatching { fis.close() }
                return null
            }
            if (readIntLE(header, 0) != 0x04034b50) {
                runCatching { fis.close() }
                return null
            }
            val nameLen = readShortLE(header, 26)
            val extraLen = readShortLE(header, 28)
            val dataStart = entry.localHeaderOffset + 30 + nameLen + extraLen
            val slice = FileSliceInputStream(channel, dataStart, entry.compressedSize) {
                runCatching { fis.close() }
            }
            return when (entry.method) {
                0 -> slice
                else -> InflaterInputStream(slice, Inflater(true))
            }
        } catch (e: Exception) {
            DiagLog.w("NovelfaCbzBook", "openEntryStream: entry read failed", e)
            runCatching { fis.close() }
            return null
        }
    }

    /** One central-directory row: enough to locate and open the entry. */
    private data class CentralEntry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val localHeaderOffset: Long,
        val flags: Int
    ) {
        val isEncrypted: Boolean get() = flags and 0x01 != 0
    }

    private fun tolerantEntryNames(zipFile: File): List<String>? =
        recoveredEntries(zipFile)?.map { it.name }

    /**
     * The archive's entries by any means that works: the direct
     * central-directory scan first, the local-header scan when
     * the tail (and with it the central directory) is gone.
     * Both return the same [CentralEntry] shape, so the callers
     * ([tolerantEntryNames], [openEntryStream]) need no second
     * path for recovered entries.
     */
    private fun recoveredEntries(zipFile: File): List<CentralEntry>? =
        tolerantEntries(zipFile) ?: localHeaderEntries(zipFile)

    /**
     * The central directory, read directly — the file-backed twin
     * of SeekableZip's scan for in-place archives: the EOCD is
     * found by scanning the tail, ZIP64 sentinels decline (the
     * platform reader handles those), and names decode as UTF-8
     * with a CP437 fallback, matching the platform reader's
     * legacy decoding. Returns null for anything unparseable;
     * the caller then falls back to the platform reader's
     * original exception.
     *
     * The entries are read SEQUENTIALLY from cdOffset, one by
     * one — cdSize is deliberately never consulted. ComicScreen's
     * reader (and zip4j's) reads the directory the same way, and
     * some writers record a wrong cdSize in the EOCD (too large,
     * zero, or stale): the old code required cdOffset+cdSize to
     * fit the file and read exactly cdSize bytes, so such an
     * archive failed this whole scan even though every entry is
     * intact — and, with the local walk blocked too, the import
     * failed with "zip END header not found" on a file
     * ComicScreen renders easily.
     */
    private fun tolerantEntries(zipFile: File): List<CentralEntry>? {
        return try {
            FileInputStream(zipFile).use { fis ->
                val channel = fis.channel
                val size = channel.size()
                if (size < 22) return null
                val tailLen = minOf(size, 65536L + 22).toInt()
                val tail = ByteArray(tailLen)
                if (!readFullyAt(channel, size - tailLen, tail)) return null
                var eocd = -1
                var i = tailLen - 22
                while (i >= 0) {
                    if (readIntLE(tail, i) == 0x06054b50) {
                        eocd = i
                        break
                    }
                    i--
                }
                if (eocd < 0) return null
                val count = readShortLE(tail, eocd + 10)
                val cdOffset = readIntLE(tail, eocd + 16).toLong() and 0xFFFFFFFFL
                if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL ||
                    count <= 0 || cdOffset < 0 || cdOffset >= size
                ) {
                    return null
                }
                val list = ArrayList<CentralEntry>(count)
                var p = cdOffset
                repeat(count) {
                    val fixed = ByteArray(46)
                    if (!readFullyAt(channel, p, fixed)) return null
                    if (readIntLE(fixed, 0) != 0x02014b50) return null
                    val flags = readShortLE(fixed, 8)
                    val method = readShortLE(fixed, 10)
                    val compSize = readIntLE(fixed, 20).toLong() and 0xFFFFFFFFL
                    val nameLen = readShortLE(fixed, 28)
                    val extraLen = readShortLE(fixed, 30)
                    val commentLen = readShortLE(fixed, 32)
                    val localOff = readIntLE(fixed, 42).toLong() and 0xFFFFFFFFL
                    val nameStart = p + 46
                    if (nameStart + nameLen > size) return null
                    val nameBytes = ByteArray(nameLen)
                    if (!readFullyAt(channel, nameStart, nameBytes)) return null
                    list.add(CentralEntry(decodeName(nameBytes), method, compSize, localOff, flags))
                    p = nameStart + nameLen + extraLen + commentLen
                }
                list
            }
        } catch (e: Exception) {
            DiagLog.w("NovelfaCbzBook", "tolerantEntries failed", e)
            null
        }
    }

    /**
     * The local file headers, read sequentially from the start of
     * the file — the recovery for an archive whose tail is missing
     * (an incomplete download: no EOCD, so the platform reader
     * and the central-directory scan both fail with "zip END
     * header not found"). Every COMPLETE entry before the cut is
     * recovered; the cut entry itself and everything after it are
     * dropped, so the book imports and opens with the pages that
     * survived. A file that is not a ZIP at all has no local
     * headers, so it still fails exactly as before (null here).
     *
     * Entries are walked by their own lengths: without a data
     * descriptor the size sits in the header; with one (flag bit
     * 3) the descriptor after the data carries it, found by a
     * validated signature search (see [findDescriptorWithSize])
     * — a stray signature inside compressed data cannot pass
     * that validation. Entries of any method are recorded (an
     * unservable one fails per-page later, like a
     * central-directory entry would); the scan only stops when
     * an entry's length cannot be determined — its data is cut,
     * or no valid descriptor exists before EOF. Returns null
     * when zero entries were recovered.
     */
    private fun localHeaderEntries(zipFile: File): List<CentralEntry>? {
        return try {
            FileInputStream(zipFile).use { fis ->
                val channel = fis.channel
                val size = channel.size()
                val out = ArrayList<CentralEntry>()
                var pos = findFirstLocalHeader(channel, size) ?: return null
                while (pos + 30 <= size) {
                    val header = ByteArray(30)
                    if (!readFullyAt(channel, pos, header)) break
                    if (readIntLE(header, 0) != 0x04034b50) break
                    val flags = readShortLE(header, 6)
                    val method = readShortLE(header, 8)
                    val compSizeHdr = readIntLE(header, 18).toLong() and 0xFFFFFFFFL
                    val nameLen = readShortLE(header, 26)
                    val extraLen = readShortLE(header, 28)
                    val dataStart = pos + 30L + nameLen + extraLen
                    if (dataStart > size) break
                    val nameBytes = ByteArray(nameLen)
                    if (!readFullyAt(channel, pos + 30, nameBytes)) break
                    val name = decodeName(nameBytes)
                    val compSize: Long
                    val nextPos: Long
                    if (flags and 0x08 == 0) {
                        compSize = compSizeHdr
                        val dataEnd = dataStart + compSize
                        if (dataEnd > size) break // this entry is cut: stop, keep what we have
                        nextPos = dataEnd
                    } else {
                        // Signed descriptor first, then the unsigned
                        // form (see findUnsignedDescriptor): the ZIP
                        // spec leaves the PK\x07\x08 signature
                        // optional, and some writers omit it.
                        val hit = findDescriptorWithSize(channel, dataStart, size)
                            ?: findUnsignedDescriptor(channel, dataStart, method, size)
                            ?: break // no descriptor before EOF: this entry is cut
                        compSize = hit.compSize
                        nextPos = hit.offset + hit.totalLen
                        if (nextPos > size) break
                    }
                    out.add(CentralEntry(name, method, compSize, pos, flags))
                    if (nextPos <= pos) break // cannot advance: never loop forever
                    pos = nextPos
                }
                out.ifEmpty { null }
            }
        } catch (e: Exception) {
            DiagLog.w("NovelfaCbzBook", "localHeaderEntries failed", e)
            null
        }
    }

    /** The first local-header offset: 0, or past a stub/junk prefix (first 1 MiB). */
    private fun findFirstLocalHeader(channel: FileChannel, size: Long): Long? {
        val probe = ByteArray(4)
        if (size >= 4 && readFullyAt(channel, 0, probe) &&
            readIntLE(probe, 0) == 0x04034b50
        ) {
            return 0
        }
        val window = minOf(size, 1024L * 1024).toInt()
        val buf = ByteArray(window)
        if (!readFullyAt(channel, 0, buf)) return null
        var i = 0
        while (i + 4 <= window) {
            if (readIntLE(buf, i) == 0x04034b50) return i.toLong()
            i++
        }
        return null
    }

    /** A data descriptor found after an entry's data: its offset, data length, and total size. */
    private data class DescriptorHit(
        val offset: Long,
        val compSize: Long,
        val totalLen: Long
    )

    /**
     * The data descriptor for an entry whose data starts at
     * [start]: the first PK\x07\x08 whose fields validate — the
     * 12-byte layout (crc + compSize + uncompSize) or the 20-byte
     * ZIP64 layout, either followed by a known header signature
     * or by end of file. A PK\x07\x08 inside compressed data
     * fails the validation (its "fields" are not followed by a
     * header), so the search continues past it. Null when no
     * valid descriptor exists before EOF: the entry is cut.
     */
    private fun findDescriptorWithSize(
        channel: FileChannel,
        start: Long,
        size: Long
    ): DescriptorHit? {
        var searchFrom = start
        while (true) {
            val at = findSignature(channel, searchFrom, size, 0x08074b50) ?: return null
            val fields12 = readBytesAt(channel, at + 4, 12)
            if (fields12 != null && at + 16 <= size &&
                isKnownSignatureAt(channel, at + 16, size)
            ) {
                return DescriptorHit(at, readIntLE(fields12, 4).toLong() and 0xFFFFFFFFL, 16)
            }
            val fields20 = readBytesAt(channel, at + 4, 20)
            if (fields20 != null && at + 24 <= size &&
                isKnownSignatureAt(channel, at + 24, size)
            ) {
                return DescriptorHit(at, readLongLE(fields20, 4), 24)
            }
            searchFrom = at + 4 // false positive inside the data: keep scanning
        }
    }

    /**
     * A data descriptor WITHOUT its PK\x07\x08 signature. The ZIP
     * spec leaves that signature optional, and some writers emit
     * only the 12 (or 16 for ZIP64) descriptor bytes — crc,
     * compressed size, uncompressed size — with no marker at all.
     * The descriptor is then the bytes right before the next
     * header, and the entry is resolved by finding that header
     * and validating the bytes before it exactly (CRC for STORED
     * data, a full inflate for DEFLATED), so a header-like byte
     * sequence inside the data cannot pass as a false end.
     * Returns null when no following header validates: the entry
     * is cut.
     */
    private fun findUnsignedDescriptor(
        channel: FileChannel,
        dataStart: Long,
        method: Int,
        size: Long
    ): DescriptorHit? {
        var searchFrom = dataStart
        while (true) {
            val next = findNextHeader(channel, searchFrom, size)
            if (next == null) {
                // No further header: the descriptor, if any, is
                // the 12 (or 16) bytes right before end of file —
                // the last entry of a truncated archive.
                for (totalLen in listOf(12L, 16L)) {
                    val descAt = size - totalLen
                    if (descAt < dataStart) continue
                    if (validateUnsignedDescriptor(channel, dataStart, descAt, method)) {
                        return DescriptorHit(descAt, descAt - dataStart, totalLen)
                    }
                }
                return null
            }
            // 12-byte layout first, then the 16-byte ZIP64 layout.
            for (totalLen in listOf(12L, 16L)) {
                val descAt = next - totalLen
                if (descAt < dataStart) continue
                if (validateUnsignedDescriptor(channel, dataStart, descAt, method)) {
                    return DescriptorHit(descAt, descAt - dataStart, totalLen)
                }
            }
            // Not a descriptor before this header (a false header
            // signature inside the data): keep scanning past it.
            searchFrom = next + 4
        }
    }

    private fun validateUnsignedDescriptor(
        channel: FileChannel,
        dataStart: Long,
        descAt: Long,
        method: Int
    ): Boolean {
        return try {
            when (method) {
                0 -> {
                    // STORED: the descriptor's first field is the CRC32
                    // of the verbatim data.
                    val crcBytes = readBytesAt(channel, descAt, 4) ?: return false
                    val expected = readIntLE(crcBytes, 0).toLong() and 0xFFFFFFFFL
                    val crc = CRC32()
                    val buf = ByteArray(65536)
                    var pos = dataStart
                    while (pos < descAt) {
                        val want = minOf(buf.size.toLong(), descAt - pos).toInt()
                        val bb = ByteBuffer.wrap(buf, 0, want)
                        var at = pos
                        while (bb.hasRemaining()) {
                            val n = channel.read(bb, at)
                            if (n < 0) return false
                            at += n
                        }
                        crc.update(buf, 0, want)
                        pos += want
                    }
                    crc.value == expected
                }
                8 -> {
                    // DEFLATED: the deflate stream must end exactly
                    // where the descriptor begins.
                    val inflater = Inflater(true)
                    try {
                        val inBuf = ByteArray(65536)
                        val outBuf = ByteArray(8192)
                        var pos = dataStart
                        while (!inflater.finished()) {
                            if (inflater.needsInput()) {
                                if (pos >= descAt) return false
                                val want = minOf(inBuf.size.toLong(), descAt - pos).toInt()
                                val bb = ByteBuffer.wrap(inBuf, 0, want)
                                var at = pos
                                while (bb.hasRemaining()) {
                                    val n = channel.read(bb, at)
                                    if (n < 0) return false
                                    at += n
                                }
                                inflater.setInput(inBuf, 0, want)
                                pos += want
                            }
                            try {
                                inflater.inflate(outBuf)
                            } catch (e: DataFormatException) {
                                return false
                            }
                            if (inflater.needsDictionary()) return false
                        }
                        inflater.totalIn.toLong() == descAt - dataStart
                    } finally {
                        inflater.end()
                    }
                }
                else -> false
            }
        } catch (e: Exception) {
            DiagLog.w("NovelfaCbzBook", "validateUnsignedDescriptor failed", e)
            false
        }
    }

    /** First offset >= [start] where a local, central or EOCD header starts, or null. */
    private fun findNextHeader(channel: FileChannel, start: Long, size: Long): Long? {
        val candidates = listOf(0x04034b50, 0x02014b50, 0x06054b50).mapNotNull {
            findSignature(channel, start, size, it)
        }
        return candidates.minOrNull()
    }

    /** True at end of file, or when a known ZIP header signature sits at [pos]. */
    private fun isKnownSignatureAt(channel: FileChannel, pos: Long, size: Long): Boolean {
        if (pos >= size) return true
        val sig = readBytesAt(channel, pos, 4) ?: return false
        val v = readIntLE(sig, 0)
        return v == 0x04034b50 || v == 0x02014b50 ||
            v == 0x06054b50 || v == 0x08074b50
    }

    /** First offset >= [start] where the 4-byte [sig] occurs, or null. Chunked; no full-file read. */
    private fun findSignature(channel: FileChannel, start: Long, size: Long, sig: Int): Long? {
        val chunkSize = 65536
        val buf = ByteArray(chunkSize)
        var pos = start
        var tail = ByteArray(0)
        while (pos < size) {
            val want = minOf(chunkSize.toLong(), size - pos).toInt()
            val bb = ByteBuffer.wrap(buf, 0, want)
            var at = pos
            while (bb.hasRemaining()) {
                val n = channel.read(bb, at)
                if (n < 0) return null
                at += n
            }
            val search = tail + buf.copyOfRange(0, want)
            var i = 0
            while (i + 4 <= search.size) {
                if (readIntLE(search, i) == sig) {
                    return pos - tail.size + i
                }
                i++
            }
            tail = search.takeLast(minOf(3, search.size)).toByteArray()
            pos += want
        }
        return null
    }

    /** [len] bytes at [pos], or null on a short read. */
    private fun readBytesAt(channel: FileChannel, pos: Long, len: Int): ByteArray? {
        val buf = ByteArray(len)
        return if (readFullyAt(channel, pos, buf)) buf else null
    }

    private fun decodeName(bytes: ByteArray): String {
        // Strict UTF-8 first (the flag-11 case and the common
        // case); CP437 fallback matches the platform reader's
        // legacy decoding for archives without it.
        runCatching {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }
        return runCatching {
            Charset.forName("IBM437").decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrDefault(String(bytes, Charsets.UTF_8))
    }

    private fun readShortLE(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

    private fun readIntLE(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)

    private fun readLongLE(bytes: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((bytes[off + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }

    private fun readFullyAt(channel: FileChannel, pos: Long, buf: ByteArray): Boolean {
        val bb = ByteBuffer.wrap(buf)
        var at = pos
        while (bb.hasRemaining()) {
            val n = channel.read(bb, at)
            if (n < 0) return false
            at += n
        }
        return true
    }

    /**
     * One chapter of a foldered comic: the folder name as stored
     * in the archive (book data, never translated) and the index,
     * into the natural page order, of that chapter's first page.
     * The native viewer jumps to [firstPageIndex] on selection;
     * zoom, the page slider and the reading mode are untouched by
     * the jump, so the reader lands on the chapter's first page
     * in exactly the state it was already reading in.
     */
    data class CbzChapter(
        val title: String,
        val firstPageIndex: Int
    )

    /**
     * Chapters for an archive, from its image entries alone (the
     * caller may pass the raw ZIP listing; non-images are filtered
     * and the rest is put in natural order here, so the result is
     * the same as for [imageEntryNames]).
     *
     * Rules:
     * - Chapter = the first folder segment of a page's parent
     *   folder, after stripping the path segments EVERY foldered
     *   page shares: `Series/Chapter 01/...` and
     *   `Series/Chapter 02/...` give chapters `Chapter 01` and
     *   `Chapter 02`, not two chapters both called `Series`.
     * - Deeper nesting stays inside its chapter: `Ch 1/part 1/`
     *   and `Ch 1/part 2/` are both chapter `Ch 1`.
     * - Root-level pages (a cover before the first folder) belong
     *   to no chapter; they still count as pages, so a chapter
     *   after them starts past index 0.
     * - A flat archive, or an archive whose pages all sit in one
     *   single folder, yields no chapters: there is nothing to
     *   jump between, and the viewer shows no chapter control.
     * - Chapters come out in first-appearance order, which IS
     *   reading order because the pages are naturally sorted.
     */
    fun chaptersForEntries(entryNames: List<String>): List<CbzChapter> {
        val pages = entryNames.filter { isImageEntry(it) }.sortedWith(::compareNatural)
        if (pages.isEmpty()) return emptyList()
        val parents = pages.map { it.substringBeforeLast('/', "") }
        val foldered = parents.filter { it.isNotEmpty() }
        val common: List<String> = if (foldered.isNotEmpty() && foldered.size == parents.size) {
            foldered.map { it.split('/') }.reduce { acc, parts ->
                acc.zip(parts).takeWhile { (a, b) -> a == b }.map { it.first }
            }
        } else {
            emptyList()
        }
        val firstPage = linkedMapOf<String, Int>()
        for ((index, parent) in parents.withIndex()) {
            val stripped = if (common.isEmpty()) {
                parent
            } else {
                parent.split('/').drop(common.size).joinToString("/")
            }
            // substringBefore with its default: a single-segment
            // folder IS the chapter name (an explicit "" here
            // would have thrown single-segment chapters away).
            // A nested archive segment names its chapter without
            // the extension: `ch01.zip` is chapter `ch01`, not
            // `ch01.zip` (the same strip the common-prefix rule
            // already gives the outer archive level). RAR
            // segments strip the same way.
            val rawKey = stripped.substringBefore('/')
            val key = if (rawKey.substringAfterLast('.', "").lowercase()
                    in NestedArchive.CONTAINER_EXTENSIONS
            ) {
                rawKey.substringBeforeLast('.')
            } else {
                rawKey
            }
            if (key.isNotEmpty() && key !in firstPage) {
                firstPage[key] = index
            }
        }
        return firstPage.map { (title, index) -> CbzChapter(title, index) }
    }

    /** Chapters for an archive file, in reading order. */
    fun chapters(zipFile: File, nestedCacheDir: File? = null): List<CbzChapter> =
        chaptersForEntries(imageEntryNames(zipFile, nestedCacheDir))

    /**
     * Natural order, ComicScreen's way: compare path SEGMENT by
     * segment (split on '/'), each segment naturally (runs of
     * digits as numbers, 2 < 10, the rest case-insensitive). A
     * path that is a prefix of the other sorts first, so a folder
     * sorts before its contents instead of after a same-prefix
     * file (`a/b` before `a-b`, where a flat comparison would
     * put the '-' file first because '-' < '/'). Splitting on
     * digit runs keeps `page-2` before `page-10` and keeps
     * `chapter-01/page-03` grouped under its folder.
     */
    internal fun compareNatural(a: String, b: String): Int {
        val aSegs = a.split('/')
        val bSegs = b.split('/')
        val n = minOf(aSegs.size, bSegs.size)
        for (i in 0 until n) {
            val cmp = compareNaturalSegment(aSegs[i], bSegs[i])
            if (cmp != 0) return cmp
        }
        return aSegs.size.compareTo(bSegs.size)
    }

    private fun compareNaturalSegment(a: String, b: String): Int {
        val aParts = splitNatural(a)
        val bParts = splitNatural(b)
        val n = minOf(aParts.size, bParts.size)
        for (i in 0 until n) {
            val x = aParts[i]
            val y = bParts[i]
            val cmp = if (x is Long && y is Long) {
                x.compareTo(y)
            } else {
                x.toString().compareTo(y.toString(), ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return aParts.size.compareTo(bParts.size)
    }

    private fun splitNatural(s: String): List<Any> {
        val out = mutableListOf<Any>()
        var i = 0
        while (i < s.length) {
            if (s[i].isDigit()) {
                var j = i
                while (j < s.length && s[j].isDigit()) j++
                val digits = s.substring(i, j)
                out += digits.toLongOrNull() ?: digits
                i = j
            } else {
                var j = i
                while (j < s.length && !s[j].isDigit()) j++
                out += s.substring(i, j)
                i = j
            }
        }
        return out
    }
}

/**
 * A slice of a file channel as an InputStream: positioned reads
 * only, so the channel's shared position never moves. Closing
 * the stream runs [onClose] (the channel owner). The file-backed
 * twin of SeekableZip's ChannelSliceInputStream.
 */
private class FileSliceInputStream(
    private val channel: FileChannel,
    private val start: Long,
    private val length: Long,
    private val onClose: () -> Unit = {}
) : InputStream() {
    private var pos = 0L
    private var closed = false

    override fun read(): Int {
        if (pos >= length) return -1
        val b = ByteArray(1)
        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val want = minOf(len.toLong(), length - pos).toInt()
        val bb = ByteBuffer.wrap(buf, off, want)
        var total = 0
        while (bb.hasRemaining()) {
            val n = try {
                channel.read(bb, start + pos + total)
            } catch (e: java.io.IOException) {
                return if (total == 0) -1 else total
            }
            if (n < 0) break
            total += n
        }
        if (total == 0) return -1
        pos += total
        return total
    }

    override fun available(): Int =
        minOf(length - pos, Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}
