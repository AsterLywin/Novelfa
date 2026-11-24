package com.novelfa.reader.settings

import com.novelfa.reader.theme.InMemoryRankPrefs
import com.novelfa.reader.domain.RankPrefs
import com.novelfa.reader.domain.RankService
import com.novelfa.reader.domain.XpRank
import com.novelfa.reader.domain.XpStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** XP migration + home-only celebration (RankHook.refreshXpRank). */
class RankHookXpTest {

    private fun repo() = FakeBookRepository(
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
        val total = RankHook.migrateXpIfNeeded(repo(), prefs, 0)
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
        assertNull(RankHook.refreshXpRank(repo(), prefs, 0))
        // Second migration is a no-op.
        assertEquals(100, RankHook.migrateXpIfNeeded(repo(), prefs, 0))
    }

    @Test
    fun migration_oldMaxRankFloor() = runBlocking {
        val prefs = InMemoryRankPrefs()
        prefs.setString(RankService.MAX_RANK_KEY, "6")
        val total = RankHook.migrateXpIfNeeded(FakeBookRepository(emptyList()), prefs, 0)
        assertEquals(18000, total)
        assertEquals(10, XpRank.levelFor(total).titleIndex)
    }

    @Test
    fun celebration_onlyOnLevelChange_andAcknowledged() = runBlocking {
        val prefs = InMemoryRankPrefs()
        RankHook.migrateXpIfNeeded(repo(), prefs, 0)
        // Earn XP past the next star boundary (title 2 spans 100..300;
        // 20% = 40 XP, so 140 XP = 2 stars).
        val store = XpStore(prefs)
        // The one-time anti-cheat recompute already ran: refreshXpRank
        // runs it first, and it re-derives total XP from the ledger —
        // a hand-set total would be dropped back to the ledger value
        // (100) before the celebration check ever sees it. Marking it
        // done puts the store in the steady state celebrations run in.
        store.recomputed = true
        store.totalXp = 140
        val up = RankHook.refreshXpRank(repo(), prefs, 0)!!
        assertEquals(1, up.fromLevel.stars)
        assertEquals(2, up.toLevel.stars)
        assertEquals(140, up.toXp)
        // Still pending until acknowledged: it reappears.
        assertTrue(RankHook.refreshXpRank(repo(), prefs, 0) != null)
        RankHook.acknowledgeXpCelebration(prefs, up.toXp)
        assertNull(RankHook.refreshXpRank(repo(), prefs, 0))
    }

    @Test
    fun rankIcons_upstream_d2850b8b() {
        // One icon per title, index = title index; clamped at the last.
        assertEquals("\uD83C\uDF31", XpRank.iconFor(0))
        assertEquals("\uD83D\uDCD6", XpRank.iconFor(2))
        assertEquals("\uD83C\uDF0C", XpRank.iconFor(11))
        assertEquals("\uD83C\uDF0C", XpRank.iconFor(99))
        assertEquals("\uD83C\uDF31", XpRank.iconFor(-1))
        // One icon per eternity mark, count 1-based; clamped at the last.
        assertEquals("", XpRank.eternityIcon(0))
        assertEquals("\uD83D\uDD25", XpRank.eternityIcon(1))
        assertEquals("\uD83C\uDF1F", XpRank.eternityIcon(10))
        assertEquals("\uD83C\uDF1F", XpRank.eternityIcon(99))
        // Level icon: title icon below the cap, mark icon past it.
        // At exactly 27,000 XP there is no mark yet (0 marks), so the
        // title icon stays; one full mark later it is the mark icon.
        assertEquals("\uD83C\uDF31", XpRank.levelFor(0).icon)
        assertEquals("\uD83D\uDCD6", XpRank.levelFor(100).icon)
        assertEquals("\uD83C\uDF0C", XpRank.levelFor(27_000).icon)
        assertEquals("\uD83D\uDD25", XpRank.levelFor(37_000).icon)
        assertEquals("\uD83D\uDCA7", XpRank.levelFor(47_000).icon)
        // "X of Y XP" already ported: 100 XP sits 0 into the 200-XP
        // step of title 2 (100..300).
        val level = XpRank.levelFor(100)
        assertEquals(0, level.xpIntoStep)
        assertEquals(200, level.xpStepTotal)
    }
}
