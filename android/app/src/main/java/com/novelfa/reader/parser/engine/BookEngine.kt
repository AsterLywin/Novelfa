package com.novelfa.reader.parser.engine

import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookFormat
import java.io.File

/**
 * What an import needs to know about a book WITHOUT paying for a full
 * parse. This is the ReadEra import rule, applied to Novelfa: the
 * library row is built from metadata only (title, a chapter count when
 * the container carries one for free, a cover when a single entry
 * holds it). The expensive work — unzipping a whole EPUB, walking a
 * DOCX body, extracting PDF text — happens at first open, never at
 * import. A null [chapterCount] means "unknown until open", exactly
 * like the lazy PDF rows already had; the reader corrects the row
 * when the book is first opened.
 */
data class EngineMetadata(
    val title: String,
    val chapterCount: Int?,
    val coverFile: File?
)

/**
 * One rendering engine per format. The old design routed every
 * non-EPUB format through a single [com.novelfa.reader.parser.BookConverter]
 * dispatch and EPUB through its own path, so a change to one format's
 * rendering could silently affect another's, and import had to know
 * each format's parse cost. Now each format owns both halves of its
 * lifecycle:
 *
 * - [metadata]: cheap, import-time only. Must never unzip a whole
 *   archive, walk a whole document body, or render a page. Reading
 *   one small container entry (OPF, core.xml, a cover image) is
 *   allowed; reading the whole file is not.
 * - [open]: full, open-time only. May unzip, parse and convert, and
 *   writes its output into [outDir] (the reader's per-book cache
 *   directory, stamped by the caller).
 *
 * Engines delegate to the existing parsers — this layer changes
 * WHO calls the parser and WHEN, not the parser's output — so a
 * book that opened before opens the same way now, through its own
 * engine instead of a shared dispatch.
 */
interface BookEngine {
    val format: BookFormat

    fun metadata(
        source: File,
        displayName: String,
        scratchDir: File,
        cancelCheck: (() -> Unit)? = null
    ): EngineMetadata

    suspend fun open(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String,
        displayName: String?,
        onProgress: ((Int, Int) -> Unit)? = null,
        cancelCheck: (() -> Unit)? = null
    ): ParsedBook
}

/**
 * The registry is the only place that knows which engine serves
 * which format. Callers (import, reader open, routing) ask here and
 * never branch on [BookFormat] themselves for rendering.
 */
object EngineRegistry {
    private val engines: Map<BookFormat, BookEngine> = listOf(
        EpubEngine,
        TxtEngine,
        DocxEngine,
        PdfEngine,
        CbzEngine,
        ZipEngine
    ).associateBy { it.format } + (BookFormat.MARKDOWN to TxtEngine)

    fun engineFor(format: BookFormat): BookEngine =
        engines[format] ?: throw IllegalArgumentException(
            "No rendering engine for format: ${format.name}"
        )

    fun engineForFile(file: File): BookEngine =
        engineFor(BookFormat.detect(file) ?: throw IllegalArgumentException(
            "Unsupported book format: ${file.name}"
        ))
}
