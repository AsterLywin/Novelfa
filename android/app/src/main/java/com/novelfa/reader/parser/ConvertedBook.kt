package com.novelfa.reader.parser

import com.novelfa.reader.model.Chapter
import com.novelfa.reader.model.ParsedBook
import java.io.File

/**
 * The on-disk shape of a converted (non-EPUB) book: one XHTML file per
 * section in a directory, plus optional sidecar files (DOCX images live
 * in a `media/` subfolder, referenced relatively from the chapters).
 *
 * Both the importer (fresh parse, to count sections and read the title)
 * and the reader (open path, cached under `epub_open/<bookId>`) use this
 * shape, so a converted book opens exactly like an EPUB's extracted tree:
 * [Chapter.file] points at a chapter file and the reader's existing
 * `prepareChapterBody` pipeline (per-paragraph RTL/LTR stamping, image
 * absolutizing) applies unchanged.
 */
object ConvertedBook {

    /** One reader section: a drawer title and its HTML body fragment. */
    data class Section(val title: String, val bodyHtml: String)

    /** Sections longer than this are split at a paragraph boundary. */
    const val MAX_SECTION_CHARS = 12_000

    /**
     * Writes [sections] as `ch0001.xhtml`… into [outDir] (created if
     * needed) and returns the [ParsedBook] the reader consumes. Each file
     * carries its title in a `<title>` element so [read] can rebuild the
     * drawer titles later without re-parsing the source file.
     */
    fun write(
        outDir: File,
        title: String,
        sections: List<Section>,
        coverFile: File? = null
    ): ParsedBook {
        outDir.mkdirs()
        val chapters = sections.mapIndexed { i, section ->
            // Locale.US: in a Persian locale %d would emit Eastern-Arabic
            // digits, which [read]'s ch\d+ match would then miss.
            val file = File(outDir, "ch%04d.xhtml".format(java.util.Locale.US, i + 1))
            file.writeText(
                "<html><head><meta charset=\"utf-8\"><title>" +
                    escape(section.title) + "</title></head><body>" +
                    section.bodyHtml + "</body></html>",
                Charsets.UTF_8
            )
            Chapter(title = section.title, file = file)
        }
        return ParsedBook(title = title, chapters = chapters, coverFile = coverFile)
    }

    /**
     * Rebuilds a [ParsedBook] from a directory previously written by
     * [write], without touching the source file. Null when the directory
     * holds no chapter files (caller falls back to a fresh parse).
     */
    fun read(
        outDir: File,
        fileName: String,
        chapterFallback: (Int) -> String
    ): ParsedBook? {
        val files = outDir.listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("ch\\d+\\.xhtml")) }
            ?.sortedBy { it.name }
            ?: return null
        if (files.isEmpty()) return null
        val title = File(fileName).nameWithoutExtension
        val chapters = files.mapIndexed { i, file ->
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: ""
            val found = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)?.let { unescape(it) }
                ?.trim()?.takeIf { it.isNotBlank() }
            Chapter(title = found ?: chapterFallback(i + 1), file = file)
        }
        return ParsedBook(title = title, chapters = chapters, coverFile = null)
    }

    /**
     * Splits a long section body at paragraph boundaries so no section
     * exceeds [MAX_SECTION_CHARS]. Later chunks keep the section title
     * with a language-neutral `(2)`, `(3)`… suffix — no hardcoded words
     * in either UI language.
     */
    private val voidTags = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "source", "track", "wbr"
    )

    private val blockTags = setOf(
        "address", "article", "aside", "blockquote", "details", "div",
        "dl", "dt", "dd", "fieldset", "figcaption", "figure", "footer",
        "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "li",
        "main", "nav", "ol", "pre", "section", "table", "ul"
    )

    private val paraPattern = Regex(
        "<p(\\s[^>]*)?>([\\s\\S]*?)</p\\s*>", RegexOption.IGNORE_CASE
    )
    private val tokenPattern = Regex("<[^>]*>|[^<]+")
    private val tagNamePattern = Regex("</?\\s*([A-Za-z][A-Za-z0-9-]*)")

    private fun plainTextLength(html: String): Int =
        Regex("<[^>]*>").replace(html, "").length

    /**
     * Splits any single <p>...</p> block whose TEXT exceeds
     * [MAX_SECTION_CHARS] into consecutive <p> pieces, each holding at
     * most [MAX_SECTION_CHARS] of text. Inline tags open across a split
     * are closed and reopened, so every piece stays valid HTML and the
     * text is preserved exactly — only piece boundaries are added.
     *
     * Cuts prefer a space (never mid-word when a space is in reach);
     * a spaceless run is hard-cut, moved back out of any entity
     * (`&amp;`) and out of any surrogate pair so no character is
     * corrupted. A paragraph containing block-level markup is left
     * whole (parsers never emit that inside a <p>; the fallback is
     * safe, just un-split).
     */
    fun splitLongParagraphs(bodyHtml: String): String {
        return paraPattern.replace(bodyHtml) { m ->
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            if (plainTextLength(inner) <= MAX_SECTION_CHARS) return@replace m.value
            splitParagraph(attrs, inner) ?: m.value
        }
    }

    /** Splits one over-long paragraph, or null when it must stay whole. */
    private fun splitParagraph(attrs: String, inner: String): String? {
        val tokens = tokenPattern.findAll(inner).map { it.value }.toList()
        // Block-level markup inside a <p> is not splittable safely.
        for (t in tokens) {
            if (!t.startsWith("<")) continue
            val name = tagNamePattern.find(t)?.groupValues?.get(1)?.lowercase()
                ?: continue
            if (name in blockTags) return null
        }
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        var currentText = 0
        var pieceHasContent = false
        // Stack of open inline tags: (name, full opening tag) so a split
        // can reopen them verbatim (attributes preserved).
        val stack = ArrayDeque<Pair<String, String>>()
        fun closePiece() {
            for ((name, _) in stack.asReversed()) current.append("</").append(name).append(">")
            current.append("</p>")
            pieces += current.toString()
            current.clear()
            currentText = 0
            pieceHasContent = false
        }
        fun openPiece() {
            current.append("<p").append(attrs).append(">")
            for ((_, full) in stack) current.append(full)
        }
        openPiece()
        for (t in tokens) {
            if (t.startsWith("<")) {
                val name = tagNamePattern.find(t)?.groupValues?.get(1)?.lowercase()
                if (name == null) {
                    current.append(t)
                    continue
                }
                val isClose = t.startsWith("</")
                val isVoid = t.endsWith("/>") || name in voidTags
                when {
                    isClose -> {
                        // Pop up to and including the matching opener.
                        while (stack.isNotEmpty()) {
                            val (n, _) = stack.removeLast()
                            current.append("</").append(n).append(">")
                            if (n == name) break
                        }
                    }
                    isVoid -> {
                        current.append(t)
                        pieceHasContent = true
                    }
                    else -> {
                        stack.addLast(name to t)
                        current.append(t)
                    }
                }
                continue
            }
            // Text run: append in pieces, cutting at the cap.
            var rest = t
            while (rest.isNotEmpty()) {
                if (currentText >= MAX_SECTION_CHARS) {
                    // Full piece with no room left: flush first so the
                    // cut below always has room > 0 (and progress).
                    closePiece()
                    openPiece()
                    continue
                }
                val room = MAX_SECTION_CHARS - currentText
                if (rest.length <= room) {
                    current.append(rest)
                    currentText += rest.length
                    pieceHasContent = true
                    rest = ""
                    break
                }
                var cut = rest.lastIndexOf(' ', room.coerceAtMost(rest.length - 1))
                if (cut < 0) cut = room
                cut = safeCut(rest, cut)
                if (cut <= 0) cut = room.coerceAtMost(rest.length)
                current.append(rest.substring(0, cut))
                closePiece()
                openPiece()
                // Keep the cut space at the head of the next piece (it
                // collapses visually at the paragraph start, but dropping
                // it would glue two words together across the split).
                rest = rest.substring(cut)
            }
        }
        // Close the final piece — unless the paragraph ended exactly on
        // a split, leaving a fresh empty piece behind (skip it).
        if (pieceHasContent) {
            for ((name, _) in stack.asReversed()) current.append("</").append(name).append(">")
            current.append("</p>")
            pieces += current.toString()
        }
        if (pieces.size <= 1) return null
        return pieces.joinToString("")
    }

    /**
     * Moves a cut point out of an entity (`&amp;`) and out of a UTF-16
     * surrogate pair, so a hard cut never corrupts a character. A cut
     * at a space is already safe (entities contain no spaces).
     */
    private fun safeCut(text: String, cut: Int): Int {
        var c = cut.coerceIn(0, text.length)
        if (c > 0 && c < text.length) {
            val prev = text[c - 1]
            val next = text[c]
            if (prev.isHighSurrogate() && next.isLowSurrogate()) c -= 1
        }
        // Back out of an entity: an '&' at most 10 chars back with no
        // ';' between it and the cut means the cut is inside `&...;`.
        var amp = -1
        var i = (c - 1).coerceAtMost(text.length - 1)
        while (i >= 0 && c - 1 - i < 10) {
            if (text[i] == ';') break
            if (text[i] == '&') {
                amp = i
                break
            }
            i--
        }
        if (amp >= 0) c = amp
        return c
    }

    fun chunkSections(sections: List<Section>): List<Section> {
        val out = mutableListOf<Section>()
        for (section in sections) {
            if (section.bodyHtml.length <= MAX_SECTION_CHARS) {
                out += section
                continue
            }
            // A section over the cap may still be ONE paragraph (a TXT
            // with no blank lines, a DOCX paragraph holding hundreds of
            // lines): the <p>-boundary split below would pass it through
            // whole, and the paged reader then binary-searches that
            // single leaf for every page break — tens of seconds on a
            // phone, i.e. the chapter effectively never renders. Split
            // such paragraphs first; the boundary split then works.
            val body = splitLongParagraphs(section.bodyHtml)
            val paragraphs = body.split("(?=<p[ >])".toRegex())
                .filter { it.isNotBlank() }
            var current = StringBuilder()
            var chunk = 1
            fun flush() {
                if (current.isNotEmpty()) {
                    val title = if (chunk == 1) section.title
                    else "${section.title} ($chunk)"
                    out += Section(title, current.toString())
                    current = StringBuilder()
                    chunk++
                }
            }
            for (p in paragraphs) {
                if (current.isNotEmpty() &&
                    current.length + p.length > MAX_SECTION_CHARS
                ) {
                    flush()
                }
                current.append(p)
            }
            flush()
        }
        return out
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&amp;", "&")
}
