package com.novelfa.reader.parser

import com.novelfa.reader.model.ParsedBook
import java.io.File
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * FictionBook 2 (.fb2 plain XML, .fbz = ZIP containing one .fb2).
 *
 * An FB2 is one XML document: `<description>` carries the metadata
 * (`<title-info>` with `<book-title>` and an optional `<coverpage>`
 * pointing at a `<binary>` image), one or more `<body>` elements hold
 * the text — the main body (no `name`) and, when present, a
 * `<body name="notes">` with the footnotes — and `<binary>` elements
 * hold base64 images (the cover and any illustrations).
 *
 * The conversion follows the EPUB/DOCX shape: top-level `<section>`
 * elements of each content body become chapters (a body with no
 * sections becomes one chapter), nested sections become headings
 * inside their chapter, and the reader never knows the difference.
 * Footnotes keep the DOCX behaviour: a numbered superscript link
 * at the reference, the note texts as a numbered list at the end
 * of the last section, each linking back to its reference (the
 * `data-novelfa-footnote` marker is what the reader's footnote
 * popup keys on).
 *
 * NOT supported (deliberately, like the DOCX parser's limits):
 * - `<annotation>` (the title-info blurb) is not rendered — it is
 *   metadata, the same way the EPUB parser does not render the OPF
 *   description;
 * - text colour / font names from `<style>` are dropped (the
 *   reader's own theme and font settings govern);
 * - `<binary>` images over ~10 MiB decoded are skipped, never
 *   decoded (a hostile book must not OOM the parse).
 *
 * XML hardening mirrors [DocxParser]: external entities stay
 * disabled (best-effort flags) and DOCTYPE is tolerated, because
 * older FB2 files declare one and never need entities — a hostile
 * DOCTYPE still cannot pull in external content.
 */
object Fb2Parser {

    private const val FB_NS = "http://www.gribuser.ru/xml/fictionbook/2.0"

    /** A decoded image larger than this is skipped, never held in memory. */
    private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

    /** Base64 text longer than this cannot decode under the cap above. */
    private const val MAX_BASE64_CHARS = 14L * 1024 * 1024

    /**
     * Convenience entry: a plain .fb2 is read directly; a .fbz (or
     * any ZIP carrying a .fb2 entry) is opened and its first .fb2
     * entry is parsed. The extracted bytes never touch disk — the
     * generated chapters are the only output, beside the cover and
     * illustrations under `media/`.
     */
    fun parseFile(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String,
        /** Original file name (the stored file is "$uuid.fb2"/"$uuid.fbz"). */
        titleFallback: String? = null,
        checkCancelled: (() -> Unit)? = null
    ): ParsedBook {
        val bytes = fb2Bytes(source)
        return parse(
            bytes,
            titleFallback?.takeIf { it.isNotBlank() } ?: source.name,
            outDir, chapterFallback, checkCancelled
        )
    }

    /**
     * The .fb2 document bytes inside [source]: the first .fb2 entry
     * when the file is a ZIP carrying one (the .fbz shape), the whole
     * file otherwise. A ZIP with no .fb2 entry is not an FBZ — the
     * plain read below then fails as invalid XML, not as a zip error.
     */
    fun fb2Bytes(source: File): ByteArray {
        runCatching {
            java.util.zip.ZipFile(source).use { zf ->
                val entry = zf.entries().toList()
                    .firstOrNull { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }
                    ?: return@runCatching null
                return zf.getInputStream(entry).use { it.readBytes() }
            }
        }
        return source.readBytes()
    }

    /**
     * Size of the .fb2 document inside [source] in bytes: the .fb2
     * entry's uncompressed size for a ZIP, the file size otherwise.
     * Lets callers refuse an absurd document before reading it.
     * -1 when the size cannot be known.
     */
    fun fb2DocumentSize(source: File): Long {
        runCatching {
            java.util.zip.ZipFile(source).use { zf ->
                val entry = zf.entries().toList()
                    .firstOrNull { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }
                if (entry != null) return entry.size
            }
        }
        return runCatching { source.length() }.getOrDefault(-1L)
    }

    fun parse(
        bytes: ByteArray,
        fileName: String,
        outDir: File,
        chapterFallback: (Int) -> String,
        checkCancelled: (() -> Unit)? = null
    ): ParsedBook {
        val dbf = secureFactory()
        val doc = runCatching {
            dbf.newDocumentBuilder().parse(bytes.inputStream())
        }.getOrNull() ?: throw IllegalArgumentException("Not a valid FB2: $fileName")
        val root = doc.documentElement
            ?: throw IllegalArgumentException("Not a valid FB2: $fileName")
        if (localName(root) != "FictionBook") {
            throw IllegalArgumentException("Not a valid FB2: $fileName")
        }
        val state = State(outDir)
        // Metadata: <description><title-info> — book-title first,
        // then the coverpage image id. Authors are NOT read: the
        // library has no author field (same as EPUB/DOCX).
        val description = firstChildElement(root, "description")
        val titleInfo = description?.let { firstChildElement(it, "title-info") }
        val bookTitle = titleInfo?.let { firstChildElement(it, "book-title") }
            ?.textContent?.trim()?.takeIf { it.isNotBlank() }
        val title = bookTitle ?: File(fileName).nameWithoutExtension
        // Binaries: id → element, in document order. The cover is
        // the coverpage image when one is declared, else the first
        // binary image at all (the EPUB fallback order).
        val binaries = root.getElementsByTagNameNS("*", "binary")
        for (i in 0 until binaries.length) {
            val el = binaries.item(i) as? Element ?: continue
            val id = el.getAttribute("id").takeIf { it.isNotBlank() } ?: continue
            if (!state.binaries.containsKey(id)) state.binaries[id] = el
        }
        val coverId = titleInfo?.let { firstChildElement(it, "coverpage") }
            ?.let { firstChildElement(it, "image") }
            ?.let { hrefOf(it) }?.removePrefix("#")
            ?.takeIf { it.isNotBlank() }
        state.coverBinaryId = coverId
            ?: state.binaries.entries.firstOrNull { (_, el) ->
                (el.getAttribute("content-type").ifEmpty { el.getAttribute("contentType") })
                    .startsWith("image/")
            }?.key
        // The cover is resolved NOW, not when an <image> happens
        // to embed it: most books declare the cover in the
        // coverpage and never embed it in the body, so waiting for
        // a reference would leave coverFile null for them.
        state.coverBinaryId?.let { resolveImage(it, state) }
        // Footnote bodies first (their sections are the note texts),
        // so references in the main bodies resolve as they are read.
        val bodies = childElements(root, "body")
        for (body in bodies) {
            if (bodyName(body) == "notes") {
                for (section in childElements(body, "section")) {
                    val id = section.getAttribute("id").takeIf { it.isNotBlank() } ?: continue
                    val text = noteText(section)
                    if (text.isNotBlank()) state.footnoteTexts[id] = text
                }
            }
        }
        val sections = mutableListOf<ConvertedBook.Section>()
        for (body in bodies) {
            if (bodyName(body) == "notes") continue
            checkCancelled?.invoke()
            val bodySections = childElements(body, "section")
            if (bodySections.isEmpty()) {
                // A body with no sections (a short story, a single
                // poem): one chapter, unless it holds no text at all.
                val bodyHtml = StringBuilder()
                renderBlocks(body, state, bodyHtml)
                if (bodyHtml.isNotBlank()) {
                    sections += ConvertedBook.Section(
                        chapterFallback(sections.size + 1), bodyHtml.toString()
                    )
                }
                continue
            }
            for (section in bodySections) {
                checkCancelled?.invoke()
                val sectionTitle = firstChildElement(section, "title")
                    ?.let { sectionText(it) }?.takeIf { it.isNotBlank() }
                    ?: chapterFallback(sections.size + 1)
                val bodyHtml = StringBuilder()
                renderBlocks(section, state, bodyHtml, skipTitle = true)
                sections += ConvertedBook.Section(sectionTitle, bodyHtml.toString())
            }
        }
        // Footnotes: their references above became superscript
        // numbers; the texts themselves are appended to the last
        // section so no content is lost (the DOCX shape, including
        // the two-way links and the footnote-popup marker).
        if (state.referencedFootnotes.isNotEmpty()) {
            val notesHtml = StringBuilder("<hr/><ol>")
            for ((index, id) in state.referencedFootnotes.withIndex()) {
                val num = index + 1
                notesHtml.append("<li id=\"novelfa-fn-").append(num).append("\">")
                    .append("<a href=\"#novelfa-fnref-").append(num).append("\">↩</a> ")
                    .append(ConvertedBook.escape(state.footnoteTexts[id] ?: ""))
                    .append("</li>")
            }
            notesHtml.append("</ol>")
            if (sections.isNotEmpty()) {
                val last = sections.last()
                sections[sections.size - 1] =
                    last.copy(bodyHtml = last.bodyHtml + notesHtml.toString())
            } else {
                sections += ConvertedBook.Section(chapterFallback(1), notesHtml.toString())
            }
        }
        if (sections.isEmpty()) {
            return ConvertedBook.write(outDir, title, emptyList())
        }
        return ConvertedBook.write(
            outDir, title, ConvertedBook.chunkSections(sections),
            coverFile = state.coverFile
        )
    }

    private class State(val outDir: File) {
        /** Binary id → element, document order. */
        val binaries = linkedMapOf<String, Element>()
        var coverBinaryId: String? = null
        var coverFile: File? = null
        /** Binary id → written media file, so a reused image is written once. */
        val imageDestById = mutableMapOf<String, File>()
        val footnoteTexts = mutableMapOf<String, String>()
        val referencedFootnotes = mutableListOf<String>()
        val mediaDir by lazy { File(outDir, "media").apply { mkdirs() } }
    }

    /** Plain text of a section/title: its paragraphs' text, joined. */
    private fun sectionText(el: Element): String {
        val parts = mutableListOf<String>()
        val paras = el.getElementsByTagNameNS("*", "p")
        for (i in 0 until paras.length) {
            val t = paras.item(i).textContent?.trim()
            if (!t.isNullOrEmpty()) parts += t
        }
        if (parts.isNotEmpty()) return parts.joinToString(" ")
        return el.textContent?.trim() ?: ""
    }

    /** Plain text of a footnote section, WITHOUT its `<title>` (the note's number). */
    private fun noteText(section: Element): String {
        val parts = mutableListOf<String>()
        fun collect(el: Element) {
            val kids = el.childNodes
            for (i in 0 until kids.length) {
                val n = kids.item(i)
                if (n.nodeType != Node.ELEMENT_NODE) continue
                val child = n as Element
                when (localName(child)) {
                    "title" -> Unit // the note number, not note text
                    "p" -> {
                        val t = child.textContent?.trim()
                        if (!t.isNullOrEmpty()) parts += t
                    }
                    else -> collect(child)
                }
            }
        }
        collect(section)
        return parts.joinToString(" ")
    }

    /** Renders a section/body's block-level children into [out]. */
    private fun renderBlocks(
        parent: Element,
        state: State,
        out: StringBuilder,
        skipTitle: Boolean = false
    ) {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val node = kids.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (localName(el)) {
                "title" -> {
                    if (skipTitle) continue
                    val text = sectionText(el)
                    if (text.isNotBlank()) {
                        out.append("<h3>").append(ConvertedBook.escape(text)).append("</h3>")
                    }
                }
                "p" -> {
                    val html = StringBuilder()
                    renderInline(el, state, html)
                    if (html.isNotBlank()) {
                        out.append("<p>").append(html).append("</p>")
                    }
                }
                "subtitle" -> {
                    val text = el.textContent?.trim()
                    if (!text.isNullOrEmpty()) {
                        out.append("<h3>").append(ConvertedBook.escape(text)).append("</h3>")
                    }
                }
                "section" -> {
                    // Nested section: a heading inside this chapter,
                    // not a chapter of its own.
                    val nestedTitle = firstChildElement(el, "title")
                        ?.let { sectionText(it) }?.takeIf { it.isNotBlank() }
                    if (nestedTitle != null) {
                        out.append("<h2>").append(ConvertedBook.escape(nestedTitle))
                            .append("</h2>")
                    }
                    renderBlocks(el, state, out, skipTitle = true)
                }
                "poem" -> renderBlocks(el, state, out)
                "stanza" -> {
                    val lines = childElements(el, "v")
                        .mapNotNull { it.textContent?.trim()?.takeIf { t -> t.isNotEmpty() } }
                    if (lines.isNotEmpty()) {
                        out.append("<p>")
                            .append(lines.joinToString("<br/>") { ConvertedBook.escape(it) })
                            .append("</p>")
                    }
                }
                "epigraph", "cite" -> {
                    val inner = StringBuilder()
                    renderBlocks(el, state, inner)
                    if (inner.isNotBlank()) {
                        out.append("<blockquote>").append(inner).append("</blockquote>")
                    }
                }
                "empty-line" -> out.append("<br/>")
                "table" -> out.append(renderTable(el, state))
                "image" -> {
                    val img = imageHtml(el, state)
                    if (img != null) out.append("<p>").append(img).append("</p>")
                }
                // description, binary, annotation: metadata/data, not body text.
                else -> {
                    // Unknown block: render its children so no text
                    // is silently dropped (a future FB2 element, or a
                    // namespaced extension, still reads as prose).
                    renderBlocks(el, state, out)
                }
            }
        }
    }

    /** Renders inline content (a paragraph's children) into [out]. */
    private fun renderInline(node: Node, state: State, out: StringBuilder) {
        val kids = node.childNodes
        for (i in 0 until kids.length) {
            val child = kids.item(i)
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    out.append(ConvertedBook.escape(child.nodeValue ?: ""))
                }
                Node.ELEMENT_NODE -> {
                    val el = child as Element
                    when (localName(el)) {
                        "strong" -> {
                            val inner = StringBuilder()
                            renderInline(el, state, inner)
                            out.append("<strong>").append(inner).append("</strong>")
                        }
                        "emphasis" -> {
                            val inner = StringBuilder()
                            renderInline(el, state, inner)
                            out.append("<em>").append(inner).append("</em>")
                        }
                        "strikethrough" -> {
                            val inner = StringBuilder()
                            renderInline(el, state, inner)
                            out.append("<del>").append(inner).append("</del>")
                        }
                        "sub" -> {
                            val inner = StringBuilder()
                            renderInline(el, state, inner)
                            out.append("<sub>").append(inner).append("</sub>")
                        }
                        "sup" -> {
                            val inner = StringBuilder()
                            renderInline(el, state, inner)
                            out.append("<sup>").append(inner).append("</sup>")
                        }
                        "code" -> {
                            val inner = StringBuilder()
                            renderInline(el, state, inner)
                            out.append("<code>").append(inner).append("</code>")
                        }
                        "style" -> renderInline(el, state, out)
                        "a" -> out.append(linkHtml(el, state))
                        "image" -> {
                            val img = imageHtml(el, state)
                            if (img != null) out.append(img)
                        }
                        // A block element inside a paragraph (a
                        // sloppy book): its text still belongs here.
                        else -> renderInline(el, state, out)
                    }
                }
            }
        }
    }

    /** One link: a footnote reference, an in-book anchor, or an external URL. */
    private fun linkHtml(el: Element, state: State): String {
        val href = hrefOf(el) ?: return ConvertedBook.escape(el.textContent ?: "")
        val label = el.textContent?.trim()?.takeIf { it.isNotBlank() }
        if (el.getAttribute("type") == "note" && href.startsWith("#")) {
            val id = href.removePrefix("#")
            val text = state.footnoteTexts[id]
            if (text != null) {
                if (!state.referencedFootnotes.contains(id)) {
                    state.referencedFootnotes += id
                }
                val num = state.referencedFootnotes.indexOf(id) + 1
                return "<sup><a href=\"#novelfa-fn-" + num +
                    "\" id=\"novelfa-fnref-" + num +
                    "\" data-novelfa-footnote=\"" + num +
                    "\">[" + num + "]</a></sup>"
            }
            // A note reference with no note body: keep the label
            // (usually a number the author typed), not a dead link.
            return ConvertedBook.escape(label ?: "")
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return "<a href=\"" + ConvertedBook.escape(href) + "\">" +
                ConvertedBook.escape(label ?: href) + "</a>"
        }
        // In-book anchor (#section-id): kept verbatim — the
        // reader resolves it inside the converted chapter files.
        return "<a href=\"" + ConvertedBook.escape(href) + "\">" +
            ConvertedBook.escape(label ?: href) + "</a>"
    }

    /** One `<image>` element → `<img src="media/...">`, or null when unresolvable. */
    private fun imageHtml(el: Element, state: State): String? {
        val id = hrefOf(el)?.removePrefix("#")?.takeIf { it.isNotBlank() } ?: return null
        val dest = resolveImage(id, state) ?: return null
        return "<img src=\"media/" + ConvertedBook.escape(dest.name) + "\" />"
    }

    /**
     * Decodes binary [id] into `media/` (once per id) and returns
     * the written file. The cover id, when it resolves, is also
     * remembered as the book's cover. Null when the binary is
     * missing, not an image, or over the size cap.
     */
    private fun resolveImage(id: String, state: State): File? {
        state.imageDestById[id]?.let { return it.takeIf { f -> f.exists() } }
        val el = state.binaries[id] ?: return null
        val contentType = el.getAttribute("content-type")
            .ifEmpty { el.getAttribute("contentType") }
        if (!contentType.startsWith("image/")) return null
        val base64 = el.textContent ?: return null
        if (base64.length > MAX_BASE64_CHARS) return null
        val bytes = runCatching {
            Base64.getMimeDecoder().decode(base64)
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val ext = when (contentType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            "image/avif" -> "avif"
            "image/svg+xml" -> "svg"
            else -> "jpg"
        }
        val safeId = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .takeIf { it.isNotBlank() } ?: "image"
        // Two ids can sanitize to the same name (a/b vs a_b):
        // the second one takes a numeric suffix, like DOCX media.
        var dest = File(state.mediaDir, "$safeId.$ext")
        if (dest.exists() && state.imageDestById.values.none { it == dest }) {
            var n = 2
            while (true) {
                val candidate = File(state.mediaDir, "$safeId-$n.$ext")
                if (!candidate.exists()) {
                    dest = candidate
                    break
                }
                n++
            }
        }
        runCatching { dest.writeBytes(bytes) }.getOrNull() ?: return null
        if (!dest.exists() || dest.length() == 0L) return null
        state.imageDestById[id] = dest
        if (id == state.coverBinaryId && state.coverFile == null) {
            state.coverFile = dest
        }
        return dest
    }

    private fun renderTable(tbl: Element, state: State): String {
        val sb = StringBuilder("<table>")
        for (row in childElements(tbl, "tr")) {
            sb.append("<tr>")
            val cells = row.childNodes
            for (j in 0 until cells.length) {
                val cell = cells.item(j)
                if (cell.nodeType != Node.ELEMENT_NODE) continue
                val cellEl = cell as Element
                val name = localName(cellEl)
                if (name != "td" && name != "th") continue
                val parts = mutableListOf<String>()
                val ck = cellEl.childNodes
                for (k in 0 until ck.length) {
                    val cn = ck.item(k)
                    if (cn.nodeType != Node.ELEMENT_NODE) continue
                    val cnEl = cn as Element
                    if (localName(cnEl) == "p") {
                        val html = StringBuilder()
                        renderInline(cnEl, state, html)
                        if (html.isNotBlank()) parts += html.toString()
                    }
                }
                if (parts.isEmpty()) {
                    val text = cellEl.textContent?.trim()
                    if (!text.isNullOrEmpty()) parts += ConvertedBook.escape(text)
                }
                sb.append(if (name == "th") "<th>" else "<td>")
                    .append(parts.joinToString("<br/>"))
                    .append(if (name == "th") "</th>" else "</td>")
            }
            sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }

    // ---- Import-time metadata scan (no DOM) ----

    /**
     * What import needs to know without paying for a DOM parse:
     * the book title, how many top-level sections the content
     * bodies hold (the chapter count), whether any body holds
     * readable text at all, and where the cover image's base64
     * lives. A plain tag scan over the raw text — the tags are
     * ASCII, so the decode below works in any declared encoding —
     * with a real element-depth stack, so a `<section>` nested
     * inside another section is NOT counted as a chapter (the
     * full parser splits top-level sections only).
     */
    data class Fb2Scan(
        val title: String?,
        val sectionCount: Int,
        val hasBodyText: Boolean,
        val coverBinaryId: String?,
        val binaries: Map<String, BinaryRef>
    )

    /** A `<binary>` element: its content type and its base64 span in the scanned text. */
    data class BinaryRef(val contentType: String, val start: Int, val end: Int)

    fun scanMetadata(bytes: ByteArray): Fb2Scan {
        val text = String(bytes, Charsets.UTF_8)
        if (!Regex("<FictionBook[\\s>]").containsMatchIn(text)) {
            throw IllegalArgumentException("Not a valid FB2: no FictionBook root")
        }
        var title: String? = null
        var sections = 0
        var hasBodyText = false
        var coverBinaryId: String? = null
        val binaries = linkedMapOf<String, BinaryRef>()
        // Element stack (names only) + parallel body flags, so the
        // scan knows whether a <section> or <p> sits directly under
        // a content <body> or a notes <body>.
        val stack = ArrayDeque<String>()
        val stackIsNotesBody = ArrayDeque<Boolean>()
        var inCoverpage = false
        var coverpageDepth = -1
        var pendingBinary: Triple<String, String, Int>? = null
        var i = 0
        val n = text.length
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            // Comments, CDATA, processing instructions and DOCTYPE
            // are skipped FIRST: a "<book-title" inside a comment
            // is not a title, and a '>' inside one is not a tag end.
            if (text.startsWith("<!--", lt)) {
                val end = text.indexOf("-->", lt + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (text.startsWith("<![CDATA[", lt)) {
                val end = text.indexOf("]]>", lt + 9)
                i = if (end < 0) n else end + 3
                continue
            }
            if (text.startsWith("<?", lt) || text.startsWith("<!", lt)) {
                val end = text.indexOf('>', lt + 2)
                i = if (end < 0) n else end + 1
                continue
            }
            // Book-title text: captured when its open tag is seen.
            if (title == null && text.startsWith("<book-title", lt)) {
                val gt = text.indexOf('>', lt)
                if (gt >= 0) {
                    val end = text.indexOf('<', gt + 1)
                    if (end > gt + 1) {
                        title = text.substring(gt + 1, end).trim()
                            .takeIf { it.isNotBlank() }
                    }
                }
            }
            val gt = findTagEnd(text, lt)
            if (gt < 0) break
            val raw = text.substring(lt + 1, gt).trim()
            if (raw.isEmpty()) {
                i = gt + 1
                continue
            }
            if (raw.startsWith("/")) {
                val name = raw.substring(1).trim().substringBefore(' ')
                    .substringBefore('\t').lowercase()
                if (name == "binary" && pendingBinary != null) {
                    val (id, type, start) = pendingBinary
                    binaries[id] = BinaryRef(type, start, lt)
                    pendingBinary = null
                }
                if (name == "coverpage") inCoverpage = false
                // Pop up to the matching opener (tolerant of sloppy nesting).
                while (stack.isNotEmpty()) {
                    val top = stack.removeLast()
                    stackIsNotesBody.removeLast()
                    if (top == name) break
                }
                i = gt + 1
                continue
            }
            val selfClosing = raw.endsWith("/")
            val clean = if (selfClosing) raw.dropLast(1).trim() else raw
            val name = clean.substringBefore(' ').substringBefore('\t')
                .substringBefore('\n').lowercase()
            if (name.isEmpty()) {
                i = gt + 1
                continue
            }
            val attrs = clean.substring(name.length)
            when (name) {
                "body" -> {
                    val isNotes = Regex("""name\s*=\s*"notes"""").containsMatchIn(attrs)
                    if (!selfClosing) {
                        stack.addLast(name)
                        stackIsNotesBody.addLast(isNotes)
                    }
                    if (isNotes) {
                        // A notes body never holds chapters or text.
                    }
                }
                "section" -> {
                    // Top-level only: the element directly under a
                    // content <body> (stack top is "body", not notes).
                    if (stack.isNotEmpty() && stack.last() == "body" &&
                        stackIsNotesBody.lastOrNull() != true
                    ) {
                        sections++
                    }
                    if (!selfClosing) {
                        stack.addLast(name)
                        stackIsNotesBody.addLast(false)
                    }
                }
                "p" -> {
                    // Body text only when NO enclosing body is a
                    // notes body: a <p> inside a notes <section>
                    // is footnote text, not book text (without this
                    // a notes-only document counted as readable).
                    val inNotesBody = stackIsNotesBody.any { it }
                    if (!inNotesBody) {
                        hasBodyText = true
                    }
                    if (!selfClosing) {
                        stack.addLast(name)
                        stackIsNotesBody.addLast(false)
                    }
                }
                "coverpage" -> {
                    if (!selfClosing) {
                        inCoverpage = true
                        coverpageDepth = stack.size
                        stack.addLast(name)
                        stackIsNotesBody.addLast(false)
                    }
                }
                "image" -> {
                    if (inCoverpage && coverBinaryId == null) {
                        val href = attrValue(attrs, "href")
                            ?.removePrefix("#")?.takeIf { it.isNotBlank() }
                        if (href != null) coverBinaryId = href
                    }
                    if (!selfClosing) {
                        stack.addLast(name)
                        stackIsNotesBody.addLast(false)
                    }
                }
                "binary" -> {
                    val id = attrValue(attrs, "id")
                    val type = attrValue(attrs, "content-type")
                        ?: attrValue(attrs, "contentType") ?: ""
                    if (id != null && !selfClosing) {
                        pendingBinary = Triple(id, type, gt + 1)
                        stack.addLast(name)
                        stackIsNotesBody.addLast(false)
                    } else if (id != null && selfClosing) {
                        binaries[id] = BinaryRef(type, gt + 1, gt + 1)
                    }
                }
                else -> {
                    if (!selfClosing) {
                        stack.addLast(name)
                        stackIsNotesBody.addLast(false)
                    }
                }
            }
            if (inCoverpage && stack.size <= coverpageDepth && name != "coverpage") {
                inCoverpage = false
            }
            i = gt + 1
        }
        return Fb2Scan(title, sections, hasBodyText, coverBinaryId, binaries)
    }

    /**
     * The cover image bytes for [bytes] (a plain .fb2, or the raw
     * bytes of an .fbz — the .fb2 entry is unwrapped first),
     * written into [scratchDir]: the coverpage image first, else
     * the first binary image. Null when there is no usable cover —
     * the caller falls back to the generated cover, exactly like
     * the other formats.
     */
    fun extractCover(bytes: ByteArray, scratchDir: File): File? {
        val docBytes = unwrapFbz(bytes)
        val scan = runCatching { scanMetadata(docBytes) }.getOrNull() ?: return null
        val coverId = scan.coverBinaryId
            ?: scan.binaries.entries.firstOrNull { (_, ref) ->
                ref.contentType.startsWith("image/")
            }?.key ?: return null
        val ref = scan.binaries[coverId] ?: return null
        if (!ref.contentType.startsWith("image/")) return null
        val text = String(docBytes, Charsets.UTF_8)
        val base64 = text.substring(
            ref.start.coerceIn(0, text.length),
            ref.end.coerceIn(0, text.length)
        )
        if (base64.length > MAX_BASE64_CHARS) return null
        val decoded = runCatching {
            Base64.getMimeDecoder().decode(base64)
        }.getOrNull() ?: return null
        if (decoded.isEmpty() || decoded.size > MAX_IMAGE_BYTES) return null
        val ext = when (ref.contentType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            "image/avif" -> "avif"
            else -> "jpg"
        }
        scratchDir.mkdirs()
        val dest = File(scratchDir, "cover.$ext")
        runCatching { dest.writeBytes(decoded) }.getOrNull() ?: return null
        return dest.takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * The .fb2 document inside [bytes]: the first .fb2 entry when
     * the bytes are a ZIP carrying one (the .fbz shape), the bytes
     * themselves otherwise. A ZIP with no .fb2 entry returns the
     * input unchanged, so the caller's scan fails as invalid FB2.
     */
    private fun unwrapFbz(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() ||
            bytes[1] != 'K'.code.toByte()
        ) {
            return bytes
        }
        return runCatching {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".fb2")) {
                        return zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
                null
            }
        }.getOrNull() ?: bytes
    }

    /** End of the tag starting at [lt]: the first '>' outside quotes. */
    private fun findTagEnd(text: String, lt: Int): Int {
        var quote: Char? = null
        var i = lt + 1
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c == '>' -> return i
            }
            i++
        }
        return -1
    }

    /** Value of attribute [attr] in a raw attribute string (any prefix: l:href, xlink:href, href). */
    private fun attrValue(attrs: String, attr: String): String? {
        // Matches `attr="..."`, `prefix:attr="..."` and single quotes.
        val regex = Regex(
            """(?:^|\s)[A-Za-z_][\w.-]*:$attr\s*=\s*"([^"]*)"|""" +
                """(?:^|\s)[A-Za-z_][\w.-]*:$attr\s*=\s*'([^']*)'|""" +
                """(?:^|\s)$attr\s*=\s*"([^"]*)"|""" +
                """(?:^|\s)$attr\s*=\s*'([^']*)'"""
        )
        val m = regex.find(attrs) ?: return null
        return m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            runCatching { isNamespaceAware = true }
            runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching {
                setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false
                )
            }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }

    private fun localName(el: Element): String =
        el.localName ?: el.nodeName.substringAfter(':')

    private fun bodyName(body: Element): String? =
        body.getAttribute("name").takeIf { it.isNotBlank() }

    private fun firstChildElement(parent: Element, local: String): Element? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && localName(n as Element) == local) {
                return n
            }
        }
        return null
    }

    private fun childElements(parent: Element, local: String): List<Element> {
        val out = mutableListOf<Element>()
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && localName(n as Element) == local) {
                out += n as Element
            }
        }
        return out
    }

    /** The href of an `<image>` or `<a>`: l:href, xlink:href or plain href. */
    private fun hrefOf(el: Element): String? {
        for (attr in listOf("l:href", "xlink:href", "href")) {
            val v = el.getAttribute(attr)
            if (v.isNotEmpty()) return v
        }
        val ns = el.getAttributeNS("*", "href")
        return ns.takeIf { it.isNotEmpty() }
    }
}
