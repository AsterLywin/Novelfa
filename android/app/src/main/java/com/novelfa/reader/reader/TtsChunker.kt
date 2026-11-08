package com.novelfa.reader.reader

/**
 * Pure, JVM-unit-testable text-to-speech preparation.
 *
 * [chunkHtml] turns one already-loaded chapter body (the prepared HTML
 * [ReaderViewModel] renders — never a re-read of the book file) into the
 * short utterances fed to [android.speech.tts.TextToSpeech]: one chunk is
 * at most [MAX_CHUNK_CHARS] long and, whenever the text allows it, ends
 * on a sentence pause, so the engine never has to swallow a whole
 * chapter in one utterance and a pause never lands mid-phrase.
 *
 * What is read, and what is skipped:
 * - Block text (paragraphs, headings, list items, quotes) in document
 *   order; headings are read, so a chapter's title is spoken.
 * - Footnote content is NOT body text: footnote-reference links
 *   (`data-novelfa-footnote`, marked by [HtmlText.markFootnoteLinks])
 *   contribute nothing (their text is just the reference number), and
 *   whole footnote/endnote containers (class/id naming a footnote) are
 *   dropped with their notes. The notes themselves stay readable in
 *   the footnote popup; they are simply not read aloud as body text.
 * - UI chrome (bars, dividers, the chapter divider) is never in the
 *   chapter body, so it cannot leak in here; `<script>`/`<style>`
 *   content is stripped before chunking.
 */
object TtsChunker {

    /** Longest single utterance handed to the TTS engine. */
    const val MAX_CHUNK_CHARS = 600

    /**
     * Sentence-ending pauses, tiered the same way the pager tiers them
     * (see HtmlBuilder PAGER_JS): a strong pause closes a sentence or
     * clause, a weak pause closes a phrase. A chunk prefers to end on
     * a strong pause, then a weak one, then a word boundary.
     */
    private val STRONG_END = setOf('.', '؟', '!', '…', '؛', '?', ';')
    private val WEAK_END = setOf('،', ':', ',', ':')
    private val CLOSERS = setOf('»', '«', '”', '“', '’', '‘', '\'', '"', ')', ']', '}')

    private val scriptStyleRegex =
        Regex("<(script|style)[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val footnoteLinkRegex = Regex(
        "<a\\b[^>]*data-novelfa-footnote[^>]*>[\\s\\S]*?</a\\s*>",
        RegexOption.IGNORE_CASE
    )
    private val footnoteContainerRegex = Regex(
        "<(div|section|ol|ul|aside|footer)\\b[^>]*" +
            "(?:class|id)\\s*=\\s*[\"'][^\"']*(?:footnote|endnote)[^\"']*[\"'][^>]*>" +
            "[\\s\\S]*?</\\1\\s*>",
        RegexOption.IGNORE_CASE
    )
    /** A block close (or a <br>) is a paragraph boundary. */
    private val blockEndRegex = Regex(
        "</(?:p|h[1-6]|li|blockquote|div|section|article|tr|dd|dt|figcaption|caption)\\s*>|<br\\s*/?>",
        RegexOption.IGNORE_CASE
    )

    /**
     * Splits prepared chapter HTML into speakable chunks, in document
     * order. Empty chapters (cover / image-only) yield an empty list.
     */
    fun chunkHtml(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        var text = scriptStyleRegex.replace(html, " ")
        text = footnoteLinkRegex.replace(text, " ")
        text = footnoteContainerRegex.replace(text, " ")
        // Block ends become a paragraph separator; every other tag is
        // dropped without inventing a space (DOM textContent agrees).
        text = blockEndRegex.replace(text, "\u0001")
        val plain = ReaderSearchText.plainText(text)
        val paragraphs = plain.split('\u0001')
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (para in paragraphs) out += splitParagraph(para)
        return out
    }

    /** Splits one paragraph into <= [MAX_CHUNK_CHARS] sentence-ended chunks. */
    fun splitParagraph(paragraph: String): List<String> {
        val text = paragraph.trim()
        if (text.isEmpty()) return emptyList()
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)
        val sentences = splitSentences(text)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                out += current.toString().trim()
                current.clear()
            }
        }
        for (sentence in sentences) {
            val piece = sentence.trim()
            if (piece.isEmpty()) continue
            if (piece.length > MAX_CHUNK_CHARS) {
                // One over-long sentence: flush what is buffered, then
                // fill chunks at word boundaries (never mid-word).
                flush()
                out += splitLongText(piece)
            } else if (current.isEmpty()) {
                current.append(piece)
            } else if (current.length + 1 + piece.length <= MAX_CHUNK_CHARS) {
                current.append(' ').append(piece)
            } else {
                flush()
                current.append(piece)
            }
        }
        flush()
        return out
    }

    /**
     * Splits at sentence pauses: the pause character (plus trailing
     * closers and whitespace) stays with its sentence. A run with no
     * pause at all is one piece.
     */
    fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in STRONG_END || c in WEAK_END) {
                var end = i + 1
                while (end < text.length && text[end] in CLOSERS) end++
                while (end < text.length && text[end].isWhitespace()) end++
                out += text.substring(start, end).trim()
                start = end
                i = end
            } else {
                i++
            }
        }
        if (start < text.length) {
            val tail = text.substring(start).trim()
            if (tail.isNotEmpty()) out += tail
        }
        return out
    }

    /** Word-boundary fill for a single over-long sentence. */
    private fun splitLongText(text: String): List<String> {
        val out = mutableListOf<String>()
        val words = text.split(' ').filter { it.isNotEmpty() }
        val current = StringBuilder()
        for (word in words) {
            if (word.length > MAX_CHUNK_CHARS) {
                // One unbreakable token (a long URL, a long word):
                // hard-cut it; there is no word boundary to use.
                if (current.isNotEmpty()) {
                    out += current.toString().trim()
                    current.clear()
                }
                var at = 0
                while (at < word.length) {
                    val end = minOf(at + MAX_CHUNK_CHARS, word.length)
                    out += word.substring(at, end)
                    at = end
                }
            } else if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= MAX_CHUNK_CHARS) {
                current.append(' ').append(word)
            } else {
                out += current.toString().trim()
                current.clear()
                current.append(word)
            }
        }
        if (current.isNotEmpty()) out += current.toString().trim()
        return out
    }
}

/**
 * Playback speed: the persisted value (see [com.novelfa.reader.data.PrefsStore])
 * is a float in [TtsSpeed.MIN]..[TtsSpeed.MAX]; anything outside is
 * clamped, never rejected, so a corrupt stored value can never break
 * playback. Pure, so it is unit-testable without DataStore.
 */
object TtsSpeed {
    const val MIN = 0.5f
    const val MAX = 2.0f
    const val DEFAULT = 1.0f

    fun clamp(speed: Float): Float =
        if (speed.isNaN()) DEFAULT else speed.coerceIn(MIN, MAX)
}

/**
 * The chapter-advance decision at the end of a chapter's chunks.
 * Pure, so the "advance vs stop at book end" rule is unit-testable
 * without a TextToSpeech engine: given the chapter that just finished
 * and the book's chapter count, there is exactly one right answer.
 */
object TtsPlaybackPlan {

    sealed interface Next {
        /** Keep reading in the same chapter, at this chunk index. */
        data class Chunk(val index: Int) : Next
        /** The chapter is done: load this next chapter and read it. */
        data class Chapter(val index: Int) : Next
        /** The last chapter is done: the book is finished. */
        data object Finished : Next
    }

    /**
     * [chapter] is the chapter just finished, [chapterCount] the
     * book's chapter count, [chunkIndex] the chunk just spoken and
     * [chunkCount] the chapter's chunk count. A caller that is
     * mid-chapter uses [Chunk]; this is the end-of-chapter call.
     */
    fun afterChapter(
        chapter: Int,
        chapterCount: Int,
        chunkIndex: Int,
        chunkCount: Int
    ): Next {
        if (chunkIndex + 1 < chunkCount) return Next.Chunk(chunkIndex + 1)
        return afterChapterEnd(chapter, chapterCount)
    }

    /**
     * End-of-chapter call, when the last chunk is already spoken and
     * only the chapter-vs-book-end question remains (the reader's
     * advance effect uses this: the service reports a finished
     * chapter, and the chunk position is past the last chunk by
     * construction).
     */
    fun afterChapterEnd(chapter: Int, chapterCount: Int): Next {
        if (chapter + 1 < chapterCount) return Next.Chapter(chapter + 1)
        return Next.Finished
    }
}
