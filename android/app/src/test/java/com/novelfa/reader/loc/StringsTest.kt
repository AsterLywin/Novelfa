package com.novelfa.reader.loc

import com.novelfa.reader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Task 4 (RED first): every WPF Loc.T key (fa text from LocStrings.cs)
 * must resolve to a real string resource; en + fa XML must stay in sync.
 */
class StringsTest {

    @Test
    fun mapSize_equalsFaEntryCount() {
        // 311 fa entries parsed from EpubReader/Services/LocStrings.cs
        // + 15 UI-pass entries (library header/hero/sort, reader
        // theme/font/fullscreen, about) = 326.
        assertEquals(326, Strings.size)
    }

    @Test
    fun coreKeys_resolveToExpectedIds() {
        assertEquals(R.string.pin, Strings.res("پین"))
        assertEquals(R.string.library, Strings.res("کتابخانه"))
        assertEquals(R.string.close, Strings.res("بستن"))
        assertEquals(R.string.error, Strings.res("خطا"))
    }

    @Test
    fun spotCheck_keysResolveToRealIds() {
        val keys = listOf(
            "تغییر رمز",
            "رمز خصوصی با موفقیت تغییر کرد.",
            "کتابخانه",
            "در حال مطالعه",
            "تمام‌شده",
            "علاقه‌مندی‌ها",
            "افزودن کتاب",
            "حالت تیره",
            "پین اشتباهه",
            "برای نمایش کتاب، برنامه‌ی «Microsoft Edge WebView2 Runtime» لازم است و روی این سیستم پیدا نشد یا اجرا نشد.\n\nصفحه‌ی دانلود آن باز شود؟",
            "از اینکه با حمایت‌های گرمتون باعث رشد تیم ما میشید صمیمانه سپاسگزاریم ❤️"
        )
        assertTrue(keys.size >= 10)
        for (k in keys) {
            assertNotEquals("no resource for key: $k", R.string.app_name, Strings.res(k))
        }
    }

    @Test
    fun everyKey_resolvesToRealId() {
        for (k in Strings.keys) {
            assertNotEquals("no resource for key: $k", R.string.app_name, Strings.res(k))
        }
    }

    @Test
    fun noDuplicateResourceIds() {
        assertEquals(Strings.size, Strings.resIds().toSet().size)
    }

    @Test
    fun unknownKey_fallsBackToAppName() {
        assertEquals(R.string.app_name, Strings.res("__no_such_key__"))
    }

    @Test
    fun xmlResources_coverEveryKey() {
        val enNames = stringNames(File("src/main/res/values/strings.xml"))
        val faNames = stringNames(File("src/main/res/values-fa/strings.xml"))
        // +1 for the app_name entry (fallback id, not a Loc key)
        assertEquals(Strings.size + 1, enNames.size)
        assertEquals(enNames, faNames)
        assertTrue(enNames.contains("app_name"))
    }

    @Test
    fun faXml_containsPinText() {
        val fa = File("src/main/res/values-fa/strings.xml").readText(Charsets.UTF_8)
        assertTrue(fa.contains("پین"))
        assertTrue(fa.contains("کتابخانه"))
    }

    private fun stringNames(f: File): Set<String> {
        assertTrue("missing " + f.path, f.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("string")
        val out = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            out += nodes.item(i).attributes.getNamedItem("name").nodeValue
        }
        return out
    }
}
