package com.novelfa.reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novelfa.reader.reader.SectionPosition
import com.novelfa.reader.reader.parseSectionPosition
import com.novelfa.reader.reader.serializeSectionPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Base64

/**
 * Small per-book reader extras for the native viewer, kept out
 * of Room on purpose: bookmarks and the exact vertical scroll
 * offset are viewer state, not library data, and a Room column
 * would force a database migration (with a destructive fallback
 * risking the whole library) for two tiny values. A separate
 * DataStore file holds both, keyed by book id:
 *
 * - bookmarks: a comma-separated page list ("3,7,12"), empty =
 *   none. Page indices, never titles — the book's own pages
 *   are the stable identity here.
 * - scroll offset: the vertical strip's first-visible-item
 *   scroll offset in pixels, so reopening a strip lands exactly
 *   where it was, not at the top of the page (ComicScreen
 *   tracks the fractional page offset for the same reason).
 */
private val Context.readerExtrasDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "reader_extras")

/**
 * One user highlight in the text reader. Offsets are character
 * offsets in the chapter's plain text (text nodes concatenated in
 * document order): pagination re-slices the same text without
 * losing or duplicating a character, so the offsets survive
 * rebuilds, font/spacing changes, and mode switches. [color] is a
 * #RRGGBB hex string.
 */
data class TextHighlight(
    val chapter: Int,
    val start: Int,
    val end: Int,
    val color: String
)

/** Pure highlight-list codec, unit-testable without DataStore. Format: "chapter:start-end:#RRGGBB;…". */
internal fun parseHighlights(raw: String?): List<TextHighlight> {
    if (raw.isNullOrBlank()) return emptyList()
    val out = mutableListOf<TextHighlight>()
    for (entry in raw.split(';')) {
        val parts = entry.split(':')
        if (parts.size != 3) continue
        val chapter = parts[0].toIntOrNull() ?: continue
        val range = parts[1].split('-')
        if (range.size != 2) continue
        val start = range[0].toIntOrNull() ?: continue
        val end = range[1].toIntOrNull() ?: continue
        val color = parts[2]
        if (chapter < 0 || start < 0 || end <= start) continue
        if (!Regex("^#[0-9A-Fa-f]{6}$").matches(color)) continue
        out += TextHighlight(chapter, start, end, color)
    }
    return out.sortedWith(compareBy({ it.chapter }, { it.start }))
}

internal fun serializeHighlights(highlights: List<TextHighlight>): String {
    return highlights.sortedWith(compareBy({ it.chapter }, { it.start }))
        .joinToString(";") { "${it.chapter}:${it.start}-${it.end}:${it.color}" }
}

/**
 * One saved quote in the text reader (ReadEra's Quote action):
 * the selected text plus the same chapter char offsets a
 * highlight carries, so the quotes list can jump back to the
 * passage. [createdAt] is epoch millis, the list's display
 * order tiebreak (newest last). The text is stored, not
 * re-derived: re-parsing the chapter to recover it would fail
 * whenever the file changed since the quote was saved.
 */
data class TextQuote(
    val chapter: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val createdAt: Long
)

/**
 * Pure quote-list codec, unit-testable without DataStore.
 * Format: "chapter:start-end:createdAt:base64;…" — the text is
 * Base64 (java.util, no Android dependency, so the codec runs
 * on the JVM harness) because raw text may contain ';' and ':',
 * the codec's own separators. Malformed entries are dropped,
 * never guessed; the list sorts by (chapter, start, createdAt).
 */
internal fun parseQuotes(raw: String?): List<TextQuote> {
    if (raw.isNullOrBlank()) return emptyList()
    val out = mutableListOf<TextQuote>()
    for (entry in raw.split(';')) {
        val parts = entry.split(':')
        if (parts.size != 4) continue
        val chapter = parts[0].toIntOrNull() ?: continue
        val range = parts[1].split('-')
        if (range.size != 2) continue
        val start = range[0].toIntOrNull() ?: continue
        val end = range[1].toIntOrNull() ?: continue
        val createdAt = parts[2].toLongOrNull() ?: continue
        val text = runCatching {
            String(Base64.getDecoder().decode(parts[3]), Charsets.UTF_8)
        }.getOrNull() ?: continue
        if (chapter < 0 || start < 0 || end <= start) continue
        if (text.isBlank()) continue
        out += TextQuote(chapter, start, end, text, createdAt)
    }
    return out.sortedWith(compareBy({ it.chapter }, { it.start }, { it.createdAt }))
}

internal fun serializeQuotes(quotes: List<TextQuote>): String {
    return quotes.sortedWith(compareBy({ it.chapter }, { it.start }, { it.createdAt }))
        .joinToString(";") {
            val b64 = Base64.getEncoder().encodeToString(it.text.toByteArray(Charsets.UTF_8))
            "${it.chapter}:${it.start}-${it.end}:${it.createdAt}:$b64"
        }
}

/** Pure bookmark-list codec, unit-testable without DataStore. */
internal fun parseBookmarkPages(raw: String?): Set<Int> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it >= 0 }
        .toSortedSet()
}

internal fun serializeBookmarkPages(pages: Set<Int>): String {
    return pages.sorted().joinToString(",")
}

/**
 * One text-reader bookmark: the chapter plus the in-section
 * fraction inside it — a [SectionPosition], not a page number.
 *
 * Identity decision (task 4a): the native viewer's `bm_` store
 * keys by page Int because a PDF/CBZ page IS a fixed original
 * page — a stable identity. In the text reader a paged-mode page
 * number is a pagination artifact: it moves with every font /
 * margin / spacing change, so it cannot identify a position.
 * (chapter, fraction) is the identity the vertical restore
 * already uses ([SectionPosition]) and survives reflow, so the
 * text reader bookmarks that — one bookmark per chapter (the
 * toggle's unit, like the native viewer's per-page toggle).
 * The `bm_` page store is untouched: native bookmark semantics
 * do not change.
 */
data class TextBookmark(val chapter: Int, val fraction: Float)

/**
 * Pure text-bookmark codec, unit-testable without DataStore.
 * Format: "chapter|fraction;…" — one [SectionPosition] per
 * entry, ';' separated like the highlight codec. Malformed
 * entries are dropped, never guessed; the list sorts by
 * chapter, so the dialog reads in book order.
 */
internal fun parseTextBookmarks(raw: String?): List<TextBookmark> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(';')
        .mapNotNull { parseSectionPosition(it.trim()) }
        .map { TextBookmark(it.chapter, it.fraction) }
        .sortedBy { it.chapter }
}

internal fun serializeTextBookmarks(bookmarks: List<TextBookmark>): String {
    return bookmarks.sortedBy { it.chapter }
        .joinToString(";") {
            serializeSectionPosition(SectionPosition(it.chapter, it.fraction))
        }
}

/**
 * Pure toggle over a bookmark list, one bookmark per chapter:
 * toggling a chapter that already has one removes it (wherever
 * the user now is inside that chapter); otherwise the current
 * position is added. An invalid (chapter, fraction) is ignored
 * — a toggle must never store a position that cannot restore.
 */
internal fun toggleTextBookmark(
    bookmarks: List<TextBookmark>,
    chapter: Int,
    fraction: Float
): List<TextBookmark> {
    if (bookmarks.any { it.chapter == chapter }) {
        return bookmarks.filterNot { it.chapter == chapter }
    }
    val pos = runCatching { SectionPosition(chapter, fraction) }.getOrNull()
        ?: return bookmarks
    return (bookmarks + TextBookmark(pos.chapter, pos.fraction))
        .sortedBy { it.chapter }
}

/**
 * A manual crop rect, in fractions of the displayed page.
 * A plain data class on purpose — not android.graphics.RectF
 * — so the codec below stays unit-testable on the JVM (local
 * unit tests run against stub android classes, and RectF
 * construction throws there). The viewer converts to RectF
 * only where the pipeline needs it, if at all.
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Pure manual-crop codec, unit-testable without DataStore.
 * Format: "page:left,top,right,bottom;…" with the rect in
 * FRACTIONS of the displayed page (0..1), so it survives
 * re-decodes at any size. Float.toString() is locale-free
 * (always '.'), so the stored text parses back anywhere.
 */
internal fun parseCropRects(raw: String?): Map<Int, CropRect> {
    if (raw.isNullOrBlank()) return emptyMap()
    val out = mutableMapOf<Int, CropRect>()
    for (entry in raw.split(';')) {
        val head = entry.substringBefore(':', "")
        val body = entry.substringAfter(':', "")
        val page = head.toIntOrNull() ?: continue
        val parts = body.split(',').mapNotNull { it.toFloatOrNull() }
        if (parts.size != 4 || page < 0) continue
        val (l, t, r, b) = parts
        if (l < 0f || t < 0f || r > 1f || b > 1f || r <= l || b <= t) continue
        out[page] = CropRect(l, t, r, b)
    }
    return out
}

internal fun serializeCropRects(rects: Map<Int, CropRect>): String {
    return rects.entries.sortedBy { it.key }.joinToString(";") { (page, r) ->
        "$page:${r.left},${r.top},${r.right},${r.bottom}"
    }
}

/**
 * Pure search-history codec, unit-testable without DataStore.
 * One entry per query, newest first, at most [MAX_SEARCH_HISTORY].
 * Queries are URL-encoded (UTF-8) before the ';' join, so any
 * text — ';', Persian, quotes, newlines — survives the round
 * trip; a stored entry that fails to decode is dropped.
 */
internal const val MAX_SEARCH_HISTORY = 10

internal fun parseSearchHistory(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(';').mapNotNull { entry ->
        runCatching {
            java.net.URLDecoder.decode(entry, "UTF-8")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }.take(MAX_SEARCH_HISTORY)
}

internal fun serializeSearchHistory(history: List<String>): String {
    return history.filter { it.isNotBlank() }.take(MAX_SEARCH_HISTORY)
        .joinToString(";") { java.net.URLEncoder.encode(it, "UTF-8") }
}

/** New query on top, case-insensitive duplicate removed, capped at [MAX_SEARCH_HISTORY]. Blank queries are ignored. */
internal fun addToSearchHistory(history: List<String>, query: String): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return history
    return (listOf(q) + history.filterNot { it.equals(q, ignoreCase = true) })
        .take(MAX_SEARCH_HISTORY)
}

/**
 * Per-chapter final page counts for one layout, stored as
 * "chapter:pages,chapter:pages" sorted by chapter. A malformed
 * entry is dropped, never a guess; a chapter with 0 pages is
 * dropped too (the pager guarantees at least 1 page per chapter).
 */
internal fun parseChapterPageCounts(raw: String?): Map<Int, Int> {
    if (raw.isNullOrBlank()) return emptyMap()
    val out = mutableMapOf<Int, Int>()
    for (entry in raw.split(",")) {
        val parts = entry.split(":")
        if (parts.size != 2) continue
        val chapter = parts[0].toIntOrNull() ?: continue
        val count = parts[1].toIntOrNull() ?: continue
        if (chapter < 0 || count <= 0) continue
        out[chapter] = count
    }
    return out
}

internal fun serializeChapterPageCounts(counts: Map<Int, Int>): String =
    counts.entries.filter { it.key >= 0 && it.value > 0 }
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}:${it.value}" }

class ReaderExtrasStore(private val context: Context) {

    private fun bookmarksKey(bookId: String) = stringPreferencesKey("bm_$bookId")
    private fun scrollOffsetKey(bookId: String) = intPreferencesKey("off_$bookId")
    private fun highlightsKey(bookId: String) = stringPreferencesKey("hl_$bookId")
    private fun zoomKey(bookId: String) = intPreferencesKey("zoom_$bookId")

    fun highlights(bookId: String): Flow<List<TextHighlight>> =
        context.readerExtrasDataStore.data.map {
            parseHighlights(it[highlightsKey(bookId)])
        }

    suspend fun setHighlights(bookId: String, highlights: List<TextHighlight>) {
        context.readerExtrasDataStore.edit { prefs ->
            prefs[highlightsKey(bookId)] = serializeHighlights(highlights)
        }
    }

    private fun quotesKey(bookId: String) = stringPreferencesKey("qt_$bookId")

    /** Saved quotes for [bookId], in (chapter, start) order. The `qt_` key is swept by [clearBook]. */
    fun quotes(bookId: String): Flow<List<TextQuote>> =
        context.readerExtrasDataStore.data.map {
            parseQuotes(it[quotesKey(bookId)])
        }

    suspend fun setQuotes(bookId: String, quotes: List<TextQuote>) {
        context.readerExtrasDataStore.edit { prefs ->
            prefs[quotesKey(bookId)] = serializeQuotes(quotes)
        }
    }

    /** Native-viewer zoom step index (0 = fit-width, see zoomFactors); 0 when never set. */
    fun zoomIndex(bookId: String): Flow<Int> =
        context.readerExtrasDataStore.data.map {
            (it[zoomKey(bookId)] ?: 0).coerceIn(0, 2)
        }

    suspend fun setZoomIndex(bookId: String, index: Int) {
        context.readerExtrasDataStore.edit { prefs ->
            prefs[zoomKey(bookId)] = index.coerceIn(0, 2)
        }
    }

    fun bookmarks(bookId: String): Flow<Set<Int>> =
        context.readerExtrasDataStore.data.map {
            parseBookmarkPages(it[bookmarksKey(bookId)])
        }

    suspend fun toggleBookmark(bookId: String, page: Int) {
        context.readerExtrasDataStore.edit { prefs ->
            val key = bookmarksKey(bookId)
            val current = parseBookmarkPages(prefs[key]).toMutableSet()
            if (page in current) current.remove(page) else current.add(page)
            prefs[key] = serializeBookmarkPages(current)
        }
    }

    // Text-reader bookmarks (chapter + in-section fraction, see
    // TextBookmark above): a separate key from the native viewer's
    // page bookmarks — the two identities must never share a
    // codec. One bookmark per chapter; toggling a chapter that
    // already has one removes it.
    private fun textBookmarksKey(bookId: String) = stringPreferencesKey("tbm_$bookId")

    fun textBookmarks(bookId: String): Flow<List<TextBookmark>> =
        context.readerExtrasDataStore.data.map {
            parseTextBookmarks(it[textBookmarksKey(bookId)])
        }

    suspend fun toggleTextBookmark(bookId: String, chapter: Int, fraction: Float) {
        context.readerExtrasDataStore.edit { prefs ->
            val key = textBookmarksKey(bookId)
            prefs[key] = serializeTextBookmarks(
                toggleTextBookmark(parseTextBookmarks(prefs[key]), chapter, fraction)
            )
        }
    }

    suspend fun removeTextBookmark(bookId: String, chapter: Int) {
        context.readerExtrasDataStore.edit { prefs ->
            val key = textBookmarksKey(bookId)
            prefs[key] = serializeTextBookmarks(
                parseTextBookmarks(prefs[key]).filterNot { it.chapter == chapter }
            )
        }
    }

    // Manual crop rects (ComicScreen's CropActivity, persisted:
    // there the rect lives only as a tag on the page's view and
    // dies with the session; here it is stored, so a page the
    // user cropped by hand stays cropped on reopen).
    private fun cropRectsKey(bookId: String) = stringPreferencesKey("crop_$bookId")

    fun cropRects(bookId: String): Flow<Map<Int, CropRect>> =
        context.readerExtrasDataStore.data.map {
            parseCropRects(it[cropRectsKey(bookId)])
        }

    suspend fun setCropRect(
        bookId: String,
        page: Int,
        rect: CropRect?
    ) {
        context.readerExtrasDataStore.edit { prefs ->
            val key = cropRectsKey(bookId)
            val current = parseCropRects(prefs[key]).toMutableMap()
            if (rect == null) current.remove(page) else current[page] = rect
            prefs[key] = serializeCropRects(current)
        }
    }

    fun scrollOffset(bookId: String): Flow<Int> =
        context.readerExtrasDataStore.data.map { it[scrollOffsetKey(bookId)] ?: 0 }

    suspend fun setScrollOffset(bookId: String, offsetPx: Int) {
        context.readerExtrasDataStore.edit { prefs ->
            prefs[scrollOffsetKey(bookId)] = offsetPx.coerceAtLeast(0)
        }
    }

    // Strip restore anchor: the FIRST visible strip item at the
    // last save. It is deliberately separate from the saved
    // progress page (Book.lastChapterIndex): since the leading-edge
    // fix the saved page is the furthest-reached page, and the
    // stored scroll offset belongs to the first visible item, not
    // to it — restoring to the progress page with the first
    // visible item's offset would land up to a page too far down.
    // Null = never saved (books last read before this fix, which
    // saved the anchor as the progress page itself).
    private fun stripAnchorKey(bookId: String) = intPreferencesKey("anc_$bookId")

    fun stripAnchorPage(bookId: String): Flow<Int?> =
        context.readerExtrasDataStore.data.map { it[stripAnchorKey(bookId)] }

    suspend fun setStripAnchorPage(bookId: String, page: Int) {
        context.readerExtrasDataStore.edit { prefs ->
            prefs[stripAnchorKey(bookId)] = page.coerceAtLeast(0)
        }
    }

    // In-book search history (ReadEra's recent-searches list):
    // the last queries for this book, newest first. Recorded on
    // submit, never on keystroke; the reader shows them under
    // the search field while no results are shown.
    private fun searchHistoryKey(bookId: String) = stringPreferencesKey("sh_$bookId")

    fun searchHistory(bookId: String): Flow<List<String>> =
        context.readerExtrasDataStore.data.map {
            parseSearchHistory(it[searchHistoryKey(bookId)])
        }

    suspend fun addSearchHistory(bookId: String, query: String) {
        context.readerExtrasDataStore.edit { prefs ->
            val key = searchHistoryKey(bookId)
            prefs[key] = serializeSearchHistory(
                addToSearchHistory(parseSearchHistory(prefs[key]), query)
            )
        }
    }

    suspend fun removeSearchHistory(bookId: String, query: String) {
        context.readerExtrasDataStore.edit { prefs ->
            val key = searchHistoryKey(bookId)
            prefs[key] = serializeSearchHistory(
                parseSearchHistory(prefs[key]).filterNot { it.equals(query, ignoreCase = true) }
            )
        }
    }

    /**
     * Per-chapter final page counts for one layout fingerprint (see
     * BookPageCount.kt): the book-wide total's cache. The key carries
     * the fingerprint's hash, so a font, spacing or viewport change
     * (rotation, fullscreen toggle) reads a different — possibly
     * empty — map instead of mixing counts from another layout, and
     * toggling back finds the old map again. Keys end in "_$bookId"
     * so clearBook's suffix sweep removes them like every other
     * per-book key. Only final totals are recorded (the pager
     * reports total 0 while building, and the reader only records
     * adopted reports), so a cached count is always a real count.
     */
    private fun chapterPageCountsKey(bookId: String, fingerprint: String) =
        stringPreferencesKey("pgc_${Integer.toHexString(fingerprint.hashCode())}_$bookId")

    fun chapterPageCounts(bookId: String, fingerprint: String): Flow<Map<Int, Int>> =
        context.readerExtrasDataStore.data.map {
            parseChapterPageCounts(it[chapterPageCountsKey(bookId, fingerprint)])
        }

    suspend fun setChapterPageCount(bookId: String, fingerprint: String, chapter: Int, count: Int) {
        if (chapter < 0 || count <= 0) return
        val key = chapterPageCountsKey(bookId, fingerprint)
        context.readerExtrasDataStore.edit { prefs ->
            val counts = parseChapterPageCounts(prefs[key]).toMutableMap()
            counts[chapter] = count
            prefs[key] = serializeChapterPageCounts(counts)
        }
    }

    /**
     * Exact in-section position for vertical (continuous) EPUB mode
     * (see SectionPosition.kt): "chapter|fraction". Unlike the pixel
     * off_ key above (native strip viewer only), a fraction survives
     * font/spacing reflows, so it is the vertical reader's restore
     * source of truth. Paged mode never writes it (its paged anchor
     * owns position there). Cleared by [clearBook]'s suffix sweep
     * like every other per-book key.
     */
    private fun sectionPositionKey(bookId: String) = stringPreferencesKey("sec_$bookId")

    fun sectionPosition(bookId: String): Flow<SectionPosition?> =
        context.readerExtrasDataStore.data.map {
            parseSectionPosition(it[sectionPositionKey(bookId)])
        }

    suspend fun setSectionPosition(bookId: String, chapter: Int, fraction: Float) {
        val pos = runCatching { SectionPosition(chapter, fraction) }.getOrNull() ?: return
        context.readerExtrasDataStore.edit { prefs ->
            prefs[sectionPositionKey(bookId)] = serializeSectionPosition(pos)
        }
    }

    /** Removes only the in-section position (progress reset keeps bookmarks/highlights). */
    suspend fun clearSectionPosition(bookId: String) {
        context.readerExtrasDataStore.edit { prefs ->
            prefs.remove(sectionPositionKey(bookId))
        }
    }

    /**
     * Phase 2 (DB-03): delete EVERY per-book entry for [bookId] in
     * one edit — bookmarks, scroll offset, highlights, zoom and
     * crop rects — plus a sweep for any other key ending in this
     * id, so "no key with this id remains" holds even if a new
     * per-book key is added later without updating this function.
     * Idempotent: clearing a book that has no keys is a no-op.
     * Called from book removal for public AND private books.
     */
    suspend fun clearBook(bookId: String) {
        context.readerExtrasDataStore.edit { prefs ->
            prefs.remove(bookmarksKey(bookId))
            prefs.remove(textBookmarksKey(bookId))
            prefs.remove(scrollOffsetKey(bookId))
            prefs.remove(highlightsKey(bookId))
            prefs.remove(quotesKey(bookId))
            prefs.remove(zoomKey(bookId))
            prefs.remove(cropRectsKey(bookId))
            prefs.remove(searchHistoryKey(bookId))
            prefs.asMap().keys
                .filter { it.name.endsWith("_$bookId") }
                .forEach { prefs.remove(it) }
        }
    }
}
