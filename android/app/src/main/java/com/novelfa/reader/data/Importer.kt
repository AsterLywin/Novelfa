package com.novelfa.reader.data

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.novelfa.reader.R
import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.CacheStamp
import com.novelfa.reader.parser.PdfParser
import com.novelfa.reader.parser.engine.CbzEngine
import com.novelfa.reader.parser.engine.DocxEngine
import com.novelfa.reader.parser.engine.EpubEngine
import com.novelfa.reader.parser.engine.TxtEngine
import com.novelfa.reader.parser.engine.ZipEngine
import com.novelfa.reader.parser.engine.ZipKind
import com.novelfa.reader.parser.PdfPasswordRequiredException
import com.novelfa.reader.parser.PdfPasswordWrongException
import com.novelfa.reader.parser.PdfUnsupportedEncryptionException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

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
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    // CBZ/ZIP image books: providers report these under several
    // types (a .cbz often arrives as application/zip), so all four
    // are listed — the importer itself still validates the format
    // and rejects anything that is not a real book archive.
    "application/zip",
    "application/x-zip-compressed",
    "application/x-cbz",
    "application/vnd.comicbook+zip"
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

class Importer(
    private val context: Context,
    private val db: ReaderDb,
    /** Settings toggle (null in unit tests = fast import ON, see below). */
    private val prefs: PrefsStore? = null
) {

    private val recordStore by lazy { ReadingRecordStore(context) }

    /**
     * Background scope for deferred generated covers (fast import only):
     * deliberately NOT a child of the import coroutine — the import dialog
     * must close as soon as the book is saved, while the cover still
     * builds and lands via the library Flow. Best-effort only: a failed
     * or interrupted cover job leaves the book coverless, never failing
     * an import that already succeeded.
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * SHA-256 of the bytes actually copied, keyed by the new book id
     * (fast import only). Lets [applyReadingRecord] and the PDF
     * record-chapter lookup reuse the copy pass instead of re-reading
     * the whole stored file. Removed on use by [applyReadingRecord];
     * falls back to a file hash when absent (old imports, a new
     * Importer instance), so correctness never depends on this map.
     */
    private val importHashes = ConcurrentHashMap<String, String>()

    /** Settings toggle, read once per import (default ON when no prefs). */
    private suspend fun fastImportEnabled(): Boolean =
        prefs?.fastImportNow() ?: true

    /** In-place import toggle, read once per import (default OFF when no prefs). */
    private suspend fun inPlaceImportEnabled(): Boolean =
        prefs?.inPlaceImportNow() ?: false

    /** Best-effort release of an in-place book's persistable permission (see InPlaceSource). */
    fun releaseInPlacePermission(sourceUri: String?) {
        InPlaceSource.releasePermission(context, sourceUri)
    }

    /**
     * Re-points an in-place book at a new location of the same
     * file. Returns false (and changes nothing) when the book is
     * not in-place, the new Uri cannot be read, or a `content://`
     * Uri refuses a persistable permission — the old source stays
     * in place in all of those cases, so a failed relink never
     * strands a book with a dead source. On success the old
     * working copy and chapter cache are dropped, the old
     * persistable permission is released, and the next open
     * rebuilds everything from the new location.
     */
    suspend fun relinkBook(bookId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val book = db.bookDao().getById(bookId) ?: return@withContext false
        if (!book.isInPlace()) return@withContext false
        if (uri.scheme == "content") {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.isSuccess
            if (!persisted) return@withContext false
        }
        val newPath = when (uri.scheme) {
            "file" -> uri.path ?: return@withContext false
            "content" -> {
                val ext = (displayNameOf(uri) ?: "").substringAfterLast('.', "")
                InPlaceSource.workingFile(context.cacheDir, bookId, ext).absolutePath
            }
            else -> return@withContext false
        }
        val oldSource = book.sourceUri
        db.bookDao().update(
            book.copy(sourceUri = uri.toString(), filePath = newPath)
        )
        runCatching {
            InPlaceSource.workingDir(context.cacheDir, bookId).deleteRecursively()
        }
        runCatching {
            File(context.filesDir, "epub_open" + File.separatorChar + bookId)
                .takeIf { it.isDirectory }?.deleteRecursively()
        }
        if (oldSource != null && oldSource != uri.toString()) {
            InPlaceSource.releasePermission(context, oldSource)
        }
        true
    }

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
        // The import hash is consulted BEFORE the file check: a
        // `content://` in-place book has no working copy yet (it is
        // materialized on first open), so requiring the file here
        // would silently drop its saved reading record on
        // re-import. The file hash is only the fallback when no
        // import hash exists; readEpubIdentifier is null-safe on
        // a missing file (it catches the ZipFile failure).
        // Remove on use: the hash is only needed for the restore right
        // after import, so the map never grows past imports whose
        // restore has not run yet (normally zero or one).
        val hash = importHashes.remove(bookId)
            ?: if (!file.isFile) return@withContext false else sha256OfFile(file)
        val record = recordStore.find(
            hash, readEpubIdentifier(file)
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
     * DOCX, CBZ, ZIP). Every format is imported through its own
     * rendering engine (see parser/engine): EPUB, DOCX and book-carrying
     * ZIPs contribute metadata only at import (title, a chapter count
     * carried by the container, a single-entry cover) and are fully
     * parsed at first open; TXT/Markdown are parsed here because their
     * single-pass text parse IS the reader's open cache; PDF and
     * image archives were already lazy. The ORIGINAL file is what
     * gets stored, with its real extension, so backups and re-opens keep
     * working from the source format, not from a lossy conversion.
     */
    suspend fun importBook(
        sourceUri: Uri,
        overwrite: Boolean = false,
        passwordProvider: PdfPasswordProvider? = null,
        // Stage reporter for the import dialog (null = no reporting):
        // the dialog shows which step a stuck import sits in, so a
        // "never finishes" report names the step instead of guessing.
        // Values: "copy", "decrypt", "validate", "save"; null resets.
        onStage: ((String?) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        // Captured once: the blocking steps below (file copy, native
        // open, hashing) have no suspension point, so without an
        // explicit check the dialog's Cancel could not interrupt them
        // at all — the job was cancelled but the work ran on.
        val ioCtx = currentCoroutineContext()
        // Read once per import: the toggle only affects imports started
        // after it changes; a book already importing keeps its mode.
        val fastImport = fastImportEnabled()
        onStage?.invoke(null)
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
        // In-place mode, read once per import like fastImport: the
        // toggle only affects imports started after it changes.
        val wantInPlace = inPlaceImportEnabled()
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val tmpDest = File(booksDir, "$id.tmp")
        // Reader-open cache. Only TXT/Markdown are filled HERE at
        // import time (their parse output IS this cache, so the first
        // open reuses it via the stamp). For EPUB, DOCX and
        // book-carrying ZIPs this directory is only scratch for a
        // single-entry cover at import; the first open fills it by
        // parsing through the format's engine, and the stamp is
        // written by openParsed, not here. A failed import deletes
        // it again (see the catch blocks); the startup prune removes
        // it if the book row ever disappears.
        val openCacheDir = File(context.filesDir, "epub_open/$id")
        // Assigned inside the try (extension known only after sniffing);
        // the failure paths below delete whichever file got created.
        var dest: File? = null
        // Lazy-PDF chapter count from a saved reading record, when
        // one exists for this exact content (see the PDF branch).
        var recordChapters: Int? = null
        // Native image-book page count (CBZ/ZIP): known at import
        // from the archive listing alone, so unlike lazy PDFs the
        // row carries the real count from the start.
        var imageBookPages: Int? = null
        // Fail fast on absurd picks instead of filling internal storage with
        // the unbounded SAF copy below.
        val pickedSize = queryDisplaySize(sourceUri)
        if (pickedSize != null && pickedSize > MAX_BOOK_BYTES) {
            throw ImportException(
                "Book too large: $pickedSize bytes",
                messageFa = context.getString(R.string.import_error_too_large)
            )
        }
        // In-place `content://` import: take the persistable read
        // permission FIRST, because the register-only path below
        // stores the Uri and re-opens it on every future read.
        // If the picker did not grant a persistable permission
        // (some providers don't), this is not an error — the import
        // simply falls through to the copy flow, so the book still
        // imports, just as a stored copy.
        if (wantInPlace && sourceUri.scheme == "content") {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.isSuccess
            if (persisted) {
                return@withContext importInPlaceContent(sourceUri, id, fastImport, ioCtx, onStage)
            }
        }
        // In-place `file://` import: the real file is the book.
        // No copy is made at all — the branches below run against
        // it directly (metadata only; they never write to it, with
        // one guarded exception in the PDF branch). Encrypted PDFs
        // are the exception: the reader has no password dialog at
        // open time, so today they are stored decrypted — an
        // in-place import of one falls back to the copy flow.
        // The probe costs one full read for file:// PDFs; the PDF
        // branch pays the same read again for its decrypt check,
        // so this is one extra read, only for PDFs, in exchange
        // for never writing decrypted bytes over the user's file.
        var inPlaceFile: File? = null
        var inPlacePdfBytes: ByteArray? = null
        if (wantInPlace && sourceUri.scheme == "file") {
            val realFile = sourceUri.path?.let { File(it) }?.takeIf { it.isFile }
            if (realFile != null) {
                val looksPdf = BookFormat.detectStrongContent(realFile) == BookFormat.PDF
                val encrypted = if (looksPdf) {
                    inPlacePdfBytes = runCatching { realFile.readBytes() }.getOrNull()
                    inPlacePdfBytes?.let { PdfParser.maybeEncrypted(it) } ?: true
                } else false
                if (!encrypted) inPlaceFile = realFile
                if (encrypted) inPlacePdfBytes = null
            }
        }
        // SHA-256 of the copied bytes (fast import only): computed
        // WHILE copying, so the duplicate/record/restore checks below
        // never re-read the file. OFF = null, preserving the old
        // re-read behaviour exactly.
        var copyHash: String? = null
        try {
            onStage?.invoke("copy")
            if (inPlaceFile != null) {
                // In-place file://: no copy at all. The hash (fast
                // import only) is computed from the real file, for
                // the same duplicate/record/restore checks the copy
                // pass served — one read, no write.
                copyHash = if (fastImport) {
                    runCatching { sha256OfFile(inPlaceFile) }.getOrNull()
                } else null
                if (copyHash != null) importHashes[id] = copyHash
            } else {
                val copyDigest = if (fastImport) MessageDigest.getInstance("SHA-256") else null
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    tmpDest.outputStream().use { output ->
                        // Cap the copy even when the provider didn't report a size:
                        // copyTo with a byte budget instead of unbounded.
                        var remaining = MAX_BOOK_BYTES
                        var zeroReads = 0
                        val buf = ByteArray(8192)
                        while (true) {
                            ioCtx.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) {
                                // A provider that returns 0 forever (never
                                // data, never EOF) used to spin this loop
                                // eternally with no progress at all.
                                if (++zeroReads > 10000) {
                                    throw IOException("stalled input stream: $sourceUri")
                                }
                                continue
                            }
                            zeroReads = 0
                            remaining -= n
                            if (remaining < 0) {
                                throw ImportException(
                                    "Book too large: exceeds $MAX_BOOK_BYTES bytes",
                                    messageFa = context.getString(R.string.import_error_too_large)
                                )
                            }
                            output.write(buf, 0, n)
                            copyDigest?.update(buf, 0, n)
                        }
                    }
                } ?: throw IOException("Cannot open input stream: $sourceUri")
                copyHash = copyDigest?.digest()?.joinToString("") { "%02x".format(it) }
                if (copyHash != null) importHashes[id] = copyHash
            }
            // Keep the original file name: when a book has no internal
            // title, the parser falls back to the file name — the UUID
            // name would show up as the book title otherwise.
            // (For file:// in-place books the real file name is
            // known directly; the SAF query returns nothing for a
            // file Uri, so the old code would have stored the UUID.)
            val displayName = inPlaceFile?.name ?: queryDisplayName(sourceUri) ?: id
            // Strong content signatures win over the extension: a
            // mislabelled file (DOCX named .pdf, EPUB named .docx)
            // must parse as what it IS, not fail as what it claims.
            // Plain-text formats have no signature, so their extension
            // still decides (see BookFormat.detectStrongContent).
            val sniffTarget = inPlaceFile ?: tmpDest
            val format = BookFormat.detectStrongContent(sniffTarget)
                ?: BookFormat.fromExtension(displayName)
                ?: BookFormat.detect(sniffTarget)
                ?: throw ImportException(
                    "Unsupported book format: $displayName",
                    messageFa = context.getString(R.string.import_error_unsupported_format)
                )
            dest = inPlaceFile ?: File(booksDir, "$id.${format.extension}")
            val destFile = dest!!
            if (inPlaceFile == null && !tmpDest.renameTo(destFile)) {
                tmpDest.copyTo(destFile, overwrite = true)
                tmpDest.delete()
            }
            openCacheDir.mkdirs()
            val chapterFallback: (Int) -> String = { n ->
                context.getString(R.string.chapter_fallback, n)
            }
            // Chapter count carried by a metadata-only engine
            // (EPUB spine count, DOCX heading estimate). Null means
            // the count is unknown until first open, as for lazy PDFs.
            var metadataChapterCount: Int? = null
            // True when a ZIP carries a book (not images): it is
            // neither a native image book nor a converted-cache book
            // at import — its first open extracts the chosen book
            // inside and parses it through that book's engine.
            var zipIsBook = false
            val parsed = if (format == BookFormat.EPUB) {
                // Engine metadata only: title + spine count + one
                // cover entry, straight out of the ZIP. The old path
                // unzipped the whole book and parsed every spine
                // item's title here; that was the long step for big
                // EPUBs, and the dialog sat on "copy" all through
                // it. The full parse now happens at first open,
                // where the reader already unzipped on a cache miss.
                onStage?.invoke("parse")
                val meta = try {
                    EpubEngine.metadata(destFile, displayName, openCacheDir) {
                        ioCtx.ensureActive()
                    }
                } catch (e: Exception) {
                    throw ImportException(
                        e.message ?: "invalid EPUB: $displayName",
                        messageFa = context.getString(R.string.import_error_bad_structure),
                        cause = e
                    )
                }
                metadataChapterCount = meta.chapterCount
                ParsedBook(title = meta.title, chapters = emptyList(), coverFile = meta.coverFile)
            } else if (format == BookFormat.PDF) {
                // LAZY import (the ReadEra/ComicScreen rule): import
                // time must not grow with the book. The old path ran
                // the full text extraction (every page) and, for
                // scanned books, rendered every page to JPEG here —
                // on a phone that is minutes for a big PDF, which is
                // exactly the "import never finishes" users report,
                // and the Cancel button could not interrupt the
                // blocking parse at all (no suspension point inside
                // it). Now import only:
                //  - decrypts when needed (fast crypto; the reader
                //    opens the stored file like any plain PDF after),
                //  - validates with a cheap native open (PdfRenderer
                //    page count — Pdfium, full spec), failing fast
                //    on files it cannot open,
                // and defers extraction + rendering to first open:
                // openParsed re-parses on a missing cache stamp, and
                // the first progress save persists the real chapter
                // count to the row. The book is in the library in
                // seconds; the first open pays the conversion cost
                // behind the reader's loading indicator instead.
                // Password-protected PDFs are stored DECRYPTED (see
                // resolvePdfDecryption): the reader then opens them
                // like any plain PDF, and the password itself is
                // never stored.
                ioCtx.ensureActive()
                onStage?.invoke("decrypt")
                val decrypted = resolvePdfDecryption(
                    inPlacePdfBytes ?: destFile.readBytes(), displayName, passwordProvider
                ) { ioCtx.ensureActive() }
                if (decrypted != null) {
                    // The pre-check above guarantees this never fires
                    // for in-place files (encrypted PDFs fall back
                    // to the copy flow), but if it ever did, writing
                    // decrypted bytes over the user's own file would
                    // be silent data modification — fail loudly
                    // instead of doing it.
                    if (inPlaceFile != null) {
                        throw ImportException(
                            "Encrypted PDF cannot be read in place",
                            messageFa = context.getString(R.string.import_in_place_encrypted_pdf)
                        )
                    }
                    destFile.writeBytes(decrypted)
                }
                ioCtx.ensureActive()
                onStage?.invoke("validate")
                pdfPageCountOrThrow(destFile, displayName)
                // A re-imported file keeps its saved position: the
                // record is keyed by content hash, and the same
                // content parses to the same chapters, so the
                // record's chapter count IS this book's chapter
                // count — the row carries it until the first open
                // replaces it with the freshly parsed count. Without
                // a record the count stays 0 (unknown) until then.
                recordChapters = if (fastImport) {
                    // Reuse the copy-pass hash: no second full read.
                    copyHash?.let { recordStore.find(it, null)?.totalChapters }
                        ?.takeIf { it > 0 }
                } else {
                    runCatching { sha256OfFile(destFile) }
                        .getOrNull()?.let { recordStore.find(it, null)?.totalChapters }
                        ?.takeIf { it > 0 }
                }
                ParsedBook(
                    title = File(displayName).nameWithoutExtension
                        .takeIf { it.isNotBlank() } ?: displayName,
                    chapters = emptyList(),
                    coverFile = null
                )
            } else if (format == BookFormat.CBZ) {
                // Native image book (manhwa/manga/comic): import only
                // lists the archive and extracts the first page as
                // the cover — no full extraction, no conversion, no
                // per-page work. The viewer decodes pages on demand
                // from the stored archive. A corrupt archive fails
                // here via the generic catch (localized import
                // error); an archive with no images fails cleanly
                // with the no-readable-sections message, exactly
                // like a text-less book used to.
                ioCtx.ensureActive()
                onStage?.invoke("validate")
                val meta = try {
                    CbzEngine.metadata(destFile, displayName, openCacheDir) {
                        ioCtx.ensureActive()
                    }
                } catch (e: Exception) {
                    throw ImportException(
                        e.message ?: "invalid comic archive: $displayName",
                        messageFa = context.getString(R.string.import_error_bad_structure),
                        cause = e
                    )
                }
                imageBookPages = meta.chapterCount
                ParsedBook(title = meta.title, chapters = emptyList(), coverFile = meta.coverFile)
            } else if (format == BookFormat.ZIP) {
                // Archive engine: a ZIP is classified before any
                // row is written. Image-only ZIPs are comics (same
                // as CBZ above, first page as cover). ZIPs carrying
                // a book are that book: the row is created with an
                // unknown chapter count and the first open extracts
                // the book inside through its own engine. ZIPs with
                // several books keep the archive title; the reader
                // asks which book to open instead of guessing.
                ioCtx.ensureActive()
                onStage?.invoke("validate")
                val meta = try {
                    ZipEngine.metadata(destFile, displayName, openCacheDir) {
                        ioCtx.ensureActive()
                    }
                } catch (e: Exception) {
                    throw ImportException(
                        e.message ?: "invalid archive: $displayName",
                        messageFa = context.getString(R.string.import_error_no_readable_sections),
                        cause = e
                    )
                }
                when (ZipEngine.classify(destFile)) {
                    is ZipKind.ImageOnly -> imageBookPages = meta.chapterCount
                    else -> zipIsBook = true
                }
                ParsedBook(title = meta.title, chapters = emptyList(), coverFile = meta.coverFile)
            } else if (format == BookFormat.DOCX) {
                // Engine metadata only: title from core.xml and a
                // section estimate from a single scan of
                // document.xml for Heading1/Heading2 styles. The old
                // path unzipped every media file and DOM-parsed the
                // whole body here; the full parse now happens at
                // first open and corrects the row then.
                onStage?.invoke("parse")
                val meta = try {
                    DocxEngine.metadata(destFile, displayName, openCacheDir) {
                        ioCtx.ensureActive()
                    }
                } catch (e: Exception) {
                    throw ImportException(
                        e.message ?: "parse failed",
                        messageFa = context.getString(parseErrorMessageRes(e)),
                        cause = e
                    )
                }
                metadataChapterCount = meta.chapterCount
                ParsedBook(title = meta.title, chapters = emptyList(), coverFile = null)
            } else {
                // TXT/Markdown through their engine: a single-pass
                // text parse whose output IS the reader's open
                // cache, so parsing here is not double work.
                // Cancellation must NOT be wrapped into an import
                // error here: it used to be, so pressing Cancel
                // mid-parse showed a failure snackbar instead of
                // just stopping the import.
                onStage?.invoke("parse")
                try {
                    TxtEngine.open(destFile, openCacheDir, chapterFallback, displayName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw ImportException(
                        e.message ?: "parse failed",
                        messageFa = context.getString(parseErrorMessageRes(e)),
                        cause = e
                    )
                }
            }
            // Lazy PDFs, native image books and metadata-only books
            // deliberately have no chapters yet (PDFs and EPUB/DOCX/
            // book-ZIPs are built on first open; image books never
            // have chapters, their pages ARE the book). Only the
            // TXT/Markdown path must have parsed chapters here.
            val isNativeBook = format == BookFormat.PDF ||
                format == BookFormat.CBZ || format == BookFormat.ZIP
            val isMetadataOnly = format == BookFormat.EPUB ||
                format == BookFormat.DOCX || zipIsBook
            if (!isNativeBook && !isMetadataOnly && parsed.chapters.isEmpty()) {
                throw ImportException(
                    "Book has no readable chapters",
                    messageFa = context.getString(R.string.import_error_no_readable_sections)
                )
            }
            ioCtx.ensureActive()
            onStage?.invoke("save")
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
            if (coverPath == null && !fastImport) {
                // Old behaviour (toggle OFF): coverless book
                // (plain-text formats carry no art, and some EPUBs ship
                // without a cover) gets a typographic cover BEFORE the
                // book is saved, so the import dialog waits for it. A
                // failure here must never fail the import — the book
                // simply stays coverless, exactly as before.
                // A Cancel pressed just before this step must still
                // stop the import instead of saving the book anyway.
                ioCtx.ensureActive()
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
            // Fast import: a coverless book is saved coverless NOW and
            // its generated cover is built in the background below, so
            // the import dialog never waits for it. The library Flow
            // re-emits when the row is updated, so the cover appears
            // on its own a moment later.
            db.bookDao().insert(
                BookEntity(
                    id = id,
                    title = bookTitle,
                    filePath = destFile.absolutePath,
                    coverPath = coverPath,
                    totalChapters = imageBookPages ?: recordChapters
                        ?: metadataChapterCount ?: parsed.chapters.size,
                    dateAdded = Instant.now().toString(),
                    originalFileName = displayName,
                    sourceUri = inPlaceFile?.let { sourceUri.toString() },
                    importMode = if (inPlaceFile != null) IMPORT_MODE_IN_PLACE else IMPORT_MODE_COPY
                )
            )
            if (fastImport && coverPath == null) {
                // Deferred generated cover (see backgroundScope): the
                // book is already in the library, so this job only
                // fills its cover. It re-reads the row and writes only
                // when the book still exists and is still coverless —
                // deleting the book first, or a cover arriving by
                // another path, must never be overwritten.
                val deferredTitle = bookTitle
                val deferredPath = destFile.absolutePath
                backgroundScope.launch {
                    runCatching {
                        val tmp = GeneratedCover.generate(
                            context, deferredTitle, deferredPath
                        ) ?: return@runCatching
                        try {
                            val saved = CoverStore(context)
                                .saveCover(tmp, deferredPath).absolutePath
                            val current = db.bookDao().getById(id)
                            if (current != null && current.coverPath == null) {
                                db.bookDao().update(current.copy(coverPath = saved))
                            }
                        } finally {
                            tmp.delete()
                        }
                    }
                }
            }
            // Only TXT/Markdown have a complete converted cache at
            // import: stamp it so the first open trusts it instead of
            // re-parsing. Every other format is lazy at import — the
            // missing stamp is exactly what makes the first open
            // parse it through its engine. (Previously EPUB and DOCX
            // were stamped here because they were fully parsed at
            // import; stamping their metadata-only cache would make
            // the first open trust an empty cache and fail.)
            if (format == BookFormat.TXT || format == BookFormat.MARKDOWN) {
                CacheStamp.writeStamp(openCacheDir, destFile)
            }
            id
        } catch (e: CancellationException) {
            // Never swallow coroutine cancellation: clean up and rethrow so the
            // caller's scope cancels properly instead of faking an import error.
            // The dest guard is the in-place safety rule: `dest` IS the
            // user's own file for file:// in-place imports, and a failed
            // import must never delete it. (tmpDest was never created
            // for those imports, so deleting it is a harmless no-op.)
            openCacheDir.deleteRecursively()
            if (inPlaceFile == null) dest?.delete()
            tmpDest.delete()
            throw e
        } catch (e: ImportException) {
            Log.e("NovelfaImporter", "import failed: ${e.message}", e)
            openCacheDir.deleteRecursively()
            if (inPlaceFile == null) dest?.delete()
            tmpDest.delete()
            throw e
        } catch (e: Exception) {
            Log.e("NovelfaImporter", "import failed: ${e.message}", e)
            openCacheDir.deleteRecursively()
            if (inPlaceFile == null) dest?.delete()
            tmpDest.delete()
            throw ImportException(
                e.message ?: "import failed",
                messageFa = context.getString(importErrorMessageRes(e)),
                cause = e
            )
        }
    }

    /**
     * Register-only import for `content://` in-place books: the
     * persistable permission is already held (checked by the caller).
     * One streaming pass hashes the file (duplicate/record checks)
     * while keeping the head sample for [InPlaceSource.sniffFormat];
     * nothing is copied, parsed, or covered here. The row's
     * [BookEntity.filePath] is the transient working-copy path
     * (see [InPlaceSource.resolve]), which does not exist yet —
     * the first open materializes it, then parses through the
     * format's engine exactly like a lazy PDF. Chapter count is
     * unknown until then, except for PDFs with a saved record.
     * The generated cover is still built in the background when
     * fast import is on: [GeneratedCover] is typographic and
     * needs only the title and the path key, not the file itself.
     * Cancellation before the row is inserted releases nothing
     * (the permission is released when the book is removed, and
     * an uninserted row leaves no book to remove — the stale
     * permission is harmless and reclaimed on the next import
     * of the same Uri, which re-takes it).
     */
    private suspend fun importInPlaceContent(
        sourceUri: Uri,
        id: String,
        fastImport: Boolean,
        ioCtx: CoroutineContext,
        onStage: ((String?) -> Unit)?
    ): String = withContext(Dispatchers.IO) {
        onStage?.invoke("validate")
        val displayName = queryDisplayName(sourceUri) ?: id
        val head = ByteArray(8192)
        var headLen = 0
        var total = 0L
        var zeroReads = 0
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                ioCtx.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) {
                    if (++zeroReads > 10000) {
                        throw IOException("stalled input stream: $sourceUri")
                    }
                    continue
                }
                zeroReads = 0
                total += n
                if (total > MAX_BOOK_BYTES) {
                    throw ImportException(
                        "Book too large: exceeds $MAX_BOOK_BYTES bytes",
                        messageFa = context.getString(R.string.import_error_too_large)
                    )
                }
                digest.update(buf, 0, n)
                if (headLen < head.size) {
                    val take = minOf(n, head.size - headLen)
                    buf.copyInto(head, headLen, 0, take)
                    headLen += take
                }
            }
        } ?: throw IOException("Cannot open input stream: $sourceUri")
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        importHashes[id] = hash
        val format = InPlaceSource.sniffFormat(head, headLen, displayName)
            ?: throw ImportException(
                "Unsupported book format: $displayName",
                messageFa = context.getString(R.string.import_error_unsupported_format)
            )
        val recordChapters = if (format == BookFormat.PDF) {
            recordStore.find(hash, null)?.totalChapters?.takeIf { it > 0 }
        } else null
        val workingPath = InPlaceSource.workingFile(context.cacheDir, id, format.extension).absolutePath
        val bookTitle = File(displayName).nameWithoutExtension
            .takeIf { it.isNotBlank() } ?: displayName
        ioCtx.ensureActive()
        onStage?.invoke("save")
        db.bookDao().insert(
            BookEntity(
                id = id,
                title = bookTitle,
                filePath = workingPath,
                coverPath = null,
                totalChapters = recordChapters ?: 0,
                dateAdded = Instant.now().toString(),
                originalFileName = displayName,
                sourceUri = sourceUri.toString(),
                importMode = IMPORT_MODE_IN_PLACE
            )
        )
        if (fastImport) {
            val deferredTitle = bookTitle
            val deferredPath = workingPath
            backgroundScope.launch {
                runCatching {
                    val tmp = GeneratedCover.generate(context, deferredTitle, deferredPath)
                        ?: return@runCatching
                    try {
                        val saved = CoverStore(context)
                            .saveCover(tmp, deferredPath).absolutePath
                        val current = db.bookDao().getById(id)
                        if (current != null && current.coverPath == null) {
                            db.bookDao().update(current.copy(coverPath = saved))
                        }
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }
        id
    }

    /**
     * Fast PDF validation for lazy import: opens the stored file with
     * the platform renderer (Pdfium) and reads only its page count.
     * No text extraction, no page rendering — seconds at most. A file
     * the renderer cannot open fails here, fast, instead of after
     * minutes of parsing that was never going to succeed.
     */
    private fun pdfPageCountOrThrow(pdfFile: File, displayName: String): Int {
        try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val count = renderer.pageCount
                    if (count <= 0) throw IOException("PDF has no pages: $displayName")
                    return count
                }
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException(
                e.message ?: "PDF cannot be opened: $displayName",
                messageFa = context.getString(R.string.import_error_bad_structure),
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
        provider: PdfPasswordProvider?,
        // Cancellation hook for the blocking decrypt rewrite (see
        // PdfParser.decryptToPlainPdf): without it, Cancel pressed
        // during an encrypted PDF's rewrite ran on regardless.
        checkCancelled: (() -> Unit)? = null
    ): ByteArray? {
        // Fast path: most PDFs are unencrypted, and the old code paid
        // for a whole PdfDoc construction (full xref scan) on every
        // import just to learn that. The trailer probe below is
        // conservative — any doubt keeps the full probe — so skipping
        // it can never skip a decryption the file actually needs.
        if (!PdfParser.maybeEncrypted(data)) return null
        try {
            return PdfParser.decryptToPlainPdf(data, null, checkCancelled)
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
                return PdfParser.decryptToPlainPdf(data, pw, checkCancelled)
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
        // Captured for the same reason as in importBook: this hash
        // re-reads the whole picked file, and its loop must notice
        // Cancel instead of running to completion regardless.
        val ioCtx = currentCoroutineContext()
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    ioCtx.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            } ?: return@runCatching null
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /**
     * Cancellable streaming SHA-256 of a STORED book file, for
     * duplicate detection in [LibraryViewModel]. The shared
     * [sha256OfFile] helper has no cancellation point, and duplicate
     * detection re-reads whole stored books — a Cancel pressed
     * during that read used to run on to the end regardless.
     */
    suspend fun sha256OfStoredFile(file: File): String? = withContext(Dispatchers.IO) {
        val ioCtx = currentCoroutineContext()
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    ioCtx.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
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

}

/** Upper bound for the initial SAF copy, before unzip/parse get a say. */
private const val MAX_BOOK_BYTES = 500L * 1024 * 1024
