package com.novelfa.reader.parser

import java.io.File
import java.util.zip.ZipFile

/**
 * Book file formats Novelfa opens. EPUB is the native format (parsed by
 * [EpubParser]); the rest are converted to chapter HTML files at import
 * and at open time by the parsers in this package, so the reader itself
 * never knows the difference.
 *
 * Detection prefers the file extension (what pickers and file managers
 * report) and falls back to content sniffing for extension-less files.
 */
enum class BookFormat(val extension: String) {
    EPUB("epub"),
    TXT("txt"),
    MARKDOWN("md"),
    PDF("pdf"),
    DOCX("docx");

    companion object {
        fun fromExtension(fileName: String): BookFormat? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "epub" -> EPUB
                "txt", "text" -> TXT
                "md", "markdown", "mdown" -> MARKDOWN
                "pdf" -> PDF
                "docx" -> DOCX
                else -> null
            }
        }

        /**
         * Strong content sniffing, IGNORING the file extension: a PDF
         * magic header, or a zip carrying word/document.xml (DOCX) or a
         * package document (EPUB). Only these three have a signature
         * strong enough to overrule an extension — plain text has none,
         * so a .md file full of plain text must never be reclassified
         * as TXT by sniffing. Null when nothing strong matches.
         * Callers that must not trust a possibly-wrong extension
         * (importer, reader open path) check this FIRST.
         */
        fun detectStrongContent(file: File): BookFormat? {
            if (!file.isFile) return null
            return runCatching {
                val head = ByteArray(8)
                val read = file.inputStream().use { it.read(head) }
                if (read >= 4 && head[0] == '%'.code.toByte() &&
                    head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() &&
                    head[3] == 'F'.code.toByte()
                ) {
                    return@runCatching PDF
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
                            else -> null
                        }
                    }
                }
                null
            }.getOrNull()
        }

        /**
         * Extension first, content sniffing second. A zip is a DOCX when
         * it carries word/document.xml, an EPUB when it carries a package
         * document; anything else that looks like plain text is TXT.
         * Null when the content matches nothing known.
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
