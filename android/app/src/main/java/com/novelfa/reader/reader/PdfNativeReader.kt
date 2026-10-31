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
import java.io.ByteArrayOutputStream
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.data.CoverBackfill
import com.novelfa.reader.data.InPlaceSource
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.ReaderExtrasStore
import com.novelfa.reader.data.sha256OfFile
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.CbzBook
import com.novelfa.reader.parser.CbzBook.CbzChapter
import com.novelfa.reader.parser.engine.ZipEngine
import com.novelfa.reader.parser.engine.ZipKind
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind
import com.novelfa.reader.theme.PrefsStoreRankPrefs
import com.novelfa.reader.theme.XpContentKeyResolver
import com.novelfa.reader.theme.XpSessionTracker
import com.novelfa.reader.theme.XpStore
import java.io.Closeable
import java.io.File
import java.time.Instant
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import com.novelfa.reader.data.CropRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *   ZoomablePageContainer). In paged mode horizontal drag is the
 *   page turn, so zoom there only sharpens the render (the same
 *   trade-off ReadEra documents for its horizontal mode).
 * - A page that fails to render fails alone (placeholder + retry),
 *   it never kills the book.
 * - Progress reuses the existing book row: [BookEntity.lastChapterIndex]
 *   IS the page index and [BookEntity.totalChapters] IS the page
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
 * - No text search, selection, annotation or XP credit yet for
 *   native books. Those need page-coordinate data the old XHTML
 *   path also threw away; they belong on top of this viewer.
 * - CBZ chapter folders become a chapter list (see
 *   [CbzBook.chaptersForEntries]): the top bar offers it as a
 *   one-tap jump to a chapter's first page, and a flat archive
 *   (no folders) shows no chapter control at all, reading
 *   exactly as before.
 * - CBR (RAR) is not supported: it needs a RAR decoder.
 * - Password PDFs work only because import already stores them
 *   decrypted; the platform renderer cannot open an encrypted
 *   file at all, so this viewer never sees one.
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
 */
internal class CbzNativeSource(
    zipFile: File,
    password: CharArray? = null
) : NativePageSource {
    private val lock = Any()
    private val zip = ZipFile(zipFile).apply {
        if (password != null) setPassword(password)
    }
    private val entries: List<String> = CbzBook.imageEntryNames(zipFile)

    private fun headerFor(name: String): FileHeader? {
        return runCatching {
            zip.fileHeaders.firstOrNull { it.fileName == name }
        }.getOrNull()
    }

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
            val encrypted = runCatching {
                zip.fileHeaders.firstOrNull { it.isEncrypted }
            }.getOrNull() ?: return true
            return runCatching {
                zip.getInputStream(encrypted).use { it.readBytes() }
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
            val header = headerFor(entries[index]) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                zip.getInputStream(header).use { stream ->
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
            val header = headerFor(entries[index]) ?: return null
            return runCatching {
                val bytes = zip.getInputStream(header).use { it.readBytes() }
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
            val header = headerFor(name) ?: return@runCatching 0
            zip.getInputStream(header).use { stream ->
                ExifRotation.degreesFromStream(stream)
            }
        }.getOrDefault(0)
    }

    override fun thumbnail(index: Int, targetWidthPx: Int): Bitmap? {
        synchronized(lock) {
            if (index < 0 || index >= entries.size) return null
            val header = headerFor(entries[index]) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                zip.getInputStream(header).use { stream ->
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
                zip.getInputStream(header).use { stream ->
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
        synchronized(lock) {
            if (index < 0 || index >= entries.size) {
                throw IndexOutOfBoundsException("Comic page $index out of range")
            }
            val header = headerFor(entries[index])
                ?: throw IllegalArgumentException("Missing comic entry: ${entries[index]}")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(header).use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IllegalArgumentException("Unreadable comic image: ${entries[index]}")
            }
            // EXIF rotation, read BEFORE the sample loop: a
            // 90/270 rotation swaps the dimensions the ceiling
            // is checked against, or an ultra-wide page stored
            // sideways would sample down for the wrong axis.
            // The rotation itself is applied to the decoded
            // bitmap below, so the crop/resize/sharpen pipeline
            // downstream always sees the upright page.
            val exifDegrees = exifDegrees(entries[index])
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
            // on a fresh stream before giving up. Each attempt
            // needs its own stream — a consumed stream cannot
            // be decoded twice. The extra memory is bounded:
            // page bitmaps are owned per visible item and
            // recycled when the item leaves composition, so
            // only the visible window's pages are alive.
            fun decodeAs(config: Bitmap.Config, sampleSize: Int): Bitmap? {
                return runCatching {
                    zip.getInputStream(header).use { stream ->
                        BitmapFactory.decodeStream(
                            stream, null,
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
                    }
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
                ?: throw IllegalArgumentException("Unreadable comic image: ${entries[index]}")
            if (exifDegrees == 0) return decoded
            val rotated = rotateBitmap(decoded, exifDegrees)
            if (rotated !== decoded) decoded.recycle()
            return rotated
        }
    }

    override fun close() {
        // No-op on purpose: zip4j parses the central directory
        // at construction (its handle is closed after parsing)
        // and opens a fresh handle per stream, so there is no
        // persistent handle to close — unlike java.util.zip.
    }

    companion object {
        private const val MAX_DECODE_HEIGHT = 16384
        private const val MAX_DECODE_WIDTH = 8192
    }
}

/**
 * Route wrapper: decides, from the stored file alone, whether this
 * book gets the native page viewer or the existing EPUB/WebView
 * reader. This is the ONLY call site change for the old reader:
 * [ReaderScreen] itself is untouched, so EPUB, TXT, Markdown and
 * DOCX behaviour cannot regress through this change. A book whose
 * file is gone, or whose row is missing, shows the existing
 * "couldn't open" path instead of crashing the route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderRoute(
    bookId: String,
    dao: BookDao,
    onClose: () -> Unit,
    support: com.novelfa.reader.settings.SupportTracker? = null
) {
    val context = LocalContext.current
    var entity by remember { mutableStateOf<BookEntity?>(null) }
    var missing by remember { mutableStateOf(false) }
    var resolvedFile by remember { mutableStateOf<File?>(null) }
    var resolveFailed by remember { mutableStateOf(false) }
    LaunchedEffect(bookId) {
        val found = withContext(Dispatchers.IO) {
            runCatching { dao.getById(bookId) }.getOrNull()
        }
        if (found == null) missing = true else entity = found
    }
    // Resolve before routing: for in-place books the entity path
    // is a not-yet-materialized working copy (content://) or the
    // user's real file (file://), so routing on the raw path
    // would mis-detect format or fail outright. Copy books
    // resolve to their stored file, unchanged.
    LaunchedEffect(entity) {
        val found = entity ?: return@LaunchedEffect
        resolvedFile = withContext(Dispatchers.IO) {
            runCatching { InPlaceSource.resolve(context, found) }.getOrNull()
        }
        if (resolvedFile == null) resolveFailed = true
    }
    // First-open cover backfill for native books (CBZ and
    // image-only ZIPs), the counterpart of ReaderScreen's call
    // for text books: a register-only `content://` in-place
    // import never extracted the first page as a cover, so the
    // row still shows a generated cover or none. Restricted to
    // CBZ/ZIP here so text books are not backfilled twice (the
    // reader does those); the helper itself is idempotent, so
    // a ZIP that also reaches the reader is still safe.
    LaunchedEffect(entity, resolvedFile) {
        val found = entity ?: return@LaunchedEffect
        val file = resolvedFile ?: return@LaunchedEffect
        if (found.importMode != "IN_PLACE") return@LaunchedEffect
        val format = BookFormat.detect(file) ?: return@LaunchedEffect
        if (format != BookFormat.CBZ && format != BookFormat.ZIP) return@LaunchedEffect
        CoverBackfill.backfill(context, dao, found, file)
    }
    when {
        missing || resolveFailed -> {
            Scaffold { padding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // A failed resolve is always an in-place
                        // source that is gone (copy books resolve
                        // to a plain File constructor), so it gets
                        // the "file missing" message and its relink
                        // path in the library, not a generic error.
                        Text(
                            if (resolveFailed) stringResource(R.string.book_file_missing)
                            else stringResource(R.string.couldn_t_open_the_book)
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onClose) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }
        entity == null || resolvedFile == null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        // PDF routing REVERTED 2026-10-29 at the user's instruction:
        // PDF books go back to ReaderScreen (the converted
        // text/image path via the PDF engine), where the user's
        // chosen font applies to the text — the native PdfRenderer
        // viewer renders fixed PDF bitmaps, so it could never apply
        // the user's font, and the user reports its rendering as
        // broken. PdfNativeRenderer below is kept (currently unused)
        // in case native PDF is ever wanted again; CBZ and
        // image-only ZIPs keep the native viewer, since they never
        // had a converted path. A ZIP carrying a book is NOT a
        // comic: it is classified below and routed to ReaderScreen,
        // where the archive engine opens the book inside it.
        BookFormat.detect(resolvedFile!!) == BookFormat.CBZ -> {
            val bookFile = resolvedFile!!
            NativeImageBookViewer(
                book = entity!!,
                dao = dao,
                onClose = onClose,
                sourceFactory = { pw -> CbzNativeSource(bookFile, pw) },
                loadingRes = R.string.cbz_native_loading,
                errorRes = R.string.cbz_native_error
            )
        }
        BookFormat.detect(resolvedFile!!) == BookFormat.ZIP -> {
            ZipReaderRoute(
                book = entity!!, resolvedFile = resolvedFile!!,
                dao = dao, onClose = onClose, support = support
            )
        }
        else -> {
            ReaderScreen(bookId = bookId, dao = dao, onClose = onClose, support = support)
        }
    }
}

/**
 * Routing for a `.zip` book, decided by the archive engine — not by
 * the extension. Image-only archives go to the native comic viewer;
 * archives carrying a book go to the HTML reader, where the engine
 * opens the book inside (asking first when there are several).
 * Previously every ZIP went to the comic viewer, so a ZIP
 * containing an EPUB opened as an empty comic.
 */
@Composable
private fun ZipReaderRoute(
    book: BookEntity,
    resolvedFile: File,
    dao: BookDao,
    onClose: () -> Unit,
    support: com.novelfa.reader.settings.SupportTracker? = null
) {
    var kind by remember { mutableStateOf<ZipKind?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(book.id, resolvedFile) {
        kind = withContext(Dispatchers.IO) {
            runCatching { ZipEngine.classify(resolvedFile) }.getOrNull()
        }
        if (kind == null) failed = true
    }
    when {
        failed -> {
            Scaffold { padding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.cbz_native_error))
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onClose) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }
        kind == null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        kind is ZipKind.ImageOnly -> {
            NativeImageBookViewer(
                book = book,
                dao = dao,
                onClose = onClose,
                sourceFactory = { pw -> CbzNativeSource(resolvedFile, pw) },
                loadingRes = R.string.cbz_native_loading,
                errorRes = R.string.cbz_native_error
            )
        }
        else -> {
            ReaderScreen(bookId = book.id, dao = dao, onClose = onClose, support = support)
        }
    }
}

/**
 * One pager item in dual-page mode (ComicScreen's 2p view).
 * [firstPage] is the page the bottom bar, progress save and XP
 * all track for this item — the pair's first page in reading
 * order, so bookkeeping stays in pages even though the pager
 * moves in pairs.
 */
internal sealed interface DualPair {
    val firstPage: Int

    /** The book's last page, alone (odd page count). */
    data class Single(override val firstPage: Int) : DualPair

    /** Two consecutive pages, side by side. */
    data class Pair(override val firstPage: Int, val secondPage: Int) : DualPair

    /** One landscape page, split across the pair (see below). */
    data class Split(override val firstPage: Int) : DualPair
}

/**
 * Pairs for a book, in reading order. A page is split only when
 * it is landscape (wider than tall, by DISPLAY size — EXIF
 * rotation already applied) AND not animated: an animation
 * cannot be halved into two stills. PDF sources report no native
 * size, so they never split — they pair plainly. A bounds decode
 * that fails for a page treats it as portrait: the page still
 * pairs, it just never splits on its own.
 */
internal fun buildDualPairs(source: NativePageSource, pageCount: Int): List<DualPair> {
    val pairs = mutableListOf<DualPair>()
    var i = 0
    while (i < pageCount) {
        val size = runCatching { source.nativePageSize(i) }.getOrNull()
        val split = size != null && size.first > size.second &&
            !runCatching { source.isAnimatedPage(i) }.getOrDefault(false)
        if (split) {
            pairs += DualPair.Split(i)
            i += 1
        } else if (i + 1 < pageCount) {
            pairs += DualPair.Pair(i, i + 1)
            i += 2
        } else {
            pairs += DualPair.Single(i)
            i += 1
        }
    }
    return pairs
}

/**
 * The pair holding [page]: a pair contains its pages, so a
 * slider jump or chapter jump to any page lands on its pair.
 * Falls back to the last pair starting at or before the page
 * (and to 0 for a page before every pair, which cannot happen
 * for a real book but keeps the pager index in range).
 */
internal fun pairIndexForPage(pairs: List<DualPair>, page: Int): Int {
    for ((index, pair) in pairs.withIndex()) {
        when (pair) {
            is DualPair.Single -> if (pair.firstPage == page) return index
            is DualPair.Pair -> if (pair.firstPage == page || pair.secondPage == page) return index
            is DualPair.Split -> if (pair.firstPage == page) return index
        }
    }
    return pairs.indexOfLast { it.firstPage <= page }.coerceAtLeast(0)
}

/**
 * The native viewer itself, for PDF and CBZ alike. Render width is
 * the screen width times the zoom factor (1x / 1.5x / 2x, fit-width
 * is 1x); changing zoom re-renders visible pages at the new width,
 * it never scales a stale bitmap up, so zoom stays sharp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeImageBookViewer(
    book: BookEntity,
    dao: BookDao,
    onClose: () -> Unit,
    sourceFactory: (CharArray?) -> NativePageSource,
    loadingRes: Int,
    errorRes: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember(context.applicationContext) {
        PrefsStore(context.applicationContext)
    }
    val savedModeKey by prefs.readingMode.collectAsState(initial = "paged")
    val comicModeKey by prefs.comicReadingMode.collectAsState(initial = null)
    // Comic mode: the comic viewer's own key, falling back to
    // the shared key while unset (see PrefsStore: comics have
    // three modes now, the EPUB reader understands two, and
    // sharing one key would make the EPUB reader misbehave in
    // horizontal-strip mode).
    val modeNow = ReadingMode.fromKey(comicModeKey ?: savedModeKey)
    val isPaged = modeNow == ReadingMode.PAGED_HORIZONTAL
    val isHorizontalStrip = modeNow == ReadingMode.HORIZONTAL_STRIP
    // Per-book viewer extras (bookmarks, exact strip offset),
    // kept out of Room on purpose — see ReaderExtrasStore.
    val extras = remember(context.applicationContext) {
        ReaderExtrasStore(context.applicationContext)
    }

    val directionRtl by prefs.comicDirectionRtl.collectAsState(initial = true)
    val sharpen by prefs.comicSharpen.collectAsState(initial = false)
    val sharpenLevel by prefs.comicSharpenLevel.collectAsState(initial = 5)
    val upscaleMode by prefs.comicUpscaleMode.collectAsState(initial = "cubic")
    val filterGray by prefs.comicFilterGray.collectAsState(initial = false)
    val filterContrast by prefs.comicFilterContrast.collectAsState(initial = false)
    val filterInvert by prefs.comicFilterInvert.collectAsState(initial = false)
    val viewRotation by prefs.comicRotation.collectAsState(initial = 0)
    val volumeKeysOn by prefs.comicVolumeKeys.collectAsState(initial = false)
    val bookmarks by extras.bookmarks(book.id).collectAsState(initial = emptySet())
    val manualCropRects by extras.cropRects(book.id).collectAsState(initial = emptyMap())
    // Effective book direction at the current view rotation:
    // 180 mirrors the layout (ComicScreen's rotation rule), so
    // the pager direction and the dual-pair order both flip.
    val effectiveRtl = directionRtl != (viewRotation == 180)
    val pairDirection = if (effectiveRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    var source by remember { mutableStateOf<NativePageSource?>(null) }
    var openError by remember { mutableStateOf(false) }
    // Encrypted-archive password (ComicScreen's ZIP password
    // support): null until the user supplies one. The open
    // effect restarts when it changes, so submitting the
    // dialog retries the open with the password. Session-only
    // by design — a password is never persisted.
    var zipPassword by remember { mutableStateOf<CharArray?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordWrong by remember { mutableStateOf(false) }
    var totalChaptersFixed by remember { mutableStateOf(false) }

    // Open on IO: renderer/zip construction parses the file header
    // and must never block the main thread on a big book.
    LaunchedEffect(book.id, book.filePath, zipPassword) {
        val opened = withContext(Dispatchers.IO) {
            runCatching { sourceFactory(zipPassword) }.getOrNull()
        }
        if (opened == null || opened.pageCount <= 0) {
            runCatching { opened?.close() }
            openError = true
        } else {
            // Encrypted entries the password cannot open (or
            // no password yet): ask, do not show a broken book.
            // The probe runs on IO (it reads a full entry).
            // The source is closed; the viewer stays on the
            // loading state under the dialog, and dismissing
            // the dialog without a password lands on the error
            // screen (see the dialog's onDismissRequest).
            val probeOk = if (opened is CbzNativeSource) {
                withContext(Dispatchers.IO) { opened.probePassword() }
            } else {
                true
            }
            if (!probeOk) {
                runCatching { opened.close() }
                passwordWrong = zipPassword != null
                showPasswordDialog = true
            } else {
                source = opened
            }
        }
    }
    // THE "zip file closed" FIX: `source` is a MutableState, so
    // reading it inside onDispose reads its CURRENT value, not
    // the value the effect started with. When the open effect
    // set source null -> opened, this effect restarted and its
    // old onDispose ran AFTER the state already held the new
    // source — closing the just-opened zip before a single page
    // rendered (page count still showed, because the entry list
    // is built in the constructor, before the close). Capturing
    // the instance in a local val makes dispose close exactly
    // the source this effect instance started with: the old
    // effect closes null (no-op), the live one closes the real
    // source only when the viewer actually leaves.
    DisposableEffect(source) {
        val toClose = source
        // A throw inside onDispose kills the app, so closing a
        // source that is already half-dead must never throw out.
        onDispose { runCatching { toClose?.close() } }
    }

    val pageCount = source?.pageCount ?: 0
    // Chapter list, CBZ/ZIP only (PDF sources report none). The
    // control appears only when there is something to jump
    // between: two or more folders, or a single folder after
    // root-level pages (a cover before the first chapter). A flat
    // archive, or one folder holding the whole book, shows no
    // chapter control and reads exactly as before.
    val chapters = source?.chapters ?: emptyList()
    val showChapterList = chapters.size > 1 ||
        (chapters.size == 1 && chapters[0].firstPageIndex > 0)
    var showChapters by remember { mutableStateOf(false) }
    val savedPage = book.lastChapterIndex
    var restoredPage by remember { mutableStateOf(false) }
    val initialPage = 0

    // Fill in the lazy-import placeholder (totalChapters == 0) the
    // first time the real page count is known, without touching the
    // saved page index. CBZ import already set the count, so this
    // is a no-op there.
    LaunchedEffect(pageCount, totalChaptersFixed) {
        if (pageCount > 0 && !totalChaptersFixed && book.totalChapters != pageCount) {
            withContext(Dispatchers.IO) {
                runCatching {
                    dao.update(book.copy(totalChapters = pageCount))
                }
            }
            totalChaptersFixed = true
        }
    }

    // Dual page (ComicScreen's 2p view): in paged mode each
    // pager item is a PAIR, not a page. A pair is two consecutive
    // pages side by side, a single page when the book has an odd
    // page left over, or one landscape page SPLIT across the pair
    // (its halves, reading order first) — a wide page shown whole
    // in a portrait slot would shrink to illegibility, and the
    // split is what keeps it readable. Continuous mode ignores
    // dual entirely: a strip is one page wide by definition.
    // Pairs are computed on IO from bounds-only sizes (no pixel
    // decode), cached per source, and only when the switch is on
    // — a dual-off book never pays for them.
    val dualPage by prefs.comicDualPage.collectAsState(initial = false)
    var dualPairs by remember { mutableStateOf<List<DualPair>?>(null) }
    var dualPairsFor by remember { mutableStateOf<NativePageSource?>(null) }
    LaunchedEffect(source, dualPage, pageCount) {
        val src = source ?: return@LaunchedEffect
        if (!dualPage || pageCount <= 0) return@LaunchedEffect
        if (dualPairsFor === src && dualPairs != null) return@LaunchedEffect
        val pairs = withContext(Dispatchers.IO) {
            runCatching { buildDualPairs(src, pageCount) }.getOrNull()
        }
        if (pairs != null) {
            dualPairs = pairs
            dualPairsFor = src
        }
    }
    val pagerPageCount =
        if (dualPage && dualPairs != null) dualPairs!!.size else pageCount

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pagerPageCount }
    )
    var currentPage by remember { mutableIntStateOf(initialPage) }

    // XP for native books (PDF/CBZ/ZIP pages): the EPUB reader had XP
    // from the start, native books had none — a PDF novel or a manhwa
    // earned nothing. Same rules as the EPUB path, with page = unit:
    // visited-only (slider jumps never credit skipped pages),
    // >= 5 s dwell via [XpSessionTracker], once per page per book
    // (XpLedger), private books excluded, and the book bonus from
    // XpStore (last page credited + >= 90% of pages) applies unchanged.
    // Words are not extracted in the native viewer, so every page
    // counts as words = 0, which the formula floors to 1 XP — the
    // same result a typical ~500-word PDF page would give, and the
    // only honest value for an image page.
    val rankPrefs = remember(prefs) { PrefsStoreRankPrefs(prefs) }
    val xpTracker = remember(book.id) { XpSessionTracker() }
    // Lazy content-key rekeying (same as the EPUB reader): the first
    // page credit hashes the stored file once and moves the ledger
    // entry from the import UUID to the file SHA-256 key. The
    // resolver object is stable across recompositions, so the
    // LaunchedEffect/close closures below never see a stale key.
    val xpResolver = remember(book.id) { XpContentKeyResolver(XpStore(rankPrefs)) }
    // Independent scope for the dispose safety net (ReaderScreen
    // parity): the composition scope is cancelled on dispose.
    val xpFlushScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    suspend fun xpCreditPage(page: Int) {
        if (book.isPrivate || pageCount <= 0 || page !in 0 until pageCount) return
        runCatching {
            rankPrefs.awaitLoaded()
            val hash = if (!xpResolver.resolved) {
                withContext(Dispatchers.IO) {
                    runCatching { sha256OfFile(File(book.filePath)) }.getOrNull()
                }
            } else {
                null
            }
            val key = xpResolver.resolve(book.id, hash)
            XpStore(rankPrefs).creditUnit(key, page, 0, pageCount, book.isPrivate)
        }
    }
    // Drive XP from currentPage, not from the pager/list tracking
    // flows: those flows re-emit on a mode switch (and can emit 0
    // first), which would credit the page the user is still on.
    // currentPage only changes when the user actually moves, so a
    // mode toggle, zoom change or restore never credits anything.
    // Dual page gets a SECOND dwell clock: both pages of a pair
    // are on screen together, so both earn their own dwell — the
    // pair's second page is not a skipped page, it was read.
    // A split page has no second page (its halves are one page),
    // and leaving dual settles the second clock through flush.
    val xpTrackerSecond = remember(book.id) { XpSessionTracker() }
    LaunchedEffect(currentPage, pageCount, dualPage, dualPairs) {
        if (pageCount <= 0) return@LaunchedEffect
        xpTracker.enter(currentPage)?.let { xpCreditPage(it) }
        val second = if (dualPage && dualPairs != null) {
            val pair = dualPairs!!.getOrNull(pairIndexForPage(dualPairs!!, currentPage))
            (pair as? DualPair.Pair)?.secondPage
        } else {
            null
        }
        if (second != null) {
            xpTrackerSecond.enter(second)?.let { xpCreditPage(it) }
        } else {
            xpTrackerSecond.flush()?.let { xpCreditPage(it) }
        }
    }
    DisposableEffect(book.id) {
        onDispose {
            val tracker = xpTracker
            val trackerSecond = xpTrackerSecond
            xpFlushScope.launch {
                runCatching {
                    val flushed = tracker.flush()
                    if (flushed != null) xpCreditPage(flushed)
                    val flushedSecond = trackerSecond.flush()
                    if (flushedSecond != null) xpCreditPage(flushedSecond)
                }
            }
        }
    }
    // Page to land the newly-active state on when the mode flips.
    // Set BEFORE the preference changes (the new state's tracking
    // flow emits its own position 0 immediately on start, so the
    // restore must not read currentPage after the flip — it would
    // read 0 and lose the place).
    var pendingModeTarget by remember { mutableStateOf<Int?>(null) }
    // Keys include isHorizontalStrip: vertical -> horizontal
    // keeps isPaged false, so without it the effect would not
    // restart and the new strip would sit at page 0.
    LaunchedEffect(pageCount, isPaged, isHorizontalStrip) {
        if (pageCount <= 0) return@LaunchedEffect
        val target = pendingModeTarget?.also { pendingModeTarget = null }
            ?: if (!restoredPage) {
                restoredPage = true
                savedPage.coerceIn(0, pageCount - 1)
            } else {
                return@LaunchedEffect
            }
        if (target != 0) {
            currentPage = target
            if (isPaged) {
                // Dual: the pager moves in pairs, so the restore
                // target (a page) maps to its pair; when pairs
                // are not computed yet the correction effect
                // below lands it once they are.
                val pagerTarget =
                    if (dualPage && dualPairs != null) {
                        pairIndexForPage(dualPairs!!, target)
                    } else {
                        target
                    }
                runCatching { pagerState.scrollToPage(pagerTarget) }
            } else {
                // Exact strip offset (ComicScreen's fractional
                // page offset): the strip lands where it was,
                // not at the top of the page. The offset key is
                // shared by both strips — an out-of-range pixel
                // offset simply clamps, so a mode switch cannot
                // break the landing.
                val offset = runCatching {
                    extras.scrollOffset(book.id).first()
                }.getOrDefault(0)
                if (isHorizontalStrip) {
                    runCatching { rowState.scrollToItem(target, offset) }
                } else {
                    runCatching { listState.scrollToItem(target, offset) }
                }
            }
        }
    }
    var lastSaveMs by remember { mutableStateOf(0L) }

    suspend fun savePage(page: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveMs < 500) return
        lastSaveMs = now
        if (pageCount <= 0) return
        withContext(Dispatchers.IO) {
            runCatching {
                val current = dao.getById(book.id) ?: book
                dao.update(
                    current.copy(
                        lastChapterIndex = page.coerceIn(0, pageCount - 1),
                        totalChapters = pageCount,
                        lastOpenedDate = Instant.now().toString(),
                        lastAnchor = null
                    )
                )
            }
        }
    }

    // Visible-page tracking, throttled like the EPUB reader's
    // saveProgress (500ms): scrolling through pages must not cost
    // a DB write per page. The effect restarts on a mode switch,
    // so the pager flow replaces the list flow (and vice versa)
    // instead of both tracking at once.
    // The first emission after every restart is skipped on
    // purpose: it is the position the pager/list ALREADY sits at
    // (restore, mode switch, dual toggle), and currentPage
    // already holds it. Accepting it would, on a dual toggle,
    // save the transient pair index as a page number before the
    // correction effect below lands the pager on the right pair.
    LaunchedEffect(listState, rowState, pagerState, pageCount, isPaged, isHorizontalStrip, dualPage, dualPairs) {
        if (pageCount <= 0) return@LaunchedEffect
        var first = true
        if (isPaged) {
            snapshotFlow { pagerState.currentPage }.collect { pagerIdx ->
                if (first) {
                    first = false
                    return@collect
                }
                val page = if (dualPage && dualPairs != null) {
                    dualPairs!!.getOrNull(pagerIdx)?.firstPage ?: return@collect
                } else {
                    pagerIdx
                }
                if (page in 0 until pageCount) {
                    currentPage = page
                    savePage(page)
                }
            }
        } else {
            // Both strips track the same way; the active one
            // also saves its exact scroll offset, so a close
            // mid-page restores mid-page (see the restore
            // effect). Offset saves ride the page-change rate
            // — one DataStore edit per page, never per frame.
            val active = if (isHorizontalStrip) rowState else listState
            snapshotFlow {
                active.firstVisibleItemIndex to active.firstVisibleItemScrollOffset
            }.collect { (page, offset) ->
                if (first) {
                    first = false
                    return@collect
                }
                if (page in 0 until pageCount) {
                    currentPage = page
                    savePage(page)
                    scope.launch { extras.setScrollOffset(book.id, offset) }
                }
            }
        }
    }

    // Fractional slider position for continuous modes
    // (ComicScreen's fractional page offset): index + the
    // fraction of the first visible page scrolled past, so the
    // slider sits between pages while a page is half-scrolled
    // instead of snapping to integers. Tracked in a flow:
    // layoutInfo is not composition-observable, but the scroll
    // offset changes every scroll frame, so the flow emits
    // continuously while scrolling and the size read is fresh.
    var stripPosition by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(listState, rowState, isHorizontalStrip, pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        val active = if (isHorizontalStrip) rowState else listState
        snapshotFlow {
            active.firstVisibleItemIndex to active.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            val first = index.coerceIn(0, pageCount - 1)
            val info = active.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == first }
            val fraction = if (info != null && info.size > 0) {
                (offset.toFloat() / info.size).coerceIn(0f, 1f)
            } else {
                0f
            }
            stripPosition = (first + fraction)
                .coerceIn(0f, (pageCount - 1).toFloat())
        }
    }

    // Dual-toggle landing: when dual turns on, or pairs finish
    // loading, the pager index (a page index until now) must
    // become the pair holding the current page; when dual turns
    // off, the pair index must become the page again. Without
    // this the pager would sit on a meaningless index after the
    // toggle, and the tracking effect above would save it as a
    // page number. The skipped first emission above is what makes
    // this race-free in either effect order.
    LaunchedEffect(dualPage, dualPairs, isPaged) {
        if (!isPaged || pageCount <= 0) return@LaunchedEffect
        if (dualPage && dualPairs != null) {
            val target = pairIndexForPage(dualPairs!!, currentPage)
            if (pagerState.currentPage != target) {
                runCatching { pagerState.scrollToPage(target) }
            }
        } else if (!dualPage) {
            if (pagerState.currentPage != currentPage) {
                runCatching { pagerState.scrollToPage(currentPage) }
            }
        }
    }

    fun closeViewer() {
        scope.launch {
            // XP flush first, like ReaderScreen: the home celebration
            // must see the final page, and the dispose safety net
            // above is only a fallback (its flush then finds nothing).
            runCatching {
                val flushed = xpTracker.flush()
                if (flushed != null) xpCreditPage(flushed)
                val flushedSecond = xpTrackerSecond.flush()
                if (flushedSecond != null) xpCreditPage(flushedSecond)
            }
            savePage(currentPage, force = true)
            if (!isPaged) {
                val offset = if (isHorizontalStrip) {
                    rowState.firstVisibleItemScrollOffset
                } else {
                    listState.firstVisibleItemScrollOffset
                }
                runCatching { extras.setScrollOffset(book.id, offset) }
            }
            onClose()
        }
    }

    // Top-bar icon cycles all three modes (paged -> vertical
    // strip -> horizontal strip -> paged); the sheet rows pick
    // one directly through setMode below. Both write the
    // comic viewer's own key, never the shared EPUB key.
    fun toggleMode() {
        val next = when (modeNow) {
            ReadingMode.PAGED_HORIZONTAL -> ReadingMode.CONTINUOUS_VERTICAL
            ReadingMode.CONTINUOUS_VERTICAL -> ReadingMode.HORIZONTAL_STRIP
            ReadingMode.HORIZONTAL_STRIP -> ReadingMode.PAGED_HORIZONTAL
        }
        pendingModeTarget = currentPage
        scope.launch {
            withContext(Dispatchers.IO) {
                prefs.setComicReadingMode(next.key)
            }
        }
    }

    fun setMode(mode: ReadingMode) {
        if (mode == modeNow) return
        pendingModeTarget = currentPage
        scope.launch {
            withContext(Dispatchers.IO) {
                prefs.setComicReadingMode(mode.key)
            }
        }
    }

    // Rotate action: cycles the view rotation 0 -> 90 -> 180
    // -> 270 -> 0 (persisted, so it survives reopening). The
    // pipeline rotates bitmap pages, the ImageView rotates
    // animated ones; 180 also mirrors navigation (see
    // effectiveRtl above).


    /** Direct jump (slider, restore, zoom): never animates through pages. */
    fun jumpTo(page: Int) {
        scope.launch {
            val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            if (isPaged) {
                // Dual: the pager moves in pairs, so a jump to a
                // page lands on its pair (never a half pair).
                val pagerTarget =
                    if (dualPage && dualPairs != null) {
                        pairIndexForPage(dualPairs!!, target)
                    } else {
                        target
                    }
                runCatching { pagerState.scrollToPage(pagerTarget) }
            } else if (isHorizontalStrip) {
                runCatching { rowState.scrollToItem(target) }
            } else {
                runCatching { listState.scrollToItem(target) }
            }
        }
    }

    /** Single-page step (prev/next buttons): animated in paged mode. */
    fun stepTo(page: Int) {
        scope.launch {
            val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            if (isPaged) {
                if (dualPage && dualPairs != null) {
                    // Step by PAIR, not by page: the buttons call
                    // this with currentPage +/- 1, and stepping a
                    // single page in dual mode would land mid-pair
                    // and break the pairing. The direction comes
                    // from the requested page vs the current one.
                    val dir = if (target > currentPage) 1 else -1
                    val cur = pairIndexForPage(dualPairs!!, currentPage)
                    val next = (cur + dir).coerceIn(0, dualPairs!!.size - 1)
                    runCatching { pagerState.animateScrollToPage(next) }
                } else {
                    runCatching { pagerState.animateScrollToPage(target) }
                }
            } else if (isHorizontalStrip) {
                runCatching { rowState.scrollToItem(target) }
            } else {
                runCatching { listState.scrollToItem(target) }
            }
        }
    }

    val density = LocalDensity.current
    val screenWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx().toInt()
    }
    val screenHeightPx = with(density) {
        LocalConfiguration.current.screenHeightDp.dp.toPx().toInt()
    }
    // Zoom steps: fit-width, 1.5x, 2x. Never below fit-width: a page
    // smaller than the screen is a wasted screen, not a feature.
    val zoomFactors = remember { listOf(1f, 1.5f, 2f) }
    var zoomIndex by remember { mutableIntStateOf(0) }
    val renderWidthPx = (screenWidthPx * zoomFactors[zoomIndex]).toInt().coerceAtLeast(1)
    // After a zoom change the item heights change; land back on the
    // same page instead of wherever the new heights dropped us.
    LaunchedEffect(zoomIndex) {
        if (pageCount > 0) {
            jumpTo(currentPage)
        }
    }

    // ComicScreen-style chrome. The old bars were always visible
    // and crowded: five text buttons up top, two more below, on a
    // phone that is mostly page. Now a tap anywhere on the page
    // hides both bars (reading goes full-screen) and the next tap
    // brings them back. Tap only: a swipe still turns or scrolls
    // the page, because the pager/list consumes the drag before
    // this detector's up-event can fire — so hiding never eats a
    // page turn. The top bar keeps only icons (chapters, mode,
    // settings); zoom and fit-width moved into the settings sheet,
    // where text buttons have room.
    var chromeVisible by remember { mutableStateOf(true) }
    var showViewSettings by remember { mutableStateOf(false) }
    var showOverview by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showCropEditor by remember { mutableStateOf(false) }
    // Double-tap support: the chrome toggle waits 300ms to
    // tell a single tap from the first tap of a double-tap
    // (the same window the page containers use for their
    // double-tap zoom). A second tap inside the window clears
    // the pending toggle — the container zooms instead — so a
    // double-tap never flickers the chrome. Cost: every chrome
    // toggle takes 300ms (the standard PhotoView trade-off).
    var pendingTapMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pendingTapMs) {
        val t = pendingTapMs ?: return@LaunchedEffect
        delay(300)
        if (pendingTapMs == t) {
            chromeVisible = !chromeVisible
            pendingTapMs = null
        }
    }
    // Volume-key focus: the tap surface holds focus while the
    // switch is on, so the volume keys reach it. Focus is
    // re-requested when the sheet closes (the sheet steals it
    // while open); in touch mode a tap does not move focus, so
    // the top-bar buttons never steal it.
    fun cycleRotation() {
        val next = when (viewRotation) {
            0 -> 90
            90 -> 180
            180 -> 270
            else -> 0
        }
        scope.launch { prefs.setComicRotation(next) }
    }

    // Capture action: saves the CURRENT page to the gallery
    // (Pictures/Novelfa, via the existing ImageSaver — no
    // permission, no picker). What is saved is the page as
    // decoded (native pixels + EXIF rotation), NOT the
    // displayed pipeline output: crop/filters/sharpen live in
    // the item and re-running them here would duplicate the
    // pipeline and drift from it. The original page is the
    // honest thing to save.
    fun capturePage() {
        val src = source ?: return
        val page = currentPage
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = src.renderPage(page, screenWidthPx)
                    try {
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        val name = "${book.title} p${page + 1}.png"
                            .replace('/', '-')
                            .replace('\\', '-')
                        ImageSaver.saveToGallery(
                            context,
                            ImageSaveTarget(null, stream.toByteArray(), name, "image/png")
                        ).getOrThrow()
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            result.onSuccess { name ->
                snackbar.showSnackbar(
                    context.getString(R.string.comic_page_saved, name)
                )
            }.onFailure {
                snackbar.showSnackbar(context.getString(R.string.comic_save_failed))
            }
        }
    }

    val volumeFocus = remember { FocusRequester() }
    LaunchedEffect(volumeKeysOn, showViewSettings) {
        if (volumeKeysOn && !showViewSettings) {
            volumeFocus.requestFocus()
        }
    }
    val viewSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Scrub preview, not a live move (ComicScreen's page move
    // preview, number-only: a thumbnail would decode a page per
    // scrub step). While the thumb is dragged the indicator shows
    // the page it WOULD land on and nothing else moves — no page
    // flips, no progress saves, no XP entries for skipped pages.
    // Releasing the thumb jumps there through the same jump the
    // buttons use.
    var sliderPreview by remember { mutableStateOf<Int?>(null) }
    // Page background (letterbox around narrower pages): the old
    // dark grey, black, or white — persisted like the reading
    // mode, so the choice survives reopening the book.
    val savedBgKey by prefs.comicBackground.collectAsState(initial = "dark")
    val pageBg = when (savedBgKey) {
        "black" -> Color.Black
        "white" -> Color.White
        else -> Color(0xFF1E1E1E)
    }
    val cutMargin by prefs.comicCutMargin.collectAsState(initial = false)
    val cropPadH by prefs.comicCropPadH.collectAsState(initial = 0)
    val cropPadV by prefs.comicCropPadV.collectAsState(initial = 0)
    val paddingEnabled by prefs.comicPaddingEnabled.collectAsState(initial = false)
    // Percent 0..40 (ComicScreen's SeekBar max 400 -> ratio
    // 0..0.4); the effective ratio is 0 when the switch is off.
    val pageMarginPct by prefs.comicPageMargin.collectAsState(initial = 0)
    val paddingRatio = if (paddingEnabled) pageMarginPct / 100f else 0f
    // Page fit, direction and sharpen (ComicScreen's fitscreen /
    // page-direction / filter-sharp settings, same defaults):
    // fit-width, right-to-left, sharpen off.
    val fitMode by prefs.comicFitMode.collectAsState(initial = "width")
    val currentChapterIndex = chapters.indexOfLast { it.firstPageIndex <= currentPage }
    val currentChapter = chapters.getOrNull(currentChapterIndex)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = book.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (currentChapter != null) {
                                Text(
                                    text = currentChapter.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalContentColor.current.copy(alpha = 0.7f)
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { closeViewer() }) {
                            AppIcon(
                                AppIconKind.ArrowBack,
                                tint = LocalContentColor.current,
                                size = 18.dp
                            )
                        }
                    },
                    actions = {
                        if (showChapterList) {
                            IconButton(onClick = { showChapters = true }) {
                                AppIcon(
                                    AppIconKind.List,
                                    tint = LocalContentColor.current,
                                    size = 19.dp
                                )
                            }
                        }
                        // Icon shows the CURRENT mode, not the
                        // destination — the old convention showed
                        // the mode the tap would switch TO, and
                        // its scroll glyph looks vertical, so it
                        // actively misled: the user circled it
                        // believing vertical mode was ON while
                        // the app was still paged (which is why
                        // pages still slid sideways). Now the
                        // glyph reads as the state: two pages
                        // side by side = paging horizontally, a
                        // vertical strip = scrolling vertically.
                        // The settings sheet already highlights
                        // the current mode the same way, so the
                        // two indicators can no longer disagree.
                        // (Read above the button: semantics is
                        // not composable, so the label is resolved
                        // here, never hardcoded.)
                        val modeDescription = stringResource(
                            when {
                                isPaged -> R.string.mode_horizontal
                                isHorizontalStrip -> R.string.mode_horizontal_continuous
                                else -> R.string.mode_vertical_continuous
                            }
                        )
                        IconButton(
                            onClick = { toggleMode() },
                            modifier = Modifier.semantics {
                                contentDescription = modeDescription
                            }
                        ) {
                            AppIcon(
                                when {
                                    isPaged -> AppIconKind.Pages
                                    isHorizontalStrip -> AppIconKind.HorizontalScroll
                                    else -> AppIconKind.Scroll
                                },
                                tint = LocalContentColor.current,
                                size = 19.dp
                            )
                        }
                        // Quick toggles, ComicScreen's top-bar
                        // btn4/btn8 pattern: the icon itself is
                        // the switch, and the selected state is
                        // the primary tint (its setSelected).
                        // Fine-tuning (kept margins, ratio)
                        // stays in the settings sheet.
                        IconButton(onClick = {
                            scope.launch {
                                prefs.setComicCutMargin(!cutMargin)
                            }
                        }) {
                            AppIcon(
                                AppIconKind.Crop,
                                tint = if (cutMargin) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                },
                                size = 19.dp
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                prefs.setComicPaddingEnabled(!paddingEnabled)
                            }
                        }) {
                            AppIcon(
                                AppIconKind.Padding,
                                tint = if (paddingEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                },
                                size = 19.dp
                            )
                        }
                        // Bookmark toggle for the current page
                        // (a per-page bookmark, not a chapter):
                        // filled when this page is bookmarked,
                        // outline otherwise. One tap toggles;
                        // the list lives in the sheet. Hidden
                        // until the book is open (pageCount 0
                        // has no page to bookmark).
                        if (pageCount > 0) {
                            val bookmarked = currentPage in bookmarks
                            val bookmarkDescription =
                                stringResource(R.string.comic_bookmark)
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        extras.toggleBookmark(book.id, currentPage)
                                    }
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = bookmarkDescription
                                }
                            ) {
                                AppIcon(
                                    if (bookmarked) {
                                        AppIconKind.Bookmark
                                    } else {
                                        AppIconKind.BookmarkAdd
                                    },
                                    tint = if (bookmarked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        LocalContentColor.current
                                    },
                                    size = 19.dp
                                )
                            }
                        }
                        IconButton(onClick = { showViewSettings = true }) {
                            AppIcon(
                                AppIconKind.Gear,
                                tint = LocalContentColor.current,
                                size = 19.dp
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = chromeVisible && pageCount > 0,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                BottomAppBar {
                    // Arrows follow the reading flow, not the Latin
                    // alphabet: right-to-left (the default) puts the
                    // previous page on the right and the next page on
                    // the left, so prev points right and next left;
                    // left-to-right flips both sides. The actions
                    // never change, only the sides they sit on.
                    IconButton(
                        enabled = currentPage > 0,
                        onClick = { stepTo(currentPage - 1) }
                    ) {
                        AppIcon(
                            if (directionRtl) AppIconKind.ArrowForward else AppIconKind.ArrowBack,
                            tint = if (currentPage > 0) {
                                LocalContentColor.current
                            } else {
                                LocalContentColor.current.copy(alpha = 0.38f)
                            },
                            size = 19.dp
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val preview = sliderPreview
                        // Scrub thumbnail: while the thumb is
                        // dragged, a small preview of the page
                        // it WOULD land on — beyond ComicScreen's
                        // number-only preview. The thumbnail is
                        // a cheap sampled decode (never the full
                        // pipeline), so scrubbing stays smooth.
                        if (preview != null) {
                            PageThumbnail(
                                source = source,
                                page = preview,
                                modifier = Modifier.width(120.dp)
                                    .heightIn(max = 160.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.page_indicator,
                                (preview ?: currentPage) + 1, pageCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = if (preview != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            }
                        )
                        Slider(
                            value = sliderPreview?.toFloat()
                                ?: if (isPaged) currentPage.toFloat() else stripPosition,
                            onValueChange = { v ->
                                sliderPreview =
                                    v.toInt().coerceIn(0, pageCount - 1)
                            },
                            onValueChangeFinished = {
                                val target = sliderPreview
                                sliderPreview = null
                                if (target != null) jumpTo(target)
                            },
                            valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
                            steps = (pageCount - 2).coerceIn(0, 1000)
                        )
                    }
                    IconButton(
                        enabled = currentPage < pageCount - 1,
                        onClick = { stepTo(currentPage + 1) }
                    ) {
                        AppIcon(
                            if (directionRtl) AppIconKind.ArrowBack else AppIconKind.ArrowForward,
                            tint = if (currentPage < pageCount - 1) {
                                LocalContentColor.current
                            } else {
                                LocalContentColor.current.copy(alpha = 0.38f)
                            },
                            size = 19.dp
                        )
                    }
                }
            }
        }
    ) { padding ->
        // The tap surface for the chrome toggle: the whole page
        // area, padded by the Scaffold's bars while they are
        // visible, full-screen once they hide. Buttons inside
        // (retry, close) consume their own taps, so a tap on one
        // never toggles the chrome by accident.
        Box(
            modifier = Modifier.fillMaxSize().padding(padding)
                .focusRequester(volumeFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // Volume-key turning (opt-in switch in the
                    // sheet, default off): volume down = next
                    // page, volume up = previous. Consuming the
                    // key stops the system volume UI — that is
                    // the point of the switch, not a side effect.
                    if (!volumeKeysOn || event.type != KeyEventType.KeyDown) {
                        false
                    } else when (event.key) {
                        Key.VolumeDown -> {
                            stepTo(currentPage + 1)
                            true
                        }
                        Key.VolumeUp -> {
                            stepTo(currentPage - 1)
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        val now = System.currentTimeMillis()
                        if (pendingTapMs != null && now - pendingTapMs!! < 300) {
                            pendingTapMs = null
                        } else {
                            pendingTapMs = now
                        }
                    })
                }
        ) {
            when {
                openError -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(errorRes))
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = onClose) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    }
                }
                source == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(loadingRes))
                    }
                }
            }
            isPaged -> {
                val activeSource = source!!
                val pairs = dualPairs
                val useDual = dualPage && pairs != null
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                        .background(pageBg),
                    // Page direction (ComicScreen's
                    // set_img_page_direction_pager): the pager
                    // already reads right-to-left in this RTL
                    // layout, so left-to-right is the reversed
                    // layout, not a different component. At 180
                    // view rotation the layout mirrors
                    // (ComicScreen's rotation rule), so the
                    // direction flips with it.
                    reverseLayout = if (viewRotation == 180) {
                        directionRtl
                    } else {
                        !directionRtl
                    },
                    beyondViewportPageCount = 1
                ) { pagerIdx ->
                    // One item, one set of parameters: the only
                    // thing that varies between single and dual
                    // is which page(s) the item shows.
                    @Composable
                    fun singleItem(page: Int, halfSide: Int = 0) {
                        NativePageItem(
                            source = activeSource,
                            page = page,
                            renderWidthPx = renderWidthPx,
                            paged = true,
                            zoomFactor = zoomFactors[zoomIndex],
                            cropMargin = cutMargin,
                            cropPadH = cropPadH,
                            cropPadV = cropPadV,
                            paddingRatio = paddingRatio,
                            pageBg = pageBg,
                            screenWidthPx = screenWidthPx,
                            screenHeightPx = screenHeightPx,
                            fitMode = fitMode,
                            sharpen = sharpen,
                            sharpenLevel = sharpenLevel,
                            upscaleMode = upscaleMode,
                            filterGray = filterGray,
                            filterContrast = filterContrast,
                            filterInvert = filterInvert,
                            viewRotation = viewRotation,
                            manualCropRect = manualCropRects[page],
                            halfSide = halfSide
                        )
                    }
                    if (!useDual) {
                        singleItem(pagerIdx)
                    } else {
                        when (val pair = pairs!![pagerIdx]) {
                            is DualPair.Single -> singleItem(pair.firstPage)
                            is DualPair.Pair -> {
                                // The pair is laid out in the
                                // BOOK direction, not the UI
                                // locale's: the provider makes the
                                // first child sit on the reading
                                // side (right for RTL books, left
                                // for LTR) even when the UI locale
                                // disagrees — the old bare Row
                                // followed the UI locale and put
                                // LTR-book pairs backwards in an
                                // RTL UI. The provider also
                                // carries the 180 mirror (see
                                // pairDirection above).
                                CompositionLocalProvider(
                                    LocalLayoutDirection provides pairDirection
                                ) {
                                    Row(Modifier.fillMaxSize()) {
                                        Box(Modifier.weight(1f).fillMaxHeight()) {
                                            singleItem(pair.firstPage)
                                        }
                                        Box(Modifier.weight(1f).fillMaxHeight()) {
                                            singleItem(pair.secondPage)
                                        }
                                    }
                                }
                            }
                            is DualPair.Split -> {
                                // One landscape page across the
                                // pair: the half read FIRST goes
                                // in the first slot — in RTL that
                                // is the RIGHT half (reading
                                // starts on the right), in LTR
                                // the LEFT half. Row lays the
                                // first child per direction, so
                                // (first-read half, second half)
                                // lands correctly in both.
                                // First-read half mirrors with
                                // the layout (see pairDirection):
                                // RTL reads the right half first,
                                // LTR the left half, 180 swaps it.
                                val (firstSide, secondSide) =
                                    if (effectiveRtl) 2 to 1 else 1 to 2
                                CompositionLocalProvider(
                                    LocalLayoutDirection provides pairDirection
                                ) {
                                    Row(Modifier.fillMaxSize()) {
                                        Box(Modifier.weight(1f).fillMaxHeight()) {
                                            singleItem(pair.firstPage, firstSide)
                                        }
                                        Box(Modifier.weight(1f).fillMaxHeight()) {
                                            singleItem(pair.firstPage, secondSide)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            isHorizontalStrip -> {
                val activeSource = source!!
                // ComicScreen's horizontal strip: the mirror of
                // the vertical one — each page fills the strip
                // HEIGHT, pages sit side by side, no vertical
                // scroll. Laid out in the book's direction (see
                // the dual-pair provider above), so an RTL book
                // starts on the right. Pinch zoom and pan live
                // on the page itself, like the vertical strip.
                CompositionLocalProvider(
                    LocalLayoutDirection provides pairDirection
                ) {
                    LazyRow(
                        state = rowState,
                        modifier = Modifier.fillMaxSize()
                            .background(pageBg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pageCount) { page ->
                            NativePageItem(
                                source = activeSource,
                                page = page,
                                renderWidthPx = renderWidthPx,
                                paged = false,
                                zoomFactor = zoomFactors[zoomIndex],
                                cropMargin = cutMargin,
                                cropPadH = cropPadH,
                                cropPadV = cropPadV,
                                paddingRatio = paddingRatio,
                                pageBg = pageBg,
                                screenWidthPx = screenWidthPx,
                                screenHeightPx = screenHeightPx,
                                fitMode = fitMode,
                                sharpen = sharpen,
                                sharpenLevel = sharpenLevel,
                                upscaleMode = upscaleMode,
                                filterGray = filterGray,
                                filterContrast = filterContrast,
                                filterInvert = filterInvert,
                                viewRotation = viewRotation,
                                manualCropRect = manualCropRects[page],
                                horizontalStrip = true
                            )
                        }
                    }
                }
            }
            else -> {
                val activeSource = source!!
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .background(pageBg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pageCount) { page ->
                        NativePageItem(
                            source = activeSource,
                            page = page,
                            renderWidthPx = renderWidthPx,
                            paged = false,
                            zoomFactor = zoomFactors[zoomIndex],
                            cropMargin = cutMargin,
                            cropPadH = cropPadH,
                            cropPadV = cropPadV,
                            paddingRatio = paddingRatio,
                            pageBg = pageBg,
                            screenWidthPx = screenWidthPx,
                            screenHeightPx = screenHeightPx,
                            fitMode = fitMode,
                            sharpen = sharpen,
                            sharpenLevel = sharpenLevel,
                            upscaleMode = upscaleMode,
                            filterGray = filterGray,
                            filterContrast = filterContrast,
                            filterInvert = filterInvert,
                            viewRotation = viewRotation,
                            manualCropRect = manualCropRects[page]
                        )
                    }
                }
            }
            }
        }
    }

    // Chapter list: a plain dialog, not a drawer — the native
    // viewer has no drawer, and a dialog needs no new strings
    // (the existing section-list title and close labels are
    // reused). Tapping a chapter jumps to its first page through
    // the same jump the slider uses, so zoom, the slider and the
    // reading mode are untouched: the reader lands on the
    // chapter's first page in exactly the state it was in.
    if (showChapters) {
        val currentChapter = chapters.indexOfLast { it.firstPageIndex <= currentPage }
        AlertDialog(
            onDismissRequest = { showChapters = false },
            title = { Text(stringResource(R.string.chapters)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    chapters.forEachIndexed { i, chapter ->
                        TextButton(
                            onClick = {
                                showChapters = false
                                jumpTo(chapter.firstPageIndex)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = chapter.title,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                                color = if (i == currentChapter) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChapters = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // Bookmarks: the per-page marks from the top-bar toggle.
    // Tapping one jumps to that page (same jump as the
    // slider); removing one is the toggle on that page. An
    // empty list states it plainly instead of showing a
    // blank dialog.
    if (showBookmarks) {
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            title = { Text(stringResource(R.string.comic_bookmarks)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (bookmarks.isEmpty()) {
                        Text(stringResource(R.string.comic_no_bookmarks))
                    } else {
                        bookmarks.sorted().forEach { page ->
                            TextButton(
                                onClick = {
                                    showBookmarks = false
                                    jumpTo(page)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.page_indicator, page + 1, pageCount
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start,
                                    color = if (page == currentPage) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        LocalContentColor.current
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBookmarks = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // Page overview: a thumbnail grid of the whole book, tap
    // to jump. Cells decode their own cheap thumbnails on
    // composition (like the scrub preview) and recycle them on
    // dispose, so a 500-page book never holds 500 bitmaps —
    // only the visible cells do. The current page gets a
    // primary border.
    if (showOverview && source != null) {
        val overviewSource = source!!
        AlertDialog(
            onDismissRequest = { showOverview = false },
            title = { Text(stringResource(R.string.comic_overview)) },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(pageCount) { page ->
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .aspectRatio(0.7f)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (page == currentPage) 2.dp else 0.dp,
                                    color = if (page == currentPage) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    showOverview = false
                                    jumpTo(page)
                                }
                        ) {
                            PageThumbnail(
                                source = overviewSource,
                                page = page,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOverview = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // Encrypted-archive password dialog: shown when the open
    // probe fails (no password yet, or a wrong one). Submitting
    // restarts the open with the password; cancelling lands on
    // the error screen instead of a spinner that never ends.
    if (showPasswordDialog) {
        var passwordText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showPasswordDialog = false
                openError = true
            },
            title = { Text(stringResource(R.string.comic_zip_password)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (passwordWrong) {
                        Text(
                            text = stringResource(R.string.comic_password_wrong),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextField(
                        value = passwordText,
                        onValueChange = { passwordText = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passwordText.isNotEmpty(),
                    onClick = {
                        zipPassword = passwordText.toCharArray()
                        showPasswordDialog = false
                    }
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPasswordDialog = false
                    openError = true
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Manual crop editor (ComicScreen's CropActivity, as a
    // full-screen dialog instead of an activity): the current
    // page, its saved rect (or the full frame), corner drags
    // to resize, inside drags to move. Apply saves the rect
    // (persisted per page, unlike ComicScreen's session-only
    // view tag); Reset clears the saved rect.
    if (showCropEditor) {
        val cropSource = source
        if (cropSource != null) {
            CropEditorDialog(
                source = cropSource,
                page = currentPage,
                viewRotation = viewRotation,
                screenWidthPx = screenWidthPx,
                initialRect = manualCropRects[currentPage],
                onApply = { rect ->
                    showCropEditor = false
                    scope.launch { extras.setCropRect(book.id, currentPage, rect) }
                },
                onReset = {
                    showCropEditor = false
                    scope.launch { extras.setCropRect(book.id, currentPage, null) }
                },
                onDismiss = { showCropEditor = false }
            )
        }
    }

    // View settings sheet (the gear in the top bar): everything
    // that used to crowd the top bar as text buttons, plus the
    // page background. It stays open while adjusting, so zoom,
    // mode and background can be tried in one visit; the close
    // button or a drag dismisses it. Nothing here touches the
    // reading position: mode keeps pendingModeTarget, zoom
    // re-lands on the same page, background only repaints the
    // letterbox.
    if (showViewSettings) {
        ModalBottomSheet(
            onDismissRequest = { showViewSettings = false },
            sheetState = viewSheetState
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.comic_view_settings),
                    style = MaterialTheme.typography.titleMedium
                )
                // Mode: a real choice, not buttons one of
                // which is mysteriously disabled. All three rows
                // are always tappable and the active one is
                // highlighted — the old pair (one enabled, one
                // dimmed) read as "one of these is broken",
                // which is how vertical mode stayed
                // undiscovered. Tapping the active mode is a
                // no-op, never a flip back.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.reading_mode),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SheetChoiceRow(
                        icon = AppIconKind.Pages,
                        label = stringResource(R.string.mode_horizontal),
                        selected = isPaged,
                        onClick = { setMode(ReadingMode.PAGED_HORIZONTAL) }
                    )
                    SheetChoiceRow(
                        icon = AppIconKind.Scroll,
                        label = stringResource(R.string.mode_vertical_continuous),
                        selected = !isPaged && !isHorizontalStrip,
                        onClick = { setMode(ReadingMode.CONTINUOUS_VERTICAL) }
                    )
                    SheetChoiceRow(
                        icon = AppIconKind.HorizontalScroll,
                        label = stringResource(R.string.mode_horizontal_continuous),
                        selected = isHorizontalStrip,
                        onClick = { setMode(ReadingMode.HORIZONTAL_STRIP) }
                    )
                }
                // Dual page (ComicScreen's 2p view): two pages
                // side by side per screen in paged mode, with a
                // landscape page split across the pair. Paged
                // only, like fit/direction/zoom — in vertical
                // mode the strip is one page wide by definition,
                // so the switch is dimmed there rather than
                // pretending to do something.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.comic_dual_page),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isPaged) {
                            LocalContentColor.current
                        } else {
                            LocalContentColor.current.copy(alpha = 0.38f)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = dualPage,
                        enabled = isPaged,
                        onCheckedChange = { checked ->
                            scope.launch { prefs.setComicDualPage(checked) }
                        }
                    )
                }
                // Zoom steps: paged-original only. In vertical
                // mode pages fill the strip width and pinch
                // zooms, so the stepper is dimmed there rather
                // than pretending to do something.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.comic_zoom),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isPaged) {
                            LocalContentColor.current
                        } else {
                            LocalContentColor.current.copy(alpha = 0.38f)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        enabled = isPaged && zoomIndex > 0,
                        onClick = { zoomIndex-- }
                    ) {
                        AppIcon(
                            AppIconKind.Minus,
                            tint = LocalContentColor.current,
                            size = 18.dp
                        )
                    }
                    Text(
                        text = when (zoomFactors[zoomIndex]) {
                            1.5f -> "1.5×"
                            2f -> "2×"
                            else -> "1×"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(
                        enabled = isPaged && zoomIndex < zoomFactors.lastIndex,
                        onClick = { zoomIndex++ }
                    ) {
                        AppIcon(
                            AppIconKind.Plus,
                            tint = LocalContentColor.current,
                            size = 18.dp
                        )
                    }
                    IconButton(
                        enabled = isPaged && zoomIndex != 0,
                        onClick = { zoomIndex = 0 }
                    ) {
                        AppIcon(
                            AppIconKind.Fullscreen,
                            tint = LocalContentColor.current,
                            size = 18.dp
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.comic_background),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val bgOptions = listOf(
                        Triple("dark", Color(0xFF1E1E1E), R.string.comic_bg_dark),
                        Triple("black", Color.Black, R.string.comic_bg_black),
                        Triple("white", Color.White, R.string.comic_bg_white)
                    )
                    bgOptions.forEach { (key, color, labelRes) ->
                        val selected = savedBgKey == key
                        val bgLabel = stringResource(labelRes)
                        Box(
                            modifier = Modifier.size(36.dp)
                                .clip(CircleShape)
                                .semantics { contentDescription = bgLabel }
                                .background(color)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        LocalContentColor.current.copy(alpha = 0.4f)
                                    },
                                    shape = CircleShape
                                )
                                .clickable {
                                    scope.launch {
                                        prefs.setComicBackground(key)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                AppIcon(
                                    AppIconKind.Check,
                                    tint = if (key == "white") {
                                        Color.Black
                                    } else {
                                        Color.White
                                    },
                                    size = 16.dp
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(
                            AppIconKind.Crop,
                            tint = LocalContentColor.current,
                            size = 16.dp
                        )
                        Text(
                            text = stringResource(R.string.comic_cut_margin),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(
                        checked = cutMargin,
                        onCheckedChange = { checked ->
                            scope.launch { prefs.setComicCutMargin(checked) }
                        }
                    )
                }
                // Kept margins after auto-cropping (ComicScreen's
                // set_img_crop_auto_pad_h/v, 0..100): each side
                // grows by this percent of the larger margin on
                // that axis. 0 = crop exactly to the detected
                // content box, the ComicScreen default.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(
                                AppIconKind.Crop,
                                tint = LocalContentColor.current,
                                size = 16.dp
                            )
                            Text(
                                text = stringResource(R.string.comic_crop_pad_h),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = "$cropPadH%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Slider(
                        value = cropPadH.toFloat(),
                        onValueChange = { v ->
                            scope.launch { prefs.setComicCropPadH(v.toInt()) }
                        },
                        valueRange = 0f..100f
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(
                                AppIconKind.Crop,
                                tint = LocalContentColor.current,
                                size = 16.dp
                            )
                            Text(
                                text = stringResource(R.string.comic_crop_pad_v),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = "$cropPadV%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Slider(
                        value = cropPadV.toFloat(),
                        onValueChange = { v ->
                            scope.launch { prefs.setComicCropPadV(v.toInt()) }
                        },
                        valueRange = 0f..100f
                    )
                }
                // Padding (ComicScreen's img_btn8 dialog): a
                // switch plus a ratio SeekBar, 0..40, displayed
                // as "- X%" because the image SHRINKS by that
                // much and the freed space becomes the margin.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(
                            AppIconKind.Padding,
                            tint = LocalContentColor.current,
                            size = 16.dp
                        )
                        Text(
                            text = stringResource(R.string.comic_page_margin),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(
                        checked = paddingEnabled,
                        onCheckedChange = { checked ->
                            scope.launch { prefs.setComicPaddingEnabled(checked) }
                        }
                    )
                }
                // The switch row above labels this slider, so
                // it carries no repeated label of its own — just
                // the slider and its value, indented as a sub-row
                // (the old header repeated "فاصله از حاشیه" and
                // made the sheet read as two identical settings).
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = pageMarginPct.toFloat(),
                        onValueChange = { v ->
                            scope.launch { prefs.setComicPageMargin(v.toInt()) }
                        },
                        valueRange = 0f..40f,
                        steps = 39,
                        enabled = paddingEnabled,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (pageMarginPct == 0) {
                            "0%"
                        } else {
                            "− $pageMarginPct%"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // Page fit (ComicScreen's fitscreen menu: off /
                // height / width / stretch). Paged mode only —
                // vertical mode always fills the strip width,
                // so these rows are dimmed there. "Original" is
                // ComicScreen's off: native pixels, pannable.
                // One full-width row per option: the old row of
                // four squeezed buttons wrapped the long
                // Persian labels word-per-line (the screenshot
                // showed "اندازه‌ی واقعی" stacked vertically).
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.comic_fit_mode),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SheetChoiceRow(
                        label = stringResource(R.string.comic_fit_width),
                        selected = fitMode == "width",
                        enabled = isPaged,
                        onClick = {
                            scope.launch { prefs.setComicFitMode("width") }
                        }
                    )
                    SheetChoiceRow(
                        label = stringResource(R.string.comic_fit_height),
                        selected = fitMode == "height",
                        enabled = isPaged,
                        onClick = {
                            scope.launch { prefs.setComicFitMode("height") }
                        }
                    )
                    SheetChoiceRow(
                        label = stringResource(R.string.comic_fit_original),
                        selected = fitMode == "original",
                        enabled = isPaged,
                        onClick = {
                            scope.launch { prefs.setComicFitMode("original") }
                        }
                    )
                    SheetChoiceRow(
                        label = stringResource(R.string.comic_fit_stretch),
                        selected = fitMode == "stretch",
                        enabled = isPaged,
                        onClick = {
                            scope.launch { prefs.setComicFitMode("stretch") }
                        }
                    )
                }
                // Page direction (ComicScreen's direction dialog,
                // pager half): right-to-left is the default for
                // the translated books this viewer was built for;
                // left-to-right flips the pager and both bottom
                // arrows. Paged mode only — continuous scroll
                // has no page order to flip.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.comic_direction),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SheetChoiceRow(
                        label = stringResource(R.string.comic_direction_rtl),
                        selected = directionRtl,
                        enabled = isPaged,
                        onClick = {
                            scope.launch { prefs.setComicDirectionRtl(true) }
                        }
                    )
                    SheetChoiceRow(
                        label = stringResource(R.string.comic_direction_ltr),
                        selected = !directionRtl,
                        enabled = isPaged,
                        onClick = {
                            scope.launch { prefs.setComicDirectionRtl(false) }
                        }
                    )
                }
                // Sharpen (ComicScreen's filter dialog: sharp
                // checkbox + level SeekBar). Off by default, like
                // there; the level slider is dimmed while off.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.comic_sharpen),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = sharpen,
                        onCheckedChange = { checked ->
                            scope.launch { prefs.setComicSharpen(checked) }
                        }
                    )
                }
                // Same de-duplication as the margin slider: the
                // switch row above is the label, so this is just
                // the slider and its level, indented as a sub-row
                // (the old header repeated "تیز کردن تصویر" and
                // the sheet showed two identical rows).
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = sharpenLevel.toFloat(),
                        onValueChange = { v ->
                            scope.launch { prefs.setComicSharpenLevel(v.toInt()) }
                        },
                        valueRange = 0f..10f,
                        steps = 9,
                        enabled = sharpen,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$sharpenLevel",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // Colour filters (ComicScreen's filter dialog:
                // gray / contrast / invert). All default off,
                // bitmap pages only — animated pages play as-is.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.comic_filter_gray),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = filterGray,
                        onCheckedChange = { checked ->
                            scope.launch { prefs.setComicFilterGray(checked) }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.comic_filter_contrast),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = filterContrast,
                        onCheckedChange = { checked ->
                            scope.launch { prefs.setComicFilterContrast(checked) }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.comic_filter_invert),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = filterInvert,
                        onCheckedChange = { checked ->
                            scope.launch { prefs.setComicFilterInvert(checked) }
                        }
                    )
                }
                // Upscale interpolation (ComicScreen's
                // set_img_filter_upscale_mode): Linear is the
                // cheap 2-tap, Cubic the previous default,
                // Lanczos the sharpest (and slowest). Upscale
                // only — downscale stays area-average.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.comic_upscale_mode),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SheetChoiceRow(
                        label = stringResource(R.string.comic_upscale_linear),
                        selected = upscaleMode == "linear",
                        onClick = {
                            scope.launch { prefs.setComicUpscaleMode("linear") }
                        }
                    )
                    SheetChoiceRow(
                        label = stringResource(R.string.comic_upscale_cubic),
                        selected = upscaleMode == "cubic",
                        onClick = {
                            scope.launch { prefs.setComicUpscaleMode("cubic") }
                        }
                    )
                    SheetChoiceRow(
                        label = stringResource(R.string.comic_upscale_lanczos),
                        selected = upscaleMode == "lanczos",
                        onClick = {
                            scope.launch { prefs.setComicUpscaleMode("lanczos") }
                        }
                    )
                }
                // Volume-key turning (default off: volume keys
                // normally adjust volume, and hijacking them is
                // a choice, not a default).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.comic_volume_keys),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = volumeKeysOn,
                        onCheckedChange = { checked ->
                            scope.launch { prefs.setComicVolumeKeys(checked) }
                        }
                    )
                }
                // Actions: one-tap jobs, not settings. Capture
                // and the dialogs close the sheet; Rotate stays
                // open, so its label shows the new degrees.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SheetChoiceRow(
                        icon = AppIconKind.Capture,
                        label = stringResource(R.string.comic_capture),
                        selected = false,
                        onClick = {
                            showViewSettings = false
                            capturePage()
                        }
                    )
                    SheetChoiceRow(
                        icon = AppIconKind.Rotate,
                        label = stringResource(R.string.comic_rotate) +
                            " · $viewRotation°",
                        selected = false,
                        onClick = { cycleRotation() }
                    )
                    SheetChoiceRow(
                        icon = AppIconKind.Crop,
                        label = stringResource(R.string.comic_manual_crop),
                        selected = false,
                        onClick = {
                            showViewSettings = false
                            showCropEditor = true
                        }
                    )
                    SheetChoiceRow(
                        icon = AppIconKind.Grid,
                        label = stringResource(R.string.comic_overview),
                        selected = false,
                        onClick = {
                            showViewSettings = false
                            showOverview = true
                        }
                    )
                    SheetChoiceRow(
                        icon = AppIconKind.Bookmark,
                        label = stringResource(R.string.comic_bookmarks),
                        selected = false,
                        onClick = {
                            showViewSettings = false
                            showBookmarks = true
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showViewSettings = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

/**
 * Manual crop editor: the current page at fit size, with a
 * draggable rect over it — corners resize, dragging inside
 * moves the whole rect (ComicScreen's CropImageView behaviour,
 * reimplemented: no third-party crop widget, just a Canvas
 * overlay and drag gestures). Coordinates are fractions of
 * the displayed page, so the saved rect applies at any decode
 * size. The dim outside the rect is four strips (no blend
 * modes, no layers); the handles are primary squares.
 */
@Composable
private fun CropEditorDialog(
    source: NativePageSource,
    page: Int,
    viewRotation: Int,
    screenWidthPx: Int,
    initialRect: CropRect?,
    onApply: (CropRect) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source, page, viewRotation) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val raw = source.renderPage(page, screenWidthPx)
                if (viewRotation != 0) {
                    val rotated = rotateBitmap(raw, viewRotation)
                    if (rotated !== raw) raw.recycle()
                    rotated
                } else {
                    raw
                }
            }.getOrNull()
        }
        bitmap = decoded
    }
    DisposableEffect(Unit) {
        onDispose { bitmap?.recycle() }
    }
    // Immutable data class: no copy needed, the drag code
    // replaces the state instead of mutating it.
    var rect by remember {
        mutableStateOf(initialRect ?: CropRect(0f, 0f, 1f, 1f))
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize()) {
                var areaSize by remember { mutableStateOf(IntSize.Zero) }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .onSizeChanged { areaSize = it },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    if (bmp != null && areaSize.width > 0 && areaSize.height > 0) {
                        val ratio = bmp.width.toFloat() / bmp.height
                        val areaRatio = areaSize.width.toFloat() / areaSize.height
                        // Contain: bound by width for wide pages,
                        // by height for tall ones — so the box
                        // IS the displayed image, and overlay
                        // fractions map to it exactly.
                        val boxModifier = if (ratio >= areaRatio) {
                            Modifier.fillMaxWidth().aspectRatio(ratio)
                        } else {
                            Modifier.fillMaxHeight().aspectRatio(ratio)
                        }
                        Box(
                            modifier = boxModifier.pointerInput(rect) {
                                // Drag target: nearest corner
                                // within 8% of the small side,
                                // else a move when inside, else
                                // ignored (a tap outside the rect
                                // does nothing — no new rect).
                                var target = -1
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        val threshold =
                                            minOf(w, h) * 0.08f
                                        val fx = offset.x / w
                                        val fy = offset.y / h
                                        val corners = listOf(
                                            rect.left to rect.top,
                                            rect.right to rect.top,
                                            rect.left to rect.bottom,
                                            rect.right to rect.bottom
                                        )
                                        target = corners.indexOfFirst { (cx, cy) ->
                                            val dx = (fx - cx) * w
                                            val dy = (fy - cy) * h
                                            dx * dx + dy * dy < threshold * threshold
                                        }
                                        if (target < 0 &&
                                            fx in rect.left..rect.right &&
                                            fy in rect.top..rect.bottom
                                        ) {
                                            target = 4
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        if (target < 0) return@detectDragGestures
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        val dx = dragAmount.x / w
                                        val dy = dragAmount.y / h
                                        val min = 0.05f
                                        var next = rect
                                        when (target) {
                                            0 -> next = next.copy(
                                                left = (next.left + dx).coerceIn(0f, next.right - min),
                                                top = (next.top + dy).coerceIn(0f, next.bottom - min)
                                            )
                                            1 -> next = next.copy(
                                                right = (next.right + dx).coerceIn(next.left + min, 1f),
                                                top = (next.top + dy).coerceIn(0f, next.bottom - min)
                                            )
                                            2 -> next = next.copy(
                                                left = (next.left + dx).coerceIn(0f, next.right - min),
                                                bottom = (next.bottom + dy).coerceIn(next.top + min, 1f)
                                            )
                                            3 -> next = next.copy(
                                                right = (next.right + dx).coerceIn(next.left + min, 1f),
                                                bottom = (next.bottom + dy).coerceIn(next.top + min, 1f)
                                            )
                                            else -> {
                                                // Move: clamp the
                                                // whole rect into
                                                // the frame.
                                                val shiftX = dx.coerceIn(
                                                    -next.left, 1f - next.right
                                                )
                                                val shiftY = dy.coerceIn(
                                                    -next.top, 1f - next.bottom
                                                )
                                                next = next.copy(
                                                    left = next.left + shiftX,
                                                    right = next.right + shiftX,
                                                    top = next.top + shiftY,
                                                    bottom = next.bottom + shiftY
                                                )
                                            }
                                        }
                                        rect = next
                                    }
                                )
                            }
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                            // MaterialTheme.colorScheme is a
                            // @Composable read: it must happen
                            // here, in the composable scope — not
                            // inside the Canvas lambda below,
                            // which is a DrawScope and cannot
                            // call composables (that was a
                            // compile error).
                            val cropPrimary =
                                MaterialTheme.colorScheme.primary
                            Canvas(Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val l = rect.left * w
                                val t = rect.top * h
                                val r = rect.right * w
                                val b = rect.bottom * h
                                val dim = Color.Black.copy(alpha = 0.55f)
                                drawRect(
                                    dim,
                                    androidx.compose.ui.geometry.Offset.Zero,
                                    androidx.compose.ui.geometry.Size(w, t)
                                )
                                drawRect(
                                    dim,
                                    androidx.compose.ui.geometry.Offset(0f, b),
                                    androidx.compose.ui.geometry.Size(w, h - b)
                                )
                                drawRect(
                                    dim,
                                    androidx.compose.ui.geometry.Offset.Zero,
                                    androidx.compose.ui.geometry.Size(l, h)
                                )
                                drawRect(
                                    dim,
                                    androidx.compose.ui.geometry.Offset(r, 0f),
                                    androidx.compose.ui.geometry.Size(w - r, h)
                                )
                                drawRect(
                                    Color.White,
                                    androidx.compose.ui.geometry.Offset(l, t),
                                    androidx.compose.ui.geometry.Size(r - l, b - t),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 2.dp.toPx()
                                    )
                                )
                                val handle = 14.dp.toPx()
                                listOf(
                                    l to t, r to t, l to b, r to b
                                ).forEach { (cx, cy) ->
                                    drawRect(
                                        cropPrimary,
                                        androidx.compose.ui.geometry.Offset(
                                            cx - handle / 2, cy - handle / 2
                                        ),
                                        androidx.compose.ui.geometry.Size(handle, handle)
                                    )
                                }
                            }
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
                Text(
                    text = stringResource(R.string.comic_crop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onApply(rect) }) {
                        Text(stringResource(R.string.comic_crop_apply))
                    }
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.comic_crop_reset))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

/**
 * A cheap page thumbnail (scrub preview, overview grid):
 * a sampled decode through the source's thumbnail path —
 * never the full pipeline — recycled when the cell leaves
 * composition. The dispose read is live on purpose (same
 * pattern as NativePageItem): the state object this closure
 * captures belongs to this remember instance, so a page
 * change disposes against the OLD instance's bitmap while
 * the effect restart above already recycled it.
 */
@Composable
private fun PageThumbnail(
    source: NativePageSource?,
    page: Int,
    modifier: Modifier,
    contentScale: ContentScale
) {
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source, page) {
        val old = thumb
        thumb = null
        old?.recycle()
        if (source == null) return@LaunchedEffect
        thumb = withContext(Dispatchers.IO) {
            runCatching { source.thumbnail(page, 240) }.getOrNull()
        }
    }
    DisposableEffect(Unit) {
        onDispose { thumb?.recycle() }
    }
    val bmp = thumb
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier.background(Color(0xFF1E1E1E))
        )
    } else {
        Box(
            modifier = modifier.background(Color(0xFF1E1E1E)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

/**
 * One full-width choice row for the view settings sheet
 * (mode, fit, direction). The old sheet squeezed several
 * long Persian option labels into one horizontal row, and
 * they wrapped word-per-line; one option per row, with the
 * active one highlighted and ticked, cannot squeeze. A
 * disabled row keeps its tick if it is the current value,
 * dimmed like its label, so "current but not changeable
 * in this mode" still reads.
 */
@Composable
private fun SheetChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: AppIconKind? = null,
    enabled: Boolean = true
) {
    val contentColor = if (enabled) {
        LocalContentColor.current
    } else {
        LocalContentColor.current.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            AppIcon(
                icon,
                tint = contentColor,
                size = 18.dp
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            AppIcon(
                AppIconKind.Check,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    contentColor
                },
                size = 18.dp
            )
        }
    }
}

/** Pixel budget for the software image steps (resize, sharpen):
 * above this, the transient IntArray copies would risk an OOM
 * on tall pages, so the native bitmap is kept for GPU display. */
private const val MAX_SOFTWARE_PIXELS = 8_000_000L

/**
 * An animated page (GIF) in a classic ImageView. Compose's Image
 * cannot play an AnimatedImageDrawable, so the drawable lives in
 * a View: update sets it and starts it, onRelease stops whatever
 * the view currently holds (robust against the drawable state
 * changing under it) and clears the view. The caller sizes the
 * view with the same modifiers as the bitmap path, so fit modes,
 * padding shrink and the unified-width slot behave identically —
 * FIT_CENTER draws the drawable into its aspect-correct box,
 * FIT_XY only for the stretch mode that ignores aspect.
 */
@Composable
private fun AnimatedPageImage(
    drawable: Drawable,
    modifier: Modifier,
    scaleType: ImageView.ScaleType,
    rotation: Float = 0f
) {
    AndroidView(
        factory = { context ->
            ImageView(context).apply { this.scaleType = scaleType }
        },
        update = { view ->
            view.rotation = rotation
            view.setImageDrawable(drawable)
            (drawable as? Animatable)?.start()
        },
        onRelease = { view ->
            (view.drawable as? Animatable)?.stop()
            view.setImageDrawable(null)
        },
        modifier = modifier.background(Color.White)
    )
}

/**
 * One original page. The bitmap is owned by THIS item: rendered on
 * IO when the item is composed, recycled when it leaves
 * composition. Sharing bitmaps through a cache was rejected on
 * purpose — a recycled bitmap still being drawn by another item
 * is a crash, and the visible window already bounds how many
 * items are alive at once, so a shared cache buys nothing here.
 *
 * In vertical mode the strip is ComicScreen's: every page fills
 * the strip width, one below the other, no horizontal scroll —
 * zoom and pan live on the page itself (pinch, see
 * ZoomablePageContainer). In paged mode horizontal drag is the
 * page turn, so the page is fit to the screen width and zoom
 * only sharpens it.
 */
@Composable
private fun NativePageItem(
    source: NativePageSource,
    page: Int,
    renderWidthPx: Int,
    paged: Boolean,
    zoomFactor: Float = 1f,
    cropMargin: Boolean = false,
    cropPadH: Int = 0,
    cropPadV: Int = 0,
    paddingRatio: Float = 0f,
    pageBg: Color = Color(0xFF1E1E1E),
    screenWidthPx: Int = 0,
    screenHeightPx: Int = 0,
    fitMode: String = "width",
    sharpen: Boolean = false,
    sharpenLevel: Int = 0,
    // Upscale interpolation ("linear" / "cubic" / "lanczos",
    // default cubic = the behaviour before this setting).
    upscaleMode: String = "cubic",
    // ComicScreen's colour filters, all default off, applied
    // after sharpen, bitmap pages only.
    filterGray: Boolean = false,
    filterContrast: Boolean = false,
    filterInvert: Boolean = false,
    // View rotation, degrees clockwise (0/90/180/270). The
    // Rotate action cycles it; 180 also mirrors navigation
    // direction (see the viewer). Animated pages rotate via
    // the ImageView, bitmap pages in the pipeline below.
    viewRotation: Int = 0,
    // Horizontal strip: the page fills the strip HEIGHT (not
    // width) and the resize target is height-based. Paged is
    // always false with this on; it only changes the strip
    // geometry, never the page order.
    horizontalStrip: Boolean = false,
    // Manual crop rect, in FRACTIONS of the displayed page
    // (0..1, from the crop editor): when set it replaces the
    // auto-crop for this page — the user drew it by hand.
    // Null = no manual crop (auto-crop as before).
    manualCropRect: CropRect? = null,
    // Dual-page half: 0 = the whole page, 1 = its left half,
    // 2 = its right half (IMAGE coordinates — the pair lays the
    // halves out in reading order). A half item renders its
    // half at the slot width (half the screen), never the full
    // page squeezed into the slot.
    halfSide: Int = 0
) {
    var bitmap by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableStateOf<Bitmap?>(null) }
    // An animated drawable (GIF / animated WebP pages only).
    // When one is present the bitmap path is skipped entirely:
    // the drawable IS the page, frames and all — a bitmap would
    // freeze it on frame one, which is what the viewer did
    // before this change.
    var drawable by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableStateOf<Drawable?>(null) }
    var failed by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableStateOf(false) }
    // The render error itself, shown under the failure text: the
    // old code threw it away (getOrNull), so a phone-only tester
    // could only ever report "it failed" and the cause stayed
    // invisible. The message names the entry and the reason
    // (unreadable image, missing entry, unsupported compression),
    // which is what a screenshot of this screen needs to carry.
    var failMessage by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableStateOf<String?>(null) }
    var retry by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableIntStateOf(0) }
    // Zoom re-render (ComicScreen's tile refinement,
    // coarse-grained): the pipeline sizes the bitmap to the
    // display width, so a 2x pinch would otherwise just upscale
    // that bitmap on the GPU and go soft. When the pinch passes
    // 1.75x the page re-renders at 2x display width (bicubic
    // from the native pixels, not from the 1x bitmap), and when
    // it falls back under 1.25x it re-renders at 1x. The
    // hysteresis stops flapping at the threshold. renderScale
    // is deliberately NOT in the bitmap/drawables' remember
    // keys: a re-render must swap the bitmap under the live
    // zoom container (which keeps its scale), not dispose the
    // container and snap the page back to 1x mid-pinch.
    var renderScale by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) { mutableIntStateOf(1) }
    // Threshold-guarded: the container reports every pinch move,
    // but only a threshold crossing re-renders.
    val onPinchScale: (Float) -> Unit = { s ->
        if (s > 1.75f && renderScale != 2) {
            renderScale = 2
        } else if (s < 1.25f && renderScale != 1) {
            renderScale = 1
        }
    }

    LaunchedEffect(page, renderWidthPx, retry, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect, renderScale) {
        // No bitmap/drawable nulling here on purpose: every key
        // above except renderScale is also a remember key, so a
        // page/parameter change already resets the states to
        // null through remember — and a renderScale change must
        // keep the old bitmap on screen until the sharper one
        // arrives, or the zoom container would dispose mid-pinch
        // (see renderScale). Only the failure flags reset: a
        // re-render that fails must not leave a stale failure
        // screen over a still-valid old bitmap (the branch below
        // shows the bitmap before the failure, so the old page
        // simply stays until its replacement arrives).
        failed = false
        failMessage = null
        // The bitmap this run replaces, recycled once its
        // replacement lands (not in onDispose: the dispose
        // effect below is keyed without renderScale, so it
        // would otherwise recycle the replacement too).
        val previousBitmap = bitmap
        // Animated path first: for a GIF page the drawable
        // replaces the bitmap path wholesale. For every other
        // format it returns null immediately (extension check)
        // and the bitmap path below runs exactly as before.
        // A drawable failure also falls through to the bitmap
        // path, so a corrupt GIF still shows its first frame
        // instead of a failure screen.
        val animated = withContext(Dispatchers.IO) {
            runCatching { source.animatedDrawable(page) }.getOrNull()
        }
        if (animated != null) {
            drawable = animated
            return@LaunchedEffect
        }
        val rendered = withContext(Dispatchers.IO) {
            runCatching {
                // The pipeline mirrors ComicScreen's per-page
                // filter order — view rotation first (so crop
                // detection sees the page as displayed), then
                // crop, upscale, sharpen, colour filters.
                // Each step replaces the bitmap and recycles the
                // previous one, so only one full-size bitmap per
                // item is ever alive.
                var current = source.renderPage(page, renderWidthPx)
                if (viewRotation != 0) {
                    val rotated = rotateBitmap(current, viewRotation)
                    if (rotated !== current) {
                        current.recycle()
                        current = rotated
                    }
                }
                if (manualCropRect != null) {
                    // Manual crop wins over auto-crop for this
                    // page: the rect is fractions of the
                    // (already rotated) page, from the editor.
                    val l = (manualCropRect.left * current.width).toInt()
                        .coerceIn(0, current.width - 1)
                    val t = (manualCropRect.top * current.height).toInt()
                        .coerceIn(0, current.height - 1)
                    val r = (manualCropRect.right * current.width).toInt()
                        .coerceIn(l + 1, current.width)
                    val b = (manualCropRect.bottom * current.height).toInt()
                        .coerceIn(t + 1, current.height)
                    if (r > l && b > t) {
                        val cropped = Bitmap.createBitmap(current, l, t, r - l, b - t)
                        current.recycle()
                        current = cropped
                    }
                } else if (cropMargin) {
                    val rect = detectContentRect(current, cropPadH, cropPadV)
                    if (rect != null) {
                        // createBitmap copies: the uncropped
                        // original must go now, or every cropped
                        // page leaks a full-size bitmap beside
                        // its crop.
                        val cropped = Bitmap.createBitmap(
                            current, rect.left, rect.top,
                            rect.width(), rect.height()
                        )
                        current.recycle()
                        current = cropped
                    }
                }
                // Resize to display size. Vertical mode sizes
                // every page to the screen width (ComicScreen's
                // vertical strip), so it resizes exactly like
                // paged fit-width; paged fit-height resizes to
                // the screen height; original/stretch never
                // resample (original is native by definition,
                // stretch is a GPU fill).
                // Smaller than display: bicubic upscale (a small
                // page stretched by the GPU goes soft). Larger
                // than display: area-average downscale — the GPU
                // downscales with plain bilinear filtering and no
                // mipmaps, which drops thin lines on line art,
                // and that display downscale is format-blind, so
                // it was the remaining softness for PNG/WebP/GIF
                // (their decodes are already pixel-exact; the
                // quality decode flag only changes JPEG).
                // Padding's shrink is ignored here on purpose:
                // resizing to the full screen size and letting
                // the GPU shave the padding off is still far
                // sharper than resampling from the wrong size,
                // and it keeps the padding slider from
                // re-rendering on every drag.
                // MEMORY BUDGET: every software step below
                // materializes 2-3 full-size IntArray copies
                // beside the bitmap itself. On a tall manhwa
                // strip (1200x12000 = 14M pixels) that is
                // ~170MB transient on top of the pager's
                // preloaded neighbours — enough to OOM the
                // heap, and runCatching turns the OOM into a
                // failed page instead of an image. So software
                // steps run only up to 8M pixels; bigger pages
                // keep the native bitmap for the GPU path, which
                // is exactly what the build before this change
                // did. The budget is re-checked per step, on
                // the current size: a page resized down first
                // is small by the time sharpen runs.
                // Display width for this item: the screen width
                // times the zoom re-render scale (1x, or 2x once
                // the pinch passes 1.75x), halved for a dual-page
                // half item — its slot is half the screen, and
                // resizing to the full screen would only make
                // the GPU downscale it back in the slot.
                val displayWidthPx = (screenWidthPx * renderScale)
                    .let { if (halfSide != 0) it / 2 else it }
                val resizeTarget: Pair<Int, Int>? = when {
                    // Horizontal strip: the display size is the
                    // strip HEIGHT (screen height x re-render
                    // scale), width follows the aspect — the
                    // mirror of the vertical strip's width-based
                    // target below. Checked first: a strip item
                    // is paged=false, so it would otherwise
                    // fall into the vertical branch.
                    horizontalStrip && screenHeightPx > 0 -> {
                        val displayHeightPx = screenHeightPx * renderScale
                        (displayHeightPx * current.width / current.height)
                            .coerceAtLeast(1) to displayHeightPx
                    }
                    !paged && displayWidthPx > 0 ->
                        displayWidthPx to
                            (displayWidthPx * current.height / current.width)
                                .coerceAtLeast(1)
                    paged && fitMode == "width" && displayWidthPx > 0 ->
                        displayWidthPx to
                            (displayWidthPx * current.height / current.width)
                                .coerceAtLeast(1)
                    paged && fitMode == "height" && screenHeightPx > 0 ->
                        (screenHeightPx * current.width / current.height)
                            .coerceAtLeast(1) to screenHeightPx
                    else -> null
                }
                if (resizeTarget != null) {
                    val (targetW, targetH) = resizeTarget
                    // TARGET-side budget, added after a real
                    // hole was found: the old check only looked
                    // at the current size, so a small page
                    // zoomed to 2x (3M px native -> 23M px
                    // target on a tall strip) passed it and
                    // materialized a 90MB+ bitmap with its
                    // transient copies — an OOM on a phone.
                    // Oversized targets keep the native bitmap
                    // for the GPU path, exactly like oversized
                    // sources.
                    if (current.width.toLong() * current.height <= MAX_SOFTWARE_PIXELS &&
                        targetW.toLong() * targetH <= MAX_SOFTWARE_PIXELS &&
                        current.width != targetW
                    ) {
                        val resized = if (current.width < targetW) {
                            upscaleByMode(current, targetW, targetH, upscaleMode)
                        } else {
                            resizeBox(current, targetW, targetH)
                        }
                        if (resized !== current) {
                            current.recycle()
                            current = resized
                        }
                    }
                }
                if (sharpen && sharpenLevel > 0 &&
                    current.width.toLong() * current.height <= MAX_SOFTWARE_PIXELS
                ) {
                    val sharp = sharpenUnsharp(current, sharpenLevel)
                    if (sharp !== current) {
                        current.recycle()
                        current = sharp
                    }
                }
                // ComicScreen's colour filters, after sharpen:
                // grayscale, contrast (histogram equalisation
                // on luma), invert. Same 8M budget as sharpen;
                // animated pages skip them (they have no
                // bitmap to filter — the drawable branch above
                // never reaches this pipeline).
                if (filterGray &&
                    current.width.toLong() * current.height <= MAX_SOFTWARE_PIXELS
                ) {
                    val gray = grayscale(current)
                    if (gray !== current) {
                        current.recycle()
                        current = gray
                    }
                }
                if (filterContrast &&
                    current.width.toLong() * current.height <= MAX_SOFTWARE_PIXELS
                ) {
                    val contrasted = contrastHistEq(current)
                    if (contrasted !== current) {
                        current.recycle()
                        current = contrasted
                    }
                }
                if (filterInvert &&
                    current.width.toLong() * current.height <= MAX_SOFTWARE_PIXELS
                ) {
                    val inverted = invertColors(current)
                    if (inverted !== current) {
                        current.recycle()
                        current = inverted
                    }
                }
                // Dual-page half, LAST: crop/resize/sharpen all
                // ran on the whole page (they need the full
                // image), and only the finished page is halved.
                // 1 = left half, 2 = right half, in IMAGE
                // coordinates — the pair lays them out in
                // reading order, so this never needs to know the
                // direction.
                if (halfSide != 0) {
                    val halfW = (current.width / 2).coerceAtLeast(1)
                    val x = if (halfSide == 1) 0 else halfW
                    val w = if (halfSide == 1) {
                        halfW
                    } else {
                        (current.width - halfW).coerceAtLeast(1)
                    }
                    val half = Bitmap.createBitmap(current, x, 0, w, current.height)
                    if (half !== current) {
                        current.recycle()
                        current = half
                    }
                }
                current
            }
        }
        rendered.onSuccess {
            bitmap = it
            // A re-render (zoom scale change) replaces a live
            // bitmap: the replaced one goes now, or every zoom
            // re-render leaks a full-size bitmap beside its
            // replacement. The dispose effect below is keyed
            // without renderScale, so it never fires on a
            // re-render — this is the only place the replaced
            // bitmap is freed, and the dispose effect frees the
            // final one.
            if (previousBitmap != null && previousBitmap !== it) {
                previousBitmap.recycle()
            }
        }
        rendered.onFailure {
            failed = true
            failMessage = it.message ?: it.javaClass.simpleName
        }
    }
    // Recycle the bitmap this item owns when it leaves
    // composition. The read is live on purpose: the state object
    // this closure captures belongs to this remember instance,
    // so a key change (new page/parameters) disposes against the
    // OLD instance's bitmap, while a zoom re-render — which is
    // NOT a key of this effect — swaps the bitmap without
    // disposing, and the dispose then frees the final bitmap,
    // not a stale one (the swapped-out ones were freed in
    // onSuccess above). Reading state in onDispose is safe
    // here precisely because the state object, not the value,
    // is what the closure holds.
    DisposableEffect(page, renderWidthPx, retry, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx, halfSide, upscaleMode, filterGray, filterContrast, filterInvert, viewRotation, horizontalStrip, manualCropRect) {
        onDispose {
            bitmap?.recycle()
            (drawable as? Animatable)?.stop()
        }
    }

    val density = LocalDensity.current
    when {
        drawable != null -> {
            // Animated page: the drawable plays as-is. Crop,
            // resize and sharpen are bitmap operations and do
            // not apply here — cropping an animation would mean
            // re-encoding the GIF frame by frame, which is out
            // of scope; the page is shown whole, in the same fit
            // modes and padding as a still page.
            val anim = drawable!!
            val ratio = if (anim.intrinsicWidth > 0 && anim.intrinsicHeight > 0) {
                anim.intrinsicWidth.toFloat() / anim.intrinsicHeight.toFloat()
            } else {
                1f
            }
            val shrink = if (paddingRatio > 0f) {
                (1f - 1.25f * paddingRatio).coerceAtLeast(0.5f)
            } else {
                1f
            }
            if (paged) {
                when (fitMode) {
                    "height" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedPageImage(
                                rotation = viewRotation.toFloat(),
                                drawable = anim,
                                scaleType = ImageView.ScaleType.FIT_CENTER,
                                modifier = Modifier
                                    .fillMaxHeight(shrink)
                                    .aspectRatio(ratio)
                            )
                        }
                    }
                    "original" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val nativeWidthDp = with(density) {
                                    (anim.intrinsicWidth * zoomFactor * shrink)
                                        .toInt().toDp()
                                }
                                AnimatedPageImage(
                                    rotation = viewRotation.toFloat(),
                                    drawable = anim,
                                    scaleType = ImageView.ScaleType.FIT_CENTER,
                                    modifier = Modifier
                                        .width(nativeWidthDp)
                                        .aspectRatio(ratio)
                                )
                            }
                        }
                    }
                    "stretch" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedPageImage(
                                rotation = viewRotation.toFloat(),
                                drawable = anim,
                                scaleType = ImageView.ScaleType.FIT_XY,
                                modifier = Modifier.fillMaxSize(shrink)
                            )
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .background(pageBg),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AnimatedPageImage(
                                rotation = viewRotation.toFloat(),
                                drawable = anim,
                                scaleType = ImageView.ScaleType.FIT_CENTER,
                                modifier = Modifier
                                    .fillMaxWidth(shrink)
                                    .aspectRatio(ratio)
                            )
                        }
                    }
                }
            } else {
                // Vertical mode, ComicScreen's strip: the page
                // fills the strip width, pages stack one below
                // the other, no horizontal scroll at all. Zoom
                // and pan live on the page itself (see
                // ZoomablePageContainer): the sheet's zoom steps
                // no longer change this display, pinch does.
                ZoomablePageContainer(
                    // Horizontal strip: fill the strip height
                    // instead of the width (see horizontalStrip).
                    modifier = if (horizontalStrip) {
                        Modifier.fillMaxHeight(shrink).aspectRatio(ratio)
                    } else {
                        Modifier.fillMaxWidth(shrink).aspectRatio(ratio)
                    }
                ) {
                    AnimatedPageImage(
                        rotation = viewRotation.toFloat(),
                        drawable = anim,
                        scaleType = ImageView.ScaleType.FIT_CENTER,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        bitmap != null -> {
            val bmp = bitmap!!
            val ratio = bmp.width.toFloat() / bmp.height.toFloat()
            // Padding ratio (ComicScreen's set_img_padding_ratio):
            // the displayed page shrinks — horizontally by
            // ratio * 1.25, the 1.25 compensating a portrait
            // screen so the inset looks even — and the freed
            // space becomes the margin. Aspect is preserved:
            // the width drives, the height follows the ratio.
            // 0 means off, exactly the old behaviour.
            val shrink = if (paddingRatio > 0f) {
                (1f - 1.25f * paddingRatio).coerceAtLeast(0.5f)
            } else {
                1f
            }
            if (paged) {
                // Page fit (ComicScreen's fitscreen menu). Width
                // is the old behaviour; height sizes the page to
                // the viewport height and pans sideways when the
                // page is wider; original shows native pixels
                // (times the zoom factor) and pans both ways;
                // stretch fills the screen and ignores aspect.
                // Zoom is deliberately ignored by width/height/
                // stretch: those modes size the page to the
                // screen, so a zoom factor would only fight the
                // fit. Original is the mode where zoom means
                // something, and it applies there.
                when (fitMode) {
                    "height" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxHeight(shrink)
                                    .aspectRatio(ratio)
                                    .background(Color.White)
                            )
                        }
                    }
                    "original" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val nativeWidthDp = with(density) {
                                    (bmp.width * zoomFactor * shrink).toInt().toDp()
                                }
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .width(nativeWidthDp)
                                        .aspectRatio(ratio)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                    "stretch" -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(pageBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .fillMaxSize(shrink)
                                    .background(Color.White)
                            )
                        }
                    }
                    else -> {
                        // Fit-width, now with the same pinch
                        // zoom the strip has (ComicScreen's
                        // PhotoView per page): before this, a
                        // paged page could only zoom through
                        // the sheet stepper in original mode,
                        // and width mode ignored zoom entirely.
                        // The container's edge handoff IS the
                        // zoom lock: at 1x a drag is never
                        // consumed, so the pager turns; while
                        // zoomed a drag pans, and at the pan
                        // edge the pager takes over again — the
                        // page never fights the page turn. The
                        // Column's vertical scroll is the tall
                        // page's own scroll (ComicScreen wraps
                        // an over-tall page in its own vertical
                        // ScrollView inside the pager), so a
                        // tall page is never cut in paged mode
                        // either: it scrolls vertically inside
                        // its page, exactly like the strip.
                        Column(
                            modifier = Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .background(pageBg),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ZoomablePageContainer(
                                modifier = Modifier
                                    .fillMaxWidth(shrink)
                                    .aspectRatio(ratio),
                                onScaleChange = onPinchScale
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }
            } else {
                // Vertical mode, ComicScreen's strip: the page
                // fills the strip width, pages stack one below
                // the other, no horizontal scroll at all. Zoom
                // and pan live on the page itself (see
                // ZoomablePageContainer): the sheet's zoom steps
                // no longer change this display, pinch does.
                // The bitmap was already resized to the screen
                // width by the pipeline above, so fillMaxWidth
                // draws it 1:1 (up to the padding shrink).
                ZoomablePageContainer(
                    // Horizontal strip: fill the strip height
                    // instead of the width (see horizontalStrip).
                    modifier = if (horizontalStrip) {
                        Modifier.fillMaxHeight(shrink).aspectRatio(ratio)
                    } else {
                        Modifier.fillMaxWidth(shrink).aspectRatio(ratio)
                    },
                    onScaleChange = onPinchScale
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                            .background(Color.White)
                    )
                }
            }
        }
        failed -> {
            // The placeholder sits on a WHITE background, so its
            // text must be dark explicitly: with the app's dark
            // reader theme the default onSurface colour is light,
            // which used to render this message almost invisible
            // (the screenshot that reported this bug showed it as
            // faint grey on white).
            if (paged) {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.White),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.pdf_page_failed),
                        textAlign = TextAlign.Center,
                        color = Color(0xFF1E1E1E)
                    )
                    failMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            textAlign = TextAlign.Center,
                            color = Color(0xFF555555),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { retry++ }) {
                        Text(stringResource(R.string.pdf_retry))
                    }
                }
            } else {
                Column(
                    modifier = if (horizontalStrip) Modifier.fillMaxHeight().width(200.dp) else Modifier.fillMaxWidth().height(200.dp)
                        .background(Color.White),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.pdf_page_failed),
                        textAlign = TextAlign.Center,
                        color = Color(0xFF1E1E1E)
                    )
                    failMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            textAlign = TextAlign.Center,
                            color = Color(0xFF555555),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { retry++ }) {
                        Text(stringResource(R.string.pdf_retry))
                    }
                }
            }
        }
        else -> {
            Box(
                modifier = if (paged) {
                    Modifier.fillMaxSize()
                } else {
                    if (horizontalStrip) Modifier.fillMaxHeight().width(200.dp) else Modifier.fillMaxWidth().height(200.dp)
                },
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
