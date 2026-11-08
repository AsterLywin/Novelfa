package com.novelfa.reader.reader

import kotlin.math.roundToInt

/**
 * Book-wide page numbers, ReadEra-style: the reader's position as
 * "page N of M" for the WHOLE book, not just the current chapter.
 *
 * ReadEra's engine lays out the whole book first and slices it into
 * pages, so its total is exact from the open. Novelfa paginates one
 * chapter at a time in the WebView, so a chapter's page count is only
 * known once that chapter has been paginated — the counts are cached
 * per chapter (see ReaderExtrasStore) and the chapters not yet
 * counted are ESTIMATED from their file size, calibrated on the
 * chapters already counted. [exact] is false while any chapter is
 * estimated; the pill shows "~" for the total then, and the estimate
 * sharpens into the exact total as the user reads.
 *
 * Pure Kotlin (no Android dependency) so the math is unit-testable;
 * the reader only reports counts and reads the cached map.
 */
data class BookPageNumbers(val page: Int, val total: Int, val exact: Boolean) {
    init {
        require(page >= 1) { "page must be >= 1" }
        require(total >= page) { "total must be >= page" }
    }
}

/**
 * Chapters smaller than this do not calibrate the pages-per-byte
 * ratio: a near-empty chapter (a divider, one paragraph) has almost
 * no text for its one page, so its density would drag the ratio
 * down and every estimate with it.
 */
internal const val MIN_CALIBRATION_BYTES = 1024L

/**
 * Pages per byte of chapter file, calibrated on the chapters whose
 * final page count is known. Null when no known chapter is big
 * enough to calibrate on — then no book-wide total can be computed
 * yet and the caller falls back to chapter-local numbers.
 */
fun pagesPerByte(counts: Map<Int, Int>, chapterBytes: List<Long>): Double? {
    var pages = 0L
    var bytes = 0L
    for ((chapter, count) in counts) {
        if (chapter < 0 || count <= 0) continue
        val b = chapterBytes.getOrNull(chapter) ?: continue
        if (b < MIN_CALIBRATION_BYTES) continue
        pages += count
        bytes += b
    }
    if (bytes <= 0L || pages <= 0L) return null
    return pages.toDouble() / bytes.toDouble()
}

/**
 * Final page count for one chapter: the cached count when known,
 * else the calibrated estimate (at least 1 page — an empty chapter
 * is still one blank page, and a chapter with no file size to
 * estimate from is one page too).
 */
fun chapterPageCount(
    counts: Map<Int, Int>,
    chapterBytes: List<Long>,
    chapter: Int,
    ratio: Double,
): Int {
    val known = counts[chapter]
    if (known != null && known > 0) return known
    val bytes = chapterBytes.getOrNull(chapter) ?: 0L
    if (bytes <= 0L) return 1
    return (bytes * ratio).roundToInt().coerceAtLeast(1)
}

/**
 * Book-wide (page, total, exact) for [currentChapterPage] (1-based)
 * inside [currentChapter]. Null when the total cannot be computed
 * yet (no calibration — see [pagesPerByte]).
 *
 * The current page is clamped into its chapter: a live page number
 * from a repagination racing a stale cached count must not push
 * the book position past the book's end.
 */
fun bookPageNumbers(
    counts: Map<Int, Int>,
    chapterBytes: List<Long>,
    currentChapter: Int,
    currentChapterPage: Int,
): BookPageNumbers? {
    if (chapterBytes.isEmpty()) return null
    val ratio = pagesPerByte(counts, chapterBytes) ?: return null
    val chapter = currentChapter.coerceIn(0, chapterBytes.size - 1)
    var total = 0
    var before = 0
    for (i in chapterBytes.indices) {
        val n = chapterPageCount(counts, chapterBytes, i, ratio)
        total += n
        if (i < chapter) before += n
    }
    val chapterPages = chapterPageCount(counts, chapterBytes, chapter, ratio)
    val pageInChapter = currentChapterPage.coerceIn(1, chapterPages)
    val exact = chapterBytes.indices.all { counts[it] != null && counts[it]!! > 0 }
    return BookPageNumbers(page = before + pageInChapter, total = total, exact = exact)
}

/**
 * Page inside a chapter at [fraction] of its height (the vertical
 * section fraction): the page whose top the viewport is showing.
 * Fraction 0 is page 1; fraction just under 1 is the last page.
 */
fun pageWithinChapter(fraction: Float, chapterPages: Int): Int {
    if (chapterPages <= 1) return 1
    val f = fraction.coerceIn(0f, 0.999f)
    return (f * chapterPages).toInt().coerceIn(0, chapterPages - 1) + 1
}
