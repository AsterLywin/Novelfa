package com.novelfa.reader.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.novelfa.reader.parser.CbzBook
import com.novelfa.reader.parser.NestedArchive
import com.novelfa.reader.parser.engine.ZipExtract
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Seekable ZIP reader for in-place `content://` archives (step 5).
 *
 * Why it exists: every engine in the app wants a File, and a SAF Uri
 * is not one — so in-place `content://` books paid a full working-copy
 * build on first open, which just moved the copy from import time to
 * open time. For comics that copy is the whole archive, and it is what
 * made "in-place" feel no faster than a copy on first open. This reader
 * opens the provider's file descriptor directly and reads ONLY the
 * end-of-central-directory record plus the central directory (kilobytes),
 * then serves each entry on demand through positioned channel reads —
 * the same shape as the working-copy path, with no copy at all.
 *
 * Rules, all conservative — any doubt falls back to the working copy:
 * - The descriptor must be genuinely seekable: a pipe-backed provider
 *   fails the positioned-read probe in [openFromUri] and returns null.
 * - ZIP64 archives return null (their EOCD counts/offsets are
 *   0xFFFF/0xFFFFFFFF sentinels this parser does not follow).
 * - Entry names must decode as strict UTF-8: a legacy-charset archive
 *   would decode to garbage names here, and the zip4j path (which
 *   handles it) beats showing wrong page names.
 * - Only STORED and DEFLATED entries are served; anything else
 *   (bzip2, encrypted entries) returns null for that entry, and
 *   any encrypted entry at all makes the caller fall back —
 *   encryption needs zip4j, which this reader deliberately does not
 *   reimplement.
 * - Positioned reads (`FileChannel.read(buf, position)`) never move
 *   the channel's shared position, so entry streams are safe to
 *   read from several threads at once, exactly like the bytes
 *   the comic viewer already decodes in parallel.
 */
class SeekableZip private constructor(
    private val pfd: ParcelFileDescriptor?,
    private val channel: FileChannel,
    val entries: List<Entry>
) : Closeable {

    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val localHeaderOffset: Long,
        val flags: Int
    ) {
        val isDirectory: Boolean get() = name.endsWith('/')
        val isEncrypted: Boolean get() = flags and 0x01 != 0
    }

    fun entryNames(): List<String> = entries.map { it.name }

    /** Image pages in exactly the order the file-backed viewer uses. */
    fun imageEntryNames(): List<String> =
        entries.map { it.name }
            .filter { CbzBook.isImageEntry(it) }
            .sortedWith { a, b -> CbzBook.compareNatural(a, b) }

    fun hasEncryptedEntries(): Boolean = entries.any { it.isEncrypted }

    /**
     * Flattened image list, nested archives included: a chapter
     * ZIP inside this archive contributes its images under
     * virtual paths (`ch01.zip/ch01/001.jpg`), in the same
     * natural order as the file-backed listing. Nested archives
     * are materialized once into [cacheDir] ([NestedArchive]);
     * [sourceKey] identifies this source (URI) so a changed
     * source misses the cache instead of serving stale pages.
     * Unreadable nested archives are skipped, exactly like the
     * file-backed path.
     */
    fun nestedImageEntryNames(cacheDir: File, sourceKey: String): List<String> {
        val out = imageEntryNames().toMutableList()
        val archives = entries.map { it.name }
            .filter { NestedArchive.isArchiveName(it) }
            .sortedWith { a, b -> CbzBook.compareNatural(a, b) }
        for (arc in archives) {
            val entry = entries.firstOrNull { it.name == arc } ?: continue
            val stream = openEntry(arc) ?: continue
            val file = NestedArchive.materialize(
                stream, "$sourceKey|$arc|${entry.compressedSize}", cacheDir
            ) ?: continue
            out += NestedArchive.imageEntries(file, cacheDir)
                .map { "$arc/$it" }
            if (out.size >= ZipExtract.MAX_ENTRIES) break
        }
        return out.sortedWith { a, b -> CbzBook.compareNatural(a, b) }
            .take(ZipExtract.MAX_ENTRIES)
    }

    /** A stream over one virtual entry, or null when any level fails. */
    fun openNestedEntry(
        virtualPath: String,
        cacheDir: File,
        sourceKey: String
    ): InputStream? {
        val parts = virtualPath.split('/')
        if (parts.size < 2) return openEntry(virtualPath)
        val first = parts.first()
        val entry = entries.firstOrNull { it.name == first } ?: return null
        val stream = openEntry(first) ?: return null
        val file = NestedArchive.materialize(
            stream, "$sourceKey|$first|${entry.compressedSize}", cacheDir
        ) ?: return null
        val rest = parts.drop(1).joinToString("/")
        return NestedArchive.openPage(file, rest, cacheDir)
    }

    /** True when a book file hides anywhere in the nested tree. */
    fun hasNestedBook(cacheDir: File, sourceKey: String): Boolean {
        for (arc in entries.map { it.name }.filter { NestedArchive.isArchiveName(it) }) {
            val entry = entries.firstOrNull { it.name == arc } ?: continue
            val stream = openEntry(arc) ?: continue
            val file = NestedArchive.materialize(
                stream, "$sourceKey|$arc|${entry.compressedSize}", cacheDir
            ) ?: continue
            if (NestedArchive.containsBook(file, cacheDir)) return true
        }
        return false
    }

    /**
     * True when any image inside a nested archive uses a method
     * this reader cannot serve. The direct route declines such
     * archives; the working-copy backends fail only the bad
     * pages instead of the whole book.
     */
    fun hasNestedUnservableImages(cacheDir: File, sourceKey: String): Boolean {
        for (arc in entries.map { it.name }.filter { NestedArchive.isArchiveName(it) }) {
            val entry = entries.firstOrNull { it.name == arc } ?: continue
            val stream = openEntry(arc) ?: continue
            val file = NestedArchive.materialize(
                stream, "$sourceKey|$arc|${entry.compressedSize}", cacheDir
            ) ?: continue
            if (NestedArchive.hasUnservableImages(file, cacheDir)) return true
        }
        return false
    }

    /**
     * True when an image entry uses a compression method this
     * reader cannot serve (anything but STORED/DEFLATED). The
     * listing still works — names and page count look fine — but
     * such a page can never open through the direct route, and
     * Retry cannot help it: the store is fixed at open time, so
     * the same entry fails the same way every time. The caller
     * should fall back to the working copy instead, whose
     * backends serve the readable pages and fail only the bad
     * ones. See the class rule above: any doubt falls back to
     * the working copy.
     */
    fun hasUnservableImageEntries(): Boolean =
        entries.any { entry ->
            entry.method != 0 && entry.method != 8 &&
                CbzBook.isImageEntry(entry.name)
        }

    /**
     * Opens one entry for reading, or null when the entry cannot be
     * served (unknown method, encrypted, or a bad local header).
     * The caller owns the stream; closing it does not close the archive.
     */
    fun openEntry(name: String): InputStream? {
        val entry = entries.firstOrNull { it.name == name } ?: return null
        if (entry.isEncrypted) return null
        // Local file header: signature(4) + version(2) + flags(2) +
        // method(2) + time(2) + date(2) + crc(4) + sizes(8) +
        // nameLen(2) + extraLen(2) = 30 bytes, then name + extra,
        // then the entry data the central directory sized.
        val header = ByteArray(30)
        if (!readFullyAt(entry.localHeaderOffset, header)) return null
        if (readIntLE(header, 0) != 0x04034b50) return null
        val nameLen = readShortLE(header, 26)
        val extraLen = readShortLE(header, 28)
        val dataStart = entry.localHeaderOffset + 30 + nameLen + extraLen
        val slice = ChannelSliceInputStream(channel, dataStart, entry.compressedSize)
        return when (entry.method) {
            0 -> slice
            8 -> InflaterInputStream(slice, Inflater(true))
            else -> null
        }
    }

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        // Closing the descriptor closes the channel's fd with it;
        // the FileInputStream that produced the channel is never
        // closed separately, because it would close this same fd.
        runCatching { pfd?.close() }
    }

    companion object {

        /**
         * Opens a `content://` Uri seekably, or null when the
         * provider cannot serve it that way (pipe, empty file,
         * unreadable descriptor, unparseable archive) — the
         * caller then uses the working-copy path unchanged.
         */
        fun openFromUri(context: Context, uri: Uri): SeekableZip? {
            val pfd = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")
            }.getOrNull() ?: return null
            try {
                val channel = FileInputStream(pfd.fileDescriptor).channel
                // Seekability proof: a pipe-backed descriptor fails
                // positioned reads (ESPIPE) — that failure IS the
                // signal to fall back, not an error to surface.
                val size = runCatching { channel.size() }.getOrNull()
                if (size == null || size <= 0L) {
                    runCatching { pfd.close() }
                    return null
                }
                val probe = ByteBuffer.allocate(1)
                val probed = runCatching { channel.read(probe, 0) }.getOrNull()
                if (probed == null) {
                    runCatching { pfd.close() }
                    return null
                }
                return open(channel, pfd)
            } catch (e: Exception) {
                runCatching { pfd.close() }
                return null
            }
        }

        /**
         * Parses the central directory of an already-open seekable
         * channel, or null for anything this parser does not serve
         * (not a ZIP, ZIP64, bad directory). The [pfd] is owned
         * by the result when given, and closed with it.
         */
        fun open(channel: FileChannel, pfd: ParcelFileDescriptor? = null): SeekableZip? {
            return runCatching {
                val size = channel.size()
                // EOCD: 22 bytes + up to a 64KB comment, at the tail.
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
                // ZIP64 sentinels, or a directory that cannot fit:
                // fall back rather than misparse.
                if (count == 0xFFFF || cdSize == 0xFFFFFFFFL ||
                    cdOffset == 0xFFFFFFFFL || count <= 0 || cdSize <= 0L ||
                    cdOffset < 0 || cdOffset + cdSize > size
                ) {
                    return null
                }
                val cd = ByteArray(cdSize.toInt())
                if (!readFullyAt(channel, cdOffset, cd)) return null
                val list = ArrayList<Entry>(count)
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
                    val name = runCatching {
                        Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(nameBytes)).toString()
                    }.getOrNull() ?: return null
                    list.add(Entry(name, method, compSize, localOff, flags))
                    p += 46 + nameLen + extraLen + commentLen
                }
                SeekableZip(pfd, channel, list)
            }.getOrNull()
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

        private fun SeekableZip.readFullyAt(pos: Long, buf: ByteArray): Boolean =
            readFullyAt(channel, pos, buf)
    }
}

/**
 * A slice of a channel as an InputStream: positioned reads only, so
 * several slices can read concurrently without moving each other.
 * Closing a slice closes nothing (the archive owns the channel).
 */
private class ChannelSliceInputStream(
    private val channel: FileChannel,
    private val start: Long,
    private val length: Long
) : InputStream() {
    private var pos = 0L

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
            } catch (e: IOException) {
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
}
