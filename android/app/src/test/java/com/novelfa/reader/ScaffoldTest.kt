package com.novelfa.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaffoldTest {
    @Test
    fun routes_areLocked() {
        assertEquals("library", Routes.LIBRARY)
        assertEquals("reader/{bookId}", Routes.READER)
        assertEquals("settings", Routes.SETTINGS)
    }
}
