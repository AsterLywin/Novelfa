package com.novelfa.reader.theme

/**
 * Reader rank from finished (non-private) books, ported from
 * EpubReader/Services/RankService.cs.
 *
 * Logic is copied EXACT: rankIndexFor desc loop, maxRank default -1
 * (prefs "MaxRank"), isRankUnlocked(req) = req <= max(0, maxRank),
 * secrets CSV in prefs "SecretThemes", isUnlocked = rank || secret,
 * recordProgress monotonic (never decreases, so deleting a book
 * never relocks themes).
 *
 * Persistence goes through [RankPrefs] (a tiny sync string store) so
 * this class stays pure-JVM and unit-testable. Production wires it to
 * Task 3 PrefsStore via [PrefsStoreRankPrefs] (see PrefsStore.kt
 * additive keys MAX_RANK / SECRET_THEMES).
 */
data class RankInfo(val index: Int, val nameFa: String, val minBooks: Int)

/** Minimal sync string store backing RankService. */
interface RankPrefs {
    fun getString(key: String, default: String): String
    fun setString(key: String, value: String)
}

/** In-memory RankPrefs for tests and previews. */
class InMemoryRankPrefs : RankPrefs {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String, default: String): String = map[key] ?: default
    override fun setString(key: String, value: String) {
        map[key] = value
    }
}

class RankService(private val prefs: RankPrefs) {

    companion object {
        const val MAX_RANK_KEY = "MaxRank"
        const val SECRET_KEY = "SecretThemes"

        val Ranks: List<RankInfo> = listOf(
            RankInfo(0, "تازه‌وارد", 0),
            RankInfo(1, "کتاب‌باز", 1),
            RankInfo(2, "کتاب‌خور", 3),
            RankInfo(3, "کتابدار", 10),
            RankInfo(4, "افسانه‌ی کتابخانه", 25),
            RankInfo(5, "دانای پلایادس", 60),
            RankInfo(6, "هیولای پلایادس", 80)
        )

        /** Desc loop, like RankService.RankIndexFor. */
        fun rankIndexForStatic(done: Int): Int {
            for (i in Ranks.size - 1 downTo 0) {
                if (done >= Ranks[i].minBooks) return i
            }
            return 0
        }

        fun parseMaxRank(raw: String?): Int = raw?.toIntOrNull() ?: -1
    }

    fun rankIndexFor(done: Int): Int = rankIndexForStatic(done)

    /** Highest rank ever recorded. -1 = never recorded (first run). */
    var maxRank: Int
        get() = parseMaxRank(prefs.getString(MAX_RANK_KEY, "-1"))
        set(value) {
            prefs.setString(MAX_RANK_KEY, value.toString())
        }

    fun isRankUnlocked(requiredRank: Int): Boolean =
        requiredRank <= maxOf(0, maxRank)

    /** Theme unlocked by rank or by its own secret. */
    fun isUnlocked(theme: CharacterTheme?): Boolean =
        theme != null && (isRankUnlocked(theme.requiredRank) || isSecretUnlocked(theme.id))

    /** Unlocked by rank only (not by secret). */
    fun isUnlockedByRank(theme: CharacterTheme?): Boolean =
        theme != null && isRankUnlocked(theme.requiredRank)

    fun isSecretUnlocked(themeId: String?): Boolean {
        if (themeId.isNullOrEmpty()) return false
        return prefs.getString(SECRET_KEY, "")
            .split(',')
            .any { it.trim() == themeId }
    }

    fun secretUnlock(themeId: String) {
        if (themeId.isEmpty() || isSecretUnlocked(themeId)) return
        val old = prefs.getString(SECRET_KEY, "")
        prefs.setString(SECRET_KEY, if (old.isEmpty()) themeId else "$old,$themeId")
    }

    fun unlockedCount(): Int = CharacterThemes.All.count { isUnlocked(it) }

    fun visibleCount(): Int = CharacterThemes.visibleCount { isUnlocked(it) }

    /**
     * Record finished-book progress. maxRank only moves up, never down.
     */
    fun recordProgress(completedNonPrivate: Int) {
        val rank = rankIndexFor(completedNonPrivate)
        if (rank > maxRank) maxRank = rank
    }
}
