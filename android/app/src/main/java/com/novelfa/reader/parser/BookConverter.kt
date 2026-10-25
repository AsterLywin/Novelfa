package com.novelfa.reader.parser

import com.novelfa.reader.model.ParsedBook
import java.io.File

/**
 * Single dispatch for every non-EPUB format, shared by the importer
 * (fresh parse, to count sections and read the title) and the reader
 * (open path, into the cached `epub_open/<bookId>` directory).
 * EPUB keeps its own path ([EpubParser] over an extracted tree) and is
 * rejected here so a caller can never silently route it through a
 * converter that would drop its images, styles and spine order.
 */
object BookConverter {

    fun parse(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String
    ): ParsedBook {
        return when (BookFormat.detect(source)) {
            BookFormat.TXT -> TextBookParser.parseText(
                source.readBytes(), source.name, outDir, chapterFallback
            )
            BookFormat.MARKDOWN -> TextBookParser.parseMarkdown(
                source.readBytes(), source.name, outDir, chapterFallback
            )
            BookFormat.PDF -> parsePdf(source, outDir, chapterFallback)
            BookFormat.DOCX -> DocxParser.parseFile(source, outDir, chapterFallback)
            else -> throw IllegalArgumentException(
                "Unsupported book format: ${source.name}"
            )
        }
    }

    /**
     * PDF: text first, images as fallback. A scanned / image-only PDF
     * yields zero text chapters — instead of failing the import with
     * "no readable sections", its pages are rendered as images
     * ([PdfImageBook], the ComicScreen approach: a PDF page is a page
     * image, not a text flow). A text parse that throws is given the
     * same chance, since the platform renderer has its own parser and
     * can open files the text extractor rejects — except password
     * problems, which are resolved upstream and must never be masked
     * as an image failure. If rendering also fails, the original text
     * error (when there is one) is the honest one to report.
     */
    private fun parsePdf(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String
    ): ParsedBook {
        val text = runCatching {
            PdfParser.parse(source.readBytes(), source.name, outDir, chapterFallback)
        }
        if (text.isSuccess) {
            val parsed = text.getOrThrow()
            if (parsed.chapters.isNotEmpty()) return parsed
        } else {
            val err = text.exceptionOrNull()
            if (err is PdfPasswordRequiredException ||
                err is PdfPasswordWrongException ||
                err is PdfUnsupportedEncryptionException
            ) {
                throw err
            }
        }
        val title = text.getOrNull()?.title ?: source.nameWithoutExtension
        try {
            return PdfImageBook.render(source, outDir, title)
        } catch (renderErr: Exception) {
            throw text.exceptionOrNull() ?: renderErr
        }
    }
}
