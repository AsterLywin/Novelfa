package com.novelfa.reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

class ReaderExtrasStore(private val context: Context) {

    private fun bookmarksKey(bookId: String) = stringPreferencesKey("bm_$bookId")
    private fun scrollOffsetKey(bookId: String) = intPreferencesKey("off_$bookId")

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
}
