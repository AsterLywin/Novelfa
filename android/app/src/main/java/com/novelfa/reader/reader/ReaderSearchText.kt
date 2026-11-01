package com.novelfa.reader.reader

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
}
