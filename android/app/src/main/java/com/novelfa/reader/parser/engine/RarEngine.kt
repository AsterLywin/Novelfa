package com.novelfa.reader.parser.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.CbzBook
import com.novelfa.reader.parser.RarEntry
import com.novelfa.reader.parser.RarNative
import java.io.File

/**
 * The RAR comic engine (.cbr, .rar): a RAR of page images, the
 * same book shape as CBZ, through the native unrar bridge
 * ([RarNative]) instead of the platform ZIP reader.
 *
 * - import is metadata-only, like [Fb2Engine]: the entry list
 *   comes from [RarNative.nListEntries], the page count is the
 *   image-entry count, and exactly one entry — the first page —
 *   is extracted as the library cover. The archive is never
 *   extracted at import.
 * - reading decodes one page on demand from the stored archive,
 *   in the native image viewer — not through this engine's
 *   [open], which is deliberately unsupported, exactly like
 *   [CbzEngine]: a comic has no chapters to convert to HTML,
 *   its pages ARE the book. The viewer source
 *   (reader/RarNativeSource) serves pages through the same
 *   decode pipeline [CbzNativeSource] uses.
 * - page rules are the CBZ rules, reused not duplicated:
 *   only [CbzBook.isImageEntry] entries count, natural order
 *   ([CbzBook.compareNatural]), foldered archives split into
 *   chapters ([CbzBook.chaptersForEntries]), same 64 MiB
 *   per-entry cap as [CbzBook.readEntryBytes].
 * - a RAR with zero image pages is not a book: import rejects
 *   it with the existing no-readable-sections path.
 * - when the native lib is absent ([RarNative.available] is
 *   false) every function here throws IllegalStateException
 *   with a stable internal message; the importer maps that to
 *   the localized "RAR engine missing" string, and no other
 *   format is affected. A null listing, a null entry extraction
 *   or a ZIP wearing a .cbr name are clean import errors the
 *   same way, never crashes.
 */
object RarEngine : BookEngine {
    override val format: BookFormat = BookFormat.CBR

    private const val MAX_COVER_BYTES = 5L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** True only when the native RAR engine is loaded. */
    fun isAvailable(): Boolean = RarNative.available

    /**
     * Image entry names of a RAR listing, in reading order:
     * image entries only ([CbzBook.isImageEntry] — directories,
     * `__MACOSX/` junk and dot-files ignored), natural order.
     * Pure Kotlin over the listing — no native call — so it is
     * unit-testable with fake [RarEntry] lists.
     */
    fun imageEntryNames(entries: List<RarEntry>): List<String> =
        entries.filter { !it.isDirectory && CbzBook.isImageEntry(it.name) }
            .map { it.name }
            .sortedWith(CbzBook::compareNatural)

    /** Chapters for a RAR listing, from its image entries alone. */
    fun chapters(entries: List<RarEntry>): List<CbzBook.CbzChapter> =
        CbzBook.chaptersForEntries(imageEntryNames(entries))

    /**
     * The RAR version of [source] from the native bridge
     * (4, 5, or 0 = unknown / not a RAR), or 0 when the engine
     * is absent. Never throws: a missing lib is not a crash.
     */
    fun rarVersion(source: File): Int =
        if (!RarNative.available) 0
        else runCatching { RarNative.nGetRarVersion(source.absolutePath) }.getOrDefault(0)

    /**
     * One entry's bytes, or null when the engine is absent, the
     * native call fails (wrong password, corrupt entry, solid
     * archive needing an earlier entry) or the entry exceeds
     * [MAX_ENTRY_BYTES] — the same 64 MiB cap
     * [CbzBook.readEntryBytes] applies, so one hostile entry
     * cannot exhaust memory. The [password] is a transient
     * String for the native call only, never stored.
     */
    fun readEntryBytes(
        source: File,
        entry: RarEntry,
        password: String? = null,
        maxBytes: Long = MAX_ENTRY_BYTES
    ): ByteArray? {
        if (!RarNative.available) return null
        val bytes = runCatching {
            RarNative.nExtractEntryBytes(
                source.absolutePath, password, entry.name,
                entry.headerOffset, entry.isSolid
            )
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        return bytes
    }

    /**
     * The entry listing, or null when the engine is absent or
     * the native call fails. Null is not an empty archive: the
     * caller treats it as an unreadable file, not a book with
     * no pages.
     */
    fun listEntries(source: File): List<RarEntry>? {
        if (!RarNative.available) return null
        return runCatching { RarNative.nListEntries(source.absolutePath)?.toList() }.getOrNull()
    }

    override fun metadata(
        source: File,
        displayName: String,
        scratchDir: File,
        cancelCheck: (() -> Unit)?
    ): EngineMetadata {
        if (!RarNative.available) throw IllegalStateException(
            "RAR engine unavailable: $displayName"
        )
        // A ZIP wearing a .cbr name must never reach the RAR
        // bridge: the importer reports it as a ZIP (rename to
        // .cbz) instead of a corrupt RAR. Anything that is not
        // RAR at all fails here the same way.
        if (CbzBook.archiveKind(source) != "RAR") throw IllegalArgumentException(
            "Not a RAR archive: $displayName"
        )
        val entries = listEntries(source)
            ?: throw IllegalArgumentException("Cannot list RAR archive: $displayName")
        val pages = imageEntryNames(entries)
        if (pages.isEmpty()) throw IllegalArgumentException(
            "Comic archive has no readable images: $displayName"
        )
        val coverFile = extractFirstImage(source, entries, pages, scratchDir)
        return EngineMetadata(
            title = File(displayName).nameWithoutExtension
                .takeIf { it.isNotBlank() } ?: displayName,
            chapterCount = pages.size,
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
            "CBR books open in the native image viewer, not through the HTML reader"
        )
    }

    /**
     * The cover is the first READABLE page, not necessarily the
     * first page — the same rule as [CbzEngine]: a poisoned
     * first page (a corrupt entry, a solid entry the bridge
     * cannot serve) must not cost the cover when the next page
     * reads fine, and a book whose every page is unreadable
     * still imports — with a generated cover — instead of
     * failing the whole import. Import carries no password, so
     * a password-protected archive yields no cover here; the
     * password is asked at open, and the first-open backfill
     * still cannot extract one without it either.
     */
    private fun extractFirstImage(
        source: File,
        entries: List<RarEntry>,
        pageNames: List<String>,
        scratchDir: File
    ): File? {
        val byName = entries.associateBy { it.name }
        for (name in pageNames) {
            val entry = byName[name] ?: continue
            val bytes = readEntryBytes(source, entry) ?: continue
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
     * caller falls back to the generated cover. Same shape as
     * the CBZ cover path; kept here because that helper is
     * private to [CbzEngine].
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
