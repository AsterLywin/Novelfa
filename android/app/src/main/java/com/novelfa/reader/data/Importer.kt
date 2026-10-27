package com.novelfa.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.novelfa.reader.R
import com.novelfa.reader.parser.BookConverter
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.CacheStamp
import com.novelfa.reader.parser.EpubParser
import com.novelfa.reader.parser.PdfParser
import com.novelfa.reader.parser.PdfPasswordRequiredException
import com.novelfa.reader.parser.PdfPasswordWrongException
import com.novelfa.reader.parser.PdfUnsupportedEncryptionException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MIME types the in-app pickers offer. Kept here (not in the UI files)
 * so the library and collections pickers can never drift apart.
 * File managers may still hand the app anything via ACTION_VIEW; the
 * importer itself validates the format and rejects the rest.
 */
val SUPPORTED_BOOK_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "application/epub",
    "text/plain",
    "text/markdown",
    "text/x-markdown",
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
)

class ImportException(
    message: String,
    /** Localized user-facing message, resolved by the thrower via resources. */
    val messageFa: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Password prompt the UI supplies for encrypted PDFs: the file name and
 * whether a previous attempt was wrong, returning the password or null
 * when the user cancels. Null (no UI attached) means only permission-only
 * files — empty user password — can open.
 */
typealias PdfPasswordProvider = suspend (fileName: String, retry: Boolean) -> String?

class Importer(private val context: Context, private val db: ReaderDb) {

    private val recordStore by lazy { ReadingRecordStore(context) }

    /**
     * Applies a stored reading record to a freshly imported book, if one
     * matches by file hash or EPUB identifier. Returns true when the
     * book's reading state was restored. Cheap no-op when the store is
     * empty (no restore has ever brought records in).
     */
    suspend fun applyReadingRecord(bookId: String): Boolean = withContext(Dispatchers.IO) {
        if (recordStore.isEmpty()) return@withContext false
        val dao = db.bookDao()
        val book = dao.getById(bookId) ?: return@withContext false
        if (book.isPrivate) return@withContext false
        val file = File(book.filePath)
        if (!file.isFile) return@withContext false
        val record = recordStore.find(
            sha256OfFile(file), readEpubIdentifier(file)
        ) ?: return@withContext false
        val updated = applyRecordTo(book, record) ?: return@withContext false
        dao.update(updated)
        true
    }

    /**
     * Saves a book's reading record into the store before the book is
     * removed, so re-importing the same file later restores its place.
     * Private books are never recorded (their titles must not leak into
     * restorable data).
     */
    suspend fun captureReadingRecord(bookId: String) = withContext(Dispatchers.IO) {
        val book = db.bookDao().getById(bookId) ?: return@withContext
        if (book.isPrivate) return@withContext
        val file = File(book.filePath)
        if (!file.isFile) return@withContext
        recordStore.upsert(
            readingRecordFor(book, sha256OfFile(file), readEpubIdentifier(file))
        )
    }

    /**
     * Imports one book in any supported format (EPUB, TXT, PDF, Markdown,
     * DOCX). EPUB keeps its zip path; the rest are converted to chapter
     * files by [BookConverter] (into a throwaway dir here — the reader
     * re-converts on open into its own cache). The ORIGINAL file is what
     * gets stored, with its real extension, so backups and re-opens keep
     * working from the source format, not from a lossy conversion.
     */
    suspend fun importBook(
        sourceUri: Uri,
        overwrite: Boolean = false,
        passwordProvider: PdfPasswordProvider? = null
    ): String = withContext(Dispatchers.IO) {
        // When overwriting, find and delete the existing book with the same
        // original file name first.
        if (overwrite) {
            val displayName = displayNameOf(sourceUri)
            if (displayName != null) {
                // The caller (ViewModel) handles the DB; we just need the
                // file replaced. The old book entry is deleted by the ViewModel
                // via removeBook before calling this with overwrite=true.
            }
        }
        val id = UUID.randomUUID().toString()
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val tmpDest = File(booksDir, "$id.tmp")
        // Reader-open cache, filled HERE at import time (not thrown
        // away): the first open reuses these chapters/extraction via
        // the stamp instead of re-parsing the whole book — the DOCX
        // "render takes forever on first open" was this second parse.
        // A failed import deletes it again (see the catch blocks);
        // a successful import leaves it for openParsed, and the
        // startup prune removes it if the book row ever disappears.
        val openCacheDir = File(context.filesDir, "epub_open/$id")
        // Assigned inside the try (extension known only after sniffing);
        // the failure paths below delete whichever file got created.
        var dest: File? = null
        // Fail fast on absurd picks instead of filling internal storage with
        // the unbounded SAF copy below.
        val pickedSize = queryDisplaySize(sourceUri)
        if (pickedSize != null && pickedSize > MAX_BOOK_BYTES) {
            throw ImportException(
                "Book too large: $pickedSize bytes",
                messageFa = context.getString(R.string.import_error_too_large)
            )
        }
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tmpDest.outputStream().use { output ->
                    // Cap the copy even when the provider didn't report a size:
                    // copyTo with a byte budget instead of unbounded.
                    var remaining = MAX_BOOK_BYTES
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        remaining -= n
                        if (remaining < 0) {
                            throw ImportException(
                                "Book too large: exceeds $MAX_BOOK_BYTES bytes",
                                messageFa = context.getString(R.string.import_error_too_large)
                            )
                        }
                        output.write(buf, 0, n)
                    }
                }
            } ?: throw IOException("Cannot open input stream: $sourceUri")
            // Keep the original file name: when a book has no internal
            // title, the parser falls back to the file name — the UUID
            // name would show up as the book title otherwise.
            val displayName = queryDisplayName(sourceUri) ?: id
            // Strong content signatures win over the extension: a
            // mislabelled file (DOCX named .pdf, EPUB named .docx)
            // must parse as what it IS, not fail as what it claims.
            // Plain-text formats have no signature, so their extension
            // still decides (see BookFormat.detectStrongContent).
            val format = BookFormat.detectStrongContent(tmpDest)
                ?: BookFormat.fromExtension(displayName)
                ?: BookFormat.detect(tmpDest)
                ?: throw ImportException(
                    "Unsupported book format: $displayName",
                    messageFa = context.getString(R.string.import_error_unsupported_format)
                )
            dest = File(booksDir, "$id.${format.extension}")
            val destFile = dest!!
            if (!tmpDest.renameTo(destFile)) {
                tmpDest.copyTo(destFile, overwrite = true)
                tmpDest.delete()
            }
            openCacheDir.mkdirs()
            val chapterFallback: (Int) -> String = { n ->
                context.getString(R.string.chapter_fallback, n)
            }
            val parsed = if (format == BookFormat.EPUB) {
                unzip(destFile, openCacheDir)
                EpubParser.parse(openCacheDir, displayName, chapterFallback)
            } else if (format == BookFormat.PDF) {
                // Password-protected PDFs are stored DECRYPTED (see
                // resolvePdfDecryption): the reader then opens them like
                // any plain PDF, and the password itself is never stored.
                val decrypted = resolvePdfDecryption(
                    destFile.readBytes(), displayName, passwordProvider
                )
                if (decrypted != null) destFile.writeBytes(decrypted)
                try {
                    BookConverter.parse(destFile, openCacheDir, chapterFallback, displayName)
                } catch (e: Exception) {
                    throw ImportException(
                        e.message ?: "parse failed",
                        messageFa = context.getString(parseErrorMessageRes(e)),
                        cause = e
                    )
                }
            } else {
                // Converted formats: parse errors get a format-aware
                // user-facing message instead of the EPUB-only texts.
                try {
                    BookConverter.parse(destFile, openCacheDir, chapterFallback, displayName)
                } catch (e: Exception) {
                    throw ImportException(
                        e.message ?: "parse failed",
                        messageFa = context.getString(parseErrorMessageRes(e)),
                        cause = e
                    )
                }
            }
            if (parsed.chapters.isEmpty()) {
                throw ImportException(
                    "Book has no readable chapters",
                    messageFa = context.getString(R.string.import_error_no_readable_sections)
                )
            }
            // Belt and braces on top of the titleFallback above: a
            // parser must never store the storage UUID as the book
            // title when the original file name is known. The generated
            // cover below uses the same corrected title, so it never
            // renders a UUID either.
            val bookTitle = if (isUuidLikeTitle(parsed.title)) {
                File(displayName).nameWithoutExtension.takeIf { it.isNotBlank() } ?: parsed.title
            } else {
                parsed.title
            }
            var coverPath = parsed.coverFile
                ?.takeIf { it.exists() }
                ?.let { CoverStore(context).saveCover(it, destFile.absolutePath).absolutePath }
            if (coverPath == null) {
                // Coverless book (plain-text formats carry no art, and
                // some EPUBs ship without a cover): generate a
                // typographic cover instead of a blank box. A failure
                // here must never fail the import — the book simply
                // stays coverless, exactly as before.
                coverPath = runCatching {
                    val tmp = GeneratedCover.generate(
                        context, bookTitle, destFile.absolutePath
                    ) ?: return@runCatching null
                    try {
                        CoverStore(context).saveCover(tmp, destFile.absolutePath).absolutePath
                    } finally {
                        tmp.delete()
                    }
                }.getOrNull()
            }
            db.bookDao().insert(
                BookEntity(
                    id = id,
                    title = bookTitle,
                    filePath = destFile.absolutePath,
                    coverPath = coverPath,
                    totalChapters = parsed.chapters.size,
                    dateAdded = Instant.now().toString(),
                    originalFileName = displayName
                )
            )
            // The cache above is complete and the stored file is final
            // (decrypted PDFs were already rewritten): stamp it so the
            // first open trusts it instead of re-parsing.
            CacheStamp.writeStamp(openCacheDir, destFile)
            id
        } catch (e: CancellationException) {
            // Never swallow coroutine cancellation: clean up and rethrow so the
            // caller's scope cancels properly instead of faking an import error.
            openCacheDir.deleteRecursively()
            dest?.delete()
            tmpDest.delete()
            throw e
        } catch (e: ImportException) {
            Log.e("NovelfaImporter", "import failed: ${e.message}", e)
            openCacheDir.deleteRecursively()
            dest?.delete()
            tmpDest.delete()
            throw e
        } catch (e: Exception) {
            Log.e("NovelfaImporter", "import failed: ${e.message}", e)
            openCacheDir.deleteRecursively()
            dest?.delete()
            tmpDest.delete()
            throw ImportException(
                e.message ?: "import failed",
                messageFa = context.getString(importErrorMessageRes(e)),
                cause = e
            )
        }
    }

    /**
     * Decrypts an encrypted PDF for storage, or returns null when the
     * file is not encrypted at all. The first attempt uses no password:
     * permission-only files open right there (level 1, no prompt). When
     * a password is required, the UI provider is asked up to three times
     * (retry flag set after a wrong password); cancelling, or three
     * wrong answers, fails the import with a localized message.
     */
    private suspend fun resolvePdfDecryption(
        data: ByteArray,
        displayName: String,
        provider: PdfPasswordProvider?
    ): ByteArray? {
        try {
            return PdfParser.decryptToPlainPdf(data, null)
        } catch (e: PdfUnsupportedEncryptionException) {
            throw ImportException(
                e.message ?: "unsupported encryption",
                messageFa = context.getString(R.string.import_error_pdf_unsupported_encryption),
                cause = e
            )
        } catch (e: PdfPasswordRequiredException) {
            // Fall through to the prompt loop below.
        }
        val prompt = provider ?: throw ImportException(
            "PDF password required",
            messageFa = context.getString(R.string.import_error_pdf_password_required)
        )
        var retry = false
        repeat(3) {
            val pw = prompt(displayName, retry)
                ?: throw ImportException(
                    "PDF password required",
                    messageFa = context.getString(R.string.import_error_pdf_password_required)
                )
            try {
                return PdfParser.decryptToPlainPdf(data, pw)
                    ?: throw ImportException(
                        "PDF decryption failed",
                        messageFa = context.getString(R.string.import_error_damaged)
                    )
            } catch (e: PdfPasswordWrongException) {
                retry = true
            } catch (e: PdfUnsupportedEncryptionException) {
                throw ImportException(
                    e.message ?: "unsupported encryption",
                    messageFa = context.getString(R.string.import_error_pdf_unsupported_encryption),
                    cause = e
                )
            }
        }
        throw ImportException(
            "PDF password wrong",
            messageFa = context.getString(R.string.import_error_pdf_password_wrong)
        )
    }

    /**
     * User-facing message for a converted-format parse failure. Kept
     * separate from [importErrorMessageRes] (the EPUB path) so a corrupt
     * DOCX is never reported as "not a valid EPUB".
     */
    private fun parseErrorMessageRes(e: Throwable): Int {
        var t: Throwable? = e
        while (t != null) {
            when {
                t.message?.contains("encrypted", ignoreCase = true) == true ->
                    return R.string.import_error_pdf_encrypted
                t.message?.contains("too many pages", ignoreCase = true) == true ->
                    return R.string.import_error_pdf_too_many_pages
                t is ZipException -> return R.string.import_error_damaged
                t is SecurityException -> return R.string.import_error_access_denied
                t is IOException -> return R.string.import_error_cannot_read
            }
            t = t.cause
        }
        return R.string.import_error_damaged
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
                t.message?.contains("encrypted", ignoreCase = true) == true ->
                    return R.string.import_error_pdf_encrypted
                t.message?.contains("Unsupported book format", ignoreCase = true) == true ->
                    return R.string.import_error_unsupported_format
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

    /** Declared size of the SAF pick in bytes (null when unavailable). */
    fun displaySizeOf(uri: Uri): Long? = queryDisplaySize(uri)

    /**
     * Streaming SHA-256 of a picked file (null on any failure): used
     * for content-based duplicate detection, so the same book under a
     * new name is still a duplicate and an updated book under the old
     * name is not. Never loads the file into memory.
     */
    suspend fun sha256OfUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            } ?: return@runCatching null
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull() ?: run {
        // file:// intents from a file manager carry no SAF display
        // name: fall back to the path's last segment, which IS the
        // original file name. (For content:// URIs the last segment
        // is usually a numeric document id, so it is not used here —
        // a missing name there still falls back to the UUID at the
        // call site, as before.)
        if (uri.scheme == "file") {
            uri.path?.let { File(it).name }?.takeIf { it.isNotBlank() }
        } else null
    }

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
     * Reads the total entry count from the ZIP End-of-Central-Directory
     * record. The EOCD is NOT necessarily in the last 22 bytes: a zip
     * comment (up to 64 KiB) sits after it, so the tail is scanned
     * backwards for the signature. Returns 0 when the EOCD can't be
     * found or the count is 0xFFFF (zip64) — 0 defers to the in-loop
     * guard, which is the correct check there. The old code returned
     * Int.MAX_VALUE in exactly these cases, so the pre-check above
     * rejected EVERY commented or zip64 EPUB as "too many entries".
     */
    private fun centralDirectoryEntryCount(zip: File): Int {
        if (zip.length() < 22) return 0
        return try {
            val tailSize = minOf(zip.length(), 22L + 65535L).toInt()
            val buf = ByteArray(tailSize)
            java.io.RandomAccessFile(zip, "r").use { raf ->
                raf.seek(zip.length() - tailSize)
                raf.readFully(buf)
            }
            // Scan backwards for the EOCD signature 0x06054b50. A
            // signature found inside the comment itself must not win:
            // the real EOCD is the one whose comment-length field ends
            // exactly at the end of the file.
            var i = tailSize - 22
            while (i >= 0) {
                if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4b.toByte() &&
                    buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()
                ) {
                    val commentLen = ((buf[i + 21].toInt() and 0xFF) shl 8) or
                        (buf[i + 20].toInt() and 0xFF)
                    if (i + 22 + commentLen == tailSize) {
                        val count = ((buf[i + 11].toInt() and 0xFF) shl 8) or
                            (buf[i + 10].toInt() and 0xFF)
                        // zip64: the real count lives in the zip64 EOCD.
                        return if (count == 0xFFFF) 0 else count
                    }
                }
                i--
            }
            0
        } catch (e: Exception) {
            0
        }
    }
}

/** Zip-bomb guardrails for EPUB/DOCX extraction (generous: real books are far smaller). */
private const val MAX_ZIP_ENTRIES = 10_000
private const val MAX_ZIP_BYTES = 500L * 1024 * 1024
/** Upper bound for the initial SAF copy, before unzip/parse get a say. */
private const val MAX_BOOK_BYTES = 500L * 1024 * 1024
