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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.util.zip.ZipFile
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
     * An animated drawable for one page, or null. Only GIF pages
     * have one: BitmapFactory decodes a GIF to its FIRST frame
     * only, so an animated page needs its own path — decoded
     * with ImageDecoder into an AnimatedImageDrawable that the
     * viewer plays in an ImageView. Every other format returns
     * null and renders through [renderPage] as before. Default
     * null, so existing sources keep compiling unchanged.
     */
    fun animatedDrawable(index: Int): Drawable? = null

    /**
     * Native pixel size of one page (width to height), without
     * decoding its pixels — a bounds-only decode. Used to lay
     * out a book's pages at one unified display width. Sources
     * that render at a caller-chosen width (PDF) return null:
     * they have no native size to unify. Default null, so
     * existing sources keep compiling unchanged.
     */
    fun nativePageSize(index: Int): Pair<Int, Int>? = null
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
 */
internal class CbzNativeSource(zipFile: File) : NativePageSource {
    private val lock = Any()
    private val zip = ZipFile(zipFile)
    private val entries: List<String> = CbzBook.imageEntryNames(zipFile)

    override val pageCount: Int
        get() = entries.size

    override val chapters: List<CbzChapter> =
        CbzBook.chaptersForEntries(entries)

    override fun nativePageSize(index: Int): Pair<Int, Int>? {
        synchronized(lock) {
            if (index < 0 || index >= entries.size) return null
            val entry = zip.getEntry(entries[index]) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                zip.getInputStream(entry).use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            return bounds.outWidth to bounds.outHeight
        }
    }

    override fun animatedDrawable(index: Int): Drawable? {
        synchronized(lock) {
            if (index < 0 || index >= entries.size) return null
            // Extension check only: a GIF entry is the one case
            // where the bitmap path loses information (all frames
            // but the first), so it alone takes the drawable path.
            if (!entries[index].endsWith(".gif", ignoreCase = true)) return null
            val entry = zip.getEntry(entries[index]) ?: return null
            return runCatching {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                )
            }.getOrNull()
        }
    }

    override fun renderPage(index: Int, targetWidthPx: Int): Bitmap {
        synchronized(lock) {
            if (index < 0 || index >= entries.size) {
                throw IndexOutOfBoundsException("Comic page $index out of range")
            }
            val entry = zip.getEntry(entries[index])
                ?: throw IllegalArgumentException("Missing comic entry: ${entries[index]}")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IllegalArgumentException("Unreadable comic image: ${entries[index]}")
            }
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
            while (bounds.outWidth / sample > MAX_DECODE_WIDTH ||
                bounds.outHeight / sample > MAX_DECODE_HEIGHT
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
            fun decodeAs(config: Bitmap.Config): Bitmap? {
                return runCatching {
                    zip.getInputStream(entry).use { stream ->
                        BitmapFactory.decodeStream(
                            stream, null,
                            BitmapFactory.Options().apply {
                                inSampleSize = sample
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
            val bitmap = decodeAs(Bitmap.Config.ARGB_8888)
                ?: decodeAs(Bitmap.Config.RGB_565)
                ?: throw IllegalArgumentException("Unreadable comic image: ${entries[index]}")
            return bitmap
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { zip.close() }
        }
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
                sourceFactory = { CbzNativeSource(bookFile) },
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
                sourceFactory = { CbzNativeSource(resolvedFile) },
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
    sourceFactory: () -> NativePageSource,
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
    val modeNow = ReadingMode.fromKey(savedModeKey)
    val isPaged = modeNow == ReadingMode.PAGED_HORIZONTAL

    var source by remember { mutableStateOf<NativePageSource?>(null) }
    var openError by remember { mutableStateOf(false) }
    var totalChaptersFixed by remember { mutableStateOf(false) }

    // Open on IO: renderer/zip construction parses the file header
    // and must never block the main thread on a big book.
    LaunchedEffect(book.id, book.filePath) {
        val opened = withContext(Dispatchers.IO) {
            runCatching { sourceFactory() }.getOrNull()
        }
        if (opened == null || opened.pageCount <= 0) {
            runCatching { opened?.close() }
            openError = true
        } else {
            source = opened
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

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pageCount }
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
    LaunchedEffect(currentPage, pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        val toCredit = xpTracker.enter(currentPage) ?: return@LaunchedEffect
        xpCreditPage(toCredit)
    }
    DisposableEffect(book.id) {
        onDispose {
            val tracker = xpTracker
            xpFlushScope.launch {
                runCatching {
                    val flushed = tracker.flush()
                    if (flushed != null) xpCreditPage(flushed)
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
    LaunchedEffect(pageCount, isPaged) {
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
                runCatching { pagerState.scrollToPage(target) }
            } else {
                runCatching { listState.scrollToItem(target) }
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
    LaunchedEffect(listState, pagerState, pageCount, isPaged) {
        if (pageCount <= 0) return@LaunchedEffect
        if (isPaged) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                if (page in 0 until pageCount) {
                    currentPage = page
                    savePage(page)
                }
            }
        } else {
            snapshotFlow { listState.firstVisibleItemIndex }.collect { page ->
                if (page in 0 until pageCount) {
                    currentPage = page
                    savePage(page)
                }
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
            }
            savePage(currentPage, force = true)
            onClose()
        }
    }

    fun toggleMode() {
        val next = if (isPaged) {
            ReadingMode.CONTINUOUS_VERTICAL
        } else {
            ReadingMode.PAGED_HORIZONTAL
        }
        pendingModeTarget = currentPage
        scope.launch {
            withContext(Dispatchers.IO) {
                prefs.setReadingMode(next.key)
            }
        }
    }

    /** Direct jump (slider, restore, zoom): never animates through pages. */
    fun jumpTo(page: Int) {
        scope.launch {
            val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            if (isPaged) {
                runCatching { pagerState.scrollToPage(target) }
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
                runCatching { pagerState.animateScrollToPage(target) }
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
    val directionRtl by prefs.comicDirectionRtl.collectAsState(initial = true)
    val sharpen by prefs.comicSharpen.collectAsState(initial = false)
    val sharpenLevel by prefs.comicSharpenLevel.collectAsState(initial = 5)
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
                        // Icon shows the mode the tap SWITCHES to:
                        // a scroll glyph while paged, a pages glyph
                        // while continuous.
                        IconButton(onClick = { toggleMode() }) {
                            AppIcon(
                                if (isPaged) {
                                    AppIconKind.Scroll
                                } else {
                                    AppIconKind.Pages
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
                            value = (sliderPreview ?: currentPage).toFloat(),
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
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { chromeVisible = !chromeVisible })
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
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                        .background(pageBg),
                    // Page direction (ComicScreen's
                    // set_img_page_direction_pager): the pager
                    // already reads right-to-left in this RTL
                    // layout, so left-to-right is the reversed
                    // layout, not a different component.
                    reverseLayout = !directionRtl,
                    beyondViewportPageCount = 1
                ) { page ->
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
                        sharpenLevel = sharpenLevel
                    )
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
                            sharpenLevel = sharpenLevel
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !isPaged,
                        onClick = { toggleMode() }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(
                                AppIconKind.Pages,
                                tint = LocalContentColor.current,
                                size = 15.dp
                            )
                            Text(stringResource(R.string.mode_horizontal))
                        }
                    }
                    TextButton(
                        enabled = isPaged,
                        onClick = { toggleMode() }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(
                                AppIconKind.Scroll,
                                tint = LocalContentColor.current,
                                size = 15.dp
                            )
                            Text(stringResource(R.string.mode_vertical_continuous))
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        enabled = zoomIndex > 0,
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
                        enabled = zoomIndex < zoomFactors.lastIndex,
                        onClick = { zoomIndex++ }
                    ) {
                        AppIcon(
                            AppIconKind.Plus,
                            tint = LocalContentColor.current,
                            size = 18.dp
                        )
                    }
                    IconButton(
                        enabled = zoomIndex != 0,
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
                                AppIconKind.Padding,
                                tint = LocalContentColor.current,
                                size = 16.dp
                            )
                            Text(
                                text = stringResource(R.string.comic_page_margin),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = if (pageMarginPct == 0) {
                                "0%"
                            } else {
                                "− $pageMarginPct%"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Slider(
                        value = pageMarginPct.toFloat(),
                        onValueChange = { v ->
                            scope.launch { prefs.setComicPageMargin(v.toInt()) }
                        },
                        valueRange = 0f..40f,
                        steps = 39,
                        enabled = paddingEnabled
                    )
                }
                // Page fit (ComicScreen's fitscreen menu: off /
                // height / width / stretch). Paged mode only —
                // continuous mode always shows native size, so
                // these buttons are dimmed there; the mode row
                // above switches to paged. "Original" is
                // ComicScreen's off: native pixels, pannable.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.comic_fit_mode),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = isPaged && fitMode != "width",
                            onClick = {
                                scope.launch { prefs.setComicFitMode("width") }
                            }
                        ) {
                            Text(stringResource(R.string.comic_fit_width))
                        }
                        TextButton(
                            enabled = isPaged && fitMode != "height",
                            onClick = {
                                scope.launch { prefs.setComicFitMode("height") }
                            }
                        ) {
                            Text(stringResource(R.string.comic_fit_height))
                        }
                        TextButton(
                            enabled = isPaged && fitMode != "original",
                            onClick = {
                                scope.launch { prefs.setComicFitMode("original") }
                            }
                        ) {
                            Text(stringResource(R.string.comic_fit_original))
                        }
                        TextButton(
                            enabled = isPaged && fitMode != "stretch",
                            onClick = {
                                scope.launch { prefs.setComicFitMode("stretch") }
                            }
                        ) {
                            Text(stringResource(R.string.comic_fit_stretch))
                        }
                    }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = isPaged && !directionRtl,
                            onClick = {
                                scope.launch { prefs.setComicDirectionRtl(true) }
                            }
                        ) {
                            Text(stringResource(R.string.comic_direction_rtl))
                        }
                        TextButton(
                            enabled = isPaged && directionRtl,
                            onClick = {
                                scope.launch { prefs.setComicDirectionRtl(false) }
                            }
                        ) {
                            Text(stringResource(R.string.comic_direction_ltr))
                        }
                    }
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.comic_sharpen),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "$sharpenLevel",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Slider(
                        value = sharpenLevel.toFloat(),
                        onValueChange = { v ->
                            scope.launch { prefs.setComicSharpenLevel(v.toInt()) }
                        },
                        valueRange = 0f..10f,
                        steps = 9,
                        enabled = sharpen
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
    scaleType: ImageView.ScaleType
) {
    AndroidView(
        factory = { context ->
            ImageView(context).apply { this.scaleType = scaleType }
        },
        update = { view ->
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
    sharpenLevel: Int = 0
) {
    var bitmap by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx) { mutableStateOf<Bitmap?>(null) }
    // An animated drawable (GIF pages only). When one is present
    // the bitmap path is skipped entirely: the drawable IS the
    // page, frames and all — a bitmap would freeze it on frame
    // one, which is what the viewer did before this change.
    var drawable by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx) { mutableStateOf<Drawable?>(null) }
    var failed by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx) { mutableStateOf(false) }
    // The render error itself, shown under the failure text: the
    // old code threw it away (getOrNull), so a phone-only tester
    // could only ever report "it failed" and the cause stayed
    // invisible. The message names the entry and the reason
    // (unreadable image, missing entry, unsupported compression),
    // which is what a screenshot of this screen needs to carry.
    var failMessage by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx) { mutableStateOf<String?>(null) }
    var retry by remember(page, renderWidthPx, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx) { mutableIntStateOf(0) }

    LaunchedEffect(page, renderWidthPx, retry, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx) {
        bitmap = null
        drawable = null
        failed = false
        failMessage = null
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
                // filter order: crop, then upscale, then sharpen.
                // Each step replaces the bitmap and recycles the
                // previous one, so only one full-size bitmap per
                // item is ever alive.
                var current = source.renderPage(page, renderWidthPx)
                if (cropMargin) {
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
                val resizeTarget: Pair<Int, Int>? = when {
                    !paged && screenWidthPx > 0 ->
                        screenWidthPx to
                            (screenWidthPx * current.height / current.width)
                                .coerceAtLeast(1)
                    paged && fitMode == "width" && screenWidthPx > 0 ->
                        screenWidthPx to
                            (screenWidthPx * current.height / current.width)
                                .coerceAtLeast(1)
                    paged && fitMode == "height" && screenHeightPx > 0 ->
                        (screenHeightPx * current.width / current.height)
                            .coerceAtLeast(1) to screenHeightPx
                    else -> null
                }
                if (resizeTarget != null &&
                    current.width.toLong() * current.height <= MAX_SOFTWARE_PIXELS
                ) {
                    val (targetW, targetH) = resizeTarget
                    if (current.width != targetW) {
                        val resized = if (current.width < targetW) {
                            upscaleBicubic(current, targetW, targetH)
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
                current
            }
        }
        rendered.onSuccess { bitmap = it }
        rendered.onFailure {
            failed = true
            failMessage = it.message ?: it.javaClass.simpleName
        }
    }
    // Recycle exactly the bitmap this item rendered, and only when
    // this item (page + width + retry generation) leaves composition.
    // Same trap as the source close above: `bitmap` is state, so
    // reading it in onDispose could recycle a NEWER bitmap that a
    // re-render already assigned. Capture it in a local val.
    DisposableEffect(page, renderWidthPx, retry, cropMargin, cropPadH, cropPadV, fitMode, sharpen, sharpenLevel, screenWidthPx, screenHeightPx) {
        val toRecycle = bitmap
        val toStop = drawable
        onDispose {
            toRecycle?.recycle()
            (toStop as? Animatable)?.stop()
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
                    modifier = Modifier.fillMaxWidth(shrink)
                        .aspectRatio(ratio)
                ) {
                    AnimatedPageImage(
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
                        Column(
                            modifier = Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .background(pageBg),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth(shrink)
                                    .aspectRatio(ratio)
                                    .background(Color.White)
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
                // The bitmap was already resized to the screen
                // width by the pipeline above, so fillMaxWidth
                // draws it 1:1 (up to the padding shrink).
                ZoomablePageContainer(
                    modifier = Modifier.fillMaxWidth(shrink)
                        .aspectRatio(ratio)
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
                    modifier = Modifier.fillMaxWidth().height(200.dp)
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
                    Modifier.fillMaxWidth().height(200.dp)
                },
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
