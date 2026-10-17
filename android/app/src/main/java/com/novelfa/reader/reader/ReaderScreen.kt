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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.novelfa.reader.theme.Themes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import androidx.compose.material3.rememberDrawerState

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
    var loadError by remember { mutableStateOf(false) }
    var pageHtml by remember { mutableStateOf("") }
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

    val bridge = remember {
        ScrollBridge { t, h, c, gen ->
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
        }
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
            val v = ReaderViewModel(
                dao, bookId, entity.copy(totalChapters = parsed.chapters.size),
                parsed.chapters,
                theme = Themes.findStatic(savedTheme) ?: Themes.DARK,
                fontFiles = files
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

    /**
     * Horizontal page turn (paged mode). Asks the page to turn via
     * `rdrTurn`; at the chapter edges it moves to the adjacent chapter
     * instead (like a manga reader).
     */
    fun turnPage(v: ReaderViewModel, forward: Boolean) {
        val wv = webView ?: return
        wv.evaluateJavascript("rdrTurn(${if (forward) 1 else -1})") { result ->
            val r = result?.trim()?.trim { it == '"' }
            scope.launch {
                val vv = vm ?: return@launch
                if (r == "end" && forward && vv.index + 1 < vv.chapters.size) {
                    gotoChapter(vv, vv.index + 1)
                } else if (r == "start" && !forward && vv.index > 0) {
                    gotoChapter(vv, vv.index - 1)
                }
            }
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
                        Text("\u2630")
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
                    LazyColumn {
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
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (!fullscreenUi) {
                val v = vm
                @Suppress("UNUSED_EXPRESSION")
                rev
                TopAppBar(
                    title = {
                        val titleText = if (v != null && v.chapters.isNotEmpty()) {
                            stringResource(
                                R.string.section_x_of_y, v.index + 1, v.chapters.size
                            )
                        } else ""
                        Text(
                            text = titleText,
                            textAlign = if (HtmlText.detectDirection(titleText) == 1) {
                                TextAlign.Left
                            } else {
                                TextAlign.Right
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰")
                        }
                    },
                    actions = {
                        // Reading layout mode (replaces the old continuous
                        // toggle): ⇄ horizontal paging, ↕ vertical-continuous.
                        TextButton(onClick = { modeOpen = true }, enabled = vm != null) {
                            Text(
                                if (modeNow == ReadingMode.PAGED_HORIZONTAL) "⇄" else "↕",
                                fontSize = 22.sp
                            )
                        }
                        TextButton(onClick = { themeOpen = true }, enabled = vm != null) {
                            Text("◑", fontSize = 22.sp)
                        }
                        TextButton(onClick = { fontOpen = true }, enabled = vm != null) {
                            Text("F")
                        }
                        TextButton(onClick = { fullscreenUi = true }, enabled = vm != null) {
                            Text("⛶")
                        }
                        TextButton(onClick = onClose) { Text("✕") }
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
                                scope.launch {
                                    val vv = vm ?: return@launch
                                    gotoChapter(vv, vv.index - 1)
                                }
                            },
                            enabled = v != null && v.index > 0
                        ) { Text(stringResource(R.string.previous_chapter)) }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val vv = vm ?: return@launch
                                    gotoChapter(vv, vv.index + 1)
                                }
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
                        ) { Text("Aa") }
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
                            TextButton(onClick = onClose) { Text("✕") }
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
                    TextButton(
                        onClick = { fullscreenUi = false },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) { Text(stringResource(R.string.exit_fullscreen)) }
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
                            glyph = "⇄",
                            label = stringResource(R.string.mode_horizontal),
                            selected = modeNow == ReadingMode.PAGED_HORIZONTAL,
                            onClick = { setMode(ReadingMode.PAGED_HORIZONTAL) }
                        )
                        ReadingModeChoice(
                            glyph = "↕",
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
        AlertDialog(
            onDismissRequest = { themeOpen = false },
            title = { Text(stringResource(R.string.choose_theme)) },
            text = {
                LazyColumn {
                    items(Themes.ALL.size) { i ->
                        val t = Themes.ALL[i]
                        TextButton(onClick = {
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
                        }) {
                            Text(Themes.NAMES_FA[t.key] ?: t.key)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { themeOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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
    }
}

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
    return EpubParser.parse(outDir, epub.name)
}

/** Zip-bomb guardrails for EPUB extraction (generous: real books are far smaller). */
private const val MAX_ZIP_ENTRIES = 10_000
private const val MAX_ZIP_BYTES = 500L * 1024 * 1024

/**
 * One reading-mode option in the mode picker popup: a big arrow glyph
 * (bidirectional ⇄ for horizontal, up-down ↕ for vertical-continuous)
 * with its label underneath, highlighted when selected.
 */
@Composable
private fun ReadingModeChoice(
    glyph: String,
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
        Text(
            text = glyph,
            fontSize = 40.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
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
