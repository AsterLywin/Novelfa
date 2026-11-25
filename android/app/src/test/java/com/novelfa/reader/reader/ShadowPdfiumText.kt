package com.novelfa.reader.reader

import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.io.File

/**
 * Fake FPDFText bridge for Robolectric tests (standing rule,
 * 2026-11-25: custom shadows cover what Robolectric cannot —
 * native code). It replaces ONLY the nine private JNI methods
 * of [PdfiumText]; every public Kotlin function — the
 * `available` guard, the `doc != 0L` checks, the rect mapping
 * — runs for real, so the tests exercise the real logic with
 * a fake native layer underneath.
 *
 * With this shadow registered, the bridge reads as available
 * under Robolectric and the natives run — the real guard
 * passes and the fake answers flow. Do NOT try to flip
 * `available` off from a test: the guard is a static field
 * read inside PdfiumText's own methods, and a shadow cannot
 * change what that read sees (proven 2026-11-25: setting the
 * shadow's flag made the external getter read false while
 * openDocument still returned a handle). The degraded path
 * (bridge absent → searcher unavailable, empty results) is
 * covered where it belongs, on the plain JVM, by
 * PdfSearchTest.searcher_degradedWithoutNativeLibrary.
 *
 * The fake models the REAL contract, including its sharp
 * edges, so a test can tell a correct use from a crash:
 *  - a document is 3 pages of canned text ("hello world
 *    hello" / "foo bar baz" / "hello again"), Letter size;
 *    [nFindAll] does a real case-insensitive substring
 *    search over that text, like FPDFText_FindStart;
 *  - opening a file that does not exist returns 0, like
 *    FPDF_LoadDocument failing — a normal failure, no fault;
 *  - closing a handle twice, or calling any method on a
 *    handle that was already closed, records a fault: the
 *    real nCloseDocument has NO double-close guard, and a
 *    second FPDF_CloseDocument is a native double-free;
 *  - [destroyLibrary] stands in for PdfiumCore.closeDocument
 *    → FPDF_DestroyLibrary (the render side, which cannot
 *    run on the JVM at all: PdfiumCore's static init loads
 *    five .so files uncaught, and loadLibrary throws under
 *    Robolectric — the `available=false` probe, 2026-11-25).
 *    After it, ANY native call on an open bridge doc records
 *    a fault: that is the PDF reopen crash (c1941cf0) — the
 *    viewer used to destroy the library while the bridge
 *    doc was still open, and the fix closes the searcher
 *    FIRST. The reopen tests assert exactly that split.
 *
 * State is static and MUST be reset per test ([reset]); a
 * fresh Robolectric sandbox usually gives fresh statics, but
 * the reset is what makes the tests independent.
 */
@Implements(PdfiumText::class)
class ShadowPdfiumText {

    @Implementation
    protected fun nOpenDocument(path: String, password: String?): Long {
        if (libraryDestroyed) {
            faults += "open after library destroy ($path)"
            return 0L
        }
        // FPDF_LoadDocument fails on a missing file: a normal
        // failure, not a fault.
        if (!File(path).exists()) return 0L
        val handle = nextHandle++
        openDocs[handle] = FakeDoc(
            path,
            listOf("hello world hello", "foo bar baz", "hello again")
        )
        return handle
    }

    @Implementation
    protected fun nCloseDocument(doc: Long) {
        if (libraryDestroyed) {
            faults += "close after library destroy (handle $doc)"
            return
        }
        if (openDocs.remove(doc) == null) {
            faults += "close of unknown handle $doc (double close?)"
            return
        }
        closeCalls++
    }

    @Implementation
    protected fun nGetPageCount(doc: Long): Int {
        if (libraryDestroyed) {
            faults += "pageCount after library destroy (handle $doc)"
            return 0
        }
        return openDocs[doc]?.texts?.size ?: run {
            faults += "pageCount on closed handle $doc"
            0
        }
    }

    @Implementation
    protected fun nGetPageSize(doc: Long, page: Int): FloatArray {
        if (libraryDestroyed) {
            faults += "pageSize after library destroy (handle $doc)"
            return floatArrayOf()
        }
        if (openDocs[doc] == null) {
            faults += "pageSize on closed handle $doc"
            return floatArrayOf()
        }
        // Letter, in PDF points.
        return floatArrayOf(612f, 792f)
    }

    @Implementation
    protected fun nGetPageText(doc: Long, page: Int): String? {
        if (libraryDestroyed) {
            faults += "pageText after library destroy (handle $doc)"
            return null
        }
        val texts = openDocs[doc]?.texts ?: run {
            faults += "pageText on closed handle $doc"
            return null
        }
        return texts.getOrNull(page)
    }

    @Implementation
    protected fun nGetRangeRects(
        doc: Long,
        page: Int,
        start: Int,
        count: Int
    ): FloatArray {
        if (libraryDestroyed) {
            faults += "rangeRects after library destroy (handle $doc)"
            return floatArrayOf()
        }
        if (openDocs[doc] == null) {
            faults += "rangeRects on closed handle $doc"
            return floatArrayOf()
        }
        // One rect, PDF points, y-up — valid and non-empty.
        return floatArrayOf(72f, 700f, 200f, 720f)
    }

    @Implementation
    protected fun nFindAll(
        doc: Long,
        page: Int,
        query: String,
        matchCase: Boolean,
        wholeWord: Boolean
    ): IntArray {
        if (libraryDestroyed) {
            faults += "findAll after library destroy (handle $doc)"
            return intArrayOf()
        }
        val texts = openDocs[doc]?.texts ?: run {
            faults += "findAll on closed handle $doc"
            return intArrayOf()
        }
        val text = texts.getOrNull(page) ?: return intArrayOf()
        if (query.isEmpty()) return intArrayOf()
        // Real substring search over the fake text, like
        // FPDFText_FindStart/FindNext — case-insensitive
        // unless matchCase, whole-word matching skipped
        // (no fake page has a word-boundary case for it).
        val hay = if (matchCase) text else text.lowercase()
        val needle = if (matchCase) query else query.lowercase()
        val out = ArrayList<Int>()
        var from = 0
        while (true) {
            val at = hay.indexOf(needle, from)
            if (at < 0) break
            out += at
            out += needle.length
            from = at + needle.length
        }
        return out.toIntArray()
    }

    @Implementation
    protected fun nGetCharIndexAtPos(
        doc: Long,
        page: Int,
        x: Double,
        y: Double
    ): Int {
        if (libraryDestroyed || openDocs[doc] == null) return -1
        return 0
    }

    @Implementation
    protected fun nGetLastError(): Int = 0

    companion object {
        /** Set by [destroyLibrary]: the render doc destroyed FPDF. */
        var libraryDestroyed: Boolean = false

        /** Every recorded native misuse (double close, use after close, any call after destroy). */
        val faults = mutableListOf<String>()

        /** Successful nCloseDocument calls. */
        var closeCalls: Int = 0

        private var nextHandle = 1000L
        private val openDocs = LinkedHashMap<Long, FakeDoc>()

        private data class FakeDoc(val path: String, val texts: List<String>)

        /** Stands in for PdfiumCore.closeDocument → FPDF_DestroyLibrary. */
        fun destroyLibrary() {
            libraryDestroyed = true
        }

        fun reset() {
            libraryDestroyed = false
            faults.clear()
            closeCalls = 0
            nextHandle = 1000L
            openDocs.clear()
        }
    }
}
