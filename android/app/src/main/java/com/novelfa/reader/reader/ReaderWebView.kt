package com.novelfa.reader.reader

import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView

/**
 * JS bridge for the adapted scroll reporter in [HtmlBuilder.build]
 * (`Android.onScroll(t,h,c)`).
 */
class ScrollBridge(private val onScroll: (Int, Int, Int) -> Unit) {

    @JavascriptInterface
    fun onScroll(t: Long, h: Long, c: Long) {
        onScroll(t.toInt(), h.toInt(), c.toInt())
    }
}

/** Port of ApplySpacingLive: replaces the #readerSpacing style element. */
fun WebView.updateSpacingJs(css: String) {
    evaluateJavascript(HtmlBuilder.spacingUpdateJs(css), null)
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
    onPageFinished: () -> Unit = {}
) {
    val loaded = remember { mutableStateOf<String?>(null) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Local file: subresources (chapter images/fonts) must load
                // under loadDataWithBaseURL; the app is fully offline.
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = true
                // Responsive scaling (pairs with the viewport meta tag) +
                // pinch zoom for accessibility; no on-screen zoom buttons.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.defaultTextEncodingName = "utf-8"
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
            if (loaded.value != html) {
                loaded.value = html
                wv.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        }
    )
}
