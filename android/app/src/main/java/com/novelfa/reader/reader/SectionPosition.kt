package com.novelfa.reader.reader

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Exact in-section reading position for vertical (continuous) mode:
 * the chapter at the viewport top + the fraction of that chapter's
 * height above the viewport top.
 *
 * A fraction survives font/spacing changes (the chapter reflows but
 * the relative position holds), which a raw pixel offset cannot —
 * that is why vertical mode restores (chapter, fraction) instead of
 * the pixel scroll offset the native strip viewer keeps in
 * ReaderExtrasStore's off_ key. It is also what makes single-chapter
 * books work: with one chapter, the chapter index alone says nothing,
 * and the fraction is the whole position.
 *
 * Pure Kotlin (no Android dependency) so the math is unit-testable;
 * the WebView side only reports (chapter, fraction) and applies the
 * restore JS built by [HtmlBuilder.sectionRestoreJs].
 */
data class SectionPosition(val chapter: Int, val fraction: Float) {
    init {
        require(chapter >= 0) { "chapter must be >= 0" }
        require(fraction >= 0f && fraction < 1f) { "fraction must be in [0, 1)" }
    }
}

/**
 * Fraction of [chapterHeight] above the viewport top, clamped to
 * [0, 0.999]. A zero or negative height (empty chapter) is the top
 * of the chapter; a scroll past the chapter bottom clamps just short
 * of the next chapter so the position never names another chapter.
 */
fun sectionFraction(scrollTop: Int, chapterTop: Int, chapterHeight: Int): Float {
    if (chapterHeight <= 0) return 0f
    return ((scrollTop - chapterTop).toFloat() / chapterHeight).coerceIn(0f, 0.999f)
}

/**
 * Absolute scrollTop for [fraction] inside a chapter starting at
 * [chapterTop]. Mirrors the restore JS (top + fraction * height).
 */
fun sectionRestoreScrollTop(chapterTop: Int, chapterHeight: Int, fraction: Float): Int {
    if (chapterHeight <= 0) return chapterTop.coerceAtLeast(0)
    return chapterTop + (chapterHeight * fraction.coerceIn(0f, 0.999f)).roundToInt()
}

/** Stored form: "chapter|fraction" with 4 decimals (~1px on a 10kpx chapter). */
fun serializeSectionPosition(pos: SectionPosition): String =
    "${pos.chapter}|${"%.4f".format(Locale.US, pos.fraction)}"

/** Strict parse: anything malformed (or out of range) is no position, never a guess. */
fun parseSectionPosition(raw: String?): SectionPosition? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split("|")
    if (parts.size != 2) return null
    val chapter = parts[0].toIntOrNull() ?: return null
    val fraction = parts[1].toFloatOrNull() ?: return null
    if (chapter < 0 || fraction < 0f || fraction >= 1f) return null
    return SectionPosition(chapter, fraction)
}
