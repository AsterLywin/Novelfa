package com.novelfa.reader.settings

import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.isCompleted
import com.novelfa.reader.theme.CharacterTheme
import com.novelfa.reader.theme.CharacterThemes
import com.novelfa.reader.theme.PrefsStoreRankPrefs
import com.novelfa.reader.theme.RankService
import kotlinx.coroutines.flow.first

/** A rank-up that unlocked fresh character themes (WPF CheckRankUp parity). */
data class RankUp(
    val toRank: Int,
    val newThemes: List<CharacterTheme>
) {
    val best: CharacterTheme get() = newThemes.last()
}

/**
 * Rank refresh hook: counts finished non-private books (same predicate as
 * WPF LibraryWindow.CompletedCount: `!IsPrivate && IsCompleted`) and records
 * it via [RankService.recordProgress] (monotonic — deleting a book never
 * relocks themes). Called from LibraryScreen on first composition/resume.
 *
 * Returns a [RankUp] when the rank increased past a theme-unlocking rank,
 * so the UI can celebrate like the desktop client's AnnounceUnlocks.
 */
object RankHook {

    suspend fun refreshRank(dao: BookDao, rankPrefs: PrefsStoreRankPrefs): RankUp? {
        // Wait for the bridge's DataStore preload: recordProgress is
        // monotonic, so it must read the real stored maxRank, not a default.
        rankPrefs.awaitLoaded()
        val done = dao.allBooks().first().count { !it.isPrivate && isCompleted(it) }
        val service = RankService(rankPrefs)
        val newRank = service.rankIndexFor(done)
        val oldMax = service.maxRank
        if (newRank <= oldMax) return null
        service.maxRank = newRank
        // First-ever record landing on rank 0 unlocks nothing worth announcing.
        if (oldMax < 0 && newRank == 0) return null
        val fromRank = maxOf(0, oldMax) + if (oldMax < 0) 0 else 1
        val fresh = CharacterThemes.All.filter {
            it.requiredRank in fromRank..newRank && it.requiredRank > 0
        }
        if (fresh.isEmpty()) return null
        return RankUp(newRank, fresh)
    }
}
