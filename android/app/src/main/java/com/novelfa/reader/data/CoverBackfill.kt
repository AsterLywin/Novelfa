package com.novelfa.reader.data

import android.content.Context
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.engine.CbzEngine
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
 * - EPUB/DOCX/CBZ and image-only ZIPs: extract the real cover
 *   (EPUB cover entry, DOCX first body image, CBZ/ZIP first
 *   page) and replace whatever cover the row has — a generated
 *   cover from import is replaced, an already-backfilled real
 *   cover is re-extracted identically, so the job is idempotent
 *   and safe to run on every open.
 * - Every other format (TXT/MD/PDF, and ZIPs carrying books):
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
                val latest = dao.getById(book.id) ?: return@runCatching
                if (realCover != null) {
                    // Replace a generated cover from import: the
                    // generated file shares the CoverStore key of
                    // this book path, and saveCover refuses to
                    // overwrite an existing file — so remove any
                    // cover file keyed to this path first, then
                    // save the real one under the same key.
                    val coversDir = File(context.filesDir, "covers")
                    // coverFileName(path, "") is exactly the
                    // "cover_<md5>" prefix every cover for this
                    // book path shares, whatever its extension.
                    val keyPrefix = CoverStore.coverFileName(resolvedFile.absolutePath, "")
                    coversDir.listFiles()
                        ?.filter { it.name.startsWith(keyPrefix) }
                        ?.forEach { runCatching { it.delete() } }
                    val saved = CoverStore(context)
                        .saveCover(realCover, resolvedFile.absolutePath).absolutePath
                    val fresh = dao.getById(book.id) ?: return@runCatching
                    if (fresh.coverPath != saved) {
                        dao.update(fresh.copy(coverPath = saved))
                    }
                } else if (latest.coverPath == null) {
                    val tmp = GeneratedCover.generate(context, latest.title, resolvedFile.absolutePath)
                        ?: return@runCatching
                    try {
                        val saved = CoverStore(context)
                            .saveCover(tmp, resolvedFile.absolutePath).absolutePath
                        val fresh = dao.getById(book.id) ?: return@runCatching
                        if (fresh.coverPath == null) {
                            dao.update(fresh.copy(coverPath = saved))
                        }
                    } finally {
                        tmp.delete()
                    }
                }
                runCatching { scratchDir.deleteRecursively() }
            }
        }
    }
}
