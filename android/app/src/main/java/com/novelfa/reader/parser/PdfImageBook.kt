package com.novelfa.reader.parser

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.novelfa.reader.model.ParsedBook
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * ComicScreen-style image fallback for PDFs with no extractable text
 * (scanned / image-only PDFs): the platform renderer turns each PDF
 * page into an image, and the book becomes a single image section.
 * Vertical mode then scrolls it like a webtoon; paged mode shows one
 * image per page (the pager's full-bleed image-page rule already
 * handles a page whose only content is one image).
 *
 * Only reached when the text extractor yields zero chapters (or throws
 * a non-password error the renderer might still survive); text PDFs
 * never take this path. Mirrors the DOCX shape — images in `media/`,
 * referenced relatively — so the reader's existing image pipeline and
 * the open-path cache apply unchanged, and a re-open re-renders only
 * when the source file itself changed (the open cache stamp).
 */
object PdfImageBook {

    /** Image rendering is bounded: beyond this the import fails cleanly. */
    const val MAX_IMAGE_PAGES = 1000
    /**
     * Pages rendered before the reader opens (the ReadEra rule: the
     * first pages must be readable in seconds, not after the whole
     * book renders). The rest render in the background while the
     * user reads ([renderMissing], launched by the reader); reading
     * speed is far slower than rendering speed, so the background
     * pass normally stays ahead of the reader.
     */
    const val OPEN_PRELOAD_PAGES = 20
    const val RENDER_WIDTH = 1440
    const val JPEG_QUALITY = 85
    private const val MAX_RENDER_HEIGHT = 8192

    /** Pure: stable, Locale.US-padded image file name for a page index. */
    fun fileNameFor(index: Int): String =
        "page-%04d.jpg".format(Locale.US, index + 1)

    /**
     * Pure: one section holding every page image, titled with the book
     * title (no new UI string needed — the drawer shows the book's own
     * title as its single section). Each image sits in its own <p> so
     * the pager's image-page rule can claim it as a full-bleed page.
     */
    fun buildSections(title: String, pageCount: Int): List<ConvertedBook.Section> {
        val body = (0 until pageCount).joinToString("") { i ->
            "<p><img src=\"media/" + fileNameFor(i) + "\"></p>"
        }
        return listOf(ConvertedBook.Section(title, body))
    }

    /**
     * Builds the image book for [pdfFile]: sections for EVERY page are
     * written at once (cheap HTML), but only the first
     * [OPEN_PRELOAD_PAGES] pages are rendered before returning — that
     * is what makes the reader open in seconds instead of after a
     * minutes-long full render behind a bare spinner. The remaining
     * pages render in the background via [renderMissing]. The file
     * must already be decrypted (the importer stores password PDFs
     * decrypted; the platform renderer cannot open encrypted files
     * at all). The 1000-page cap keeps its old meaning: a book over
     * it still fails here instead of opening half-rendered.
     */
    suspend fun render(
        pdfFile: File,
        outDir: File,
        title: String,
        onProgress: ((Int, Int) -> Unit)? = null
    ): ParsedBook {
        // Chapters FIRST, page images second (the old order rendered
        // the preload and only then wrote the chapters): if the
        // process dies mid-preload, the chapters now already exist,
        // so the next open can return them and let renderMissing
        // fill the missing pages instead of converting from zero —
        // see openParsed's stamp-miss rescue. The 1000-page cap is
        // still enforced here before anything is written.
        val count = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
        if (count <= 0) throw IllegalArgumentException("PDF has no pages")
        if (count > MAX_IMAGE_PAGES) {
            throw IllegalArgumentException("PDF has too many pages for image rendering")
        }
        val parsed = ConvertedBook.write(outDir, title, buildSections(title, count))
        renderPages(pdfFile, outDir, null, onProgress, OPEN_PRELOAD_PAGES)
        return parsed
    }

    /**
     * Renders every page image the chapter files reference but whose
     * file does not exist yet, in reading order, skipping pages past
     * [MAX_IMAGE_PAGES]. The reader launches this in the background
     * right after opening (and on every later open, resuming where a
     * previous session stopped): it is how a lazily-opened book
     * becomes complete without blocking the first page. Returns how
     * many pages it rendered; 0 when nothing was missing. Pure
     * file-existence work when the book is already complete, so
     * re-opens stay cheap.
     */
    suspend fun renderMissing(pdfFile: File, outDir: File): Int {
        val refs = mutableSetOf<Int>()
        val refRegex = Regex("""media/page-(\d+)\.jpg""")
        outDir.listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("""ch\d+\.xhtml""")) }
            ?.forEach { chapter ->
                val text = runCatching { chapter.readText(Charsets.UTF_8) }.getOrNull()
                    ?: return@forEach
                refRegex.findAll(text).forEach { m ->
                    (m.groupValues[1].toIntOrNull()?.minus(1))?.let { refs += it }
                }
            }
        if (refs.isEmpty()) return 0
        val mediaDir = File(outDir, "media")
        val missing = refs.filter { it < MAX_IMAGE_PAGES && !File(mediaDir, fileNameFor(it)).isFile }
            .sorted()
        if (missing.isEmpty()) return 0
        renderPages(pdfFile, outDir, missing)
        return missing.size
    }

    /**
     * Renders [indices] (or every page when null) into `outDir/media/`
     * and returns the PDF's page count. Used by the mixed text+image
     * path, which renders only the image pages instead of the whole
     * book. The per-page cap still applies to a full render; a mixed
     * render is capped by its caller on the image-page count.
     *
     * Rendering used to be strictly sequential — one page at a time
     * through one PdfRenderer — so a scanned book's import time was
     * the sum of every page's native render, and that sum dominated
     * the whole import. Pages are independent (each writes its own
     * file), so larger renders now split across a few worker
     * renderers: every worker opens its OWN PdfRenderer on its own
     * file descriptor (a renderer instance is single-threaded, but
     * separate instances are independent), renders its own
     * contiguous chunk, and recycles each bitmap as before. Three
     * workers is the cap: more would multiply the peak transient
     * bitmap memory (one per worker) for little extra gain on a
     * phone, and small renders stay sequential to avoid thread
     * overhead. Validation and output files are unchanged.
     */
    suspend fun renderPages(
        pdfFile: File,
        outDir: File,
        indices: List<Int>?,
        // Progress for the reader's loading UI: (pages rendered in
        // this call, total pages in the PDF). Throttled to every 10th
        // page and the last one, so a 1000-page render does not
        // recompose the UI a thousand times. Invoked from worker
        // threads on the parallel path; the UI only ever moves it
        // forward, so out-of-order reports are harmless.
        onProgress: ((Int, Int) -> Unit)? = null,
        // When set with indices == null, renders only the first
        // [limit] pages instead of the whole book (the open-phase
        // preload); the cap check below is then the caller's job.
        limit: Int? = null
    ): Int {
        val count = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
        if (count <= 0) throw IllegalArgumentException("PDF has no pages")
        // The cap applies to the whole book even on a preload call:
        // a 2000-page scan must still fail here (as before) instead
        // of opening after 20 wasted renders.
        if (indices == null && count > MAX_IMAGE_PAGES) {
            throw IllegalArgumentException("PDF has too many pages for image rendering")
        }
        val wanted = (indices ?: (0 until count).toList())
            .let { if (limit != null) it.take(limit) else it }
        if (wanted.any { it < 0 || it >= count }) {
            throw IllegalArgumentException("PDF page index out of range")
        }
        val mediaDir = File(outDir, "media").apply { mkdirs() }
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        fun report() {
            val d = done.get()
            if (d == wanted.size || d % 10 == 0) onProgress?.invoke(d, count)
        }
        val workers = minOf(3, Runtime.getRuntime().availableProcessors(), wanted.size)
        if (workers <= 1) {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (i in wanted) {
                        // Cancellation lands between pages: a native
                        // render is uninterruptible, but the wait
                        // between pages must not ignore Cancel.
                        currentCoroutineContext().ensureActive()
                        renderOne(renderer, i, mediaDir)
                        done.incrementAndGet()
                        report()
                    }
                }
            }
            return count
        }
        // Contiguous chunks: each worker's pages are adjacent, which
        // keeps its renderer's page cache warm across its own chunk.
        val chunk = (wanted.size + workers - 1) / workers
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map { w ->
                val part = wanted.subList(
                    minOf(w * chunk, wanted.size),
                    minOf((w + 1) * chunk, wanted.size)
                )
                // Explicit Callable: a bare lambda here is ambiguous
                // between submit(Runnable) and submit(Callable).
                pool.submit(java.util.concurrent.Callable {
                    ParcelFileDescriptor.open(
                        pdfFile, ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            for (i in part) {
                                renderOne(renderer, i, mediaDir)
                                done.incrementAndGet()
                                report()
                            }
                        }
                    }
                })
            }
            for (f in futures) {
                // A bare f.get() is a blocking wait: coroutine
                // cancellation cannot reach it, so the import dialog's
                // Cancel button looked dead for the whole render of a
                // big scanned book. Poll with a short timeout and let
                // cancellation land between polls; the finally below
                // shuts the pool down, stopping the workers.
                while (true) {
                    try {
                        f.get(100, TimeUnit.MILLISECONDS)
                        break
                    } catch (e: TimeoutException) {
                        currentCoroutineContext().ensureActive()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        // Surface the worker's own failure (a bad
                        // page), not the wrapper, so the caller's
                        // error reporting stays exactly what the
                        // sequential path produced.
                        throw (e.cause as? Exception) ?: e
                    }
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return count
    }

    /** Renders one page to `media/<page file>`; bitmap recycled on every path. */
    private fun renderOne(renderer: PdfRenderer, index: Int, mediaDir: File) {
        renderer.openPage(index).use { page ->
            var width = RENDER_WIDTH
            var height = (page.height * (width.toFloat() / page.width.toFloat()))
                .toInt().coerceAtLeast(1)
            if (height > MAX_RENDER_HEIGHT) {
                // Absurdly tall page: shrink to the cap,
                // width following, aspect preserved.
                val shrink = MAX_RENDER_HEIGHT.toFloat() / height.toFloat()
                height = MAX_RENDER_HEIGHT
                width = (width * shrink).toInt().coerceAtLeast(1)
            }
            val bitmap = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888
            )
            try {
                page.render(
                    bitmap, null, null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )
                File(mediaDir, fileNameFor(index)).outputStream().use { stream ->
                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream
                    )
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
