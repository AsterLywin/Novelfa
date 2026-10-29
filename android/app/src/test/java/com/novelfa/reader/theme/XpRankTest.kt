package com.novelfa.reader.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XpRankTest {

    @Test
    fun titles_thresholds() {
        assertEquals(0, XpRank.levelFor(0).titleIndex)
        assertEquals(1, XpRank.levelFor(0).stars)
        assertEquals(1, XpRank.levelFor(25).titleIndex)
        assertEquals(2, XpRank.levelFor(100).titleIndex)
        assertEquals(6, XpRank.levelFor(3000).titleIndex)
        assertEquals("غرق‌شده در دنیاها", XpRank.levelFor(3000).title.nameFa)
        assertEquals(10, XpRank.levelFor(18000).titleIndex)
        assertEquals(11, XpRank.levelFor(27000).titleIndex)
    }

    @Test
    fun stars_onePerTwentyPercent() {
        // Title 5 (1500..3000): 0% -> 1 star, 40% -> 3, 80% -> 5.
        assertEquals(1, XpRank.levelFor(1500).stars)
        assertEquals(3, XpRank.levelFor(2100).stars)
        assertEquals(5, XpRank.levelFor(2700).stars)
        assertEquals("کتابدار ★★★☆☆", XpRank.levelFor(2100).displayName(false))
    }

    @Test
    fun prestige_marksNeverEnd() {
        assertEquals(0, XpRank.levelFor(27000).eternityMarks)
        assertEquals(0, XpRank.levelFor(36999).eternityMarks)
        assertEquals(1, XpRank.levelFor(37000).eternityMarks)
        assertEquals(2, XpRank.levelFor(47000).eternityMarks)
        assertEquals(10, XpRank.levelFor(127000).eternityMarks)
        assertEquals(11, XpRank.levelFor(137000).eternityMarks)
        // 11+ reuse the 10th design.
        assertEquals("خورشید ابدیتِ جهان‌ها", XpRank.levelFor(137000).mark!!.nameFa)
        assertEquals(5, XpRank.levelFor(37000).stars)
    }

    @Test
    fun xpForUnit_byVolume() {
        assertEquals(1, XpRank.xpForUnit(0))
        assertEquals(1, XpRank.xpForUnit(2000))
        assertEquals(2, XpRank.xpForUnit(3000))
        assertEquals(50, XpRank.xpForUnit(100000))
    }

    @Test
    fun countWords_faEnCjk() {
        assertEquals(2, XpRank.countWords("سلام دنیا"))
        assertEquals(2, XpRank.countWords("hello world"))
        assertEquals(4, XpRank.countWords("你好世界"))
        assertEquals(2, XpRank.wordsFromHtml("<p>سلام <b>دنیا</b></p><script>evil evil</script>"))
    }

    @Test
    fun ledger_sequentialAndJump() {
        val led = XpLedger()
        assertTrue(led.markCredited("b", 0, 1))
        assertTrue(led.markCredited("b", 1, 1))
        assertFalse(led.markCredited("b", 1, 1))
        assertEquals(2, led.creditedCount("b"))
        // Jump to 5, then fill the gap: 2,3,4 absorb into upTo.
        assertTrue(led.markCredited("b", 5, 1))
        assertEquals(3, led.creditedCount("b"))
        led.markCredited("b", 2, 1)
        led.markCredited("b", 3, 1)
        led.markCredited("b", 4, 1)
        assertEquals(6, led.creditedCount("b"))
        assertTrue(led.isCredited("b", 5))
        // Serialize round-trip preserves everything.
        val parsed = XpLedger.parse(led.serialize())
        assertEquals(6, parsed.creditedCount("b"))
        assertTrue(parsed.isCredited("b", 0))
        assertTrue(parsed.isCredited("b", 5))
    }

    @Test
    fun tracker_dwellGate() {
        var now = 0L
        val t = XpSessionTracker { now }
        assertNull(t.enter(0))
        now = 4000
        // Leaving before 5 s: unit 0 earns nothing.
        assertNull(t.enter(1))
        now = 4000 + 5000
        // Unit 1 held exactly 5 s: creditable.
        assertEquals(1, t.enter(2))
        now += 1000
        // Flush before 5 s in unit 2: nothing.
        assertNull(t.flush())
    }

    @Test
    fun store_creditAndBonus() {
        val store = XpStore(InMemoryRankPrefs())
        assertNull(store.creditUnit("b", 0, 100, 10, true))
        // 9 units, last unit not yet credited: no bonus.
        for (u in 0 until 9) {
            assertTrue(store.creditUnit("b", u, 0, 10, false) != null)
        }
        assertEquals(9, store.totalXp)
        // Last unit: 10 units XP + bonus max(10, 10/10) = 10.
        val last = store.creditUnit("b", 9, 0, 10, false)!!
        assertEquals(1, last.unitXp)
        assertEquals(10, last.bonusXp)
        assertEquals(20, last.totalXp)
        // Duplicate unit: nothing, bonus never doubles.
        assertNull(store.creditUnit("b", 9, 0, 10, false))
        assertEquals(20, store.totalXp)
    }

    @Test
    fun oldRankFloor_mapping() {
        assertEquals(0, XpRank.oldRankToMinXp(0))
        assertEquals(100, XpRank.oldRankToMinXp(1))
        assertEquals(1500, XpRank.oldRankToMinXp(3))
        assertEquals(18000, XpRank.oldRankToMinXp(6))
    }

    @Test
    fun contentKey_format() {
        val hash = "a".repeat(64)
        val key = XpLedger.contentKeyFor(hash)
        assertEquals(65, key.length)
        assertTrue(XpLedger.isContentKey(key))
        assertFalse(XpLedger.isContentKey("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse(XpLedger.isContentKey("h" + "g".repeat(64)))
        assertFalse(XpLedger.isContentKey("h" + "a".repeat(63)))
    }

    @Test
    fun ledger_transfer_renameAndBonus() {
        val led = XpLedger()
        led.markCredited("old-uuid", 0, 1)
        led.markCredited("old-uuid", 2, 1)
        led.markBonusAwarded("old-uuid")
        val key = XpLedger.contentKeyFor("b".repeat(64))
        assertTrue(led.transfer("old-uuid", key))
        assertEquals(2, led.creditedCount(key))
        assertTrue(led.isCredited(key, 0))
        assertTrue(led.isCredited(key, 2))
        assertTrue(led.isBonusAwarded(key))
        assertFalse(led.isBonusAwarded("old-uuid"))
        assertEquals(0, led.creditedCount("old-uuid"))
        // Idempotent: a second transfer finds nothing.
        assertFalse(led.transfer("old-uuid", key))
        assertFalse(led.transfer(key, key))
    }

    @Test
    fun ledger_transfer_mergesIntoExisting() {
        val led = XpLedger()
        led.markCredited("old-uuid", 0, 1)
        led.markCredited("old-uuid", 1, 1)
        val key = XpLedger.contentKeyFor("c".repeat(64))
        led.markCredited(key, 2, 1)
        assertTrue(led.transfer("old-uuid", key))
        assertEquals(3, led.creditedCount(key))
        assertTrue(led.isCredited(key, 0))
        assertTrue(led.isCredited(key, 2))
    }

    @Test
    fun ledger_mergeContentFrom_ignoresUuid() {
        val other = XpLedger()
        other.markCredited("some-uuid", 0, 1)
        val key = XpLedger.contentKeyFor("d".repeat(64))
        other.markCredited(key, 5, 1)
        other.markBonusAwarded(key)
        val led = XpLedger()
        led.mergeContentFrom(other)
        assertEquals(0, led.creditedCount("some-uuid"))
        assertEquals(1, led.creditedCount(key))
        assertTrue(led.isCredited(key, 5))
        assertTrue(led.isBonusAwarded(key))
        // Content-only serialization drops the UUID entry entirely.
        val contentOnly = led.serializeContentOnly()
        assertFalse(contentOnly.contains("some-uuid"))
        assertTrue(contentOnly.contains(key))
    }

    @Test
    fun store_resolver_rekeysBeforeFirstCredit() {
        val prefs = InMemoryRankPrefs()
        val store = XpStore(prefs)
        // Old UUID credits (pre-rekeying behaviour).
        store.creditUnit("old-uuid", 0, 0, 10, false)
        store.creditUnit("old-uuid", 1, 0, 10, false)
        assertEquals(2, store.totalXp)
        val resolver = XpContentKeyResolver(store)
        val key = resolver.resolve("old-uuid", "e".repeat(64))
        assertTrue(XpLedger.isContentKey(key))
        // The same key comes back on later calls, hash or no hash.
        assertEquals(key, resolver.resolve("old-uuid", null))
        // Credits now land under the content key, and old units
        // are NOT re-credited (total stays 2 for units 0 and 1).
        assertNull(store.creditUnit(key, 0, 0, 10, false))
        assertNull(store.creditUnit(key, 1, 0, 10, false))
        assertEquals(2, store.totalXp)
        // A new unit credits normally under the content key.
        assertTrue(store.creditUnit(key, 2, 0, 10, false) != null)
        assertEquals(3, store.totalXp)
    }

    @Test
    fun store_mergeContentLedger_neverChangesTotal() {
        val prefs = InMemoryRankPrefs()
        val store = XpStore(prefs)
        store.creditUnit("local-uuid", 0, 0, 10, false)
        val other = XpLedger()
        val key = XpLedger.contentKeyFor("f".repeat(64))
        other.markCredited(key, 3, 1)
        val before = store.totalXp
        store.mergeContentLedger(other.serialize())
        assertEquals(before, store.totalXp)
        assertEquals(1, store.ledger.creditedCount(key))
        // A credit for that unit is now a duplicate, not new XP.
        assertNull(store.creditUnit(key, 3, 0, 10, false))
        assertEquals(before, store.totalXp)
    }
}
