package com.novelfa.reader.data

import org.junit.Assert.*
import org.junit.Test

class SortFaTest {
    @Test
    fun `جلد2_before_جلد10`() {
        assertTrue(SortFa.compare("جلد 2", "جلد 10") < 0)
    }

    @Test
    fun `persian zero-padded number sorts by value not length`() {
        // "۰۵" (5) used to rank above "۶" (6): only ASCII '0' was
        // trimmed, so the length comparison saw 2 digits vs 1.
        assertTrue(SortFa.compare("جلد ۰۵", "جلد ۶") < 0)
        assertTrue(SortFa.compare("جلد ۰۹", "جلد ۱۰") < 0)
    }
}
