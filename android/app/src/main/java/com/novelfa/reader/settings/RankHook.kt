package com.novelfa.reader.settings

import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.isCompleted
import com.novelfa.reader.theme.CelebrationTier
import com.novelfa.reader.theme.CharacterTheme
import com.novelfa.reader.theme.CharacterThemes
import com.novelfa.reader.theme.PrefsStoreRankPrefs
import com.novelfa.reader.theme.RankPrefs
import com.novelfa.reader.theme.RankService
import com.novelfa.reader.theme.XpLevel
import com.novelfa.reader.theme.XpRank
import com.novelfa.reader.theme.XpStore
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

    /** Await the DataStore preload when the concrete bridge is used; a
     * plain in-memory RankPrefs needs no waiting. */
    private suspend fun awaitPrefs(rankPrefs: RankPrefs) {
        if (rankPrefs is PrefsStoreRankPrefs) rankPrefs.awaitLoaded()
    }


    suspend fun refreshRank(dao: BookDao, rankPrefs: RankPrefs, floor: Int = 0): RankUp? {
        // Wait for the bridge's DataStore preload: recordProgress is
        // monotonic, so it must read the real stored maxRank, not a default.
        awaitPrefs(rankPrefs)
        // Backup floor (plan §7): a restored lifetime count keeps the
        // record alive on an empty library; max() never double-counts.
        val done = maxOf(
            dao.allBooks().first().count { !it.isPrivate && isCompleted(it) },
            floor
        )
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

    // ---- XP rank system (see Novelfa-XP-Rank-System.md) ----

    /** A pending XP celebration: from the last celebrated level to now. */
    data class XpRankUp(
        val fromLevel: XpLevel,
        val toLevel: XpLevel,
        val tier: CelebrationTier,
        val newThemes: List<CharacterTheme>,
        val toXp: Int
    ) {
        val bestTheme: CharacterTheme? get() = newThemes.lastOrNull()
    }

    /**
     * One-time migration from the finished-books rank to XP (spec §9).
     * Legacy units count 1 XP each; the old-rank floor (name-for-name)
     * guarantees no demotion. The result is the max of (library estimate,
     * old-rank floor, already-stored XP) — never a sum, never a decrease.
     * Migration sets celebratedXp = total, so it never triggers a spurious
     * celebration dialog; only post-migration progress celebrates.
     */
    suspend fun migrateXpIfNeeded(
        dao: BookDao,
        rankPrefs: RankPrefs,
        floor: Int = 0
    ): Int {
        awaitPrefs(rankPrefs)
        val store = XpStore(rankPrefs)
        if (store.migrated) return store.totalXp
        val books = dao.allBooks().first()
        val oldService = RankService(rankPrefs)
        val libraryFinished = books.count { !it.isPrivate && isCompleted(it) }
        val doneBooks = maxOf(libraryFinished, floor)
        val floorXp = maxOf(
            XpRank.oldRankToMinXp(oldService.maxRank),
            XpRank.oldRankToMinXp(oldService.rankIndexFor(doneBooks))
        )
        val ledger = store.ledger
        var estimate = 0
        for (book in books) {
            if (book.isPrivate) continue
            val finished = isCompleted(book)
            val unitsRead = if (finished) book.totalChapters else book.lastChapterIndex
            if (unitsRead <= 0) continue
            val bookXp = unitsRead
            val bonus = if (finished) XpRank.bookBonus(bookXp) else 0
            estimate += bookXp + bonus
            ledger.setLegacy(book.id, unitsRead, bookXp, bonus > 0)
        }
        val total = maxOf(estimate, floorXp, store.totalXp)
        store.ledger = ledger
        store.totalXp = total
        // Also keep the legacy maxRank honest for old backups/clients.
        if (oldService.rankIndexFor(doneBooks) > oldService.maxRank) {
            oldService.maxRank = oldService.rankIndexFor(doneBooks)
        }
        store.celebratedXp = total
        store.migrated = true
        return total
    }

    /** Current XP level (migrates first; idempotent after the first run). */
    suspend fun xpLevelNow(dao: BookDao, rankPrefs: RankPrefs, floor: Int = 0): XpLevel {
        val total = migrateXpIfNeeded(dao, rankPrefs, floor)
        return XpRank.levelFor(total)
    }

    /**
     * XP celebration check for the HOME screen only. Call this from
     * LibraryScreen (on composition/resume), never from the reader: XP
     * earned mid-reading stays pending (celebratedXp is untouched) until
     * the user returns home, even if the app was closed in between.
     * Returns null when the celebrated level already matches the current
     * level. The caller must call [acknowledgeXpCelebration] when the
     * dialog is dismissed, otherwise it reappears on the next home visit.
     */
    suspend fun refreshXpRank(
        dao: BookDao,
        rankPrefs: RankPrefs,
        floor: Int = 0
    ): XpRankUp? {
        awaitPrefs(rankPrefs)
        val total = migrateXpIfNeeded(dao, rankPrefs, floor)
        val store = XpStore(rankPrefs)
        val toLevel = XpRank.levelFor(total)
        val fromLevel = XpRank.levelFor(store.celebratedXp)
        if (toLevel.celebrationKey() == fromLevel.celebrationKey()) return null
        val fresh = CharacterThemes.All.filter {
            it.xpTitle > fromLevel.titleIndex &&
                it.xpTitle <= toLevel.titleIndex
        }
        return XpRankUp(
            fromLevel = fromLevel,
            toLevel = toLevel,
            tier = XpRank.celebrationTier(fromLevel, toLevel),
            newThemes = fresh,
            toXp = total
        )
    }

    /** Mark a shown celebration as seen (see [refreshXpRank]). */
    suspend fun acknowledgeXpCelebration(rankPrefs: RankPrefs, toXp: Int) {
        awaitPrefs(rankPrefs)
        val store = XpStore(rankPrefs)
        if (toXp > store.celebratedXp) store.celebratedXp = toXp
    }
}
