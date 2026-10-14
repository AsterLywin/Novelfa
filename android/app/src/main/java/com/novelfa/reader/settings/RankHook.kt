package com.novelfa.reader.settings

import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.isCompleted
import com.novelfa.reader.theme.RankPrefs
import com.novelfa.reader.theme.RankService
import kotlinx.coroutines.flow.first

/**
 * Rank refresh hook: counts finished non-private books (same predicate as
 * WPF LibraryWindow.CompletedCount: `!IsPrivate && IsCompleted`) and records
 * it via [RankService.recordProgress] (monotonic — deleting a book never
 * relocks themes). Called from LibraryScreen on first composition/resume.
 */
object RankHook {

    suspend fun refreshRank(dao: BookDao, rankPrefs: RankPrefs) {
        val done = dao.allBooks().first().count { !it.isPrivate && isCompleted(it) }
        RankService(rankPrefs).recordProgress(done)
    }
}
