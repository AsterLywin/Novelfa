package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RtfParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fallback(n: Int) = "Section $n"

    private val sampleRtf = """
        {\rtf1\ansi\ansicpg1252\deff0
        {\fonttbl{\f0\fnil\fcharset0 Calibri;}}
        {\colortbl;\red255\green0\blue0;}
        {\stylesheet{\s0 Normal;}{\s1 Heading;}}
        {\info{\title Sample Book}{\author Someone}}
        {\*\generator Word;}
        \pard\qc\fs32 Title Line\par
        \pard\ql\fs24 Hello \b bold\b0 and \i italic\i0 and \ul underlined\ulnone and \strike struck\strike0.\par
        Unicode: \u1587\u1604\u1575\u1605? and hex \'e9.\par
        \line After a line break.\par
        \tab Indented.\par
        {\pict\pngblip 89504e470d0a} skipped picture\par
        {\object\objemb{\*\objclass Word.Document}{\*\objdata 010203}} skipped object\par
        {\footnote Footnote text that must not appear}\par
        \trowd\cell First cell\cell Second cell\cell\row\par
        {\field{\*\fldinst HYPERLINK "http://example.com"}{\fldrslt link text}}\par
        \sect
        Second section text.\par
        }
    """.trimIndent()

    @Test
    fun commonControls_infoTitle_sections() {
        val out = tempFolder.newFolder("out")
        val book = RtfParser.parse(
            sampleRtf.toByteArray(Charsets.ISO_8859_1), "book.rtf", out, ::fallback
        )
        assertEquals("Sample Book", book.title)
        assertEquals(2, book.chapters.size)
        val ch1 = book.chapters[0].file.readText()
        val ch2 = book.chapters[1].file.readText()
        // Ignored destinations leave no trace.
        assertTrue(!ch1.contains("Calibri"))
        assertTrue(!ch1.contains("Normal"))
        assertTrue(!ch1.contains("Someone"))
        assertTrue(!ch1.contains("Word"))
        assertTrue(!ch1.contains("89504e47"))
        assertTrue(!ch1.contains("Word.Document"))
        assertTrue(!ch1.contains("Footnote text"))
        // Formatting.
        assertTrue(ch1.contains("<strong>bold</strong>"))
        assertTrue(ch1.contains("<em>italic</em>"))
        assertTrue(ch1.contains("<u>underlined</u>"))
        assertTrue(ch1.contains("<del>struck</del>"))
        assertTrue(ch1.contains("font-size:16pt"))
        assertTrue(ch1.contains("text-align:center"))
        // Unicode \uN run keeps every character (سلام).
        assertTrue(ch1.contains("سلام"))
        // Hex escape \'e9 in windows-1252 is é.
        assertTrue(ch1.contains("é"))
        // Line break and tab survive.
        assertTrue(ch1.contains("<br/>"))
        assertTrue(ch1.contains("&nbsp;&nbsp;&nbsp;&nbsp;Indented"))
        // Table text survives as paragraphs; the grid does not.
        assertTrue(ch1.contains("First cell"))
        assertTrue(ch1.contains("Second cell"))
        // Field: result text kept, instruction and link dropped.
        assertTrue(ch1.contains("link text"))
        assertTrue(!ch1.contains("HYPERLINK"))
        assertTrue(!ch1.contains("example.com"))
        assertTrue(!ch1.contains("<a href"))
        // \sect starts the second chapter.
        assertTrue(ch2.contains("Second section text."))
    }

    @Test
    fun fallbackCharAfterUnicode_isSkipped() {
        // \u1587? — the '?' is the ANSI fallback for س and must not appear.
        val rtf = "{\\rtf1\\ansi\\ansicpg1252\\u1587?\\par}"
        val out = tempFolder.newFolder("out")
        val book = RtfParser.parse(rtf.toByteArray(Charsets.ISO_8859_1), "u.rtf", out, ::fallback)
        assertEquals(1, book.chapters.size)
        val html = book.chapters[0].file.readText()
        assertTrue(html.contains("س"))
        assertTrue(!html.contains("?"))
    }

    @Test
    fun surrogatePair_combines() {
        // U+1D11E (MUSICAL SYMBOL G CLEF) as a surrogate pair:
        // high 0xD834 = -10188, low 0xDD1E = -8930 as signed \u values.
        val rtf = "{\\rtf1\\ansi\\u-10188?\\u-8930?\\par}"
        val out = tempFolder.newFolder("out")
        val book = RtfParser.parse(rtf.toByteArray(Charsets.ISO_8859_1), "s.rtf", out, ::fallback)
        val html = book.chapters[0].file.readText()
        assertTrue(html.contains("\uD834\uDD1E"))
    }

    @Test
    fun windows1256_hexEscapes_decodePersian() {
        // \ansicpg1256 + \'d3\'e1\'c7\'e3 = سلام in windows-1256 bytes.
        val rtf = "{\\rtf1\\ansi\\ansicpg1256\\'d3\\'e1\\'c7\\'e3\\par}"
        val out = tempFolder.newFolder("out")
        val book = RtfParser.parse(rtf.toByteArray(Charsets.ISO_8859_1), "fa.rtf", out, ::fallback)
        val html = book.chapters[0].file.readText()
        assertTrue(html.contains("سلام"))
    }

    @Test
    fun unknownControlWords_doNotFail() {
        val rtf = "{\\rtf1\\ansi\\somefuture123 Hello \\anotherone world\\par}"
        val out = tempFolder.newFolder("out")
        val book = RtfParser.parse(rtf.toByteArray(Charsets.ISO_8859_1), "f.rtf", out, ::fallback)
        assertEquals(1, book.chapters.size)
        val html = book.chapters[0].file.readText()
        assertTrue(html.contains("Hello"))
        assertTrue(html.contains("world"))
    }

    @Test
    fun emptyRtf_hasNoChapters() {
        val rtf = "{\\rtf1\\ansi}"
        val out = tempFolder.newFolder("out")
        val book = RtfParser.parse(rtf.toByteArray(Charsets.ISO_8859_1), "e.rtf", out, ::fallback)
        assertTrue(book.chapters.isEmpty())
    }

    @Test
    fun infoTitle_absent_fallsBackToFileName() {
        val rtf = "{\\rtf1\\ansi Hello\\par}"
        assertEquals(null, RtfParser.infoTitle(rtf.toByteArray(Charsets.ISO_8859_1)))
        val out = tempFolder.newFolder("out")
        val book = RtfParser.parse(rtf.toByteArray(Charsets.ISO_8859_1), "mybook.rtf", out, ::fallback)
        assertEquals("mybook", book.title)
    }

    @Test
    fun nestedGroups_restoreFormatting() {
        // Bold opened in an outer group, italic in an inner one:
        // after the inner group closes, italic must end but bold continue.
        val rtf = "{\\rtf1\\ansi\\b outer {\\i inner} plain\\b0\\par}"
        val out = tempFolder.newFolder("out")
        val book = RtfParser.parse(rtf.toByteArray(Charsets.ISO_8859_1), "g.rtf", out, ::fallback)
        val html = book.chapters[0].file.readText()
        assertTrue(html.contains("<strong>outer <em>inner</em> plain</strong>"))
    }

    @Test
    fun alignment_rightAndJustify() {
        val rtf = "{\\rtf1\\ansi\\qr Right\\par\\qj Justified\\par}"
        val out = tempFolder.newFolder("out")
        val book = RtfParser.parse(rtf.toByteArray(Charsets.ISO_8859_1), "a.rtf", out, ::fallback)
        val html = book.chapters[0].file.readText()
        assertTrue(html.contains("text-align:right"))
        assertTrue(html.contains("text-align:justify"))
    }
}
