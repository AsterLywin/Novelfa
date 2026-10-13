package com.novelfa.reader.theme

import com.novelfa.reader.data.PrefsStore
import kotlinx.coroutines.runBlocking

/**
 * Production RankPrefs backed by Task 3 PrefsStore (DataStore).
 * DataStore is suspend-based; this bridge uses runBlocking so the
 * pure-JVM RankService API stays sync. Only called from UI threads
 * on tiny preference reads/writes.
 */
class PrefsStoreRankPrefs(private val store: PrefsStore) : RankPrefs {
    override fun getString(key: String, default: String): String = runBlocking {
        when (key) {
            RankService.MAX_RANK_KEY -> store.getString(PrefsStore.MAX_RANK, default)
            RankService.SECRET_KEY -> store.getString(PrefsStore.SECRET_THEMES, default)
            else -> default
        }
    }

    override fun setString(key: String, value: String) {
        runBlocking {
            when (key) {
                RankService.MAX_RANK_KEY -> store.setString(PrefsStore.MAX_RANK, value)
                RankService.SECRET_KEY -> store.setString(PrefsStore.SECRET_THEMES, value)
            }
        }
    }
}
