package com.novelfa.reader.reader

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
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
import com.novelfa.reader.parser.BookConverter
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.ConvertedBook
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
    // Pager-recovery guard (see recoverPagerTurn): the chapter index
    // whose pager already got its one reload; reset in gotoChapter
    // whenever the chapter actually changes.
    var pagerReloadTriedIndex by remember { mutableStateOf(-1) }
    // Guard-spent message guard (same reset rule): the chapter index
    // the "pagination failed" notice was already shown for.
    var pagerFailedShownIndex by remember { mutableStateOf(-1) }
    // Last JS pager-teardown reason (via the onPagerError bridge),
    // shown in the recovery notices so a still-dead reader is
    // diagnosable instead of a mystery.
    var lastPagerError by remember { mutableStateOf<String?>(null) }
    // Paged-mode page indicator (ComicScreen "page move preview"
    // analogue for text): the current page and the chapter's page
    // count, read from the pager's own rdrCurrentPage/rdrPageCount
    // (0/0 = unknown, e.g. while a chapter is loading). Reset in
    // gotoChapter so a stale chapter's numbers never show on the new
    // chapter, and refreshed on page-finish and on every turn.
    var pagedPage by remember { mutableIntStateOf(0) }
    var pagedPageCount by remember { mutableIntStateOf(0) }
    // True once the live document has finished loading. A turn that
    // finds rdrTurn missing WHILE the page is still loading must wait
    // (like a 'busy' turn), not recover: recovery reloads the chapter,
    // which cancels the in-flight load, and a tap during the reload
    // then sees a missing rdrTurn again — a "turn -> reload -> turn
    // -> pagination failed" loop on a chapter whose pager was never
    // actually broken. Set false by onPageStarted, true by
    // onPageFinished; only a missing rdrTurn on a LOADED document
    // means a pager-less page and earns a recovery.
    var pageLoaded by remember { mutableStateOf(false) }
    // True once the live document's pager has torn down (JS
    // teardown): the chapter stays readable as a scrolling page, but
    // its page numbers are dead — the bridge must stop feeding the
    // indicator from scroll geometry, or the pill shows a stale
    // "1/60" over a pager that no longer exists. Reset by gotoChapter
    // and by onPageStarted (a new document gets a fresh pager).
    var pagerTornDown by remember { mutableStateOf(false) }
    // Fragment to land on after a cross-chapter in-book link opens its
    // chapter; applied (and cleared) by the onPageFinished hook.
    var pendingAnchor by remember { mutableStateOf<String?>(null) }
    // Text-point anchor ("li:tok") to restore after a same-chapter
    // reload (open, mode switch, settings reload): set by
    // onPageStarted from the ViewModel's saved anchor for this
    // chapter, applied (and cleared) by onPageFinished. A chapter
    // change clears the ViewModel anchor first, so a new chapter
    // never inherits the previous chapter's anchor.
    var pendingAnchorFrag by remember { mutableStateOf<String?>(null) }
    // Paged-mode "pages are being built" state: true from onPageStarted
    // until the pager first reports a page count (or tears down).
    // While true the page pill is replaced by a "preparing" pill, so
    // a building chapter never looks like a blank, dead reader —
    // ReadEra's EventPagesReady idea, in native form.
    var pagerBuilding by remember { mutableStateOf(false) }
    // In-app diagnostic (phone-only testing: no logcat exists here, so
    // the reader must be able to report its own pager/gesture state
    // on screen). Opened from the reading-mode dialog in paged mode.
    // Live JS values, refreshed when the dialog opens and on Refresh:
    // diagJsAnswered=false means the WebView did not answer at all.
    var diagOpen by remember { mutableStateOf(false) }
    var diagPagerState by remember { mutableStateOf("") }
    var diagPageCount by remember { mutableIntStateOf(-1) }
    var diagCurrentPage by remember { mutableIntStateOf(-1) }
    var diagAnchor by remember { mutableStateOf("") }
    var diagJsError by remember { mutableStateOf("") }
    var diagJsAnswered by remember { mutableStateOf(false) }
    // Last native turn result and last native gesture, in raw
    // diagnostic codes (rdrTurn answers, not user prose): these are
    // the logcat lines, surfaced on screen instead.
    var lastTurnDiag by remember { mutableStateOf("") }
    var lastGestureDiag by remember { mutableStateOf("") }
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
    // Font-size limit messages (hoisted: stringResource is not callable
    // from inside a coroutine). Shown when the user hits 10 or 72, so a
    // press that changes nothing is explained instead of looking broken.
    val fontMinMsg = stringResource(R.string.font_size_min, 10)
    val fontMaxMsg = stringResource(R.string.font_size_max, 72)
    val pagerReloadMsg = stringResource(R.string.pager_reload)
    val pagerFailedMsg = stringResource(R.string.pager_failed)

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
            val parsed = withContext(Dispatchers.IO) { openParsed(context, entity.filePath, bookId, entity.originalFileName) }
            val store = FontStore(context)
            val files = withContext(Dispatchers.IO) {
                store.list().filter {
                    it.nameWithoutExtension.contains(entity.fontFamily, ignoreCase = true)
                }
            }
            // Bundled fallback faces (Vazirmatn): installed from assets
            // on first launch, so they are present whenever the reader
            // opens. They feed the NovelfaFallback stack entry that keeps
            // reader text off the phone's own system font.
            val fallbackFiles = withContext(Dispatchers.IO) {
                store.list().filter {
                    it.nameWithoutExtension.contains("Vazirmatn", ignoreCase = true)
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
                fallbackFontFiles = fallbackFiles,
                onXpUnitFinished = xpCallback
            )
            // Book direction from CONTENT, not from the UI language:
            // `isEnglish` drives the paged strip geometry (which side the
            // next page sits on) and the swipe/tap maps, and it used to
            // stay false for every book — so an English book paginated
            // right-to-left. Per-paragraph direction inside the page is
            // stamped separately by prepareChapterBody either way.
            v.isEnglish = detectBookIsLtr(parsed.chapters)
            v.readingMode = ReadingMode.fromKey(savedMode)
            // Seed the text-point anchor BEFORE loadChapter: its
            // forced saveProgress must preserve the saved anchor for
            // the target chapter (not clear it), and onPageStarted
            // restores it after the reload.
            v.pagedAnchor = entity.lastAnchor
            v.pagedAnchorChapter = entity.lastChapterIndex
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
        // A real chapter change re-arms pager recovery; a same-chapter
        // reload (the recovery itself, a mode switch) must not, or a
        // permanently torn-down chapter would reload-loop on every tap.
        if (i != v.index) {
            pagerReloadTriedIndex = -1
            pagerFailedShownIndex = -1
            lastPagerError = null
            pagerTornDown = false
            // A new chapter has no text anchor yet: clear it before
            // the load, so onPageStarted cannot restore the previous
            // chapter's anchor into this chapter, and the saved
            // position for the new chapter starts clean.
            v.clearPagedAnchor()
            pendingAnchorFrag = null
        }
        if (v.loadChapter(i)) {
            pageHtml = v.html
            pageBase = v.baseUrl
            pagedPage = 0
            pagedPageCount = 0
            rev++
        } else {
            showVmError(v)
        }
    }

    /**
     * Refreshes the paged-mode page indicator from the pager itself
     * (never from the scroll reporter: in paged mode the document does
     * not scroll, so scrollTop/scrollHeight would report a bogus 1/1).
     * Called on page-finish and after a successful turn; while the
     * pager is still building, rdrPageCount() is 0 and the indicator
     * stays hidden instead of showing a wrong number.
     */
    fun refreshPagedIndicator() {
        val wv = webView ?: return
        // Also queries the pager state and the JS teardown reason:
        // this is the bridge-independent torn detection. The
        // onPagerError bridge is the primary path, but a torn pager
        // whose bridge call never arrived used to leave native state
        // desynced (live pill, paged gestures, building flag stuck)
        // — a direct query here, run on page-finish and after every
        // turn, catches that case and applies the same torn handling.
        wv.evaluateJavascript(
            "(function(){try{var out='';" +
                "if(window.rdrPageCount&&window.rdrCurrentPage)" +
                "{var c=window.rdrPageCount();if(c>0)out=(window.rdrCurrentPage()+1)+'/'+c;}" +
                "out+='|';if(window.rdrPagerState)out+=window.rdrPagerState();" +
                "out+='|';if(window.__rdrPagerError)out+=window.__rdrPagerError;" +
                "return out;}catch(e){return '';}})();"
        ) { result ->
            val clean = result?.trim()?.trim { it == '"' } ?: ""
            val parts = clean.split("|")
            if (parts.size == 3) {
                val state = parts[1]
                if (state == "torn") {
                    // Same handling as onPagerError, without depending
                    // on the bridge: torn document, no page numbers,
                    // no building pill, reason kept for the dialog and
                    // for the failure notice.
                    pagerTornDown = true
                    pagedPage = 0
                    pagedPageCount = 0
                    pagerBuilding = false
                    if (parts[2].isNotEmpty()) lastPagerError = parts[2]
                    return@evaluateJavascript
                }
                val nums = parts[0].split("/")
                if (nums.size == 2) {
                    val p = nums[0].toIntOrNull()
                    val total = nums[1].toIntOrNull()
                    if (p != null && total != null && total > 0 && p in 1..total) {
                        pagedPage = p
                        pagedPageCount = total
                        // First real page count = pager ready: the
                        // "preparing" pill gives way to the page pill.
                        pagerBuilding = false
                    }
                }
            }
        }
    }

    /**
     * Queries the live pager once for the diagnostic dialog: state,
     * page count, current page (0-based, as rdrCurrentPage returns
     * it) and text anchor, joined by '|'. A missing function means
     * the pager script never ran in this document ('missing'); an
     * unanswered WebView leaves diagJsAnswered=false. Called when
     * the dialog opens and on its Refresh button — never on a timer,
     * so it cannot spam the WebView while reading.
     */
    fun refreshDiag() {
        val wv = webView
        if (wv == null) {
            diagJsAnswered = false
            return
        }
        wv.evaluateJavascript(
            "(function(){try{var s='';" +
                "s+=(window.rdrPagerState?window.rdrPagerState():'missing');s+='|';" +
                "s+=(window.rdrPageCount?window.rdrPageCount():-1);s+='|';" +
                "s+=(window.rdrCurrentPage?window.rdrCurrentPage():-1);s+='|';" +
                "s+=(window.rdrCurrentAnchor?window.rdrCurrentAnchor():'');" +
                "return s;}catch(e){return 'error|-1|-1|';}})();"
        ) { result ->
            val clean = result?.trim()?.trim { it == '"' } ?: ""
            val parts = clean.split("|")
            if (parts.size == 4) {
                diagJsAnswered = true
                diagPagerState = parts[0]
                diagPageCount = parts[1].toIntOrNull() ?: -1
                diagCurrentPage = parts[2].toIntOrNull() ?: -1
                diagAnchor = parts[3]
                // Teardown reason straight from JS, as a second
                // evaluateJavascript call (a '|' inside a reason would
                // break the split above): the dialog shows it when
                // the bridge error never arrived.
                if (parts[0] == "torn") {
                    wv.evaluateJavascript(
                        "(function(){try{return window.__rdrPagerError||'';}catch(e){return '';}})();"
                    ) { errResult ->
                        diagJsError = errResult?.trim()?.trim { it == '"' } ?: ""
                    }
                } else {
                    diagJsError = ""
                }
            } else {
                diagJsAnswered = false
            }
        }
    }

    /**
     * Reads the current paged page's text-point anchor ("li:tok") from
     * the pager and hands it to the ViewModel for (throttled) saving.
     * Called after every successful paged turn and on page-finish, so
     * the saved anchor is never more than a few pages stale. A torn
     * or not-yet-built pager returns '' and is ignored.
     */
    fun refreshPagedAnchor() {
        val wv = webView ?: return
        val v = vm ?: return
        if (v.readingMode != ReadingMode.PAGED_HORIZONTAL) return
        wv.evaluateJavascript(
            "(function(){try{return window.rdrCurrentAnchor ? window.rdrCurrentAnchor() : '';}catch(e){return '';}})();"
        ) { result ->
            val anchor = result?.trim()?.trim { it == '"' } ?: ""
            if (anchor.matches(Regex("^\\d+:\\d+$"))) {
                val vv = vm ?: return@evaluateJavascript
                scope.launch { vv.notePagedAnchor(anchor) }
            }
        }
    }

    /**
     * Pager recovery, shared by native turns (turnPage) and JS tap
     * turns (onPagerStuck): when a turn fails because the pager never
     * became ready (torn down) or the live page was built without it,
     * the reader otherwise sits dead with no signal at all. Reloading
     * the current chapter once gives the pager a fresh document: a
     * transient failure heals, and the guard above stops a
     * content-permanent failure from looping. The user is told a
     * reload happened, so a still-dead reader after it is a reportable
     * fact instead of a mystery.
     */
    fun recoverPagerTurn() {
        val vv = vm ?: return
        if (pagerReloadTriedIndex == vv.index) {
            // The one reload already happened and the pager still
            // fails: further turns used to die silently here, leaving
            // a dead reader with no signal at all. Say so once per
            // chapter, with the JS teardown reason when known, and
            // point at the ways the chapter stays readable (teardown
            // restores it as a scrolling page, and the drawer can open
            // another chapter whose pager may work).
            Log.w("NovelfaPager", "page turn still failing after pager reload (chapter ${vv.index})")
            if (pagerFailedShownIndex != vv.index) {
                pagerFailedShownIndex = vv.index
                val suffix = lastPagerError?.let { " ($it)" } ?: ""
                scope.launch { snackbar.showSnackbar(pagerFailedMsg + suffix) }
            }
            return
        }
        pagerReloadTriedIndex = vv.index
        Log.d("NovelfaPager", "recoverPagerTurn reload chapter=${vv.index} lastError=$lastPagerError")
        scope.launch {
            val suffix = lastPagerError?.let { " ($it)" } ?: ""
            snackbar.showSnackbar(pagerReloadMsg + suffix)
            gotoChapter(vv, vv.index)
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
                // NOTE: onScroll deliberately does NOT update the page
                // pill anymore. It used to derive pill numbers from
                // scroll geometry (t/c, h/c) on the assumption that
                // only the pager calls onScroll in paged mode — but a
                // torn-down pager restores a scrolling flow document
                // whose scroll reporter then feeds its scroll screens
                // (screens tall, screen at top) into the pill as if
                // they were pages. Page numbers now come only from
                // onPagerReport below, which a flow document can
                // never fire.
                applyIncrementalJs(v, last, first)
                rev++
            }
            },
            // Pager page report (see ScrollBridge): the sole source
            // of page-pill numbers. A report also proves the pager
            // is alive, so it clears the building flag.
            onPagerReport = { page, total ->
                scope.launch {
                    if (total > 0 && page in 1..total) {
                        if (pagedPage != page || pagedPageCount != total) {
                            Log.d("NovelfaPager", "pager report page=$page/$total")
                        }
                        pagedPage = page
                        pagedPageCount = total
                        pagerBuilding = false
                    }
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
            },
            // Paged-mode tap turn at a chapter edge (the JS tap zones
            // call rdrTurn directly, so edges need native handling just
            // like swipe turns get in turnPage).
            onPageEdge = { dir ->
                lastGestureDiag = "JS tap, page edge $dir"
                scope.launch {
                    val v = vm ?: return@launch
                    if (dir > 0 && v.index + 1 < v.chapters.size) {
                        gotoChapter(v, v.index + 1)
                    } else if (dir < 0 && v.index > 0) {
                        pendingLastPage = true
                        gotoChapter(v, v.index - 1)
                    }
                }
            },
            // Pager stuck (JS tap turns): a torn-down or missing pager
            // needs the same native recovery as swipe turns get in
            // turnPage (one reload per chapter, guarded there).
            onPagerStuck = {
                Log.w("NovelfaPager", "onPagerStuck from JS -> recover")
                lastGestureDiag = "JS tap stuck -> recover"
                scope.launch { recoverPagerTurn() }
            },
            // JS teardown reason: logged and kept for the recovery
            // notices, so a deterministically failing chapter reports
            // WHY instead of just dying.
            onPagerError = { msg ->
                Log.w("NovelfaPager", "pager teardown: $msg")
                scope.launch {
                    lastPagerError = msg
                    // The pager is gone and the chapter is a scrolling
                    // page now: hide the pill instead of leaving the
                    // last good number on screen as a stale "1/60".
                    // (The bridge guard above stops scroll geometry
                    // from repopulating it afterwards.)
                    pagerTornDown = true
                    pagedPage = 0
                    pagedPageCount = 0
                    pagerBuilding = false
                }
            }
        )
    }

    /**
     * Fallback turn for a document whose pager is gone (torn down, or
     * built without one): the document is a scrolling flow page, so a
     * turn becomes a one-viewport scroll, and a turn at the flow's top
     * or bottom becomes a chapter move instead. This is what keeps a
     * failed chapter readable instead of silently dead: without it, a
     * turn on a torn document did nothing at all once its one reload
     * was spent, and even the "scroll vertically" advice failed because
     * the native listener had eaten the WebView's ACTION_DOWN.
     * Only ever called for a pager-less document (see turnPage's
     * 'torn' and missing-rdrTurn branches): on a live paged strip the
     * scroll geometry is viewport-sized, so this edge logic would
     * wrongly jump chapters there.
     */
    fun fallbackScrollPage(v: ReaderViewModel, forward: Boolean) {
        val wv = webView ?: return
        wv.evaluateJavascript(
            "(function(){var d=document.documentElement,b=document.body;" +
                "var h=Math.max(d.scrollHeight||0,b.scrollHeight||0);" +
                "var t=Math.max(d.scrollTop||0,b.scrollTop||0);" +
                "var vh=window.innerHeight||d.clientHeight||0;" +
                "return t+'/'+h+'/'+vh;})();"
        ) { result ->
            val parts = (result?.trim()?.trim { it == '"' } ?: "").split("/")
            val t = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val h = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val vh = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            scope.launch {
                val vv = vm ?: return@launch
                if (forward && vh > 0 && t + vh >= h - 2 && vv.index + 1 < vv.chapters.size) {
                    gotoChapter(vv, vv.index + 1)
                } else if (!forward && t <= 2 && vv.index > 0) {
                    pendingLastPage = true
                    gotoChapter(vv, vv.index - 1)
                } else if (vh > 0) {
                    webView?.evaluateJavascript(
                        "window.scrollBy(0, ${if (forward) vh.toInt() else -vh.toInt()})", null
                    )
                }
            }
        }
    }

    /**
     * Horizontal page turn (paged mode). Asks the page to turn via
     * `rdrTurn`; at the chapter edges it moves to the adjacent chapter
     * instead (like a manga reader).
     *
     * A 'busy' answer (pagination still building, or mid-animation) is
     * RETRIED, not dropped: a dropped turn was exactly the "swipe does
     * nothing / the next page never arrives" symptom. Retries stop
     * after 10 attempts (~3s) and only run while this same ViewModel
     * is still live, so a chapter jump in between can't turn a page
     * in the new chapter by accident.
     */
    fun turnPage(v: ReaderViewModel, forward: Boolean, attempt: Int = 0) {
        val wv = webView ?: run {
            Log.d("NovelfaPager", "turnPage dropped webView=null forward=$forward")
            return
        }
        wv.evaluateJavascript("rdrTurn(${if (forward) 1 else -1})") { result ->
            val r = result?.trim()?.trim { it == '"' }
            // Diagnostic: one line per turn attempt (attempt>0 only on
            // busy retries, so a healthy turn logs exactly once).
            Log.d("NovelfaPager", "turnPage forward=$forward attempt=$attempt rdrTurn=$r")
            if (r == "torn") {
                // Pager tore down: retrying it is pointless (it will
                // never become ready) and was exactly the path that
                // ended in silence. Reload once if that hasn't been
                // tried; otherwise scroll the restored flow document
                // instead of doing nothing (recoverPagerTurn shows the
                // failure notice once per chapter in the spent case).
                Log.w("NovelfaPager", "turnPage rdrTurn torn forward=$forward")
                // The turn itself proves the document is torn, even
                // if the onPagerError bridge never arrived (the
                // desync that left a torn document with a live pill
                // and paged gestures): mark it torn NOW, so gestures
                // switch off for this document and the pill stops
                // accepting scroll geometry. A reload (recover) resets
                // the flag for the new document via onPageStarted.
                pagerTornDown = true
                val alreadyReloaded = pagerReloadTriedIndex == v.index
                lastTurnDiag = if (alreadyReloaded) "torn -> fallback scroll (reload spent)"
                    else "torn -> recover (reload once)"
                recoverPagerTurn()
                if (alreadyReloaded) fallbackScrollPage(v, forward)
                return@evaluateJavascript
            }
            if (r == null) {
                // rdrTurn itself is missing. While the document is
                // still loading that is EXPECTED (its scripts have not
                // run yet): wait like a 'busy' turn instead of
                // recovering, because recovery reloads the chapter and
                // a tap during the reload would see a missing rdrTurn
                // again — the reload loop behind the "pagination
                // failed" notice on a healthy chapter. Only a missing
                // rdrTurn on a loaded document means a pager-less page
                // (a mode/desync), which no retry can ever turn.
                if (!pageLoaded && attempt < 10) {
                    Log.d("NovelfaPager", "turnPage rdrTurn missing while loading -> wait attempt=$attempt")
                    lastTurnDiag = "rdrTurn missing, waiting (attempt $attempt)"
                    scope.launch {
                        delay(300)
                        if (vm === v) turnPage(v, forward, attempt + 1)
                    }
                } else {
                    // Same spent-reload rule as 'torn': a loaded
                    // document without rdrTurn is a flow document, so
                    // scroll it instead of going silent after reload.
                    Log.w("NovelfaPager", "turnPage rdrTurn missing -> recover")
                    // Same torn-document marking as the 'torn' branch:
                    // a loaded document without rdrTurn is a flow
                    // document, not a paged one.
                    pagerTornDown = true
                    val alreadyReloaded = pagerReloadTriedIndex == v.index
                    lastTurnDiag = if (alreadyReloaded) "rdrTurn missing -> fallback scroll (reload spent)"
                        else "rdrTurn missing -> recover (reload once)"
                    recoverPagerTurn()
                    if (alreadyReloaded) fallbackScrollPage(v, forward)
                }
                return@evaluateJavascript
            }
            if (r == "busy") {
                if (attempt < 10) {
                    lastTurnDiag = "busy, retry (attempt $attempt)"
                    scope.launch {
                        delay(300)
                        if (vm === v) turnPage(v, forward, attempt + 1)
                    }
                } else if (attempt < 30) {
                    // Past the first window, ask the pager what it is
                    // doing before deciding: a chapter that is merely
                    // still BUILDING (slow chapter, late images) must
                    // never be reloaded — reloading restarts the build
                    // and shows a bogus failure. Keep waiting for it
                    // (the "preparing" pill is visible meanwhile); only
                    // a non-building pager earns recovery.
                    wv.evaluateJavascript(
                        "(function(){try{return window.rdrPagerState ? window.rdrPagerState() : '';}catch(e){return '';}})();"
                    ) { stateResult ->
                        val state = stateResult?.trim()?.trim { it == '"' } ?: ""
                        scope.launch {
                            delay(300)
                            if (vm === v) {
                                if (state == "building") {
                                    lastTurnDiag = "busy, building, retry (attempt ${attempt + 1})"
                                    turnPage(v, forward, attempt + 1)
                                } else {
                                    Log.w("NovelfaPager", "turnPage rdrTurn busy exhausted state=$state -> recover")
                                    lastTurnDiag = "busy exhausted, state=$state -> recover"
                                    recoverPagerTurn()
                                }
                            }
                        }
                    }
                } else {
                    // Still building after ~9s: drop this turn without
                    // reloading or failing — the pager may still become
                    // ready, and the user can tap again. A reload here
                    // would only restart a slow build.
                    Log.w("NovelfaPager", "turnPage rdrTurn busy exhausted after long build -> drop")
                    lastTurnDiag = "busy, building, dropped after ~9s"
                }
                return@evaluateJavascript
            }
            scope.launch {
                val vv = vm ?: return@launch
                if (r == "page") {
                    lastTurnDiag = "page"
                    refreshPagedIndicator()
                    refreshPagedAnchor()
                } else if (r == "end" && forward && vv.index + 1 < vv.chapters.size) {
                    lastTurnDiag = "end -> next chapter"
                    gotoChapter(vv, vv.index + 1)
                } else if (r == "start" && !forward && vv.index > 0) {
                    // Land on the previous chapter's last page; the
                    // onPageFinished hook fires rdrLast() once loaded.
                    lastTurnDiag = "start -> prev chapter (last page)"
                    pendingLastPage = true
                    gotoChapter(vv, vv.index - 1)
                } else if (r == "end" || r == "start") {
                    // At the book's edge: no chapter to move to, so
                    // the turn correctly does nothing.
                    lastTurnDiag = "$r (book edge, no chapter)"
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
                    // RTL: the first child lands at the right. An X closes
                    // (the old list icon here read as "menu", not "close").
                    IconButton(onClick = { scope.launch { drawerState.close() } }) {
                        AppIcon(AppIconKind.Close, tint = LocalContentColor.current, size = 18.dp)
                    }
                    Text(
                        stringResource(R.string.chapters),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    vm?.chapters?.size?.let { count ->
                        Text(
                            text = stringResource(R.string.chapters_count, count),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
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
                            val selected = i == v.index
                            NavigationDrawerItem(
                                label = { Text(c.title) },
                                selected = selected,
                                onClick = {
                                    scope.launch {
                                        gotoChapter(v, i)
                                        drawerState.close()
                                    }
                                },
                                // Number chip (same avatar-chip language as
                                // the character rows): bare numeric titles
                                // no longer leave the row looking empty,
                                // and titled chapters gain their number.
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = String.format("%d", i + 1),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                // The default selected pill is purple
                                // secondaryContainer — off-theme next to
                                // the app's orange. Use the accent.
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
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
                        // Paged (horizontal) mode: chapter jumps happen
                        // by turning past the chapter's edges (tap zones,
                        // swipe, drag) and via the chapter drawer, so the
                        // Previous/Next section buttons are replaced by
                        // those gestures here. Vertical mode keeps them.
                        if (modeNow != ReadingMode.PAGED_HORIZONTAL) {
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
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val vv = vm ?: return@launch
                                    if (vv.fontSize <= 10) {
                                        snackbar.showSnackbar(fontMinMsg)
                                    } else {
                                        vv.changeFontSize(-2)
                                        pageHtml = vv.html
                                        rev++
                                    }
                                }
                            },
                            enabled = v != null
                        ) { Text("−A") }
                        // Numeric size box between −A and +A (WPF txtFontSize
                        // parity): shows the current size, tap to type one.
                        // A typed value outside 10..72 is reported (not
                        // silently snapped): the ViewModel still clamps as
                        // a safety net, but the user now knows the limit.
                        FontSizeBox(
                            fontSize = v?.fontSize ?: 18,
                            enabled = v != null,
                            onCommit = { size ->
                                scope.launch {
                                    val vv = vm ?: return@launch
                                    if (size < 10) {
                                        snackbar.showSnackbar(fontMinMsg)
                                    } else if (size > 72) {
                                        snackbar.showSnackbar(fontMaxMsg)
                                    }
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
                                    if (vv.fontSize >= 72) {
                                        snackbar.showSnackbar(fontMaxMsg)
                                    } else {
                                        vv.changeFontSize(2)
                                        pageHtml = vv.html
                                        rev++
                                    }
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
                        // Torn-down pager => the document is a scrolling
                        // flow page: stop eating its touches (see
                        // ReaderWebView's paged comment) so it scrolls
                        // natively again. Reset by onPageStarted for the
                        // next document, which gets a fresh pager.
                        paged = modeNow == ReadingMode.PAGED_HORIZONTAL && !pagerTornDown,
                        onPageStarted = {
                            // A new document is loading: its pager does
                            // not exist yet, and any indicator numbers
                            // on screen belong to the previous document.
                            pageLoaded = false
                            pagerTornDown = false
                            pagedPage = 0
                            pagedPageCount = 0
                            // A new document's pager has no error yet:
                            // the previous document's teardown reason
                            // must not linger into the new document's
                            // dialog. (Failure notices read the reason
                            // before the reload, so clearing here loses
                            // nothing.)
                            lastPagerError = null
                            // Same-chapter reload (open, mode switch,
                            // settings reload): remember the saved
                            // text anchor for this chapter so
                            // onPageFinished can restore it. A chapter
                            // change clears the ViewModel anchor in
                            // gotoChapter, so this never fires for a
                            // new chapter.
                            val vv = vm
                            if (modeNow == ReadingMode.PAGED_HORIZONTAL && vv != null &&
                                vv.pagedAnchorChapter == vv.index && vv.pagedAnchor != null
                            ) {
                                pendingAnchorFrag = vv.pagedAnchor
                                pagerBuilding = true
                            } else if (modeNow == ReadingMode.PAGED_HORIZONTAL) {
                                pagerBuilding = true
                            }
                        },
                        onPageFinished = {
                            pageLoaded = true
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
                            // Text-point restore for a same-chapter
                            // reload: hand the saved anchor to the
                            // pager. At onPageFinished the pager is
                            // usually still building (it waits for
                            // fonts/images), so rdrGoToAnchorFrag
                            // QUEUES it and paginate() applies it
                            // when its first build finishes — the
                            // call must not wait for readiness here.
                            // A torn pager ignores it (its document
                            // is a flow page, not a strip).
                            if (modeNow == ReadingMode.PAGED_HORIZONTAL && !pagerTornDown) {
                                val frag = pendingAnchorFrag
                                if (frag != null) {
                                    pendingAnchorFrag = null
                                    webView?.evaluateJavascript(
                                        "window.rdrGoToAnchorFrag ? window.rdrGoToAnchorFrag('$frag') : false",
                                        null
                                    )
                                }
                            }
                            // Fallback refresh: the pager's report()
                            // (via onScroll above) is the primary source,
                            // but a turn that landed via rdrLast() or an
                            // anchor jump also needs the indicator updated.
                            if (modeNow == ReadingMode.PAGED_HORIZONTAL) {
                                refreshPagedIndicator()
                                refreshPagedAnchor()
                            }
                        },
                        onSwipeLeft = {
                            lastGestureDiag = "swipe left"
                            val v = vm
                            if (v != null && v.readingMode == ReadingMode.PAGED_HORIZONTAL) {
                                // Paged mode, strip math (show() uses
                                // tx = -physLeft): LTR next pages sit to
                                // the right, so a leftward swipe (content
                                // dragged left) advances; RTL next pages
                                // sit to the left, so a leftward swipe
                                // goes back. The old mapping had this
                                // backwards for both directions (never
                                // noticed because onDown killed swipes).
                                turnPage(v, forward = v.isEnglish)
                            }
                        },
                        onSwipeRight = {
                            lastGestureDiag = "swipe right"
                            val v = vm
                            if (v != null && v.readingMode == ReadingMode.PAGED_HORIZONTAL) {
                                // Mirror of the above: RTL next pages sit
                                // to the left, so a rightward swipe
                                // (content dragged right) advances.
                                turnPage(v, forward = !v.isEnglish)
                            }
                        },
                        // Native tap edges (see ReaderWebView): the
                        // position-to-direction map is the JS tap-zone
                        // map, NOT the swipe map — tapping the side a
                        // page sits on turns to it. RTL: left = forward,
                        // right = back. LTR: the mirror.
                        onTapLeftEdge = {
                            lastGestureDiag = "tap left edge"
                            val v = vm
                            if (v != null && v.readingMode == ReadingMode.PAGED_HORIZONTAL) {
                                turnPage(v, forward = !v.isEnglish)
                            }
                        },
                        onTapRightEdge = {
                            lastGestureDiag = "tap right edge"
                            val v = vm
                            if (v != null && v.readingMode == ReadingMode.PAGED_HORIZONTAL) {
                                turnPage(v, forward = v.isEnglish)
                            }
                        }
                    )
                }
                // Paged mode: ComicScreen-style page-move preview,
                // text edition — a small "page X of Y" pill, so a turn
                // is never a blind jump and a stuck pager (frozen
                // number) is visible at a glance. Hidden while the
                // pager is still building (0/0) and in fullscreen.
                if (modeNow == ReadingMode.PAGED_HORIZONTAL && !fullscreenUi &&
                    pagedPageCount > 0 && pagedPage in 1..pagedPageCount
                ) {
                    Text(
                        text = stringResource(R.string.page_indicator, pagedPage, pagedPageCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                // Paged mode, pager still building: the page pill
                // above is hidden (0/0), so show a "preparing" pill
                // in the same spot instead of a blank reader.
                if (modeNow == ReadingMode.PAGED_HORIZONTAL && !fullscreenUi &&
                    pagerBuilding && pagedPageCount == 0
                ) {
                    Text(
                        text = stringResource(R.string.pager_building),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                // Vertical mode: the next chapter is stitched in on
                // scroll, so show a thin bar while an append/prepend is
                // in flight — the user sees "loading" instead of a dead
                // end at the bottom of the page.
                if (modeNow == ReadingMode.CONTINUOUS_VERTICAL && vm?.isAppending == true) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
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
                    // Phone-only testing: the paged diagnostic dialog
                    // (pager state, page numbers, last turn/gesture) is
                    // the on-screen replacement for logcat. Only in
                    // paged mode — it reports paged state, which does
                    // not exist in vertical mode.
                    if (modeNow == ReadingMode.PAGED_HORIZONTAL) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = {
                            modeOpen = false
                            diagOpen = true
                            refreshDiag()
                        }) { Text(stringResource(R.string.diag_open_button)) }
                    }
                }
            }
        }
    }

    // Diagnostic dialog: live pager values (queried on open and on
    // Refresh) plus native state. The user reads it off the phone
    // and reports the rows — no computer needed. Values are raw
    // diagnostic codes (pager states, rdrTurn answers); only the
    // row labels are localized.
    if (diagOpen) {
        val v = vm
        val diagNone = stringResource(R.string.diag_none)
        val diagYes = stringResource(R.string.yes)
        val diagNo = stringResource(R.string.no)
        Dialog(onDismissRequest = { diagOpen = false }) {
            Card(modifier = Modifier.padding(8.dp)) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.diag_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    DiagRow(
                        stringResource(R.string.diag_mode),
                        if (modeNow == ReadingMode.PAGED_HORIZONTAL)
                            stringResource(R.string.mode_horizontal)
                        else stringResource(R.string.mode_vertical_continuous)
                    )
                    DiagRow(
                        stringResource(R.string.diag_chapter),
                        if (v != null) "${v.index + 1} / ${v.chapters.size}" else diagNone
                    )
                    if (v != null && v.index in v.chapters.indices) {
                        DiagRow(
                            stringResource(R.string.diag_chapter_title),
                            v.chapters[v.index].title
                        )
                    }
                    DiagRow(
                        stringResource(R.string.diag_doc_loaded),
                        if (pageLoaded) diagYes else diagNo
                    )
                    DiagRow(
                        stringResource(R.string.diag_pager_state),
                        if (diagJsAnswered) diagPagerState else diagNone
                    )
                    DiagRow(
                        stringResource(R.string.diag_page_count),
                        if (diagJsAnswered && diagPageCount >= 0) "$diagPageCount" else diagNone
                    )
                    DiagRow(
                        stringResource(R.string.diag_current_page),
                        if (diagJsAnswered && diagPageCount > 0 && diagCurrentPage >= 0)
                            "${diagCurrentPage + 1}" else diagNone
                    )
                    DiagRow(
                        stringResource(R.string.diag_pill),
                        if (pagedPageCount > 0) "$pagedPage / $pagedPageCount" else diagNone
                    )
                    DiagRow(
                        stringResource(R.string.diag_anchor),
                        if (diagAnchor.isNotEmpty()) diagAnchor else diagNone
                    )
                    DiagRow(
                        stringResource(R.string.diag_last_turn),
                        if (lastTurnDiag.isNotEmpty()) lastTurnDiag else diagNone
                    )
                    DiagRow(
                        stringResource(R.string.diag_last_error),
                        lastPagerError
                            ?: if (diagJsError.isNotEmpty()) diagJsError else diagNone
                    )
                    DiagRow(
                        stringResource(R.string.diag_torn),
                        if (pagerTornDown) diagYes else diagNo
                    )
                    DiagRow(
                        stringResource(R.string.diag_building),
                        if (pagerBuilding) diagYes else diagNo
                    )
                    DiagRow(
                        stringResource(R.string.diag_last_gesture),
                        if (lastGestureDiag.isNotEmpty()) lastGestureDiag else diagNone
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.diag_note_js_taps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(onClick = { refreshDiag() }) {
                            Text(stringResource(R.string.diag_refresh))
                        }
                        TextButton(onClick = { diagOpen = false }) {
                            Text(stringResource(R.string.close))
                        }
                    }
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
        // No Tahoma entry: it does not exist on Android and only ever
        // fell through to the phone's system font.
        var families by remember { mutableStateOf(emptyList<String>()) }
        LaunchedEffect(fontOpen) {
            families = withContext(Dispatchers.IO) {
                val store = FontStore(context)
                store.list()
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
                                    store.list().filter {
                                        it.nameWithoutExtension.contains(family, ignoreCase = true)
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
                            Text(family)
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

/**
 * Opens a stored book into its chapter files. EPUBs are extracted (once
 * per source change, guarded by the stamp) and parsed from the tree;
 * TXT / Markdown / PDF / DOCX are converted by [BookConverter] into
 * chapter files in the same cache directory, and a valid stamp lets the
 * next open rebuild the book from those files without re-converting.
 * The stamp covers mtime AND length, so a replaced file with an
 * unchanged mtime still re-converts.
 */
@Throws(IOException::class)
private fun openParsed(
    context: Context,
    bookPath: String,
    bookId: String,
    /** Original file name, when known: title fallback instead of the "$uuid.<ext>" storage name. */
    titleFallback: String? = null
): com.novelfa.reader.model.ParsedBook {
    val book = File(bookPath)
    if (!book.exists()) {
        throw IOException("book missing: " + book.absolutePath)
    }
    val format = BookFormat.detect(book)
        ?: throw IOException("unsupported book format: " + book.name)
    // Converted/extracted chapters live in filesDir, NOT cacheDir:
    // Android may evict cacheDir under storage pressure, and every
    // eviction forced a full re-parse on the next open — for DOCX that
    // is the "render takes forever" users see on every open. filesDir
    // is never evicted by the system; removeBook + the startup prune
    // delete a book's dir, and backup rules exclude it (regenerable).
    val outDir = File(context.filesDir, "epub_open/" + bookId)
    outDir.mkdirs()
    val chapterFallback: (Int) -> String = { n ->
        context.getString(R.string.chapter_fallback, n)
    }
    val stamp = File(outDir, ".src_stamp")
    val stampText = book.lastModified().toString() + "|" + book.length()
    val stampOk = stamp.exists() && runCatching { stamp.readText() }.getOrNull() == stampText
    if (stampOk) {
        if (format == BookFormat.EPUB) {
            return EpubParser.parse(outDir, titleFallback ?: book.name, chapterFallback)
        }
        ConvertedBook.read(outDir, titleFallback ?: book.name, chapterFallback)?.let { return it }
        // Cache present but no chapter files (e.g. a crashed convert):
        // fall through to a fresh conversion below.
    }
    outDir.deleteRecursively()
    outDir.mkdirs()
    val parsed = if (format == BookFormat.EPUB) {
        ZipFile(book).use { zf ->
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
        EpubParser.parse(outDir, titleFallback ?: book.name, chapterFallback)
    } else {
        BookConverter.parse(book, outDir, chapterFallback, titleFallback)
    }
    stamp.writeText(stampText)
    return parsed
}

/**
 * Book-level reading direction from content: the majority script of the
 * first few chapters' plain text. Latin majority → LTR; anything else
 * (Persian/Arabic majority, or no letters at all) → RTL, matching the
 * app's Persian-first default. Only a sample is read (4 KB per chapter)
 * so opening a long book stays cheap.
 */
private fun detectBookIsLtr(chapters: List<com.novelfa.reader.model.Chapter>): Boolean {
    val sample = StringBuilder()
    for (chapter in chapters.take(3)) {
        val text = runCatching {
            chapter.file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(4096)
                val n = reader.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
        }.getOrNull() ?: continue
        sample.append(HtmlText.htmlToPlainText(text)).append(' ')
        if (sample.length >= 8192) break
    }
    return HtmlText.detectDirection(sample.toString()) == 1
}

/** Zip-bomb guardrails for EPUB extraction (generous: real books are far smaller). */
private const val MAX_ZIP_ENTRIES = 10_000
private const val MAX_ZIP_BYTES = 500L * 1024 * 1024

/**
 * One row of the diagnostic dialog: a localized label on one side
 * and the raw diagnostic value on the other. Values are codes and
 * numbers, never prose, so they need no translation; labels are
 * all in strings.xml.
 */
@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

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
 * - Done/Enter commits, focus loss commits (LostFocus parity);
 * - invalid/empty input reverts to the current size;
 * - A+/A− presses update the box via [fontSize].
 *
 * The raw typed value reaches [onCommit] unclamped: the caller reports
 * an out-of-range value (10..72) to the user, and the ViewModel clamps
 * as a safety net. Clamping here would hide the limit hit again.
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
            onCommit(value)
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
