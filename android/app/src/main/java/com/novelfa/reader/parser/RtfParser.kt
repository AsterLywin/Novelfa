package com.novelfa.reader.parser

import com.novelfa.reader.model.ParsedBook
import java.io.File
import java.nio.charset.Charset

/**
 * Rich Text Format (.rtf) books — pure-Kotlin RTF → HTML.
 *
 * A single linear pass over the file: groups push/pop a formatting
 * state (bold/italic/underline/strikethrough, font size, alignment,
 * code page, `\uc` skip count); control words mutate it; text is
 * emitted into paragraphs with the state as inline tags; `\par`
 * closes a paragraph and `\sect` starts a new chapter.
 *
 * SUPPORTED (the common controls):
 * - paragraphs (`\par`, `\pard`), line breaks (`\line`), tabs, page breaks
 * - bold / italic / underline / strikethrough (`\b`, `\i`, `\ul`, `\strike`, with `0` off)
 * - font size (`\fsN` half-points → `font-size:N/2 pt` span; the reader
 *   renders chapters in a WebView, so the span is honoured)
 * - alignment (`\ql`, `\qc`, `\qr`, `\qj`)
 * - Unicode (`\uN`, including high+low surrogate pairs, with `\ucN`
 *   fallback skipping), hex escapes (`\'hh`), raw bytes in the
 *   declared code page (`\ansi` / `\ansicpgN`, incl. 1256 Persian)
 * - groups and ignored destinations: `{\fonttbl}`, `{\colortbl}`,
 *   `{\stylesheet}`, `{\info}`, `\pict`, `\object`, footnotes,
 *   headers/footers, list tables, field instructions (`\fldinst` —
 *   the result text in `\fldrslt` IS kept), bookmarks, `{\*\...}`
 *   destinations in general
 * - tables: `\cell` / `\row` become paragraph breaks, so table text
 *   survives as paragraphs (structure is lost — see below)
 *
 * NOT SUPPORTED (stated, not silently half-done):
 * - embedded pictures (`\pict`) and embedded objects (`\object`):
 *   skipped entirely — no image is extracted, the book gets the
 *   generated cover like a plain-text book;
 * - footnotes (`\footnote`): skipped — their text does not appear
 *   (unlike FB2/DOCX, where footnotes are rendered as a notes list);
 * - tables as tables: cell text is kept, the grid is not;
 * - list markers: `\pntext` / list-table destinations are skipped,
 *   so list items read as plain paragraphs without bullets/numbers;
 * - text colour (`\cf`), highlighting, fonts (`\fN`), styles
 *   (`\sN`), paragraph spacing/indents: ignored — the reader's own
 *   theme, font and size settings govern;
 * - hyperlinks: a field's result text is kept, the link target is not;
 * - Office Math (`\mmath`): skipped;
 * - UTF-8 RTF (`\ansicpg65001`): the bytes are decoded as UTF-8, but
 *   `\'hh` escapes inside such a file are treated as single Latin-1
 *   bytes, so a file mixing both can mis-render those escapes.
 *
 * No new UI string is needed: a book with no readable text fails
 * import with the existing "no readable sections" message, exactly
 * like an empty TXT.
 */
object RtfParser {

    /** Destinations whose whole group is skipped (text and all). */
    private val IGNORED_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
        "objdata", "objclass", "objemb", "datafield",
        "header", "headerf", "headerl", "headerr",
        "footer", "footerf", "footerl", "footerr",
        "footnote", "listtable", "listoverridetable", "list",
        "revtbl", "rsidtbl", "generator", "thead", "datastore",
        "wgrffmtfilter", "fontemb", "fontfile", "filetbl",
        "fldinst", "bkmkstart", "bkmkend", "xe", "tc", "tcw", "private",
        "shpinst", "shptxt", "pntext", "pntxtb", "pntxta",
        "themedata", "colorschememapping", "latentstyles", "pgptbl",
        "mmath"
    )

    private const val DEFAULT_FONT_SIZE_HALF_POINTS = 24

    fun parse(
        bytes: ByteArray,
        fileName: String,
        outDir: File,
        chapterFallback: (Int) -> String
    ): ParsedBook {
        val title = infoTitle(bytes)
            ?: File(fileName).nameWithoutExtension
        val parser = Parser(bytes, chapterFallback)
        parser.run()
        val sections = parser.sections
        return ConvertedBook.write(
            outDir, title,
            if (sections.isEmpty()) emptyList()
            else ConvertedBook.chunkSections(sections)
        )
    }

    /**
     * Best-effort title from `{\info{\title ...}}` (the RTF
     * equivalent of DOCX core.xml): the text up to the next `}` or
     * control word, with `\'hh` escapes decoded in the file's
     * declared code page. Null when the file carries no title.
     */
    fun infoTitle(bytes: ByteArray): String? {
        val text = bytes.toString(Charsets.ISO_8859_1)
        val infoAt = text.indexOf("\\info")
        if (infoAt < 0) return null
        val titleAt = text.indexOf("\\title", infoAt)
        if (titleAt < 0 || titleAt > infoAt + 4096) return null
        var i = titleAt + "\\title".length
        while (i < text.length && text[i] == ' ') i++
        val end = text.indexOfFirst(i) { it == '}' || it == '\\' || it == '{' }
        val raw = text.substring(i, if (end < 0) text.length else end).trim()
        if (raw.isBlank()) return null
        // \'hh escapes inside the title, in the declared code page.
        val codepage = Regex("""\\ansicpg(\d+)""").find(text)?.groupValues?.get(1)
            ?.toIntOrNull()?.let { codepageFor(it) } ?: Charsets.UTF_8
        val decoded = Regex("""'([0-9a-fA-F]{2})""").replace(raw) { m ->
            val b = m.groupValues[1].toInt(16).toByte()
            String(byteArrayOf(b), codepage)
        }
        return decoded.trim().takeIf { it.isNotBlank() }
    }

    private fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
        for (i in from until length) if (pred(this[i])) return i
        return -1
    }

    private fun codepageFor(cpg: Int): Charset = when (cpg) {
        1256 -> Charset.forName("windows-1256")
        1252 -> Charset.forName("windows-1252")
        1250 -> Charset.forName("windows-1250")
        1251 -> Charset.forName("windows-1251")
        1255 -> Charset.forName("windows-1255")
        1257 -> Charset.forName("windows-1257")
        437 -> Charset.forName("IBM437")
        850 -> Charset.forName("IBM850")
        866 -> Charset.forName("IBM866")
        else -> runCatching { Charset.forName("windows-$cpg") }
            .getOrDefault(Charset.forName("windows-1252"))
    }

    /** One group's formatting state; pushed/popped on `{` / `}`. */
    private class Fmt(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var sizeHalfPoints: Int = DEFAULT_FONT_SIZE_HALF_POINTS,
        var align: Align = Align.LEFT,
        var codepage: Charset = Charset.forName("windows-1252"),
        var ucSkip: Int = 1
    ) {
        fun copy() = Fmt(bold, italic, underline, strike, sizeHalfPoints, align, codepage, ucSkip)
    }

    private enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

    private class Parser(
        bytes: ByteArray,
        private val chapterFallback: (Int) -> String
    ) {
        // The file as Latin-1 (byte → char identity) so control
        // words stay readable and raw bytes survive for code-page
        // mapping — except \ansicpg65001 files, decoded as UTF-8
        // up front (control words are ASCII either way).
        private val utf8Mode: Boolean =
            Regex("""\\ansicpg65001\b""").containsMatchIn(bytes.toString(Charsets.ISO_8859_1))
        private val text: String = if (utf8Mode) {
            String(bytes, Charsets.UTF_8)
        } else {
            bytes.toString(Charsets.ISO_8859_1)
        }
        private var i = 0
        private val n = text.length

        val sections = mutableListOf<ConvertedBook.Section>()
        private var sectionBody = StringBuilder()
        private var sectionHasContent = false

        private var state = Fmt()
        private val groupStack = ArrayDeque<Fmt>()

        /** Inline tags currently open inside the paragraph, as full strings. */
        private val openInline = mutableListOf<String>()
        private var paraOpen = false
        private var paraHtml = StringBuilder()

        fun run() {
            while (i < n) {
                when (val c = text[i]) {
                    '{' -> {
                        val first = peekGroupDestination(i)
                        if (first != null && first in IGNORED_DESTINATIONS) {
                            i = skipGroup(i)
                        } else {
                            groupStack.addLast(state)
                            state = state.copy()
                            i++
                        }
                    }
                    '}' -> {
                        if (groupStack.isNotEmpty()) {
                            state = groupStack.removeLast()
                        }
                        i++
                    }
                    '\\' -> parseControl()
                    '\n', '\r' -> i++ // source line breaks are not content
                    else -> {
                        emitText(mapByteChar(c).toString())
                        i++
                    }
                }
            }
            closeParagraph()
            flushSection()
        }

        /** Desired inline tags for the current state, as full strings. */
        private fun desiredInlineTags(): List<String> {
            val tags = mutableListOf<String>()
            if (state.bold) tags += "<strong>"
            if (state.italic) tags += "<em>"
            if (state.underline) tags += "<u>"
            if (state.strike) tags += "<del>"
            if (state.sizeHalfPoints != DEFAULT_FONT_SIZE_HALF_POINTS) {
                // Half-points → points; the span is honoured by the
                // WebView that renders chapters, and ignored harmlessly
                // anywhere else.
                val pt = state.sizeHalfPoints / 2
                tags += "<span style=\"font-size:${pt}pt\">"
            }
            return tags
        }

        private fun emitText(s: String) {
            if (s.isEmpty()) return
            ensureParagraph()
            val desired = desiredInlineTags()
            var common = 0
            while (common < openInline.size && common < desired.size &&
                openInline[common] == desired[common]
            ) common++
            for (j in openInline.size - 1 downTo common) {
                paraHtml.append(closeTagFor(openInline[j]))
                openInline.removeAt(j)
            }
            for (j in common until desired.size) {
                paraHtml.append(desired[j])
                openInline += desired[j]
            }
            paraHtml.append(ConvertedBook.escape(s))
            sectionHasContent = true
        }

        private fun closeTagFor(openTag: String): String = when {
            openTag.startsWith("<strong") -> "</strong>"
            openTag.startsWith("<em") -> "</em>"
            openTag.startsWith("<u") -> "</u>"
            openTag.startsWith("<del") -> "</del>"
            openTag.startsWith("<span") -> "</span>"
            else -> ""
        }

        private fun ensureParagraph() {
            if (paraOpen) return
            paraOpen = true
            paraHtml = StringBuilder()
            when (state.align) {
                Align.CENTER -> paraHtml.append("<p style=\"text-align:center\">")
                Align.RIGHT -> paraHtml.append("<p style=\"text-align:right\">")
                Align.JUSTIFY -> paraHtml.append("<p style=\"text-align:justify\">")
                Align.LEFT -> paraHtml.append("<p>")
            }
        }

        private fun closeParagraph() {
            if (!paraOpen) return
            for (j in openInline.size - 1 downTo 0) {
                paraHtml.append(closeTagFor(openInline[j]))
            }
            openInline.clear()
            paraHtml.append("</p>")
            sectionBody.append(paraHtml)
            paraOpen = false
        }

        private fun flushSection() {
            if (!sectionHasContent) return
            // Sections split only on \sect, so most RTF books have
            // exactly one section; the fallback title is the
            // localized "بخش N" / "Section N" the caller owns.
            sections += ConvertedBook.Section(
                chapterFallback(sections.size + 1), sectionBody.toString()
            )
            sectionBody = StringBuilder()
            sectionHasContent = false
        }

        /** A raw byte (a Latin-1 char) mapped through the active code page. */
        private fun mapByteChar(c: Char): Char {
            if (utf8Mode || c.code < 0x80) return c
            return runCatching {
                String(byteArrayOf(c.code.toByte()), state.codepage).firstOrNull() ?: c
            }.getOrDefault(c)
        }

        /** The first control word of the group starting at [braceAt], after an optional `\*`. */
        private fun peekGroupDestination(braceAt: Int): String? {
            var j = braceAt + 1
            while (j < n && (text[j] == ' ' || text[j] == '\n' || text[j] == '\r')) j++
            if (j < n && text[j] == '\\' && j + 1 < n && text[j + 1] == '*') {
                j += 2
                while (j < n && text[j] == ' ') j++
            }
            if (j >= n || text[j] != '\\') return null
            j++
            if (j >= n || !text[j].isLetter()) return null
            val start = j
            while (j < n && text[j].isLetter()) j++
            return text.substring(start, j).lowercase()
        }

        /** Index just past the group starting at [braceAt] (brace-counted, escapes honoured). */
        private fun skipGroup(braceAt: Int): Int {
            var depth = 0
            var j = braceAt
            while (j < n) {
                val c = text[j]
                if (c == '\\' && j + 1 < n && (text[j + 1] == '{' || text[j + 1] == '}' || text[j + 1] == '\\')) {
                    j += 2
                    continue
                }
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    if (depth == 0) return j + 1
                }
                j++
            }
            return n
        }

        private fun parseControl() {
            // text[i] == '\\'
            if (i + 1 >= n) {
                i++
                return
            }
            val next = text[i + 1]
            if (next.isLetter()) {
                var j = i + 1
                while (j < n && text[j].isLetter()) j++
                val word = text.substring(i + 1, j).lowercase()
                var param: Int? = null
                var k = j
                if (k < n && (text[k] == '-' || text[k].isDigit())) {
                    val start = k
                    if (text[k] == '-') k++
                    while (k < n && text[k].isDigit()) k++
                    param = text.substring(start, k).toIntOrNull()
                }
                // One space after a control word is its delimiter, not content.
                if (k < n && text[k] == ' ') k++
                i = k
                applyControlWord(word, param)
                return
            }
            // Control symbols: \'hh, \\, \{, \}, \~, \-, \_, \*, \:
            when (next) {
                '\'' -> {
                    if (i + 3 < n) {
                        val hex = text.substring(i + 2, i + 4)
                        val v = hex.toIntOrNull(16)
                        if (v != null) {
                            val ch = if (v < 0x80) v.toChar() else mapByteChar(v.toChar())
                            emitText(ch.toString())
                        }
                        i += 4
                    } else {
                        i = n
                    }
                }
                '\\', '{', '}' -> {
                    emitText(next.toString())
                    i += 2
                }
                '~' -> {
                    emitText("\u00A0")
                    i += 2
                }
                '-' -> {
                    // Optional hyphen: nothing visible.
                    i += 2
                }
                '_' -> {
                    emitText("-")
                    i += 2
                }
                '*' -> {
                    // "Ignore if unknown" flag for the next control
                    // word — the group peek above already uses it;
                    // here it is just a token to consume.
                    i += 2
                }
                ':' -> {
                    // Sub-entry marker in an index entry: ignore.
                    i += 2
                }
                else -> i += 2 // unknown symbol: skip, never emit
            }
        }

        private fun applyControlWord(word: String, param: Int?) {
            when (word) {
                "par", "page" -> closeParagraph()
                "sect" -> {
                    closeParagraph()
                    flushSection()
                }
                "pard" -> {
                    state.align = Align.LEFT
                }
                "plain" -> {
                    state.bold = false
                    state.italic = false
                    state.underline = false
                    state.strike = false
                    state.sizeHalfPoints = DEFAULT_FONT_SIZE_HALF_POINTS
                }
                "b" -> state.bold = param != 0
                "i" -> state.italic = param != 0
                "ul" -> state.underline = param != 0
                "ulnone" -> state.underline = false
                "strike" -> state.strike = param != 0
                "fs" -> {
                    if (param != null && param > 0) state.sizeHalfPoints = param
                }
                "ql" -> state.align = Align.LEFT
                "qc" -> state.align = Align.CENTER
                "qr" -> state.align = Align.RIGHT
                "qj" -> state.align = Align.JUSTIFY
                "line" -> {
                    ensureParagraph()
                    paraHtml.append("<br/>")
                    sectionHasContent = true
                }
                "tab" -> emitText("\u00A0\u00A0\u00A0\u00A0")
                "cell", "row" -> closeParagraph() // table grid lost; text survives
                "bullet" -> emitText("• ")
                "lquote" -> emitText("‘")
                "rquote" -> emitText("’")
                "ldblquote" -> emitText("“")
                "rdblquote" -> emitText("”")
                "emdash" -> emitText("—")
                "endash" -> emitText("–")
                "u" -> {
                    var v = param ?: return
                    if (v < 0) v += 65536
                    if (v in 0xD800..0xDBFF) {
                        // High surrogate: a following \u low
                        // surrogate completes the pair.
                        val low = peekNextUnicode()
                        if (low != null && low in 0xDC00..0xDFFF) {
                            emitText(
                                String(Character.toChars((v - 0xD800) * 0x400 + (low - 0xDC00) + 0x10000))
                            )
                            consumeNextUnicode()
                            skipFallbackUnits(state.ucSkip)
                            return
                        }
                    }
                    emitText(v.toChar().toString())
                    skipFallbackUnits(state.ucSkip)
                }
                "uc" -> {
                    if (param != null && param >= 0) state.ucSkip = param
                }
                "ansicpg" -> {
                    if (param != null) state.codepage = codepageFor(param)
                }
                "ansi" -> state.codepage = Charset.forName("windows-1252")
                "mac" -> state.codepage = runCatching { Charset.forName("x-MacRoman") }
                    .getOrDefault(Charset.forName("windows-1252"))
                "pc" -> state.codepage = Charset.forName("IBM437")
                "pca" -> state.codepage = Charset.forName("IBM850")
                "bin" -> {
                    // N raw bytes follow: not text, skip them.
                    if (param != null && param > 0) i = minOf(n, i + param)
                }
                // Ignored outright (no visible effect on the text):
                // font number, colour, style number, language,
                // spacing/indents, field content markers.
                "f", "cf", "cb", "highlight", "s", "lang", "sa", "sb",
                "fi", "li", "ri", "sl", "fldrslt", "field", "shp",
                "outlinelevel", "itap", "nosupersub", "super", "sub",
                "dn", "up", "charscalex", "expnd", "expndtw",
                "animtext", "strikedl", "uld", "uldash", "uldb",
                "ulth", "ulw", "ulwave", "ltrch", "rtlch",
                "ltrpar", "rtlpar", "ab", "absh", "ltrmark",
                "rtlmark", "zwbo", "zwnbo", "zwj", "zwnj" -> Unit
                else -> Unit // unknown control word: skip, never fail
            }
        }

        /** The value of the next `\uN` control word, without consuming it. */
        private fun peekNextUnicode(): Int? {
            var j = i
            if (j + 1 >= n || text[j] != '\\' || text[j + 1] != 'u') return null
            j += 2
            var k = j
            if (k < n && text[k] == '-') k++
            while (k < n && text[k].isDigit()) k++
            if (k == j) return null
            var v = text.substring(j, k).toIntOrNull() ?: return null
            if (v < 0) v += 65536
            return v
        }

        /** Consumes the next `\uN` control word (its delimiter space too). */
        private fun consumeNextUnicode() {
            var j = i + 2
            if (j < n && text[j] == '-') j++
            while (j < n && text[j].isDigit()) j++
            if (j < n && text[j] == ' ') j++
            i = j
        }

        /**
         * Skips [count] fallback characters after a `\uN`: the single
         * character (or one `\'hh` escape) that follows the control
         * word immediately. A control word or a group boundary in
         * that spot means the writer omitted the fallback — nothing
         * is skipped, so a `\uN\uN` run keeps every character.
         */
        private fun skipFallbackUnits(count: Int) {
            var left = count
            while (left > 0 && i < n) {
                val c = text[i]
                if (c == '{' || c == '}') return
                if (c == '\\' && i + 1 < n) {
                    val nx = text[i + 1]
                    if (nx == '\'') {
                        i = minOf(n, i + 4)
                        left--
                        continue
                    }
                    // A control word here is NOT a fallback character:
                    // stop, so `\uN\uN` keeps the second character.
                    if (nx.isLetter()) return
                    // A control symbol (\\, \{, \}) IS a literal char.
                    i += 2
                    left--
                    continue
                }
                i++
                left--
            }
        }
    }
}
