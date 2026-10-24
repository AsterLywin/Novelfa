package com.novelfa.reader.reader

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
    private val onPageEdge: (Int) -> Unit = {}
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
    onPageFinished: () -> Unit = {},
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
    // Paged (horizontal) mode: the page turn is fully programmatic
    // (rdrTurn); the fling must be consumed so the WebView doesn't also
    // fling-scroll natively and fight the turn animation.
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
                val swipeDetector = GestureDetector(ctx,
                    object : GestureDetector.SimpleOnGestureListener() {
                        // REQUIRED: SimpleOnGestureListener.onDown returns
                        // false by default, and a false onDown makes the
                        // detector drop the whole gesture — onScroll and
                        // onFling never fire. That is why swipe/drag page
                        // turns silently did nothing at all (the buttons
                        // used to hide the fact that swipes never worked).
                        override fun onDown(e: MotionEvent): Boolean = true

                        override fun onFling(
                            e1: MotionEvent?, e2: MotionEvent,
                            velocityX: Float, velocityY: Float
                        ): Boolean {
                            if (e1 == null || turnedThisGesture) return false
                            val dx = e2.x - e1.x
                            val dy = e2.y - e1.y
                            if (abs(dx) > abs(dy) * 1.5f &&
                                abs(dx) > 80f && abs(velocityX) > 400f
                            ) {
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
                                if (dx < 0) onSwipeLeft() else onSwipeRight()
                                turnedThisGesture = true
                                return true
                            }
                            return false
                        }
                    })
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) turnedThisGesture = false
                    val handled = swipeDetector.onTouchEvent(event)
                    // In paged mode the turn is fully programmatic; consume
                    // the fling so the WebView can't fling-scroll natively
                    // and fight rdrTurn's animation.
                    handled && pagedNow.value
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
                wv.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
        // A WebView holds its Activity and native renderer resources: destroy
        // it when the composable leaves the composition instead of leaking it.
        onRelease = { wv -> wv.destroy() }
    )
}
