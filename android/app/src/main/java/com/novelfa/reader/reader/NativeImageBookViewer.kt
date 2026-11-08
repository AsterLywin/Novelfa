package com.novelfa.reader.reader

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import com.novelfa.reader.data.BookRepository
import com.novelfa.reader.data.InPlaceSource
import com.novelfa.reader.domain.Book
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.ReaderExtrasStore
import com.novelfa.reader.data.sha256OfFile
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind
import com.novelfa.reader.data.PrefsStoreRankPrefs
import com.novelfa.reader.domain.XpContentKeyResolver
import com.novelfa.reader.domain.XpSessionTracker
import com.novelfa.reader.domain.XpStore
import android.content.Context
import android.net.Uri
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The native viewer itself, for PDF and CBZ alike. Render width is
 * the screen width times the zoom factor (1x / 1.5x / 2x, fit-width
 * is 1x); changing zoom re-renders visible pages at the new width,
 * it never scales a stale bitmap up, so zoom stays sharp.
 */
/**
 * Phase 2 (F1): SHA-256 of the bytes behind a book's source Uri —
 * the direct routes' hash source, where no File exists at all.
 * `content://` is streamed once (no working copy is materialized
 * for this); `file://` is hashed as a plain file. Null on any
 * failure; hashing must never fail a credit.
 */
private fun hashFromSourceUri(context: Context, sourceUri: String?): String? {
    if (sourceUri.isNullOrBlank()) return null
    return try {
        when (InPlaceSource.sourceScheme(sourceUri)) {
            "content" -> context.contentResolver.openInputStream(Uri.parse(sourceUri))
                ?.use { input ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            "file" -> Uri.parse(sourceUri).path
                ?.let { File(it) }?.takeIf { it.isFile }
                ?.let { sha256OfFile(it) }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Strip-level pinch detector (2026-11-05): a pinch whose two
 * fingers land on DIFFERENT strip pages.
 *
 * Why this has to live here, above the items: each page box
 * (ZoomablePageContainer) only ever sees the pointers that land
 * inside its own bounds, so a pinch straddling a page boundary
 * reached neither box as a pinch — each box saw one finger and
 * treated it as a drag. This detector sits on the strip itself,
 * so it sees every pointer in the strip at once, whichever page
 * it landed on.
 *
 * It owns EVERY strip pinch, not only straddling ones: when
 * both fingers are on one page, the box's own pinch branch is
 * disabled (see ZoomablePageContainer), so the scale is never
 * applied twice per frame. Paged mode is untouched — it passes
 * no shared state, and its boxes keep their own pinch.
 *
 * How it stays out of the way of everything else: it reads
 * events in the Initial pass (ancestors first) and consumes a
 * move only while two pointers are down. Single-finger drags
 * are never consumed, so list scrolling, per-page panning and
 * taps behave exactly as before. While a pinch runs, the
 * consumed moves also stop the list from scrolling under the
 * pinch and stop the boxes from panning the same fingers a
 * second time. The scale math is the same as the box pinch
 * (start-referenced target on a smoothed span + focalPan
 * against the viewport); at gesture end it publishes the
 * settled scale once, so every strip page crosses the sharp
 * re-render threshold together.
 */
/**
 * One-canvas strip zoom (2026-11-05): the strip is ONE box —
 * every page laid out at fit size x the SHARED scale, stuck
 * together with no gap and no overlap — so zoom changes the
 * LAYOUT, not a GPU transform inside each page box. The
 * detector owns every strip pinch and writes the ONE shared
 * state (scale + shared cross-axis pan). The focal math
 * therefore anchors in STRIP coordinates, not box
 * coordinates (see PageZoomState.stripScrollDelta /
 * stripCrossOffset):
 * - Main axis: the list scroll IS the pan — each frame the
 *   detector scrolls by the delta that keeps the content
 *   point under the centroid under it.
 * - Cross axis: one shared offset for all pages, around the
 *   centroid.
 * A pinch may start anywhere over content (the centroid must
 * be over an item — over empty space below the last page
 * there is no content point to anchor). At gesture end the
 * settled scale is published once, so every strip page crosses
 * the sharp re-render threshold together.
 */
private fun Modifier.stripPinchDetector(
    stripZoomState: MutableState<PageZoomState>,
    stripSettledZoom: MutableFloatState,
    listState: LazyListState,
    horizontal: Boolean,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    fitFraction: Float
): Modifier = pointerInput(stripZoomState, stripSettledZoom, listState, horizontal, viewportWidthPx, viewportHeightPx, fitFraction) {
    // awaitPointerEvent is a member of AwaitPointerEventScope,
    // not of PointerInputScope, so the loop lives inside this
    // scope (same pattern as the box loop in ZoomableImage).
    awaitPointerEventScope {
    var pinching = false
    var pinchStartScale = 1f
    var pinchStartSpan = 0f
    var pinchSmoothSpan = 0f
    // The page holding a strip point, from the list layout:
    // the visible item whose main-axis span contains it.
    // Null when the point is on no page (past the last item,
    // or the list has no layout yet) — then no pinch starts.
    fun pageAt(mainPos: Float): Int? {
        val info = listState.layoutInfo.visibleItemsInfo
        for (item in info) {
            val start = item.offset.toFloat()
            val end = start + item.size.toFloat()
            if (mainPos >= start && mainPos < end) return item.index
        }
        return null
    }
    // This item's leading edge in viewport coordinates, from
    // the list layout (negative when scrolled past).
    fun itemOffsetMain(page: Int): Float? {
        val item = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == page } ?: return null
        return item.offset.toFloat()
    }
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val pressed = event.changes.filter { it.pressed }.sortedBy { it.id.value }
        if (pressed.size >= 2) {
            val c1 = pressed[0].position
            val c2 = pressed[1].position
            val centroid = (c1 + c2) / 2f
            val distance = (c1 - c2).getDistance()
            if (!pinching) {
                // The zoom is global (one canvas), so the
                // anchor is the CENTROID, not the first
                // finger — and it must be over content.
                val mainPos = if (horizontal) centroid.x else centroid.y
                val page = pageAt(mainPos) ?: continue
                itemOffsetMain(page) ?: continue
                pinching = true
                pinchStartScale = stripZoomState.value.scale
                pinchStartSpan = distance
                pinchSmoothSpan = distance
            } else if (distance < MIN_PINCH_SPAN_PX) {
                // Fingers too close for a signal: hold the
                // scale and re-base the gesture reference at
                // the floor (see the box pinch loop), so
                // crossing it again resumes continuously.
                pinchStartScale = stripZoomState.value.scale
                pinchStartSpan = MIN_PINCH_SPAN_PX
                pinchSmoothSpan = distance
                pressed.forEach { it.consume() }
            } else {
                pinchSmoothSpan = smoothPinchSpan(pinchSmoothSpan, distance)
                val newScale = pinchTargetScale(
                    pinchStartScale, pinchStartSpan, pinchSmoothSpan
                )
                val cur = stripZoomState.value
                // Main axis: scroll so the content point under
                // the centroid stays under it. The item under
                // the centroid is re-resolved every frame (the
                // content point stays put by construction, so
                // it is the same item, but the layout moves
                // under it as the strip rescales).
                val mainPos = if (horizontal) centroid.x else centroid.y
                val page = pageAt(mainPos)
                if (page != null) {
                    val off = itemOffsetMain(page)
                    if (off != null) {
                        val delta = stripScrollDelta(off, mainPos, cur.scale, newScale)
                        // dispatchRawDelta, not scrollBy: the loop
                        // below runs in awaitPointerEventScope, a
                        // RESTRICTED scope that may only call its
                        // own suspend members, so no suspend scroll
                        // runs here — and PointerInputScope is not
                        // a CoroutineScope, so nothing can be
                        // launched from it either. dispatchRawDelta
                        // is the synchronous scroll primitive
                        // (what scrollBy applies internally); it
                        // returns the consumed amount, so a
                        // shortfall at the list ends just lets the
                        // focal point drift a little there.
                        if (delta != 0f) listState.dispatchRawDelta(delta)
                    }
                }
                // Cross axis: one shared offset around the
                // centroid (see PageZoomState.stripCrossOffset).
                val (centroidCross, viewportCross) = if (horizontal) {
                    centroid.y to viewportHeightPx
                } else {
                    centroid.x to viewportWidthPx
                }
                val oldCross = if (horizontal) cur.offsetY else cur.offsetX
                val newCross = stripCrossOffset(
                    cur.scale, oldCross, centroidCross, viewportCross,
                    fitFraction, newScale
                )
                stripZoomState.value = if (horizontal) {
                    cur.copy(scale = newScale, offsetY = newCross)
                } else {
                    cur.copy(scale = newScale, offsetX = newCross)
                }
                pressed.forEach { it.consume() }
            }
        } else if (pinching) {
            pinching = false
            val settled = stripZoomState.value.scale
            if (stripSettledZoom.floatValue != settled) stripSettledZoom.floatValue = settled
        }
    }
    }
}

/**
 * One PDF search hit's highlight geometry: the hit's rects in
 * the page's own point space (the effect in the viewer calls
 * hitRects with the page size, so the rects come back in
 * points), plus that page size for the scale and the aspect
 * ratio. One value, so the rects and the size they were mapped
 * against can never disagree.
 */
private data class PdfHitGeom(
    val rects: List<PdfiumText.MappedRect>,
    val pageWidth: Float,
    val pageHeight: Float
)

/**
 * Yellow highlight for the current PDF search hit, drawn over
 * the rendered page bitmap. The caller must size this Canvas
 * exactly like the displayed page (same width and aspect
 * ratio, same centering) — the rects are scaled from point
 * space onto the Canvas size, so any size mismatch moves the
 * highlight off the text. The overlay takes no pointer input:
 * it is purely visual, so taps still reach the page below.
 *
 * Where it is exact, and where it is not (stated, not hidden):
 * exact at 1x zoom on landing — every next/prev jump lands
 * the page at 1x with its vertical scroll at the top, and the
 * overlay reproduces the item's own fit-width layout. It does
 * NOT track a pinch zoom/pan or a vertical scroll of a tall
 * page afterwards: those transforms live inside the page item
 * (ZoomablePageContainer / its scroll Column), which this
 * viewer-level overlay cannot see. It is therefore only drawn
 * on the plain fit-width path — no manual/auto crop, no view
 * rotation, no split half — where the displayed ratio is the
 * page ratio; on any other path the jump still lands, only the
 * yellow rect is skipped, which is honest where a drawn rect
 * would point at the wrong text.
 */
@Composable
private fun PdfSearchHighlightOverlay(
    geom: PdfHitGeom,
    modifier: Modifier = Modifier
) {
    if (geom.rects.isEmpty()) return
    Canvas(modifier) {
        val sx = size.width / geom.pageWidth
        val sy = size.height / geom.pageHeight
        for (r in geom.rects) {
            drawRect(
                // The text reader's match yellow (#FFD400),
                // translucent so the text stays readable.
                color = Color(0xFFFFD400).copy(alpha = 0.45f),
                topLeft = Offset(r.left * sx, r.top * sy),
                size = Size(
                    (r.right - r.left) * sx,
                    (r.bottom - r.top) * sy
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeImageBookViewer(
    book: Book,
    repo: BookRepository,
    onClose: () -> Unit,
    sourceFactory: (CharArray?) -> NativePageSource,
    loadingRes: Int,
    errorRes: Int,
    // True only on the PDF route: the password dialog then
    // uses the PDF strings (title + book title + Unlock),
    // otherwise the ZIP strings (Archive password + OK).
    // Default false, so the CBZ and image-ZIP routes — which
    // do not pass it — keep exactly their old dialog.
    isPdf: Boolean = false,
    // Phase 2 (F1): the file the route resolved for this book's
    // source (PdfReaderRoute's resolvedFile). Null on the direct
    // routes, where no File exists by design — the credit path
    // then falls back to the row's hash and the source Uri.
    resolvedFile: File? = null
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
    // the shared key while unset (see PrefsStore). A stale
    // saved "horizontal" value (the removed strip mode) falls
    // back to paged in fromKey, so no migration is needed.
    val modeNow = ReadingMode.fromKey(comicModeKey ?: savedModeKey)
    val isPaged = modeNow == ReadingMode.PAGED_HORIZONTAL
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
    // Book password (ZIP archive or PDF document — the name
    // used to say zipPassword, but the PDF dialog writes the
    // same state now, so the name follows the use): null
    // until the user supplies one. The open effect restarts
    // when it changes, so submitting the dialog retries the
    // open with the password. Session-only by design — a
    // password is never persisted.
    var bookPassword by remember { mutableStateOf<CharArray?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordWrong by remember { mutableStateOf(false) }
    var totalChaptersFixed by remember { mutableStateOf(false) }

    // Open on IO: renderer/zip construction parses the file header
    // and must never block the main thread on a big book.
    LaunchedEffect(book.id, book.filePath, bookPassword) {
        val opened = withContext(Dispatchers.IO) {
            runCatching { sourceFactory(bookPassword) }.getOrNull()
        }
        if (opened == null) {
            openError = true
        } else if (opened.pageCount <= 0) {
            // Open failed with a source in hand: for a PDF
            // this is where the password case lives — a
            // wrong or missing password gives 0 pages, and
            // PdfiumNativeSource.needsPassword (trailer
            // check) separates that from a corrupt file.
            // For a CBZ, 0 pages means an empty archive, not
            // a password (encrypted entries still list, and
            // the probe below handles their password), so
            // only the PDF flag triggers the dialog here.
            val needsPw = (opened as? PdfiumNativeSource)?.needsPassword == true
            runCatching { opened.close() }
            if (needsPw) {
                passwordWrong = bookPassword != null
                showPasswordDialog = true
            } else {
                openError = true
            }
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
            } else if (opened is RarNativeSource) {
                withContext(Dispatchers.IO) { opened.probePassword() }
            } else {
                true
            }
            if (!probeOk) {
                runCatching { opened.close() }
                passwordWrong = bookPassword != null
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

    // PDF text search (task 4b): the engine ([PdfTextSearcher],
    // see PdfNativeReader.kt) opens its own bridge document over
    // the resolved file, so it needs the file + the session
    // password — both already in scope here. It is built on IO
    // (opening parses the file header) and rebuilt when the
    // password arrives; the dispose effect owns closing it,
    // exactly like the page source above. CBZ and image books
    // have no text layer, so no searcher is built for them;
    // the direct routes have no File, so search stays off
    // there too. When the bridge .so is absent
    // ([PdfiumText.available] is false) the searcher reports
    // available=false and the search entry point says so
    // through the snackbar instead of pretending search works.
    var pdfSearcher by remember { mutableStateOf<PdfTextSearcher?>(null) }
    LaunchedEffect(isPdf, resolvedFile, bookPassword) {
        // Drop the old searcher first: the dispose effect below
        // closes exactly the searcher it started with, so the
        // old bridge document is freed before the new one opens.
        pdfSearcher = null
        if (!isPdf) return@LaunchedEffect
        val file = resolvedFile ?: return@LaunchedEffect
        if (!file.isFile) return@LaunchedEffect
        pdfSearcher = withContext(Dispatchers.IO) {
            PdfTextSearcher(file, bookPassword)
        }
    }
    DisposableEffect(pdfSearcher) {
        val toClose = pdfSearcher
        onDispose { runCatching { toClose?.close() } }
    }
    // False until the searcher is built AND its bridge document
    // opened: the search entry point reads this, never the
    // searcher's presence alone.
    val pdfSearchAvailable = pdfSearcher?.available == true

    // Search UI state (mirrors the text reader's search bar in
    // ReaderScreen: query field, "N of M" count, prev/next that
    // clamp at the ends). pdfSearchRan is the "a search ran"
    // signal for the "no match" text — a blank query is not a
    // no-match. pdfHitGeom is the current hit's highlight
    // geometry (see the overlay note above); the effect below
    // recomputes it whenever the current hit changes.
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var pdfSearch by remember { mutableStateOf(PdfSearchState(emptyList())) }
    var pdfSearchBusy by remember { mutableStateOf(false) }
    var pdfSearchRan by remember { mutableStateOf(false) }
    var pdfHitGeom by remember { mutableStateOf<PdfHitGeom?>(null) }
    LaunchedEffect(pdfSearch.current, pdfSearcher, source) {
        val hit = pdfSearch.current
        val searcher = pdfSearcher
        val src = source
        if (hit == null || searcher == null || src == null) {
            pdfHitGeom = null
            return@LaunchedEffect
        }
        pdfHitGeom = withContext(Dispatchers.IO) {
            // The page size is the ratio the overlay lays out
            // by, and the nominal size the rects are mapped
            // against: hitRects maps points to bitmap pixels
            // linearly, so calling it with the page size itself
            // returns the rects in point space, and the overlay
            // scales them onto the displayed page.
            val size = runCatching { src.nativePageSize(hit.page) }.getOrNull()
                ?.takeIf { it.first > 0 && it.second > 0 }
                ?: return@withContext null
            val rects = runCatching {
                searcher.hitRects(hit, size.first, size.second)
            }.getOrDefault(emptyList())
            if (rects.isEmpty()) {
                null
            } else {
                PdfHitGeom(rects, size.first.toFloat(), size.second.toFloat())
            }
        }
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
    var showPageJump by remember { mutableStateOf(false) }
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
                    repo.update(book.copy(totalChapters = pageCount))
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
    // One-canvas strip zoom (2026-11-05): ONE shared state for
    // the whole strip — scale + shared cross-axis pan — and one
    // settled scale for every page's sharp re-render. The zoom
    // changes the layout (each item is fit size x the shared
    // scale), so the pages stay stuck together with no gap and
    // no overlap by construction; the main-axis pan is the list
    // scroll. Two earlier strip models failed on the phone and
    // are gone: a shared GPU scale per box (cfa4beaf + 1a143f8c)
    // crowded every page boundary (vc7), and per-page zoom
    // (13ad461d) zoomed each page on its own (vc8) — the
    // original complaint all over again. Paged mode keeps its
    // own per-box states and is untouched.
    val stripZoomState = remember(book.id) { mutableStateOf(PageZoomState(pageId = "strip")) }
    val stripSettledZoom = remember(book.id) { mutableFloatStateOf(1f) }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pagerPageCount }
    )
    var currentPage by remember { mutableIntStateOf(initialPage) }

    // Page-return bitmap park — ComicScreen's pager trick
    // (fx1's drawable map), in Novelfa's own form. Today a page
    // that leaves composition loses its bitmap: the pager's
    // beyond-viewport window is 1 and the strips dispose items
    // as soon as they leave the viewport, so going back to a
    // page re-runs the whole pipeline (zip stream + decode +
    // EXIF rotate + Sobel crop + resize + sharpen + filters).
    // ComicScreen instead parks the finished drawable of the
    // pages around the current one, keyed by page URI, and a
    // re-created page takes its parked drawable instead of
    // re-decoding. This cache is that park, for finished
    // bitmaps: an item hands its bitmap to the cache when it
    // leaves composition, and a new item for the same page and
    // the same render parameters takes it back. Animated pages
    // never park (an Animatable drawable has a playback
    // lifecycle a parked bitmap does not). The cache is
    // per-book: a new book gets a fresh cache, and the close
    // effect below frees the old book's parked bitmaps — a
    // stale bitmap from another book can never be served for
    // the same page number.
    val pageBitmapCache = remember(book.id) { NativePageBitmapCache() }
    DisposableEffect(book.id) {
        onDispose { pageBitmapCache.close() }
    }

    // XP for native books (PDF/CBZ/ZIP pages): the EPUB reader had XP
    // from the start, native books had none — a PDF novel or a manhwa
    // earned nothing. Same rules as the EPUB path, with page = unit:
    // visited-only (slider jumps never credit skipped pages),
    // >= 5 s dwell via [XpSessionTracker], once per page per book
    // (XpLedger), private books excluded, and the book bonus from
    // XpStore (last page credited + >= 90% of pages) applies unchanged.
    // Words come from the page source when it has them
    // ([NativePageSource.pageWords]: the PDFium bridge for
    // PDFs); a source without text data reports 0, which the
    // formula floors to 1 XP — the same result a typical
    // ~500-word PDF page would give, and the only honest value
    // for an image page.
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
                    // Phase 2 (F1): hash the file the source
                    // ACTUALLY reads — the route's resolved file
                    // first — never blindly the row's filePath,
                    // which for in-place `content://` books is a
                    // not-yet-materialized working-copy path (or
                    // a stale extension after the strong-sniff
                    // rename) and used to hash to nothing, leaving
                    // the credit on a UUID key forever. Fallbacks,
                    // in order: the row's recorded hash (already
                    // a true digest when present), the source
                    // Uri's bytes (direct routes have no File),
                    // and finally the row's path (copy books:
                    // the stored file itself).
                    if (resolvedFile != null && resolvedFile.isFile) {
                        runCatching { sha256OfFile(resolvedFile) }.getOrNull()
                    } else if (!book.contentHash.isNullOrBlank()) {
                        book.contentHash
                    } else {
                        hashFromSourceUri(context, book.sourceUri)
                            ?: runCatching { sha256OfFile(File(book.filePath)) }.getOrNull()
                    }
                }
            } else {
                null
            }
            // Phase 2 (F2): record the fresh hash through the
            // single rule (first knowledge / unchanged / real
            // change with progress reset) before resolving the
            // credit key, and pass the row's stored hash as the
            // same-content proof for the UUID→content transfer —
            // a changed content must never inherit the old
            // content's UUID entry. A note failure must not
            // lose the credit itself.
            if (hash != null) {
                runCatching { repo.noteContentHash(book.id, hash) }
            }
            val key = xpResolver.resolve(book.id, hash, book.contentHash)
            val words = source?.pageWords(page) ?: 0
            XpStore(rankPrefs).creditUnit(key, page, words, pageCount, book.isPrivate)
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
        // Dual: currentPage is the pair's LAST page (progress), but
        // XP still needs a dwell clock per page — so the pair's
        // first page goes through the first tracker and the second
        // page through the second, exactly the old split, just
        // keyed off the pair instead of currentPage. Entering the
        // last page in the first tracker would leave the first
        // page of every pair uncredited.
        val xpFirst = if (dualPage && dualPairs != null) {
            dualPairs!!.getOrNull(pairIndexForPage(dualPairs!!, currentPage))?.firstPage
                ?: currentPage
        } else {
            currentPage
        }
        xpTracker.enter(xpFirst)?.let { xpCreditPage(it) }
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
    LaunchedEffect(pageCount, isPaged) {
        if (pageCount <= 0) return@LaunchedEffect
        val hadPending = pendingModeTarget != null
        val target = pendingModeTarget?.also { pendingModeTarget = null }
            ?: if (!restoredPage) {
                restoredPage = true
                savedPage.coerceIn(0, pageCount - 1)
            } else {
                return@LaunchedEffect
            }
        if (target != 0) {
            if (isPaged) {
                currentPage = target
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
                // not at the top of the page. An out-of-range
                // pixel offset simply clamps, so a mode switch
                // cannot break the landing. The landing item is
                // the stored strip anchor (first visible item),
                // NOT the saved progress page: since the
                // leading-edge fix the saved page is the
                // furthest-reached page, and the stored offset
                // belongs to the first visible item — landing
                // on the progress page with it would sit up to
                // a page too far down. A mode-switch landing
                // (pending target) uses the target itself, as
                // before; a pre-fix book has no anchor, so it
                // falls back to the target, which was its anchor.
                val anchor = if (hadPending) null
                    else runCatching {
                        extras.stripAnchorPage(book.id).first()
                    }.getOrNull()
                val landing = (anchor ?: target).coerceIn(0, pageCount - 1)
                currentPage = landing
                val offset = runCatching {
                    extras.scrollOffset(book.id).first()
                }.getOrDefault(0)
                runCatching { listState.scrollToItem(landing, offset) }
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
                val current = repo.book(book.id) ?: book
                repo.update(
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

    // Strip progress (leading edge, PageProgress.kt): the furthest
    // page on screen in the direction of travel. Starts at the
    // saved page; the tracking effect below keeps it current on
    // every scroll emission, including the skipped first one, so
    // the indicator is right from the landing, not from the first
    // page turn.
    var stripProgressPage by remember {
        mutableIntStateOf(savedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
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
    // (The strip's leading-edge display update below is NOT
    // skipped: the landing position still has a leading edge.)
    LaunchedEffect(listState, pagerState, pageCount, isPaged, dualPage, dualPairs) {
        if (pageCount <= 0) return@LaunchedEffect
        var first = true
        // Strip scroll direction, for the leading edge (see
        // PageProgress.kt): reset on every restart, so a landing
        // starts direction-less and reports the first visible item
        // until the user actually scrolls.
        var prevStripIndex = -1
        var prevStripOffset = -1
        var stripDown = false
        if (isPaged) {
            snapshotFlow { pagerState.currentPage }.collect { pagerIdx ->
                if (first) {
                    first = false
                    return@collect
                }
                // Dual: progress is the spread's LAST page, so the
                // last spread saves page N-1 and the book can
                // complete; the anchor page (currentPage) stays
                // the pair's first page for XP, chapters and crop.
                val page = if (dualPage && dualPairs != null) {
                    dualPairs!!.getOrNull(pagerIdx)?.lastPage ?: return@collect
                } else {
                    pagerIdx
                }
                if (page in 0 until pageCount) {
                    currentPage = page
                    savePage(page)
                }
            }
        } else {
            // The strip also saves its exact scroll offset, so
            // a close mid-page restores mid-page (see the
            // restore effect). Offset saves ride the page-change
            // rate — one DataStore edit per page, never per frame.
            snapshotFlow {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }.collect { (page, offset) ->
                // Leading edge (ComicScreen's scroll rule): the
                // last visible item while scrolling down, the
                // first visible item while scrolling up. The
                // saved progress is the leading edge, so the last
                // page counts the moment it comes on screen; the
                // restore anchor stays the first visible item
                // (saved separately below).
                val lastVisible =
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                if (prevStripIndex >= 0) {
                    if (page > prevStripIndex ||
                        (page == prevStripIndex && offset > prevStripOffset)
                    ) {
                        stripDown = true
                    } else if (page < prevStripIndex ||
                        (page == prevStripIndex && offset < prevStripOffset)
                    ) {
                        stripDown = false
                    }
                }
                prevStripIndex = page
                prevStripOffset = offset
                val leading = stripLeadingPage(page, lastVisible, stripDown)
                    .coerceIn(0, pageCount - 1)
                stripProgressPage = leading
                if (first) {
                    first = false
                    return@collect
                }
                if (page in 0 until pageCount) {
                    currentPage = page
                    savePage(leading)
                    scope.launch {
                        extras.setScrollOffset(book.id, offset)
                        extras.setStripAnchorPage(book.id, page)
                    }
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
    LaunchedEffect(listState, pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            val first = index.coerceIn(0, pageCount - 1)
            val info = listState.layoutInfo.visibleItemsInfo
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

    // Progress page (PageProgress.kt): the FURTHEST page on
    // screen, which is what the saved progress, the page
    // indicator, the paged slider and the next arrow follow.
    // In dual mode that is the spread's last page; in the strip
    // it is the leading edge in the direction of travel; in
    // single-page mode it is the page itself. currentPage keeps
    // the anchor meaning (first page of the pair / first visible
    // item) for restore, XP, chapters, bookmarks and crop.
    val progressPage = if (isPaged) {
        if (dualPage && dualPairs != null) {
            dualPairs!!.getOrNull(pairIndexForPage(dualPairs!!, currentPage))?.lastPage
                ?: currentPage
        } else {
            currentPage
        }
    } else {
        stripProgressPage
    }
    // Arrow enablement follows the same two ends: previous exists
    // when the anchor can step back (in dual mode, when the
    // current spread is not the first — the anchor of the first
    // spread is its second page, so currentPage > 0 alone would
    // leave the button enabled-but-dead there), next exists while
    // the furthest page on screen is not the last page.
    val canPrev = if (isPaged && dualPage && dualPairs != null) {
        pairIndexForPage(dualPairs!!, currentPage) > 0
    } else {
        currentPage > 0
    }
    val canNext = progressPage < pageCount - 1

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
            savePage(progressPage, force = true)
            if (!isPaged) {
                val offset = listState.firstVisibleItemScrollOffset
                runCatching {
                    extras.setScrollOffset(book.id, offset)
                    extras.setStripAnchorPage(book.id, listState.firstVisibleItemIndex)
                }
            }
            onClose()
        }
    }

    // Top-bar icon cycles both modes (paged -> vertical strip
    // -> paged); the sheet rows pick one directly through
    // setMode below. Both write the comic viewer's own key,
    // never the shared EPUB key.
    fun toggleMode() {
        val next = when (modeNow) {
            ReadingMode.PAGED_HORIZONTAL -> ReadingMode.CONTINUOUS_VERTICAL
            ReadingMode.CONTINUOUS_VERTICAL -> ReadingMode.PAGED_HORIZONTAL
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


    /** Direct jump (slider release, chapter, bookmark, overview,
     * page-number dialog): animated when [animate] is set, so the
     * jump glides instead of snapping. Restore and zoom/layout
     * corrections keep the default instant landing: an opening
     * book must not animate to its position. */
    fun jumpTo(page: Int, animate: Boolean = false) {
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
                runCatching {
                    if (animate) pagerState.animateScrollToPage(pagerTarget)
                    else pagerState.scrollToPage(pagerTarget)
                }
            } else {
                runCatching {
                    if (animate) listState.animateScrollToItem(target)
                    else listState.scrollToItem(target)
                }
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
            } else {
                runCatching { listState.scrollToItem(target) }
            }
        }
    }

    // PDF search actions (task 4b). searchAll walks every page,
    // so it runs on IO; the landing mirrors the text reader —
    // the first hit becomes current and the viewer jumps to its
    // page. Next/prev step through PdfSearchState (clamped at
    // the ends, no wrap) and jump to the new current hit's page;
    // a jump to the page the viewer already shows is a no-op
    // landing, so stepping between two hits on one page still
    // moves the highlight. Closing the bar clears the query,
    // the results and the highlight, like the text reader's
    // clearSearch.
    fun runPdfSearch() {
        val searcher = pdfSearcher ?: return
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            pdfSearch = PdfSearchState(emptyList())
            pdfSearchRan = false
            return
        }
        scope.launch {
            pdfSearchBusy = true
            val hits = withContext(Dispatchers.IO) {
                runCatching { searcher.searchAll(q) }.getOrDefault(emptyList())
            }
            pdfSearchBusy = false
            pdfSearchRan = true
            val landed = PdfSearchState(hits).next()
            pdfSearch = landed
            landed.current?.let { jumpTo(it.page) }
        }
    }

    fun stepPdfSearch(dir: Int) {
        val stepped = if (dir > 0) pdfSearch.next() else pdfSearch.prev()
        if (stepped.index == pdfSearch.index) return
        pdfSearch = stepped
        stepped.current?.let { jumpTo(it.page) }
    }

    fun clearPdfSearch() {
        searchQuery = ""
        pdfSearch = PdfSearchState(emptyList())
        pdfSearchRan = false
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
    // Per-book zoom (Windows parity: PdfZoom_<BookId>): restored on
    // open, saved on every step change. -1 = not loaded yet.
    val savedZoom by extras.zoomIndex(book.id).collectAsState(initial = -1)
    LaunchedEffect(savedZoom) {
        if (savedZoom >= 0) zoomIndex = savedZoom
    }
    LaunchedEffect(zoomIndex) {
        if (savedZoom >= 0 && zoomIndex != savedZoom) {
            extras.setZoomIndex(book.id, zoomIndex)
        }
    }
    val renderWidthPx = (screenWidthPx * zoomFactors[zoomIndex]).toInt().coerceAtLeast(1)
    // After a zoom change the item heights change; land back on the
    // same page instead of wherever the new heights dropped us.
    LaunchedEffect(zoomIndex) {
        if (pageCount > 0) {
            jumpTo(currentPage)
        }
    }
    // Finger zoom (pinch / double-tap) is per page box, NOT a
    // viewer-level scale — NOT the sheet zoom steps above,
    // which are per-book render widths and already apply to
    // all pages. Each Zoomable page box owns its own
    // PageZoomState (see ZoomablePageContainer): zooming one
    // page never moves another, and in the pager the zoom
    // resets when the displayed page changes. Session-only,
    // like ComicScreen's PhotoView zoom: it is deliberately
    // NOT saved per book — a saved 2x would reopen every page
    // zoomed, which is not what a reader expects from a fresh
    // open. The viewer only passes identity keys (the book id
    // as sessionKey, the pager item as slotKey/activeSlotKey)
    // down to the items; it holds no zoom scale itself. The
    // old globalZoom state was removed by the 2026-11-03
    // zoom-engine redesign, which reversed the 32bb0313
    // shared-scale decision.

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
    // double-tap zoom). The OLD rule — a second tap inside
    // the window CANCELS the pending toggle — is what made
    // tap-to-hide look dead on the phone: the 300ms wait
    // feels unresponsive, so the tester taps again, and that
    // re-tap cancelled the very toggle he was waiting for.
    // New rule: a second tap never cancels anything. Only a
    // CONFIRMED double-tap — reported by the container
    // itself, same spot within 300ms, the one that actually
    // zooms — suppresses the pending toggle (tapSuppress).
    // Cost: every chrome toggle takes 300ms (the standard
    // PhotoView trade-off).
    var pendingTapMs by remember { mutableStateOf<Long?>(null) }
    var tapSuppress by remember { mutableStateOf(false) }
    LaunchedEffect(pendingTapMs) {
        val t = pendingTapMs ?: return@LaunchedEffect
        delay(300)
        if (pendingTapMs == t) {
            if (!tapSuppress) chromeVisible = !chromeVisible
            tapSuppress = false
            pendingTapMs = null
        }
    }
    // Tap on a page itself toggles the chrome, ComicScreen-style:
    // tap once -> bars hide, tap again -> bars show. The 300ms
    // pending window above is what keeps a double-tap (zoom)
    // from toggling: a confirmed double-tap suppresses the
    // pending toggle — a second tap on its own never cancels
    // anything, it is simply ignored while a toggle is
    // pending (see the note above). The stale-suppress clear
    // below is defensive only: a suppress always arrives
    // with a pending toggle (same tap pair), so a fresh
    // pending toggle never carries a live suppress with it.
    // Standard tap handling (2026-11-02): this handler is
    // called by the page's own tap surfaces only — the
    // Zoomable container's single-tap report for zoomable
    // pages, and a plain clickable on the page item for all
    // other pages. No ancestor pointerInput listener, no
    // Scaffold content node, no diagnostic overlay: those
    // were the temporary layers that never fired on the
    // phone and, in the Scaffold's case, covered the page
    // area. A second report while a toggle is pending is
    // ignored, so a tap that reaches both surfaces is
    // still a single toggle.
    fun handlePageTap() {
        if (pendingTapMs == null) {
            pendingTapMs = System.currentTimeMillis()
            tapSuppress = false
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
    // The strip's fit fraction (the padding shrink): the
    // detector's cross-axis anchor math needs the item's real
    // width, which the list layout does not report.
    val stripFitFraction = paddingShrink(paddingRatio)
    // Page fit, direction and sharpen (ComicScreen's fitscreen /
    // page-direction / filter-sharp settings, same defaults):
    // fit-width, right-to-left, sharpen off.
    val fitMode by prefs.comicFitMode.collectAsState(initial = "width")
    val currentChapterIndex = chapters.indexOfLast { it.firstPageIndex <= currentPage }
    val currentChapter = chapters.getOrNull(currentChapterIndex)

    // Chrome is an overlay, not an inset (see the comment
    // on the page Box below): a tap source shared by the
    // two bar containers, so a tap on a bar's empty area
    // is swallowed there instead of falling through to
    // the page underneath and toggling the chrome.
    val chromeTapSource = remember { MutableInteractionSource() }
    Box(modifier = Modifier.fillMaxSize()) {
        // The page area, ALWAYS full-screen. Standard
        // overlay chrome (2026-11-02): the bars below are
        // plain overlay Boxes in this same outer Box, top
        // and bottom only, so hiding them never resizes or
        // moves this Box: the page stays exactly where it
        // was, and hiding only uncovers the strip of page
        // that was under the bars. There is NO Scaffold and
        // NO layout node above this Box in the middle area,
        // so page touches reach the pager/list/Zoomable
        // below directly. Tap-to-hide lives on the page
        // content itself (Zoomable single tap, item
        // clickable), never on an ancestor listener here.
        Box(
            modifier = Modifier.fillMaxSize()
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
        ) {
            when {
                openError -> {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { handlePageTap() })
                            },
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
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { handlePageTap() })
                            },
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
                    // Prefetch two pages each way, not one:
                    // a paged turn used to compose its target
                    // only as the swipe started, so the target's
                    // whole decode (zip stream + pixels, or a
                    // PDFium render, serialized inside the
                    // native library) ran while the user was
                    // already dragging — the lag users report.
                    // With two, both neighbours of the current
                    // page are decoded while the user is still
                    // reading it, so an ordinary one-page turn
                    // lands on a finished bitmap. The cost is
                    // up to two more live bitmaps beside the
                    // current page; the return-visit park stays
                    // at 4, so the bitmap budget grows by that
                    // much and no more. A fast multi-page fling
                    // still decodes pages it passes — no pager
                    // setting can decode pages that were never
                    // composed — but the first turn, the common
                    // case, no longer waits on a decode.
                    beyondViewportPageCount = 2
                ) { pagerIdx ->
                    // One item, one set of parameters: the only
                    // thing that varies between single and dual
                    // is which page(s) the item shows.
                    @Composable
                    fun singleItem(page: Int, halfSide: Int = 0) {
                        Box(Modifier.fillMaxSize()) {
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
                                halfSide = halfSide,
                                onTap = { handlePageTap() },
                                onDoubleTap = { tapSuppress = true },
                                sessionKey = book.id,
                                slotKey = pagerIdx,
                                activeSlotKey = pagerState.currentPage,
                                zoomPageId = "pager-$pagerIdx-page-$page-half-$halfSide",
                                bitmapCache = pageBitmapCache
                            )
                            // PDF search highlight (task 4b): the
                            // overlay reproduces this item's own
                            // fit-width layout — full width x the
                            // padding shrink, the page's aspect,
                            // centered in a viewport-tall box (see
                            // the overlay note for the paths where
                            // it is skipped). It sits inside the
                            // turn box, so it tilts with the page
                            // on a turn; it takes no pointer input,
                            // so taps still reach the page.
                            val geom = pdfHitGeom
                            if (geom != null && pdfSearch.current?.page == page &&
                                halfSide == 0 &&
                                fitMode != "height" && fitMode != "original" &&
                                fitMode != "stretch" &&
                                viewRotation == 0 && !cutMargin &&
                                manualCropRects[page] == null
                            ) {
                                val ratio = geom.pageWidth / geom.pageHeight
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    val viewportHeight = maxHeight
                                    Box(
                                        modifier = Modifier.fillMaxWidth()
                                            .heightIn(min = viewportHeight),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        PdfSearchHighlightOverlay(
                                            geom = geom,
                                            modifier = Modifier
                                                .fillMaxWidth(paddingShrink(paddingRatio))
                                                .aspectRatio(ratio)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // Page-turn transition: a plain pager slide reads
                    // flat, so each page tilts in 3D, shrinks slightly
                    // and fades as it leaves — the turn reads as a page
                    // turning, not a strip sliding. The offset is
                    // signed, so the tilt follows the swipe direction
                    // in both RTL and LTR layouts (reverseLayout
                    // already mirrors it); in dual mode the whole
                    // pair tilts as one spread.
                    val pageOffset = (pagerState.currentPage - pagerIdx) +
                        pagerState.currentPageOffsetFraction
                    val turnModifier = Modifier.graphicsLayer {
                        val abs = kotlin.math.abs(pageOffset)
                        rotationY = pageOffset * -20f
                        cameraDistance = 12f * density.density
                        scaleX = 1f - 0.08f * abs
                        scaleY = 1f - 0.08f * abs
                        alpha = 1f - 0.15f * abs
                    }
                    if (!useDual) {
                        Box(turnModifier.fillMaxSize()) {
                            singleItem(pagerIdx)
                        }
                    } else {
                        when (val pair = pairs!![pagerIdx]) {
                            is DualPair.Single -> Box(turnModifier.fillMaxSize()) {
                                singleItem(pair.firstPage)
                            }
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
                                    Row(turnModifier.fillMaxSize()) {
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
                                    Row(turnModifier.fillMaxSize()) {
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
            else -> {
                val activeSource = source!!
                // One host per strip (not per page): every
                // strip item reads the same one.
                val stripHost = StripZoomHost(
                    listState, horizontal = false,
                    screenWidthPx.toFloat(), screenHeightPx.toFloat(),
                    stripFitFraction
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .background(pageBg)
                        .stripPinchDetector(
                            stripZoomState, stripSettledZoom, listState, horizontal = false,
                            screenWidthPx.toFloat(), screenHeightPx.toFloat(),
                            stripFitFraction
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // No gap: ComicScreen's strip pages touch.
                    // The old 8.dp showed as a dark strip
                    // between every two pages.
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(pageCount) { page ->
                        Box {
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
                                onTap = { handlePageTap() },
                                onDoubleTap = { tapSuppress = true },
                                sessionKey = book.id,
                                zoomPageId = "strip-$page",
                                externalZoomState = stripZoomState,
                                externalSettledZoom = stripSettledZoom,
                                stripHost = stripHost,
                                bitmapCache = pageBitmapCache
                            )
                            // PDF search highlight (task 4b): the
                            // strip item IS the page (required
                            // width x the page's aspect), so the
                            // overlay takes the same size and rides
                            // the list scroll with it. Same plain
                            // path gate as the paged overlay (see
                            // the overlay note); the strip's pinch
                            // scale is in the width, but a
                            // cross-axis pan is not tracked, like
                            // the paged pinch.
                            val geom = pdfHitGeom
                            if (geom != null && pdfSearch.current?.page == page &&
                                viewRotation == 0 && !cutMargin &&
                                manualCropRects[page] == null
                            ) {
                                val ratio = geom.pageWidth / geom.pageHeight
                                val crossDp = with(density) {
                                    stripItemCrossPx(
                                        screenWidthPx.toFloat(),
                                        stripFitFraction,
                                        stripZoomState.value.scale
                                    ).toDp()
                                }
                                PdfSearchHighlightOverlay(
                                    geom = geom,
                                    modifier = Modifier
                                        .requiredWidth(crossDp)
                                        .aspectRatio(ratio)
                                )
                            }
                        }
                    }
                }
            }
            }
        }
        // Standard chrome overlay (2026-11-02, no
        // Scaffold): bars are plain overlay Boxes in
        // this same outer Box, top and bottom only.
        // The middle area has NO layout node above
        // the page at all, so page touches reach the
        // pager/list/Zoomable directly. Bar taps are
        // swallowed by each bar's own clickable
        // (empty onClick, no indication), so a tap
        // on a bar never falls through to the page.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
                AnimatedVisibility(
                    visible = chromeVisible,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(interactionSource = chromeTapSource, indication = null) {},
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
                                if (isPaged) R.string.mode_horizontal
                                else R.string.mode_vertical_continuous
                            )
                            IconButton(
                                onClick = { toggleMode() },
                                modifier = Modifier.semantics {
                                    contentDescription = modeDescription
                                }
                            ) {
                                AppIcon(
                                    if (isPaged) AppIconKind.Pages else AppIconKind.Scroll,
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
                            // PDF text search (task 4b): PDFs only —
                            // CBZ/image pages have no text layer to
                            // search. The icon is the entry point;
                            // when the searcher is unavailable (the
                            // direct route has no File, or the text
                            // bridge .so is absent) the tap says so
                            // through the snackbar instead of opening
                            // a field that cannot work.
                            if (isPdf) {
                                val searchDescription =
                                    stringResource(R.string.search_hint)
                                IconButton(
                                    onClick = {
                                        if (pdfSearchAvailable) {
                                            searchOpen = true
                                        } else {
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    context.getString(
                                                        R.string.pdf_search_unavailable
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = searchDescription
                                    }
                                ) {
                                    AppIcon(
                                        AppIconKind.Search,
                                        tint = LocalContentColor.current,
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
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
                AnimatedVisibility(
                    visible = chromeVisible && pageCount > 0,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(interactionSource = chromeTapSource, indication = null) {},
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
                            enabled = canPrev,
                            onClick = { stepTo(currentPage - 1) }
                        ) {
                            AppIcon(
                                if (directionRtl) AppIconKind.ArrowForward else AppIconKind.ArrowBack,
                                tint = if (canPrev) {
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
                                    (preview ?: progressPage) + 1, pageCount
                                ),
                                // Tap the indicator for a direct page
                                // jump (Windows parity: cur/tot input).
                                modifier = Modifier.clickable { showPageJump = true },
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = if (preview != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                }
                            )
                            CompositionLocalProvider(
                                LocalLayoutDirection provides
                                    if (directionRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                            ) {
                                Slider(
                                    value = sliderPreview?.toFloat()
                                        ?: if (isPaged) progressPage.toFloat() else stripPosition,
                                    onValueChange = { v ->
                                        sliderPreview =
                                            v.toInt().coerceIn(0, pageCount - 1)
                                    },
                                    onValueChangeFinished = {
                                        val target = sliderPreview
                                        sliderPreview = null
                                        if (target != null) jumpTo(target, animate = true)
                                    },
                                    valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
                                    // Snapping stays (steps), but the
                                    // stops are not drawn: the old
                                    // dotted track read as clutter.
                                    // Clean round thumb + a rounded
                                    // 6dp track. The bar's direction
                                    // follows the TURNING mode
                                    // (directionRtl), not the UI locale:
                                    // the provider below mirrors the
                                    // whole Slider, so thumb and fill
                                    // always agree, in both directions.
                                    steps = (pageCount - 2).coerceIn(0, 1000),
                                    thumb = {
                                        Box(
                                            Modifier.size(20.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    CircleShape
                                                )
                                        )
                                    },
                                    track = { state ->
                                        val range = state.valueRange
                                        val fraction =
                                            if (range.endInclusive > range.start) {
                                                ((state.value - range.start) /
                                                    (range.endInclusive - range.start))
                                                    .coerceIn(0f, 1f)
                                            } else {
                                                0f
                                            }
                                        Box(
                                            Modifier.fillMaxWidth().height(6.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                )
                                        ) {
                                            Box(
                                                Modifier.fillMaxHeight()
                                                    .fillMaxWidth(fraction)
                                                    // The fill grows from the
                                                    // reading-start side: in the
                                                    // slider's own turning-direction
                                                    // layout, CenterStart IS that
                                                    // side in both directions. The
                                                    // old CenterEnd-for-RTL put the
                                                    // fill on the wrong side —
                                                    // CenterEnd is LEFT in RTL — so
                                                    // the fill and the thumb
                                                    // disagreed (the reported
                                                    // mismatch).
                                                    .align(Alignment.CenterStart)
                                                    .background(
                                                        MaterialTheme.colorScheme.primary
                                                    )
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        IconButton(
                            enabled = canNext,
                            onClick = { stepTo(currentPage + 1) }
                        ) {
                            AppIcon(
                                if (directionRtl) AppIconKind.ArrowBack else AppIconKind.ArrowForward,
                                tint = if (canNext) {
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
        // PDF search bar (task 4b): the text reader's bar in
        // ReaderScreen, as an overlay at the top of the reading
        // area, so it works in fullscreen too once opened. It
        // paints above the top bar (it is a later child of this
        // outer Box) and swallows its own taps like the chrome
        // bars, so a tap on the bar never reaches the page. The
        // field is gated on the searcher: it only ever opens
        // when available, and the enabled flags below are the
        // second gate. Prev/next clamp at the ends (no wrap);
        // the count reads "N of M" while there are hits and
        // "No match" once a search ran with none.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedVisibility(
                visible = searchOpen,
                modifier = Modifier.fillMaxWidth()
                    .clickable(interactionSource = chromeTapSource, indication = null) {},
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                // The shown results belong to the
                                // old query: clear them while the
                                // user edits, so the count never
                                // disagrees with the field.
                                if (pdfSearch.count > 0 || pdfSearchRan) {
                                    pdfSearch = PdfSearchState(emptyList())
                                    pdfSearchRan = false
                                }
                            },
                            singleLine = true,
                            enabled = pdfSearchAvailable,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { runPdfSearch() }
                            )
                        )
                        IconButton(
                            onClick = { runPdfSearch() },
                            enabled = pdfSearchAvailable
                        ) {
                            AppIcon(AppIconKind.Search, tint = LocalContentColor.current, size = 18.dp)
                        }
                        IconButton(onClick = { clearPdfSearch(); searchOpen = false }) {
                            AppIcon(AppIconKind.Close, tint = LocalContentColor.current, size = 16.dp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (pdfSearch.count > 0) {
                                stringResource(
                                    R.string.search_count,
                                    pdfSearch.index + 1, pdfSearch.count
                                )
                            } else if (pdfSearchRan) {
                                stringResource(R.string.search_no_match)
                            } else {
                                ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { stepPdfSearch(-1) },
                            enabled = pdfSearch.index > 0
                        ) {
                            Text(stringResource(R.string.search_prev))
                        }
                        TextButton(
                            onClick = { stepPdfSearch(1) },
                            enabled = pdfSearch.index >= 0 &&
                                pdfSearch.index < pdfSearch.count - 1
                        ) {
                            Text(stringResource(R.string.search_next))
                        }
                    }
                    if (pdfSearchBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            SnackbarHost(
                snackbar,
                modifier = Modifier.padding(
                    bottom = if (chromeVisible) 120.dp else 16.dp
                )
            )
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
                                jumpTo(chapter.firstPageIndex, animate = true)
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

    // Direct page jump (Windows parity: cur/tot input + Enter):
    // tap the "page N of M" indicator, type the number, jump.
    // jumpTo clamps, so an out-of-range number lands on the
    // nearest page instead of erroring.
    if (showPageJump) {
        var pageText by remember { mutableStateOf((currentPage + 1).toString()) }
        AlertDialog(
            onDismissRequest = { showPageJump = false },
            title = { Text(stringResource(R.string.page_jump_title)) },
            text = {
                TextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = {
                        Text(
                            stringResource(
                                R.string.page_indicator, currentPage + 1, pageCount
                            )
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = pageText.toIntOrNull()
                    showPageJump = false
                    if (n != null) jumpTo(n - 1, animate = true)
                }) {
                    Text(stringResource(R.string.page_jump_go))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPageJump = false }) {
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
                                    jumpTo(page, animate = true)
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
                                    jumpTo(page, animate = true)
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

    // Book password dialog: shown when the open probe fails
    // (ZIP) or a PDF open fails with needsPassword (no
    // password yet, or a wrong one). Submitting restarts the
    // open with the password; cancelling lands on the error
    // screen instead of a spinner that never ends. The PDF
    // strings are the import dialog's own strings (title +
    // book title + Unlock), reused here so no new strings
    // and no hardcoded text are needed; the ZIP strings
    // stay exactly as before for archives.
    if (showPasswordDialog) {
        var passwordText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showPasswordDialog = false
                openError = true
            },
            title = {
                Text(
                    stringResource(
                        if (isPdf) R.string.pdf_password_title
                        else R.string.comic_zip_password
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isPdf) {
                        Text(
                            text = stringResource(
                                R.string.pdf_password_message, book.title
                            )
                        )
                    }
                    if (passwordWrong) {
                        Text(
                            text = stringResource(
                                if (isPdf) R.string.pdf_password_wrong
                                else R.string.comic_password_wrong
                            ),
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
                        bookPassword = passwordText.toCharArray()
                        showPasswordDialog = false
                    }
                ) {
                    Text(
                        stringResource(
                            if (isPdf) R.string.pdf_password_unlock
                            else R.string.ok
                        )
                    )
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
                        selected = !isPaged,
                        onClick = { setMode(ReadingMode.CONTINUOUS_VERTICAL) }
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
                                AppIconKind.MarginH,
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
                                AppIconKind.MarginV,
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
