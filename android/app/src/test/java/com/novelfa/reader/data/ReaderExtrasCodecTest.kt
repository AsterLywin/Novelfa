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
    fun cropRects_rejectsGarbage() {
        // Inverted rect, out-of-range fraction, bad page, bad
        // float: all dropped, the valid entry survives.
        val parsed = parseCropRects("3:0.9,0.1,0.2,0.8;4:0,0,1.5,1;x:0,0,1,1;7:0,0,1,1")
        assertEquals(setOf(7), parsed.keys)
        assertTrue(parseCropRects("not a rect").isEmpty())
    }
}
