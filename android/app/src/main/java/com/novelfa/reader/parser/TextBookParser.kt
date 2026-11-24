package com.novelfa.reader.parser

import com.novelfa.reader.model.ParsedBook
import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.definition.DefinitionExtension
import com.vladsch.flexmark.ext.footnotes.FootnoteExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughSubscriptExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.superscript.SuperscriptExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.util.misc.Extension
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Plain-text and Markdown books.
 *
 * Direction is NOT decided here: the generated chapters are plain HTML
 * and the reader's existing pipeline ([HtmlText] in the reader package)
 * stamps every paragraph and every chapter with its own direction from
 * its majority script, so a Persian paragraph inside an English book —
 * or the reverse — renders correctly in both paged and continuous mode.
 * The book-level paged geometry (which side the next page sits on) is
 * derived from the content at open time, not from the UI language.
 */
object TextBookParser {

    /** A paragraph that starts a new section on its own. */
    private val headingWordsFa = setOf(
        "مقدمه", "پیش‌گفتار", "پیشگفتار", "مؤخره", "پس‌گفتار", "دیباچه",
        "سرآغاز", "فهرست", "درباره‌ی نویسنده", "درباره نویسنده",
        "درباره‌ی کتاب", "درباره کتاب", "سپاسگزاری", "یادداشت مترجم"
    )
    private val headingWordsEn = setOf(
        "introduction", "preface", "prologue", "epilogue", "foreword",
        "contents", "acknowledgements", "acknowledgments", "about the author"
    )

    fun parseText(
        bytes: ByteArray,
        fileName: String,
        outDir: File,
        chapterFallback: (Int) -> String
    ): ParsedBook {
        val raw = decodeText(bytes).replace("\r\n", "\n").replace('\r', '\n')
            .trim('\uFEFF', '\n')
        val blocks = splitParagraphs(raw)
        if (blocks.isEmpty()) {
            return ConvertedBook.write(outDir, File(fileName).nameWithoutExtension, emptyList())
        }
        // Book title: a short first line with no sentence punctuation,
        // followed by a blank line, in a document that has more to say.
        // Anything else keeps the file name (the EPUB parser's fallback).
        // Windows parity: the Windows reader claims such a line as the
        // title unconditionally. Android additionally needs evidence it
        // IS a title page — either the next block is a heading (the
        // title-page shape: "My Book" then "فصل ۱") or the line repeats
        // the file's own name — so a short opening line that is neither
        // (a dedication, "به نام خدا", a one-line opening) is never
        // silently deleted from the text: it stays in the body and the
        // filename is the title.
        var title = File(fileName).nameWithoutExtension
        var bodyBlocks = blocks
        val first = blocks.first()
        val titleEvidence = blocks.getOrNull(1)?.let { isHeading(it) } == true ||
            normalizeTitle(first).equals(
                normalizeTitle(File(fileName).nameWithoutExtension), ignoreCase = true
            )
        if (blocks.size >= 3 && first.lines().size == 1 && first.length <= 60 &&
            first.last() !in ".!؟?،؛:" && !isHeading(first) && titleEvidence
        ) {
            title = first.trim()
            bodyBlocks = blocks.drop(1)
        }
        val sections = mutableListOf<ConvertedBook.Section>()
        var current = mutableListOf<String>()
        var currentTitle: String? = null
        fun flush() {
            if (current.isEmpty()) return
            val body = current.joinToString("") { "<p>" + ConvertedBook.escape(it) + "</p>" }
            sections += ConvertedBook.Section(
                currentTitle ?: chapterFallback(sections.size + 1), body
            )
            current = mutableListOf()
            currentTitle = null
        }
        for (block in bodyBlocks) {
            if (isHeading(block) && block.lines().size == 1) {
                flush()
                currentTitle = block.trim()
            } else {
                current += block
            }
        }
        flush()
        if (sections.isEmpty()) {
            sections += ConvertedBook.Section(
                chapterFallback(1),
                bodyBlocks.joinToString("") { "<p>" + ConvertedBook.escape(it) + "</p>" }
            )
        }
        return ConvertedBook.write(
            outDir, title, ConvertedBook.chunkSections(sections)
        )
    }

    fun parseMarkdown(
        bytes: ByteArray,
        fileName: String,
        outDir: File,
        chapterFallback: (Int) -> String
    ): ParsedBook {
        val raw = decodeText(bytes).replace("\r\n", "\n").replace('\r', '\n')
            .trim('\uFEFF', '\n')
        // Deeply nested Markdown overflows the parse thread's stack:
        // flexmark recurses per nesting level, and a ~500-deep list
        // already overflows a 1 MB IO-thread stack (probe, real 0.64.8
        // jars). StackOverflowError is an Error, so no `catch
        // (e: Exception)` upstream — import, reader open — would ever
        // see it: without this the import (and the open) dies as an
        // app crash instead of a readable import error. The shared
        // parser/renderer hold no per-parse state, so the thread is
        // safe to reuse after the unwind. Cancellation still propagates.
        val (title, sections) = try {
            renderMarkdown(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw IllegalStateException("Markdown too deeply nested to render", t)
        }
        return ConvertedBook.write(
            outDir, title ?: File(fileName).nameWithoutExtension,
            if (sections.isEmpty()) emptyList()
            else ConvertedBook.chunkSections(
                sections.mapIndexed { i, (sectionTitle, bodyHtml) ->
                    ConvertedBook.Section(
                        sectionTitle ?: chapterFallback(i + 1), bodyHtml
                    )
                }
            )
        )
    }

    /**
     * Encoding, in order: BOM (UTF-8 / UTF-16 LE / BE), strict UTF-8,
     * then Windows-1256 — the encoding of a great many Persian text
     * files produced on Windows. A final UTF-8-with-replacement fallback
     * keeps an undecodable file readable instead of failing the import.
     */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        val strict = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (strict != null) return strict
        // Not valid UTF-8: pick the single-byte encoding by evidence,
        // not by habit. The old code always fell back to Windows-1256,
        // which mangles Western-European bytes (the two encodings
        // disagree in the 0x80-0x9F range). Windows-1256 wins only
        // when the decoded text actually contains Arabic-script
        // characters; otherwise Windows-1252 is the honest fallback
        // for Latin text, mirroring the multi-encoding tables of
        // mature readers instead of a one-encoding guess.
        val decoded1256 = runCatching {
            String(bytes, java.nio.charset.Charset.forName("windows-1256"))
        }.getOrNull()
        if (decoded1256 != null && decoded1256.any {
                it in '\u0600'..'\u06FF' || it in '\uFB50'..'\uFDFF' || it in '\uFE80'..'\uFEFF'
            }) {
            return decoded1256
        }
        return runCatching {
            String(bytes, java.nio.charset.Charset.forName("windows-1252"))
        }.getOrDefault(String(bytes, Charsets.UTF_8))
    }

    /** Persian/Arabic letter variants, so a file named with ي/ك still matches its title line. */
    private fun normalizeTitle(s: String) =
        s.trim().replace('ي', 'ی').replace('ك', 'ک')

    /** Blank-line paragraphs; with no blank lines, every line is one. */
    private fun splitParagraphs(text: String): List<String> {
        val byBlank = text.split(Regex("\n\\s*\n")).map { it.trim() }
            .filter { it.isNotEmpty() }
        if (byBlank.size > 1) return byBlank
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun isHeading(block: String): Boolean {
        val line = block.trim()
        if (line.isEmpty() || line.length > 80) return false
        if (line in headingWordsFa) return true
        if (line.lowercase() in headingWordsEn) return true
        if (Regex("^(فصل|بخش|قسمت|کتاب)\\s+[\\p{L}0-9۰-۹]+").containsMatchIn(line)) return true
        if (Regex("^(chapter|part|section)\\s+[\\p{L}0-9]+", RegexOption.IGNORE_CASE)
                .containsMatchIn(line)
        ) return true
        // ALL-CAPS Latin line, or a line underlined with === (setext).
        if (line.length >= 3 && line.none { it.isLowerCase() } &&
            line.any { it.isUpperCase() }
        ) return true
        return false
    }
}


/**
 * Markdown rendering via flexmark-java (CommonMark + GFM): headings,
 * fenced code, lists (nested, loose, task lists), blockquotes
 * (nested), tables with alignment, rules, footnotes, reference
 * links, autolinks, definition lists, sub/superscript, inline code /
 * bold / italic / strike / links, and raw HTML passthrough.
 *
 * Sectioning keeps the old hand-written parser's contract: the first
 * `# ` heading — and only when it is the very first content — is the
 * book title; every `# ` / `## ` heading after that starts a new
 * section whose title is the heading text and whose body starts with
 * the heading itself; deeper headings stay inline.
 *
 * Images: a remote (`http(s):`, `data:`, `file:`) `src` renders as an
 * `<img>` — the WebView can load it. A relative `src` has no file to
 * resolve to (a picked document carries no directory), so it keeps
 * the old behaviour: only the alt text survives, as emphasis, instead
 * of a broken-image icon. Resolving sibling images needs the source
 * directory plumbed through the importer; that is a separate change.
 *
 * Footnote links (`#fn-…`) are plain in-page anchors: they work in
 * paged mode, where the reader's anchor JS intercepts `#frag`, and
 * hit the known vertical-mode `#frag` navigation bug otherwise.
 */
private val markdownOptions: MutableDataSet = MutableDataSet().set(
    Parser.EXTENSIONS,
    listOf<Extension>(
        TablesExtension.create(),
        FootnoteExtension.create(),
        StrikethroughSubscriptExtension.create(),
        TaskListExtension.create(),
        AutolinkExtension.create(),
        DefinitionExtension.create(),
        SuperscriptExtension.create()
    )
)
private val markdownParser: Parser = Parser.builder(markdownOptions).build()
private val markdownRenderer: HtmlRenderer =
    HtmlRenderer.builder(markdownOptions).build()

/** Book title (null when none) plus (section title?, body HTML) pairs. */
/**
 * Renders the whole document in one pass, then splits the resulting
 * HTML into sections on top-level `<h1>`/`<h2>` tags.
 *
 * The one-pass render matters: rendering flexmark nodes one by one
 * silently drops document-scoped output — link references and
 * autolinks come out as plain text, images as empty paragraphs, and
 * the footnote list is never emitted (proven with a probe against
 * the real 0.64.8 jars). Sectioning therefore works on the finished
 * HTML, tracking tag depth so a heading inside a blockquote or list
 * never starts a section.
 *
 * Sectioning contract: the first H1 is the book title only when it
 * is the very first content; H1/H2 otherwise start new sections
 * (title = heading text, body starts with the heading HTML); deeper
 * headings stay inline; content before the first heading gets a
 * fallback title from the caller.
 */
private fun renderMarkdown(raw: String): Pair<String?, List<Pair<String?, String>>> {
    val html = markdownRenderer.render(markdownParser.parse(raw))
    val sections = mutableListOf<Pair<String?, String>>()
    var title: String? = null
    var sectionTitle: String? = null
    val out = StringBuilder()
    fun flushSection() {
        // Whitespace-only gaps between blocks (newlines flexmark leaves
        // between them) must not become phantom chapters.
        if (out.toString().isNotBlank() || sectionTitle != null) {
            sections += sectionTitle to finishMarkdownHtml(out.toString())
            out.clear()
            sectionTitle = null
        }
    }
    val voidTags = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
    )
    val tagPattern = Regex("<(/?)([A-Za-z][A-Za-z0-9]*)[^>]*>")
    var depth = 0
    var cursor = 0
    var searchFrom = 0
    while (true) {
        // Skip HTML comments: a "<h1>" inside one must not split.
        if (html.startsWith("<!--", searchFrom)) {
            val end = html.indexOf("-->", searchFrom + 4)
            searchFrom = if (end < 0) html.length else end + 3
            continue
        }
        val match = tagPattern.find(html, searchFrom) ?: break
        val (slash, name) = match.groupValues[1] to match.groupValues[2]
        val lower = name.lowercase()
        val selfClosing = match.value.endsWith("/>")
        searchFrom = match.range.last + 1
        if (slash.isNotEmpty()) {
            depth = maxOf(0, depth - 1)
            continue
        }
        if (selfClosing || lower in voidTags) continue
        if (depth == 0 && (lower == "h1" || lower == "h2")) {
            val close = html.indexOf("</$lower>", match.range.last + 1, ignoreCase = true)
            if (close < 0) continue
            val headingEnd = close + lower.length + 3
            val headingHtml = html.substring(match.range.first, headingEnd)
            if (lower == "h1" && title == null && sections.isEmpty() && out.isEmpty()) {
                title = stripMarkdownHtml(headingHtml)
            } else {
                out.append(html, cursor, match.range.first)
                flushSection()
                sectionTitle = stripMarkdownHtml(headingHtml)
                out.append(headingHtml)
            }
            cursor = headingEnd
            searchFrom = headingEnd
            continue
        }
        depth++
    }
    out.append(html, cursor, html.length)
    flushSection()
    // A document with no sections but real content — a lone
    // `# Title`, or headings with no body text — used to parse to
    // ZERO chapters: the copy import failed it as "no readable
    // chapters" and the reader opened a blank book. The title was
    // already consumed as the book title above, so the rendered HTML
    // itself becomes the one section (title included) and the book
    // renders instead of vanishing. A truly empty document still
    // yields zero sections.
    if (sections.isEmpty() && html.isNotBlank()) {
        sections += null to finishMarkdownHtml(html)
    }
    return title to sections
}

/**
 * Post-processing of one section's rendered HTML: relative `<img>`
 * tags become their alt text (see above), and the trailing newline
 * flexmark leaves inside fenced code blocks is dropped so a code
 * block does not end on a blank line.
 */
private fun finishMarkdownHtml(html: String): String {
    var out = html.replace("\n</code>", "</code>")
    out = Regex("<img\\s[^>]*>").replace(out) { m ->
        val tag = m.value
        val src = Regex("src=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: ""
        val alt = Regex("alt=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: ""
        if (src.startsWith("http://") || src.startsWith("https://") ||
            src.startsWith("data:") || src.startsWith("file:")
        ) {
            tag
        } else if (alt.isBlank()) {
            ""
        } else {
            "<em>$alt</em>"
        }
    }
    return out
}

/** Rendered HTML back to plain text (heading titles): strip tags, unescape entities. */
private fun stripMarkdownHtml(html: String): String =
    html.replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&")
        .trim()
