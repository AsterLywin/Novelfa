package com.novelfa.reader.reader

import com.novelfa.reader.data.SeekableZip
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import com.novelfa.reader.data.BookRepository
import com.novelfa.reader.domain.Book
import android.net.Uri
import com.novelfa.reader.data.InPlaceSource
import java.io.IOException
import com.novelfa.reader.parser.CbzBook
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.engine.ZipEngine
import com.novelfa.reader.parser.engine.ZipKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    repo: BookRepository,
    onClose: () -> Unit,
    support: com.novelfa.reader.settings.SupportTracker? = null
) {
    val context = LocalContext.current
    var book by remember { mutableStateOf<Book?>(null) }
    var missing by remember { mutableStateOf(false) }
    var resolvedFile by remember { mutableStateOf<File?>(null) }
    var resolveFailed by remember { mutableStateOf(false) }
    // Direct (no-working-copy) route, step 5: for in-place
    // `content://` PDFs and comics, the viewer opens the
    // provider's descriptor / archive directly instead of
    // waiting for a full working-copy build. routeReady is
    // false while the probe runs; directCbz holds the open
    // archive when the comic probe hits (null = not a direct
    // comic). The archive lifetime is the route's: the effect
    // below closes it when the route leaves or the book
    // changes — sources built over it must never close it
    // themselves (see SeekableEntryStore).
    var directPdf by remember { mutableStateOf(false) }
    var directCbz by remember { mutableStateOf<SeekableZip?>(null) }
    var routeReady by remember { mutableStateOf(false) }
    DisposableEffect(directCbz) {
        onDispose { directCbz?.close() }
    }
    LaunchedEffect(bookId) {
        val found = withContext(Dispatchers.IO) {
            runCatching { repo.book(bookId) }.getOrNull()
        }
        if (found == null) missing = true else book = found
    }
    // Direct-route probe, then resolve as before: for in-place
    // books the book path is a not-yet-materialized working
    // copy (content://) or the user's real file (file://), so
    // routing on the raw path would mis-detect format or fail
    // outright. Copy books resolve to their stored file,
    // unchanged. The probe runs FIRST and reads only a
    // descriptor open (PDF) or the archive tail + central
    // directory (CBZ/ZIP), so a hit skips the whole
    // working-copy build; any miss falls through to the
    // resolve below, unchanged.
    LaunchedEffect(book) {
        val found = book ?: return@LaunchedEffect
        routeReady = false
        directPdf = false
        directCbz = null
        resolvedFile = null
        resolveFailed = false
        if (found.importMode == "IN_PLACE" &&
            InPlaceSource.sourceScheme(found.sourceUri) == "content"
        ) {
            val uriStr = found.sourceUri
            val ext = (found.originalFileName ?: found.filePath)
                .substringAfterLast('.', "").lowercase()
            if (uriStr != null) {
                if (ext == "pdf") {
                    val pfd = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openFileDescriptor(
                                Uri.parse(uriStr), "r"
                            )
                        }.getOrNull()
                    }
                    if (pfd != null) {
                        // Probe only: construct + close. The
                        // viewer factory opens a fresh
                        // descriptor per attempt (password
                        // retries need one each), so this
                        // descriptor is not kept.
                        withContext(Dispatchers.IO) {
                            runCatching {
                                PdfiumNativeSource(pfd, context, null).close()
                            }
                        }
                        directPdf = true
                        routeReady = true
                        return@LaunchedEffect
                    }
                } else if (ext == "cbz" || ext == "zip") {
                    val seekable = withContext(Dispatchers.IO) {
                        runCatching {
                            SeekableZip.openFromUri(context, Uri.parse(uriStr))
                        }.getOrNull()
                    }
                    // The direct route lists fine even when image
                    // entries use a method it cannot serve (bzip2,
                    // LZMA, zstd) — but then every such page fails
                    // to open, and Retry cannot help (the store is
                    // fixed). Such archives fall through to the
                    // working copy below, whose backends serve the
                    // readable pages instead. See
                    // SeekableZip.hasUnservableImageEntries. The
                    // same rule covers nested archives, whose
                    // images are listed and served from
                    // materialized copies ([NestedArchive]).
                    if (seekable != null && !seekable.hasEncryptedEntries() &&
                        !seekable.hasUnservableImageEntries()
                    ) {
                        val nestedCache = File(context.cacheDir, "nested-archives")
                        val sourceKey = uriStr
                        val direct = withContext(Dispatchers.IO) {
                            if (seekable.hasNestedUnservableImages(nestedCache, sourceKey)) {
                                return@withContext false
                            }
                            val images = seekable.nestedImageEntryNames(nestedCache, sourceKey)
                            if (images.isEmpty()) return@withContext false
                            if (ext == "cbz") return@withContext true
                            // ZIP: only image-only archives go
                            // direct — a ZIP carrying a book must
                            // still resolve, so the archive engine
                            // can open the book inside it. The
                            // book may hide inside a nested
                            // archive too.
                            val names = seekable.entries
                                .filter { !it.isDirectory }
                                .map { it.name }
                                .filter { !it.startsWith("__MACOSX/") }
                                .filter { !it.substringAfterLast('/').startsWith(".") }
                            val hasBook = names.any {
                                it.substringAfterLast('.', "").lowercase() in
                                    setOf(
                                        "epub", "docx", "pdf", "txt", "text",
                                        "md", "markdown", "mdown",
                                        "fb2", "fbz", "rtf"
                                    )
                            } || seekable.hasNestedBook(nestedCache, sourceKey)
                            !hasBook
                        }
                        if (direct) {
                            directCbz = seekable
                            routeReady = true
                            return@LaunchedEffect
                        }
                    }
                    seekable?.close()
                }
            }
        }
        resolvedFile = withContext(Dispatchers.IO) {
            runCatching {
                repo.resolveBookFile(context, found)
            }.getOrNull()
        }
        if (resolvedFile == null) resolveFailed = true
        routeReady = true
    }
    // First-open cover backfill for native books (PDF, CBZ and
    // image-only ZIPs), the counterpart of ReaderScreen's call
    // for text books: a register-only `content://` in-place
    // import never extracted the first page as a cover, so the
    // row still shows a generated cover or none. Restricted to
    // native formats here so text books are not backfilled twice
    // (the reader does those); the helper itself is idempotent,
    // so a ZIP that also reaches the reader is still safe.
    LaunchedEffect(book, resolvedFile) {
        val found = book ?: return@LaunchedEffect
        val file = resolvedFile ?: return@LaunchedEffect
        if (found.importMode != "IN_PLACE") return@LaunchedEffect
        val format = BookFormat.detect(file) ?: return@LaunchedEffect
        if (format != BookFormat.PDF && format != BookFormat.CBZ &&
            format != BookFormat.CBR && format != BookFormat.RAR &&
            format != BookFormat.ZIP
        ) return@LaunchedEffect
        repo.backfillCover(context, found, file)
    }
    // First-open cover backfill for direct routes, where no
    // resolved file exists: the real first page still replaces
    // the generated cover, from the descriptor / archive
    // directly instead of a working copy.
    LaunchedEffect(book, directPdf, directCbz) {
        val found = book ?: return@LaunchedEffect
        if (found.importMode != "IN_PLACE") return@LaunchedEffect
        if (directPdf) {
            repo.backfillDirectPdf(context, found)
        } else {
            val seekable = directCbz ?: return@LaunchedEffect
            repo.backfillDirectCbz(context, found, seekable)
        }
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
        book == null || !routeReady ||
            (resolvedFile == null && !directPdf && directCbz == null) -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        // Direct routes, step 5: no resolved file exists, by
        // design — the viewer opens the descriptor / archive
        // itself. The factories re-open per attempt, so a
        // password retry gets a fresh descriptor each time.
        directPdf -> {
            NativeImageBookViewer(
                book = book!!,
                repo = repo,
                onClose = onClose,
                sourceFactory = { pw ->
                    val pfd = context.contentResolver.openFileDescriptor(
                        Uri.parse(book!!.sourceUri!!), "r"
                    ) ?: throw IOException("Cannot open in-place source")
                    PdfiumNativeSource(pfd, context, pw)
                },
                loadingRes = R.string.pdf_native_loading,
                errorRes = R.string.pdf_native_error,
                isPdf = true
            )
        }
        directCbz != null -> {
            NativeImageBookViewer(
                book = book!!,
                repo = repo,
                onClose = onClose,
                sourceFactory = { _ ->
                    CbzNativeSource(
                        directCbz!!,
                        File(context.cacheDir, "nested-archives"),
                        book!!.sourceUri ?: book!!.id
                    )
                },
                loadingRes = R.string.cbz_native_loading,
                errorRes = R.string.cbz_native_error
            )
        }
        // PDF routing, 2026-11-01 (PDFium engine): PDF books open
        // in the native viewer via PdfiumNativeSource — a fixed
        // page rendered by PDFium, exactly as the document is.
        // This REPLACES the 2026-10-29 revert (PDFs back to
        // ReaderScreen's converted text/image path): the user
        // chose the professional engine over that path, whose
        // hand-written extractor broke on ASCII85 streams and
        // visual-order Persian. Consequences, stated not hidden:
        // the reader font does not apply to fixed PDF pages (no
        // PDF viewer reflows text, Adobe included); search,
        // highlight and selection for PDFs return with the text
        // layer on top of this viewer, not yet in this step; XP
        // credit for PDFs IS wired here (page-based, see the
        // viewer). The converted path survives for PDFs carried
        // inside a ZIP archive (classified below), which still
        // open through the archive engine. CBZ and image-only
        // ZIPs keep the native viewer, since they never had a
        // converted path. A ZIP carrying a book is NOT a comic:
        // it is classified below and routed to ReaderScreen,
        // where the archive engine opens the book inside it.
        BookFormat.detect(resolvedFile!!) == BookFormat.PDF -> {
            val bookFile = resolvedFile!!
            NativeImageBookViewer(
                book = book!!,
                repo = repo,
                onClose = onClose,
                sourceFactory = { pw -> PdfiumNativeSource(bookFile, context, pw) },
                loadingRes = R.string.pdf_native_loading,
                errorRes = R.string.pdf_native_error,
                isPdf = true,
                resolvedFile = bookFile
            )
        }
        BookFormat.detect(resolvedFile!!) == BookFormat.CBZ -> {
            val bookFile = resolvedFile!!
            NativeImageBookViewer(
                book = book!!,
                repo = repo,
                onClose = onClose,
                sourceFactory = { pw ->
                    // A RAR wearing a .cbz name (in-place books keep
                    // their original name, so the extension cannot be
                    // trusted): open it as the RAR it is instead of
                    // failing it as a corrupt ZIP. Mirror of the
                    // ZIP-wearing-.cbr fallback in the CBR branch.
                    if (CbzBook.archiveKind(bookFile) == "RAR") RarNativeSource(bookFile, pw)
                    else CbzNativeSource(bookFile, pw)
                },
                loadingRes = R.string.cbz_native_loading,
                errorRes = R.string.cbz_native_error,
                resolvedFile = bookFile
            )
        }
        BookFormat.detect(resolvedFile!!) == BookFormat.CBR ||
            BookFormat.detect(resolvedFile!!) == BookFormat.RAR -> {
            val bookFile = resolvedFile!!
            NativeImageBookViewer(
                book = book!!,
                repo = repo,
                onClose = onClose,
                sourceFactory = { pw ->
                    // A ZIP wearing a .cbr name (import rejects
                    // it with the rename message, but a file
                    // opened directly by ACTION_VIEW can still
                    // arrive here): open it as the CBZ it is
                    // instead of failing it as a corrupt RAR.
                    if (CbzBook.archiveKind(bookFile) == "ZIP") CbzNativeSource(bookFile, pw)
                    else RarNativeSource(bookFile, pw)
                },
                loadingRes = R.string.cbz_native_loading,
                errorRes = R.string.cbz_native_error,
                resolvedFile = bookFile
            )
        }
        BookFormat.detect(resolvedFile!!) == BookFormat.ZIP -> {
            ZipReaderRoute(
                book = book!!, resolvedFile = resolvedFile!!,
                repo = repo, onClose = onClose, support = support
            )
        }
        else -> {
            ReaderScreen(bookId = bookId, repo = repo, onClose = onClose, support = support)
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
    book: Book,
    resolvedFile: File,
    repo: BookRepository,
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
                repo = repo,
                onClose = onClose,
                sourceFactory = { pw -> CbzNativeSource(resolvedFile, pw) },
                loadingRes = R.string.cbz_native_loading,
                errorRes = R.string.cbz_native_error,
                resolvedFile = resolvedFile
            )
        }
        else -> {
            ReaderScreen(bookId = book.id, repo = repo, onClose = onClose, support = support)
        }
    }
}

/**
 * One pager item in dual-page mode (ComicScreen's 2p view).
 * [firstPage] is the pair's first page in reading order, so
 * bookkeeping stays in pages even though the pager moves in
 * pairs. Progress, the bottom bar and the slider track
 * [lastPage] instead — the furthest page this item shows —
 * because a pair whose first page is saved can never complete
 * the book: the last spread of an even-page book would save
 * page N-2 and stick at (N-1)/N percent (see PageProgress.kt).
 * XP still uses both pages, one dwell clock each.
 */
internal sealed interface DualPair {
    val firstPage: Int

    /** Furthest page this item shows (see [spreadLastPage]). */
    val lastPage: Int get() = spreadLastPage(firstPage, (this as? Pair)?.secondPage)

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
