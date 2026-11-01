package com.novelfa.reader.reader

/**
 * Kotlin side of the PDFium text bridge (src/main/cpp/pdftext.cpp).
 *
 * PdfiumCore from the pdfium-android AAR renders pages and gives the
 * table of contents, but exposes no text API — this bridge adds
 * exactly that: page text, search hits and character geometry from
 * PDFium's FPDFText API, so search, highlight and selection work on
 * the rendered page for every producer, not only for PDFs whose text
 * the hand-written extractor happens to decode.
 *
 * The prebuilt .so (libnovelfa_pdftext.so) lives in
 * src/main/jniLibs/<abi>/ like the AVIF engine. If it is absent,
 * [available] is false and every function degrades to an empty
 * result — the app never crashes on a missing library.
 *
 * Coordinates: PDFium reports rects and positions in PDF page
 * points with y measured UP from the bottom-left of the page (see
 * pdftext.cpp). Rendered bitmaps have y DOWN from the top-left, so
 * [mapRectToBitmap] / [mapPointToPage] flip y against the page
 * height. Both are pure and unit-tested (PdfiumTextMappingTest).
 */
object PdfiumText {

    /** False when libnovelfa_pdftext.so is absent (or failed to load). */
    val available: Boolean = try {
        System.loadLibrary("novelfa_pdftext")
        true
    } catch (e: Throwable) {
        false
    }

    /** FPDF_GetLastError codes: 0 ok, 2 file, 3 format, 4 password. */
    const val ERROR_PASSWORD = 4

    /** One search hit: [start] is a char index into [pageText]. */
    data class TextHit(val start: Int, val count: Int)

    /**
     * A rect in bitmap pixels (y-down). Deliberately NOT
     * android.graphics.RectF: local unit tests run against Android
     * stubs whose RectF constructor never sets its fields, so a
     * RectF-returning mapping cannot be tested on the JVM. The
     * viewer converts this to a real RectF on device.
     */
    data class MappedRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    fun openDocument(path: String, password: String?): Long =
        if (available) nOpenDocument(path, password) else 0L

    fun closeDocument(doc: Long) {
        if (available && doc != 0L) nCloseDocument(doc)
    }

    fun pageCount(doc: Long): Int =
        if (available && doc != 0L) nGetPageCount(doc) else 0

    /** Page size in PDF points, or null when unavailable. */
    fun pageSize(doc: Long, page: Int): Pair<Float, Float>? {
        if (!available || doc == 0L) return null
        val wh = nGetPageSize(doc, page)
        if (wh.size < 2 || wh[0] <= 0f || wh[1] <= 0f) return null
        return wh[0] to wh[1]
    }

    /** Full page text in PDFium's own char order, or null. */
    fun pageText(doc: Long, page: Int): String? =
        if (available && doc != 0L) nGetPageText(doc, page) else null

    /** Rects (page points, y-up) covering [start, start + count). */
    fun rangeRects(doc: Long, page: Int, start: Int, count: Int): List<MappedRect> {
        if (!available || doc == 0L) return emptyList()
        val raw = nGetRangeRects(doc, page, start, count)
        val out = ArrayList<MappedRect>(raw.size / 4)
        var i = 0
        while (i + 3 < raw.size) {
            out += MappedRect(raw[i], raw[i + 1], raw[i + 2], raw[i + 3])
            i += 4
        }
        return out
    }

    /** All matches of [query] on one page, in PDFium char order. */
    fun findAll(
        doc: Long,
        page: Int,
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): List<TextHit> {
        if (!available || doc == 0L || query.isEmpty()) return emptyList()
        val raw = nFindAll(doc, page, query, matchCase, wholeWord)
        val out = ArrayList<TextHit>(raw.size / 2)
        var i = 0
        while (i + 1 < raw.size) {
            out += TextHit(raw[i], raw[i + 1])
            i += 2
        }
        return out
    }

    /**
     * Char index at a page point (points, y-up — see
     * [mapPointToPage] to convert a bitmap touch first), or -1.
     */
    fun charIndexAtPos(doc: Long, page: Int, x: Double, y: Double): Int =
        if (available && doc != 0L) {
            nGetCharIndexAtPos(doc, page, x, y)
        } else {
            -1
        }

    fun lastError(): Int = if (available) nGetLastError() else 0

    /**
     * Maps a PDFium rect (points, y-up) onto a rendered bitmap
     * (pixels, y-down). The rect is normalized, so a flipped or
     * empty input still yields a valid (possibly empty) rect.
     */
    fun mapRectToBitmap(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        pageWidth: Float,
        pageHeight: Float,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): MappedRect {
        if (pageWidth <= 0f || pageHeight <= 0f ||
            bitmapWidth <= 0 || bitmapHeight <= 0
        ) {
            return MappedRect(0f, 0f, 0f, 0f)
        }
        val sx = bitmapWidth / pageWidth
        val sy = bitmapHeight / pageHeight
        val l = minOf(left, right) * sx
        val r = maxOf(left, right) * sx
        // y-up to y-down: the PDFium "top" (larger y) becomes the
        // bitmap's smaller y, and vice versa.
        val t = (pageHeight - maxOf(top, bottom)) * sy
        val b = (pageHeight - minOf(top, bottom)) * sy
        return MappedRect(l, t, r, b)
    }

    /** Maps a bitmap point (pixels, y-down) to a page point (y-up). */
    fun mapPointToPage(
        bitmapX: Float,
        bitmapY: Float,
        pageWidth: Float,
        pageHeight: Float,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Pair<Float, Float> {
        if (pageWidth <= 0f || pageHeight <= 0f ||
            bitmapWidth <= 0 || bitmapHeight <= 0
        ) {
            return 0f to 0f
        }
        val x = bitmapX / bitmapWidth * pageWidth
        val y = pageHeight - bitmapY / bitmapHeight * pageHeight
        return x to y
    }

    private external fun nOpenDocument(path: String, password: String?): Long
    private external fun nCloseDocument(doc: Long)
    private external fun nGetPageCount(doc: Long): Int
    private external fun nGetPageSize(doc: Long, page: Int): FloatArray
    private external fun nGetPageText(doc: Long, page: Int): String?
    private external fun nGetRangeRects(
        doc: Long,
        page: Int,
        start: Int,
        count: Int
    ): FloatArray
    private external fun nFindAll(
        doc: Long,
        page: Int,
        query: String,
        matchCase: Boolean,
        wholeWord: Boolean
    ): IntArray
    private external fun nGetCharIndexAtPos(
        doc: Long,
        page: Int,
        x: Double,
        y: Double
    ): Int
    private external fun nGetLastError(): Int
}
