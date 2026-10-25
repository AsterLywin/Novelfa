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
            BookFormat.PDF -> PdfParser.parse(
                source.readBytes(), source.name, outDir, chapterFallback
            )
            BookFormat.DOCX -> DocxParser.parseFile(source, outDir, chapterFallback)
            else -> throw IllegalArgumentException(
                "Unsupported book format: ${source.name}"
            )
        }
    }
}
