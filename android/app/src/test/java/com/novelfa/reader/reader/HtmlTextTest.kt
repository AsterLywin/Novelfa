package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtmlTextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun extractBody_withBodyTags_returnsInner() {
        val html = "<html><head><title>t</title></head><body class=\"x\"><p>hi</p></body></html>"
        assertEquals("<p>hi</p>", HtmlText.extractBody(html))
    }

    @Test
    fun extractBody_withoutBodyTags_returnsWhole() {
        val html = "<p>no body here</p>"
        assertEquals(html, HtmlText.extractBody(html))
    }

    @Test
    fun stripScripts_removesScriptKeepsRest() {
        val html = "<p>keep</p><script>alert(1)</script><SCRIPT src=\"x.js\">var a=1;</SCRIPT><p>also</p>"
        val got = HtmlText.stripScripts(html)
        assertFalse(got.contains("alert"))
        assertFalse(got.contains("<script"))
        assertTrue(got.contains("keep"))
        assertTrue(got.contains("also"))
    }

    @Test
    fun convertSvgImages_svgImageToImg_xlink() {
        val html = "<svg xmlns=\"http://www.w3.org/2000/svg\"><image xlink:href=\"cover.png\" /></svg>"
        assertEquals("<img src=\"cover.png\" />", HtmlText.convertSvgImages(html))
    }

    @Test
    fun convertSvgImages_svgImageToImg_plainHref() {
        val html = "<svg><g><image href=\"a/b.jpg\"></image></g></svg>"
        assertEquals("<img src=\"a/b.jpg\" />", HtmlText.convertSvgImages(html))
    }

    @Test
    fun stripSizeAttributes_removesWidthHeightFromImg() {
        val html = "<img src=\"a.png\" width=\"100\" height='50' alt=\"x\">"
        val got = HtmlText.stripSizeAttributes(html)
        assertFalse(got.contains("width"))
        assertFalse(got.contains("height"))
        assertTrue(got.contains("src=\"a.png\""))
        assertTrue(got.contains("alt=\"x\""))
    }

    @Test
    fun stripSizeAttributes_keepsSvgTags() {
        val html = "<svg width=\"100\" height=\"50\"><rect width=\"10\" height=\"5\"/></svg>"
        assertEquals(html, HtmlText.stripSizeAttributes(html))
    }

    @Test
    fun makeImageSourcesAbsolute_relativeBecomesFileUri() {
        val folder = tmp.newFolder("chap1")
        val got = HtmlText.makeImageSourcesAbsolute("<img src=\"pic.png\">", folder)
        assertTrue(got.contains("file:"))
        assertTrue(got.contains("pic.png"))
    }

    @Test
    fun makeImageSourcesAbsolute_absoluteLikeSrcs_untouched() {
        val folder = tmp.newFolder("chap2")
        val html = "<img src=\"http://x/y.png\"><img src=\"https://x/y.png\">" +
            "<img src=\"data:image/png;base64,xx\"><img src=\"file:///c/y.png\">" +
            "<img src=\"//cdn/x.png\"><img src=\"about:blank\"><img src=\"\">"
        assertEquals(html, HtmlText.makeImageSourcesAbsolute(html, folder))
    }

    @Test
    fun htmlToPlainText_stripsTagsAndDecodes() {
        val got = HtmlText.htmlToPlainText("<p>سلام &amp; hi</p><style>p{}</style>")
        assertTrue(got.contains("سلام"))
        assertTrue(got.contains("&"))
        assertFalse(got.contains("<p>"))
    }

    @Test
    fun prepareChapterBody_fullPipeline() {
        val folder = tmp.newFolder("chap3")
        val html = "<html><body><script>evil()</script><p width=\"99\">سلام <img src=\"i.png\"></p></body></html>"
        val got = HtmlText.prepareChapterBody(html, folder)
        assertFalse(got.contains("evil"))
        assertFalse(got.contains("width"))
        assertTrue(got.contains("سلام"))
        assertTrue(got.contains("file:"))
    }

    // --- Render-pipeline hardening (ported from AI Studio defect pass) ---

    @Test
    fun stripScripts_keepsDataImageSrc_blocksDataHref() {
        val html = "<img src=\"data:image/png;base64,xx\">" +
            "<a href=\"data:text/html;base64,evil\">x</a>" +
            "<img src=\"javascript:evil()\">"
        val got = HtmlText.stripScripts(html)
        assertTrue(got.contains("data:image/png;base64,xx"))
        assertFalse(got.contains("data:text/html"))
        assertFalse(got.contains("javascript:"))
    }

    @Test
    fun stripScripts_removesNoscriptAndInlineHandlers() {
        val html = "<p onclick=\"evil()\">hi</p><noscript>no</noscript><img src=\"a.png\" onload='x()'>"
        val got = HtmlText.stripScripts(html)
        assertFalse(got.contains("evil"))
        assertFalse(got.contains("noscript"))
        assertFalse(got.contains("onload"))
        assertFalse(got.contains("onclick"))
        assertTrue(got.contains("hi"))
        assertTrue(got.contains("src=\"a.png\""))
    }

    @Test
    fun convertSvgImages_standaloneSvg_responsive() {
        val got = HtmlText.convertSvgImages("<svg width=\"600\" height=\"400\"><rect/></svg>")
        assertTrue(got.contains("max-width:100%"))
        assertTrue(got.contains("<rect/>"))
    }

    @Test
    fun makeImageSourcesAbsolute_srcsetResolved() {
        val folder = tmp.newFolder("chap4")
        val got = HtmlText.makeImageSourcesAbsolute(
            "<img src=\"a.png\" srcset=\"a.png 1x, big/b.png 2x\">", folder
        )
        assertFalse(got.contains("\"a.png 1x\""))
        assertTrue(got.contains("file:"))
        assertTrue(got.contains("2x"))
    }

    @Test
    fun markFootnoteLinks_fnFragment_marks() {
        val got = HtmlText.markFootnoteLinks("<p>متن <sup><a href=\"#fn1\">1</a></sup></p>")
        assertTrue(got.contains("data-novelfa-footnote"))
    }

    @Test
    fun markFootnoteLinks_fileHrefFootnote_marks() {
        val got = HtmlText.markFootnoteLinks("<p><a href=\"notes.xhtml#footnote-2\">2</a></p>")
        assertTrue(got.contains("data-novelfa-footnote"))
    }

    @Test
    fun markFootnoteLinks_chapterPrefixedFn_marks() {
        val got = HtmlText.markFootnoteLinks("<p><a href=\"#ch1-fn3\">3</a></p>")
        assertTrue(got.contains("data-novelfa-footnote"))
    }

    @Test
    fun markFootnoteLinks_noterefType_marks() {
        val got = HtmlText.markFootnoteLinks("<p><a epub:type=\"noteref\" href=\"#x1\">1</a></p>")
        assertTrue(got.contains("data-novelfa-footnote"))
    }

    @Test
    fun markFootnoteLinks_classFootnote_marks() {
        val got = HtmlText.markFootnoteLinks("<p><a class=\"footnote\" href=\"#r5\">5</a></p>")
        assertTrue(got.contains("data-novelfa-footnote"))
    }

    @Test
    fun markFootnoteLinks_plainAnchor_notMarked() {
        val got = HtmlText.markFootnoteLinks("<p><a href=\"#section-2\">بخش ۲</a></p>")
        assertFalse(got.contains("data-novelfa-footnote"))
    }

    @Test
    fun markFootnoteLinks_backLink_notMarked() {
        val got = HtmlText.markFootnoteLinks("<li><a href=\"#novelfa-fnref-1\">↩</a> متن</li>")
        assertFalse(got.contains("data-novelfa-footnote"))
    }

    @Test
    fun markFootnoteLinks_alreadyMarked_notDoubled() {
        val got = HtmlText.markFootnoteLinks(
            "<p><a href=\"#novelfa-fn-1\" data-novelfa-footnote=\"1\">[1]</a></p>"
        )
        assertEquals(1, Regex("data-novelfa-footnote").findAll(got).count())
    }

    @Test
    fun footnoteText_liWithBacklink_returnsTextWithoutArrow() {
        val html = "<ol><li id=\"novelfa-fn-1\">" +
            "<a href=\"#novelfa-fnref-1\">↩</a> متن پاورقی</li></ol>"
        assertEquals("متن پاورقی", HtmlText.footnoteText(html, "novelfa-fn-1"))
    }

    @Test
    fun footnoteText_pWithNestedTags_stripsTagsAndDecodes() {
        val html = "<p id=\"fn1\">متن <b>مهم</b> و &amp; چیز</p>"
        assertEquals("متن مهم و & چیز", HtmlText.footnoteText(html, "fn1"))
    }

    @Test
    fun footnoteText_missingId_returnsNull() {
        assertEquals(null, HtmlText.footnoteText("<p id=\"fn1\">x</p>", "fn2"))
    }

    @Test
    fun footnoteText_empty_returnsNull() {
        assertEquals(null, HtmlText.footnoteText("<p id=\"fn1\">   </p>", "fn1"))
    }

    @Test
    fun footnoteText_singleQuoteId_works() {
        assertEquals("متن", HtmlText.footnoteText("<p id='fn1'>متن</p>", "fn1"))
    }
}
