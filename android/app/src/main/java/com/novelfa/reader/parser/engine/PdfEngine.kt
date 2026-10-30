package com.novelfa.reader.parser.engine

import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookConverter
import com.novelfa.reader.parser.BookFormat
import java.io.File

/**
 * The PDF engine. PDF was already lazy at import (decrypt +
 * platform-renderer page-count validation only), so this engine
 * changes no import behaviour — it only gives PDF its own rendering
 * owner instead of sharing the old shared dispatch. Its open path
 * is the converted text/image path through [BookConverter]: text
 * first, page images for scanned pages, mixed books page by page.
 *
 * The native fixed-page viewer (platform PdfRenderer bitmaps, the
 * ReadEra fixed-page model) still exists in
 * [com.novelfa.reader.reader.PdfNativeReader] but stays unused: the
 * user reverted PDF to this converted path because the native
 * viewer cannot apply the reader font and was reported as broken
 * rendering. If it is ever wanted again, it belongs here, behind
 * this engine, as a user-chosen rendering mode — not as a second
 * dispatch in the reader.
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
