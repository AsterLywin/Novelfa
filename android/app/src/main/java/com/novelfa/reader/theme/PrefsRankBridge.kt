package com.novelfa.reader.theme

import com.novelfa.reader.data.PrefsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val cache = ConcurrentHashMap<String, String>()
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            cache[RankService.MAX_RANK_KEY] = store.getString(PrefsStore.MAX_RANK, "-1")
            cache[RankService.SECRET_KEY] = store.getString(PrefsStore.SECRET_THEMES, "")
            loaded.complete(Unit)
        }
    }

    /**
     * Suspends until the initial DataStore preload has finished. Call this
     * before any read-modify-write (e.g. recordProgress) so a not-yet-loaded
     * cache can't clobber the stored monotonic maxRank with a default.
     */
    suspend fun awaitLoaded() = loaded.await()

    override fun getString(key: String, default: String): String =
        cache[key] ?: default

    override fun setString(key: String, value: String) {
        cache[key] = value
        scope.launch {
            when (key) {
                RankService.MAX_RANK_KEY -> store.setString(PrefsStore.MAX_RANK, value)
                RankService.SECRET_KEY -> store.setString(PrefsStore.SECRET_THEMES, value)
            }
        }
    }
}
