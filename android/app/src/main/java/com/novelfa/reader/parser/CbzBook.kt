package com.novelfa.reader.parser

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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
 * - CBR (RAR) is deliberately NOT supported: it needs a RAR
 *   decoder, which is a separate dependency. A .cbr file fails
 *   import with the unsupported-format message, not silently.
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
     */
    fun imageEntryNames(zipFile: File): List<String> {
        return allEntryNames(zipFile)
            .filter { isImageEntry(it) }
            .sortedWith(::compareNatural)
    }

    /**
     * Every entry name in the archive, in central-directory order.
     * Same tolerance as [imageEntryNames]: the platform listing
     * first, the direct central-directory scan when it refuses
     * the archive. Throws the platform reader's original
     * exception only when both readers fail — a file that is
     * not a readable ZIP at all.
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
        maxBytes: Long = 64L * 1024 * 1024
    ): ByteArray? {
        return try {
            openEntryStream(zipFile, entryName)?.use { stream ->
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
     */
    fun openEntryStream(zipFile: File, entryName: String): InputStream? {
        val entry = tolerantEntries(zipFile)
            ?.firstOrNull { it.name == entryName } ?: return null
        if (entry.isEncrypted || (entry.method != 0 && entry.method != 8)) return null
        val fis = try {
            FileInputStream(zipFile)
        } catch (e: Exception) {
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
        tolerantEntries(zipFile)?.map { it.name }

    /**
     * The central directory, read directly — the file-backed twin
     * of SeekableZip's scan for in-place archives: the EOCD is
     * found by scanning the tail, ZIP64 sentinels decline (the
     * platform reader handles those), and names decode as UTF-8
     * with a CP437 fallback, matching the platform reader's
     * legacy decoding. Returns null for anything unparseable;
     * the caller then falls back to the platform reader's
     * original exception.
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
                val cdSize = readIntLE(tail, eocd + 12).toLong() and 0xFFFFFFFFL
                val cdOffset = readIntLE(tail, eocd + 16).toLong() and 0xFFFFFFFFL
                if (count == 0xFFFF || cdSize == 0xFFFFFFFFL ||
                    cdOffset == 0xFFFFFFFFL || count <= 0 || cdSize <= 0L ||
                    cdOffset < 0 || cdOffset + cdSize > size
                ) {
                    return null
                }
                val cd = ByteArray(cdSize.toInt())
                if (!readFullyAt(channel, cdOffset, cd)) return null
                val list = ArrayList<CentralEntry>(count)
                var p = 0
                repeat(count) {
                    if (p + 46 > cd.size) return null
                    if (readIntLE(cd, p) != 0x02014b50) return null
                    val flags = readShortLE(cd, p + 8)
                    val method = readShortLE(cd, p + 10)
                    val compSize = readIntLE(cd, p + 20).toLong() and 0xFFFFFFFFL
                    val nameLen = readShortLE(cd, p + 28)
                    val extraLen = readShortLE(cd, p + 30)
                    val commentLen = readShortLE(cd, p + 32)
                    val localOff = readIntLE(cd, p + 42).toLong() and 0xFFFFFFFFL
                    if (p + 46 + nameLen > cd.size) return null
                    val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)
                    list.add(CentralEntry(decodeName(nameBytes), method, compSize, localOff, flags))
                    p += 46 + nameLen + extraLen + commentLen
                }
                list
            }
        } catch (e: Exception) {
            null
        }
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
            val key = stripped.substringBefore('/')
            if (key.isNotEmpty() && key !in firstPage) {
                firstPage[key] = index
            }
        }
        return firstPage.map { (title, index) -> CbzChapter(title, index) }
    }

    /** Chapters for an archive file, in reading order. */
    fun chapters(zipFile: File): List<CbzChapter> =
        chaptersForEntries(imageEntryNames(zipFile))

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
