package com.novelfa.reader.parser.engine

import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.CbzBook
import java.io.File
import java.util.zip.ZipFile

/**
 * A book candidate inside a ZIP archive: the entry's path inside
 * the archive, the format it claims, and the name shown to the user
 * when a choice is needed. The display name is book data (the file
 * name inside the archive), never a translated string.
 */
data class ArchiveCandidate(
    val entryName: String,
    val format: BookFormat,
    val displayName: String
)

/**
 * What a ZIP archive actually is. A ZIP is two different things in
 * Novelfa, and confusing them was a real bug: a ZIP of images is a
 * comic, but a ZIP carrying a book is that book. Previously routing
 * sent every `.zip` to the native comic viewer, so a ZIP containing
 * an EPUB opened as a (usually empty) comic. Now the archive is
 * classified from its central directory — entry names only, no
 * extraction — before any viewer is chosen.
 */
sealed class ZipKind {
    /** Only page images: a comic. [pageCount] is the image count. */
    data class ImageOnly(val pageCount: Int) : ZipKind()

    /** Exactly one book inside: it opens without asking. */
    data class SingleBook(val candidate: ArchiveCandidate) : ZipKind()

    /** Several books inside: the engine must not guess. This is
     *  ReadEra's ARCHIVE_COLLISION, surfaced as a choice dialog
     *  instead of a silent first-file open. */
    data class MultiBook(val candidates: List<ArchiveCandidate>) : ZipKind()

    /** Neither a book nor images: import fails, it never opens. */
    data object NothingReadable : ZipKind()
}

/**
 * The archive engine. It owns the ZIP-as-container question for
 * every caller: import (what row to create), routing (which viewer),
 * and open (which book inside, extracted on demand).
 *
 * Rules, in order:
 * - book candidates win over images: a ZIP with an EPUB inside is
 *   that EPUB, even if it also carries loose images;
 * - candidates are found from entry names only, skipping
 *   `__MACOSX/` junk and dot-files, at any folder depth;
 * - a single candidate opens directly; several candidates require
 *   an explicit user choice ([openCandidate]);
 * - image-only archives delegate to [CbzEngine] and never reach
 *   the HTML reader.
 */
object ZipEngine : BookEngine {
    override val format: BookFormat = BookFormat.ZIP

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun classify(source: File): ZipKind {
        ZipFile(source).use { zf ->
            val names = zf.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .filter { !it.startsWith("__MACOSX/") }
                .filter { !it.substringAfterLast('/').startsWith(".") }
                .toList()
            val candidates = names.mapNotNull { name ->
                val format = when (name.substringAfterLast('.', "").lowercase()) {
                    "epub" -> BookFormat.EPUB
                    "docx" -> BookFormat.DOCX
                    "pdf" -> BookFormat.PDF
                    "txt", "text" -> BookFormat.TXT
                    "md", "markdown", "mdown" -> BookFormat.MARKDOWN
                    else -> return@mapNotNull null
                }
                ArchiveCandidate(name, format, name.substringAfterLast('/'))
            }.distinctBy { it.entryName }
            if (candidates.isNotEmpty()) {
                return if (candidates.size == 1) ZipKind.SingleBook(candidates[0])
                else ZipKind.MultiBook(candidates)
            }
            val images = names.count {
                it.substringAfterLast('.', "").lowercase() in imageExtensions
            }
            if (images > 0) {
                // CbzBook applies the same natural-order and junk
                // rules to the count the viewer will actually use.
                return ZipKind.ImageOnly(CbzBook.imageEntryNames(source).size)
            }
            return ZipKind.NothingReadable
        }
    }

    override fun metadata(
        source: File,
        displayName: String,
        scratchDir: File,
        cancelCheck: (() -> Unit)?
    ): EngineMetadata {
        return when (val kind = classify(source)) {
            is ZipKind.ImageOnly -> CbzEngine.metadata(source, displayName, scratchDir, cancelCheck)
            is ZipKind.SingleBook -> EngineMetadata(
                title = File(kind.candidate.displayName).nameWithoutExtension
                    .takeIf { it.isNotBlank() } ?: kind.candidate.displayName,
                chapterCount = null,
                coverFile = null
            )
            is ZipKind.MultiBook -> EngineMetadata(
                title = File(displayName).nameWithoutExtension
                    .takeIf { it.isNotBlank() } ?: displayName,
                chapterCount = null,
                coverFile = null
            )
            ZipKind.NothingReadable -> throw IllegalArgumentException(
                "ZIP archive has no readable book or images: $displayName"
            )
        }
    }

    override suspend fun open(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String,
        displayName: String?,
        onProgress: ((Int, Int) -> Unit)?,
        cancelCheck: (() -> Unit)?
    ): ParsedBook {
        return when (val kind = classify(source)) {
            is ZipKind.SingleBook -> openCandidate(
                source, kind.candidate, outDir, chapterFallback, displayName, onProgress, cancelCheck
            )
            is ZipKind.MultiBook -> throw IllegalStateException(
                "ZIP archive holds several books; a candidate must be chosen first"
            )
            is ZipKind.ImageOnly -> throw IllegalStateException(
                "Image-only ZIP archives open in the native image viewer"
            )
            ZipKind.NothingReadable -> throw IllegalArgumentException(
                "ZIP archive has no readable book or images"
            )
        }
    }

    /**
     * Opens one chosen book from inside [source]: the entry is
     * extracted once, into the book's cache directory, and then
     * handed to that book's own engine. The archive itself is
     * never fully extracted.
     */
    suspend fun openCandidate(
        source: File,
        candidate: ArchiveCandidate,
        outDir: File,
        chapterFallback: (Int) -> String,
        displayName: String?,
        onProgress: ((Int, Int) -> Unit)?,
        cancelCheck: (() -> Unit)?
    ): ParsedBook {
        val ext = candidate.entryName.substringAfterLast('.', candidate.format.extension)
        val extracted = File(outDir, "_candidate.$ext")
        if (!ZipExtract.extractOne(source, candidate.entryName, extracted)) {
            throw IllegalArgumentException("Book entry not found in archive: ${candidate.entryName}")
        }
        return EngineRegistry.engineFor(candidate.format).open(
            extracted, outDir, chapterFallback,
            displayName ?: candidate.displayName, onProgress, cancelCheck
        )
    }
}
