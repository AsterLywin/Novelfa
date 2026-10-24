package com.novelfa.reader.theme

/** In-memory RankPrefs for unit tests (test-only helper). */
class InMemoryRankPrefs : RankPrefs {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String, default: String): String = map[key] ?: default
    override fun setString(key: String, value: String) {
        map[key] = value
    }
}
