package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSearchTextTest {

    @Test
    fun stripTags_keepsTextVerbatim() {
        assertEquals("Hello World", ReaderSearchText.stripTags("<p>Hello <b>World</b></p>"))
        // No space is invented for a tag: DOM textContent agrees.
        assertEquals("ab", ReaderSearchText.stripTags("a<br/>b"))
    }

    @Test
    fun decodeEntities_commonAndNumeric() {
        assertEquals("a & b < c", ReaderSearchText.decodeEntities("a &amp; b &lt; c"))
        assertEquals("Aé", ReaderSearchText.decodeEntities("A&#233;"))
        assertEquals("Aé", ReaderSearchText.decodeEntities("A&#xE9;"))
        assertEquals("a " + "\u00A0" + " b", ReaderSearchText.decodeEntities("a &nbsp; b"))
        assertEquals("&unknown;", ReaderSearchText.decodeEntities("&unknown;"))
    }

    @Test
    fun countOccurrences_caseInsensitiveNonOverlapping() {
        assertEquals(2, ReaderSearchText.countOccurrences("کتاب خوب کتاب", "کتاب"))
        assertEquals(3, ReaderSearchText.countOccurrences("Book book BOOK", "book"))
        assertEquals(1, ReaderSearchText.countOccurrences("aaa", "aa"))
        assertEquals(0, ReaderSearchText.countOccurrences("abc", ""))
        assertEquals(0, ReaderSearchText.countOccurrences("", "x"))
    }

    @Test
    fun plainText_combinesBoth() {
        assertEquals("a & b", ReaderSearchText.plainText("<p>a &amp; b</p>"))
    }
}
