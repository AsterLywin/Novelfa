package com.novelfa.reader.parser.engine

import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookConverter
import com.novelfa.reader.parser.BookFormat
import java.io.File

/**
 * The PDF engine. PDF was already lazy at import (decrypt +
 * platform-renderer page-count validation only), so this engine
 * changes no import behaviour — it only gives PDF its own
 * rendering owner instead of sharing the old shared dispatch.
 *
 * Reading: direct PDFs open in the native viewer
 * ([com.novelfa.reader.reader.PdfiumNativeSource], routed in
 * PdfReaderRoute) since 2026-11-01, when the user chose the
 * PDFium engine over the converted path. This engine's
 * converted text/image path through [BookConverter] (text
 * first, page images for scanned pages, mixed books page by
 * page) remains for PDFs carried inside a ZIP archive, which
 * the archive engine still opens through the HTML reader —
 * and as the fallback if native PDF routing ever reverts.
 */
object PdfEngine : BookEngine {
    override val format: BookFormat = BookFormat.PDF

    override fun metadata(
        source: File,
        displayName: String,
        scratchDir: File,
        cancelCheck: (() -> Unit)?
    ): EngineMetadata {
        return EngineMetadata(
            title = File(displayName).nameWithoutExtension
                .takeIf { it.isNotBlank() } ?: displayName,
            chapterCount = null,
            coverFile = null
        )
    }

    override suspend fun open(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String,
        displayName: String?,
        onProgress: ((Int, Int) -> Unit)?,
        cancelCheck: (() -> Unit)?
    ): ParsedBook {
        return BookConverter.parse(source, outDir, chapterFallback, displayName, onProgress)
    }
}
