package com.novelfa.reader.parser.engine

import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.CbzBook
import java.io.File
import java.util.zip.ZipFile

/**
 * The comic engine. A CBZ is a ZIP of page images, and this engine
 * treats it that way at every stage — never extracting the archive:
 *
 * - import lists image entries only (natural order, `__MACOSX/`
 *   junk and dot-files ignored) and extracts exactly one entry,
 *   the first page, as the library cover. Previously comics got a
 *   generated typographic cover because import never looked inside
 *   the archive for art; now the first page IS the cover, as in
 *   ReadEra's library.
 * - reading decodes one page on demand from the stored archive, at
 *   display size, in the native image viewer — not through this
 *   engine's [open], which is deliberately unsupported: a comic has
 *   no chapters to convert to HTML, its pages ARE the book.
 *
 * CBR is still not supported: it needs a RAR decoder, which neither
 * the platform nor this codebase provides, and pretending otherwise
 * by renaming the extension would only produce a corrupt-book error
 * at open. That gap is stated, not papered over.
 */
object CbzEngine : BookEngine {
    override val format: BookFormat = BookFormat.CBZ

    private const val MAX_COVER_BYTES = 5L * 1024 * 1024

    override fun metadata(
        source: File,
        displayName: String,
        scratchDir: File,
        cancelCheck: (() -> Unit)?
    ): EngineMetadata {
        val entries = CbzBook.imageEntryNames(source)
        if (entries.isEmpty()) throw IllegalArgumentException(
            "Comic archive has no readable images: $displayName"
        )
        val coverFile = extractFirstImage(source, entries.first(), scratchDir)
        return EngineMetadata(
            title = File(displayName).nameWithoutExtension
                .takeIf { it.isNotBlank() } ?: displayName,
            chapterCount = entries.size,
            coverFile = coverFile
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
        throw IllegalStateException(
            "CBZ books open in the native image viewer, not through the HTML reader"
        )
    }

    private fun extractFirstImage(source: File, entryName: String, scratchDir: File): File? {
        ZipFile(source).use { zf ->
            val entry = zf.getEntry(entryName) ?: return null
            if (entry.size > MAX_COVER_BYTES) return null
            scratchDir.mkdirs()
            val dest = File(scratchDir, "cover." + entryName.substringAfterLast('.', "jpg"))
            zf.getInputStream(entry).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return dest.takeIf { it.exists() && it.length() > 0 }
        }
    }
}
