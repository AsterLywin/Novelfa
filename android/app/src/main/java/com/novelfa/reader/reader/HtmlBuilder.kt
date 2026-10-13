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
 * to `Android.onScroll(t,h,c)` via a JavascriptInterface, including the
 * lr-restore `z()` used after prepend.
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
        docGen: Int
    ): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><base href=\"")
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
        sb.append("body * { color:inherit !important; }")
        sb.append("[data-rdr=\"l\"]{direction:ltr;text-align:left;}")
        sb.append("[data-rdr=\"r\"]{direction:rtl;text-align:right;}")
        sb.append("img{max-width:95% !important;width:auto !important;height:auto !important;")
        sb.append("display:block;margin:12px auto;}</style>")
        sb.append("<style id=\"rdrFontFace\">").append(fontFaceCss).append("</style>")
        sb.append("<style id=\"readerSpacing\">")
            .append(spacingCss(lineHeight, sideMargin, paragraphSpacing)).append("</style>")
        sb.append("<script>window.__rdrGen=").append(docGen).append(";</script>")
        sb.append("<script>(function(){")
        sb.append("var lt=-1,lh=-1,lc=-1,lr=-1;")
        sb.append("function r(){try{var d=document.documentElement,b=document.body;")
        sb.append("var t=Math.round(Math.max(d.scrollTop||0,b.scrollTop||0)),")
        sb.append("h=Math.max(d.scrollHeight||0,b.scrollHeight||0),")
        sb.append("c=d.clientHeight||b.clientHeight||0;")
        sb.append("if(t!=lt||h!=lh||c!=lc){lt=t;lh=h;lc=c;")
        sb.append("try{Android.onScroll(t,h,c);}catch(x){}}")
        sb.append("else if(h>c){lr=t/(h-c);}}catch(x){}}")
        sb.append("function z(){try{if(lr>=0){var d=document.documentElement,b=document.body;")
        sb.append("var h=Math.max(d.scrollHeight||0,b.scrollHeight||0),")
        sb.append("c=d.clientHeight||b.clientHeight||0;")
        sb.append("var nt=Math.round(lr*Math.max(h-c,0));d.scrollTop=nt;b.scrollTop=nt;}}catch(x){}r();}")
        sb.append("window.addEventListener('scroll',r);window.addEventListener('resize',z);setInterval(r,200);")
        sb.append("})();</script></head><body>").append(bodyContent).append("</body></html>")
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
                .append(";src:url(data:").append(mime).append(";base64,")
                .append(Base64.getEncoder().encodeToString(bytes)).append(");}")
        }
        return sb.toString()
    }

    /** Port of the AppendNextChapter insert script (docGen-guarded). */
    fun appendJs(docGen: Int, chunk: String): String =
        "(function(){if(window.__rdrGen!==$docGen)return 0;" +
            "document.body.insertAdjacentHTML('beforeend', '" +
            HtmlText.escapeForJs(chunk) + "');return 1;})();"

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
