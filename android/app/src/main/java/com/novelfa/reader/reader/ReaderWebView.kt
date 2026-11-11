package com.novelfa.reader.reader

import android.content.Context
import android.util.Log
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.novelfa.reader.R
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * JS bridge for the reader documents: the scroll reporter
 * (`Android.onScroll(t,h,c,gen)`), the visible-chapter reporter
 * (`Android.onChapter(ci,gen)` — the chapter at the viewport top),
 * the in-section position reporter (`Android.onSectionPos(ci,frac,gen)`
 * — the fraction of the top chapter's height above the viewport top,
 * see SectionPosition.kt), the vertical chapter-screens reporter
 * (`Android.onChapterScreens(ci,screens,gen)` — the chapter's height
 * in screens at the current viewport, for the vertical pill), and
 * in-book link taps handed over by the LINK_JS interceptor in
 * [HtmlBuilder.build] (`Android.onLink(href, chapIdx)` with the raw
 * authored href and the index of the chapter containing the link),
 * and footnote taps in popup mode (`Android.onFootnoteLink(href,
 * chapIdx, label)` — a marked footnote reference, with the link's
 * own text as the note number; the app resolves the note and shows
 * it in a popup on the same page instead of navigating).
 */
class ScrollBridge(
    private val onScroll: (Int, Int, Int, Int) -> Unit,
    private val onLinkClick: (String, Int) -> Unit = { _, _ -> },
    /**
     * Footnote tap in popup mode (LINK_JS): raw href + containing
     * chapter + link text + tap point in CSS px (WebView viewport,
     * for the Moon+ Reader anchored popup). A real tap carries its
     * own coordinates; the paged mode's synthetic click has none,
     * so LINK_JS falls back to window.__rdrLastTapX/Y, set by the
     * native tap handler below before dispatching that click.
     */
    private val onFootnoteClick: (String, Int, String, Float, Float) -> Unit = { _, _, _, _, _ -> },
    /** Image-save tap (IMAGE_JS): raw <img> src + containing chapter index. */
    private val onImageClick: (String, Int) -> Unit = { _, _ -> },
    private val onChapterVisible: (Int, Int) -> Unit = { _, _ -> },
    /**
     * In-section position report (see SectionPosition.kt): the chapter
     * at the viewport top + the fraction of its height above the
     * viewport top, throttled in JS (1% of the chapter or 1000ms).
     * Vertical mode persists it; paged mode ignores it (its own
     * paged anchor owns position there).
     */
    private val onSectionPos: (Int, Float, Int) -> Unit = { _, _, _ -> },
    /**
     * Vertical chapter screens (see the scroll reporter in
     * [HtmlBuilder.build]): the chapter index + its height in
     * screens at the current viewport, reported when it changes.
     * Vertical mode records it into the per-layout chapter-count
     * map, so the vertical pill can show book-wide numbers like
     * the paged pill. Paged documents never report it (their
     * .rdr-chap is the fixed stage, always 1 screen).
     */
    private val onChapterScreens: (Int, Int, Int) -> Unit = { _, _, _ -> },
    /** Paged-mode tap turn at a chapter edge: +1 = last page, -1 = first page. */
    private val onPageEdge: (Int) -> Unit = {},
    /** Paged-mode turn failed: pager torn down or missing (see recoverPagerTurn). */
    private val onPagerStuck: () -> Unit = {},
    /** Pager teardown reason from JS (see PAGER_JS teardown): surfaced to logcat. */
    private val onPagerError: (String) -> Unit = {},
    /**
     * Pager page report (1-based page, total pages), fired by the
     * pager's show() — the ONLY source of page-pill numbers. It
     * replaces the old overloading of onScroll for pager geometry:
     * onScroll carries real scroll geometry, and a torn-down pager
     * restores a scrolling flow document whose scroll geometry
     * (screens tall, screen at top) then masqueraded as page
     * numbers in the pill. A flow document has no pager, so it can
     * never fire onPagerReport, and the pill can never show scroll
     * screens as pages again.
     */
    private val onPagerReport: (Int, Int) -> Unit = { _, _ -> },
    /** Live text selection state (SELECT_JS): true while the user has text selected. */
    private val onSelection: (Boolean) -> Unit = {},
    /** The highlight the live selection overlaps (SELECT_JS): its data-hl id, or "" when none. */
    private val onSelectionHighlight: (String) -> Unit = {},
    /** Tap on a highlight mark (SELECT_JS / paged hit test): the mark's data-hl id ("chap:start-end"). */
    private val onHighlightTap: (String) -> Unit = {}
) {

    @JavascriptInterface
    fun onScroll(t: Long, h: Long, c: Long, gen: Long) {
        onScroll(t.toInt(), h.toInt(), c.toInt(), gen.toInt())
    }

    @JavascriptInterface
    fun onLink(href: String, chap: Long) {
        onLinkClick(href, chap.toInt())
    }

    @JavascriptInterface
    fun onFootnoteLink(href: String, chap: Long, label: String, x: Double, y: Double) {
        onFootnoteClick(href, chap.toInt(), label, x.toFloat(), y.toFloat())
    }

    @JavascriptInterface
    fun onImageClick(src: String, chap: Long) {
        onImageClick(src, chap.toInt())
    }

    @JavascriptInterface
    fun onChapter(ci: Long, gen: Long) {
        onChapterVisible(ci.toInt(), gen.toInt())
    }

    @JavascriptInterface
    fun onSectionPos(ci: Long, frac: Double, gen: Long) {
        onSectionPos(ci.toInt(), frac.toFloat(), gen.toInt())
    }

    @JavascriptInterface
    fun onChapterScreens(ci: Long, screens: Long, gen: Long) {
        onChapterScreens(ci.toInt(), screens.toInt(), gen.toInt())
    }

    @JavascriptInterface
    fun onPageEdge(dir: Long) {
        onPageEdge(dir.toInt())
    }

    @JavascriptInterface
    fun onPagerStuck() {
        onPagerStuck()
    }

    @JavascriptInterface
    fun onPagerError(msg: String) {
        onPagerError(msg)
    }

    @JavascriptInterface
    fun onPagerReport(page: Long, total: Long) {
        onPagerReport(page.toInt(), total.toInt())
    }

    @JavascriptInterface
    fun onSelection(active: Long) {
        onSelection(active != 0L)
    }

    @JavascriptInterface
    fun onSelectionHighlight(id: String) {
        onSelectionHighlight(id)
    }

    @JavascriptInterface
    fun onHighlightTap(id: String) {
        onHighlightTap(id)
    }
}

/** Port of ApplySpacingLive: replaces the #readerSpacing style element. */
fun WebView.updateSpacingJs(css: String) {
    evaluateJavascript(HtmlBuilder.spacingUpdateJs(css), null)
}

/** Paged mode: replaces the #rdrPaged style element, then repaginates. */
fun WebView.updatePagedStyleJs(css: String) {
    evaluateJavascript(HtmlBuilder.pagedStyleUpdateJs(css), null)
}

/** Paged mode: jump to the page holding the element with id [frag]. */
fun WebView.goToAnchorPaged(frag: String) {
    evaluateJavascript(HtmlBuilder.anchorJumpJs(frag), null)
}

/** Vertical mode: scroll the element with id [frag] into view. */
fun WebView.scrollToAnchor(frag: String) {
    evaluateJavascript(HtmlBuilder.anchorScrollJs(frag), null)
}

/**
 * Selection-menu actions the floating text-selection ActionMode
 * gains beside the system's Copy — ReadEra's selection popup
 * model (Copy, Quote, Translate, …): Novelfa adds the two its
 * menu lacks. Copy is the system's own item and stays as is.
 */
enum class SelectionMenuAction { QUOTE, TRANSLATE }

/**
 * The reader's WebView: its floating text-selection ActionMode
 * gains Quote and Translate items (added in onCreate/onPrepare
 * AFTER the system's items, so they land after Copy). The mode
 * is NOT finished here — the native handler resolves the live
 * selection first (evaluateJavascript is async, and finishing
 * the mode would clear the selection before the JS runs) and
 * clears the selection itself afterwards, which ends the mode.
 */
class SelectionWebView(context: Context) : WebView(context) {
    var onSelectionAction: ((SelectionMenuAction) -> Unit)? = null

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        if (type != ActionMode.TYPE_FLOATING) return super.startActionMode(callback, type)
        return super.startActionMode(
            SelectionActionModeCallback(callback, onSelectionAction), type
        )
    }
}

private class SelectionActionModeCallback(
    private val original: ActionMode.Callback,
    private val onAction: ((SelectionMenuAction) -> Unit)?
) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val created = original.onCreateActionMode(mode, menu)
        addItems(menu)
        return created
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val prepared = original.onPrepareActionMode(mode, menu)
        addItems(menu)
        return prepared
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_QUOTE -> {
                onAction?.invoke(SelectionMenuAction.QUOTE)
                true
            }
            MENU_TRANSLATE -> {
                onAction?.invoke(SelectionMenuAction.TRANSLATE)
                true
            }
            else -> original.onActionItemClicked(mode, item)
        }
    }

    override fun onDestroyActionMode(mode: ActionMode) = original.onDestroyActionMode(mode)

    private fun addItems(menu: Menu) {
        if (menu.findItem(MENU_QUOTE) == null) {
            menu.add(Menu.NONE, MENU_QUOTE, Menu.NONE, R.string.selection_quote)
        }
        if (menu.findItem(MENU_TRANSLATE) == null) {
            menu.add(Menu.NONE, MENU_TRANSLATE, Menu.NONE, R.string.selection_translate)
        }
    }

    companion object {
        private const val MENU_QUOTE = 1001
        private const val MENU_TRANSLATE = 1002
    }
}

/**
 * WebView owner. JS enabled, no zoom buttons, base URL = chapter folder URI
 * so relative images resolve. Reloads only when [html] changes; continuous
 * appends/prepends go through evaluateJavascript so scroll position is kept.
 */
@Composable
fun ReaderWebView(
    html: String,
    baseUrl: String?,
    bridge: ScrollBridge,
    onError: (String) -> Unit,
    onWebView: (WebView) -> Unit = {},
    onPageStarted: () -> Unit = {},
    onPageFinished: () -> Unit = {},
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
    // Paged-mode native tap edges (language-agnostic: left/right quarter
    // of the view). The caller maps them to forward/back from the book
    // language, mirroring the JS tap zones. Native taps are PRIMARY in
    // paged mode because native taps own ACTION_UP (the touch listener
    // below consumes UP for a handled tap, so the WebView never fires
    // its own document click for it) — so the JS tap zones in PAGER_JS
    // never fire on device. They stay as a fallback for environments
    // without this native listener (e.g. headless tests). ACTION_DOWN
    // itself is deliberately NOT consumed (see the listener): the
    // WebView needs it to start its own scroll sequence for tall pages.
    onTapLeftEdge: () -> Unit = {},
    onTapRightEdge: () -> Unit = {},
    // Middle tap (both modes): toggles fullscreen. The caller flips
    // its fullscreen state; links, images and highlights keep owning
    // their tap (hit-tested first below), and a live text selection
    // keeps owning the gesture, so a middle tap never steals a link
    // or clears a selection. Native-viewer parity: the comic viewer
    // hides/shows its bars on a page tap the same way.
    onTapMiddle: () -> Unit = {},
    // Paged (horizontal) mode: the page turn is fully programmatic
    // (rdrTurn); the fling must be consumed so the WebView doesn't also
    // fling-scroll natively and fight the turn animation.
    // The caller passes false here once the live document's pager has
    // torn down (see ReaderScreen): the torn document is a scrolling
    // flow page, so turn gestures and paged WebView settings are
    // switched off for it and it scrolls (and taps links) natively.
    paged: Boolean = false,
    // Paged-mode image tap: raw <img> src at the tap point. Needed
    // because native taps own ACTION_UP in paged mode, so the JS
    // image listener (IMAGE_JS) never fires there — this hit test is
    // the paged-mode equivalent, and it feeds the same save dialog.
    onImageTap: (String) -> Unit = {},
    // Highlight-mark tap (paged native hit test; vertical mode
    // reports it through SELECT_JS's click listener instead).
    onHighlightTap: (String) -> Unit = {},
    // Floating selection-menu action (Quote / Translate): the
    // WebView's own ActionMode gains the items beside Copy (see
    // SelectionWebView); the caller resolves the live selection.
    onSelectionAction: (SelectionMenuAction) -> Unit = {},
    // True while the user has text selected (SELECT_JS reports it):
    // page-turn gestures must not fire then, or a drag would turn
    // the page instead of extending the selection, and a tap would
    // cancel the selection instead of clearing it natively.
    selectionActive: Boolean = false
) {
    val loaded = remember { mutableStateOf<String?>(null) }
    // The WebView factory runs once; the mode can change afterwards.
    val pagedNow = rememberUpdatedState(paged)
    val onImageTapNow = rememberUpdatedState(onImageTap)
    val onHighlightTapNow = rememberUpdatedState(onHighlightTap)
    val onSelectionActionNow = rememberUpdatedState(onSelectionAction)
    val onTapMiddleNow = rememberUpdatedState(onTapMiddle)
    val selectionActiveNow = rememberUpdatedState(selectionActive)
    // Tracks the mode the WebView settings below were last applied for.
    // These settings only take full effect on page load / when changed,
    // so they must be refreshed whenever the mode flips (mode switches
    // rebuild the chapter HTML, but this guards the case where they
    // wouldn't).
    val lastPagedSettings = remember { mutableStateOf<Boolean?>(null) }
    // fillMaxSize is load-bearing, not cosmetic: without a modifier
    // AndroidView sizes the WebView to its CONTENT height. In paged
    // mode boot hides all chapter content (display:none source, empty
    // strip), so the content height collapses to 0, the viewport
    // becomes 393x0, the pager measures a zero stage, refuses every
    // build, and tears down to flow — which restores visible content,
    // grows the WebView back to full height, and looks exactly like
    // "horizontal mode showing the whole page like vertical mode".
    // A fixed full-size WebView breaks that loop: the viewport stays
    // real whatever the document hides inside it.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SelectionWebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                // No functional need for DOM storage in the reader; leaving it
                // on would give every book's pages a shared persistent
                // localStorage.
                settings.domStorageEnabled = false
                // Local file: subresources (chapter images/fonts) must load
                // under loadDataWithBaseURL; the app is fully offline.
                // NOTE: allowFileAccessFromFileURLs / allowUniversalAccessFromFileURLs
                // are deliberately OFF (they were on before): they let JS from a
                // file:// page read ANY file:// URL, so a malicious EPUB's script
                // could read app-private files. allowFileAccess + the chapter-dir
                // base URL is all the reader needs.
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                // Responsive scaling (pairs with the viewport meta tag) +
                // pinch zoom for accessibility; no on-screen zoom buttons.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.defaultTextEncodingName = "utf-8"
                // Horizontal fling -> previous/next page. Observed only, never
                // consumed, so vertical scrolling and pinch zoom keep working.
                // Plus drag-to-turn (paged mode only): a slow horizontal
                // drag that passes 140px also turns once per gesture, so
                // paging does not depend on fling velocity alone. The
                // flag stops a drag-turn and its release fling from
                // turning twice, and resets on every new touch-down.
                var turnedThisGesture = false
                // Diagnostic tag: filter logcat with
                // `adb logcat -s NovelfaTouch NovelfaPager` to see which
                // gate a dead drag/tap dies at. Logs are per-gesture
                // (DOWN, fling, threshold-cross, tap), never per-MOVE,
                // so they do not spam on ordinary scrolling.
                val host = this
                val swipeDetector = GestureDetector(ctx,
                    object : GestureDetector.SimpleOnGestureListener() {
                        // REQUIRED: SimpleOnGestureListener.onDown returns
                        // false by default, and a false onDown makes the
                        // detector drop the whole gesture — onScroll and
                        // onFling never fire. That is why swipe/drag page
                        // turns silently did nothing at all (the buttons
                        // used to hide the fact that swipes never worked).
                        override fun onDown(e: MotionEvent): Boolean {
                            Log.d("NovelfaTouch", "down paged=${pagedNow.value} w=${host.width}")
                            return true
                        }

                        override fun onFling(
                            e1: MotionEvent?, e2: MotionEvent,
                            velocityX: Float, velocityY: Float
                        ): Boolean {
                            // No paged gate here before: a fling in
                            // vertical mode — or in a torn-down paged
                            // document that must scroll natively now —
                            // still fired a turn callback, double-acting
                            // with the WebView's own fling-scroll.
                            if (e1 == null || turnedThisGesture || !pagedNow.value || selectionActiveNow.value) return false
                            val dx = e2.x - e1.x
                            val dy = e2.y - e1.y
                            val qualified = abs(dx) > abs(dy) * 1.5f &&
                                abs(dx) > 80f && abs(velocityX) > 400f
                            Log.d(
                                "NovelfaTouch",
                                "fling dx=$dx dy=$dy vx=$velocityX qualified=$qualified paged=${pagedNow.value}"
                            )
                            if (qualified) {
                                if (dx < 0) onSwipeLeft() else onSwipeRight()
                                turnedThisGesture = true
                                return true
                            }
                            return false
                        }

                        override fun onScroll(
                            e1: MotionEvent?, e2: MotionEvent,
                            distanceX: Float, distanceY: Float
                        ): Boolean {
                            if (e1 == null || turnedThisGesture || !pagedNow.value || selectionActiveNow.value) return false
                            val dx = e2.x - e1.x
                            val dy = e2.y - e1.y
                            if (abs(dx) > abs(dy) * 1.5f && abs(dx) > 140f) {
                                Log.d("NovelfaTouch", "drag-turn dx=$dx dy=$dy")
                                if (dx < 0) onSwipeLeft() else onSwipeRight()
                                turnedThisGesture = true
                                return true
                            }
                            return false
                        }

                        /**
                         * Native tap. In paged mode this consumes
                         * ACTION_UP, so the WebView never fires its own
                         * document click for the tap — this is the tap
                         * path. (ACTION_DOWN is deliberately given to
                         * the WebView too, so its tall-page scrolling
                         * can start; see the listener below. That does
                         * not re-enable JS tap zones, because UP is
                         * still consumed here.) In vertical mode it
                         * returns false, so the WebView keeps its own
                         * tap/click handling (and LINK_JS) — the hit
                         * test below only reads the tap point, and only
                         * a middle tap on plain text gains anything
                         * (the fullscreen toggle).
                         * Link safety: the tap point is hit-tested in JS
                         * first (CSS coords = view px / density, paged
                         * scale is 1). In paged mode a link tap gets a
                         * synthetic click on the hit element, which
                         * LINK_JS intercepts exactly like a real tap; a
                         * non-link tap on an outer quarter turns, a
                         * middle tap toggles fullscreen (same zones as
                         * the JS tap code, except the middle now acts).
                         * In vertical mode links, images and highlights
                         * stay native — only a middle tap that hit none
                         * of them toggles fullscreen.
                         */
                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            // A live text selection owns the gesture:
                            // return false so the WebView handles the
                            // tap natively (clearing the selection)
                            // instead of turning the page and sending
                            // the CANCEL that would kill the selection.
                            if (selectionActiveNow.value) return false
                            val paged = pagedNow.value
                            val w = host.width
                            if (w <= 0) {
                                Log.d("NovelfaTouch", "tap ignored w=0")
                                return false
                            }
                            val zone = when {
                                e.x < w * 0.25f -> "left"
                                e.x > w * 0.75f -> "right"
                                else -> "middle"
                            }
                            val density = host.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
                            val cssX = (e.x / density).toInt()
                            val cssY = (e.y / density).toInt()
                            Log.d("NovelfaTouch", "tap x=${e.x} w=$w zone=$zone css=$cssX,$cssY")
                            // The listener deliberately does NOT consume
                            // ACTION_DOWN (tall-page scrolling needs
                            // it), and a paged tap consumes ACTION_UP —
                            // so without a cancel the WebView is left
                            // with a DOWN and no UP, and its long-press
                            // timer turns that dangling gesture into a
                            // text SELECTION on the page the turn just
                            // revealed (same coordinates, new content).
                            // A synthetic CANCEL, fed straight to the
                            // WebView's own handler (bypassing this
                            // listener, so no recursion), ends its
                            // gesture cleanly; the turn itself is
                            // programmatic and unaffected. Swipes never
                            // showed this because their UP reaches the
                            // WebView normally. Vertical mode skips the
                            // CANCEL: its UP is not consumed (the tap
                            // returns false below), so the WebView's
                            // gesture ends normally on its own.
                            if (paged) {
                                val cancel = MotionEvent.obtain(
                                    e.downTime, e.eventTime,
                                    MotionEvent.ACTION_CANCEL, e.x, e.y, 0
                                )
                                host.onTouchEvent(cancel)
                                cancel.recycle()
                            }
                            host.evaluateJavascript(
                                "(function(){var el=document.elementFromPoint($cssX,$cssY);" +
                                    "if(!el) return 'none';" +
                                    // Images own their tap, like in
                                    // IMAGE_JS: an image tap opens the
                                    // save dialog and never turns the
                                    // page, even on an outer quarter.
                                    // The backdrop clone on image pages
                                    // has pointer-events:none, so the
                                    // hit element here is the real img.
                                    "var img=(el.tagName==='IMG')?el:(el.closest?el.closest('img'):null);" +
                                    "if(img) return 'image:'+((img.getAttribute('src')||img.src||''));" +
                                    "var hl=(el.closest?el.closest('mark.rdr-hl'):null);" +
                                    "if(hl) return 'hl:'+(hl.getAttribute('data-hl')||'');" +
                                    "var a=el.closest?el.closest('a[href]'):null;" +
                                    "return a?'link':'notlink';})();"
                            ) { result ->
                                val hit = result?.trim()?.trim { it == '"' } ?: ""
                                Log.d("NovelfaTouch", "tap hit=$hit zone=$zone paged=$paged")
                                if (!paged) {
                                    // Vertical mode: the WebView handles
                                    // links, images and highlights
                                    // natively (this tap was not
                                    // consumed), so only a middle tap
                                    // that hit none of them toggles
                                    // fullscreen. Left/right taps stay
                                    // inert — there is no page to turn
                                    // to in a scrolling document.
                                    val ownsTap = hit == "link" ||
                                        hit.startsWith("image:") ||
                                        hit.startsWith("hl:")
                                    if (zone == "middle" && !ownsTap) {
                                        onTapMiddleNow.value()
                                    }
                                } else if (hit.startsWith("image:")) {
                                    val src = hit.removePrefix("image:")
                                    if (src.isNotEmpty()) onImageTapNow.value(src)
                                } else if (hit.startsWith("hl:")) {
                                    val id = hit.removePrefix("hl:")
                                    if (id.isNotEmpty()) onHighlightTapNow.value(id)
                                } else if (hit == "link") {
                                    // Preserve the link: dispatch a click
                                    // on the hit element; LINK_JS (capture
                                    // phase) owns it from here. The
                                    // synthetic click carries no
                                    // coordinates (clientX/Y are 0), so
                                    // the tap point is stashed first for
                                    // LINK_JS to read as
                                    // window.__rdrLastTapX/Y — the
                                    // footnote popup anchors to it.
                                    host.evaluateJavascript(
                                        "(function(){var el=document.elementFromPoint($cssX,$cssY);" +
                                            "if(el){window.__rdrLastTapX=$cssX;window.__rdrLastTapY=$cssY;el.click();} return 'ok';})();",
                                        null
                                    )
                                } else if (zone == "left") {
                                    onTapLeftEdge()
                                } else if (zone == "right") {
                                    onTapRightEdge()
                                } else if (zone == "middle") {
                                    onTapMiddleNow.value()
                                }
                            }
                            // Paged taps are consumed (the WebView's own
                            // click must not fire); vertical taps are
                            // not — the WebView handles them natively.
                            return paged
                        }
                    })
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        turnedThisGesture = false
                        // NEVER consume ACTION_DOWN, even in paged mode.
                        // The detector still sees DOWN (so tap/drag/fling
                        // turns keep working), but the WebView must ALSO
                        // get DOWN to own the touch sequence: without it
                        // its scrolling never starts, so a tall page's
                        // internal .rdr-tallwrap scroll — the only way to
                        // read the rest of an oversize page — was dead,
                        // and so was every other in-document scroll.
                        // Consuming only the events the detector actually
                        // handles (a qualified drag/fling turn, a tap
                        // turn on UP) keeps turns programmatic without
                        // breaking the WebView's own scrolling.
                        swipeDetector.onTouchEvent(event)
                        false
                    } else {
                        val handled = swipeDetector.onTouchEvent(event)
                        // In paged mode the turn is fully programmatic; consume
                        // the fling so the WebView can't fling-scroll natively
                        // and fight rdrTurn's animation.
                        handled && pagedNow.value
                    }
                }
                addJavascriptInterface(bridge, "Android")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onPageFinished()
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (request.isForMainFrame) {
                            onError(error.description?.toString() ?: "")
                        } else {
                            android.util.Log.w(
                                "ReaderWebView",
                                "subresource failed: " + request.url +
                                    " code=" + error.errorCode
                            )
                        }
                    }
                }
                onWebView(this)
            }
        },
        update = { wv ->
            onWebView(wv)
            // The factory runs once; the selection-menu callback
            // must stay the current one (it closes over fresh
            // Compose state on every recomposition).
            (wv as? SelectionWebView)?.onSelectionAction = { onSelectionActionNow.value(it) }
            if (lastPagedSettings.value != paged) {
                // Paged mode lays the chapter out as an N×100vw multicol
                // strip. Overview mode would zoom out to fit ALL pages side
                // by side (a row of tiny pages) instead of showing one full
                // page per screen — keep it off in paged mode so each 100vw
                // column fills the viewport at scale 1. (The paged viewport
                // meta also locks maximum-scale=1 as a second barrier.)
                wv.settings.loadWithOverviewMode = !paged
                // Pinch-zoom is fundamentally incompatible with the 100vw
                // pagination math (rdrTurn scrolls by CSS-px pages): a
                // zoomed page turns wrong and looks broken. Disable it in
                // paged mode (font size stays adjustable via A+/A- and the
                // numeric box).
                wv.settings.setSupportZoom(!paged)
                lastPagedSettings.value = paged
                // NOTE: no wv.reload() here — it races with the
                // loadDataWithBaseURL below and can cancel an in-flight
                // chapter load. The settings above apply to the next load,
                // and mode switches always rebuild the chapter HTML (which
                // reloads anyway).
            }
            if (loaded.value != html) {
                loaded.value = html
                onPageStarted()
                wv.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
        // A WebView holds its Activity and native renderer resources: destroy
        // it when the composable leaves the composition instead of leaking it.
        onRelease = { wv -> wv.destroy() }
    )
}
