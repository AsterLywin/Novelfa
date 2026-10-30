package com.novelfa.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.novelfa.reader.parser.BookFormat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Import modes, stored as [BookEntity.importMode] strings.
 * COPY is the default for every existing book; IN_PLACE is only
 * ever set by the Settings «در جای خود» switch, for imports
 * started after it is flipped.
 */
const val IMPORT_MODE_COPY = "COPY"
const val IMPORT_MODE_IN_PLACE = "IN_PLACE"

fun BookEntity.isInPlace(): Boolean = importMode == IMPORT_MODE_IN_PLACE

/**
 * Resolving an in-place book to a File the engines can open.
 *
 * The three shapes, and why each is what it is:
 *
 * - Copy books: [BookEntity.filePath] is the stored file. Unchanged.
 * - In-place `file://` books: [BookEntity.filePath] IS the user's
 *   real file. It is opened directly — no copy of any kind, not
 *   even a transient one. This is the only truly zero-copy shape.
 * - In-place `content://` books: Android's ZipFile needs a real
 *   File for random access to archive entries, and a SAF Uri is
 *   not one. So the first open materializes a transient working
 *   copy in cacheDir/inplace/<bookId>/, regenerated whenever the
 *   source's size or modification time changes, and deleted when
 *   the book is removed. It is cache, not a library copy: it is
 *   never in filesDir/books, never backed up, and the system may
 *   evict cacheDir at any time (the next open simply rebuilds it).
 *   TXT/MD/PDF could in principle stream from the Uri, but they
 *   go through the same working copy so every engine keeps one
 *   File-based contract — one code path, not one per format.
 *
 * The pure helpers below ([sourceScheme], [isExternalFile],
 * [workingDir], [metaText], [sniffFormat]) take no Context so
 * they are unit-testable; only [resolve] and the permission
 * helpers touch Android APIs.
 */
object InPlaceSource {

    /** Scheme of a stored source Uri, from the string alone. */
    fun sourceScheme(sourceUri: String?): String? =
        sourceUri?.substringBefore(':')?.lowercase()?.takeIf { it.isNotBlank() }

    /**
     * True only for the one shape whose [BookEntity.filePath] is a
     * file the app must NEVER delete: an in-place book picked by
     * path. Every other shape's filePath is app-owned (a stored
     * copy, or a transient working copy) and may be deleted with
     * the book. This is the guard [CollectionManager] uses.
     */
    fun isExternalFile(book: BookEntity): Boolean =
        book.isInPlace() && sourceScheme(book.sourceUri) == "file"

    fun workingDir(cacheDir: File, bookId: String): File =
        File(cacheDir, "inplace" + File.separatorChar + bookId)

    fun workingFile(cacheDir: File, bookId: String, ext: String): File =
        File(workingDir(cacheDir, bookId), "source.$ext")

    /** Fingerprint of a source revision: size + modification time. */
    fun metaText(size: Long?, lastModified: Long?): String =
        "${size ?: -1}|${lastModified ?: -1}"

    /**
     * Format sniffing from a head sample plus the picker's file
     * name, for register-only (content://) in-place imports where
     * no File exists yet to run [BookFormat.detect] on. This is
     * deliberately approximate: %PDF and PK signatures are strong,
     * zip containers are distinguished by extension (the picker's
     * claim), and plain text is claimed from a mostly-printable
     * strict-UTF-8 sample. The first open re-detects from the
     * working copy with the full [BookFormat.detect], so a
     * mislabelled zip container is corrected at open time, not
     * at import time — the import only needs a plausible format
     * to create the row.
     */
    fun sniffFormat(head: ByteArray, headLen: Int, displayName: String): BookFormat? {
        if (headLen >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()
        ) {
            return BookFormat.PDF
        }
        if (headLen >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return BookFormat.fromExtension(displayName) ?: BookFormat.ZIP
        }
        BookFormat.fromExtension(displayName)?.let { return it }
        if (headLen > 0 && looksLikeText(head, headLen)) return BookFormat.TXT
        return null
    }

    private fun looksLikeText(head: ByteArray, headLen: Int): Boolean {
        var controls = 0
        for (i in 0 until headLen) {
            val b = head[i].toInt() and 0xFF
            if ((b < 0x09 && b != 0x09) || b == 0x7F) controls++
        }
        if (controls * 20 > headLen) return false
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(head, 0, headLen)).toString()
            true
        }.getOrDefault(false)
    }

    /**
     * Is the source still reachable? For `file://`, the file must
     * exist. For `content://`, the persistable read permission
     * must still be held — the permission, not a stream open, is
     * checked, so listing the library never opens every book.
     * Copy books use the old rule (the stored file exists).
     */
    fun isSourceAvailable(context: Context, book: BookEntity): Boolean {
        if (!book.isInPlace()) return book.filePath.isNotBlank() && File(book.filePath).isFile
        val uriStr = book.sourceUri ?: return File(book.filePath).isFile
        return when (sourceScheme(uriStr)) {
            "file" -> {
                val path = Uri.parse(uriStr).path
                path != null && File(path).isFile
            }
            "content" -> context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == uriStr && it.isReadPermission
            }
            else -> false
        }
    }

    /**
     * Resolves [book] to a File the engines can open, materializing
     * the transient working copy for `content://` books when it is
     * missing or the source has changed. Throws IOException when
     * the source is gone — callers show the "file missing" UI
     * instead of opening the reader into an error.
     */
    suspend fun resolve(context: Context, book: BookEntity): File = withContext(Dispatchers.IO) {
        if (!book.isInPlace()) return@withContext File(book.filePath)
        val uriStr = book.sourceUri ?: return@withContext File(book.filePath)
        val uri = Uri.parse(uriStr)
        when (sourceScheme(uriStr)) {
            "file" -> {
                val path = uri.path
                    ?: throw IOException("In-place source has no path: $uriStr")
                val file = File(path)
                if (!file.isFile) throw IOException("In-place source missing: $path")
                file
            }
            "content" -> {
                val dir = workingDir(context.cacheDir, book.id)
                dir.mkdirs()
                val (size, lastModified) = sourceInfo(context, uri)
                val meta = File(dir, ".source-meta")
                val wantedMeta = metaText(size, lastModified)
                val existing = dir.listFiles()
                    ?.firstOrNull { it.isFile && it.name.startsWith("source.") }
                if (existing != null && meta.takeIf { it.isFile }?.readText() == wantedMeta) {
                    return@withContext existing
                }
                val ext = book.filePath.substringAfterLast('.', "")
                    .takeIf { it.isNotBlank() }
                    ?: book.originalFileName?.substringAfterLast('.', "")
                        .orEmpty()
                val working = workingFile(context.cacheDir, book.id, ext)
                existing?.takeIf { it != working }?.delete()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    working.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("Cannot open in-place source: $uriStr")
                meta.writeText(wantedMeta)
                // A mislabelled zip container is corrected here, not
                // at import: the working copy is renamed to the
                // strongly-sniffed extension so the open path (which
                // detects by extension first) routes it correctly.
                // The next resolve finds it via the "source.*" scan
                // above, so this rename happens at most once per
                // source revision.
                val strong = BookFormat.detectStrongContent(working)
                if (strong != null && strong.extension != ext) {
                    val renamed = workingFile(context.cacheDir, book.id, strong.extension)
                    if (working.renameTo(renamed)) return@withContext renamed
                }
                working
            }
            else -> throw IOException("Unsupported in-place source: $uriStr")
        }
    }

    /** Best-effort release of a persistable permission on book removal. */
    fun releasePermission(context: Context, sourceUri: String?) {
        if (sourceUri.isNullOrBlank() || sourceScheme(sourceUri) != "content") return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(sourceUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun sourceInfo(context: Context, uri: Uri): Pair<Long?, Long?> {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@runCatching null to null
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                val mod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else null
                size to mod
            } ?: (null to null)
        }.getOrDefault(null to null)
    }
}
