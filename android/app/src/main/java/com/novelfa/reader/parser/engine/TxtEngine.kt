package com.novelfa.reader.parser.engine

import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookConverter
import com.novelfa.reader.parser.BookFormat
import java.io.File

/**
 * The plain-text / Markdown engine. Unlike EPUB and DOCX, TXT and
 * Markdown are NOT made lazy at import, deliberately: their parse is
 * a single pass over plain text, and its output IS the reader's open
 * cache — parsing at import is not double work, it is the open cache
 * being built once. The engine separation is still real: import and
 * open both go through this engine, and no other format's code path
 * can reach the text parser.
 *
 * ReadEra points applied here, in [com.novelfa.reader.parser.TextBookParser]:
 * Markdown keeps its CommonMark-subset rendering (headings, fenced
 * code, nested lists, blockquotes, tables, inline code/bold/italic/
 * strike/links) with a table of contents built from its headings, and
 * text decoding covers BOM-marked UTF-8/UTF-16 plus Windows-1256
 * Persian, falling back to Windows-1252 when no Arabic-script band
 * is present (the old fallback always chose 1256, which mangles
 * Western-European text bytes).
 */
object TxtEngine : BookEngine {
    // TXT and Markdown share one engine; the registry maps both
    // formats to it, and [open] re-dispatches on the file itself.
    override val format: BookFormat = BookFormat.TXT

    override fun metadata(
        source: File,
        displayName: String,
        scratchDir: File,
        cancelCheck: (() -> Unit)?
    ): EngineMetadata {
        // A text file carries no container metadata: its title is
        // its file name and its section count is only known after
        // the (single-pass) parse, which import performs through
        // [open] into the open cache. Callers must not treat the
        // null count as zero chapters.
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
        val detected = BookFormat.detect(source)
        require(detected == BookFormat.TXT || detected == BookFormat.MARKDOWN) {
            "TxtEngine cannot open format: ${detected?.name}"
        }
        return BookConverter.parse(source, outDir, chapterFallback, displayName, onProgress)
    }
}
