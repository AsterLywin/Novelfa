package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsChunkerTest {

    @Test
    fun chunkHtml_paragraphsInOrder() {
        val html = "<p>First paragraph here.</p><p>Second paragraph here.</p>"
        assertEquals(
            listOf("First paragraph here.", "Second paragraph here."),
            TtsChunker.chunkHtml(html)
        )
    }

    @Test
    fun chunkHtml_headingsAreRead() {
        val html = "<h1>Chapter One</h1><p>Body text here.</p>"
        assertEquals(
            listOf("Chapter One", "Body text here."),
            TtsChunker.chunkHtml(html)
        )
    }

    @Test
    fun chunkHtml_footnoteReferenceLinksAreSkipped() {
        // The reference is just a number: it must not be spoken.
        val html = "<p>Body text<a data-novelfa-footnote=\"1\" href=\"#fn1\">1</a> continues.</p>"
        assertEquals(listOf("Body text continues."), TtsChunker.chunkHtml(html))
    }

    @Test
    fun chunkHtml_footnoteContainerIsSkipped() {
        val html = "<p>Body text.</p>" +
            "<div class=\"footnotes\"><ol><li id=\"fn1\">The note itself.</li></ol></div>" +
            "<p>More body.</p>"
        assertEquals(listOf("Body text.", "More body."), TtsChunker.chunkHtml(html))
    }

    @Test
    fun chunkHtml_scriptsAndEmptyBlocksAreSkipped() {
        val html = "<script>var x = 1;</script><p>Real text.</p><p></p><p>   </p>"
        assertEquals(listOf("Real text."), TtsChunker.chunkHtml(html))
    }

    @Test
    fun chunkHtml_emptyAndImageOnlyYieldNothing() {
        assertEquals(emptyList<String>(), TtsChunker.chunkHtml(""))
        assertEquals(emptyList<String>(), TtsChunker.chunkHtml("<p><img src=\"x.png\" /></p>"))
    }

    @Test
    fun splitSentences_keepsPauseWithItsSentence() {
        val text = "Hello world. How are you? Fine!"
        assertEquals(
            listOf("Hello world.", "How are you?", "Fine!"),
            TtsChunker.splitSentences(text)
        )
    }

    @Test
    fun splitSentences_persianPauses() {
        val text = "سلام دنیا. حالت چطور است؟ خوبم!"
        assertEquals(
            listOf("سلام دنیا.", "حالت چطور است؟", "خوبم!"),
            TtsChunker.splitSentences(text)
        )
    }

    @Test
    fun splitParagraph_longParagraphEndsOnPausesWithinLimit() {
        val sentence = "This is a sentence with some words in it. "
        val para = sentence.repeat(30).trim()
        val chunks = TtsChunker.splitParagraph(para)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(chunk.length <= TtsChunker.MAX_CHUNK_CHARS)
        }
        // Nothing is lost or duplicated: the chunks rejoin to the paragraph.
        assertEquals(para, chunks.joinToString(" "))
    }

    @Test
    fun splitParagraph_overLongSentenceSplitsAtWordBoundaries() {
        val para = ("word ".repeat(200)).trim()
        val chunks = TtsChunker.splitParagraph(para)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(chunk.length <= TtsChunker.MAX_CHUNK_CHARS)
            // No chunk ends mid-word.
            assertTrue(chunk.endsWith("word"))
        }
        assertEquals(para, chunks.joinToString(" "))
    }

    @Test
    fun splitParagraph_shortParagraphIsOneChunk() {
        assertEquals(listOf("Short text."), TtsChunker.splitParagraph("Short text."))
        assertEquals(emptyList<String>(), TtsChunker.splitParagraph("   "))
    }

    @Test
    fun speed_clamp() {
        assertEquals(0.5f, TtsSpeed.clamp(0.1f))
        assertEquals(2.0f, TtsSpeed.clamp(3.0f))
        assertEquals(1.25f, TtsSpeed.clamp(1.25f))
        assertEquals(1.0f, TtsSpeed.clamp(Float.NaN))
    }

    @Test
    fun plan_midChapterAdvancesChunk() {
        assertEquals(
            TtsPlaybackPlan.Next.Chunk(2),
            TtsPlaybackPlan.afterChapter(0, 5, 1, 4)
        )
    }

    @Test
    fun plan_lastChunkAdvancesChapter() {
        assertEquals(
            TtsPlaybackPlan.Next.Chapter(1),
            TtsPlaybackPlan.afterChapter(0, 5, 3, 4)
        )
        assertEquals(
            TtsPlaybackPlan.Next.Chapter(1),
            TtsPlaybackPlan.afterChapterEnd(0, 5)
        )
    }

    @Test
    fun plan_lastChapterOfBookFinishes() {
        assertEquals(
            TtsPlaybackPlan.Next.Finished,
            TtsPlaybackPlan.afterChapter(4, 5, 3, 4)
        )
        assertEquals(
            TtsPlaybackPlan.Next.Finished,
            TtsPlaybackPlan.afterChapterEnd(4, 5)
        )
    }

    @Test
    fun plan_singleChapterBookFinishes() {
        assertEquals(
            TtsPlaybackPlan.Next.Finished,
            TtsPlaybackPlan.afterChapterEnd(0, 1)
        )
    }
}
