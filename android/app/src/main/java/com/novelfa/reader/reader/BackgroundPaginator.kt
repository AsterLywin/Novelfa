package com.novelfa.reader.reader

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.novelfa.reader.data.ReaderExtrasStore
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Exact book-wide page numbers from the first open (2026-11-23 bug
 * report: "the total page count changes every time I scroll").
 *
 * Root cause (see BookPageCount.kt / ReaderScreen.kt's
 * chapterPageCounts): a chapter's page/screen count is only known
 * once the LIVE reader actually lays it out, which only happens as
 * the user scrolls or turns pages into it. Until every chapter is
 * known, the book-wide total is ESTIMATED from a pages-per-byte
 * ratio over whatever chapters happen to be counted so far — and
 * that ratio shifts every time a newly-counted chapter turns out
 * denser or sparser than the ones counted before it (dialogue-heavy
 * vs. prose-heavy chapters paginate very differently per byte), so
 * the estimated total visibly jumps around while reading, even
 * though nothing about the book changed.
 *
 * The fix is to not estimate at all: silently paginate every
 * chapter, in a hidden WebView the user never sees, right after the
 * book opens — so by the time the estimate would have kicked in,
 * the real counts are either already there or arrive within a few
 * seconds, and the total only ever moves (if at all) while it is
 * still labelled "about".
 *
 * This measures BOTH book-wide metrics the live reader can show,
 * for the chapters it does not already have cached under the
 * current layout fingerprint:
 *  - the paged page count (ReaderWebView's PAGER_JS; see
 *    [ReaderViewModel.buildStandaloneChapterHtml] with paged=true),
 *    via `window.rdrPagerState()` / `window.rdrPageCount()`.
 *  - the vertical screens count (HtmlBuilder's self-reporting `r()`,
 *    `setInterval(r, 200)`; paged=false), via `window.__rdrScreens`.
 * Both JS paths are chapter-isolated already (see HtmlBuilder.kt):
 * neither depends on the book's continuous-scroll window, so one
 * chapter's standalone document, sized to the real target viewport,
 * reports the same number a visible reader would eventually report
 * for it. No JS changes were needed for this.
 *
 * One hidden WebView does the whole pass, chapter by chapter, for
 * BOTH modes before moving to the next chapter (so an early close
 * still leaves whichever chapters it finished fully measured for
 * both pills, instead of racing two separate passes). It is
 * attached to the real activity window (off-screen, translated out
 * of the visible area) rather than left unattached, because the
 * pager refuses to build below a 50x120 px stage (see
 * HtmlBuilder.kt's `Wpx < 50 || Hpx < 120` guard) and an unattached
 * WebView cannot be reliably measured/laid out to a real size on
 * every OS version. [cancel] tears it down; the caller MUST call it
 * when the reader closes or the book/layout changes, since nothing
 * else stops the pass or frees the WebView.
 */
class BackgroundPaginator(
    private val context: Context,
    private val vm: ReaderViewModel,
    private val bookId: String,
    private val extrasStore: ReaderExtrasStore
) {
    companion object {
        private const val TAG = "NovelfaBgPaginate"

        /** Matches HtmlBuilder's own settle budget, plus slack for the time-sliced build loop. */
        private const val PER_CHAPTER_TIMEOUT_MS = 6_000L

        /** Breathing room between chapters: this is a background courtesy job, not a race. */
        private const val BETWEEN_CHAPTERS_DELAY_MS = 80L
    }

    private var webView: WebView? = null
    private var host: FrameLayout? = null

    /** True once [cancel] has run; guards against using a torn-down WebView. */
    @Volatile private var cancelled = false

    /**
     * Runs the whole pass for the given viewport (CSS px, i.e.
     * already divided by density — the same w/h [ReaderScreen]'s
     * pageCountFingerprint() computes from the live WebView).
     * Suspends until every chapter is measured or [cancel] is
     * called; safe to launch in a coroutine tied to the reader
     * screen's own lifetime and let it run to completion or
     * cancellation in the background.
     */
    suspend fun run(viewportWCss: Int, viewportHCss: Int) {
        if (viewportWCss <= 0 || viewportHCss <= 0) return
        val pagedFp = vm.layoutSettingsKey() + "|${viewportWCss}x$viewportHCss"
        val verticalFp = pagedFp + "|vertical"
        val pagedDone = runCatching {
            extrasStore.chapterPageCounts(bookId, pagedFp).first()
        }.getOrDefault(emptyMap()).toMutableMap()
        val verticalDone = runCatching {
            extrasStore.chapterPageCounts(bookId, verticalFp).first()
        }.getOrDefault(emptyMap()).toMutableMap()

        val wv = ensureWebView(viewportWCss, viewportHCss) ?: return

        for (i in vm.chapters.indices) {
            if (cancelled) return
            val needPaged = !pagedDone.containsKey(i)
            val needVertical = !verticalDone.containsKey(i)
            if (!needPaged && !needVertical) continue

            if (needPaged) {
                val count = measureOne(wv, i, paged = true)
                if (cancelled) return
                if (count != null && count > 0) {
                    pagedDone[i] = count
                    runCatching { extrasStore.setChapterPageCount(bookId, pagedFp, i, count) }
                }
            }
            if (cancelled) return
            if (needVertical) {
                val screens = measureOne(wv, i, paged = false)
                if (cancelled) return
                if (screens != null && screens > 0) {
                    verticalDone[i] = screens
                    runCatching { extrasStore.setChapterPageCount(bookId, verticalFp, i, screens) }
                }
            }
            // Yield between chapters: this is a courtesy background
            // pass, not a rush to finish — a 2500+ page book can
            // take a while regardless, and there is no reason to
            // starve the main thread or the live reader's own WebView.
            delay(BETWEEN_CHAPTERS_DELAY_MS)
        }
        Log.d(TAG, "background pagination pass finished for $bookId")
    }

    /** Builds, loads and measures one chapter's standalone document; null on any failure/timeout. */
    private suspend fun measureOne(wv: WebView, chapter: Int, paged: Boolean): Int? {
        val (html, base) = runCatching {
            vm.buildStandaloneChapterHtml(chapter, paged)
        }.getOrNull() ?: return null

        return withTimeoutOrNull(PER_CHAPTER_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int?> { cont ->
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pollUntilReady(wv, paged, cont)
                    }
                }
                wv.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
            }
        }
    }

    /** Polls the JS side every 150ms until the chapter's count is final, or gives up on cancellation. */
    private fun pollUntilReady(
        wv: WebView,
        paged: Boolean,
        cont: CancellableContinuation<Int?>
    ) {
        if (cancelled || !cont.isActive) return
        val js = if (paged) {
            "(function(){try{return window.rdrPagerState?window.rdrPagerState():'';}catch(e){return '';}})();"
        } else {
            // Flow document: HtmlBuilder's own r()/setInterval(r,200) keeps
            // window.__rdrScreens[0] current for this doc's single chapter
            // (data-idx is always 0 here — buildStandaloneChapterHtml wraps
            // exactly one chapter per document, whatever its real book
            // index is) once content has settled and has real height.
            "(function(){try{var c=document.documentElement.clientHeight||document.body.clientHeight||0;" +
                "var h=document.body.scrollHeight||document.documentElement.scrollHeight||0;" +
                "if(c<=0||h<=0)return 'building';" +
                "return (window.__rdrScreens&&window.__rdrScreens[0]!=null)?'ready':'building';" +
                "}catch(e){return 'building';}})();"
        }
        wv.evaluateJavascript(js) { raw ->
            if (cancelled || !cont.isActive) return@evaluateJavascript
            val state = raw?.trim('"')
            when (state) {
                "ready" -> readCount(wv, paged, cont)
                "torn" -> cont.resume(null)
                else -> {
                    // Still building (or JS not ready yet) — try again shortly.
                    wv.postDelayed({ pollUntilReady(wv, paged, cont) }, 150L)
                }
            }
        }
    }

    private fun readCount(
        wv: WebView,
        paged: Boolean,
        cont: CancellableContinuation<Int?>
    ) {
        val js = if (paged) {
            "(function(){try{return window.rdrPageCount?window.rdrPageCount():0;}catch(e){return 0;}})();"
        } else {
            "(function(){try{return (window.__rdrScreens&&window.__rdrScreens[0])||0;}catch(e){return 0;}})();"
        }
        wv.evaluateJavascript(js) { raw ->
            val count = raw?.toIntOrNull() ?: 0
            if (cont.isActive) cont.resume(if (count > 0) count else null)
        }
    }

    /**
     * Creates the hidden WebView, attached to the activity's real
     * window (off-screen) so it measures/lays out to a genuine
     * non-zero size — see the class doc for why an unattached
     * WebView is not reliable here. Returns null if the context
     * is not (or no longer) backed by a live Activity (e.g. the
     * screen was already torn down), in which case the whole pass
     * is simply skipped rather than risking a half-attached view.
     */
    private fun ensureWebView(wCss: Int, hCss: Int): WebView? {
        webView?.let { return it }
        val activity = context.unwrapActivity() ?: return null
        val root = activity.window?.decorView as? ViewGroup ?: return null
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val wPx = (wCss * density).roundToInt().coerceAtLeast(50)
        val hPx = (hCss * density).roundToInt().coerceAtLeast(120)

        val frame = FrameLayout(activity).apply {
            // Off-screen, not zero-size and not View.GONE: both of
            // those would make the WebView measure to 0x0 and the
            // pager would refuse every build (Wpx<50||Hpx<120) and
            // sit retrying forever. A huge negative translation
            // keeps it fully outside the visible window while it
            // still gets a real layout pass at the target size.
            translationX = -100_000f
        }
        val wv = WebView(activity).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.defaultTextEncodingName = "utf-8"
            // No zoom, no selection UI, no link navigation needed —
            // this WebView is never shown and never touched.
            settings.setSupportZoom(false)
        }
        frame.addView(wv, FrameLayout.LayoutParams(wPx, hPx))
        // Attaching to the real decorView with exact LayoutParams is
        // enough: the window's own layout pass (triggered by addView)
        // measures/lays out both frame and wv to wPx x hPx before the
        // next frame, which is well before loadDataWithBaseURL's
        // onPageFinished can fire. No manual measure()/layout() call
        // needed (or safe to rely on) on a view already attached to
        // a live window — the next real pass would just override it.
        root.addView(frame, ViewGroup.LayoutParams(wPx, hPx))
        host = frame
        webView = wv
        return wv
    }

    /** Stops the pass and frees the hidden WebView; safe to call more than once. */
    fun cancel() {
        if (cancelled) return
        cancelled = true
        val wv = webView
        webView = null
        val frame = host
        host = null
        wv?.let {
            it.stopLoading()
            it.webViewClient = WebViewClient()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        frame?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }
}

private fun Context.unwrapActivity(): Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}
