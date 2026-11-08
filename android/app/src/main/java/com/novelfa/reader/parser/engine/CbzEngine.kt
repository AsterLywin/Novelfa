package com.novelfa.reader.parser.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.CbzBook
import java.io.File

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
 * - CBR (RAR) is the same book shape through the native unrar
 *   bridge instead of the platform ZIP reader: [RarEngine]
 *   reuses this object's page rules (image filter, natural
 *   order, chapter split, cover downsample) and only the entry
 *   bytes come from JNI.
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
        val coverFile = extractFirstImage(source, entries, scratchDir)
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

    /**
     * The cover is the first READABLE page, not necessarily the
     * first page: a poisoned first page (an unusual compression
     * method, a corrupt entry) must not cost the cover when the
     * next page reads fine, and a book whose every page is
     * unreadable still imports — with a generated cover — instead
     * of failing the whole import.
     */
    private fun extractFirstImage(
        source: File,
        entryNames: List<String>,
        scratchDir: File
    ): File? {
        for (name in entryNames) {
            val bytes = runCatching { CbzBook.readEntryBytes(source, name) }.getOrNull()
                ?: continue
            if (bytes.isEmpty()) continue
            scratchDir.mkdirs()
            if (bytes.size <= MAX_COVER_BYTES) {
                val dest = File(scratchDir, "cover." + name.substringAfterLast('.', "jpg"))
                runCatching { dest.writeBytes(bytes) }.getOrNull() ?: continue
                val done = dest.takeIf { it.exists() && it.length() > 0 } ?: continue
                return done
            }
            val downsampled = downsampledCover(bytes, scratchDir)
            if (downsampled != null) return downsampled
        }
        return null
    }

    /**
     * Downsamples one oversized page's bytes to a <=1200px
     * cover (JPEG 85). Bounds are read first so the sample size
     * is known before any pixels are decoded — the full bitmap
     * is never held in memory. Any failure returns null and the
     * caller falls back to the generated cover.
     */
    private fun downsampledCover(bytes: ByteArray, scratchDir: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        var sample = 1
        while (width / sample > 1200 || height / sample > 1200) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
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
