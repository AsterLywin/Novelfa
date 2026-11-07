package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun findOccurrences_offsetsInOrderWithLimit() {
        assertEquals(listOf(0, 5, 10), ReaderSearchText.findOccurrences("Book book BOOK", "book"))
        assertEquals(listOf(0), ReaderSearchText.findOccurrences("aaa", "aa"))
        assertEquals(listOf(0), ReaderSearchText.findOccurrences("abcabc", "abc", limit = 1))
        assertEquals(emptyList<Int>(), ReaderSearchText.findOccurrences("abc", ""))
        assertEquals(emptyList<Int>(), ReaderSearchText.findOccurrences("", "x"))
        assertEquals(emptyList<Int>(), ReaderSearchText.findOccurrences("abc", "x", limit = 0))
    }

    @Test
    fun snippetFor_shortTextIsWholeText() {
        assertEquals(
            "Hello world, this is a test." to 6,
            ReaderSearchText.snippetFor("Hello world, this is a test.", 6, 5)
        )
        assertEquals(
            "Hello world, this is a test." to 0,
            ReaderSearchText.snippetFor("Hello world, this is a test.", 0, 5)
        )
    }

    @Test
    fun snippetFor_collapsesWhitespace() {
        // Block newlines in the plain text become single spaces;
        // the match start tracks the collapse.
        assertEquals(
            "line one line two needle line three" to 18,
            ReaderSearchText.snippetFor("line one\n\nline two needle line three", 19, 6)
        )
    }

    @Test
    fun snippetFor_longTextCutsAtWordBoundaries() {
        val text = "word ".repeat(30) + "needle" + " word".repeat(30)
        val (snippet, matchStart) = ReaderSearchText.snippetFor(text, 150, 6)
        assertEquals("needle", snippet.substring(matchStart, matchStart + 6))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        // Never opens or closes mid-word.
        assertTrue(snippet.startsWith("…word"))
        assertTrue(snippet.endsWith("word…"))
        assertTrue(snippet.length < text.length)
    }

    @Test
    fun parsePageList_rdrSearchPagesResults() {
        assertEquals(listOf(0, 2, -1), ReaderSearchText.parsePageList("[0,2,-1]"))
        assertEquals(listOf(1, 3), ReaderSearchText.parsePageList("\"[1,3]\""))
        assertEquals(emptyList<Int>(), ReaderSearchText.parsePageList("[]"))
        assertEquals(emptyList<Int>(), ReaderSearchText.parsePageList(null))
        assertEquals(emptyList<Int>(), ReaderSearchText.parsePageList(""))
        assertEquals(emptyList<Int>(), ReaderSearchText.parsePageList("garbage"))
        assertEquals(emptyList<Int>(), ReaderSearchText.parsePageList("[0, x]"))
    }
}
