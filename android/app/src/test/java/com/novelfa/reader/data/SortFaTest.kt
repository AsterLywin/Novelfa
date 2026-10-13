package com.novelfa.reader.data

import org.junit.Assert.*
import org.junit.Test

class SortFaTest {
    @Test
    fun `جلد2_before_جلد10`() {
        assertTrue(SortFa.compare("جلد 2", "جلد 10") < 0)
    }
}
