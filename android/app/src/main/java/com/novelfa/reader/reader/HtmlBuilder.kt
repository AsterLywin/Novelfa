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
 * lr-restore `z()` used after prepend. `rdrTurn(d)` drives horizontal page
 * turns in paged mode.
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
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
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
        sb.append("var lt=-1,lh=-1,lc=-1,lr=-1;")
        sb.append("function r(){try{var d=document.documentElement,b=document.body;")
        sb.append("var t=Math.round(Math.max(d.scrollTop||0,b.scrollTop||0)),")
        sb.append("h=Math.max(d.scrollHeight||0,b.scrollHeight||0),")
        sb.append("c=d.clientHeight||b.clientHeight||0;")
        sb.append("if(t!=lt||h!=lh||c!=lc){lt=t;lh=h;lc=c;")
        sb.append("try{Android.onScroll(t,h,c,window.__rdrGen);}catch(x){}}")
        sb.append("else if(h>c){lr=t/(h-c);}}catch(x){}}")
        sb.append("function z(){try{if(lr>=0){var d=document.documentElement,b=document.body;")
        sb.append("var h=Math.max(d.scrollHeight||0,b.scrollHeight||0),")
        sb.append("c=d.clientHeight||b.clientHeight||0;")
        sb.append("var nt=Math.round(lr*Math.max(h-c,0));d.scrollTop=nt;b.scrollTop=nt;}}catch(x){}r();}")
        sb.append("window.addEventListener('scroll',r);window.addEventListener('resize',z);setInterval(r,200);")
        // Horizontal page turning (paged mode). Returns 'page' when a page
        // turned, 'start'/'end' at the chapter edges so native code can move
        // to the adjacent chapter. RTL: scrollLeft runs 0 -> negative.
        sb.append("function rdrTurn(d){try{")
        sb.append("var now=Date.now();")
        // Animation lock: a second swipe mid-animation would compute the
        // current page from a mid-flight offset and jump to the wrong page.
        sb.append("if(window.__rdrLockT&&now<window.__rdrLockT)return 'busy';")
        sb.append("var de=document.documentElement;")
        sb.append("if(!de.classList.contains('rdr-paged'))return 'page';")
        sb.append("var w=de.clientWidth||window.innerWidth||1;")
        sb.append("var rtl=de.getAttribute('dir')==='rtl';")
        sb.append("var sl=window.pageXOffset||de.scrollLeft||0;")
        sb.append("var cur=rtl?Math.round(-sl/w):Math.round(sl/w);")
        sb.append("var count=Math.max(1,Math.round(de.scrollWidth/w));")
        sb.append("var n=cur+d;if(n<0)return 'start';if(n>=count)return 'end';")
        sb.append("window.__rdrLockT=now+450;")
        sb.append("window.scrollTo({left:rtl?-n*w:n*w,behavior:'smooth'});")
        sb.append("return 'page';}catch(x){return 'page';}}")
        // Jump to the last page (used when navigating to the previous
        // chapter: a backwards page-turn should continue from its end).
        sb.append("function rdrLast(){try{")
        sb.append("var de=document.documentElement;")
        sb.append("if(!de.classList.contains('rdr-paged'))return 'no';")
        sb.append("var w=de.clientWidth||window.innerWidth||1;")
        sb.append("var rtl=de.getAttribute('dir')==='rtl';")
        sb.append("var count=Math.max(1,Math.round(de.scrollWidth/w));")
        sb.append("var n=count-1;")
        sb.append("window.scrollTo({left:rtl?-n*w:n*w});")
        sb.append("return 'ok';}catch(x){return 'no';}}")
        sb.append("})();</script></head><body>").append(bodyContent).append("</body></html>")
        return sb.toString()
    }

    /**
     * Paged-mode overrides. Paging is fully programmatic (rdrTurn/rdrLast):
     * native horizontal scrolling is disabled (overflow-x:hidden) so a fling
     * can never fight the page-turn animation or land between pages.
     * Refreshed live by ReaderViewModel.refreshSpacing() when the margin
     * changes, so the margin must stay a parameter here.
     */
    fun pagedCss(sideMargin: Int): String {
        val sb = StringBuilder()
        sb.append("html.rdr-paged,html.rdr-paged body{height:100%;}")
        sb.append("html.rdr-paged{overflow-x:hidden;overflow-y:hidden;}")
        sb.append("html.rdr-paged body{margin:0 !important;padding:16px 0 !important;")
        sb.append("column-width:100vw;column-gap:0;box-sizing:border-box;")
        // Windows parity: chapter fades in on load (rdrFade .3s).
        sb.append("animation:rdrFade .3s ease-out;}")
        sb.append("@keyframes rdrFade{from{opacity:.3;}to{opacity:1;}}")
        sb.append("html.rdr-paged .rdr-chap{padding:0 ").append(sideMargin)
            .append("px;box-sizing:border-box;}")
        // Never split media across the page boundary (vertical-scroll
        // Windows never fragments, so this is paged-only parity).
        sb.append("html.rdr-paged img,html.rdr-paged table,html.rdr-paged pre,")
        sb.append("html.rdr-paged figure,html.rdr-paged blockquote{break-inside:avoid;}")
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
            "if(s.styleSheet){s.styleSheet.cssText='$esc';}else{s.innerHTML='$esc';}return 'ok';})();"
    }

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
}
