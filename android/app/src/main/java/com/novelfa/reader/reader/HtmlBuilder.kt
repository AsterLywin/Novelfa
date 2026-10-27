package com.novelfa.reader.reader

import com.novelfa.reader.theme.ReaderTheme
import java.io.File
import java.util.Base64
import java.util.Locale

/**
 * Pure-JVM port of MainWindow.xaml.cs BuildStyledHtml (672-705),
 * BuildSpacingCss (1605-1619), ApplySpacingLive JS (1621-1629),
 * BuildFontFaceCss/AppendFontFace (2527-2590, base64 @font-face part),
 * and the Append/Prepend insertAdjacentHTML scripts (2062-2149).
 *
 * Dropped per spec (no keyboard on mobile): the shortcut-key script and the
 * `chrome.webview.postMessage` bridge. The scroll reporter is kept, adapted
 * to `Android.onScroll(t,h,c,gen)` via a JavascriptInterface, including the
 * lr-restore `z()` used after prepend. Horizontal page turns in paged mode
 * are driven by the rdrPager script (PAGER_JS at the bottom of this file):
 * `window.rdrTurn(d)` / `window.rdrLast()`.
 */
object HtmlBuilder {

    /**
     * Family name of the bundled fallback face. The reader font stack
     * ends with this face (see [build]) so text NEVER resolves to the
     * phone's own system font: a user-installed phone font must not be
     * able to re-shape the reader's layout or glyphs. The face is fed
     * by the bundled Vazirmatn files (the same files the app UI font
     * comes from); when they are absent it simply resolves to nothing
     * and the old system-sans-serif fallback applies.
     */
    const val FALLBACK_FAMILY = "NovelfaFallback"

    fun cssQuote(s: String): String =
        (s).replace("\\", "\\\\").replace("'", "\\'")

    fun build(
        baseUrl: String,
        bodyContent: String,
        theme: ReaderTheme,
        fontFamily: String,
        fontSize: Int,
        lineHeight: Double,
        sideMargin: Int,
        paragraphSpacing: Double,
        isEnglish: Boolean,
        fontFaceCss: String,
        docGen: Int,
        paged: Boolean = false
    ): String {
        val sb = StringBuilder()
        val themeAttr = if (theme.isDark) " data-theme=\"dark\"" else " data-theme=\"light\""
        sb.append("<!DOCTYPE html><html")
        if (paged) {
            // Horizontal paging: CSS multicol lays the chapter out as
            // 100vw pages; the flow direction follows the book language
            // (RTL books paginate right-to-left, LTR left-to-right).
            sb.append(" class=\"rdr-paged\" dir=\"")
                .append(if (isEnglish) "ltr" else "rtl").append("\"")
        }
        sb.append(themeAttr).append("><head><meta charset=\"utf-8\">")
        if (paged) {
            // Paged mode MUST render at scale 1: the 100vw pagination math
            // (rdrTurn) breaks if the WebView zooms out to fit the whole
            // N-page strip or the user pinch-zooms. maximum-scale=1 +
            // user-scalable=no locks the scale; font size stays adjustable
            // via the A+/A-/numeric controls.
            sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">")
        } else {
            sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        }
        sb.append("<base href=\"")
            .append(baseUrl).append("\">")
        sb.append("<style>html,body{margin:0;padding:0;background-color:")
            .append(theme.bg).append(";scrollbar-width:none;overflow-anchor:none;}")
        sb.append("html::-webkit-scrollbar,body::-webkit-scrollbar{width:0;height:0;display:none;}")
        if (isEnglish) {
            sb.append("html{direction:ltr;}body{direction:ltr;text-align:left;font-family:'")
        } else {
            sb.append("body{direction:rtl;text-align:right;font-family:'")
        }
        sb.append(cssQuote(fontFamily)).append("','").append(FALLBACK_FAMILY).append("',sans-serif;font-size:").append(fontSize)
        sb.append("px;line-height:1.8;padding:16px 24px;background-color:")
            .append(theme.bg).append(";color:").append(theme.text)
            .append(";animation:rdrFade .3s ease-out;}")
        sb.append("@keyframes rdrFade{from{opacity:.3;}to{opacity:1;}}")
        sb.append("body{-webkit-text-size-adjust:100%;overflow-wrap:break-word;word-wrap:break-word;}")
        sb.append("h1,h2,h3,h4,h5,h6{line-height:1.4;margin-top:1.1em;margin-bottom:0.5em;}")
        sb.append("a,a:link,a:visited{color:").append(theme.link)
            .append(" !important;text-decoration:underline;}")
        sb.append("[data-rdr=\"l\"]{direction:ltr;text-align:left;}")
        sb.append("[data-rdr=\"r\"]{direction:rtl;text-align:right;}")
        sb.append("img,svg{max-width:95% !important;width:auto !important;height:auto !important;}")
        sb.append("img{display:block;margin:12px auto;object-fit:contain;}")
        sb.append("table{max-width:100% !important;width:auto !important;display:block !important;")
        sb.append("overflow-x:auto !important;border-collapse:collapse;margin:1em 0;}")
        sb.append("th,td{padding:6px 10px;border:1px solid rgba(128,128,128,0.35);}")
        sb.append("pre{max-width:100% !important;white-space:pre-wrap !important;")
        sb.append("word-break:break-word !important;overflow-x:auto !important;}")
        sb.append("code{word-break:break-word;}")
        sb.append("[data-theme=\"dark\"] [style*=\"background:#fff\" i],")
        sb.append("[data-theme=\"dark\"] [style*=\"background:white\" i]{background-color:transparent !important;}")
        sb.append("</style>")
        sb.append("<style id=\"rdrFontFace\">").append(fontFaceCss).append("</style>")
        sb.append("<style id=\"readerSpacing\">")
            .append(spacingCss(lineHeight, sideMargin, paragraphSpacing)).append("</style>")
        if (paged) {
            // Paged layout overrides: the body becomes a horizontal
            // multicol pager (one 100vw column = one page); side margins
            // move onto the chapter div so columns stay exactly 100vw.
            // This style block comes after #readerSpacing on purpose.
            sb.append("<style id=\"rdrPaged\">").append(pagedCss(sideMargin))
                .append("</style>")
        }
        sb.append("<script>window.__rdrGen=").append(docGen).append(";</script>")
        sb.append("<script>(function(){")
        sb.append("var lt=-1,lh=-1,lc=-1,lr=-1,lci=-1;")
        sb.append("function r(){try{var d=document.documentElement,b=document.body;")
        sb.append("var t=Math.round(Math.max(d.scrollTop||0,b.scrollTop||0)),")
        sb.append("h=Math.max(d.scrollHeight||0,b.scrollHeight||0),")
        sb.append("c=d.clientHeight||b.clientHeight||0;")
        // Visible-chapter tracking: the chapter div whose span holds the
        // viewport top is the chapter being READ. Reported on change via
        // Android.onChapter — appends/prepends stitch the continuous
        // window around the reading position and must not move it.
        sb.append("var ci=-1;try{var chs=d.querySelectorAll('.rdr-chap');")
        sb.append("for(var k=0;k<chs.length;k++){var o=chs[k].offsetTop,hh=chs[k].offsetHeight;")
        sb.append("if(t>=o&&t<o+hh){ci=parseInt(chs[k].getAttribute('data-idx'),10);break;}}")
        sb.append("if(ci<0&&chs.length){var fq=chs[0],lq=chs[chs.length-1];")
        sb.append("if(t<(fq.offsetTop||0))ci=parseInt(fq.getAttribute('data-idx'),10);")
        sb.append("else if(t>=(lq.offsetTop||0))ci=parseInt(lq.getAttribute('data-idx'),10);}}catch(x){}")
        sb.append("if(ci!=lci){lci=ci;if(ci>=0){try{Android.onChapter(ci,window.__rdrGen);}catch(x){}}}")
        sb.append("if(t!=lt||h!=lh||c!=lc){lt=t;lh=h;lc=c;")
        sb.append("try{Android.onScroll(t,h,c,window.__rdrGen);}catch(x){}}")
        sb.append("else if(h>c){lr=t/(h-c);}}catch(x){}}")
        sb.append("function z(){try{if(lr>=0){var d=document.documentElement,b=document.body;")
        sb.append("var h=Math.max(d.scrollHeight||0,b.scrollHeight||0),")
        sb.append("c=d.clientHeight||b.clientHeight||0;")
        sb.append("var nt=Math.round(lr*Math.max(h-c,0));d.scrollTop=nt;b.scrollTop=nt;}}catch(x){}r();}")
        sb.append("window.addEventListener('scroll',r);window.addEventListener('resize',z);setInterval(r,200);")
        sb.append("})();</script>")
        // In-book link interception for every build (see LINK_JS):
        // without it a tapped link navigates the WebView itself out of
        // the reader shell.
        sb.append("<script>").append(LINK_JS).append("</script>")
        if (paged) {
            // rdrPager: deterministic block-level pagination (see PAGER_JS).
            // Runs before <body> is parsed; its boot waits for
            // DOMContentLoaded, then for fonts/images to settle.
            sb.append("<script>").append(PAGER_JS).append("</script>")
        }
        sb.append("</head><body>").append(bodyContent).append("</body></html>")
        return sb.toString()
    }

    /**
     * Paged-mode overrides (v2, rdrPager). The chapter div becomes a
     * fixed stage; the pager script measures the chapter's blocks and
     * packs them into explicit .rdr-page boxes inside a transformed
     * .rdr-strip. No multicol, no scroll geometry: page positions are
     * constructed in JS pixels. The side margin lives on .rdr-page
     * padding; ReaderViewModel.refreshSpacing() rebuilds this block so
     * margin changes apply on the next load (live swaps go through
     * pagedStyleUpdateJs + rdrPaginate()).
     */
    fun pagedCss(sideMargin: Int): String {
        val sb = StringBuilder()
        sb.append("html.rdr-paged,html.rdr-paged body{height:100%;}")
        sb.append("html.rdr-paged{overflow:hidden;}")
        // The WebView now receives ACTION_DOWN in paged mode (so tall
        // pages can scroll); without this, every tap flashes the
        // WebView's default tap highlight over the page.
        sb.append("html.rdr-paged body{-webkit-tap-highlight-color:transparent;}")
        sb.append("html.rdr-paged body{margin:0 !important;padding:0 !important;")
        // Windows parity: chapter fades in on load (rdrFade .3s).
        sb.append("animation:rdrFade .3s ease-out;}")
        sb.append("@keyframes rdrFade{from{opacity:.3;}to{opacity:1;}}")
        sb.append("html.rdr-paged .rdr-chap{position:absolute;top:0;right:0;bottom:0;left:0;overflow:hidden;}")
        sb.append(".rdr-strip{position:absolute;top:0;bottom:0;left:0;")
        sb.append("transition:transform .28s ease;}")
        sb.append(".rdr-strip.rdr-notrans{transition:none !important;}")
        sb.append(".rdr-page{position:absolute;top:0;height:100%;overflow:hidden;")
        sb.append("box-sizing:border-box;padding:16px ").append(sideMargin).append("px;}")
        sb.append(".rdr-tallwrap{height:100%;overflow-y:auto;}")
        // Windows parity for images: an image always fits COMPLETELY inside
        // one page. The cap leaves room for the page padding (32px), the
        // image's own margins (24px) and a possible caption, so a figure
        // can never overflow its page or force a phantom page.
        sb.append("html.rdr-paged img{max-height:calc(100vh - 128px) !important;}")
        // Full-bleed image pages (see markImagePages in PAGER_JS): the
        // page padding is dropped, the single image fills the whole
        // content box (contain: never cropped, never distorted), and a
        // blurred cover copy of the same image sits behind it, so the
        // aspect-mismatch remainder reads as artwork-colored ambience
        // instead of empty black bars. The backdrop rule is deliberately
        // more specific than the main-image rule below so it wins.
        sb.append(".rdr-page.rdr-imgpage{padding:0 !important;}")
        sb.append(".rdr-page.rdr-imgpage>:not(.rdr-imgbg){position:relative;z-index:1;height:100% !important;margin:0 !important;display:flex;align-items:center;justify-content:center;}")
        sb.append(".rdr-page.rdr-imgpage img,.rdr-page.rdr-imgpage svg{width:100% !important;height:100% !important;max-width:100% !important;max-height:none !important;margin:0 !important;object-fit:contain !important;}")
        sb.append(".rdr-imgbg{position:absolute;top:0;right:0;bottom:0;left:0;overflow:hidden;pointer-events:none;}")
        sb.append(".rdr-page.rdr-imgpage .rdr-imgbg img,.rdr-page.rdr-imgpage .rdr-imgbg svg{width:100% !important;height:100% !important;max-width:none !important;max-height:none !important;margin:0 !important;object-fit:cover !important;filter:blur(28px) brightness(0.65);transform:scale(1.15);}")
        return sb.toString()
    }

    /** Port of BuildSpacingCss; the maximized max-width branch is skipped (mobile). */
    fun spacingCss(lineHeight: Double, sideMargin: Int, paragraphSpacing: Double): String {
        var css = "body{line-height:" + String.format(Locale.US, "%.1f", lineHeight) +
            " !important;padding:16px " + sideMargin + "px !important;}" +
            "body *{line-height:inherit !important;}"
        if (paragraphSpacing > 0) {
            css += "p{margin-top:0 !important;margin-bottom:" +
                String.format(Locale.US, "%.1f", paragraphSpacing) + "em !important;}"
        }
        return css
    }

    /** Port of ApplySpacingLive: JS replacing the #readerSpacing style element. */
    fun spacingUpdateJs(css: String): String {
        val esc = css.replace("\\", "\\\\").replace("'", "\\'")
        return "(function(){var s=document.getElementById('readerSpacing');if(!s){return 'no';}" +
            "if(s.styleSheet){s.styleSheet.cssText='$esc';}else{s.innerHTML='$esc';}return 'ok';})();" +
            REPAGINATE_JS
    }

    /**
     * Live swap of the #rdrPaged style element (paged mode's margin lives
     * there, on .rdr-page padding) followed by a repagination, since page
     * geometry depends on it. No-op in continuous documents (no such
     * element, no rdrPaginate global).
     */
    fun pagedStyleUpdateJs(css: String): String {
        val esc = css.replace("\\", "\\\\").replace("'", "\\'")
        return "(function(){var s=document.getElementById('rdrPaged');if(!s){return 'no';}" +
            "if(s.styleSheet){s.styleSheet.cssText='$esc';}else{s.innerHTML='$esc';}return 'ok';})();" +
            REPAGINATE_JS
    }

    private fun jsStringLiteral(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /**
     * Paged mode: jump to the page holding the element with id [frag]
     * (rdrGoToAnchor queues the jump if pagination is still running).
     */
    fun anchorJumpJs(frag: String): String =
        "(function(){try{if(window.rdrGoToAnchor)window.rdrGoToAnchor(" +
            jsStringLiteral(frag) + ");}catch(e){}})();"

    /**
     * Vertical mode: scroll the element with id [frag] (or a legacy
     * <a name>) into view — used for in-book links whose target lives in
     * another chapter file.
     */
    fun anchorScrollJs(frag: String): String =
        "(function(){try{var id=" + jsStringLiteral(frag) + ";" +
            "var el=document.getElementById(id)||document.querySelector('a[name=\"'+id+'\"]');" +
            "if(el){el.scrollIntoView();return 'ok';}return 'miss';}catch(e){return 'err';}})();"

    /** Ask the pager (if present) to rebuild after a live style swap. */
    private const val REPAGINATE_JS =
        "(function(){try{if(window.rdrPaginate)window.rdrPaginate();}catch(e){}})();"

    /**
     * Port of AppendFontFace: weight 700 if the filename contains "bold"
     * else 400, mime font/otf for .otf else font/ttf.
     */
    fun fontFaceCss(family: String, files: List<File>): String {
        if (family.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        for (f in files) {
            val bytes = try {
                f.readBytes()
            } catch (e: Exception) {
                continue
            }
            val lowerName = f.nameWithoutExtension.lowercase(Locale.ROOT)
            val weight = if (lowerName.contains("bold")) "700" else "400"
            val mime = if (f.name.lowercase(Locale.ROOT).endsWith(".otf")) "font/otf" else "font/ttf"
            sb.append("@font-face{font-family:'").append(cssQuote(family))
                .append("';font-weight:").append(weight)
                .append(";font-display:swap;src:url(data:").append(mime).append(";base64,")
                .append(Base64.getEncoder().encodeToString(bytes)).append(");}")
        }
        return sb.toString()
    }

    /** Port of the AppendNextChapter insert script (docGen-guarded). */
    fun appendJs(docGen: Int, chunk: String): String =
        "(function(){if(window.__rdrGen!==$docGen)return 0;" +
            "document.body.insertAdjacentHTML('beforeend', '" +
            HtmlText.escapeForJs(chunk) + "');return 1;})();"

    /**
     * Removes out-of-window chapters from the DOM (continuous-mode trim):
     * deletes every `.rdr-chap` div whose `data-idx` falls outside
     * [keepFirst]..[keepLast], then compensates `scrollTop` ONLY for height
     * removed ABOVE the viewport (removing below-viewport chapters must not
     * move the scroll position). `querySelectorAll` returns a static list,
     * so removal during iteration is safe. DocGen-guarded like the
     * append/prepend scripts.
     */
    fun dropJs(docGen: Int, keepFirst: Int, keepLast: Int): String =
        "(function(){if(window.__rdrGen!==$docGen)return 0;" +
            "var d=document.documentElement,b=document.body;" +
            "function T(){return Math.max(d.scrollTop||0,b.scrollTop||0);}" +
            "function S(v){d.scrollTop=v;b.scrollTop=v;}" +
            "var ot=T(),removedAbove=0;" +
            "var chaps=b.querySelectorAll('.rdr-chap');" +
            "for(var i=0;i<chaps.length;i++){" +
            "var idx=parseInt(chaps[i].getAttribute('data-idx'),10);" +
            "if(idx<$keepFirst||idx>$keepLast){" +
            "var r=chaps[i].getBoundingClientRect();" +
            "if(r.bottom<=1)removedAbove+=r.height;" +
            "chaps[i].parentNode.removeChild(chaps[i]);}}" +
            "S(Math.max(0,ot-removedAbove));" +
            "return 1;})();"

    /** Port of the PrependPreviousChapter insert script (scroll-preserving). */
    fun prependJs(docGen: Int, chunk: String, divider: String): String =
        "(function(){if(window.__rdrGen!==$docGen)return 0;" +
            "var d=document.documentElement,b=document.body;" +
            "function H(){return Math.max(d.scrollHeight||0,b.scrollHeight||0);}" +
            "function T(){return Math.max(d.scrollTop||0,b.scrollTop||0);}" +
            "function S(v){d.scrollTop=v;b.scrollTop=v;}" +
            "var cs=b.getElementsByTagName('div'),f=null;" +
            "for(var i=0;i<cs.length;i++){if(cs[i].className=='rdr-chap'){f=cs[i];break;}}" +
            "var oh=H(),ot=T();" +
            "if(f){f.insertAdjacentHTML('afterbegin','" + HtmlText.escapeForJs(divider) + "');}" +
            "b.insertAdjacentHTML('afterbegin','" + HtmlText.escapeForJs(chunk) + "');" +
            "var ph=H();S(ot+(ph-oh));" +
            "return 1;})();"

    /**
     * rdrPager script (paged builds only) — GENERATED, DO NOT REINDENT.
     * Byte-identical to the pager.js verified in headless Chromium
     * (488 pagination checks + 68 link checks: text integrity, per-page
     * fit, turn contract, RTL+LTR geometry, repagination anchors,
     * in-book link navigation). It assigns window.rdrTurn /
     * window.rdrLast / window.rdrGoToAnchor explicitly: the old multicol
     * versions were declared inside the reporter script's IIFE and were
     * never callable from native code (verified:
     * typeof window.rdrTurn === 'undefined').
     */
    private const val PAGER_JS = """// rdrPager — deterministic block-level pagination for Novelfa paged mode.
//
// Replaces the CSS-multicol pager. Why: multicol page geometry was DERIVED
// from Blink's fragmentation layout (scrollWidth / scrollLeft), which is
// inexact — phantom pages, sliced images, RTL scroll-model drift. Here the
// geometry is CONSTRUCTED: after fonts/images settle, the chapter's blocks
// are measured at the exact page content size and packed into explicit
// .rdr-page boxes; navigation is a physical translateX on the strip.
// No vw units, no scrollWidth, no scrollLeft anywhere.
//
// Contract with native code (unchanged from the multicol version):
//   rdrTurn(d)  -> 'page' | 'start' | 'end' | 'busy' | 'torn'
//   ('torn' = the pager tore down: the document is a scrolling flow
//   page now, so native code must NOT retry it as a busy pager —
//   it must fall back to a viewport scroll / chapter move, or the
//   reader goes silently dead after its one reload is spent.)
//   rdrLast()   -> 'ok' | 'no'   (land on the last page)
//   Android.onScroll(t,h,c,gen) is fed synthesized values
//   (t = page*clientH, h = pages*clientH) so the bridge stays meaningful.
// Extra: rdrPaginate() rebuilds (spacing/margin/font/resize), restoring the
// reading position by anchor block; rdrPageCount()/rdrCurrentPage().
(function () {
    var de = document.documentElement;
    if (!de.classList.contains('rdr-paged')) return;

    var EPS = 1.5;              // packing tolerance (px)
    var ANIM_MS = 280;          // must match the .rdr-strip CSS transition
    var SETTLE_TIMEOUT = 2500;  // max wait for fonts/images before paginating
    // Was 3: images in one chapter often arrive staggered, and each
    // arrival schedules one debounced repagination — 3 was exhausted
    // before the last images landed, leaving pages measured with
    // zero-height images and their overflow CLIPPED (.rdr-page
    // overflow:hidden), i.e. "the next page never renders fully".
    // 10 stays bounded (loads are finite) while covering staggered
    // image arrivals in image-heavy chapters.
    var MAX_AUTO_REPAGINATE = 10;

    var rtl = de.getAttribute('dir') === 'rtl';
    var chap = null;            // .rdr-chap — becomes the stage
    var source = null;          // .rdr-source — pristine chapter content (display:none)
    var strip = null;           // .rdr-strip — transformed page strip
    var pages = [];             // [{el, firstLi, firstTok}]
    var cur = 0;
    var ready = false;
    var lockUntil = 0;
    var pendingLast = false;
    var pendingAnchor = null;
    // Text-point restore queued by native code (rdrGoToAnchorFrag)
    // while the pager is still building: applied by paginate() when
    // its build finishes, then cleared, so a later repagination
    // (late images, resize, spacing) never jumps back to it after
    // the user has turned pages.
    var pendingFrag = null;
    var autoRepag = 0;
    var repagTimer = 0;
    var building = false;

    // ------------------------------------------------------------------
    // Tag classification (structure-based; EPUB XHTML is semantic enough
    // that computed styles are not needed to classify blocks).
    // ------------------------------------------------------------------
    var ATOMIC_TAGS = {
        IMG: 1, TABLE: 1, PRE: 1, HR: 1, FIGURE: 1, VIDEO: 1, AUDIO: 1,
        IFRAME: 1, SVG: 1, CANVAS: 1, OBJECT: 1, EMBED: 1, FORM: 1,
        FIELDSET: 1, MATH: 1
    };
    var TEXT_TAGS = {
        P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, LI: 1, DT: 1, DD: 1,
        SUMMARY: 1, FIGCAPTION: 1, CAPTION: 1, TD: 1, TH: 1
    };
    var BLOCK_TAGS = {
        P: 1, DIV: 1, SECTION: 1, ARTICLE: 1, ASIDE: 1, NAV: 1, HEADER: 1,
        FOOTER: 1, MAIN: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1,
        UL: 1, OL: 1, LI: 1, DL: 1, DT: 1, DD: 1, BLOCKQUOTE: 1, FIGURE: 1,
        FIGCAPTION: 1, TABLE: 1, PRE: 1, HR: 1, FORM: 1, FIELDSET: 1,
        ADDRESS: 1, DETAILS: 1
    };
    // Elements that make a text block unsafe to split (splitting would
    // need to reason about their layout) — such blocks fall back instead.
    var UNSPLITTABLE_INSIDE = 'img,svg,table,video,audio,iframe,object,embed,pre,math,canvas';

    function isBlockLevel(node) {
        return node.nodeType === 1 && !!BLOCK_TAGS[node.tagName];
    }

    // Group a container's children into top-level items: block elements
    // stand alone; runs of text/inline nodes are wrapped in .rdr-anon so
    // they become measurable, movable blocks.
    function topItems(container) {
        var items = [], run = null, i, n;
        function flush() {
            if (run && run.childNodes.length) items.push(run);
            run = null;
        }
        for (i = 0; i < container.childNodes.length; i++) {
            n = container.childNodes[i];
            if (isBlockLevel(n)) {
                flush();
                items.push(n);
            } else if (n.nodeType === 3 && !/\S/.test(n.nodeValue)) {
                // whitespace-only text between blocks: layout-irrelevant
                continue;
            } else {
                if (!run) {
                    run = document.createElement('div');
                    run.className = 'rdr-anon';
                }
                run.appendChild(n.cloneNode(true));
            }
        }
        flush();
        return items;
    }

    function hasBlockChild(el) {
        for (var i = 0; i < el.childNodes.length; i++) {
            if (isBlockLevel(el.childNodes[i])) return true;
        }
        return false;
    }

    // Flatten the chapter tree into an ordered leaf stream. Containers are
    // descended into; each leaf remembers its wrapper chain (outermost
    // first) so page fragments can be wrapped in shallow clones of the
    // original containers (styles, backgrounds, indents preserved).
    // Returns [{chain:[el,...], leaf:el}]
    function flatten(container, chain, out) {
        var items = topItems(container), i, it;
        for (i = 0; i < items.length; i++) {
            it = items[i];
            if (!ATOMIC_TAGS[it.tagName] && !TEXT_TAGS[it.tagName] && hasBlockChild(it)) {
                flatten(it, chain.concat([it]), out);
            } else {
                out.push({ chain: chain, leaf: it });
            }
        }
        return out;
    }

    // Wrap a leaf clone in shallow clones of its chain (innermost last).
    function wrapFragment(chain, leafClone) {
        var node = leafClone, i, w;
        for (i = chain.length - 1; i >= 0; i--) {
            w = chain[i].cloneNode(false);
            w.appendChild(node);
            node = w;
        }
        return node;
    }

    // ------------------------------------------------------------------
    // Text splitting. A splittable leaf (pure text + inline markup) is
    // tokenized into words / atomic inline elements, each token knowing
    // its inline-ancestor chain. Fragments are rebuilt by replaying a
    // token range and cloning the chain — so a split never loses or
    // duplicates a character (the token stream partitions the text).
    // ------------------------------------------------------------------
    function tokenize(leaf) {
        var tokens = [];
        function walk(node, chain) {
            var i, ch, m, last, re;
            for (i = 0; i < node.childNodes.length; i++) {
                ch = node.childNodes[i];
                if (ch.nodeType === 3) {
                    var s = ch.nodeValue;
                    re = /\S+\s*/g; last = 0;
                    while ((m = re.exec(s)) !== null) {
                        if (m.index > last) {
                            tokens.push({ t: 'w', text: s.slice(last, m.index), chain: chain.slice() });
                        }
                        tokens.push({ t: 'w', text: m[0], chain: chain.slice() });
                        last = m.index + m[0].length;
                    }
                    if (last < s.length) {
                        tokens.push({ t: 'w', text: s.slice(last), chain: chain.slice() });
                    }
                } else if (ch.nodeType === 1) {
                    if (ch.tagName === 'BR' || !hasText(ch)) {
                        tokens.push({ t: 'e', el: ch, chain: chain.slice() });
                    } else {
                        walk(ch, chain.concat([ch]));
                    }
                }
            }
        }
        function hasText(el) {
            return /\S/.test(el.textContent || '');
        }
        walk(leaf, []);
        return tokens;
    }

    function buildFragment(leaf, tokens, from, to) {
        var frag = leaf.cloneNode(false);
        frag.className = (frag.className ? frag.className + ' ' : '') + 'rdr-frag';
        var open = []; // [{src, el}]
        function parentEl() { return open.length ? open[open.length - 1].el : frag; }
        for (var i = from; i < to; i++) {
            var tok = tokens[i], want = tok.chain, k = 0, j, c;
            while (k < open.length && k < want.length && open[k].src === want[k]) k++;
            open.length = k;
            for (j = k; j < want.length; j++) {
                c = want[j].cloneNode(false);
                parentEl().appendChild(c);
                open.push({ src: want[j], el: c });
            }
            if (tok.t === 'w') parentEl().appendChild(document.createTextNode(tok.text));
            else parentEl().appendChild(tok.el.cloneNode(true));
        }
        return frag;
    }

    function isSplittable(leaf) {
        if (ATOMIC_TAGS[leaf.tagName]) return false;
        if (!TEXT_TAGS[leaf.tagName] && hasBlockChild(leaf)) return false;
        if (leaf.querySelector && leaf.querySelector(UNSPLITTABLE_INSIDE)) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // NPE-2.3 pause rule (see NOVELFA_PAGE_ARCHITECTURE.md §17): a page
    // must never end mid-phrase. Pauses are tiered by what they close:
    //   STRONG — a complete unit closes (sentence end or independent
    //     clause/list item): . ؟ ! … ؛ (+ Latin . ? ! … ;)
    //   WEAK — a phrase closes but the sentence still depends on what
    //     follows (introductory phrase, announcement): ، : (+ Latin , :)
    // The break is the latest STRONG pause that wastes at most 3 lines
    // (the same "standard" as the tail exception); if the strong pause
    // is farther back, the latest WEAK pause after it; if there is no
    // weak pause after it, the strong pause anyway; if the page has no
    // pause at all, the fill-point break (last resort). A pause that
    // would leave a first fragment of a single line is skipped (orphan
    // guard) — the leaf then starts fresh on the next page instead.
    // ------------------------------------------------------------------
    var PAUSE_STRONG = '.؟!…؛?;';
    var PAUSE_WEAK = '،:,';
    var PAUSE_CLOSERS = '»«“”‘’\'")]}';
    var PAUSE_WASTE_STD = 3;
    // Tier of the pause a word token ends with ('strong' / 'weak' / '').
    // Trailing whitespace and trailing quote/bracket closers are stripped
    // first, so «می‌سوزد.»» counts as a sentence end and «گفت:«» as a
    // colon — Persian quotes otherwise hide the pause.
    function pauseKind(tokText) {
        var w = tokText, c;
        while (w.length && /\s/.test(w.charAt(w.length - 1))) w = w.slice(0, -1);
        while (w.length && PAUSE_CLOSERS.indexOf(w.charAt(w.length - 1)) >= 0) w = w.slice(0, -1);
        if (!w.length) return '';
        c = w.charAt(w.length - 1);
        if (PAUSE_STRONG.indexOf(c) >= 0) return 'strong';
        if (PAUSE_WEAK.indexOf(c) >= 0) return 'weak';
        return '';
    }

    // ------------------------------------------------------------------
    // Page construction.
    // ------------------------------------------------------------------
    // Build generation: incremented whenever a newer build supersedes
    // the running one (a resize mid-build). Slices of the old build
    // check their captured generation at each slice boundary and stop
    // silently — they must never call onDone or tear the document down
    // after a newer build has taken over.
    var buildSeq = 0;
    // Stage telemetry: what the device actually measured, so a
    // diagnostic screenshot settles "transient vs permanently broken"
    // instead of another round of guessing. stageDbg() returns a short
    // string with no '|' (the native dialog splits on it); the native
    // side reads it via window.__rdrStageDbg().
    var stageRefused = '', stageResizes = 0, stageLast = '', stagePolls = 0;
    var stageSrcW = '', stageSrcH = '';
    function stageNote() {
        stageLast = stageW() + 'x' + stageH() + ' (' + stageSrcW + '/' + stageSrcH + ')';
    }
    function stageDbg() {
        return 'refused ' + (stageRefused || '-') + ', resizes ' + stageResizes + ', last ' + (stageLast || '-') + ', polls ' + stagePolls;
    }
    window.__rdrStageDbg = stageDbg;
    // Collapsed-stage wait, v2: when a build is refused because the
    // stage is implausibly small, DO NOT depend on a resize event to
    // heal it — on the reporting phone the stage stayed insane for the
    // whole 3s with no healing resize (the resize fired before the
    // listener attached, or the WebView grew without one). Poll the
    // stage every 250ms and restart the build the moment it is sane,
    // whatever woke it up; the resize handler stays as the fast path.
    // If the stage never becomes sane within 6s, tear down with the
    // measured values in the error so the diagnostic names the actual
    // numbers instead of a bare guess. Cleared the moment a real
    // build starts.
    var stageWaitTimer = 0, stageWaitDeadline = 0;
    function clearStageWait() {
        if (stageWaitTimer) { clearTimeout(stageWaitTimer); stageWaitTimer = 0; }
        stageWaitDeadline = 0;
    }
    function stageWaitTick() {
        stageWaitTimer = 0;
        if (tornDown || !building || ready) return;
        stagePolls++;
        var Wpx = stageW(), Hpx = stageH();
        stageNote();
        if (Wpx >= 50 && Hpx >= 120) {
            // Healed without a resize event: restart exactly like the
            // resize handler does (abort the waiting build first).
            buildSeq++;
            building = false;
            window.__rdrPagerBuilding = false;
            clearStageWait();
            paginate();
            return;
        }
        if (Date.now() >= stageWaitDeadline) {
            teardown(new Error('stage never reached usable size (last ' + stageLast + ', resizes ' + stageResizes + ')'));
            return;
        }
        stageWaitTimer = setTimeout(stageWaitTick, 250);
    }
    function armStageWait() {
        if (stageWaitTimer) return;
        stageWaitDeadline = Date.now() + 6000;
        stageWaitTimer = setTimeout(stageWaitTick, 250);
    }
    function newPageEl() {
        var p = document.createElement('div');
        p.className = 'rdr-page';
        return p;
    }

    function physLeft(i, count, w) {
        return rtl ? (count - 1 - i) * w : i * w;
    }

    // The stage (.rdr-chap) must exactly fill the viewport. If its CSS
    // fails on an old WebView (e.g. no `inset` support -> shrink-wrapped
    // stage), chap.clientWidth/Height are garbage (max-content width,
    // full-chapter height) and every page lands off-screen: a completely
    // blank reader. The window size is the ground truth in that case.
    function stageW() {
        var w = chap ? chap.clientWidth : 0;
        var iw = window.innerWidth || 0;
        if (iw && (!w || Math.abs(w - iw) > 2)) { stageSrcW = 'win'; return iw; }
        if (w || iw) { stageSrcW = w ? 'chap' : 'win'; return w || iw; }
        // Both window and stage read 0 (a WebView whose window object
        // lags its layout): the document element's client size is the
        // same viewport by another measurement — use it rather than
        // refusing a build on a stage that is really there.
        var dw = document.documentElement ? document.documentElement.clientWidth : 0;
        stageSrcW = dw ? 'doc' : 'none';
        return dw;
    }
    function stageH() {
        var h = chap ? chap.clientHeight : 0;
        var ih = window.innerHeight || 0;
        if (ih && (!h || Math.abs(h - ih) > 2)) { stageSrcH = 'win'; return ih; }
        if (h || ih) { stageSrcH = h ? 'chap' : 'win'; return h || ih; }
        var dh = document.documentElement ? document.documentElement.clientHeight : 0;
        stageSrcH = dh ? 'doc' : 'none';
        return dh;
    }

    // onDone(earlyRestored) runs when the build finishes. progressive
    // is true for the FIRST build of a document (pages appear as they
    // are built); a repagination rebuilds for a new size/font, and
    // showing half-built pages there would flicker and then jump back
    // to the restored page — so repaginations keep the strip hidden
    // until the build finishes, exactly like the old synchronous build.
    function buildPages(onDone, progressive) {
        var myGen = buildSeq;
        var Wpx = stageW(), Hpx = stageH();
        // Collapsed-stage guard: a stage only a few dozen px tall (or
        // narrow) cannot be a real reader viewport — it is the WebView
        // still settling (bars animating in, first layout). Building
        // on it packs one token per page (contentH below one line
        // height) into a thousand-page strip that then takes minutes
        // to grind through, while the native side reports the growing
        // garbage count as real pages. Refuse the build instead: the
        // resize handler restarts it once the stage is sane, and the
        // wait timer above bounds the wait. Nothing is reported, no
        // pages are built, the document is not torn down.
        if (Wpx < 50 || Hpx < 120) { stageRefused = Wpx + 'x' + Hpx; stageNote(); armStageWait(); return; }

        // Pages MUST carry their final width from the start: an
        // absolutely-positioned page without a width shrink-wraps, and
        // every measurement taken inside it would be garbage.
        function mkPage() {
            var p = newPageEl();
            p.style.width = Wpx + 'px';
            p.style.left = '0px';
            return p;
        }

        // Padding probe, appended to the stage (not the strip) so the
        // strip's existing pages are untouched if the content-height
        // guard below refuses the build.
        var padProbe = mkPage();
        padProbe.style.visibility = 'hidden';
        chap.appendChild(padProbe);
        var pcs = window.getComputedStyle(padProbe);
        var padT = parseFloat(pcs.paddingTop) || 0;
        var padB = parseFloat(pcs.paddingBottom) || 0;
        chap.removeChild(padProbe);
        var contentH = Hpx - padT - padB;
        if (contentH < 48) { stageRefused = Wpx + 'x' + Hpx + ' (contentH ' + Math.round(contentH) + ')'; stageNote(); armStageWait(); return; }
        clearStageWait();

        strip.innerHTML = '';
        pages = [];
        // Progressive display: on the first build the strip stays
        // visible from the first slice, so the reader shows real pages
        // as they are built instead of a blank "preparing" screen for
        // the whole chapter. Only the page under construction (the
        // probe) is hidden until it is finalized; finished pages are
        // revealed by finalizePage as soon as they are complete.
        strip.style.visibility = progressive ? '' : 'hidden';

        var probe = mkPage();
        // visibility:hidden (never display:none): the probe still has
        // layout, so every measurement taken inside it is identical
        // to the old hidden-strip build — only its pixels are hidden.
        probe.style.visibility = 'hidden';
        strip.appendChild(probe);

        var leaves = flatten(source, [], []);
        var cur1 = { el: probe, frags: [] };
        var li = 0;
        var splitState = null;
        var earlyRestored = false;

        function probeTop() { return probe.getBoundingClientRect().top + padT; }
        function usedH() {
            var last = probe.lastElementChild;
            if (!last) return 0;
            return last.getBoundingClientRect().bottom - probeTop();
        }
        function tagFrag(root, li, tokFrom, tokTo) {
            root.setAttribute('data-rdr-li', li);
            root.setAttribute('data-rdr-tok', tokFrom);
            root.setAttribute('data-rdr-tokend', tokTo);
        }
        function finalizePage() {
            if (cur1.frags.length) {
                pages.push(cur1);
                // The page is complete: reveal it now, not at the end
                // of the whole chapter.
                cur1.el.style.visibility = '';
            } else if (probe.parentNode === strip) {
                // Empty construction page (e.g. finalize called twice in a
                // row around a tall page): discard it — a stray .rdr-page
                // left in the strip would corrupt the page geometry.
                strip.removeChild(probe);
            }
            probe = mkPage();
            probe.style.visibility = 'hidden';
            strip.appendChild(probe);
            cur1 = { el: probe, frags: [] };
        }
        function emitTallPage(chain, leaf, li, tokFrom, tokTo) {
            // finalizePage leaves a fresh empty probe as the construction
            // page; insert the tall page BEFORE it (keeps DOM order ==
            // page order) and keep building into that same probe. (An
            // earlier version created a second probe here and orphaned
            // the first one as a stray .rdr-page in the strip.)
            finalizePage();
            var pg = mkPage();
            pg.className = 'rdr-page rdr-tall';
            var wrap = document.createElement('div');
            wrap.className = 'rdr-tallwrap';
            var frag = wrapFragment(chain, leaf.cloneNode(true));
            tagFrag(frag, li, tokFrom || 0, (tokTo === undefined ? -1 : tokTo));
            wrap.appendChild(frag);
            pg.appendChild(wrap);
            strip.insertBefore(pg, probe);
            pages.push({ el: pg, frags: [frag] });
        }

        // Try to place a whole leaf on the current page; returns true if placed.
        function tryWhole(chain, leaf, li) {
            var frag = wrapFragment(chain, leaf.cloneNode(true));
            tagFrag(frag, li, 0, -1);
            probe.appendChild(frag);
            if (usedH() <= contentH + EPS) {
                cur1.frags.push(frag);
                return true;
            }
            probe.removeChild(frag);
            return false;
        }

        // --- Splittable-leaf pagination, one fragment per step. ---
        // splitLeaf used to run its whole while loop synchronously:
        // one binary search per page-fragment, each step a forced
        // layout — for a long leaf, the whole chapter's layout work
        // in a single JS task, i.e. seconds of frozen UI on a phone,
        // during which native polls queue up and the pager looks
        // stuck. The step form below performs exactly the same
        // fragment decisions in the same order (geometry-identical
        // output); the slice driver just yields to the event loop
        // between steps.
        function startSplit(chain, leaf, li, availH) {
            var tokens = tokenize(leaf);
            // A leaf with no tokens (an empty block, a spacer div with
            // only padding) must be skipped, not split: the split loop
            // would otherwise read tokens[from] past the end and throw
            // mid-build, tearing down a healthy chapter.
            if (!tokens.length) return null;
            var lineH = 0;
            try { lineH = parseFloat(window.getComputedStyle(leaf).lineHeight) || 0; } catch (e) { }
            if (!lineH) lineH = 24;
            return {
                chain: chain, leaf: leaf, li: li, availH: availH,
                tokens: tokens, from: 0, first: true,
                fragIndex: 0, lineH: lineH
            };
        }
        // One iteration of the old splitLeaf while loop. Returns true
        // when the leaf is fully consumed.
        function stepSplit(st) {
            var tokens = st.tokens, chain = st.chain, leaf = st.leaf, li = st.li;
            var lineH = st.lineH;
            var avail = st.first ? st.availH : contentH;
            if (avail < lineH) { finalizePage(); avail = contentH; }
            var hi = tokens.length, best = st.from, trial = null;
            function measure(end) {
                if (trial) { probe.removeChild(trial); trial = null; }
                var f = buildFragment(leaf, tokens, st.from, end);
                if (st.fragIndex > 0) f.style.marginTop = '0px';
                if (end < tokens.length) f.style.marginBottom = '0px';
                var w = wrapFragment(chain, f);
                tagFrag(w, li, st.from, end);
                probe.appendChild(w);
                trial = w;
                return usedH() <= contentH + EPS;
            }
            if (measure(hi)) {
                best = hi;
            } else {
                var a = st.from, b = hi;
                while (a < b) {
                    var mid = (a + b + 1) >> 1;
                    if (measure(mid)) a = mid; else b = mid - 1;
                }
                best = a;
                if (best > st.from) measure(best); // leave the best trial in the probe
            }
            if (best === st.from) {
                if (trial) { probe.removeChild(trial); trial = null; }
                if (usedH() > 0) {
                    // Not even one token fits in THIS page's remaining
                    // space (margin collapsing can eat more room than
                    // the line-height estimate suggested) — that is
                    // normal: finish the page and retry on a fresh one
                    // before concluding the token is oversize.
                    finalizePage();
                    st.first = false;
                    return false;
                }
                // Even on an empty page one token does not fit: force
                // it onto its own tall page rather than losing it or
                // looping forever.
                var one = buildFragment(leaf, tokens, st.from, st.from + 1);
                if (st.fragIndex > 0) one.style.marginTop = '0px';
                if (st.from + 1 < tokens.length) one.style.marginBottom = '0px';
                emitTallPage(chain, one, li, st.from, st.from + 1);
                st.from += 1;
                st.fragIndex++;
                st.first = false;
                return st.from >= tokens.length;
            }
                // ---- NPE-2.3 pause rule: never end a page mid-phrase.
                // The binary search above chose `best` (the fill point).
                // Backtrack to a pause inside the fitted tokens instead:
                // latest STRONG pause if it wastes <= PAUSE_WASTE_STD
                // lines, else latest WEAK pause after it, else the strong
                // pause anyway. If there is no pause and this is the first
                // fragment on a non-empty page, the last pause on the page
                // is at the end of an earlier block: finish the page and
                // let the leaf start fresh (§17.5 fallback). Otherwise
                // keep the fill-point break (last resort).
                var chosenEnd = best;
                if (best < tokens.length) {
                    var sEnd = -1, wEnd = -1, ti, pk;
                    for (ti = best - 1; ti >= st.from; ti--) {
                        if (tokens[ti].t !== 'w') continue;
                        pk = pauseKind(tokens[ti].text);
                        if (pk === 'strong' && sEnd < 0) sEnd = ti + 1;
                        else if (pk === 'weak' && wEnd < 0) wEnd = ti + 1;
                        if (sEnd >= 0 && wEnd >= 0) break;
                    }
                    var cand = -1;
                    if (sEnd >= 0) {
                        var usedBest = usedH();
                        measure(sEnd);
                        var waste = Math.round((usedBest - usedH()) / (lineH || 24));
                        if (waste <= PAUSE_WASTE_STD) cand = sEnd;
                        else if (wEnd > sEnd) cand = wEnd;
                        else cand = sEnd;
                    } else if (wEnd >= 0) {
                        cand = wEnd;
                    }
                    if (cand >= 0 && st.fragIndex === 0) {
                        // Orphan guard: a first fragment of a single line
                        // is not a break (approximation: fragment height
                        // vs 1.5 line heights — leaf padding can blur it,
                        // but the guard only ever skips a break, never
                        // creates one, so a miss is harmless).
                        if (cand !== best) measure(cand);
                        var fh = trial ? trial.getBoundingClientRect().height : 0;
                        if (fh <= (lineH || 24) * 1.5 + EPS) cand = -1;
                    }
                    if (cand >= 0) {
                        chosenEnd = cand;
                    } else if (st.fragIndex === 0 && cur1.frags.length > 0) {
                        if (trial) { probe.removeChild(trial); trial = null; }
                        finalizePage();
                        st.first = false;
                        return false;
                    } else {
                        chosenEnd = best;
                    }
                    // The trial in the probe must be exactly the chosen
                    // fragment (measure() swaps the trial and re-tags it).
                    measure(chosenEnd);
                }
            // The trial in the probe is exactly the chosen fragment.
            cur1.frags.push(trial);
            trial = null;
            st.from = chosenEnd;
            st.fragIndex++;
            st.first = false;
            if (st.from < tokens.length) finalizePage();
            return st.from >= tokens.length;
        }

        // One unit of leaf work. Returns true when every leaf is done.
        function stepWork() {
            if (splitState) {
                if (stepSplit(splitState)) { splitState = null; li++; }
                return splitState === null && li >= leaves.length;
            }
            if (li >= leaves.length) return true;
            var chain = leaves[li].chain, leaf = leaves[li].leaf;
            if (tryWhole(chain, leaf, li)) { li++; return li >= leaves.length; }
            var avail = contentH - usedH();
            var lineH = 0;
            try { lineH = parseFloat(window.getComputedStyle(leaf).lineHeight) || 0; } catch (e) { }
            if (isSplittable(leaf) && (cur1.frags.length === 0 || avail >= (lineH || 24))) {
                splitState = startSplit(chain, leaf, li, avail);
                if (!splitState) { li++; return li >= leaves.length; }
                return false;
            } else if (cur1.frags.length) {
                finalizePage();
                if (!tryWhole(chain, leaf, li)) {
                    if (isSplittable(leaf)) {
                        splitState = startSplit(chain, leaf, li, contentH);
                        if (!splitState) { li++; return li >= leaves.length; }
                        return false;
                    } else emitTallPage(chain, leaf, li);
                }
                li++;
                return li >= leaves.length;
            } else {
                emitTallPage(chain, leaf, li);
                li++;
                return li >= leaves.length;
            }
        }

        function refreshBuiltGeometry() {
            var i;
            for (i = 0; i < pages.length; i++) {
                pages[i].el.style.left = physLeft(i, pages.length, Wpx) + 'px';
                pages[i].el.style.width = Wpx + 'px';
            }
            strip.style.width = (pages.length * Wpx) + 'px';
            // Keep the page the user is looking at in view as the
            // strip grows (RTL: a page's offset depends on the total
            // count, so it shifts with every appended page).
            if (pages.length) show(cur, true);
        }

        // Has the pager built far enough that a queued text-point
        // restore (li:tok) can land on its final page? True once a
        // built fragment starts strictly after the target while an
        // earlier built fragment starts at or before it: the fragment
        // covering the target is then built, and its page is already
        // finalized (a page only ever gains fragments before it is
        // finalized, never after).
        function builtCoversFrag(tli, ttok) {
            var seenAt = false, seenAfter = false, i, j, f, fli, ftok;
            function scan(frags) {
                for (j = 0; j < frags.length; j++) {
                    f = frags[j];
                    fli = parseInt(f.getAttribute('data-rdr-li'), 10) || 0;
                    ftok = parseInt(f.getAttribute('data-rdr-tok'), 10) || 0;
                    if (fli < tli || (fli === tli && ftok <= ttok)) seenAt = true;
                    else seenAfter = true;
                    if (seenAt && seenAfter) return true;
                }
                return false;
            }
            for (i = 0; i < pages.length; i++) {
                if (scan(pages[i].frags)) return true;
            }
            if (scan(cur1.frags)) return true;
            return false;
        }

        function maybeEarlyRestore() {
            if (pendingFrag) {
                var pp = String(pendingFrag).split(':');
                var pli = parseInt(pp[0], 10), ptok = parseInt(pp[1], 10);
                if (!isNaN(pli) && !isNaN(ptok) && builtCoversFrag(pli, ptok)) {
                    pendingFrag = null;
                    earlyRestored = true;
                    show(pageForAnchor({ li: pli, tok: ptok }), true);
                }
            } else if (pendingAnchor) {
                if (findAnchor(pendingAnchor)) {
                    var id = pendingAnchor;
                    pendingAnchor = null;
                    earlyRestored = true;
                    goToAnchorNow(id);
                }
            }
        }

        function finishBuild() {
            finalizePage();
            // Drop the trailing empty construction page finalizePage created.
            if (probe.parentNode === strip && !probe.childNodes.length) {
                strip.removeChild(probe);
            }
            if (!pages.length) {
                var only = newPageEl();
                strip.appendChild(only);
                pages.push({ el: only, frags: [] });
            }
            // Geometry: explicit px positions, direction-aware.
            var i;
            for (i = 0; i < pages.length; i++) {
                pages[i].el.style.left = physLeft(i, pages.length, Wpx) + 'px';
                pages[i].el.style.width = Wpx + 'px';
            }
            strip.style.width = (pages.length * Wpx) + 'px';
            strip.style.visibility = '';
            onDone(earlyRestored);
        }

        // Time-sliced driver: at most ~30ms of layout work per slice,
        // then yield so the page stays responsive (scrolling, taps,
        // the native "preparing" poll) while a long chapter builds.
        function runSlice() {
            if (myGen !== buildSeq) return;
            var t0 = Date.now(), done = false;
            try {
                while (!done && Date.now() - t0 < 30) done = stepWork();
            } catch (e) {
                teardown(e);
                return;
            }
            if (done) {
                finishBuild();
                return;
            }
            if (progressive) {
                refreshBuiltGeometry();
                maybeEarlyRestore();
            }
            setTimeout(runSlice, 0);
        }
        runSlice();
    }

    // ------------------------------------------------------------------
    // Navigation.
    // ------------------------------------------------------------------
    function report() {
        try {
            // Dedicated page-report channel (see ScrollBridge):
            // never onScroll. onScroll carries real scroll geometry,
            // and a torn document's scroll geometry (screens tall)
            // used to masquerade as page numbers in the native pill
            // once the native torn flag was missed.
            if (window.Android && Android.onPagerReport) {
                Android.onPagerReport(cur + 1, pages.length);
            }
        } catch (e) { }
    }

    function show(i, instant) {
        if (!pages.length) return;
        cur = Math.max(0, Math.min(i, pages.length - 1));
        var tx = -physLeft(cur, pages.length, stageW());
        if (instant) strip.classList.add('rdr-notrans');
        strip.style.transform = 'translate3d(' + tx + 'px,0,0)';
        if (instant) {
            // Force reflow so the no-transition jump applies before the
            // class is removed again.
            void strip.offsetWidth;
            strip.classList.remove('rdr-notrans');
        } else {
            lockUntil = Date.now() + ANIM_MS;
        }
        report();
    }

    function currentAnchor() {
        if (!pages.length || !pages[cur].frags.length) return { li: 0, tok: 0 };
        var f = pages[cur].frags[0];
        return {
            li: parseInt(f.getAttribute('data-rdr-li'), 10) || 0,
            tok: parseInt(f.getAttribute('data-rdr-tok'), 10) || 0
        };
    }

    function pageForAnchor(a) {
        var best = 0, i, j;
        for (i = 0; i < pages.length; i++) {
            for (j = 0; j < pages[i].frags.length; j++) {
                var f = pages[i].frags[j];
                var li = parseInt(f.getAttribute('data-rdr-li'), 10) || 0;
                var tok = parseInt(f.getAttribute('data-rdr-tok'), 10) || 0;
                if (li < a.li || (li === a.li && tok <= a.tok)) best = i;
                else return best;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // (Re)pagination with anchor restore + a catastrophic-failure escape
    // hatch: if anything throws (or the stage measures zero), TEAR DOWN
    // to a normal flowing document — the pristine source children move
    // back into .rdr-chap, the strip is dropped, and the rdr-paged class
    // is removed so every paged CSS rule stops applying. Whatever goes
    // wrong on a given device, the chapter stays readable as a scrolling
    // page instead of a blank screen.
    // ------------------------------------------------------------------
    var tornDown = false;
    function teardown(err) {
        if (tornDown) return;
        tornDown = true;
        window.__rdrPagerError = String(err && err.message || err || 'teardown');
        window.__rdrPagerFailed = true;
        try { if (window.Android && Android.onPagerError) Android.onPagerError(window.__rdrPagerError); } catch (e) { }
        try {
            if (strip && strip.parentNode) strip.parentNode.removeChild(strip);
            if (source && chap) {
                while (source.firstChild) chap.appendChild(source.firstChild);
                if (source.parentNode) source.parentNode.removeChild(source);
            }
            if (document.documentElement) document.documentElement.classList.remove('rdr-paged');
            // Let in-book '#frag' taps fall back to native fragment
            // scrolling in the restored flow document.
            window.rdrGoToAnchor = undefined;
        } catch (e) { }
        pages = [];
        building = false;
        window.__rdrPagerBuilding = false;
        ready = false;
    }

    function paginate() {
        if (!chap || building || tornDown) return;
        var anchor = ready ? currentAnchor() : { li: 0, tok: 0 };
        var progressive = !ready;
        building = true;
        window.__rdrPagerBuilding = true;
        // New generation: any slices still queued from a superseded
        // build die on their generation check instead of finishing
        // into (or tearing down) this document.
        buildSeq++;
        lastW = stageW(); lastH = stageH();
        // Remember the build size so the completion can rebuild once
        // for the current size instead of leaving pages built for a
        // stale viewport (a resize mid-build restarts the build
        // outright via the resize handler below; this is the belt
        // to its braces for changes that arrive between slices).
        var buildW = stageW(), buildH = stageH();
        var buildRetried = false;
        function onBuilt(earlyRestored) {
            try {
                if (stageW() <= 0 || stageH() <= 0) throw new Error('zero-size stage');
                if (!pages.length && source && source.childNodes.length) {
                    throw new Error('no pages from non-empty source');
                }
                if (pages.length && stageH() > 0) {
                    // A collapsed stage (old WebView dropping the stage
                    // offsets) can leave JS geometry "valid" while the pages
                    // render a few px tall: garbage that throws nothing.
                    // A transient stage glitch must not kill the chapter
                    // outright: rebuild once with fresh geometry, and
                    // only tear down if it repeats.
                    var pr = pages[0].el.getBoundingClientRect();
                    if (pr && pr.height < stageH() * 0.5) throw new Error('page height collapse');
                }
                window.__rdrPagerError = null;
            } catch (e) {
                // Zero-size stage at completion is the same family: the
                // build measured a sane stage while slicing, but the
                // window reads zero now (a resize racing the debounced
                // abort, a detached WebView finishing a build). Tearing
                // down on a transient zero is wrong: rebuild once, and
                // let the stage guard refuse (then wait, then tear
                // down honestly) if the stage is really gone.
                var emsg = String(e && e.message || e);
                if ((emsg === 'page height collapse' || emsg === 'zero-size stage') && !buildRetried) {
                    buildRetried = true;
                    buildPages(onBuilt, false);
                    return;
                }
                teardown(e);
                return;
            }
                building = false;
                window.__rdrPagerBuilding = false;
                ready = true;
                window.__rdrPagerReady = true;
                if (earlyRestored) {
                    // The restore already landed on its final page
                    // during the build; just refresh the transform for
                    // the final page count, never jump back to page 0.
                    show(cur, true);
                } else if (pendingAnchor) {
                    var pa = pendingAnchor;
                    pendingAnchor = null;
                    pendingLast = false;
                    if (!goToAnchorNow(pa)) show(pageForAnchor(anchor), true);
                } else if (pendingLast) {
                    pendingAnchor = null;
                    pendingLast = false;
                    show(pages.length - 1, true);
                } else if (pendingFrag) {
                    // Queued text-point restore (native, e.g. reopen or a
                    // settings reload): an explicit link/last-page jump above
                    // wins over it, and it is consumed here so no later
                    // repagination re-applies it.
                    var pf = pendingFrag;
                    pendingFrag = null;
                    var pp = String(pf).split(':');
                    var pli = parseInt(pp[0], 10), ptok = parseInt(pp[1], 10);
                    if (!isNaN(pli) && !isNaN(ptok)) show(pageForAnchor({ li: pli, tok: ptok }), true);
                    else show(pageForAnchor(anchor), true);
                } else {
                    show(pageForAnchor(anchor), true);
                }
            watchLateImages();
            // After the watch: backdrops must never be watched as late
            // images (they load from cache and would only cause extra
            // repagination cycles), so marking runs last.
            markImagePages();
            if (Math.abs(stageW() - buildW) >= 2 || Math.abs(stageH() - buildH) >= 2) {
                scheduleRepaginate();
            }
        }
        try {
            if (window.__rdrPagerTestFail) throw new Error('test-forced failure');
            buildPages(onBuilt, progressive);
        } catch (e) {
            teardown(e);
            return;
        }
        // NOTE: building stays true until the sliced build completes
        // (the callback above); the old code set building=false here,
        // which was only correct because buildPages was synchronous.
    }

    // Full-bleed image pages: a page whose only visual content is one
    // image (possibly wrapped once in a div/figure/p, or an SVG) gets
    // .rdr-imgpage (see pagedCss): padding drops, the image fills the
    // content box, and a blurred cover copy of the same image is laid
    // behind it so the aspect-mismatch remainder is ambience, not
    // black bars. Book-markup vertical centering (flex wrappers,
    // full-height SVGs) is neutralized by the CSS forcing the wrapper
    // to full height with no margins. Tall (scrolling) pages are left
    // alone, and text pages with inline pictures are never marked:
    // the rule requires exactly one visual element. Runs after every
    // build (including repaginations); the next rebuild wipes the
    // strip, so backdrops can never be measured as content.
    function singleVisual(el) {
        var n = el, depth = 0;
        while (n && depth < 4) {
            if (n.nodeType === 1 && (n.tagName === 'IMG' || n.tagName === 'SVG')) return n;
            var kids = [], i, c;
            for (i = 0; i < n.childNodes.length; i++) {
                c = n.childNodes[i];
                if (c.nodeType === 1) kids.push(c);
                else if (c.nodeType === 3 && /\S/.test(c.nodeValue)) return null;
            }
            if (kids.length !== 1) return null;
            n = kids[0];
            depth++;
        }
        return null;
    }
    function markImagePages() {
        var i, pg, vis, bg, clone, j, ch, kids;
        for (i = 0; i < pages.length; i++) {
            pg = pages[i].el;
            if (pg.className.indexOf('rdr-tall') >= 0) continue;
            kids = [];
            for (j = 0; j < pg.childNodes.length; j++) {
                ch = pg.childNodes[j];
                if (ch.nodeType === 1) kids.push(ch);
            }
            if (kids.length !== 1) continue;
            vis = singleVisual(kids[0]);
            if (!vis) continue;
            pg.classList.add('rdr-imgpage');
            bg = document.createElement('div');
            bg.className = 'rdr-imgbg';
            clone = vis.cloneNode(true);
            if (clone.tagName === 'SVG') {
                try { clone.setAttribute('preserveAspectRatio', 'xMidYMid slice'); } catch (e) { }
            }
            bg.appendChild(clone);
            pg.insertBefore(bg, pg.firstChild);
        }
    }

    function scheduleRepaginate() {
        if (!ready || building) return;
        if (repagTimer) clearTimeout(repagTimer);
        repagTimer = setTimeout(function () {
            repagTimer = 0;
            paginate();
        }, 120);
    }

    // Images cloned into pages load independently of the (already loaded)
    // source images; if one arrives late and changes a page's layout, the
    // heights measured earlier may be stale — rebuild (bounded).
    function watchLateImages() {
        if (autoRepag >= MAX_AUTO_REPAGINATE) return;
        var imgs = strip.querySelectorAll('img'), i, pending = false;
        for (i = 0; i < imgs.length; i++) {
            if (!imgs[i].complete) {
                pending = true;
                imgs[i].addEventListener('load', onLateSettle);
                imgs[i].addEventListener('error', onLateSettle);
            }
        }
        function onLateSettle() {
            if (autoRepag >= MAX_AUTO_REPAGINATE) return;
            autoRepag++;
            scheduleRepaginate();
        }
        return pending;
    }

    // ------------------------------------------------------------------
    // Public API (same names/contract as the multicol version).
    // ------------------------------------------------------------------
    window.rdrTurn = function (d) {
        try {
            // Torn down beats busy: a torn pager is NEVER going to
            // become ready, and answering 'busy' made native code
            // retry it, reload it, and then go silent (the "no turn
            // at all" symptom). 'torn' lets native fall back to
            // scrolling the restored flow document instead.
            if (tornDown) return 'torn';
            if (building) {
                // Progressive build: pages already built are real and
                // turnable; only a turn past the built frontier waits
                // (native retries it while building, as before). A
                // hidden-strip (repagination) build is not turnable:
                // its pages are not on screen, and a turn there would
                // just be overridden by the completion restore.
                if (!pages.length) return 'busy';
                if (strip && strip.style.visibility === 'hidden') return 'busy';
                if (Date.now() < lockUntil) return 'busy';
                var nb = cur + d;
                if (nb < 0) return 'start';
                if (nb >= pages.length) return 'busy';
                show(nb, false);
                return 'page';
            }
            if (!ready) return 'busy';
            if (Date.now() < lockUntil) return 'busy';
            var n = cur + d;
            if (n < 0) return 'start';
            if (n >= pages.length) return 'end';
            show(n, false);
            return 'page';
        } catch (e) { return 'page'; }
    };
    window.rdrLast = function () {
        try {
            if (!ready) { pendingLast = true; return 'ok'; }
            show(pages.length - 1, true);
            return 'ok';
        } catch (e) { return 'no'; }
    };
    window.rdrGoToPage = function (i) {
        if (building) {
            if (i < 0 || i >= pages.length) return -1;
            show(i, true);
            return cur;
        }
        if (!ready) return -1;
        show(i, true);
        return cur;
    };
    // In-book link navigation: jump to the page containing the element
    // with the given id (or <a name>). Pre-ready calls are queued and
    // applied when the build finishes (cross-chapter link landings).
    function findAnchor(id) {
        var i, j, all, el;
        for (i = 0; i < pages.length; i++) {
            all = pages[i].el.getElementsByTagName('*');
            for (j = 0; j < all.length; j++) {
                el = all[j];
                if (el.id === id) return { page: i, el: el };
                if (el.tagName === 'A' && el.getAttribute('name') === id) return { page: i, el: el };
            }
        }
        return null;
    }
    function goToAnchorNow(id) {
        var hit = findAnchor(id);
        if (!hit) return false;
        show(hit.page, true);
        // Target inside an internally-scrolling tall page: scroll the
        // wrap so the target is visible.
        var n = hit.el, wrap = null;
        while (n && n !== strip) {
            if (n.className && (' ' + n.className + ' ').indexOf(' rdr-tallwrap ') >= 0) { wrap = n; break; }
            n = n.parentNode;
        }
        if (wrap) {
            try {
                var dy = hit.el.getBoundingClientRect().top - wrap.getBoundingClientRect().top;
                wrap.scrollTop += dy - 8;
            } catch (e) { }
        }
        return true;
    }
    window.rdrGoToAnchor = function (id) {
        try {
            if (!id) return false;
            if (!ready || building) { pendingAnchor = String(id); return false; }
            return goToAnchorNow(String(id));
        } catch (e) { return false; }
    };
    window.rdrPageCount = function () { return pages.length; };
    window.rdrCurrentPage = function () { return cur; };
    // Readiness, distinct from rdrTurn's 'busy': native code must NOT
    // reload a chapter that is merely still building (a slow chapter
    // with late images can stay building past the turn-retry window),
    // and the UI wants a "preparing" state instead of a blank pill.
    // 'ready' | 'building' | 'torn' — never 'busy' (that conflates
    // building with the short animation lock).
    window.rdrPagerState = function () {
        try {
            if (tornDown) return 'torn';
            if (!ready || building) return 'building';
            return 'ready';
        } catch (e) { return 'building'; }
    };
    // Text-point anchor of the current page, in the same li:tok units
    // repagination already restores within a session (currentAnchor /
    // pageForAnchor): the first fragment of the current page. Native
    // code persists it per chapter, so reopening a book (or reloading
    // the same chapter after a settings change) lands on the same
    // text point instead of the chapter start — the ReadEra XPath
    // idea, in the pager's own coordinates. Stable across
    // repagination because tokenization does not depend on layout.
    window.rdrCurrentAnchor = function () {
        try {
            if (!ready || !pages.length) return '';
            var a = currentAnchor();
            return a.li + ':' + a.tok;
        } catch (e) { return ''; }
    };
    window.rdrGoToAnchorFrag = function (s) {
        try {
            if (!s) return false;
            var parts = String(s).split(':');
            var li = parseInt(parts[0], 10), tok = parseInt(parts[1], 10);
            if (isNaN(li) || isNaN(tok)) return false;
            // Not ready yet (the usual case at onPageFinished, when
            // the pager is still waiting for fonts/images): queue it
            // for paginate() instead of dropping it or jumping to a
            // half-built strip.
            if (!ready || building) { pendingFrag = String(s); return true; }
            show(pageForAnchor({ li: li, tok: tok }), true);
            return true;
        } catch (e) { return false; }
    };
    window.rdrPaginate = function () { scheduleRepaginate(); };

    // Tap zones (paged mode): tapping the outer quarters of the page
    // turns it — the bottom-bar chapter buttons are hidden in paged
    // mode, so this (plus swipe/drag) is how pages turn. The middle
    // half does nothing, so ordinary text taps never turn a page.
    // Links are excluded: LINK_JS (capture phase) owns them, and this
    // bubble-phase handler returns early for any tap inside an <a>.
    // At a chapter edge rdrTurn answers 'end'/'start'; those go to
    // native code via Android.onPageEdge so the chapter advances
    // exactly like a swipe turn does. A 'busy' answer (pagination
    // still building, or mid-animation) is retried like native
    // turnPage does instead of being dropped.
    document.addEventListener('click', function (e) {
        var t = e.target;
        if (t && t.nodeType !== 1) t = t.parentElement;
        if (t && t.closest && t.closest('a[href]')) return;
        var w = window.innerWidth || 0;
        if (!w) return;
        var x = e.clientX || 0;
        var fw = rtl ? (x < w * 0.25) : (x > w * 0.75);
        var bk = rtl ? (x > w * 0.75) : (x < w * 0.25);
        if (!fw && !bk) return;
        var d = fw ? 1 : -1;
        (function tryTurn(n) {
            var r = null;
            try { r = window.rdrTurn(d); } catch (err) {
                // rdrTurn missing: the live page was built without the
                // pager. Same native recovery as a stuck pager below.
                try { if (window.Android && Android.onPagerStuck) Android.onPagerStuck(); } catch (e2) { }
                return;
            }
            if (r === 'busy' && (n < 10 || (window.__rdrPagerBuilding && n < 30))) {
                setTimeout(function () { tryTurn(n + 1); }, 300);
            } else if (r === 'busy' && window.__rdrPagerBuilding) {
                // Still building after ~9s: drop this tap, never reload
                // a building pager — it may still become ready, and the
                // user can tap again. Only a non-building 'busy' goes
                // to native recovery.
            } else if (r === 'busy') {
                // Pagination never became ready (torn down): retries
                // alone can never turn it — hand it to native recovery.
                try { if (window.Android && Android.onPagerStuck) Android.onPagerStuck(); } catch (err) { }
            } else if (r === 'end') {
                try { if (window.Android && Android.onPageEdge) Android.onPageEdge(1); } catch (err) { }
            } else if (r === 'start') {
                try { if (window.Android && Android.onPageEdge) Android.onPageEdge(-1); } catch (err) { }
            }
        })(0);
    });

    // ------------------------------------------------------------------
    // Boot: park the original chapter content in .rdr-source, create the
    // strip, wait for fonts + images to settle, then paginate.
    // ------------------------------------------------------------------
    function boot() {
        chap = document.querySelector('.rdr-chap');
        if (!chap) return;
        source = document.createElement('div');
        source.className = 'rdr-source';
        source.style.display = 'none';
        while (chap.firstChild) source.appendChild(chap.firstChild);
        chap.appendChild(source);
        strip = document.createElement('div');
        strip.className = 'rdr-strip';
        chap.appendChild(strip);

        var done = false;
        function finish() {
            if (done) return;
            done = true;
            paginate();
        }
        var imgs = source.querySelectorAll('img'), i, waiting = 0;
        for (i = 0; i < imgs.length; i++) {
            if (!imgs[i].complete) {
                waiting++;
                imgs[i].addEventListener('load', one);
                imgs[i].addEventListener('error', one);
            }
        }
        function one() { if (--waiting <= 0 && fontsDone) finish(); }
        var fontsDone = false;
        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(function () {
                fontsDone = true;
                if (waiting <= 0) finish();
            });
        } else {
            fontsDone = true;
        }
        if (waiting <= 0 && fontsDone) finish();
        setTimeout(finish, SETTLE_TIMEOUT);
        if (document.fonts && document.fonts.addEventListener) {
            document.fonts.addEventListener('loadingdone', function () {
                if (ready && autoRepag < MAX_AUTO_REPAGINATE) {
                    autoRepag++;
                    scheduleRepaginate();
                }
            });
        }
    }

    var lastW = 0, lastH = 0, resizeTimer = 0;
    window.addEventListener('resize', function () {
        if (tornDown) return;
        if (resizeTimer) clearTimeout(resizeTimer);
        resizeTimer = setTimeout(function () {
            resizeTimer = 0;
            if (!chap || tornDown) return;
            stageResizes++;
            stageNote();
            if (Math.abs(stageW() - lastW) >= 2 || Math.abs(stageH() - lastH) >= 2) {
                lastW = stageW(); lastH = stageH();
                if (building) {
                    // The stage moved mid-build (the classic reader-open
                    // case: the WebView grows while the first build is
                    // still slicing). The old code ignored resizes
                    // until ready, so the build ground on with stale
                    // geometry and its garbage pages stuck. Abort it
                    // and restart for the new size instead.
                    buildSeq++;
                    building = false;
                    window.__rdrPagerBuilding = false;
                    clearStageWait();
                }
                paginate();
            }
        }, 180);
    });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            lastW = 0; boot();
            lastW = stageW(); lastH = stageH();
        });
    } else {
        boot();
        lastW = stageW(); lastH = stageH();
    }
})();"""
    /**
     * In-book link interception (ALL builds) — GENERATED, DO NOT REINDENT.
     * Byte-identical to the links.js verified in headless Chromium
     * (68 link checks together with the pager suite). Intercepts clicks
     * on <a href>: '#frag' jumps via the pager in paged mode (native
     * fragment scroll in vertical mode), relative file hrefs are handed
     * to Android.onLink for chapter resolution, external schemes are
     * swallowed (the reader is offline; the WebView must never navigate
     * itself out of the reader shell).
     */
    private const val LINK_JS = """// Novelfa in-book link interception — injected into EVERY reader document
// (paged and vertical). EPUB chapters link to footnotes/endnotes and to
// other chapter files; the WebView must never navigate itself (it would
// leave the reader shell and load a raw XHTML file). Instead:
//   - "#frag"        -> paged: rdrGoToAnchor jumps to the target's page;
//                       vertical: native fragment scroll (left untouched).
//   - "file.xhtml#f" -> Android.onLink(rawHref); the app resolves it
//                       against the current chapter folder, opens that
//                       chapter, then lands on the fragment.
//   - http/mailto/…  -> swallowed (the reader is fully offline).
(function () {
    document.addEventListener('click', function (e) {
        var t = e.target;
        if (t && t.nodeType !== 1) t = t.parentElement;
        if (!t || !t.closest) return;
        var a = t.closest('a[href]');
        if (!a) return;
        var raw = a.getAttribute('href');
        if (raw == null) return;
        var h = raw.trim();
        if (!h) { e.preventDefault(); return; }
        if (h.charAt(0) === '#') {
            if (window.rdrGoToAnchor) {
                e.preventDefault();
                var id = h.substring(1);
                try { id = decodeURIComponent(id); } catch (err) { }
                if (id) window.rdrGoToAnchor(id);
            }
            return;
        }
        var low = h.toLowerCase();
        if (low.indexOf('http:') === 0 || low.indexOf('https:') === 0 ||
            low.indexOf('mailto:') === 0 || low.indexOf('tel:') === 0 ||
            low.indexOf('javascript:') === 0 || low.indexOf('data:') === 0) {
            e.preventDefault();
            return;
        }
        e.preventDefault();
        // The href is relative to the chapter CONTAINING the link —
        // in continuous mode several chapters share one document, so
        // tell the app exactly which chapter this link lives in.
        var ci = -1;
        var cd = a.closest ? a.closest('.rdr-chap') : null;
        if (cd) {
            var dv = parseInt(cd.getAttribute('data-idx'), 10);
            if (!isNaN(dv)) ci = dv;
        }
        try { if (window.Android && Android.onLink) Android.onLink(h, ci); } catch (err) { }
    }, true);
})();"""
}
