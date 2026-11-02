package com.novelfa.reader.loc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * String resources live in res/values/strings.xml (en) and
 * res/values-fa/strings.xml (fa) and are used directly via R.string.
 * The old WPF Loc.T key map (Strings) is gone, so these tests guard
 * what that map used to guard: the two XML files stay in sync, contain
 * no duplicates, and carry the core Persian UI texts.
 *
 * Working directory for unit tests is the app module dir, so the
 * relative res paths below resolve under android/app.
 */
class StringsTest {

    @Test
    fun enFa_nameSets_equal() {
        val enNames = stringNames(File("src/main/res/values/strings.xml"))
        val faNames = stringNames(File("src/main/res/values-fa/strings.xml"))
        assertEquals(enNames, faNames)
    }

    @Test
    fun entryCount_substantial() {
        val enNames = stringNames(File("src/main/res/values/strings.xml"))
        assertTrue("expected hundreds of strings, got ${enNames.size}", enNames.size >= 300)
    }

    @Test
    fun noDuplicateNames_withinEachFile() {
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-fa/strings.xml")) {
            val f = File(path)
            assertTrue("missing " + f.path, f.exists())
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.getElementsByTagName("string")
            val names = mutableListOf<String>()
            for (i in 0 until nodes.length) {
                names += nodes.item(i).attributes.getNamedItem("name").nodeValue
            }
            assertEquals("$path has duplicate string names", names.size, names.toSet().size)
        }
    }

    @Test
    fun coreKeys_existInBoth() {
        val enNames = stringNames(File("src/main/res/values/strings.xml"))
        val faNames = stringNames(File("src/main/res/values-fa/strings.xml"))
        for (key in listOf("app_name", "pin", "close")) {
            assertTrue("en missing $key", enNames.contains(key))
            assertTrue("fa missing $key", faNames.contains(key))
        }
    }

    @Test
    fun faXml_containsPersianTexts() {
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
