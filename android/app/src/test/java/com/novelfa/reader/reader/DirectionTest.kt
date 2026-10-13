package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionTest {

    @Test
    fun pureLatin_returns1() {
        assertEquals(1, HtmlText.detectDirection("Hello World"))
    }

    @Test
    fun purePersian_returns2() {
        assertEquals(2, HtmlText.detectDirection("سلام دنیا"))
    }

    @Test
    fun mixedMajorityLatin_returns1() {
        assertEquals(1, HtmlText.detectDirection("Hello سلام World"))
    }

    @Test
    fun mixedMajorityPersian_returns2() {
        assertEquals(2, HtmlText.detectDirection("سلام Hello دنیا عزیز"))
    }

    @Test
    fun digitsOnly_returns0() {
        assertEquals(0, HtmlText.detectDirection("1234567890 ... !?"))
    }

    @Test
    fun extendedLatin_countsAsLatin() {
        // U+00C0-U+024F minus ×÷ are latin per WPF MainWindow.DetectDirection.
        assertEquals(1, HtmlText.detectDirection("Àéîõü"))
    }

    @Test
    fun chapterDirAttr_latin_exact() {
        assertEquals(" dir=\"ltr\" data-rdr=\"l\"", HtmlText.chapterDirAttr("<p>Hello</p>"))
    }

    @Test
    fun chapterDirAttr_persian_exact() {
        assertEquals(" dir=\"rtl\" data-rdr=\"r\"", HtmlText.chapterDirAttr("<p>سلام</p>"))
    }

    @Test
    fun applyTextDirection_stampsParagraphs() {
        val fa = HtmlText.applyTextDirection("<p>سلام</p>")
        assertTrue(fa.contains("data-rdr=\"r\""))
        val en = HtmlText.applyTextDirection("<p>Hello</p>")
        assertTrue(en.contains("data-rdr=\"l\""))
    }

    @Test
    fun applyTextDirection_stripsExistingDirFirst() {
        val got = HtmlText.applyTextDirection("<p dir=\"ltr\">سلام دنیا عزیز</p>")
        assertTrue(got.contains("dir=\"rtl\""))
        assertTrue(got.contains("data-rdr=\"r\""))
    }
}
