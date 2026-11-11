package com.novelfa.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.NestedArchive
import com.shockwave.pdfium.PdfiumCore
import com.novelfa.reader.parser.engine.CbzEngine
import com.novelfa.reader.parser.engine.RarEngine
import com.novelfa.reader.parser.engine.DocxEngine
import com.novelfa.reader.parser.engine.EpubEngine
import com.novelfa.reader.parser.engine.ZipEngine
import com.novelfa.reader.parser.engine.ZipKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * First-open cover backfill for `content://` in-place books.
 *
 * Why it exists: a register-only import never opens the archive,
 * so an EPUB/CBZ/DOCX imported this way has no real cover at
 * import — only the typographic cover (fast import) or nothing
 * (fast import off). The first open already materializes the
 * working copy and parses the book, so that is the cheapest
 * moment to extract the real cover and put it on the row.
 *
 * Rules, all deliberate:
 * - Only `content://` in-place books. Copy books and `file://`
 *   in-place books already get their real cover at import from
 *   the engine metadata; touching them here would only risk
 *   clobbering a good cover.
 * - EPUB/DOCX/CBZ, image-only ZIPs and PDFs: extract the real
 *   cover (EPUB cover entry, DOCX first body image, CBZ/ZIP
 *   first page, PDF first page rendered by PDFium) and replace
 *   whatever cover the row has — a generated cover from import
 *   is replaced, an already-backfilled real cover is
 *   re-extracted identically, so the job is idempotent and
 *   safe to run on every open.
 * - Every other format (TXT/MD, and ZIPs carrying books):
 *   there is no real cover to extract, so the job only fills a
 *   missing cover with a generated one, and leaves an existing
 *   cover alone.
 * - The row is re-read immediately before the update, so a
 *   progress save that landed during the extraction is not
 *   clobbered by a stale snapshot — only coverPath changes.
 * - Everything is best-effort (runCatching): a cover must never
 *   fail an open that already succeeded.
 */
object CoverBackfill {

    suspend fun backfill(context: Context, dao: BookDao, book: BookEntity, resolvedFile: File) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!book.isInPlace()) return@runCatching
                if (InPlaceSource.sourceScheme(book.sourceUri) != "content") return@runCatching
                val current = dao.getById(book.id) ?: return@runCatching
                val format = BookFormat.detect(resolvedFile) ?: return@runCatching
                // Scratch lives in cacheDir, NOT in the reader's
                // chapter cache (epub_open/<id>): a stray cover
                // file there would sit inside the extracted book
                // tree the reader parses from. It is deleted
                // below once the cover is saved (or not needed).
                val scratchDir = File(
                    context.cacheDir,
                    "cover_backfill" + File.separatorChar + book.id
                ).apply { mkdirs() }
                val realCover: File? = when (format) {
                    BookFormat.EPUB -> runCatching {
                        EpubEngine.metadata(resolvedFile, current.title, scratchDir) {}.coverFile
                    }.getOrNull()
                    BookFormat.DOCX -> runCatching {
                        DocxEngine.metadata(resolvedFile, current.title, scratchDir) {}.coverFile
                    }.getOrNull()
                    BookFormat.CBZ -> runCatching {
                        CbzEngine.metadata(resolvedFile, current.title, scratchDir) {}.coverFile
                    }.getOrNull()
                    BookFormat.CBR, BookFormat.RAR -> runCatching {
                        RarEngine.metadata(resolvedFile, current.title, scratchDir) {}.coverFile
                    }.getOrNull()
                    BookFormat.PDF -> runCatching {
                        val pfd = ParcelFileDescriptor.open(
                            resolvedFile, ParcelFileDescriptor.MODE_READ_ONLY
                        )
                        try {
                            pdfFirstPageCover(context, pfd, scratchDir)
                        } finally {
                            runCatching { pfd.close() }
                        }
                    }.getOrNull()
                    BookFormat.ZIP -> {
                        val meta = runCatching {
                            ZipEngine.metadata(resolvedFile, current.title, scratchDir) {}
                        }.getOrNull()
                        val isImageOnly = runCatching {
                            ZipEngine.classify(resolvedFile) is ZipKind.ImageOnly
                        }.getOrDefault(false)
                        if (isImageOnly) meta?.coverFile else null
                    }
                    else -> null
                }?.takeIf { it.exists() }
                saveRealCover(context, dao, book, realCover, resolvedFile.absolutePath)
                runCatching { scratchDir.deleteRecursively() }
            }
        }
    }

    /**
     * Shared save step for both backfill paths: a real cover
     * replaces whatever the row has (including a generated
     * cover from import — the generated file shares the
     * CoverStore key of this book path, and saveCover refuses
     * to overwrite an existing file, so any cover file keyed
     * to this path is removed first, then the real one is
     * saved under the same key); otherwise a missing cover is
     * filled with a generated one. The row is re-read
     * immediately before the update, so a progress save that
     * landed during extraction is not clobbered — only
     * coverPath changes.
     */
    private suspend fun saveRealCover(
        context: Context,
        dao: BookDao,
        book: BookEntity,
        realCover: File?,
        pathKey: String
    ) {
        val latest = dao.getById(book.id) ?: return
        if (realCover != null) {
            val coversDir = File(context.filesDir, "covers")
            // coverFileName(path, "") is exactly the
            // "cover_<md5>" prefix every cover for this
            // book path shares, whatever its extension.
            val keyPrefix = CoverStore.coverFileName(pathKey, "")
            coversDir.listFiles()
                ?.filter { it.name.startsWith(keyPrefix) }
                ?.forEach { runCatching { it.delete() } }
            val saved = CoverStore(context)
                .saveCover(realCover, pathKey).absolutePath
            val fresh = dao.getById(book.id) ?: return
            // Always write the row, even when the path string
            // is unchanged: the cover file was replaced in
            // place (same path, new bytes — a PNG first page
            // replacing a generated PNG), and the write is
            // what re-emits the library Flow so the new
            // signature key takes effect. Without it the
            // library keeps showing the generated cover.
            dao.update(fresh.copy(coverPath = saved))
        } else if (latest.coverPath == null) {
            val tmp = GeneratedCover.generate(context, latest.title, pathKey)
                ?: return
            try {
                val saved = CoverStore(context)
                    .saveCover(tmp, pathKey).absolutePath
                val fresh = dao.getById(book.id) ?: return
                if (fresh.coverPath == null) {
                    dao.update(fresh.copy(coverPath = saved))
                }
            } finally {
                tmp.delete()
            }
        }
    }

    /**
     * Direct-route counterpart of [backfill] for PDFs: the first
     * page renders from the provider's descriptor, with no
     * working copy. Best-effort like [backfill]. The path key
     * is the row's working-copy path — the same key the
     * import-time generated cover used, so replacement works
     * exactly as in the file-backed path.
     */
    suspend fun backfillDirectPdf(context: Context, dao: BookDao, book: BookEntity) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!book.isInPlace()) return@runCatching
                val uriStr = book.sourceUri ?: return@runCatching
                if (InPlaceSource.sourceScheme(uriStr) != "content") return@runCatching
                if (dao.getById(book.id) == null) return@runCatching
                val scratchDir = File(
                    context.cacheDir,
                    "cover_backfill" + File.separatorChar + book.id
                ).apply { mkdirs() }
                val realCover = pdfFirstPageCoverForUri(
                    context, Uri.parse(uriStr), scratchDir
                )?.takeIf { it.exists() }
                saveRealCover(context, dao, book, realCover, book.filePath)
                runCatching { scratchDir.deleteRecursively() }
            }
        }
    }

    /**
     * Renders a PDF file's first page as its cover (JPEG, 600px
     * wide), for import-time use: a PDF's library cover is its
     * first page, not a generated typographic cover. One page
     * render is O(1) — it does not grow with the book, so lazy
     * import still holds. Best-effort: any failure returns null
     * and the caller falls back to the generated cover.
     */
    fun pdfFirstPageCoverForFile(
        context: Context,
        file: File,
        scratchDir: File
    ): File? {
        val pfd = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull() ?: return null
        try {
            return pdfFirstPageCover(context, pfd, scratchDir)
        } finally {
            runCatching { pfd.close() }
        }
    }

    /**
     * Same first-page render from a provider descriptor, for
     * register-only (content://) imports where no File exists
     * yet. Best-effort like [pdfFirstPageCoverForFile].
     */
    fun pdfFirstPageCoverForUri(
        context: Context,
        uri: Uri,
        scratchDir: File
    ): File? {
        val pfd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
        }.getOrNull() ?: return null
        try {
            return pdfFirstPageCover(context, pfd, scratchDir)
        } finally {
            runCatching { pfd.close() }
        }
    }

    /**
     * Direct-route counterpart of [backfill] for comics: the
     * first image entry is the cover, read from the archive
     * directly. Best-effort like [backfill].
     */
    suspend fun backfillDirectCbz(
        context: Context,
        dao: BookDao,
        book: BookEntity,
        seekable: SeekableZip
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!book.isInPlace()) return@runCatching
                if (dao.getById(book.id) == null) return@runCatching
                val scratchDir = File(
                    context.cacheDir,
                    "cover_backfill" + File.separatorChar + book.id
                ).apply { mkdirs() }
                val nestedCache = File(context.cacheDir, "nested-archives")
                val sourceKey = book.sourceUri ?: book.id
                val first = seekable.nestedImageEntryNames(nestedCache, sourceKey)
                    .firstOrNull()
                val realCover = first?.let { name ->
                    runCatching {
                        val bytes = (
                            if (NestedArchive.isVirtualPath(name)) {
                                seekable.openNestedEntry(name, nestedCache, sourceKey)
                            } else {
                                seekable.openEntry(name)
                            }
                        )?.use { it.readBytes() }
                            ?: return@runCatching null
                        val ext = name.substringAfterLast('.', "jpg")
                            .takeIf { it.isNotBlank() } ?: "jpg"
                        val dest = File(scratchDir, "cover.$ext")
                        dest.writeBytes(bytes)
                        dest.takeIf { it.exists() && it.length() > 0 }
                    }.getOrNull()
                }
                saveRealCover(context, dao, book, realCover, book.filePath)
                runCatching { scratchDir.deleteRecursively() }
            }
        }
    }

    /**
     * Renders a PDF's first page as its cover (JPEG, 600px
     * wide) — the PDFium equivalent of the CBZ first-page
     * cover. Best-effort: any failure returns null (or throws
     * into the caller's runCatching) and the caller falls back
     * to the generated cover. The caller owns the descriptor;
     * this function only reads from it.
     */
    private fun pdfFirstPageCover(
        context: Context,
        pfd: ParcelFileDescriptor,
        scratchDir: File
    ): File? {
        val core = PdfiumCore(context)
        val doc = core.newDocument(pfd)
        try {
            if (core.getPageCount(doc) <= 0) return null
            // PdfiumCore page rule (see PdfiumNativeSource):
            // a page must be opened before any per-page
            // call, or its size reads 0 and the render
            // silently produces a blank bitmap — which
            // would then be saved as a white cover. A
            // page that cannot be opened, or has no size,
            // returns null so the caller keeps the
            // generated cover instead of a white one.
            if (!doc.hasPage(0)) core.openPage(doc, 0)
            if (!doc.hasPage(0)) return null
            val widthPt = core.getPageWidthPoint(doc, 0)
            val heightPt = core.getPageHeightPoint(doc, 0)
            if (widthPt <= 0 || heightPt <= 0) return null
            val width = 600
            val height = (heightPt.toFloat() * width / widthPt.toFloat())
                .toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888
            )
            try {
                core.renderPageBitmap(doc, bitmap, 0, 0, 0, width, height)
                scratchDir.mkdirs()
                val dest = File(scratchDir, "cover.jpg")
                dest.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                return dest.takeIf { it.exists() && it.length() > 0 }
            } finally {
                bitmap.recycle()
            }
        } finally {
            runCatching { core.closeDocument(doc) }
        }
    }
}
