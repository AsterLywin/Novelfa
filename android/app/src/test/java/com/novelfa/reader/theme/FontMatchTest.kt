package com.novelfa.reader.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Task 7 RED: font-file resolution heuristic
 * (improved Task 6 filename heuristic; no GDI name-table parsing in v1).
 */
class FontMatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String) = tmp.newFile(name)

    @Test
    fun exactStoredName_matchIgnoresCase() {
        val stored = listOf(file("Vazirmatn-Regular.ttf"), file("Other.ttf"))
        val assets = VazirmatnFiles(file("a-Regular.ttf"), file("a-Bold.ttf"))
        val found = resolveFontFiles("vazirmatn-regular", stored, assets)
        assertEquals(1, found.size)
        assertEquals("Vazirmatn-Regular.ttf", found[0].name)
    }

    @Test
    fun vazirmatnFamily_returnsAssets() {
        val assets = VazirmatnFiles(file("Vazirmatn-Regular.ttf"), file("Vazirmatn-Bold.ttf"))
        val found = resolveFontFiles("Vazirmatn", emptyList(), assets)
        assertEquals(2, found.size)
        assertTrue(found.any { it.name == "Vazirmatn-Regular.ttf" })
        assertTrue(found.any { it.name == "Vazirmatn-Bold.ttf" })
    }

    @Test
    fun filenameContains_fallback() {
        val stored = listOf(file("MyNaskh-Font.ttf"), file("Other.ttf"))
        val assets = VazirmatnFiles(file("a-Regular.ttf"), file("a-Bold.ttf"))
        val found = resolveFontFiles("naskh", stored, assets)
        assertEquals(1, found.size)
        assertEquals("MyNaskh-Font.ttf", found[0].name)
    }

    @Test
    fun noMatch_empty() {
        val assets = VazirmatnFiles(file("a-Regular.ttf"), file("a-Bold.ttf"))
        assertTrue(resolveFontFiles("nothing-here", emptyList(), assets).isEmpty())
    }
}
