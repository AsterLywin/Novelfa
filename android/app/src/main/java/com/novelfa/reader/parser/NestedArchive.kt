package com.novelfa.reader.parser

import com.novelfa.reader.parser.engine.ZipExtract
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Nested archives (ZIP-in-ZIP): a comic or book pack whose chapters
 * are themselves ZIP files inside one outer ZIP — e.g. one file per
 * chapter, each holding its chapter's images in its own folder, all
 * zipped again into a single file (and sometimes zipped once more).
 * ComicScreen does not open that shape at all, and Novelfa used to
 * fail it the same way: the top-level listing holds only `.zip`
 * entries, so the classifier saw no images and no book and the file
 * was "nothing readable".
 *
 * Model: a nested archive is a transparent directory. A page inside
 * one is named by its virtual path, archive segments joined with
 * `/` — `ch01.zip/ch01/001.jpg` — and every listing in the app
 * (page list, chapter split, cover, classification) works on the
 * flattened virtual list, in the same natural order as a flat
 * archive.
 *
 * How a nested archive is opened: a nested entry is a byte range
 * inside its parent, and a DEFLATED one cannot be seeked into
 * without inflating it whole — so the first use materializes it
 * once, as a real file in a cache directory, and every later read
 * (listing, page bytes, cover, book candidate) uses the existing
 * file-backed machinery on that file. The materialized file is
 * keyed by the parent's identity (length + mtime) and the entry
 * name, so an unchanged parent reuses it across opens and a
 * changed parent re-materializes; stale files are pruned on
 * materialize (older than 7 days). The cache lives under the
 * app cache dir by default, never next to the user's own file.
 *
 * Caps (same numbers as the rest of the archive code, so a
 * hostile file cannot cost more here than anywhere else):
 * - [MAX_DEPTH] archive levels including the outer file (the
 *   reported shape needs 2–3; a self-referential archive — a ZIP
 *   containing a copy of itself — is stopped by the cap alone);
 * - [ZipExtract.MAX_BYTES] (500MB) per materialized nested
 *   archive;
 * - [ZipExtract.MAX_ENTRIES] (10,000) flattened pages total.
 *
 * Failure shape: an unreadable nested archive (encrypted,
 * unknown method, corrupt) is SKIPPED for listing, and its pages
 * fail one by one at serve time — exactly like an unreadable page
 * in a flat archive. Only when nothing readable remains anywhere
 * does the file fail as a whole.
 */
object NestedArchive {

    /** Archive levels including the outer file. */
    const val MAX_DEPTH = 4

    private const val STALE_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** Container extensions that can hold a nested archive. */
    val ARCHIVE_EXTENSIONS = setOf("zip", "cbz")

    /** Book extensions, for the nested-candidate search. */
    val BOOK_EXTENSIONS = setOf(
        "epub", "docx", "pdf", "txt", "text",
        "md", "markdown", "mdown", "fb2", "fbz", "rtf"
    )

    /** True for a non-directory archive entry (a nested container). */
    fun isArchiveName(name: String): Boolean {
        if (name.endsWith('/')) return false
        val base = name.substringAfterLast('/')
        if (base.startsWith('.')) return false
        if (name.startsWith("__MACOSX/")) return false
        return base.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS
    }

    /** True when a virtual path dives into a nested archive: any
     *  segment but the last is an archive name. */
    fun isVirtualPath(name: String): Boolean {
        val parts = name.split('/')
        if (parts.size < 2) return false
        return parts.dropLast(1).any { isArchiveName(it) }
    }

    /**
     * The shared nested-archive cache: a directory under the JVM
     * temp dir, which on Android IS the app cache dir. Callers
     * with a per-book cache pass it explicitly instead; this is
     * only the default for call sites that have no book context
     * (classify, listing, cover).
     */
    fun defaultCacheDir(): File {
        val tmp = System.getProperty("java.io.tmpdir") ?: "."
        return File(tmp, "novelfa-nested").apply { mkdirs() }
    }

    private fun cacheKey(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
            .take(32)
    }

    private fun pruneStale(cacheDir: File) {
        runCatching {
            val now = System.currentTimeMillis()
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && now - file.lastModified() > STALE_MILLIS) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Materializes one nested archive entry of a file-backed
     * archive into [cacheDir] and returns the materialized file,
     * or null when the entry cannot be served (encrypted, unknown
     * method, corrupt, over the size cap, not a ZIP). A cached
     * file is returned as-is when the parent still matches its
     * signature (length + mtime), so the inflate is paid once.
     */
    fun materialize(source: File, entryName: String, cacheDir: File): File? {
        val key = cacheKey(
            source.absolutePath, source.length().toString(),
            source.lastModified().toString(), entryName
        )
        return materializeIntoCache(
            cacheDir, key,
            openStream = { CbzBook.openEntryStream(source, entryName) }
        )
    }

    /**
     * Stream twin of [materialize]: the entry bytes come from a
     * caller-owned stream (a [com.novelfa.reader.data.SeekableZip]
     * entry, which has no File). [key] must identify the source
     * and the entry — the caller builds it from its URI plus the
     * entry's compressed size, so a changed source misses the
     * cache instead of serving stale pages.
     */
    fun materialize(stream: InputStream, key: String, cacheDir: File): File? {
        val fileKey = cacheKey("stream", key)
        return materializeIntoCache(cacheDir, fileKey, openStream = { stream })
    }

    private fun materializeIntoCache(
        cacheDir: File,
        key: String,
        openStream: () -> InputStream?
    ): File? {
        runCatching { cacheDir.mkdirs() }
        val dest = File(cacheDir, "$key.zip")
        if (dest.isFile && dest.length() > 0) return dest
        pruneStale(cacheDir)
        val stream = openStream() ?: return null
        val tmp = File(cacheDir, "$key.tmp")
        try {
            var total = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > ZipExtract.MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
            }
            if (total <= 0) return null
            // A materialized nested archive must BE a ZIP: an
            // entry that only wears the extension (a misnamed
            // RAR, a truncated download) is refused here, not
            // passed on to list as an empty archive.
            val head = ByteArray(2)
            tmp.inputStream().use {
                if (it.read(head) < 2) return null
            }
            if (!(head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())) {
                return null
            }
            if (!tmp.renameTo(dest)) {
                // Lost a race with another materialization of the
                // same entry: the winner's file is the result.
                if (dest.isFile && dest.length() > 0) return dest
                return null
            }
            return dest
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            return null
        } finally {
            runCatching { stream.close() }
        }
    }

    /**
     * Flattened image list for [archiveFile], in natural order:
     * its own images plus, for each nested archive (natural
     * order), that archive's images with the virtual prefix.
     * Throws when the outer archive itself cannot be listed at
     * all (the caller's generic catch turns that into the import
     * error); unreadable nested archives are skipped silently.
     */
    fun imageEntries(
        archiveFile: File,
        cacheDir: File,
        depth: Int = MAX_DEPTH
    ): List<String> {
        val out = ArrayList<String>()
        collectImages(archiveFile, cacheDir, depth, "", out)
        return out.sortedWith { a, b -> CbzBook.compareNatural(a, b) }
            .take(ZipExtract.MAX_ENTRIES)
    }

    private fun collectImages(
        archiveFile: File,
        cacheDir: File,
        depth: Int,
        prefix: String,
        out: MutableList<String>
    ) {
        val names = CbzBook.allEntryNames(archiveFile)
        for (name in names) {
            if (CbzBook.isImageEntry(name)) out += prefix + name
            if (out.size >= ZipExtract.MAX_ENTRIES) return
        }
        if (depth <= 1) return
        val nested = names.filter { isArchiveName(it) }
            .sortedWith { a, b -> CbzBook.compareNatural(a, b) }
        for (entry in nested) {
            val file = runCatching {
                materialize(archiveFile, entry, cacheDir)
            }.getOrNull() ?: continue
            runCatching {
                collectImages(file, cacheDir, depth - 1, prefix + entry + "/", out)
            }
            if (out.size >= ZipExtract.MAX_ENTRIES) return
        }
    }

    /**
     * Book files inside [archiveFile], as virtual paths — the
     * nested twin of [com.novelfa.reader.parser.engine.ZipEngine]
     * candidate search. Unreadable nested archives are skipped.
     */
    fun bookEntries(
        archiveFile: File,
        cacheDir: File,
        depth: Int = MAX_DEPTH
    ): List<String> {
        val out = ArrayList<String>()
        collectBooks(archiveFile, cacheDir, depth, "", out)
        return out.distinct().sortedWith { a, b -> CbzBook.compareNatural(a, b) }
    }

    private fun collectBooks(
        archiveFile: File,
        cacheDir: File,
        depth: Int,
        prefix: String,
        out: MutableList<String>
    ) {
        val names = runCatching { CbzBook.allEntryNames(archiveFile) }.getOrNull()
            ?: return
        for (name in names) {
            if (name.endsWith('/')) continue
            val base = name.substringAfterLast('/')
            if (base.startsWith('.')) continue
            if (name.startsWith("__MACOSX/")) continue
            if (base.substringAfterLast('.', "").lowercase() in BOOK_EXTENSIONS) {
                out += prefix + name
            }
        }
        if (depth <= 1) return
        val nested = names.filter { isArchiveName(it) }
            .sortedWith { a, b -> CbzBook.compareNatural(a, b) }
        for (entry in nested) {
            val file = runCatching {
                materialize(archiveFile, entry, cacheDir)
            }.getOrNull() ?: continue
            collectBooks(file, cacheDir, depth - 1, prefix + entry + "/", out)
        }
    }

    /** True when any book file hides anywhere in the tree. */
    fun containsBook(
        archiveFile: File,
        cacheDir: File,
        depth: Int = MAX_DEPTH
    ): Boolean {
        val names = runCatching { CbzBook.allEntryNames(archiveFile) }.getOrNull()
            ?: return false
        for (name in names) {
            if (name.endsWith('/')) continue
            val base = name.substringAfterLast('/')
            if (base.startsWith('.')) continue
            if (name.startsWith("__MACOSX/")) continue
            if (base.substringAfterLast('.', "").lowercase() in BOOK_EXTENSIONS) {
                return true
            }
        }
        if (depth <= 1) return false
        for (entry in names.filter { isArchiveName(it) }) {
            val file = runCatching {
                materialize(archiveFile, entry, cacheDir)
            }.getOrNull() ?: continue
            if (containsBook(file, cacheDir, depth - 1)) return true
        }
        return false
    }

    /** True when any image entry in the tree uses a method this
     *  app cannot serve (anything but STORED/DEFLATED). The
     *  direct route declines such archives — the working-copy
     *  backends fail only the bad pages instead of the whole
     *  book. The top level is checked by the caller's own
     *  [com.novelfa.reader.data.SeekableZip.hasUnservableImageEntries];
     *  this covers the nested levels only. */
    fun hasUnservableImages(archiveFile: File, cacheDir: File, depth: Int = MAX_DEPTH): Boolean {
        val nested = runCatching { CbzBook.allEntryNames(archiveFile) }.getOrNull()
            ?.filter { isArchiveName(it) } ?: return false
        if (depth <= 1) return false
        for (entry in nested) {
            val file = runCatching {
                materialize(archiveFile, entry, cacheDir)
            }.getOrNull() ?: continue
            if (hasUnservableImagesDirect(file)) return true
            if (hasUnservableImages(file, cacheDir, depth - 1)) return true
        }
        return false
    }

    private fun hasUnservableImagesDirect(archiveFile: File): Boolean {
        return try {
            ZipFile(archiveFile).use { zip ->
                zip.entries().toList().any { entry ->
                    !entry.isDirectory && CbzBook.isImageEntry(entry.name) &&
                        entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolves a virtual path to the deepest archive file plus
     * the entry name inside it: `a.zip/b.zip/book.epub` becomes
     * (materialized b.zip, `book.epub`). Folder segments between
     * archives belong to the path inside the current archive
     * (`series/ch01.zip/ch01/001.jpg` materializes the entry
     * `series/ch01.zip`, then reads `ch01/001.jpg` from it).
     * Null when any level cannot be materialized.
     */
    fun resolveFile(
        source: File,
        virtualPath: String,
        cacheDir: File
    ): Pair<File, String>? {
        val parts = virtualPath.split('/')
        if (parts.size < 2) return source to virtualPath
        var current = source
        var pending = ""
        for (segment in parts.dropLast(1)) {
            if (isArchiveName(segment)) {
                current = materialize(current, pending + segment, cacheDir)
                    ?: return null
                pending = ""
            } else {
                pending += "$segment/"
            }
        }
        return current to (pending + parts.last())
    }

    /** A stream over one virtual entry, or null when any level fails. */
    fun openPage(source: File, virtualPath: String, cacheDir: File): InputStream? {
        val (file, entry) = resolveFile(source, virtualPath, cacheDir) ?: return null
        return CbzBook.openEntryStream(file, entry)
    }
}
