package com.novelfa.reader.settings

import com.novelfa.reader.data.BookRepository
import com.novelfa.reader.data.DiagLog
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.domain.isCompleted
import com.novelfa.reader.data.sha256OfFile
import com.novelfa.reader.domain.CelebrationTier
import com.novelfa.reader.domain.CharacterTheme
import com.novelfa.reader.domain.CharacterThemes
import com.novelfa.reader.domain.RankPrefs
import com.novelfa.reader.domain.RankService
import com.novelfa.reader.domain.XpLedger
import com.novelfa.reader.domain.XpLevel
import com.novelfa.reader.domain.XpRank
import com.novelfa.reader.domain.XpStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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

    /** Await the DataStore preload (a no-op for plain in-memory
     * RankPrefs — see the interface default). */
    suspend fun refreshRank(repo: BookRepository, rankPrefs: RankPrefs, floor: Int = 0): RankUp? {
        // Wait for the bridge's DataStore preload: recordProgress is
        // monotonic, so it must read the real stored maxRank, not a default.
        rankPrefs.awaitLoaded()
        // Backup floor (plan §7): a restored lifetime count keeps the
        // record alive on an empty library; max() never double-counts.
        val done = maxOf(
            repo.finishedCount(),
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
        repo: BookRepository,
        rankPrefs: RankPrefs,
        floor: Int = 0
    ): Int {
        rankPrefs.awaitLoaded()
        val store = XpStore(rankPrefs)
        if (store.migrated) return store.totalXp
        val books = repo.books().first()
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

    /**
     * One-time mass rekeying of the XP ledger (UUID -> file SHA-256
     * content key). Runs once, in the background, from the home
     * screen — never blocking it: hashing is IO-bound and a large
     * library can take a while, but the home UI does not wait for
     * it. Books whose file is missing are skipped here and re-keyed
     * lazily on first open (the reader resolver), so a partial run
     * still ends with the flag set. Transfers are idempotent and
     * total XP is never touched, so an interrupted run simply
     * resumes harmlessly on the next home visit — except the flag
     * is only set at the end, so an interrupted run re-runs from
     * the start (already-transferred books are no-ops).
     */
    suspend fun rekeyXpLedgerIfNeeded(repo: BookRepository, rankPrefs: RankPrefs) {
        rankPrefs.awaitLoaded()
        val store = XpStore(rankPrefs)
        if (store.rekeyed) return
        val books = withContext(Dispatchers.IO) { repo.books().first() }
        for (book in books) {
            if (book.isPrivate) continue
            val file = File(book.filePath)
            if (!file.isFile) continue
            val hash = withContext(Dispatchers.IO) {
                runCatching { sha256OfFile(file) }.getOrNull()
            } ?: continue
            // Phase 2 (ADR-3 / F2): the UUID→content transfer is
            // allowed only on same-content proof — the row's
            // stored hash is null or equals the fresh hash. A
            // stored DIFFERENT hash means the content changed
            // since the row was recorded, so the UUID entry
            // belongs to the old content and must NOT move to
            // the new content's key.
            if (book.contentHash != null && book.contentHash != hash) continue
            val contentKey = XpLedger.contentKeyFor(hash)
            if (contentKey != book.id) store.transferLedger(book.id, contentKey)
        }
        store.rekeyed = true
    }

    /**
     * One-time anti-cheat recompute (see backup-anti-cheat-design.md).
     * Runs once, on first launch after the update, in the same home-screen
     * flow as the migration (after migrateXpIfNeeded, before the UI reads
     * the level): rank and XP are re-derived from real books and the ledger
     * instead of trusting stored numbers. The flag is set LAST, so a failure
     * (or a kill mid-run) retries on the next launch instead of leaving a
     * half-recomputed state behind. Legitimate readers never lose anything:
     * the ledger still holds every credit they earned, and the migration
     * floor (from the derived rank) keeps migrated readers from demoting.
     * A forged total (e.g. a hand-made max-rank backup) drops to the true
     * value here.
     */
    suspend fun recomputeXpIfNeeded(
        repo: BookRepository,
        rankPrefs: RankPrefs,
        prefs: PrefsStore
    ) {
        rankPrefs.awaitLoaded()
        val store = XpStore(rankPrefs)
        if (store.recomputed) return
        val service = RankService(rankPrefs)
        val oldRank = service.maxRank
        val oldTotal = store.totalXp
        val finished = repo.books().first().count { !it.isPrivate && isCompleted(it) }
        val rank = RankService.rankIndexForStatic(finished)
        service.maxRank = rank
        prefs.setFinishedBooksRestored(finished)
        val total = store.recomputeTotalFromLedger(store.migrated, rank)
        store.recomputed = true
        DiagLog.w(
            "NovelfaXp",
            "one-time anti-cheat recompute: rank $oldRank -> $rank, " +
                "totalXp $oldTotal -> $total (finished=$finished)"
        )
    }

    /** Current XP level (migrates first; idempotent after the first run). */
    suspend fun xpLevelNow(
        repo: BookRepository,
        rankPrefs: RankPrefs,
        floor: Int = 0,
        prefs: PrefsStore
    ): XpLevel {
        migrateXpIfNeeded(repo, rankPrefs, floor)
        recomputeXpIfNeeded(repo, rankPrefs, prefs)
        return XpRank.levelFor(XpStore(rankPrefs).totalXp)
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
        repo: BookRepository,
        rankPrefs: RankPrefs,
        floor: Int = 0,
        prefs: PrefsStore
    ): XpRankUp? {
        rankPrefs.awaitLoaded()
        migrateXpIfNeeded(repo, rankPrefs, floor)
        recomputeXpIfNeeded(repo, rankPrefs, prefs)
        val store = XpStore(rankPrefs)
        val toLevel = XpRank.levelFor(store.totalXp)
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
            toXp = store.totalXp
        )
    }

    /** Mark a shown celebration as seen (see [refreshXpRank]). */
    suspend fun acknowledgeXpCelebration(rankPrefs: RankPrefs, toXp: Int) {
        rankPrefs.awaitLoaded()
        val store = XpStore(rankPrefs)
        if (toXp > store.celebratedXp) store.celebratedXp = toXp
    }
}
