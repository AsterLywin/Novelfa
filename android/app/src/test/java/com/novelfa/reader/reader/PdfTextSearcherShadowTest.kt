package com.novelfa.reader.reader

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PdfTextSearcher end-to-end under the fake native bridge
 * ([ShadowPdfiumText]): the real searcher, the real Kotlin
 * guards, only the nine JNI methods faked. This is the class
 * of bug Robolectric alone cannot see — the PDF reopen crash
 * (c1941cf0) was a native-lifetime bug: the viewer destroyed
 * the shared PDFium library while the bridge document was
 * still open, so the searcher's close faulted natively.
 *
 * The shadow models that fault ([ShadowPdfiumText.faults]):
 * any native call after [ShadowPdfiumText.destroyLibrary]
 * records one, exactly as a real FPDF call on a destroyed
 * library faults on device. The two reopen tests assert the
 * split the fix depends on: searcher closed FIRST, then the
 * library destroyed → clean; library destroyed first, then
 * the searcher closed → a fault. The wrong-order test is
 * the negative control: it proves the shadow would have
 * caught the original bug.
 *
 * The fake document is 3 pages ("hello world hello" /
 * "foo bar baz" / "hello again"); the file itself only has
 * to exist — the shadow checks existence, like
 * FPDF_LoadDocument failing on a missing file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowPdfiumText::class])
class PdfTextSearcherShadowTest {

    private lateinit var pdfFile: File

    @Before
    fun setUp() {
        ShadowPdfiumText.reset()
        pdfFile = File.createTempFile("shadow-pdf", ".pdf")
        pdfFile.writeBytes("%PDF-1.4 fake\n".toByteArray())
    }

    @After
    fun tearDown() {
        pdfFile.delete()
    }

    @Test
    fun searcher_findsFakeHits() {
        val searcher = PdfTextSearcher(pdfFile)
        assertTrue(searcher.available)
        assertEquals(3, searcher.pageCount)
        assertEquals(
            listOf(
                PdfSearchHit(0, 0, 5),
                PdfSearchHit(0, 12, 5),
                PdfSearchHit(2, 0, 5)
            ),
            searcher.searchAll("hello")
        )
        assertTrue(searcher.searchAll("missing").isEmpty())
        assertTrue(searcher.searchAll("HELLO").isNotEmpty())
        assertEquals(
            listOf(PdfiumText.MappedRect(72f, 72f, 200f, 92f)),
            searcher.hitRects(PdfSearchHit(0, 0, 5), 612, 792)
        )
        assertTrue(ShadowPdfiumText.faults.isEmpty())
        searcher.close()
    }

    @Test
    fun searcher_missingFileIsUnavailable() {
        val searcher = PdfTextSearcher(File("/nonexistent/book.pdf"))
        assertFalse(searcher.available)
        assertEquals(0, searcher.pageCount)
        assertTrue(searcher.searchAll("hello").isEmpty())
        searcher.close()
        assertTrue(ShadowPdfiumText.faults.isEmpty())
    }

    @Test
    fun searcher_closeIsIdempotent() {
        val searcher = PdfTextSearcher(pdfFile)
        assertTrue(searcher.available)
        searcher.close()
        searcher.close()
        assertFalse(searcher.available)
        // One native close, no double-free fault: the viewer's
        // dispose effect and the searcher's own effect both
        // close it, and the second must be a no-op.
        assertEquals(1, ShadowPdfiumText.closeCalls)
        assertTrue(ShadowPdfiumText.faults.isEmpty())
    }

    @Test
    fun reopenCrash_correctCloseOrderIsClean() {
        // The fixed viewer order (c1941cf0): the bridge doc
        // dies BEFORE the render document destroys the shared
        // library. No native call ever touches a destroyed
        // library, so nothing faults.
        val searcher = PdfTextSearcher(pdfFile)
        searcher.close()
        ShadowPdfiumText.destroyLibrary()
        assertTrue(ShadowPdfiumText.faults.isEmpty())
    }

    @Test
    fun reopenCrash_wrongCloseOrderFaults() {
        // The original bug, replayed: the library is destroyed
        // while the bridge doc is still open, and the searcher's
        // close then faults natively. This is the negative
        // control — it proves the shadow models the crash the
        // fixed order avoids.
        val searcher = PdfTextSearcher(pdfFile)
        ShadowPdfiumText.destroyLibrary()
        searcher.close()
        assertEquals(0, ShadowPdfiumText.closeCalls)
        assertEquals(1, ShadowPdfiumText.faults.size)
        assertTrue(
            ShadowPdfiumText.faults[0]
                .startsWith("close after library destroy")
        )
    }

    // The degraded path (bridge absent → searcher unavailable,
    // empty results) is NOT tested here: the `available` guard
    // is a static field read inside PdfiumText's own methods,
    // and a shadow cannot flip what that read sees (proven
    // 2026-11-25). It is covered on the plain JVM by
    // PdfSearchTest.searcher_degradedWithoutNativeLibrary; the
    // missing-file test above covers the same shape (open
    // fails → unavailable) through this shadow.

    @Test
    fun searcher_searchAfterClose_touchesFreedHandle() {
        // Sharp edge, documented not fixed: only `available`
        // checks `closed` — searchAll/pageCount do not. A
        // search after close calls FPDF on a freed handle,
        // which faults natively. The viewer must never do
        // this; the shadow proves the fault is detectable.
        val searcher = PdfTextSearcher(pdfFile)
        searcher.close()
        assertTrue(searcher.searchAll("hello").isEmpty())
        assertEquals(1, ShadowPdfiumText.faults.size)
        assertTrue(
            ShadowPdfiumText.faults[0]
                .startsWith("pageCount on closed handle")
        )
    }
}
