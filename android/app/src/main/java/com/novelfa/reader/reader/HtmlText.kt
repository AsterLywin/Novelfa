package com.novelfa.reader.reader

import java.io.File
import java.util.Locale

/**
 * Pure-JVM port of the MainWindow.xaml.cs chapter-text pipeline:
 * ExtractBody (809-822), StripScripts (713-716), ConvertSvgImages (754-770),
 * StripSizeAttributes (744-752, svgTagNames 719-723),
 * MakeImageSourcesAbsolute (772-807), HtmlToPlainText (1504-1509),
 * DetectDirection (1482-1502), ChapterDirAttr (1517-1525),
 * StampDirection (1527-1540), ApplyTextDirection (1542-1553),
 * EscapeForJs (2592-2602).
 */
object HtmlText {

    /** Copied verbatim from MainWindow.xaml.cs svgTagNames. */
    val svgTagNames: Set<String> = setOf(
        "svg", "image", "use", "symbol", "pattern", "mask", "g", "defs",
        "rect", "circle", "ellipse", "line", "polygon", "polyline", "path",
        "clippath", "filter", "foreignobject"
    )

    fun extractBody(html: String): String {
        val lower = html.lowercase(Locale.ROOT)
        val bodyStart = lower.indexOf("<body")
        val bodyEnd = lower.indexOf("</body>")
        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            val tagCloseIndex = html.indexOf('>', bodyStart)
            if (tagCloseIndex >= 0 && tagCloseIndex < bodyEnd) {
                // WPF Substring(start, length) -> Kotlin substring(start, endExclusive).
                return html.substring(tagCloseIndex + 1, bodyEnd)
            }
        }
        return html
    }

    fun stripScripts(html: String): String {
        var out = Regex("<script[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)
            .replace(html, "")
        out = Regex("<noscript[\\s\\S]*?</noscript\\s*>", RegexOption.IGNORE_CASE)
            .replace(out, "")
        // Inline event handlers (onclick=..., onload=..., ...) cannot run offline
        // and are a classic XSS vector in imported books.
        out = Regex(
            "\\s+on[a-zA-Z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
            RegexOption.IGNORE_CASE
        ).replace(out, "")
        // javascript:/vbscript: URLs in href/src, and data: URLs in
        // href: the reader runs with JS enabled, so a crafted book
        // could execute script when a link is tapped. Neuter the
        // attribute (kept as inert data-blocked). data: in src is
        // NOT neutered anymore: an EPUB chapter's embedded base64
        // image (data:image/…) is ordinary content, and the old rule
        // silently stripped every such image from the reader.
        out = Regex(
            "\\s+href\\s*=\\s*(\"|')\\s*(?:javascript|vbscript|data)\\s*:",
            RegexOption.IGNORE_CASE
        ).replace(out, " data-blocked=$1")
        out = Regex(
            "\\s+(?:src|xlink:href)\\s*=\\s*(\"|')\\s*(?:javascript|vbscript)\\s*:",
            RegexOption.IGNORE_CASE
        ).replace(out, " data-blocked=$1")
        return out
    }

    fun convertSvgImages(html: String): String {
        var out = Regex(
            "<svg\\b[^>]*>\\s*(?:<g\\b[^>]*>\\s*)?<image\\b[^>]*?>" +
                "\\s*(?:</image>\\s*)?(?:</g>\\s*)?</svg>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).replace(html) { m ->
            val href = Regex(
                "<image\\b[^>]*?\\b(?:xlink:)?href\\s*=\\s*[\"']([^\"']+)[\"']",
                RegexOption.IGNORE_CASE
            ).find(m.value)
            if (href == null) m.value else "<img src=\"" + href.groupValues[1] + "\" />"
        }
        // Remaining standalone <svg> (vector art, not image wrappers): force
        // responsive scaling so fixed width/height attributes cannot overflow
        // the phone viewport. Merges into an existing style attribute when present.
        out = Regex("<svg\\b([^>]*)>", RegexOption.IGNORE_CASE).replace(out) { m ->
            val attrs = m.groupValues[1]
            if (attrs.contains("max-width", ignoreCase = true)) {
                m.value
            } else {
                val styleMatch = Regex(
                    "style\\s*=\\s*([\"'])(.*?)\\1",
                    RegexOption.IGNORE_CASE
                ).find(attrs)
                if (styleMatch == null) {
                    "<svg" + attrs + " style=\"max-width:100%;height:auto;\">"
                } else {
                    val quote = styleMatch.groupValues[1]
                    val merged = (styleMatch.groupValues[2].trim().trimEnd(';') +
                        ";max-width:100%;height:auto;")
                    "<svg" + attrs.replaceRange(
                        styleMatch.range,
                        "style=" + quote + merged + quote
                    ) + ">"
                }
            }
        }
        return out
    }

    private val tagPattern = Regex("<(?![/!?])([a-zA-Z][a-zA-Z0-9:_-]*)[^>]*>")
    private val sizeAttrPattern = Regex(
        "\\s(width|height)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
        RegexOption.IGNORE_CASE
    )

    fun stripSizeAttributes(html: String): String =
        tagPattern.replace(html) { m ->
            val name = m.groupValues[1]
            if (svgTagNames.any { it.equals(name, ignoreCase = true) }) {
                m.value
            } else {
                sizeAttrPattern.replace(m.value, "")
            }
        }

    private val imgSrcPattern = Regex(
        "(<img\\b[^>]*?\\ssrc\\s*=\\s*)([\"'])([^\"']*)\\2",
        RegexOption.IGNORE_CASE
    )

    private val srcsetPattern = Regex(
            "(<\\w+\\b[^>]*?\\ssrcset\\s*=\\s*)([\"'])([^\"']*)\\2",
        RegexOption.IGNORE_CASE
    )

    private val cssUrlPattern = Regex(
        "url\\(\\s*([\"']?)([^\"')\\s][^\"')]*)\\1\\s*\\)",
        RegexOption.IGNORE_CASE
    )

    /** URL-decodes a path while preserving literal '+' (URLDecoder turns it into a space). */
    private fun decodePath(raw: String): String = try {
        java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        raw
    }

    private fun isAbsoluteRef(lower: String): Boolean =
        lower.isEmpty() || lower.startsWith("data:") ||
            lower.startsWith("http:") || lower.startsWith("https:") ||
            lower.startsWith("file:") || lower.startsWith("//") ||
            lower.startsWith("about:")

    fun makeImageSourcesAbsolute(html: String, folder: File): String {
        val base = try {
            File(folder.path + File.separatorChar).toURI()
        } catch (e: Exception) {
            return html
        }
        fun absolutize(src: String): String? {
            if (isAbsoluteRef(src.lowercase(Locale.ROOT))) return null
            return try {
                base.resolve(decodePath(src)).toASCIIString()
            } catch (e: Exception) {
                null
            }
        }
        var out = imgSrcPattern.replace(html) { m ->
            val abs = absolutize(m.groupValues[3]) ?: return@replace m.value
            m.groupValues[1] + m.groupValues[2] + abs + m.groupValues[2]
        }
        // srcset="a.png 1x, b.png 2x": resolve each candidate URL, keep descriptors.
        out = srcsetPattern.replace(out) { m ->
            val resolved = m.groupValues[3].split(",").joinToString(", ") { candidate ->
                val t = candidate.trim()
                if (t.isEmpty()) {
                    candidate
                } else {
                    val sp = t.indexOfAny(charArrayOf(' ', '\t'))
                    val url = if (sp < 0) t else t.substring(0, sp)
                    val desc = if (sp < 0) "" else t.substring(sp)
                    val abs = absolutize(url) ?: return@joinToString candidate
                    abs + desc
                }
            }
            m.groupValues[1] + m.groupValues[2] + resolved + m.groupValues[2]
        }
        // CSS url(...) in inline styles / embedded <style>: resolve relative refs.
        out = cssUrlPattern.replace(out) { m ->
            val abs = absolutize(m.groupValues[2]) ?: return@replace m.value
            "url('" + abs + "')"
        }
        return out
    }

    fun htmlToPlainText(html: String): String {
        var t = Regex(
            "<(style|script)[\\s\\S]*?</\\1\\s*>",
            RegexOption.IGNORE_CASE
        ).replace(html, " ")
        t = Regex("<[^>]*>").replace(t, " ")
        return htmlDecode(t)
    }

    fun htmlDecode(s: String): String {
        var t = s
        t = t.replace("&lt;", "<")
        t = t.replace("&gt;", ">")
        t = t.replace("&quot;", "\"")
        t = t.replace("&#39;", "'")
        t = t.replace("&apos;", "'")
        t = t.replace("&nbsp;", 0x00A0.toChar().toString())
        t = Regex("&#(\\d+);").replace(t) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code == null) m.value else code.toChar().toString()
        }
        t = t.replace("&amp;", "&")
        return t
    }

    fun htmlEscape(s: String): String {
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

    /**
     * 0 = no letters, 1 = latin majority (LTR), 2 = arabic/persian majority (RTL).
     * Char ranges copied EXACTLY from MainWindow.DetectDirection.
     */
    fun detectDirection(text: String): Int {
        var latin = 0
        var rtl = 0
        for (c in text) {
            if ((c in 'A'..'Z') || (c in 'a'..'z') ||
                (c in 0x00C0.toChar()..0x024F.toChar() &&
                    c != 0x00D7.toChar() && c != 0x00F7.toChar())
            ) {
                latin++
            } else if ((c in 0x0590.toChar()..0x08FF.toChar()) ||
                (c in 0xFB1D.toChar()..0xFDFF.toChar()) ||
                (c in 0xFE70.toChar()..0xFEFF.toChar())) {
                rtl++
            }
        }
        if (latin == 0 && rtl == 0) {
            return 0
        }
        return if (latin > rtl) 1 else 2
    }

    fun chapterDirAttr(bodyHtml: String): String {
        return if (detectDirection(htmlToPlainText(bodyHtml)) == 1) {
            " dir=\"ltr\" data-rdr=\"l\""
        } else {
            " dir=\"rtl\" data-rdr=\"r\""
        }
    }

    private val dirAttrPattern = Regex(
        "\\s(dir|data-rdr)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
        RegexOption.IGNORE_CASE
    )

    private fun stamp(tag: String, attrs: String, inner: String, full: String): String {
        val d = detectDirection(htmlToPlainText(inner))
        if (d == 0) {
            return full
        }
        val clean = dirAttrPattern.replace(attrs, "")
        val stampStr = if (d == 1) " dir=\"ltr\" data-rdr=\"l\"" else " dir=\"rtl\" data-rdr=\"r\""
        return "<$tag$clean$stampStr>$inner</$tag>"
    }

    fun applyTextDirection(body: String): String {
        var b = Regex(
            "<(p|h[1-6]|dd|dt|figcaption|caption)\\b([^>]*?)(?<!/)>([\\s\\S]*?)</\\1\\s*>",
            RegexOption.IGNORE_CASE
        ).replace(body) { m ->
            stamp(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.value)
        }
        b = Regex(
            "<(div)\\b([^>]*?)(?<!/)>((?:(?!<div\\b|</div|<p\\b|<h[1-6]\\b|<ul\\b|<ol\\b|<table\\b|<blockquote\\b)[\\s\\S])*?)</div\\s*>",
            RegexOption.IGNORE_CASE
        ).replace(b) { m ->
            stamp(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.value)
        }
        b = Regex(
            "<(ul|ol|blockquote)\\b([^>]*?)(?<!/)>([\\s\\S]*?)</\\1\\s*>",
            RegexOption.IGNORE_CASE
        ).replace(b) { m ->
            stamp(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.value)
        }
        return b
    }

    fun prepareChapterBody(html: String, absoluteFolder: File?): String {
        var body = stripScripts(extractBody(html))
        body = convertSvgImages(body)
        body = stripSizeAttributes(body)
        if (absoluteFolder != null) {
            body = makeImageSourcesAbsolute(body, absoluteFolder)
        }
        body = markFootnoteLinks(body)
        return applyTextDirection(body)
    }

    /**
     * Marks footnote-reference links so the reader can tell them
     * apart from ordinary in-book links (see LINK_JS): a marked link
     * opens the note in a popup when the footnote-display setting
     * says so, instead of navigating away. EPUB chapters are the
     * book's own XHTML, so the mark is a heuristic — the href
     * fragment looks like a footnote id (fn1, footnote-2, ch1-fn3,
     * note_4, endnote1), or the link carries epub:type="noteref",
     * or its class names a footnote. DOCX references are marked
     * deterministically by the parser instead (data-novelfa-footnote
     * is already on them; an existing mark is never doubled). The
     * note's own back-link (↩, pointing at the reference) is never
     * marked: it stays a navigation link.
     */
    fun markFootnoteLinks(html: String): String {
        return Regex("<a\\b[^>]*>", RegexOption.IGNORE_CASE).replace(html) { m ->
            val tag = m.value
            if (tag.contains("data-novelfa-footnote", ignoreCase = true)) return@replace tag
            val href = Regex(
                "\\bhref\\s*=\\s*\"([^\"]*)\"|\\bhref\\s*=\\s*'([^']*)'",
                RegexOption.IGNORE_CASE
            ).find(tag)?.let {
                it.groupValues[1].ifEmpty { it.groupValues[2] }
            } ?: return@replace tag
            val frag = href.substringAfter('#', "")
            val isFootnote =
                FOOTNOTE_FRAG.containsMatchIn(frag) ||
                    Regex(
                        "(?:epub:)?type\\s*=\\s*[\"']noteref[\"']",
                        RegexOption.IGNORE_CASE
                    ).containsMatchIn(tag) ||
                    Regex(
                        "\\bclass\\s*=\\s*\"([^\"]*)\"|\\bclass\\s*=\\s*'([^']*)'",
                        RegexOption.IGNORE_CASE
                    ).find(tag)?.let {
                        val cls = it.groupValues[1].ifEmpty { it.groupValues[2] }
                        Regex(
                            "\\b(footnote|noteref|fn)\\b",
                            RegexOption.IGNORE_CASE
                        ).containsMatchIn(cls)
                    } == true
            if (!isFootnote) return@replace tag
            tag.replaceFirst("<a", "<a data-novelfa-footnote=\"1\"", ignoreCase = true)
        }
    }

    /** A footnote-id fragment: fn1, footnote-2, ch1-fn3, note_4, endnote1. */
    private val FOOTNOTE_FRAG = Regex(
        "(?:^|[-_.])(?:fn|footnote|note|endnote)[-_]?[0-9]+[a-z]?$",
        RegexOption.IGNORE_CASE
    )

    /**
     * The inner HTML of the element with id [fragId] in [html] (a
     * footnote/endnote paragraph or list item), or null when the
     * element is missing or unclosed. The element is bounded by its
     * own closing tag — footnote containers are flat paragraphs or
     * list items, so the first matching close tag ends it (a
     * same-tag nest inside would cut early; that never occurs in a
     * footnote). Shared by [footnoteText] (plain text) and the
     * footnote popup (clickable links, Moon+ Reader parity).
     */
    fun footnoteInnerHtml(html: String, fragId: String): String? {
        if (fragId.isBlank()) return null
        val idAt = Regex(
            "\\bid\\s*=\\s*[\"']" + Regex.escape(fragId) + "[\"']",
            RegexOption.IGNORE_CASE
        ).find(html) ?: return null
        val tagStart = html.lastIndexOf('<', idAt.range.first)
        if (tagStart < 0) return null
        val nameStart = tagStart + 1
        if (nameStart >= html.length || html[nameStart] == '/') return null
        val tagName = Regex("[A-Za-z][A-Za-z0-9]*").find(html, nameStart)
            ?.takeIf { it.range.first == nameStart }?.value ?: return null
        val contentStart = html.indexOf('>', idAt.range.last)
        if (contentStart < 0) return null
        val closeAt = Regex(
            "</" + Regex.escape(tagName) + "\\s*>",
            RegexOption.IGNORE_CASE
        ).find(html, contentStart + 1) ?: return null
        return html.substring(contentStart + 1, closeAt.range.first)
    }

    /**
     * The plain text of the element with id [fragId] in [html]
     * (a footnote/endnote paragraph or list item), or null when the
     * element is missing, unclosed, or empty. The DOCX back-link
     * remnant ("↩") is stripped from the front; everything else
     * is the note's own text.
     */
    fun footnoteText(html: String, fragId: String): String? {
        val inner = footnoteInnerHtml(html, fragId) ?: return null
        var text = htmlToPlainText(inner)
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.startsWith("↩")) text = text.substring(1).trim()
        return text.ifEmpty { null }
    }

    /**
     * The note's inner HTML for the popup, or null when the element
     * is missing. Unlike [footnoteText] the back-link stays: it is
     * the note's own link (Moon+ Reader shows it as "[n←]" and
     * keeps it tappable), and the popup's OPEN button appears
     * exactly when this HTML carries a link.
     */
    fun footnoteHtml(html: String, fragId: String): String? {
        val inner = footnoteInnerHtml(html, fragId) ?: return null
        return inner.trim().ifEmpty { null }
    }

    /** Replacements copied EXACTLY from MainWindow.EscapeForJs. */
    fun escapeForJs(s: String): String {
        if (s.isEmpty()) {
            return ""
        }
        return s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", "")
            .replace("\n", "\\n")
            .replace(0x2028.toChar().toString(), "\\u2028")
            .replace(0x2029.toChar().toString(), "\\u2029")
    }

    /**
     * Decodes one JSON string literal (what WebView
     * evaluateJavascript returns for a JS string: the value
     * JSON-encoded, e.g. "\"a\\n b\""). Returns null when the
     * input is not a well-formed string literal. Pure Kotlin,
     * no org.json dependency, so it runs on the JVM harness.
     */
    fun decodeJsString(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.length < 2 || s.first() != '"' || s.last() != '"') return null
        val out = StringBuilder(s.length)
        var i = 1
        while (i < s.length - 1) {
            val c = s[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= s.length - 1) return null
            when (val e = s[i + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 5 >= s.length) return null
                    val code = s.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    i += 4
                }
                else -> return null
            }
            i += 2
        }
        return out.toString()
    }

    /** One live text selection: the "chap:start-end" range id plus the selected text. */
    data class SelectionInfo(val range: String, val text: String)

    /**
     * Parses the evaluateJavascript result of
     * `JSON.stringify({r: <range>, t: <text>})` — an outer JSON
     * string literal wrapping an inner JSON object with exactly
     * those two fields. Returns null on any malformed input, an
     * empty range, or empty text (a collapsed selection reports
     * both empty and must not act).
     */
    fun parseSelectionInfo(raw: String?): SelectionInfo? {
        val inner = decodeJsString(raw) ?: return null
        val range = extractJsonStringField(inner, "r") ?: return null
        val text = extractJsonStringField(inner, "t") ?: return null
        if (range.isEmpty() || text.isBlank()) return null
        return SelectionInfo(range, text)
    }

    /** Reads one string field out of a small JSON object literal ("key":"value"), escape-aware. */
    private fun extractJsonStringField(json: String, key: String): String? {
        val needle = "\"$key\":\""
        val at = json.indexOf(needle)
        if (at < 0) return null
        val start = at + needle.length
        val out = StringBuilder()
        var i = start
        while (i < json.length) {
            val c = json[i]
            if (c == '"') return out.toString()
            if (c == '\\' && i + 1 < json.length) {
                when (val e = json[i + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (i + 5 >= json.length) return null
                        val code = json.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        i += 4
                    }
                    else -> return null
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return null
    }
}
