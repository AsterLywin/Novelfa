package com.novelfa.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderExtrasCodecTest {

    @Test
    fun bookmarks_roundTrip() {
        assertEquals(setOf(3, 7, 12), parseBookmarkPages(serializeBookmarkPages(setOf(12, 3, 7))))
        assertEquals(emptySet<Int>(), parseBookmarkPages(null))
        assertEquals(emptySet<Int>(), parseBookmarkPages(""))
        assertEquals(setOf(5), parseBookmarkPages("5,abc,-1,5"))
    }

    @Test
    fun cropRects_roundTrip() {
        val rects = mapOf(
            2 to CropRect(0.1f, 0.2f, 0.9f, 0.8f),
            5 to CropRect(0f, 0f, 1f, 1f)
        )
        val parsed = parseCropRects(serializeCropRects(rects))
        assertEquals(2, parsed.size)
        assertEquals(0.1f, parsed.getValue(2).left, 0.0001f)
        assertEquals(0.8f, parsed.getValue(2).bottom, 0.0001f)
        assertEquals(1f, parsed.getValue(5).right, 0.0001f)
    }

    @Test
    fun highlights_roundTrip() {
        val list = listOf(
            TextHighlight(2, 10, 40, "#FFE45E"),
            TextHighlight(0, 0, 5, "#F8BBD0")
        )
        assertEquals(
            listOf(
                TextHighlight(0, 0, 5, "#F8BBD0"),
                TextHighlight(2, 10, 40, "#FFE45E")
            ),
            parseHighlights(serializeHighlights(list))
        )
        assertEquals(emptyList<TextHighlight>(), parseHighlights(null))
        assertEquals(emptyList<TextHighlight>(), parseHighlights(""))
    }

    @Test
    fun highlights_rejectsGarbage() {
        // Inverted range, bad offset, non-hex color: all dropped,
        // the valid entry survives.
        val parsed = parseHighlights(
            "1:10-5:#FFE45E;2:abc-9:#FFE45E;3:0-9:yellow;4:0-9:#F8BBD0"
        )
        assertEquals(listOf(TextHighlight(4, 0, 9, "#F8BBD0")), parsed)
    }

    @Test
    fun cropRects_rejectsGarbage() {
        // Inverted rect, out-of-range fraction, bad page, bad
        // float: all dropped, the valid entry survives.
        val parsed = parseCropRects("3:0.9,0.1,0.2,0.8;4:0,0,1.5,1;x:0,0,1,1;7:0,0,1,1")
        assertEquals(setOf(7), parsed.keys)
        assertTrue(parseCropRects("not a rect").isEmpty())
    }

    @Test
    fun searchHistory_roundTrip() {
        val list = listOf("hello world", "a;b", "کتاب", "quote\"x", "semi;colon")
        assertEquals(list, parseSearchHistory(serializeSearchHistory(list)))
        assertEquals(emptyList<String>(), parseSearchHistory(null))
        assertEquals(emptyList<String>(), parseSearchHistory(""))
        assertEquals(emptyList<String>(), parseSearchHistory(";;;"))
    }

    @Test
    fun searchHistory_addDedupesAndCaps() {
        assertEquals(listOf("c", "b", "a"), addToSearchHistory(listOf("b", "a"), "c"))
        // Case-insensitive duplicate moves to the top.
        assertEquals(listOf("book", "x"), addToSearchHistory(listOf("Book", "x"), "book"))
        // Blank queries are ignored; surrounding space is trimmed.
        assertEquals(listOf("a"), addToSearchHistory(listOf("a"), "   "))
        assertEquals(listOf("q", "a"), addToSearchHistory(listOf("a"), "  q  "))
        // Capped at 10, newest first, oldest dropped.
        val ten = (1..10).map { "q$it" }
        assertEquals(
            listOf("new") + ten.take(9),
            addToSearchHistory(ten, "new")
        )
    }

    @Test
    fun chapterPageCounts_roundTrip() {
        val counts = mapOf(0 to 12, 3 to 8, 7 to 21)
        assertEquals(counts, parseChapterPageCounts(serializeChapterPageCounts(counts)))
        assertEquals(emptyMap<Int, Int>(), parseChapterPageCounts(null))
        assertEquals(emptyMap<Int, Int>(), parseChapterPageCounts(""))
        // Malformed entries are dropped, never a guess; zero-page
        // chapters are dropped too (the pager guarantees >= 1 page).
        assertEquals(
            mapOf(5 to 4),
            parseChapterPageCounts("5:4,x:3,2:0,-1:7,9:,3:abc,5:4")
        )
    }
}
