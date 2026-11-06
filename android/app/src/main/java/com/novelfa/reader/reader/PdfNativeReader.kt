package com.novelfa.reader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.widget.ImageView
import java.nio.ByteBuffer
import android.os.ParcelFileDescriptor
import com.novelfa.reader.data.SeekableZip
import java.io.ByteArrayInputStream
import java.io.InputStream
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import com.novelfa.reader.parser.CbzBook
import com.novelfa.reader.parser.CbzBook.CbzChapter
import java.io.Closeable
import java.io.File
import net.lingala.zip4j.ZipFile
import kotlinx.coroutines.Dispatchers

/**
 * Native page-book reader (PDF since 2026-10-29, CBZ/ZIP manhwa
 * since 2026-10-29): the fix for the PDF report, extended to
 * image books on the same engine.
 *
 * Why this exists: the old path converted a PDF to EPUB-like XHTML
 * (hand-rolled PdfParser for text, JPEG pre-renders for scans) and
 * only then showed it in the EPUB WebView. That made first open pay
 * for the WHOLE book, lost the original page layout, and capped
 * scanned books at 1000 pages. The readers compared in the report
 * all do the opposite: a book stays in its own format, import only
 * registers it, and the viewer renders one original page at a time,
 * on demand. CBZ is the same idea with a different container: a
 * CBZ is a ZIP of page images ([CbzBook]), so a manhwa page is
 * decoded from the archive when it is scrolled to, never extracted
 * at import.
 *
 * Shared rules for both formats:
 * - No text extraction, no XHTML, no pre-rendered JPEG files. A page
 *   is rendered only when its list/pager item is composed, so
 *   opening a 500-page book costs one page, not 500.
 * - No 1000-page cap: nothing is pre-rendered or stored, so a long
 *   book is no more expensive to open than a short one. Memory is
 *   bounded by the visible window, not by the book: each page item
 *   owns its bitmap and recycles it when it leaves composition.
 * - One original page is never cut: each item is exactly one page
 *   at its own aspect ratio, fit to the screen width by default.
 *   Zoom re-renders that page at a larger width, it never reflows
 *   text. In vertical mode the strip is ComicScreen's: every page
 *   fills the strip width, one below the other, no horizontal
 *   scroll — zoom and pan live on the page itself (pinch, see
 *   ZoomablePageContainer), one independent zoom per page box.
 *   In paged mode horizontal drag is the page turn at 1x; while
 *   a page is zoomed the drag pans that page only and the pager
 *   is locked, and the zoom resets when the displayed page
 *   changes.
 * - A page that fails to render fails alone (placeholder + retry),
 *   it never kills the book.
 * - Progress reuses the existing book row: lastChapterIndex
 *   IS the page index and totalChapters IS the page
 *   count, so the library progress bar works unchanged. Lazy PDF
 *   import left totalChapters at 0; the first native open fills it
 *   in. CBZ import already knows the count from the archive listing.
 * - Modes follow the GLOBAL reading-mode preference (the same
 *   paged/continuous toggle the EPUB reader uses): paged =
 *   HorizontalPager, one original page per screen, vertical scroll
 *   inside a tall page; continuous = LazyColumn. Tapping the mode
 *   button here changes the same preference the EPUB reader reads.
 *
 * Deliberate limits, stated not hidden:
 * - No text search, selection or annotation yet for native
 *   books. Those need the text layer on top of this viewer
 *   (the FPDFText bridge exists for it); they belong there,
 *   not bolted onto the bitmap path.
 * - XP credit exists for native books (page-based, same
 *   visited + dwell + once rules as the text reader, dual
 *   pairs credited per page shown); a source without text
 *   data credits the 1 XP floor per page.
 * - CBZ chapter folders become a chapter list (see
 *   [CbzBook.chaptersForEntries]): the top bar offers it as a
 *   one-tap jump to a chapter's first page, and a flat archive
 *   (no folders) shows no chapter control at all, reading
 *   exactly as before.
 * - CBR (RAR) is not supported: it needs a RAR decoder.
 * - Password PDFs: import still stores copy-mode PDFs
 *   decrypted (so most PDFs never prompt), but a PDF that
 *   reaches this viewer still encrypted — an in-place or
 *   directly opened file — is NOT a dead end anymore: the
 *   viewer asks for its password here (see PdfiumNativeSource
 *   .needsPassword and the viewer dialog), session-only,
 *   never persisted, exactly like the ZIP password.
 */

/** One page source for the native viewer, PDF or CBZ alike. */
internal interface NativePageSource : Closeable {
    val pageCount: Int

    /** Chapter list for foldered books; empty for flat books and PDFs. */
    val chapters: List<CbzChapter>
        get() = emptyList()

    /** Renders one page to a fresh bitmap the CALLER owns. */
    fun renderPage(index: Int, targetWidthPx: Int): Bitmap

    /**
     * An animated drawable for one page, or null. GIF and
     * animated WebP pages have one: BitmapFactory decodes either
     * to its FIRST frame only, so an animated page needs its own
     * path — decoded with ImageDecoder into an
     * AnimatedImageDrawable that the viewer plays in an ImageView.
     * A STILL WebP returns null: ImageDecoder would hand back a
     * plain BitmapDrawable for it, and taking the drawable path
     * would silently drop crop/resize/sharpen for a still page.
     * The Animatable check is what keeps the two apart. Every
     * other format returns null and renders through [renderPage]
     * as before. Default null, so existing sources keep
     * compiling unchanged.
     */
    fun animatedDrawable(index: Int): Drawable? = null

    /**
     * Whether one page is animated (GIF/WebP). The dual-page
     * pairing needs it without decoding: a split page is two
     * halves of one still image, and an animation cannot be
     * halved that way. Default false; CBZ overrides it from the
     * entry extension, the same check [animatedDrawable] uses.
     */
    fun isAnimatedPage(index: Int): Boolean = false

    /**
     * Native pixel size of one page (width to height), without
     * decoding its pixels — a bounds-only decode. Used to lay
     * out a book's pages at one unified display width. Sources
     * that render at a caller-chosen width (PDF) return null:
     * they have no native size to unify. Default null, so
     * existing sources keep compiling unchanged.
     */
    fun nativePageSize(index: Int): Pair<Int, Int>? = null

    /**
     * A small thumbnail of one page (about [targetWidthPx]
     * wide, height capped at 1200px), for the scrub preview and
     * the page-overview grid. Cheap by construction: a sampled
     * decode, never the full pipeline (no crop/sharpen/filters)
     * — a thumbnail that decoded a page per scrub step would
     * stall the slider, and a grid of full pages would OOM.
     * Default null (no thumbnail); sources override it.
     */
    fun thumbnail(index: Int, targetWidthPx: Int): Bitmap? = null

    /**
     * Word count of one page, for native XP credit only.
     * Default 0: a source without text data still credits the
     * 1 XP floor per page. PDFium overrides it from the
     * FPDFText bridge when the native library is present.
     */
    fun pageWords(index: Int): Int = 0
}

internal class PdfNativeRenderer(pdfFile: File) : NativePageSource {
    private val lock = Any()
    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(pfd)

    override val pageCount: Int
        get() = renderer.pageCount

    /**
     * Renders one page to a fresh bitmap the CALLER owns (and must
     * recycle). PdfRenderer is single-threaded, so every render is
     * serialized on [lock]; callers must call from Dispatchers.IO,
     * never from the main thread. Height is capped at 8192px with
     * the width scaled down to match, so an absurdly tall page
     * cannot allocate an unbounded bitmap.
     */
    override fun renderPage(index: Int, targetWidthPx: Int): Bitmap {
        synchronized(lock) {
            if (index < 0 || index >= renderer.pageCount) {
                throw IndexOutOfBoundsException("PDF page $index out of range")
            }
            renderer.openPage(index).use { page ->
                var width = targetWidthPx.coerceAtLeast(1)
                var height = (page.height * (width.toFloat() / page.width.toFloat()))
                    .toInt().coerceAtLeast(1)
                if (height > MAX_RENDER_HEIGHT) {
                    val shrink = MAX_RENDER_HEIGHT.toFloat() / height.toFloat()
                    height = MAX_RENDER_HEIGHT
                    width = (width * shrink).toInt().coerceAtLeast(1)
                }
                val bitmap = Bitmap.createBitmap(
                    width, height, Bitmap.Config.ARGB_8888
                )
                try {
                    page.render(
                        bitmap, null, null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                } catch (e: Exception) {
                    bitmap.recycle()
                    throw e
                }
                return bitmap
            }
        }
    }

    override fun thumbnail(index: Int, targetWidthPx: Int): Bitmap? {
        // PdfRenderer renders at the requested width, so a
        // small request IS a cheap thumbnail — no sampling
        // dance needed (unlike CBZ, which decodes pixels).
        return runCatching { renderPage(index, targetWidthPx) }.getOrNull()
    }

    override fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    companion object {
        private const val MAX_RENDER_HEIGHT = 8192
    }
}

/**
 * CBZ/ZIP page source: decodes one archive image on demand, at
 * the image's NATIVE size and FULL colour — no sampling down
 * to the screen width and no 16-bit colour quantisation (see
 * renderPage). Pages are allowed up to 16384px tall / 8192px
 * wide before sampling down — a tall strip must stay readable,
 * not be shrunk to a thumbnail by a PDF-sized cap. Sampling is
 * a power of two (the decoder's efficient path).
 *
 * Colour: ARGB_8888 first (the image's own depth). RGB_565 is
 * only the fallback for a decoder refusing a config (WebP in
 * particular used to return null for 565) or an out-of-memory
 * on a giant page — never the happy path, because 565's
 * banding on gradients is plainly visible on manhwa art.
 *
 * Backend: zip4j (Apache-2.0, GPL-compatible), not
 * java.util.zip — the JDK reader cannot decrypt, so a
 * password-protected archive used to fail every page with a
 * corrupt-image error. zip4j reads plain archives exactly the
 * same way and additionally decrypts (traditional ZipCrypto
 * and AES) when a password is supplied. Entry NAMES still come
 * from [CbzBook.imageEntryNames]: ZIP encrypts file data, not
 * the central directory, so names list fine without a password.
 * Second backend (step 5): [SeekableZip] for in-place
 * `content://` archives — pages stream from the provider's
 * descriptor with no working copy. It serves only unencrypted
 * STORED/DEFLATED entries; anything else falls back to the
 * zip4j backend over a resolved working copy.
 */
/**
 * Entry backend for the comic viewer. zip4j is the default (it
 * decrypts password-protected archives); [SeekableZip] is the
 * in-place `content://` backend (step 5: pages stream from the
 * provider's descriptor with no working copy). Both serve the
 * same calls, so the decode pipeline below never learns which
 * one it is reading from. The file-backed store falls back to
 * [CbzBook.openEntryStream] when zip4j refuses an archive (one
 * entry with an unusual compression method makes zip4j fail the
 * whole header read), so the good pages of such an archive
 * still decode and only the one page fails.
 */
internal interface CbzEntryStore : Closeable {
    fun openEntry(name: String): InputStream?
    fun firstEncryptedName(): String?
}

internal class ZipFileStore(
    private val zipFile: File,
    password: CharArray?
) : CbzEntryStore {
    private val zip = ZipFile(zipFile).apply {
        if (password != null) setPassword(password)
    }

    override fun openEntry(name: String): InputStream? {
        // zip4j first: it alone decrypts password-protected
        // archives. But its header read throws for the whole
        // archive when any entry uses an unusual compression
        // method — then the tolerant reader serves the good
        // pages (STORED/DEFLATED) one by one instead of failing
        // every page of the book.
        val viaZip4j = runCatching {
            val header = zip.fileHeaders.firstOrNull { it.fileName == name }
                ?: return@runCatching null
            zip.getInputStream(header)
        }.getOrNull()
        if (viaZip4j != null) return viaZip4j
        return runCatching { CbzBook.openEntryStream(zipFile, name) }.getOrNull()
    }

    override fun firstEncryptedName(): String? =
        runCatching {
            zip.fileHeaders.firstOrNull { it.isEncrypted }?.fileName
        }.getOrNull()

    override fun close() {
        // No-op on purpose: zip4j parses the central directory
        // at construction (its handle is closed after parsing)
        // and opens a fresh handle per stream, so there is no
        // persistent handle to close — unlike java.util.zip.
    }
}

internal class SeekableEntryStore(
    private val seekable: SeekableZip
) : CbzEntryStore {
    override fun openEntry(name: String): InputStream? =
        runCatching { seekable.openEntry(name) }.getOrNull()

    // The caller only builds this store when the archive has no
    // encrypted entries (they need zip4j), so this is constant.
    override fun firstEncryptedName(): String? = null

    override fun close() {
        // No-op on purpose: the route owns the provider
        // descriptor (it may build several sources over one
        // descriptor), so a source must never close it out
        // from under its siblings. PdfReaderRoute closes the
        // descriptor when the viewer leaves.
    }
}

internal class CbzNativeSource(
    private val store: CbzEntryStore,
    private val entries: List<String>
) : NativePageSource {
    constructor(zipFile: File, password: CharArray?) : this(
        ZipFileStore(zipFile, password),
        CbzBook.imageEntryNames(zipFile)
    )

    constructor(seekable: SeekableZip) : this(
        SeekableEntryStore(seekable),
        seekable.imageEntryNames()
    )

    private val lock = Any()

    // AVIF pages (native libavif engine, see AvifDecoder): the
    // BitmapFactory path cannot decode them on any API level —
    // platform AVIF exists only from API 31 and the app's minSdk
    // is 29 — so AVIF entries take their own branch in all three
    // decode functions below, reading the entry bytes once and
    // letting the engine validate (extension alone is not proof:
    // a misnamed file must fail the page, not crash it).
    private fun isAvifEntry(name: String): Boolean =
        name.endsWith(".avif", ignoreCase = true)

    private fun avifBytes(name: String): ByteArray? =
        runCatching {
            store.openEntry(name)?.use { it.readBytes() }
        }.getOrNull()

    /**
     * Validates the password by fully reading the first
     * encrypted entry: zip4j verifies an AES password only once
     * the entry is fully read (the auth code sits at the end),
     * so a partial read cannot validate it. Unencrypted
     * archives always pass. Called on IO by the viewer before
     * the source is shown, so a wrong password asks again
     * instead of failing every page.
     */
    fun probePassword(): Boolean {
        synchronized(lock) {
            val encrypted = store.firstEncryptedName() ?: return true
            return runCatching {
                store.openEntry(encrypted)?.use { it.readBytes() }
            }.isSuccess
        }
    }

    override val pageCount: Int
        get() = entries.size

    override val chapters: List<CbzChapter> =
        CbzBook.chaptersForEntries(entries)

    override fun nativePageSize(index: Int): Pair<Int, Int>? {
        synchronized(lock) {
            if (index < 0 || index >= entries.size) return null
            if (isAvifEntry(entries[index])) {
                val bytes = avifBytes(entries[index]) ?: return null
                return AvifDecoder.decodeBounds(bytes)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                store.openEntry(entries[index])?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            // Display size, not file size: a 90/270 EXIF rotation
            // swaps width and height, and the dual-page split
            // decision (landscape or not) must see the rotated
            // shape, not the stored one.
            val degrees = exifDegrees(entries[index])
            return if (degrees == 90 || degrees == 270) {
                bounds.outHeight to bounds.outWidth
            } else {
                bounds.outWidth to bounds.outHeight
            }
        }
    }

    override fun isAnimatedPage(index: Int): Boolean {
        if (index < 0 || index >= entries.size) return false
        val name = entries[index]
        return name.endsWith(".gif", ignoreCase = true) ||
            name.endsWith(".webp", ignoreCase = true)
    }

    override fun animatedDrawable(index: Int): Drawable? {
        synchronized(lock) {
            if (index < 0 || index >= entries.size) return null
            // Extension check only: GIF and WebP entries are the
            // cases where the bitmap path loses information (all
            // frames but the first), so they alone try the
            // drawable path — and only if the decoded drawable
            // actually animates (see the interface doc: a still
            // WebP falls through to the bitmap path with its
            // crop/resize/sharpen intact).
            if (!isAnimatedPage(index)) return null
            return runCatching {
                val bytes = store.openEntry(entries[index])?.use { it.readBytes() }
                    ?: return@runCatching null
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                )
            }.getOrNull()?.takeIf { it is Animatable }
        }
    }

    /**
     * EXIF rotation for one entry, in degrees (0/90/180/270).
     * ComicScreen honours EXIF orientation in its tile decoder;
     * without it a sideways camera photo in a CBZ shows
     * sideways. Any failure (not a JPEG, no EXIF, truncated
     * header) means upright — a bad header must never fail the
     * page. Each call needs its own stream: a consumed stream
     * cannot be read twice.
     */
    private fun exifDegrees(name: String): Int {
        return runCatching {
            store.openEntry(name)?.use { stream ->
                ExifRotation.degreesFromStream(stream)
            } ?: 0
        }.getOrDefault(0)
    }

    override fun thumbnail(index: Int, targetWidthPx: Int): Bitmap? {
        synchronized(lock) {
            if (index < 0 || index >= entries.size) return null
            if (isAvifEntry(entries[index])) {
                val bytes = avifBytes(entries[index]) ?: return null
                val decoded = AvifDecoder.decode(bytes) ?: return null
                // Same caps as the sampled path below: width near
                // the target (within 2x, so the scrub preview
                // stays crisp) and height under the 1200px cap.
                // libavif has no sampled decode, so the full
                // frame decodes first and scales down here.
                if (decoded.width <= targetWidthPx * 2 &&
                    decoded.height <= 1200
                ) {
                    return decoded
                }
                val scale = minOf(
                    targetWidthPx * 2.0 / decoded.width,
                    1200.0 / decoded.height
                )
                val tw = (decoded.width * scale).toInt().coerceAtLeast(1)
                val th = (decoded.height * scale).toInt().coerceAtLeast(1)
                val scaled = resizeBox(decoded, tw, th)
                if (scaled !== decoded) decoded.recycle()
                return scaled
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                store.openEntry(entries[index])?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            // Sample down until the width is near the target
            // (within 2x, so the scrub preview stays crisp)
            // AND the height fits the 1200px cap — a tall strip
            // sampled by width alone would still be a 480x4800
            // bitmap, and a grid of those is an OOM.
            var sample = 1
            while ((bounds.outWidth / sample > targetWidthPx * 2 ||
                bounds.outHeight / sample > 1200) && sample < 64
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferQualityOverSpeed = true
            }
            val decoded = runCatching {
                store.openEntry(entries[index])?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }.getOrNull() ?: return null
            val degrees = exifDegrees(entries[index])
            if (degrees == 0) return decoded
            val rotated = rotateBitmap(decoded, degrees)
            if (rotated !== decoded) decoded.recycle()
            return rotated
        }
    }

    override fun renderPage(index: Int, targetWidthPx: Int): Bitmap {
        // Lock scope, narrowed for paging speed: zip4j's
        // ZipFile is not thread-safe, so every stream open
        // and read stays under [lock] — but the pixel decode
        // (the slow part: JPEG decode, libaom AVIF decode)
        // must NOT be under it. With the whole render
        // serialized, a fling across pages queued every
        // page's decode behind the previous one, which is
        // the paged-mode lag: the target page waited for
        // pages being skipped. So the entry bytes are read
        // once under the lock, and everything below decodes
        // from memory, in parallel across pages on
        // Dispatchers.IO. The other methods (thumbnail,
        // nativePageSize, animatedDrawable) still hold the
        // lock for their whole stream use, so no zip access
        // ever runs outside it.
        if (index < 0 || index >= entries.size) {
            throw IndexOutOfBoundsException("Comic page $index out of range")
        }
        val entryName = entries[index]
        val bytes = synchronized(lock) {
            runCatching {
                store.openEntry(entryName)?.use { it.readBytes() }
            }.getOrNull()
        } ?: throw IllegalArgumentException("Unreadable comic image: $entryName")
        if (isAvifEntry(entryName)) {
                val decoded = AvifDecoder.decode(bytes)
                    ?: throw IllegalArgumentException("Unreadable comic image: ${entries[index]}")
                // Ceiling: the same 8192 x 16384 cap as the
                // sampled path below. libavif has no sampled
                // decode, so an over-ceiling AVIF decodes full
                // and scales down here; the transient full-size
                // allocation is the v1 trade-off (an OOM lands
                // on the page's failure placeholder like any
                // other undecodable page).
                if (decoded.width <= MAX_DECODE_WIDTH &&
                    decoded.height <= MAX_DECODE_HEIGHT
                ) {
                    return decoded
                }
                val scale = minOf(
                    MAX_DECODE_WIDTH.toDouble() / decoded.width,
                    MAX_DECODE_HEIGHT.toDouble() / decoded.height
                )
                val tw = (decoded.width * scale).toInt().coerceAtLeast(1)
                val th = (decoded.height * scale).toInt().coerceAtLeast(1)
                val scaled = resizeBox(decoded, tw, th)
                if (scaled !== decoded) decoded.recycle()
                return scaled
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IllegalArgumentException("Unreadable comic image: $entryName")
            }
            // EXIF rotation, read BEFORE the sample loop: a
            // 90/270 rotation swaps the dimensions the ceiling
            // is checked against, or an ultra-wide page stored
            // sideways would sample down for the wrong axis.
            // The rotation itself is applied to the decoded
            // bitmap below, so the crop/resize/sharpen pipeline
            // downstream always sees the upright page.
            // EXIF from the same bytes, not a second stream:
            // the bytes are already in memory, and a fresh
            // stream would need the lock again.
            val exifDegrees = runCatching {
                ExifRotation.degreesFromStream(ByteArrayInputStream(bytes))
            }.getOrDefault(0)
            val effWidth =
                if (exifDegrees == 90 || exifDegrees == 270) bounds.outHeight else bounds.outWidth
            val effHeight =
                if (exifDegrees == 90 || exifDegrees == 270) bounds.outWidth else bounds.outHeight
            // Native-size decode: pages used to be sampled down
            // to the SCREEN width (a 1600px page on a 1080px
            // screen decoded at 800px — half its pixels thrown
            // away before display). Now the bitmap keeps the
            // image's own dimensions and [targetWidthPx] is
            // ignored for comics. The ONLY sampling left is the
            // safety ceiling: a page taller than 16384px or
            // wider than 8192px would blow the GPU texture
            // limit and the heap, so those — and only those —
            // sample down just enough to fit. Display scaling
            // (fit-width in paged mode, zoom, the unified-width
            // slot in continuous mode) happens in the UI layer
            // on the full bitmap, never by throwing pixels away
            // at decode time.
            var sample = 1
            while (effWidth / sample > MAX_DECODE_WIDTH ||
                effHeight / sample > MAX_DECODE_HEIGHT
            ) {
                sample *= 2
            }
            // Full-colour decode first: RGB_565 (16-bit: 5/6/5
            // bits per channel) used to be the first try for
            // half the memory, but it visibly degrades the art
            // — colour banding on gradients and washed-out
            // pastels, exactly the "much worse than the
            // original" a device report showed on manhwa pages.
            // A JPEG/PNG that decodes fine as 565 stays 565, so
            // the old order never even reached the ARGB
            // fallback. Now ARGB_8888 (8 bits per channel, the
            // image's own colour depth) is the decode, and
            // RGB_565 is only the fallback: a null result (a
            // decoder refusing a config, WebP in particular) or
            // an out-of-memory on a giant page retries as 565
            // from the same bytes before giving up. The extra
            // memory is bounded:
            // page bitmaps are owned per visible item and
            // recycled when the item leaves composition, so
            // only the visible window's pages are alive.
            // Decodes from the bytes, not a stream: a consumed
            // stream cannot be decoded twice, so the old code
            // re-opened the zip for every attempt — and held
            // the lock while doing it. Bytes decode identically
            // and retry for free, with no zip access at all.
            fun decodeAs(config: Bitmap.Config, sampleSize: Int): Bitmap? {
                return runCatching {
                    BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = config
                            // Quality over speed: ComicScreen's
                            // tile decoder sets this, and it is
                            // the one flag the JPEG decoder
                            // actually listens to — fancy
                            // chroma upsampling and a more
                            // accurate IDCT instead of the fast
                            // approximations. On line art and
                            // flat manhwa colour the difference
                            // is visible; the cost is decode
                            // time on the IO thread, which is
                            // where this already runs.
                            inPreferQualityOverSpeed = true
                        }
                    )
                }.getOrNull()
            }
            // Out-of-memory retry, ComicScreen's way: a decode
            // that dies on memory is retried at double the
            // sample size (a quarter of the pixels) instead of
            // failing the page outright. runCatching swallows
            // the OOM (an Error, but a caught one — the heap is
            // still usable, just full), so a null here means
            // "try smaller", not "give up". Three rounds bound
            // the work: a genuinely corrupt image fails all six
            // attempts fast and still lands on the page's
            // failure placeholder with a retry button, never a
            // dead book. A decoded bitmap is never the fallback
            // here: Novelfa's per-page failure screen (message +
            // retry) already IS the graceful fallback, and it
            // says more than a blank page would.
            var bitmap: Bitmap? = null
            var attemptSample = sample
            for (round in 0 until 3) {
                bitmap = decodeAs(Bitmap.Config.ARGB_8888, attemptSample)
                    ?: decodeAs(Bitmap.Config.RGB_565, attemptSample)
                if (bitmap != null) break
                attemptSample *= 2
            }
            val decoded = bitmap
                ?: throw IllegalArgumentException("Unreadable comic image: $entryName")
            if (exifDegrees == 0) return decoded
            val rotated = rotateBitmap(decoded, exifDegrees)
            if (rotated !== decoded) decoded.recycle()
            return rotated
    }

    override fun close() {
        // The store decides: ZipFileStore is a no-op (zip4j opens
        // a fresh handle per stream), and SeekableEntryStore is
        // also a no-op on purpose — the route owns the provider
        // descriptor (it may build several sources over one
        // descriptor), so a source must never close it out from
        // under its siblings.
        runCatching { store.close() }
    }

    companion object {
        private const val MAX_DECODE_HEIGHT = 16384
        private const val MAX_DECODE_WIDTH = 8192
    }
}

