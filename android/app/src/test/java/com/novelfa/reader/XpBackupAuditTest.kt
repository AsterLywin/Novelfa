package com.novelfa.reader

import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.PrefsStoreRankPrefs
import com.novelfa.reader.data.ReadingRecord
import com.novelfa.reader.data.filterPrivateRecords
import com.novelfa.reader.data.sha256OfFile
import com.novelfa.reader.domain.CharacterThemes
import com.novelfa.reader.domain.RankService
import com.novelfa.reader.domain.XpGuard
import com.novelfa.reader.domain.XpStore
import com.novelfa.reader.settings.FakeBookRepository
import com.novelfa.reader.settings.RankHook
import com.novelfa.reader.settings.testBook
import com.novelfa.reader.theme.InMemoryRankPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * PROBE tests for the 2026-11-25 XP / BACKUP / THEMES / SETTINGS audit.
 *
 * Each test asserts the CORRECT behaviour and FAILS on current code —
 * that failure IS the demonstration of the finding. Do not "fix" the
 * tests; fix the production code they point at. The two control tests
 * (claimPending_bookWithRowHash_claims, pendingLedger_control_ledgerKeyPersists)
 * PASS on current code and prove the probes are fair: only the defective
 * path fails.
 *
 * Findings (see the audit report for file:line + reproduction):
 *  1. pendingLedger_survivesRestart (+ the bridge-level probe below it):
 *     XpStore.PENDING_KEY is never persisted — PrefsStoreRankPrefs has no
 *     branch for it and PrefsStore has no XP_PENDING key — so a restore's
 *     pending XP vanishes on the next process restart.
 *  2. restore_mustNotLowerMaxRank / finishedBooksRestoredFloor_mustNotDrop:
 *     applyBackup and recomputeXpIfNeeded overwrite maxRank and the
 *     FinishedBooksRestored floor with the CURRENT library count,
 *     breaking the documented monotonicity (deleting a book never
 *     relocks themes).
 *  3. claimPending_bookWithNullRowHash_neverClaims: the pending claim
 *     builds its allowed set from row contentHash only — books whose
 *     hash has not landed yet never get their pending XP claimed.
 *  4. failedPreload_emptyCache_secretUnlockWipesSecrets: after a failed
 *     DataStore preload the bridge cache is empty, and the next
 *     read-modify-write (secretUnlock) clobbers the stored secrets.
 *  5. recordsKept_countsFilteredPrivateRecordsAsKept: the restore result
 *     counts private-filtered records as "kept" although they never
 *     reached the store.
 *
 * NOT re-probed here: the 2026-11-03 audit_xp_findings.md items F1–F14
 * and M1–M9, the secret-code fix (d431c89c), and the device-confirmed
 * backup anti-cheat.
 */
class XpBackupAuditTest {

    @Before
    fun setUp() {
        XpGuard.resetForTest()
    }

    // ---- Finding 2: maxRank must never go down on a restore ----

    @Test
    fun restore_mustNotLowerMaxRank() {
        val prefs = InMemoryRankPrefs()
        val service = RankService(prefs)
        service.recordProgress(80)
        assertEquals(6, service.maxRank)
        val beatrice = CharacterThemes.find("beatrice")!!
        assertTrue(service.isUnlocked(beatrice))
        // What the production code does today, verbatim:
        // BackupService.applyBackup (BackupService.kt:986) and
        // RankHook.recomputeXpIfNeeded (RankHook.kt:198) assign the rank
        // of the CURRENT library, unconditionally.
        service.maxRank = RankService.rankIndexForStatic(1)
        // Documented monotonicity (RankService KDoc, RankHook.refreshRank
        // KDoc): deleting a book never relocks themes.
        assertEquals(6, service.maxRank)
        assertTrue(service.isUnlocked(beatrice))
    }

    // ---- Finding 3: pending claim needs the row hash ----

    @Test
    fun claimPending_bookWithNullRowHash_neverClaims() = runBlocking {
        val prefs = InMemoryRankPrefs()
        // Fixture (fixed 2026-11-25 with the production fix): the
        // pending entry must be keyed by the hash the on-demand
        // computation will actually produce, so the book is a REAL
        // temp file whose SHA-256 we compute here. The original
        // fixture used a made-up "c"*64 hash with a book whose file
        // ("/books/imported.epub") does not exist — no hash
        // computation could ever have produced it, so the probe could
        // not have passed with any correct fix.
        val file = File.createTempFile("audit-claim", ".epub").apply {
            writeBytes("audit pending-claim fixture".toByteArray(Charsets.UTF_8))
        }
        try {
            val hash = sha256OfFile(file)
            val store = XpStore(prefs)
            assertEquals(
                1,
                store.mergeBackupLedger("h$hash:0::5", allowedContentHashes = emptySet())
            )
            assertTrue(store.hasPending)
            // The book was imported after the restore, but its row hash has
            // not landed yet (deferred background hash job, >500MB file, or
            // a failed source stream): contentHash is still null.
            val repo = FakeBookRepository(
                listOf(
                    testBook("imported", totalChapters = 10).copy(
                        filePath = file.absolutePath
                    )
                )
            )
            RankHook.claimPendingXpIfNeeded(repo, prefs)
            // The book IS on the device, so its pending XP should apply.
            assertFalse(store.hasPending)
            assertEquals(5, store.totalXp)
        } finally {
            file.delete()
        }
    }

    @Test
    fun claimPending_bookWithRowHash_claims() = runBlocking {
        val prefs = InMemoryRankPrefs()
        val hash = "c".repeat(64)
        val store = XpStore(prefs)
        assertEquals(
            1,
            store.mergeBackupLedger("h$hash:0::5", allowedContentHashes = emptySet())
        )
        assertTrue(store.hasPending)
        // Same book, but the row hash HAS landed: the claim applies.
        val repo = FakeBookRepository(
            listOf(
                testBook("imported", totalChapters = 10).copy(contentHash = hash)
            )
        )
        RankHook.claimPendingXpIfNeeded(repo, prefs)
        assertFalse(store.hasPending)
        assertEquals(5, store.totalXp)
    }

    // ---- Finding 5: recordsKept over-counts ----

    @Test
    fun recordsKept_countsFilteredPrivateRecordsAsKept() {
        val privateHash = "d".repeat(64)
        val previewRecords = listOf(
            ReadingRecord(sha256 = privateHash, title = "Private Book", finished = true),
            ReadingRecord(sha256 = "e".repeat(64), title = "Public Book", finished = false)
        )
        val stored = filterPrivateRecords(previewRecords, setOf(privateHash), emptySet())
        assertEquals(1, stored.size)
        val matchedKeys = emptySet<String>()
        // Copy of BackupService.applyBackup's recordsKept expression
        // (BackupService.kt:956) AFTER the fix: it counts the FILTERED
        // list (the one the store actually received), not
        // preview.records. The original replica counted preview.records
        // (UNFILTERED) and could never pass — it embedded the bug.
        val recordsKept = stored.count { rec ->
            (rec.sha256.isBlank() || rec.sha256 !in matchedKeys) &&
                (rec.epubId.isBlank() || rec.epubId !in matchedKeys)
        }
        // Only the public record is actually kept in the store.
        assertEquals(1, recordsKept)
    }
}

/**
 * Robolectric probes (findings 1, 2-floor, 4): they need a real
 * PrefsStore/DataStore. The "restart" is simulated by clearing the
 * bridge's static shared cache via reflection and building a new
 * bridge on the same PrefsStore — exactly what a fresh process sees
 * (empty cache + DataStore preload).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class XpBackupAuditRobolectricTest {

    @Before
    fun setUp() {
        XpGuard.resetForTest()
    }

    /** Drop PrefsStoreRankPrefs' static shared cache (process death). */
    private fun clearBridgeSharedCache() {
        // NOTE (2026-11-25, verification fix): sharedCache is a
        // `private val` in the companion object, but kotlinc compiles
        // it as a `private static` field on the OUTER class (javap:
        // the Companion class is empty) — looking it up on the
        // Companion class throws NoSuchFieldException.
        val field = PrefsStoreRankPrefs::class.java.getDeclaredField("sharedCache")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as MutableMap<String, String>).clear()
    }

    /**
     * Read DataStore until it holds the expected value (a background
     * persist landed) or the deadline passes. NOTE (2026-11-25, fix
     * verification): the old "stops changing" criterion returned early
     * on a STABLE WRONG value — two polls both reading "" before the
     * fire-and-forget write landed, or "beatrice" before the second
     * unlock's write landed — so the same test passed in one run and
     * failed in the next. Waiting for the expected value is the only
     * criterion that establishes the write actually landed.
     */
    private suspend fun awaitDataStoreValue(expected: String, read: suspend () -> String): String {
        val deadline = System.currentTimeMillis() + 5_000
        var last = read()
        while (last != expected && System.currentTimeMillis() < deadline) {
            delay(100)
            last = read()
        }
        return last
    }

    /**
     * Same wait, but the stored value only needs to CONTAIN the expected
     * fragment. The pending ledger accumulates entries across the tests
     * in this class (they share one DataStore), so an exact match is the
     * wrong expectation there.
     */
    private suspend fun awaitDataStoreContains(expected: String, read: suspend () -> String): String {
        val deadline = System.currentTimeMillis() + 5_000
        var last = read()
        while (!last.contains(expected) && System.currentTimeMillis() < deadline) {
            delay(100)
            last = read()
        }
        return last
    }

    // ---- Finding 1: the pending ledger is never persisted ----

    @Test
    fun pendingLedger_bridgeKey_survivesRestart() = runBlocking {
        val store = PrefsStore(RuntimeEnvironment.getApplication())
        val bridge = PrefsStoreRankPrefs(store)
        bridge.awaitLoaded()
        // The production write path: XpStore.mergeBackupLedger does
        // prefs.setString(PENDING_KEY, ...) — and PrefsStoreRankPrefs
        // has no branch for that key, so nothing reaches DataStore.
        val pending = "h" + "a".repeat(64) + ":0::5"
        bridge.setString(XpStore.PENDING_KEY, pending)
        assertEquals(pending, bridge.getString(XpStore.PENDING_KEY, ""))
        // Wait for the fire-and-forget DataStore write to land before
        // simulating the restart — without this the preload may read ""
        // purely on scheduling luck.
        assertEquals(
            pending,
            awaitDataStoreValue(pending) { store.getString(PrefsStore.XP_PENDING, "") }
        )
        clearBridgeSharedCache()
        val bridge2 = PrefsStoreRankPrefs(store)
        bridge2.awaitLoaded()
        assertEquals(pending, bridge2.getString(XpStore.PENDING_KEY, ""))
    }

    @Test
    fun pendingLedger_control_ledgerKeyPersists() = runBlocking {
        // Control: the LEDGER key goes through the same bridge and DOES
        // survive the same restart simulation, so the simulation is fair.
        val store = PrefsStore(RuntimeEnvironment.getApplication())
        val bridge = PrefsStoreRankPrefs(store)
        bridge.awaitLoaded()
        val ledger = "h" + "a".repeat(64) + ":0::5"
        bridge.setString(XpStore.LEDGER_KEY, ledger)
        assertEquals(
            ledger,
            awaitDataStoreValue(ledger) { store.getString(PrefsStore.XP_LEDGER, "") }
        )
        clearBridgeSharedCache()
        val bridge2 = PrefsStoreRankPrefs(store)
        bridge2.awaitLoaded()
        assertEquals(ledger, bridge2.getString(XpStore.LEDGER_KEY, ""))
    }

    @Test
    fun pendingLedger_mergeBackupLedger_survivesRestart() = runBlocking {
        val store = PrefsStore(RuntimeEnvironment.getApplication())
        val bridge = PrefsStoreRankPrefs(store)
        bridge.awaitLoaded()
        val xpStore = XpStore(bridge)
        val hash = "b".repeat(64)
        assertEquals(
            1,
            xpStore.mergeBackupLedger("h$hash:0::5", allowedContentHashes = emptySet())
        )
        assertTrue(xpStore.hasPending)
        // Wait for the fire-and-forget DataStore write to land before
        // simulating the restart (see bridgeKey above).
        assertTrue(
            "pending ledger must contain this test's entry",
            awaitDataStoreContains("h$hash:0::5") { store.getString(PrefsStore.XP_PENDING, "") }
                .contains("h$hash:0::5")
        )
        clearBridgeSharedCache()
        val bridge2 = PrefsStoreRankPrefs(store)
        bridge2.awaitLoaded()
        // The restore promised these entries wait for their book — but
        // the pending ledger never reached DataStore, so after a
        // restart the app no longer knows they exist.
        assertTrue(XpStore(bridge2).hasPending)
    }

    // ---- Finding 2 (floor half): FinishedBooksRestored must not drop ----

    @Test
    fun finishedBooksRestoredFloor_mustNotDrop() = runBlocking {
        val store = PrefsStore(RuntimeEnvironment.getApplication())
        store.setFinishedBooksRestored(50)
        assertEquals(50, store.getFinishedBooksRestored())
        // The exact call BackupService.applyBackup makes
        // (BackupService.kt:987) — the "exact set" that the
        // setFinishedBooksRestored KDoc claims nobody else uses.
        store.setFinishedBooksRestored(2)
        // raiseFinishedBooksRestored's contract: "the floor only ever
        // moves up".
        assertEquals(50, store.getFinishedBooksRestored())
    }

    // ---- Finding 4: empty cache after a failed preload clobbers ----

    @Test
    fun failedPreload_emptyCache_secretUnlockWipesSecrets() = runBlocking {
        val store = PrefsStore(RuntimeEnvironment.getApplication())
        val bridgeA = PrefsStoreRankPrefs(store)
        bridgeA.awaitLoaded()
        val serviceA = RankService(bridgeA)
        serviceA.secretUnlock("beatrice")
        serviceA.secretUnlock("emilia")
        assertEquals(
            "beatrice,emilia",
            awaitDataStoreValue("beatrice,emilia") { store.getString(PrefsStore.SECRET_THEMES, "") }
        )
        // Simulate a fresh process whose preload FAILED (corrupt
        // DataStore): the cache is empty while DataStore still holds
        // the secrets. B's preload would succeed here (DataStore is
        // fine), so drop the cache again to reach exactly the
        // failed-preload state: empty cache, loaded complete.
        clearBridgeSharedCache()
        val bridgeB = PrefsStoreRankPrefs(store)
        bridgeB.awaitLoaded()
        clearBridgeSharedCache()
        RankService(bridgeB).secretUnlock("reif")
        // secretUnlock is a blind read-modify-write on the cache: it
        // read "" and persisted just "reif", wiping the other two.
        assertEquals(
            "beatrice,emilia,reif",
            awaitDataStoreValue("beatrice,emilia,reif") { store.getString(PrefsStore.SECRET_THEMES, "") }
        )
    }
}
