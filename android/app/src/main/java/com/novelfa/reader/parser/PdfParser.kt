package com.novelfa.reader.parser

import com.novelfa.reader.model.ParsedBook
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * Text-layer PDF books, with no third-party dependency.
 *
 * What works: classic and xref-stream cross-references, object streams,
 * Flate-compressed streams, page trees, document outlines (→ chapters),
 * document-info titles, simple fonts (WinAnsi / Differences) and CID
 * fonts with ToUnicode maps — the combination that covers Word-,
 * browser- and LaTeX-produced PDFs, including Persian PDFs whose
 * embedded fonts carry a ToUnicode map.
 *
 * Honest limits (a text extractor cannot invent text): encrypted PDFs
 * are rejected, scanned-image PDFs have no text layer and therefore
 * import as "no readable sections", and PDFs whose fonts lack both a
 * ToUnicode map and a usable encoding lose the affected glyphs.
 * Text is kept in stream order; the reader's per-paragraph direction
 * stamping then lays RTL and LTR paragraphs out correctly.
 */
object PdfParser {

    private const val MAX_PAGES = 5_000

    fun parse(
        bytes: ByteArray,
        fileName: String,
        outDir: File,
        chapterFallback: (Int) -> String
    ): ParsedBook {
        val doc = PdfDoc(bytes)
        val pages = doc.pageObjectNumbers()
        if (pages.isEmpty()) {
            throw IllegalArgumentException("PDF has no pages")
        }
        if (pages.size > MAX_PAGES) {
            throw IllegalArgumentException("PDF has too many pages")
        }
        val pageParagraphs = pages.map { doc.pageParagraphs(it) }
        val outline = doc.outlineEntries(pages)
        val sections: List<ConvertedBook.Section>
        if (outline.size >= 2) {
            sections = outline.mapIndexed { idx, entry ->
                val end = outline.getOrNull(idx + 1)?.pageIndex ?: pages.size
                val paras = (entry.pageIndex until end).flatMap { pageParagraphs[it] }
                ConvertedBook.Section(
                    entry.title.trim().take(120).ifBlank { chapterFallback(idx + 1) },
                    paras.joinToString("") { "<p>" + ConvertedBook.escape(it) + "</p>" }
                )
            }.filter { it.bodyHtml.isNotEmpty() }
        } else {
            val all = pageParagraphs.flatten()
            val built = mutableListOf<ConvertedBook.Section>()
            var current = StringBuilder()
            fun flush() {
                if (current.isNotEmpty()) {
                    built += ConvertedBook.Section(
                        chapterFallback(built.size + 1), current.toString()
                    )
                    current = StringBuilder()
                }
            }
            for (para in all) {
                val html = "<p>" + ConvertedBook.escape(para) + "</p>"
                if (current.isNotEmpty() &&
                    current.length + html.length > ConvertedBook.MAX_SECTION_CHARS
                ) {
                    flush()
                }
                current.append(html)
            }
            flush()
            sections = built
        }
        val title = doc.infoTitle() ?: File(fileName).nameWithoutExtension
        return ConvertedBook.write(outDir, title, ConvertedBook.chunkSections(sections))
    }

    // ------------------------------------------------------------------
    // Document model: xref, objects, streams, pages, outlines.
    // ------------------------------------------------------------------

    private data class OutlineEntry(val title: String, val pageIndex: Int)

    private class PdfDoc(private val bytes: ByteArray) {
        /** Latin-1 view: char index == byte index, so offsets stay valid. */
        val text: String = String(bytes, Charsets.ISO_8859_1)

        private val offsets = mutableMapOf<Int, Int>()
        private val objStm = mutableMapOf<Int, Pair<Int, Int>>()
        private val objStmCache = mutableMapOf<Int, Map<Int, String>>()
        private val rawCache = mutableMapOf<Int, String>()
        private val streamCache = mutableMapOf<Int, ByteArray?>()
        private val fontCache = mutableMapOf<Int, FontInfo?>()
        private var trailerDict: String? = null

        init {
            buildXref()
        }

        // -- xref -------------------------------------------------------

        private fun buildXref() {
            var offset = lastStartxref()
            var guard = 0
            while (offset != null && guard++ < 25) {
                val at = skipWs(offset)
                if (text.startsWith("xref", at)) {
                    // Classic table, parsed line by line: a subsection
                    // header "first count" followed by count 20-byte
                    // entries, then the next subsection or "trailer".
                    var pos = at + 4
                    var trailer: String? = null
                    while (pos < text.length) {
                        pos = skipWs(pos)
                        if (text.startsWith("trailer", pos)) {
                            trailer = balancedDict(text, text.indexOf("<<", pos))
                            break
                        }
                        val lineEnd = text.indexOf('\n', pos).takeIf { it >= 0 } ?: text.length
                        val header = text.substring(pos, lineEnd).trim()
                            .split(Regex("\\s+"))
                        val start = header.getOrNull(0)?.toIntOrNull()
                        val count = header.getOrNull(1)?.toIntOrNull()
                        if (start == null || count == null) break
                        pos = lineEnd + 1
                        repeat(count) { k ->
                            val entryEnd = text.indexOf('\n', pos)
                                .takeIf { it >= 0 } ?: text.length
                            val entry = Regex("(\\d{10})\\s+(\\d{5})\\s+([nf])")
                                .find(text.substring(pos, entryEnd))
                            if (entry != null && entry.groupValues[3] == "n") {
                                val num = start + k
                                if (num !in offsets) {
                                    offsets[num] = entry.groupValues[1].toInt()
                                }
                            }
                            pos = entryEnd + 1
                        }
                    }
                    if (trailerDict == null) trailerDict = trailer
                    offset = trailer?.let { intValue(it, "Prev") }
                } else {
                    // An xref-stream object: its dict IS the trailer.
                    // The header must start exactly at `at` (a later
                    // "N G obj" match inside stream bytes would be a
                    // different object entirely — hence no \G shortcut).
                    val header = Regex("(\\d+)\\s+\\d+\\s+obj").find(text, at)
                    if (header == null || header.range.first != at) break
                    val objNum = header.groupValues[1].toIntOrNull() ?: break
                    // Register the stream object's own offset first, or
                    // dictOf/streamOf below cannot locate it at all.
                    if (objNum !in offsets) offsets[objNum] = header.range.first
                    val dict = dictOf(objNum) ?: break
                    if (trailerDict == null) trailerDict = dict
                    parseXrefStream(objNum, dict)
                    offset = intValue(dict, "Prev")
                }
            }
            if (offsets.isEmpty() && objStm.isEmpty()) {
                // Last resort: linear scan for "N G obj" occurrences.
                Regex("(\\d+)\\s+\\d+\\s+obj").findAll(text).forEach { m ->
                    val num = m.groupValues[1].toInt()
                    if (num !in offsets) offsets[num] = m.range.first
                }
            }
            val trailer = trailerDict ?: ""
            if (Regex("/Encrypt\\s").containsMatchIn(trailer)) {
                throw IllegalArgumentException("PDF is encrypted")
            }
        }

        private fun lastStartxref(): Int? {
            val tail = text.takeLast(4096)
            val idx = tail.lastIndexOf("startxref")
            if (idx < 0) return null
            // The number after startxref IS the absolute xref offset.
            return Regex("\\d+").find(tail, idx + 9)?.value?.toIntOrNull()
        }

        private fun parseXrefStream(objNum: Int, dict: String) {
            val w = arrayInts(dict, "W")
            if (w.size < 3) return
            val index = arrayInts(dict, "Index")
            val sections = if (index.size >= 2) {
                index.chunked(2).mapNotNull { if (it.size == 2) it[0] to it[1] else null }
            } else {
                listOf(0 to (intValue(dict, "Size") ?: 0))
            }
            val data = streamOf(objNum) ?: return
            var pos = 0
            fun field(size: Int, default: Int): Int {
                if (size == 0) return default
                var v = 0
                repeat(size) {
                    if (pos < data.size) v = (v shl 8) or (data[pos++].toInt() and 0xFF)
                }
                return v
            }
            for ((first, count) in sections) {
                repeat(count) { k ->
                    val type = field(w[0], 1)
                    val f2 = field(w[1], 0)
                    val f3 = field(w[2], 0)
                    val num = first + k
                    when (type) {
                        1 -> if (num !in offsets) offsets[num] = f2
                        2 -> if (num !in objStm) objStm[num] = f2 to f3
                    }
                }
            }
        }

        // -- raw objects and streams ------------------------------------

        fun rawObject(num: Int): String? {
            rawCache[num]?.let { return it }
            val result = offsets[num]?.let { off ->
                val contentStart = Regex("obj\\s").find(text, off)
                    ?.range?.last?.plus(1) ?: return@let null
                val end = text.indexOf("endobj", contentStart)
                if (end < 0) null else text.substring(contentStart, end)
            } ?: objStm[num]?.let { (stmNum, index) ->
                objStmObjects(stmNum)[index]
            }
            if (result != null) rawCache[num] = result
            return result
        }

        private fun objStmObjects(stmNum: Int): Map<Int, String> {
            objStmCache[stmNum]?.let { return it }
            val result = mutableMapOf<Int, String>()
            val dict = dictOf(stmNum)
            val data = streamOf(stmNum)
            if (dict != null && data != null) {
                val first = intValue(dict, "First") ?: 0
                val n = intValue(dict, "N") ?: 0
                val header = String(data, 0, minOf(first, data.size), Charsets.ISO_8859_1)
                val body = String(data, minOf(first, data.size), data.size - minOf(first, data.size), Charsets.ISO_8859_1)
                val pairs = Regex("(\\d+)\\s+(\\d+)").findAll(header)
                    .take(n).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                    .toList()
                pairs.forEachIndexed { i, (num, off) ->
                    val end = pairs.getOrNull(i + 1)?.second ?: body.length
                    if (off in 0..body.length && end in off..body.length) {
                        result[num] = body.substring(off, end)
                    }
                }
            }
            objStmCache[stmNum] = result
            return result
        }

        fun dictOf(num: Int): String? {
            val raw = rawObject(num) ?: return null
            val start = raw.indexOf("<<")
            if (start < 0) return null
            return balancedDict(raw, start)
        }

        fun streamOf(num: Int): ByteArray? {
            if (streamCache.containsKey(num)) return streamCache[num]
            val result = computeStream(num)
            streamCache[num] = result
            return result
        }

        private fun computeStream(num: Int): ByteArray? {
            val raw = rawObject(num) ?: return null
            val dictStart = raw.indexOf("<<")
            val dict = balancedDict(raw, dictStart) ?: return null
            // The stream keyword AFTER the dict — a dict value could
            // itself contain the word "stream".
            val streamIdx = raw.indexOf("stream", dictStart + dict.length)
            if (streamIdx < 0) return null
            var dataStart = streamIdx + 6
            if (raw.startsWith("\r\n", dataStart)) dataStart += 2
            else if (dataStart < raw.length && (raw[dataStart] == '\n' || raw[dataStart] == '\r')) dataStart++
            // Byte offsets in the Latin-1 view equal raw-string indices,
            // but the stream bytes live in the ORIGINAL byte array: locate
            // them via the object's file offset instead.
            val fileOff = offsets[num] ?: return null
            val objTextStart = Regex("obj\\s").find(text, fileOff)
                ?.range?.last?.plus(1) ?: return null
            val streamFileIdx = objTextStart + dataStart
            // endstream preceded by an end-of-line (a compressed stream
            // can contain the bare bytes "endstream" by coincidence).
            var endMarker = -1
            var searchFrom = streamFileIdx
            while (true) {
                val found = text.indexOf("endstream", searchFrom)
                if (found < 0) break
                if (found > streamFileIdx &&
                    (text[found - 1] == '\n' || text[found - 1] == '\r')
                ) {
                    endMarker = found
                    break
                }
                searchFrom = found + 1
            }
            if (endMarker < 0) return null
            var dataEnd = endMarker
            while (dataEnd > streamFileIdx &&
                (text[dataEnd - 1] == '\n' || text[dataEnd - 1] == '\r')
            ) dataEnd--
            // /Length may itself be an indirect reference.
            val length = intValue(dict, "Length")
                ?: refIn(dict, "Length")?.let { rawObject(it)?.trim()?.toIntOrNull() }
            if (length != null && length > 0 && streamFileIdx + length <= bytes.size) {
                dataEnd = streamFileIdx + length
            }
            if (dataEnd <= streamFileIdx) return null
            val rawBytes = bytes.copyOfRange(streamFileIdx, minOf(dataEnd, bytes.size))
            return decodeFilters(dict, rawBytes)
        }

        private fun decodeFilters(dict: String, data: ByteArray): ByteArray? {
            val filterText = Regex("/Filter\\s*(\\[[^\\]]*\\]|/\\S+)").find(dict)
                ?.groupValues?.get(1) ?: return data
            val filters = Regex("/([A-Za-z0-9]+)").findAll(filterText)
                .map { it.groupValues[1] }.toList()
            var current = data
            for (filter in filters) {
                when (filter) {
                    "FlateDecode", "Fl" -> {
                        current = inflate(current) ?: return null
                    }
                    // Already-decoded or identity: ASCII85 / LZW / DCT are
                    // image or exotic paths — a failed decode drops the
                    // stream rather than failing the whole book.
                    else -> return null
                }
            }
            return current
        }

        private fun inflate(data: ByteArray): ByteArray? {
            for (nowrap in listOf(true, false)) {
                try {
                    val inflater = Inflater(nowrap)
                    inflater.setInput(data)
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buf)
                        if (n == 0) {
                            if (inflater.needsInput()) break else break
                        }
                        out.write(buf, 0, n)
                    }
                    inflater.end()
                    if (out.size() > 0) return out.toByteArray()
                } catch (e: Exception) {
                    // Try the other wrapping.
                }
            }
            return null
        }

        // -- dict helpers ------------------------------------------------

        fun refIn(dict: String, key: String): Int? {
            val m = Regex("/$key\\s+(\\d+)\\s+\\d+\\s+R").find(dict)
            return m?.groupValues?.get(1)?.toIntOrNull()
        }

        fun nameIn(dict: String, key: String): String? {
            val m = Regex("/$key\\s*/([^\\s\\[\\]<>(){}%/]+)").find(dict)
                ?: return null
            return decodeName(m.groupValues[1])
        }

        fun intValue(dict: String, key: String): Int? {
            val m = Regex("/$key\\s+(-?\\d+)").find(dict)
            return m?.groupValues?.get(1)?.toIntOrNull()
        }

        fun arrayInts(dict: String, key: String): List<Int> {
            val seg = Regex("/$key\\s*\\[([^\\]]*)\\]").find(dict)
                ?.groupValues?.get(1) ?: return emptyList()
            return Regex("-?\\d+").findAll(seg).map { it.value.toInt() }.toList()
        }

        fun arrayRefs(dict: String, key: String): List<Int> {
            val seg = Regex("/$key\\s*\\[([^\\]]*)\\]").find(dict)
                ?.groupValues?.get(1) ?: return emptyList()
            return Regex("(\\d+)\\s+\\d+\\s+R").findAll(seg)
                .map { it.groupValues[1].toInt() }.toList()
        }

        /** The dict (or array) starting at [start], balanced over << >>. */
        fun balancedDict(src: String, start: Int): String? {
            if (start < 0 || start + 1 >= src.length) return null
            if (!src.startsWith("<<", start)) return null
            var depth = 0
            var i = start
            while (i + 1 < src.length) {
                when {
                    src.startsWith("<<", i) -> {
                        depth++
                        i += 2
                    }
                    src.startsWith(">>", i) -> {
                        depth--
                        i += 2
                        if (depth == 0) return src.substring(start, i)
                    }
                    src[i] == '(' -> {
                        // Skip a literal string (nested, escaped).
                        var d = 1
                        i++
                        while (i < src.length && d > 0) {
                            when {
                                src[i] == '\\' -> i += 2
                                src[i] == '(' -> {
                                    d++
                                    i++
                                }
                                src[i] == ')' -> {
                                    d--
                                    i++
                                }
                                else -> i++
                            }
                        }
                    }
                    else -> i++
                }
            }
            return null
        }

        fun dictSegment(dict: String, key: String): String? {
            val idx = Regex("/$key\\s*<<").find(dict) ?: return null
            val start = dict.indexOf("<<", idx.range.first)
            return balancedDict(dict, start)
        }

        fun arraySegment(dict: String, key: String): String? {
            val idx = Regex("/$key\\s*\\[").find(dict) ?: return null
            val start = dict.indexOf('[', idx.range.first)
            var depth = 0
            var i = start
            while (i < dict.length) {
                when (dict[i]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return dict.substring(start, i + 1)
                    }
                }
                i++
            }
            return null
        }

        private fun decodeName(name: String): String {
            if ('#' !in name) return name
            val sb = StringBuilder()
            var i = 0
            while (i < name.length) {
                if (name[i] == '#' && i + 2 < name.length) {
                    val hex = name.substring(i + 1, i + 3)
                    val v = hex.toIntOrNull(16)
                    if (v != null) {
                        sb.append(v.toChar())
                        i += 3
                        continue
                    }
                }
                sb.append(name[i])
                i++
            }
            return sb.toString()
        }

        private fun skipWs(pos: Int): Int {
            var i = pos
            while (i < text.length && text[i].isWhitespace()) i++
            return i
        }

        // -- pages, outlines, info ---------------------------------------

        fun pageObjectNumbers(): List<Int> {
            val trailer = trailerDict ?: return emptyList()
            val rootNum = refIn(trailer, "Root") ?: return emptyList()
            val catalog = dictOf(rootNum) ?: return emptyList()
            val pagesNum = refIn(catalog, "Pages") ?: return emptyList()
            val out = mutableListOf<Int>()
            val visited = mutableSetOf<Int>()
            fun walk(num: Int) {
                if (num in visited || out.size > MAX_PAGES) return
                visited += num
                val dict = dictOf(num) ?: return
                val type = nameIn(dict, "Type")
                if (type == "Page" || refIn(dict, "Contents") != null) {
                    out += num
                    return
                }
                for (kid in arrayRefs(dict, "Kids")) walk(kid)
            }
            walk(pagesNum)
            return out
        }

        fun infoTitle(): String? {
            val trailer = trailerDict ?: return null
            val infoNum = refIn(trailer, "Info") ?: return null
            val dict = dictOf(infoNum) ?: return null
            val raw = rawObject(infoNum) ?: return null
            return stringAfterKey(raw, "Title")
        }

        fun outlineEntries(pages: List<Int>): List<OutlineEntry> {
            val trailer = trailerDict ?: return emptyList()
            val rootNum = refIn(trailer, "Root") ?: return emptyList()
            val catalog = dictOf(rootNum) ?: return emptyList()
            val outlinesNum = refIn(catalog, "Outlines") ?: return emptyList()
            val outlines = dictOf(outlinesNum) ?: return emptyList()
            var current = refIn(outlines, "First") ?: return emptyList()
            val pageIndexByNum = pages.withIndex().associate { it.value to it.index }
            val destByName = namedDestinations(catalog)
            val out = mutableListOf<OutlineEntry>()
            var guard = 0
            while (guard++ < 10_000) {
                val dict = dictOf(current) ?: break
                val raw = rawObject(current) ?: break
                val title = stringAfterKey(raw, "Title")
                var pageNum: Int? = null
                val destSeg = arraySegment(dict, "Dest")
                if (destSeg != null) {
                    pageNum = Regex("(\\d+)\\s+\\d+\\s+R").find(destSeg)
                        ?.groupValues?.get(1)?.toIntOrNull()
                }
                if (pageNum == null) {
                    // /A << /D [page …] >> (action destination).
                    val action = dictSegment(dict, "A")
                    if (action != null) {
                        val dSeg = arraySegment(action, "D")
                        pageNum = dSeg?.let {
                            Regex("(\\d+)\\s+\\d+\\s+R").find(it)
                                ?.groupValues?.get(1)?.toIntOrNull()
                        }
                    }
                }
                if (pageNum == null) {
                    // Named destination: /Dest (name).
                    val name = Regex("/Dest\\s*\\(([^)]*)\\)").find(dict)
                        ?.groupValues?.get(1)
                    if (name != null) pageNum = destByName[name]
                }
                if (title != null && pageNum != null) {
                    val idx = pageIndexByNum[pageNum]
                    if (idx != null) out += OutlineEntry(title, idx)
                }
                current = refIn(dict, "Next") ?: break
            }
            // Sorted, one entry per page (first title wins on duplicates).
            return out.sortedBy { it.pageIndex }
                .distinctBy { it.pageIndex }
        }

        private fun namedDestinations(catalog: String): Map<String, Int> {
            val namesNum = refIn(catalog, "Names") ?: return emptyMap()
            val names = dictOf(namesNum) ?: return emptyMap()
            val destsNum = refIn(names, "Dests") ?: return emptyMap()
            val dests = dictOf(destsNum) ?: return emptyMap()
            val array = arraySegment(dests, "Names") ?: return emptyMap()
            val out = mutableMapOf<String, Int>()
            val pairs = Regex("\\(([^)]*)\\)\\s*\\[(\\d+)\\s+\\d+\\s+R")
                .findAll(array)
            for (m in pairs) {
                out[m.groupValues[1]] = m.groupValues[2].toInt()
            }
            return out
        }

        /** PDF string value after `/Key`: literal `(…)` or hex `<…>`. */
        fun stringAfterKey(raw: String, key: String): String? {
            val m = Regex("/$key\\s*").find(raw) ?: return null
            var i = m.range.last + 1
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length) return null
            return when (raw[i]) {
                '(' -> {
                    val sb = StringBuilder()
                    var depth = 1
                    i++
                    while (i < raw.length && depth > 0) {
                        val c = raw[i]
                        if (c == '\\' && i + 1 < raw.length) {
                            val e = raw[i + 1]
                            when (e) {
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                '(', ')', '\\' -> sb.append(e)
                                '\n' -> Unit
                                '\r' -> {
                                    if (raw.getOrNull(i + 2) == '\n') i++
                                }
                                else -> {
                                    if (e.isDigit()) {
                                        var oct = ""
                                        var j = i + 1
                                        while (j < raw.length && j < i + 4 && raw[j].isDigit()) {
                                            oct += raw[j]
                                            j++
                                        }
                                        sb.append(oct.toIntOrNull(8)?.toChar() ?: e)
                                        i = j - 1
                                    } else sb.append(e)
                                }
                            }
                            i += 2
                        } else if (c == '(') {
                            depth++
                            sb.append(c)
                            i++
                        } else if (c == ')') {
                            depth--
                            if (depth > 0) sb.append(c)
                            i++
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                    decodePdfString(sb.toString())
                }
                '<' -> {
                    val end = raw.indexOf('>', i)
                    if (end < 0) null else decodePdfString(
                        hexBytes(raw.substring(i + 1, end))
                    )
                }
                else -> null
            }
        }

        /**
         * A decoded PDF string. In the Latin-1 view a BOM is two separate
         * bytes (FE FF), not one U+FEFF character — so the BOM test is on
         * byte values, and the payload is rebuilt as real UTF-16 bytes.
         */
        private fun decodePdfString(s: String): String {
            if (s.length >= 2 && s[0].code == 0xFE && s[1].code == 0xFF) {
                val raw = ByteArray(s.length - 2) { (s[it + 2].code and 0xFF).toByte() }
                return runCatching { String(raw, Charsets.UTF_16BE) }.getOrDefault(s)
            }
            if (s.length >= 2 && s[0].code == 0xFF && s[1].code == 0xFE) {
                val raw = ByteArray(s.length - 2) { (s[it + 2].code and 0xFF).toByte() }
                return runCatching { String(raw, Charsets.UTF_16LE) }.getOrDefault(s)
            }
            return s
        }

        private fun decodePdfString(bytes: ByteArray): String {
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
            return String(bytes, Charsets.ISO_8859_1)
        }

        private fun hexBytes(hex: String): ByteArray {
            val clean = hex.filter { it.isLetterOrDigit() }
            val out = ByteArray((clean.length + 1) / 2)
            for (i in out.indices) {
                val pair = clean.substring(i * 2, minOf(i * 2 + 2, clean.length))
                out[i] = (pair.padEnd(2, '0').toIntOrNull(16) ?: 0).toByte()
            }
            return out
        }

        // -- fonts and text ----------------------------------------------

        fun pageParagraphs(pageNum: Int): List<String> {
            val dict = dictOf(pageNum) ?: return emptyList()
            val contents = arrayRefs(dict, "Contents").ifEmpty {
                refIn(dict, "Contents")?.let { listOf(it) } ?: emptyList()
            }
            val resources = resourcesOf(dict)
            val lines = mutableListOf<Line>()
            for (contentNum in contents) {
                val stream = streamOf(contentNum) ?: continue
                val content = String(stream, Charsets.ISO_8859_1)
                lines += extractLines(content, resources)
            }
            if (lines.isEmpty()) return emptyList()
            val gaps = lines.mapNotNull { it.gap.takeIf { g -> g > 0 } }.sorted()
            val median = gaps.getOrNull(gaps.size / 2) ?: 0.0
            val threshold = maxOf(median * 1.6, 4.0)
            val paragraphs = mutableListOf<String>()
            val current = StringBuilder()
            fun flushPara() {
                if (current.isNotEmpty()) {
                    paragraphs += current.toString()
                    current.clear()
                }
            }
            for (line in lines) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(line.text)
                if (line.gap > threshold) flushPara()
            }
            flushPara()
            return paragraphs.filter { it.isNotBlank() }
        }

        private fun resourcesOf(pageDict: String): String? {
            val direct = dictSegment(pageDict, "Resources")
            if (direct != null) return direct
            val ref = refIn(pageDict, "Resources") ?: return null
            return dictOf(ref)
        }

        private data class Line(val text: String, val gap: Double)

        private fun extractLines(content: String, resources: String?): List<Line> {
            val lines = mutableListOf<Line>()
            val cur = StringBuilder()
            var font: FontInfo? = null
            var fontSize = 12.0
            var lastY: Double? = null
            var lastX: Double? = null
            var needSpace = false
            fun flush(gap: Double) {
                val t = cur.toString().trim()
                if (t.isNotEmpty()) lines += Line(t, gap)
                cur.clear()
                needSpace = false
            }
            fun appendShow(show: String) {
                if (show.isEmpty()) return
                if (needSpace && cur.isNotEmpty() &&
                    cur.last().isLetterOrDigit() && show.first().isLetterOrDigit()
                ) {
                    cur.append(' ')
                }
                cur.append(show)
                needSpace = false
            }
            val tokens = tokenize(content)
            var i = 0
            val stack = mutableListOf<Token>()
            while (i < tokens.size) {
                val tok = tokens[i++]
                when (tok) {
                    is Token.Op -> {
                        when (tok.name) {
                            "BT" -> {
                                flush(0.0)
                                lastY = null
                                lastX = null
                            }
                            "ET" -> flush(0.0)
                            "Tf" -> {
                                val name = stack.filterIsInstance<Token.Name>().lastOrNull()?.value
                                val size = stack.filterIsInstance<Token.Number>().lastOrNull()?.value
                                if (name != null) {
                                    font = fontInfo(resources, name)
                                    if (size != null) fontSize = size
                                }
                            }
                            "Td", "TD" -> {
                                val nums = stack.filterIsInstance<Token.Number>()
                                if (nums.size >= 2) {
                                    val tx = nums[nums.size - 2].value
                                    val ty = nums[nums.size - 1].value
                                    if (ty != 0.0) {
                                        flush(kotlin.math.abs(ty))
                                        lastY = null
                                        lastX = null
                                    } else if (tx != 0.0) {
                                        needSpace = true
                                    }
                                }
                            }
                            "Tm" -> {
                                val nums = stack.filterIsInstance<Token.Number>()
                                if (nums.size >= 6) {
                                    val e = nums[nums.size - 2].value
                                    val f = nums[nums.size - 1].value
                                    if (lastY != null && f != lastY) {
                                        flush(kotlin.math.abs(f - lastY!!))
                                    } else if (lastX != null && e != lastX) {
                                        needSpace = true
                                    }
                                    lastY = f
                                    lastX = e
                                }
                            }
                            "T*" -> flush(fontSize)
                            "Tj" -> {
                                val s = stack.filterIsInstance<Token.Str>().lastOrNull()
                                if (s != null) appendShow(decodeShow(font, s.bytes))
                            }
                            "TJ" -> {
                                val arr = stack.filterIsInstance<Token.Arr>().lastOrNull()
                                if (arr != null) {
                                    for (el in arr.items) {
                                        when (el) {
                                            is Token.Str -> appendShow(decodeShow(font, el.bytes))
                                            is Token.Number -> if (el.value < -150) needSpace = true
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                            "'", "\"" -> {
                                // Both are T* + show: next line, then text.
                                flush(fontSize)
                                val s = stack.filterIsInstance<Token.Str>().lastOrNull()
                                if (s != null) appendShow(decodeShow(font, s.bytes))
                            }
                        }
                        stack.clear()
                    }
                    else -> stack += tok
                }
            }
            flush(0.0)
            return lines
        }

        private fun fontInfo(resources: String?, name: String): FontInfo? {
            if (resources == null) return null
            val fontDictSeg = dictSegment(resources, "Font") ?: return null
            val ref = Regex("/$name\\s+(\\d+)\\s+\\d+\\s+R").find(fontDictSeg)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return null
            fontCache[ref]?.let { return it }
            val result = buildFontInfo(ref)
            fontCache[ref] = result
            return result
        }

        private fun buildFontInfo(ref: Int): FontInfo? {
            val dict = dictOf(ref) ?: return null
            val subtype = nameIn(dict, "Subtype")
            if (subtype == "Type0") {
                val descRefs = arrayRefs(dict, "DescendantFonts")
                val descNum = descRefs.firstOrNull() ?: return null
                val desc = dictOf(descNum) ?: return null
                val toUnicode = toUnicodeMap(refIn(desc, "ToUnicode"))
                val codeBytes = toUnicode?.codeBytes ?: 2
                return FontInfo(toUnicode?.map ?: emptyMap(), codeBytes, emptyMap(), null)
            }
            val toUnicode = toUnicodeMap(refIn(dict, "ToUnicode"))
            val encoding = nameIn(dict, "Encoding")
            val differences = mutableMapOf<Int, String>()
            val encSeg = dictSegment(dict, "Encoding")
            if (encSeg != null) {
                val diffSeg = arraySegment(encSeg, "Differences")
                if (diffSeg != null) {
                    var code = 0
                    val parts = Regex("(\\d+)|/([^\\s\\[\\]]+)").findAll(diffSeg)
                    for (m in parts) {
                        if (m.groupValues[1].isNotEmpty()) {
                            code = m.groupValues[1].toInt()
                        } else {
                            differences[code] = m.groupValues[2]
                            code++
                        }
                    }
                }
            }
            val codeBytes = toUnicode?.codeBytes ?: 1
            return FontInfo(toUnicode?.map ?: emptyMap(), codeBytes, differences, encoding)
        }

        private data class CMap(val map: Map<Int, String>, val codeBytes: Int)

        private fun toUnicodeMap(ref: Int?): CMap? {
            if (ref == null) return null
            val stream = streamOf(ref) ?: return null
            val cmap = String(stream, Charsets.ISO_8859_1)
            var maxHi = 0
            val spaceSeg = Regex("begincodespacerange(.*?)endcodespacerange", RegexOption.DOT_MATCHES_ALL)
                .find(cmap)?.groupValues?.get(1) ?: ""
            for (m in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(spaceSeg)) {
                maxHi = maxOf(maxHi, m.groupValues[2].toIntOrNull(16) ?: 0)
            }
            val map = mutableMapOf<Int, String>()
            val bfChar = Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL)
                .find(cmap)?.groupValues?.get(1) ?: ""
            for (m in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(bfChar)) {
                val src = m.groupValues[1].toIntOrNull(16) ?: continue
                map[src] = decodeCMapDst(m.groupValues[2])
            }
            val bfRange = Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL)
                .find(cmap)?.groupValues?.get(1) ?: ""
            for (m in Regex(
                "<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<([0-9A-Fa-f]+)>|\\[([^\\]]*)\\])"
            ).findAll(bfRange)) {
                val lo = m.groupValues[1].toIntOrNull(16) ?: continue
                val hi = m.groupValues[2].toIntOrNull(16) ?: continue
                if (m.groupValues[4].isNotEmpty()) {
                    val base = decodeCMapDst(m.groupValues[4])
                    val baseCode = base.firstOrNull()?.code ?: 0
                    for (code in lo..hi) {
                        if (code - lo > 10_000) break
                        map[code] = (baseCode + (code - lo)).toChar().toString()
                    }
                } else if (m.groupValues[5].isNotEmpty()) {
                    val dsts = Regex("<([0-9A-Fa-f]+)>").findAll(m.groupValues[5])
                        .map { decodeCMapDst(it.groupValues[1]) }.toList()
                    for ((k, code) in (lo..hi).withIndex()) {
                        if (k < dsts.size) map[code] = dsts[k]
                    }
                }
            }
            if (map.isEmpty()) return null
            return CMap(map, if (maxHi > 0xFF) 2 else 1)
        }

        private fun decodeCMapDst(hex: String): String {
            val bytes = hexBytes(hex)
            if (bytes.isEmpty()) return ""
            if (bytes.size == 1) return bytes[0].toInt().and(0xFF).toChar().toString()
            return String(bytes, Charsets.UTF_16BE)
        }

        private fun decodeShow(font: FontInfo?, bytes: ByteArray): String {
            if (font == null) {
                // No font context: Latin-1 passthrough for plain ASCII text.
                return bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
            }
            val sb = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val code: Int
                if (font.codeBytes == 2) {
                    if (i + 1 >= bytes.size) break
                    code = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                    i += 2
                } else {
                    code = bytes[i].toInt() and 0xFF
                    i += 1
                }
                val mapped = font.toUnicode[code]
                if (mapped != null) {
                    sb.append(mapped)
                    continue
                }
                val diffName = font.differences[code]
                if (diffName != null) {
                    glyphToUnicode(diffName)?.let { sb.append(it) }
                    continue
                }
                when {
                    code in 32..126 -> sb.append(code.toChar())
                    font.encoding == "WinAnsiEncoding" -> winAnsi(code)?.let { sb.append(it) }
                    code in 160..255 -> sb.append(code.toChar())
                    else -> Unit // Unmapped glyph: dropped, not replaced.
                }
            }
            return sb.toString()
        }

        private fun glyphToUnicode(name: String): String? {
            if (name.length == 1) return name
            if (name.startsWith("uni") && name.length > 3) {
                val hex = name.substring(3)
                if (hex.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) {
                    return hex.chunked(4).mapNotNull { it.toIntOrNull(16)?.toChar() }
                        .joinToString("")
                }
            }
            return when (name) {
                "space" -> " "
                "hyphen", "minus" -> "-"
                "endash" -> "–"
                "emdash" -> "—"
                "quoteleft" -> "‘"
                "quoteright" -> "’"
                "quotedblleft" -> "“"
                "quotedblright" -> "”"
                "quotesingle" -> "'"
                "quotedbl" -> "\""
                "bullet" -> "•"
                "ellipsis" -> "…"
                "Euro" -> "€"
                "fi" -> "fi"
                "fl" -> "fl"
                "ff" -> "ff"
                "ffi" -> "ffi"
                "ffl" -> "ffl"
                else -> null
            }
        }

        private fun winAnsi(code: Int): Char? = when (code) {
            0x80 -> '€'
            0x82 -> '‚'
            0x83 -> 'ƒ'
            0x84 -> '„'
            0x85 -> '…'
            0x86 -> '†'
            0x87 -> '‡'
            0x88 -> 'ˆ'
            0x89 -> '‰'
            0x8A -> 'Š'
            0x8B -> '‹'
            0x8C -> 'Œ'
            0x8E -> 'Ž'
            0x91 -> '‘'
            0x92 -> '’'
            0x93 -> '“'
            0x94 -> '”'
            0x95 -> '•'
            0x96 -> '–'
            0x97 -> '—'
            0x98 -> '˜'
            0x99 -> '™'
            0x9A -> 'š'
            0x9B -> '›'
            0x9C -> 'œ'
            0x9E -> 'ž'
            0x9F -> 'Ÿ'
            else -> null
        }

        private data class FontInfo(
            val toUnicode: Map<Int, String>,
            val codeBytes: Int,
            val differences: Map<Int, String>,
            val encoding: String?
        )

        // -- content-stream tokenizer ------------------------------------

        private sealed interface Token {
            data class Str(val bytes: ByteArray) : Token
            data class Name(val value: String) : Token
            data class Number(val value: Double) : Token
            data class Arr(val items: List<Token>) : Token
            data class Op(val name: String) : Token
        }

        private fun tokenize(content: String): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            fun isDelim(c: Char): Boolean =
                c.isWhitespace() || c in "()<>[]{}/%"

            while (i < content.length) {
                val c = content[i]
                when {
                    c.isWhitespace() -> i++
                    c == '%' -> {
                        while (i < content.length && content[i] != '\n') i++
                    }
                    c == '(' -> {
                        val bytes = mutableListOf<Byte>()
                        var depth = 1
                        i++
                        while (i < content.length && depth > 0) {
                            val ch = content[i]
                            if (ch == '\\' && i + 1 < content.length) {
                                val e = content[i + 1]
                                when (e) {
                                    'n' -> bytes += '\n'.code.toByte()
                                    'r' -> bytes += '\r'.code.toByte()
                                    't' -> bytes += '\t'.code.toByte()
                                    'b' -> bytes += '\b'.code.toByte()
                                    'f' -> bytes += 0x0C.toByte()
                                    '(', ')', '\\' -> bytes += e.code.toByte()
                                    '\n' -> Unit
                                    '\r' -> {
                                        if (content.getOrNull(i + 2) == '\n') i++
                                    }
                                    else -> {
                                        if (e.isDigit()) {
                                            var oct = ""
                                            var j = i + 1
                                            while (j < content.length && j < i + 4 &&
                                                content[j].isDigit()
                                            ) {
                                                oct += content[j]
                                                j++
                                            }
                                            bytes += (oct.toIntOrNull(8) ?: 0).toByte()
                                            i = j - 1
                                        } else bytes += e.code.toByte()
                                    }
                                }
                                i += 2
                            } else if (ch == '(') {
                                depth++
                                bytes += ch.code.toByte()
                                i++
                            } else if (ch == ')') {
                                depth--
                                if (depth > 0) bytes += ch.code.toByte()
                                i++
                            } else {
                                bytes += ch.code.toByte()
                                i++
                            }
                        }
                        out += Token.Str(bytes.toByteArray())
                    }
                    c == '<' && content.getOrNull(i + 1) == '<' -> {
                        // Inline dict (gs arguments etc.): skip balanced.
                        var depth = 0
                        while (i + 1 < content.length) {
                            if (content.startsWith("<<", i)) {
                                depth++
                                i += 2
                            } else if (content.startsWith(">>", i)) {
                                depth--
                                i += 2
                                if (depth == 0) break
                            } else i++
                        }
                    }
                    c == '<' -> {
                        val end = content.indexOf('>', i)
                        if (end < 0) break
                        out += Token.Str(hexBytes(content.substring(i + 1, end)))
                        i = end + 1
                    }
                    c == '[' -> {
                        val items = mutableListOf<Token>()
                        i++
                        while (i < content.length) {
                            while (i < content.length && content[i].isWhitespace()) i++
                            if (i >= content.length) break
                            if (content[i] == ']') {
                                i++
                                break
                            }
                            if (content[i] == '(' || content[i] == '<') {
                                val (tok, next) = readStringToken(content, i)
                                if (tok != null) {
                                    items += tok
                                    i = next
                                } else break
                            } else {
                                var j = i
                                while (j < content.length && !isDelim(content[j])) j++
                                val word = content.substring(i, j)
                                word.toDoubleOrNull()?.let { items += Token.Number(it) }
                                i = j
                            }
                        }
                        out += Token.Arr(items)
                    }
                    c == '/' -> {
                        var j = i + 1
                        while (j < content.length && !isDelim(content[j])) j++
                        out += Token.Name(content.substring(i + 1, j))
                        i = j
                    }
                    c.isDigit() || c == '-' || c == '+' || c == '.' -> {
                        var j = i
                        while (j < content.length &&
                            (content[j].isDigit() || content[j] in "-+.eE")
                        ) j++
                        content.substring(i, j).toDoubleOrNull()?.let {
                            out += Token.Number(it)
                        }
                        i = j
                    }
                    c.isLetter() || c == '*' || c == '\'' || c == '"' -> {
                        var j = i
                        while (j < content.length &&
                            (content[j].isLetter() || content[j] in "*'\"")
                        ) j++
                        out += Token.Op(content.substring(i, j))
                        i = j
                    }
                    else -> i++
                }
            }
            return out
        }

        private fun readStringToken(content: String, start: Int): Pair<Token.Str?, Int> {
            if (content[start] == '(') {
                val bytes = mutableListOf<Byte>()
                var depth = 1
                var i = start + 1
                while (i < content.length && depth > 0) {
                    val ch = content[i]
                    if (ch == '\\' && i + 1 < content.length) {
                        val e = content[i + 1]
                        when (e) {
                            'n' -> bytes += '\n'.code.toByte()
                            'r' -> bytes += '\r'.code.toByte()
                            't' -> bytes += '\t'.code.toByte()
                            '(', ')', '\\' -> bytes += e.code.toByte()
                            else -> {
                                if (e.isDigit()) {
                                    var oct = ""
                                    var j = i + 1
                                    while (j < content.length && j < i + 4 && content[j].isDigit()) {
                                        oct += content[j]
                                        j++
                                    }
                                    bytes += (oct.toIntOrNull(8) ?: 0).toByte()
                                    i = j - 1
                                } else bytes += e.code.toByte()
                            }
                        }
                        i += 2
                    } else if (ch == '(') {
                        depth++
                        bytes += ch.code.toByte()
                        i++
                    } else if (ch == ')') {
                        depth--
                        if (depth > 0) bytes += ch.code.toByte()
                        i++
                    } else {
                        bytes += ch.code.toByte()
                        i++
                    }
                }
                return Token.Str(bytes.toByteArray()) to i
            }
            if (content[start] == '<') {
                val end = content.indexOf('>', start)
                if (end < 0) return null to start
                return Token.Str(hexBytes(content.substring(start + 1, end))) to end + 1
            }
            return null to start
        }
    }
}
