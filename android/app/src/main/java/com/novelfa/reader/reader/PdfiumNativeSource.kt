package com.novelfa.reader.reader

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.novelfa.reader.parser.CbzBook.CbzChapter
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File

/**
 * PDF page source for the native viewer, backed by PDFium (the
 * engine behind Chrome and Android's own PdfRenderer) instead of
 * the hand-written text extractor: every PDF — text, scanned,
 * mixed, ASCII85-encoded, visual-order Persian — renders as the
 * fixed page it is, on demand, at display width. The old
 * converted text/image path through ReaderScreen is bypassed for
 * PDFs entirely: import stays lazy (decrypt + validate only),
 * first open renders from the stored file, and no conversion
 * cost or extractor bug can ever block a PDF again.
 *
 * Rendering is PdfiumCore's [PdfiumCore.renderPageBitmap] into a
 * fresh bitmap the caller owns (same contract as
 * [PdfNativeRenderer], which this replaces for PDFs — the
 * platform renderer stays in the file as a fallback). Page
 * sizes come from PDFium points, which already account for the
 * page's /Rotate, so a landscape or rotated page renders
 * upright in a bitmap shaped to match.
 *
 * Chapters come from the PDF's own outline (bookmarks),
 * flattened depth-first (parent, then its children) into the
 * viewer's chapter list — the same jump list foldered CBZs
 * get. A missing or broken outline is an empty list, never a
 * failed open.
 *
 * Word counts come from the FPDFText bridge ([PdfiumText]) and
 * exist only to feed native XP credit: a page the bridge cannot
 * read (library absent — the .so files are committed from the
 * PC, not by the connector) reports 0 words, which still earns
 * the 1 XP floor, never more.
 *
 * Threading: PdfiumCore serializes its own native calls, but
 * every render here is still expected from Dispatchers.IO —
 * a page render takes tens of milliseconds and must never run
 * on the main thread. The viewer closes this source on
 * dispose; [close] is idempotent.
 *
 * PdfiumCore page rule, found the hard way (2026-11-02, a
 * blank white PDF page on the phone): a page MUST be opened
 * with [PdfiumCore.openPage] before ANY per-page call on it.
 * Without it, [PdfiumCore.getPageWidthPoint] /
 * [PdfiumCore.getPageHeightPoint] return 0 (so the bitmap
 * falls back to a square) and [PdfiumCore.renderPageBitmap]
 * silently renders nothing — its own code catches the
 * resulting NullPointerException and only logs it, so the
 * caller gets a blank bitmap and no error, and the viewer
 * shows a white page instead of the failure placeholder.
 * [ensurePageOpen] is therefore called before every size
 * query and render, and a page whose size still reads 0
 * after opening fails loudly (failure placeholder) instead
 * of ever producing another silent blank page.
 */
internal class PdfiumNativeSource(
    private val pdfFile: File,
    context: Context
) : NativePageSource {
    private val appContext = context.applicationContext
    private val core = PdfiumCore(appContext)
    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
    private var doc: PdfDocument? = null
    private var bridgeDoc: Long = 0L
    private var closed = false

    override val pageCount: Int
    override val chapters: List<CbzChapter>

    init {
        val opened = core.newDocument(pfd)
        doc = opened
        pageCount = runCatching { core.getPageCount(opened) }.getOrDefault(0)
        chapters = runCatching {
            flattenBookmarks(core.getTableOfContents(opened), pageCount)
        }.getOrDefault(emptyList())
    }

    /**
     * Opens [index] in the PdfiumCore document if it is not
     * open yet (see the class note: per-page calls on an
     * unopened page silently do nothing). Opening is lazy,
     * one page at a time — opening every page at init would
     * load a whole long book just to show page one.
     * [PdfiumCore.closeDocument] closes every opened page,
     * so there is nothing extra to close here.
     */
    private fun ensurePageOpen(document: PdfDocument, index: Int) {
        if (!document.hasPage(index)) {
            core.openPage(document, index)
        }
        if (!document.hasPage(index)) {
            throw IllegalStateException("PDFium could not open page $index")
        }
    }

    override fun renderPage(index: Int, targetWidthPx: Int): Bitmap {
        val document = doc
            ?: throw IllegalStateException("PDFium document is closed")
        if (index < 0 || index >= pageCount) {
            throw IndexOutOfBoundsException("PDF page $index out of range")
        }
        ensurePageOpen(document, index)
        val widthPt = core.getPageWidthPoint(document, index)
        val heightPt = core.getPageHeightPoint(document, index)
        if (widthPt <= 0 || heightPt <= 0) {
            // Never fall back to a square blank page again:
            // a size of 0 after opening means the page is
            // genuinely unreadable, and the viewer must show
            // its failure placeholder, not a white page.
            throw IllegalStateException(
                "PDFium page $index has no size ($widthPt x $heightPt)"
            )
        }
        var width = targetWidthPx.coerceAtLeast(1)
        var height = (heightPt.toFloat() * width / widthPt.toFloat())
            .toInt().coerceAtLeast(1)
        if (height > MAX_RENDER_HEIGHT) {
            val shrink = MAX_RENDER_HEIGHT.toFloat() / height.toFloat()
            height = MAX_RENDER_HEIGHT
            width = (width * shrink).toInt().coerceAtLeast(1)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            core.renderPageBitmap(document, bitmap, index, 0, 0, width, height)
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        return bitmap
    }

    override fun thumbnail(index: Int, targetWidthPx: Int): Bitmap? {
        // PDFium renders at the requested width, so a small
        // request IS a cheap thumbnail — no sampling dance
        // needed (unlike CBZ, which decodes pixels).
        return runCatching { renderPage(index, targetWidthPx) }.getOrNull()
    }

    /**
     * Bounds-only page size (PDFium points, already
     * accounting for the page's /Rotate), without rendering
     * a bitmap — the PDF half of ComicScreen's dimension
     * prefetch. The base interface documents this as null
     * for PDF sources ("no native size to unify"), but a
     * ratio IS what the strip placeholder needs to avoid
     * jumping when the bitmap lands, and points give that
     * ratio exactly, without pixels. Opening a page is
     * parsing, not rendering, so it stays cheap — the same
     * lazy rule as renderPage, just without the bitmap.
     */
    override fun nativePageSize(index: Int): Pair<Int, Int>? {
        val document = doc ?: return null
        if (closed || index < 0 || index >= pageCount) return null
        return runCatching {
            ensurePageOpen(document, index)
            val widthPt = core.getPageWidthPoint(document, index)
            val heightPt = core.getPageHeightPoint(document, index)
            if (widthPt <= 0 || heightPt <= 0) null
            else widthPt to heightPt
        }.getOrNull()
    }

    /**
     * Word count of one page for XP credit (see [PdfiumText]):
     * 0 when the bridge is absent, which still earns the 1 XP
     * floor. The bridge document is opened lazily on first use
     * and closed with this source; it is a second FPDF document
     * over the same file, independent of the render document.
     */
    override fun pageWords(index: Int): Int {
        if (closed) return 0
        if (!PdfiumText.available) return 0
        if (index < 0 || index >= pageCount) return 0
        val bridge = bridgeDoc.takeIf { it != 0L } ?: runCatching {
            PdfiumText.openDocument(pdfFile.absolutePath, null)
                .also { bridgeDoc = it }
        }.getOrNull() ?: return 0
        val text = runCatching { PdfiumText.pageText(bridge, index) }.getOrNull()
            ?: return 0
        return text.splitToSequence(' ', '\n', '\r', '\t')
            .count { it.isNotEmpty() }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (bridgeDoc != 0L) {
            runCatching { PdfiumText.closeDocument(bridgeDoc) }
            bridgeDoc = 0L
        }
        val document = doc
        doc = null
        if (document != null) {
            runCatching { core.closeDocument(document) }
        }
        runCatching { pfd.close() }
    }

    companion object {
        private const val MAX_RENDER_HEIGHT = 8192

        /**
         * Flattens a nested bookmark tree depth-first (a parent,
         * then its children, in document order) into the
         * viewer's flat chapter list. Entries with a blank
         * title or a page outside the book are dropped — a
         * malformed outline must not invent chapters.
         */
        internal fun flattenBookmarks(
            bookmarks: List<PdfDocument.Bookmark>,
            pageCount: Int
        ): List<CbzChapter> {
            val out = ArrayList<CbzChapter>()
            fun walk(list: List<PdfDocument.Bookmark>) {
                for (bookmark in list) {
                    val title = runCatching { bookmark.title }.getOrNull()
                    val page = runCatching { bookmark.pageIdx }.getOrNull()
                    if (!title.isNullOrBlank() && page != null &&
                        page in 0 until pageCount
                    ) {
                        out += CbzChapter(title, page.toInt())
                    }
                    val children = runCatching { bookmark.children }.getOrNull()
                    if (!children.isNullOrEmpty()) walk(children)
                }
            }
            walk(bookmarks)
            return out
        }
    }
}
