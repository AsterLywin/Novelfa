package com.novelfa.reader.theme

import com.novelfa.reader.domain.CharacterThemes
import com.novelfa.reader.domain.RankService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secret theme codes (2026-11-25): the six d2850b8b codes for the
 * three new themes were never ported — Android carried only the old
 * five — and a code for an already-unlocked theme silently died in
 * handleQuery's `!isUnlocked` guard, so only PEIDE (reif, the one
 * theme no rank/XP gate can ever pre-unlock) visibly worked. The
 * table below pins upstream parity; the matcher pins the
 * TrackSecretKeys matching rule.
 */
class SecretCodesTest {

    @Test
    fun table_matchesUpstream_d2850b8b() {
        // Verbatim from upstream MainWindow.secretThemeCodes after
        // d2850b8b, same order.
        assertEquals(
            listOf(
                "BEAKO" to "beatrice",
                "OTTO" to "otto",
                "VICE" to "gta",
                "GTA" to "gta",
                "JULIUS" to "julius",
                "JUKULIUS" to "julius",
                "JUUKULIUS" to "julius",
                "EMT" to "emilia",
                "TEA" to "echidna",
                "TSUGI" to "tsugi",
                "PEIDE" to "reif"
            ),
            CharacterThemes.SecretCodes
        )
    }

    @Test
    fun everyCodeTargetExists() {
        for ((_, themeId) in CharacterThemes.SecretCodes) {
            assertTrue(
                "no theme for code target $themeId",
                CharacterThemes.find(themeId) != null
            )
        }
    }

    @Test
    fun newThemesHaveCodes() {
        val targets = CharacterThemes.SecretCodes.map { it.second }.toSet()
        assertTrue(targets.contains("otto"))
        assertTrue(targets.contains("julius"))
        assertTrue(targets.contains("gta"))
    }

    @Test
    fun codesAreUppercaseAscii_noSuffixCollisions() {
        val codes = CharacterThemes.SecretCodes.map { it.first }
        for (code in codes) {
            assertTrue(code.all { it in 'A'..'Z' })
        }
        for (a in codes) {
            for (b in codes) {
                if (a != b) assertFalse("code $a is a suffix of $b", b.endsWith(a))
            }
        }
    }

    @Test
    fun matcher_matchesKnownCodes() {
        assertEquals("beatrice", CharacterThemes.matchSecretCode("BEAKO")?.second)
        assertEquals("beatrice", CharacterThemes.matchSecretCode("beako")?.second)
        assertEquals("beatrice", CharacterThemes.matchSecretCode("xxBEAKO")?.second)
        assertEquals("emilia", CharacterThemes.matchSecretCode("EMT")?.second)
        assertEquals("echidna", CharacterThemes.matchSecretCode("xTEA")?.second)
        assertEquals("tsugi", CharacterThemes.matchSecretCode("TSUGI")?.second)
        assertEquals("reif", CharacterThemes.matchSecretCode("PEIDE")?.second)
    }

    @Test
    fun matcher_matchesNewCodes() {
        assertEquals("otto", CharacterThemes.matchSecretCode("OTTO")?.second)
        assertEquals("otto", CharacterThemes.matchSecretCode("otto")?.second)
        assertEquals("gta", CharacterThemes.matchSecretCode("VICE")?.second)
        assertEquals("gta", CharacterThemes.matchSecretCode("GTA")?.second)
        assertEquals("julius", CharacterThemes.matchSecretCode("JULIUS")?.second)
        assertEquals("julius", CharacterThemes.matchSecretCode("JUKULIUS")?.second)
        assertEquals("julius", CharacterThemes.matchSecretCode("juukulius")?.second)
    }

    @Test
    fun matcher_noMatch() {
        assertNull(CharacterThemes.matchSecretCode("hello"))
        assertNull(CharacterThemes.matchSecretCode(""))
        // Trailing run is "time", not "TEA".
        assertNull(CharacterThemes.matchSecretCode("tea time"))
        // Punctuation breaks the trailing letter run.
        assertNull(CharacterThemes.matchSecretCode("BEAKO!"))
        assertNull(CharacterThemes.matchSecretCode("123"))
    }

    @Test
    fun secretUnlock_isIdempotent_retypingCodeKeepsUnlock() {
        // The fix re-records the code even when the theme is already
        // unlocked; secretUnlock must stay a no-op the second time.
        val prefs = InMemoryRankPrefs()
        val s = RankService(prefs)
        s.secretUnlock("beatrice")
        s.secretUnlock("beatrice")
        assertTrue(s.isSecretUnlocked("beatrice"))
        assertEquals("beatrice", prefs.getString(RankService.SECRET_KEY, ""))
        assertTrue(s.isUnlocked(CharacterThemes.find("beatrice")!!))
    }

    @Test
    fun secretUnlock_recordsCodeForRankUnlockedTheme() {
        // A theme the rank gate already opened still gets its secret
        // recorded when its code is typed (handleQuery calls
        // secretUnlock unconditionally now).
        val prefs = InMemoryRankPrefs()
        val s = RankService(prefs)
        s.maxRank = 6
        assertTrue(s.isUnlocked(CharacterThemes.find("beatrice")!!))
        assertFalse(s.isSecretUnlocked("beatrice"))
        s.secretUnlock("beatrice")
        assertTrue(s.isSecretUnlocked("beatrice"))
        assertTrue(s.isUnlocked(CharacterThemes.find("beatrice")!!))
    }
}
