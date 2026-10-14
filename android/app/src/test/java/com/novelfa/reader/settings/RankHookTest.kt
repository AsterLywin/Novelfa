package com.novelfa.reader.settings

import com.novelfa.reader.theme.InMemoryRankPrefs
import com.novelfa.reader.theme.RankService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Task 8 RED: RankHook.refreshRank counts completed non-private books. */
class RankHookTest {

    @Test
    fun refreshRank_countsCompletedNonPrivate() = runBlocking {
        val dao = FakeBookDao(
            listOf(
                testBook("done", lastChapterIndex = 9, lastOpenedDate = "2026-01-05T00:00:00Z"),
                testBook("donePrivate", lastChapterIndex = 9, lastOpenedDate = "2026-01-05T00:00:00Z", isPrivate = true),
                testBook("reading", lastChapterIndex = 2, lastOpenedDate = "2026-01-02T00:00:00Z"),
                testBook("fresh")
            )
        )
        val prefs = InMemoryRankPrefs()
        RankHook.refreshRank(dao, prefs)
        // Only "done" counts -> rank 1 ("کتاب‌باز", minBooks=1).
        assertEquals(1, RankService(prefs).maxRank)
    }
}
