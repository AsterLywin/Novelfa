package com.novelfa.reader.reader

/**
 * One whole-book search hit: a single occurrence, not a chapter.
 * [page] is the 1-based page inside the chapter in paged mode —
 * known only once that chapter is loaded and paginated, so it is
 * null until the JS reports it (and always null in vertical mode,
 * which has no pages); the list shows the chapter title instead
 * then.
 */
data class SearchResult(
    val chapter: Int,
    val matchIndex: Int,
    val snippet: String,
    val matchStart: Int,
    val matchLength: Int,
    val page: Int? = null
)

/**
 * Pure, JVM-unit-testable text helpers for whole-book search.
 * [plainText] mirrors what the reader document shows: tags are
 * stripped (their text kept verbatim, so whitespace matches DOM
 * textContent) and entities are decoded the way a browser decodes
 * them, so per-chapter match counts agree with the in-chapter JS
 * search (exact navigation re-runs the JS search anyway, so a
 * small mismatch here could only mis-count a chapter row, never
 * mis-navigate).
 */
object ReaderSearchText {

    private val tagRegex = Regex("<[^>]*>")
    private val namedEntities = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "hellip" to "…",
        "mdash" to "—",
        "ndash" to "–",
        "laquo" to "«",
        "raquo" to "»"
    )
    private val numericEntityRegex = Regex("&#(x[0-9A-Fa-f]+|[0-9]+);")
    private val namedEntityRegex = Regex("&([A-Za-z]+);")

    fun stripTags(html: String): String = tagRegex.replace(html, "")

    fun decodeEntities(s: String): String {
        var out = numericEntityRegex.replace(s) { m ->
            val num = m.groupValues[1]
            val code = if (num.startsWith("x") || num.startsWith("X")) {
                num.drop(1).toIntOrNull(16)
            } else {
                num.toIntOrNull()
            }
            if (code != null && code in 1..0x10FFFF) {
                String(Character.toChars(code))
            } else {
                m.value
            }
        }
        out = namedEntityRegex.replace(out) { m ->
            namedEntities[m.groupValues[1]] ?: m.value
        }
        return out
    }

    fun plainText(html: String): String = decodeEntities(stripTags(html))

    /** Non-overlapping, case-insensitive occurrence count. */
    fun countOccurrences(text: String, query: String): Int {
        if (query.isEmpty()) return 0
        val hay = text.lowercase()
        val needle = query.lowercase()
        var count = 0
        var at = 0
        while (true) {
            val found = hay.indexOf(needle, at)
            if (found < 0) return count
            count++
            at = found + needle.length
        }
    }

    /**
     * Non-overlapping, case-insensitive occurrence offsets in
     * document order. [limit] stops early once that many offsets
     * are found (whole-book search caps its result list, so it
     * never needs the tail of a very common query).
     */
    fun findOccurrences(text: String, query: String, limit: Int = Int.MAX_VALUE): List<Int> {
        if (query.isEmpty() || limit <= 0) return emptyList()
        val hay = text.lowercase()
        val needle = query.lowercase()
        val out = mutableListOf<Int>()
        var at = 0
        while (out.size < limit) {
            val found = hay.indexOf(needle, at)
            if (found < 0) break
            out += found
            at = found + needle.length
        }
        return out
    }

    /** Whole-book results list cap: the overview list never grows past this many rows. */
    const val MAX_SEARCH_RESULTS = 500

    /** Snippet window: this many plain-text chars on each side of the match. */
    const val SEARCH_SNIPPET_RADIUS = 60

    /**
     * Snippet around the occurrence at [offset]: [SEARCH_SNIPPET_RADIUS]
     * chars on each side, cut at word boundaries (never mid-word),
     * whitespace runs collapsed to one space (chapter plain text
     * carries block newlines), "…" where text was cut. Returns the
     * snippet and the match start inside it.
     */
    fun snippetFor(text: String, offset: Int, queryLength: Int): Pair<String, Int> {
        if (text.isEmpty() || queryLength <= 0) return "" to 0
        val off = offset.coerceIn(0, text.length)
        val end = (off + queryLength).coerceAtMost(text.length)
        var start = (off - SEARCH_SNIPPET_RADIUS).coerceAtLeast(0)
        var stop = (end + SEARCH_SNIPPET_RADIUS).coerceAtMost(text.length)
        if (start > 0) {
            // Move forward to just after the next space, so the
            // snippet never opens mid-word; keep the raw start
            // when no space follows (one long word).
            val space = text.indexOf(' ', start)
            if (space in start until off) start = space + 1
        }
        if (stop < text.length) {
            // Move back to just before the previous space, so the
            // snippet never ends mid-word.
            val space = text.lastIndexOf(' ', stop - 1)
            if (space in end until stop) stop = space
        }
        val window = text.substring(start, stop)
        val matchInWindow = off - start
        // Collapse whitespace runs to one space, tracking where
        // the match lands after the collapse. The query is trimmed
        // before searching, so the match always starts (and ends)
        // on a non-whitespace char — the collapse can never move
        // the match start onto a space.
        val collapsed = StringBuilder(window.length)
        var matchStart = 0
        var i = 0
        while (i < window.length) {
            val c = window[i]
            if (c.isWhitespace()) {
                if (collapsed.isNotEmpty() && collapsed.last() != ' ') collapsed.append(' ')
                while (i < window.length && window[i].isWhitespace()) i++
            } else {
                if (i == matchInWindow) matchStart = collapsed.length
                collapsed.append(c)
                i++
            }
        }
        val prefix = if (start > 0) "…" else ""
        val suffix = if (stop < text.length) "…" else ""
        return prefix + collapsed.toString().trimEnd() + suffix to (matchStart + prefix.length)
    }

    /**
     * Parses the `window.rdrSearchPages` result ("[0,2,-1]" — the
     * 0-based page of each in-chapter match, -1 when the match is
     * not inside a paginated page). Garbage yields an empty list.
     */
    fun parsePageList(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        val body = raw.trim().trim('"')
            .removePrefix("[").removeSuffix("]").trim()
        if (body.isEmpty()) return emptyList()
        return body.split(',').map { it.trim().toIntOrNull() ?: return emptyList() }
    }
}
