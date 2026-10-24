package com.novelfa.reader.reader

import com.novelfa.reader.theme.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HtmlBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun build(
        body: String = "<p>سلام</p>",
        isEnglish: Boolean = false,
        fontFaceCss: String = "",
        docGen: Int = 1
    ): String = HtmlBuilder.build(
        "file:///tmp/", body, Themes.DARK, "Vazirmatn",
        18, 1.8, 24, 0.0, isEnglish, fontFaceCss, docGen
    )

    @Test
    fun build_containsBgHex() {
        assertTrue(build().contains("#1C1A2A"))
    }

    @Test
    fun build_rtlForPersian() {
        assertTrue(build().contains("dir=\"rtl\"") || build().contains("direction:rtl"))
    }

    @Test
    fun build_ltrWhenEnglish() {
        assertTrue(build(isEnglish = true).contains("direction:ltr"))
    }

    @Test
    fun build_containsFontSizeAndStack() {
        val html = build()
        assertTrue(html.contains("font-size:18px"))
        assertTrue(html.contains("Vazirmatn"))
        assertTrue(html.contains("'NovelfaFallback',sans-serif"))
    }

    @Test
    fun build_containsFontFaceContent() {
        val css = "@font-face{font-family:'Vazirmatn';}"
        assertTrue(build(fontFaceCss = css).contains(css))
        assertTrue(build(fontFaceCss = css).contains("id=\"rdrFontFace\""))
    }

    @Test
    fun build_spacingLineHeight() {
        assertTrue(build().contains("line-height:1.8"))
        assertTrue(build().contains("id=\"readerSpacing\""))
    }

    @Test
    fun build_noChromeWebview() {
        assertFalse(build().contains("chrome.webview"))
    }

    @Test
    fun build_noShortcutKeys() {
        assertFalse(build().contains("__rdrKeys"))
    }

    @Test
    fun build_scrollReporterUsesAndroidBridge() {
        val html = build()
        assertTrue(html.contains("Android.onScroll"))
        assertTrue(html.contains("setInterval(r,200)"))
        assertTrue(html.contains("__rdrGen=1"))
    }

    @Test
    fun divider_markup() {
        val div = ChapterDivider.divider("فصل 2", Themes.DARK)
        assertTrue(div.contains("فصل 2"))
        assertTrue(div.contains("#2A2838"))
        assertTrue(div.contains("#A78BFA"))
    }

    @Test
    fun divider_escapesTitle() {
        val div = ChapterDivider.divider("a<b>&\"c\"", Themes.DARK)
        assertTrue(div.contains("&lt;b&gt;"))
        assertFalse(div.contains("<b>"))
    }

    @Test
    fun escapeForJs_noRawNewlines() {
        val input = "a\\b'c" + "\r" + "\n" + "d" + 8232.toChar() + "e" + 8233.toChar() + "f"
        val got = HtmlText.escapeForJs(input)
        assertFalse(got.contains("\n"))
        assertFalse(got.contains("\r"))
        assertFalse(got.contains(8232.toChar().toString()))
        assertFalse(got.contains(8233.toChar().toString()))
        assertTrue(got.contains("a\\\\b\\'c"))
        assertTrue(got.contains("c\\nd"))
        assertTrue(got.contains("d\\u2028e"))
        assertTrue(got.contains("\\u2028"))
        assertTrue(got.contains("\\u2029"))
    }

    @Test
    fun fontFaceCss_weightAndMime() {
        val bold = File(tmp.newFolder(), "MyFont-Bold.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val css = HtmlBuilder.fontFaceCss("MyFont", listOf(bold))
        assertTrue(css.contains("font-weight:700"))
        assertTrue(css.contains("font/ttf"))
        assertTrue(css.contains("AQID"))
    }

    @Test
    fun fontFaceCss_otfRegular() {
        val reg = File(tmp.newFolder(), "MyFont-Regular.otf").apply { writeBytes(byteArrayOf(4, 5)) }
        val css = HtmlBuilder.fontFaceCss("MyFont", listOf(reg))
        assertTrue(css.contains("font-weight:400"))
        assertTrue(css.contains("font/otf"))
    }

    @Test
    fun fontFaceCss_emptyFamily_empty() {
        assertEquals("", HtmlBuilder.fontFaceCss("", listOf(File("x.ttf"))))
    }

    @Test
    fun spacingCss_paragraphSpacing() {
        val css = HtmlBuilder.spacingCss(2.0, 32, 0.5)
        assertTrue(css.contains("line-height:2.0"))
        assertTrue(css.contains("padding:16px 32px"))
        assertTrue(css.contains("margin-bottom:0.5em"))
    }

    @Test
    fun spacingCss_zeroParagraph_noMarginRule() {
        assertFalse(HtmlBuilder.spacingCss(1.8, 24, 0.0).contains("margin-bottom"))
    }

    @Test
    fun appendJs_noRawNewlines() {
        val js = HtmlBuilder.appendJs(3, "<div>line1\nline2</div>")
        assertTrue(js.contains("insertAdjacentHTML"))
        assertTrue(js.contains("__rdrGen!==3"))
        assertFalse(js.contains("\n"))
    }

    // --- Render-pipeline hardening (ported from AI Studio defect pass) ---

    @Test
    fun build_containsViewportMeta() {
        assertTrue(build().contains("name=\"viewport\""))
        assertTrue(build().contains("width=device-width"))
    }

    @Test
    fun build_noColorInheritKill() {
        assertFalse(build().contains("color:inherit"))
    }

    @Test
    fun build_linkColorReadable() {
        // DARK theme link color must be present and distinguishable.
        assertTrue(build().contains("color:#A78BFA"))
    }

    @Test
    fun build_tableAndPreContained() {
        val html = build()
        assertTrue(html.contains("overflow-x:auto"))
        assertTrue(html.contains("pre-wrap"))
        assertTrue(html.contains("max-width:100%"))
    }

    @Test
    fun build_darkThemeAttrPresent() {
        assertTrue(build().contains("data-theme=\"dark\""))
    }

    @Test
    fun fontFaceCss_hasFontDisplaySwap() {
        val f = File(tmp.newFolder(), "F.ttf").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(HtmlBuilder.fontFaceCss("F", listOf(f)).contains("font-display:swap"))
    }
}
