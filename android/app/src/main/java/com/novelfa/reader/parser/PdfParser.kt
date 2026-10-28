package com.novelfa.reader.parser

import com.novelfa.reader.model.ParsedBook
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
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

    private const val MAX_PAGES = 5_000

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
            // Progress for the reader's loading UI: every 10th page
            // and the last one, so a long parse does not recompose
            // the UI once per page.
            if (idx + 1 == pages.size || (idx + 1) % 10 == 0) {
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

    private data class OutlineEntry(val title: String, val pageIndex: Int)

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
    fun decryptToPlainPdf(data: ByteArray, password: String?): ByteArray? {
        val doc = PdfDoc(data, password)
        if (doc.security == null) return null
        return doc.writeDecryptedPdf()
    }

    private class PdfDoc(private val bytes: ByteArray, private val password: String? = null) {
        /** Latin-1 view: char index == byte index, so offsets stay valid. */
        val text: String = String(bytes, Charsets.ISO_8859_1)

        private val offsets = mutableMapOf<Int, Int>()
        private val genMap = mutableMapOf<Int, Int>()
        private val objStm = mutableMapOf<Int, Pair<Int, Int>>()
        private val objStmCache = mutableMapOf<Int, Map<Int, String>>()
        private val rawCache = mutableMapOf<Int, String>()
        private val streamCache = mutableMapOf<Int, ByteArray?>()
        private val fontCache = mutableMapOf<Int, FontInfo?>()
        private var trailerDict: String? = null
        /** Null for unencrypted files; set once the /Encrypt dict is seen. */
        var security: Security? = null
            private set

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
                                    genMap[num] = entry.groupValues[2].toIntOrNull() ?: 0
                                }
                            }
                            pos = entryEnd + 1
                        }
                    }
                    if (trailerDict == null) trailerDict = trailer
                    if (trailer != null) ensureSecurity(trailer)
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
                    // The xref stream itself is encrypted (StmF), but its
                    // dict — and the /Encrypt + /ID entries in it — are
                    // plaintext, so security can be built BEFORE the
                    // stream bytes are read below.
                    ensureSecurity(dict)
                    parseXrefStream(objNum, dict)
                    offset = intValue(dict, "Prev")
                }
            }
            if (offsets.isEmpty() && objStm.isEmpty()) {
                // Last resort: linear scan for "N G obj" occurrences.
                Regex("(\\d+)\\s+(\\d+)\\s+obj").findAll(text).forEach { m ->
                    val num = m.groupValues[1].toInt()
                    if (num !in offsets) {
                        offsets[num] = m.range.first
                        genMap[num] = m.groupValues[2].toIntOrNull() ?: 0
                    }
                }
            }
            // A trailer without /Encrypt (or a file whose trailers were
            // all unreadable) simply stays unencrypted.
            trailerDict?.let { ensureSecurity(it) }
        }

        /**
         * Builds [security] from a trailer dict the first time an
         * /Encrypt entry is seen. The encrypt dict is a regular object:
         * in classic files it is already in [offsets]; in xref-stream
         * files the stream is not parsed yet, so it is found by a linear
         * scan (producers never compress the encrypt dict).
         */
        private fun ensureSecurity(trailer: String) {
            if (security != null) return
            val encDict = dictSegment(trailer, "Encrypt")
                ?: refIn(trailer, "Encrypt")?.let { encNum ->
                    dictOf(encNum) ?: linearScanDict(encNum)
                    ?: throw PdfUnsupportedEncryptionException(
                        "Encrypt dict object $encNum not found"
                    )
                }
                ?: return
            security = Security(encDict, idFromTrailer(trailer))
        }

        private fun linearScanDict(num: Int): String? {
            val header = Regex("(?m)^$num\\s+(\\d+)\\s+obj").find(text) ?: return null
            if (num !in offsets) {
                offsets[num] = header.range.first
                genMap[num] = header.groupValues[1].toIntOrNull() ?: 0
            }
            val rawStart = text.indexOf("<<", header.range.last + 1)
            if (rawStart < 0) return null
            return balancedDict(text, rawStart)
        }

        private fun idFromTrailer(trailer: String): ByteArray? {
            val seg = arraySegment(trailer, "ID") ?: return null
            val hex = Regex("<([0-9A-Fa-f]+)>").find(seg)?.groupValues?.get(1)
            if (hex != null) return hexBytes(hex)
            val lit = Regex("\\(([^)]*)\\)").find(seg)?.groupValues?.get(1)
            return lit?.toByteArray(Charsets.ISO_8859_1)
        }

        // -- encryption (Standard security handler) ---------------------

        private enum class CryptMethod { IDENTITY, RC4, AES128, AES256 }

        /**
         * The Standard security handler for this file, built from the
         * /Encrypt dict. The constructor validates the password: it tries
         * the given password first, then the empty password (level 1:
         * permission-only files open with no prompt at all). Both failing
         * throws [PdfPasswordWrongException] when a password was given,
         * [PdfPasswordRequiredException] otherwise. Malformed dicts and
         * non-Standard handlers throw [PdfUnsupportedEncryptionException].
         */
        private inner class Security(encDict: String, id0: ByteArray?) {
            private val revision: Int =
                intValue(encDict, "R")
                    ?: throw PdfUnsupportedEncryptionException("Encrypt dict has no /R")
            private val version: Int = intValue(encDict, "V") ?: 0
            private val keyLen: Int =
                if (revision >= 5) 32 else (intValue(encDict, "Length") ?: 40) / 8
            private val oBytes: ByteArray =
                stringBytesAfterKey(encDict, "O")
                    ?: throw PdfUnsupportedEncryptionException("Encrypt dict has no /O")
            private val uBytes: ByteArray =
                stringBytesAfterKey(encDict, "U")
                    ?: throw PdfUnsupportedEncryptionException("Encrypt dict has no /U")
            private val oeBytes: ByteArray? = stringBytesAfterKey(encDict, "OE")
            private val ueBytes: ByteArray? = stringBytesAfterKey(encDict, "UE")
            private val permsBytes: ByteArray? = stringBytesAfterKey(encDict, "Perms")
            private val pValue: Int = intValue(encDict, "P") ?: 0
            private val pBytes: ByteArray = byteArrayOf(
                pValue.toByte(), (pValue shr 8).toByte(),
                (pValue shr 16).toByte(), (pValue shr 24).toByte()
            )
            private val cfDict: String? = dictSegment(encDict, "CF")
            private val stringMethod: CryptMethod
            private val streamMethod: CryptMethod

            /** False when the metadata stream is stored unencrypted. */
            val encryptMetadata: Boolean

            /** The file key: derived once, used for every object. */
            val fileKey: ByteArray

            init {
                val filter = nameIn(encDict, "Filter")
                if (filter != null && filter != "Standard") {
                    throw PdfUnsupportedEncryptionException(
                        "Security handler is not Standard: $filter"
                    )
                }
                if (revision !in 2..6) {
                    throw PdfUnsupportedEncryptionException(
                        "Unsupported security revision: $revision"
                    )
                }
                if (revision < 5) {
                    if (oBytes.size != 32 || uBytes.size != 32) {
                        throw PdfUnsupportedEncryptionException("Malformed /O or /U")
                    }
                } else {
                    if (uBytes.size != 48 || oBytes.size != 48 ||
                        ueBytes?.size != 32 || oeBytes?.size != 32
                    ) {
                        throw PdfUnsupportedEncryptionException("Malformed R5/R6 entries")
                    }
                }
                fun methodFor(which: String?): CryptMethod {
                    if (version < 4) return CryptMethod.RC4
                    val name = which ?: return CryptMethod.IDENTITY
                    if (name == "Identity") return CryptMethod.IDENTITY
                    val seg = cfDict?.let { dictSegment(it, name) }
                        ?: throw PdfUnsupportedEncryptionException(
                            "Unknown crypt filter: $name"
                        )
                    return when (nameIn(seg, "CFM")) {
                        "None" -> CryptMethod.IDENTITY
                        "V1", "V2" -> CryptMethod.RC4
                        "AESV2" -> CryptMethod.AES128
                        "AESV3" -> CryptMethod.AES256
                        else -> throw PdfUnsupportedEncryptionException(
                            "Unsupported crypt filter method in $name"
                        )
                    }
                }
                stringMethod = methodFor(nameIn(encDict, "StrF"))
                streamMethod = methodFor(nameIn(encDict, "StmF"))
                if (revision >= 5 &&
                    (stringMethod == CryptMethod.RC4 || stringMethod == CryptMethod.AES128 ||
                        streamMethod == CryptMethod.RC4 || streamMethod == CryptMethod.AES128)
                ) {
                    throw PdfUnsupportedEncryptionException(
                        "R5/R6 requires AESV3 crypt filters"
                    )
                }
                encryptMetadata = if (revision >= 5) {
                    permsBytes?.getOrNull(8)?.toInt()?.and(0xFF) != 0x46
                } else {
                    !Regex("/EncryptMetadata\\s+false").containsMatchIn(encDict)
                }
                val pwBytes = password?.toByteArray(Charsets.UTF_8)
                val candidates = mutableListOf<ByteArray>()
                if (pwBytes != null) candidates += pwBytes
                if (pwBytes == null || !pwBytes.contentEquals(ByteArray(0))) {
                    candidates += ByteArray(0)
                }
                var found: ByteArray? = null
                for (pw in candidates) {
                    found = tryPassword(pw, id0)
                    if (found != null) break
                }
                fileKey = found ?: if (password != null) {
                    throw PdfPasswordWrongException()
                } else {
                    throw PdfPasswordRequiredException()
                }
            }

            /** File key for [pw], or null when it does not open the file. */
            private fun tryPassword(pw: ByteArray, id0: ByteArray?): ByteArray? {
                return when (revision) {
                    2 -> {
                        val padded = PdfCrypto.padPassword32(pw)
                        val key = PdfCrypto.md5(
                            padded, oBytes, pBytes, id0 ?: ByteArray(0)
                        ).copyOf(5)
                        val computed = PdfCrypto.rc4(key, PdfCrypto.PADDING)
                        if (computed.contentEquals(uBytes)) key else null
                    }
                    3, 4 -> {
                        val padded = PdfCrypto.padPassword32(pw)
                        val meta = if (revision >= 4 && !encryptMetadata) {
                            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
                        } else {
                            ByteArray(0)
                        }
                        val digest = PdfCrypto.md5(
                            padded, oBytes, pBytes, id0 ?: ByteArray(0), meta
                        )
                        val key = digest.copyOf(keyLen)
                        if (validatesU(digest, key)) key else null
                    }
                    5, 6 -> {
                        val pw127 = PdfCrypto.truncatePassword127(pw)
                        // User password first, then owner password (an owner
                        // password also opens the document).
                        val userKey = userFileKey(pw127, revision == 6)
                        if (userKey != null) return userKey
                        ownerFileKey(pw127, revision == 6)
                    }
                    else -> null
                }
            }

            /**
             * R3/R4 U validation: 20 RC4 passes over the digest, keyed by
             * the digest XORed with the round number. The spec's "original
             * key" is read two ways in the wild (key-length bytes vs the
             * full digest); both are tried, since a password validating
             * under either reading is certainly correct.
             */
            private fun validatesU(digest: ByteArray, key: ByteArray): Boolean {
                if (uBytes.size < 16) return false
                val target = uBytes.copyOfRange(0, 16)
                for (keySize in listOf(keyLen, 16)) {
                    var data = digest.copyOf()
                    for (i in 0 until 20) {
                        val k = ByteArray(keySize) {
                            (digest[it].toInt() xor i).toByte()
                        }
                        data = PdfCrypto.rc4(k, data)
                    }
                    if (data.copyOfRange(0, 16).contentEquals(target)) return true
                }
                return false
            }

            private fun userFileKey(pw127: ByteArray, isR6: Boolean): ByteArray? {
                if (uBytes.size < 48 || ueBytes == null) return null
                val hash = if (isR6) {
                    PdfCrypto.r6Hash(pw127, uBytes.copyOfRange(32, 40), ByteArray(0))
                } else {
                    PdfCrypto.sha256(pw127, uBytes.copyOfRange(32, 40))
                }
                if (!hash.contentEquals(uBytes.copyOfRange(0, 32))) return null
                val salt = uBytes.copyOfRange(40, 48)
                val keyHash = if (isR6) {
                    PdfCrypto.r6Hash(pw127, salt, ByteArray(0))
                } else {
                    PdfCrypto.sha256(pw127, salt)
                }
                val key = PdfCrypto.aesEcbDecrypt(keyHash, ueBytes) ?: return null
                return if (validPerms(key)) key else null
            }

            private fun ownerFileKey(pw127: ByteArray, isR6: Boolean): ByteArray? {
                if (oBytes.size < 48 || oeBytes == null || uBytes.size < 48) return null
                val u48 = uBytes.copyOfRange(0, 48)
                val hash = if (isR6) {
                    PdfCrypto.r6Hash(pw127, oBytes.copyOfRange(32, 40), u48)
                } else {
                    PdfCrypto.sha256(pw127, oBytes.copyOfRange(32, 40), u48)
                }
                if (!hash.contentEquals(oBytes.copyOfRange(0, 32))) return null
                val salt = oBytes.copyOfRange(40, 48)
                val keyHash = if (isR6) {
                    PdfCrypto.r6Hash(pw127, salt, u48)
                } else {
                    PdfCrypto.sha256(pw127, salt, u48)
                }
                val key = PdfCrypto.aesEcbDecrypt(keyHash, oeBytes) ?: return null
                return if (validPerms(key)) key else null
            }

            /** Perms integrity: P, all-ones, and the 'adb' marker. */
            private fun validPerms(key: ByteArray): Boolean {
                val perms = permsBytes ?: return true
                if (perms.size != 16) return true
                val dec = PdfCrypto.aesEcbDecrypt(key, perms) ?: return false
                if (!dec.copyOfRange(0, 4).contentEquals(pBytes)) return false
                for (i in 4 until 8) {
                    if ((dec[i].toInt() and 0xFF) != 0xFF) return false
                }
                return dec[9] == 0x61.toByte() && dec[10] == 0x64.toByte() &&
                    dec[11] == 0x62.toByte()
            }

            /** Per-object key (R<5): file key + object/generation + salt. */
            private fun objectKey(objNum: Int, aesSalt: Boolean): ByteArray {
                val gen = genMap[objNum] ?: 0
                val material = fileKey +
                    byteArrayOf(
                        (objNum and 0xFF).toByte(), ((objNum shr 8) and 0xFF).toByte(),
                        ((objNum shr 16) and 0xFF).toByte()
                    ) +
                    byteArrayOf((gen and 0xFF).toByte(), ((gen shr 8) and 0xFF).toByte()) +
                    if (aesSalt) {
                        byteArrayOf(0x73, 0x41, 0x6C, 0x54)
                    } else {
                        ByteArray(0)
                    }
                val digest = PdfCrypto.md5(material)
                val len = minOf(keyLen + 5, 16)
                val key = digest.copyOf(len)
                return if (aesSalt && key.size < 16) {
                    key + ByteArray(16 - key.size)
                } else {
                    key
                }
            }

            /** Decrypts one string value belonging to [objNum]. */
            fun decryptString(data: ByteArray, objNum: Int): ByteArray {
                // Strings inside object streams are plaintext: the whole
                // stream was decrypted, not its members.
                if (objNum in objStm) return data
                return when (stringMethod) {
                    CryptMethod.IDENTITY -> data
                    CryptMethod.RC4 -> PdfCrypto.rc4(objectKey(objNum, false), data)
                    CryptMethod.AES128 -> PdfCrypto.aesCbcDecryptWithIvPrefix(
                        objectKey(objNum, true), data
                    ) ?: ByteArray(0)
                    CryptMethod.AES256 -> PdfCrypto.aesCbcDecryptWithIvPrefix(
                        fileKey, data
                    ) ?: ByteArray(0)
                }
            }

            /** Decrypts one stream belonging to [objNum]. */
            fun decryptStream(data: ByteArray, objNum: Int): ByteArray {
                return when (streamMethod) {
                    CryptMethod.IDENTITY -> data
                    CryptMethod.RC4 -> PdfCrypto.rc4(objectKey(objNum, false), data)
                    CryptMethod.AES128 -> PdfCrypto.aesCbcDecryptWithIvPrefix(
                        objectKey(objNum, true), data
                    ) ?: ByteArray(0)
                    CryptMethod.AES256 -> PdfCrypto.aesCbcDecryptWithIvPrefix(
                        fileKey, data
                    ) ?: ByteArray(0)
                }
            }
        }

        /**
         * The stored file rewritten without encryption: every regular
         * object with its strings decrypted and its stream decrypted
         * (filters kept, /Length fixed), object-stream members expanded
         * to regular objects, all references renumbered to generation 0,
         * and a fresh classic xref + trailer without /Encrypt. The result
         * parses with this same parser sans password — which the caller
         * verifies by parsing it before storing.
         */
        fun writeDecryptedPdf(): ByteArray {
            val sec = security ?: return bytes
            val out = ByteArrayOutputStream()
            fun ascii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
            ascii("%PDF-1.7\n")
            val writtenOffsets = mutableMapOf<Int, Int>()
            // The metadata stream stays plaintext when EncryptMetadata is
            // false; everything else is decrypted.
            val metadataNum = if (!sec.encryptMetadata) {
                val trailer = trailerDict ?: ""
                refIn(trailer, "Root")?.let { dictOf(it) }?.let { refIn(it, "Metadata") }
            } else {
                null
            }
            fun emitObject(num: Int, body: String, streamBytes: ByteArray?) {
                writtenOffsets[num] = out.size()
                ascii("$num 0 obj\n")
                if (streamBytes != null) {
                    ascii(body)
                    ascii("\nstream\n")
                    out.write(streamBytes)
                    ascii("\nendstream\nendobj\n")
                } else {
                    ascii(body)
                    ascii("\nendobj\n")
                }
            }
            fun rewriteRefs(text: String): String =
                Regex("(\\d+)\\s+(\\d+)\\s+R").replace(text) { "${it.groupValues[1]} 0 R" }

            // The encrypt dict itself is dropped: the rewritten trailer
            // has no /Encrypt, so it would only be an orphan.
            val encryptNum = trailerDict?.let { refIn(it, "Encrypt") }
            fun fixLength(dict: String, len: Int): String {
                val refLen = Regex("/Length\\s+\\d+\\s+0\\s+R").find(dict)
                if (refLen != null) {
                    return dict.replaceRange(refLen.range, "/Length $len")
                }
                val directLen = Regex("/Length\\s+\\d+").find(dict)
                if (directLen != null) {
                    return dict.replaceRange(directLen.range, "/Length $len")
                }
                val idx = dict.lastIndexOf(">>")
                return if (idx >= 0) {
                    dict.substring(0, idx) + " /Length $len " + dict.substring(idx)
                } else {
                    dict
                }
            }
            for (num in offsets.keys.sorted()) {
                if (num == 0 || num == encryptNum) continue
                val raw = rawObject(num) ?: continue
                val dictStart = raw.indexOf("<<")
                if (dictStart < 0) {
                    // Bare value: decrypt any strings, rewrite refs.
                    emitObject(num, rewriteRefs(decryptStringsInText(raw, num)), null)
                    continue
                }
                val dict = balancedDict(raw, dictStart) ?: continue
                val stream = rawStream(num)
                if (stream == null) {
                    // Dict object without a readable stream.
                    emitObject(num, rewriteRefs(decryptStringsInText(dict, num)), null)
                    continue
                }
                val (streamDict, stored) = stream
                val plain = if (num == metadataNum) {
                    stored
                } else {
                    sec.decryptStream(stored, num)
                }
                val newDict = fixLength(
                    rewriteRefs(decryptStringsInText(streamDict, num)), plain.size
                )
                emitObject(num, newDict, plain)
            }
            // Object-stream members become regular objects (their text is
            // already plaintext — the stream was decrypted as a whole).
            val inner = mutableMapOf<Int, String>()
            for ((stmNum, _) in objStm) {
                for ((n, body) in objStmObjects(stmNum)) inner[n] = body
            }
            for ((num, body) in inner) {
                if (num == 0 || num in writtenOffsets) continue
                emitObject(num, rewriteRefs(body), null)
            }
            val maxNum = writtenOffsets.keys.maxOrNull() ?: 0
            val xrefPos = out.size()
            ascii("xref\n0 ${maxNum + 1}\n")
            ascii("0000000000 65535 f \n")
            for (n in 1..maxNum) {
                val off = writtenOffsets[n]
                if (off != null) {
                    // Locale.US: Persian-locale %d would emit Eastern-Arabic
                    // digits and corrupt the xref table.
                    ascii(String.format(Locale.US, "%010d 00000 n \n", off))
                } else {
                    ascii("0000000000 00000 f \n")
                }
            }
            val trailer = trailerDict ?: ""
            val root = refIn(trailer, "Root")
            val info = refIn(trailer, "Info")
            val idSeg = arraySegment(trailer, "ID")
            val trailerOut = StringBuilder("<< /Size ${maxNum + 1}")
            if (root != null) trailerOut.append(" /Root $root 0 R")
            if (info != null) trailerOut.append(" /Info $info 0 R")
            if (idSeg != null) trailerOut.append(" /ID $idSeg")
            trailerOut.append(" >>")
            ascii("trailer\n$trailerOut\nstartxref\n$xrefPos\n%%EOF\n")
            return out.toByteArray()
        }

        /**
         * Decrypts every literal/hex string token in [text] (dicts and
         * bare values) with [objNum]'s key, re-emitting each as a hex
         * string — unambiguous, no escaping questions. Dict delimiters
         * << >> are passed through, never mistaken for hex strings.
         */
        private fun decryptStringsInText(text: String, objNum: Int): String {
            val sec = security ?: return text
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("<<", i) -> {
                        out.append("<<")
                        i += 2
                    }
                    text.startsWith(">>", i) -> {
                        out.append(">>")
                        i += 2
                    }
                    text[i] == '(' -> {
                        val parsed = parseLiteralBytes(text, i)
                        if (parsed == null) {
                            out.append(text[i])
                            i++
                        } else {
                            val (data, end) = parsed
                            val plain = sec.decryptString(data, objNum)
                            out.append('<')
                            for (b in plain) {
                                out.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
                            }
                            out.append('>')
                            i = end
                        }
                    }
                    text[i] == '<' -> {
                        val end = text.indexOf('>', i)
                        if (end < 0) {
                            out.append(text[i])
                            i++
                        } else {
                            val plain = sec.decryptString(hexBytes(text.substring(i + 1, end)), objNum)
                            out.append('<')
                            for (b in plain) {
                                out.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
                            }
                            out.append('>')
                            i = end + 1
                        }
                    }
                    else -> {
                        out.append(text[i])
                        i++
                    }
                }
            }
            return out.toString()
        }

        /** Literal string at [start] ('(') → its bytes + end index. */
        private fun parseLiteralBytes(text: String, start: Int): Pair<ByteArray, Int>? {
            var i = start + 1
            var depth = 1
            val out = ByteArrayOutputStream()
            while (i < text.length && depth > 0) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    val e = text[i + 1]
                    when (e) {
                        'n' -> {
                            out.write('\n'.code)
                            i += 2
                        }
                        'r' -> {
                            out.write('\r'.code)
                            i += 2
                        }
                        't' -> {
                            out.write('\t'.code)
                            i += 2
                        }
                        'b' -> {
                            out.write('\b'.code)
                            i += 2
                        }
                        'f' -> {
                            out.write(0x0C)
                            i += 2
                        }
                        '(', ')', '\\' -> {
                            out.write(e.code)
                            i += 2
                        }
                        '\n' -> i += 2
                        '\r' -> i += if (text.getOrNull(i + 2) == '\n') 3 else 2
                        else -> {
                            if (e.isDigit()) {
                                var oct = ""
                                var j = i + 1
                                while (j < text.length && j < i + 4 && text[j].isDigit()) {
                                    oct += text[j]
                                    j++
                                }
                                out.write(oct.toIntOrNull(8) ?: e.code)
                                i = j
                            } else {
                                out.write(e.code)
                                i += 2
                            }
                        }
                    }
                } else if (c == '(') {
                    depth++
                    out.write(c.code)
                    i++
                } else if (c == ')') {
                    depth--
                    if (depth > 0) out.write(c.code)
                    i++
                } else {
                    out.write(c.code and 0xFF)
                    i++
                }
            }
            if (depth != 0) return null
            return out.toByteArray() to i
        }

        /** Raw bytes of the string value after `/Key` (no text decoding). */
        private fun stringBytesAfterKey(dict: String, key: String): ByteArray? {
            val m = Regex("/$key\\s*").find(dict) ?: return null
            var i = m.range.last + 1
            while (i < dict.length && dict[i].isWhitespace()) i++
            if (i >= dict.length) return null
            return when (dict[i]) {
                '(' -> parseLiteralBytes(dict, i)?.first
                '<' -> {
                    if (dict.startsWith("<<", i)) {
                        null
                    } else {
                        val end = dict.indexOf('>', i)
                        if (end < 0) null else hexBytes(dict.substring(i + 1, end))
                    }
                }
                else -> null
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
                        1 -> if (num !in offsets) {
                            offsets[num] = f2
                            genMap[num] = f3
                        }
                        2 -> if (num !in objStm) objStm[num] = f2 to f3
                    }
                }
            }
        }

        // -- raw objects and streams ------------------------------------

        fun rawObject(num: Int): String? {
            rawCache[num]?.let { return it }
            val result = offsets[num]?.let { off ->
                val header = Regex("(\\d+)\\s+(\\d+)\\s+obj").find(text, off)
                if (header != null && header.groupValues[1].toIntOrNull() == num &&
                    num !in genMap
                ) {
                    genMap[num] = header.groupValues[2].toIntOrNull() ?: 0
                }
                val contentStart = Regex("obj\\s").find(text, off)
                    ?.range?.last?.plus(1) ?: return@let null
                val end = text.indexOf("endobj", contentStart)
                if (end < 0) null else text.substring(contentStart, end)
            } ?: objStm[num]?.let { (stmNum, index) ->
                objStmObjects(stmNum)[index]
            }
            // Bounded memoization: raw object bodies include stream
            // bytes, so caching every object retains the whole file a
            // second time (as substrings) for the entire parse — GC
            // pressure that grows with the book. Small dicts (pages,
            // fonts, outlines) stay cached; large bodies are re-read
            // from the file view on the rare second access instead.
            if (result != null && result.length <= 131072) rawCache[num] = result
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
            // Same bound as rawCache: decoded content streams are the
            // biggest retained objects in a parse, and each is normally
            // read once per page anyway (font CMaps are small and stay
            // cached, so font lookups keep their fast path).
            if (result == null || result.size <= 131072) streamCache[num] = result
            return result
        }

        private fun computeStream(num: Int): ByteArray? {
            val (dict, stored) = rawStream(num) ?: return null
            // Encryption is applied AFTER filters on write, so it comes
            // off FIRST on read; then the filters decode as usual.
            val plain = security?.decryptStream(stored, num) ?: stored
            return decodeFilters(dict, plain)
        }

        /**
         * A stream's dict and its STORED bytes (still encrypted when the
         * file is encrypted). Shared by [computeStream] and the
         * decrypted-file rewriter.
         */
        private fun rawStream(num: Int): Pair<String, ByteArray>? {
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
            return dict to rawBytes
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
            return stringAfterKey(raw, "Title", infoNum)
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
                val title = stringAfterKey(raw, "Title", current)
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
                    // Named destination: /Dest (name) — decrypted with
                    // this object's key when the file is encrypted.
                    val nameRaw = Regex("/Dest\\s*\\(([^)]*)\\)").find(dict)
                        ?.groupValues?.get(1)
                    if (nameRaw != null) {
                        val nameBytes = parseLiteralBytes("($nameRaw)", 0)?.first
                            ?: nameRaw.toByteArray(Charsets.ISO_8859_1)
                        val plain = if (current !in objStm) {
                            security?.decryptString(nameBytes, current) ?: nameBytes
                        } else {
                            nameBytes
                        }
                        pageNum = destByName[decodePdfString(plain)]
                    }
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
                // Names live in the dests object: decrypt them when the
                // file is encrypted (unless the object is compressed).
                val nameBytes = parseLiteralBytes("(${m.groupValues[1]})", 0)?.first
                    ?: m.groupValues[1].toByteArray(Charsets.ISO_8859_1)
                val plain = if (destsNum !in objStm) {
                    security?.decryptString(nameBytes, destsNum) ?: nameBytes
                } else {
                    nameBytes
                }
                out[decodePdfString(plain)] = m.groupValues[2].toInt()
            }
            return out
        }

        /**
         * PDF string value after `/Key`: literal `(…)` or hex `<…>`.
         * [objNum] decrypts the value when the file is encrypted (the
         * string lives in that object); object-stream members are
         * already plaintext and must not be decrypted again.
         */
        fun stringAfterKey(raw: String, key: String, objNum: Int? = null): String? {
            val m = Regex("/$key\\s*").find(raw) ?: return null
            var i = m.range.last + 1
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length) return null
            val bytes: ByteArray = when (raw[i]) {
                '(' -> parseLiteralBytes(raw, i)?.first ?: return null
                '<' -> {
                    if (raw.startsWith("<<", i)) return null
                    val end = raw.indexOf('>', i)
                    if (end < 0) return null
                    hexBytes(raw.substring(i + 1, end))
                }
                else -> return null
            }
            val plain = if (objNum != null && objNum !in objStm) {
                security?.decryptString(bytes, objNum) ?: bytes
            } else {
                bytes
            }
            return decodePdfString(plain)
        }

        /**
         * A decoded PDF string. A BOM is two separate bytes (FE FF), not
         * one U+FEFF character — so the BOM test is on byte values, and
         * the payload is rebuilt as real UTF-16 bytes.
         */
        private fun decodePdfString(bytes: ByteArray): String {
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
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
            // Operand tracking WITHOUT a stack list: the old code ran
            // filterIsInstance over the whole operand stack on EVERY
            // operator (Tf/Td/Tm/Tj/TJ), allocating a filtered list per
            // operator — for text-heavy pages, thousands of throwaway
            // lists per page. The stack is cleared after each operator,
            // so only the latest operand of each kind (and the count of
            // numbers, for Tm's >= 6 check) can ever be read; tracking
            // those directly is exactly equivalent.
            var lastName: String? = null
            var lastNum: Double? = null
            var prevNum: Double? = null
            var numCount = 0
            var lastStr: Token.Str? = null
            var lastArr: Token.Arr? = null
            fun clearOperands() {
                lastName = null
                lastNum = null
                prevNum = null
                numCount = 0
                lastStr = null
                lastArr = null
            }
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
                                val name = lastName
                                val size = lastNum
                                if (name != null) {
                                    font = fontInfo(resources, name)
                                    if (size != null) fontSize = size
                                }
                            }
                            "Td", "TD" -> {
                                if (numCount >= 2) {
                                    val tx = prevNum!!
                                    val ty = lastNum!!
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
                                if (numCount >= 6) {
                                    val e = prevNum!!
                                    val f = lastNum!!
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
                                val s = lastStr
                                if (s != null) appendShow(decodeShow(font, s.bytes))
                            }
                            "TJ" -> {
                                val arr = lastArr
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
                                val s = lastStr
                                if (s != null) appendShow(decodeShow(font, s.bytes))
                            }
                        }
                        clearOperands()
                    }
                    else -> when (tok) {
                        is Token.Name -> lastName = tok.value
                        is Token.Number -> {
                            prevNum = lastNum
                            lastNum = tok.value
                            numCount++
                        }
                        is Token.Str -> lastStr = tok
                        is Token.Arr -> lastArr = tok
                        else -> Unit
                    }
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
                // (StringBuilder loop, not bytes.map{}.joinToString: the
                // old form allocated a boxed Char per byte plus the list.)
                val sb = StringBuilder(bytes.size)
                for (b in bytes) sb.append((b.toInt() and 0xFF).toChar())
                return sb.toString()
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
                        // ByteArrayOutputStream, NOT MutableList<Byte>:
                        // the old list boxed EVERY content byte (one
                        // Byte object per byte of every page stream),
                        // which was the single biggest allocation churn
                        // in a text-PDF import.
                        val bytes = ByteArrayOutputStream()
                        var depth = 1
                        i++
                        while (i < content.length && depth > 0) {
                            val ch = content[i]
                            if (ch == '\\' && i + 1 < content.length) {
                                val e = content[i + 1]
                                when (e) {
                                    'n' -> bytes.write('\n'.code)
                                    'r' -> bytes.write('\r'.code)
                                    't' -> bytes.write('\t'.code)
                                    'b' -> bytes.write('\b'.code)
                                    'f' -> bytes.write(0x0C)
                                    '(', ')', '\\' -> bytes.write(e.code)
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
                                            bytes.write(oct.toIntOrNull(8) ?: 0)
                                            i = j - 1
                                        } else bytes.write(e.code)
                                    }
                                }
                                i += 2
                            } else if (ch == '(') {
                                depth++
                                bytes.write(ch.code)
                                i++
                            } else if (ch == ')') {
                                depth--
                                if (depth > 0) bytes.write(ch.code)
                                i++
                            } else {
                                bytes.write(ch.code)
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
                            } else if (content[i] == '/') {
                                // Names can appear inside arrays (marked
                                // content, pattern operands): same token
                                // as at the top level, not an empty word.
                                var j = i + 1
                                while (j < content.length && !isDelim(content[j])) j++
                                items += Token.Name(content.substring(i + 1, j))
                                i = j
                            } else if (content[i] == '%') {
                                // Comments can appear inside arrays too.
                                while (i < content.length && content[i] != '\n') i++
                            } else {
                                var j = i
                                while (j < content.length && !isDelim(content[j])) j++
                                val word = content.substring(i, j)
                                word.toDoubleOrNull()?.let { items += Token.Number(it) }
                                // A stray delimiter (')', '{', '}', ...)
                                // yields an EMPTY word; without this
                                // guard i never advances, the tokenizer
                                // spins forever, and the import dialog
                                // stays up for eternity.
                                i = if (j > i) j else i + 1
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
                val bytes = ByteArrayOutputStream()
                var depth = 1
                var i = start + 1
                while (i < content.length && depth > 0) {
                    val ch = content[i]
                    if (ch == '\\' && i + 1 < content.length) {
                        val e = content[i + 1]
                        when (e) {
                            'n' -> bytes.write('\n'.code)
                            'r' -> bytes.write('\r'.code)
                            't' -> bytes.write('\t'.code)
                            '(', ')', '\\' -> bytes.write(e.code)
                            else -> {
                                if (e.isDigit()) {
                                    var oct = ""
                                    var j = i + 1
                                    while (j < content.length && j < i + 4 && content[j].isDigit()) {
                                        oct += content[j]
                                        j++
                                    }
                                    bytes.write(oct.toIntOrNull(8) ?: 0)
                                    i = j - 1
                                } else bytes.write(e.code)
                            }
                        }
                        i += 2
                    } else if (ch == '(') {
                        depth++
                        bytes.write(ch.code)
                        i++
                    } else if (ch == ')') {
                        depth--
                        if (depth > 0) bytes.write(ch.code)
                        i++
                    } else {
                        bytes.write(ch.code)
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
