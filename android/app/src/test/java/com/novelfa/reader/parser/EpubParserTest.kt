package com.novelfa.reader.parser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class EpubParserTest {
    @Test
    fun resolveHref_decodesPersianAndStripsHash() {
        val folder = File("src/test/resources/opf").apply { mkdirs() }
        File(folder, "فصل ۱.xhtml").writeText("x")
        val got = HrefResolver.resolve(folder, "%D9%81%D8%B5%D9%84%20%DB%B1.xhtml#ch1")
        assertTrue(got.exists())
    }
    @Test
    fun spineOrder_isPreserved() {
        val dir = File("src/test/resources/book1")
        val book = EpubParser.parse(dir, "mybook.epub")
        assertEquals(listOf("فصل 1", "فصل 2"), book.chapters.map { it.title })
        assertEquals("My Title", book.title)
    }
}
