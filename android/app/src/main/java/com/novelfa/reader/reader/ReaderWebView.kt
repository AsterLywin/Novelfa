package com.novelfa.reader.reader

import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.viewinterop.AndroidView

/**
 * JS bridge for the reader documents: the scroll reporter
 * (`Android.onScroll(t,h,c,gen)`), the visible-chapter reporter
 * (`Android.onChapter(ci,gen)` — the chapter at the viewport top), and
 * in-book link taps handed over by the LINK_JS interceptor in
 * [HtmlBuilder.build] (`Android.onLink(href, chapIdx)` with the raw
 * authored href and the index of the chapter containing the link).
 */
class ScrollBridge(
    private val onScroll: (Int, Int, Int, Int) -> Unit,
    private val onLinkClick: (String, Int) -> Unit = { _, _ -> },
    private val onChapterVisible: (Int, Int) -> Unit = { _, _ -> },
    /** Paged-mode tap turn at a chapter edge: +1 = last page, -1 = first page. */
    private val onPageEdge: (Int) -> Unit = {},
    /** Paged-mode turn failed: pager torn down or missing (see recoverPagerTurn). */
    private val onPagerStuck: () -> Unit = {},
    /** Pager teardown reason from JS (see PAGER_JS teardown): surfaced to logcat. */
    private val onPagerError: (String) -> Unit = {}
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
    fun onChapter(ci: Long, gen: Long) {
        onChapterVisible(ci.toInt(), gen.toInt())
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
    // Paged (horizontal) mode: the page turn is fully programmatic
    // (rdrTurn); the fling must be consumed so the WebView doesn't also
    // fling-scroll natively and fight the turn animation.
    // The caller passes false here once the live document's pager has
    // torn down (see ReaderScreen): the torn document is a scrolling
    // flow page, so turn gestures and paged WebView settings are
    // switched off for it and it scrolls (and taps links) natively.
    paged: Boolean = false
) {
    val loaded = remember { mutableStateOf<String?>(null) }
    // The WebView factory runs once; the mode can change afterwards.
    val pagedNow = rememberUpdatedState(paged)
    // Tracks the mode the WebView settings below were last applied for.
    // These settings only take full effect on page load / when changed,
    // so they must be refreshed whenever the mode flips (mode switches
    // rebuild the chapter HTML, but this guards the case where they
    // wouldn't).
    val lastPagedSettings = remember { mutableStateOf<Boolean?>(null) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
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
                            if (e1 == null || turnedThisGesture || !pagedNow.value) return false
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
                            if (e1 == null || turnedThisGesture || !pagedNow.value) return false
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
                         * Native paged-mode tap. Fires only in paged mode;
                         * in vertical mode it returns false so the WebView
                         * keeps its own tap/click handling (and LINK_JS).
                         * In paged mode this consumes ACTION_UP, so the
                         * WebView never fires its own document click for
                         * the tap — this is the tap path. (ACTION_DOWN is
                         * deliberately given to the WebView too, so its
                         * tall-page scrolling can start; see the listener
                         * below. That does not re-enable JS tap zones,
                         * because UP is still consumed here.)
                         * Link safety: the tap point is hit-tested in JS
                         * first (CSS coords = view px / density, paged
                         * scale is 1). A link tap gets a synthetic
                         * click on the hit element, which LINK_JS
                         * intercepts exactly like a real tap; a non-link
                         * tap on an outer quarter turns, a middle tap
                         * does nothing (same zones as the JS tap code).
                         */
                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            if (!pagedNow.value) return false
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
                            host.evaluateJavascript(
                                "(function(){var el=document.elementFromPoint($cssX,$cssY);" +
                                    "if(!el) return 'none';" +
                                    "var a=el.closest?el.closest('a[href]'):null;" +
                                    "return a?'link':'notlink';})();"
                            ) { result ->
                                val hit = result?.trim()?.trim { it == '"' } ?: ""
                                Log.d("NovelfaTouch", "tap hit=$hit zone=$zone")
                                if (hit == "link") {
                                    // Preserve the link: dispatch a click
                                    // on the hit element; LINK_JS (capture
                                    // phase) owns it from here.
                                    host.evaluateJavascript(
                                        "(function(){var el=document.elementFromPoint($cssX,$cssY);" +
                                            "if(el) el.click(); return 'ok';})();",
                                        null
                                    )
                                } else if (zone == "left") {
                                    onTapLeftEdge()
                                } else if (zone == "right") {
                                    onTapRightEdge()
                                }
                                // Middle non-link taps are inert by design.
                            }
                            return true
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
