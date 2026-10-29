package com.novelfa.reader.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind
import java.io.Closeable
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native PDF reader (2026-10-29): the fix for the PDF report, not
 * another conversion round.
 *
 * Why this exists: the old path converted a PDF to EPUB-like XHTML
 * (hand-rolled [com.novelfa.reader.parser.PdfParser] for text, JPEG
 * pre-renders for scans) and only then showed it in the EPUB WebView.
 * That made first open pay for the WHOLE book, lost the original
 * page layout, links and fidelity, and capped scanned books at
 * 1000 pages. The three readers compared in the report all do the
 * opposite: a PDF stays a PDF, import only registers it, and the
 * viewer renders one original page at a time, on demand.
 *
 * This file does exactly that, with the platform renderer that was
 * already in the app (android.graphics.pdf.PdfRenderer, i.e. Pdfium;
 * no new dependency, no native library to ship):
 *
 * - No text extraction, no XHTML, no pre-rendered JPEG files. A page
 *   is rendered only when its list item is composed (LazyColumn
 *   windows composition to the visible pages plus a few neighbours),
 *   so opening a 500-page book costs one page, not 500.
 * - No 1000-page cap: nothing is pre-rendered or stored, so a long
 *   book is no more expensive to open than a short one. Memory is
 *   bounded by the LazyColumn window, not by the book: each page
 *   item owns its bitmap and recycles it when it leaves composition.
 * - One original page is never cut: each item is exactly one PDF
 *   page at its own aspect ratio, fit to the screen width by
 *   default. Zoom only re-renders that page at a larger width, it
 *   never reflows text.
 * - A page that fails to render fails alone (placeholder + retry),
 *   it never kills the book — the per-page isolation ReadEra uses.
 * - Progress reuses the existing book row: for a PDF,
 *   [BookEntity.lastChapterIndex] IS the page index and
 *   [BookEntity.totalChapters] IS the page count, so the library
 *   progress bar and "finished" logic work unchanged. Lazy import
 *   left totalChapters at 0; the first native open fills it in.
 *
 * Deliberate v1 limits, stated not hidden:
 * - Vertical-continuous only. The global paged/vertical toggle is
 *   an EPUB pager concept; a paged PDF mode (one original page per
 *   screen, swipe) is a follow-up, and vertical already satisfies
 *   the page model (whole page, never cut, vertical scroll inside
 *   a tall page).
 * - No text search, selection, annotation or XP credit yet. Those
 *   need page-coordinate data the old XHTML path also threw away;
 *   they belong on top of this viewer, not on the converter.
 * - Password PDFs work because import already stores them
 *   decrypted; the platform renderer cannot open an encrypted file
 *   at all, so this viewer never sees one.
 */
internal class PdfNativeRenderer(pdfFile: File) : Closeable {
    private val lock = Any()
    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(pfd)

    /** Page count, read once the renderer is open; 0 is never valid. */
    val pageCount: Int
        get() = renderer.pageCount

    /**
     * Renders one page to a fresh bitmap the CALLER owns (and must
     * recycle). PdfRenderer is single-threaded, so every render is
     * serialized on [lock]; callers must call from Dispatchers.IO,
     * never from the main thread. Height is capped at 8192px with
     * the width scaled down to match, so an absurdly tall page
     * cannot allocate an unbounded bitmap.
     */
    fun renderPage(index: Int, targetWidthPx: Int): Bitmap {
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
 * Route wrapper: decides, from the stored file alone, whether this
 * book gets the native PDF viewer or the existing EPUB/WebView
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
    var entity by remember { mutableStateOf<BookEntity?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(bookId) {
        val found = withContext(Dispatchers.IO) { dao.getById(bookId) }
        if (found == null) missing = true else entity = found
    }
    when {
        missing -> {
            Scaffold { padding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.couldn_t_open_the_book))
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onClose) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }
        entity == null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        BookFormat.detect(File(entity!!.filePath)) == BookFormat.PDF -> {
            PdfNativeViewer(book = entity!!, dao = dao, onClose = onClose)
        }
        else -> {
            ReaderScreen(bookId = bookId, dao = dao, onClose = onClose, support = support)
        }
    }
}

/**
 * The native viewer itself. Render width is the screen width times
 * the zoom factor (1x / 1.5x / 2x, fit-width is 1x); changing zoom
 * re-renders visible pages at the new width, it never scales a
 * stale bitmap up, so zoom stays sharp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfNativeViewer(
    book: BookEntity,
    dao: BookDao,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pdfFile = remember(book.filePath) { File(book.filePath) }

    var renderer by remember { mutableStateOf<PdfNativeRenderer?>(null) }
    var openError by remember { mutableStateOf(false) }
    var totalChaptersFixed by remember { mutableStateOf(false) }

    // Open on IO: PdfRenderer construction parses the file header
    // and must never block the main thread on a big PDF.
    LaunchedEffect(book.id, book.filePath) {
        val opened = withContext(Dispatchers.IO) {
            runCatching { PdfNativeRenderer(pdfFile) }.getOrNull()
        }
        if (opened == null || opened.pageCount <= 0) {
            runCatching { opened?.close() }
            openError = true
        } else {
            renderer = opened
        }
    }
    DisposableEffect(renderer) {
        onDispose { renderer?.close() }
    }

    val pageCount = renderer?.pageCount ?: 0
    val savedPage = book.lastChapterIndex
    // The list state is remembered before the renderer opens (so its
    // initial index is always 0); the saved page is restored by the
    // scroll below, once the real page count is known. Without it,
    // every PDF reopen would land on page 1.
    var restoredPage by remember { mutableStateOf(false) }
    val initialPage = 0

    // Fill in the lazy-import placeholder (totalChapters == 0) the
    // first time the real page count is known, without touching the
    // saved page index.
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
    var currentPage by remember { mutableIntStateOf(initialPage) }
    LaunchedEffect(pageCount) {
        if (pageCount > 0 && !restoredPage) {
            restoredPage = true
            val target = savedPage.coerceIn(0, pageCount - 1)
            if (target != 0) {
                currentPage = target
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
    // a DB write per page.
    LaunchedEffect(listState, pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collect { page ->
            if (page in 0 until pageCount) {
                currentPage = page
                savePage(page)
            }
        }
    }

    fun closeViewer() {
        scope.launch {
            savePage(currentPage, force = true)
            onClose()
        }
    }

    val density = LocalDensity.current
    val screenWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx().toInt()
    }
    // Zoom steps: fit-width, 1.5x, 2x. Never below fit-width: a PDF
    // page smaller than the screen is a wasted screen, not a feature.
    val zoomFactors = remember { listOf(1f, 1.5f, 2f) }
    var zoomIndex by remember { mutableIntStateOf(0) }
    val renderWidthPx = (screenWidthPx * zoomFactors[zoomIndex]).toInt().coerceAtLeast(1)
    // After a zoom change the item heights change; land back on the
    // same page instead of wherever the new heights dropped us.
    LaunchedEffect(zoomIndex) {
        if (pageCount > 0) {
            runCatching { listState.scrollToItem(currentPage) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = book.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                    TextButton(
                        enabled = zoomIndex > 0,
                        onClick = { zoomIndex-- }
                    ) { Text(stringResource(R.string.pdf_zoom_out)) }
                    TextButton(
                        enabled = zoomIndex < zoomFactors.lastIndex,
                        onClick = { zoomIndex++ }
                    ) { Text(stringResource(R.string.pdf_zoom_in)) }
                    TextButton(
                        enabled = zoomIndex != 0,
                        onClick = { zoomIndex = 0 }
                    ) { Text(stringResource(R.string.pdf_fit_width)) }
                }
            )
        },
        bottomBar = {
            if (pageCount > 0) {
                BottomAppBar {
                    TextButton(
                        enabled = currentPage > 0,
                        onClick = {
                            scope.launch {
                                listState.scrollToItem((currentPage - 1).coerceAtLeast(0))
                            }
                        }
                    ) { Text(stringResource(R.string.pdf_prev_page)) }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(
                                R.string.page_indicator,
                                currentPage + 1, pageCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Slider(
                            value = currentPage.toFloat(),
                            onValueChange = { v ->
                                currentPage = v.toInt().coerceIn(0, pageCount - 1)
                            },
                            onValueChangeFinished = {
                                scope.launch {
                                    listState.scrollToItem(currentPage)
                                }
                            },
                            valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
                            steps = (pageCount - 2).coerceIn(0, 1000)
                        )
                    }
                    TextButton(
                        enabled = currentPage < pageCount - 1,
                        onClick = {
                            scope.launch {
                                listState.scrollToItem((currentPage + 1).coerceAtMost(pageCount - 1))
                            }
                        }
                    ) { Text(stringResource(R.string.pdf_next_page)) }
                }
            }
        }
    ) { padding ->
        when {
            openError -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.pdf_native_error))
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onClose) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
            renderer == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.pdf_native_loading))
                    }
                }
            }
            else -> {
                val activeRenderer = renderer!!
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding)
                        .background(Color(0xFF1E1E1E)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pageCount) { page ->
                        PdfPageItem(
                            renderer = activeRenderer,
                            page = page,
                            renderWidthPx = renderWidthPx
                        )
                    }
                }
            }
        }
    }
}

/**
 * One original page. The bitmap is owned by THIS item: rendered on
 * IO when the item is composed, recycled when it leaves
 * composition. Sharing bitmaps through a cache was rejected on
 * purpose — a recycled bitmap still being drawn by another item
 * is a crash, and LazyColumn already bounds how many items are
 * alive at once, so a shared cache buys nothing here.
 */
@Composable
private fun PdfPageItem(
    renderer: PdfNativeRenderer,
    page: Int,
    renderWidthPx: Int
) {
    var bitmap by remember(page, renderWidthPx) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(page, renderWidthPx) { mutableStateOf(false) }
    var retry by remember(page, renderWidthPx) { mutableIntStateOf(0) }

    LaunchedEffect(page, renderWidthPx, retry) {
        bitmap = null
        failed = false
        val rendered = withContext(Dispatchers.IO) {
            runCatching { renderer.renderPage(page, renderWidthPx) }.getOrNull()
        }
        if (rendered == null) failed = true else bitmap = rendered
    }
    // Recycle exactly the bitmap this item rendered, and only when
    // this item (page + width + retry generation) leaves composition.
    DisposableEffect(page, renderWidthPx, retry) {
        onDispose { bitmap?.recycle() }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> {
                val bmp = bitmap!!
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                        .background(Color.White)
                )
            }
            failed -> {
                Column(
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                        .background(Color.White),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.pdf_page_failed),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { retry++ }) {
                        Text(stringResource(R.string.pdf_retry))
                    }
                }
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
