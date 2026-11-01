package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure coordinate mapping of the PDFium text layer. No native
 * library is needed: these functions never touch JNI.
 *
 * PDFium rects are in points, y-UP from the page bottom-left;
 * bitmaps are pixels, y-DOWN from the top-left. A rect at the
 * PDFium top of the page must land at the bitmap top, and a
 * rect at the PDFium bottom at the bitmap bottom.
 */
class PdfiumTextMappingTest {

    @Test
    fun mapRect_fullPage() {
        val r = PdfiumText.mapRectToBitmap(
            0f, 842f, 595f, 0f, 595f, 842f, 595, 842
        )
        assertEquals(0f, r.left, 0.001f)
        assertEquals(0f, r.top, 0.001f)
        assertEquals(595f, r.right, 0.001f)
        assertEquals(842f, r.bottom, 0.001f)
    }

    @Test
    fun mapRect_topWordFlipsToBitmapTop() {
        // A word near the PDFium top (y 742..772 of an 842pt page)
        // must appear near the bitmap top (y 70..100 at 1:1 scale).
        val r = PdfiumText.mapRectToBitmap(
            100f, 772f, 200f, 742f, 595f, 842f, 595, 842
        )
        assertEquals(100f, r.left, 0.001f)
        assertEquals(70f, r.top, 0.001f)
        assertEquals(200f, r.right, 0.001f)
        assertEquals(100f, r.bottom, 0.001f)
    }

    @Test
    fun mapRect_scalesToBitmapSize() {
        // Same page rendered at double size: every edge doubles.
        val r = PdfiumText.mapRectToBitmap(
            100f, 772f, 200f, 742f, 595f, 842f, 1190, 1684
        )
        assertEquals(200f, r.left, 0.001f)
        assertEquals(140f, r.top, 0.001f)
        assertEquals(400f, r.right, 0.001f)
        assertEquals(200f, r.bottom, 0.001f)
    }

    @Test
    fun mapRect_degenerateInputIsEmpty() {
        val r = PdfiumText.mapRectToBitmap(
            0f, 0f, 0f, 0f, 0f, 0f, 0, 0
        )
        assertEquals(0f, r.left, 0.001f)
        assertEquals(0f, r.top, 0.001f)
        assertEquals(0f, r.right, 0.001f)
        assertEquals(0f, r.bottom, 0.001f)
    }

    @Test
    fun mapPoint_cornersFlip() {
        // Bitmap top-left -> page top-left (y = page height).
        val (x0, y0) = PdfiumText.mapPointToPage(
            0f, 0f, 595f, 842f, 595, 842
        )
        assertEquals(0f, x0, 0.001f)
        assertEquals(842f, y0, 0.001f)
        // Bitmap bottom-right -> page bottom-right (y = 0).
        val (x1, y1) = PdfiumText.mapPointToPage(
            595f, 842f, 595f, 842f, 595, 842
        )
        assertEquals(595f, x1, 0.001f)
        assertEquals(0f, y1, 0.001f)
    }
}
