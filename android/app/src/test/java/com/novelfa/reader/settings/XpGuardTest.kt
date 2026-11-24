package com.novelfa.reader.settings

import com.novelfa.reader.domain.XpGuard
import com.novelfa.reader.domain.XpLedger
import com.novelfa.reader.domain.XpRank
import com.novelfa.reader.domain.XpStore
import com.novelfa.reader.theme.InMemoryRankPrefs
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * XP guard + pending ledger (Ali 2026-11-23 port): the guard file
 * (peak / max title / ledger backup), SelfHeal, the 200-XP-per-unit
 * clamp and the pending ledger that waits for absent books.
 */
class XpGuardTest {

    private lateinit var dir: File

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val keyA = XpLedger.contentKeyFor(hashA)
    private val keyB = XpLedger.contentKeyFor(hashB)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("xpguard-test").toFile()
        XpGuard.init(dir)
    }

    @After
    fun tearDown() {
        // The guard is a process-wide singleton: never leak a peak
        // into other test classes sharing this JVM.
        XpGuard.resetForTest()
        dir.deleteRecursively()
    }

    private fun ledgerWith(vararg entries: Pair<String, Int>): XpLedger {
        val led = XpLedger()
        for ((key, units) in entries) {
            for (u in 0 until units) led.markCredited(key, u, 1)
        }
        return led
    }

    @Test
    fun guard_peakOnlyMovesUp() {
        XpGuard.noteTotal(100)
        assertEquals(100, XpGuard.peakValue)
        assertEquals(XpRank.levelFor(100).titleIndex, XpGuard.maxTitleValue)
        XpGuard.noteTotal(50)
        assertEquals(100, XpGuard.peakValue)
    }

    @Test
    fun guard_persistsAcrossInit() {
        XpGuard.noteTotal(140)
        XpGuard.init(dir)
        assertEquals(140, XpGuard.peakValue)
    }

    @Test
    fun guard_fallsBackToBakWhenDatIsCorrupt() {
        // Two changing notes: the first lands in .bak on the second persist.
        XpGuard.noteTotal(100)
        XpGuard.noteTotal(140)
        assertTrue(File(dir, "xpguard.dat.bak").isFile)
        File(dir, "xpguard.dat").writeText("garbage\nno equals here\n")
        XpGuard.init(dir)
        assertEquals(100, XpGuard.peakValue)
    }

    @Test
    fun totalXp_neverDropsBelowPeak() {
        val prefs = InMemoryRankPrefs()
        val store = XpStore(prefs)
        store.totalXp = 100
        store.totalXp = 50
        assertEquals(100, store.totalXp)
        assertEquals("100", prefs.getString(XpStore.TOTAL_XP_KEY, "?"))
    }

    @Test
    fun ledgerSetter_notifiesGuard() {
        val prefs = InMemoryRankPrefs()
        val store = XpStore(prefs)
        val led = ledgerWith(keyA to 3)
        store.ledger = led
        assertEquals(led.serialize(), XpGuard.ledgerBackupValue)
    }

    @Test
    fun selfHeal_restoresDroppedTotal() {
        val prefs = InMemoryRankPrefs()
        val store = XpStore(prefs)
        store.totalXp = 100
        // Simulate a corrupt / rolled-back prefs file: the stored
        // number drops, bypassing the guarded setter.
        prefs.setString(XpStore.TOTAL_XP_KEY, "0")
        store.selfHeal()
        assertEquals(100, store.totalXp)
    }

    @Test
    fun selfHeal_healsWipedLedger() {
        val prefs = InMemoryRankPrefs()
        val store = XpStore(prefs)
        store.ledger = ledgerWith(keyA to 5)
        store.totalXp = 5
        prefs.setString(XpStore.LEDGER_KEY, "")
        prefs.setString(XpStore.TOTAL_XP_KEY, "0")
        store.selfHeal()
        assertEquals(5, store.ledger.derivedTotal())
        assertEquals(5, store.totalXp)
    }

    @Test
    fun mergeBackupLedger_pendingAndClaim() {
        val prefs = InMemoryRankPrefs()
        val store = XpStore(prefs)
        val backup = ledgerWith(keyA to 5, keyB to 3).serialize()
        // Only book A is on this device: B goes pending, nothing lost.
        val pending = store.mergeBackupLedger(backup, setOf(hashA))
        assertEquals(1, pending)
        assertTrue(store.hasPending)
        assertEquals(5, store.ledger.derivedTotal())
        // Book B arrives: its entries apply, total only goes up.
        val gained = store.claimPending(setOf(hashA, hashB))
        assertEquals(3, gained)
        assertFalse(store.hasPending)
        assertEquals(8, store.ledger.derivedTotal())
    }

    @Test
    fun mergeBackupLedger_clampsForgedBookXp() {
        val prefs = InMemoryRankPrefs()
        val store = XpStore(prefs)
        // Hand-made entry: 5 units claiming 5000 XP (cap is 5*200=1000).
        val forged = "$keyA:4::5000"
        store.mergeBackupLedger(forged, setOf(hashA))
        assertEquals(1000, store.ledger.bookXp(keyA))
    }

    @Test
    fun raiseTotalToLedger_onlyGoesUp() {
        val prefs = InMemoryRankPrefs()
        val store = XpStore(prefs)
        store.totalXp = 10
        store.ledger = ledgerWith(keyA to 25)
        assertEquals(25, store.raiseTotalToLedger())
        store.ledger = ledgerWith(keyA to 3)
        assertEquals(25, store.raiseTotalToLedger())
    }
}
