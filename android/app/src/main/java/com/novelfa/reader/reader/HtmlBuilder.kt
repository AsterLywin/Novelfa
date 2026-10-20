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
        sb.append(cssQuote(fontFamily)).append("',Tahoma,sans-serif;font-size:").append(fontSize)
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
//   rdrTurn(d)  -> 'page' | 'start' | 'end' | 'busy'
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
    var MAX_AUTO_REPAGINATE = 3;

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
    // Page construction.
    // ------------------------------------------------------------------
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
        if (iw && (!w || Math.abs(w - iw) > 2)) return iw;
        return w || iw;
    }
    function stageH() {
        var h = chap ? chap.clientHeight : 0;
        var ih = window.innerHeight || 0;
        if (ih && (!h || Math.abs(h - ih) > 2)) return ih;
        return h || ih;
    }

    function buildPages() {
        var Wpx = stageW(), Hpx = stageH();
        strip.innerHTML = '';
        pages = [];
        strip.style.visibility = 'hidden';

        // Pages MUST carry their final width from the start: an
        // absolutely-positioned page without a width shrink-wraps, and
        // every measurement taken inside it would be garbage.
        function mkPage() {
            var p = newPageEl();
            p.style.width = Wpx + 'px';
            p.style.left = '0px';
            return p;
        }

        var probe = mkPage();
        strip.appendChild(probe);
        var pcs = window.getComputedStyle(probe);
        var padT = parseFloat(pcs.paddingTop) || 0;
        var padB = parseFloat(pcs.paddingBottom) || 0;
        var contentH = Hpx - padT - padB;

        var leaves = flatten(source, [], []);
        var cur1 = { el: probe, frags: [] };

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
            } else if (probe.parentNode === strip) {
                // Empty construction page (e.g. finalize called twice in a
                // row around a tall page): discard it — a stray .rdr-page
                // left in the strip would corrupt the page geometry.
                strip.removeChild(probe);
            }
            probe = mkPage();
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

        // Split a text leaf across pages. The first fragment may use the
        // current page's remaining space; later fragments get full pages.
        // A fragment fits iff the probe's used height (which includes the
        // fragment, appended after the items already on the page) stays
        // within contentH — the same measure the packer uses for whole
        // blocks, so a kept fragment can never overflow its real page.
        // Consumes the leaf completely.
        function splitLeaf(chain, leaf, li, availH) {
            var tokens = tokenize(leaf);
            if (!tokens.length) return;
            var from = 0, first = true, fragIndex = 0;
            var lineH = 0;
            try {
                lineH = parseFloat(window.getComputedStyle(leaf).lineHeight) || 0;
            } catch (e) { }
            if (!lineH) lineH = 24;
            while (from < tokens.length) {
                var avail = first ? availH : contentH;
                if (avail < lineH) { finalizePage(); avail = contentH; }
                var hi = tokens.length, best = from, trial = null;
                function measure(end) {
                    if (trial) { probe.removeChild(trial); trial = null; }
                    var f = buildFragment(leaf, tokens, from, end);
                    if (fragIndex > 0) f.style.marginTop = '0px';
                    if (end < tokens.length) f.style.marginBottom = '0px';
                    var w = wrapFragment(chain, f);
                    tagFrag(w, li, from, end);
                    probe.appendChild(w);
                    trial = w;
                    return usedH() <= contentH + EPS;
                }
                if (measure(hi)) {
                    best = hi;
                } else {
                    var a = from, b = hi;
                    while (a < b) {
                        var mid = (a + b + 1) >> 1;
                        if (measure(mid)) a = mid; else b = mid - 1;
                    }
                    best = a;
                    if (best > from) measure(best); // leave the best trial in the probe
                }
                if (best === from) {
                    if (trial) { probe.removeChild(trial); trial = null; }
                    if (usedH() > 0) {
                        // Not even one token fits in THIS page's remaining
                        // space (margin collapsing can eat more room than
                        // the line-height estimate suggested) — that is
                        // normal: finish the page and retry on a fresh one
                        // before concluding the token is oversize.
                        finalizePage();
                        first = false;
                        continue;
                    }
                    // Even on an empty page one token does not fit: force
                    // it onto its own tall page rather than losing it or
                    // looping forever.
                    var one = buildFragment(leaf, tokens, from, from + 1);
                    if (fragIndex > 0) one.style.marginTop = '0px';
                    if (from + 1 < tokens.length) one.style.marginBottom = '0px';
                    emitTallPage(chain, one, li, from, from + 1);
                    from += 1;
                    fragIndex++;
                    first = false;
                    continue;
                }
                // The trial in the probe is exactly the chosen fragment.
                cur1.frags.push(trial);
                trial = null;
                from = best;
                fragIndex++;
                first = false;
                if (from < tokens.length) finalizePage();
            }
        }

        var li;
        for (li = 0; li < leaves.length; li++) {
            var chain = leaves[li].chain, leaf = leaves[li].leaf;
            if (tryWhole(chain, leaf, li)) continue;
            var avail = contentH - usedH();
            var lineH = 0;
            try { lineH = parseFloat(window.getComputedStyle(leaf).lineHeight) || 0; } catch (e) { }
            if (isSplittable(leaf) && (cur1.frags.length === 0 || avail >= (lineH || 24))) {
                splitLeaf(chain, leaf, li, avail);
            } else if (cur1.frags.length) {
                finalizePage();
                if (!tryWhole(chain, leaf, li)) {
                    if (isSplittable(leaf)) splitLeaf(chain, leaf, li, contentH);
                    else emitTallPage(chain, leaf, li);
                }
            } else {
                emitTallPage(chain, leaf, li);
            }
        }
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
    }

    // ------------------------------------------------------------------
    // Navigation.
    // ------------------------------------------------------------------
    function report() {
        try {
            if (window.Android && Android.onScroll) {
                var h = stageH();
                Android.onScroll(cur * h, pages.length * h, h, window.__rdrGen || 0);
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
        building = true;
        window.__rdrPagerBuilding = true;
        try {
            if (window.__rdrPagerTestFail) throw new Error('test-forced failure');
            buildPages();
            if (stageW() <= 0 || stageH() <= 0) throw new Error('zero-size stage');
            if (!pages.length && source && source.childNodes.length) {
                throw new Error('no pages from non-empty source');
            }
            if (pages.length && stageH() > 0) {
                // A collapsed stage (old WebView dropping the stage
                // offsets) can leave JS geometry "valid" while the pages
                // render a few px tall: garbage that throws nothing.
                // Detect it visually and tear down to the readable flow.
                var pr = pages[0].el.getBoundingClientRect();
                if (pr && pr.height < stageH() * 0.5) throw new Error('page height collapse');
            }
            window.__rdrPagerError = null;
        } catch (e) {
            teardown(e);
            return;
        }
        building = false;
        window.__rdrPagerBuilding = false;
        ready = true;
        window.__rdrPagerReady = true;
        if (pendingAnchor) {
            var pa = pendingAnchor;
            pendingAnchor = null;
            pendingLast = false;
            if (!goToAnchorNow(pa)) show(pageForAnchor(anchor), true);
        } else if (pendingLast) {
            pendingLast = false;
            show(pages.length - 1, true);
        } else {
            show(pageForAnchor(anchor), true);
        }
        watchLateImages();
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
            if (!ready || building) return 'busy';
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
    window.rdrPageCount = function () { return ready ? pages.length : 0; };
    window.rdrCurrentPage = function () { return ready ? cur : 0; };
    window.rdrPaginate = function () { scheduleRepaginate(); };

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
        if (!ready) return;
        if (resizeTimer) clearTimeout(resizeTimer);
        resizeTimer = setTimeout(function () {
            resizeTimer = 0;
            if (!chap) return;
            if (Math.abs(stageW() - lastW) >= 2 || Math.abs(stageH() - lastH) >= 2) {
                lastW = stageW(); lastH = stageH();
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
