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
    private val pfd: ParcelFileDescriptor,
    context: Context,
    // Viewer password, session-only, never persisted — the
    // same contract as the ZIP password in the viewer. Kept
    // as the caller's CharArray (no copy), and converted to
    // the String PdfiumCore requires only transiently, at
    // each open (render doc here, bridge doc in pageWords),
    // so no password String is ever stored in a field.
    // Null = no password yet (plain PDFs, and the first
    // attempt on an encrypted PDF, which is how the viewer
    // learns it needs one).
    private val password: CharArray? = null,
    // File for the trailer encryption check and the text
    // bridge, when the document came from one. Null for
    // descriptor-backed documents (step 5: in-place
    // `content://` PDFs opened with no working copy): there
    // an unopenable document is treated as "needs a
    // password" — the same conservative direction the file
    // check already errs in (a password prompt is recoverable
    // via Cancel; a bare error screen for an encrypted file
    // is a dead end) — and pageWords returns 0, so XP credit
    // falls back to the 1 XP/page floor for those books.
    private val pdfFile: File? = null
) : NativePageSource {
    constructor(pdfFile: File, context: Context, password: CharArray? = null) : this(
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY),
        context,
        password,
        pdfFile
    )

    private val appContext = context.applicationContext
    private val core = PdfiumCore(appContext)
    private var doc: PdfDocument? = null
    private var bridgeDoc: Long = 0L
    private var closed = false

    override val pageCount: Int
    override val chapters: List<CbzChapter>

    /**
     * True when the open failed AND the file is encrypted —
     * the viewer's signal to show its password dialog instead
     * of the error screen. False for plain PDFs (success, or
     * a corrupt file that no password would fix). Set only
     * here, in init: a wrong password and a missing password
     * look identical to PDFium (a doc with 0 pages, or an
     * open exception), so the trailer check below is what
     * separates "needs a password" from "broken file".
     */
    val needsPassword: Boolean

    init {
        var openedDoc: PdfDocument? = null
        var openedCount = 0
        var openedChapters: List<CbzChapter> = emptyList()
        // Transient String for PdfiumCore only: it goes out
        // of scope when init returns and is never stored.
        val passwordString = password?.let { String(it) }
        try {
            openedDoc = if (passwordString != null) {
                core.newDocument(pfd, passwordString)
            } else {
                core.newDocument(pfd)
            }
            openedCount =
                runCatching { core.getPageCount(openedDoc) }.getOrDefault(0)
            openedChapters = if (openedCount > 0) {
                runCatching {
                    flattenBookmarks(core.getTableOfContents(openedDoc), openedCount)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            // An open exception (not just 0 pages) still
            // lands here: the doc stays null, the count stays
            // 0, and needsPassword below decides dialog vs
            // error. The exception itself is not rethrown, so
            // the viewer can read needsPassword instead of
            // seeing only a null source from runCatching.
            openedDoc = null
            openedCount = 0
            openedChapters = emptyList()
        }
        doc = openedDoc
        pageCount = openedCount
        chapters = openedChapters
        needsPassword = if (openedCount <= 0) {
            val f = pdfFile
            if (f != null) runCatching { isEncrypted(f) }.getOrDefault(false) else true
        } else {
            false
        }
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
        // The bridge doc needs the same password as the
        // render doc, or a password PDF would read 0 words
        // and credit only the 1 XP floor. Transient String
        // again, never stored — the bridge handle is what
        // persists, not the password.
        // Descriptor-backed documents have no path for the
        // text bridge: words read 0 and XP uses its floor.
        val path = pdfFile?.absolutePath ?: return 0
        val bridge = bridgeDoc.takeIf { it != 0L } ?: runCatching {
            PdfiumText.openDocument(
                path,
                password?.let { String(it) }
            ).also { bridgeDoc = it }
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
         * Whether a PDF file is encrypted, from its trailer
         * dicts only — a tail-check port of
         * PdfParser.maybeEncrypted, which needs the whole
         * file in memory. This one seeks with
         * RandomAccessFile instead, so a 100-page book is
         * not loaded just to ask "does it need a password".
         * Uncertain (no startxref, a dict that does not
         * close, a broken /Prev chain) answers TRUE, exactly
         * like maybeEncrypted: prompting for a password is
         * recoverable (Cancel lands on the error screen),
         * while an error screen with no prompt is a dead
         * end for an encrypted file. A plain file answers
         * false only when its last trailer is read cleanly
         * and has no /Encrypt and no further /Prev.
         */
        internal fun isEncrypted(pdfFile: File): Boolean {
            val raf = try {
                java.io.RandomAccessFile(pdfFile, "r")
            } catch (e: Exception) {
                return false
            }
            return try {
                val length = raf.length()
                if (length <= 0L) return false
                val tailLen = minOf(2048L, length).toInt()
                val tailBytes = ByteArray(tailLen)
                raf.seek(length - tailLen)
                raf.readFully(tailBytes)
                val tail = String(tailBytes, Charsets.ISO_8859_1)
                val sx = tail.lastIndexOf("startxref")
                if (sx < 0) return true
                var offset = tail.substring(sx + "startxref".length)
                    .trimStart().takeWhile { it.isDigit() }
                    .toIntOrNull() ?: return true
                val seen = mutableSetOf<Int>()
                var guard = 0
                while (guard++ < 25) {
                    if (offset < 0 || offset.toLong() >= length ||
                        !seen.add(offset)
                    ) return true
                    val winLen = minOf(65536L, length - offset).toInt()
                    val winBytes = ByteArray(winLen)
                    raf.seek(offset.toLong())
                    raf.readFully(winBytes)
                    val win = String(winBytes, Charsets.ISO_8859_1)
                    val body = win.trimStart()
                    val dictStart = if (body.startsWith("xref")) {
                        val tr = body.indexOf("trailer")
                        if (tr < 0) return true
                        body.indexOf("<<", tr)
                    } else {
                        body.indexOf("<<")
                    }
                    if (dictStart < 0) return true
                    val dict = balancedDict(body, dictStart) ?: return true
                    if (dict.contains("/Encrypt")) return true
                    val prev = Regex("/Prev\\s+(\\d+)").find(dict)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: return false
                    offset = prev
                }
                true
            } catch (e: Exception) {
                false
            } finally {
                runCatching { raf.close() }
            }
        }

        /**
         * Balanced `<<...>>` dict starting at [start], or
         * null if it does not close inside the text — the
         * same walk PdfParser uses, so a "/Encrypt" inside
         * a nested dict or a literal string is not mistaken
         * for the trailer's own /Encrypt, and stream bytes
         * after the dict are never scanned.
         */
        private fun balancedDict(text: String, start: Int): String? {
            var depth = 0
            var i = start
            while (i < text.length) {
                when {
                    text.startsWith("<<", i) -> {
                        depth++
                        i += 2
                    }
                    text.startsWith(">>", i) -> {
                        depth--
                        i += 2
                        if (depth == 0) return text.substring(start, i)
                    }
                    text[i] == '(' -> {
                        var d = 1
                        i++
                        while (i < text.length && d > 0) {
                            when {
                                text[i] == '\\' -> i += 2
                                text[i] == '(' -> {
                                    d++
                                    i++
                                }
                                text[i] == ')' -> {
                                    d--
                                    i++
                                }
                                else -> i++
                            }
                        }
                    }
                    else -> i++
                }
            }
            return null
        }

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
