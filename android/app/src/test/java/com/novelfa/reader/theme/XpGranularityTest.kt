package com.novelfa.reader.theme

import com.novelfa.reader.parser.ConvertedBook
import com.novelfa.reader.parser.ConvertedBook.Section
import com.novelfa.reader.parser.EpubParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the XP granularity rules and the one asymmetry they create:
 *
 * - Converted books (TXT / Markdown / DOCX / converted PDF) chunk any
 *   section over [ConvertedBook.MAX_SECTION_CHARS] chars into several
 *   units; every unit earns its own [XpRank.xpForUnit], and the
 *   +1000 rounding inside that formula applies per unit.
 * - EPUB chapters are NEVER chunked: one spine item is one unit,
 *   however long it is.
 *
 * So the same long text can earn different XP depending on the format
 * it arrived in. These tests pin that current behaviour exactly; they
 * do not fix it. Unifying granularity (chunking EPUB too, or crediting
 * by book total) is a product decision that must update these numbers
 * deliberately, not accidentally.
 */
class XpGranularityTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun xpForUnit_boundaries() {
        assertEquals(1, XpRank.xpForUnit(0))
        assertEquals(1, XpRank.xpForUnit(1))
        assertEquals(1, XpRank.xpForUnit(999))
        assertEquals(1, XpRank.xpForUnit(1000))
        assertEquals(1, XpRank.xpForUnit(2999))
        assertEquals(2, XpRank.xpForUnit(3000))
        assertEquals(2, XpRank.xpForUnit(4999))
        assertEquals(3, XpRank.xpForUnit(5000))
        assertEquals(3, XpRank.xpForUnit(6999))
        assertEquals(4, XpRank.xpForUnit(7000))
        assertEquals(1, XpRank.xpForUnit(-5))
        assertEquals(50, XpRank.xpForUnit(100000))
    }

    @Test
    fun chunkSections_shortSection_unchanged() {
        val section = Section("S", "<p>short</p>")
        assertEquals(listOf(section), ConvertedBook.chunkSections(listOf(section)))
    }

    @Test
    fun chunkSections_multiParagraph_groupsAtCap() {
        val p = "<p>" + "word ".repeat(1000) + "</p>" // 5000 chars of text
        val chunks = ConvertedBook.chunkSections(
            listOf(Section("T", p + p + p))
        )
        // 5007 + 5007 <= 12000, the third paragraph would exceed it.
        assertEquals(listOf("T", "T (2)"), chunks.map { it.title })
        assertEquals(2, chunks.size)
        assertTrue(chunks[1].bodyHtml.contains("word"))
    }

    @Test
    fun chunkSections_longSingleParagraph_splitsAndPreservesWords() {
        val text = "word ".repeat(2999) // 2999 words, 14995 chars
        val chunks = ConvertedBook.chunkSections(
            listOf(Section("T", "<p>$text</p>"))
        )
        assertEquals(listOf("T", "T (2)"), chunks.map { it.title })
        // The split moves text between units but never loses a word.
        assertEquals(2999, chunks.sumOf { XpRank.wordsFromHtml(it.bodyHtml) })
    }

    @Test
    fun xp_chunkedVsSingle_sameTextDifferentXp() {
        val text = "word ".repeat(2999)
        val chunks = ConvertedBook.chunkSections(
            listOf(Section("T", "<p>$text</p>"))
        )
        val chunkedXp = chunks.sumOf { XpRank.xpForUnit(XpRank.wordsFromHtml(it.bodyHtml)) }
        val singleXp = XpRank.xpForUnit(XpRank.wordsFromHtml("<p>$text</p>"))
        // 2999 words as one unit: (2999 + 1000) / 2000 = 1.
        // As two chunked units: 1 + 1 = 2. The per-unit rounding bonus
        // is what makes the same text worth different XP by format.
        assertEquals(1, singleXp)
        assertEquals(2, chunkedXp)
        assertNotEquals(singleXp, chunkedXp)
    }

    @Test
    fun xp_store_chunkedVsSingle() {
        val text = "word ".repeat(2999)
        val chunks = ConvertedBook.chunkSections(
            listOf(Section("T", "<p>$text</p>"))
        )
        // totalUnits = 10 so the last unit is never credited: no bonus
        // pollutes the pure unit-XP comparison.
        val chunkedStore = XpStore(InMemoryRankPrefs())
        chunks.forEachIndexed { i, chunk ->
            chunkedStore.creditUnit("chunked", i, XpRank.wordsFromHtml(chunk.bodyHtml), 10, false)
        }
        assertEquals(2, chunkedStore.totalXp)

        val singleStore = XpStore(InMemoryRankPrefs())
        singleStore.creditUnit("single", 0, XpRank.wordsFromHtml("<p>$text</p>"), 10, false)
        assertEquals(1, singleStore.totalXp)
    }

    @Test
    fun epubLongChapter_staysSingleUnit() {
        val text = "word ".repeat(2999)
        val oebps = tmp.newFolder("OEBPS")
        File(oebps, "content.opf").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Long Book</dc:title>
                <dc:identifier id="id">long-book</dc:identifier>
                <dc:language>en</dc:language>
              </metadata>
              <manifest><item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="ch1"/></spine>
            </package>""".trimIndent()
        )
        File(oebps, "ch1.xhtml").writeText(
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Long Chapter</title></head>" +
                "<body><p>$text</p></body></html>"
        )
        val book = EpubParser.parse(tmp.root, "long.epub", chapterFallback = { n -> "Section $n" })
        // The same 14995-char text that chunkSections splits into 2
        // units stays ONE EPUB chapter: no chunking on the EPUB path.
        assertEquals(1, book.chapters.size)
        assertEquals("Long Chapter", book.chapters[0].title)
        assertEquals("ch1.xhtml", book.chapters[0].file.name)
        assertEquals(
            2,
            ConvertedBook.chunkSections(listOf(Section("Long Chapter", "<p>$text</p>"))).size
        )
    }

    @Test
    fun bonus_smallUnitExemption() {
        // 8 normal units (100 words) + 2 small units (10 words) that the
        // reader never credits (jumped over). The last qualifying unit
        // is 7, qualifying total is 8.
        val exemptStore = XpStore(InMemoryRankPrefs())
        var last: XpCreditResult? = null
        for (u in 0 until 8) {
            last = exemptStore.creditUnit("exempt", u, 100, 10, false, 8, 7)
        }
        // 8 x 1 XP + bonus max(10, 8 / 10) = 18: the skipped small units
        // do not drag the 90% below the threshold.
        assertEquals(10, last!!.bonusXp)
        assertEquals(18, exemptStore.totalXp)

        // Same credits, no exemption: the last unit (9) was never
        // credited, so no bonus at all.
        val plainStore = XpStore(InMemoryRankPrefs())
        var plainLast: XpCreditResult? = null
        for (u in 0 until 8) {
            plainLast = plainStore.creditUnit("plain", u, 100, 10, false)
        }
        assertEquals(0, plainLast!!.bonusXp)
        assertEquals(8, plainStore.totalXp)
    }
}
