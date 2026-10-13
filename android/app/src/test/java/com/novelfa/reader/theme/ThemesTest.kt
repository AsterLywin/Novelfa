package com.novelfa.reader.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 7 RED: static reader themes (MainWindow.xaml.cs:99-107) +
 * character pages (BuildCharacterPages, MainWindow.xaml.cs:112-121).
 */
class ThemesTest {

    @Test
    fun dark_hexExact() {
        val d = Themes.DARK
        assertEquals("dark", d.key)
        assertEquals("#1C1A2A", d.bg)
        assertEquals("#E4E1F0", d.text)
        assertEquals("#A78BFA", d.thumb)
        assertEquals("#1C1A29", d.track)
        assertEquals("#2A2838", d.divider)
        assertTrue(d.isDark)
    }

    @Test
    fun static_count6_withExactHex() {
        assertEquals(6, Themes.ALL.size)
        val byKey = Themes.ALL.associateBy { it.key }
        assertEquals(setOf("dark", "white", "cream", "black", "gray", "green"), byKey.keys)

        val white = byKey.getValue("white")
        assertEquals("#FFFFFF", white.bg)
        assertEquals("#000000", white.text)
        assertEquals("#C0C0C0", white.thumb)
        assertEquals("#F0F0F0", white.track)
        assertEquals("#EEEEEE", white.divider)
        assertFalse(white.isDark)

        val cream = byKey.getValue("cream")
        assertEquals("#F4ECD8", cream.bg)
        assertEquals("#3B2F1E", cream.text)
        assertEquals("#C9B48A", cream.thumb)
        assertEquals("#EADFC4", cream.track)
        assertEquals("#D9CCAA", cream.divider)
        assertFalse(cream.isDark)

        val black = byKey.getValue("black")
        assertEquals("#000000", black.bg)
        assertEquals("#C8C8C8", black.text)
        assertEquals("#555555", black.thumb)
        assertEquals("#111111", black.track)
        assertEquals("#222222", black.divider)
        assertTrue(black.isDark)

        val gray = byKey.getValue("gray")
        assertEquals("#2F3237", gray.bg)
        assertEquals("#E0E0E0", gray.text)
        assertEquals("#7A7F87", gray.thumb)
        assertEquals("#26282C", gray.track)
        assertEquals("#41454B", gray.divider)
        assertTrue(gray.isDark)

        val green = byKey.getValue("green")
        assertEquals("#DCE8D5", green.bg)
        assertEquals("#1F2D1F", green.text)
        assertEquals("#9DB594", green.thumb)
        assertEquals("#CFDDC7", green.track)
        assertEquals("#BCCFB2", green.divider)
        assertFalse(green.isDark)
    }

    @Test
    fun characterPages_size12() {
        assertEquals(12, characterPages().size)
    }

    @Test
    fun pageKey_format() {
        val subaru = CharacterThemes.find("subaru")!!
        assertEquals("rz.subaru.night", subaru.pageKeyNight)
        assertEquals("rz.subaru.day", subaru.pageKeyDay)
        val echidna = CharacterThemes.find("echidna")!!
        assertEquals("rz.echidna.night", echidna.pageKeyNight)
        assertEquals("rz.echidna.day", echidna.pageKeyDay)
    }

    @Test
    fun subaru_night_exactMapping() {
        // night=(key, dark.card, dark.text, dark.accent, dark.sidebar, dark.border, isDark=true)
        val night = characterPages().first { it.key == "rz.subaru.night" }
        assertEquals("#17231D", night.bg)
        assertEquals("#E8F0EA", night.text)
        assertEquals("#FF8A3D", night.thumb)
        assertEquals("#111A16", night.track)
        assertEquals("#26382F", night.divider)
        assertTrue(night.isDark)
    }

    @Test
    fun subaru_day_hero1() {
        // day=(key, light.hero1, light.text, light.accent, light.hover, light.border, isDark=false)
        val day = characterPages().first { it.key == "rz.subaru.day" }
        assertEquals("#EAF6EE", day.bg)
        assertEquals("#14231B", day.text)
        assertEquals("#CC4A08", day.thumb)
        assertEquals("#E9F3EC", day.track)
        assertEquals("#D5E5DA", day.divider)
        assertFalse(day.isDark)
    }

    @Test
    fun accent_7_firstPurple() {
        assertEquals(7, Accent.PRESETS.size)
        assertEquals("بنفش", Accent.PRESETS[0].nameFa)
        assertEquals("#7C3AED", Accent.PRESETS[0].hex)
        assertEquals("#7C3AED", Accent.DEFAULT)
    }

    @Test
    fun themePalette_parse_throwsUnder16() {
        try {
            ThemePalette.parse("#000000 #111111")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun themeResolve_lockedCharacterPage_fallsBackToDark() {
        val resolved = currentReaderTheme("rz.echidna.night") { false }
        assertEquals(Themes.DARK, resolved)
    }

    @Test
    fun themeResolve_unlockedCharacterPage_resolves() {
        val resolved = currentReaderTheme("rz.subaru.night") { true }
        assertEquals("rz.subaru.night", resolved.key)
        assertEquals("#17231D", resolved.bg)
    }

    @Test
    fun themeResolve_staticKey_resolves() {
        assertEquals("#FFFFFF", currentReaderTheme("white") { false }.bg)
    }

    @Test
    fun themeResolve_unknownKey_fallsBackToDark() {
        assertEquals(Themes.DARK, currentReaderTheme("nope") { true })
    }
}
