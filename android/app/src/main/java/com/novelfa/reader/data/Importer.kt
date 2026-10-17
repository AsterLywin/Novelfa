package com.novelfa.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.novelfa.reader.R
import com.novelfa.reader.parser.EpubParser
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImportException(
    message: String,
    val messageFa: String = "خطا در باز کردن کتاب",
    cause: Throwable? = null
) : Exception(message, cause)

class Importer(private val context: Context, private val db: ReaderDb) {

    suspend fun importEpub(sourceUri: Uri): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val dest = File(booksDir, "$id.epub")
        val tmpDir = File(context.cacheDir, "epub_tmp/$id")
        // Fail fast on absurd picks instead of filling internal storage with
        // the unbounded SAF copy below.
        val pickedSize = queryDisplaySize(sourceUri)
        if (pickedSize != null && pickedSize > MAX_EPUB_BYTES) {
            throw ImportException(
                "EPUB too large: $pickedSize bytes",
                messageFa = "حجم فایل بیش از حد مجاز است."
            )
        }
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                dest.outputStream().use { output ->
                    // Cap the copy even when the provider didn't report a size:
                    // copyTo with a byte budget instead of unbounded.
                    var remaining = MAX_EPUB_BYTES
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        remaining -= n
                        if (remaining < 0) {
                            throw ImportException(
                                "EPUB too large: exceeds $MAX_EPUB_BYTES bytes",
                                messageFa = "حجم فایل بیش از حد مجاز است."
                            )
                        }
                        output.write(buf, 0, n)
                    }
                }
            } ?: throw IOException("Cannot open input stream: $sourceUri")
            tmpDir.mkdirs()
            unzip(dest, tmpDir)
            // Keep the original file name: when the EPUB has no <dc:title>,
            // the parser falls back to the file name — the UUID name would
            // show up as the book title otherwise.
            val displayName = queryDisplayName(sourceUri) ?: "$id.epub"
            val parsed = EpubParser.parse(tmpDir, displayName)
            if (parsed.chapters.isEmpty()) {
                throw ImportException(
                    "EPUB has no readable chapters",
                    messageFa = "این فایل هیچ بخش قابل خواندنی ندارد."
                )
            }
            val coverPath = parsed.coverFile
                ?.takeIf { it.exists() }
                ?.let { CoverStore(context).saveCover(it, dest.absolutePath).absolutePath }
            db.bookDao().insert(
                BookEntity(
                    id = id,
                    title = parsed.title,
                    filePath = dest.absolutePath,
                    coverPath = coverPath,
                    totalChapters = parsed.chapters.size,
                    dateAdded = Instant.now().toString(),
                    originalFileName = displayName
                )
            )
            tmpDir.deleteRecursively()
            id
        } catch (e: CancellationException) {
            // Never swallow coroutine cancellation: clean up and rethrow so the
            // caller's scope cancels properly instead of faking an import error.
            tmpDir.deleteRecursively()
            dest.delete()
            throw e
        } catch (e: ImportException) {
            Log.e("NovelfaImporter", "import failed: ${e.message}", e)
            tmpDir.deleteRecursively()
            dest.delete()
            throw e
        } catch (e: Exception) {
            Log.e("NovelfaImporter", "import failed: ${e.message}", e)
            tmpDir.deleteRecursively()
            dest.delete()
            throw ImportException(
                e.message ?: "import failed",
                messageFa = context.getString(importErrorMessageRes(e)),
                cause = e
            )
        }
    }

    /**
     * Maps an import failure to a localized, user-facing message. The raw
     * technical detail is still surfaced by the caller's diagnostic snackbar;
     * this is the human-readable line. Walks the cause chain because parsers
     * often wrap the original failure.
     */
    private fun importErrorMessageRes(e: Throwable): Int {
        var t: Throwable? = e
        while (t != null) {
            when {
                // NB: ZipException extends IOException — check it first.
                t is ZipException -> return R.string.import_error_not_valid_epub
                t is SecurityException -> return R.string.import_error_access_denied
                t is IOException -> return R.string.import_error_cannot_read
                t is IllegalArgumentException &&
                    t.message?.contains("DOCTYPE", ignoreCase = true) == true ->
                    return R.string.import_error_old_format
                t is IllegalArgumentException &&
                    t.message?.contains("OPF", ignoreCase = true) == true ->
                    return R.string.import_error_bad_structure
            }
            t = t.cause
        }
        return R.string.import_error_generic
    }

    /** Original file name from the SAF picker (null when unavailable). */
    fun displayNameOf(uri: Uri): String? = queryDisplayName(uri)

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    /** Declared size of the SAF pick in bytes (null when unavailable). */
    private fun queryDisplaySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getLong(0).takeIf { it >= 0 } else null
        }
    }.getOrNull()

    private fun unzip(zip: File, outDir: File) {
        // Zip-bomb guard: read the entry count from the End-of-Central-Directory
        // record BEFORE ZipFile() materializes the whole central directory.
        if (centralDirectoryEntryCount(zip) > MAX_ZIP_ENTRIES) {
            throw IOException("Zip entry count exceeds limit")
        }
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            var entryCount = 0
            var totalBytes = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                // Zip-bomb guard: cap entry count and total uncompressed size.
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw IOException("Zip entry count exceeds limit: ${entry.name}")
                }
                val out = File(outDir, entry.name)
                val canonicalOut = out.canonicalPath
                val canonicalDir = outDir.canonicalPath
                // Trailing separator: without it, a sibling dir sharing the
                // string prefix (e.g. "<uuid>-evil") would pass the check.
                if (!canonicalOut.startsWith(canonicalDir + File.separatorChar)) {
                    throw IOException("Zip entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        out.outputStream().use { output ->
                            totalBytes += input.copyTo(output)
                            if (totalBytes > MAX_ZIP_BYTES) {
                                throw IOException("Zip uncompressed size exceeds limit")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads the total entry count from the ZIP End-of-Central-Directory record
     * (last 22 bytes). Returns Int.MAX_VALUE if the EOCD can't be found (e.g.
     * zip64 or comment) — the in-loop guard still applies.
     */
    private fun centralDirectoryEntryCount(zip: File): Int {
        if (zip.length() < 22) return Int.MAX_VALUE
        return try {
            java.io.RandomAccessFile(zip, "r").use { raf ->
                raf.seek(zip.length() - 22)
                val buf = ByteArray(22)
                raf.readFully(buf)
                // EOCD signature 0x06054b50, entry count at offset 10 (LE u16).
                if (buf[0] != 0x50.toByte() || buf[1] != 0x4b.toByte() ||
                    buf[2] != 0x05.toByte() || buf[3] != 0x06.toByte()) {
                    return Int.MAX_VALUE
                }
                ((buf[11].toInt() and 0xFF) shl 8) or (buf[10].toInt() and 0xFF)
            }
        } catch (e: Exception) {
            Int.MAX_VALUE
        }
    }
}

/** Zip-bomb guardrails for EPUB extraction (generous: real books are far smaller). */
private const val MAX_ZIP_ENTRIES = 10_000
private const val MAX_ZIP_BYTES = 500L * 1024 * 1024
/** Upper bound for the initial SAF copy, before unzip/parse get a say. */
private const val MAX_EPUB_BYTES = 500L * 1024 * 1024
