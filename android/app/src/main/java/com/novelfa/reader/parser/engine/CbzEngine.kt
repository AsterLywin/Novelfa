package com.novelfa.reader.parser.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.CbzBook
import java.io.File
import java.util.zip.ZipEntry
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
            if (entry.size > MAX_COVER_BYTES) {
                // Oversized first page (a high-res PNG): downsample
                // it instead of giving up the cover — a big page
                // is still the cover, just stored smaller. The
                // cap used to return null here, so the book fell
                // back to a generated cover with no trace of why.
                return downsampledCover(zf, entry, scratchDir)
            }
            scratchDir.mkdirs()
            val dest = File(scratchDir, "cover." + entryName.substringAfterLast('.', "jpg"))
            zf.getInputStream(entry).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return dest.takeIf { it.exists() && it.length() > 0 }
        }
    }

    /**
     * Downsamples one oversized archive entry to a <=1200px
     * cover (JPEG 85). Bounds are read first so the sample size
     * is known before any pixels are decoded — the full bitmap
     * is never held in memory. Any failure returns null and the
     * caller falls back to the generated cover.
     */
    private fun downsampledCover(zf: ZipFile, entry: ZipEntry, scratchDir: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zf.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        var sample = 1
        while (width / sample > 1200 || height / sample > 1200) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = zf.getInputStream(entry).use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        scratchDir.mkdirs()
        val dest = File(scratchDir, "cover.jpg")
        try {
            dest.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            return dest.takeIf { it.exists() && it.length() > 0 }
        } finally {
            bitmap.recycle()
        }
    }
}
