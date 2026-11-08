package com.novelfa.reader.reader

import com.novelfa.reader.data.TextBookmark
import com.novelfa.reader.data.parseTextBookmarks
import com.novelfa.reader.data.serializeTextBookmarks
import com.novelfa.reader.data.toggleTextBookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests (JVM) for the text-reader bookmark codec
 * (task 4a): the stored "chapter|fraction;…" form and the
 * one-bookmark-per-chapter toggle. The DataStore side is not
 * exercised here — the store only persists what this codec
 * produces, and the phone test then only has to confirm the
 * toggle writes and the list jumps, not re-derive the codec.
 */
class TextBookmarkCodecTest {

    @Test
    fun roundTrip_sortedByChapter() {
        val list = listOf(
            TextBookmark(5, 0.25f),
            TextBookmark(1, 0.5f),
            TextBookmark(3, 0.75f)
        )
        assertEquals(
            listOf(
                TextBookmark(1, 0.5f),
                TextBookmark(3, 0.75f),
                TextBookmark(5, 0.25f)
            ),
            parseTextBookmarks(serializeTextBookmarks(list))
        )
    }

    @Test
    fun parse_emptyInputs() {
        assertEquals(emptyList<TextBookmark>(), parseTextBookmarks(null))
        assertEquals(emptyList<TextBookmark>(), parseTextBookmarks(""))
        assertEquals(emptyList<TextBookmark>(), parseTextBookmarks(";;;"))
    }

    @Test
    fun parse_malformedEntriesDropped() {
        // "bad" is not a position, 1.5 is out of the [0, 1)
        // fraction range, -1 is not a chapter, "abc" is not a
        // fraction — each is dropped, never guessed.
        val parsed = parseTextBookmarks("2|0.5000;bad;3|1.5000;1|abc;-1|0.5000")
        assertEquals(listOf(TextBookmark(2, 0.5f)), parsed)
    }

    @Test
    fun serialize_formatIsChapterFraction() {
        assertEquals(
            "2|0.5000",
            serializeTextBookmarks(listOf(TextBookmark(2, 0.5f)))
        )
        assertEquals("", serializeTextBookmarks(emptyList()))
    }

    @Test
    fun toggle_addsWhenAbsent() {
        val next = toggleTextBookmark(emptyList(), 2, 0.5f)
        assertEquals(listOf(TextBookmark(2, 0.5f)), next)
    }

    @Test
    fun toggle_removesSameChapterAtAnyFraction() {
        // One bookmark per chapter: toggling the chapter again
        // removes it, even from a different position inside it.
        val list = listOf(TextBookmark(2, 0.25f))
        assertEquals(emptyList<TextBookmark>(), toggleTextBookmark(list, 2, 0.8f))
    }

    @Test
    fun toggle_keepsOtherChaptersAndSorts() {
        val list = listOf(TextBookmark(1, 0.1f), TextBookmark(5, 0.9f))
        val next = toggleTextBookmark(list, 3, 0.5f)
        assertEquals(
            listOf(
                TextBookmark(1, 0.1f),
                TextBookmark(3, 0.5f),
                TextBookmark(5, 0.9f)
            ),
            next
        )
    }

    @Test
    fun toggle_invalidPositionIgnored() {
        val list = listOf(TextBookmark(1, 0.1f))
        assertEquals(list, toggleTextBookmark(list, 2, 1.5f))
        assertEquals(list, toggleTextBookmark(list, -1, 0.5f))
        assertTrue(toggleTextBookmark(list, 2, -0.1f) == list)
    }
}
