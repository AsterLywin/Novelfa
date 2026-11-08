package com.novelfa.reader.parser.engine

import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.Fb2Parser
import java.io.File

/**
 * The FictionBook engine (.fb2 plain XML, .fbz = ZIP with one .fb2).
 *
 * Import is metadata-only, like EPUB/DOCX: a tag scan over the raw
 * XML ([Fb2Parser.scanMetadata]) reads the title, counts top-level
 * sections (the chapter count) and extracts the cover image's base64
 * — no DOM is built, no other binary is decoded. The full parse
 * happens at first open and corrects the row then.
 *
 * The section count is exact, not an estimate: the scan tracks
 * element depth, so a `<section>` nested inside another section is
 * not counted (the full parser splits top-level sections only). A
 * body with sections is counted by its sections; a section-less body
 * with text counts as one chapter. A document with no readable text
 * at all fails at import, like a text-less DOCX.
 */
object Fb2Engine : BookEngine {
    override val format: BookFormat = BookFormat.FB2

    private const val MAX_FBZ_ENTRY_BYTES = 64L * 1024 * 1024

    override fun metadata(
        source: File,
        displayName: String,
        scratchDir: File,
        cancelCheck: (() -> Unit)?
    ): EngineMetadata {
        val fallbackTitle = File(displayName).nameWithoutExtension
            .takeIf { it.isNotBlank() } ?: displayName
        val bytes = if (Fb2Parser.fb2DocumentSize(source) > MAX_FBZ_ENTRY_BYTES) {
            // Absurdly large for a single XML document: import with
            // the file name and an unknown count; the first open
            // parses it (or fails honestly) through the full parser.
            return EngineMetadata(fallbackTitle, null, null)
        } else {
            Fb2Parser.fb2Bytes(source)
        }
        val scan = Fb2Parser.scanMetadata(bytes)
        if (!scan.hasBodyText && scan.sectionCount == 0) {
            throw IllegalArgumentException("FB2 has no readable text: $displayName")
        }
        val title = scan.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val sections = when {
            scan.sectionCount > 0 -> scan.sectionCount
            scan.hasBodyText -> 1
            else -> 1
        }
        val cover = Fb2Parser.extractCover(bytes, scratchDir)
        return EngineMetadata(title, sections, cover)
    }

    override suspend fun open(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String,
        displayName: String?,
        onProgress: ((Int, Int) -> Unit)?,
        cancelCheck: (() -> Unit)?
    ): ParsedBook {
        return Fb2Parser.parseFile(
            source,
            outDir,
            chapterFallback,
            displayName,
            cancelCheck
        )
    }
}
