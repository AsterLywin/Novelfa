package com.novelfa.reader.data

import org.junit.Assert.*
import org.junit.Test

class ProgressTest {
    @Test
    fun progress_formulaMatchesWpf() {
        val e = BookEntity(id = "1", title = "t", filePath = "f", totalChapters = 10, lastChapterIndex = 2, dateAdded = "x", lastOpenedDate = "y")
        assertEquals(30.0, progressPercent(e), 0.001)
        assertEquals(30, percentDisplay(e))
    }
    @Test
    fun zeroChapters_isZero() {
        val e = BookEntity(id = "1", title = "t", filePath = "f", totalChapters = 0, dateAdded = "x")
        assertEquals(0.0, progressPercent(e), 0.001)
    }
}
