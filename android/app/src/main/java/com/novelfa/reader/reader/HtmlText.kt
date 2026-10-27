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
        return applyTextDirection(body)
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
}
