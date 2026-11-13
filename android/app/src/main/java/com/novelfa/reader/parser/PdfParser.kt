package com.novelfa.reader.parser

import com.novelfa.reader.data.DiagLog
import com.novelfa.reader.model.ParsedBook
import java.io.File

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
 * Honest limits (a text extractor cannot invent text): scanned-image
 * PDFs have no text layer and therefore import as "no readable sections",
 * and PDFs whose fonts lack both a ToUnicode map and a usable encoding
 * lose the affected glyphs. Encrypted PDFs using the Standard security
 * handler (R2–R6) are supported: an empty user password opens
 * automatically, otherwise [PdfPasswordRequiredException] asks the caller
 * for a password ([PdfPasswordWrongException] on a wrong one) and
 * [decryptToPlainPdf] rewrites the file without encryption for storage.
 * Non-Standard security handlers (vendor DRM) are not supported.
 * Text is kept in stream order; the reader's per-paragraph direction
 * stamping then lays RTL and LTR paragraphs out correctly.
 */
object PdfParser {


    fun parse(
        bytes: ByteArray,
        fileName: String,
        outDir: File,
        chapterFallback: (Int) -> String,
        password: String? = null,
    checkCancelled: (() -> Unit)? = null
    ): ParsedBook = parseDetailed(bytes, fileName, outDir, chapterFallback, password, checkCancelled).first

    /**
     * Conservative encryption probe, for the importer's fast path.
     * Returns true when any trailer in the startxref chain declares
     * /Encrypt, OR when the trailers cannot be read at all (the caller
     * then runs the full [PdfDoc] probe, exactly as before). Returns
     * false only when every trailer in the chain was read successfully
     * and none mentions /Encrypt — the common unencrypted case, where
     * constructing a whole [PdfDoc] (full xref scan over the file)
     * just to learn "nothing to decrypt" was pure import overhead.
     * The check reads only the trailer dicts (a few KB near the
     * startxref offsets), never the whole file as a string, and it
     * can only err towards true (full probe), never towards skipping
     * a decryption the file actually needs: encryption is declared
     * in a trailer dict by definition, and the walk follows /Prev
     * through incremental updates and hybrid xref/stream chains.
     */
    fun maybeEncrypted(bytes: ByteArray): Boolean {
        // startxref lives in the tail (%%EOF trailer).
        val tailStart = maxOf(0, bytes.size - 2048)
        val tail = String(bytes, tailStart, bytes.size - tailStart, Charsets.ISO_8859_1)
        val sx = tail.lastIndexOf("startxref")
        if (sx < 0) return true
        var offset = tail.substring(sx + "startxref".length)
            .trimStart().takeWhile { it.isDigit() }.toIntOrNull() ?: return true
        val seen = mutableSetOf<Int>()
        var guard = 0
        while (guard++ < 25) {
            if (offset < 0 || offset >= bytes.size || !seen.add(offset)) return true
            // Trailer dicts are small; a bounded window is enough, and
            // a dict that does not close inside it means "uncertain".
            val winEnd = minOf(bytes.size, offset + 65536)
            val win = String(bytes, offset, winEnd - offset, Charsets.ISO_8859_1)
            val body = win.trimStart()
            val dictStart = if (body.startsWith("xref")) {
                val tr = body.indexOf("trailer")
                if (tr < 0) return true
                body.indexOf("<<", tr)
            } else {
                body.indexOf("<<")
            }
            if (dictStart < 0) return true
            val dict = balancedTrailerDict(body, dictStart) ?: return true
            if (dict.contains("/Encrypt")) return true
            val prev = Regex("/Prev\\s+(\\d+)").find(dict)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return false
            offset = prev
        }
        return true
    }

    /**
     * File-based twin of [maybeEncrypted]: the same trailer walk,
     * but every window is read with RandomAccessFile seeks instead
     * of loading the whole file into a ByteArray first. The
     * importer's file:// in-place probe and the copy-flow PDF
     * branch used to pay a full readBytes just to ask "is this
     * encrypted" — for a big plain PDF that was the single
     * largest wasted read at import. Same conservative contract:
     * uncertain answers true; only a cleanly-read chain with no
     * /Encrypt answers false.
     */
    fun maybeEncryptedFile(file: File): Boolean {
        val raf = try {
            java.io.RandomAccessFile(file, "r")
        } catch (e: Exception) {
            DiagLog.w("NovelfaPdfParser", "maybeEncryptedFile: cannot open file, assuming encrypted", e)
            return true
        }
        try {
            val length = raf.length()
            if (length <= 0L) return true
            val tailLen = minOf(2048L, length).toInt()
            val tailBytes = ByteArray(tailLen)
            raf.seek(length - tailLen)
            raf.readFully(tailBytes)
            val tail = String(tailBytes, Charsets.ISO_8859_1)
            val sx = tail.lastIndexOf("startxref")
            if (sx < 0) return true
            var offset = tail.substring(sx + "startxref".length)
                .trimStart().takeWhile { it.isDigit() }.toIntOrNull() ?: return true
            val seen = mutableSetOf<Int>()
            var guard = 0
            while (guard++ < 25) {
                if (offset < 0 || offset >= length || !seen.add(offset)) return true
                val winEnd = minOf(length, offset + 65536L)
                val winBytes = ByteArray((winEnd - offset).toInt())
                raf.seek(offset.toLong())
                raf.readFully(winBytes)
                val body = String(winBytes, Charsets.ISO_8859_1).trimStart()
                val dictStart = if (body.startsWith("xref")) {
                    val tr = body.indexOf("trailer")
                    if (tr < 0) return true
                    body.indexOf("<<", tr)
                } else {
                    body.indexOf("<<")
                }
                if (dictStart < 0) return true
                val dict = balancedTrailerDict(body, dictStart) ?: return true
                if (dict.contains("/Encrypt")) return true
                val prev = Regex("/Prev\\s+(\\d+)").find(dict)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: return false
                offset = prev
            }
            return true
        } catch (e: Exception) {
            DiagLog.w("NovelfaPdfParser", "maybeEncryptedFile: trailer walk failed, assuming encrypted", e)
            return true
        } finally {
            runCatching { raf.close() }
        }
    }

    /** Balanced `<<...>>` dict starting at [start], or null if it does not close. */
    private fun balancedTrailerDict(text: String, start: Int): String? {
        var depth = 0
        var i = start
        while (i < text.length) {
            when {
                text.startsWith("<<", i) -> {
                    depth++
                    i += 2
                }
                text.startsWith(">>", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return text.substring(start, i)
                }
                text[i] == '(' -> {
                    // Literal string: skip balanced parens with escapes.
                    var d = 1
                    i++
                    while (i < text.length && d > 0) {
                        when {
                            text[i] == '\\' && i + 1 < text.length -> i += 2
                            text[i] == '(' -> {
                                d++
                                i++
                            }
                            text[i] == ')' -> {
                                d--
                                i++
                            }
                            else -> i++
                        }
                    }
                }
                text[i] == '<' -> {
                    val end = text.indexOf('>', i)
                    if (end < 0) return null
                    i = end + 1
                }
                else -> i++
            }
        }
        return null
    }

    /**
     * Like [parse], plus the per-page text the caller needs to build a
     * MIXED book (text pages + image pages in page order): the parsed
     * book, one flag per page (true = that page yielded text), and the
     * paragraphs per page. [BookConverter] uses this so a PDF with some
     * scanned pages no longer drops those pages entirely.
     */
    fun parseDetailed(
        bytes: ByteArray,
        fileName: String,
        outDir: File,
        chapterFallback: (Int) -> String,
        password: String? = null,
    checkCancelled: (() -> Unit)? = null,
    onPage: ((Int, Int) -> Unit)? = null
    ): Triple<ParsedBook, List<Boolean>, List<List<String>>> {
        val doc = PdfDoc(bytes, password)
        val pages = doc.pageObjectNumbers()
        if (pages.isEmpty()) {
            throw IllegalArgumentException("PDF has no pages")
        }
        if (pages.size > MAX_PAGES) {
            throw IllegalArgumentException("PDF has too many pages")
        }
        // ReadEra's per-page failure rule, adapted: one pathological
        // page must never kill the whole book. A page that throws
        // becomes a text-less page (the mixed path then renders it
        // as an image, exactly like a scanned page) instead of the
        // entire text parse collapsing into the full-image fallback
        // or failing the import outright.
        // Cancellation hook (the caller injects it): page extraction
        // is pure blocking CPU work with no suspension point, so
        // without a per-page check the import dialog's Cancel button
        // could not interrupt a big text PDF at all — the job was
        // cancelled but the parse ran on to completion regardless.
        val pageParagraphs = mutableListOf<List<String>>()
        for ((idx, num) in pages.withIndex()) {
            checkCancelled?.invoke()
            pageParagraphs += runCatching { doc.pageParagraphs(num) }.getOrDefault(emptyList())
            // Progress for the reader's loading UI: the first page,
            // every 10th page and the last one, so a long parse does
            // not recompose the UI once per page — but the user also
            // does not stare at a bare spinner until page 10 with no
            // sign that anything is happening at all.
            if (idx == 0 || idx + 1 == pages.size || (idx + 1) % 10 == 0) {
                onPage?.invoke(idx + 1, pages.size)
            }
        }
        val outline = doc.outlineEntries(pages)
        val sections: List<ConvertedBook.Section>
        if (outline.size >= 2) {
            // Pages before the first outline entry used to be dropped
            // entirely (cover, foreword, …): they become a first
            // section of their own, and the entry titles shift by one.
            val hasPre = outline.first().pageIndex > 0
            val preSection = if (hasPre) {
                val pre = (0 until outline.first().pageIndex).flatMap { pageParagraphs[it] }
                listOf(
                    ConvertedBook.Section(
                        chapterFallback(1),
                        pre.joinToString("") { "<p>" + ConvertedBook.escape(it) + "</p>" }
                    )
                )
            } else emptyList()
            val outlineSections = outline.mapIndexed { idx, entry ->
                val end = outline.getOrNull(idx + 1)?.pageIndex ?: pages.size
                val paras = (entry.pageIndex until end).flatMap { pageParagraphs[it] }
                ConvertedBook.Section(
                    entry.title.trim().take(120)
                        .ifBlank { chapterFallback(idx + if (hasPre) 2 else 1) },
                    paras.joinToString("") { "<p>" + ConvertedBook.escape(it) + "</p>" }
                )
            }
            sections = (preSection + outlineSections).filter { it.bodyHtml.isNotEmpty() }
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
        val parsed = ConvertedBook.write(outDir, title, ConvertedBook.chunkSections(sections))
        return Triple(parsed, pageParagraphs.map { it.isNotEmpty() }, pageParagraphs)
    }

    // ------------------------------------------------------------------
    // Document model: xref, objects, streams, pages, outlines.
    // ------------------------------------------------------------------


    /**
     * Returns the stored file rewritten without encryption, or null when
     * the file is not encrypted at all. The caller stores the result, so
     * the book opens later without ever asking for a password again —
     * the password itself is never stored. Throws
     * [PdfPasswordRequiredException] when [password] is null and the
     * empty password does not open the file, [PdfPasswordWrongException]
     * when a given password fails, and
     * [PdfUnsupportedEncryptionException] for non-Standard handlers.
     */
    fun decryptToPlainPdf(
        data: ByteArray,
        password: String?,
        // Cancellation hook (the importer injects it): the rewrite
        // below walks every object of the file with no suspension
        // point, so without it Cancel could not interrupt an
        // encrypted PDF's rewrite at all.
        checkCancelled: (() -> Unit)? = null
    ): ByteArray? {
        val doc = PdfDoc(data, password)
        if (doc.security == null) return null
        return doc.writeDecryptedPdf(checkCancelled)
    }

}
