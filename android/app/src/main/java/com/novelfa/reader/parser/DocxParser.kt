package com.novelfa.reader.parser

import com.novelfa.reader.model.ParsedBook
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Word (.docx) books. A docx is a zip: `word/document.xml` holds the
 * text, `word/_rels/document.xml.rels` maps image ids to `word/media/`,
 * `docProps/core.xml` may hold the title, and `word/numbering.xml`
 * decides bullet vs. numbered lists.
 *
 * XML hardening mirrors [EpubParser]: external entities stay disabled
 * (best-effort flags — some Android JAXP providers reject the flags,
 * and DOCTYPE is tolerated here because Word files never need entities,
 * so a hostile DOCTYPE still cannot pull in external content).
 */
object DocxParser {

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val CP_NS = "http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"

    /**
     * Convenience entry: unzips the .docx into [outDir] (same zip-bomb
     * and path-traversal guards as the EPUB importer) and parses it.
     * The extracted `word/` tree stays beside the generated chapters;
     * [ConvertedBook.read] only looks at `ch*.xhtml`, so it is harmless.
     */
    fun parseFile(
        docxFile: File,
        outDir: File,
        chapterFallback: (Int) -> String,
        /** Original file name (see [BookConverter.parse]): the stored file is "$uuid.docx". */
        titleFallback: String? = null
    ): ParsedBook {
        outDir.mkdirs()
        unzip(docxFile, outDir)
        return parse(outDir, titleFallback?.takeIf { it.isNotBlank() } ?: docxFile.name, outDir, chapterFallback)
    }

    private fun unzip(zip: File, outDir: File) {
        java.util.zip.ZipFile(zip).use { zf ->
            val entries = zf.entries()
            var entryCount = 0
            var totalBytes = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++entryCount > 10_000) {
                    throw java.io.IOException("Zip entry count exceeds limit: ${entry.name}")
                }
                val out = File(outDir, entry.name)
                val canonicalOut = out.canonicalPath
                val canonicalDir = outDir.canonicalPath
                if (!canonicalOut.startsWith(canonicalDir + File.separatorChar)) {
                    throw java.io.IOException("Zip entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        out.outputStream().use { output ->
                            totalBytes += input.copyTo(output)
                            if (totalBytes > 500L * 1024 * 1024) {
                                throw java.io.IOException("Zip uncompressed size exceeds limit")
                            }
                        }
                    }
                }
            }
        }
    }

    fun parse(
        extractDir: File,
        fileName: String,
        outDir: File,
        chapterFallback: (Int) -> String
    ): ParsedBook {
        val document = File(extractDir, "word/document.xml")
        if (!document.isFile) {
            throw IllegalArgumentException("Not a valid DOCX: word/document.xml missing")
        }
        val dbf = secureFactory()
        val doc = dbf.newDocumentBuilder().parse(document)
        val rels = readRels(extractDir, dbf)
        val numbering = readNumbering(extractDir, dbf)
        val coreTitle = readCoreTitle(extractDir, dbf)
        val mediaDir = File(outDir, "media").apply { mkdirs() }
        val state = State(extractDir, rels, numbering, mediaDir)

        val body = doc.documentElement?.let { firstChildElement(it, "body") }
            ?: throw IllegalArgumentException("Not a valid DOCX: no document body")
        val sections = mutableListOf<ConvertedBook.Section>()
        var currentTitle: String? = null
        var currentBody = StringBuilder()
        var pendingList: MutableList<ListItem>? = null
        fun flushList() {
            if (pendingList != null) {
                currentBody.append(renderList(pendingList!!))
                pendingList = null
            }
        }
        fun flushSection() {
            flushList()
            if (currentBody.isNotEmpty() || currentTitle != null) {
                sections += ConvertedBook.Section(
                    currentTitle ?: chapterFallback(sections.size + 1),
                    currentBody.toString()
                )
                currentBody = StringBuilder()
                currentTitle = null
            }
        }
        var title = coreTitle ?: File(fileName).nameWithoutExtension
        var seenContent = false
        val kids = body.childNodes
        for (i in 0 until kids.length) {
            val node = kids.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (localName(el)) {
                "p" -> {
                    val para = parseParagraph(el, state)
                    val style = para.style
                    if (style == "Title" && !seenContent && coreTitle == null) {
                        title = para.text
                        continue
                    }
                    if (style == "Heading1" || style == "Heading2") {
                        flushSection()
                        currentTitle = para.text
                        val level = if (style == "Heading1") 1 else 2
                        currentBody.append("<h$level>")
                            .append(ConvertedBook.escape(para.text)).append("</h$level>")
                        seenContent = true
                        continue
                    }
                    if (para.isList) {
                        val list = pendingList
                            ?: mutableListOf<ListItem>().also { pendingList = it }
                        list += ListItem(
                            level = para.listLevel,
                            ordered = numbering.isOrdered(para.numId),
                            html = para.html
                        )
                        seenContent = true
                        continue
                    }
                    flushList()
                    if (style != null && style.startsWith("Heading")) {
                        val level = style.removePrefix("Heading").toIntOrNull()
                            ?.coerceIn(3, 6) ?: 3
                        currentBody.append("<h$level>").append(para.html).append("</h$level>")
                    } else if (para.html.isNotBlank()) {
                        currentBody.append("<p>").append(para.html).append("</p>")
                    }
                    if (para.html.isNotBlank()) seenContent = true
                }
                "tbl" -> {
                    flushList()
                    currentBody.append(renderTable(el, state))
                    seenContent = true
                }
                // sectPr and anything else: layout metadata, not content.
                else -> Unit
            }
        }
        flushSection()
        if (sections.isEmpty()) {
            return ConvertedBook.write(outDir, title, emptyList())
        }
        return ConvertedBook.write(
            outDir, title, ConvertedBook.chunkSections(sections),
            coverFile = state.firstImage
        )
    }

    private class State(
        val extractDir: File,
        val rels: Map<String, String>,
        val numbering: Numbering,
        val mediaDir: File
    ) {
        var firstImage: File? = null
    }

    private data class Para(
        val text: String,
        val html: String,
        val style: String?,
        val isList: Boolean,
        val listLevel: Int,
        val numId: Int?
    )

    private data class ListItem(val level: Int, val ordered: Boolean, val html: String)

    /** One paragraph: runs → HTML, plus style / list metadata. */
    private fun parseParagraph(p: Element, state: State): Para {
        var style: String? = null
        var numId: Int? = null
        var ilvl = 0
        val pPr = firstChildElement(p, "pPr")
        if (pPr != null) {
            style = firstChildElement(pPr, "pStyle")?.getAttribute("w:val")
                ?: firstChildElement(pPr, "pStyle")?.getAttribute("val")
            val numPr = firstChildElement(pPr, "numPr")
            if (numPr != null) {
                numId = firstChildElement(numPr, "numId")?.let {
                    (it.getAttribute("w:val").ifEmpty { it.getAttribute("val") }).toIntOrNull()
                }
                ilvl = firstChildElement(numPr, "ilvl")?.let {
                    (it.getAttribute("w:val").ifEmpty { it.getAttribute("val") }).toIntOrNull()
                } ?: 0
            }
        }
        val html = StringBuilder()
        val plain = StringBuilder()
        val kids = p.childNodes
        for (i in 0 until kids.length) {
            val node = kids.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (localName(el)) {
                "r" -> {
                    val (h, t) = renderRun(el, state)
                    html.append(h)
                    plain.append(t)
                }
                "hyperlink" -> {
                    // Hyperlink runs render like normal runs; the href
                    // survives only for external relationships.
                    val href = el.getAttribute("r:id").takeIf { it.isNotEmpty() }
                        ?.let { state.rels[it] }
                    val innerH = StringBuilder()
                    val innerT = StringBuilder()
                    val hk = el.childNodes
                    for (j in 0 until hk.length) {
                        val hn = hk.item(j)
                        if (hn.nodeType != Node.ELEMENT_NODE) continue
                        if (localName(hn as Element) == "r") {
                            val (h, t) = renderRun(hn, state)
                            innerH.append(h)
                            innerT.append(t)
                        }
                    }
                    if (href != null && (href.startsWith("http://") || href.startsWith("https://"))) {
                        html.append("<a href=\"").append(ConvertedBook.escape(href))
                            .append("\">").append(innerH).append("</a>")
                    } else {
                        html.append(innerH)
                    }
                    plain.append(innerT)
                }
                // bookmarkStart/End, proofErr, etc.: no visible text.
                else -> Unit
            }
        }
        return Para(
            text = plain.toString().trim(),
            html = html.toString(),
            style = style,
            isList = numId != null,
            listLevel = ilvl.coerceIn(0, 8),
            numId = numId
        )
    }

    /** One run: text nodes + inline formatting + embedded images. */
    private fun renderRun(r: Element, state: State): Pair<String, String> {
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        val rPr = firstChildElement(r, "rPr")
        if (rPr != null) {
            val rk = rPr.childNodes
            for (i in 0 until rk.length) {
                val n = rk.item(i)
                if (n.nodeType != Node.ELEMENT_NODE) continue
                when (localName(n as Element)) {
                    "b" -> bold = true
                    "i" -> italic = true
                    "u" -> underline = true
                    "strike" -> strike = true
                }
            }
        }
        val html = StringBuilder()
        val plain = StringBuilder()
        val kids = r.childNodes
        for (i in 0 until kids.length) {
            val node = kids.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (localName(el)) {
                "t" -> {
                    val t = el.textContent ?: ""
                    plain.append(t)
                    html.append(ConvertedBook.escape(t))
                }
                "tab" -> {
                    plain.append(' ')
                    html.append(' ')
                }
                "br" -> html.append("<br/>")
                "drawing", "pict" -> {
                    // Embedded image: blip/imageData r:embed → media file.
                    val embed = findEmbedId(el)
                    val target = embed?.let { state.rels[it] } ?: continue
                    val srcFile = File(state.extractDir, "word/" + target)
                    if (!srcFile.isFile) continue
                    val dest = File(state.mediaDir, srcFile.name)
                    if (!dest.exists()) {
                        runCatching { srcFile.copyTo(dest) }
                    }
                    if (state.firstImage == null && dest.exists()) {
                        state.firstImage = dest
                    }
                    if (dest.exists()) {
                        html.append("<img src=\"media/")
                            .append(ConvertedBook.escape(dest.name)).append("\" />")
                    }
                }
                else -> Unit
            }
        }
        var out = html.toString()
        if (strike) out = "<del>$out</del>"
        if (underline) out = "<u>$out</u>"
        if (italic) out = "<em>$out</em>"
        if (bold) out = "<strong>$out</strong>"
        return out to plain.toString()
    }

    private fun findEmbedId(el: Element): String? {
        // drawing → blip/@r:embed; pict → imagedata/@r:id.
        val all = el.getElementsByTagNameNS("*", "blip")
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            val v = e.getAttribute("r:embed").ifEmpty { e.getAttribute("embed") }
            if (v.isNotEmpty()) return v
        }
        val imgs = el.getElementsByTagNameNS("*", "imagedata")
        for (i in 0 until imgs.length) {
            val e = imgs.item(i) as Element
            val v = e.getAttribute("r:id").ifEmpty { e.getAttribute("id") }
            if (v.isNotEmpty()) return v
        }
        return null
    }

    private fun renderTable(tbl: Element, state: State): String {
        val sb = StringBuilder("<table>")
        val rows = tbl.childNodes
        for (i in 0 until rows.length) {
            val row = rows.item(i)
            if (row.nodeType != Node.ELEMENT_NODE || localName(row as Element) != "tr") continue
            sb.append("<tr>")
            val cells = row.childNodes
            for (j in 0 until cells.length) {
                val cell = cells.item(j)
                if (cell.nodeType != Node.ELEMENT_NODE || localName(cell as Element) != "tc") continue
                val parts = mutableListOf<String>()
                val ck = cell.childNodes
                for (k in 0 until ck.length) {
                    val cn = ck.item(k)
                    if (cn.nodeType != Node.ELEMENT_NODE || localName(cn as Element) != "p") continue
                    val para = parseParagraph(cn, state)
                    if (para.html.isNotBlank()) parts += para.html
                }
                sb.append("<td>").append(parts.joinToString("<br/>")).append("</td>")
            }
            sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }

    /** Flat list items → nested <ul>/<ol> by level. */
    private fun renderList(items: List<ListItem>): String {
        val (html, _) = renderListAt(items, 0, items.firstOrNull()?.level ?: 0)
        return html
    }

    /**
     * Renders items from [from] at [baseLevel]; returns the HTML and the
     * index of the first unconsumed item. A deeper run nests inside the
     * previous `<li>` (its closing tag is stripped, the nest appended,
     * the tag restored).
     */
    private fun renderListAt(
        items: List<ListItem>,
        from: Int,
        baseLevel: Int
    ): Pair<String, Int> {
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
            val item = items[idx]
            if (item.level < baseLevel) break
            if (item.level > baseLevel) {
                val (nested, next) = renderListAt(items, idx, item.level)
                val tail = "</li>"
                if (sb.endsWith(tail)) sb.setLength(sb.length - tail.length)
                sb.append(nested).append(tail)
                idx = next
                continue
            }
            val tag = if (item.ordered) "ol" else "ul"
            if (openTag != tag) {
                close()
                sb.append("<").append(tag).append(">")
                openTag = tag
            }
            sb.append("<li>").append(item.html).append("</li>")
            idx++
        }
        close()
        return sb.toString() to idx
    }

    /** numId → abstract numbering → ordered? (bullet formats are unordered). */
    private class Numbering(private val orderedByNum: Map<Int, Boolean>) {
        fun isOrdered(numId: Int?): Boolean {
            if (numId == null) return false
            return orderedByNum[numId] ?: true
        }
    }

    private fun readNumbering(extractDir: File, dbf: DocumentBuilderFactory): Numbering {
        val file = File(extractDir, "word/numbering.xml")
        if (!file.isFile) return Numbering(emptyMap())
        return runCatching {
            val doc = dbf.newDocumentBuilder().parse(file)
            val root = doc.documentElement ?: return@runCatching Numbering(emptyMap())
            val abstractFmt = mutableMapOf<Int, Boolean>()
            val nums = root.childNodes
            for (i in 0 until nums.length) {
                val n = nums.item(i)
                if (n.nodeType != Node.ELEMENT_NODE) continue
                val el = n as Element
                if (localName(el) == "abstractNum") {
                    val id = (el.getAttribute("w:abstractNumId")
                        .ifEmpty { el.getAttribute("abstractNumId") }).toIntOrNull()
                        ?: continue
                    // Level 0 decides the list kind.
                    val lvls = el.childNodes
                    var ordered = true
                    for (j in 0 until lvls.length) {
                        val ln = lvls.item(j)
                        if (ln.nodeType != Node.ELEMENT_NODE ||
                            localName(ln as Element) != "lvl"
                        ) continue
                        val lvlEl = ln as Element
                        val ilvl = (lvlEl.getAttribute("w:ilvl")
                            .ifEmpty { lvlEl.getAttribute("ilvl") }).toIntOrNull() ?: 0
                        if (ilvl != 0) continue
                        val fmt = firstChildElement(lvlEl, "numFmt")?.let {
                            it.getAttribute("w:val").ifEmpty { it.getAttribute("val") }
                        }
                        ordered = fmt != null && fmt != "bullet" && fmt != "none"
                        break
                    }
                    abstractFmt[id] = ordered
                }
            }
            val orderedByNum = mutableMapOf<Int, Boolean>()
            for (i in 0 until nums.length) {
                val n = nums.item(i)
                if (n.nodeType != Node.ELEMENT_NODE) continue
                val el = n as Element
                if (localName(el) == "num") {
                    val numId = (el.getAttribute("w:numId")
                        .ifEmpty { el.getAttribute("numId") }).toIntOrNull() ?: continue
                    val abs = firstChildElement(el, "abstractNumId")?.let {
                        (it.getAttribute("w:val").ifEmpty { it.getAttribute("val") })
                            .toIntOrNull()
                    } ?: continue
                    orderedByNum[numId] = abstractFmt[abs] ?: true
                }
            }
            Numbering(orderedByNum)
        }.getOrDefault(Numbering(emptyMap()))
    }

    private fun readRels(extractDir: File, dbf: DocumentBuilderFactory): Map<String, String> {
        val file = File(extractDir, "word/_rels/document.xml.rels")
        if (!file.isFile) return emptyMap()
        return runCatching {
            val doc = dbf.newDocumentBuilder().parse(file)
            val out = mutableMapOf<String, String>()
            val rels = doc.documentElement?.childNodes ?: return@runCatching out
            for (i in 0 until rels.length) {
                val n = rels.item(i)
                if (n.nodeType != Node.ELEMENT_NODE) continue
                val el = n as Element
                val id = el.getAttribute("Id")
                val target = el.getAttribute("Target")
                if (id.isNotEmpty() && target.isNotEmpty() &&
                    !target.startsWith("http://") && !target.startsWith("https://")
                ) {
                    out[id] = target.removePrefix("/")
                } else if (id.isNotEmpty() && target.isNotEmpty()) {
                    out[id] = target
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun readCoreTitle(extractDir: File, dbf: DocumentBuilderFactory): String? {
        val file = File(extractDir, "docProps/core.xml")
        if (!file.isFile) return null
        return runCatching {
            val doc = dbf.newDocumentBuilder().parse(file)
            val titles = doc.getElementsByTagNameNS(DC_NS, "title")
            if (titles.length == 0) return@runCatching null
            titles.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
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
}
