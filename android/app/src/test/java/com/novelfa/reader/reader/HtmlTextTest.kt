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
}
