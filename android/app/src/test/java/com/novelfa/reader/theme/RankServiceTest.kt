package com.novelfa.reader.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 7 RED: ranks + unlock (RankService.cs) + secret-only visibility
 * (CharacterThemes.cs VisibleCount/SecretOnly).
 */
class RankServiceTest {

    private fun service(): RankService = RankService(InMemoryRankPrefs())

    @Test
    fun thresholds_descLoop() {
        val s = service()
        assertEquals(0, s.rankIndexFor(0))
        assertEquals(1, s.rankIndexFor(1))
        assertEquals(1, s.rankIndexFor(2))
        assertEquals(2, s.rankIndexFor(3))
        assertEquals(2, s.rankIndexFor(9))
        assertEquals(3, s.rankIndexFor(10))
        assertEquals(3, s.rankIndexFor(24))
        assertEquals(4, s.rankIndexFor(25))
    }

    @Test
    fun secret_roundTrip() {
        val s = service()
        assertFalse(s.isSecretUnlocked("echidna"))
        assertFalse(s.isUnlocked(CharacterThemes.find("echidna")!!))
        s.secretUnlock("echidna")
        assertTrue(s.isSecretUnlocked("echidna"))
        assertTrue(s.isUnlocked(CharacterThemes.find("echidna")!!))
    }

    @Test
    fun echidna_rankGated_notSecretOnly() {
        // Stale expectation fixed: in the current CharacterThemes, echidna
        // is rank-gated (legacy rank 5, XP title 9), not secret-only, and
        // no theme is secret-only, so all 7 themes are visible even when
        // locked.
        val echidna = CharacterThemes.find("echidna")!!
        assertFalse(echidna.secretOnly)
        assertEquals(5, echidna.requiredRank)
        assertEquals(9, echidna.xpTitle)
        val s = service()
        assertEquals(7, s.visibleCount())
        assertFalse(s.isUnlocked(echidna))
        s.secretUnlock("echidna")
        assertTrue(s.isUnlocked(echidna))
    }

    @Test
    fun subaru_notSecretOnly() {
        assertFalse(CharacterThemes.find("subaru")!!.secretOnly)
    }

    @Test
    fun recordProgress_monotonic() {
        val s = service()
        s.recordProgress(25)
        assertEquals(4, s.maxRank)
        assertTrue(s.isUnlocked(CharacterThemes.find("emilia")!!))
        // Progress drops (e.g. book deleted) — unlocked ranks never relock.
        s.recordProgress(0)
        assertEquals(4, s.maxRank)
        assertTrue(s.isUnlocked(CharacterThemes.find("emilia")!!))
    }

    @Test
    fun recordProgress_updatesMaxRank() {
        val s = service()
        assertEquals(-1, s.maxRank)
        s.recordProgress(3)
        assertEquals(2, s.maxRank)
        assertTrue(s.isRankUnlocked(2))
        assertFalse(s.isRankUnlocked(3))
    }

    @Test
    fun unlockedCount_countsRankAndSecret() {
        val s = service()
        assertEquals(1, s.unlockedCount())
        s.recordProgress(1)
        assertEquals(2, s.unlockedCount())
        s.secretUnlock("echidna")
        assertEquals(3, s.unlockedCount())
    }
}
