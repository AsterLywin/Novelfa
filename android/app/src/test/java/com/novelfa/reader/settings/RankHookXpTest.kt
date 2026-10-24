package com.novelfa.reader.settings

import com.novelfa.reader.theme.InMemoryRankPrefs
import com.novelfa.reader.theme.RankService
import com.novelfa.reader.theme.XpRank
import com.novelfa.reader.theme.XpStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** XP migration + home-only celebration (RankHook.refreshXpRank). */
class RankHookXpTest {

    private fun dao() = FakeBookDao(
        listOf(
            testBook("done", totalChapters = 10, lastChapterIndex = 9, lastOpenedDate = "2026-01-05T00:00:00Z"),
            testBook("reading", totalChapters = 10, lastChapterIndex = 3, lastOpenedDate = "2026-01-02T00:00:00Z"),
            testBook("donePrivate", totalChapters = 10, lastChapterIndex = 9, lastOpenedDate = "2026-01-05T00:00:00Z", isPrivate = true),
            testBook("fresh", totalChapters = 10)
        )
    )

    @Test
    fun migration_libraryEstimateVsOldRankFloor() = runBlocking {
        val prefs = InMemoryRankPrefs()
        val total = RankHook.migrateXpIfNeeded(dao(), prefs, 0)
        // Library estimate: done 10 XP + bonus 10, reading 3 XP = 23.
        // Old-rank floor: 1 finished non-private book -> old rank 1 -> 100 XP.
        // Max wins: 100. Level: title 2 (Book Lover), 1 star.
        assertEquals(100, total)
        assertTrue(XpStore(prefs).migrated)
        assertEquals(100, XpStore(prefs).celebratedXp)
        val level = XpRank.levelFor(total)
        assertEquals(2, level.titleIndex)
        assertEquals(1, level.stars)
        // Migration never celebrates itself.
        assertNull(RankHook.refreshXpRank(dao(), prefs, 0))
        // Second migration is a no-op.
        assertEquals(100, RankHook.migrateXpIfNeeded(dao(), prefs, 0))
    }

    @Test
    fun migration_oldMaxRankFloor() = runBlocking {
        val prefs = InMemoryRankPrefs()
        prefs.setString(RankService.MAX_RANK_KEY, "6")
        val total = RankHook.migrateXpIfNeeded(FakeBookDao(emptyList()), prefs, 0)
        assertEquals(18000, total)
        assertEquals(10, XpRank.levelFor(total).titleIndex)
    }

    @Test
    fun celebration_onlyOnLevelChange_andAcknowledged() = runBlocking {
        val prefs = InMemoryRankPrefs()
        RankHook.migrateXpIfNeeded(dao(), prefs, 0)
        // Earn XP past the next star boundary (title 2 spans 100..300;
        // 20% = 40 XP, so 140 XP = 2 stars).
        val store = XpStore(prefs)
        store.totalXp = 140
        val up = RankHook.refreshXpRank(dao(), prefs, 0)!!
        assertEquals(1, up.fromLevel.stars)
        assertEquals(2, up.toLevel.stars)
        assertEquals(140, up.toXp)
        // Still pending until acknowledged: it reappears.
        assertTrue(RankHook.refreshXpRank(dao(), prefs, 0) != null)
        RankHook.acknowledgeXpCelebration(prefs, up.toXp)
        assertNull(RankHook.refreshXpRank(dao(), prefs, 0))
    }
}
