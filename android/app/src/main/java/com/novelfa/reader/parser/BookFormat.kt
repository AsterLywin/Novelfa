package com.novelfa.reader.parser

import java.io.File
import java.util.zip.ZipFile

/**
 * Book file formats Novelfa opens. EPUB is the native format (parsed by
 * [EpubParser]); TXT, Markdown, DOCX, FB2/FBZ and RTF are converted to
 * chapter HTML files at import and at open time by the parsers in this
 * package, so the reader itself never knows the difference. PDF, CBZ
 * and ZIP are the exception: they are NATIVE image/page books, opened
 * on demand by the native viewer (see reader/PdfNativeReader.kt) and
 * never converted — a PDF stays a PDF, a CBZ/ZIP stays its archive of
 * page images ([CbzBook]).
 *
 * Detection prefers the file extension (what pickers and file managers
 * report) and falls back to content sniffing for extension-less files.
 * A .zip that actually carries an EPUB or DOCX is still claimed by
 * strong sniffing first (see the importer, which checks sniffing
 * before extension); a plain .zip of images is an image book.
 */
enum class BookFormat(val extension: String) {
    EPUB("epub"),
    TXT("txt"),
    MARKDOWN("md"),
    PDF("pdf"),
    DOCX("docx"),
    CBZ("cbz"),
    ZIP("zip"),
    FB2("fb2"),
    FBZ("fbz"),
    RTF("rtf");

    companion object {
        fun fromExtension(fileName: String): BookFormat? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "epub" -> EPUB
                "txt", "text" -> TXT
                "md", "markdown", "mdown" -> MARKDOWN
                "pdf" -> PDF
                "docx" -> DOCX
                "cbz" -> CBZ
                "zip" -> ZIP
                "fb2" -> FB2
                "fbz" -> FBZ
                "rtf" -> RTF
                else -> null
            }
        }

        /**
         * Strong content sniffing, IGNORING the file extension: a PDF
         * magic header, an RTF `{\rtf` header, an FB2 `<FictionBook>`
         * root, or a zip carrying word/document.xml (DOCX), a package
         * document (EPUB) or a .fb2 entry (FBZ). Only these have a
         * signature strong enough to overrule an extension — plain
         * text has none, so a .md file full of plain text must never
         * be reclassified as TXT by sniffing. Null when nothing
         * strong matches. Callers that must not trust a possibly-wrong
         * extension (importer, reader open path) check this FIRST.
         */
        fun detectStrongContent(file: File): BookFormat? {
            if (!file.isFile) return null
            return runCatching {
                // The FB2 root sits past an XML declaration (and
                // possibly a BOM), so the head sample is 4 KiB,
                // not the 8 bytes the PDF check needs.
                val head = ByteArray(4096)
                val read = file.inputStream().use { it.read(head) }
                if (read >= 4 && head[0] == '%'.code.toByte() &&
                    head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() &&
                    head[3] == 'F'.code.toByte()
                ) {
                    return@runCatching PDF
                }
                if (read >= 5 && head[0] == '{'.code.toByte() &&
                    head[1] == '\\'.code.toByte() && head[2] == 'r'.code.toByte() &&
                    head[3] == 't'.code.toByte() && head[4] == 'f'.code.toByte()
                ) {
                    return@runCatching RTF
                }
                if (read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                    ZipFile(file).use { zip ->
                        val names = zip.entries().toList().map { it.name }
                        return@runCatching when {
                            names.any { it == "word/document.xml" } -> DOCX
                            names.any {
                                it == "META-INF/container.xml" ||
                                    it.lowercase().endsWith(".opf")
                            } -> EPUB
                            names.any { it.lowercase().endsWith(".fb2") } -> FBZ
                            else -> null
                        }
                    }
                }
                // FB2: `<FictionBook` anywhere in the head sample
                // (past `<?xml ...?>`, a BOM, comments and
                // whitespace). The literal root tag is unambiguous:
                // no other format opens with it. A file that only
                // MENTIONS FictionBook in prose (e.g. a TXT about
                // FB2) cannot match — the tag must be a real tag
                // open, and the sample is the file's start, where
                // only the root element lives.
                if (read > 0) {
                    val text = String(head, 0, read, Charsets.UTF_8)
                        .replace("\u0000", "")
                    if (Regex("<FictionBook[\\s>]").containsMatchIn(text)) {
                        return@runCatching FB2
                    }
                }
                null
            }.getOrNull()
        }

        /**
         * Extension first, content sniffing second. A zip is a DOCX when
         * it carries word/document.xml, an EPUB when it carries a package
         * document, an FBZ when it carries a .fb2 entry; an
         * extension-less file starting with `{\rtf` is RTF and one
         * whose root is `<FictionBook>` is FB2. Anything else that
         * looks like plain text is TXT. Null when the content matches
         * nothing known.
         */
        fun detect(file: File): BookFormat? {
            fromExtension(file.name)?.let { return it }
            detectStrongContent(file)?.let { return it }
            if (!file.isFile) return null
            return runCatching {
                // Extension-less plain text: mostly printable, valid
                // UTF-8 — or Windows-1256 Persian (see below), the
                // encoding of many Persian text files from Windows.
                val sample = ByteArray(4096)
                val n = file.inputStream().use { it.read(sample) }
                if (n > 0 && (isProbablyText(sample, n) ||
                        isProbablyWindows1256Text(sample, n))
                ) TXT else null
            }.getOrNull()
        }

        /**
         * Windows-1256 sniff, deliberately narrow: a binary file must
         * never be mistaken for text. Requires few control bytes, a
         * majority of high bytes in the 0xC0-0xFF band where
         * Windows-1256 puts Arabic/Persian letters, and at least one
         * decoded Arabic-script character. Plain Latin-1/Windows-1252
         * text without Arabic letters is NOT claimed here (it is
         * already valid UTF-8 in the ASCII range, or fails cleanly).
         */
        private fun isProbablyWindows1256Text(sample: ByteArray, n: Int): Boolean {
            var controls = 0
            var high = 0
            var highArabicBand = 0
            for (i in 0 until n) {
                val b = sample[i].toInt() and 0xFF
                if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) controls++
                if (b >= 0x80) {
                    high++
                    if (b >= 0xC0) highArabicBand++
                }
            }
            if (controls * 100 > n) return false
            if (high == 0 || highArabicBand * 2 < high) return false
            return runCatching {
                val decoded = String(sample, 0, n, java.nio.charset.Charset.forName("windows-1256"))
                decoded.any { it in '\u0600'..'\u06FF' || it in '\uFB50'..'\uFDFF' }
            }.getOrDefault(false)
        }

        private fun isProbablyText(sample: ByteArray, n: Int): Boolean {
            var controls = 0
            for (i in 0 until n) {
                val b = sample[i].toInt() and 0xFF
                if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) controls++
            }
            if (controls * 100 > n) return false
            return runCatching {
                java.nio.charset.Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(sample, 0, n))
                true
            }.getOrDefault(false)
        }
    }
}
