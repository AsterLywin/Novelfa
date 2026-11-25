package com.novelfa.reader.data

import com.novelfa.reader.domain.RankPrefs
import com.novelfa.reader.domain.RankService
import com.novelfa.reader.domain.XpStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Production RankPrefs backed by Task 3 PrefsStore (DataStore).
 *
 * DataStore is suspend-based but the RankPrefs API is sync (kept sync so
 * RankService stays pure-JVM and unit-testable). Instead of runBlocking on
 * the caller's thread — which blocked the UI thread on every library tab
 * switch and on every character-theme row — values are preloaded once on
 * Dispatchers.IO into an in-memory cache: reads are served from the cache
 * (never blocking) and writes update the cache immediately while persisting
 * to DataStore in the background.
 */
class PrefsStoreRankPrefs(
    private val store: PrefsStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : RankPrefs {
    companion object {
        /**
         * ONE cache shared by every bridge instance in the process. The
         * app used to build a bridge per screen (library, reader,
         * settings, nav graph), each with its own cache preloaded once:
         * XP credited by the reader's bridge never reached the library
         * bridge's cache, so home XP and celebrations stayed stale until
         * a cold start, and a secret unlocked through one bridge could be
         * overwritten by another bridge's stale read-modify-write.
         * All rank writes in the app go through this cache, so it is the
         * source of truth once loaded; the preload below only fills keys
         * that are not already cached, never overwriting a newer write
         * with a stale DataStore read that raced it.
         */
        private val sharedCache = ConcurrentHashMap<String, String>()

        /**
         * Serializes every background DataStore write from every bridge
         * instance in the process. setString launches one coroutine per
         * call on Dispatchers.IO, so two rapid writes (e.g. two secret
         * unlocks in a row) could land out of order and the older value
         * would overwrite the newer one in DataStore — the second unlock
         * lost on the next restart even though the cache held it. With
         * the mutex, writes land in call order.
         */
        private val writeMutex = Mutex()
    }

    private val cache: ConcurrentHashMap<String, String> get() = sharedCache
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                val fresh = mapOf(
                    RankService.MAX_RANK_KEY to store.getString(PrefsStore.MAX_RANK, "-1"),
                    RankService.SECRET_KEY to store.getString(PrefsStore.SECRET_THEMES, ""),
                    XpStore.TOTAL_XP_KEY to store.getString(PrefsStore.TOTAL_XP, "0"),
                    XpStore.LEDGER_KEY to store.getString(PrefsStore.XP_LEDGER, ""),
                    XpStore.PENDING_KEY to store.getString(PrefsStore.XP_PENDING, ""),
                    XpStore.MIGRATED_KEY to store.getString(PrefsStore.XP_MIGRATED, "0"),
                    XpStore.CELEBRATED_XP_KEY to store.getString(PrefsStore.XP_CELEBRATED, "0"),
                    XpStore.REKEYED_KEY to store.getString(PrefsStore.XP_REKEYED, "0"),
                    XpStore.RECOMPUTED_KEY to store.getString(PrefsStore.XP_RECOMPUTED, "0")
                )
                for ((key, value) in fresh) cache.putIfAbsent(key, value)
            } finally {
                // Always release waiters: a failed preload (e.g. corrupted
                // DataStore) must not hang awaitLoaded() forever.
                loaded.complete(Unit)
            }
        }
    }

    /**
     * Suspends until the initial DataStore preload has finished. Call this
     * before any read-modify-write (e.g. recordProgress) so a not-yet-loaded
     * cache can't clobber the stored monotonic maxRank with a default.
     */
    override suspend fun awaitLoaded() = loaded.await()

    override fun getString(key: String, default: String): String =
        cache[key] ?: default

    override fun setString(key: String, value: String) {
        cache[key] = value
        scope.launch { persistString(key, value) }
    }

    /** Serialized DataStore write (see writeMutex). */
    private suspend fun persistString(key: String, value: String) {
        writeMutex.withLock {
            when (key) {
                RankService.MAX_RANK_KEY -> {
                    // Max with the stored value: this write was
                    // read-modify-written from the cache, and a
                    // failed preload leaves the cache reading -1 —
                    // a plain overwrite could demote a stored rank.
                    val stored = store.getString(PrefsStore.MAX_RANK, "-1")
                        .toIntOrNull() ?: -1
                    val merged = maxOf(stored, value.toIntOrNull() ?: -1)
                    store.setString(PrefsStore.MAX_RANK, merged.toString())
                    cache[key] = merged.toString()
                }
                RankService.SECRET_KEY -> {
                    // Union with the stored value: secrets are
                    // append-only, and this write was
                    // read-modify-written from the cache — a failed
                    // preload leaves the cache reading "" and a plain
                    // overwrite would wipe the stored secrets.
                    val merged = unionSecretCsv(
                        store.getString(PrefsStore.SECRET_THEMES, ""), value
                    )
                    store.setString(PrefsStore.SECRET_THEMES, merged)
                    cache[key] = merged
                }
                XpStore.TOTAL_XP_KEY -> store.setString(PrefsStore.TOTAL_XP, value)
                XpStore.LEDGER_KEY -> store.setString(PrefsStore.XP_LEDGER, value)
                XpStore.PENDING_KEY -> store.setString(PrefsStore.XP_PENDING, value)
                XpStore.MIGRATED_KEY -> store.setString(PrefsStore.XP_MIGRATED, value)
                XpStore.CELEBRATED_XP_KEY -> store.setString(PrefsStore.XP_CELEBRATED, value)
                XpStore.REKEYED_KEY -> store.setString(PrefsStore.XP_REKEYED, value)
                XpStore.RECOMPUTED_KEY -> store.setString(PrefsStore.XP_RECOMPUTED, value)
            }
        }
    }

    /**
     * Union of two secret-theme CSVs (stored order first, then any new
     * ids in incoming order). Secrets are append-only — there is no
     * removal path — so a union can never lose an unlock.
     */
    private fun unionSecretCsv(stored: String, incoming: String): String {
        val out = ArrayList<String>()
        for (part in stored.split(',') + incoming.split(',')) {
            val id = part.trim()
            if (id.isNotEmpty() && id !in out) out += id
        }
        return out.joinToString(",")
    }

    /**
     * Persists an XP credit (ledger + total) in ONE DataStore
     * transaction. Two separate setString calls could persist only half
     * of a credit if the process died between them, leaving the ledger
     * saying "credited" while the total missed the XP — unrecoverable,
     * because the unit can never be credited again.
     */
    override fun setXpState(ledgerValue: String, totalValue: String) {
        cache[XpStore.LEDGER_KEY] = ledgerValue
        cache[XpStore.TOTAL_XP_KEY] = totalValue
        scope.launch {
            writeMutex.withLock {
                // Explicit Pair: the `to` infix here would otherwise resolve
                // to DataStore's Key.to(value) (a Preferences.Pair), not a
                // kotlin.Pair, and mapOf would reject it.
                store.setStrings(
                    mapOf(
                        kotlin.Pair(PrefsStore.XP_LEDGER, ledgerValue),
                        kotlin.Pair(PrefsStore.TOTAL_XP, totalValue)
                    )
                )
            }
        }
    }
}
