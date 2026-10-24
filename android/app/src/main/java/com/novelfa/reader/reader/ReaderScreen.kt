package com.novelfa.reader.reader

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.novelfa.reader.R
import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.FontStore
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.parser.EpubParser
import com.novelfa.reader.settings.SupportDialog
import com.novelfa.reader.settings.SupportTracker
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind
import com.novelfa.reader.theme.ContrastIcon
import com.novelfa.reader.theme.Themes
import com.novelfa.reader.theme.ThemePreviewCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import androidx.compose.material3.rememberDrawerState
import com.novelfa.reader.theme.CharacterThemes
import com.novelfa.reader.theme.ParticleLayer
import com.novelfa.reader.theme.PrefsStoreRankPrefs
import com.novelfa.reader.theme.XpStore
import com.novelfa.reader.theme.characterPages

/**
 * Route `reader/{bookId}`: top bar (dir-aware chapter title + position
 * text, reading-mode picker), WebView, bottom bar (prev/next, font size +/-,
 * spacing dialog, chapter list drawer). Errors surface via snackbar with
 * R.string messages (fa "خطا در نمایش فصل:\n" equivalents, Task 4 map).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    dao: BookDao,
    onClose: () -> Unit,
    // Task 8: optional support-reminder tracker. While a book is open, every
    // 10s of foreground reading accumulates (like the WPF DispatcherTimer);
    // at the 30min threshold the dialog shows once and the counter resets.
    support: SupportTracker? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Reader page theme is stored per book (WPF parity), unlike the global
    // app theme — an Activity context here would leak on recreation.
    val prefs = remember(context) { PrefsStore(context.applicationContext) }

    var vm by remember { mutableStateOf<ReaderViewModel?>(null) }
    // XP flush on reader exit: the close button flushes first and only
    // then navigates home (so the home celebration sees the final unit);
    // system-back/gesture exits hit the DisposableEffect safety net below
    // (independent scope, because the composition scope is cancelled on
    // dispose — a missed flush there only delays the celebration, it never
    // loses XP, because celebratedXp stays behind totalXp).
    val xpFlushScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    fun closeReader() {
        scope.launch {
            runCatching { vm?.xpFlush() }
            onClose()
        }
    }
    DisposableEffect(vm) {
        onDispose {
            val v = vm
            if (v != null) xpFlushScope.launch { runCatching { v.xpFlush() } }
        }
    }
    var loadError by remember { mutableStateOf(false) }
    var pageHtml by remember { mutableStateOf("") }
    var pendingLastPage by remember { mutableStateOf(false) }
    // Fragment to land on after a cross-chapter in-book link opens its
    // chapter; applied (and cleared) by the onPageFinished hook.
    var pendingAnchor by remember { mutableStateOf<String?>(null) }
    var pageBase by remember { mutableStateOf<String?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var spacingOpen by remember { mutableStateOf(false) }
    var supportOpen by remember { mutableStateOf(false) }
    var themeOpen by remember { mutableStateOf(false) }
    var fontOpen by remember { mutableStateOf(false) }
    var fullscreenUi by rememberSaveable { mutableStateOf(false) }
    // Reading layout mode (persisted globally): horizontal paging or
    // vertical-continuous. Replaces the old continuous toggle.
    val savedModeKey by prefs.readingMode.collectAsState(initial = "paged")
    val modeNow = ReadingMode.fromKey(savedModeKey)
    var modeOpen by remember { mutableStateOf(false) }

    val chapterErr = stringResource(R.string.couldn_t_display_the_chapter)
    val openErr = stringResource(R.string.couldn_t_open_the_book)
    val themeErr = stringResource(R.string.couldn_t_apply_the_theme)

    suspend fun showVmError(v: ReaderViewModel) {
        snackbar.showSnackbar(chapterErr + (v.lastError ?: ""))
    }

    /**
     * Applies a ViewModel append/prepend to the WebView without a full
     * reload: evaluates the chunk JS, then removes chapters dropped by the
     * window trim via [HtmlBuilder.dropJs] (scroll-compensated, no jump).
     * The drop script takes the KEEP range ([contFirst]..[contLast]) —
     * passing the dropped range instead would invert it and wipe the live
     * window from the DOM.
     */
    suspend fun applyIncrementalJs(v: ReaderViewModel, prevLast: Int, prevFirst: Int) {
        val drop = v.lastDropped
        val dropJs = if (drop != null && !drop.isEmpty()) {
            HtmlBuilder.dropJs(v.docGen, v.contFirst, v.contLast)
        } else ""
        when {
            v.lastChunkKind == "append" && v.contLast != prevLast -> {
                webView?.evaluateJavascript(
                    HtmlBuilder.appendJs(v.docGen, v.lastChunk ?: "") + dropJs, null
                )
            }
            v.lastChunkKind == "prepend" && v.contFirst != prevFirst -> {
                webView?.evaluateJavascript(
                    HtmlBuilder.prependJs(v.docGen, v.lastChunk ?: "", v.lastPrependDivider) +
                        dropJs,
                    null
                )
            }
        }
        v.clearDropped()
    }


    LaunchedEffect(bookId) {
        try {
            val entity = withContext(Dispatchers.IO) { dao.getById(bookId) }
                ?: throw IOException("book not found")
            val parsed = withContext(Dispatchers.IO) { openParsed(context, entity.filePath, bookId) }
            val store = FontStore(context)
            val files = withContext(Dispatchers.IO) {
                store.list().filter {
                    it.nameWithoutExtension.contains(entity.fontFamily, ignoreCase = true)
                }
            }
            val savedTheme = withContext(Dispatchers.IO) { prefs.getReaderTheme(bookId) }
            val savedMode = withContext(Dispatchers.IO) { prefs.getReadingMode() }
            val charThemeId = withContext(Dispatchers.IO) { prefs.charTheme.first() }
            val isLight = withContext(Dispatchers.IO) { prefs.theme.first() == "light" }
            // XP credit: private books never earn XP (old-rank parity).
            // The bridge preload is awaited so a credit can never clobber
            // the stored total with a not-yet-loaded default.
            val xpPrefs = PrefsStoreRankPrefs(prefs)
            val xpCallback: (suspend (Int, Int) -> Unit)? =
                if (entity.isPrivate) null else { unit, words ->
                    runCatching {
                        xpPrefs.awaitLoaded()
                        withContext(Dispatchers.IO) {
                            XpStore(xpPrefs).creditUnit(
                                bookId, unit, words, parsed.chapters.size, false
                            )
                        }
                    }
                }
            val v = ReaderViewModel(
                dao, bookId, entity.copy(totalChapters = parsed.chapters.size),
                parsed.chapters,
                theme = ReaderViewModel.themeForBook(savedTheme, charThemeId, isLight),
                fontFiles = files,
                onXpUnitFinished = xpCallback
            )
            v.readingMode = ReadingMode.fromKey(savedMode)
            val target = entity.lastChapterIndex.coerceIn(0, parsed.chapters.size - 1)
            if (!v.loadChapter(target)) {
                throw IOException(v.lastError ?: "")
            }
            vm = v
            pageHtml = v.html
            pageBase = v.baseUrl
        } catch (e: CancellationException) {
            // Navigation away: don't show a spurious error.
            throw e
        } catch (e: Exception) {
            loadError = true
            snackbar.showSnackbar(openErr)
        }
    }

    // Task 8 support reminder: 10s foreground ticks while the reader route is
    // composed (WPF additionally required active + unminimized window).
    LaunchedEffect(bookId, support) {
        val tracker = support ?: return@LaunchedEffect
        while (true) {
            delay(10_000)
            // DataStore read+write every tick: keep it off the UI thread.
            val prompt = withContext(Dispatchers.IO) {
                tracker.addForegroundSeconds(10)
                if (tracker.shouldPrompt()) {
                    tracker.onPromptShown()
                    true
                } else false
            }
            if (prompt) supportOpen = true
        }
    }

    suspend fun gotoChapter(v: ReaderViewModel, i: Int) {
        if (v.loadChapter(i)) {
            pageHtml = v.html
            pageBase = v.baseUrl
            rev++
        } else {
            showVmError(v)
        }
    }

    /** Jumps to an in-book anchor in the currently shown chapter. */
    fun jumpToAnchor(v: ReaderViewModel, frag: String) {
        val wv = webView ?: return
        if (v.readingMode == ReadingMode.PAGED_HORIZONTAL) {
            wv.goToAnchorPaged(frag)
        } else {
            wv.scrollToAnchor(frag)
        }
    }

    /**
     * In-book link tap (from the LINK_JS interceptor via the bridge):
     * resolves the authored href to a spine chapter; a link back into
     * the current chapter file jumps in place, otherwise the target
     * chapter opens and its fragment is applied on page-finish.
     */
    suspend fun handleInBookLink(v: ReaderViewModel, href: String, chap: Int) {
        val (idx, frag) = v.resolveLink(href, chap) ?: return
        if (idx == v.index) {
            if (frag.isNotBlank()) jumpToAnchor(v, frag)
        } else {
            if (frag.isNotBlank()) pendingAnchor = frag
            gotoChapter(v, idx)
        }
    }

    val bridge = remember {
        ScrollBridge(
            onScroll = { t, h, c, gen ->
            scope.launch {
                val v = vm ?: return@launch
                // Stale-page guard: a drawer jump / theme change bumps
                // docGen and reloads; the old page's 200ms interval can
                // still fire once afterwards. Without this, appendNext
                // would advance contLast while the gen-guarded chunk JS
                // gets rejected — desyncing the ViewModel window from the
                // DOM (the "can't advance after drawer select" bug).
                if (gen != v.docGen) return@launch
                val last = v.contLast
                val first = v.contFirst
                v.onScroll(t, h, c)
                applyIncrementalJs(v, last, first)
                rev++
            }
            },
            onLinkClick = { href, chap ->
                val v = vm
                if (v != null) scope.launch { handleInBookLink(v, href, chap) }
            },
            onChapterVisible = { ci, gen ->
                scope.launch {
                    val v = vm ?: return@launch
                    // Same stale-page guard as the scroll bridge: a
                    // report from the previous document must not move
                    // the reading chapter of the new one.
                    if (gen != v.docGen) return@launch
                    v.onChapterVisible(ci)
                    rev++
                }
            }
        )
    }

    /**
     * Horizontal page turn (paged mode). Asks the page to turn via
     * `rdrTurn`; at the chapter edges it moves to the adjacent chapter
     * instead (like a manga reader).
     */
    fun turnPage(v: ReaderViewModel, forward: Boolean) {
        val wv = webView ?: return
        wv.evaluateJavascript("rdrTurn(${if (forward) 1 else -1})") { result ->
            val r = result?.trim()?.trim { it == '"' }
            // 'busy' = swipe landed mid-animation; the lock drops it.
            if (r == "busy") return@evaluateJavascript
            scope.launch {
                val vv = vm ?: return@launch
                if (r == "end" && forward && vv.index + 1 < vv.chapters.size) {
                    gotoChapter(vv, vv.index + 1)
                } else if (r == "start" && !forward && vv.index > 0) {
                    // Land on the previous chapter's last page; the
                    // onPageFinished hook fires rdrLast() once loaded.
                    pendingLastPage = true
                    gotoChapter(vv, vv.index - 1)
                }
            }
        }
    }

    /**
     * Chapter-level jump (bottom-bar buttons, Windows btnPrevChapter /
     * btnNextChapter parity). Backward jumps land on the last page in
     * paged mode; forward jumps start at the first page.
     */
    fun jumpChapter(v: ReaderViewModel, delta: Int) {
        val i = v.index + delta
        if (i !in v.chapters.indices) return
        scope.launch {
            if (delta < 0 && v.readingMode == ReadingMode.PAGED_HORIZONTAL) {
                pendingLastPage = true
            }
            gotoChapter(v, i)
        }
    }

    /**
     * Switches the reading layout mode (from the mode popup): persists the
     * choice, rebuilds the current chapter in the new layout, and — when
     * entering vertical-continuous — stitches the next chapter right away
     * instead of waiting for a scroll-to-bottom.
     */
    fun setMode(m: ReadingMode) {
        val v = vm ?: return
        scope.launch {
            withContext(Dispatchers.IO) { prefs.setReadingMode(m.key) }
            v.readingMode = m
            if (m == ReadingMode.CONTINUOUS_VERTICAL) {
                if (v.loadChapter(v.index)) {
                    pageHtml = v.html
                    pageBase = v.baseUrl
                    // Don't appendNext() here: the WebView hasn't finished
                    // reloading (still on the old gen), so the chunk JS would
                    // be gen-rejected while the ViewModel records the stitch —
                    // a permanent desync. The scroll handler appends the next
                    // chapter once the fresh page is live.
                    rev++
                } else {
                    showVmError(v)
                }
            } else {
                gotoChapter(v, v.index)
            }
            modeOpen = false
        }
    }

    // Fullscreen reading: hide system bars while on, always restore on exit.
    val activity = context as? android.app.Activity
    DisposableEffect(fullscreenUi) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreenUi) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // No edge-swipe to open (the top-bar menu button is the single open
        // path), but taps outside MUST close: ModalNavigationDrawer's scrim
        // tap-to-close is gated by gesturesEnabled, so it follows the drawer
        // state — disabled while closed, enabled while open.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // RTL: the first child lands at the right — the menu icon
                    // sits to the right of the title; tapping it closes.
                    TextButton(onClick = { scope.launch { drawerState.close() } }) {
                        AppIcon(AppIconKind.List, tint = LocalContentColor.current, size = 18.dp)
                    }
                    Text(
                        stringResource(R.string.chapters),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                val v = vm
                if (v == null) {
                    Text(
                        stringResource(R.string.no_chapter_selected),
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    @Suppress("UNUSED_EXPRESSION")
                    rev
                    val chapterListState = rememberLazyListState()
                    // Land on the chapter being read when the drawer
                    // opens — with long books the current row can be far
                    // off-screen otherwise.
                    LaunchedEffect(drawerState.isOpen) {
                        if (drawerState.isOpen && v.chapters.isNotEmpty()) {
                            val target = v.index.coerceIn(0, v.chapters.size - 1)
                            // Only pull the list when the current
                            // chapter is actually off-screen — yanking
                            // it on every open feels like a jump when
                            // the list was being browsed by hand.
                            val visible = chapterListState.layoutInfo.visibleItemsInfo
                            if (visible.none { it.index == target }) {
                                chapterListState.scrollToItem(target)
                            }
                        }
                    }
                    LazyColumn(state = chapterListState) {
                        itemsIndexed(v.chapters, key = { _, c -> c.file.absolutePath }) { i, c ->
                            NavigationDrawerItem(
                                label = { Text(c.title) },
                                selected = i == v.index,
                                onClick = {
                                    scope.launch {
                                        gotoChapter(v, i)
                                        drawerState.close()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) {
        // Windows parity: character-theme particles on the reading page
        // (ThemeParticles.cs draws over the whole window including reader).
        val particlesOn by prefs.themeParticles.collectAsState(initial = false)
        val readerCharThemeId by prefs.charTheme.collectAsState(initial = "")
        val readerParticleType = remember(readerCharThemeId) {
            CharacterThemes.find(readerCharThemeId)?.particle.orEmpty()
        }
        val readerParticleTheme = remember(readerCharThemeId) {
            CharacterThemes.find(readerCharThemeId)
        }
        val readerThemeKey by prefs.theme.collectAsState(initial = "dark")
        Box(modifier = Modifier.fillMaxSize()) {
            ParticleLayer(
                type = readerParticleType,
                enabled = particlesOn,
                theme = readerParticleTheme,
                light = readerThemeKey == "light"
            )
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = Color.Transparent,
            topBar = {
                if (!fullscreenUi) {
                val v = vm
                @Suppress("UNUSED_EXPRESSION")
                rev
                TopAppBar(
                    // No title text: with six action icons in the bar
                    // the "section X of Y" label was squeezed into an
                    // unreadable vertical wrap next to the drawer button
                    // (removed at Aster's request, both languages).
                    title = {},
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            AppIcon(AppIconKind.List, tint = LocalContentColor.current, size = 19.dp)
                        }
                    },
                    actions = {
                        // Reading layout mode (replaces the old continuous
                        // toggle): ⇄ horizontal paging, ↕ vertical-continuous.
                        TextButton(onClick = { modeOpen = true }, enabled = vm != null) {
                            AppIcon(
                                if (modeNow == ReadingMode.PAGED_HORIZONTAL) AppIconKind.Pages
                                else AppIconKind.Scroll,
                                tint = LocalContentColor.current,
                                size = 19.dp
                            )
                        }
                        TextButton(onClick = { themeOpen = true }, enabled = vm != null) {
                            ContrastIcon(tint = LocalContentColor.current, size = 19.dp)
                        }
                        TextButton(onClick = { fontOpen = true }, enabled = vm != null) {
                            AppIcon(AppIconKind.Font, tint = LocalContentColor.current, size = 17.dp)
                        }
                        TextButton(onClick = { fullscreenUi = true }, enabled = vm != null) {
                            AppIcon(AppIconKind.Fullscreen, tint = LocalContentColor.current, size = 17.dp)
                        }
                        TextButton(onClick = { closeReader() }) {
                            AppIcon(AppIconKind.Close, tint = LocalContentColor.current, size = 16.dp)
                        }
                    }
                )
                }
            },
            bottomBar = {
                if (!fullscreenUi) {
                val v = vm
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                val vv = vm ?: return@TextButton
                                jumpChapter(vv, -1)
                            },
                            enabled = v != null && v.index > 0
                        ) { Text(stringResource(R.string.previous_chapter)) }
                        TextButton(
                            onClick = {
                                val vv = vm ?: return@TextButton
                                jumpChapter(vv, 1)
                            },
                            enabled = v != null && v.index < v.chapters.size - 1
                        ) { Text(stringResource(R.string.next_chapter)) }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val vv = vm ?: return@launch
                                    vv.changeFontSize(-2)
                                    pageHtml = vv.html
                                    rev++
                                }
                            },
                            enabled = v != null
                        ) { Text("−A") }
                        // Numeric size box between −A and +A (WPF txtFontSize
                        // parity): shows the current size, tap to type one.
                        FontSizeBox(
                            fontSize = v?.fontSize ?: 18,
                            enabled = v != null,
                            onCommit = { size ->
                                scope.launch {
                                    val vv = vm ?: return@launch
                                    vv.setFontSize(size)
                                    pageHtml = vv.html
                                    rev++
                                }
                            }
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val vv = vm ?: return@launch
                                    vv.changeFontSize(2)
                                    pageHtml = vv.html
                                    rev++
                                }
                            },
                            enabled = v != null
                        ) { Text("+A") }
                        TextButton(
                            onClick = { spacingOpen = true },
                            enabled = v != null
                        ) { AppIcon(AppIconKind.Spacing, tint = LocalContentColor.current, size = 19.dp) }
                    }
                }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    loadError && vm == null -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(openErr)
                            TextButton(onClick = { closeReader() }) { Text(stringResource(R.string.close)) }
                        }
                    }
                    pageHtml.isEmpty() -> CircularProgressIndicator()
                    else -> ReaderWebView(
                        html = pageHtml,
                        baseUrl = pageBase,
                        bridge = bridge,
                        onError = { details ->
                            scope.launch { snackbar.showSnackbar(chapterErr + details) }
                        },
                        onWebView = { webView = it },
                        paged = modeNow == ReadingMode.PAGED_HORIZONTAL,
                        onPageFinished = {
                            // Backward chapter jump in paged mode lands on
                            // the new chapter's last page (manga-reader
                            // behavior); forward jumps start at page one.
                            if (pendingLastPage) {
                                pendingLastPage = false
                                if (modeNow == ReadingMode.PAGED_HORIZONTAL) {
                                    webView?.evaluateJavascript("rdrLast()", null)
                                }
                            }
                            // In-book link landing: jump to the linked
                            // fragment (the pager queues the jump if
                            // pagination is still running).
                            pendingAnchor?.let { frag ->
                                pendingAnchor = null
                                vm?.let { jumpToAnchor(it, frag) }
                            }
                        },
                        onSwipeLeft = {
                            val v = vm
                            if (v != null && v.readingMode == ReadingMode.PAGED_HORIZONTAL) {
                                // Paged mode: a leftward swipe turns the page
                                // forward for RTL books, backward for LTR.
                                turnPage(v, forward = !v.isEnglish)
                            }
                        },
                        onSwipeRight = {
                            val v = vm
                            if (v != null && v.readingMode == ReadingMode.PAGED_HORIZONTAL) {
                                // Paged mode: a rightward swipe turns the page
                                // forward for LTR books, backward for RTL.
                                turnPage(v, forward = v.isEnglish)
                            }
                        }
                    )
                }
                if (fullscreenUi) {
                    IconButton(
                        onClick = { fullscreenUi = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                            )
                    ) {
                        AppIcon(
                            AppIconKind.ExitFullscreen,
                            tint = MaterialTheme.colorScheme.onSurface,
                            size = 18.dp
                        )
                    }
                }
            }
        }
    }

    if (spacingOpen) {
        val v = vm
        if (v != null) {
            @Suppress("UNUSED_EXPRESSION")
            rev
            SpacingDialog(
                lineHeight = v.lineHeight.toFloat(),
                sideMargin = v.sideMargin,
                paragraphSpacing = v.paragraphSpacing.toFloat(),
                onDismiss = { spacingOpen = false },
                onApply = { lh, margin, para ->
                    scope.launch {
                        try {
                            v.applySpacing(lh.toDouble(), margin, para.toDouble())
                            webView?.updateSpacingJs(v.spacingCss())
                            webView?.updatePagedStyleJs(v.pagedCss())
                            pageHtml = v.html
                            rev++
                        } finally {
                            // A DB failure must not leave the dialog stuck open.
                            spacingOpen = false
                        }
                    }
                },
                onReset = {
                    scope.launch {
                        try {
                            v.applySpacing(1.8, 24, 0.0)
                            webView?.updateSpacingJs(v.spacingCss())
                            webView?.updatePagedStyleJs(v.pagedCss())
                            pageHtml = v.html
                            rev++
                        } finally {
                            spacingOpen = false
                        }
                    }
                }
            )
        }
    }

    if (supportOpen) {
        SupportDialog(onDismiss = { supportOpen = false })
    }

    // Reading-mode picker (manga-reader style popup): horizontal paging
    // (bidirectional arrow; direction follows the book language) vs
    // vertical-continuous (up-down arrow).
    if (modeOpen) {
        Dialog(onDismissRequest = { modeOpen = false }) {
            Card {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.reading_mode),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ReadingModeChoice(
                            icon = AppIconKind.Pages,
                            label = stringResource(R.string.mode_horizontal),
                            selected = modeNow == ReadingMode.PAGED_HORIZONTAL,
                            onClick = { setMode(ReadingMode.PAGED_HORIZONTAL) }
                        )
                        ReadingModeChoice(
                            icon = AppIconKind.Scroll,
                            label = stringResource(R.string.mode_vertical_continuous),
                            selected = modeNow == ReadingMode.CONTINUOUS_VERTICAL,
                            onClick = { setMode(ReadingMode.CONTINUOUS_VERTICAL) }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.mode_direction_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (themeOpen) {
        val v = vm
        // Combined list: static themes + character night/day pages (WPF parity).
        val allThemes = remember { Themes.ALL + characterPages() }
        val currentKey = v?.theme?.key
        Dialog(onDismissRequest = { themeOpen = false }) {
            Card(modifier = Modifier.padding(8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.choose_theme),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    // Two-column grid: each card shows a palette preview so
                    // the user sees what the theme looks like before picking.
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.height(420.dp)
                    ) {
                        items(allThemes, key = { it.key }) { t ->
                            ThemePreviewCard(
                                theme = t,
                                selected = t.key == currentKey,
                                onClick = {
                                    scope.launch {
                                        if (v?.applyTheme(t) == true) {
                                            pageHtml = v.html
                                            pageBase = v.baseUrl
                                            rev++
                                            // Persisted per book (WPF parity).
                                            withContext(Dispatchers.IO) {
                                                prefs.setReaderTheme(bookId, t.key)
                                            }
                                        } else {
                                            snackbar.showSnackbar(themeErr)
                                        }
                                        themeOpen = false
                                    }
                                }
                            )
                        }
                    }
                    TextButton(
                        onClick = { themeOpen = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }

    if (fontOpen) {
        val v = vm
        // Disk I/O off the composition: load the family list once on IO.
        var families by remember { mutableStateOf(listOf("Tahoma")) }
        LaunchedEffect(fontOpen) {
            families = withContext(Dispatchers.IO) {
                val store = FontStore(context)
                listOf("Tahoma") + store.list()
                    .map { it.nameWithoutExtension }
                    .filter { it.isNotBlank() }
                    .distinct()
            }
        }
        AlertDialog(
            onDismissRequest = { fontOpen = false },
            title = { Text(stringResource(R.string.choose_font)) },
            text = {
                LazyColumn {
                    items(families.size) { i ->
                        val family = families[i]
                        TextButton(onClick = {
                            scope.launch {
                                val store = FontStore(context)
                                val files = withContext(Dispatchers.IO) {
                                    if (family == "Tahoma") {
                                        emptyList()
                                    } else {
                                        store.list().filter {
                                            it.nameWithoutExtension.contains(family, ignoreCase = true)
                                        }
                                    }
                                }
                                if (v?.changeFontFamily(family, files) == true) {
                                    pageHtml = v.html
                                    pageBase = v.baseUrl
                                    rev++
                                }
                                fontOpen = false
                            }
                        }) {
                            Text(
                                if (family == "Tahoma") stringResource(R.string.font_system_tahoma)
                                else family
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { fontOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    } // Close fontOpen
        } // Close particle Box
} // Close ReaderScr

@Composable
private fun SpacingDialog(
    lineHeight: Float,
    sideMargin: Int,
    paragraphSpacing: Float,
    onDismiss: () -> Unit,
    onApply: (Float, Int, Float) -> Unit,
    onReset: () -> Unit
) {
    var lh by remember { mutableFloatStateOf(lineHeight) }
    var margin by remember { mutableIntStateOf(sideMargin) }
    var para by remember { mutableFloatStateOf(paragraphSpacing) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onApply(lh, margin, para) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.reset_to_default))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        title = { Text(stringResource(R.string.spacing_settings)) },
        text = {
            Column {
                Text(stringResource(R.string.line_spacing) + ": " + lh.toString())
                Slider(
                    value = lh,
                    onValueChange = { lh = it },
                    valueRange = 1.2f..3.0f,
                    steps = 17
                )
                Text(stringResource(R.string.side_margins) + ": " + margin.toString())
                Slider(
                    value = margin.toFloat(),
                    onValueChange = { margin = it.toInt() },
                    valueRange = 0f..160f,
                    steps = 159
                )
                Text(stringResource(R.string.paragraph_spacing) + ": " + para.toString())
                Slider(
                    value = para,
                    onValueChange = { para = it },
                    valueRange = 0f..2.5f,
                    steps = 24
                )
            }
        }
    )
}

@Throws(IOException::class)
private fun openParsed(
    context: Context,
    epubPath: String,
    bookId: String
): com.novelfa.reader.model.ParsedBook {
    val epub = File(epubPath)
    if (!epub.exists()) {
        throw IOException("epub missing: " + epub.absolutePath)
    }
    val outDir = File(context.cacheDir, "epub_open/" + bookId)
    outDir.mkdirs()
    // Re-extract when the epub changed since the last open.
    val stamp = File(outDir, ".src_stamp")
    val stampOk = stamp.exists() && stamp.readText() == epub.lastModified().toString()
    if (!stampOk) {
        outDir.deleteRecursively()
        outDir.mkdirs()
        ZipFile(epub).use { zf ->
            val entries = zf.entries()
            var entryCount = 0
            var totalBytes = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                // Zip-bomb guard: cap entry count and total uncompressed size.
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw IOException("Zip entry count exceeds limit: " + entry.name)
                }
                val out = File(outDir, entry.name)
                val canonicalOut = out.canonicalPath
                val canonicalDir = outDir.canonicalPath
                // Trailing separator: without it, a sibling dir sharing the
                // string prefix would pass the check.
                if (!canonicalOut.startsWith(canonicalDir + File.separatorChar)) {
                    throw IOException("Zip entry outside target dir: " + entry.name)
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        out.outputStream().use { output ->
                            totalBytes += input.copyTo(output)
                            if (totalBytes > MAX_ZIP_BYTES) {
                                throw IOException("Zip uncompressed size exceeds limit")
                            }
                        }
                    }
                }
            }
        }
        stamp.writeText(epub.lastModified().toString())
    }
    return EpubParser.parse(outDir, epub.name) { n ->
        context.getString(R.string.chapter_fallback, n)
    }
}

/** Zip-bomb guardrails for EPUB extraction (generous: real books are far smaller). */
private const val MAX_ZIP_ENTRIES = 10_000
private const val MAX_ZIP_BYTES = 500L * 1024 * 1024

/**
 * One reading-mode option in the mode picker popup: the mode's drawn
 * icon (IcoStack pages for horizontal, IcoScroll for vertical-continuous)
 * with its label underneath, highlighted when selected.
 */
@Composable
private fun ReadingModeChoice(
    icon: AppIconKind,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        AppIcon(
            icon,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            size = 38.dp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Numeric font-size box between −A and +A — port of WPF txtFontSize
 * (MainWindow.xaml:101 + ApplyFontSize/CommitFontSizeText).
 *
 * Shows the current size; tap to type a new one. Windows parity:
 * - digits only (MaxLength=2); Persian/Arabic digits are accepted and
 *   normalized to English digits on commit (NormalizeDigits parity);
 * - Done/Enter commits, clamped to 10..72;
 * - focus loss commits (LostFocus parity);
 * - invalid/empty input reverts to the current size;
 * - A+/A− presses update the box via [fontSize].
 */
@Composable
private fun FontSizeBox(
    fontSize: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit
) {
    val focusManager = LocalFocusManager.current
    // Reset to the live size whenever it changes externally (A+/A−);
    // while the user types, fontSize doesn't change so their input is kept.
    var text by remember(fontSize) { mutableStateOf(fontSize.toString()) }
    var wasFocused by remember { mutableStateOf(false) }

    fun commit() {
        // NormalizeDigits parity: keep every Unicode digit, map to 0-9.
        val digits = text.filter { it.isDigit() }
            .map { Character.getNumericValue(it) }
            .joinToString("") { it.toString() }
        val value = digits.toIntOrNull()
        if (value != null) {
            onCommit(value.coerceIn(10, 72))
        }
        // Revert the box to the current size (the remember(fontSize)
        // above refreshes it once the ViewModel reports the new size).
        text = fontSize.toString()
    }

    BasicTextField(
        value = text,
        onValueChange = { input ->
            // PreviewTextInput parity: digits only, MaxLength=2.
            text = input.filter { it.isDigit() }.take(2)
        },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelLarge.copy(
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                commit()
                focusManager.clearFocus()
            }
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .width(44.dp)
            .onFocusChanged { state ->
                if (wasFocused && !state.isFocused) {
                    commit()
                }
                wasFocused = state.isFocused
            }
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(vertical = 6.dp)
    )
}
