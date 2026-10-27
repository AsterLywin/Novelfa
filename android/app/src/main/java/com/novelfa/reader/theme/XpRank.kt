package com.novelfa.reader.theme

import kotlin.math.max

/**
 * XP rank system (replaces the finished-books-only rank for progression).
 *
 * Rules (see ~/workspace/your_files/Novelfa-XP-Rank-System.md for the full
 * spec, in Persian):
 * - Unit = one EPUB spine item (a novel chapter, or a section/chapter of a
 *   light novel / digital book). XP is by TEXT VOLUME, not by raw unit:
 *   xp = max(1, (words + 1000) / 2000), so a ~2000-word chapter = 1 XP.
 * - 12 titles x 5 stars. Entering a title = 1 star, each further 20% of the
 *   way to the next title = +1 star (so a title shows 1..5 stars).
 * - After the last title (27,000 XP) every further 10,000 XP = 1 eternity
 *   mark. Marks never end; only their count grows. The first 10 marks have
 *   distinct names/designs, the 10th being the most beautiful.
 * - Book-completion bonus: max(10, bookXp / 10), only when the book is
 *   finished AND >= 90% of its units have earned XP.
 * - Anti-cheat lives in [XpSessionTracker] (visited-only, >= 5 s dwell,
 *   once per unit per book) and [XpLedger] (no double credit, private
 *   books excluded by the caller).
 *
 * This file is pure JVM (no Android imports) so the engine stays unit-
 * testable; persistence goes through the existing [RankPrefs] string store.
 */

data class XpTitle(val index: Int, val nameFa: String, val nameEn: String, val minXp: Int) {
    fun displayName(isEnglish: Boolean): String = if (isEnglish) nameEn else nameFa
}

data class EternityMark(val index: Int, val nameFa: String, val nameEn: String) {
    fun displayName(isEnglish: Boolean): String = if (isEnglish) nameEn else nameFa
}

/** One readable level: title + stars, or (past the cap) eternity marks. */
data class XpLevel(
    val xp: Int,
    val titleIndex: Int,
    val title: XpTitle,
    /** 1..5 inside a title; always 5 once past the cap. */
    val stars: Int,
    /** 0 before the first mark (27,000..36,999 XP), then 1, 2, … unbounded. */
    val eternityMarks: Int,
    /** XP still needed for the next title (or next mark past the cap). */
    val xpToNext: Int,
    /** 0.0..1.0 progress inside the current title (or towards the next mark). */
    val progress: Double
) {
    val mark: EternityMark? get() = if (eternityMarks >= 1) {
        XpRank.eternityMarkFor(eternityMarks)
    } else {
        null
    }

    /**
     * Continuous star fill for the drawn StarRow (0..5 across the row):
     * progress inside the current title, so 12% in fills ~0.6 of a star
     * instead of one whole glyph star. Past the cap the title is
     * complete, so 5. [displayName] keeps the whole-glyph text form
     * (used in sentences and unit tests); the drawn UI uses this.
     */
    val starFill: Float
        get() = if (eternityMarks >= 1) 5f else (progress * 5.0).toFloat().coerceIn(0f, 5f)

    /** Display like «کتابدار ★★★☆☆» / «بلعنده‌ی جهان‌ها ✦۳». */
    fun displayName(isEnglish: Boolean): String {
        val base = title.displayName(isEnglish)
        return if (eternityMarks >= 1) {
            "$base ✦$eternityMarks"
        } else {
            val filled = "★".repeat(stars)
            val empty = "☆".repeat(max(0, 5 - stars))
            "$base $filled$empty"
        }
    }

    /** Same title, stars and marks => same celebration level. */
    fun celebrationKey(): Triple<Int, Int, Int> = Triple(titleIndex, stars, eternityMarks)
}

enum class CelebrationTier { STAR, TITLE, ETERNITY, ETERNITY_FINAL }

object XpRank {
    const val CAP_XP = 27_000
    const val MARK_STEP_XP = 10_000

    val Titles: List<XpTitle> = listOf(
        XpTitle(0, "تازه‌وارد", "Newcomer", 0),
        XpTitle(1, "رهگذر کتابخانه", "Library Passerby", 25),
        XpTitle(2, "کتاب‌باز", "Book Lover", 100),
        XpTitle(3, "کتاب‌خور", "Bookworm", 300),
        XpTitle(4, "شکارچی داستان", "Story Hunter", 750),
        XpTitle(5, "کتابدار", "Librarian", 1500),
        XpTitle(6, "غرق‌شده در دنیاها", "Lost in Worlds", 3000),
        XpTitle(7, "نگهبان کتابخانه‌ی ممنوعه", "Keeper of the Forbidden Library", 5000),
        XpTitle(8, "افسانه‌ی کتابخانه", "Library Legend", 8000),
        XpTitle(9, "دانای پلایادس", "Sage of Pleiades", 12000),
        XpTitle(10, "هیولای پلایادس", "Monster of Pleiades", 18000),
        XpTitle(11, "بلعنده‌ی جهان‌ها", "Devourer of Worlds", 27000)
    )

    /** The 10 designed eternity marks; mark 10 is the most beautiful. */
    val EternityMarks: List<EternityMark> = listOf(
        EternityMark(1, "اخگر ابدیت", "Ember of Eternity"),
        EternityMark(2, "شبنم ستاره‌ای", "Stardew"),
        EternityMark(3, "برگ زرین", "Golden Leaf"),
        EternityMark(4, "هلال نقره‌ای", "Silver Crescent"),
        EternityMark(5, "خورشید کاغذی", "Paper Sun"),
        EternityMark(6, "تاج کهکشانی", "Galactic Crown"),
        EternityMark(7, "بالِ نگهبان کتاب", "Keeper’s Wing"),
        EternityMark(8, "قلب پلایادس", "Heart of Pleiades"),
        EternityMark(9, "تخت ابدیت", "Throne of Eternity"),
        EternityMark(10, "خورشید ابدیتِ جهان‌ها", "Eternal Sun of Worlds")
    )

    /** Mark design for a count: 1..10 by index, 11+ reuse the 10th design. */
    fun eternityMarkFor(count: Int): EternityMark =
        EternityMarks[minOf(max(1, count), EternityMarks.size) - 1]

    fun levelFor(rawXp: Int): XpLevel {
        val xp = max(0, rawXp)
        if (xp < CAP_XP) {
            var title = Titles[0]
            var nextMin = CAP_XP
            for (i in Titles.indices) {
                if (xp >= Titles[i].minXp) {
                    title = Titles[i]
                    nextMin = if (i + 1 < Titles.size) Titles[i + 1].minXp else CAP_XP
                } else {
                    break
                }
            }
            val span = max(1, nextMin - title.minXp)
            val fraction = (xp - title.minXp).toDouble() / span.toDouble()
            val stars = minOf(5, 1 + (fraction * 5.0).toInt())
            return XpLevel(
                xp = xp,
                titleIndex = title.index,
                title = title,
                stars = stars,
                eternityMarks = 0,
                xpToNext = nextMin - xp,
                progress = fraction.coerceIn(0.0, 1.0)
            )
        }
        val over = xp - CAP_XP
        val marks = over / MARK_STEP_XP
        val intoMark = over % MARK_STEP_XP
        return XpLevel(
            xp = xp,
            titleIndex = 11,
            title = Titles[11],
            stars = 5,
            eternityMarks = marks,
            xpToNext = MARK_STEP_XP - intoMark,
            progress = intoMark.toDouble() / MARK_STEP_XP.toDouble()
        )
    }

    /** XP for one unit from its word count. */
    fun xpForUnit(words: Int): Int = max(1, (max(0, words) + 1000) / 2000)

    /** Book-completion bonus from the book's own credited XP total. */
    fun bookBonus(bookXp: Int): Int = max(10, bookXp / 10)

    /** Fair old-rank -> minimum new XP (name-for-name; see spec §9). */
    fun oldRankToMinXp(oldRank: Int): Int = when (oldRank) {
        0 -> 0
        1 -> 100
        2 -> 300
        3 -> 1500
        4 -> 8000
        5 -> 12000
        6 -> 18000
        else -> 0
    }

    fun celebrationTier(from: XpLevel, to: XpLevel): CelebrationTier = when {
        to.eternityMarks > from.eternityMarks ->
            if (to.eternityMarks >= 10) CelebrationTier.ETERNITY_FINAL
            else CelebrationTier.ETERNITY
        to.titleIndex > from.titleIndex -> CelebrationTier.TITLE
        else -> CelebrationTier.STAR
    }

    private fun isCjk(c: Char): Boolean =
        c in '\u3400'..'\u4DBF' || c in '\u4E00'..'\u9FFF' ||
            c in '\u3040'..'\u30FF' || c in '\uAC00'..'\uD7AF'

    /** Word count of plain text (CJK chars count one each, see spec §2). */
    fun countWords(plainText: String): Int {
        var count = 0
        var inWord = false
        for (c in plainText) {
            when {
                isCjk(c) -> {
                    count++
                    inWord = false
                }
                c.isWhitespace() -> inWord = false
                !inWord -> {
                    count++
                    inWord = true
                }
            }
        }
        return count
    }

    /** Strip tags/scripts/styles and decode the common entities. */
    fun plainTextFromHtml(html: String): String {
        var out = html
        out = Regex("<script[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE).replace(out, " ")
        out = Regex("<style[\\s\\S]*?</style\\s*>", RegexOption.IGNORE_CASE).replace(out, " ")
        out = Regex("<[^>]+>").replace(out, " ")
        out = out.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        return out
    }

    fun wordsFromHtml(html: String): Int = countWords(plainTextFromHtml(html))
}

/**
 * Per-book ledger of XP-credited units. Sequential reading collapses to a
 * single [upTo] int (units 0..upTo credited); jumped-to units live in
 * [extras]. [bookXp] is the sum of XP credited for that book (bonus base).
 * Format (device-local, book ids are UUIDs, so no separator collisions):
 * `id:upTo:extraCsv:bookXp;id:…|bonusId,bonusId` — lenient parse, bad
 * entries are skipped rather than failing the whole ledger.
 */
class XpLedger {
    private data class BookLedger(var upTo: Int = -1, val extras: MutableSet<Int> = mutableSetOf(), var bookXp: Int = 0)

    private val books = mutableMapOf<String, BookLedger>()
    private val bonusAwarded = mutableSetOf<String>()

    fun isCredited(bookId: String, unit: Int): Boolean {
        val b = books[bookId] ?: return false
        return unit <= b.upTo || unit in b.extras
    }

    fun creditedCount(bookId: String): Int {
        val b = books[bookId] ?: return 0
        val base = if (b.upTo >= 0) b.upTo + 1 else 0
        return base + b.extras.count { it > b.upTo }
    }

    fun bookXp(bookId: String): Int = books[bookId]?.bookXp ?: 0

    fun isBonusAwarded(bookId: String): Boolean = bookId in bonusAwarded

    fun markBonusAwarded(bookId: String) {
        bonusAwarded += bookId
    }

    /** Returns false when the unit was already credited. */
    fun markCredited(bookId: String, unit: Int, xp: Int): Boolean {
        if (isCredited(bookId, unit)) return false
        val b = books.getOrPut(bookId) { BookLedger() }
        if (unit == b.upTo + 1) {
            b.upTo = unit
            while (b.upTo + 1 in b.extras) {
                b.extras.remove(b.upTo + 1)
                b.upTo++
            }
        } else {
            b.extras += unit
        }
        b.bookXp += xp
        return true
    }

    /** Legacy migration: units 0..unitsRead-1 credited, 1 XP each. */
    fun setLegacy(bookId: String, unitsRead: Int, bookXpValue: Int, bonus: Boolean) {
        if (unitsRead <= 0) return
        val b = books.getOrPut(bookId) { BookLedger() }
        if (unitsRead - 1 > b.upTo) b.upTo = unitsRead - 1
        b.extras.removeAll { it <= b.upTo }
        if (bookXpValue > b.bookXp) b.bookXp = bookXpValue
        if (bonus) bonusAwarded += bookId
    }

    fun serialize(): String {
        val booksPart = books.entries.sortedBy { it.key }.joinToString(";") { (id, b) ->
            val extras = b.extras.sorted().joinToString(",")
            "$id:${b.upTo}:$extras:${b.bookXp}"
        }
        return if (bonusAwarded.isEmpty()) booksPart
        else booksPart + "|" + bonusAwarded.sorted().joinToString(",")
    }

    companion object {
        fun parse(raw: String): XpLedger {
            val out = XpLedger()
            if (raw.isBlank()) return out
            val parts = raw.split("|", limit = 2)
            val booksPart = parts[0]
            if (booksPart.isNotBlank()) {
                for (entry in booksPart.split(";")) {
                    if (entry.isBlank()) continue
                    val fields = entry.split(":")
                    if (fields.size != 4) continue
                    val id = fields[0]
                    if (id.isBlank()) continue
                    val upTo = fields[1].toIntOrNull() ?: continue
                    val extras = fields[2].takeIf { it.isNotBlank() }
                        ?.split(",")?.mapNotNull { it.toIntOrNull() }
                        ?.filter { it >= 0 }?.toMutableSet() ?: mutableSetOf()
                    val bookXp = fields[3].toIntOrNull() ?: continue
                    if (upTo < -1 || upTo > 1_000_000 || bookXp < 0) continue
                    val b = BookLedger(upTo.coerceIn(-1, 1_000_000), extras, bookXp)
                    b.extras.removeAll { it <= b.upTo }
                    out.books[id] = b
                }
            }
            if (parts.size > 1) {
                for (id in parts[1].split(",")) {
                    if (id.isNotBlank()) out.bonusAwarded += id
                }
            }
            return out
        }
    }
}

/**
 * In-session anti-cheat tracker: a unit only becomes creditable when the
 * reader LEAVES it (to another unit, or on reader close) after at least
 * [MIN_DWELL_MS] inside it. Fast flings and drawer jumps therefore earn
 * nothing for the units they skip through; [enter] returns the previous
 * unit only when its dwell qualified, otherwise it is silently dropped.
 */
class XpSessionTracker(private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val MIN_DWELL_MS = 5_000L
    }

    private var current: Int? = null
    private var enteredMs: Long = 0L

    /** Returns the previous unit to credit (or null) when moving to [unit]. */
    fun enter(unit: Int): Int? {
        val now = clock()
        if (unit == current) return null
        val prev = current
        val prevEntered = enteredMs
        current = unit
        enteredMs = now
        if (prev == null) return null
        return if (now - prevEntered >= MIN_DWELL_MS) prev else null
    }

    /** Credit the current unit on reader close, if its dwell qualified. */
    fun flush(): Int? {
        val now = clock()
        val cur = current ?: return null
        current = null
        return if (now - enteredMs >= MIN_DWELL_MS) cur else null
    }
}

/** Result of crediting one unit (null = no XP: private, duplicate, invalid). */
data class XpCreditResult(
    val unitXp: Int,
    val bonusXp: Int,
    val totalXp: Int,
    val creditedUnits: Int
)

/**
 * Persistence for total XP + ledger + celebration state, on the existing
 * sync [RankPrefs] store (same cache pattern as MaxRank/SecretThemes).
 */
class XpStore(private val prefs: RankPrefs) {
    companion object {
        const val TOTAL_XP_KEY = "TotalXp"
        const val LEDGER_KEY = "XpLedger"
        const val MIGRATED_KEY = "XpMigrated"
        const val CELEBRATED_XP_KEY = "XpCelebratedXp"
    }

    var totalXp: Int
        get() = prefs.getString(TOTAL_XP_KEY, "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
        set(value) {
            prefs.setString(TOTAL_XP_KEY, max(0, value).toString())
        }

    var ledger: XpLedger
        get() = XpLedger.parse(prefs.getString(LEDGER_KEY, ""))
        set(value) {
            prefs.setString(LEDGER_KEY, value.serialize())
        }

    var migrated: Boolean
        get() = prefs.getString(MIGRATED_KEY, "0") == "1"
        set(value) {
            prefs.setString(MIGRATED_KEY, if (value) "1" else "0")
        }

    /** XP at the last celebration shown on the home screen (see spec §6). */
    var celebratedXp: Int
        get() = prefs.getString(CELEBRATED_XP_KEY, "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
        set(value) {
            prefs.setString(CELEBRATED_XP_KEY, max(0, value).toString())
        }

    /**
     * Credit one unit. The book bonus is awarded the first time the book
     * counts as finished — last unit credited AND >= 90% of units
     * credited — whichever credit reaches that state (so a last unit read
     * early, before 90%, does not lose the bonus forever: the credit that
     * later pushes the book past 90% awards it).
     */
    fun creditUnit(
        bookId: String,
        unit: Int,
        words: Int,
        totalUnits: Int,
        isPrivate: Boolean
    ): XpCreditResult? {
        if (isPrivate || unit < 0 || totalUnits <= 0) return null
        val led = ledger
        if (led.isCredited(bookId, unit)) return null
        val unitXp = XpRank.xpForUnit(words)
        if (!led.markCredited(bookId, unit, unitXp)) return null
        var bonus = 0
        val credited = led.creditedCount(bookId)
        val finished = led.isCredited(bookId, totalUnits - 1)
        if (finished && !led.isBonusAwarded(bookId) && credited * 10 >= totalUnits * 9) {
            bonus = XpRank.bookBonus(led.bookXp(bookId))
            led.markBonusAwarded(bookId)
        }
        val newTotal = totalXp + unitXp + bonus
        // Atomic pair write on the production bridge (single DataStore
        // transaction); other RankPrefs implementations keep the two
        // sequential writes they already had.
        if (prefs is PrefsStoreRankPrefs) {
            prefs.setXpState(led.serialize(), newTotal.toString())
        } else {
            ledger = led
            totalXp = newTotal
        }
        return XpCreditResult(unitXp, bonus, newTotal, credited)
    }
}
