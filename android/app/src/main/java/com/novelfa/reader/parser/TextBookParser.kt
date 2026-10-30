package com.novelfa.reader.parser

import com.novelfa.reader.model.ParsedBook
import java.io.File

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
        // The title is only claimed when the NEXT block is a heading
        // (the title-page shape: "My Book" then "فصل ۱"): without that
        // guard, a book whose first line is simply short — a
        // dedication, an author line, a one-line opening — had that
        // line silently deleted from the text. With the guard, such a
        // line stays in the body and the filename is the title.
        var title = File(fileName).nameWithoutExtension
        var bodyBlocks = blocks
        val first = blocks.first()
        if (blocks.size >= 3 && first.lines().size == 1 && first.length <= 60 &&
            first.last() !in ".!؟?،؛:" && !isHeading(first) &&
            blocks.getOrNull(1)?.let { isHeading(it) } == true
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
        val md = MarkdownParser(raw)
        val title = md.title ?: File(fileName).nameWithoutExtension
        val sections = md.sections.mapIndexed { i, s ->
            ConvertedBook.Section(s.title ?: chapterFallback(i + 1), s.bodyHtml)
        }
        return ConvertedBook.write(
            outDir, title,
            if (sections.isEmpty()) emptyList()
            else ConvertedBook.chunkSections(sections)
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
 * Small CommonMark-subset renderer: headings, fenced code, lists
 * (nested by indent), blockquotes, tables, rules, and inline code /
 * bold / italic / strike / links. Images have no resolvable file next
 * to a picked document, so only their alt text survives (as emphasis) —
 * a broken-image icon would be worse than no image.
 */
private class MarkdownParser(private val raw: String) {

    data class Section(val title: String?, val bodyHtml: String)

    var title: String? = null
        private set
    val sections = mutableListOf<Section>()

    init {
        parse()
    }

    private fun parse() {
        val lines = raw.lines()
        val out = StringBuilder()
        var sectionTitle: String? = null
        var i = 0
        fun flushSection() {
            if (out.isNotEmpty() || sectionTitle != null) {
                sections += Section(sectionTitle, out.toString())
                out.clear()
                sectionTitle = null
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            // A stray === underline (left behind when a multi-line
            // paragraph's setext break fired): it is markup, not text.
            if (line.trim().isNotEmpty() && line.trim().all { it == '=' }) {
                i++
                continue
            }
            // Fenced code block.
            if (line.trimStart().startsWith("```")) {
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    buf.append(lines[i]).append('\n')
                    i++
                }
                i++ // closing fence
                out.append("<pre><code>")
                    .append(ConvertedBook.escape(buf.toString().trimEnd('\n')))
                    .append("</code></pre>")
                continue
            }
            // ATX heading.
            val atx = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line.trim())
            if (atx != null) {
                val level = atx.groupValues[1].length
                val text = atx.groupValues[2].trim().trimEnd('#').trim()
                if (level == 1 && title == null && sections.isEmpty() && out.isEmpty()) {
                    title = stripInline(text)
                    i++
                    continue
                }
                if (level <= 2) {
                    flushSection()
                    sectionTitle = stripInline(text)
                    out.append("<h$level>").append(inline(text)).append("</h$level>")
                } else {
                    out.append("<h$level>").append(inline(text)).append("</h$level>")
                }
                i++
                continue
            }
            // Setext heading (text line followed by === or ---).
            if (i + 1 < lines.size) {
                val under = lines[i + 1].trim()
                if (under.isNotEmpty() && under.all { it == '=' }) {
                    if (title == null && sections.isEmpty() && out.isEmpty()) {
                        title = stripInline(line.trim())
                    } else {
                        flushSection()
                        sectionTitle = stripInline(line.trim())
                        out.append("<h1>").append(inline(line.trim())).append("</h1>")
                    }
                    i += 2
                    continue
                }
                if (under.isNotEmpty() && under.all { it == '-' } && line.isNotBlank()) {
                    flushSection()
                    sectionTitle = stripInline(line.trim())
                    out.append("<h2>").append(inline(line.trim())).append("</h2>")
                    i += 2
                    continue
                }
            }
            // Horizontal rule.
            if (Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$").matches(line)) {
                out.append("<hr/>")
                i++
                continue
            }
            // Table (header + delimiter row).
            if (line.contains('|') && i + 1 < lines.size &&
                Regex("^\\s*\\|?[\\s:|-]+\\|\\s*$").matches(lines[i + 1]) &&
                lines[i + 1].contains('-')
            ) {
                val headerCells = splitRow(line)
                out.append("<table><thead><tr>")
                for (c in headerCells) out.append("<th>").append(inline(c)).append("</th>")
                out.append("</tr></thead><tbody>")
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    out.append("<tr>")
                    for (c in splitRow(lines[i])) {
                        out.append("<td>").append(inline(c)).append("</td>")
                    }
                    out.append("</tr>")
                    i++
                }
                out.append("</tbody></table>")
                continue
            }
            // Blockquote run.
            if (line.trimStart().startsWith(">")) {
                val buf = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    buf.append(lines[i].trimStart().removePrefix(">").trimStart())
                        .append(' ')
                    i++
                }
                out.append("<blockquote><p>").append(inline(buf.toString().trim()))
                    .append("</p></blockquote>")
                continue
            }
            // List run (ordered or unordered, nested by indent).
            val listMatch = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$").matchEntire(line)
            if (listMatch != null) {
                val items = mutableListOf<Triple<Int, Boolean, String>>()
                while (i < lines.size) {
                    val m = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
                        .matchEntire(lines[i]) ?: break
                    val indent = m.groupValues[1].length
                    val ordered = m.groupValues[2].first().isDigit()
                    items += Triple(indent, ordered, m.groupValues[3])
                    i++
                }
                out.append(renderList(items, 0, items.firstOrNull()?.first ?: 0))
                continue
            }
            // Plain paragraph: consecutive lines joined with a space
            // (Markdown soft wrap); a trailing double space is a <br/>.
            val para = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() &&
                !lines[i].trimStart().startsWith("#") &&
                !lines[i].trimStart().startsWith("```") &&
                !lines[i].trimStart().startsWith(">") &&
                Regex("^(\\s*)([-*+]|\\d+[.)])\\s+").matchEntire(lines[i]) == null &&
                !(lines[i].contains('|') && i + 1 < lines.size &&
                    lines[i + 1].contains('-') &&
                    Regex("^\\s*\\|?[\\s:|-]+\\|\\s*$").matches(lines[i + 1]))
            ) {
                val l = lines[i]
                if (para.isNotEmpty()) {
                    para.append(if (para.endsWith("  ")) "<br/>" else " ")
                }
                para.append(l.trim())
                i++
                // Setext underline ends this paragraph (handled next loop).
                if (i < lines.size && lines[i].trim().all { it == '=' || it == '-' } &&
                    lines[i].trim().isNotEmpty()
                ) break
            }
            out.append("<p>").append(inline(para.toString())).append("</p>")
        }
        flushSection()
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.drop(1)
        if (t.endsWith("|")) t = t.dropLast(1)
        return t.split("|").map { it.trim() }
    }

    /** Renders a flat item list into nested <ul>/<ol> by indent level. */
    private fun renderList(
        items: List<Triple<Int, Boolean, String>>,
        from: Int,
        baseIndent: Int
    ): String {
        val sb = StringBuilder()
        var idx = from
        var openTag: String? = null
        fun close() {
            if (openTag != null) {
                sb.append("</").append(openTag).append(">")
                openTag = null
            }
        }
        while (idx < items.size) {
            val (indent, ordered, text) = items[idx]
            if (indent < baseIndent) break
            if (indent > baseIndent) {
                // Nested run belongs inside the previous <li>: reopen it.
                val nested = renderList(items, idx, indent)
                // Strip the closing </li> we just wrote, append nested, re-close.
                val tail = "</li>"
                if (sb.endsWith(tail)) sb.setLength(sb.length - tail.length)
                sb.append(nested).append(tail)
                // Advance idx past the nested run.
                var j = idx
                while (j < items.size && items[j].first >= indent) j++
                idx = j
                continue
            }
            val tag = if (ordered) "ol" else "ul"
            if (openTag != tag) {
                close()
                sb.append("<").append(tag).append(">")
                openTag = tag
            }
            sb.append("<li>").append(inline(text)).append("</li>")
            idx++
        }
        close()
        return sb.toString()
    }

    /** Inline formatting with code spans protected from later passes. */
    private fun inline(text: String): String {
        val codes = mutableListOf<String>()
        var t = ConvertedBook.escape(text)
        t = Regex("`([^`]+)`").replace(t) { m ->
            codes += m.groupValues[1]
            "\u0000${codes.size - 1}\u0000"
        }
        // Images: keep only the alt text (no resolvable file beside the pick).
        t = Regex("!\\[([^\\]]*)\\]\\([^\\)]*\\)").replace(t) { m ->
            val alt = m.groupValues[1]
            if (alt.isBlank()) "" else "<em>$alt</em>"
        }
        t = Regex("\\[([^\\]]+)\\]\\(([^\\)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
            .replace(t) { m ->
                "<a href=\"" + m.groupValues[2] + "\">" + m.groupValues[1] + "</a>"
            }
        t = Regex("\\*\\*([^*]+)\\*\\*").replace(t, "<strong>$1</strong>")
        t = Regex("__([^_]+)__").replace(t, "<strong>$1</strong>")
        t = Regex("~~([^~]+)~~").replace(t, "<del>$1</del>")
        t = Regex("\\*([^*]+)\\*").replace(t, "<em>$1</em>")
        t = Regex("(?<!\\w)_([^_]+)_(?!\\w)").replace(t, "<em>$1</em>")
        t = Regex("\u0000(\\d+)\u0000").replace(t) { m ->
            "<code>" + codes[m.groupValues[1].toInt()] + "</code>"
        }
        return t
    }

    private fun stripInline(text: String): String =
        inline(text).replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&amp;", "&")
}
